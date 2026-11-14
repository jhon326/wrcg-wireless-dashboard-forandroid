"use strict";

const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { spawnSync } = require("node:child_process");

const CONFIG_RELATIVE_PATH = path.join("My Games", "WRCG", "UserSettings.cfg");
const TARGET_SETTINGS = Object.freeze([
  ["WRC.Telemetry.EnableTelemetry", "true"],
  ["WRC.Telemetry.TelemetryAdress", '"127.0.0.1"'],
  ["WRC.Telemetry.TelemetryPort", "20777"],
  ["WRC.Telemetry.TelemetryRate", "30"]
]);

function uniquePaths(paths) {
  return paths.filter((value, index, list) => value && list.indexOf(value) === index);
}

function findWrcgSettingsPath(environment = process.env, home = os.homedir()) {
  const userProfile = environment.USERPROFILE || home;
  const candidates = uniquePaths([
    path.join(userProfile, "Documents", CONFIG_RELATIVE_PATH),
    environment.OneDrive && path.join(environment.OneDrive, "Documents", CONFIG_RELATIVE_PATH),
    environment.OneDriveConsumer && path.join(environment.OneDriveConsumer, "Documents", CONFIG_RELATIVE_PATH),
    environment.OneDriveCommercial && path.join(environment.OneDriveCommercial, "Documents", CONFIG_RELATIVE_PATH),
    path.join(home, "OneDrive", "Documents", CONFIG_RELATIVE_PATH),
    path.join(home, "Documents", CONFIG_RELATIVE_PATH)
  ]);
  return candidates.find((candidate) => fs.existsSync(candidate)) || candidates[0];
}

function getSettingValue(content, name) {
  const pattern = new RegExp(`^\\s*${escapeRegExp(name)}\\s*=\\s*(.*?);\\s*$`, "m");
  const match = pattern.exec(content);
  return match ? match[1].trim() : null;
}

function setSettingValue(content, name, value) {
  const pattern = new RegExp(`^(\\s*${escapeRegExp(name)}\\s*=\\s*).*?;\\s*$`, "m");
  if (pattern.test(content)) {
    return content.replace(pattern, `$1${value};`);
  }
  const newline = content.includes("\r\n") ? "\r\n" : "\n";
  return `${content.replace(/\s+$/, "")}${content.trim() ? newline : ""}${name} = ${value};${newline}`;
}

function needsTelemetryChanges(content) {
  return TARGET_SETTINGS.some(([name, value]) => getSettingValue(content, name) !== value);
}

function isWrcgRunning() {
  if (process.platform !== "win32") return false;
  const result = spawnSync("tasklist", ["/FO", "CSV", "/NH"], {
    encoding: "utf8",
    windowsHide: true
  });
  if (result.error || result.status !== 0) return false;
  return /^"WRCG(?:-Win64-Shipping)?\.exe",/im.test(result.stdout || "");
}

function configureWrcgTelemetry(options = {}) {
  const settingsPath = options.settingsPath || findWrcgSettingsPath(options.environment, options.home);
  const gameRunning = options.isGameRunning || isWrcgRunning;

  if (!fs.existsSync(settingsPath)) {
    return {
      status: "config-missing",
      path: settingsPath,
      message: "未找到 WRCG 配置文件。请先启动一次 WRCG，再重新打开本程序。"
    };
  }

  try {
    const original = fs.readFileSync(settingsPath, "utf8");
    if (!needsTelemetryChanges(original)) {
      return {
        status: "already-configured",
        path: settingsPath,
        message: "WRCG 遥测已启用，无需修改。"
      };
    }

    if (gameRunning()) {
      return {
        status: "game-running",
        path: settingsPath,
        message: "WRCG 正在运行，暂不修改遥测设置。请退出游戏后重新打开本程序。"
      };
    }

    const stamp = new Date().toISOString().replace(/[:.]/g, "-");
    const backupPath = `${settingsPath}.wrcg-dashboard-${stamp}.bak`;
    fs.copyFileSync(settingsPath, backupPath);

    let updated = original;
    for (const [name, value] of TARGET_SETTINGS) {
      updated = setSettingValue(updated, name, value);
    }
    fs.writeFileSync(settingsPath, updated, "utf8");

    return {
      status: "updated",
      path: settingsPath,
      backupPath,
      message: "已自动启用 WRCG 遥测，原配置已备份。"
    };
  } catch (error) {
    return {
      status: "error",
      path: settingsPath,
      message: `无法修改 WRCG 遥测设置：${error.message}`
    };
  }
}

function escapeRegExp(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

module.exports = {
  CONFIG_RELATIVE_PATH,
  TARGET_SETTINGS,
  configureWrcgTelemetry,
  findWrcgSettingsPath,
  getSettingValue,
  isWrcgRunning,
  needsTelemetryChanges,
  setSettingValue
};
