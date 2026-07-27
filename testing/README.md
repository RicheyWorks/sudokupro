# Live-server test harnesses

Black-box probes that run against a **running** SudokuPro server. They complement the
JUnit suite: several of the bugs they found are invisible to unit tests because they only
appear with real Redis, a real servlet chain, or a real browser.

Start a server first (H2 + Redis is enough):

```bash
redis-server --daemonize yes --port 6379
cd server && mvn spring-boot:run -Dspring-boot.run.useTestClasspath=true \
  -Dspring-boot.run.profiles=dev \
  -Dspring-boot.run.jvmArguments="-Dspring.datasource.url=jdbc:h2:mem:play;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1 -Dspring.datasource.driver-class-name=org.h2.Driver -Dspring.datasource.username=sa -Dspring.datasource.password= -Dspring.jpa.database-platform=org.hibernate.dialect.H2Dialect -Dspring.jpa.hibernate.ddl-auto=create-drop -Dspring.flyway.enabled=false -DADMIN_PASSWORD=<pick-one>"
```

## adversarial_api_test.py

20 black-box checks over two real accounts: cross-player ownership on
save/end/resume/hint, anonymous access, CSRF enforcement, input validation, registration
hygiene, and a check that the solution never appears in a `BoardState` payload.

```bash
python3 testing/adversarial_api_test.py
```

Found (both now fixed): a spectator could drain the **board owner's** wallet by requesting
a hint on their gameId, and a wrong password returned **HTTP 500** instead of 401 — which
also meant the brute-force lockout never engaged, because the underlying
`IllegalArgumentException` is not an `AuthenticationException` and so published no
authentication-failure event for the limiter to count.

## reward_replay_probe.py

Registers a player, solves a board for real over the gameplay WebSocket, then replays
`POST /api/game/{id}/end` seven times and prints the wallet after each call.

```bash
pip install websockets
python3 testing/reward_replay_probe.py
```

Before the fix this minted +15 gems and +15 XP per replay (15 → 135 gems). Expected output
now: the balance rises once on the genuine solve and then stays flat.

## web_client_test.py

A real Chromium browser against a real server, driving the shipped web client at
`/play/`. It logs in, starts a puzzle, and **plays it to completion by clicking cells and
pressing digit keys** — then asserts what the player actually experiences: the win is
announced, the solve time is shown, pencil marks survive a dropped-and-restored
connection, and no uncaught JS error occurred anywhere in the session.

```bash
pip install playwright && playwright install chromium
python3 testing/web_client_test.py            # add --headed to watch it play
```

Until pass 17 `app.js` had **no test of any kind**, so two already-fixed client defects
were protected by nothing but the source comment describing them.

**Run it against a jar built from your current checkout.** Spring serves `/play/app.js`
out of the packaged jar, so a server started from an older build serves the OLD client
and the harness silently tests code you are not editing — the first mutation audit of
this suite came back falsely green for exactly that reason. For quick iteration on the
client, start the server with:

```bash
--spring.web.resources.static-locations=file:server/src/main/resources/static/
```

which serves the files straight off disk, so an edit takes effect on reload.

## Note on the shared-IP lockout

Every player here connects from the same address, and `LoginAttemptLimiter` keys on
`request.getRemoteAddr()` with no `X-Forwarded-For` handling. One burst of bad passwords
locks out *everything* from that IP for 60s — a load-test run immediately after the
brute-force probe had all 25 players fail at `session`. The same applies behind a
Kubernetes Service or ingress, where all users share one source IP.
