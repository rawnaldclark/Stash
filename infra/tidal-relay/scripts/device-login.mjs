#!/usr/bin/env node
import readline from "node:readline/promises";
import { stdin as input, stdout as output } from "node:process";

const origin = (process.env.TIDAL_RELAY_URL || "").replace(/\/$/, "");
const adminToken = process.env.TIDAL_ADMIN_TOKEN || "";
if (!origin.startsWith("https://") || !adminToken) {
  console.error("Set TIDAL_RELAY_URL (https origin) and TIDAL_ADMIN_TOKEN in this shell first.");
  process.exit(2);
}
const headers = { "X-Access-Token": adminToken, Accept: "application/json" };
const accountState = process.argv.includes("--reserve") ? "reserve" : "live";
const start = await fetch(`${origin}/admin/device/start`, {
  method: "POST", headers: { ...headers, "Content-Type": "application/json" },
  body: JSON.stringify({ account_state: accountState }),
});
if (!start.ok) throw new Error(`device start failed: HTTP ${start.status}`);
const device = await start.json();
console.log(`Approve this Tidal sign-in in your browser:\n${device.verification_uri}\nCode: ${device.user_code}`);
const rl = readline.createInterface({ input, output });
await rl.question("After approving it in Tidal, press Enter here to finish linking: ");
rl.close();

const deadline = Date.now() + Number(device.expires_in || 300) * 1000;
while (Date.now() < deadline) {
  const response = await fetch(`${origin}/admin/device/poll`, {
    method: "POST", headers: { ...headers, "Content-Type": "application/json" },
    body: JSON.stringify({ user_code: device.user_code }),
  });
  const result = await response.json().catch(() => ({}));
  if (response.ok && result.status === "authorized") {
    console.log(`Tidal account authorized as ${accountState} and token stored in the relay D1 database.`);
    process.exit(0);
  }
  if (result.status === "expired" || response.status === 410) throw new Error("device code expired; start a new device login");
  if (!response.ok && response.status !== 202) throw new Error(`device poll failed: HTTP ${response.status}`);
  await new Promise((resolve) => setTimeout(resolve, Math.max(4, Number(result.retry_after || device.interval || 5)) * 1000));
}
throw new Error("device code expired before authorization completed");
