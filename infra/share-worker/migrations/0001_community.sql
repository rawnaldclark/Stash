-- Stash Community (spec docs/superpowers/specs/2026-09-26-stash-community-design.md §2).

CREATE TABLE posts (
  id           TEXT PRIMARY KEY,
  kind         TEXT NOT NULL CHECK (kind IN ('playlist', 'mix', 'song')),
  title        TEXT NOT NULL,
  poster_name  TEXT NOT NULL,
  poster       TEXT NOT NULL,
  ip_hash      TEXT NOT NULL,
  summary      TEXT NOT NULL,
  body         TEXT NOT NULL,
  track_count  INTEGER NOT NULL,
  created_at   INTEGER NOT NULL,
  expires_at   INTEGER NOT NULL,
  up           INTEGER NOT NULL DEFAULT 0,
  down         INTEGER NOT NULL DEFAULT 0,
  removed_at   INTEGER
);
CREATE INDEX posts_live   ON posts (expires_at) WHERE removed_at IS NULL;
CREATE INDEX posts_poster ON posts (poster, created_at);
CREATE INDEX posts_ip     ON posts (ip_hash, created_at);

CREATE TABLE votes (
  post_id  TEXT NOT NULL,
  voter    TEXT NOT NULL,
  ip_hash  TEXT NOT NULL,
  value    INTEGER NOT NULL CHECK (value IN (-1, 1)),
  at       INTEGER NOT NULL,
  PRIMARY KEY (post_id, voter)
);

CREATE TABLE blocked (
  poster  TEXT PRIMARY KEY,
  at      INTEGER NOT NULL,
  note    TEXT
);
