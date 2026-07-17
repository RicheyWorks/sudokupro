-- V6: real player accounts. BCrypt hash for self-registered players; NULL for
-- rows auto-provisioned as wallets before registration existed (such usernames
-- can be claimed by the first registration that sets a hash).
alter table if exists users add column if not exists password_hash varchar(72);
