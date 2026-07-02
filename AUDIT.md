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
