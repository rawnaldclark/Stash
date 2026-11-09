ALTER TABLE tidal_accounts ADD COLUMN cooling_until INTEGER NOT NULL DEFAULT 0;
ALTER TABLE tidal_accounts ADD COLUMN dead_reason TEXT NOT NULL DEFAULT '';
ALTER TABLE tidal_device_auth ADD COLUMN account_state TEXT NOT NULL DEFAULT 'live';

-- Existing accounts stay live. Reserve rows are authorized accounts held back
-- until the operator promotes them to live in D1.
