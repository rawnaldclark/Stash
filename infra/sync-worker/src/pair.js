/**
 * Pairing slots (spec §5.1), pure: every function takes the slot (or null), the request and the time, and returns
 * `{ status, body?, state? }`, where `state` is the slot to store when it changed. src/pair-slot.js keeps it in storage,
 * holds the long-polls and deletes the slot with its alarm.
 *
 * A slot: the browser's device (id, token hash, public key, encrypted label), opened through the player only; then one
 * answer from a phone (its ephemeral key, its encrypted message, and its own device record); maybe one reply from the
 * browser (when it holds the space); then one claim, made by the device that adds the other to a space, which burns it.
 */
import { err, ok, sameText } from "./http.js";
import { cleanBox, cleanDevice, isPub, LIMITS } from "./validate.js";

/** The code on screen lives 3 minutes; it can be answered only within that time. */
export const PAIR_TTL_MS = 3 * 60_000;
/** After that, the two devices have this long to finish (reply, claim) before the slot is deleted. */
export const CLAIM_GRACE_MS = 2 * 60_000;
/** Long-polls (the browser waiting for the answer, the phone for the reply) answer 204 after this. */
export const POLL_MS = 25_000;

const expired = () => err(410, "expired", "This code expired. Show a new one.");
const used = () => err(409, "used", "This code was already used. Show a new one on your computer.");
const forbidden = () => err(403, "forbidden", "Not a device of this code");
const alive = (s, now) => !!s && now < s.expiresAt + CLAIM_GRACE_MS;
const is = (caller, rec) => !!caller && !!rec && caller.deviceId === rec.id && sameText(caller.tokenHash, rec.tokenHash);

export function openSlot(pairId, device, now) {
    const browser = cleanDevice(device, "web");
    if (!browser) return err(400, "bad_request", "Not a device");
    const state = { v: 1, pairId, browser, createdAt: now, expiresAt: now + PAIR_TTL_MS, answer: null, reply: null, claimed: false };
    return { status: 201, body: { pairId, expiresAt: state.expiresAt }, state };
}

/** The phone reads the browser's encrypted label for its confirm sheet. */
export function readLabel(s, now) {
    if (!s || now >= s.expiresAt) return expired();
    if (s.answer || s.claimed) return used();
    return ok({ labelCt: s.browser.labelCt, expiresAt: s.expiresAt });
}

/** The phone's answer: `{ phonePub, ct, device: { id, tokenHash, pub, labelCt } }`. One per slot. */
export function answerSlot(s, body, now) {
    const phonePub = body?.phonePub;
    const ct = cleanBox(body?.ct, LIMITS.pairChars);
    const device = cleanDevice(body?.device, "phone");
    if (!isPub(phonePub) || !ct || !device) return err(400, "bad_request", "Not an answer");
    if (!s || now >= s.expiresAt) return expired();
    if (s.answer || s.claimed) return used();
    if (device.id === s.browser.id) return err(400, "bad_request", "Same device on both sides");
    return { status: 201, body: null, state: { ...s, answer: { phonePub, ct, device, at: now } } };
}

/** The browser's long-poll: 200 with the answer, 204 still waiting, 410 gone. */
export function pollSlot(s, caller, now) {
    if (!alive(s, now)) return expired();
    if (!is(caller, s.browser)) return forbidden();
    if (s.answer) return ok({ phonePub: s.answer.phonePub, ct: s.answer.ct });
    if (now >= s.expiresAt) return expired();
    return { status: 204, body: null };
}

/** The browser's reply when it holds the space and the phone has none (§5.1 step 4): `{ ct }`. One per slot. */
export function replySlot(s, caller, body, now) {
    const ct = cleanBox(body?.ct, LIMITS.pairChars);
    if (!ct) return err(400, "bad_request", "Not a reply");
    if (!alive(s, now)) return expired();
    if (!is(caller, s.browser)) return forbidden();
    if (!s.answer) return err(409, "pending", "No answer yet");
    if (s.reply || s.claimed) return used();
    return { status: 201, body: null, state: { ...s, reply: { ct, at: now } } };
}

/** The phone's long-poll for that reply. */
export function readReply(s, caller, now) {
    if (!alive(s, now)) return expired();
    if (!s.answer || !is(caller, s.answer.device)) return forbidden();
    if (s.reply) return ok({ ct: s.reply.ct });
    return { status: 204, body: null };
}

/**
 * Burns the slot for the device that completes membership (§5.1 step 5).
 * `create`: the phone that answered makes a new space with both devices → `{ phone, browser }`.
 * `join`: either device, already a member of a space, adds the other → `{ add }` (the browser's or the phone's record).
 */
export function claimSlot(s, caller, mode, now) {
    if (!alive(s, now)) return expired();
    if (!s.answer) return err(409, "pending", "No answer yet");
    if (s.claimed) return used();
    const phone = s.answer.device;
    let body;
    if (mode === "create") {
        if (!is(caller, phone)) return forbidden();
        body = { phone, browser: s.browser };
    } else if (is(caller, phone)) body = { add: s.browser };
    else if (is(caller, s.browser)) body = { add: phone };
    else return forbidden();
    return { status: 200, body, state: { ...s, claimed: true } };
}
