-- One pacing gap per seat for EVERY upstream Tidal call (search, playback, metadata), and
-- an escalating 429 cooldown. Tidal's 429 is a short-window burst limit with no Retry-After:
-- a seat idle for 35 min was refused on its 5th call in 14 s, yet answered again minutes later.
ALTER TABLE tidal_accounts ADD COLUMN last_call_at INTEGER NOT NULL DEFAULT 0;
ALTER TABLE tidal_accounts ADD COLUMN strikes INTEGER NOT NULL DEFAULT 0;
UPDATE tidal_accounts SET last_call_at = MAX(last_used_at, last_playback_at);
-- Every cached not_available was written 04:56-05:03 UTC 2026-10-16, the post-unpause
-- stampede: throttled refusals, not real "not on Tidal" answers. Drop them.
DELETE FROM tidal_response_cache WHERE response_json LIKE '%"not_available"%';
