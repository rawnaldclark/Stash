# Revoking a supporter token

A supporter token (src/entitlement.js) posted publicly is revoked by its `tid` (a field of the payload,
the token's first segment; see below to read it). The relays check the `revoked_tokens`
table of the `stash-quota` D1 database:

```bash
npx wrangler d1 execute stash-quota --remote --command "INSERT INTO revoked_tokens(tid,revoked_at,reason) VALUES('<tid>',strftime('%s','now'),'shared publicly')"
```

The donor can claim a fresh token with the same email.

To read a token's `tid` without sending it anywhere:

```bash
node -e "console.log(JSON.parse(Buffer.from(process.argv[1].split('.')[0],'base64url')).tid)" '<token>'
```
