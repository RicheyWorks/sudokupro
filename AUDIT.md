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

---

## Status update — 2026-07-10 (follow-up audit)

Fresh independent pass over current HEAD (build verified clean: 55/55 tests pass, 4 Docker-gated integration tests skip without Docker, all three module artifacts build). Spot-checked prior "resolved" items (P0-1, P0-2, P1-2, P1-4, and the Phase-6 `SudokuBoard.generateBoard` phantom-removal fix) — all still genuinely fixed, no regressions. New findings from this pass:

**P1-NEW-1 (confirmed): `SudokuGenerator` never marks retained clue cells as `given` — a sibling of the twice-fixed phantom-removal bug, in the opposite direction.** `SudokuGenerator.createFullSolution`/`solveSudoku` (model, ~L96-125) fill the board via `setValue()` but never call `setGiven(true)`. `removeNumbers` (~L127-190) only sets `given(true)` on the restore-after-failed-removal path — cells never selected for removal (the vast majority of clues) stay `isGiven=false` on the finished board. Verified directly: `initializeBoard` sets every cell `given(false)` (L88); nothing sets it back to `true` except the removal-rollback branches. Consequences: `SudokuCell.setValue`'s given-cell guard (`isGiven && value != this.value` → refuse) never protects real clues, and anything counting `isGiven` (difficulty scoring) is silently wrong. `SudokuGeneratorTest`'s `solverPreservesGivens` doesn't catch it because it keys off `isGiven()` itself. Fix: mark every cell `given(true)` right after `createFullSolution`, before `removeNumbers` runs (the removal loop's existing `setGiven(false)` before `setValue(0)` already unmarks exactly the removed cells).

**P2-NEW-1 (confirmed): the WebSocket move path has no explicit editability check, only the (now-shown-unreliable) `SudokuCell` guard.** `SudokuBoard.applyExternalMove`/`applyBatchMoves` (the path `WebSocketController`'s `"move"` case actually calls via `GameService.applyMove`) check `isValidMove` only — never `isCellEditable`. Contrast `SudokuBoard.makeMove` (L206-210), which correctly checks both. Combined with P1-NEW-1, a player can currently overwrite an original puzzle clue over `/ws/game`. Fix: add `isCellEditable` to `applyExternalMove`/`applyBatchMoves` as defense in depth (independent of fixing P1-NEW-1).

**P2-NEW-2 (confirmed): `GameLockManager` falls back to local-only locking on genuine cross-replica contention, not just Redis outage.** `acquireRedis` (server, L64-86) returns `null` identically whether Redis is down or another pod holds the lock past the 3s wait budget — either way the caller proceeds unprotected. The log message only documents the outage case. Consider rejecting/retrying longer on genuine contention instead of silently falling through.

**P2-NEW-3 (confirmed): `client/net` (Phase 6 — `ServerApi`, `GameSocket`, `GameClient`, etc.) has zero tests.** No `client/src/test` directory exists. CSRF double-submit, Basic-Auth header construction, envelope dispatch, and gameId-rejoin are all unexercised, unlike the server side's 15 test classes.

Deployment/config artifacts (Dockerfile, compose, k8s manifests, CI) remain consistent with the current module layout — no new findings there.

**P1-NEW-1 / P2-NEW-1 — fixed (2026-07-10).** `SudokuGenerator` now marks every cell `given(true)` in a new `markAllGiven` step right after `createFullSolution`, before `removeNumbers` runs; the removal loop's existing `setGiven(false)`-before-`setValue(0)` then correctly unmarks exactly the cells it removes, so every retained clue ends generation flagged `isGiven=true`. `SudokuBoard.applyExternalMove` and `applyBatchMoves` (the WebSocket move path) now also check `isCellEditable` before applying a move, as defense in depth alongside the given-cell guard in `SudokuCell.setValue`. Full suite green post-fix (55/55, all modules build); `SudokuGeneratorTest.solverPreservesGivens` — previously near-vacuous since almost every clue was mis-flagged — now meaningfully exercises the fix.

**P2-NEW-2 — fixed (2026-07-10).** `GameLockManager.acquireRedis` now distinguishes the two failure modes it previously conflated: on a Redis outage (exception) it still degrades to local-only locking and logs once, as before — correct for a single replica. On genuine cross-replica contention (Redis reachable, `setIfAbsent` returns false for the whole wait budget, no exception) it now throws `IllegalStateException` instead of silently falling through to the same degrade path, since proceeding there would defeat the exclusion the lock exists for. `GameLockManager.lock()` releases the local lock before rethrowing so the failure doesn't leak the per-game monitor. New test `GameLockManagerTest.throwsOnGenuineCrossReplicaContentionAndDoesNotLeakLocalLock` covers both the throw and the no-leak property (a second thread's acquisition attempt on the same contended game must reach — and throw from — `acquireRedis` again rather than hang forever on the local lock).

**P2-NEW-3 — fixed (2026-07-10).** Added `client/src/test/java/.../client/net/{ServerConfigTest,EnvelopeTest,ApiExceptionTest,GameSocketTest,ServerApiTest}.java` (28 tests). `ServerApiTest` runs against a real loopback `com.sun.net.httpserver.HttpServer` (JDK built-in — `ServerApi` builds its `HttpClient` internally with no seam to mock, and the client module deliberately has no Spring/Mockito dependency to preserve the pure-network-client split from Phase 6) covering session bootstrap, CSRF-token echo on mutating requests, Basic-Auth header construction, 401→auth-failure mapping, problem-detail parsing on other error statuses, and list-response deserialization. `GameSocketTest` drives envelope framing/parsing directly against a hand-written no-op `WebSocket` fake (`GameSocket`'s constructor widened from `private` to package-private for this) — multi-frame buffering, malformed-JSON dropping, missing-field defaults, and close/error callback wiring. Full suite is now 84/84 across all three modules.

---

## Hardening pass — 2026-07-10

**H-1 (new, fixed): Docker image was not actually buildable.** `.dockerignore` excluded the whole `client/` directory, but the Dockerfile explicitly `COPY client/pom.xml client/` (needed so the Maven reactor can resolve the module even though only `-pl server -am` gets built). `docker build .` failed every time with `"/client/pom.xml": not found` — Docker even emits an explicit `CopyIgnoredFile` warning naming the exact conflicting line. This means the CI "docker build gate" documented as passing in the earlier resolution log could not have been exercising a working build; either it was silently broken or the gate wasn't actually catching it. Fixed by narrowing the exclusion to `client/*` + `!client/pom.xml` (Docker requires the "exclude siblings, negate one file" form — you cannot un-ignore a file under a wholesale-excluded parent directory). Verified with a real `docker build .` (succeeds) and a full `docker compose up` smoke test (app reaches `/actuator/health` UP against real Postgres + Redis containers).

**H-2 (new, added): no brute-force protection on HTTP Basic login.** The single fixed admin account (`spring.security.user.*`) had unlimited login attempts. Added `LoginAttemptLimiter` (Redis `INCR`+`EXPIRE`, same degrade-to-local-map-on-Redis-outage shape as `GameLockManager`/`PlayerStateStore`) and `LoginAttemptFilter` (runs before `BasicAuthenticationFilter`, keyed on `request.getRemoteAddr()`, only inspects requests carrying a `Basic` `Authorization` header so health checks/WebSocket/permitAll paths are never affected). `LoginAttemptEventListener` feeds it from Spring Security's own `AbstractAuthenticationFailureEvent`/`AuthenticationSuccessEvent` (via `WebAuthenticationDetails.getRemoteAddress()`, which is the same value the filter keys on). Default: 5 failures / 60s lockout, configurable via `sudokupro.security.login.max-attempts` / `.lockout-seconds`. `LoginAttemptFilter` is intentionally not a `@Component` — Spring Boot auto-registers any `Filter`-typed bean as a second, global servlet filter, so it's wired only via `SecurityConfig`'s `addFilterBefore`, with a `FilterRegistrationBean.setEnabled(false)` to suppress the automatic one. Verified live end-to-end via `docker compose`: 5 wrong-password attempts → 401 each, 6th attempt (even with the *correct* password) → 429, `/actuator/health` unaffected throughout.

**H-3 (new, added): Kubernetes manifest had no pod/container `securityContext`.** The Dockerfile already runs as a non-root user (uid 1001), but nothing in `kubernetes/deployment.yaml` enforced that at the cluster level. Added `runAsNonRoot`/`runAsUser: 1001`/`runAsGroup`/`fsGroup`, `seccompProfile: RuntimeDefault` at the pod level, and `allowPrivilegeEscalation: false`, `readOnlyRootFilesystem: true`, `capabilities.drop: ["ALL"]` at the container level, plus an `emptyDir` `/tmp` mount (Spring Boot/Tomcat need a writable temp dir; nothing else does) and a CPU limit alongside the existing memory limit. `readOnlyRootFilesystem` was verified, not assumed: ran the built image with `docker run --read-only --tmpfs /tmp` against real Postgres/Redis containers and confirmed it starts and reports `/actuator/health` UP.

All three verified live via Docker, not just unit tests, given the blast radius of touching the authentication path and deployment manifests. Full suite: 89/89 across all three modules.

---

## Feature: real game save/load — 2026-07-16

The old `POST /api/game/save` stub was deleted under P1-3; this pass implements the real thing. Along the way it surfaced a latent state-loss bug worth recording:

**F-1 (found & fixed): the 9x9 grid never survived a database or Redis round-trip.** `SudokuBoard.board` is `@Transient` for JPA and was getter-only for Jackson, so `GameService.getGame`'s documented read-through ("the authoritative copy lives in Redis/DB") returned boards whose grid was the blank 9x9 the no-arg constructor builds — every cell 0, nothing given — whenever the board actually had to be fetched from Redis (other pod) or Postgres (cache expiry/restart). Fix, model side: a persisted `cells_json` column (Flyway `V3__add_cells_json.sql`) holding a compact per-cell snapshot (`snapshotCells()`/`restoreCells()` — value, given flag, move source, pencil marks, conflicts; restore builds the full replacement grid before swapping, so malformed snapshots leave the board untouched, and sets value before the given flag so the given-cell guard doesn't reject restores). `@PostLoad` rebuilds the grid on read; `@PrePersist` writes the snapshot for new rows. Updates deliberately do NOT use `@PreUpdate`: saves of detached boards go through JPA merge, and a `@PreUpdate` callback runs on the *managed* copy — whose grid was rebuilt from the OLD snapshot — and would clobber the fresh state. Instead `GameService` routes every board write through a single `persistBoard()` choke point that calls the explicit `syncCellsJson()` first. The Redis path gets the same treatment via a `cellsJson` Jackson property (`getCellsJson` snapshots live, `setCellsJson` restores), plus `@JsonIgnoreProperties(ignoreUnknown)` so cache entries in the old format still deserialize.

**F-2 (found & fixed, adjacent): Jackson round-trips also silently reset setter-less scalars.** `startTime`, `solved`, `solveTimeSeconds`, `moveCount`, `hintCount`, `usedUndo`, `cosmicDripLevel`, `revives`, `tensRule`, `diagonalRules` have getters but deliberately no public setters, so the Redis serializer dropped them on read. Annotated the fields with `@JsonProperty` (field-level deserialization, API surface unchanged). Relatedly, `BoardState.from` now reports `getMoveCount()` (persisted counter) instead of `getMoveHistory().size()` (transient deque, empty on any restored board).

**Feature surface.** Server: `POST /api/game/{id}/save` (explicit persist, 403 if not owner), `GET /api/game/saved?limit=` (unfinished games with a snapshot, newest first, limit capped at 50, non-cached query), `POST /api/game/{id}/resume` (read-through load + re-registration; 403 not owner / 404 unknown / 409 already solved). Service: `saveGame`/`listSavedGames`/`resumeGame` with an ownership check (`SecurityException` → 403). Repository: `findResumableByPlayerId` (filters `solved = false AND cells_json IS NOT NULL` — pre-V3 rows have no grid to restore). Client: `ServerApi.saveGame`/`savedGames`/`resumeGame`.

**Tests.** `SudokuBoardSnapshotTest` (round-trip fidelity incl. pencil marks/conflicts/move sources, restored givens still refuse modification, malformed-snapshot atomicity, Jackson-property path), `GameSaveLoadJpaTest` (@DataJpaTest on H2: real `@PrePersist`/`@PostLoad` lifecycle round-trip, `findResumableByPlayerId` scoping), plus `GameServiceTest` (owner checks, DB-fallback resume restores the grid and re-registers, limit cap) and controller/`ServerApiTest` endpoint coverage.
