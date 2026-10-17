-- Per-seat playback pacing: the last time a seat was taken for a playbackinfo lookup.
-- takeAccount({ playback: true }) only picks seats idle for TIDAL_PLAYBACK_MIN_INTERVAL_SECONDS.
ALTER TABLE tidal_accounts ADD COLUMN last_playback_at INTEGER NOT NULL DEFAULT 0;
