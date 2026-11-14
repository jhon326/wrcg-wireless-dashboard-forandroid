"use strict";

const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { spawn } = require("node:child_process");
const { createDashboardServer, getLanEndpoints, parseArgs } = require("./server");
const { configureWrcgTelemetry } = require("./wrcg-settings");

let embeddedPublicAssets = null;
try {
  embeddedPublicAssets = require("wrcg-public-assets");
} catch {
  // Source checkouts use the normal public/ directory.
}

function main() {
  const rawArgs = process.argv.slice(2);
  if (rawArgs.includes("--help") || rawArgs.includes("-h")) {
    printHelp();
    return;
  }

  const options = parseArgs(rawArgs);
  options.open = !rawArgs.includes("--no-open");
  options.publicAssets = embeddedPublicAssets;
  options.captureDirectory = resolveCaptureDirectory();
  options.capture = !rawArgs.includes("--no-capture");

  if (!rawArgs.includes("--no-configure")) {
    const result = configureWrcgTelemetry();
    console.log(`[WRCG] ${result.message}`);
    if (result.status === "updated") {
      console.log(`[WRCG] 配置备份：${result.backupPath}`);
    } else if (result.status !== "already-configured") {
      console.log(`[WRCG] 配置文件：${result.path}`);
    }
  }

  const app = createDashboardServer(options);
  app.server.on("error", (error) => {
    if (error.code === "EADDRINUSE") {
      console.error(`\n无法启动：端口 ${options.port} 已被其他程序占用。`);
    } else {
      console.error(`\nWeb 服务启动失败：${error.message}`);
    }
    console.error("请关闭重复运行的仪表盘程序，或使用 --port 参数指定其他端口。");
    process.exitCode = 1;
  });

  app.server.listen(options.port, options.host, async () => {
    if (rawArgs.includes("--smoke-test")) {
      await runSmokeTest(app);
      return;
    }

    if (options.open) {
      launchDashboard(options.port);
    }

    console.log("窗口不要关闭；关闭本窗口会停止电脑端服务。");
    console.log("手机 App：设置 → 连接设置 → 输入上面标记为“手机仪表”的地址。");
    console.log("首次连接如果弹出 Windows 防火墙提示，请勾选“专用网络”并允许访问。\n");
  });

  let shuttingDown = false;
  const shutdown = async (exitCode = 0) => {
    if (shuttingDown) return;
    shuttingDown = true;
    console.log("\n正在停止服务...");
    await app.close();
    process.exit(exitCode);
  };
  process.on("SIGINT", () => void shutdown(0));
  process.on("SIGTERM", () => void shutdown(0));
  process.on("SIGBREAK", () => void shutdown(0));
}

function resolveCaptureDirectory() {
  const base = process.env.LOCALAPPDATA || path.join(os.homedir(), "AppData", "Local");
  return path.join(base, "WRCG Dash", "captures");
}

function launchDashboard(port) {
  const url = `http://127.0.0.1:${port}`;
  const browser = findBrowserExecutable();
  try {
    if (browser) {
      const child = spawn(browser, [`--app=${url}`, "--start-maximized"], {
        detached: true,
        stdio: "ignore",
        windowsHide: true
      });
      child.unref();
      return;
    }
    const child = spawn("rundll32.exe", ["url.dll,FileProtocolHandler", url], {
      detached: true,
      stdio: "ignore",
      windowsHide: true
    });
    child.unref();
  } catch (error) {
    console.warn(`[dashboard] 无法自动打开浏览器：${error.message}`);
    console.warn(`[dashboard] 请手动打开 ${url}`);
  }
}

function findBrowserExecutable() {
  if (process.platform !== "win32") return null;
  const roots = [
    process.env["ProgramFiles(x86)"],
    process.env.ProgramFiles,
    process.env.LOCALAPPDATA
  ].filter(Boolean);
  const relativePaths = [
    path.join("Microsoft", "Edge", "Application", "msedge.exe"),
    path.join("Google", "Chrome", "Application", "chrome.exe")
  ];
  for (const root of roots) {
    for (const relativePath of relativePaths) {
      const candidate = path.join(root, relativePath);
      if (fs.existsSync(candidate)) return candidate;
    }
  }
  return null;
}

async function runSmokeTest(app) {
  const port = app.server.address().port;
  try {
    const healthResponse = await fetch(`http://127.0.0.1:${port}/healthz`);
    const health = await healthResponse.json();
    const pageResponse = await fetch(`http://127.0.0.1:${port}/`);
    const page = await pageResponse.text();
    if (!health.ok || pageResponse.status !== 200 || !page.includes("WRCG")) {
      throw new Error(`内嵌资源检查失败：health=${healthResponse.status}, page=${pageResponse.status}`);
    }
    const endpoints = getLanEndpoints();
    console.log(`SMOKE_TEST_OK port=${port} embeddedAssets=${Boolean(embeddedPublicAssets)} lanAddresses=${endpoints.length}`);
  } catch (error) {
    console.error(`SMOKE_TEST_FAILED ${error.message}`);
    process.exitCode = 1;
  } finally {
    await app.close();
  }
}

function printHelp() {
  console.log([
    "WRCG 无线仪表盘电脑端",
    "",
    "直接双击即可启动。可选参数：",
    "  --port <端口>           Web 服务端口，默认 8787",
    "  --udp-port <端口>       WRCG UDP 端口，默认 20777",
    "  --no-open               启动后不自动打开浏览器",
    "  --no-configure          不自动修改 WRCG 遥测设置",
    "  --no-capture            不录制原始遥测包",
    "  --smoke-test            检查内嵌页面和服务后退出"
  ].join("\n"));
}

main();
