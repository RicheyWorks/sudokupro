# SudokuPro

A multiplayer Sudoku platform with real-time duels, daily challenges, leaderboards, and an anti-cheat engine. Spring Boot 3.2 backend, JavaFX desktop client, deployable to Docker or Kubernetes (multi-replica ready).

```
JavaFX client ──REST /api/** + WebSocket /ws/game──▶ Spring Boot server ──▶ PostgreSQL
                                                            │
                                                            └──▶ Redis (cache, locks, cross-replica pub/sub)
```

---

## Modules

```
sudokupro/
├── model/    Shared domain: board, cells, moves, generator, constants,
│             and the wire records (model.api). No web, no JavaFX.
├── server/   Spring Boot backend. Produces the deployable boot jar
│             (sudokupro-server-*-exec.jar). All tests live here.
└── client/   JavaFX desktop app. Owns all JavaFX dependencies and the
              per-OS platform profiles. Pure network client: depends only
              on model and talks to the server over REST + WebSocket.
```

---

## Features

- **Puzzle engine** — backtracking generator with a verified unique solution at every difficulty
- **Real-time multiplayer** — raw-WebSocket duels, drip showdowns, and daily challenges; broadcasts fan out across server replicas via Redis pub/sub
- **AI solver & hints** — logical move hints with cosmic hotspot ranking; full backtracking auto-solve
- **Leaderboards** — points, cosmic drip, hype meter, duel wins, combined skill score
- **Anti-cheat** — solve-time, move-rate, complexity, and peer-skill signal scoring with automatic flagging (random flavor mechanics deliberately excluded from enforcement)
- **Economy** — gems, XP, power-ups, and tier progression (Unranked → Bronze → Silver → Gold → Cosmic)
- **Themes** — Astral Nebula, Cyber Grid, Manga Mode, Retro Pixel (saved locally per machine)
- **Observability** — Micrometer metrics and Spring Actuator health (db, Redis, disk, and a game-engine self-test at `/actuator/health`)

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 17 |
| Framework | Spring Boot 3.2 |
| Security | Spring Security 6 + OAuth2, fail-fast credential guard |
| Persistence | PostgreSQL + Spring Data JPA, Flyway migrations |
| Cache / Pub-Sub / Locks | Redis (Spring Data Redis + Jedis) |
| Real-time | Raw WebSocket (`/ws/game`) with cross-replica Redis relay |
| API docs | springdoc-openapi (Swagger UI) |
| Desktop client | JavaFX 21 + JDK `java.net.http` (REST & WebSocket) |
| Testing | JUnit 5, Mockito, H2, Testcontainers (Postgres + Redis) |
| Build / Deploy | Maven multi-module, Docker, docker-compose, Kubernetes, GitHub Actions |

---

## Quick Start

### 1. Start a server

**Docker Compose** (app + Postgres + Redis):

```bash
cp .env.example .env    # set real DB_PASSWORD and ADMIN_PASSWORD
docker compose up --build
```

The app runs with the `prod` profile: startup **fails on missing or well-known
credentials** (`secret`, `sudoku123`, `CHANGE_ME`, …) by design. Flyway creates
and migrates the schema automatically.

**Or run it locally** — prerequisites: Java 17+, Maven 3.9+, PostgreSQL 14+
(Redis 7+ optional; the app degrades gracefully without it):

```bash
createdb sudokupro       # or: CREATE DATABASE sudokupro;
mvn -pl server -am spring-boot:run
```

The `dev` profile is the default for bare local runs and ships working local
defaults (Hibernate `ddl-auto=update`, Flyway off). Production must set
`SPRING_PROFILES_ACTIVE=prod` and provide real credentials via the environment.

- API: `http://localhost:8080`
- Swagger UI: `http://localhost:8080/swagger-ui.html`
- Health: `http://localhost:8080/actuator/health`

### 2. Start the desktop client

```bash
mvn -pl client -am javafx:run
```

The client is a pure network client — it never loads server code. The welcome
screen prefills `http://localhost:8080` / `admin` (override via
`SUDOKUPRO_SERVER`, `SUDOKUPRO_USER`, `SUDOKUPRO_PASS`); enter the password and
connect. All gameplay flows over REST (`/api/**`) and the WebSocket channel
(`/ws/game`). Undo/redo round-trip through the server, which stays
authoritative for every board.

---

## Environment Variables

All credentials are injected via environment variables — never hardcoded.
Outside the `dev`/`test` profiles, `SecretsGuard` refuses to start on missing
or well-known values.

**Server**

| Variable | Default | Description |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/sudokupro` | JDBC connection URL |
| `DB_USERNAME` | `postgres` | Database user |
| `DB_PASSWORD` | *(required outside dev)* | Database password |
| `REDIS_HOST` | `localhost` | Redis hostname |
| `REDIS_PORT` | `6379` | Redis port |
| `DDL_AUTO` | `validate` | Hibernate DDL strategy — Flyway owns the schema |
| `ADMIN_USERNAME` | `admin` | Default admin account username |
| `ADMIN_PASSWORD` | *(required outside dev)* | Default admin account password |

**Desktop client**

| Variable | Default | Description |
|---|---|---|
| `SUDOKUPRO_SERVER` | `http://localhost:8080` | Server base URL |
| `SUDOKUPRO_USER` | `admin` | Username (HTTP Basic) |
| `SUDOKUPRO_PASS` | *(empty)* | Password — also editable on the welcome screen |

See `.env.example` for a full template.

---

## Database Migrations

Flyway owns the schema; Hibernate only validates it.

- `server/src/main/resources/db/migration/common/` — portable migrations.
  `V1__baseline_schema.sql` was generated by Hibernate itself from the JPA
  entities, so `validate` passes by construction.
- `server/src/main/resources/db/migration/postgresql/` — vendor-specific
  migrations (e.g. `V2` converts the legacy `start_time` BIGINT column).
- Pre-Flyway databases are baselined automatically
  (`baseline-on-migrate=true`, `baseline-version=1`): V1 is skipped, V2+ run.

Both the fresh-install and legacy-upgrade paths are verified against real
PostgreSQL in CI (`FlywayMigrationTest`).

---

## Scaling

The Kubernetes deployment supports multiple replicas, provided a shared Redis
is available:

- Boards are Redis/DB-backed; each pod keeps only a cache.
- Player streaks, cosmic points, and input locks live in Redis (`PlayerStateStore`).
- Game mutations take a cross-replica Redis lock (`GameLockManager`).
- WebSocket broadcasts fan out to all pods via Redis pub/sub (`RedisBroadcastRelay`),
  so players in the same game see each other regardless of which pod they hit.

Without Redis, every component degrades to single-replica behavior (logged once).
Cross-pod delivery is verified by a two-pod integration test on real Redis in CI.

---

## API

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/game/new?difficulty=1..4&chaos=&mirror=` | Create a new game for the authenticated player |
| `GET` | `/api/game/{gameId}` | Current board state (player-visible projection — never the solution) |
| `POST` | `/api/game/{gameId}/solve` | AI auto-solve |
| `POST` | `/api/game/{gameId}/end` | End/leave a game (state persisted server-side) |
| `GET` | `/api/game/hint` | AI hint for the player's active game |
| `GET` | `/api/session` | Auth check + CSRF bootstrap for API clients |
| `GET` | `/api/leaderboard?limit=` | Public leaderboard |
| `GET` | `/api/events` | Active live events |
| `WS` | `/ws/game` | Gameplay channel (authenticated principal required) |
| `GET` | `/admin/constants` | Game constants (admin) |
| `POST` | `/admin/constants/reload` | Hot-reload constants (admin) |
| `GET` | `/actuator/health` | Health: db, Redis, disk, game-engine self-test |

WebSocket envelope format: `{"type", "from", "payload"}` — client-to-server
types: `move`, `join`, `chat`, `undo`, `redo`, `sync`; server-to-client adds
`board` (full-state resync), `leave`, `event`, `status`, `hint`, `error`.
Join an existing game by connecting to `/ws/game?gameId=...`.

Full interactive docs at `/swagger-ui.html` when running locally.

---

## Testing

```bash
mvn test
```

Unit and context tests run anywhere (H2-backed, no local services needed).
Four integration tests are Docker-gated and skip automatically without Docker:
Flyway migrations on real PostgreSQL and cross-replica broadcast on real Redis.
CI (GitHub Actions) runs the full suite including the Docker-gated tests, then
builds the server Docker image.

---

## Security Notes

- Unauthenticated WebSocket connections are rejected at session establishment
- The board sent to clients is a projection (`BoardState`) — the solution never leaves the server
- `SecretsGuard` fails startup on missing or well-known credentials outside dev/test
- CSRF protection enabled with `CookieCsrfTokenRepository`; API clients bootstrap the
  double-submit token via `GET /api/session` (WebSocket endpoints exempted)
- Security headers: CSP, HSTS (1 year), `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, `Referrer-Policy`
- WebSocket origins restricted via `sudokupro.ws.allowed-origins` (defaults to localhost)
- All credentials sourced from environment variables — no secrets in source

See `AUDIT.md` for the full code-health audit and its resolution history.
