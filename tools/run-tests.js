"use strict";

const { spawnSync } = require("node:child_process");

const major = Number(process.versions.node.split(".")[0]);
const args = [];
if (major < 22 && !process.execArgv.includes("--experimental-websocket")) {
  args.push("--experimental-websocket");
}
args.push("--test", ...process.argv.slice(2));

const result = spawnSync(process.execPath, args, {
  stdio: "inherit",
  env: process.env
});
if (result.error) {
  console.error(result.error.message);
  process.exit(1);
}
process.exit(result.status ?? 1);
