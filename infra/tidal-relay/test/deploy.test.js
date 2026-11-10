import test from "node:test";
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { createServer } from "node:http";
import { existsSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { configuredRedirect, decide, liveRedirect } from "../scripts/deploy.mjs";

/** npm run deploy's guard (scripts/deploy.mjs): a routine deploy must not turn the live cutover shim off. Local servers only. */
const NEW = "https://stash-addon-tidal.example.workers.dev";

test("configuredRedirect: wrangler.toml's REDIRECT_ORIGIN, or a --var for it", () => {
  assert.equal(configuredRedirect('[vars]\nREDIRECT_ORIGIN = ""\n', []), "");
  assert.equal(configuredRedirect(`[vars]\nREDIRECT_ORIGIN = "${NEW}"\n`, []), NEW);
  assert.equal(configuredRedirect('REDIRECT_ORIGIN = ""', ["--var", `REDIRECT_ORIGIN:${NEW}/`]), NEW);
  assert.equal(configuredRedirect(`REDIRECT_ORIGIN = "${NEW}"`, [`--var=REDIRECT_ORIGIN:`]), "");
});

test("decide: turning a live shim off or elsewhere is refused unless on purpose; keeping or turning it on deploys", () => {
  assert.equal(decide({ live: NEW, configured: "" }).ok, false);
  assert.match(decide({ live: NEW, configured: "" }).message, /REFUSED.*would turn that OFF/);
  assert.equal(decide({ live: NEW, configured: "https://elsewhere.example" }).ok, false);
  assert.equal(decide({ live: NEW, configured: "", offOnPurpose: true }).ok, true);
  assert.equal(decide({ live: NEW, configured: NEW }).ok, true);
  assert.match(decide({ live: "", configured: NEW }).message, /turns the redirect ON/);
  assert.equal(decide({ live: "", configured: "" }).ok, true);
});

async function server(handler) {
  const s = createServer(handler);
  await new Promise((ok) => s.listen(0, "127.0.0.1", ok));
  return { origin: `http://127.0.0.1:${s.address().port}`, close: () => s.close() };
}
const shimOn = (req, res) => res.writeHead(307, { Location: `${NEW}${req.url}?via=old` }).end('{"error":"moved"}');
const serving = (req, res) => res.writeHead(401, { "content-type": "application/json" }).end('{"error":"unauthorized"}');

test("liveRedirect asks /search without following: the 307's origin, or '' when served", async (t) => {
  const on = await server(shimOn), off = await server(serving);
  t.after(() => { on.close(); off.close(); });
  assert.equal(await liveRedirect(on.origin), NEW);
  assert.equal(await liveRedirect(off.origin), "");
});

function deploy(args, env = {}) {
  const dir = mkdtempSync(join(tmpdir(), "relay-deploy-"));
  const log = join(dir, "calls.json");
  const fake = join(dir, "wrangler.mjs");
  writeFileSync(fake, `import { writeFileSync } from "node:fs"; writeFileSync(${JSON.stringify(log)}, JSON.stringify({ argv: process.argv.slice(2), logs: process.env.WRANGLER_WRITE_LOGS }));`);
  const script = fileURLToPath(new URL("../scripts/deploy.mjs", import.meta.url));
  return new Promise((done) => {
    const child = spawn(process.execPath, [script, ...args], { env: { ...process.env, CLOUDFLARE_ACCOUNT_ID: "", STASH_WRANGLER_BIN: fake, ...env }, stdio: ["ignore", "pipe", "pipe"] });
    let out = "";
    child.stdout.on("data", (d) => { out += d; });
    child.stderr.on("data", (d) => { out += d; });
    child.on("close", (code) => done({ code, out, call: existsSync(log) ? JSON.parse(readFileSync(log, "utf8")) : null }));
  });
}

test("npm run deploy refuses to switch a live shim off, deploys when the redirect is kept, and refuses another account", async (t) => {
  const on = await server(shimOn);
  t.after(() => on.close());
  const refused = await deploy(["--origin", on.origin]);
  assert.equal(refused.code, 1);
  assert.match(refused.out, /REFUSED/);
  assert.equal(refused.call, null, "wrangler never ran");
  const kept = await deploy(["--origin", on.origin, "--var", `REDIRECT_ORIGIN:${NEW}`]);
  assert.equal(kept.code, 0, kept.out);
  assert.deepEqual(kept.call, { argv: ["deploy", "--var", `REDIRECT_ORIGIN:${NEW}`], logs: "false" });
  const other = await deploy(["--origin", on.origin, "--var", `REDIRECT_ORIGIN:${NEW}`], { CLOUDFLARE_ACCOUNT_ID: "0000000000000000000000000000a1b2" });
  assert.equal(other.code, 1);
  assert.match(other.out, /another account/);
});
