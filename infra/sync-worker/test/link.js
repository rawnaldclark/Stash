/** Pairing steps shared by the tests: a code opened through the player, a phone's answer, a whole first link. */
import assert from "node:assert/strict";
import { webcrypto } from "node:crypto";
import { base64url } from "../src/http.js";
import { box, makeDevice, record, req } from "./fakes.js";

export const spaceIdNew = () => base64url(webcrypto.getRandomValues(new Uint8Array(16)));

/** Opens a code in the browser (through the player), and returns its id. */
export async function openCode(w, browser, headers = { "X-Stash-Session": "sess_abcdef12" }) {
    const res = await w.fetch(req("POST", "/v1/pair", { player: true, body: { device: record(browser) }, headers }));
    assert.equal(res.status, 201);
    const body = await res.json();
    assert.match(body.pairId, /^[A-Za-z0-9_-]{22}$/);
    return body;
}

export const answerBody = (phone) => ({ phonePub: phone.pub, ct: box(), device: record(phone) });

/** The whole first link (§5.1, neither device has a space): browser code, phone answer, browser poll, phone creates. */
export async function linkNew(w) {
    const phone = await makeDevice("p");
    const browser = await makeDevice("w");
    const { pairId } = await openCode(w, browser);
    assert.equal((await w.fetch(req("POST", `/v1/pair/${pairId}/answer`, { body: answerBody(phone) }))).status, 201);
    const spaceId = spaceIdNew();
    const created = await w.fetch(req("POST", "/v1/spaces", { device: phone, body: { pairId, spaceId } }));
    assert.equal(created.status, 201);
    assert.deepEqual(await created.json(), { spaceId, epoch: 1 });
    return { phone, browser, spaceId, pairId };
}

