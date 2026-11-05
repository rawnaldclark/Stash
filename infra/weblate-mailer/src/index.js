// Receives mail from the Railway bridge over HTTPS and sends it with the
// Email Service binding. No API token is needed: the binding is the permission.
// The From address comes from Weblate (WEBLATE_DEFAULT_FROM_EMAIL), but only
// addresses on the allowed domain are accepted; anything else falls back to
// FROM_ADDRESS. So a leaked secret can't send as other domains.

const MAX_BODY_BYTES = 1024 * 1024; // 1 MB
const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

function safeEqual(a, b) {
  const enc = new TextEncoder();
  const x = enc.encode(a);
  const y = enc.encode(b);
  if (x.length !== y.length) return false;
  return crypto.subtle.timingSafeEqual(x, y);
}

function pickFrom(env, requested) {
  const fallback = String(env.FROM_ADDRESS || "").trim();
  const domain = String(env.ALLOWED_FROM_DOMAIN || fallback.split("@")[1] || "").toLowerCase();
  const addr = String(requested || "").trim().toLowerCase();
  if (domain && EMAIL_RE.test(addr) && addr.endsWith("@" + domain)) return addr;
  if (addr) console.warn(`From address "${addr}" is not on ${domain}; using ${fallback}`);
  return fallback;
}

const json = (obj, status = 200) =>
  new Response(JSON.stringify(obj), {
    status,
    headers: { "Content-Type": "application/json" },
  });

export default {
  async fetch(request, env) {
    if (request.method !== "POST") return json({ ok: false, error: "Method not allowed" }, 405);

    if (!env.MAIL_SECRET || !safeEqual(request.headers.get("Authorization") || "", `Bearer ${env.MAIL_SECRET}`)) {
      return json({ ok: false, error: "Unauthorized" }, 401);
    }

    const raw = await request.text();
    if (raw.length > MAX_BODY_BYTES) return json({ ok: false, error: "Too large" }, 413);

    let m;
    try {
      m = JSON.parse(raw);
    } catch {
      return json({ ok: false, error: "Bad JSON" }, 400);
    }

    const to = String(m.to || "").trim();
    if (!EMAIL_RE.test(to)) return json({ ok: false, error: "Invalid recipient" }, 400);
    if (!m.text && !m.html) return json({ ok: false, error: "Empty message" }, 400);

    const message = {
      to,
      from: { email: pickFrom(env, m.from_address), name: String(m.from_name || env.FROM_NAME || "").slice(0, 100) || undefined },
      subject: String(m.subject || "(no subject)").slice(0, 300),
      text: String(m.text || " "),
    };
    if (m.html) message.html = String(m.html);
    if (m.reply_to && EMAIL_RE.test(String(m.reply_to))) message.replyTo = String(m.reply_to);

    try {
      const result = await env.EMAIL.send(message);
      return json({ ok: true, messageId: result?.messageId });
    } catch (e) {
      console.error("Email send failed:", e?.message || e);
      return json({ ok: false, error: String(e?.message || e).slice(0, 300) }, 502);
    }
  },
};
