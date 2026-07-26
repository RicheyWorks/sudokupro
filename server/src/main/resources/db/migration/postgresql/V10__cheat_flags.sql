-- V10: durable anti-cheat flags.
--
-- AntiCheatEngine.flagPlayer halved the player's cosmic_drip and returned. Nothing recorded
-- that a moderation decision had been taken: the only "flag" was a HashMap entry inside
-- AntiCheatScheduler on a single pod, erased by any restart or rolling deploy and never
-- visible to the other replicas. The penalty was durable and recurring (the scheduler
-- re-flags every 60 seconds) while the reason for it was not recorded anywhere a human
-- could read.
--
-- cheat_flag_count accumulates; first_flagged_at is written once and never overwritten
-- (so "how long has this been going on" has a real answer); last_flagged_at advances.
--
-- Everything below is wrapped in a guard, for two reasons the newly-executable
-- FlywayMigrationTest caught the moment it was first run for real:
--
--  1. `create index ... on users` is NOT covered by `alter table IF EXISTS users`. On the
--     legacy "server ran games only" install — which V4, V5, V6 and V9 all explicitly
--     support, and which has no users table at all — V10 aborted with
--     `relation "users" does not exist`, so V1..V9 committed, V10 died, and the application
--     could not start. This is verbatim the defect just fixed in V9; it was reintroduced
--     here because the guard idiom lives in the ALTER and the index statement looks
--     unrelated. PL/pgSQL plans lazily, so an early RETURN means `users` is never resolved.
--
--  2. On a database whose schema Hibernate generated from the entities (the dev profile
--     runs ddl-auto=update with Flyway disabled), `cheat_flag_count` already exists as
--     `integer not null` with NO default, because the entity field is a primitive `int`.
--     `add column if not exists` then no-ops and the missing default stays missing, so any
--     INSERT that does not name the column fails with
--     `null value in column "cheat_flag_count" violates not-null constraint`. Hibernate
--     always names it, which is why this hides until something else writes a row. The
--     default is therefore set unconditionally, and pre-existing NULLs are backfilled
--     before the constraint is asserted.
do $$
begin
    if to_regclass('users') is null then
        raise notice 'V10: no users table on this install; skipping anti-cheat flag columns';
        return;
    end if;

    alter table users add column if not exists cheat_flag_count integer;
    alter table users add column if not exists first_flagged_at timestamp;
    alter table users add column if not exists last_flagged_at  timestamp;

    -- Idempotent regardless of who created the column, and safe when it already carries
    -- the constraint: set the default first so the backfill and any concurrent insert
    -- both have a value, then fill existing rows, then assert NOT NULL.
    alter table users alter column cheat_flag_count set default 0;
    update users set cheat_flag_count = 0 where cheat_flag_count is null;
    alter table users alter column cheat_flag_count set not null;

    -- Moderation reads are "show me everyone currently flagged, newest first". Partial
    -- index so it costs nothing on the overwhelming majority of rows, which are not flagged.
    create index if not exists idx_users_cheat_flagged
        on users (last_flagged_at desc)
        where cheat_flag_count > 0;
end
$$;
