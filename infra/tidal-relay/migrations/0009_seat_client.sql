-- Which Tidal OAuth client a seat's tokens belong to. 'device' = the Android device-flow client (TIDAL_CLIENT_ID,
-- the original seats; Tidal hands their DASH FLAC out on sp-ad-fa.audio.tidal.com, which refuses foreign browser
-- Origins). 'pkce' = python-tidal's PKCE client (TIDAL_PKCE_CLIENT_ID): its DASH FLAC comes from the CloudFront host
-- sp-ad-cf.audio.tidal.com, which answers any Origin, so only these "web seats" can serve ?web=mse (browser MSE).
-- Existing seats are device seats.
ALTER TABLE tidal_accounts ADD COLUMN client TEXT NOT NULL DEFAULT 'device';
