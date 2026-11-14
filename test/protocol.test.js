"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const { normalizeGear, normalizeTelemetry, parseDatagram } = require("../src/protocol");
const { DemoTelemetry } = require("../src/demo");

test("gear values are normalized for display", () => {
  assert.equal(normalizeGear(-1), "R");
  assert.equal(normalizeGear(0), "N");
  assert.equal(normalizeGear(4), "4");
  assert.equal(normalizeGear("n"), "N");
  assert.equal(normalizeGear("r"), "R");
});

test("telemetry aliases and ranges are normalized", () => {
  const frame = normalizeTelemetry({
    vehicle_speed: 123.4,
    engine_rpm_current: 7100,
    vehicle_gear_index: 5,
    vehicle_throttle: 1.4,
    vehicle_brake: -0.2,
    vehicle_steering: 2,
    stage_current_time: 12.5,
    stage_progress: 0.32,
    turbo_active: true,
    turbo_pressure_kpa: 168,
    hybrid_state: "regen",
    motor_power_kw: -22,
    battery_percent: 74
  }, { source: "test", sequence: 9 });

  assert.equal(frame.speedKph, 123.4);
  assert.equal(frame.rpm, 7100);
  assert.equal(frame.gear, "5");
  assert.equal(frame.throttle, 1);
  assert.equal(frame.brake, 0);
  assert.equal(frame.steering, 1);
  assert.equal(frame.stageTimeMs, 12500);
  assert.equal(frame.stageProgress, 0.32);
  assert.equal(frame.source, "test");
  assert.equal(frame.sequence, 9);
  assert.equal(frame.turboActive, true);
  assert.equal(frame.turboPressureKpa, 168);
  assert.equal(frame.hybridState, "charge");
  assert.equal(frame.motorPowerKw, -22);
  assert.equal(frame.batteryPercent, 0.74);
});

test("JSON datagrams are accepted and unknown binary is not guessed", () => {
  const json = parseDatagram(Buffer.from(JSON.stringify({ speedKph: 88, gear: 3 })));
  assert.equal(json.ok, true);
  assert.equal(json.telemetry.speedKph, 88);
  assert.equal(json.telemetry.gear, "3");

  const binary = parseDatagram(Buffer.from([1, 2, 3, 4, 5, 6, 7, 8]));
  assert.equal(binary.ok, false);
  assert.equal(binary.kind, "unrecognized-binary");
});

test("Kylotonn-compatible 96-byte WRC packet is decoded when fields are plausible", () => {
  const packet = Buffer.alloc(128);
  packet.writeFloatLE(0.42, 0);
  packet.writeFloatLE(33.333, 4);
  packet.writeFloatLE(-0.2, 8);
  packet.writeFloatLE(0.81, 12);
  packet.writeFloatLE(0.05, 16);
  packet.writeFloatLE(0, 20);
  packet.writeFloatLE(0.1, 24);
  packet.writeUInt32LE(4, 28);
  packet.writeFloatLE(6800, 32);
  packet.writeFloatLE(8000, 36);

  const parsed = parseDatagram(packet);
  assert.equal(parsed.ok, true);
  assert.equal(parsed.kind, "wrcg-kylotonn");
  assert.ok(Math.abs(parsed.telemetry.speedKph - 120) < 0.1);
  assert.equal(parsed.telemetry.gear, "4");
  assert.ok(Math.abs(parsed.telemetry.stageProgress - 0.42) < 0.001);
  assert.equal(parsed.telemetry.stageTimeMs, null);
});

test("captured WRCG 280-byte field layout is decoded", () => {
  const packet = Buffer.alloc(280);
  packet.writeFloatLE(0.43, 12);
  packet.writeFloatLE(10, 100);
  packet.writeFloatLE(10, 104);
  packet.writeFloatLE(10, 108);
  packet.writeFloatLE(10, 112);
  packet.writeFloatLE(0.82, 116);
  packet.writeFloatLE(-0.24, 120);
  packet.writeFloatLE(0.06, 124);
  packet.writeFloatLE(0.14, 128);
  packet.writeFloatLE(3, 132);
  packet.writeFloatLE(684.5, 148);
  packet.writeFloatLE(0.63, 160);
  packet.writeFloatLE(0.9, 164);
  packet.writeFloatLE(890, 252);

  const parsed = parseDatagram(packet);
  assert.equal(parsed.ok, true);
  assert.equal(parsed.kind, "wrcg-280");
  assert.ok(Math.abs(parsed.telemetry.speedKph - 36) < 0.01);
  assert.equal(parsed.telemetry.rpm, 6845);
  assert.equal(parsed.telemetry.maxRpm, 8900);
  assert.equal(parsed.telemetry.gear, "3");
  assert.ok(Math.abs(parsed.telemetry.throttle - 0.82) < 0.001);
  assert.ok(Math.abs(parsed.telemetry.brake - 0.06) < 0.001);
  assert.ok(Math.abs(parsed.telemetry.steering - 0.24) < 0.001);
  assert.ok(Math.abs(parsed.telemetry.clutch - 0.14) < 0.001);
  assert.ok(Math.abs(parsed.telemetry.stageProgress - 0.43) < 0.001);
  assert.ok(Math.abs(parsed.telemetry.batteryPercent - 0.7) < 0.001);
  assert.equal(parsed.telemetry.hybridState, "idle");
  assert.equal(parsed.telemetry.turboState, "ON");
  assert.equal(parsed.telemetry.turboEstimated, true);
  assert.ok(parsed.telemetry.turboPressureKpa > 101);
  assert.equal(parsed.telemetry.source, "wrcg-280");
});

test("demo telemetry produces a plausible moving frame", () => {
  const demo = new DemoTelemetry(1_700_000_000_000);
  const first = demo.next(1_700_000_000_000);
  const later = demo.next(1_700_000_003_000);

  assert.ok(first.speedKph > 0);
  assert.ok(first.rpm >= 0 && first.rpm <= first.maxRpm);
  assert.match(first.gear, /^[1-9RN]$/);
  assert.ok(later.stageTimeMs > first.stageTimeMs);
  assert.equal(first.turboEstimated, true);
  assert.ok(Number.isFinite(first.batteryPercent));
  assert.equal(later.source, "demo");
  assert.equal(later.sequence, 2);
});
