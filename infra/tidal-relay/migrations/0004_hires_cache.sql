-- Per-track "has a Hi-Res lossless master" flag for the ?aac=only answer.
-- D1, not the Cache API: this worker runs on *.workers.dev, where cache.put is a no-op.
CREATE TABLE tidal_hires_cache (
  track_id TEXT PRIMARY KEY,
  hires INTEGER NOT NULL,
  checked_at INTEGER NOT NULL
);
