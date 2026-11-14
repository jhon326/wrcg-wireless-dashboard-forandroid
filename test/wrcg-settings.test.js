"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const {
  configureWrcgTelemetry,
  getSettingValue,
  needsTelemetryChanges,
  setSettingValue
} = require("../src/wrcg-settings");

test("WRCG telemetry settings are updated once and the original file is backed up", () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "wrcg-settings-"));
  const settingsPath = path.join(directory, "UserSettings.cfg");
  const original = [
    "WRC.Telemetry.EnableTelemetry = false;",
    'WRC.Telemetry.TelemetryAdress = "192.168.1.2";',
    "WRC.Telemetry.TelemetryPort = 1234;",
    "WRC.Telemetry.TelemetryRate = 60;",
    "WRC.Video.Fullscreen = true;"
  ].join("\r\n");
  fs.writeFileSync(settingsPath, original, "utf8");

  const result = configureWrcgTelemetry({
    settingsPath,
    isGameRunning: () => false
  });
  assert.equal(result.status, "updated");
  assert.ok(fs.existsSync(result.backupPath));
  assert.equal(fs.readFileSync(result.backupPath, "utf8"), original);

  const updated = fs.readFileSync(settingsPath, "utf8");
  assert.equal(getSettingValue(updated, "WRC.Telemetry.EnableTelemetry"), "true");
  assert.equal(getSettingValue(updated, "WRC.Telemetry.TelemetryAdress"), '"127.0.0.1"');
  assert.equal(getSettingValue(updated, "WRC.Telemetry.TelemetryPort"), "20777");
  assert.equal(getSettingValue(updated, "WRC.Telemetry.TelemetryRate"), "30");
  assert.match(updated, /WRC\.Video\.Fullscreen = true;/);
  assert.equal(needsTelemetryChanges(updated), false);

  const second = configureWrcgTelemetry({
    settingsPath,
    isGameRunning: () => false
  });
  assert.equal(second.status, "already-configured");
});

test("WRCG settings are not changed while the game is running", () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "wrcg-settings-running-"));
  const settingsPath = path.join(directory, "UserSettings.cfg");
  fs.writeFileSync(settingsPath, "WRC.Telemetry.EnableTelemetry = false;\r\n", "utf8");

  const result = configureWrcgTelemetry({
    settingsPath,
    isGameRunning: () => true
  });
  assert.equal(result.status, "game-running");
  assert.equal(
    fs.readFileSync(settingsPath, "utf8"),
    "WRC.Telemetry.EnableTelemetry = false;\r\n"
  );
});

test("missing WRCG setting lines are appended without losing existing settings", () => {
  const content = "Graphics = high;\r\n";
  const updated = setSettingValue(content, "WRC.Telemetry.TelemetryRate", "30");
  assert.match(updated, /^Graphics = high;\r\nWRC\.Telemetry\.TelemetryRate = 30;\r\n$/);
});
