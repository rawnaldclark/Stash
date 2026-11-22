-- Sessions step 5, regulars (spec docs/superpowers/specs/2026-11-20-sessions-ux.md §7): how many of a station's
-- sessions a listener has stayed 10 minutes or more for. Three makes them a regular there. The listener is
-- sha256(community key), as everywhere; a row not added to for 60 days is swept by the daily cron.

CREATE TABLE station_regulars (
  station_id TEXT NOT NULL,
  listener   TEXT NOT NULL,                    -- sha256(key), hex
  sessions   INTEGER NOT NULL DEFAULT 1,       -- sessions stayed for, each counted once by its room
  last_at    INTEGER NOT NULL,                 -- ms, the last one
  PRIMARY KEY (station_id, listener)
);
-- The sweep walks it by age.
CREATE INDEX station_regulars_age ON station_regulars (last_at);
