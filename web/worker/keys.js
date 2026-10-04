/**
 * Every ACCESS_KV key the website uses. The namespace is shared with the tip jar (infra/tipjar-worker), which
 * writes Ko-fi supporters and donations into it; this Worker never binds the tip jar's STASH_KV. The full list
 * of keys, with who writes each one and how long it's kept, is in infra/tipjar-worker/README.md, "KV keys".
 * Change both together.
 */
export const KEYS = {
    /** On the access list: { source: "kofi", firstAt, lastAt } or { source: "approved" | "manual", by, at }. */
    access: (h) => `access:${h}`,
    /** Up to 3 live sign-in codes and the wrong tries: { codes: [{ c: HMAC, exp }], n }. 10 minutes after the newest. */
    code: (h) => `code:${h}`,
    /** Codes sent to this email in the current hour: { n, until }. 1 hour. */
    sends: (h) => `sends:${h}`,
    /** An access request: { email, note, at }, metadata { at }. Until handled, or 30 days. */
    request: (h) => `request:${h}`,
    requestPrefix: "request:",
    /** One goal entry: goal:<YYYY-MM>:<kofi|manual>:<id>, metadata { cents, source, at, by?, orig? }. */
    goalEntry: (month, kind, id) => `goal:${month}:${kind}:${id}`,
    goalMonth: (month) => `goal:${month}:`,
    /** The tip jar's hash of HASHCHECK_EMAIL, to spot an EMAIL_PEPPER mismatch between the two Workers. */
    hashCheck: "meta:hashcheck",
};

export const HASHCHECK_EMAIL = "hashcheck@stashfm.app";
