#!/usr/bin/env python3
"""
Browser harness for the shipped web client (server/src/main/resources/static/play).

WHY THIS EXISTS
---------------
Until now `app.js` had **no test of any kind** — no JS unit tests, no browser
driver, and no CI step that referenced it. Two defects fixed in earlier passes
live entirely in this file (the client never noticing a normally-solved game,
and the pencil-mark/resync interaction), and the only way to confirm either was
still fixed was to read the source by hand. That is not a safety net; it is a
promise. A regression in `app.js` could not turn a build red.

WHAT IT DOES
------------
Drives real Chromium against a real running server and **plays a complete game
through the UI** — clicking cells and pressing digit keys, the same path a
player takes — until the puzzle is solved. Playing for real is the point: the
solved-game detection this protects is reached through the WebSocket `gameEnd`
envelope, which only the server emits, and only when the final move actually
lands. A test that poked the DOM into a solved state would assert nothing.

DESIGN NOTES
------------
* **No skipping.** An unreachable server or a missing browser is a FAILURE, with
  the exact command to fix it. The project already learned this the hard way:
  a skip that looks like a pass is the defect (pass 14, pass 15).
* **Console errors are checks, not noise.** Any uncaught page error or
  `console.error` fails the run — that is how a silently broken handler shows up.
* The puzzle is solved in Python (`solve_grid`) rather than read from the server:
  the client is never sent the solution, and neither is this harness. It derives
  it from the givens exactly as a player would have to.

Usage:  python3 testing/web_client_test.py [--base http://localhost:8080] [--headed]
"""

import argparse
import base64
import json
import sys
import time
import urllib.error
import urllib.request

BASE = "http://localhost:8080"

results = []
console_problems = []


def chk(name, got, want, note=""):
    ok = got in want if isinstance(want, (list, tuple, set)) else got == want
    results.append((ok, name, got, want, note))
    print(f"  [{'PASS' if ok else 'FAIL'}] {name}: {got} (want {want}) {note}", flush=True)
    return ok


def die(msg):
    print("\nFIXTURE FAILURE: " + msg, file=sys.stderr)
    sys.exit(2)


# ---------------------------------------------------------------- server side

def api(method, path, body=None, user=None, password=None, token=None, opener=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Accept", "application/json")
    if data:
        req.add_header("Content-Type", "application/json")
    if user:
        req.add_header("Authorization", "Basic " +
                       base64.b64encode(f"{user}:{password}".encode()).decode())
    if token and method != "GET":
        req.add_header("X-XSRF-TOKEN", token)
    op = opener or urllib.request.build_opener()
    try:
        with op.open(req, timeout=30) as r:
            t = r.read().decode()
            return r.status, (json.loads(t) if t else None)
    except urllib.error.HTTPError as e:
        t = e.read().decode()
        try:
            return e.code, json.loads(t)
        except Exception:
            return e.code, t[:200]
    except Exception as e:
        return -1, str(e)[:200]


def solve_grid(grid):
    """Plain backtracking. Returns a solved 9x9 or None."""
    g = [row[:] for row in grid]

    def ok(r, c, v):
        for i in range(9):
            if g[r][i] == v or g[i][c] == v:
                return False
        br, bc = (r // 3) * 3, (c // 3) * 3
        for i in range(3):
            for j in range(3):
                if g[br + i][bc + j] == v:
                    return False
        return True

    def go():
        for r in range(9):
            for c in range(9):
                if g[r][c] == 0:
                    for v in range(1, 10):
                        if ok(r, c, v):
                            g[r][c] = v
                            if go():
                                return True
                            g[r][c] = 0
                    return False
        return True

    return g if go() else None


# ---------------------------------------------------------------- browser side

def cell_labels(page):
    return page.eval_on_selector_all(
        '#board [role="gridcell"]', "els => els.map(e => e.getAttribute('aria-label'))")


def read_grid_from_dom(page):
    """The player's view of the grid: 0 for empty, digit otherwise."""
    return page.eval_on_selector_all(
        '#board [role="gridcell"]',
        """els => els.map(e => {
             const t = (e.textContent || '').trim();
             const d = t.match(/^[1-9]/);
             return d ? Number(d[0]) : 0;
           })""")


def givens_from_dom(page):
    return page.eval_on_selector_all(
        '#board [role="gridcell"]',
        "els => els.map(e => e.className.includes('given'))")


def enter_digit(page, index, digit):
    """Click a cell and type a digit — the path a real player takes."""
    page.click(f'#board [role="gridcell"] >> nth={index}')
    page.keyboard.press(str(digit))


def main():
    global BASE
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default=BASE)
    ap.add_argument("--headed", action="store_true")
    ap.add_argument("--timeout-ms", type=int, default=15000)
    args = ap.parse_args()
    BASE = args.base.rstrip("/")

    try:
        from playwright.sync_api import sync_playwright
    except ImportError:
        die("playwright is not installed. Run:\n"
            "  pip install playwright && playwright install chromium\n"
            "The web client is shipped code; it must not go untested because a "
            "dependency was missing.")

    code, _ = api("GET", "/actuator/health")
    if code != 200:
        die(f"the server at {BASE} is not healthy (GET /actuator/health -> {code}).\n"
            f"Start it first:  java -jar server/target/sudokupro-server-*-exec.jar "
            f"--spring.profiles.active=dev")

    ts = str(int(time.time()))[-6:]
    user, password = f"web{ts}", "password123"
    code, _ = api("POST", "/api/auth/register", {"username": user, "password": password})
    if code not in (200, 201, 409):
        die(f"could not create the test account ({code}). If this is 429 the registration "
            f"throttle is saturated — start the server with "
            f"--sudokupro.security.register.max-attempts=500.")

    print("=== SudokuPro WEB CLIENT harness — real browser, real server ===")
    print(f"    target={BASE}  player={user}\n", flush=True)

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=not args.headed)
        page = browser.new_page(viewport={"width": 1280, "height": 900})
        page.set_default_timeout(args.timeout_ms)

        page.on("pageerror", lambda e: console_problems.append(f"pageerror: {e}"))
        page.on("console", lambda m: console_problems.append(f"console.{m.type}: {m.text}")
                if m.type == "error" else None)

        try:
            run_checks(page, user, password)
        finally:
            browser.close()

    # An uncaught exception in a handler is how a broken client actually presents.
    chk("no uncaught page errors or console errors", len(console_problems), {0},
        ("first: " + console_problems[0][:160]) if console_problems else "")

    bad = [r for r in results if not r[0]]
    print(f"\n===== {len(results) - len(bad)}/{len(results)} passed, {len(bad)} FAILED =====")
    for _, n, got, want, note in bad:
        print(f"  FAIL {n}: got {got}, want {want} {note}")
    sys.exit(1 if bad else 0)


def run_checks(page, user, password):
    # ---- login ------------------------------------------------------------
    page.goto(BASE + "/play/", wait_until="domcontentloaded")
    chk("the documented entry point /play/ loads", page.title() != "", True)

    # #server lives in a collapsed <details> and defaults to this origin, which is
    # what the page was loaded from — leave it alone rather than forcing it open.
    page.fill("#user", user)
    page.fill("#pass", password)
    page.click("#btnLogin")
    page.wait_for_selector("#game", state="visible")
    chk("logging in reveals the game view", page.is_visible("#game"), True)
    chk("the HUD shows the signed-in player", page.inner_text("#hudPlayer").strip(), {user})

    # ---- a new game renders a real grid ------------------------------------
    page.select_option("#difficulty", "1")
    page.click("#btnNew")
    page.wait_for_selector('#board [role="gridcell"]')
    page.wait_for_function(
        "() => document.querySelectorAll('#board [role=gridcell]').length === 81")

    cells = page.query_selector_all('#board [role="gridcell"]')
    chk("the board renders 81 cells", len(cells), {81})

    grid = read_grid_from_dom(page)
    givens = givens_from_dom(page)
    filled = sum(1 for v in grid if v)
    chk("a fresh puzzle has some empty cells", 0 < filled < 81, True, f"{filled}/81 filled")
    chk("every filled cell on a fresh board is a given",
        all(givens[i] for i, v in enumerate(grid) if v), True,
        "a non-given prefilled cell would mean the solution leaked into the client")

    # ---- pencil marks survive a server resync ------------------------------
    # The defect class: notes live only in the client, and a resync replaces the
    # board wholesale. If notes were keyed to the object being replaced they would
    # vanish on every sync — silently destroying the player's deductions.
    empty_idx = next(i for i, v in enumerate(grid) if v == 0)
    page.click(f'#board [role="gridcell"] >> nth={empty_idx}')
    page.keyboard.press("n")                       # notes mode
    page.keyboard.press("7")
    page.wait_for_timeout(150)
    label_before = cell_labels(page)[empty_idx]
    noted = "notes" in (label_before or "")
    chk("a pencil mark is recorded on the cell", noted, True, f"label={label_before!r}")

    page.keyboard.press("n")                       # back to normal entry

    # Drop the connection and restore it. That is the client's real resync path —
    # the socket dies, the retry loop reconnects and calls requestSync(), and the
    # server's reply replaces the whole board object. Poking an internal function
    # would not prove the same thing, because the bug being guarded against is
    # precisely that the replacement discards state hanging off the old board.
    page.context.set_offline(True)
    page.wait_for_timeout(500)
    page.context.set_offline(False)

    resynced = False
    for _ in range(40):
        if page.eval_on_selector("#hudConn", "e => (e.textContent || '').toLowerCase()") \
                .find("live") >= 0:
            resynced = True
            break
        page.wait_for_timeout(250)
    chk("the client reconnects after the socket drops", resynced, True,
        "a client that cannot recover a dropped socket silently stops receiving moves")

    page.wait_for_timeout(400)
    label_after = cell_labels(page)[empty_idx]
    chk("pencil marks survive a resync", "notes" in (label_after or ""), True,
        f"label={label_after!r}")

    # ---- play the puzzle to completion through the UI ----------------------
    grid2d = [grid[r * 9:(r + 1) * 9] for r in range(9)]
    solution = solve_grid(grid2d)
    if solution is None:
        chk("the rendered puzzle is solvable", False, True,
            "backtracking found no solution for the grid the client displayed")
        return
    chk("the rendered puzzle is solvable", True, True)

    to_enter = [(i, solution[i // 9][i % 9]) for i, v in enumerate(grid) if v == 0]
    print(f"  … playing {len(to_enter)} cells through the UI", flush=True)
    for idx, digit in to_enter:
        enter_digit(page, idx, digit)

    # The solved state arrives over the WebSocket (gameEnd -> resync -> solved),
    # so give the round trip a moment rather than asserting instantly.
    solved = False
    for _ in range(40):
        if page.eval_on_selector("#boardWrap", "e => e.classList.contains('solved')"):
            solved = True
            break
        page.wait_for_timeout(250)

    # Deliberately two checks, because they fail independently and a single one
    # would overstate what it proves. The board reaching a solved state only shows
    # that the server round trip happened — `case 'board'` toggles that class before
    # it calls onSolved(), so it stays true even with the whole victory path dead
    # (confirmed by mutation). The player-facing signals below are what actually
    # break when the win goes unannounced.
    chk("the client's board reaches the solved state", solved, True,
        "the last move must round-trip and come back marked solved")

    announced = page.inner_text("#srAnnounce")
    stamped = page.eval_on_selector("#boardWrap", "e => e.dataset.time || ''")
    chk("the player is told they won", "solved" in announced.lower(), True,
        f"announce={announced!r} — a finished puzzle that says nothing is the defect "
        f"this guards: the win is announced to assistive tech and shown as a toast")
    chk("the solve time is shown", bool(stamped), True,
        f"time={stamped!r} — the one number a puzzle player wants, previously visible "
        f"only in a running timer that the next New game reset")

    grid_final = read_grid_from_dom(page)
    chk("every cell is filled after the last move", sum(1 for v in grid_final if v), {81})


if __name__ == "__main__":
    main()
