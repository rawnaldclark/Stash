# stash-quota (shared D1)

The daily lossless counter both lossless relays share (design:
[`docs/superpowers/specs/2026-10-17-lossless-daily-counter-design.md`](../../docs/superpowers/specs/2026-10-17-lossless-daily-counter-design.md)).
One table, `usage(day, subject, n)`: `day` is the UTC date (`YYYY-MM-DD`), `subject` is `i:<install id>` for the app
(`a:<account hash>` is reserved for the website), `n` the lossless answers served that day.

Bound as `QUOTA_DB` in `infra/lossless-relay/wrangler.toml` (Qobuz relay) and
`infra/lossless-relay/tidal-worker/wrangler.toml` (Tidal relay). Both relays read the row before serving a lossless
answer, bump it with one upsert after, and prune rows older than 7 days in their hourly cron.

Migration `0002_revoked_tokens.sql` adds `revoked_tokens(tid PRIMARY KEY, revoked_at, reason)`: supporter tokens the
operator has withdrawn (they get the free quota again). Supporter answers also count under subject `t:<tid>` for the
per-token daily ceiling (`SUPPORTER_DAILY_CAP`). Revoke and un-revoke commands: `../lossless-relay/README.md`.

This folder is not a Worker; `wrangler.toml` only carries the binding so the schema migrates on its own:

```powershell
npx wrangler d1 migrations apply stash-quota --remote --config infra/lossless-quota/wrangler.toml
```

Apply it before deploying either relay with the `QUOTA_DB` binding. A relay without the binding skips the counter
(and logs `QUOTA_DB not bound` once per isolate). The Tidal relay's tests use a copy of the migration
(`tidal-worker/test/fixtures/quota_0001_init.sql`, `quota_0002_revoked_tokens.sql`): change both together.

Inspect a day: `npx wrangler d1 execute stash-quota --remote --config infra/lossless-quota/wrangler.toml --command "SELECT subject, n FROM usage WHERE day = date('now') ORDER BY n DESC LIMIT 25"`.
