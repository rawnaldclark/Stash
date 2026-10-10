/** Pairing steps shared by the tests: a code opened through the player, a phone's answer, a whole first link. */
import assert from "node:assert/strict";
import { randomBytes } from "node:crypto";
import { box, makeDevice, record, req } from "./fakes.js";

/** A space id as a phone mints it (sync-v1): `s_` + 16 random bytes, base64url. */
export const newSpaceId = () => `s_${randomBytes(16).toString("base64url")}`;

/** Opens a code in the browser (through the player), and returns its id. */
export async function openCode(w, browser, headers = { "X-Stash-Session": "sess_abcdef12" }) {
    const res = await w.fetch(req("POST", "/v1/pair", { player: true, body: { device: record(browser) }, headers }));
    assert.equal(res.status, 201);
    const body = await res.json();
    assert.match(body.pairId, /^[A-Za-z0-9_-]{22}$/);
    return body;
}

export const answerBody = (phone) => ({ phonePub: phone.pub, ct: box(), device: record(phone) });

/** What the phone reads before answering: the label and the browser's id and key. */
export async function readLabel(w, pairId) {
    const res = await w.fetch(req("GET", `/v1/pair/${pairId}/label`));
    assert.equal(res.status, 200);
    return res.json();
}

/** The whole first link (§5.1, neither device has a space): browser code, phone answer, phone creates the space. */
export async function linkNew(w, { headers } = {}) {
    const phone = await makeDevice("p");
    const browser = await makeDevice("w");
    const { pairId } = await openCode(w, browser, headers);
    await readLabel(w, pairId);
    const spaceId = newSpaceId();
    assert.equal((await w.fetch(req("POST", `/v1/pair/${pairId}/answer`, { body: answerBody(phone) }))).status, 201);
    const created = await w.fetch(req("POST", "/v1/spaces", { device: phone, body: { pairId, spaceId, labels: { [phone.id]: box(1), [browser.id]: box(1) } } }));
    assert.equal(created.status, 201);
    assert.deepEqual(await created.json(), { spaceId, epoch: 1 });
    return { phone, browser, spaceId, pairId };
}

/** A second device joins through a new code: `sponsor` is already a member, `epoch` is the key epoch it hands over. */
export async function joinBrowser(w, spaceId, sponsor, { epoch = 1, envelope } = {}) {
    const b = await makeDevice("w");
    const { pairId } = await openCode(w, b);
    await w.fetch(req("POST", `/v1/pair/${pairId}/answer`, { body: answerBody(sponsor) }));
    const res = await w.fetch(req("POST", `/v1/spaces/${spaceId}/devices`, { device: sponsor, body: { pairId, epoch, labelCt: box(1), ...(envelope ? { envelope } : {}) } }));
    return { device: b, pairId, res };
}

/** Both devices' labels re-sealed under the new space's data key (epoch 1), as `POST /v1/spaces` carries them. */
export const labelsFor = (phone, browser) => ({ [phone.id]: box(1), [browser.id]: box(1) });
