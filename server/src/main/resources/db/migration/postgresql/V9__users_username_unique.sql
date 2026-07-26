-- V9: one row per username.
--
-- `username` is the application's real identity for a player — every lookup goes through
-- UserRepository.findByUsername — but nothing enforced uniqueness at any layer: no
-- @Column(unique = true) on the entity, no constraint in V1's baseline schema, and no
-- lock around the several check-then-insert callers.
--
-- EconomyService.walletFor is the worst of them:
--     findByUsername(playerId).orElseGet(() -> save(new User(null, playerId)))
-- Two requests from the same brand-new player, on two different games, take two DIFFERENT
-- per-game locks and so run fully concurrently in two READ COMMITTED transactions. Neither
-- sees the other's uncommitted INSERT, and nothing at the database level stopped the
-- second. AccountService.register races auto-provisioning the same way.
--
-- The consequence is the same one V8 documents for game_id, and worse in blast radius:
-- findByUsername returns Optional<User>, a single-result query, so from the moment a
-- duplicate exists it throws IncorrectResultSizeDataAccessException on every call. That is
-- not an IllegalArgumentException, so no caller's catch block handles it. Every hint,
-- every solve payout, every friend request naming the player, every duel wallet read, and
-- loadUserByUsername all 500 permanently — the player cannot even log in. It does not heal
-- on restart and needs manual DB surgery to undo.
--
-- Dedupe first, then constrain. Keep the row that can actually authenticate (one with a
-- password hash), then the one carrying the most progress, then the lowest id — a
-- duplicate is by definition a row created by the losing side of a race, so it holds at
-- most a few seconds of state.
--
-- Guarded on the table's existence (2026-07-26, with FlywayMigrationTest). This file used
-- to reference `users` unconditionally, so on a pre-Flyway install that never created a
-- users table — the server-runs-games-only case V4's own comment promises "still migrates
-- cleanly", and which V4/V5/V6 all guard with IF EXISTS — the whole chain died here with
-- 'ERROR: relation "users" does not exist'. V2 through V8 committed, V9 did not, and the
-- application could not start. Nothing caught it because FlywayMigrationTest was gated on
-- Docker and silently disabled itself everywhere Docker was absent.
--
-- The statements live in a PL/pgSQL block so they are only parsed when reached: PL/pgSQL
-- plans lazily, so the early RETURN means `users` is never resolved when it is absent.
--
-- OPERATORS: this edit changes the file's Flyway checksum. A database that already applied
-- the previous version of V9 must be repaired once (`flyway repair`) before its next
-- migrate, or validate-on-migrate will refuse to run.

DO $$
BEGIN
    IF to_regclass('users') IS NULL THEN
        RAISE NOTICE 'users table does not exist - nothing to dedupe or constrain.';
        RETURN;
    END IF;

    WITH ranked AS (
        SELECT id,
               row_number() OVER (
                   PARTITION BY username
                   ORDER BY (password_hash IS NOT NULL) DESC,
                            (points + gems + xp) DESC,
                            id ASC
               ) AS row_rank
        FROM users
        WHERE username IS NOT NULL
    )
    DELETE FROM users
    WHERE id IN (SELECT id FROM ranked WHERE row_rank > 1);

    -- Partial index: any legacy row with a NULL username is left alone rather than colliding
    -- with other NULLs (Postgres treats NULLs as distinct in a unique index anyway, but being
    -- explicit keeps the intent readable).
    CREATE UNIQUE INDEX IF NOT EXISTS ux_users_username
        ON users (username)
        WHERE username IS NOT NULL;
END
$$;
