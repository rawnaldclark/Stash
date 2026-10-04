/**
 * Every STASH_KV key the website uses. The namespace is shared with the tip jar (infra/tipjar-worker), and
 * the full list of keys, with who writes each one and how long it's kept, is in infra/tipjar-worker/README.md,
 * "STASH_KV keys". Change both together.
 */
export const KEYS = {
    /** On the access list: { source: "kofi", firstAt, lastAt } or { source: "approved" | "manual", by, at }. */
    access: (h) => `access:${h}`,
    /** A sign-in code: { c: HMAC of the code, n: tries so far, exp: unix seconds }. 10 minutes. */
    code: (h) => `code:${h}`,
    /** Codes sent to this email in the current hour: { n, until }. 1 hour. */
    sends: (h) => `sends:${h}`,
    /** An access request: { email, note, at }. Until handled, or 30 days. */
    request: (h) => `request:${h}`,
    requestPrefix: "request:",
    /** A month's goal: { cents, entries: [{ cents, source, at, id?, orig? }], updatedAt }. */
    goal: (month) => `goal:${month}`,
    /** The tip jar's hash of HASHCHECK_EMAIL, to spot an EMAIL_PEPPER mismatch between the two Workers. */
    hashCheck: "meta:hashcheck",
};

export const HASHCHECK_EMAIL = "hashcheck@stashfm.app";
