/**
 * The welcome email for a donor who just got early access to stashfm.app (src/access.js, a NEW access:<hash>
 * entry only). The email itself is sent by the stash-mailer Worker (rawnaldclark/stash-web-workspace,
 * mailer/), reached through the service binding MAILER with the shared MAILER_TOKEN secret:
 *
 *   POST /welcome   Authorization: Bearer <MAILER_TOKEN>   {"emails": ["<the donor>"]}
 *
 * It runs after the webhook has answered (ctx.waitUntil), so it never slows or fails the webhook, and it never
 * throws. Without MAILER or MAILER_TOKEN nothing is sent. Logs show a masked address only ("a***@e***.org").
 */

/** The binding's URL: only the path matters, a service binding never leaves Cloudflare. */
const MAILER_WELCOME_URL = "https://stash-mailer/welcome";

/** "ann.lee@example.org" -> "a***@e***.org", for logs. Anything that isn't an address is "***". */
export function maskEmail(email) {
    const s = String(email ?? "").trim().toLowerCase();
    const at = s.lastIndexOf("@");
    if (at < 1 || at === s.length - 1) return "***";
    const domain = s.slice(at + 1);
    const dot = domain.lastIndexOf(".");
    const tld = dot > 0 ? domain.slice(dot) : "";
    return `${s[0]}***@${domain[0]}***${tld.length <= 12 ? tld : ""}`;
}

/** Asks the mailer to welcome [email]. Resolves true when it says sent, false otherwise; never rejects. */
export async function sendWelcome(env, email) {
    if (!env.MAILER || !env.MAILER_TOKEN) {
        console.log("welcome: no MAILER binding or MAILER_TOKEN, skipped");
        return false;
    }
    try {
        const res = await env.MAILER.fetch(MAILER_WELCOME_URL, {
            method: "POST",
            headers: { authorization: `Bearer ${env.MAILER_TOKEN}`, "content-type": "application/json" },
            body: JSON.stringify({ emails: [email] }),
        });
        let body = null;
        try {
            body = await res.json();
        } catch {
            // not JSON
        }
        const result = body?.results?.[0];
        if (res.status === 200 && result?.sent === true) {
            console.log(`welcome sent to ${maskEmail(email)}`);
            return true;
        }
        console.error(`welcome not sent to ${maskEmail(email)}: ${res.status} ${String(result?.error ?? body?.error ?? "").slice(0, 64)}`);
        return false;
    } catch (err) {
        console.error(`welcome not sent to ${maskEmail(email)}: ${String(err?.message ?? err).slice(0, 120)}`);
        return false;
    }
}
