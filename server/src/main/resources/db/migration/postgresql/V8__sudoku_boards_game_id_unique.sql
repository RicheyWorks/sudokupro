-- V8: one row per game_id.
--
-- game_id is the application's real identity for a board (the primary key is a
-- surrogate bigserial), but nothing enforced uniqueness. The join/accept paths probe
-- for an existing game and then insert without holding a lock across both steps, so a
-- double-clicked "Join daily", a retried POST, or two replicas racing the first join
-- of the day could insert TWO rows with the same game_id.
--
-- That is not a cosmetic duplicate: GameRepository.findByGameId is a single-result
-- query, so from then on every read that misses the per-pod cache — another replica, a
-- pod restart, a Redis TTL expiry — throws IncorrectResultSizeDataAccessException.
-- It is not an IllegalArgumentException, so the callers' catch blocks do not handle it
-- and getGame/hint/save/resume/end all 500 permanently for that game id.
--
-- Dedupe first, then constrain. Keep the most useful surviving row per game_id:
-- prefer one that actually has a grid snapshot, then the most recently started, then
-- the highest surrogate id. Rows losing the tie-break are duplicates of a board the
-- player could not reach anyway — the single-result query was already failing on them.
delete from sudoku_boards
where id in (
    select id
    from (
        select id,
               row_number() over (
                   partition by game_id
                   order by (cells_json is not null) desc,
                            start_time desc nulls last,
                            id desc
               ) as row_rank
        from sudoku_boards
        where game_id is not null
    ) ranked
    where row_rank > 1
);

-- Partial index: legacy rows with a NULL game_id (pre-dating the column being populated)
-- are left alone rather than colliding with each other.
create unique index if not exists ux_sudoku_boards_game_id
    on sudoku_boards (game_id)
    where game_id is not null;
