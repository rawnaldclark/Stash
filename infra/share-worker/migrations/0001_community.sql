-- Stash Community (spec docs/superpowers/specs/2026-09-26-stash-community-design.md §2).

CREATE TABLE posts (
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
  body         TEXT NOT NULL                   -- JSON: {covers, tracks} or {track}. Last, so reading the other columns never walks past it
);
-- Must match the feed's ORDER BY exactly (12960000.0 is MS_PER_VOTE), or the list goes back to sorting every live post.
-- No index on expires_at: with literal values (wrangler d1 execute) SQLite picks it and sorts again. The Worker and the EXPLAIN test bind their values, so no test would notice one.
CREATE INDEX posts_hot    ON posts ((up - down) + created_at / 12960000.0, created_at) WHERE removed_at IS NULL;
CREATE INDEX posts_poster ON posts (poster, created_at);
CREATE INDEX posts_ip     ON posts (ip_hash, created_at);

CREATE TABLE votes (
  post_id  TEXT NOT NULL,
  voter    TEXT NOT NULL,                      -- sha256(key), hex
  ip_hash  TEXT NOT NULL,                      -- the voter's network, for the per-network cap
  value    INTEGER NOT NULL CHECK (value IN (-1, 1)),
  at       INTEGER NOT NULL,
  PRIMARY KEY (post_id, voter)
);

CREATE TABLE blocked (
  poster  TEXT PRIMARY KEY,
  at      INTEGER NOT NULL,
  note    TEXT
);
