/**
 * Every ACCESS_KV key the website uses. The namespace is shared with the tip jar (infra/tipjar-worker), which
 * writes Ko-fi supporters and donations into it; this Worker never binds the tip jar's STASH_KV. The full list
 * of keys, with who writes each one and how long it's kept, is in infra/tipjar-worker/README.md, "KV keys".
 * Change both together.
 */
export const KEYS = {
    /**
     * On the access list: { source: "kofi", firstAt, lastAt } or { source: "approved" | "manual", by, at, via? }; via: "open"
     * marks someone kept from an open window. Open-window sign-ups made before 2026-10-13 ~02:00 UTC are here too, as
     * { source: "open", at } with metadata { source: "open", at } (open.js), until the admin page keeps or removes them.
     */
    access: (h) => `access:${h}`,
    accessPrefix: "access:",
    /**
     * An open-window sign-up (open.js): { at }, metadata { at }. Beside the access list, never in it, so keeping or
     * removing sign-ups can't touch a supporter. Until the admin page keeps or removes it.
     */
    open: (h) => `open:${h}`,
    openPrefix: "open:",
    /** Codes sent on one UTC day to emails on neither list, while a window is open: { n }. 2 days. */
    openSends: (day) => `opensends:${day}`,
    /** The admin page's last "Keep them all" / "Remove them all": { action, at }. 10 minutes. */
    openAction: "meta:openaction",
    /**
     * Up to 3 live sign-in codes one browser asked for, and that browser's wrong tries: { codes: [{ c: HMAC, exp }], n }.
     * [browser] is its stash_signin id (worker/session.js). 10 minutes after the newest code.
     */
    code: (h, browser) => `code:${h}:${browser}`,
    /** Every browser's codes for one email (the admin page clears them when it removes the email). */
    codePrefix: (h) => `code:${h}:`,
    /** Codes sent to this email in the current hour: { n, until }. 1 hour. */
    sends: (h) => `sends:${h}`,
    /** An access request: { email, note, at }, metadata { at }. Until handled, or 30 days. */
    request: (h) => `request:${h}`,
    requestPrefix: "request:",
    /** One goal entry: goal:<YYYY-MM>:<kofi|manual>:<id>, metadata { cents, source, at, by?, orig? }. */
    goalEntry: (month, kind, id) => `goal:${month}:${kind}:${id}`,
    goalMonth: (month) => `goal:${month}:`,
    /**
     * The tip jar's Ko-fi payments for one email still under the $5 entry: kofipay:<h>:<Ko-fi transaction id>, { cents, at }
     * (infra/tipjar-worker/src/access.js). The tip jar writes them; this Worker only deletes them, on Remove access.
     */
    kofiPayPrefix: (h) => `kofipay:${h}:`,
    /** The tip jar's hash of HASHCHECK_EMAIL, to spot an EMAIL_PEPPER mismatch between the two Workers. */
    hashCheck: "meta:hashcheck",
};

export const HASHCHECK_EMAIL = "hashcheck@stashfm.app";
