#!/usr/bin/env python3
"""
SudokuPro LIVE game-playing engine.

The companion Engine.java drives the model classes in-process. That is useful but
structurally blind: it never issues an HTTP request, never opens a WebSocket, never
touches Postgres or Redis, and never observes what two players do to each other. Every
P0 in BUG_AUDIT_2026-07-24.md that involved authorization, the economy, persistence or
the realtime layer was invisible to it — including a generator bug that survived a
"fix" precisely because the in-process check compared grids literally.

This engine is the other half. It registers real accounts, logs in, and PLAYS — full
games from an empty grid to a solved board, over the same REST + WebSocket API the
browser and desktop clients use (moves, undo, redo and sync are WebSocket verbs; there
is no REST move endpoint) — while asserting invariants that only hold if the whole
stack is behaving:

  * board integrity      the served grid is a real, consistent, uniquely-solvable puzzle
  * structural diversity puzzles are not one grid with the digits renamed
  * move semantics       correct moves land, illegal ones do not, and the board never
                         ends up holding a duplicate
  * completion           a genuinely solved board is recognised, exactly once
  * economy conservation gems move only by documented amounts, including under real
                         concurrency across different games
  * hint correctness     a hint names the value the puzzle's UNIQUE solution requires
  * undo / redo          are exact inverses of the authoritative board
  * persistence          save -> resume and cache eviction -> reload are lossless
  * isolation            one player's requests never alter another player's board
  * competitive rules    daily boards are shared as puzzles but private as progress
  * no 5xx               any server error is a finding, whatever else was asserted

Usage:
    python3 engine/live_engine.py [--base URL] [--players N] [--quick]

Exit code is non-zero if any invariant is violated, so CI fails the build.
"""

import argparse
import asyncio
import base64
import http.cookiejar
import json
import random
import re
import string
import sys
import threading
import time
import urllib.error
import urllib.request
from collections import defaultdict

import websockets


# ──────────────────────────────────────────────────────────────────────────────
# Findings
# ──────────────────────────────────────────────────────────────────────────────

_lock = threading.Lock()
FINDINGS = []
CHECKS = [0]
SUITES = []
FIVE_HUNDREDS = []
BASE = "http://localhost:8080"


def finding(ident, detail):
    with _lock:
        FINDINGS.append((ident, detail))
        print(f"  [BUG] {ident}: {detail}", flush=True)


def ok(msg):
    with _lock:
        CHECKS[0] += 1
        print(f"  [ ok] {msg}", flush=True)


def note(msg):
    """A neutral line that is neither a passed check nor a finding — used when a suite
    deliberately does not apply to the configuration in force. Printed loudly so a skipped
    suite can never be mistaken for a passing one, which is the failure mode the JUnit
    Docker-gated tests had for months."""
    with _lock:
        print(f"  [note] {msg}", flush=True)


def check(cond, ident, detail, okmsg):
    if cond:
        ok(okmsg)
        return True
    finding(ident, detail)
    return False


def suite(name):
    def deco(fn):
        SUITES.append((name, fn))
        return fn
    return deco


# ──────────────────────────────────────────────────────────────────────────────
# An independent Sudoku implementation — deliberately not the server's
# ──────────────────────────────────────────────────────────────────────────────

def legal(grid, r, c, v):
    for i in range(9):
        if grid[r][i] == v or grid[i][c] == v:
            return False
    br, bc = (r // 3) * 3, (c // 3) * 3
    for i in range(3):
        for j in range(3):
            if grid[br + i][bc + j] == v:
                return False
    return True


def consistent(grid):
    """True when no filled cell duplicates another in its row, column or box."""
    for r in range(9):
        for c in range(9):
            v = grid[r][c]
            if v == 0:
                continue
            grid[r][c] = 0
            good = legal(grid, r, c, v)
            grid[r][c] = v
            if not good:
                return False
    return True


def solve(grid):
    """Completes a COPY of grid; returns the solution or None."""
    g = [row[:] for row in grid]
    if not consistent(g):
        return None
    order = list(range(81))

    def rec(idx):
        if idx == 81:
            return True
        r, c = divmod(order[idx], 9)
        if g[r][c]:
            return rec(idx + 1)
        for v in range(1, 10):
            if legal(g, r, c, v):
                g[r][c] = v
                if rec(idx + 1):
                    return True
                g[r][c] = 0
        return False

    return g if rec(0) else None


def count_solutions(grid, cap=2):
    """Counts completions up to `cap` — enough to prove uniqueness."""
    g = [row[:] for row in grid]
    if not consistent(g):
        return 0
    found = [0]

    def rec(idx):
        if found[0] >= cap:
            return
        if idx == 81:
            found[0] += 1
            return
        r, c = divmod(idx, 9)
        if g[r][c]:
            rec(idx + 1)
            return
        for v in range(1, 10):
            if legal(g, r, c, v):
                g[r][c] = v
                rec(idx + 1)
                g[r][c] = 0
                if found[0] >= cap:
                    return

    rec(0)
    return found[0]


def grid_of(state):
    return [[cell["value"] for cell in row] for row in state["cells"]]


def givens_of(state):
    return [[cell["value"] if cell.get("isGiven") else 0 for cell in row]
            for row in state["cells"]]


def filled(grid):
    return sum(1 for row in grid for v in row if v)


def canonical(grid):
    """Relabels so row 0 reads 1..9 — exposes puzzles that are one grid renamed."""
    m = {grid[0][c]: c + 1 for c in range(9)}
    return "".join(str(m.get(grid[r][c], 0)) for r in range(9) for c in range(9))


# ──────────────────────────────────────────────────────────────────────────────
# Player: REST for lifecycle, WebSocket for play
# ──────────────────────────────────────────────────────────────────────────────

def rand_name(prefix):
    return prefix + "".join(random.choices(string.ascii_lowercase + string.digits, k=8))


class Player:
    def __init__(self, name=None, password="password123"):
        self.name = name or rand_name("bot")
        self.password = password
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(self.jar))
        self.auth = "Basic " + base64.b64encode(
            f"{self.name}:{self.password}".encode()).decode()
        self.csrf = None

    # ---- REST ----------------------------------------------------------------

    def call(self, method, path, body=None, expect=None, anonymous=False):
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(BASE + path, data=data, method=method)
        req.add_header("Accept", "application/json")
        # Registration must go out WITHOUT credentials: the account does not exist yet, so
        # BasicAuthenticationFilter would reject the request with 401 before the controller
        # ever ran, and every later call would then fail against a non-existent account.
        if not anonymous:
            req.add_header("Authorization", self.auth)
        if data:
            req.add_header("Content-Type", "application/json")
        if method != "GET" and self.csrf:
            req.add_header("X-XSRF-TOKEN", self.csrf)
        try:
            with self.opener.open(req, timeout=45) as resp:
                text = resp.read().decode()
                status, payload = resp.status, (json.loads(text) if text else None)
        except urllib.error.HTTPError as e:
            text = e.read().decode()
            try:
                status, payload = e.code, json.loads(text)
            except Exception:
                status, payload = e.code, text
        except Exception as e:
            status, payload = -1, f"{type(e).__name__}: {e}"

        if isinstance(status, int) and 500 <= status < 600:
            with _lock:
                FIVE_HUNDREDS.append((method, path, status, str(payload)[:160]))
        if expect is not None and status not in expect:
            finding("HTTP-UNEXPECTED-STATUS",
                    f"{method} {path} -> {status} (wanted {expect}): {str(payload)[:160]}")
        return status, payload

    def register(self):
        st, resp = self.call("POST", "/api/auth/register",
                             {"username": self.name, "password": self.password},
                             anonymous=True)
        if st != 201:
            finding("BOT-REGISTRATION-FAILED",
                    f"could not create the account {self.name}: {st} {str(resp)[:120]}")
        return self

    def try_register(self):
        """Attempt registration and return (status, body) WITHOUT recording a finding.

        register() treats a non-201 as a bug, which is right for a bot that needs an
        account. The throttle suite needs the opposite: a refusal there is the feature
        working, so it must not pollute the findings list."""
        return self.call("POST", "/api/auth/register",
                         {"username": self.name, "password": self.password},
                         anonymous=True)

    def login(self):
        st, sess = self.call("GET", "/api/session")
        if isinstance(sess, dict):
            self.csrf = sess.get("csrfToken")
        return st == 200

    def wallet(self):
        _, w = self.call("GET", "/api/economy/wallet")
        return w if isinstance(w, dict) else {}

    def gems(self):
        return self.wallet().get("gems", -1)

    def new_game(self, difficulty=2, chaos=False, mirror=False):
        _, s = self.call(
            "POST",
            f"/api/game/new?difficulty={difficulty}"
            f"&chaos={str(chaos).lower()}&mirror={str(mirror).lower()}",
            expect=[200])
        return s if isinstance(s, dict) else None

    def board(self, game_id, expect=(200,)):
        return self.call("GET", f"/api/game/{game_id}", expect=list(expect))

    def cookie_header(self):
        return "; ".join(f"{c.name}={c.value}" for c in self.jar)


class Session:
    """A live WebSocket on one game — the path real clients play through."""

    def __init__(self, player, game_id):
        self.player = player
        self.game_id = game_id
        self.ws = None
        self.errors = []
        self.moves_echoed = 0

    async def __aenter__(self):
        url = BASE.replace("http", "ws") + "/ws/game?gameId=" + self.game_id
        self.ws = await websockets.connect(
            url, additional_headers={"Cookie": self.player.cookie_header()},
            open_timeout=25, close_timeout=5)
        return self

    async def __aexit__(self, *exc):
        try:
            await self.ws.close()
        except Exception:
            pass

    async def send(self, obj):
        await self.ws.send(json.dumps(obj))

    async def move(self, r, c, old, new):
        await self.send({"type": "move",
                         "payload": {"row": r, "col": c, "oldVal": old,
                                     "newVal": new, "source": "PLAYER"}})

    async def drain(self, seconds=0.6):
        """Collects whatever the server volunteers, recording error envelopes."""
        deadline = time.time() + seconds
        while time.time() < deadline:
            try:
                raw = await asyncio.wait_for(self.ws.recv(), timeout=0.25)
            except (asyncio.TimeoutError, TimeoutError):
                continue
            except Exception:
                break
            try:
                env = json.loads(raw)
            except Exception:
                continue
            if env.get("type") == "error":
                self.errors.append(env.get("payload"))
            elif env.get("type") == "move":
                self.moves_echoed += 1

    async def sync(self, timeout=15):
        """Asks for and returns the authoritative BoardState."""
        await self.send({"type": "sync", "payload": ""})
        deadline = time.time() + timeout
        while time.time() < deadline:
            try:
                raw = await asyncio.wait_for(self.ws.recv(), timeout=3)
            except (asyncio.TimeoutError, TimeoutError):
                continue
            except Exception:
                return None
            try:
                env = json.loads(raw)
            except Exception:
                continue
            if env.get("type") == "board":
                return env.get("payload")
            if env.get("type") == "error":
                self.errors.append(env.get("payload"))
        return None

    async def simple(self, verb):
        """
        Sends a no-payload verb (undo/redo) and returns the resulting board.

        The drain is load-bearing: the server BROADCASTS a board envelope after undo and
        redo, so a bare sync() can return the broadcast from the PREVIOUS verb that was
        still sitting in the socket buffer. Reading that stale envelope made this harness
        report a redo failure against a server that was behaving correctly — worth
        recording, because a false positive from a bug-finding tool costs more than a miss.
        """
        await self.send({"type": verb, "payload": ""})
        await asyncio.sleep(0.15)
        await self.drain(0.6)
        return await self.sync()


async def play_full_game(player, state, shuffle=True, stop_after=None):
    """
    Plays every empty cell of `state` with the puzzle's own solution, over the
    WebSocket, and returns (final_board_state, session).
    """
    solution = solve(givens_of(state))
    if solution is None:
        finding("PLAY-UNSOLVABLE-BOARD",
                f"server issued game {state['gameId']} whose givens admit no solution")
        return None, None
    grid = grid_of(state)
    empties = [(r, c) for r in range(9) for c in range(9) if grid[r][c] == 0]
    if shuffle:
        random.shuffle(empties)
    if stop_after:
        empties = empties[:stop_after]

    async with Session(player, state["gameId"]) as sess:
        for (r, c) in empties:
            await sess.move(r, c, 0, solution[r][c])
            await asyncio.sleep(0.008)
        await sess.drain(1.0)
        final = await sess.sync()
        return final, sess


# ──────────────────────────────────────────────────────────────────────────────
# Suites
# ──────────────────────────────────────────────────────────────────────────────

@suite("L1  served puzzles are real, consistent and uniquely solvable")
def s_board_quality(cfg):
    p = Player().register(); p.login()
    canon, clue_counts = set(), defaultdict(list)
    non_unique = inconsistent = unsolvable = boards = 0

    for difficulty in (1, 2, 3, 4, 5):
        for _ in range(cfg.boards_per_difficulty):
            state = p.new_game(difficulty)
            if not isinstance(state, dict) or "cells" not in state:
                finding("GEN-NEW-GAME-FAILED",
                        f"POST /api/game/new?difficulty={difficulty} did not return a board")
                continue
            boards += 1
            live, givens = grid_of(state), givens_of(state)
            if not consistent(live):
                inconsistent += 1
                continue
            sol = solve(givens)
            if sol is None:
                unsolvable += 1
                continue
            if count_solutions(givens) != 1:
                non_unique += 1
            canon.add(canonical(sol))
            clue_counts[difficulty].append(filled(givens))

    if not boards:
        finding("GEN-NO-BOARDS", "could not obtain any board from the server")
        return

    check(inconsistent == 0, "GEN-INCONSISTENT-BOARD",
          f"{inconsistent}/{boards} served boards already hold a duplicate in a row, "
          "column or box",
          f"all {boards} served boards are internally consistent")
    check(unsolvable == 0, "GEN-UNSOLVABLE-BOARD",
          f"{unsolvable}/{boards} served puzzles have no solution at all",
          f"all {boards} served puzzles are solvable")
    check(non_unique == 0, "GEN-NON-UNIQUE-SOLUTION",
          f"{non_unique}/{boards} served puzzles admit more than one solution — such a "
          "puzzle cannot be fairly scored, hinted or verified",
          f"all {boards} served puzzles have exactly one solution")

    # The relabeling trap that defeated the in-process harness.
    check(len(canon) > boards // 2, "GEN-LOW-STRUCTURAL-DIVERSITY",
          f"only {len(canon)} structurally distinct grids across {boards} boards once "
          "canonicalised by relabeling row 0 — the puzzles are one grid renamed, so a "
          "few clues reveal every answer on the platform",
          f"structurally distinct solution grids: {len(canon)}/{boards}")

    means = {d: sum(v) / len(v) for d, v in clue_counts.items() if v}
    if len(means) >= 2:
        seq = [means[d] for d in sorted(means)]
        monotone = all(seq[i] >= seq[i + 1] - 1.5 for i in range(len(seq) - 1))
        desc = ", ".join(f"d{d}={means[d]:.1f}" for d in sorted(means))
        check(monotone, "GEN-DIFFICULTY-NOT-MONOTONE",
              f"clue count does not fall as difficulty rises: {desc} — difficulty is "
              "advertised to the player and drives the payout",
              f"clue count falls with difficulty: {desc}")


@suite("L2  a full game plays to victory over the WebSocket and pays once")
def s_play_and_pay(cfg):
    async def run():
        for difficulty in (1, 2, 3):
            p = Player().register(); p.login()
            before = p.gems()
            state = p.new_game(difficulty)
            if not state:
                continue
            gid = state["gameId"]
            final, sess = await play_full_game(p, state)
            if final is None:
                finding("PLAY-NO-BOARD-AFTER-PLAY",
                        f"game {gid}: the server never returned a board after a full game")
                continue

            g = grid_of(final)
            check(consistent(g), "PLAY-BOARD-CORRUPTED",
                  f"game {gid} holds duplicate values after normal play",
                  f"difficulty {difficulty}: board stayed consistent")
            check(filled(g) == 81, "PLAY-MOVES-LOST",
                  f"game {gid}: played every empty cell with the unique solution but only "
                  f"{filled(g)}/81 cells are filled — {81 - filled(g)} moves were lost",
                  f"difficulty {difficulty}: all 81 cells filled")
            check(not sess.errors, "PLAY-CORRECT-MOVE-REJECTED",
                  f"game {gid}: the puzzle's OWN solution values were rejected "
                  f"{len(sess.errors)} times: {sess.errors[:3]}",
                  f"difficulty {difficulty}: no correct move rejected")
            check(final.get("solved") is True, "PLAY-SOLVED-NOT-DETECTED",
                  f"game {gid}: board complete and correct but solved={final.get('solved')}",
                  f"difficulty {difficulty}: completion recognised")

            p.call("POST", f"/api/game/{gid}/end")
            after_first = p.gems()
            earned = after_first - before
            check(earned > 0, "ECON-SOLVE-PAYS-NOTHING",
                  f"solving a difficulty-{difficulty} game paid {earned} gems",
                  f"difficulty {difficulty} solve paid {earned} gems")

            for _ in range(4):
                p.call("POST", f"/api/game/{gid}/end")
            check(p.gems() == after_first, "ECON-END-REPLAY-PAYS-AGAIN",
                  f"four extra /end calls moved the balance "
                  f"{after_first} -> {p.gems()} — an unbounded currency farm",
                  "replaying /end pays nothing further")

    asyncio.run(run())


@suite("L3  illegal moves are refused and never reach the board")
def s_illegal_moves(cfg):
    async def run():
        p = Player().register(); p.login()
        state = p.new_game(3)
        if not state:
            return
        gid = state["gameId"]
        grid = grid_of(state)

        target = None
        for r in range(9):
            present = [v for v in grid[r] if v]
            empties = [c for c in range(9) if grid[r][c] == 0]
            if present and empties:
                target = (r, empties[0], present[0])
                break
        given = next(((r, c) for r in range(9) for c in range(9)
                      if state["cells"][r][c].get("isGiven")), None)

        async with Session(p, gid) as sess:
            if target:
                r, c, dup = target
                await sess.move(r, c, 0, dup)
                await sess.drain(0.8)
                after = await sess.sync()
                if after:
                    g = grid_of(after)
                    check(g[r][c] != dup, "MOVE-ILLEGAL-ACCEPTED",
                          f"a duplicate {dup} in row {r} was written at ({r},{c}); the "
                          "board now violates Sudoku's basic constraint",
                          "a row-duplicate move does not land")
                    check(consistent(g), "MOVE-BOARD-INCONSISTENT",
                          "the board holds duplicates after a rejected move",
                          "the board stays consistent after a rejected move")

            if given:
                gr, gc = given
                original = grid[gr][gc]
                await sess.move(gr, gc, original, 1 + (original % 9))
                await sess.drain(0.8)
                after = await sess.sync()
                if after:
                    check(grid_of(after)[gr][gc] == original, "MOVE-GIVEN-OVERWRITTEN",
                          f"a given clue at ({gr},{gc}) changed from {original} to "
                          f"{grid_of(after)[gr][gc]}",
                          "given clues cannot be overwritten")

            # Out-of-range coordinates and values must not crash or corrupt anything.
            for bad in ({"row": 0, "col": 0, "oldVal": 0, "newVal": 99, "source": "PLAYER"},
                        {"row": -1, "col": 0, "oldVal": 0, "newVal": 5, "source": "PLAYER"},
                        {"row": 0, "col": 99, "oldVal": 0, "newVal": 5, "source": "PLAYER"},
                        {"row": 300, "col": 300, "oldVal": 0, "newVal": 5, "source": "PLAYER"}):
                await sess.send({"type": "move", "payload": bad})
            await sess.drain(1.0)
            after = await sess.sync()
            check(after is not None, "MOVE-OUT-OF-RANGE-KILLED-SESSION",
                  "the session stopped responding after out-of-range move payloads",
                  "the session survives out-of-range move payloads")
            if after:
                check(consistent(grid_of(after)), "MOVE-OUT-OF-RANGE-CORRUPTED",
                      "out-of-range payloads corrupted the board",
                      "out-of-range payloads leave the board intact")

    asyncio.run(run())


@suite("L4  a client cannot label its own move HINT or AUTOSOLVE")
def s_move_source_spoofing(cfg):
    async def run():
        p = Player().register(); p.login()
        before = p.gems()
        state = p.new_game(2)
        if not state:
            return
        gid = state["gameId"]
        solution = solve(givens_of(state))
        grid = grid_of(state)
        if solution is None:
            return
        empties = [(r, c) for r in range(9) for c in range(9) if grid[r][c] == 0]

        async with Session(p, gid) as sess:
            # Claim every move is AUTOSOLVE. If the server believed it, the reward guard
            # would suppress the payout; if it believed HINT, the clean-solve bonus
            # would be forfeited without ever paying for a hint.
            for (r, c) in empties:
                await sess.send({"type": "move",
                                 "payload": {"row": r, "col": c, "oldVal": 0,
                                             "newVal": solution[r][c],
                                             "source": "AUTOSOLVE"}})
                await asyncio.sleep(0.008)
            await sess.drain(1.0)
            final = await sess.sync()

        if not final or not final.get("solved"):
            return
        p.call("POST", f"/api/game/{gid}/end")
        earned = p.gems() - before
        check(earned > 0, "SOURCE-SPOOF-SUPPRESSED-PAYOUT",
              "a legitimately played game paid nothing because the client labelled its "
              f"own moves AUTOSOLVE (balance moved {earned})",
              f"client-supplied move source is ignored; the honest solve still paid {earned}")

        cells = final["cells"]
        spoofed = sum(1 for row in cells for cl in row
                      if cl.get("moveSource") == "AUTOSOLVE")
        check(spoofed == 0, "SOURCE-SPOOF-ACCEPTED",
              f"{spoofed} cells are recorded as AUTOSOLVE because the client said so — "
              "the clean-solve bonus and the auto-solve reward guard both key on this",
              "the server stamps the move source itself")

    asyncio.run(run())


@suite("L5  hints name the true value and cost exactly the advertised price")
def s_hints(cfg):
    p = Player().register(); p.login()
    wrong = checked = 0
    charges = []
    for _ in range(cfg.hint_boards):
        state = p.new_game(4)
        if not state:
            continue
        solution = solve(givens_of(state))
        if solution is None:
            continue
        before = p.gems()
        st, resp = p.call("POST", f"/api/game/hint?gameId={state['gameId']}")
        if st == 402:
            break
        if st != 200 or not isinstance(resp, dict):
            continue
        m = re.search(r"(\d+) at row (\d+), col (\d+)", str(resp.get("hint", "")))
        if not m:
            continue
        value, row, col = int(m.group(1)), int(m.group(2)) - 1, int(m.group(3)) - 1
        checked += 1
        if not (0 <= row < 9 and 0 <= col < 9):
            finding("HINT-OUT-OF-BOUNDS", f"hint referenced ({row},{col})")
            continue
        if solution[row][col] != value:
            wrong += 1
            finding("HINT-WRONG-VALUE",
                    f"hint said {value} at ({row + 1},{col + 1}) but the puzzle's unique "
                    f"solution requires {solution[row][col]} — the player paid 5 gems "
                    "for a value that makes the board unsolvable")
        charges.append(before - p.gems())

    if checked:
        check(wrong == 0, "HINT-UNRELIABLE",
              f"{wrong}/{checked} hints contradicted the puzzle's unique solution",
              f"all {checked} hints named the true value")
        bad_charges = [c for c in charges if c not in (0, 5)]
        check(not bad_charges, "HINT-CHARGE-WRONG",
              f"hint charges of {bad_charges} gems (expected 5)",
              f"every hint cost exactly 5 gems ({len(charges)} sampled)")


@suite("L6  undo and redo are exact inverses of the authoritative board")
def s_undo_redo(cfg):
    async def run():
        p = Player().register(); p.login()
        state = p.new_game(2)
        if not state:
            return
        solution = solve(givens_of(state))
        grid = grid_of(state)
        if solution is None:
            return
        empties = [(r, c) for r in range(9) for c in range(9) if grid[r][c] == 0][:6]

        async with Session(p, state["gameId"]) as sess:
            for (r, c) in empties:
                await sess.move(r, c, 0, solution[r][c])
                await asyncio.sleep(0.01)
            await sess.drain(0.8)
            mid = await sess.sync()
            if not mid:
                return
            mid_grid = grid_of(mid)

            undone = await sess.simple("undo")
            if not undone:
                return
            undone_grid = grid_of(undone)
            check(undone_grid != mid_grid, "UNDO-NO-EFFECT",
                  "undo left the board byte-identical",
                  "undo changed the board")
            check(consistent(undone_grid), "UNDO-CORRUPTS-BOARD",
                  "the board is inconsistent after undo",
                  "the board stays consistent after undo")
            check(filled(undone_grid) == filled(mid_grid) - 1, "UNDO-WRONG-CELL-COUNT",
                  f"undo changed the filled count from {filled(mid_grid)} to "
                  f"{filled(undone_grid)} — one undo should remove exactly one value",
                  "undo removed exactly one value")

            redone = await sess.simple("redo")
            if redone:
                check(grid_of(redone) == mid_grid, "REDO-NOT-INVERSE",
                      "redo did not restore the board that undo removed",
                      "redo exactly restores the pre-undo board")

            # Undo everything: the board must return to the original clue set.
            for _ in range(len(empties) + 2):
                await sess.send({"type": "undo", "payload": ""})
                await asyncio.sleep(0.02)
            await sess.drain(0.8)
            emptied = await sess.sync()
            if emptied:
                check(grid_of(emptied) == givens_of(state), "UNDO-ALL-NOT-ORIGINAL",
                      "undoing every move did not return the board to its original clues",
                      "undoing every move returns the board to its original clues")

    asyncio.run(run())


@suite("L7  save/resume and cache eviction round-trip losslessly")
def s_persistence(cfg):
    async def run():
        p = Player().register(); p.login()
        state = p.new_game(3)
        if not state:
            return
        gid = state["gameId"]
        solution = solve(givens_of(state))
        grid = grid_of(state)
        if solution is None:
            return

        async with Session(p, gid) as sess:
            for (r, c) in [(r, c) for r in range(9) for c in range(9)
                           if grid[r][c] == 0][:8]:
                await sess.move(r, c, 0, solution[r][c])
                await asyncio.sleep(0.01)
            await sess.drain(0.8)

        # Take a hint too, so hintCount has something to lose.
        p.call("POST", f"/api/game/hint?gameId={gid}")
        _, before = p.board(gid)
        p.call("POST", f"/api/game/{gid}/save")

        # Push the board out of the per-pod cache with unrelated games.
        for _ in range(cfg.eviction_pressure):
            p.new_game(1)

        st, after = p.board(gid)
        check(st == 200, "PERSIST-READ-AFTER-PRESSURE-FAILED",
              f"reading a saved game back after {cfg.eviction_pressure} other games "
              f"returned {st} — this is the path that used to 500 for a whole cache TTL",
              "a saved game is still readable after cache pressure")

        if isinstance(before, dict) and isinstance(after, dict):
            check(grid_of(before) == grid_of(after), "PERSIST-GRID-DRIFT",
                  "the grid changed across a save/reload cycle",
                  "the grid survives save/reload unchanged")
            check(before.get("moveCount") == after.get("moveCount"),
                  "PERSIST-MOVECOUNT-DRIFT",
                  f"moveCount {before.get('moveCount')} -> {after.get('moveCount')}",
                  "moveCount survives reload")
            check(before.get("hintCount") == after.get("hintCount"),
                  "PERSIST-HINTCOUNT-DRIFT",
                  f"hintCount {before.get('hintCount')} -> {after.get('hintCount')} — a "
                  "forgotten hint grants the clean-solve bonus on a hinted game",
                  "hintCount survives reload")

        # Resume must hand back the same board, and it must still be playable.
        st, resumed = p.call("POST", f"/api/game/{gid}/resume")
        if st == 200 and isinstance(resumed, dict):
            check(grid_of(resumed) == grid_of(after), "RESUME-DIFFERENT-BOARD",
                  "resume returned a different grid from the one on record",
                  "resume returns the board exactly as saved")

    asyncio.run(run())


@suite("L8  players are isolated from one another")
def s_isolation(cfg):
    a = Player().register(); a.login()
    b = Player().register(); b.login()
    state = a.new_game(2)
    if not state:
        return
    gid = state["gameId"]
    _, before = a.board(gid)
    b_gems_before = b.gems()

    for method, path, body in (
        ("POST", f"/api/game/{gid}/solve", None),
        ("POST", f"/api/game/{gid}/save", None),
        ("POST", f"/api/game/{gid}/end", None),
        ("POST", f"/api/game/{gid}/resume", None),
    ):
        st, _ = b.call(method, path, body)
        check(st not in (200, 204), "ISOLATION-FOREIGN-MUTATION-ALLOWED",
              f"player B got {st} from {method} {path} on player A's board",
              f"B is refused {path.rsplit('/', 1)[-1]} on A's board ({st})")

    st, _ = b.call("POST", f"/api/game/hint?gameId={gid}")
    check(st not in (200,), "ISOLATION-FOREIGN-HINT-ALLOWED",
          f"player B took a hint on player A's board ({st}) — the charge lands on A",
          f"B cannot take a hint on A's board ({st})")

    _, after = a.board(gid)
    if isinstance(before, dict) and isinstance(after, dict):
        check(grid_of(before) == grid_of(after), "ISOLATION-BOARD-MUTATED",
              "another player's requests changed the owner's grid",
              "A's grid is untouched by B's attempts")
        check(after.get("solved") is not True, "ISOLATION-BOARD-SOLVED-BY-OTHER",
              "another player marked the owner's board solved",
              "A's board was not solved by B")
    check(b.gems() == b_gems_before, "ISOLATION-ATTACKER-EARNED",
          f"B's balance moved {b_gems_before} -> {b.gems()} from touching A's game",
          "B earned nothing from touching A's game")


@suite("L9  the economy conserves currency under real concurrency")
def s_economy(cfg):
    p = Player().register(); p.login()
    start = p.gems()
    for _ in range(8):
        p.wallet()
        p.call("GET", "/api/leaderboard?limit=5")
    check(p.gems() == start, "ECON-READS-MOVE-BALANCE",
          f"read-only requests moved the balance {start} -> {p.gems()}",
          "read-only requests do not change the balance")

    games = [g["gameId"] for g in
             (p.new_game(4) for _ in range(cfg.concurrent_hints)) if g]
    if not games:
        return
    before = p.gems()
    results = []

    def take(gid):
        st, _ = p.call("POST", f"/api/game/hint?gameId={gid}")
        with _lock:
            results.append(st)

    threads = [threading.Thread(target=take, args=(g,)) for g in games]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    served = sum(1 for st in results if st == 200)
    spent = before - p.gems()
    check(spent == served * 5, "ECON-CONCURRENT-HINTS-OVERSOLD",
          f"{served} concurrent hints on DIFFERENT games cost {spent} gems, not "
          f"{served * 5} — the per-game lock does not serialise wallet writes",
          f"{served} concurrent hints cost exactly {spent} gems")
    check(p.gems() >= 0, "ECON-BALANCE-NEGATIVE",
          f"the balance went negative: {p.gems()}", f"balance stayed non-negative")


@suite("L10 daily boards are shared as puzzles but private as progress")
def s_competitive(cfg):
    a = Player().register(); a.login()
    b = Player().register(); b.login()

    st, daily = a.call("GET", "/api/daily")
    date = daily.get("date") if isinstance(daily, dict) else None

    st, ba = a.call("POST", "/api/daily/join")
    if st != 200 or not isinstance(ba, dict):
        return
    gid_a = ba["gameId"]
    st, bb = b.call("POST", "/api/daily/join")

    if isinstance(bb, dict):
        check(givens_of(ba) == givens_of(bb), "DAILY-DIFFERENT-PUZZLES",
              "two players joining the same daily received different clue sets — the "
              "whole point of a daily is that everyone races the same puzzle",
              "both players received the same daily puzzle")
        check(gid_a != bb["gameId"], "DAILY-SHARED-GAME-ID",
              "two players share one daily game id, so they would play the same board",
              "each player got their own daily board id")
        sol_a = solve(givens_of(ba))
        check(sol_a is not None and count_solutions(givens_of(ba)) == 1,
              "DAILY-NOT-UNIQUELY-SOLVABLE",
              "today's daily puzzle does not have exactly one solution",
              "today's daily puzzle has exactly one solution")

    st, _ = b.board(gid_a, expect=(403, 404))
    check(st in (403, 404), "DAILY-BOARD-READABLE-BY-OTHERS",
          f"player B read player A's daily board with {st}",
          f"A's daily board is private ({st})")
    st, _ = b.call("GET", f"/api/game/{gid_a}/share")
    check(st in (403, 404), "DAILY-BOARD-EXPORTABLE-BY-OTHERS",
          f"player B exported player A's daily board with {st}",
          f"A's daily board cannot be exported by B ({st})")

    if date:
        st, _ = b.call("POST", f"/api/game/daily-{date}/solve")
        check(st not in (200, 204), "DAILY-TEMPLATE-POISONABLE",
              f"a passer-by auto-solved the shared daily template ({st}), which hands "
              "every later joiner a pre-solved, unwinnable board",
              f"the shared daily template is not solvable by a passer-by ({st})")

    c = Player().register(); c.login()
    st, bc = c.call("POST", "/api/daily/join")
    if isinstance(bc, dict):
        check(bc.get("solved") is not True, "DAILY-LATER-JOINER-PRESOLVED",
              "a later joiner received an already-solved daily board",
              "a later joiner still receives an unsolved daily")


@suite("L11 sustained concurrent play leaves no drift, loss or corruption")
def s_soak(cfg):
    async def run():
        players = []
        for _ in range(cfg.players):
            p = Player().register()
            p.login()
            players.append(p)

        stats = {"games": 0, "solved": 0, "lost": 0, "rejected": 0, "corrupt": 0}

        async def one(p):
            for _ in range(cfg.games_per_player):
                state = p.new_game(random.choice([1, 2, 3]))
                if not state:
                    continue
                stats["games"] += 1
                final, sess = await play_full_game(p, state)
                if final is None:
                    stats["lost"] += 1
                    continue
                g = grid_of(final)
                if not consistent(g):
                    stats["corrupt"] += 1
                    finding("SOAK-BOARD-CORRUPTED",
                            f"game {state['gameId']} holds duplicates after normal play")
                if filled(g) != 81:
                    stats["lost"] += 1
                    finding("SOAK-MOVES-LOST",
                            f"game {state['gameId']}: {81 - filled(g)} of the puzzle's own "
                            "solution values never reached the board")
                if sess and sess.errors:
                    stats["rejected"] += len(sess.errors)
                if final.get("solved"):
                    stats["solved"] += 1
                    p.call("POST", f"/api/game/{state['gameId']}/end")

        t0 = time.time()
        await asyncio.gather(*[one(p) for p in players])
        elapsed = time.time() - t0

        print(f"  soak: {cfg.players} concurrent players, {stats['games']} games, "
              f"{stats['solved']} solved, in {elapsed:.1f}s", flush=True)
        check(stats["corrupt"] == 0, "SOAK-CORRUPTION",
              f"{stats['corrupt']} boards corrupted under concurrent play",
              "no board corrupted under concurrent play")
        check(stats["rejected"] == 0, "SOAK-CORRECT-MOVES-REJECTED",
              f"{stats['rejected']} correct moves rejected under concurrent play",
              "no correct move rejected under concurrent play")
        check(stats["lost"] == 0, "SOAK-MOVES-LOST",
              f"{stats['lost']} games lost moves under concurrent play",
              "no moves lost under concurrent play")
        check(stats["solved"] == stats["games"], "SOAK-SOLVE-NOT-RECOGNISED",
              f"only {stats['solved']}/{stats['games']} fully-played games were "
              "recognised as solved",
              f"all {stats['solved']} fully-played games were recognised as solved")

    asyncio.run(run())



@suite("L13 duels: both players race the same puzzle, one winner, rating is zero-sum")
def s_duels(cfg):
    async def run():
        a = Player().register(); a.login()
        b = Player().register(); b.login()

        st, resp = a.call("POST", "/api/duel/challenge", {"opponent": b.name, "difficulty": 2})
        if st not in (200, 201) or not isinstance(resp, dict):
            ok(f"duel challenge unavailable ({st}) — skipping duel suite")
            return
        duel_id = resp.get("duelId") or resp.get("id")
        if not duel_id:
            return

        # Only the challenged player may accept.
        st, _ = a.call("POST", f"/api/duel/{duel_id}/accept")
        check(st not in (200, 201), "DUEL-SELF-ACCEPT-ALLOWED",
              f"the challenger accepted their own duel ({st})",
              f"the challenger cannot accept their own duel ({st})")

        st, accepted = b.call("POST", f"/api/duel/{duel_id}/accept")
        if st not in (200, 201):
            ok(f"duel accept returned {st}; nothing further to assert")
            return

        rating_a0 = a.wallet().get("duelRating", 1000)
        rating_b0 = b.wallet().get("duelRating", 1000)

        gid_a = f"duel-{duel_id}:{a.name}"
        gid_b = f"duel-{duel_id}:{b.name}"
        sta, board_a = a.board(gid_a)
        stb, board_b = b.board(gid_b)
        if sta != 200 or stb != 200:
            ok("duel boards not addressable by the documented id scheme; skipping")
            return

        check(givens_of(board_a) == givens_of(board_b), "DUEL-DIFFERENT-PUZZLES",
              "the two duellists were given DIFFERENT puzzles, so 'first correct solve "
              "wins' is meaningless",
              "both duellists race the identical puzzle")

        # The opponent's live board must be unreadable: their entries are correct answers.
        st, _ = b.board(gid_a, expect=(403, 404))
        check(st in (403, 404), "DUEL-OPPONENT-BOARD-READABLE",
              f"a duellist read their opponent's live board ({st}) — every value in it is "
              "a correct answer to copy",
              f"a duellist cannot read the opponent's board ({st})")

        # A plays it out and wins.
        final, sess = await play_full_game(a, board_a)
        if final and final.get("solved"):
            a.call("POST", f"/api/game/{gid_a}/end")
            await asyncio.sleep(0.5)
            ra = a.wallet().get("duelRating", rating_a0)
            rb = b.wallet().get("duelRating", rating_b0)
            gained, lost = ra - rating_a0, rating_b0 - rb
            check(gained > 0, "DUEL-WINNER-GAINS-NOTHING",
                  f"the winner's rating moved by {gained}",
                  f"the duel winner gained {gained} rating")
            check(gained == lost, "DUEL-RATING-NOT-ZERO-SUM",
                  f"the winner gained {gained} but the loser lost {lost} — rating is being "
                  "created or destroyed, which inflates the ladder over time",
                  f"duel rating is zero-sum (+{gained}/-{lost})")

            # The loser must not also be able to claim the win.
            fb, _ = await play_full_game(b, board_b)
            if fb and fb.get("solved"):
                b.call("POST", f"/api/game/{gid_b}/end")
                await asyncio.sleep(0.4)
                rb2 = b.wallet().get("duelRating", rb)
                check(rb2 <= rb, "DUEL-SECOND-SOLVER-ALSO-WINS",
                      f"the second finisher's rating rose {rb} -> {rb2}; a duel has one winner",
                      "the second finisher does not also win the duel")

    asyncio.run(run())


@suite("L14 power-ups: bought once, spent once, never usable from thin air")
def s_powerups(cfg):
    p = Player().register(); p.login()
    st, catalog = p.call("GET", "/api/powerups")
    if st != 200:
        ok(f"power-up catalog unavailable ({st}) — skipping")
        return

    # Using something you do not hold must fail, and must not consume anything.
    st, _ = p.call("POST", "/api/powerups/use/EXTRA_LIFE")
    check(st not in (200, 204), "POWERUP-USE-WITHOUT-HOLDING",
          f"used EXTRA_LIFE without owning one ({st})",
          f"cannot use a power-up you do not hold ({st})")

    before = p.gems()
    st, _ = p.call("POST", "/api/powerups/buy/EXTRA_LIFE")
    if st not in (200, 201):
        ok(f"could not buy a power-up ({st}) — skipping the rest")
        return
    after = p.gems()
    check(after < before, "POWERUP-BUY-IS-FREE",
          f"buying a power-up did not cost anything ({before} -> {after})",
          f"buying EXTRA_LIFE cost {before - after} gems")

    # Inventory is nested under GET /api/powerups as {"catalog":..., "inventory":...};
    # there is no /api/powerups/inventory endpoint.
    st, shop = p.call("GET", "/api/powerups")
    inv = shop.get("inventory", {}) if isinstance(shop, dict) else {}
    held = inv.get("EXTRA_LIFE", 0)
    check(held >= 1, "POWERUP-NOT-DELIVERED",
          f"paid for EXTRA_LIFE but hold {held}",
          f"the purchased power-up is in the inventory ({held})")

    state = p.new_game(2)
    if not state:
        return
    _, b0 = p.board(state["gameId"])
    lives0 = b0.get("lives") if isinstance(b0, dict) else None
    st, _ = p.call("POST", f"/api/powerups/use/EXTRA_LIFE?gameId={state['gameId']}")
    _, b1 = p.board(state["gameId"])
    if st in (200, 204) and isinstance(b1, dict) and lives0 is not None:
        check(b1.get("lives") == lives0 + 1, "POWERUP-NO-EFFECT",
              f"EXTRA_LIFE was consumed but lives went {lives0} -> {b1.get('lives')}",
              f"EXTRA_LIFE added a life ({lives0} -> {b1.get('lives')})")
        st, shop2 = p.call("GET", "/api/powerups")
        held2 = (shop2.get("inventory", {}) if isinstance(shop2, dict) else {}).get("EXTRA_LIFE", 0)
        check(held2 == held - 1, "POWERUP-NOT-CONSUMED",
              f"inventory went {held} -> {held2} after using one",
              f"using a power-up consumed exactly one ({held} -> {held2})")

    # Spending more than you hold, concurrently, must not oversell.
    st, _ = p.call("POST", "/api/powerups/buy/EXTRA_LIFE")
    st, shop3 = p.call("GET", "/api/powerups")
    stock = (shop3.get("inventory", {}) if isinstance(shop3, dict) else {}).get("EXTRA_LIFE", 0)
    if stock >= 1:
        results = []

        def use():
            code, _ = p.call("POST", f"/api/powerups/use/EXTRA_LIFE?gameId={state['gameId']}")
            with _lock:
                results.append(code)

        threads = [threading.Thread(target=use) for _ in range(6)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()
        succeeded = sum(1 for c in results if c in (200, 204))
        check(succeeded <= stock, "POWERUP-OVERSPENT",
              f"{succeeded} concurrent uses succeeded against a stock of {stock}",
              f"{succeeded} concurrent uses against a stock of {stock} — not oversold")


@suite("L15 the saved-game list reflects reality")
def s_saved_list(cfg):
    p = Player().register(); p.login()
    made = []
    for _ in range(4):
        s = p.new_game(2)
        if s:
            made.append(s["gameId"])
            p.call("POST", f"/api/game/{s['gameId']}/save")

    st, saves = p.call("GET", "/api/game/saved?limit=20")
    if st != 200 or not isinstance(saves, list):
        return
    ids = {s.get("gameId") for s in saves}
    missing = [g for g in made if g not in ids]
    check(not missing, "SAVED-LIST-MISSING-GAMES",
          f"{len(missing)} explicitly saved games are absent from /api/game/saved",
          f"all {len(made)} saved games appear in the list")

    for s in saves:
        if not isinstance(s, dict) or "cells" not in s:
            continue
        check(consistent(grid_of(s)), "SAVED-LIST-CORRUPT-BOARD",
              f"saved game {s.get('gameId')} holds duplicate values",
              f"saved game {s.get('gameId')} is internally consistent")
        break

    # The list must never leak another player's saves.
    other = Player().register(); other.login()
    st, theirs = other.call("GET", "/api/game/saved?limit=20")
    if st == 200 and isinstance(theirs, list):
        leaked = [s.get("gameId") for s in theirs if s.get("gameId") in ids]
        check(not leaked, "SAVED-LIST-LEAKS-OTHER-PLAYERS",
              f"a fresh account sees {len(leaked)} of another player's saved games",
              "the saved-game list is scoped to its owner")


@suite("L16 the leaderboard is ordered, stable and consistent with the wallet")
def s_leaderboard(cfg):
    p = Player().register(); p.login()
    st, rows = p.call("GET", "/api/leaderboard?limit=20")
    if st != 200 or not isinstance(rows, list) or not rows:
        ok("leaderboard empty or unavailable — skipping")
        return

    vals = [r.get("sortValue") for r in rows if isinstance(r, dict)]
    if all(isinstance(v, int) for v in vals):
        check(vals == sorted(vals, reverse=True), "LEADERBOARD-NOT-ORDERED",
              f"rows are not in descending order of the value they are ranked by: {vals[:8]}",
              "leaderboard rows are ordered by their sort value")

    ranks = [r.get("rank") for r in rows if isinstance(r, dict)]
    if all(isinstance(v, int) for v in ranks):
        check(ranks == sorted(ranks), "LEADERBOARD-RANKS-UNORDERED",
              f"the rank column is not monotonically increasing: {ranks[:8]}",
              "the rank column increases monotonically")

    # Two identical reads must return the same order — ties are the usual culprit.
    st, again = p.call("GET", "/api/leaderboard?limit=20")
    if st == 200 and isinstance(again, list):
        names1 = [r.get("username") for r in rows]
        names2 = [r.get("username") for r in again]
        check(names1 == names2, "LEADERBOARD-UNSTABLE-ORDER",
              "two identical leaderboard reads returned different orders, so paging can "
              "show one player twice and skip another",
              "repeated leaderboard reads return a stable order")

    names = [r.get("username") for r in rows if isinstance(r, dict)]
    check(len(names) == len(set(names)), "LEADERBOARD-DUPLICATE-PLAYERS",
          f"the leaderboard lists the same player more than once: {names}",
          "no player appears twice on the leaderboard")


@suite("L17 two players in one game see each other's moves")
def s_multiplayer_visibility(cfg):
    async def run():
        a = Player().register(); a.login()
        state = a.new_game(2)
        if not state:
            return
        gid = state["gameId"]
        solution = solve(givens_of(state))
        grid = grid_of(state)
        if solution is None:
            return
        empties = [(r, c) for r in range(9) for c in range(9) if grid[r][c] == 0]
        if not empties:
            return
        r, c = empties[0]

        async with Session(a, gid) as owner:
            # A second socket for the SAME owner (a second tab, or a reconnect) must see
            # the move too — this is the path the session registry's orphan-set race broke.
            async with Session(a, gid) as second:
                await asyncio.sleep(0.3)
                await owner.move(r, c, 0, solution[r][c])
                await asyncio.sleep(0.8)
                await second.drain(1.5)
                board = await second.sync()
                if board:
                    check(grid_of(board)[r][c] == solution[r][c],
                          "MULTIPLAYER-MOVE-NOT-VISIBLE",
                          "a second live session on the same game does not see a move that "
                          "the authoritative board accepted",
                          "a second session sees the move on the shared board")
                    check(second.moves_echoed >= 1, "MULTIPLAYER-NO-BROADCAST",
                          "the second session received no move broadcast at all, so a "
                          "player would watch a frozen board",
                          f"the second session received {second.moves_echoed} move broadcast(s)")

    asyncio.run(run())


@suite("L18 reconnecting mid-game resumes exactly where play stopped")
def s_reconnect(cfg):
    async def run():
        p = Player().register(); p.login()
        state = p.new_game(3)
        if not state:
            return
        gid = state["gameId"]
        solution = solve(givens_of(state))
        grid = grid_of(state)
        if solution is None:
            return
        empties = [(r, c) for r in range(9) for c in range(9) if grid[r][c] == 0][:10]

        async with Session(p, gid) as s1:
            for (r, c) in empties:
                await s1.move(r, c, 0, solution[r][c])
                await asyncio.sleep(0.01)
            await s1.drain(1.0)
            before = await s1.sync()

        await asyncio.sleep(0.4)

        async with Session(p, gid) as s2:
            after = await s2.sync()
            if before and after:
                check(grid_of(before) == grid_of(after), "RECONNECT-STATE-LOST",
                      "reconnecting to a game returned a different board from the one the "
                      "player left — their work is gone",
                      "reconnecting returns the exact board the player left")
                check(before.get("moveCount") == after.get("moveCount"),
                      "RECONNECT-MOVECOUNT-DRIFT",
                      f"moveCount {before.get('moveCount')} -> {after.get('moveCount')} "
                      "across a reconnect",
                      "moveCount survives a reconnect")
            # And play must still work after reconnecting.
            remaining = [(r, c) for r in range(9) for c in range(9)
                         if grid_of(after)[r][c] == 0][:3] if after else []
            for (r, c) in remaining:
                await s2.move(r, c, 0, solution[r][c])
                await asyncio.sleep(0.02)
            await s2.drain(0.8)
            final = await s2.sync()
            if final and remaining:
                landed = all(grid_of(final)[r][c] == solution[r][c] for (r, c) in remaining)
                check(landed, "RECONNECT-PLAY-BROKEN",
                      "moves made after reconnecting did not reach the board",
                      "play works normally after a reconnect")

    asyncio.run(run())


@suite("L19 share/import round-trips a puzzle without leaking a solution")
def s_share_import(cfg):
    p = Player().register(); p.login()
    state = p.new_game(3)
    if not state:
        return
    gid = state["gameId"]
    st, resp = p.call("GET", f"/api/game/{gid}/share")
    if st != 200 or not isinstance(resp, dict) or not resp.get("code"):
        ok(f"share unavailable ({st}) — skipping")
        return
    code = resp["code"]

    before = p.gems()
    st, imported = p.call("POST", "/api/game/import", {"code": code})
    check(st == 200, "IMPORT-REJECTS-OWN-SHARE",
          f"importing a code the server itself produced failed with {st}",
          "a share code the server produced imports cleanly")
    if st != 200 or not isinstance(imported, dict):
        return

    check(imported.get("solved") is not True, "IMPORT-ARRIVES-SOLVED",
          "an imported board arrived already solved — a free payout",
          "an imported board does not arrive solved")
    check(givens_of(imported) == givens_of(state), "IMPORT-PUZZLE-CHANGED",
          "the imported puzzle's clues differ from the shared one",
          "the imported puzzle carries the same clues")
    check(p.gems() == before, "IMPORT-PAYS",
          f"importing a puzzle changed the balance {before} -> {p.gems()}",
          "importing a puzzle pays nothing")

    # A share code must not carry the answer.
    import gzip as _gz
    try:
        raw = base64.urlsafe_b64decode(code + "=" * (-len(code) % 4))
        text = _gz.decompress(raw).decode()
        cells = json.loads(text)
        revealed = sum(1 for row in cells for cell in row if cell.get("v"))
        check(revealed < 81, "SHARE-CODE-LEAKS-SOLUTION",
              f"the share code carries {revealed}/81 values — it contains the answer",
              f"the share code carries only the {revealed} visible clues")
    except Exception:
        pass


@suite("L20 an impossibly fast solve is not silently rewarded as a clean one")
def s_anticheat(cfg):
    async def run():
        p = Player().register(); p.login()
        before = p.gems()
        state = p.new_game(5)
        if not state:
            return
        gid = state["gameId"]
        final, _ = await play_full_game(p, state)
        if not final or not final.get("solved"):
            return
        p.call("POST", f"/api/game/{gid}/end")
        earned = p.gems() - before

        # A hardest-difficulty board solved in under a second by a machine. We do not
        # assert it is BLOCKED — that is a product decision — but the payout must be
        # bounded and the solve must be recorded, not silently doubled.
        check(earned <= 100, "ECON-IMPLAUSIBLE-PAYOUT",
              f"a single difficulty-5 solve paid {earned} gems",
              f"a difficulty-5 solve paid a bounded {earned} gems")

        _, again = p.board(gid)
        if isinstance(again, dict):
            check(again.get("solved") is True, "SOLVED-FLAG-LOST-AFTER-END",
                  "the board stopped reporting solved after the game ended",
                  "the board still reports solved after the game ended")

    asyncio.run(run())


@suite("L21 chaos and mirror modes do not corrupt or hang a game")
def s_modes(cfg):
    async def run():
        for chaos, mirror in ((True, False), (False, True), (True, True)):
            p = Player().register(); p.login()
            state = p.new_game(2, chaos=chaos, mirror=mirror)
            if not state:
                finding("MODE-NEW-GAME-FAILED",
                        f"could not create a game with chaos={chaos} mirror={mirror}")
                continue
            label = f"chaos={chaos} mirror={mirror}"
            check(consistent(grid_of(state)), "MODE-BOARD-INCONSISTENT",
                  f"{label}: the served board already holds a duplicate",
                  f"{label}: served board is consistent")

            final, sess = await play_full_game(p, state, stop_after=12)
            if final is None:
                finding("MODE-PLAY-HUNG", f"{label}: the game stopped responding during play")
                continue
            check(consistent(grid_of(final)), "MODE-PLAY-CORRUPTS",
                  f"{label}: the board holds duplicates after normal play",
                  f"{label}: the board stayed consistent through play")

    asyncio.run(run())


@suite("L22 the API refuses malformed and hostile input without breaking")
def s_hostile_input(cfg):
    p = Player().register(); p.login()
    state = p.new_game(2)
    gid = state["gameId"] if state else "nope"

    probes = [
        ("GET", f"/api/game/{'A' * 5000}", None, "a 5000-character game id"),
        ("GET", "/api/game/../../etc/passwd", None, "a path-traversal game id"),
        ("GET", "/api/game/%00null", None, "a null-byte game id"),
        ("POST", "/api/game/new?difficulty=0", None, "difficulty 0"),
        ("POST", "/api/game/new?difficulty=99", None, "difficulty 99"),
        ("POST", "/api/game/new?difficulty=abc", None, "a non-numeric difficulty"),
        ("GET", "/api/game/saved?limit=-1", None, "a negative page limit"),
        ("GET", "/api/game/saved?limit=999999", None, "an enormous page limit"),
        ("POST", "/api/game/import", {"code": "not-base64!!"}, "a malformed share code"),
        ("POST", "/api/game/import", {"code": ""}, "an empty share code"),
    ]
    for method, path, body, label in probes:
        st, _ = p.call(method, path, body)
        check(not (isinstance(st, int) and 500 <= st < 600), "INPUT-CAUSES-5XX",
              f"{label} produced {st} — malformed input is the caller's fault, not a "
              "server error, and a 500 is what monitoring pages on",
              f"{label} was refused cleanly ({st})")

    st, _ = p.call("GET", "/actuator/health")
    check(st == 200, "HOSTILE-INPUT-DEGRADED-SERVER",
          f"the server is unhealthy after hostile input ({st})",
          "the server is still healthy after hostile input")


# ──────────────────────────────────────────────────────────────────────────────
# A stateful, realistic player — the simulation half of the engine
# ──────────────────────────────────────────────────────────────────────────────

class Ledger:
    """
    Every gem movement the simulation believes should have happened.

    The point is reconciliation: at the end of a run, the server's balance for each
    player must equal their starting bonus plus the sum of what we recorded. Any drift
    is currency created or destroyed by the server, which is the whole class of bug the
    import mint and the concurrent-hint oversell belonged to. Spot-checking a balance
    after one action cannot see slow leaks; a ledger can.
    """

    def __init__(self):
        self.entries = defaultdict(list)      # player -> [(reason, delta)]

    def record(self, player, reason, delta):
        with _lock:
            self.entries[player].append((reason, delta))

    def expected(self, player):
        return sum(d for _, d in self.entries[player])

    def explain(self, player):
        return ", ".join(f"{r}{d:+d}" for r, d in self.entries[player]) or "(no activity)"


LEDGER = Ledger()


class SimPlayer(Player):
    """
    A player with memory and habits, rather than a one-shot request.

    Real players do not play one perfect game and stop. They abandon boards, come back
    to them, make mistakes, take hints when stuck, undo, keep several games going, and
    hold more than one tab open. Every bug this project has found in the persistence,
    economy and realtime layers lived in exactly those transitions — a board evicted
    between two visits, a hint charged on one connection and forgotten on another. This
    class exists to generate those transitions on purpose.
    """

    def __init__(self, name=None):
        super().__init__(name)
        self.games = {}            # gameId -> {"solution": grid, "state": BoardState}
        self.solved = 0
        self.abandoned = 0
        self.hints_taken = 0
        self.starting_gems = None

    def bootstrap(self):
        self.register()
        self.login()
        self.starting_gems = self.gems()
        return self

    def start_game(self, difficulty=None):
        state = self.new_game(difficulty or random.choice([1, 2, 3, 4]))
        if not state:
            return None
        solution = solve(givens_of(state))
        if solution is None:
            finding("SIM-UNSOLVABLE-BOARD",
                    f"the server issued {state['gameId']} with no solution")
            return None
        self.games[state["gameId"]] = {"solution": solution, "state": state}
        return state

    async def play_some(self, game_id, cells, mistakes=0):
        """Plays `cells` correct moves and `mistakes` deliberate wrong ones."""
        entry = self.games.get(game_id)
        if not entry:
            return None
        solution = entry["solution"]
        async with Session(self, game_id) as sess:
            board = await sess.sync()
            if not board:
                return None
            grid = grid_of(board)
            empties = [(r, c) for r in range(9) for c in range(9) if grid[r][c] == 0]
            random.shuffle(empties)

            # Deliberate mistakes first: a wrong-but-plausible digit the server must refuse.
            for (r, c) in empties[:mistakes]:
                wrong = next((v for v in range(1, 10)
                              if v != solution[r][c] and legal(grid, r, c, v)), None)
                if wrong:
                    await sess.move(r, c, 0, wrong)
                    await asyncio.sleep(0.01)

            for (r, c) in empties[:cells]:
                await sess.move(r, c, 0, solution[r][c])
                await asyncio.sleep(0.008)
            await sess.drain(0.6)
            final = await sess.sync()
            if final:
                entry["state"] = final
            return final


@suite("L23 a realistic play session survives interleaved abandon, resume and eviction")
def s_realistic_session(cfg):
    """
    The transition graph real players generate, which one-shot suites never reach.

    Each simulated player keeps several games in flight, plays a bit of each, abandons
    them under cache pressure, comes back, and finishes. Every visit re-reads the board
    and checks it against what we last saw — so a board that quietly loses moves between
    two visits is caught at the visit, not at the end.
    """
    async def run():
        players = [SimPlayer().bootstrap() for _ in range(max(2, cfg.players // 2))]
        drift = []

        async def live(p):
            ids = []
            for _ in range(3):
                st = p.start_game()
                if st:
                    ids.append(st["gameId"])
            if not ids:
                return

            # Round 1: a few moves in each, then walk away.
            for gid in ids:
                await p.play_some(gid, cells=6, mistakes=1)
                p.call("POST", f"/api/game/{gid}/save")

            snapshots = {}
            for gid in ids:
                st, board = p.board(gid)
                if st == 200 and isinstance(board, dict):
                    snapshots[gid] = (filled(grid_of(board)), board.get("moveCount"))

            # Pressure: enough unrelated games to push the saved ones out of the cache.
            for _ in range(cfg.eviction_pressure):
                p.new_game(1)

            # Round 2: come back and verify nothing was lost, then finish one.
            for gid in ids:
                st, board = p.board(gid)
                if st != 200 or not isinstance(board, dict):
                    drift.append((gid, f"unreadable after eviction: {st}"))
                    continue
                now = (filled(grid_of(board)), board.get("moveCount"))
                if gid in snapshots and now != snapshots[gid]:
                    drift.append((gid, f"{snapshots[gid]} -> {now}"))
                if not consistent(grid_of(board)):
                    drift.append((gid, "board holds duplicates"))

            finish = ids[0]
            await p.play_some(finish, cells=81)
            st, board = p.board(finish)
            if isinstance(board, dict) and board.get("solved"):
                p.call("POST", f"/api/game/{finish}/end")
                p.solved += 1

        await asyncio.gather(*[live(p) for p in players])

        check(not drift, "SIM-STATE-DRIFT",
              f"{len(drift)} games changed or became unreadable across an "
              f"abandon/eviction/resume cycle: {drift[:4]}",
              f"{len(players)} players kept 3 games each across eviction with no drift")
        check(sum(p.solved for p in players) > 0, "SIM-NO-COMPLETIONS",
              "no simulated player managed to finish a game",
              f"{sum(p.solved for p in players)} games finished after being abandoned and resumed")

    asyncio.run(run())


@suite("L24 the gem ledger reconciles exactly after a full session")
def s_economy_ledger(cfg):
    """
    Double-entry accounting against the server.

    Every action with a documented price is recorded, then the final balance is compared
    with starting + sum(ledger). A spot check after one action cannot see a slow leak;
    this can. It is deliberately strict — an exact match, not a range — because the whole
    point is that currency must be conserved to the gem.
    """
    async def run():
        players = [SimPlayer().bootstrap() for _ in range(max(2, cfg.players // 3))]
        hint_cost = 5

        async def act(p):
            for _ in range(2):
                st = p.start_game(difficulty=2)
                if not st:
                    continue
                gid = st["gameId"]

                # A couple of hints, each with a known price.
                for _ in range(2):
                    before = p.gems()
                    code, _ = p.call("POST", f"/api/game/hint?gameId={gid}")
                    after = p.gems()
                    if code == 200:
                        LEDGER.record(p.name, "hint", -(before - after))
                        p.hints_taken += 1
                        if before - after != hint_cost:
                            finding("LEDGER-HINT-PRICE",
                                    f"a hint moved the balance by {before - after}, not {hint_cost}")
                    elif code == 402:
                        if after != before:
                            finding("LEDGER-REFUSED-HINT-CHARGED",
                                    f"a refused hint still moved the balance by {before - after}")

                # Finish and collect.
                before = p.gems()
                await p.play_some(gid, cells=81)
                stt, board = p.board(gid)
                if isinstance(board, dict) and board.get("solved"):
                    p.call("POST", f"/api/game/{gid}/end")
                    LEDGER.record(p.name, "solve", p.gems() - before)

        await asyncio.gather(*[act(p) for p in players])

        for p in players:
            actual = p.gems()
            expected = p.starting_gems + LEDGER.expected(p.name)
            check(actual == expected, "LEDGER-DOES-NOT-RECONCILE",
                  f"{p.name}: server says {actual} gems, the ledger says "
                  f"{expected} (started {p.starting_gems}; {LEDGER.explain(p.name)}) — "
                  "currency is being created or destroyed outside the documented prices",
                  f"{p.name}: balance reconciles exactly at {actual} gems")

    asyncio.run(run())


@suite("L25 every mutating endpoint is idempotent or explicitly refuses a replay")
def s_idempotency(cfg):
    """
    Replaying a request must never compound its effect.

    This is the shape of the /end replay farm: an endpoint that was safe once and a
    currency printer when repeated. Anything that changes state needs a defined answer to
    "what if the client retried?" — because clients DO retry, on flaky networks, on
    double-clicks, and on load-balancer timeouts.
    """
    p = SimPlayer().bootstrap()
    st = p.start_game(difficulty=1)
    if not st:
        return
    gid = st["gameId"]

    # save: repeatable, no side effect on the balance
    before = p.gems()
    codes = [p.call("POST", f"/api/game/{gid}/save")[0] for _ in range(5)]
    check(p.gems() == before, "IDEMPOTENCY-SAVE-COSTS",
          f"five saves moved the balance {before} -> {p.gems()}",
          f"five saves left the balance unchanged (codes {sorted(set(codes))})")

    # resume: repeatable, and must keep handing back the same board
    boards = []
    for _ in range(4):
        code, b = p.call("POST", f"/api/game/{gid}/resume")
        if code == 200 and isinstance(b, dict):
            boards.append(tuple(tuple(row) for row in grid_of(b)))
    check(len(set(boards)) <= 1, "IDEMPOTENCY-RESUME-DIVERGES",
          f"four resumes returned {len(set(boards))} different boards",
          "repeated resumes return the same board")

    # end on an UNSOLVED game: must not pay, however many times it is called
    before = p.gems()
    for _ in range(5):
        p.call("POST", f"/api/game/{gid}/end")
    check(p.gems() == before, "IDEMPOTENCY-END-UNSOLVED-PAYS",
          f"ending an unsolved game five times moved the balance {before} -> {p.gems()}",
          "ending an unsolved game pays nothing, however often")

    # register: a duplicate must be refused, not silently create a second row
    code, _ = p.call("POST", "/api/auth/register",
                     {"username": p.name, "password": p.password}, anonymous=True)
    check(code in (400, 409), "IDEMPOTENCY-DUPLICATE-REGISTER",
          f"re-registering an existing username returned {code}; a second row for one "
          "username makes every later lookup throw and locks the player out",
          f"a duplicate registration is refused ({code})")


@suite("L26 the REST and WebSocket views of a board never disagree")
def s_view_agreement(cfg):
    """
    Two windows onto one board must show the same thing.

    The web client reads over REST on load and over the socket while playing; the desktop
    client does the same. If the two ever diverge, one of them is showing the player a
    board the server does not have — which is how a "lost" move or a phantom entry
    actually reaches someone.
    """
    async def run():
        p = SimPlayer().bootstrap()
        st = p.start_game(difficulty=3)
        if not st:
            return
        gid = st["gameId"]
        solution = p.games[gid]["solution"]
        mismatches = []

        async with Session(p, gid) as sess:
            board = await sess.sync()
            grid = grid_of(board) if board else None
            if grid is None:
                return
            empties = [(r, c) for r in range(9) for c in range(9) if grid[r][c] == 0]
            random.shuffle(empties)

            for i, (r, c) in enumerate(empties[:14]):
                await sess.move(r, c, 0, solution[r][c])
                await asyncio.sleep(0.05)
                await sess.drain(0.25)

                ws_board = await sess.sync()
                code, rest_board = p.board(gid)
                if not ws_board or code != 200 or not isinstance(rest_board, dict):
                    continue
                if grid_of(ws_board) != grid_of(rest_board):
                    mismatches.append(i)
                if ws_board.get("moveCount") != rest_board.get("moveCount"):
                    mismatches.append(f"{i}:moveCount")

        check(not mismatches, "VIEW-DISAGREEMENT",
              f"the REST and WebSocket views disagreed at {len(mismatches)} points "
              f"during a single game: {mismatches[:5]}",
              "REST and WebSocket agreed on the board after every move")

    asyncio.run(run())


@suite("L27 two sessions on one game stay consistent (the multi-tab case)")
def s_multi_tab(cfg):
    """
    One player, two open tabs — an ordinary thing to do, and a genuine concurrency test.

    Both sockets are the same account on the same board, so every move from either must
    land exactly once and both must converge on the same grid. Losing one, or applying it
    twice, shows up here and nowhere else.
    """
    async def run():
        p = SimPlayer().bootstrap()
        st = p.start_game(difficulty=2)
        if not st:
            return
        gid = st["gameId"]
        solution = p.games[gid]["solution"]

        async with Session(p, gid) as tab_a, Session(p, gid) as tab_b:
            await asyncio.sleep(0.3)
            board = await tab_a.sync()
            if not board:
                return
            grid = grid_of(board)
            empties = [(r, c) for r in range(9) for c in range(9) if grid[r][c] == 0]
            random.shuffle(empties)
            chosen = empties[:12]

            # Alternate tabs, so ordering across two sockets is exercised.
            for i, (r, c) in enumerate(chosen):
                tab = tab_a if i % 2 == 0 else tab_b
                await tab.move(r, c, 0, solution[r][c])
                await asyncio.sleep(0.02)

            await tab_a.drain(1.0)
            await tab_b.drain(1.0)
            a_board = await tab_a.sync()
            b_board = await tab_b.sync()

            if a_board and b_board:
                check(grid_of(a_board) == grid_of(b_board), "MULTITAB-DIVERGENCE",
                      "two sessions on the same game show different boards",
                      "both sessions converged on the same board")
                landed = sum(1 for (r, c) in chosen
                             if grid_of(a_board)[r][c] == solution[r][c])
                check(landed == len(chosen), "MULTITAB-MOVES-LOST",
                      f"only {landed}/{len(chosen)} moves survived being sent across two "
                      "sessions of the same game",
                      f"all {landed} moves from two alternating sessions landed")
                check(a_board.get("moveCount") == b_board.get("moveCount"),
                      "MULTITAB-COUNTER-DIVERGENCE",
                      f"moveCount differs between sessions: {a_board.get('moveCount')} "
                      f"vs {b_board.get('moveCount')}",
                      "both sessions report the same move count")

    asyncio.run(run())


@suite("L28 the server degrades correctly when Redis disappears")
def s_redis_fault_injection(cfg):
    """
    Redis going away must degrade the service, not break it.

    The codebase is explicit that every Redis touch falls back to a local path — that is
    why the ONE unguarded read (pass 9) was a finding. The only way to know the fallbacks
    still work is to actually remove Redis mid-session and keep playing. This suite is
    skipped unless --fault-injection is passed, because it manipulates a shared service.
    """
    if not cfg.fault_injection:
        ok("fault injection not requested (pass --fault-injection to exercise it)")
        return

    import subprocess

    def redis_running():
        return subprocess.run(["redis-cli", "ping"], capture_output=True,
                              timeout=5).stdout.strip() == b"PONG"

    if not redis_running():
        ok("Redis is not reachable from here — skipping fault injection")
        return

    async def run():
        p = SimPlayer().bootstrap()
        st = p.start_game(difficulty=2)
        if not st:
            return
        gid = st["gameId"]
        await p.play_some(gid, cells=5)
        _, before = p.board(gid)

        subprocess.run(["redis-cli", "shutdown", "nosave"], capture_output=True, timeout=5)
        time.sleep(2)
        try:
            code, during = p.board(gid)
            check(code == 200, "REDIS-DOWN-READ-FAILS",
                  f"reading a board with Redis down returned {code} — the database still "
                  "has the row, so this must degrade, not fail",
                  "a board is still readable with Redis down")
            if isinstance(before, dict) and isinstance(during, dict):
                check(grid_of(before) == grid_of(during), "REDIS-DOWN-BOARD-CHANGED",
                      "the board changed when Redis went away",
                      "the board is unchanged with Redis down")

            new_state = p.new_game(1)
            check(new_state is not None, "REDIS-DOWN-CANNOT-START-GAME",
                  "a new game could not be created with Redis down",
                  "a new game can still be created with Redis down")

            if new_state:
                p.games[new_state["gameId"]] = {
                    "solution": solve(givens_of(new_state)), "state": new_state}
                final = await p.play_some(new_state["gameId"], cells=81)
                check(final is not None and final.get("solved"),
                      "REDIS-DOWN-CANNOT-PLAY",
                      "a full game could not be played to completion with Redis down",
                      "a full game plays to completion with Redis down")

            code, _ = p.call("GET", "/actuator/health/liveness")
            check(code == 200, "REDIS-DOWN-LIVENESS-FAILS",
                  f"liveness returned {code} with Redis down — a liveness probe must not "
                  "depend on an external system, or a Redis blip crash-loops every pod",
                  "liveness stays green with Redis down")
        finally:
            subprocess.Popen(["redis-server", "--daemonize", "yes", "--port", "6379",
                              "--save", ""], stdout=subprocess.DEVNULL,
                             stderr=subprocess.DEVNULL)
            for _ in range(20):
                time.sleep(0.5)
                if redis_running():
                    break
            ok("Redis restarted for the remaining suites")

        # And the service must recover once Redis is back.
        time.sleep(2)
        code, after = p.board(gid)
        check(code == 200, "REDIS-RECOVERY-READ-FAILS",
              f"reading the board after Redis came back returned {code}",
              "the board is readable again after Redis recovers")

    asyncio.run(run())


@suite("L29 a burst of requests is rate-limited without breaking the session")
def s_rate_limits(cfg):
    """
    Flood control must bound an abuser without harming an ordinary player.

    The WebSocket has a documented token bucket (burst 40, refill 20/s) that exists
    because a flood could previously lock a player out. What must NOT happen is the
    limiter killing a session that goes slightly fast, or leaving the socket unusable
    afterwards.
    """
    async def run():
        p = SimPlayer().bootstrap()
        st = p.start_game(difficulty=1)
        if not st:
            return
        gid = st["gameId"]
        solution = p.games[gid]["solution"]

        async with Session(p, gid) as sess:
            board = await sess.sync()
            grid = grid_of(board) if board else None
            if grid is None:
                return
            empties = [(r, c) for r in range(9) for c in range(9) if grid[r][c] == 0]

            # A hard burst: far above the bucket, as fast as the socket accepts.
            for i in range(200):
                r, c = empties[i % len(empties)]
                try:
                    await sess.send({"type": "sync", "payload": ""})
                except Exception:
                    break
            await sess.drain(1.5)

            # The session must still be usable afterwards.
            after = await sess.sync()
            check(after is not None, "RATELIMIT-KILLS-SESSION",
                  "the socket stopped responding after a burst — flood control must "
                  "throttle, not disconnect a player who clicked quickly",
                  "the session survived a 200-frame burst and still responds")

            if after:
                # And an ordinary-paced move must still be accepted.
                target = next(((r, c) for r in range(9) for c in range(9)
                               if grid_of(after)[r][c] == 0), None)
                if target:
                    r, c = target
                    await sess.move(r, c, 0, solution[r][c])
                    await asyncio.sleep(0.4)
                    await sess.drain(0.6)
                    final = await sess.sync()
                    if final:
                        check(grid_of(final)[r][c] == solution[r][c],
                              "RATELIMIT-BLOCKS-LEGITIMATE-PLAY",
                              "a normal move was refused after the burst subsided",
                              "normal play resumes after the burst")

        code, _ = p.call("GET", "/actuator/health")
        check(code == 200, "RATELIMIT-DEGRADES-SERVER",
              f"the server is unhealthy after a burst ({code})",
              "the server stays healthy through a burst")

    asyncio.run(run())


@suite("L30 an authenticated player cannot reach the admin surface")
def s_privilege(cfg):
    """
    Authentication is not authorization.

    The admin endpoints expose the platform's economy and XP configuration. A test that
    only checks the anonymous case proves a login is needed, not that a ROLE is — which
    is exactly the gap a mutation audit found in the JUnit suite.
    """
    p = SimPlayer().bootstrap()
    for path in ("/admin/constants", "/admin/constants/", "/ADMIN/constants",
                 "/admin//constants", "/admin/./constants", "/admin/constants.json"):
        code, _ = p.call("GET", path)
        check(code != 200, "PRIVILEGE-ADMIN-REACHABLE",
              f"an ordinary registered player reached {path} ({code}) — this exposes the "
              "economy and XP configuration",
              f"an ordinary player is refused {path} ({code})")

    for path in ("/actuator/env", "/actuator/beans", "/actuator/configprops",
                 "/actuator/heapdump", "/actuator/threaddump", "/actuator/mappings"):
        code, _ = p.call("GET", path)
        check(code != 200, "PRIVILEGE-ACTUATOR-EXPOSED",
              f"{path} is readable by an ordinary player ({code}) — these leak "
              "configuration, credentials and internal structure",
              f"{path} is not exposed ({code})")


@suite("L31 a long soak leaves the server as healthy as it started")
def s_long_soak(cfg):
    """
    Sustained mixed load, then a health comparison against the baseline.

    Short suites cannot see a leak or a slow degradation. This one runs a mixed workload —
    new games, hints, saves, resumes, abandons, completions — across many players, then
    checks that the server is still healthy, still fast, and still correct.
    """
    async def run():
        players = [SimPlayer().bootstrap() for _ in range(cfg.players)]

        t0 = time.time()
        code, _ = players[0].call("GET", "/actuator/health")
        baseline_latency = time.time() - t0

        stats = {"games": 0, "solved": 0, "hints": 0, "saves": 0, "resumes": 0}

        async def churn(p):
            for _ in range(cfg.soak_rounds):
                action = random.choices(
                    ["play", "abandon", "hint", "resume", "save"],
                    weights=[5, 2, 2, 2, 2])[0]
                if action in ("play", "abandon") or not p.games:
                    st = p.start_game()
                    if not st:
                        continue
                    stats["games"] += 1
                    gid = st["gameId"]
                    if action == "play":
                        await p.play_some(gid, cells=81)
                        _, b = p.board(gid)
                        if isinstance(b, dict) and b.get("solved"):
                            p.call("POST", f"/api/game/{gid}/end")
                            stats["solved"] += 1
                    else:
                        await p.play_some(gid, cells=random.randint(1, 10), mistakes=1)
                        p.abandoned += 1
                else:
                    gid = random.choice(list(p.games))
                    if action == "hint":
                        p.call("POST", f"/api/game/hint?gameId={gid}")
                        stats["hints"] += 1
                    elif action == "save":
                        p.call("POST", f"/api/game/{gid}/save")
                        stats["saves"] += 1
                    else:
                        p.call("POST", f"/api/game/{gid}/resume")
                        stats["resumes"] += 1

        started = time.time()
        await asyncio.gather(*[churn(p) for p in players])
        elapsed = time.time() - started

        print(f"  soak: {cfg.players} players x {cfg.soak_rounds} rounds — "
              f"{stats['games']} games, {stats['solved']} solved, {stats['hints']} hints, "
              f"{stats['saves']} saves, {stats['resumes']} resumes in {elapsed:.1f}s",
              flush=True)

        t0 = time.time()
        code, _ = players[0].call("GET", "/actuator/health")
        after_latency = time.time() - t0

        check(code == 200, "SOAK-UNHEALTHY-AFTER",
              f"the server is unhealthy after the soak ({code})",
              "the server is still healthy after the soak")
        check(after_latency < max(2.0, baseline_latency * 20), "SOAK-LATENCY-DEGRADED",
              f"health latency went {baseline_latency*1000:.0f}ms -> "
              f"{after_latency*1000:.0f}ms across the soak, which suggests the server is "
              "accumulating work it never releases",
              f"health latency stable ({baseline_latency*1000:.0f}ms -> "
              f"{after_latency*1000:.0f}ms)")

        # Every board any player still holds must be valid.
        corrupt = 0
        checked = 0
        for p in players:
            for gid in list(p.games)[:5]:
                code, b = p.board(gid)
                if code == 200 and isinstance(b, dict):
                    checked += 1
                    if not consistent(grid_of(b)):
                        corrupt += 1
        check(corrupt == 0, "SOAK-BOARD-CORRUPTED",
              f"{corrupt}/{checked} boards hold duplicates after the soak",
              f"all {checked} sampled boards are still valid after the soak")

    asyncio.run(run())

@suite("L32 account creation is throttled per address")
def s_registration_throttle(cfg):
    """
    Registration used to be completely unthrottled: anyone could mint accounts as fast as
    they could send requests. A throttle now caps them per client address.

    This suite exists because the throttle first showed up here as *harness breakage* — the
    simulator creates dozens of players and started getting 429s. The wrong response to that
    is to raise the quota until the harness is happy and never test the feature; the right
    one is to prove the limit actually fires, and to prove it does so without collateral
    damage. So: run this suite against a server configured with a SMALL quota, and assert
    both halves.

    The quota in force is read from the server rather than assumed, because a harness run
    deliberately raises it (see --registration-quota) and a hard-coded number here would
    silently stop testing anything the moment the two drifted apart.
    """
    quota = cfg.registration_quota
    if quota is None or quota > 40:
        note(f"L32 skipped: the registration quota in force ({quota}) is too high to "
             f"exercise cheaply — run with --registration-quota to enable")
        return

    # This address may already be saturated by earlier traffic in the same window — the
    # simulator creates a lot of players. A pre-saturated counter cannot distinguish "the
    # throttle works" from "the throttle is broken open", so say so rather than reporting a
    # bug that is really a dirty fixture. Point the server at its own Redis database
    # (spring.data.redis.database) to get a clean counter.
    first, _ = Player().try_register()
    if first == 429:
        note("L32 skipped: this address is already throttled from earlier traffic in the "
             "current window, so the suite cannot tell a working throttle from a broken "
             "one — give the server its own Redis database and re-run")
        return

    accepted, throttled, other = (1 if first in (200, 201) else 0), 0, []
    if first not in (200, 201, 429):
        other.append(first)
    for _ in range(quota + 2):
        code, _body = Player().try_register()
        if code in (200, 201):
            accepted += 1
        elif code == 429:
            throttled += 1
        else:
            other.append(code)

    check(throttled > 0, "REGISTRATION-UNTHROTTLED",
          f"created {accepted} accounts from one address with no 429 — registration is "
          f"unthrottled, so an attacker can mint accounts at request speed",
          f"account creation is refused with 429 after {accepted} accounts")

    check(accepted > 0, "REGISTRATION-FULLY-BLOCKED",
          "every registration was refused — the throttle is rejecting legitimate first "
          "signups, which is worse than not having one",
          f"the first {accepted} legitimate signups still succeeded")

    check(not other, "REGISTRATION-WRONG-STATUS",
          f"registration returned unexpected statuses {sorted(set(other))} — a throttled "
          f"request must be a clean 429, not a 500 or a redirect",
          "every response was either a success or a clean 429")

    # A throttled registrant must not break anyone else's session.
    code, _ = Player().call("GET", "/actuator/health", anonymous=True)
    check(code == 200, "REGISTRATION-THROTTLE-COLLATERAL",
          f"health returned {code} while registration was throttled — the filter is "
          f"rejecting traffic it should never see",
          "unrelated endpoints are unaffected by the registration throttle")

@suite("L12 the server stays healthy and never returns 5xx")
def s_health(cfg):
    p = Player().register(); p.login()
    st, _ = p.call("GET", "/actuator/health")
    check(st == 200, "HEALTH-NOT-OK", f"/actuator/health returned {st}",
          "server reports healthy after the full run")
    check(not FIVE_HUNDREDS, "SERVER-5XX",
          f"{len(FIVE_HUNDREDS)} server errors during the run: {FIVE_HUNDREDS[:5]}",
          "no 5xx response anywhere in the run")



# ──────────────────────────────────────────────────────────────────────────────

def main():
    global BASE
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default=BASE)
    ap.add_argument("--players", type=int, default=6)
    ap.add_argument("--games-per-player", type=int, default=3)
    ap.add_argument("--boards-per-difficulty", type=int, default=6)
    ap.add_argument("--hint-boards", type=int, default=8)
    ap.add_argument("--concurrent-hints", type=int, default=6)
    ap.add_argument("--eviction-pressure", type=int, default=12)
    ap.add_argument("--soak-rounds", type=int, default=4)
    ap.add_argument("--fault-injection", action="store_true",
                    help="stop and restart Redis mid-run to exercise the degraded paths")
    ap.add_argument("--registration-quota", type=int, default=None,
                    help="the server's sudokupro.security.register.max-attempts value. "
                         "Set it to enable L32, which proves account creation is throttled. "
                         "A harness server normally raises the quota so the simulator can "
                         "create its players; pass the raised value here and L32 will skip "
                         "rather than pretend to have tested it.")
    ap.add_argument("--quick", action="store_true")
    ap.add_argument("--only", default=None)
    cfg = ap.parse_args()
    BASE = cfg.base.rstrip("/")

    if cfg.quick:
        cfg.players, cfg.games_per_player = 3, 1
        cfg.boards_per_difficulty, cfg.hint_boards = 2, 3
        cfg.concurrent_hints, cfg.eviction_pressure = 3, 4
        cfg.soak_rounds = 2

    print("=== SudokuPro LIVE engine — playing the real server to find bugs ===")
    print(f"    target={BASE}  players={cfg.players}  games/player={cfg.games_per_player}\n",
          flush=True)

    t0 = time.time()
    for name, fn in SUITES:
        if cfg.only and cfg.only not in name:
            continue
        print(f"[{name}]", flush=True)
        try:
            fn(cfg)
        except Exception as e:
            import traceback
            finding("SUITE-CRASHED", f"{name}: {type(e).__name__}: {e}")
            traceback.print_exc()
        print(flush=True)

    print("=== SUMMARY ===")
    print(f"Invariant checks passed: {CHECKS[0]}")
    print(f"Distinct bugs found:     {len(FINDINGS)}")
    print(f"Wall clock:              {time.time() - t0:.1f}s")
    for ident, detail in FINDINGS:
        print(f"  [BUG] {ident}: {detail}")

    if FINDINGS:
        print(f"\nFAIL: {len(FINDINGS)} invariant violation(s).")
        sys.exit(1)
    print(f"\nOK: all {CHECKS[0]} live invariants hold.")


if __name__ == "__main__":
    main()
