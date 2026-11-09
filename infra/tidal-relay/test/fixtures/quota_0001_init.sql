-- The daily lossless counter shared by the Qobuz and Tidal relays (and later the web player).
-- One row per UTC day and subject: 'i:<install id>' (app) or 'a:<account hash>' (website, step 4).
-- n = lossless answers served that day. Bumped with one upsert; the relays' crons prune old days.
-- COPY of infra/lossless-quota/migrations/0001_init.sql (not on this branch) for the real-SQLite tests: KEEP IN SYNC.
CREATE TABLE usage(day TEXT NOT NULL, subject TEXT NOT NULL, n INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(day, subject));
