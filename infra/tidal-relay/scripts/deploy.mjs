#!/usr/bin/env node
// npm run deploy: `wrangler deploy` behind one check, for the weeks the cutover shim is on (addons-phase2 §3.4-§3.5).
//
//   npm run deploy [-- --var REDIRECT_ORIGIN:https://<new origin>] [--shim-off-on-purpose] [--no-live-check] [--origin <url>]
//
// While the live Worker redirects (REDIRECT_ORIGIN set, plan step 3), its own seats are parked ('reserve'). A routine
// deploy from master with REDIRECT_ORIGIN = "" would make it serve again with no live seats: 503 for every phone still
// on this URL. So before deploying, this asks the live Worker (GET /search, redirects not followed) whether it
// redirects, and compares that with what this deploy sets (wrangler.toml's REDIRECT_ORIGIN, or a --var for it):
//   - live redirects to X, the deploy sets nothing or another origin  -> REFUSED, loudly, unless --shim-off-on-purpose
//     (the rollback in README "Moving to another account").
//   - live redirects to X, the deploy sets X                         -> deployed.
//   - live serves, the deploy sets X                                  -> deployed, with a note (that is plan step 3).
//   - the live check fails                                            -> refused, unless --no-live-check.
// A shell holding another account's CLOUDFLARE_ACCOUNT_ID (the add-on's cf-addons.env) is refused: this Worker lives
// on the main account. `wrangler deploy` run directly skips all of this: during the cutover, deploy with npm run deploy.
import { spawnSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

export const LIVE_ORIGIN = "https://stash-tidal-relay.rawnaldclark.workers.dev";
export const MAIN_ACCOUNT_ID = "533c789ac46b951522b39e12c3544718";
const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");

/** REDIRECT_ORIGIN as this deploy sets it: a `--var REDIRECT_ORIGIN:<v>` wins over wrangler.toml's [vars] line. */
export function configuredRedirect(toml, args) {
  for (let i = 0; i < args.length; i++) {
    const v = args[i] === "--var" ? args[i + 1] : args[i].startsWith("--var=") ? args[i].slice(6) : null;
    if (v && v.startsWith("REDIRECT_ORIGIN:")) return originOf(v.slice("REDIRECT_ORIGIN:".length));
  }
  const m = /^\s*REDIRECT_ORIGIN\s*=\s*"([^"]*)"/m.exec(toml);
  return originOf(m ? m[1] : "");
}
const originOf = (raw) => { try { return raw.trim() ? new URL(raw.trim()).origin : ""; } catch { return raw.trim(); } };

/** Where the live Worker redirects /search to (an origin), "" when it serves it, or throws when it cannot be asked. */
export async function liveRedirect(origin, fetcher = fetch) {
  const r = await fetcher(`${origin.replace(/\/+$/, "")}/search`, { redirect: "manual", signal: AbortSignal.timeout(10_000) });
  if (r.status !== 307) return "";
  return originOf(r.headers.get("location") || "");
}

/** { ok, message } for a live state and a configured one. */
export function decide({ live, configured, offOnPurpose = false }) {
  if (live && configured !== live) {
    if (offOnPurpose) return { ok: true, message: `the live Worker redirects to ${live}; this deploy ${configured ? `redirects to ${configured} instead` : "turns the redirect OFF"} (--shim-off-on-purpose)` };
    return { ok: false, message: [
      `REFUSED: the live Worker redirects /search, /stream and /quota to ${live} (the cutover shim), and this deploy ${configured ? `would redirect to ${configured} instead` : "would turn that OFF"}.`,
      "Its own seats are parked, so every phone still on this URL would get 503 busy.",
      `Keep it: set REDIRECT_ORIGIN = "${live}" in wrangler.toml (commit it to master), or pass --var REDIRECT_ORIGIN:${live}.`,
      "Turning it off is a rollback step (README \"Moving to another account\"): only then add --shim-off-on-purpose.",
    ].join("\n") };
  }
  if (!live && configured) return { ok: true, message: `this deploy turns the redirect ON: /search, /stream and /quota go to ${configured} (cutover step 3)` };
  return { ok: true, message: live ? `the redirect to ${live} stays on` : "no redirect, before or after" };
}

async function main(argv) {
  const own = new Set(["--shim-off-on-purpose", "--no-live-check"]);
  const at = argv.indexOf("--origin");
  const origin = at >= 0 ? argv[at + 1] : process.env.TIDAL_RELAY_LIVE_ORIGIN || LIVE_ORIGIN;
  const pass = argv.filter((a, i) => !own.has(a) && i !== at && i !== at + 1);
  const account = (process.env.CLOUDFLARE_ACCOUNT_ID || "").trim().toLowerCase();
  if (account && account !== MAIN_ACCOUNT_ID) {
    console.error("deploy: REFUSED: CLOUDFLARE_ACCOUNT_ID in this shell is another account (cf-addons.env?); stash-tidal-relay lives on the main account.");
    return 1;
  }
  const configured = configuredRedirect(readFileSync(join(ROOT, "wrangler.toml"), "utf8"), pass);
  let live;
  if (argv.includes("--no-live-check")) {
    console.log("deploy: live check skipped (--no-live-check)");
    live = configured;
  } else {
    try { live = await liveRedirect(origin); } catch (e) {
      console.error(`deploy: REFUSED: cannot ask ${origin}/search whether the cutover shim is on (${e?.name || "error"}). Retry, or --no-live-check.`);
      return 1;
    }
  }
  const verdict = decide({ live, configured, offOnPurpose: argv.includes("--shim-off-on-purpose") });
  (verdict.ok ? console.log : console.error)(`deploy: ${verdict.message}`);
  if (!verdict.ok) return 1;
  const bin = process.env.STASH_WRANGLER_BIN || join(ROOT, "node_modules", "wrangler", "bin", "wrangler.js");
  const r = spawnSync(process.execPath, [bin, "deploy", ...pass], { cwd: ROOT, stdio: "inherit", env: { ...process.env, WRANGLER_WRITE_LOGS: "false" } });
  return r.status ?? 1;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) process.exitCode = await main(process.argv.slice(2));
