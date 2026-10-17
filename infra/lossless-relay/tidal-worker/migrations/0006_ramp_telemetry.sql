-- First-minute load-shedding state and durable per-seat Tidal 429 observations.
CREATE TABLE tidal_soft_open_state (
  id INTEGER PRIMARY KEY CHECK (id = 1),
  started_at INTEGER NOT NULL
);

CREATE TABLE tidal_upstream_429_events (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  account_id TEXT NOT NULL,
  endpoint TEXT NOT NULL,
  hour_n INTEGER NOT NULL,
  day_n INTEGER NOT NULL,
  occurred_at INTEGER NOT NULL
);
CREATE INDEX tidal_upstream_429_events_time ON tidal_upstream_429_events(occurred_at);
