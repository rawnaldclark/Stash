-- The daily lossless counter shared by the Qobuz and Tidal relays (and later the web player).
-- One row per UTC day and subject: 'i:<install id>' (app) or 'a:<account hash>' (website, step 4).
-- n = lossless answers served that day. Bumped with one upsert; the relays' crons prune old days.
-- KEEP IN SYNC: infra/lossless-relay/tidal-worker/test/fixtures/quota_0001_init.sql is a copy for its tests.
CREATE TABLE usage(day TEXT NOT NULL, subject TEXT NOT NULL, n INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(day, subject));
