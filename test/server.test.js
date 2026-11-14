"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const dgram = require("node:dgram");
const { once } = require("node:events");
const { createDashboardServer, getLanEndpoints } = require("../src/server");

test("real Wi-Fi and Ethernet interfaces are preferred over VPN adapters", () => {
  const endpoints = getLanEndpoints();
  assert.ok(Array.isArray(endpoints));
  for (let index = 1; index < endpoints.length; index += 1) {
    assert.ok(endpoints[index - 1].score <= endpoints[index].score);
  }
});

test("server serves the dashboard, accepts JSON UDP telemetry and exposes status", async (t) => {
  const app = createDashboardServer({
    host: "127.0.0.1",
    port: 0,
    udpPort: 0,
    open: false,
    capture: false
  });

  t.after(async () => {
    await app.close();
  });

  app.server.listen(0, "127.0.0.1");
  await Promise.all([
    once(app.server, "listening"),
    once(app.udpSocket, "listening")
  ]);

  const httpPort = app.server.address().port;
  const udpPort = app.udpSocket.address().port;
  const page = await fetch(`http://127.0.0.1:${httpPort}/`);
  const html = await page.text();
  assert.equal(page.status, 200);
  assert.match(html, /WRCG 无线仪表盘/);

  const sender = dgram.createSocket("udp4");
  const payload = Buffer.from(JSON.stringify({
    speedKph: 145,
    rpm: 7350,
    gear: 5,
    throttle: 0.82,
    brake: 0,
    stageTimeMs: 42000,
    stageProgress: 0.4,
    stageDistanceKm: 8.4,
    stageLengthKm: 21
  }));
  await new Promise((resolve, reject) => {
    sender.send(payload, udpPort, "127.0.0.1", (error) => {
      sender.close();
      if (error) reject(error);
      else resolve();
    });
  });

  let status;
  for (let attempt = 0; attempt < 20; attempt += 1) {
    status = await fetch(`http://127.0.0.1:${httpPort}/api/status`).then((response) => response.json());
    if (status.mode === "live") break;
    await new Promise((resolve) => setTimeout(resolve, 20));
  }

  assert.equal(status.mode, "live");
  assert.equal(status.source, "json-udp");
  assert.equal(status.udp.packets, 1);
  assert.equal(status.udp.lastPacketSize, payload.length);
  assert.equal(app.state.frame.speedKph, 145);
  assert.equal(app.state.frame.gear, "5");
});

test("server serves embedded dashboard assets without a public directory", async (t) => {
  const app = createDashboardServer({
    host: "127.0.0.1",
    port: 0,
    udpPort: 0,
    open: false,
    capture: false,
    publicAssets: {
      "index.html": "<!doctype html><title>WRCG embedded dashboard</title>",
      "app.js": "console.log('embedded');"
    }
  });

  t.after(async () => {
    await app.close();
  });

  app.server.listen(0, "127.0.0.1");
  await Promise.all([
    once(app.server, "listening"),
    once(app.udpSocket, "listening")
  ]);
  const port = app.server.address().port;
  const page = await fetch(`http://127.0.0.1:${port}/`);
  assert.equal(page.status, 200);
  assert.match(await page.text(), /embedded dashboard/);

  const script = await fetch(`http://127.0.0.1:${port}/app.js`);
  assert.equal(script.status, 200);
  assert.match(script.headers.get("content-type"), /text\/javascript/);
  assert.match(await script.text(), /embedded/);

  const missing = await fetch(`http://127.0.0.1:${port}/missing.css`);
  assert.equal(missing.status, 404);
});

test("websocket endpoint completes the upgrade and sends telemetry", async (t) => {
  const app = createDashboardServer({
    host: "127.0.0.1",
    port: 0,
    udpPort: 0,
    open: false,
    capture: false
  });

  t.after(async () => {
    await app.close();
  });

  app.server.listen(0, "127.0.0.1");
  await Promise.all([
    once(app.server, "listening"),
    once(app.udpSocket, "listening")
  ]);
  const port = app.server.address().port;
  const socket = new WebSocket(`ws://127.0.0.1:${port}/ws`);
  t.after(() => socket.close());
  await once(socket, "open");
  socket.send(JSON.stringify({ type: "hello" }));

  let telemetry;
  while (!telemetry) {
    const [event] = await once(socket, "message");
    const message = JSON.parse(String(event.data));
    if (message.type === "telemetry") telemetry = message.frame;
  }

  assert.equal(typeof telemetry.speedKph, "number");
  assert.equal(typeof telemetry.gear, "string");
  assert.equal(typeof telemetry.sentAt, "number");
  assert.equal(app.hub.clients.size, 1);

  let frames = 0;
  socket.addEventListener("message", (event) => {
    const message = JSON.parse(String(event.data));
    if (message.type === "telemetry") frames += 1;
  });
  await new Promise((resolve) => setTimeout(resolve, 1100));
  assert.ok(frames >= 25 && frames <= 40, `expected near-30 Hz stream, received ${frames} frames`);
});

test("server derives hybrid discharge and charge from changing KERS battery", async (t) => {
  const app = createDashboardServer({
    host: "127.0.0.1",
    port: 0,
    udpPort: 0,
    open: false,
    capture: false
  });
  t.after(async () => {
    await app.close();
  });

  app.server.listen(0, "127.0.0.1");
  await Promise.all([
    once(app.server, "listening"),
    once(app.udpSocket, "listening")
  ]);
  const udpPort = app.udpSocket.address().port;
  const sender = dgram.createSocket("udp4");
  t.after(() => sender.close());

  const sendBattery = (battery) => new Promise((resolve, reject) => {
    const packet = Buffer.alloc(280);
    for (const offset of [100, 104, 108, 112]) packet.writeFloatLE(10, offset);
    packet.writeFloatLE(0.85, 116);
    packet.writeFloatLE(0.02, 124);
    packet.writeFloatLE(3, 132);
    packet.writeFloatLE(620, 148);
    packet.writeFloatLE(battery, 160);
    packet.writeFloatLE(0.9, 164);
    packet.writeFloatLE(890, 252);
    sender.send(packet, udpPort, "127.0.0.1", (error) => error ? reject(error) : resolve());
  });

  await sendBattery(0.8);
  await waitFor(() => app.state.frame.batteryPercent !== null);
  await sendBattery(0.75);
  await waitFor(() => app.state.frame.hybridState === "discharge");
  assert.ok(Math.abs(app.state.frame.batteryPercent - 0.75 / 0.9) < 0.001);

  await sendBattery(0.79);
  await waitFor(() => app.state.frame.hybridState === "charge");
  assert.ok(Math.abs(app.state.frame.batteryPercent - 0.79 / 0.9) < 0.001);
});

async function waitFor(predicate, timeoutMs = 1000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (predicate()) return;
    await new Promise((resolve) => setTimeout(resolve, 10));
  }
  throw new Error("Timed out waiting for telemetry state");
}
