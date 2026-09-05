import { createHash } from "node:crypto";

export const QOBUZ_ORIGIN = "https://www.qobuz.com";
/** Same UA the app sends (QbdlxApiClient.UA): the relay should look like the client that already works. */
export const UA = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Mobile Safari/537.36";
/** The client's whole budget is 8 s (spec §1). Two attempts at this timeout plus the D1 round trips still fit. */
export const QOBUZ_TIMEOUT_MS = 3000;

const md5 = (s) => createHash("md5").update(s).digest("hex");

/**
 * `request_sig` for track/getFileUrl: the exact concatenation QbdlxSigner.signGetFileUrl
 * uses on the device, locked by the same HAR vectors. `ts` is unix seconds and MUST be
 * the value sent as `request_ts`.
 */
export function signGetFileUrl(ts, trackId, formatId, appSecret) {
    return md5("trackgetFileUrl" + "format_id" + formatId + "intentstream" + "track_id" + trackId + ts + appSecret);
}

/** Unix-seconds expiry Qobuz embeds in its CDN URL (the client parses the same param), or null. */
export function etspOf(url) {
    const m = /[?&]etsp=(\d+)/.exec(url || "");
    return m ? Number(m[1]) : null;
}

/** The web player's app_id/app_secret pair inside its JS bundle (QobuzWebCredentialsClient.CREDS_RE). */
export function extractCreds(js) {
    const m = /app_id:"(\d{9})",app_secret:"([a-f0-9]{32})"/.exec(js || "");
    return m ? { app_id: m[1], app_secret: m[2] } : null;
}

/**
 * Classifies one getFileUrl reply the way QbdlxApiClient.get + classify do on the device:
 *   { kind: "ok", url, formatId, bitDepth, sampleRateHz, etsp }   stream it
 *   { kind: "dead", reason }      this account is finished (401, USER_BLOCKED, UserUnauthenticated): stop using it
 *   { kind: "locked" }            Qobuz 404, no URL, or a lossy format: region lock / unknown track → 404 (spec §1)
 *   { kind: "transient", reason } anything else: cool the account briefly, try another
 */
export function classify(status, body) {
    if (status === 401) return { kind: "dead", reason: "401" };
    if (status === 403 && /USER_BLOCKED/i.test(body)) return { kind: "dead", reason: "USER_BLOCKED" };
    // Qobuz answers 404 for a track id it does not know: a catalog miss, not an account problem.
    if (status === 404) return { kind: "locked" };
    if (status !== 200) return { kind: "transient", reason: `http_${status}` };
    let f;
    try { f = JSON.parse(body); } catch { return { kind: "transient", reason: "bad_json" }; }
    const unauth = (f.restrictions || []).some((r) => /^UserUnauthenticated$/i.test(r?.code || ""));
    if (unauth) return { kind: "dead", reason: "UserUnauthenticated" };
    // A 30 s sample or an MP3 (format 5) for a lossless request is about the TRACK — not
    // streamable in full for this account's region/licence — never about the account.
    // 2026-09-05: treating it as "dead" retired five healthy accounts overnight.
    if (f.sample === true || !f.url || !(f.format_id >= 6)) return { kind: "locked" };
    if (!f.url.startsWith("https://")) return { kind: "transient", reason: "plaintext_url" };
    return {
        kind: "ok",
        url: f.url,
        formatId: f.format_id,
        bitDepth: f.bit_depth | 0,
        sampleRateHz: Math.round((f.sampling_rate || 0) * 1000), // Qobuz says kHz; the wire contract is Hz (spec §1)
        etsp: etspOf(f.url),
    };
}

/** One signed getFileUrl call for `account` = { label, token, app_id, app_secret }. Never throws. */
export async function mintFromQobuz(fetchImpl, account, trackId, formatId, nowSec) {
    const sig = signGetFileUrl(nowSec, trackId, formatId, account.app_secret);
    const url = `${QOBUZ_ORIGIN}/api.json/0.2/track/getFileUrl?track_id=${trackId}&format_id=${formatId}`
        + `&app_id=${account.app_id}&request_ts=${nowSec}&request_sig=${sig}&intent=stream`;
    let res;
    try {
        res = await fetchImpl(url, {
            headers: { "X-App-Id": account.app_id, "X-User-Auth-Token": account.token, Accept: "application/json", "User-Agent": UA },
            signal: AbortSignal.timeout(QOBUZ_TIMEOUT_MS),
        });
    } catch (e) {
        return { kind: "transient", reason: e?.name === "TimeoutError" ? "timeout" : "network" };
    }
    return classify(res.status, await res.text());
}
