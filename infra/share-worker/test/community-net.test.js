import { test } from "node:test";
import assert from "node:assert/strict";
import { networkOf } from "../src/http.js";
import { limitKey } from "../src/index.js";

test("an IPv4 address is its own network", () => {
    assert.equal(networkOf("203.0.113.9"), "203.0.113.9");
    assert.equal(networkOf("::ffff:203.0.113.9"), "::ffff:203.0.113.9");
});

test("IPv6 addresses are grouped by their /56", () => {
    // Same first 56 bits (2001:db8:aa:bb00::/56), different /64s and hosts.
    assert.equal(networkOf("2001:db8:aa:bb01::1"), networkOf("2001:0db8:00aa:bbff:1:2:3:4"));
    assert.equal(networkOf("2001:db8:aa:bb01::1"), "2001:db8:aa:bb/56");
    // A different /56.
    assert.notEqual(networkOf("2001:db8:aa:bb01::1"), networkOf("2001:db8:aa:cc01::1"));
    assert.equal(networkOf("2001:db8::1"), "2001:db8:0:0/56");
    // A short 4th group is still cut by value: ab (00ab) and ff00 are different /56s.
    assert.notEqual(networkOf("2001:db8:1:ab::"), networkOf("2001:db8:1:ff00::"));
    assert.equal(networkOf("2001:db8:1:ab::"), "2001:db8:1:0/56");
    // The :: is expanded before the cut: abcd is the 5th group, outside the /56.
    assert.equal(networkOf("2001:db8::abcd:1:2:3"), "2001:db8:0:0/56");
});

test("limitKey is still exported from index.js and still cuts IPv6 to /64", () => {
    assert.equal(limitKey("2001:db8:aa:bb01::1"), "2001:db8:aa:bb01");
});
