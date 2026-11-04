-- Activate the last standby only after all three other designated seats are dead.
CREATE TRIGGER IF NOT EXISTS grifties2_promote_when_pool_dead
AFTER UPDATE OF state ON accounts
WHEN NEW.state = 'dead' AND OLD.state != 'dead'
  AND NEW.label IN ('acct-qobuz-cooldownchess-20261030', 'acct-qobuz-cooldownchess2-20261030', 'acct-qobuz-grifties1-20261030')
  AND (SELECT COUNT(*) FROM accounts WHERE state = 'dead' AND label IN ('acct-qobuz-cooldownchess-20261030', 'acct-qobuz-cooldownchess2-20261030', 'acct-qobuz-grifties1-20261030')) = 3
BEGIN
  UPDATE accounts SET state = 'live', dead_reason = '', cooling_until = 0
  WHERE label = 'acct-qobuz-grifties2-20261030' AND state = 'reserve';
END;

-- If any designated account is revived or returned to reserve, stop using
-- the last standby; a dead last standby must never be revived by this trigger.
CREATE TRIGGER IF NOT EXISTS grifties2_demote_when_pool_recovers
AFTER UPDATE OF state ON accounts
WHEN NEW.state != 'dead' AND OLD.state = 'dead'
  AND NEW.label IN ('acct-qobuz-cooldownchess-20261030', 'acct-qobuz-cooldownchess2-20261030', 'acct-qobuz-grifties1-20261030')
BEGIN
  UPDATE accounts SET state = 'reserve'
  WHERE label = 'acct-qobuz-grifties2-20261030' AND state = 'live';
END;
