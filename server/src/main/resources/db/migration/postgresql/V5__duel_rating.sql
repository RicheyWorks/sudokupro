-- V5: ELO-style duel rating (rematch/ladder feature). Everyone starts at 1000.
alter table if exists users add column if not exists duel_rating integer not null default 1000;
