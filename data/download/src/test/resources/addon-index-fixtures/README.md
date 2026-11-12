# Add-on index fixtures

Signed sample add-on indexes: what `scripts/publish-index.mjs` emits, and what a reader must refuse. They are shared
by this folder's tests (`test/fixtures.test.js`, which proves every claim below) and by the app's reader of the index.

Made by `scripts/make-index-fixtures.mjs` with a throwaway P-256 key that was never written to disk. Nothing here is
signed by a real key, and no private key is in this folder. Every run of that script makes new keys, so all the
signatures and `pubkey.b64` change together: do not re-run it unless the format changes.

**The bytes matter.** A signature covers its `index.json` byte for byte, so `.gitattributes` here keeps git from
converting line endings (`* -text`). If you copy these files elsewhere in the repo, give that folder the same rule.

## Files

| File | What it is |
|---|---|
| `pubkey.b64` | The fixture key's public half: base64 of the SubjectPublicKeyInfo DER, on one line with no line break. This is the form `LOSSLESS_CONFIG_PUBKEY` (`lossless.configPubKey`) holds, so a test sets it as the verifier's key. |
| `cases.json` | The list below for a program: per case `dir`, `expect` (`accept` or `refuse`), `refused_by`, `signature` (`valid` or `invalid` against `pubkey.b64`), `bytes`, and for accept cases `canonical`, `updated_at` and the entry counts. `valid-unknown-reason` also has `blocked_reasons`: what a reader reports as each block's reason, in order. `valid-unknown-platform` also has `addon_platforms`: what a reader reports as each add-on's `platforms`, in order (`null` where the entry has none). |
| `<case>/index.json` | The body as served. |
| `<case>/index.json.sig` | The signature as served: base64 of a DER ECDSA signature (SHA-256, P-256) over the exact bytes of `index.json`, one line, no line break. A reader trims it before decoding. |

## Cases a reader accepts

| Case | What it is |
|---|---|
| `valid` | A full index as the publisher writes it. Three add-ons: a `"default": true` entry with no optional field; one with `icon`, `manifest`, `min_build` and `platforms`; one with a `name` and a `summary` of exactly 60 characters, non-ASCII text (two-, three- and four-byte UTF-8, one character outside the BMP) and a port in its manifest. Three blocks, one per reason, with and without an `id`. `updated_at` 1794355200. |
| `valid-older` | The same index with `updated_at` one day lower. Valid by itself. A reader that has already applied `valid` refuses it (the rollback floor). |
| `valid-launch` | The shape of the first real index: the one default entry, nothing blocked. |
| `valid-unknown-fields` | An index from a later publisher, with a field no reader knows at the top level, in an add-on and in a block. Unknown fields are ignored, so it is accepted and reads as one add-on and one block. This publisher never writes such a file and refuses it as a source. |
| `valid-unknown-reason` | An index from a later publisher, with two blocks: `https://bad.example` for `harmful`, and `https://spam.example` for `spam`, a word no reader knows. **A reader accepts the index and blocks both add-ons.** The second one's reason reads as unknown, and the app shows a generic fixed sentence for it, never the word from the file. A newer list must never make an older reader drop its blocks. This publisher never writes such a file and refuses it as a source. (Until this rule was settled the same two files were named `refuse-bad-reason`; their bytes have not changed.) |
| `valid-unknown-platform` | An index from a later publisher, with two add-ons whose `platforms` hold `ios`, a word no reader knows, and one block. **A reader accepts the index, keeps the block, and drops the unknown word:** the entry listing `android` and `ios` reads as `["android"]`; the entry listing only `ios` reads as an empty list, which means it is for no platform this reader knows (the app does not show it and does not call it Official). A newer list must never make an older reader miss its blocks. This publisher never writes such a file and refuses it as a source. |

`valid`, `valid-older` and `valid-launch` are byte for byte what the publisher writes (`canonical` in `cases.json`).

## Cases a reader refuses

Each breaks one thing. Unless it says otherwise the signature is good, so the named rule is the only thing that
refuses it.

| Case | Refused by | What it is |
|---|---|---|
| `refuse-lossless-json` | `type` | A `lossless.json` (as `buildConfig` writes it) signed with the same key and served as the index. It has no `type`. Its `updated_at` is above every valid case, so the rollback floor is not what stops it. |
| `refuse-wrong-type` | `type` | A well-formed index whose `type` is `stash-addon-list`. |
| `refuse-wrong-v` | `v` | A well-formed index whose `v` is 2. |
| `refuse-wrong-signer` | signature | The bytes of `valid`, signed by a different P-256 key (an unknown key). |
| `refuse-tampered` | signature | The bytes of `valid` with one byte changed (`bad.example` became `bae.example`), under `valid`'s signature. |
| `refuse-too-many-addons` | rule | 65 add-ons; the cap is 64. |
| `refuse-too-many-blocked` | rule | 257 blocks; the cap is 256. |
| `refuse-oversize` | size | 67069 bytes; the cap is 65536. 256 blocks on long host names, so every count is within its cap. |

## What the publisher writes

- UTF-8 JSON, keys in the order `type`, `v`, `updated_at`, `addons`, `blocked`, two-space indent, one final line
  feed, no carriage returns, no byte order mark. Text is written as UTF-8, not as `\u` escapes.
- `type` is `"stash-addon-index"`, `v` is `1`, `updated_at` is Unix seconds (a whole number).
- `addons` (at most 64) and `blocked` (at most 256) are always present, even when empty.
- An add-on always has `id`, `name` and `summary`, and exactly one of `manifest` or `"default": true`. `icon`,
  `min_build` and `platforms` are optional and are left out when not given (never `null`).
- A block always has `origin` and `reason`; `id` is optional and left out when not given.
- `reason` is always one of `harmful`, `broken`, `impersonation`. A reader still takes any other word as a block
  with an unknown reason (`valid-unknown-reason`); a `reason` that is missing or not a string is a broken file.
- `name` and `summary` are 1 to 60 UTF-16 units, so they are within 60 however a reader counts.
- A blocked `origin` is written exactly as `https://host` or `https://host:port`: lower case, no default port, no
  path, no trailing slash.
- The whole body is at most 65536 bytes.
