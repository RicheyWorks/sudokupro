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
  var PENDING_LIMIT = 81;    // never more than one queued move per cell's worth
  var wsWanted = false, wsRetry = 0, wsRetryTimer = null;
  var lastFocus = null;      // element to restore focus to when a modal closes
  var cellEls = null;        // the 81 cell nodes, built once (see buildGrid)

  // ---- plumbing -------------------------------------------------------------

  /** Every request gets a deadline; without one a hung socket left the UI spinning forever. */
  var REQUEST_TIMEOUT_MS = 15000;

  async function api(method, path, body) {
    var headers = { 'Accept': 'application/json' };
    if (auth) headers['Authorization'] = auth;
    if (body) headers['Content-Type'] = 'application/json';
    if (method !== 'GET' && csrfToken) headers[csrfHeader] = csrfToken;
    var resp;
    // No fetch() in this file had a timeout. A TCP connection that is accepted and then
    // never answered (a wedged pod, a captive portal) leaves the promise pending forever:
    // the Load modal sat on "Loading…", guard() never released the button, and the only
    // way out was a page reload. AbortController + a timer gives every call a deadline.
    var ctrl = typeof AbortController === 'function' ? new AbortController() : null;
    var timedOut = false;
    var timer = ctrl ? setTimeout(function () { timedOut = true; ctrl.abort(); }, REQUEST_TIMEOUT_MS) : null;
    try {
      resp = await fetch(base + path, {
        method: method, headers: headers, credentials: 'include',
        signal: ctrl ? ctrl.signal : undefined,
        body: body ? JSON.stringify(body) : undefined
      });
    } catch (e) {
      // "Failed to fetch" is plumbing, not a message for a player.
      var ne = new Error(timedOut ? 'The server did not respond in time.'
                                  : (navigator.onLine ? 'Could not reach the server.'
                                                      : 'You appear to be offline.'));
      ne.status = 0; throw ne;
    } finally {
      if (timer) clearTimeout(timer);
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
    // Routed through api() so registration gets the same timeout and problem+json
    // handling as everything else; the raw fetch here had neither.
    var savedAuth = auth; auth = '';
    try {
      await api('POST', '/api/auth/register', { username: u, password: $('pass').value });
      status('Account created — press Log in & Play.', 'ok');
    } catch (e) {
      status(e.status === 409 ? 'That username is taken.' : e.message, 'err');
    } finally { auth = savedAuth; }
  });

  $('loginForm').onsubmit = function (e) { e.preventDefault(); login(); };
  var login = guard('btnLogin', async function () {
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
      auth = '';   // a failed login must not leave stale credentials on later calls
      status(e.status === 429 ? 'Too many attempts — wait a minute.' : ('Login failed: ' + e.message), 'err');
    }
  });

  // "Exit" never told the server anything: it dropped the socket and reloaded the page, so
  // the HttpSession created by GET /api/session stayed valid — the WebSocket handshake
  // authenticates off that cookie, so anyone on the machine could reconnect a gameplay
  // socket as the previous player just by opening /play again. Spring Security's logout is
  // POST /logout and it needs the CSRF token; without the header it 403s and the session
  // survives anyway.
  $('btnLogout').onclick = guard('btnLogout', async function () {
    wsWanted = false;
    if (wsRetryTimer) { clearTimeout(wsRetryTimer); wsRetryTimer = null; }
    closeSocket();
    stopTimer();
    try {
      await api('POST', '/logout');
    } catch (e) {
      // Never strand the player on a half-logged-out screen: report and reload regardless,
      // which at least clears every credential this tab holds.
      toast('Server logout failed: ' + e.message, 'bad');
      await new Promise(function (r) { setTimeout(r, 900); });
    }
    auth = ''; csrfToken = null; me = null;
    location.reload();
  });

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
      // POST, not GET: buying a hint spends gems and raises the board's hint count, so it
      // is not a safe method. As a GET the browser was free to replay it on a reload or a
      // prefetch and spend the player's gems unasked. The server still answers the old GET
      // (deprecated, and idempotent now) for already-shipped clients.
      var h = await api('POST', '/api/game/hint?gameId=' + encodeURIComponent(gameId));
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
      if (Array.isArray(list)) list = list.filter(isBoardShape);
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
        btn.onclick = function () { doResume(s.gameId, btn); };
        row.appendChild(meta); row.appendChild(btn);
        body.appendChild(row);
      });
      setModalNode(body);
    } catch (e) {
      if (e.status === 401) { handleAuthLoss(); return; }
      setModalBody('<div class="empty">' + escapeHtml(e.message) + '</div>');
    }
  });

  async function doResume(id, btn) {
    // Every Resume row fired straight into an unguarded async call: the modal stayed open
    // with no feedback, and a double-tap sent two resumes for two different games.
    if (btn) { if (btn.dataset.busy) return; btn.dataset.busy = '1'; btn.disabled = true; btn.classList.add('busy'); }
    try {
      var s = await api('POST', '/api/game/' + encodeURIComponent(id) + '/resume');
      closeModal();
      setBoard(s, true);
      toast('Resumed', 'good');
      log('Resumed ' + id);
    } catch (e) {
      if (e.status === 401) { handleAuthLoss(); return; }
      toast(e.status === 409 ? 'That game is already finished.'
          : e.status === 404 ? 'That save is gone.'
          : ('Resume failed: ' + e.message), 'bad');
    } finally {
      if (btn) { delete btn.dataset.busy; btn.disabled = false; btn.classList.remove('busy'); }
    }
  }

  // ---- stats / leaderboards --------------------------------------------------
  // All three of these endpoints already existed server-side and were never surfaced.

  $('btnStats').onclick = function () { openStats('global'); };

  async function openStats(tab) {
    // Re-entered on every tab click; the strip below is rebuilt, which destroys the button
    // that was just activated. Put focus back on the equivalent new one.
    var reentry = modalOpen();
    openModal('Stats', '');
    var body = document.createElement('div');

    var tabs = document.createElement('div');
    tabs.className = 'tabs';
    [['global', '🌍 Points'], ['daily', '📅 Daily'], ['badges', '🏅 Badges']].forEach(function (t) {
      var b = document.createElement('button');
      b.type = 'button';
      b.textContent = t[1];
      b.setAttribute('aria-pressed', t[0] === tab ? 'true' : 'false');
      if (t[0] === tab) b.className = 'on';
      b.onclick = function () { openStats(t[0]); };
      tabs.appendChild(b);
    });
    body.appendChild(tabs);

    var panel = document.createElement('div');
    panel.innerHTML = '<div class="empty">Loading…</div>';
    body.appendChild(panel);
    setModalNode(body);
    if (reentry) { var on = tabs.querySelector('button.on'); if (on) on.focus(); }

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
      if (e.status === 401) { handleAuthLoss(); return; }
      panel.innerHTML = '';
      var err = document.createElement('div');
      err.className = 'empty';
      err.textContent = 'Could not load: ' + e.message;
      var again = document.createElement('button');
      again.className = 'secondary'; again.type = 'button';
      again.textContent = 'Retry';
      again.style.marginTop = '10px';
      again.onclick = function () { openStats(tab); };
      panel.appendChild(err); panel.appendChild(again);
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
    // Only capture the return target on the FIRST open. openStats() re-enters openModal on
    // every tab click, which overwrote lastFocus with the tab button — and setModalNode
    // then destroyed that button, so closing the dialog called focus() on a detached node
    // and focus fell to <body>. A keyboard user who looked at the Daily tab was dumped at
    // the top of the document and had to Tab all the way back to the board.
    if (!modalOpen()) lastFocus = document.activeElement;
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
    // isConnected guards against restoring focus to a node that has since been removed
    // from the document, which silently drops focus to <body>.
    if (lastFocus && lastFocus.focus && lastFocus.isConnected) lastFocus.focus();
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

  // ---- pencil marks ----------------------------------------------------------
  // Notes are client-local (the server has no pencil-mark write channel on the socket).
  // They are kept in memory per game id, so switching between two games with Load and
  // coming back keeps each board's marks. They deliberately do NOT outlive the tab.

  var notesByGame = {};        // gameId -> { "r,c": Set(digits) }
  var NOTE_GAME_LIMIT = 12;    // bound the map: Load can cycle through many game ids
  var noteGameOrder = [];

  function saveNotes() {
    if (!gameId) return;
    var o = {};
    Object.keys(notes).forEach(function (k) { if (notes[k].size) o[k] = notes[k]; });
    notesByGame[gameId] = o;
    var at = noteGameOrder.indexOf(gameId);
    if (at !== -1) noteGameOrder.splice(at, 1);
    noteGameOrder.push(gameId);
    while (noteGameOrder.length > NOTE_GAME_LIMIT) delete notesByGame[noteGameOrder.shift()];
  }
  function loadNotes() {
    var stored = gameId ? notesByGame[gameId] : null;
    notes = {};
    if (stored) Object.keys(stored).forEach(function (k) { notes[k] = new Set(stored[k]); });
  }
  function forgetNotes(id) {
    delete notesByGame[id];
    var at = noteGameOrder.indexOf(id);
    if (at !== -1) noteGameOrder.splice(at, 1);
  }

  /** Speaks a message to assistive tech without showing it on screen. */
  function announce(msg) {
    var el = $('srAnnounce');
    if (el) el.textContent = msg;
  }

  var connState = null;
  function setConnBadge(state) {
    var el = $('hudConn');
    if (!el) return;
    var was = connState;
    connState = state;
    el.className = 'v ' + state;
    // Was "●" / "○" / "–". Colour plus an unlabelled glyph is exactly the pattern WCAG
    // 1.4.1 forbids: a screen reader read the live badge as "black circle" and the
    // disconnected one as "white circle", and the two are near-identical shapes for
    // anyone who cannot separate green from red. Words carry it instead.
    el.textContent = state === 'live' ? 'Live' : (state === 'down' ? 'Down' : 'Idle');
    el.title = state === 'live' ? 'Connected to the game channel'
             : state === 'down' ? 'Disconnected — reconnecting'
             : 'Not in a game';
    if (was && was !== state) {
      if (state === 'down') announce('Connection lost. Reconnecting.');
      else if (state === 'live' && was === 'down') announce('Reconnected.');
    }
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
    if (wsRetryTimer) { clearTimeout(wsRetryTimer); wsRetryTimer = null; }
    closeSocket();
    stopTimer(); closeModal();
    board = null; gameId = null; pending = [];
    auth = ''; csrfToken = null;
    setConnBadge('idle');
    $('game').style.display = 'none';
    $('login').style.display = '';
    status('Your session expired — please log in again.', 'err');
  }

  // ---- board rendering ------------------------------------------------------

  function setBoard(state, fresh) {
    if (!isBoardShape(state)) { toast('The server sent a board this client cannot read.', 'bad'); return; }
    var switching = state.gameId !== gameId;
    if (switching && pending.length) {
      // Queued moves belong to the game they were made in. Pressing New (or resuming a
      // different save) while the socket was down replayed them into the fresh puzzle,
      // scribbling the old game's digits onto unrelated cells.
      log('Discarded ' + pending.length + ' queued move(s) from the previous game');
      pending = [];
    }
    board = state;
    gameId = state.gameId;
    selected = null;
    wasSolved = false;
    cellEls = null;               // a different game: rebuild the grid nodes
    padEls = null;
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
    el.setAttribute('role', 'grid');
    el.setAttribute('aria-rowcount', '9');
    el.setAttribute('aria-colcount', '9');
    cellEls = [];
    for (var r = 0; r < 9; r++) {
      // role="grid" requires role="row" children; 81 gridcells hanging directly off the
      // grid is invalid ARIA and NVDA/VoiceOver refuse to report "row n of 9, column m of
      // 9" without it. The rows carry `display:contents` so the CSS grid still lays the
      // 81 cells out itself.
      var rowEl = document.createElement('div');
      rowEl.className = 'grid-row';
      rowEl.setAttribute('role', 'row');
      rowEl.setAttribute('aria-rowindex', r + 1);
      for (var c = 0; c < 9; c++) {
        var d = document.createElement('div');
        d.setAttribute('role', 'gridcell');
        d.setAttribute('aria-colindex', c + 1);
        (function (rr, cc) {
          d.onclick = function () { select(rr, cc, true); };
          d.onfocus = function () {
            if (!selected || selected[0] !== rr || selected[1] !== cc) select(rr, cc, false);
          };
        })(r, c);
        cellEls.push(d);
        rowEl.appendChild(d);
      }
      el.appendChild(rowEl);
    }
  }

  /**
   * Moves the selection, and moves DOM focus with it.
   *
   * <p>Arrow keys only reassigned `selected` and re-rendered. Roving tabindex moved to the
   * new cell but focus stayed on the old one, so: the visible focus ring and the cyan
   * selection box sat on two different cells, a screen reader kept announcing the cell the
   * player had left, and pressing Tab exited the grid from the wrong place. The board was
   * playable by keyboard but unusable by anyone relying on the focus ring or on speech.
   */
  function select(r, c, alsoFocus) {
    selected = [r, c];
    render();
    if (alsoFocus !== false && cellEls && cellEls[r * 9 + c] &&
        document.activeElement !== cellEls[r * 9 + c]) {
      cellEls[r * 9 + c].focus();
    }
  }

  function render() {
    if (!board) {
      // An empty #board rendered as a bare purple rectangle — the first thing a player
      // sees after logging in, and it reads as a crash.
      var bd = $('board');
      // Drop the grid semantics while there is no grid: a role="grid" whose only child is
      // a paragraph is invalid, and a screen reader announced "grid, 9 by 9" over an empty
      // placeholder.
      bd.setAttribute('role', 'group');
      bd.removeAttribute('aria-rowcount');
      bd.removeAttribute('aria-colcount');
      bd.innerHTML =
        '<div class="board-empty">Press <b>New</b> for a fresh puzzle' +
        '<br>or <b>Daily</b> for today’s challenge</div>';
      $('pad').innerHTML = '';
      cellEls = null;
      padEls = null;
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
        var rejected = rejectMark && rejectMark.r === r && rejectMark.c === c
                       && Date.now() < rejectMark.until;
        if (rejected) cls += ' reject';
        // Only touch className when it actually changed: reassigning it restarts the
        // reject shake on every render, and render() runs several times per rejection.
        if (d.className !== cls) d.className = cls;

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
          (conflicted ? ', conflict' : '') +
          (rejected ? ', rejected' : ''));
      });
    });
    renderPad();
  }

  var padEls = null;   // { nums: [9 buttons], rems: [9 spans], notesBtn, eraseBtn }

  /**
   * Builds the pad once.
   *
   * <p>renderPad() used to do `pad.innerHTML = ''` and recreate all eleven buttons on
   * every render — and render() runs on every move, every arrow key and every cell click.
   * Destroying the focused element moves focus to <body>, so a keyboard or switch user who
   * tabbed to the "5" key and pressed it was thrown out of the pad entirely and had to Tab
   * all the way back in for each digit. It also reset the CSS transitions and made the
   * pad flicker on touch.
   */
  function buildPad() {
    var pad = $('pad');
    pad.innerHTML = '';
    padEls = { nums: [], rems: [] };

    var nums = document.createElement('div');
    nums.id = 'nums';
    nums.setAttribute('role', 'group');
    nums.setAttribute('aria-label', 'Digits');
    for (var d = 1; d <= 9; d++) {
      (function (val) {
        var b = document.createElement('button');
        b.type = 'button';
        b.appendChild(document.createTextNode(String(val)));
        var rem = document.createElement('span');
        rem.className = 'rem';
        // Hidden from AT and given an explicit label: the count is a child of the button,
        // so the accessible name was the concatenation — a screen reader announced the
        // "1" button with 4 remaining as "fourteen".
        rem.setAttribute('aria-hidden', 'true');
        b.appendChild(rem);
        b.onclick = function () {
          // A completed digit was dimmed but still clickable, so tapping it fired a move
          // the server then rejected with a red toast. It is marked aria-disabled rather
          // than disabled: `disabled` removes the button from the tab order, and doing
          // that to the button the player is standing on (placing the ninth 7 while
          // focused on the 7 key) drops focus to <body> mid-solve.
          if (b.getAttribute('aria-disabled') === 'true') {
            toast('All nine ' + val + 's are placed.');
            return;
          }
          play(val);
        };
        padEls.nums.push(b); padEls.rems.push(rem);
        nums.appendChild(b);
      })(d);
    }
    pad.appendChild(nums);

    var utils = document.createElement('div');
    utils.id = 'utils';

    var notesBtn = document.createElement('button');
    notesBtn.id = 'btnNotes'; notesBtn.type = 'button';
    notesBtn.title = 'Notes mode (N)';
    notesBtn.onclick = function () {
      notesMode = !notesMode; renderPad();
      toast(notesMode ? 'Notes mode on' : 'Notes mode off');
      announce(notesMode ? 'Notes mode on' : 'Notes mode off');
    };
    utils.appendChild(notesBtn);
    padEls.notesBtn = notesBtn;

    var erase = document.createElement('button');
    erase.className = 'util secondary'; erase.type = 'button';
    erase.textContent = '⌫ Erase';
    erase.title = 'Erase (0 / Backspace / Delete)';
    erase.setAttribute('aria-label', 'Erase the selected cell');
    erase.onclick = function () { play(0); };
    utils.appendChild(erase);
    padEls.eraseBtn = erase;

    pad.appendChild(utils);
  }

  function renderPad() {
    if (!board) return;
    if (!padEls) buildPad();
    var counts = {};
    for (var v = 1; v <= 9; v++) counts[v] = 0;
    board.cells.forEach(function (row) { row.forEach(function (cell) { if (cell.value) counts[cell.value]++; }); });

    for (var d = 1; d <= 9; d++) {
      var b = padEls.nums[d - 1], done = counts[d] >= 9;
      var left = Math.max(0, 9 - counts[d]);
      b.className = 'num' + (done ? ' done' : '');
      padEls.rems[d - 1].textContent = String(left);
      b.setAttribute('aria-disabled', done ? 'true' : 'false');
      b.setAttribute('aria-label', d + ', ' + (done ? 'all placed' : left + ' remaining'));
    }
    padEls.notesBtn.className = 'util secondary' + (notesMode ? ' on' : '');
    padEls.notesBtn.textContent = notesMode ? '✎ Notes: ON' : '✎ Notes';
    padEls.notesBtn.setAttribute('aria-pressed', notesMode ? 'true' : 'false');
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
      saveNotes();     // was missing: a toggled pencil mark was never handed to the store
      render();
      announce((notes[key].has(value) ? 'Note ' + value + ' added at row '
                                      : 'Note ' + value + ' removed at row ') +
               (r + 1) + ' column ' + (c + 1));
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
      // Bounded: an unbounded queue grows for as long as the socket stays down (a player
      // can hammer the pad indefinitely), and every entry is replayed on reconnect. Keep
      // the most recent ones — an old queued move is superseded by a later one anyway.
      pending.push({ row: r, col: c, newVal: value });
      while (pending.length > PENDING_LIMIT) pending.shift();
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
    if (cellEls) cellEls.forEach(function (el) { el.classList.add('solvedflash'); });
    toast('🎉 Solved in ' + fmtClock(secs) + '!', 'good');
    announce('Puzzle solved in ' + fmtClock(secs));
    log('Puzzle solved in ' + fmtClock(secs) + '.');
    forgetNotes(gameId);
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
    if (e.key === 'n' || e.key === 'N') {
      notesMode = !notesMode; renderPad();
      announce(notesMode ? 'Notes mode on' : 'Notes mode off');
      return;
    }
    if (!board) return;
    if (!selected) {
      if (e.key.indexOf('Arrow') === 0) { select(0, 0, true); e.preventDefault(); }
      return;
    }
    var r = selected[0], c = selected[1];
    if (e.key === 'ArrowUp') { select((r + 8) % 9, c, true); e.preventDefault(); }
    else if (e.key === 'ArrowDown') { select((r + 1) % 9, c, true); e.preventDefault(); }
    else if (e.key === 'ArrowLeft') { select(r, (c + 8) % 9, true); e.preventDefault(); }
    else if (e.key === 'ArrowRight') { select(r, (c + 1) % 9, true); e.preventDefault(); }
    // Home/End/PageUp/PageDown are part of the WAI-ARIA grid pattern and were missing.
    else if (e.key === 'Home') { select(r, e.ctrlKey ? 0 : 0, true); e.preventDefault(); }
    else if (e.key === 'End') { select(r, 8, true); e.preventDefault(); }
    else if (e.key === 'PageUp') { select(0, c, true); e.preventDefault(); }
    else if (e.key === 'PageDown') { select(8, c, true); e.preventDefault(); }
    else if (e.key >= '1' && e.key <= '9') { play(Number(e.key)); e.preventDefault(); }
    else if (e.key === '0' || e.key === 'Backspace' || e.key === 'Delete') { play(0); e.preventDefault(); }
  });

  // ---- websocket ------------------------------------------------------------

  /**
   * Detaches every handler from a socket before closing it.
   *
   * <p>Without this, connectSocket()'s own `socket.close()` fired the OLD socket's
   * onclose, which — seeing wsWanted and a gameId — scheduled another connectSocket().
   * That reconnect then closed the socket we had just opened, whose onclose scheduled
   * another one, and so on: a permanent churn where the link badge flickered, `join` was
   * re-broadcast to every peer on every cycle and the board never settled. It was
   * triggered by ordinary use — Hint and New both call setBoard(), which calls
   * connectSocket(), and every `online` event does too.
   */
  function closeSocket() {
    var s = socket;
    socket = null;
    if (!s) return;
    s.onopen = s.onmessage = s.onclose = s.onerror = null;
    stopWatchdog();
    probeSentMs = 0;
    try { s.close(); } catch (e) { /* already closed */ }
  }

  function socketUrlFor(id) {
    var origin = base || (location.protocol + '//' + location.host);
    // encodeURIComponent escapes ':' as %3A. Daily and duel game ids are of the form
    // `daily-<date>:<player>`, and the handshake interceptor reads the query parameter
    // WITHOUT percent-decoding it, so the escaped form looked up a game that does not
    // exist and the server closed the socket with "Unknown game" — the daily puzzle could
    // be started over REST but never played. ':' is legal unescaped in a query string
    // (RFC 3986 §3.4), so leave it, and keep everything else escaped.
    return origin.replace(/^http/, 'ws') + '/ws/game?gameId=' +
      encodeURIComponent(id).replace(/%3A/g, ':');
  }

  // ── Liveness watchdog ────────────────────────────────────────────────────
  // onclose is not a reliable death notice. A socket whose TCP connection is black-holed
  // — Wi-Fi dropping out, a laptop suspending, a proxy silently timing out the tunnel —
  // stays readyState OPEN indefinitely and fires nothing. Reproduced in Chromium: after
  // the network was cut the client sat on a dead socket for over 20 seconds with the HUD
  // still reading connected, send() silently discarding every move. The server has no
  // heartbeat of its own (MultiplayerBroadcaster.broadcastHealthPing exists but nothing
  // ever calls it), so the client has to probe: `sync` is the one verb that always answers
  // the sender, and its reply doubles as a desync repair.
  var lastRxMs = 0, probeSentMs = 0, watchdogId = null;
  var IDLE_PROBE_MS = 30000, PROBE_TIMEOUT_MS = 8000;

  function startWatchdog() {
    stopWatchdog();
    watchdogId = setInterval(function () {
      if (!socket || socket.readyState !== 1) return;
      var now = Date.now();
      if (probeSentMs) {
        if (now - probeSentMs > PROBE_TIMEOUT_MS) {
          log('No reply to liveness probe — forcing reconnect');
          probeSentMs = 0;
          setConnBadge('down');
          connectSocket(true);
        }
        return;
      }
      if (now - lastRxMs > IDLE_PROBE_MS) { probeSentMs = now; requestSync(); }
    }, 5000);
  }
  function stopWatchdog() { if (watchdogId) { clearInterval(watchdogId); watchdogId = null; } }

  // Background tabs throttle setInterval to about once a minute, and a laptop that slept
  // wakes up holding a socket the server closed long ago. Re-check the moment the tab
  // comes back rather than waiting out the throttled tick.
  document.addEventListener('visibilitychange', function () {
    if (document.visibilityState !== 'visible' || !wsWanted || !gameId) return;
    if (!socket || socket.readyState !== 1) connectSocket();
    else { probeSentMs = Date.now(); requestSync(); }
  });

  function connectSocket(force) {
    if (wsRetryTimer) { clearTimeout(wsRetryTimer); wsRetryTimer = null; }
    if (!gameId) return;
    wsWanted = true;
    var url = socketUrlFor(gameId);
    // Already connected (or connecting) to this very game: nothing to do. setBoard() runs
    // on every Hint, so this used to tear down and re-handshake a perfectly good socket
    // several times a minute, losing any frame in flight.
    if (!force && socket && socket.url === url &&
        (socket.readyState === 0 || socket.readyState === 1)) {
      // Still healthy. Re-assert the badge: the `offline` listener paints it Down on the
      // OS event alone, and if the socket survived (a brief Wi-Fi blip) nothing else would
      // ever have painted it back — the HUD read "Down" for the rest of the session while
      // moves were flowing normally.
      if (socket.readyState === 1) { setConnBadge('live'); requestSync(); }
      return;
    }
    closeSocket();
    socket = new WebSocket(url);

    socket.onopen = function () {
      wsRetry = 0;
      lastRxMs = Date.now(); probeSentMs = 0;
      startWatchdog();
      setConnBadge('live');
      log('Game channel open');
      // Announce arrival — the client rendered join/leave envelopes but never sent one,
      // so peers in a shared game were never told anyone had arrived.
      try { socket.send(JSON.stringify({ type: 'join', payload: '' })); } catch (e) { /* racing close */ }
      // ALWAYS resync on (re)connect, not only when moves are queued. Anything that
      // happened while the socket was down — the peer's moves, an undo, a hint — was
      // simply missed, and the client carried on from a stale board until something else
      // happened to trigger a sync.
      requestSync();
      if (pending.length) setTimeout(flushPending, 400);
    };

    socket.onmessage = function (ev) {
      lastRxMs = Date.now(); probeSentMs = 0;
      var env;
      try { env = JSON.parse(ev.data); } catch (e) { log('· ignored unparseable frame'); return; }
      if (!env || typeof env !== 'object' || typeof env.type !== 'string') return;
      switch (env.type) {
        case 'move': {
          // The guard here used to be `env.from !== me && env.from !== 'server'`, but
          // MultiplayerBroadcaster stamps EVERY move envelope with from="server" (there is
          // no per-player attribution on the wire at all), so the condition was never true
          // and no remote move was ever applied — the branch was dead code. Compare against
          // local state instead: an echo of our own optimistic move already matches and is
          // a no-op, anything that differs is news.
          var m = env.payload;
          if (!board || !isMoveShape(m)) break;
          var target = board.cells[m.row][m.col];
          if (target.value === m.newVal) break;          // our own echo
          target.value = m.newVal;
          render();
          announce(m.newVal
            ? 'Opponent placed ' + m.newVal + ' at row ' + (m.row + 1) + ' column ' + (m.col + 1)
            : 'Opponent cleared row ' + (m.row + 1) + ' column ' + (m.col + 1));
          // Do NOT bump the move counter here — the same move is broadcast by
          // two server paths, so per-envelope counting double-counts. The HUD
          // is driven by the authoritative moveCount on 'board' syncs instead.
          if (isLocallyFull()) requestSync();
          break;
        }
        case 'board':
          // Assigning env.payload unchecked meant one malformed frame replaced `board`
          // with whatever arrived, and the very next render() threw on board.cells —
          // taking down every later handler on this socket with it.
          if (!isBoardShape(env.payload)) { log('· ignored malformed board envelope'); break; }
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
        case 'error': {
          // The "erase is always rejected" special case was removed: SudokuBoard.isValidMove
          // now returns true for value 0 (`if (value == 0) return true;`), so a clear is a
          // legal move and lands like any other. The old branch swallowed the real reason
          // for ANY failed erase and told the player to press Undo instead.
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
            toast('Server: ' + payloadText(env.payload, 'move rejected').slice(0, 120), 'bad');
          }
          lastWasClear = false;
          requestSync();  // reconcile optimistic state
          break;
        }
        case 'health': break; // keep-alive ping
        case 'notification': case 'DAILY': case 'DUEL': case 'ACHIEVEMENT':
        case 'TOURNAMENT': case 'FRIEND': case 'SEASON':
          toast(payloadText(env.payload, 'Notification'));
          log('🔔 ' + payloadText(env.payload, 'Notification'));
          refreshHud();
          break;
        // batch_moves was silently dropped, so the board DESYNCED with no error until
        // the next sync. The cosmic-event envelopes were dropped too, so a player pulled
        // into a Cosmic Duel or awarded event gems saw nothing at all.
        case 'batch_moves':
          if (Array.isArray(env.payload) && board) {
            env.payload.forEach(function (m) {
              if (isMoveShape(m)) board.cells[m.row][m.col].value = m.newVal;
            });
            render();
            requestSync();
          }
          break;
        case 'event': case 'cosmic_duel': case 'event_join':
        case 'event_score': case 'event_reward':
          // MultiplayerBroadcaster.sendGameEvent puts a whole GameEvent OBJECT in the
          // payload, so String(env.payload) rendered a toast reading "[object Object]".
          toast(payloadText(env.payload, 'Event'));
          log('⚡ ' + payloadText(env.payload, 'Event'));
          refreshHud();
          break;
        case 'hint': case 'debug':
          $('hintline').textContent = '💡 ' + payloadText(env.payload, '');
          break;
        // Never fully silent: an unknown type at least reaches the event log.
        default: log('· unhandled envelope: ' + env.type); break;
      }
    };
    // A dropped socket used to do nothing but write one line into a <details> that is
    // collapsed by default: no reconnect, no retry, no indicator anywhere in the UI. The
    // board silently desynced and the next move failed on a stale oldVal.
    socket.onclose = function (ev) {
      log('Game channel closed' + (ev && ev.code ? ' (' + ev.code + ')' : ''));
      if (socket === this) socket = null;
      stopWatchdog(); probeSentMs = 0;
      setConnBadge('down');
      if (!wsWanted || !gameId) return;
      // 1008 is POLICY_VIOLATION: the server refuses this connection outright —
      // "Authentication required", "Unknown game", "Competitive games cannot be
      // spectated". Retrying is guaranteed to fail, and the old code retried forever,
      // hammering the handshake every 15s and leaving the player staring at a dead board
      // with no explanation.
      if (ev && ev.code === 1008) {
        wsWanted = false;
        var why = (ev.reason || 'the server refused the connection');
        toast('Cannot join this game: ' + why, 'bad');
        announce('Disconnected. ' + why);
        if (/auth/i.test(why)) handleAuthLoss();
        return;
      }
      var delay = Math.min(15000, 500 * Math.pow(2, wsRetry++));
      log('Reconnecting in ' + Math.round(delay / 100) / 10 + 's');
      wsRetryTimer = setTimeout(connectSocket, delay);
    };
    socket.onerror = function () { log('Game channel error'); setConnBadge('down'); };
  }

  /** True for a {row, col, newVal} payload that addresses a real cell on this board. */
  function isMoveShape(m) {
    return !!m && typeof m === 'object' &&
      Number.isInteger(m.row) && m.row >= 0 && m.row <= 8 &&
      Number.isInteger(m.col) && m.col >= 0 && m.col <= 8 &&
      Number.isInteger(m.newVal) && m.newVal >= 0 && m.newVal <= 9 &&
      !!board && !!board.cells && !!board.cells[m.row] && !!board.cells[m.row][m.col];
  }

  /** True for a payload that is actually a 9x9 BoardState. */
  function isBoardShape(b) {
    if (!b || typeof b !== 'object' || !Array.isArray(b.cells) || b.cells.length !== 9) return false;
    for (var i = 0; i < 9; i++) {
      if (!Array.isArray(b.cells[i]) || b.cells[i].length !== 9) return false;
    }
    return true;
  }

  /**
   * Envelope payloads are sometimes a String and sometimes an object (GameEvent,
   * {status: ...}, {detail: ...}). String(obj) gives "[object Object]", which is what the
   * player used to see in the toast.
   */
  function payloadText(p, fallback) {
    if (p == null) return fallback || '';
    if (typeof p === 'string') return p;
    if (typeof p !== 'object') return String(p);
    var pick = p.detail || p.message || p.status || p.reason || p.type;
    if (typeof pick === 'string' && pick) return pick;
    try {
      var s = JSON.stringify(p);
      return s && s !== '{}' ? s.slice(0, 160) : (fallback || '');
    } catch (e) { return fallback || ''; }
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

  /**
   * Flashes a cell the server refused, instead of toasting "Server: Invalid move".
   *
   * <p>The flash has to survive a re-render. The 'error' handler ends with requestSync(),
   * the server answers with a 'board' envelope within a few milliseconds, and render()
   * reassigns `d.className` wholesale — which stripped `.reject` long before the 350ms
   * shake or the ✕ could be seen. Verified in Chromium: 400ms after a rejected keystroke
   * the cell's class list was back to "cell b-right sel" with no trace of the rejection,
   * so the only thing a sighted player ever got was a value that silently vanished. Hold
   * the state in a variable render() consults instead of on the node.
   */
  var rejectMark = null, rejectTimer = null;
  function flashReject(r, c, v) {
    if (!cellEls || !cellEls[r * 9 + c]) return;
    rejectMark = { r: r, c: c, until: Date.now() + 900 };
    if (rejectTimer) clearTimeout(rejectTimer);
    rejectTimer = setTimeout(function () { rejectMark = null; rejectTimer = null; render(); }, 900);
    render();
    announce(v + ' conflicts at row ' + (r + 1) + ' column ' + (c + 1) + ', not placed');
  }
})();
