/**
 * The signed add-on index (add-ons phase 4, plan sections 1.3 and 1.4): the official list and the blocklist,
 * published as `index.json` + `index.json.sig` in the public `stash-addons/registry` repo and found by the app
 * through `addon_index` in lossless.json. Pure (no files, no network), the sibling of config.js, so every rule is
 * testable. scripts/publish-index.mjs is the command around it.
 *
 * Signed with the lossless.json key and envelope: SHA256withECDSA (P-256) over the exact bytes, the DER signature in
 * base64 in the .sig. Both files share one key, so `type` must be exactly "stash-addon-index": a signed
 * lossless.json has no `type` and must never pass as an index.
 *
 * Two readings, on purpose:
 *  - buildIndex (the publisher's source) is STRICT: an unknown key, a bad entry or a near-miss is refused, never
 *    repaired, so a typo fails here and never reaches a phone.
 *  - readIndex (published bytes) reads the way a phone does: every rule again, but unknown fields are ignored, so an
 *    index written by a later publisher still reads; and a block whose `reason` is a word it does not know is
 *    still a block (its reason reads as UNKNOWN_REASON), so a newer list never makes an older reader drop a block.
 *
 * No error message repeats a manifest URL: an official manifest must not carry a token, but if one slips into the
 * source, the message must not print it. Only ids, origins and reasons are ever printed.
 *
 * This file is ASCII only. Every special character is written as a code point number.
 */
import { createPublicKey, sign, verify } from "node:crypto";

export const INDEX_TYPE = "stash-addon-index";
/** Where the index lives unless lossless.json's `addon_index` says otherwise (plan 1.5). */
export const INDEX_URL = "https://raw.githubusercontent.com/stash-addons/registry/main/index.json";
/** At most this many `addons` and `blocked` entries (plan 1.3). */
export const MAX_ADDONS = 64;
export const MAX_BLOCKED = 256;
/** The app's cap on the fetched body (plan 1.5), as MAX_CONFIG_BYTES for lossless.json. */
export const MAX_INDEX_BYTES = 64 * 1024;
/**
 * Text caps (plan 1.3: 60 characters). Counted here in UTF-16 units, the strictest way to count: text that passes
 * is within the cap whether a reader counts code points or chars.
 */
export const MAX_NAME = 60;
export const MAX_SUMMARY = 60;
/** The app shows a fixed sentence for each reason, never text from the file. */
export const BLOCK_REASONS = Object.freeze(["harmful", "broken", "impersonation"]);
/**
 * How a reader reports a block whose `reason` is a word it does not know (one a later publisher added): the
 * add-on is still blocked, with a generic fixed sentence. The publisher itself never writes it.
 */
export const UNKNOWN_REASON = "unknown";
export const PLATFORMS = Object.freeze(["android", "web"]);
/** SPEC.md 2: the manifest `id` rule. */
export const ID_RULE = /^[a-z0-9][a-z0-9.-]{0,63}$/;
export const MAX_URL = 2048;
/** An Android versionCode is a positive int. */
export const MAX_BUILD = 2_147_483_647;

const TOP_KEYS = ["type", "v", "updated_at", "addons", "blocked"];
const ADDON_KEYS = ["id", "name", "summary", "icon", "manifest", "default", "min_build", "platforms"];
const BLOCK_KEYS = ["origin", "id", "reason"];

/**
 * Code points a name or summary may not hold, as [first, last]. The first rows are the characters a host replaces
 * in text (SPEC.md 1.1; the checker's list in semantics.mjs) plus tab and line feed, which that list leaves to
 * whitespace handling: a name is one line. The rest are other invisible characters that list does not name.
 */
export const UNSAFE_TEXT_RANGES = Object.freeze([
    [0x0000, 0x001f], // C0 controls, tab, line feed and carriage return among them
    [0x007f, 0x009f], // DEL and the C1 controls
    [0x061c, 0x061c], // Arabic letter mark (bidi)
    [0x200b, 0x200f], // zero-width space, non-joiner and joiner; left-to-right and right-to-left marks
    [0x2028, 0x202e], // line and paragraph separators; bidi embeddings, overrides and pop
    [0x2060, 0x2069], // word joiner, invisible operators, bidi isolates
    [0xfeff, 0xfeff], // byte order mark (zero-width no-break space)
    // Not in SPEC.md's list, and just as invisible:
    [0x00ad, 0x00ad], // soft hyphen
    [0x180e, 0x180e], // Mongolian vowel separator
    [0x206a, 0x206f], // deprecated format characters
    [0xd800, 0xdfff], // half of a surrogate pair on its own (a whole pair is one code point and does not match)
    [0xfff9, 0xfffb], // interlinear annotation marks
    [0xe0000, 0xe007f], // tag characters
]);
const UNSAFE_TEXT = new RegExp(`[${UNSAFE_TEXT_RANGES.map(([a, b]) => `\\u{${a.toString(16)}}-\\u{${b.toString(16)}}`).join("")}]`, "u");
/** Any whitespace character but the plain space (no-break, thin, ideographic and the rest). */
const ODD_SPACE = /[^\S\x20]/u;

const isObject = (v) => !!v && typeof v === "object" && !Array.isArray(v);
const toBuffer = (bytes) => (Buffer.isBuffer(bytes) ? bytes : Buffer.from(String(bytes), "utf8"));

/**
 * Builds the exact text of index.json from a source `{ addons?, blocked? }` (each list optional; absent means
 * empty). The source may also be a published index: `type` and `v` must then be right, and its `updated_at` is
 * ignored, because the publisher sets it: max(now, live + 1), so the app's rollback floor always passes.
 * `liveUpdatedAt` is the `updated_at` of the newest index a phone may hold (null, undefined or 0: none).
 * Pretty-printed with a final line feed so the public repo's diffs read well; the signature covers the UTF-8 bytes
 * of the returned string, exactly.
 */
export function buildIndex(source, liveUpdatedAt, nowSec) {
    if (!isObject(source)) throw new Error("the index source must be a JSON object");
    refuseUnknownKeys(source, TOP_KEYS, "the index source", true);
    if (source.type !== undefined && source.type !== INDEX_TYPE) throw new Error(`type must be "${INDEX_TYPE}"`);
    if (source.v !== undefined && source.v !== 1) throw new Error("v must be 1");
    if (typeof nowSec !== "number" || !Number.isFinite(nowSec) || nowSec < 1) throw new Error("the clock (now, in Unix seconds) must be a positive number");
    const live = liveUpdatedAt ?? 0;
    if (!Number.isSafeInteger(live) || live < 0) throw new Error("the live updated_at must be a whole number of seconds (or nothing, when no index is live)");
    const updated_at = Math.max(Math.floor(nowSec), live + 1);
    if (!Number.isSafeInteger(updated_at)) throw new Error("updated_at is out of range");
    const text = writeIndex({ updated_at, ...checkLists(source, true) });
    const size = Buffer.byteLength(text, "utf8");
    if (size > MAX_INDEX_BYTES) throw new Error(`the index is ${size} bytes; the app reads at most ${MAX_INDEX_BYTES}`);
    return text;
}

/** The one written form: fixed key order, two-space indent, a final line feed. */
function writeIndex({ updated_at, addons, blocked }) {
    return `${JSON.stringify({ type: INDEX_TYPE, v: 1, updated_at, addons, blocked }, null, 2)}\n`;
}

/**
 * Reads published index bytes (a Buffer, or a string taken as UTF-8) the way a phone does: the size cap, UTF-8,
 * JSON, `type`, `v`, `updated_at`, both lists and every entry rule. Unknown fields are ignored (plan 1.3); a known
 * field that breaks a rule refuses the whole index, with two exceptions, both words a later publisher may add: a
 * block's `reason` may be any word, and one that is not in BLOCK_REASONS reads as UNKNOWN_REASON (the add-on is
 * blocked all the same); and a word in an add-on's `platforms` that is not in PLATFORMS is dropped from that list
 * (the entry is then for the platforms left, or for none this reader knows: an empty list). Returns the
 * index with only the fields this version knows, in the written order; throws on any break. The signature is
 * checked separately (verifyIndexSignature), so a caller can say which of the two failed.
 */
export function readIndex(bytes) {
    const buf = toBuffer(bytes);
    if (buf.length > MAX_INDEX_BYTES) throw new Error(`the index is ${buf.length} bytes; the app reads at most ${MAX_INDEX_BYTES}`);
    let text;
    // ignoreBOM keeps a leading byte order mark in the text, where JSON.parse then refuses it (as the app's parser does).
    try { text = new TextDecoder("utf-8", { fatal: true, ignoreBOM: true }).decode(buf); } catch { throw new Error("the index is not UTF-8"); }
    let index;
    try { index = JSON.parse(text); } catch { throw new Error("the index is not JSON"); }
    if (!isObject(index)) throw new Error("the index is not a JSON object");
    if (index.type !== INDEX_TYPE) throw new Error(`type is not "${INDEX_TYPE}": this is not an add-on index`);
    if (index.v !== 1) throw new Error("v is not 1");
    if (!Number.isSafeInteger(index.updated_at) || index.updated_at < 1) throw new Error("updated_at is not a whole number of seconds");
    for (const k of ["addons", "blocked"]) if (!Array.isArray(index[k])) throw new Error(`${k} is missing or not a list`);
    return { type: INDEX_TYPE, v: 1, updated_at: index.updated_at, ...checkLists(index, false) };
}

/**
 * True when the bytes are exactly what this publisher writes for their own content: the canonical form, with no
 * unknown field, no other spacing and no other line endings. The publisher checks its own output with it.
 */
export function isCanonical(bytes) {
    try {
        const buf = toBuffer(bytes);
        const index = readIndex(buf);
        // Built again as a source, strictly: an unknown field or an unknown reason is not something this publisher writes.
        return Buffer.from(buildIndex(JSON.parse(buf.toString("utf8")), index.updated_at - 1, 1), "utf8").equals(buf);
    } catch {
        return false;
    }
}

function refuseUnknownKeys(obj, known, at, strict) {
    if (!strict) return;
    const extra = Object.keys(obj).filter((k) => !known.includes(k));
    if (extra.length) throw new Error(`${at} has unknown keys: ${extra.join(", ")}`);
}

/** Checks `addons` and `blocked` and returns them in their written form. `strict` also refuses unknown keys. */
function checkLists(source, strict) {
    const addons = checkAddons(source.addons ?? [], strict);
    const blocked = checkBlocked(source.blocked ?? [], strict);
    // A listed add-on that is also blocked is a contradiction: refuse it rather than guess which one was meant.
    for (const [i, a] of addons.entries()) {
        if (!a.manifest) continue;
        const origin = new URL(a.manifest).origin;
        if (blocked.some((b) => b.origin === origin && (b.id === undefined || b.id === a.id))) {
            throw new Error(`addons[${i}] (${a.id}) is on an origin that blocked[] also blocks (${origin})`);
        }
    }
    return { addons, blocked };
}

function checkAddons(raw, strict) {
    if (!Array.isArray(raw)) throw new Error("addons must be a list");
    if (raw.length > MAX_ADDONS) throw new Error(`addons holds at most ${MAX_ADDONS} entries`);
    const ids = new Set();
    const manifests = new Set();
    return raw.map((e, i) => {
        const at = `addons[${i}]`;
        if (!isObject(e)) throw new Error(`${at} must be an object`);
        refuseUnknownKeys(e, ADDON_KEYS, at, strict);
        if (typeof e.id !== "string" || !ID_RULE.test(e.id)) throw new Error(`${at}.id must match ${ID_RULE} (SPEC.md 2)`);
        if (ids.has(e.id)) throw new Error(`${at}.id ${e.id} is listed twice`);
        ids.add(e.id);
        const out = { id: e.id, name: checkText(e.name, `${at}.name`, MAX_NAME), summary: checkText(e.summary, `${at}.summary`, MAX_SUMMARY) };
        if (e.icon !== undefined) {
            const icon = checkUrl(e.icon);
            if (!icon) throw new Error(`${at}.icon must be an https URL with a host and no credentials, written in its normal form, at most ${MAX_URL} characters`);
            out.icon = icon;
        }
        // Exactly one of `manifest` (a public URL) or `"default": true` (its manifest comes from lossless.json's
        // default_addons by id, so a token in that URL never reaches this public file).
        if (e.default !== undefined && e.default !== true) throw new Error(`${at}.default must be true when given (leave it out otherwise)`);
        if ((e.manifest === undefined) === (e.default === undefined)) throw new Error(`${at} needs exactly one of "manifest" or "default": true`);
        if (e.manifest !== undefined) {
            const manifest = checkManifest(e.manifest, at);
            if (manifests.has(manifest)) throw new Error(`${at}.manifest is listed twice`);
            manifests.add(manifest);
            out.manifest = manifest;
        } else out.default = true;
        if (e.min_build !== undefined) {
            if (!Number.isInteger(e.min_build) || e.min_build < 1 || e.min_build > MAX_BUILD) throw new Error(`${at}.min_build must be a positive whole number (an Android versionCode)`);
            out.min_build = e.min_build;
        }
        if (e.platforms !== undefined) {
            const p = e.platforms;
            // The publisher writes only the platforms it knows: any other word in a source is a typo and fails here.
            const shaped = Array.isArray(p) && p.length > 0 && p.every((x) => typeof x === "string") && new Set(p).size === p.length;
            if (!shaped || (strict && p.some((x) => !PLATFORMS.includes(x)))) {
                throw new Error(`${at}.platforms must be a non-empty list of distinct values from: ${PLATFORMS.join(", ")}`);
            }
            // A reader drops a word it does not know (one a later publisher added) and keeps the entry: the add-on is
            // then for the platforms that are left, and for none this reader knows when the list comes out empty. The
            // index is never refused for it: a newer list must never make an older reader miss its blocks.
            out.platforms = p.filter((x) => PLATFORMS.includes(x));
        }
        return out;
    });
}

function checkBlocked(raw, strict) {
    if (!Array.isArray(raw)) throw new Error("blocked must be a list");
    if (raw.length > MAX_BLOCKED) throw new Error(`blocked holds at most ${MAX_BLOCKED} entries`);
    const out = raw.map((e, i) => {
        const at = `blocked[${i}]`;
        if (!isObject(e)) throw new Error(`${at} must be an object`);
        refuseUnknownKeys(e, BLOCK_KEYS, at, strict);
        const origin = checkOrigin(e.origin);
        if (!origin.ok) throw new Error(`${at}.origin ${origin.why}`);
        const entry = { origin: origin.value };
        if (e.id !== undefined) {
            if (typeof e.id !== "string" || !ID_RULE.test(e.id)) throw new Error(`${at}.id must match ${ID_RULE} (SPEC.md 2)`);
            entry.id = e.id;
        }
        const known = typeof e.reason === "string" && BLOCK_REASONS.includes(e.reason);
        // The publisher writes only the three reasons: any other word in a source is a typo and fails here.
        if (strict && !known) throw new Error(`${at}.reason must be one of: ${BLOCK_REASONS.join(", ")}`);
        // A reader keeps the block: a list from a later publisher must never make an older reader drop its blocks.
        // The word itself is not kept, so nothing from the file is ever shown or printed in its place.
        if (typeof e.reason !== "string") throw new Error(`${at}.reason must be a word`);
        entry.reason = known ? e.reason : UNKNOWN_REASON;
        return entry;
    });
    // One entry per (origin, id); an entry without an id already covers every id on its origin.
    const seen = new Map(); // origin -> Map(id or "" for the whole origin -> index)
    for (const [i, b] of out.entries()) {
        const onOrigin = seen.get(b.origin) ?? new Map();
        const key = b.id ?? "";
        if (onOrigin.has(key)) throw new Error(`blocked[${i}] repeats blocked[${onOrigin.get(key)}]`);
        if (onOrigin.size && (key === "" || onOrigin.has(""))) {
            const other = key === "" ? [...onOrigin.values()][0] : onOrigin.get("");
            throw new Error(`blocked[${i}] and blocked[${other}] overlap: an entry without an id already blocks every add-on on ${b.origin}; keep one`);
        }
        onOrigin.set(key, i);
        seen.set(b.origin, onOrigin);
    }
    return out;
}

/**
 * One line of text: nothing invisible, no whitespace but single plain spaces between words, 1 to `cap` UTF-16
 * units. So a host's own cleaning (SPEC.md 1.1) would change nothing in it.
 */
function checkText(v, at, cap) {
    if (typeof v !== "string" || !v.length) throw new Error(`${at} is required text`);
    if (UNSAFE_TEXT.test(v)) throw new Error(`${at} has a control, bidi, zero-width or other invisible character`);
    if (ODD_SPACE.test(v)) throw new Error(`${at} has a whitespace character other than a plain space`);
    if (v.startsWith(" ") || v.endsWith(" ") || v.includes("  ")) throw new Error(`${at} has a leading, trailing or doubled space`);
    if (v.length > cap) throw new Error(`${at} is over ${cap} characters`);
    return v;
}

/**
 * SPEC.md 1.1's URL rule (https, a host, no user or password, no whitespace, control characters or backslash, at
 * most 2048), and already in its normal form (lower-case host, no default port, nothing left to escape): a URL
 * that would have to be rewritten is refused, never repaired. Returns the URL or null.
 */
function checkUrl(raw) {
    if (typeof raw !== "string" || !raw || raw.length > MAX_URL || /[\s\\]/u.test(raw) || UNSAFE_TEXT.test(raw)) return null;
    let u;
    try { u = new URL(raw); } catch { return null; }
    if (u.protocol !== "https:" || !u.hostname || u.username || u.password) return null;
    return u.href === raw ? raw : null;
}

/**
 * An official add-on's public manifest URL: checkUrl's rule, ending in `/manifest.json`, no query or fragment,
 * and no path segment that looks like a token. A manifest whose URL carries a token stays in lossless.json and is
 * listed here by id with `"default": true`.
 */
function checkManifest(raw, at) {
    const url = checkUrl(raw);
    const u = url ? new URL(url) : null;
    if (!u || u.search || u.hash || raw.includes("?") || raw.includes("#") || !u.pathname.endsWith("/manifest.json")) {
        throw new Error(`${at}.manifest must be an https URL ending in /manifest.json, with no credentials, query or fragment, written in its normal form`);
    }
    if (u.pathname.split("/").slice(1, -1).some(looksLikeToken)) {
        throw new Error(`${at}.manifest has a path segment that looks like a token; an official manifest must be public (use "default": true for a manifest whose URL carries a token)`);
    }
    return url;
}

/**
 * True when a path segment could be a secret. A guess, and eager on purpose: a false alarm costs a rename, a miss
 * publishes a token. A segment passes only when it is lowercase words of letters and digits joined by `-` or `.`,
 * at most 32 characters in all, with no word of 20 or more characters and no word of 8 or more that holds a digit.
 * It cannot catch a short token ("tok-123" passes): the rule that a token URL is never listed is the author's to
 * keep, and this is the net under it.
 */
export function looksLikeToken(segment) {
    if (typeof segment !== "string" || segment.length > 32 || !/^[a-z0-9]+(?:[-.][a-z0-9]+)*$/.test(segment)) return true;
    return segment.split(/[-.]/).some((w) => w.length >= 20 || (w.length >= 8 && /[0-9]/.test(w)));
}

/**
 * A blocked `origin`: exactly `https://host[:port]`, lower case, no default port, no path and no trailing slash
 * (the form URL.origin gives), and no dot after the host. Compared exactly, so there is one way to write each.
 * Returns { ok: true, value } or { ok: false, why }; `why` names at most the origin, never a path.
 */
export function checkOrigin(raw) {
    if (typeof raw !== "string" || !raw) return { ok: false, why: "is required" };
    if (raw.length > MAX_URL) return { ok: false, why: `is over ${MAX_URL} characters` };
    let u;
    try { u = new URL(raw); } catch { return { ok: false, why: "is not a URL" }; }
    if (u.protocol !== "https:") return { ok: false, why: "must be https" };
    if (u.origin !== raw) return { ok: false, why: `must be written exactly as its origin (${u.origin}): no path, query, trailing slash, default port, credentials or upper case` };
    if (u.hostname.endsWith(".")) return { ok: false, why: "must not end its host with a dot" };
    return { ok: true, value: u.origin };
}

/** What may be printed about an index: ids, origins and reasons, never a manifest path. */
export function describeIndex(index) {
    const addons = (index.addons ?? []).map((a) => {
        let where = "default";
        if (a.manifest) { try { where = new URL(a.manifest).origin; } catch { where = "(bad url)"; } }
        return `${a.id} (${where})`;
    });
    const blocked = (index.blocked ?? []).map((b) => `${b.origin}${b.id ? ` [${b.id}]` : ""} ${b.reason}`);
    return `updated_at=${index.updated_at} addons=${addons.length ? addons.join(", ") : "(none)"} blocked=${blocked.length ? blocked.join(", ") : "(none)"}`;
}

/** The URL of an index's signature: the index URL plus `.sig`, as for lossless.json. */
export const sigUrlOf = (indexUrl) => `${indexUrl}.sig`;

/** True for an EC P-256 key (private or public KeyObject): the only kind the app verifies. */
export function isP256(key) {
    return key?.asymmetricKeyType === "ec" && key.asymmetricKeyDetails?.namedCurve === "prime256v1";
}

/** A key's public half as base64 SPKI DER: the form LOSSLESS_CONFIG_PUBKEY (the app's `lossless.configPubKey`) holds. */
export function publicKeyBase64(key) {
    const pub = key.type === "public" ? key : createPublicKey(key);
    return pub.export({ type: "spki", format: "der" }).toString("base64");
}

/**
 * The lossless.json envelope: SHA256withECDSA over the exact bytes, the DER signature in base64 on one line with
 * no line break (the text of the .sig file). Refuses any key but P-256, which the app could not verify.
 */
export function signIndex(bytes, privateKey) {
    if (!isP256(privateKey)) throw new Error("the signing key must be an EC P-256 (prime256v1) private key");
    return sign("sha256", toBuffer(bytes), privateKey).toString("base64");
}

/** Standard base64 as java.util.Base64's basic decoder takes it: one run, no line breaks, no URL-safe letters. */
const STRICT_BASE64 = /^[A-Za-z0-9+/]+={0,2}$/;

/**
 * Verifies a signature the way the device does (LosslessConfigFetcher.verify): the public key as base64 SPKI DER
 * (what LOSSLESS_CONFIG_PUBKEY holds) or a KeyObject; the .sig file's text, trimmed, as strict base64 of a DER
 * signature. A .sig wrapped over several lines fails here, as it would on a phone. Never throws.
 */
export function verifyIndexSignature(bytes, sigText, publicKey) {
    try {
        const sig = String(sigText).trim();
        if (!STRICT_BASE64.test(sig)) return false;
        let key = publicKey;
        if (typeof publicKey === "string") {
            if (!STRICT_BASE64.test(publicKey)) return false;
            key = createPublicKey({ key: Buffer.from(publicKey, "base64"), type: "spki", format: "der" });
        }
        if (!isP256(key)) return false;
        return verify("sha256", toBuffer(bytes), key, Buffer.from(sig, "base64"));
    } catch {
        return false;
    }
}
