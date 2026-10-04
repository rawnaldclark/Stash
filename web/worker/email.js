/**
 * The two emails the site sends, through Cloudflare Email Service (the `send_email` binding EMAIL), from
 * EMAIL_FROM (access@stashfm.app) with the display name "Stash". Replies reach the owner through Email
 * Routing. Failures come back as { ok: false, code } using the binding's error codes (E_RATE_LIMIT_EXCEEDED,
 * E_DAILY_LIMIT_EXCEEDED, E_RECIPIENT_SUPPRESSED, E_SENDER_NOT_VERIFIED, ...) and are logged without the
 * address.
 */

const FROM_NAME = "Stash";
const DEFAULT_FROM = "access@stashfm.app";

export async function sendEmail(env, { to, subject, text, html }) {
    try {
        const result = await env.EMAIL.send({ to, from: { email: env.EMAIL_FROM || DEFAULT_FROM, name: FROM_NAME }, subject, text, html });
        return { ok: true, id: result?.messageId };
    } catch (err) {
        const code = err?.code || "E_UNKNOWN";
        console.error(`email not sent (${subject}): ${code} ${err?.message ?? ""}`);
        return { ok: false, code };
    }
}

const page = (body) => `<!doctype html><html><body style="font-family:system-ui,-apple-system,'Segoe UI',Roboto,sans-serif;font-size:16px;line-height:1.5;color:#1a0b2e;margin:0;padding:24px">${body}</body></html>`;

export function codeEmail(code) {
    return {
        // Not in the subject: subjects show on lock screens and in inbox previews.
        subject: "Your Stash sign-in code",
        text: `Your code for stashfm.app is ${code}\n\nIt works for 10 minutes. If you didn't ask for it, you can ignore this email.\n`,
        html: page(`<p>Your code for stashfm.app is</p><p style="font-size:32px;font-weight:700;letter-spacing:6px;margin:8px 0 16px">${code}</p><p>It works for 10 minutes. If you didn't ask for it, you can ignore this email.</p>`),
    };
}

export function welcomeEmail(origin) {
    const link = `${origin}/`;
    return {
        subject: "You're in: Stash early access",
        text: `You're in. Sign in at ${link} with this email address, and we'll send you a code.\n\nThanks for your interest in Stash.\n`,
        html: page(`<p>You're in. Sign in at <a href="${link}" style="color:#7c3aed">${link.replace(/^https:\/\//, "").replace(/\/$/, "")}</a> with this email address, and we'll send you a code.</p><p>Thanks for your interest in Stash.</p>`),
    };
}
