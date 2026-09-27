-- Adds posts.vouched by rebuilding posts: ADD COLUMN could only put it after body, which must stay last.

CREATE TABLE posts_new (
  id           TEXT PRIMARY KEY,               -- 8 chars [A-Za-z0-9], like a mix id
  kind         TEXT NOT NULL CHECK (kind IN ('playlist', 'mix', 'song')),
  title        TEXT NOT NULL,                  -- playlist/mix name, or the song title
  poster_name  TEXT NOT NULL,                  -- "Show my name as" at posting time
  poster       TEXT NOT NULL,                  -- sha256(key), hex
  ip_hash      TEXT NOT NULL,                  -- the network, hex
  summary      TEXT NOT NULL,                  -- small JSON for the list: {covers} or {art, artist}
  track_count  INTEGER NOT NULL,
  created_at   INTEGER NOT NULL,               -- ms
  expires_at   INTEGER NOT NULL,               -- created_at + 30 days
  up           INTEGER NOT NULL DEFAULT 0,     -- counted votes (per-network cap applied)
  down         INTEGER NOT NULL DEFAULT 0,
  removed_at   INTEGER,                        -- ms, when it was taken down or removed
  removed_by   TEXT CHECK (removed_by IN ('poster', 'owner') AND (removed_by IS NULL) = (removed_at IS NULL)),  -- who removed it; restore only undoes 'owner'
  vouched      INTEGER NOT NULL DEFAULT 0,     -- 1 once the owner restores it: votes never hide it again
  body         TEXT NOT NULL                   -- JSON: {covers, tracks} or {track}. Last, so reading the other columns never walks past it
);
INSERT INTO posts_new (id, kind, title, poster_name, poster, ip_hash, summary, track_count, created_at, expires_at, up, down, removed_at, removed_by, body)
  SELECT id, kind, title, poster_name, poster, ip_hash, summary, track_count, created_at, expires_at, up, down, removed_at, removed_by, body FROM posts;
DROP TABLE posts;
ALTER TABLE posts_new RENAME TO posts;
-- Must match the feed's ORDER BY exactly (12960000.0 is MS_PER_VOTE), or the list goes back to sorting every live post.
-- No index on expires_at: with literal values (wrangler d1 execute) SQLite picks it and sorts again. The Worker and the EXPLAIN test bind their values, so no test would notice one.
CREATE INDEX posts_hot    ON posts ((up - down) + created_at / 12960000.0, created_at) WHERE removed_at IS NULL;
CREATE INDEX posts_poster ON posts (poster, created_at);
CREATE INDEX posts_ip     ON posts (ip_hash, created_at);
