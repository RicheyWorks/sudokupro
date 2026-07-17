// SudokuPro web client. Talks to the same REST + WebSocket API as the desktop
// app. Auth: HTTP Basic on fetches; the WebSocket handshake authenticates via
// the session cookie established by GET /api/session (browsers cannot set
// custom headers on WebSocket connects). CSRF: double-submit token from
// /api/session echoed on mutating requests.
(function () {
  'use strict';

  const $ = (id) => document.getElementById(id);
  let base = '';           // '' = same origin
  let auth = '';           // Basic header value
  let csrfHeader = 'X-XSRF-TOKEN', csrfToken = null;
  let me = null;
  let board = null;        // BoardState
  let gameId = null;
  let socket = null;
  let selected = null;     // [row, col]

  // ---- plumbing -------------------------------------------------------------

  async function api(method, path, body) {
    const headers = { 'Authorization': auth, 'Accept': 'application/json' };
    if (body) headers['Content-Type'] = 'application/json';
    if (method !== 'GET' && csrfToken) headers[csrfHeader] = csrfToken;
    const resp = await fetch(base + path, {
      method, headers, credentials: 'include',
      body: body ? JSON.stringify(body) : undefined
    });
    if (!resp.ok) {
      let detail = 'HTTP ' + resp.status;
      try { detail = (await resp.json()).detail || detail; } catch (e) { /* keep default */ }
      throw new Error(detail);
    }
    const text = await resp.text();
    return text ? JSON.parse(text) : null;
  }

  function status(msg, cls) {
    const el = $('status');
    el.textContent = msg;
    el.className = cls || '';
  }

  function log(msg) {
    const el = $('log');
    el.textContent = msg + '\n' + el.textContent.split('\n').slice(0, 8).join('\n');
  }

  // ---- auth -----------------------------------------------------------------

  $('btnRegister').onclick = async () => {
    base = $('server').value.trim().replace(/\/+$/, '');
    try {
      const resp = await fetch(base + '/api/auth/register', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username: $('user').value.trim(), password: $('pass').value })
      });
      if (resp.status === 201) { status('Account created — now log in!', 'ok'); return; }
      const err = await resp.json();
      status(err.detail || ('HTTP ' + resp.status), 'err');
    } catch (e) { status(e.message, 'err'); }
  };

  $('btnLogin').onclick = async () => {
    base = $('server').value.trim().replace(/\/+$/, '');
    auth = 'Basic ' + btoa($('user').value.trim() + ':' + $('pass').value);
    try {
      const session = await api('GET', '/api/session');
      me = session.playerId;
      csrfHeader = session.csrfHeaderName || csrfHeader;
      csrfToken = session.csrfToken;
      $('login').style.display = 'none';
      $('game').style.display = 'flex';
      $('hudPlayer').textContent = me;
      refreshHud();
      try {
        const rec = await api('GET', '/api/game/recommended-difficulty');
        $('difficulty').value = String(rec.difficulty);
        log('Recommended difficulty: ' + rec.difficulty);
      } catch (e) { /* cosmetic */ }
    } catch (e) { status(e.message, 'err'); }
  };

  // ---- game lifecycle ---------------------------------------------------------

  $('btnNew').onclick = async () => {
    try {
      setBoard(await api('POST', '/api/game/new?difficulty=' + $('difficulty').value + '&chaos=false&mirror=false'));
      log('New game started');
    } catch (e) { log('New game failed: ' + e.message); }
  };

  $('btnDaily').onclick = async () => {
    try {
      const daily = await api('GET', '/api/daily');
      if (daily.completed) { log('Daily already solved — streak ' + daily.streakDays + '. Back at midnight UTC!'); return; }
      setBoard(await api('POST', '/api/daily/join'));
      log('Daily ' + daily.date + ' — streak ' + daily.streakDays);
      $('hudStreak').textContent = daily.streakDays;
    } catch (e) { log('Daily failed: ' + e.message); }
  };

  $('btnHint').onclick = async () => {
    if (!gameId) return;
    try {
      const h = await api('GET', '/api/game/hint?gameId=' + gameId);
      log('Hint: ' + h.hint);
      setBoard(await api('GET', '/api/game/' + gameId)); // hints may mutate server state
      refreshHud();
    } catch (e) { log('Hint: ' + e.message); }
  };

  async function refreshHud() {
    try {
      const wallet = await api('GET', '/api/economy/wallet');
      $('hudGems').textContent = wallet.gems;
      const daily = await api('GET', '/api/daily');
      $('hudStreak').textContent = daily.streakDays;
    } catch (e) { /* cosmetic */ }
  }

  // ---- board rendering --------------------------------------------------------

  function setBoard(state) {
    board = state;
    gameId = state.gameId;
    selected = null;
    $('hudMoves').textContent = state.moveCount;
    render();
    connectSocket();
  }

  function render() {
    const el = $('board');
    el.innerHTML = '';
    board.cells.forEach((row, r) => row.forEach((cell, c) => {
      const d = document.createElement('div');
      d.className = 'cell' + (cell.isGiven ? ' given' : '');
      if (c % 3 === 2 && c !== 8) d.classList.add('b-right');
      if (r % 3 === 2 && r !== 8) d.classList.add('b-bottom');
      if (selected && selected[0] === r && selected[1] === c) d.classList.add('sel');
      d.textContent = cell.value === 0 ? '' : cell.value;
      if (!cell.isGiven) d.onclick = () => { selected = [r, c]; render(); };
      el.appendChild(d);
    }));

    const pad = $('pad');
    pad.innerHTML = '';
    for (let v = 1; v <= 9; v++) {
      const b = document.createElement('button');
      b.textContent = v;
      b.onclick = () => play(v);
      pad.appendChild(b);
    }
    const clear = document.createElement('button');
    clear.textContent = '⌫';
    clear.className = 'secondary';
    clear.onclick = () => play(0);
    pad.appendChild(clear);
  }

  function play(value) {
    if (!selected || !socket || socket.readyState !== 1) return;
    const [r, c] = selected;
    const oldVal = board.cells[r][c].value;
    if (board.cells[r][c].isGiven || oldVal === value) return;
    board.cells[r][c].value = value; // optimistic; server error resyncs
    render();
    socket.send(JSON.stringify({ type: 'move', payload: { row: r, col: c, oldVal, newVal: value } }));
  }

  // ---- websocket ----------------------------------------------------------------

  function connectSocket() {
    if (socket) { try { socket.close(); } catch (e) { /* already closed */ } }
    const origin = base || (location.protocol + '//' + location.host);
    const wsUrl = origin.replace(/^http/, 'ws') + '/ws/game?gameId=' + encodeURIComponent(gameId);
    socket = new WebSocket(wsUrl);
    socket.onmessage = (ev) => {
      let env;
      try { env = JSON.parse(ev.data); } catch (e) { return; }
      switch (env.type) {
        case 'move': {
          const m = env.payload;
          if (env.from !== me && board) { board.cells[m.row][m.col].value = m.newVal; render(); }
          $('hudMoves').textContent = String(Number($('hudMoves').textContent) + 1);
          break;
        }
        case 'board':
          board = env.payload; gameId = board.gameId;
          $('hudMoves').textContent = board.moveCount;
          render();
          if (board.solved) { log('🎉 Solved!'); refreshHud(); }
          break;
        case 'error':
          log('Server: ' + (env.payload && env.payload.detail ? env.payload.detail : 'error'));
          socket.send(JSON.stringify({ type: 'sync', payload: '' }));
          break;
        case 'notification': case 'DAILY': case 'DUEL': case 'ACHIEVEMENT': case 'TOURNAMENT':
        case 'FRIEND': case 'SEASON':
          log(String(env.payload)); refreshHud(); break;
        default: break;
      }
    };
    socket.onclose = () => log('Game channel closed');
  }
})();
