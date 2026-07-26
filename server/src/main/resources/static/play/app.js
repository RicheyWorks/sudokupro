// SudokuPro web client. Talks to the same REST + WebSocket API as the desktop
// app. Auth: HTTP Basic on fetches; the WebSocket handshake authenticates via
// the session cookie established by GET /api/session (browsers cannot set
// custom headers on WebSocket connects). CSRF: double-submit token from
// /api/session echoed on mutating requests.
//
// This client keeps strictly to the existing envelope contract
// ({type, from, payload}); client-to-server: move / sync. Server-to-client:
// move, board, error, chat, join, leave, status, gameEnd, health, and the
// typed notification kinds (DAILY, DUEL, ACHIEVEMENT, ...).
(function () {
  'use strict';

  var $ = function (id) { return document.getElementById(id); };
  var base = '';
  var auth = '';
  var csrfHeader = 'X-XSRF-TOKEN', csrfToken = null;
  var me = null;
  var board = null;          // BoardState
  var gameId = null;
  var socket = null;
  var selected = null;       // [row, col]
  var notesMode = false;
  var notes = {};            // "r,c" -> Set of pencil digits (client-local only)
  var timerId = null, startMs = 0;
  var wasSolved = false;
  var lastWasClear = false;  // last outbound move was an erase (newVal 0)
  var lastMoveCell = null, lastMoveValue = 0;   // for flashing a server rejection
  var pending = [];          // moves made while the socket was down
  var wsWanted = false, wsRetry = 0, wsRetryTimer = null;
  var lastFocus = null;      // element to restore focus to when a modal closes
  var cellEls = null;        // the 81 cell nodes, built once (see buildGrid)

  // ---- plumbing -------------------------------------------------------------

  async function api(method, path, body) {
    var headers = { 'Authorization': auth, 'Accept': 'application/json' };
    if (body) headers['Content-Type'] = 'application/json';
    if (method !== 'GET' && csrfToken) headers[csrfHeader] = csrfToken;
    var resp;
    try {
      resp = await fetch(base + path, {
        method: method, headers: headers, credentials: 'include',
        body: body ? JSON.stringify(body) : undefined
      });
    } catch (e) {
      // "Failed to fetch" is plumbing, not a message for a player.
      var ne = new Error(navigator.onLine ? 'Could not reach the server.'
                                          : 'You appear to be offline.');
      ne.status = 0; throw ne;
    }
    if (!resp.ok) {
      // The old default was the literal string "HTTP 401", which is what users saw on a
      // wrong password. Prefer the server's problem+json detail, else a human sentence.
      var detail = null;
      try { detail = (await resp.json()).detail; } catch (e) { /* no body */ }
      if (!detail) {
        detail = ({
          400: 'That request was rejected.',
          401: 'Your session expired.',
          402: 'Not enough gems.',
          403: 'You do not have access to that.',
          404: 'Not found.',
          409: 'That game is busy — try again in a moment.',
          429: 'Too many requests — wait a moment.',
          500: 'Something went wrong on the server.',
          503: 'The server is busy — try again.'
        })[resp.status] || ('Unexpected error (' + resp.status + ')');
      }
      var err = new Error(detail); err.status = resp.status; throw err;
    }
    var text = await resp.text();
    return text ? JSON.parse(text) : null;
  }

  /** UTF-8-safe Basic-auth encoding; plain btoa() throws above U+00FF. */
  function b64(str) {
    var bytes = new TextEncoder().encode(str), out = '';
    for (var i = 0; i < bytes.length; i++) out += String.fromCharCode(bytes[i]);
    return btoa(out);
  }

  function status(msg, cls) {
    var el = $('status'); el.textContent = msg; el.className = cls || '';
  }

  var toastTimer = null;
  function toast(msg, cls) {
    var t = $('toast');
    t.textContent = msg;
    t.className = 'show' + (cls ? ' ' + cls : '');
    if (toastTimer) clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { t.className = ''; }, 2600);
  }

  function log(msg) {
    var el = $('log');
    var stamp = new Date().toLocaleTimeString();
    el.textContent = '[' + stamp + '] ' + msg + '\n' +
      el.textContent.split('\n').slice(0, 40).join('\n');
  }

  // ---- auth -----------------------------------------------------------------

  $('btnRegister').onclick = guard('btnRegister', async function () {
    base = $('server').value.trim().replace(/\/+$/, '');
    var u = $('user').value.trim();
    if (!u || !$('pass').value) { status('Enter a username and password first.', 'err'); return; }
    try {
      var resp = await fetch(base + '/api/auth/register', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username: u, password: $('pass').value })
      });
      if (resp.status === 201) { status('Account created — press Log in & Play.', 'ok'); return; }
      var err = await resp.json().catch(function () { return {}; });
      status(err.detail || ('Registration failed (HTTP ' + resp.status + ')'), 'err');
    } catch (e) { status(e.message, 'err'); }
  });

  $('loginForm').onsubmit = function (e) { e.preventDefault(); login(); };
  async function login() {
    base = $('server').value.trim().replace(/\/+$/, '');
    // btoa() throws InvalidCharacterError on any character above U+00FF, and this line
    // sat OUTSIDE the try below — so a username with an emoji or CJK character (which
    // registration accepts) killed the handler and the button simply appeared dead.
    try {
      auth = 'Basic ' + b64($('user').value.trim() + ':' + $('pass').value);
    } catch (e) {
      status('Username or password contains unsupported characters.', 'err');
      return;
    }
    status('Connecting…');
    try {
      var session = await api('GET', '/api/session');
      me = session.playerId;
      csrfHeader = session.csrfHeaderName || csrfHeader;
      csrfToken = session.csrfToken;
      $('login').style.display = 'none';
      $('game').style.display = 'flex';
      $('hudPlayer').textContent = me;
      setConnBadge('idle');
      render();          // draw the "press New" placeholder instead of an empty box
      refreshHud();
      try {
        var rec = await api('GET', '/api/game/recommended-difficulty');
        $('difficulty').value = String(rec.difficulty);
        toast('Recommended difficulty: ' + diffName(rec.difficulty));
      } catch (e) { /* cosmetic */ }
    } catch (e) {
      status(e.status === 429 ? 'Too many attempts — wait a minute.' : ('Login failed: ' + e.message), 'err');
    }
  }

  $('btnLogout').onclick = function () {
    wsWanted = false;
    if (wsRetryTimer) clearTimeout(wsRetryTimer);
    if (socket) { try { socket.close(); } catch (e) {} }
    location.reload();
  };

  // There was no navigator.onLine handling anywhere: going offline mid-game produced no
  // message at all while moves silently failed to reach the server.
  window.addEventListener('offline', function () {
    toast('You are offline — moves will be queued.', 'bad');
    setConnBadge('down');
  });
  window.addEventListener('online', function () {
    toast('Back online', 'good');
    if (gameId) connectSocket();
  });

  function diffName(d) { return ['', 'Easy', 'Medium', 'Hard', 'Insane'][d] || ('L' + d); }

  // ---- game lifecycle -------------------------------------------------------

  $('btnNew').onclick = guard('btnNew', async function () {
    try {
      var s = await api('POST', '/api/game/new?difficulty=' + $('difficulty').value + '&chaos=false&mirror=false');
      setBoard(s, true);
      toast('New ' + diffName(Number($('difficulty').value)) + ' game', 'good');
      log('New game started (' + gameId + ')');
    } catch (e) {
      if (e.status === 401) { handleAuthLoss(); return; }
      toast('New game failed: ' + e.message, 'bad');
    }
  });

  $('btnDaily').onclick = guard('btnDaily', async function () {
    try {
      var daily = await api('GET', '/api/daily');
      if (daily.completed) {
        toast('Daily already solved — streak ' + daily.streakDays + ' 🔥');
        $('hudStreak').textContent = daily.streakDays;
        return;
      }
      setBoard(await api('POST', '/api/daily/join'), true);
      $('hudStreak').textContent = daily.streakDays;
      toast('Daily ' + daily.date + ' · streak ' + daily.streakDays, 'good');
      log('Joined daily ' + daily.date);
    } catch (e) {
      if (e.status === 401) { handleAuthLoss(); return; }
      toast('Daily failed: ' + e.message, 'bad');
    }
  });

  $('btnHint').onclick = guard('btnHint', async function () {
    if (!gameId) { toast('Start a game first.'); return; }
    try {
      var h = await api('GET', '/api/game/hint?gameId=' + encodeURIComponent(gameId));
      $('hintline').textContent = '💡 ' + (h.hint || 'No hint available.');
      // Hints may mutate server state (e.g. a revealed cell) — pull authoritative board.
      setBoard(await api('GET', '/api/game/' + encodeURIComponent(gameId)), false);
      refreshHud();
    } catch (e) {
      $('hintline').textContent = '';
      if (e.status === 401) { handleAuthLoss(); return; }
      toast(e.status === 402 ? 'Not enough gems for a hint.' : ('Hint: ' + e.message), 'bad');
    }
  });

  // ---- undo / redo ----------------------------------------------------------
  // Both are WebSocket envelopes with no payload; the server replies with an
  // authoritative 'board' envelope, which the socket handler applies. Undo is
  // also the only reliable way to clear a wrong entry right now, since the
  // server rejects a move whose newVal is 0.

  function sendSimple(type) {
    if (!gameId) { toast('Start a game first.'); return; }
    if (!socket || socket.readyState !== 1) { toast('Reconnecting…'); connectSocket(); return; }
    socket.send(JSON.stringify({ type: type, payload: '' }));
  }
  $('btnUndo').onclick = function () { sendSimple('undo'); };
  $('btnRedo').onclick = function () { sendSimple('redo'); };

  // ---- save / load ----------------------------------------------------------

  $('btnSave').onclick = guard('btnSave', async function () {
    if (!gameId) { toast('Nothing to save yet.'); return; }
    try {
      await api('POST', '/api/game/' + encodeURIComponent(gameId) + '/save');
      toast('Game saved 💾', 'good');
      log('Saved ' + gameId);
    } catch (e) {
      if (e.status === 401) { handleAuthLoss(); return; }
      toast(e.status === 403 ? 'That game is not yours.' : ('Save failed: ' + e.message), 'bad');
    }
  });

  $('btnLoad').onclick = guard('btnLoad', async function () {
    openModal('Saved games', '<div class="empty">Loading…</div>');
    try {
      var list = await api('GET', '/api/game/saved?limit=10');
      if (!list || !list.length) {
        setModalBody('<div class="empty">No saved games yet.<br>Press 💾 Save during a game to keep it.</div>');
        return;
      }
      // Every row used to read exactly "Medium · 57% filled / 0 moves · 0 hints", nine
      // times over — no timestamp, no score, nothing to tell one save from another, and
      // nine buttons all labelled "Resume" for a screen reader. Each cell carries a
      // lastModified epoch, which is the one usable recency signal in the payload.
      var enriched = list.map(function (s) {
        var filled = 0, touched = 0;
        s.cells.forEach(function (row) {
          row.forEach(function (c) {
            if (c.value) filled++;
            if (c.lastModified > touched) touched = c.lastModified;
          });
        });
        return { save: s, filled: filled, touched: touched };
      });
      enriched.sort(function (a, b) { return b.touched - a.touched; });

      var body = document.createElement('div');
      enriched.forEach(function (item) {
        var s = item.save;
        var row = document.createElement('div');
        row.className = 'save-row' + (s.gameId === gameId ? ' current' : '');
        var meta = document.createElement('div');
        meta.className = 'meta';
        var name = document.createElement('b');
        name.textContent = diffName(s.difficulty) + ' · ' +
          Math.round((item.filled / 81) * 100) + '% · ' + (s.score || 0) + ' pts';
        meta.appendChild(name);
        var when = item.touched ? new Date(item.touched).toLocaleString() + ' · ' : '';
        meta.appendChild(document.createTextNode(
          when + s.moveCount + ' moves · ' + s.hintCount + ' hints · ♥' + s.lives));
        var btn = document.createElement('button');
        btn.textContent = s.gameId === gameId ? 'Current' : 'Resume';
        btn.setAttribute('aria-label', 'Resume ' + name.textContent);
        btn.onclick = function () { doResume(s.gameId); };
        row.appendChild(meta); row.appendChild(btn);
        body.appendChild(row);
      });
      setModalNode(body);
    } catch (e) {
      if (e.status === 401) { handleAuthLoss(); return; }
      setModalBody('<div class="empty">' + escapeHtml(e.message) + '</div>');
    }
  });

  async function doResume(id) {
    try {
      var s = await api('POST', '/api/game/' + encodeURIComponent(id) + '/resume');
      closeModal();
      setBoard(s, true);
      toast('Resumed', 'good');
      log('Resumed ' + id);
    } catch (e) {
      toast(e.status === 409 ? 'That game is already finished.' : ('Resume failed: ' + e.message), 'bad');
    }
  }

  // ---- stats / leaderboards --------------------------------------------------
  // All three of these endpoints already existed server-side and were never surfaced.

  $('btnStats').onclick = function () { openStats('global'); };

  async function openStats(tab) {
    openModal('Stats', '');
    var body = document.createElement('div');

    var tabs = document.createElement('div');
    tabs.className = 'tabs';
    [['global', '🌍 Points'], ['daily', '📅 Daily'], ['badges', '🏅 Badges']].forEach(function (t) {
      var b = document.createElement('button');
      b.textContent = t[1];
      if (t[0] === tab) b.className = 'on';
      b.onclick = function () { openStats(t[0]); };
      tabs.appendChild(b);
    });
    body.appendChild(tabs);

    var panel = document.createElement('div');
    panel.innerHTML = '<div class="empty">Loading…</div>';
    body.appendChild(panel);
    setModalNode(body);

    try {
      if (tab === 'global') {
        var rows = await api('GET', '/api/leaderboard?limit=10');
        // The server returns LeaderboardEntry(rank, username, sortValue, tier, ...) —
        // there is no `points` and no `score`, so every row used to read "undefined pts".
        renderRanks(panel, rows, function (r) { return r.username; },
                    function (r) { return (r.sortValue != null ? r.sortValue : 0) + ' pts'; },
                    'No ranked players yet.');
      } else if (tab === 'daily') {
        var d = await api('GET', '/api/daily/leaderboard?limit=10');
        renderRanks(panel, d, function (r) { return r.playerId; },
                    function (r) { return fmtSeconds(r.seconds != null ? r.seconds : r.solveTimeSeconds); },
                    'Nobody has finished today’s puzzle yet.');
      } else {
        var a = await api('GET', '/api/economy/achievements');
        renderBadges(panel, a);
      }
    } catch (e) {
      panel.innerHTML = '';
      var err = document.createElement('div');
      err.className = 'empty';
      err.textContent = 'Could not load: ' + e.message;
      panel.appendChild(err);
    }
  }

  function fmtSeconds(s) {
    if (s == null) return '—';
    var m = Math.floor(s / 60), r = s % 60;
    return m + ':' + String(r).padStart(2, '0');
  }

  function renderRanks(panel, rows, nameOf, valOf, emptyMsg) {
    panel.innerHTML = '';
    if (!rows || !rows.length) {
      var e = document.createElement('div'); e.className = 'empty'; e.textContent = emptyMsg;
      panel.appendChild(e); return;
    }
    rows.forEach(function (r, i) {
      var who = nameOf(r);
      // Use the server's rank when it sends one: it is authoritative and 1-based, and
      // the array index silently disagrees with it on any paged view.
      var n = (r && r.rank != null) ? r.rank : (i + 1);
      var row = document.createElement('div'); row.className = 'rank-row';
      var pos = document.createElement('div');
      pos.className = 'pos' + (n <= 3 ? ' top' : '');
      pos.textContent = n === 1 ? '🥇' : n === 2 ? '🥈' : n === 3 ? '🥉' : String(n);
      var nm = document.createElement('div');
      nm.className = 'who' + (who === me ? ' me' : '');
      nm.textContent = who || '—';            // textContent: usernames are user-supplied
      var val = document.createElement('div');
      val.className = 'val'; val.textContent = valOf(r);
      row.appendChild(pos); row.appendChild(nm); row.appendChild(val);
      panel.appendChild(row);
    });
  }

  function renderBadges(panel, data) {
    panel.innerHTML = '';
    // The endpoint may return a map {name: bool} or a list of unlocked names.
    var entries = [];
    if (Array.isArray(data)) {
      entries = data.map(function (n) { return [String(n), true]; });
    } else if (data && typeof data === 'object') {
      entries = Object.keys(data).map(function (k) { return [k, !!data[k]]; });
    }
    if (!entries.length) {
      var e = document.createElement('div'); e.className = 'empty';
      e.textContent = 'No achievements yet — solve a puzzle to get started.';
      panel.appendChild(e); return;
    }
    entries.sort(function (a, b) { return (b[1] ? 1 : 0) - (a[1] ? 1 : 0); });
    entries.forEach(function (kv) {
      var b = document.createElement('span');
      b.className = 'badge' + (kv[1] ? '' : ' locked');
      b.textContent = (kv[1] ? '🏅 ' : '🔒 ') + kv[0];
      panel.appendChild(b);
    });
  }

  // ---- modal ----------------------------------------------------------------

  // The modal was not a dialog in any meaningful sense: nothing was focused when it
  // opened, Tab walked straight out of it into the page behind, focus was never restored
  // on close, and the board's keyboard handler stayed live underneath — arrow keys moved
  // the selection behind the overlay and digit keys played moves into the hidden board.
  function openModal(title, html) {
    lastFocus = document.activeElement;
    $('modalTitle').textContent = title;
    $('modalBody').innerHTML = html || '';
    $('modal').classList.add('show');
    document.body.style.overflow = 'hidden';
    $('modalClose').focus();
  }
  function setModalBody(html) { $('modalBody').innerHTML = html; }
  function setModalNode(node) { var b = $('modalBody'); b.innerHTML = ''; b.appendChild(node); }
  function modalOpen() { return $('modal').classList.contains('show'); }
  function closeModal() {
    if (!modalOpen()) return;
    $('modal').classList.remove('show');
    document.body.style.overflow = '';
    if (lastFocus && lastFocus.focus) lastFocus.focus();
    lastFocus = null;
  }
  $('modalClose').onclick = closeModal;
  $('modal').onclick = function (e) { if (e.target === $('modal')) closeModal(); };

  var FOCUSABLE = 'button:not([disabled]),[href],input,select,textarea,[tabindex]:not([tabindex="-1"])';
  $('modal').addEventListener('keydown', function (e) {
    if (e.key !== 'Tab') return;
    var f = $('modal').querySelectorAll(FOCUSABLE);
    if (!f.length) return;
    var first = f[0], last = f[f.length - 1];
    if (e.shiftKey && document.activeElement === first) { last.focus(); e.preventDefault(); }
    else if (!e.shiftKey && document.activeElement === last) { first.focus(); e.preventDefault(); }
  });

  function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, function (ch) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[ch];
    });
  }

  async function refreshHud() {
    try {
      var wallet = await api('GET', '/api/economy/wallet');
      $('hudGems').textContent = wallet.gems;
      if (wallet.level != null) $('hudLevel').textContent = wallet.level;
      $('hudGems').title = '';
    } catch (e) {
      if (e.status === 401) { handleAuthLoss(); return; }
      // Was silently swallowed as "cosmetic", so Gems/Level just sat on stale or blank
      // values with no indication anything had failed.
      $('hudGems').textContent = '!';
      $('hudGems').title = e.message;
    }
    try {
      var daily = await api('GET', '/api/daily');
      $('hudStreak').textContent = daily.streakDays;
    } catch (e) {
      if (e.status === 401) { handleAuthLoss(); return; }
      $('hudStreak').textContent = '!';
      $('hudStreak').title = e.message;
    }
  }

  // ---- timer ----------------------------------------------------------------

  /** Zero-padded mm:ss, for the timer and the victory overlay. */
  function fmtClock(s) {
    return String(Math.floor(s / 60)).padStart(2, '0') + ':' + String(s % 60).padStart(2, '0');
  }
  function startTimer() {
    stopTimer(); startMs = Date.now();
    $('hudTime').textContent = '00:00';
    timerId = setInterval(function () {
      $('hudTime').textContent = fmtClock(Math.floor((Date.now() - startMs) / 1000));
    }, 1000);
  }
  function stopTimer() { if (timerId) { clearInterval(timerId); timerId = null; } }
  function elapsedSeconds() { return startMs ? Math.floor((Date.now() - startMs) / 1000) : 0; }

  // ---- pencil marks survive a reload -----------------------------------------
  // The server round-trips a pencilMarks array per cell, but the client never sent it,
  // so notes vanished on save/resume and on any page refresh. Keyed by game id locally.

  function saveNotes() {
    if (!gameId) return;
    try {
      var o = {};
      Object.keys(notes).forEach(function (k) { if (notes[k].size) o[k] = Array.from(notes[k]); });
      localStorage.setItem('sp.notes.' + gameId, JSON.stringify(o));
    } catch (e) { /* private mode / quota — notes stay in memory */ }
  }
  function loadNotes() {
    notes = {};
    try {
      var o = JSON.parse(localStorage.getItem('sp.notes.' + gameId) || '{}');
      Object.keys(o).forEach(function (k) { notes[k] = new Set(o[k]); });
    } catch (e) { /* ignore corrupt entry */ }
  }

  /** Speaks a message to assistive tech without showing it on screen. */
  function announce(msg) {
    var el = $('srAnnounce');
    if (el) el.textContent = msg;
  }

  function setConnBadge(state) {
    var el = $('hudConn');
    if (!el) return;
    el.className = 'v ' + state;
    el.textContent = state === 'live' ? '●' : (state === 'down' ? '○' : '–');
    el.title = state === 'live' ? 'Connected' : 'Disconnected — reconnecting';
  }

  /**
   * Wraps an async click handler so the button locks and shows a spinner while it runs.
   * Without this every async control was double-submittable with no feedback: three fast
   * clicks on New created three server-side games and three WebSocket connections, and
   * whichever response landed last won.
   */
  function guard(id, fn) {
    return async function () {
      var b = $(id);
      if (b.dataset.busy) return;
      b.dataset.busy = '1'; b.disabled = true; b.classList.add('busy');
      try { await fn.apply(this, arguments); }
      finally { delete b.dataset.busy; b.disabled = false; b.classList.remove('busy'); }
    };
  }

  /** Session loss: drop back to the login panel instead of leaving every button broken. */
  function handleAuthLoss() {
    wsWanted = false;
    if (socket) { try { socket.close(); } catch (e) { /* already closed */ } }
    stopTimer(); closeModal();
    board = null; gameId = null;
    $('game').style.display = 'none';
    $('login').style.display = '';
    status('Your session expired — please log in again.', 'err');
  }

  // ---- board rendering ------------------------------------------------------

  function setBoard(state, fresh) {
    board = state;
    gameId = state.gameId;
    selected = null;
    wasSolved = false;
    cellEls = null;               // a different game: rebuild the grid nodes
    if (fresh) { loadNotes(); startTimer(); $('hintline').textContent = ''; }
    $('hudMoves').textContent = state.moveCount;
    $('boardWrap').classList.toggle('solved', !!state.solved);
    render();
    // setBoard is reached from Hint, Resume and New, none of which used to run the
    // victory path — so a board that arrived already solved showed the overlay while the
    // timer kept ticking, with no toast, no flash and a stale HUD.
    if (state.solved) onSolved();
    connectSocket();
  }

  // duplicate detection for conflict highlighting (client-side, cosmetic)
  function conflictSet() {
    var bad = {};
    function scan(cells) {
      var seen = {};
      cells.forEach(function (rc) {
        var v = board.cells[rc[0]][rc[1]].value;
        if (v === 0) return;
        if (seen[v] !== undefined) { bad[rc[0] + ',' + rc[1]] = 1; bad[seen[v]] = 1; }
        else seen[v] = rc[0] + ',' + rc[1];
      });
    }
    for (var i = 0; i < 9; i++) {
      var rowCells = [], colCells = [];
      for (var j = 0; j < 9; j++) { rowCells.push([i, j]); colCells.push([j, i]); }
      scan(rowCells); scan(colCells);
    }
    for (var br = 0; br < 9; br += 3) for (var bc = 0; bc < 9; bc += 3) {
      var boxCells = [];
      for (var r = 0; r < 3; r++) for (var c = 0; c < 3; c++) boxCells.push([br + r, bc + c]);
      scan(boxCells);
    }
    return bad;
  }

  var GAME_ONLY_BUTTONS = ['btnUndo', 'btnRedo', 'btnHint', 'btnSave'];

  /**
   * Builds the 81 cell nodes once.
   *
   * <p>render() used to do `el.innerHTML = ''` and recreate every cell on every call, so
   * the `.cell { transition: background .12s }` rule never fired — a brand-new element has
   * no previous state to transition from. Selecting a cell or arrowing around snapped
   * instantly, which is a large part of why the board felt unfinished, and it also threw
   * away 81 nodes (and any focus inside the grid) on every keypress.
   */
  function buildGrid() {
    var el = $('board');
    el.innerHTML = '';
    cellEls = [];
    for (var r = 0; r < 9; r++) {
      for (var c = 0; c < 9; c++) {
        var d = document.createElement('div');
        d.setAttribute('role', 'gridcell');
        d.setAttribute('aria-rowindex', r + 1);
        d.setAttribute('aria-colindex', c + 1);
        (function (rr, cc) {
          d.onclick = function () { selected = [rr, cc]; render(); };
          d.onfocus = function () {
            if (!selected || selected[0] !== rr || selected[1] !== cc) { selected = [rr, cc]; render(); }
          };
        })(r, c);
        cellEls.push(d);
        el.appendChild(d);
      }
    }
  }

  function render() {
    if (!board) {
      // An empty #board rendered as a bare purple rectangle — the first thing a player
      // sees after logging in, and it reads as a crash.
      $('board').innerHTML =
        '<div class="board-empty">Press <b>New</b> for a fresh puzzle' +
        '<br>or <b>Daily</b> for today’s challenge</div>';
      $('pad').innerHTML = '';
      cellEls = null;
      GAME_ONLY_BUTTONS.forEach(function (i) { var b = $(i); if (b) b.disabled = true; });
      return;
    }
    GAME_ONLY_BUTTONS.forEach(function (i) { var b = $(i); if (b) b.disabled = false; });
    if (!cellEls) buildGrid();
    var bad = conflictSet();
    var selVal = selected ? board.cells[selected[0]][selected[1]].value : 0;

    board.cells.forEach(function (row, r) {
      row.forEach(function (cell, c) {
        var d = cellEls[r * 9 + c];
        d.textContent = '';
        var cls = 'cell';
        if (cell.isGiven) cls += ' given';
        if (c % 3 === 2 && c !== 8) cls += ' b-right';
        if (r % 3 === 2 && r !== 8) cls += ' b-bottom';
        if (selected) {
          var sr = selected[0], sc = selected[1];
          var sameBox = (Math.floor(sr / 3) === Math.floor(r / 3)) && (Math.floor(sc / 3) === Math.floor(c / 3));
          if (r === sr && c === sc) cls += ' sel';
          else if (r === sr || c === sc || sameBox) cls += ' peer';
          if (selVal !== 0 && cell.value === selVal) cls += ' same';
        }
        var conflicted = !!bad[r + ',' + c];
        if (conflicted) cls += ' conflict';
        d.className = cls;

        var key = r + ',' + c;
        if (cell.value !== 0) {
          d.textContent = cell.value;
        } else if (notes[key] && notes[key].size) {
          var nb = document.createElement('div'); nb.className = 'notes';
          nb.setAttribute('aria-hidden', 'true');
          for (var n = 1; n <= 9; n++) {
            var sp = document.createElement('span');
            sp.textContent = notes[key].has(n) ? n : '';
            nb.appendChild(sp);
          }
          d.appendChild(nb);
        }

        // Roving tabindex, the standard grid pattern: exactly one cell is tabbable and the
        // arrow keys move it. The grid previously had no role, no labels and no tabindex
        // at all, so a screen-reader user got 81 unlabelled numbers with no coordinates
        // and could not reach the board by keyboard.
        var isSel = selected && selected[0] === r && selected[1] === c;
        d.tabIndex = (isSel || (!selected && r === 0 && c === 0)) ? 0 : -1;
        d.setAttribute('aria-readonly', cell.isGiven ? 'true' : 'false');
        d.setAttribute('aria-label',
          'Row ' + (r + 1) + ' column ' + (c + 1) + ', ' +
          (cell.value ? cell.value + (cell.isGiven ? ' given' : '') : 'empty') +
          (notes[key] && notes[key].size ? ', notes ' + Array.from(notes[key]).sort().join(' ') : '') +
          (conflicted ? ', conflict' : ''));
      });
    });
    renderPad();
  }

  function renderPad() {
    var counts = {};
    for (var v = 1; v <= 9; v++) counts[v] = 0;
    board.cells.forEach(function (row) { row.forEach(function (cell) { if (cell.value) counts[cell.value]++; }); });

    var pad = $('pad'); pad.innerHTML = '';

    var nums = document.createElement('div');
    nums.id = 'nums';
    for (var d = 1; d <= 9; d++) {
      (function (val) {
        var b = document.createElement('button');
        var left = Math.max(0, 9 - counts[val]);
        b.className = 'num' + (counts[val] >= 9 ? ' done' : '');
        // Digit + how many of it are still unplaced.
        b.appendChild(document.createTextNode(String(val)));
        var rem = document.createElement('span');
        rem.className = 'rem';
        // Hidden from AT and given an explicit label: the count is a child of the button,
        // so the accessible name was the concatenation — a screen reader announced the
        // "1" button with 4 remaining as "fourteen".
        rem.setAttribute('aria-hidden', 'true');
        rem.textContent = String(left);
        b.appendChild(rem);
        b.setAttribute('aria-label', val + ', ' + left + ' remaining');
        // A completed digit was dimmed but still clickable, so tapping it fired a move
        // the server then rejected with a toast.
        b.disabled = counts[val] >= 9;
        b.onclick = function () { play(val); };
        nums.appendChild(b);
      })(d);
    }
    pad.appendChild(nums);

    var utils = document.createElement('div');
    utils.id = 'utils';

    var notesBtn = document.createElement('button');
    notesBtn.id = 'btnNotes';
    notesBtn.className = 'util secondary' + (notesMode ? ' on' : '');
    notesBtn.textContent = notesMode ? '✎ Notes: ON' : '✎ Notes';
    notesBtn.title = 'Notes mode (N)';
    notesBtn.onclick = function () { notesMode = !notesMode; renderPad(); toast(notesMode ? 'Notes mode on' : 'Notes mode off'); };
    utils.appendChild(notesBtn);

    var erase = document.createElement('button');
    erase.className = 'util secondary'; erase.textContent = '⌫ Erase';
    erase.title = 'Erase (0 / Backspace)';
    erase.onclick = function () { play(0); };
    utils.appendChild(erase);

    pad.appendChild(utils);
  }

  function play(value) {
    if (!selected || !board) return;
    var r = selected[0], c = selected[1];
    var cell = board.cells[r][c];
    if (cell.isGiven) { toast('That cell is a given clue.'); return; }
    var key = r + ',' + c;

    // Notes mode: local pencil marks only (server has no pencil-mark channel).
    if (notesMode && value !== 0 && cell.value === 0) {
      if (!notes[key]) notes[key] = new Set();
      if (notes[key].has(value)) notes[key].delete(value); else notes[key].add(value);
      render();
      return;
    }

    // Erase clears pencil marks first. Previously ⌫ / Backspace / Delete / 0 did
    // NOTHING at all in a cell that held only notes: the notes branch above requires
    // value !== 0, so an erase fell through to the `oldVal === value` guard below with
    // both sides 0 and returned silently. The only way to clear a pencil mark was to
    // toggle each digit off individually, and nothing on screen said so.
    if (value === 0 && notes[key] && notes[key].size) {
      notes[key].clear();
      saveNotes();
      if (cell.value === 0) { render(); announce('Notes cleared'); return; }
    }

    var oldVal = cell.value;
    if (oldVal === value) return;

    if (!socket || socket.readyState !== 1) {
      // Queue rather than discard. This used to toast "Reconnecting…" and return, throwing
      // the player's move away — and because connectSocket() is async, a second click
      // within a couple of hundred milliseconds was thrown away too.
      pending.push({ row: r, col: c, newVal: value });
      toast('Offline — move queued, reconnecting…', 'bad');
      connectSocket();
      return;
    }
    if (value !== 0 && notes[key]) { notes[key].clear(); saveNotes(); }

    lastWasClear = (value === 0);
    lastMoveCell = [r, c];
    lastMoveValue = value;
    cell.value = value;          // optimistic; server 'error' -> sync corrects it
    render();
    // Track the count in JS rather than reading it back out of the DOM, which also
    // incremented for erases the server was about to reject.
    board.moveCount = (board.moveCount || 0) + 1;
    $('hudMoves').textContent = String(board.moveCount);
    // `source` is included because older servers deserialize this payload straight
    // into EnhancedMove, whose constructor rejects a null MoveSource — without it
    // every move came back as an error envelope. The server re-stamps the source
    // itself (a client must not be able to claim HINT/AUTOSOLVE), so the value here
    // is only ever a compatibility shim.
    // send() throws InvalidStateError on a CLOSING socket. That escaped play() uncaught,
    // after the optimistic write had already landed — so the cell showed a value the
    // server never received and the next move was rejected on a stale oldVal.
    try {
      socket.send(JSON.stringify({ type: 'move',
        payload: { row: r, col: c, oldVal: oldVal, newVal: value, source: 'PLAYER' } }));
    } catch (e) {
      cell.value = oldVal;
      board.moveCount = Math.max(0, (board.moveCount || 1) - 1);
      $('hudMoves').textContent = String(board.moveCount);
      render();
      toast('Move not sent — reconnecting…', 'bad');
      connectSocket();
      return;
    }
    announce(value ? value + ' placed at row ' + (r + 1) + ' column ' + (c + 1)
                   : 'Cleared row ' + (r + 1) + ' column ' + (c + 1));

    // Board looks complete? Ask the server for the authoritative (solved) state.
    if (isLocallyFull()) requestSync();
  }

  function isLocallyFull() {
    for (var r = 0; r < 9; r++) for (var c = 0; c < 9; c++) if (board.cells[r][c].value === 0) return false;
    return true;
  }
  function requestSync() {
    if (socket && socket.readyState === 1) socket.send(JSON.stringify({ type: 'sync', payload: '' }));
  }

  function onSolved() {
    if (wasSolved) return;
    wasSolved = true;
    var secs = elapsedSeconds();
    stopTimer();
    $('boardWrap').classList.add('solved');
    // Show the solve time. It was previously visible only in the running timer, which
    // lived in a disabled ghost button and was silently reset by the next New game — for
    // a puzzle game that is the one number players actually want.
    $('boardWrap').dataset.time = fmtClock(secs);
    var cells = $('board').children;
    for (var i = 0; i < cells.length; i++) cells[i].classList.add('solvedflash');
    toast('🎉 Solved in ' + fmtClock(secs) + '!', 'good');
    announce('Puzzle solved in ' + fmtClock(secs));
    log('Puzzle solved in ' + fmtClock(secs) + '.');
    try { localStorage.removeItem('sp.notes.' + gameId); } catch (e) { /* ignore */ }
    refreshHud();
  }

  // ---- keyboard -------------------------------------------------------------

  document.addEventListener('keydown', function (e) {
    if (e.key === 'Escape') { closeModal(); return; }
    // While a modal is open the board must not respond to the keyboard.
    if (modalOpen()) return;
    // Typing in a field must not also drive the game: pressing `n` while the difficulty
    // select had focus both changed the dropdown and toggled Notes mode.
    var tag = e.target && e.target.tagName;
    if (tag === 'INPUT' || tag === 'SELECT' || tag === 'TEXTAREA') return;
    if ($('game').style.display === 'none') return;
    if ((e.ctrlKey || e.metaKey) && (e.key === 'z' || e.key === 'Z')) { sendSimple('undo'); e.preventDefault(); return; }
    if ((e.ctrlKey || e.metaKey) && (e.key === 'y' || e.key === 'Y')) { sendSimple('redo'); e.preventDefault(); return; }
    if (e.ctrlKey || e.metaKey) return;
    if (e.key === 'n' || e.key === 'N') { notesMode = !notesMode; renderPad(); return; }
    if (!selected) {
      if (e.key.indexOf('Arrow') === 0) { selected = [0, 0]; render(); e.preventDefault(); }
      return;
    }
    var r = selected[0], c = selected[1];
    if (e.key === 'ArrowUp') { selected = [(r + 8) % 9, c]; render(); e.preventDefault(); }
    else if (e.key === 'ArrowDown') { selected = [(r + 1) % 9, c]; render(); e.preventDefault(); }
    else if (e.key === 'ArrowLeft') { selected = [r, (c + 8) % 9]; render(); e.preventDefault(); }
    else if (e.key === 'ArrowRight') { selected = [r, (c + 1) % 9]; render(); e.preventDefault(); }
    else if (e.key >= '1' && e.key <= '9') { play(Number(e.key)); }
    else if (e.key === '0' || e.key === 'Backspace' || e.key === 'Delete') { play(0); e.preventDefault(); }
  });

  // ---- websocket ------------------------------------------------------------

  function connectSocket() {
    if (wsRetryTimer) { clearTimeout(wsRetryTimer); wsRetryTimer = null; }
    if (!gameId) return;
    wsWanted = true;
    if (socket) { try { socket.close(); } catch (e) { /* already closed */ } }
    var origin = base || (location.protocol + '//' + location.host);
    var wsUrl = origin.replace(/^http/, 'ws') + '/ws/game?gameId=' + encodeURIComponent(gameId);
    socket = new WebSocket(wsUrl);

    socket.onopen = function () {
      wsRetry = 0;
      setConnBadge('live');
      log('Game channel open');
      // Announce arrival — the client rendered join/leave envelopes but never sent one,
      // so peers in a shared game were never told anyone had arrived.
      try { socket.send(JSON.stringify({ type: 'join', payload: '' })); } catch (e) { /* racing close */ }
      // Re-derive queued moves against the authoritative board: a move queued offline
      // carries a stale oldVal that the server would reject.
      if (pending.length) { requestSync(); setTimeout(flushPending, 400); }
    };

    socket.onmessage = function (ev) {
      var env;
      try { env = JSON.parse(ev.data); } catch (e) { return; }
      switch (env.type) {
        case 'move': {
          var m = env.payload;
          // Apply other players' moves; ignore the server echo of our own.
          // Do NOT bump the move counter here — the same move is broadcast by
          // two server paths, so per-envelope counting double-counts. The HUD
          // is driven by the authoritative moveCount on 'board' syncs instead.
          if (env.from !== me && env.from !== 'server' && board) {
            board.cells[m.row][m.col].value = m.newVal;
            render();
            if (isLocallyFull()) requestSync();
          }
          break;
        }
        case 'board':
          board = env.payload; gameId = board.gameId;
          $('hudMoves').textContent = board.moveCount;   // authoritative
          $('boardWrap').classList.toggle('solved', !!board.solved);
          render();
          if (board.solved) onSolved();
          break;
        case 'status':
        case 'gameEnd':
          // A normal solve emits gameEnd (no 'board'); confirm via a sync.
          requestSync();
          break;
        case 'chat': {
          var who = env.from || 'player';
          log(who + ': ' + String(env.payload));
          break;
        }
        case 'join': log('▸ ' + (env.payload && env.payload.player ? env.payload.player : 'someone') + ' joined'); break;
        case 'leave': log('◂ ' + (env.payload && env.payload.player ? env.payload.player : 'someone') + ' left'); break;
        case 'error':
          if (lastWasClear) {
            // The server currently rejects any move with newVal 0, so an erase
            // never lands. Point the player at Undo, which does work.
            toast('Erase was rejected by the server — use ↶ Undo instead.', 'bad');
          } else {
            var d = env.payload && env.payload.detail;
            // Surface a conflict on the cell rather than as a jargon toast. The server
            // wipes the optimistic value faster than a frame, so `.cell.conflict` and the
            // whole conflictSet() scan never actually rendered anything — the player's
            // only feedback was a red toast reading "Server: Invalid move", which does not
            // say which cell or why.
            if (d === 'Invalid move' && lastMoveCell) {
              flashReject(lastMoveCell[0], lastMoveCell[1], lastMoveValue);
            } else {
              // The server's fallback error path sends a raw Java exception message.
              toast('Server: ' + String(d || 'move rejected').slice(0, 120), 'bad');
            }
          }
          lastWasClear = false;
          requestSync();  // reconcile optimistic state
          break;
        case 'health': break; // keep-alive ping
        case 'notification': case 'DAILY': case 'DUEL': case 'ACHIEVEMENT':
        case 'TOURNAMENT': case 'FRIEND': case 'SEASON':
          toast(String(env.payload));
          log('🔔 ' + String(env.payload));
          refreshHud();
          break;
        // batch_moves was silently dropped, so the board DESYNCED with no error until
        // the next sync. The cosmic-event envelopes were dropped too, so a player pulled
        // into a Cosmic Duel or awarded event gems saw nothing at all.
        case 'batch_moves':
          if (Array.isArray(env.payload) && board) {
            env.payload.forEach(function (m) {
              if (board.cells[m.row] && board.cells[m.row][m.col]) {
                board.cells[m.row][m.col].value = m.newVal;
              }
            });
            render();
            requestSync();
          }
          break;
        case 'event': case 'cosmic_duel': case 'event_join':
        case 'event_score': case 'event_reward':
          toast(String(env.payload));
          log('⚡ ' + String(env.payload));
          refreshHud();
          break;
        // Never fully silent: an unknown type at least reaches the event log.
        default: log('· unhandled envelope: ' + env.type); break;
      }
    };
    // A dropped socket used to do nothing but write one line into a <details> that is
    // collapsed by default: no reconnect, no retry, no indicator anywhere in the UI. The
    // board silently desynced and the next move failed on a stale oldVal.
    socket.onclose = function () {
      log('Game channel closed');
      setConnBadge('down');
      if (!wsWanted || !gameId) return;
      var delay = Math.min(15000, 500 * Math.pow(2, wsRetry++));
      wsRetryTimer = setTimeout(connectSocket, delay);
    };
    socket.onerror = function () { log('Game channel error'); setConnBadge('down'); };
  }

  /** Replays moves made while the socket was down, against the re-synced board. */
  function flushPending() {
    if (!pending.length || !socket || socket.readyState !== 1 || !board) return;
    var queued = pending.slice(); pending = [];
    queued.forEach(function (m) {
      var current = board.cells[m.row][m.col];
      if (current.isGiven || current.value === m.newVal) return;  // already settled
      try {
        socket.send(JSON.stringify({ type: 'move',
          payload: { row: m.row, col: m.col, oldVal: current.value, newVal: m.newVal, source: 'PLAYER' } }));
      } catch (e) { pending.push(m); }
    });
    if (queued.length) toast('Sent ' + queued.length + ' queued move(s)', 'good');
  }

  /** Flashes a cell the server refused, instead of toasting "Server: Invalid move". */
  function flashReject(r, c, v) {
    var el = cellEls && cellEls[r * 9 + c];
    if (!el) return;
    el.classList.add('reject');
    announce(v + ' conflicts at row ' + (r + 1) + ' column ' + (c + 1));
    setTimeout(function () { el.classList.remove('reject'); }, 700);
  }
})();
