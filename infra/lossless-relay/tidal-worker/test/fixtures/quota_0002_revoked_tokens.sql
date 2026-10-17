-- Supporter tokens withdrawn by the operator (a token posted publicly, a refunded tip). tid = the token's 32-hex
-- id; a revoked token gets the free daily quota again. Read by primary key, in the same query as the install's count.
-- Revoke: INSERT INTO revoked_tokens (tid, revoked_at, reason) VALUES ('<tid>', unixepoch(), 'posted publicly');
-- COPY of infra/lossless-quota/migrations/0002_revoked_tokens.sql (not on this branch) for the real-SQLite tests: KEEP IN SYNC.
CREATE TABLE revoked_tokens(tid TEXT PRIMARY KEY, revoked_at INTEGER NOT NULL, reason TEXT);
