# stash-quota (D1)

The daily lossless counter (design:
[`docs/superpowers/specs/2026-10-17-lossless-daily-counter-design.md`](../../docs/superpowers/specs/2026-10-17-lossless-daily-counter-design.md)).
One table, `usage(day, subject, n)`: `day` is the UTC date (`YYYY-MM-DD`), `subject` is `i:<install id>` for the app
(`a:<account hash>` is reserved for the website), `n` the lossless answers served that day.

Bound as `QUOTA_DB` in `infra/tidal-relay/wrangler.toml` (`stash-tidal-relay`). The relay reads the row before serving
a lossless answer, bumps it with one upsert after, and prunes rows older than 7 days in its hourly cron. The Qobuz
relay (`stash-relay`) counted here too until its pool was retired in 2026-11; its source (formerly
`infra/lossless-relay`) is no longer in the repo.

Migration `0002_revoked_tokens.sql` adds `revoked_tokens(tid PRIMARY KEY, revoked_at, reason)`: supporter tokens the
operator has withdrawn (they get the free quota again). Supporter answers also count under subject `t:<tid>` for the
per-token daily ceiling (`SUPPORTER_DAILY_CAP`). Revoke a token (takes effect on the next request; the tid is in the
token's first segment, base64url JSON):

```powershell
npx wrangler d1 execute stash-quota --remote --config infra/lossless-quota/wrangler.toml --command "INSERT INTO revoked_tokens (tid, revoked_at, reason) VALUES ('<32 hex tid>', unixepoch(), 'posted publicly')"
```

Un-revoke: `npx wrangler d1 execute stash-quota --remote --config infra/lossless-quota/wrangler.toml --command "DELETE FROM revoked_tokens WHERE tid = '<tid>'"`.

This folder is not a Worker; `wrangler.toml` only carries the binding so the schema migrates on its own:

```powershell
npx wrangler d1 migrations apply stash-quota --remote --config infra/lossless-quota/wrangler.toml
```

Apply it before deploying the relay with the `QUOTA_DB` binding. A relay without the binding skips the counter
(and logs `QUOTA_DB not bound` once per isolate). The Tidal relay's tests use a copy of the migration
(`infra/tidal-relay/test/fixtures/quota_0001_init.sql`, `quota_0002_revoked_tokens.sql`): change both together.

Inspect a day: `npx wrangler d1 execute stash-quota --remote --config infra/lossless-quota/wrangler.toml --command "SELECT subject, n FROM usage WHERE day = date('now') ORDER BY n DESC LIMIT 25"`.
