-- Sessions scoreboard (build plan docs/superpowers/plans/2026-11-20-sessions.md, decision 10): one row a day (UTC) of
-- totals over the sessions that ended that day. Numbers only: no person, station, room or song is in them. Each
-- session's room adds its own totals once, as it closes.

CREATE TABLE session_stats (
  day              TEXT PRIMARY KEY,             -- YYYY-MM-DD, UTC, the day the sessions ended
  sessions         INTEGER NOT NULL DEFAULT 0,   -- sessions that reached the air
  listeners        INTEGER NOT NULL DEFAULT 0,   -- listeners, each counted once a session
  listener_minutes INTEGER NOT NULL DEFAULT 0,   -- their time connected, added up
  stay_under_2     INTEGER NOT NULL DEFAULT 0,   -- listeners by how long they stayed, in minutes: for the median
  stay_2_to_10     INTEGER NOT NULL DEFAULT 0,
  stay_10_to_30    INTEGER NOT NULL DEFAULT 0,
  stay_30_up       INTEGER NOT NULL DEFAULT 0,
  reacted          INTEGER NOT NULL DEFAULT 0,   -- listeners who reacted at least once in their session
  said             INTEGER NOT NULL DEFAULT 0,   -- ... said a phrase
  saved            INTEGER NOT NULL DEFAULT 0,   -- ... saved a song
  requested        INTEGER NOT NULL DEFAULT 0,   -- ... asked for a song
  requests_taken   INTEGER NOT NULL DEFAULT 0    -- requests put in the queue, by the booth or by Auto-accept
);
