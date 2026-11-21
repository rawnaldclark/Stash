-- Sessions (specs docs/superpowers/specs/2026-11-20-sessions-design.md §3 and 2026-11-20-sessions-ux.md §9;
-- plan docs/superpowers/plans/2026-11-20-sessions-step1-server.md). A host's station, its votes, heard marks and
-- followers, and the directory of sessions on the air. Phones are sha256(community key), as in posts and votes.

CREATE TABLE stations (
  id           TEXT PRIMARY KEY,               -- 8 chars [A-Za-z0-9], like a post id
  owner        TEXT NOT NULL UNIQUE,           -- sha256(key), hex: one station per phone
  name         TEXT NOT NULL,                  -- 1–40 characters
  host_name    TEXT,                           -- "Show my name as", up to 40
  vibe         TEXT,                           -- the host's line ("90s house, requests open"), up to 60
  auto_live    TEXT NOT NULL DEFAULT 'off' CHECK (auto_live IN ('off', 'schedule', 'always')),
  schedule     TEXT,                           -- JSON {tz, windows: [{day 1–7 (Mon = 1), start "HH:MM", end "HH:MM"}]}
  created_at   INTEGER NOT NULL,               -- ms
  last_live_at INTEGER,                        -- ms, the station's room's last directory write
  up           INTEGER NOT NULL DEFAULT 0,     -- counted votes (per-network cap applied), as posts
  down         INTEGER NOT NULL DEFAULT 0,
  vouched      INTEGER NOT NULL DEFAULT 0,     -- 1 once the owner restores it: votes never hide it again
  removed_at   INTEGER,                        -- ms, when the owner removed it
  removed_by   TEXT CHECK (removed_by IN ('owner') AND (removed_by IS NULL) = (removed_at IS NULL))  -- a host deletes outright
);
-- Upcoming reads every scheduled station that is still up.
CREATE INDEX stations_schedule ON stations (auto_live) WHERE auto_live = 'schedule' AND removed_at IS NULL;

-- As posts' votes: one row per phone, the network kept for the per-network cap.
CREATE TABLE station_votes (
  station_id TEXT NOT NULL,
  voter      TEXT NOT NULL,                    -- sha256(key), hex
  ip_hash    TEXT NOT NULL,                    -- the voter's network, for the per-network cap
  value      INTEGER NOT NULL CHECK (value IN (-1, 1)),
  at         INTEGER NOT NULL,
  PRIMARY KEY (station_id, voter)
);

-- The app sends one after 2 minutes listening to the station's sessions, in total. Only the hash and the station.
CREATE TABLE station_heard (
  station_id TEXT NOT NULL,
  voter      TEXT NOT NULL,
  at         INTEGER NOT NULL,                 -- ms, the first time it was sent
  PRIMARY KEY (station_id, voter)
);

CREATE TABLE station_follows (
  station_id TEXT NOT NULL,
  follower   TEXT NOT NULL,                    -- sha256(key), hex
  at         INTEGER NOT NULL,
  PRIMARY KEY (station_id, follower)
);
CREATE INDEX station_follows_follower ON station_follows (follower);

-- The sessions on the air, written by their rooms. A row its room hasn't touched for 3 minutes is ignored by every
-- read and swept by the daily cron.
CREATE TABLE live (
  station_id TEXT PRIMARY KEY,
  room       TEXT NOT NULL,                    -- the room code: public on purpose, anyone may join
  track      TEXT,                             -- SharedTrack JSON of the song playing
  listeners  INTEGER NOT NULL DEFAULT 0,
  paused     INTEGER NOT NULL DEFAULT 0,
  crowd      TEXT NOT NULL DEFAULT '[]',       -- up to 4 [{i: initial, id: memberId}], host first: no names
  started_at INTEGER NOT NULL,                 -- the room's createdAt: a newer room replaces an older one's row
  seen_at    INTEGER NOT NULL                  -- ms, the room's last write
);
