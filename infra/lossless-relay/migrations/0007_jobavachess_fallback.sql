-- Keep hippojobava2 as the last reserve while jobavachess1 can serve.
DROP TRIGGER IF EXISTS hippojobava2_promote_when_pool_dead;
DROP TRIGGER IF EXISTS hippojobava2_demote_when_pool_recovers;

CREATE TRIGGER hippojobava2_promote_when_pool_dead
AFTER UPDATE OF state ON accounts
WHEN NEW.state = 'dead' AND OLD.state != 'dead'
  AND NEW.label IN ('acct-qobuz-cooldownchess-20261030', 'acct-qobuz-cooldownchess2-20261030', 'acct-qobuz-grifties1-20261030', 'acct-qobuz-grifties2-20261030', 'acct-qobuz-hippojobava-20261031', 'acct-qobuz-jobavachess1-20261031')
  AND (SELECT COUNT(*) FROM accounts WHERE state = 'dead' AND label IN ('acct-qobuz-cooldownchess-20261030', 'acct-qobuz-cooldownchess2-20261030', 'acct-qobuz-grifties1-20261030', 'acct-qobuz-grifties2-20261030', 'acct-qobuz-hippojobava-20261031', 'acct-qobuz-jobavachess1-20261031')) = 6
BEGIN
  UPDATE accounts SET state = 'live', dead_reason = '', cooling_until = 0
  WHERE label = 'acct-qobuz-hippojobava2-20261031' AND state = 'reserve';
END;

CREATE TRIGGER hippojobava2_demote_when_pool_recovers
AFTER UPDATE OF state ON accounts
WHEN NEW.state != 'dead' AND OLD.state = 'dead'
  AND NEW.label IN ('acct-qobuz-cooldownchess-20261030', 'acct-qobuz-cooldownchess2-20261030', 'acct-qobuz-grifties1-20261030', 'acct-qobuz-grifties2-20261030', 'acct-qobuz-hippojobava-20261031', 'acct-qobuz-jobavachess1-20261031')
BEGIN
  UPDATE accounts SET state = 'reserve'
  WHERE label = 'acct-qobuz-hippojobava2-20261031' AND state = 'live';
END;
