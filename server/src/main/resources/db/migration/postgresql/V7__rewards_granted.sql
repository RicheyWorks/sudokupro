-- V7: idempotent completion payouts.
--
-- POST /api/game/{id}/end re-hydrates a finished board into the active set (its
-- own ownership check calls getGame), so endGame's `activeGames.remove() != null`
-- test did not make the reward listeners idempotent: replaying that one request
-- re-fired gems, XP, achievements, streaks, and duel results every time
-- (measured: +15 gems and +15 XP per call — an unbounded currency farm).
--
-- rewards_granted records that a SOLVED board has already paid out, and is
-- persisted so the guard survives a restart or a Redis cache eviction. Existing
-- rows default to false; a finished game can therefore pay out at most once more,
-- which is the safe direction for a backfill.
alter table if exists sudoku_boards
    add column if not exists rewards_granted boolean not null default false;
