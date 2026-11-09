#!/usr/bin/env node
/**
 * Signs and publishes lossless.json to the config host: the tipjar Worker's KV (spec §3).
 *
 *   node scripts/publish-config.mjs keygen <key.pem>                 new P-256 private key (mode 0600, never overwrites); prints LOSSLESS_CONFIG_PUBKEY
 *   node scripts/publish-config.mjs pubkey <key.pem>                 prints LOSSLESS_CONFIG_PUBKEY again
 *   node scripts/publish-config.mjs relaykey                         prints a fresh 64-hex RELAY_KEY
 *   node scripts/publish-config.mjs publish <key.pem> <input.json>   sign, upload both KV keys, read back, verify
 *   node scripts/publish-config.mjs verify [<key.pem>]               fetch the live pair and report (verifies when the key is given)
 *
 * input.json: {"relays":[],"relay_key":"<RELAY_KEY>","tidal_relay_url":"https://stash-tidal-relay.rawnaldclark.workers.dev","tidal_relay_token":"<ACCESS_TOKEN>"}
 * relay_key is stash-tidal-relay's RELAY_KEY secret (the app signs its install headers with it). Leaving out relay_key or
 * tidal_relay_url switches Tidal off in every installed app. `relays: []` is the kill switch for the Qobuz relay list.
 * Keep key.pem and input.json OUTSIDE the repo. See ../README.md.
 * `updated_at` is never hand-edited: it becomes max(now, live + 1), so the device's rollback floor always passes.
 * Needs a wrangler login (or CLOUDFLARE_API_TOKEN). Temp paths must not contain spaces (npx runs through a shell).
 */
import { generateKeyPairSync, createPrivateKey, createPublicKey, randomBytes, sign, verify } from "node:crypto";
import { readFileSync, writeFileSync, mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { execFileSync } from "node:child_process";
import { buildConfig } from "../src/config.js";

export const CONFIG_URL = "https://stash-tipjar.rawnaldclark.workers.dev/lossless.json";
/** STASH_KV: the id committed in infra/tipjar-worker/wrangler.toml. */
const KV_NAMESPACE_ID = "fca1bc38b42741e6a8cac11f54de5abd";
const KV_JSON = "lossless_config";
const KV_SIG = "lossless_config_sig";

const [cmd, a1, a2] = process.argv.slice(2);
const spki = (priv) => createPublicKey(priv).export({ type: "spki", format: "der" }).toString("base64");

async function live() {
    const [j, s] = await Promise.all([fetch(CONFIG_URL), fetch(`${CONFIG_URL}.sig`)]);
    if (j.status === 404) return null;
    if (!j.ok || !s.ok) throw new Error(`config host answered ${j.status}/${s.status}`);
    return { bytes: Buffer.from(await j.arrayBuffer()), sig: (await s.text()).trim() };
}

function kvPut(key, path) {
    execFileSync("npx", ["wrangler", "kv", "key", "put", key, "--path", path, "--namespace-id", KV_NAMESPACE_ID, "--remote"],
        { stdio: "inherit", shell: process.platform === "win32" });
}

async function report(pem) {
    const current = await live();
    if (!current) { console.log("live: nothing published yet (404)"); return; }
    const cfg = JSON.parse(current.bytes.toString());
    const sigOk = pem
        ? verify("sha256", current.bytes, createPublicKey(createPrivateKey(readFileSync(pem))), Buffer.from(current.sig, "base64"))
        : "(no key given)";
    console.log(`live: updated_at=${cfg.updated_at} relays=${cfg.relays.map((r) => r.base).join(",") || "(none)"} relay_key=${cfg.relay_key ? "present" : "absent"} signature=${sigOk}`);
}

switch (cmd) {
    case "keygen": {
        const { privateKey } = generateKeyPairSync("ec", { namedCurve: "prime256v1" });
        writeFileSync(a1, privateKey.export({ type: "pkcs8", format: "pem" }), { mode: 0o600, flag: "wx" });
        console.log(`wrote ${a1}\nLOSSLESS_CONFIG_PUBKEY=${spki(privateKey)}`);
        break;
    }
    case "pubkey":
        console.log(spki(createPrivateKey(readFileSync(a1))));
        break;
    case "relaykey":
        console.log(randomBytes(32).toString("hex"));
        break;
    case "publish": {
        const priv = createPrivateKey(readFileSync(a1));
        const input = JSON.parse(readFileSync(a2, "utf8"));
        const current = await live();
        const liveUpdatedAt = current ? JSON.parse(current.bytes.toString()).updated_at : 0;
        const text = buildConfig(input, liveUpdatedAt, Math.floor(Date.now() / 1000));
        const bytes = Buffer.from(text);
        const dir = mkdtempSync(join(tmpdir(), "lossless-config-"));
        writeFileSync(join(dir, "lossless.json"), bytes);
        writeFileSync(join(dir, "lossless.json.sig"), sign("sha256", bytes, priv).toString("base64"));
        const preview = JSON.parse(text);
        console.log(`publishing: updated_at=${preview.updated_at} relays=${preview.relays.map((r) => r.base).join(",") || "(none)"} tidal_relay_url=${preview.tidal_relay_url || "(unset)"} tidal_relay_token=${preview.tidal_relay_token ? "(present; redacted)" : "(unset)"}`);
        kvPut(KV_JSON, join(dir, "lossless.json"));
        kvPut(KV_SIG, join(dir, "lossless.json.sig"));
        console.log("uploaded. KV propagates within ~60 s; reading the live copy now (re-run `verify` if it is still the old one):");
        await report(a1);
        break;
    }
    case "verify":
        await report(a1);
        break;
    default:
        console.error("usage: keygen <key.pem> | pubkey <key.pem> | relaykey | publish <key.pem> <input.json> | verify [<key.pem>]");
        process.exit(2);
}
