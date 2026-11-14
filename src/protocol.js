"use strict";

const DEFAULT_TELEMETRY = Object.freeze({
  speedKph: 0,
  rpm: 0,
  maxRpm: 8000,
  gear: "N",
  throttle: 0,
  brake: 0,
  clutch: 0,
  steering: 0,
  handbrake: 0,
  stageTimeMs: null,
  stageProgress: 0,
  stageDistanceKm: null,
  stageLengthKm: null,
  stageName: "等待赛段",
  position: null,
  deltaMs: null,
  turboActive: null,
  turboState: null,
  turboPressureKpa: null,
  turboEstimated: true,
  hybridState: null,
  motorPowerKw: null,
  batteryPercent: null,
  status: "idle"
});

function finiteNumber(value, fallback = 0) {
  const number = Number(value);
  return Number.isFinite(number) ? number : fallback;
}

function clamp(value, min = 0, max = 1) {
  return Math.min(max, Math.max(min, finiteNumber(value)));
}

function pick(input, keys, fallback) {
  for (const key of keys) {
    if (input[key] !== undefined && input[key] !== null) {
      return input[key];
    }
  }
  return fallback;
}

function normalizeGear(value) {
  if (typeof value === "string") {
    const gear = value.trim().toUpperCase();
    if (gear === "R" || gear === "N") return gear;
    if (/^[1-9]$/.test(gear)) return gear;
  }

  const numericGear = Number(value);
  if (numericGear === -1) return "R";
  if (numericGear === 0) return "N";
  if (Number.isInteger(numericGear) && numericGear > 0 && numericGear < 10) {
    return String(numericGear);
  }
  return "N";
}

function nullableBoolean(value) {
  if (value === true || value === 1 || value === "1" || value === "true" || value === "on") return true;
  if (value === false || value === 0 || value === "0" || value === "false" || value === "off") return false;
  return null;
}

function normalizeHybridState(value) {
  if (value === null || value === undefined) return null;
  const state = String(value).trim().toLowerCase();
  if (["discharge", "boost", "deploy", "assist"].includes(state)) return "discharge";
  if (["charge", "regen", "regenerating"].includes(state)) return "charge";
  if (["idle", "ready", "standby"].includes(state)) return "idle";
  return null;
}

function normalizePercent(value) {
  const number = nullableNumber(value);
  if (number === null) return null;
  return clamp(number > 1 ? number / 100 : number);
}

function normalizeTelemetry(input = {}, options = {}) {
  const throttle = clamp(pick(input, ["throttle", "vehicle_throttle"], 0));
  const brake = clamp(pick(input, ["brake", "vehicle_brake"], 0));
  const rpm = Math.max(0, finiteNumber(pick(input, ["rpm", "engineRpm", "engine_rpm_current"], 0)));
  const maxRpm = Math.max(1000, finiteNumber(pick(input, ["maxRpm", "vehicle_engine_rpm_max"], 8000)));
  const rpmRatio = clamp(rpm / maxRpm);
  const estimatedTurboLevel = clamp(
    Math.max(0, (rpmRatio - 0.12) / 0.68) * 0.58 + throttle * 0.42
  );
  const turboState = nullableString(pick(input, ["turboState", "turbo_state"], null))
    || (estimatedTurboLevel > 0.8 ? "ON" : estimatedTurboLevel > 0.28 ? "SPOOL" : "OFF");
  const turboActive = nullableBoolean(pick(input, ["turboActive", "turbo_active"], null))
    ?? estimatedTurboLevel > 0.28;
  const turboPressureKpa = nullableNumber(pick(
    input,
    ["turboPressureKpa", "turbo_pressure_kpa", "boostPressureKpa"],
    null
  )) ?? 101 + estimatedTurboLevel * 142;
  const batteryPercent = normalizePercent(pick(
    input,
    ["batteryPercent", "battery_percent", "battery", "motorBattery"],
    null
  ));
  const stageTimeProvided = ["stageTimeMs", "stage_time_ms", "stageCurrentTime", "stage_current_time"]
    .some((key) => input[key] !== undefined && input[key] !== null);
  const stageTimeRaw = pick(input, ["stageTimeMs", "stage_time_ms", "stageCurrentTime", "stage_current_time"], null);
  const stageTimeMs = stageTimeProvided
    ? stageTimeRaw > 0 && stageTimeRaw < 10000
      ? stageTimeRaw * 1000
      : stageTimeRaw
    : null;
  const deltaProvided = ["deltaMs", "delta_ms", "delta"].some((key) => input[key] !== undefined && input[key] !== null);
  const deltaRaw = pick(input, ["deltaMs", "delta_ms", "delta"], null);
  const deltaMs = deltaProvided
    ? Math.abs(deltaRaw) > 0 && Math.abs(deltaRaw) < 60
      ? deltaRaw * 1000
      : deltaRaw
    : null;

  return {
    ...DEFAULT_TELEMETRY,
    speedKph: Math.max(0, finiteNumber(pick(input, ["speedKph", "speed_kph", "vehicleSpeed", "vehicle_speed"], 0))),
    rpm,
    maxRpm,
    gear: normalizeGear(pick(input, ["gear", "vehicle_gear_index"], "N")),
    throttle,
    brake,
    clutch: clamp(pick(input, ["clutch", "vehicle_clutch"], 0)),
    steering: clamp(pick(input, ["steering", "vehicle_steering"], 0), -1, 1),
    handbrake: clamp(pick(input, ["handbrake", "vehicle_handbrake"], 0)),
    stageTimeMs: stageTimeProvided ? Math.max(0, finiteNumber(stageTimeMs)) : null,
    stageProgress: clamp(pick(input, ["stageProgress", "stage_progress"], 0)),
    stageDistanceKm: nullableNumber(pick(input, ["stageDistanceKm", "stage_current_distance"], null)),
    stageLengthKm: nullableNumber(pick(input, ["stageLengthKm", "stage_length"], null)),
    stageName: String(pick(input, ["stageName", "stage_name"], "WRCG 实时赛段")).slice(0, 80),
    position: nullableNumber(pick(input, ["position", "rank"], null)),
    deltaMs: deltaProvided ? finiteNumber(deltaMs) : null,
    turboActive,
    turboState,
    turboPressureKpa,
    turboEstimated: input.turboEstimated !== false,
    hybridState: normalizeHybridState(pick(input, ["hybridState", "hybrid_state"], null)),
    motorPowerKw: nullableNumber(pick(input, ["motorPowerKw", "motor_power_kw", "electricPowerKw"], null)),
    batteryPercent,
    status: String(pick(input, ["status"], "racing")).slice(0, 24),
    source: options.source || String(input.source || "unknown"),
    receivedAt: options.receivedAt || Date.now(),
    sequence: finiteNumber(options.sequence, 0)
  };
}

function nullableNumber(value) {
  if (value === null || value === undefined || value === "") return null;
  const number = Number(value);
  return Number.isFinite(number) ? number : null;
}

function nullableString(value) {
  if (value === null || value === undefined || value === "") return null;
  return String(value).slice(0, 24);
}

function tryParseJson(buffer) {
  const text = buffer.toString("utf8").replace(/^\uFEFF/, "").trim();
  if (!text.startsWith("{") && !text.startsWith("[")) return null;
  try {
    const parsed = JSON.parse(text);
    if (Array.isArray(parsed)) return parsed[0] || null;
    if (parsed && typeof parsed === "object" && parsed.data && typeof parsed.data === "object") {
      return parsed.data;
    }
    return parsed;
  } catch {
    return null;
  }
}

function parseWrcgBinary(buffer) {
  const wrcg = parseWrcg280(buffer);
  if (wrcg) return wrcg;

  return parseKylotonn96(buffer);
}

function parseWrcg280(buffer) {
  if (!Buffer.isBuffer(buffer) || buffer.length < 264 || buffer.length > 2048) return null;
  const view = new DataView(buffer.buffer, buffer.byteOffset, buffer.byteLength);
  const f32 = (offset) => view.getFloat32(offset, true);
  const wheelSpeeds = [f32(100), f32(104), f32(108), f32(112)].map(Math.abs);
  const throttle = f32(116);
  const steering = f32(120);
  const brake = f32(124);
  const clutch = f32(128);
  const gearRaw = f32(132);
  const rpmRaw = f32(148);
  const maxRpmRaw = f32(252);
  const kersLevel = f32(160);
  const kersMaxLevel = f32(164);
  const stageProgress = f32(12);

  const fields = [...wheelSpeeds, throttle, steering, brake, clutch, gearRaw, rpmRaw, maxRpmRaw, kersLevel, kersMaxLevel];
  if (!fields.every(Number.isFinite)) return null;
  if (Math.max(...wheelSpeeds) > 160) return null;
  if (throttle < -0.1 || throttle > 1.1 || brake < -0.1 || brake > 1.1) return null;
  if (clutch < -0.1 || clutch > 1.1) return null;
  if (steering < -1.1 || steering > 1.1) return null;
  if (gearRaw < -1.5 || gearRaw > 12.5) return null;
  if (rpmRaw < 0 || rpmRaw > 1500 || maxRpmRaw < 50 || maxRpmRaw > 1500) return null;

  const gearNumber = Math.round(gearRaw);
  const hasHybridData = kersMaxLevel > 0 && kersLevel >= 0;
  return {
    speedKph: (wheelSpeeds.reduce((sum, value) => sum + value, 0) / wheelSpeeds.length) * 3.6,
    rpm: rpmRaw * 10,
    maxRpm: maxRpmRaw * 10,
    gear: gearNumber < 0 ? "R" : gearNumber === 0 ? "N" : String(gearNumber),
    throttle,
    brake,
    clutch,
    // WRCG reports positive steering for a left input. The dashboard protocol
    // uses the conventional -1 = left, +1 = right representation.
    steering: -steering,
    stageProgress: Number.isFinite(stageProgress) ? Math.max(0, Math.min(1, stageProgress)) : 0,
    batteryPercent: hasHybridData ? Math.max(0, Math.min(1, kersLevel / kersMaxLevel)) : null,
    hybridState: hasHybridData ? "idle" : null,
    stageName: "WRCG 实时赛段",
    source: "wrcg-280"
  };
}

function parseKylotonn96(buffer) {
  if (!Buffer.isBuffer(buffer) || buffer.length < 96 || buffer.length > 2048) return null;
  const view = new DataView(buffer.buffer, buffer.byteOffset, buffer.byteLength);
  const f32 = (offset) => view.getFloat32(offset, true);
  const u32 = (offset) => view.getUint32(offset, true);
  const fields = {
    stageProgress: f32(0),
    speedMs: f32(4),
    steering: f32(8),
    throttle: f32(12),
    brake: f32(16),
    handbrake: f32(20),
    clutch: f32(24),
    gearRaw: u32(28),
    rpm: f32(32),
    maxRpm: f32(36)
  };

  const numericFields = Object.entries(fields)
    .filter(([key]) => key !== "gearRaw")
    .map(([, value]) => value);
  if (!numericFields.every(Number.isFinite)) return null;
  if (fields.stageProgress < -0.1 || fields.stageProgress > 1.1) return null;
  if (Math.abs(fields.speedMs) > 160 || fields.rpm < 0 || fields.rpm > 30000) return null;
  if (fields.maxRpm < 0 || fields.maxRpm > 30000 || fields.gearRaw > 12) return null;
  if ([fields.throttle, fields.brake, fields.handbrake, fields.clutch]
    .some((value) => value < -0.1 || value > 1.1)
    || fields.steering < -1.1
    || fields.steering > 1.1) {
    return null;
  }

  return {
    speedKph: Math.abs(fields.speedMs) * 3.6,
    rpm: fields.rpm,
    maxRpm: fields.maxRpm >= 1000 ? fields.maxRpm : 8000,
    gear: fields.gearRaw === 0 ? "R" : String(Math.min(8, fields.gearRaw)),
    stageProgress: Math.max(0, Math.min(1, fields.stageProgress)),
    throttle: fields.throttle,
    brake: fields.brake,
    clutch: fields.clutch,
    steering: fields.steering,
    handbrake: fields.handbrake,
    stageName: "WRCG 实时赛段",
    source: "wrcg-kylotonn"
  };
}

function parseDatagram(buffer) {
  const json = tryParseJson(buffer);
  if (json) {
    const telemetry = normalizeTelemetry(json, { source: json.source || "json-udp" });
    return { ok: true, kind: "json", telemetry };
  }

  const binary = parseWrcgBinary(buffer);
  if (binary) {
    return { ok: true, kind: binary.source, telemetry: normalizeTelemetry(binary, { source: binary.source }) };
  }

  return {
    ok: false,
    kind: "unrecognized-binary",
    reason: "WRCG packet layout not mapped yet",
    size: buffer.length
  };
}

module.exports = {
  DEFAULT_TELEMETRY,
  clamp,
  normalizeGear,
  normalizeTelemetry,
  parseDatagram,
  parseKylotonn96,
  parseWrcg280,
  parseWrcgBinary
};
