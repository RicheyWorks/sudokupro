# SudokuPro

A multiplayer Sudoku platform with real-time duels, daily challenges, leaderboards, and an anti-cheat engine. Built with Spring Boot 3.2 and a JavaFX desktop client.

---

## Features

- **Puzzle engine** — backtracking generator that guarantees a unique solution at every difficulty level
- **Real-time multiplayer** — WebSocket-based duels, drip showdowns, and daily challenges via `EventEngine`
- **AI solver & hints** — logical move hints with cosmic hotspot ranking; full backtracking auto-solve
- **Leaderboards** — points, cosmic drip, hype meter, duel wins, combined skill score, and per-event rankings
- **Anti-cheat** — solve-time, move-rate, peer-skill, and cosmic-drip signal scoring with automatic flagging
- **Economy** — gems, XP, power-ups, and tier progression (Unranked → Bronze → Silver → Gold → Cosmic)
- **Themes** — Astral Nebula, Cyber Grid, Manga Mode, Retro Pixel
- **Observability** — Micrometer metrics, Spring Actuator, Redis-cached dashboard sync

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 17 |
| Framework | Spring Boot 3.2 |
| Security | Spring Security 6 + OAuth2 |
| Persistence | PostgreSQL + Spring Data JPA |
| Cache / Pub-Sub | Redis (Jedis) |
| Real-time | Spring WebSocket (STOMP) |
| API Docs | springdoc-openapi (Swagger UI) |
| Desktop client | JavaFX 21 |
| Metrics | Micrometer + Spring Actuator |
| Build | Maven |

---

## Prerequisites

- Java 17+
- PostgreSQL 14+
- Redis 7+
- Maven 3.9+ (or use the Maven wrapper if present)

---

## Quick Start

**1. Clone**

```bash
git clone https://github.com/RicheyWorks/sudokupro.git
cd sudokupro
```

**2. Configure environment**

```bash
cp .env.example .env
# Edit .env and set DB_PASSWORD and ADMIN_PASSWORD
```

Create `src/main/resources/application-local.properties` for local overrides:

```properties
spring.datasource.password=yourpassword
spring.jpa.hibernate.ddl-auto=update
spring.security.user.password=secret
```

**3. Create the database**

```sql
CREATE DATABASE sudokupro;
```

**4. Run**

```bash
# Server only (headless API):
mvn -pl server -am spring-boot:run

# Desktop app (JavaFX UI + embedded server):
mvn -pl client -am javafx:run
```

The repo is split into three Maven modules: `model` (shared domain),
`server` (Spring Boot backend — the deployable artifact), and `client`
(JavaFX desktop app). Bare local runs default to the `dev` profile.

The API is available at `http://localhost:8080`.  
Swagger UI: `http://localhost:8080/swagger-ui.html`

---

## Environment Variables

All credentials are injected via environment variables — never hardcoded.

| Variable | Default | Description |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/sudokupro` | JDBC connection URL |
| `DB_USERNAME` | `postgres` | Database user |
| `DB_PASSWORD` | *(required)* | Database password |
| `REDIS_HOST` | `localhost` | Redis hostname |
| `REDIS_PORT` | `6379` | Redis port |
| `DDL_AUTO` | `validate` | Hibernate DDL strategy (`validate` in prod, `update` locally) |
| `ADMIN_USERNAME` | `admin` | Default admin account username |
| `ADMIN_PASSWORD` | *(required)* | Default admin account password |

See `.env.example` for a full template.

---

## Project Structure

```
src/main/java/com/xai/sudokupro/
├── config/          # Security, Redis, WebSocket, app config
├── controller/      # REST controllers (game, WebSocket, admin)
├── engine/          # ChaosEngine, FateEntityManager
├── model/           # JPA entities and domain objects
├── repository/      # Spring Data JPA repositories
├── service/         # Business logic: GameService, EventEngine,
│                    #   LeaderboardService, AntiCheatEngine,
│                    #   AISolverService, NotificationService, …
├── ui/              # JavaFX desktop client
├── util/            # Constants, SecureRandomGenerator, etc.
└── websocket/       # MultiplayerBroadcaster
```

---

## API Endpoints

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/game/new` | Create a new game |
| `GET` | `/api/game/{id}` | Get game state |
| `POST` | `/api/game/{id}/move` | Submit a move |
| `GET` | `/api/game/hint` | Request a hint |
| `POST` | `/api/game/{id}/solve` | Auto-solve the board |
| `GET` | `/api/leaderboard` | Paginated leaderboard |
| `GET` | `/api/leaderboard/rank/{userId}` | Single player rank |

Full interactive docs at `/swagger-ui.html` when running locally.

---

## Running Tests

```bash
mvn test
```

---

## Security Notes

- CSRF protection enabled with `CookieCsrfTokenRepository` (WebSocket endpoints exempted)
- Security headers: CSP, HSTS (1 year), `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, `Referrer-Policy`
- All credentials sourced from environment variables — no secrets in source
- `application-local.properties` is gitignored
