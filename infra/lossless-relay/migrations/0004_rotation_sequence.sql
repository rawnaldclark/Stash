-- A logical selection order prevents multiple selections within one second from
-- repeatedly choosing the same lexicographically first account.
ALTER TABLE accounts ADD COLUMN rotation_seq INTEGER NOT NULL DEFAULT 0;
