-- Promote this specific standby when its primary is marked dead (including 401,
-- UserUnauthenticated, and USER_BLOCKED). Other reserves remain untouched.
CREATE TRIGGER IF NOT EXISTS cooldownchess_promote_fallback
AFTER UPDATE OF state ON accounts
WHEN NEW.label = 'acct-qobuz-cooldownchess-20261030'
  AND NEW.state = 'dead' AND OLD.state != 'dead'
BEGIN
  UPDATE accounts SET state = 'live', dead_reason = '', cooling_until = 0
  WHERE label = 'acct-qobuz-cooldownchess2-20261030' AND state = 'reserve';
END;
