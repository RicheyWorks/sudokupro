#!/usr/bin/env python3
"""SudokuPro load test: N concurrent simulated players against a running server.

Each player registers an account, opens a game (a share of them join the daily
puzzle instead), connects the gameplay WebSocket, and plays legal moves at a
human-ish cadence until solved or out of moves. Reports latency percentiles
and error counts.

Usage:
    pip install websockets requests
    docker compose up -d          # or any running server
    python loadtest/loadtest.py --base http://localhost:8080 --players 50 --moves 30

The script never uses the admin account — every player is a fresh registration
(loadtest_<run>_<n>), so it also exercises the accounts path at scale.
"""
import argparse
import asyncio
import base64
import json
import random
import statistics
import string
import time

import requests
import websockets


def parse_args():
    p = argparse.ArgumentParser(description="SudokuPro load test")
    p.add_argument("--base", default="http://localhost:8080")
    p.add_argument("--players", type=int, default=20)
    p.add_argument("--moves", type=int, default=25, help="max moves per player")
    p.add_argument("--daily-share", type=float, default=0.3, help="fraction joining the daily")
    p.add_argument("--think-ms", type=int, default=200, help="mean pause between moves")
    return p.parse_args()


class Stats:
    def __init__(self):
        self.latencies = {}   # op -> [seconds]
        self.errors = {}      # op -> count
        self.solved = 0

    def record(self, op, seconds):
        self.latencies.setdefault(op, []).append(seconds)

    def error(self, op):
        self.errors[op] = self.errors.get(op, 0) + 1

    def report(self):
        print("\n== SudokuPro load test report ==")
        for op, xs in sorted(self.latencies.items()):
            xs_ms = sorted(x * 1000 for x in xs)
            p50 = statistics.median(xs_ms)
            p95 = xs_ms[min(len(xs_ms) - 1, int(len(xs_ms) * 0.95))]
            print(f"{op:<18} n={len(xs_ms):<6} p50={p50:7.1f}ms  p95={p95:7.1f}ms  max={xs_ms[-1]:7.1f}ms")
        if self.errors:
            print("errors:", ", ".join(f"{k}={v}" for k, v in sorted(self.errors.items())))
        print(f"solved boards: {self.solved}")


STATS = Stats()
RUN_ID = "".join(random.choices(string.ascii_lowercase + string.digits, k=6))


def timed(op, fn, *args, **kwargs):
    t0 = time.monotonic()
    try:
        result = fn(*args, **kwargs)
        STATS.record(op, time.monotonic() - t0)
        return result
    except Exception:
        STATS.error(op)
        raise


class Player:
    def __init__(self, args, n):
        self.args = args
        self.name = f"loadtest_{RUN_ID}_{n}"
        self.password = "loadtest-password-1"
        self.session = requests.Session()
        self.csrf_header = "X-XSRF-TOKEN"
        self.csrf = None
        self.board = None
        self.game_id = None

    # ---- REST ---------------------------------------------------------------

    def _headers(self, mutating=False):
        creds = base64.b64encode(f"{self.name}:{self.password}".encode()).decode()
        h = {"Authorization": f"Basic {creds}", "Accept": "application/json"}
        if mutating and self.csrf:
            h[self.csrf_header] = self.csrf
        return h

    def register(self):
        def go():
            r = self.session.post(self.args.base + "/api/auth/register",
                                  json={"username": self.name, "password": self.password}, timeout=10)
            if r.status_code != 201:
                raise RuntimeError(f"register {r.status_code}")
        timed("register", go)

    def bootstrap(self):
        def go():
            r = self.session.get(self.args.base + "/api/session", headers=self._headers(), timeout=10)
            r.raise_for_status()
            body = r.json()
            self.csrf_header = body.get("csrfHeaderName", self.csrf_header)
            self.csrf = body.get("csrfToken")
        timed("session", go)

    def start_game(self, daily):
        def go():
            if daily:
                r = self.session.post(self.args.base + "/api/daily/join",
                                      headers=self._headers(True), timeout=15)
            else:
                difficulty = random.choice([1, 2, 2, 3])
                r = self.session.post(
                    self.args.base + f"/api/game/new?difficulty={difficulty}&chaos=false&mirror=false",
                    headers=self._headers(True), timeout=30)
            r.raise_for_status()
            self.board = r.json()
            self.game_id = self.board["gameId"]
        timed("daily_join" if daily else "new_game", go)

    # ---- gameplay -----------------------------------------------------------

    def grid(self):
        return [[c["value"] for c in row] for row in self.board["cells"]]

    def legal_move(self):
        """A valid (row, col, value) judged from the visible board, or None."""
        g = self.grid()
        cells = [(r, c) for r in range(9) for c in range(9)
                 if g[r][c] == 0 and not self.board["cells"][r][c]["isGiven"]]
        random.shuffle(cells)
        for r, c in cells:
            for v in random.sample(range(1, 10), 9):
                if v in g[r]:
                    continue
                if any(g[i][c] == v for i in range(9)):
                    continue
                br, bc = 3 * (r // 3), 3 * (c // 3)
                if any(g[i][j] == v for i in range(br, br + 3) for j in range(bc, bc + 3)):
                    continue
                return r, c, v
        return None

    async def play(self):
        try:
            self.register()
            self.bootstrap()
            self.start_game(random.random() < self.args.daily_share)
        except Exception:
            return

        ws_url = (self.args.base.replace("http", "ws", 1)
                  + f"/ws/game?gameId={self.game_id}")
        cookie = "; ".join(f"{k}={v}" for k, v in self.session.cookies.items())
        headers = {"Authorization": self._headers()["Authorization"]}
        if cookie:
            headers["Cookie"] = cookie
        try:
            async with websockets.connect(ws_url, additional_headers=headers,
                                          open_timeout=10) as ws:
                STATS.record("ws_connect", 0.0)
                for _ in range(self.args.moves):
                    move = self.legal_move()
                    if move is None:
                        break
                    r, c, v = move
                    self.board["cells"][r][c]["value"] = v
                    t0 = time.monotonic()
                    await ws.send(json.dumps(
                        {"type": "move", "payload": {"row": r, "col": c, "oldVal": 0, "newVal": v}}))
                    # Drain envelopes briefly; a "board" envelope resyncs us.
                    try:
                        while True:
                            raw = await asyncio.wait_for(ws.recv(), timeout=0.5)
                            env = json.loads(raw)
                            if env.get("type") == "board":
                                self.board = env["payload"]
                                if self.board.get("solved"):
                                    STATS.solved += 1
                                    return
                            elif env.get("type") == "error":
                                await ws.send(json.dumps({"type": "sync", "payload": ""}))
                    except asyncio.TimeoutError:
                        pass
                    STATS.record("move_roundtrip", time.monotonic() - t0)
                    await asyncio.sleep(random.expovariate(1000 / max(1, self.args.think_ms)))
        except Exception:
            STATS.error("websocket")


async def main():
    args = parse_args()
    print(f"Simulating {args.players} players against {args.base} (run {RUN_ID})")
    t0 = time.monotonic()
    await asyncio.gather(*(Player(args, n).play() for n in range(args.players)))
    print(f"\nTotal wall time: {time.monotonic() - t0:.1f}s")
    STATS.report()


if __name__ == "__main__":
    asyncio.run(main())
