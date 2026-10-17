-- Shared catalog and short-lived playback response cache. Search keys are SHA-256
-- digests of normalized query text; response payloads expire per entry.
CREATE TABLE tidal_response_cache (
  cache_key TEXT PRIMARY KEY,
  response_json TEXT NOT NULL,
  expires_at INTEGER NOT NULL
);
CREATE INDEX tidal_response_cache_expiry ON tidal_response_cache(expires_at);
