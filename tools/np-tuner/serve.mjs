#!/usr/bin/env node
/**
 * Now Playing spacing tuner: a page on localhost with a live screenshot of the phone beside a
 * slider for each spacing value. Moving a slider broadcasts the values to the debug app, which
 * relays Now Playing out at once; the next screenshot shows it. See README.md.
 *
 *   node tools/np-tuner/serve.mjs --serial 192.168.137.230:5555 [--port 5178] [--adb adb]
 *
 * No dependencies beyond Node 18+. Listens on 127.0.0.1 only. Every value is checked against
 * fields.json before it reaches adb (numbers clamped, choices allow-listed), so nothing from the
 * page can become a shell command on the phone. It never sleeps or locks the phone: the only key
 * it sends is KEYCODE_WAKEUP, which can't turn a screen off.
 */
import http from 'node:http';
import { execFile, spawn } from 'node:child_process';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const FIELDS = JSON.parse(await readFile(path.join(HERE, 'fields.json'), 'utf8'));

/** How often, at most, the phone is told to stay awake while the page is watching it. */
const WAKE_EVERY_MS = 30_000;
/** A screenshot older than this is taken again; the page asks about every 800 ms. */
const SHOT_FRESH_MS = 250;
/** The longest one adb call may take before it is given up on. */
const ADB_TIMEOUT_MS = 15_000;
/** The page's POST bodies are a few hundred bytes; anything bigger is refused. */
const MAX_BODY_BYTES = 16 * 1024;
const PNG_SIGNATURE = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);

const opts = parseArgs(process.argv.slice(2));
if (opts.help) {
  console.log('Usage: node tools/np-tuner/serve.mjs --serial <adb serial> [--port 5178] [--adb <path to adb>] [--package com.stash.app.debug]');
  process.exit(0);
}
const serial = opts.serial ?? process.env.ANDROID_SERIAL;
if (!serial || !/^[\w.:\-]+$/.test(serial)) {
  console.error('Give the phone: --serial <adb serial> (for the Pixel 5 rig: 192.168.137.230:5555). See README.md.');
  process.exit(2);
}
const adbPath = opts.adb ?? 'adb';
const pkg = opts.package ?? FIELDS.receiver.package;
if (!/^[\w.]+$/.test(pkg)) {
  console.error(`Not a package name: ${pkg}`);
  process.exit(2);
}
const port = Number(opts.port ?? 5178);

// ---------------------------------------------------------------------------------------------
// adb
// ---------------------------------------------------------------------------------------------

/** Runs adb against the chosen phone; resolves with stdout (text), rejects with a readable error. */
function adb(args) {
  return new Promise((resolve, reject) => {
    execFile(adbPath, ['-s', serial, ...args], { timeout: ADB_TIMEOUT_MS, windowsHide: true }, (err, stdout, stderr) => {
      if (err) reject(new Error((stderr || err.message).toString().trim()));
      else resolve(stdout.toString());
    });
  });
}

/** One PNG of the phone's screen, straight from `adb exec-out screencap -p` (binary-safe). */
function screencap() {
  return new Promise((resolve, reject) => {
    const proc = spawn(adbPath, ['-s', serial, 'exec-out', 'screencap', '-p'], { windowsHide: true });
    const chunks = [];
    const errors = [];
    const timer = setTimeout(() => proc.kill(), ADB_TIMEOUT_MS);
    proc.stdout.on('data', (c) => chunks.push(c));
    proc.stderr.on('data', (c) => errors.push(c));
    proc.on('error', (e) => { clearTimeout(timer); reject(e); });
    proc.on('close', (code) => {
      clearTimeout(timer);
      const png = Buffer.concat(chunks);
      if (code === 0 && png.subarray(0, 8).equals(PNG_SIGNATURE)) resolve(png);
      else reject(new Error(Buffer.concat(errors).toString().trim() || `screencap exited ${code} with ${png.length} bytes`));
    });
  });
}

let lastShot = null; // { png, at }
let shotInFlight = null;
let lastWake = 0;

/** The freshest screenshot: one capture at a time, shared by every request that arrives meanwhile. */
async function shot() {
  if (lastShot && Date.now() - lastShot.at < SHOT_FRESH_MS) return lastShot.png;
  if (!shotInFlight) {
    shotInFlight = (async () => {
      await keepAwake();
      const png = await screencap();
      lastShot = { png, at: Date.now() };
      return png;
    })().finally(() => { shotInFlight = null; });
  }
  return shotInFlight;
}

/** KEYCODE_WAKEUP at most every WAKE_EVERY_MS while the page watches: wakes the screen, never sleeps it. */
async function keepAwake() {
  if (opts['no-wake'] || Date.now() - lastWake < WAKE_EVERY_MS) return;
  lastWake = Date.now();
  try {
    await adb(['shell', 'input', 'keyevent', 'KEYCODE_WAKEUP']);
  } catch (e) {
    console.warn(`wake failed: ${e.message}`);
  }
}

/**
 * Only the values fields.json knows, each checked: numbers finite and clamped to their range,
 * choices from their list. The result is JSON of numbers and allow-listed words only, so it can
 * sit inside single quotes in the phone's shell with nothing able to break out.
 */
function sanitize(input) {
  const out = {};
  if (!input || typeof input !== 'object') return out;
  for (const f of FIELDS.numbers) {
    const v = input[f.key];
    if (typeof v !== 'number' || !Number.isFinite(v)) continue;
    out[f.key] = Math.min(f.max, Math.max(f.min, Math.round(v * 1000) / 1000));
  }
  for (const c of FIELDS.choices) {
    const v = input[c.key];
    if (c.options.some((o) => o.value === v)) out[c.key] = v;
  }
  return out;
}

/** Sends the values to the debug app's receiver (explicit package: Android drops implicit ones). */
async function broadcast(extra) {
  return adb(['shell', 'am', 'broadcast', '-a', FIELDS.receiver.action, '-p', pkg, ...extra]);
}

async function deviceInfo() {
  const [size, density, installed] = await Promise.all([
    adb(['shell', 'wm', 'size']).catch((e) => e.message),
    adb(['shell', 'wm', 'density']).catch((e) => e.message),
    adb(['shell', 'pm', 'path', pkg]).then((o) => o.trim().length > 0).catch(() => false),
  ]);
  return { serial, package: pkg, size: size.trim(), density: density.trim(), installed };
}

// ---------------------------------------------------------------------------------------------
// HTTP
// ---------------------------------------------------------------------------------------------

function send(res, status, type, body) {
  res.writeHead(status, { 'Content-Type': type, 'Cache-Control': 'no-store' });
  res.end(body);
}

const json = (res, status, obj) => send(res, status, 'application/json; charset=utf-8', JSON.stringify(obj));

function readBody(req) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on('data', (c) => {
      size += c.length;
      if (size > MAX_BODY_BYTES) { reject(new Error('body too large')); req.destroy(); return; }
      chunks.push(c);
    });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}

const server = http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url, 'http://localhost');
    if (req.method === 'GET' && (url.pathname === '/' || url.pathname === '/index.html')) {
      return send(res, 200, 'text/html; charset=utf-8', await readFile(path.join(HERE, 'index.html')));
    }
    if (req.method === 'GET' && url.pathname === '/fields.json') {
      return json(res, 200, FIELDS);
    }
    if (req.method === 'GET' && url.pathname === '/shot') {
      try {
        return send(res, 200, 'image/png', await shot());
      } catch (e) {
        return json(res, 503, { error: `screenshot failed: ${e.message}` });
      }
    }
    if (req.method === 'GET' && url.pathname === '/device') {
      return json(res, 200, await deviceInfo());
    }
    // The rest change the phone: same-origin POSTs only (the page's own fetch sends this header).
    if (req.method === 'POST' && req.headers['x-np-tuner'] !== '1') {
      return json(res, 403, { error: 'not from the tuner page' });
    }
    if (req.method === 'POST' && url.pathname === '/set') {
      let input;
      try {
        input = JSON.parse(await readBody(req));
      } catch {
        return json(res, 400, { error: 'body is not JSON' });
      }
      const clean = sanitize(input);
      if (Object.keys(clean).length === 0) return json(res, 400, { error: 'no known values' });
      const payload = JSON.stringify(clean);
      const out = await broadcast(['--es', 'json', `'${payload}'`]);
      lastShot = null; // the next screenshot shows the change
      return json(res, 200, { sent: clean, adb: out.trim() });
    }
    if (req.method === 'POST' && url.pathname === '/reset') {
      const out = await broadcast(['--ez', 'reset', 'true']);
      lastShot = null;
      return json(res, 200, { reset: true, adb: out.trim() });
    }
    return json(res, 404, { error: 'not found' });
  } catch (e) {
    return json(res, 500, { error: e.message });
  }
});

server.listen(port, '127.0.0.1', () => {
  console.log(`Now Playing tuner: http://localhost:${port}  (phone ${serial}, app ${pkg})`);
  console.log('Open Now Playing on the phone with a song playing, then move the sliders. Ctrl+C to stop.');
});

// ---------------------------------------------------------------------------------------------

/** `--name value` and `--flag` pairs. */
function parseArgs(argv) {
  const out = {};
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '-h' || a === '--help') { out.help = true; continue; }
    if (!a.startsWith('--')) continue;
    const name = a.slice(2);
    const next = argv[i + 1];
    if (next === undefined || next.startsWith('--')) out[name] = true;
    else { out[name] = next; i++; }
  }
  return out;
}
