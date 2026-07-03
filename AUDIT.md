# SudokuPro — Code Health & Security Audit

**Date:** 2026-07-01
**Scope:** Full source tree (60 classes, ~12,900 LOC), config, build, deployment artifacts.
**Baseline:** Compiles clean on Java 21 (target 17). Tests: 4/5 pass — `ConstantsTest` fails only because it requires a live Postgres on localhost:5432.

---

## State of the working tree

The repo had 56 modified files uncommitted (~8k insertions). Triage shows most of it is line-ending churn; the substantive ~1,100 lines are deliberate repair work: restoration of `FateEntityManager` entity classes that a previous edit had elided with a placeholder comment, JavaFX per-OS Maven profiles (fixes builds on Windows/mac/linux), a `RawWebSocketConfig` with an origin-restriction fix, and a `db/migrate_start_time.sql` migration. This work is sound and should be committed. A `.gitattributes` file would stop the line-ending noise permanently.

---

## Findings

### P0 — fix before any public exposure

**P0-1: Unauthenticated WebSocket gameplay.** `/ws/**` is `permitAll` in `SecurityConfig`, and `WebSocketController.extractPlayerId()` falls back to `"player_" + session.getId()` when there is no principal. Anyone can connect anonymously, submit moves, and appear in game flows. Worse, every anonymous connection auto-creates a game (`gameBoards.computeIfAbsent → createNewGame`), so an attacker can create unbounded server-side game state with no credentials — `GameService` caps `activeGames` at 10,000 but `WebSocketController.gameBoards` is a separate, uncapped map. Fix: require an authenticated principal at handshake (reject in `afterConnectionEstablished` when `getPrincipal()` is null), and make `WebSocketController` use `GameService` as the single source of game state.

**P0-2: Weak defaults ship enabled.** `application.properties` silently falls back to `DB_PASSWORD:sudoku123` and an `admin`/`secret` basic-auth user with the ADMIN role. Unlike a fail-fast design, a production deployment that forgets the env vars runs happily with guessable credentials guarding `/admin/**` (including `/admin/constants/reload`). Fix: remove the fallback defaults for `DB_PASSWORD` and `ADMIN_PASSWORD` (or fail startup on placeholder values outside a dev profile).

**P0-3: Schema auto-mutation defaults on.** `spring.jpa.hibernate.ddl-auto=${DDL_AUTO:update}` means production silently alters the schema unless someone remembers the env var. The comment in the file even says validate is required in production. Fix: default to `validate`; opt into `update` only in a dev profile. The presence of a raw `db/migrate_start_time.sql` (applied by hand, no migration tool) makes this worse — adopt Flyway.

### P1 — significant health and correctness issues

**P1-1: Test coverage is ~zero.** 5 tests across 60 classes, and the only Spring context test needs a live local Postgres. `src/test/resources/test-application.properties` is never loaded — that filename isn't a Spring convention (should be `application-test.properties` + `@ActiveProfiles("test")`, or H2/Testcontainers). The core engine (generator uniqueness, solver, anti-cheat scoring, WebSocket protocol) is untested.

**P1-2: Server and desktop client share one artifact.** The Spring Boot backend and the JavaFX client (`ui/`, launched conditionally from `SudokuProApplication.main`) live in one Maven module. Every server build drags in platform-classified JavaFX natives; every client change risks the server. Split into `server` / `client` / shared `model` modules.

**P1-3: Misleading stub endpoint.** `POST /api/game/save` returns `{"status":"saved","gameId":"TBD"}` and persists nothing. A client will believe saves succeeded and lose data. Return 501 like its siblings (`load`, `move`, `validate` — currently commented out) or implement it.

**P1-4: Redis configuration split-brain.** `RedisConfig` (`@Profile("redis")`) builds a JedisPool from custom `spring.redis.*` `@Value` bindings, while Spring Boot 3.2's own autoconfiguration reads `spring.data.redis.*`. Any Boot-autoconfigured `RedisTemplate`/cache uses different properties than the hand-built pool — two clients, two configs, one namespace silently ignored. Consolidate on `spring.data.redis.*` and Boot's connection factory.

**P1-5: Dead push-notification integration.** `fcm.server-key` targets FCM's legacy server-key API, which Google shut down in 2024. The integration cannot work; it needs the HTTP v1 API (OAuth2 service account) or removal.

**P1-6: Deployment artifacts are fake.** `Dockerfile` and `docker-compose.yml` are 3-byte placeholders, yet `kubernetes/` manifests exist that presumably reference an image no build produces. CI (`test-constants.yml`) exercises only constants. Nothing in the repo can actually be deployed as-is.

**P1-7: In-memory game state defeats the k8s story.** `GameService.activeGames`, streaks, and locks are per-JVM `ConcurrentHashMap`s. With >1 replica, players land on different pods with different game state. Either document single-replica deployment or move hot state to Redis (which the codebase already has plumbing for).

### P2 — maintainability

**P2-1: God classes in `util`.** `SudokuHealthMonitor` (1,532 lines) reimplements health checking — entropy, disk space, Redis pings — that Spring Actuator (already a dependency) provides as `HealthIndicator`s. `SecureRandomGenerator` (832 lines) wraps `SecureRandom` with an enormous API surface. Both should shrink dramatically.

**P2-2: Duplicate WebSocket stacks.** STOMP (`WebSocketConfig` + `SimpMessagingTemplate`) and raw WebSocket (`RawWebSocketConfig` + `WebSocketController`) coexist deliberately, but game state and broadcast logic are duplicated between paths. Long-term, converge on one.

**P2-3: Theme/flavor coupling in core logic.** Anti-cheat signals include "cosmic drip" mechanics from `FateEntityManager`'s RNG events. Fun, but scoring cheaters on random-event outcomes invites false positives; keep flavor systems out of enforcement inputs.

---

## Recommended roadmap

**Phase 0 (security baseline):** P0-1 WebSocket auth, P0-2 secret fail-fast, P0-3 ddl-auto=validate + Flyway. Commit the pending repair work and add `.gitattributes` first so history stays reviewable.

**Phase 1 (trustworthy tests):** test profile with H2 or Testcontainers; unit tests for generator uniqueness, solver, `GameService` move/lock logic, anti-cheat scoring; a WebSocket protocol integration test. Target: every P0 fix lands with a regression test.

**Phase 2 (honest deployment):** real Dockerfile + compose (app/Postgres/Redis), CI that builds and runs the full suite, reconcile the k8s manifests, decide the replica story (P1-7).

**Phase 3 (structure):** split client/server/model modules (P1-2), consolidate Redis config (P1-4), fix or remove save/FCM stubs (P1-3, P1-5), shrink the util god classes (P2-1).

---

*Baseline evidence: `mvn test-compile` clean; surefire 4/5 green (ConstantsTest fails on missing localhost Postgres — environmental). Dependency survey and file-level findings from direct source inspection, 2026-07-01.*

---

## Status update — 2026-07-02

**Done** (commits 701581f, 0137871, c08e35e, + Phase 3a):
- **P0-1/P0-2/P0-3** — WebSocket auth required, SecretsGuard fail-fast (also rejects CHANGE_ME), ddl-auto=validate with dev-profile opt-out. Regression tests in place.
- **P1-1** — Test profile on H2 (`application-test.properties` + `@ActiveProfiles`); suite now 25 tests, green without local infra. New generator tests exposed and fixed two engine bugs (phantom cell removals; validateBoard self-collision).
- **P1-3** — Dead commented save/load/move/validate stub block deleted.
- **P1-4** — Consolidated on `spring.data.redis.*`: one JedisPool (AppConfig, reads canonical props), RedisConfig's duplicate pool and custom `spring.redis.*` bindings removed; app-level knobs moved to `sudokupro.redis.*`.
- **P1-5** — Dead FCM legacy integration removed (PushNotificationService, `fcm.server-key`, FCM_SERVER_KEY). NotificationService queue + rate-limit retained as a hook for a future HTTP-v1 integration.
- **P1-6** — Real multi-stage Dockerfile, compose stack (Postgres/Redis, healthchecks), k8s manifests (secret-backed creds, actuator probes), full-suite CI + docker build gate. Headless mode added (`sudokupro.ui.enabled`) since JavaFX cannot start in containers.
- **P1-7** — Documented: k8s replicas pinned to 1 with rationale inline.
- **P2-1** — SecureRandomGenerator 833→151 lines (64 unused methods cut). SudokuHealthMonitor (1,532 lines) deleted: infrastructure checks now come from Boot's autoconfigured HealthIndicators (db, redis, diskSpace); the one genuinely custom check lives in `GameEngineHealthIndicator` (solver self-test + active-game count), feeding /actuator/health, which the Docker HEALTHCHECK and k8s probes already hit.
- **Flyway adopted** — `flyway-core` + V1 baseline (Hibernate-generated PostgreSQL DDL) + V2 (legacy start_time BIGINT→TIMESTAMP, vendor-specific location). `baseline-on-migrate=true, baseline-version=1` so pre-Flyway databases skip V1 and apply V2+. Dev profile keeps ddl-auto=update with Flyway off; everywhere else Flyway owns the schema and Hibernate validates. compose now defaults DDL_AUTO=validate.

- **P2-2** — Converged on the raw WebSocket stack. New `GameSessionRegistry` (gameId→sessions, playerId→session) is the single transport, shared by WebSocketController and MultiplayerBroadcaster; the STOMP broker (`WebSocketConfig`, `/ws/stomp`, SimpMessagingTemplate) is deleted — no client ever subscribed to it, so every server-initiated broadcast went nowhere. Broadcaster's public API unchanged; envelope format `{type, from, payload}` unchanged. Scoping covered by GameSessionRegistryTest.
- **P2-3** — Cosmic-drip signals removed from anti-cheat enforcement (they score RNG outcomes from FateEntityManager events, not player behavior). Spikes are still logged for observability; timing/skill/move-rate signals unchanged.

- **P1-2** — Module split done: `model` (shared domain, no web/JavaFX), `server` (Spring Boot backend, produces `sudokupro-server-*-exec.jar`; no JavaFX natives), `client` (JavaFX desktop app + per-OS profiles; embeds the server in-process via `ClientLauncher`, same single-JVM behavior). Decouplings: SudokuBoard→`MoveBroadcaster` interface instead of MultiplayerBroadcaster, `replayMoves` takes a `Consumer<String>` instead of a JavaFX TextArea, Constants→`DifficultyTuner` interface instead of TelemetryService; dead `SudokuSerializer` deleted. Docker builds only `-pl server -am`.

**All audit items resolved.** Completed follow-ups: Testcontainers Flyway migration test (fresh + legacy-upgrade paths, Docker-gated); multi-replica game state (Phase 5) — `RedisBroadcastRelay` fans WebSocket broadcasts across pods via Redis pub/sub with origin-id loop prevention, `PlayerStateStore` moves streaks/cosmic points/input locks to Redis, and `GameLockManager` guards game mutations with a cross-replica SET NX PX lock (token-checked release). Everything degrades to single-replica behavior when Redis is down, logged once. Cross-pod delivery proven by a Docker-gated two-pod integration test.

**Client/server network separation — done (2026-07-02).** The client no longer embeds the server: `client` depends only on `model` and speaks to a running server over REST + WebSocket (`ServerApi`/`GameSocket`/`GameClient` in `client/net`). Server side grew the missing API surface: `BoardState` (a player-visible projection shared via `model.api` — the solution never crosses the wire), game lifecycle REST endpoints (get/solve/end), `/api/session` (auth check + CSRF double-submit bootstrap for non-browser clients), `/api/leaderboard`, `/api/events`, WS message types `chat`/`undo`/`redo`/`sync`, and a `?gameId=` handshake interceptor for rejoining games. UI concerns that lived on server beans became client-local (notifications panel, theme preference in `~/.sudokupro/`). Undo/redo round-trip through the server, which broadcasts the authoritative board to every session in the game.

Found along the way: `SudokuBoard.generateBoard` had the same phantom-removal bug Phase 1 fixed in `SudokuGenerator` — `setValue(0)` silently refuses to modify a cell while `isGiven` is true, so "removed" cells were counted but never cleared and every board shipped with only 4–9 empty cells instead of 28–49. Fixed (un-mark before clearing) and pinned by `BoardStateTest`.

No remaining audit follow-ups.
