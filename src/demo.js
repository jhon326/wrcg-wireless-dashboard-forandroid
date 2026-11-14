"use strict";

const { normalizeTelemetry } = require("./protocol");

function lerp(start, end, amount) {
  return start + (end - start) * amount;
}

class DemoTelemetry {
  constructor(startedAt = Date.now()) {
    this.startedAt = startedAt;
    this.sequence = 0;
    this.lastNow = startedAt;
    this.batteryPercent = 0.72;
    this.gearRanges = [
      [18, 52],
      [36, 79],
      [61, 112],
      [89, 143],
      [116, 174],
      [142, 204]
    ];
  }

  next(now = Date.now()) {
    const elapsed = Math.max(0, (now - this.startedAt) / 1000);
    const deltaSeconds = Math.max(0, Math.min(0.1, (now - this.lastNow) / 1000));
    this.lastNow = now;
    const stage = elapsed % 290;
    const driveCycle = elapsed % 20;
    const gearPullDuration = 2.6;
    const driveDuration = gearPullDuration * this.gearRanges.length;
    let gearIndex;
    let speedKph;
    let throttle;
    let brake;
    let rpm;

    if (driveCycle < driveDuration) {
      gearIndex = Math.min(this.gearRanges.length - 1, Math.floor(driveCycle / gearPullDuration));
      const phase = (driveCycle - gearIndex * gearPullDuration) / gearPullDuration;
      const [minSpeed, maxSpeed] = this.gearRanges[gearIndex];
      speedKph = lerp(minSpeed, maxSpeed, Math.pow(phase, 0.82)) + Math.sin(elapsed * 7.4) * 0.55;
      throttle = phase < 0.045 ? 0.22 : Math.min(1, 0.83 + 0.16 * Math.sin(elapsed * 1.4));
      brake = phase < 0.045 ? 0.18 : 0;

      if (phase < 0.045) {
        rpm = 2250 + (phase / 0.045) * 850;
      } else if (phase > 0.79) {
        rpm = 6950 + ((phase - 0.79) / 0.21) * 830;
      } else {
        rpm = 2700 + ((phase - 0.045) / 0.745) * 4400;
      }
      rpm += Math.sin(elapsed * 13) * 48;
    } else {
      const brakePhase = Math.min(1, (driveCycle - driveDuration) / (20 - driveDuration));
      const eased = 1 - Math.pow(1 - brakePhase, 2.1);
      speedKph = lerp(204, 43, eased);
      gearIndex = Math.max(1, 5 - Math.floor(brakePhase * 4));
      throttle = 0;
      brake = Math.min(1, 0.18 + brakePhase * 1.12);
      rpm = lerp(6550, 1850, Math.pow(brakePhase, 0.72)) + Math.sin(elapsed * 4.2) * 90;
    }

    speedKph = Math.max(0, speedKph);
    rpm = Math.max(0, Math.min(7800, rpm));
    const hybridState = brake > 0.18
      ? "charge"
      : throttle > 0.6 && rpm > 4200
        ? "discharge"
        : "idle";
    if (hybridState === "charge") this.batteryPercent += deltaSeconds * 0.045;
    else if (hybridState === "discharge") this.batteryPercent -= deltaSeconds * 0.055;
    this.batteryPercent = Math.max(0.18, Math.min(0.98, this.batteryPercent));

    const data = {
      speedKph: Math.max(0, speedKph),
      rpm,
      maxRpm: 7800,
      gear: String(gearIndex + 1),
      throttle,
      brake,
      clutch: 0,
      steering: 0.45 * Math.sin(elapsed * 0.82) + 0.13 * Math.sin(elapsed * 2.9),
      handbrake: brake > 0.82 && Math.sin(elapsed * 0.15) > 0.9 ? 1 : 0,
      stageTimeMs: stage * 1000,
      stageProgress: stage / 290,
      stageDistanceKm: (stage / 290) * 21.74,
      stageLengthKm: 21.74,
      stageName: "DEMO · 森林赛段",
      position: 4,
      deltaMs: 1250 * Math.sin(elapsed * 0.31) + 370 * Math.sin(elapsed * 1.31),
      turboActive: rpm > 3600 && throttle > 0.25,
      turboState: rpm > 5000 && throttle > 0.55 ? "ON" : rpm > 3300 ? "SPOOL" : "OFF",
      turboPressureKpa: 102 + Math.max(0, throttle - 0.15) * 140 * Math.min(1, rpm / 7000),
      hybridState,
      motorPowerKw: hybridState === "discharge" ? 25 + throttle * 35 : hybridState === "charge" ? -18 : 0,
      batteryPercent: this.batteryPercent,
      turboEstimated: true,
      status: "racing"
    };

    this.sequence += 1;
    return normalizeTelemetry(data, {
      source: "demo",
      receivedAt: now,
      sequence: this.sequence
    });
  }
}

module.exports = { DemoTelemetry };
