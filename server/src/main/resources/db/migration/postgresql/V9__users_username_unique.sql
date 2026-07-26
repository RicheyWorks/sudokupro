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
with ranked as (
    select id,
           row_number() over (
               partition by username
               order by (password_hash is not null) desc,
                        (points + gems + xp) desc,
                        id asc
           ) as row_rank
    from users
    where username is not null
)
delete from users
where id in (select id from ranked where row_rank > 1);

-- Partial index: any legacy row with a NULL username is left alone rather than colliding
-- with other NULLs (Postgres treats NULLs as distinct in a unique index anyway, but being
-- explicit keeps the intent readable).
create unique index if not exists ux_users_username
    on users (username)
    where username is not null;
