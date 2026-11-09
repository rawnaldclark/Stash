CREATE TABLE tidal_accounts (
  id TEXT PRIMARY KEY,
  access_token TEXT NOT NULL,
  refresh_token TEXT NOT NULL,
  token_expires_at INTEGER NOT NULL DEFAULT 0,
  state TEXT NOT NULL DEFAULT 'live',
  hour_key TEXT NOT NULL DEFAULT '',
  hour_n INTEGER NOT NULL DEFAULT 0,
  day_key TEXT NOT NULL DEFAULT '',
  day_n INTEGER NOT NULL DEFAULT 0,
  last_used_at INTEGER NOT NULL DEFAULT 0,
  generation INTEGER NOT NULL DEFAULT 1,
  refresh_lock_until INTEGER NOT NULL DEFAULT 0,
  updated_at INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE tidal_device_auth (
  device_code TEXT PRIMARY KEY,
  user_code TEXT NOT NULL,
  expires_at INTEGER NOT NULL,
  poll_interval INTEGER NOT NULL DEFAULT 5,
  next_poll_at INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE tidal_dash_manifests (
  track_id TEXT PRIMARY KEY,
  manifest_xml TEXT NOT NULL,
  init_url TEXT NOT NULL,
  expires_at INTEGER NOT NULL
);
CREATE INDEX tidal_dash_manifests_expiry ON tidal_dash_manifests(expires_at);
