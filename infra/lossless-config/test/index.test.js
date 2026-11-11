// The add-on index rules (src/index.js; add-ons phase 4, plan 1.3 and 1.4): what the publisher writes, what it
// refuses in a source, how published bytes read, the updated_at floor, and the signature the device checks.
// Special characters are made from code point numbers, so this file is ASCII like the source it tests.
import { test } from "node:test";
import assert from "node:assert/strict";
import { generateKeyPairSync, webcrypto } from "node:crypto";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { buildConfig } from "../src/config.js";
import {
    buildIndex, readIndex, isCanonical, looksLikeToken, checkOrigin, describeIndex, sigUrlOf, isP256, publicKeyBase64,
    signIndex, verifyIndexSignature, INDEX_TYPE, INDEX_URL, MAX_ADDONS, MAX_BLOCKED, MAX_INDEX_BYTES, MAX_NAME,
    MAX_SUMMARY, MAX_URL, MAX_BUILD, BLOCK_REASONS, PLATFORMS, ID_RULE, UNSAFE_TEXT_RANGES,
} from "../src/index.js";

const HERE = dirname(fileURLToPath(import.meta.url));
const cp = (...points) => String.fromCodePoint(...points);
const hex = (n) => `U+${n.toString(16).toUpperCase().padStart(4, "0")}`;
const NOW = 1794355200;
const TIDAL = { id: "tidal", name: "Tidal", summary: "Lossless and Hi-Res", default: true };
const RADIO = { id: "example-radio", name: "Example Radio", summary: "Live stations", manifest: "https://addons.example.org/radio/manifest.json" };
const build = (source, live = 0, now = NOW) => buildIndex(source, live, now);
const parsed = (source, live, now) => JSON.parse(build(source, live, now));
/** A source with one add-on changed: the shortest way to try one bad field. */
const withAddon = (patch) => ({ addons: [{ ...RADIO, ...patch }] });
const withBlock = (patch) => ({ blocked: [{ origin: "https://bad.example", reason: "harmful", ...patch }] });
const without = (obj, key) => Object.fromEntries(Object.entries(obj).filter(([k]) => k !== key));

// ---------------------------------------------------------------------------------------------------------------
// The written form

test("buildIndex: the exact bytes (key order, two-space indent, one final line feed), whatever order the source used", () => {
    const text = build({
        blocked: [{ reason: "harmful", id: "bad-addon", origin: "https://bad.example" }],
        addons: [{ platforms: ["android"], min_build: 160, default: true, summary: "Lossless and Hi-Res", name: "Tidal", icon: "https://addons.example.org/tidal.png", id: "tidal" }],
    });
    assert.equal(text, [
        "{",
        '  "type": "stash-addon-index",',
        '  "v": 1,',
        `  "updated_at": ${NOW},`,
        '  "addons": [',
        "    {",
        '      "id": "tidal",',
        '      "name": "Tidal",',
        '      "summary": "Lossless and Hi-Res",',
        '      "icon": "https://addons.example.org/tidal.png",',
        '      "default": true,',
        '      "min_build": 160,',
        '      "platforms": [',
        '        "android"',
        "      ]",
        "    }",
        "  ],",
        '  "blocked": [',
        "    {",
        '      "origin": "https://bad.example",',
        '      "id": "bad-addon",',
        '      "reason": "harmful"',
        "    }",
        "  ]",
        "}",
        "",
    ].join("\n"));
    assert.equal(text.includes("\r"), false);
});

test("buildIndex: both lists are always written, even from an empty source; optional fields are left out, never null", () => {
    assert.deepEqual(parsed({}), { type: INDEX_TYPE, v: 1, updated_at: NOW, addons: [], blocked: [] });
    assert.deepEqual(parsed({ addons: [TIDAL, RADIO], blocked: [{ origin: "https://bad.example", reason: "broken" }] }), {
        type: INDEX_TYPE, v: 1, updated_at: NOW,
        addons: [TIDAL, RADIO],
        blocked: [{ origin: "https://bad.example", reason: "broken" }],
    });
    assert.equal(build({ addons: [TIDAL] }).includes("null"), false);
    // Order is the author's: the list shows add-ons as written.
    assert.deepEqual(parsed({ addons: [RADIO, TIDAL] }).addons.map((a) => a.id), ["example-radio", "tidal"]);
});

test("buildIndex: text is written as UTF-8, not as escapes; the plan's own example builds", () => {
    const name = `Caf${cp(0xe9)} ${cp(0x266a)} ${cp(0x1f3b5)}`;
    const text = build(withAddon({ name }));
    assert.equal(text.includes(name), true);
    assert.equal(text.includes("\\u"), false);
    const plan = {
        type: "stash-addon-index", v: 1, updated_at: 1794355200,
        addons: [{ id: "tidal", name: "Tidal", summary: "Lossless and Hi-Res", icon: "https://raw.githubusercontent.com/stash-addons/registry/main/icons/tidal.png", default: true }],
        blocked: [{ origin: "https://bad.example", id: "bad-addon", reason: "harmful" }],
    };
    assert.deepEqual(parsed(plan, 0, 1794355200), plan);
});

// ---------------------------------------------------------------------------------------------------------------
// updated_at

test("updated_at: max(now, live + 1), whole seconds; the source's own value is ignored", () => {
    assert.equal(parsed({}, 0, NOW).updated_at, NOW);                 // first publish
    assert.equal(parsed({}, null, NOW).updated_at, NOW);
    assert.equal(parsed({}, undefined, NOW).updated_at, NOW);
    assert.equal(parsed({}, NOW - 5000, NOW).updated_at, NOW);        // a later clock wins
    assert.equal(parsed({}, NOW, NOW).updated_at, NOW + 1);           // the same second: still strictly above
    assert.equal(parsed({}, NOW + 5000, NOW).updated_at, NOW + 5001); // a clock behind the live copy
    assert.equal(parsed({}, 0, NOW + 0.9).updated_at, NOW);           // never a fraction
    assert.equal(parsed({ updated_at: 5 }, 0, NOW).updated_at, NOW);
    assert.equal(parsed({ updated_at: NOW * 10 }, 0, NOW).updated_at, NOW);
});

test("updated_at: a clock or a live value that is not a number of seconds throws instead of lowering the floor", () => {
    for (const now of [NaN, Infinity, 0, -1, "1794355200", null, undefined]) {
        assert.throws(() => buildIndex({}, 0, now), /the clock/, String(now));
    }
    for (const live of [-1, 1.5, "1794355200", NaN, Infinity, {}, Number.MAX_SAFE_INTEGER + 1]) {
        assert.throws(() => buildIndex({}, live, NOW), /the live updated_at/, String(live));
    }
    assert.throws(() => buildIndex({}, Number.MAX_SAFE_INTEGER, NOW), /out of range/);
});

// ---------------------------------------------------------------------------------------------------------------
// The source, strictly

test("the source: an object with only known keys; type and v must be right when given", () => {
    for (const bad of [null, undefined, [], "x", 1]) assert.throws(() => build(bad), /must be a JSON object/);
    assert.throws(() => build({ addon: [] }), /unknown keys: addon/);
    assert.throws(() => build({ addons: [], blocklist: [], notes: "x" }), /unknown keys: blocklist, notes/);
    assert.throws(() => build({ type: "stash-addon-list" }), /type must be "stash-addon-index"/);
    assert.throws(() => build({ type: null }), /type must be/);
    assert.throws(() => build({ v: 2 }), /v must be 1/);
    assert.throws(() => build({ v: "1" }), /v must be 1/);
    assert.throws(() => build({ addons: {} }), /addons must be a list/);
    assert.throws(() => build({ blocked: "none" }), /blocked must be a list/);
});

test("the source may be a published index: building it again changes only updated_at", () => {
    const first = build({ addons: [TIDAL, RADIO], blocked: [{ origin: "https://bad.example", reason: "harmful" }] });
    const second = buildIndex(JSON.parse(first), JSON.parse(first).updated_at, NOW);
    assert.equal(second, first.replace(`"updated_at": ${NOW}`, `"updated_at": ${NOW + 1}`));
});

// ---------------------------------------------------------------------------------------------------------------
// Add-ons

test("addons: at most 64; each an object with only known keys", () => {
    const many = (n) => ({ addons: Array.from({ length: n }, (_, i) => ({ id: `addon-${i}`, name: `Add-on ${i}`, summary: "One of many", manifest: `https://addons.example.org/a${i}/manifest.json` })) });
    assert.equal(parsed(many(MAX_ADDONS)).addons.length, 64);
    assert.throws(() => build(many(MAX_ADDONS + 1)), /addons holds at most 64 entries/);
    for (const bad of [null, "tidal", ["tidal"], 1]) assert.throws(() => build({ addons: [bad] }), /addons\[0\] must be an object/);
    assert.throws(() => build(withAddon({ manifestUrl: "x" })), /addons\[0\] has unknown keys: manifestUrl/);
    assert.throws(() => build(withAddon({ description: "x", author: "y" })), /unknown keys: description, author/);
    assert.throws(() => build({ addons: [TIDAL, { ...RADIO, Icon: "x" }] }), /addons\[1\] has unknown keys: Icon/);
});

test("addons: id follows the manifest id rule and is listed once", () => {
    assert.equal(ID_RULE.source, "^[a-z0-9][a-z0-9.-]{0,63}$");
    for (const id of ["a", "0", "tidal", "example-radio", "org.example.addon", "a".repeat(64), "9lives", "a--b..c"]) {
        assert.equal(parsed(withAddon({ id })).addons[0].id, id);
    }
    for (const id of ["", "Tidal", "-tidal", ".tidal", "ti dal", "tidal_2", "a".repeat(65), `tid${cp(0xe9)}l`, "tidal/2", 5, null, undefined, ["tidal"]]) {
        assert.throws(() => build(withAddon({ id })), /addons\[0\]\.id must match/, String(id));
    }
    assert.throws(() => build({ addons: [TIDAL, { ...RADIO, id: "tidal" }] }), /addons\[1\]\.id tidal is listed twice/);
});

test("addons: name and summary are required, one clean line, at most 60", () => {
    for (const field of ["name", "summary"]) {
        const cap = field === "name" ? MAX_NAME : MAX_SUMMARY;
        assert.equal(cap, 60);
        for (const bad of [undefined, null, "", 5, ["x"], {}]) {
            assert.throws(() => build(withAddon({ [field]: bad })), new RegExp(`addons\\[0\\]\\.${field} is required text`), `${field} ${String(bad)}`);
        }
        assert.equal(parsed(withAddon({ [field]: "x".repeat(cap) })).addons[0][field].length, cap);
        assert.throws(() => build(withAddon({ [field]: "x".repeat(cap + 1) })), new RegExp(`addons\\[0\\]\\.${field} is over 60 characters`));
        for (const bad of [" Radio", "Radio ", "Example  Radio"]) {
            assert.throws(() => build(withAddon({ [field]: bad })), /leading, trailing or doubled space/, JSON.stringify(bad));
        }
        assert.equal(parsed(withAddon({ [field]: "A B C" })).addons[0][field], "A B C");
    }
});

test("addons: the 60 cap counts UTF-16 units, so the text is within 60 however a reader counts", () => {
    const note = cp(0x1f3b5); // one code point, two UTF-16 units
    assert.equal(note.length, 2);
    assert.equal(parsed(withAddon({ name: note.repeat(30) })).addons[0].name, note.repeat(30));
    assert.throws(() => build(withAddon({ name: note.repeat(30) + "x" })), /over 60 characters/);
    // 31 code points, 62 units: over, although a code-point count would pass it.
    assert.equal([...note.repeat(31)].length, 31);
    assert.throws(() => build(withAddon({ name: note.repeat(31) })), /over 60 characters/);
    assert.equal(parsed(withAddon({ summary: cp(0xe9).repeat(60) })).addons[0].summary.length, 60);
});

test("addons: icon is optional; when given, an https URL in its normal form", () => {
    assert.equal("icon" in parsed(withAddon({})).addons[0], false);
    for (const icon of ["https://addons.example.org/radio/icon.png", "https://addons.example.org:8443/i.png?v=2", "https://raw.githubusercontent.com/stash-addons/registry/main/icons/radio.png", "https://xn--bcher-kva.example/icon.png"]) {
        assert.equal(parsed(withAddon({ icon })).addons[0].icon, icon);
    }
    const bad = [
        ["http", "http://addons.example.org/icon.png"],
        ["no scheme", "addons.example.org/icon.png"],
        ["another scheme", "data:image/png;base64,AAAA"],
        ["credentials", "https://user:pass@addons.example.org/icon.png"],
        ["user only", "https://user@addons.example.org/icon.png"],
        ["upper-case host", "https://Addons.Example.org/icon.png"],
        ["default port", "https://addons.example.org:443/icon.png"],
        ["no path", "https://addons.example.org"],
        ["dot segment", "https://addons.example.org/a/../icon.png"],
        ["space", "https://addons.example.org/my icon.png"],
        ["tab", "https://addons.example.org/icon.png\t"],
        ["line feed", "https://addons.example.org/icon.png\n"],
        ["backslash", "https://addons.example.org\\icon.png"],
        ["unicode host", `https://b${cp(0xfc)}cher.example/icon.png`],
        ["unicode path", `https://addons.example.org/ic${cp(0xf4)}n.png`],
        ["zero-width", `https://addons.example.org/icon${cp(0x200b)}.png`],
        ["too long", `https://addons.example.org/${"x".repeat(MAX_URL)}.png`],
        ["empty", ""],
        ["null", null],
        ["number", 5],
        ["object", { url: "https://addons.example.org/icon.png" }],
    ];
    for (const [why, icon] of bad) assert.throws(() => build(withAddon({ icon })), /addons\[0\]\.icon must be an https URL/, why);
    // Exactly at the cap is fine.
    const atCap = `https://addons.example.org/${"x".repeat(MAX_URL - "https://addons.example.org/".length)}`;
    assert.equal(atCap.length, MAX_URL);
    assert.equal(parsed(withAddon({ icon: atCap })).addons[0].icon, atCap);
});

test("addons: exactly one of manifest or default: true", () => {
    assert.deepEqual(parsed({ addons: [TIDAL] }).addons[0], TIDAL);
    assert.equal("default" in parsed({ addons: [RADIO] }).addons[0], false);
    assert.throws(() => build({ addons: [{ ...RADIO, default: true }] }), /needs exactly one of "manifest" or "default": true/);
    assert.throws(() => build({ addons: [without(RADIO, "manifest")] }), /needs exactly one of/);
    for (const bad of [false, "true", 1, null, 0]) {
        assert.throws(() => build({ addons: [{ ...TIDAL, default: bad }] }), /addons\[0\]\.default must be true when given/, String(bad));
        assert.throws(() => build({ addons: [{ ...RADIO, default: bad }] }), /addons\[0\]\.default must be true when given/, String(bad));
    }
    // A default entry may still say where and since when it applies.
    assert.deepEqual(parsed({ addons: [{ ...TIDAL, min_build: 160, platforms: ["android"] }] }).addons[0], { ...TIDAL, min_build: 160, platforms: ["android"] });
});

const SECRET = "tok-9f8e7d6c5b4a39281706f5e4d3c2b1a0";

test("addons: manifest is a public https URL ending in /manifest.json, in its normal form; no message repeats it", () => {
    for (const manifest of ["https://addons.example.org/manifest.json", "https://addons.example.org/radio/manifest.json", "https://addons.example.org:8443/v1/radio/manifest.json", "https://radio.addons.example.org/a/b/c/manifest.json"]) {
        assert.equal(parsed(withAddon({ manifest })).addons[0].manifest, manifest);
    }
    const base = `https://addons.example.org/${SECRET}`;
    const bad = [
        ["http", `http://addons.example.org/${SECRET}/manifest.json`],
        ["not a manifest", `${base}/`],
        ["another file", `${base}/manifest.jsonx`],
        ["manifest.json as a suffix only", `${base}/xmanifest.json`],
        ["query", `${base}/manifest.json?x=1`],
        ["empty query", `${base}/manifest.json?`],
        ["fragment", `${base}/manifest.json#x`],
        ["empty fragment", `${base}/manifest.json#`],
        ["credentials", `https://user:${SECRET}@addons.example.org/manifest.json`],
        ["upper-case host", `https://Addons.example.org/${SECRET}/manifest.json`],
        ["default port", `https://addons.example.org:443/${SECRET}/manifest.json`],
        ["dot segment", `${base}/x/../manifest.json`],
        ["whitespace", `${base}/a b/manifest.json`],
        ["backslash", `${base}\\manifest.json`],
        ["too long", `${base}/${"x".repeat(MAX_URL)}/manifest.json`],
        ["a token segment", `${base}/manifest.json`],
        ["a token segment after a plain one", `https://addons.example.org/v1/${SECRET}/manifest.json`],
        ["number", 5],
        ["null", null],
        ["empty", ""],
        ["list", [`${base}/manifest.json`]],
    ];
    for (const [why, manifest] of bad) {
        assert.throws(() => build(withAddon({ manifest })), (e) => {
            assert.match(e.message, /addons\[0\]\.manifest/, why);
            assert.equal(e.message.includes(SECRET), false, `${why}: the message repeats the token`);
            assert.equal(e.message.includes("addons.example.org"), false, `${why}: the message repeats the URL`);
            return true;
        }, why);
    }
    assert.throws(() => build({ addons: [RADIO, { ...RADIO, id: "radio-two" }] }), (e) => e.message === "addons[1].manifest is listed twice");
});

test("looksLikeToken: plain short words pass; anything that could be a secret does not", () => {
    for (const ok of ["v1", "radio", "example-radio", "addons", "a", "1", "2026", "v1.2", "stash-addon-template", "public", "abcdefghijklmnopqrs", "api-v2-beta", "radio2", "1234567"]) {
        assert.equal(looksLikeToken(ok), false, ok);
    }
    const tokens = [
        ["upper case", "Radio"],
        ["underscore", "my_radio"],
        ["percent escape", "my%20radio"],
        ["empty segment", ""],
        ["a leading dash", "-radio"],
        ["a doubled dash", "radio--two"],
        ["over 32 characters", "a-b-c-d-e-f-g-h-i-j-k-l-m-n-o-p-q"],
        ["a word of 20 letters", "abcdefghijklmnopqrst"],
        ["8 or more with a digit", "abc1defg"],
        ["8 digits", "12345678"],
        ["hex of 16", "9f8e7d6c5b4a3928"],
        ["hex of 32", "9f8e7d6c5b4a39281706f5e4d3c2b1a0"],
        ["a uuid", "550e8400-e29b-41d4-a716-446655440000"],
        ["base64url", "dGhpcy1pcy1hLXRva2Vu"],
        ["a jwt-like run", "eyJhbGciOiJFUzI1NiJ9.e30.abc"],
        ["not a string", 5],
        ["null", null],
    ];
    for (const [why, t] of tokens) assert.equal(looksLikeToken(t), true, why);
    // Its stated limit: a short token cannot be told from a word. The rule against listing one is the author's.
    assert.equal(looksLikeToken("tok-123"), false);
});

test("addons: min_build is a positive int, platforms a non-empty list of distinct known values", () => {
    for (const ok of [1, 160, MAX_BUILD]) assert.equal(parsed(withAddon({ min_build: ok })).addons[0].min_build, ok);
    for (const bad of [0, -1, 1.5, "160", MAX_BUILD + 1, NaN, null, true, [160]]) {
        assert.throws(() => build(withAddon({ min_build: bad })), /addons\[0\]\.min_build must be a positive whole number/, String(bad));
    }
    assert.deepEqual(PLATFORMS, ["android", "web"]);
    for (const ok of [["android"], ["web"], ["android", "web"], ["web", "android"]]) {
        assert.deepEqual(parsed(withAddon({ platforms: ok })).addons[0].platforms, ok);
    }
    for (const bad of [[], "android", ["ios"], ["android", "ios"], ["android", "android"], ["Android"], [null], null, { android: true }]) {
        assert.throws(() => build(withAddon({ platforms: bad })), /addons\[0\]\.platforms must be a non-empty list of distinct values from: android, web/, JSON.stringify(bad));
    }
});

// ---------------------------------------------------------------------------------------------------------------
// Text: every character a name may not hold

/** The ranges of the conformance checker (stash-addon-spec, checker/src/semantics.mjs), copied here as numbers. */
const SPEC_RANGES = [[0x00, 0x08], [0x0b, 0x1f], [0x7f, 0x9f], [0x61c, 0x61c], [0x200b, 0x200f], [0x2028, 0x202e], [0x2060, 0x2069], [0xfeff, 0xfeff]];
const inRanges = (n, ranges) => ranges.some(([a, b]) => n >= a && n <= b);
const refusesText = (text) => {
    try { build(withAddon({ name: text })); return false; } catch (e) { return /control, bidi, zero-width or other invisible/.test(e.message); }
};

test("unsafe text: both ends and the middle of every range are refused, in a name and in a summary", () => {
    for (const [a, b] of UNSAFE_TEXT_RANGES) {
        assert.ok(a <= b);
        for (const n of new Set([a, b, Math.floor((a + b) / 2)])) {
            for (const text of [`Ra${cp(n)}dio`, `${cp(n)}Radio`, `Radio${cp(n)}`]) {
                assert.equal(refusesText(text), true, `${hex(n)} in ${hex(a)}..${hex(b)}`);
                assert.throws(() => build(withAddon({ summary: text })), /addons\[0\]\.summary has a control, bidi, zero-width or other invisible/, hex(n));
            }
        }
    }
});

test("unsafe text: every code point of every range is refused, and the code points beside each range are not", () => {
    let refused = 0;
    for (const [a, b] of UNSAFE_TEXT_RANGES) {
        for (let n = a; n <= b; n++) {
            assert.equal(refusesText(`Ra${cp(n)}dio`), true, hex(n));
            refused++;
        }
        for (const n of [a - 1, b + 1]) {
            if (n < 0 || inRanges(n, UNSAFE_TEXT_RANGES)) continue;
            // A neighbour that is whitespace is refused by the whitespace rule instead; none is refused as invisible.
            assert.equal(refusesText(`Ra${cp(n)}dio`), false, `${hex(n)} beside ${hex(a)}..${hex(b)}`);
        }
    }
    assert.equal(refused, UNSAFE_TEXT_RANGES.reduce((sum, [a, b]) => sum + b - a + 1, 0));
    assert.equal(refused, 2276);
});

test("unsafe text: the ranges are distinct, and cover every character the spec's checker names, plus tab and line breaks", () => {
    const sorted = [...UNSAFE_TEXT_RANGES].sort((x, y) => x[0] - y[0]);
    for (let i = 1; i < sorted.length; i++) assert.ok(sorted[i][0] > sorted[i - 1][1], `${hex(sorted[i][0])} overlaps the range before it`);
    for (const [a, b] of SPEC_RANGES) {
        for (let n = a; n <= b; n++) assert.equal(inRanges(n, UNSAFE_TEXT_RANGES), true, hex(n));
    }
    for (const n of [0x09, 0x0a, 0x0d]) assert.equal(refusesText(`Example${cp(n)}Radio`), true, hex(n));
    // Named one by one, the characters this is for.
    const named = {
        "NUL": 0x00, "escape": 0x1b, "DEL": 0x7f, "next line": 0x85, "soft hyphen": 0xad, "Arabic letter mark": 0x61c,
        "Mongolian vowel separator": 0x180e, "zero-width space": 0x200b, "zero-width non-joiner": 0x200c, "zero-width joiner": 0x200d,
        "left-to-right mark": 0x200e, "right-to-left mark": 0x200f, "line separator": 0x2028, "paragraph separator": 0x2029,
        "left-to-right embedding": 0x202a, "right-to-left embedding": 0x202b, "pop directional formatting": 0x202c,
        "left-to-right override": 0x202d, "right-to-left override": 0x202e, "word joiner": 0x2060, "invisible times": 0x2062,
        "left-to-right isolate": 0x2066, "right-to-left isolate": 0x2067, "first strong isolate": 0x2068, "pop directional isolate": 0x2069,
        "inhibit symmetric swapping": 0x206a, "nominal digit shapes": 0x206f, "byte order mark": 0xfeff,
        "interlinear annotation anchor": 0xfff9, "language tag": 0xe0001, "tag latin small letter a": 0xe0061, "cancel tag": 0xe007f,
    };
    for (const [name, n] of Object.entries(named)) assert.equal(refusesText(`Ra${cp(n)}dio`), true, `${name} ${hex(n)}`);
});

test("unsafe text: half a surrogate pair is refused, a whole pair is a character like any other", () => {
    const pair = cp(0x1f3b5);
    assert.equal(parsed(withAddon({ name: `Radio ${pair}` })).addons[0].name, `Radio ${pair}`);
    assert.equal(refusesText(`Radio ${pair[0]}`), true, "a high surrogate alone");
    assert.equal(refusesText(`Radio ${pair[1]}`), true, "a low surrogate alone");
    assert.equal(refusesText(`${pair[1]}${pair[0]}`), true, "the two halves the wrong way round");
});

test("text that is fine: letters of any script, digits, punctuation and symbols, joined by single plain spaces", () => {
    const fine = [
        "Radio", "Example Radio 2", "Caf" + cp(0xe9), "R&B / Soul (live)", "Hi-Res: 24-bit", `${cp(0x65e5, 0x672c, 0x8a9e)}`, `${cp(0x420, 0x430, 0x434, 0x438, 0x43e)}`,
        `${cp(0x627, 0x644, 0x639, 0x631, 0x628, 0x64a, 0x629)}`, `Radio ${cp(0x266a)}`, `Radio ${cp(0x1f3b5)}`, `A${cp(0x2013)}B`, `${cp(0x201c)}Quoted${cp(0x201d)}`, "~", cp(0xa1), cp(0xff),
    ];
    for (const name of fine) assert.equal(parsed(withAddon({ name })).addons[0].name, name, name);
});

test("whitespace: the plain space is the only one allowed", () => {
    const odd = [0xa0, 0x1680, 0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007, 0x2008, 0x2009, 0x200a, 0x202f, 0x205f, 0x3000];
    for (const n of odd) {
        assert.throws(() => build(withAddon({ name: `Example${cp(n)}Radio` })), /addons\[0\]\.name has a whitespace character other than a plain space/, hex(n));
        assert.throws(() => build(withAddon({ summary: `Live${cp(n)}stations` })), /addons\[0\]\.summary has a whitespace character other than a plain space/, hex(n));
    }
    // Vertical tab and form feed are whitespace too, and controls first.
    for (const n of [0x0b, 0x0c]) assert.equal(refusesText(`Example${cp(n)}Radio`), true, hex(n));
});

test("the sources that hold these rules are ASCII only, so no editor or tool can alter a character in them", () => {
    for (const rel of ["../src/index.js", "../scripts/publish-index.mjs", "../scripts/make-index-fixtures.mjs", "index.test.js", "fixtures.test.js", "publish-index.test.js"]) {
        const bytes = readFileSync(join(HERE, rel));
        const at = bytes.findIndex((b) => b > 0x7e || (b < 0x20 && b !== 0x0a && b !== 0x0d));
        assert.equal(at, -1, `${rel} has a non-ASCII or control byte at offset ${at}`);
    }
});

// ---------------------------------------------------------------------------------------------------------------
// The blocklist

test("blocked: at most 256; each an object with only known keys", () => {
    const many = (n) => ({ blocked: Array.from({ length: n }, (_, i) => ({ origin: `https://blocked-${i}.example`, reason: "broken" })) });
    assert.equal(parsed(many(MAX_BLOCKED)).blocked.length, 256);
    assert.throws(() => build(many(MAX_BLOCKED + 1)), /blocked holds at most 256 entries/);
    for (const bad of [null, "https://bad.example", ["https://bad.example"], 1]) assert.throws(() => build({ blocked: [bad] }), /blocked\[0\] must be an object/);
    assert.throws(() => build(withBlock({ note: "malware" })), /blocked\[0\] has unknown keys: note/);
    assert.throws(() => build(withBlock({ message: "x", url: "y" })), /unknown keys: message, url/);
});

test("blocked: origin is exactly https://host or https://host:port", () => {
    for (const origin of ["https://bad.example", "https://bad.example:8443", "https://a.b.c.bad.example", "https://xn--bcher-kva.example", "https://192.0.2.7", "https://[2001:db8::1]:8443", "https://localhost"]) {
        assert.deepEqual(checkOrigin(origin), { ok: true, value: origin });
        assert.equal(parsed(withBlock({ origin })).blocked[0].origin, origin);
    }
    const bad = [
        ["missing", undefined, /is required/],
        ["empty", "", /is required/],
        ["number", 5, /is required/],
        ["not a URL", "bad.example", /is not a URL/],
        ["http", "http://bad.example", /must be https/],
        ["another scheme", "ftp://bad.example", /must be https/],
        ["trailing slash", "https://bad.example/", /written exactly as its origin/],
        ["a path", "https://bad.example/addon", /written exactly as its origin/],
        ["a manifest URL", "https://bad.example/manifest.json", /written exactly as its origin/],
        ["a query", "https://bad.example?x=1", /written exactly as its origin/],
        ["a fragment", "https://bad.example#x", /written exactly as its origin/],
        ["upper case", "https://Bad.Example", /written exactly as its origin/],
        ["default port", "https://bad.example:443", /written exactly as its origin/],
        ["credentials", "https://user:pass@bad.example", /written exactly as its origin/],
        ["unicode host", `https://b${cp(0xfc)}cher.example`, /written exactly as its origin/],
        ["leading space", " https://bad.example", /written exactly as its origin/],
        ["trailing dot", "https://bad.example.", /must not end its host with a dot/],
        ["too long", `https://${"a".repeat(MAX_URL)}.example`, /is over 2048 characters/],
    ];
    for (const [why, origin, message] of bad) {
        assert.equal(checkOrigin(origin).ok, false, why);
        assert.match(checkOrigin(origin).why, message, why);
        assert.throws(() => build({ blocked: [{ origin, reason: "harmful" }] }), /blocked\[0\]\.origin /, why);
    }
});

test("blocked: a message about a bad origin names the origin at most, never a path that could hold a token", () => {
    assert.throws(() => build(withBlock({ origin: `https://bad.example/${SECRET}/manifest.json` })), (e) => {
        assert.equal(e.message, "blocked[0].origin must be written exactly as its origin (https://bad.example): no path, query, trailing slash, default port, credentials or upper case");
        return true;
    });
});

test("blocked: id is optional and follows the id rule; reason is one of the three fixed words", () => {
    assert.equal("id" in parsed(withBlock({})).blocked[0], false);
    assert.equal(parsed(withBlock({ id: "bad-addon" })).blocked[0].id, "bad-addon");
    for (const id of ["", "Bad", "-bad", "bad addon", "a".repeat(65), 5, null]) {
        assert.throws(() => build(withBlock({ id })), /blocked\[0\]\.id must match/, String(id));
    }
    assert.deepEqual(BLOCK_REASONS, ["harmful", "broken", "impersonation"]);
    for (const reason of BLOCK_REASONS) assert.equal(parsed(withBlock({ reason })).blocked[0].reason, reason);
    for (const reason of [undefined, null, "", "spam", "Harmful", "harmful ", "piracy", "copyright", "dmca", 1, ["harmful"], "toString", "length"]) {
        assert.throws(() => build(withBlock({ reason })), /blocked\[0\]\.reason must be one of: harmful, broken, impersonation/, String(reason));
    }
});

test("blocked: one entry per origin and id; an entry without an id already covers its whole origin", () => {
    const b = (origin, id, reason = "harmful") => (id ? { origin, id, reason } : { origin, reason });
    // Fine: two ids on one origin, one id on two origins.
    assert.equal(parsed({ blocked: [b("https://bad.example", "one"), b("https://bad.example", "two"), b("https://worse.example", "one")] }).blocked.length, 3);
    assert.throws(() => build({ blocked: [b("https://bad.example", "one"), b("https://ok.example"), b("https://bad.example", "one", "broken")] }), /blocked\[2\] repeats blocked\[0\]/);
    assert.throws(() => build({ blocked: [b("https://bad.example"), b("https://bad.example", null, "broken")] }), /blocked\[1\] repeats blocked\[0\]/);
    assert.throws(() => build({ blocked: [b("https://bad.example"), b("https://bad.example", "one")] }), /blocked\[1\] and blocked\[0\] overlap: an entry without an id already blocks every add-on on https:\/\/bad\.example/);
    assert.throws(() => build({ blocked: [b("https://bad.example", "one"), b("https://bad.example", "two"), b("https://bad.example")] }), /blocked\[2\] and blocked\[0\] overlap/);
});

test("an add-on cannot be both listed and blocked; blocking an impostor of a listed id elsewhere is fine", () => {
    const origin = "https://addons.example.org";
    assert.throws(() => build({ addons: [RADIO], blocked: [{ origin, reason: "broken" }] }), /addons\[0\] \(example-radio\) is on an origin that blocked\[\] also blocks \(https:\/\/addons\.example\.org\)/);
    assert.throws(() => build({ addons: [TIDAL, RADIO], blocked: [{ origin, id: "example-radio", reason: "harmful" }] }), /addons\[1\] \(example-radio\)/);
    // Another id on the same origin, and the same id on another origin (an impostor), are not contradictions.
    assert.equal(parsed({ addons: [RADIO], blocked: [{ origin, id: "other-addon", reason: "broken" }] }).blocked.length, 1);
    assert.equal(parsed({ addons: [TIDAL, RADIO], blocked: [{ origin: "https://fake.example", id: "example-radio", reason: "impersonation" }, { origin: "https://fake.example", id: "tidal", reason: "impersonation" }] }).blocked.length, 2);
});

// ---------------------------------------------------------------------------------------------------------------
// Size

test("size: a full list of ordinary entries fits; a body over 64 KiB is refused at build time", () => {
    const full = {
        addons: Array.from({ length: MAX_ADDONS }, (_, i) => ({
            id: `example-addon-${i}`, name: `Example Add-on Number ${i}`, summary: "Forty characters of ordinary summary text",
            icon: `https://raw.githubusercontent.com/stash-addons/registry/main/icons/example-addon-${i}.png`,
            manifest: `https://example-addon-${i}.addons.example.org/manifest.json`, min_build: 160, platforms: ["android", "web"],
        })),
        blocked: Array.from({ length: MAX_BLOCKED }, (_, i) => ({ origin: `https://harmful-addon-number-${i}.bad.example`, id: `harmful-addon-${i}`, reason: "impersonation" })),
    };
    const size = Buffer.byteLength(build(full), "utf8");
    assert.ok(size < MAX_INDEX_BYTES, `a full ordinary index is ${size} bytes`);
    assert.equal(MAX_INDEX_BYTES, 65536);
    const long = { blocked: Array.from({ length: MAX_BLOCKED }, (_, i) => ({ origin: `https://b${i}.${"x".repeat(63)}.${"y".repeat(63)}.${"z".repeat(63)}.example`, reason: "harmful" })) };
    assert.throws(() => build(long), /the index is \d+ bytes; the app reads at most 65536/);
});

// ---------------------------------------------------------------------------------------------------------------
// Reading published bytes

const PUBLISHED = build({ addons: [TIDAL, { ...RADIO, icon: "https://addons.example.org/radio/icon.png", min_build: 160, platforms: ["android"] }], blocked: [{ origin: "https://bad.example", id: "bad-addon", reason: "harmful" }] });
const edited = (change) => { const o = JSON.parse(PUBLISHED); change(o); return JSON.stringify(o); };

test("readIndex: reads what buildIndex wrote, from a Buffer or a string", () => {
    const index = readIndex(Buffer.from(PUBLISHED, "utf8"));
    assert.deepEqual(index, JSON.parse(PUBLISHED));
    assert.deepEqual(readIndex(PUBLISHED), index);
    assert.deepEqual(Object.keys(index), ["type", "v", "updated_at", "addons", "blocked"]);
});

test("readIndex: refuses what is not an index at all", () => {
    const bad = [
        ["not UTF-8", Buffer.from([0x7b, 0x22, 0xff, 0xfe, 0x22, 0x7d]), /not UTF-8/],
        ["a truncated UTF-8 character", Buffer.concat([Buffer.from(PUBLISHED, "utf8"), Buffer.from([0xc3])]), /not UTF-8/],
        ["a byte order mark", Buffer.concat([Buffer.from([0xef, 0xbb, 0xbf]), Buffer.from(PUBLISHED, "utf8")]), /not JSON/],
        ["UTF-16", Buffer.from(PUBLISHED, "utf16le"), /not (UTF-8|JSON)/],
        ["not JSON", "<html>404</html>", /not JSON/],
        ["empty", "", /not JSON/],
        ["cut short", PUBLISHED.slice(0, -20), /not JSON/],
        ["a list", "[]", /not a JSON object/],
        ["null", "null", /not a JSON object/],
        ["a string", '"stash-addon-index"', /not a JSON object/],
        ["no type", edited((o) => { delete o.type; }), /this is not an add-on index/],
        ["another type", edited((o) => { o.type = "stash-addon-list"; }), /type is not/],
        ["type in another case", edited((o) => { o.type = "Stash-Addon-Index"; }), /type is not/],
        ["type as a list", edited((o) => { o.type = [INDEX_TYPE]; }), /type is not/],
        ["v 2", edited((o) => { o.v = 2; }), /v is not 1/],
        ["v as text", edited((o) => { o.v = "1"; }), /v is not 1/],
        ["no v", edited((o) => { delete o.v; }), /v is not 1/],
        ["no updated_at", edited((o) => { delete o.updated_at; }), /updated_at is not a whole number/],
        ["updated_at as text", edited((o) => { o.updated_at = String(NOW); }), /updated_at is not a whole number/],
        ["a fraction", edited((o) => { o.updated_at = NOW + 0.5; }), /updated_at is not a whole number/],
        ["zero", edited((o) => { o.updated_at = 0; }), /updated_at is not a whole number/],
        ["negative", edited((o) => { o.updated_at = -NOW; }), /updated_at is not a whole number/],
        ["beyond exact integers", edited((o) => { o.updated_at = 2 ** 60; }), /updated_at is not a whole number/],
        ["no addons", edited((o) => { delete o.addons; }), /addons is missing or not a list/],
        ["no blocked", edited((o) => { delete o.blocked; }), /blocked is missing or not a list/],
        ["addons null", edited((o) => { o.addons = null; }), /addons is missing or not a list/],
        ["blocked as an object", edited((o) => { o.blocked = {}; }), /blocked is missing or not a list/],
    ];
    for (const [why, bytes, message] of bad) assert.throws(() => readIndex(bytes), message, why);
});

test("readIndex: a signed lossless.json is not an index, whatever it holds", () => {
    const config = buildConfig({ relays: [], relay_key: "k".repeat(32), tidal_relay_url: "https://relay.example.org", tidal_relay_token: "t", default_addons: [{ manifest: "https://addons.example.org/manifest.json" }], addon_index: INDEX_URL }, 0, NOW);
    assert.throws(() => readIndex(config), /this is not an add-on index/);
    // And an index holds nothing a config reader looks for.
    for (const key of ["relays", "relay_key", "tidal_relay_url", "tidal_relay_token", "default_addons", "addon_index"]) assert.equal(key in JSON.parse(PUBLISHED), false, key);
});

test("readIndex: unknown fields are ignored at every level and left out of what it returns", () => {
    const later = edited((o) => { o.notes = "x"; o.mirrors = ["https://mirror.example/index.json"]; o.addons[0].category = "music"; o.addons[1].rating = 5; o.blocked[0].since = NOW; });
    assert.deepEqual(readIndex(later), JSON.parse(PUBLISHED));
    assert.throws(() => buildIndex(JSON.parse(later), 0, NOW), /unknown keys: notes, mirrors/);
});

test("readIndex: a known field that breaks a rule refuses the whole index", () => {
    const bad = [
        [(o) => { o.addons[0].id = "Tidal"; }, /addons\[0\]\.id must match/],
        [(o) => { o.addons[1].id = "tidal"; }, /listed twice/],
        [(o) => { o.addons[0].name = ""; }, /name is required text/],
        [(o) => { o.addons[0].name = "x".repeat(61); }, /over 60 characters/],
        [(o) => { o.addons[0].summary = `Loss${cp(0x202e)}less`; }, /invisible character/],
        [(o) => { delete o.addons[0].summary; }, /summary is required text/],
        [(o) => { o.addons[1].icon = "http://addons.example.org/icon.png"; }, /icon must be an https URL/],
        [(o) => { o.addons[1].icon = null; }, /icon must be an https URL/],
        [(o) => { o.addons[1].manifest = "https://addons.example.org/radio/manifest.json?token=x"; }, /manifest must be an https URL/],
        [(o) => { o.addons[1].default = true; }, /exactly one of/],
        [(o) => { o.addons[0].default = false; }, /default must be true/],
        [(o) => { delete o.addons[0].default; }, /exactly one of/],
        [(o) => { o.addons[1].min_build = 0; }, /min_build/],
        [(o) => { o.addons[1].platforms = ["ios"]; }, /platforms/],
        [(o) => { o.addons = Array.from({ length: 65 }, (_, i) => ({ ...TIDAL, id: `a${i}` })); }, /addons holds at most 64/],
        [(o) => { o.blocked[0].origin = "https://bad.example/"; }, /origin must be written exactly/],
        [(o) => { o.blocked[0].origin = "http://bad.example"; }, /origin must be https/],
        [(o) => { o.blocked[0].reason = "spam"; }, /reason must be one of/],
        [(o) => { delete o.blocked[0].reason; }, /reason must be one of/],
        [(o) => { o.blocked[0].id = "Bad Addon"; }, /blocked\[0\]\.id must match/],
        [(o) => { o.blocked.push({ ...o.blocked[0] }); }, /repeats/],
        [(o) => { o.blocked.push({ origin: "https://addons.example.org", reason: "harmful" }); }, /also blocks/],
        [(o) => { o.blocked = Array.from({ length: 257 }, (_, i) => ({ origin: `https://b${i}.example`, reason: "broken" })); }, /blocked holds at most 256/],
    ];
    for (const [change, message] of bad) assert.throws(() => readIndex(edited(change)), message, String(change));
});

test("readIndex: the size cap is on the bytes as fetched", () => {
    const padded = (n) => Buffer.concat([Buffer.from(PUBLISHED, "utf8"), Buffer.alloc(n - Buffer.byteLength(PUBLISHED), 0x20)]);
    assert.doesNotThrow(() => readIndex(padded(MAX_INDEX_BYTES)));
    assert.throws(() => readIndex(padded(MAX_INDEX_BYTES + 1)), /the index is 65537 bytes; the app reads at most 65536/);
});

test("isCanonical: only the publisher's own bytes", () => {
    assert.equal(isCanonical(PUBLISHED), true);
    assert.equal(isCanonical(Buffer.from(PUBLISHED, "utf8")), true);
    assert.equal(isCanonical(build({})), true);
    const o = JSON.parse(PUBLISHED);
    const not = {
        "compact": JSON.stringify(o),
        "four-space indent": `${JSON.stringify(o, null, 4)}\n`,
        "CRLF line endings": PUBLISHED.replace(/\n/g, "\r\n"),
        "no final line feed": PUBLISHED.slice(0, -1),
        "two final line feeds": `${PUBLISHED}\n`,
        "a leading space": ` ${PUBLISHED}`,
        "keys in another order": `${JSON.stringify({ v: 1, type: INDEX_TYPE, updated_at: o.updated_at, addons: o.addons, blocked: o.blocked }, null, 2)}\n`,
        "an unknown field": `${JSON.stringify({ ...o, notes: "x" }, null, 2)}\n`,
        "escaped text": PUBLISHED.replace("Tidal", "\\u0054idal"),
        "not an index": "{}\n",
        "not JSON": "index",
    };
    assert.equal(JSON.parse(not["escaped text"]).addons[0].name, "Tidal");
    for (const [why, text] of Object.entries(not)) assert.equal(isCanonical(text), false, why);
});

// ---------------------------------------------------------------------------------------------------------------
// What is printed

test("describeIndex: ids, origins and reasons; never a manifest path", () => {
    const index = readIndex(build({
        addons: [TIDAL, { ...RADIO, manifest: "https://addons.example.org:8443/private-path/manifest.json" }],
        blocked: [{ origin: "https://bad.example", id: "bad-addon", reason: "harmful" }, { origin: "https://broken.example", reason: "broken" }],
    }));
    const text = describeIndex(index);
    assert.equal(text, `updated_at=${NOW} addons=tidal (default), example-radio (https://addons.example.org:8443) blocked=https://bad.example [bad-addon] harmful, https://broken.example broken`);
    assert.equal(text.includes("private-path"), false);
    assert.equal(describeIndex(readIndex(build({}))), `updated_at=${NOW} addons=(none) blocked=(none)`);
    assert.equal(sigUrlOf(INDEX_URL), "https://raw.githubusercontent.com/stash-addons/registry/main/index.json.sig");
});

// ---------------------------------------------------------------------------------------------------------------
// The signature

const KEY = generateKeyPairSync("ec", { namedCurve: "prime256v1" });
const BYTES = Buffer.from(PUBLISHED, "utf8");

test("a signature over the built bytes verifies the way the device does (SHA256withECDSA, DER, base64 SPKI key)", () => {
    const sig = signIndex(BYTES, KEY.privateKey);
    assert.match(sig, /^[A-Za-z0-9+/]+={0,2}$/, "one line of standard base64");
    const spki = publicKeyBase64(KEY.privateKey); // what LOSSLESS_CONFIG_PUBKEY holds
    assert.equal(spki, KEY.publicKey.export({ type: "spki", format: "der" }).toString("base64"));
    assert.equal(spki, publicKeyBase64(KEY.publicKey));
    assert.equal(verifyIndexSignature(BYTES, sig, spki), true);
    assert.equal(verifyIndexSignature(BYTES, sig, KEY.publicKey), true);
    assert.equal(verifyIndexSignature(PUBLISHED, sig, spki), true, "a string is taken as its UTF-8 bytes");
    // The .sig is trimmed, as the device trims it.
    assert.equal(verifyIndexSignature(BYTES, `${sig}\n`, spki), true);
    assert.equal(verifyIndexSignature(BYTES, `  ${sig}\r\n`, spki), true);
    // The DER signature is a SEQUENCE of two INTEGERs, 70 to 72 bytes for P-256.
    const der = Buffer.from(sig, "base64");
    assert.equal(der[0], 0x30);
    assert.ok(der.length >= 68 && der.length <= 72, `DER length ${der.length}`);
});

test("a signature fails over any other bytes, under any other key, or in any other encoding", () => {
    const sig = signIndex(BYTES, KEY.privateKey);
    const spki = publicKeyBase64(KEY.publicKey);
    const other = generateKeyPairSync("ec", { namedCurve: "prime256v1" });
    assert.equal(verifyIndexSignature(Buffer.concat([BYTES, Buffer.from(" ")]), sig, spki), false, "one byte more");
    assert.equal(verifyIndexSignature(BYTES.subarray(0, -1), sig, spki), false, "one byte less");
    assert.equal(verifyIndexSignature(Buffer.from(PUBLISHED.replace(/\n/g, "\r\n")), sig, spki), false, "CRLF line endings");
    assert.equal(verifyIndexSignature(Buffer.from(JSON.stringify(JSON.parse(PUBLISHED))), sig, spki), false, "the same JSON, written again");
    assert.equal(verifyIndexSignature(BYTES, sig, publicKeyBase64(other.publicKey)), false, "another key");
    assert.equal(verifyIndexSignature(BYTES, signIndex(BYTES, other.privateKey), spki), false, "another signer");
    const wrapped = `${sig.slice(0, 64)}\n${sig.slice(64)}`;
    assert.equal(verifyIndexSignature(BYTES, wrapped, spki), false, "a .sig wrapped over two lines: java.util.Base64's basic decoder refuses it");
    assert.equal(verifyIndexSignature(BYTES, sig.replace(/\+/g, "-").replace(/\//g, "_"), spki), sig === sig.replace(/[+/]/g, ""), "URL-safe base64");
    assert.equal(verifyIndexSignature(BYTES, Buffer.from(sig, "base64").toString("hex"), spki), false, "hex");
    const pem = KEY.publicKey.export({ type: "spki", format: "pem" });
    assert.equal(verifyIndexSignature(BYTES, sig, pem), false, "a PEM public key is not the form the app holds");
    for (const junk of ["", " ", "AAAA", "not base64!", null, undefined, 5, {}]) {
        assert.equal(verifyIndexSignature(BYTES, junk, spki), false, `signature ${String(junk)}`);
        assert.equal(verifyIndexSignature(BYTES, sig, junk), false, `key ${String(junk)}`);
    }
});

test("only a P-256 key signs or verifies: the app holds no other kind", () => {
    const p384 = generateKeyPairSync("ec", { namedCurve: "secp384r1" });
    const rsa = generateKeyPairSync("rsa", { modulusLength: 2048 });
    const ed = generateKeyPairSync("ed25519");
    assert.equal(isP256(KEY.privateKey), true);
    assert.equal(isP256(KEY.publicKey), true);
    for (const [name, pair] of [["P-384", p384], ["RSA", rsa], ["Ed25519", ed]]) {
        assert.equal(isP256(pair.privateKey), false, name);
        assert.throws(() => signIndex(BYTES, pair.privateKey), /must be an EC P-256 \(prime256v1\) private key/, name);
        assert.equal(verifyIndexSignature(BYTES, signIndex(BYTES, KEY.privateKey), publicKeyBase64(pair.publicKey)), false, name);
    }
    for (const notAKey of [null, undefined, "key", {}]) {
        assert.equal(isP256(notAKey), false);
        assert.throws(() => signIndex(BYTES, notAKey), /must be an EC P-256/);
    }
});

test("the same signature verifies with WebCrypto, the web player's verifier (ECDSA P-256, SHA-256)", async () => {
    const sig = signIndex(BYTES, KEY.privateKey);
    // WebCrypto takes r and s as two 32-byte numbers; the .sig holds them as a DER SEQUENCE of two INTEGERs.
    const der = Buffer.from(sig, "base64");
    assert.equal(der[0], 0x30);
    assert.equal(der[1], der.length - 2);
    const ints = [];
    for (let at = 2; at < der.length;) {
        assert.equal(der[at], 0x02);
        const len = der[at + 1];
        ints.push(der.subarray(at + 2, at + 2 + len));
        at += 2 + len;
    }
    assert.equal(ints.length, 2);
    const fixed = (n) => { const body = n[0] === 0 ? n.subarray(1) : n; return Buffer.concat([Buffer.alloc(32 - body.length), body]); };
    const raw = Buffer.concat(ints.map(fixed));
    const key = await webcrypto.subtle.importKey("spki", Buffer.from(publicKeyBase64(KEY.publicKey), "base64"), { name: "ECDSA", namedCurve: "P-256" }, false, ["verify"]);
    assert.equal(await webcrypto.subtle.verify({ name: "ECDSA", hash: "SHA-256" }, key, raw, BYTES), true);
    assert.equal(await webcrypto.subtle.verify({ name: "ECDSA", hash: "SHA-256" }, key, raw, Buffer.concat([BYTES, Buffer.from(" ")])), false);
});
