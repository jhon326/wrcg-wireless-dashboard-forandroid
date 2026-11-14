"use strict";

const crypto = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");
const { spawnSync } = require("node:child_process");
const esbuild = require("esbuild");

const ROOT = path.resolve(__dirname, "..");
const DIST_DIR = path.join(ROOT, "dist");
const PUBLIC_DIR = path.join(ROOT, "public");
const ENTRY_FILE = path.join(ROOT, "src", "desktop-entry.js");
const BUNDLE_FILE = path.join(DIST_DIR, ".wrcg-dash-sea-bundle.cjs");
const SEA_CONFIG_FILE = path.join(DIST_DIR, ".wrcg-dash-sea-config.json");
const SEA_BLOB_FILE = path.join(DIST_DIR, ".wrcg-dash-sea-prep.blob");
const OUTPUT_FILE = path.join(DIST_DIR, "WRCG-Dash-Desktop.exe");
const GUIDE_FILE = path.join(DIST_DIR, "使用说明.txt");
const VERSION = require("../package.json").version;
const SEA_FUSE = "NODE_SEA_FUSE_fce680ab2cc467b6e072b8b5df1996b2";

async function build() {
  validateEnvironment();
  fs.mkdirSync(DIST_DIR, { recursive: true });
  for (const generatedFile of [BUNDLE_FILE, SEA_CONFIG_FILE, SEA_BLOB_FILE, OUTPUT_FILE]) {
    if (fs.existsSync(generatedFile)) fs.rmSync(generatedFile, { force: true });
  }

  const assetFiles = listFiles(PUBLIC_DIR);
  await esbuild.build({
    entryPoints: [ENTRY_FILE],
    outfile: BUNDLE_FILE,
    bundle: true,
    platform: "node",
    format: "cjs",
    target: `node${process.versions.node.split(".")[0]}`,
    legalComments: "none",
    sourcemap: false,
    plugins: [embeddedPublicAssetsPlugin(assetFiles)]
  });

  fs.writeFileSync(SEA_CONFIG_FILE, JSON.stringify({
    main: BUNDLE_FILE,
    output: SEA_BLOB_FILE,
    disableExperimentalSEAWarning: true,
    useSnapshot: false,
    useCodeCache: false
  }, null, 2), "utf8");

  run(process.execPath, ["--experimental-sea-config", SEA_CONFIG_FILE], "生成 Node SEA 资源");
  fs.copyFileSync(process.execPath, OUTPUT_FILE);

  const postjectCli = resolvePostjectCli();
  run(process.execPath, [
    postjectCli,
    OUTPUT_FILE,
    "NODE_SEA_BLOB",
    SEA_BLOB_FILE,
    "--sentinel-fuse",
    SEA_FUSE
  ], "注入桌面程序资源");

  verifyExecutable();
  const sha256 = hashFile(OUTPUT_FILE);
  const size = fs.statSync(OUTPUT_FILE).size;
  fs.writeFileSync(GUIDE_FILE, buildGuide(sha256, size), "utf8");
  fs.writeFileSync(path.join(DIST_DIR, "desktop-build.json"), JSON.stringify({
    name: path.basename(OUTPUT_FILE),
    version: VERSION,
    builtAt: new Date().toISOString(),
    nodeVersion: process.version,
    size,
    sha256
  }, null, 2), "utf8");

  for (const temporaryFile of [BUNDLE_FILE, SEA_CONFIG_FILE, SEA_BLOB_FILE]) {
    if (fs.existsSync(temporaryFile)) fs.rmSync(temporaryFile, { force: true });
  }

  console.log("");
  console.log(`桌面程序：${OUTPUT_FILE}`);
  console.log(`文件大小：${(size / 1024 / 1024).toFixed(1)} MB`);
  console.log(`SHA-256：${sha256}`);
  console.log("冒烟测试：通过（已隐藏 PATH，确认不依赖目标电脑的 Node.js）");
}

function embeddedPublicAssetsPlugin(assetFiles) {
  return {
    name: "embedded-wrcg-public-assets",
    setup(buildApi) {
      buildApi.onResolve({ filter: /^wrcg-public-assets$/ }, () => ({
        path: "public-assets",
        namespace: "wrcg-assets"
      }));
      buildApi.onLoad({ filter: /.*/, namespace: "wrcg-assets" }, () => {
        const entries = assetFiles.map((filePath) => {
          const relativePath = path.relative(PUBLIC_DIR, filePath).split(path.sep).join("/");
          const base64 = fs.readFileSync(filePath).toString("base64");
          return `  ${JSON.stringify(relativePath)}: Buffer.from(${JSON.stringify(base64)}, "base64")`;
        });
        return {
          contents: `"use strict";\nmodule.exports = {\n${entries.join(",\n")}\n};\n`,
          loader: "js",
          resolveDir: ROOT
        };
      });
    }
  };
}

function listFiles(directory) {
  const files = [];
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    const fullPath = path.join(directory, entry.name);
    if (entry.isDirectory()) files.push(...listFiles(fullPath));
    else if (entry.isFile()) files.push(fullPath);
  }
  return files.sort();
}

function validateEnvironment() {
  if (process.platform !== "win32") {
    throw new Error("Windows 桌面程序必须在 Windows 上构建。");
  }
  const major = Number(process.versions.node.split(".")[0]);
  if (!Number.isInteger(major) || major < 20) {
    throw new Error(`当前 Node.js ${process.version} 不支持 SEA 构建，请使用 Node.js 20 或更高版本。`);
  }
}

function resolvePostjectCli() {
  const packagePath = require.resolve("postject/package.json");
  const packageJson = JSON.parse(fs.readFileSync(packagePath, "utf8"));
  const bin = typeof packageJson.bin === "string"
    ? packageJson.bin
    : packageJson.bin && packageJson.bin.postject;
  if (!bin) throw new Error("无法定位 postject 命令行入口。");
  return path.resolve(path.dirname(packagePath), bin);
}

function run(command, args, label) {
  console.log(`[build] ${label}...`);
  const result = spawnSync(command, args, {
    cwd: ROOT,
    encoding: "utf8",
    windowsHide: true
  });
  if (result.stdout) process.stdout.write(result.stdout);
  if (result.stderr) process.stderr.write(result.stderr);
  if (result.error) throw result.error;
  if (result.status !== 0) {
    throw new Error(`${label}失败，退出码 ${result.status}`);
  }
}

function verifyExecutable() {
  console.log("[build] 验证单文件程序...");
  const environment = { ...process.env };
  delete environment.PATH;
  delete environment.Path;
  environment.Path = `${process.env.SystemRoot || "C:\\Windows"}\\System32`;
  const result = spawnSync(OUTPUT_FILE, [
    "--smoke-test",
    "--no-configure",
    "--no-open",
    "--no-capture",
    "--port",
    "0",
    "--udp-port",
    "0"
  ], {
    cwd: path.dirname(OUTPUT_FILE),
    env: environment,
    encoding: "utf8",
    timeout: 30000,
    windowsHide: true
  });
  if (result.stdout) process.stdout.write(result.stdout);
  if (result.stderr) process.stderr.write(result.stderr);
  if (result.error) throw result.error;
  if (result.status !== 0 || !/(^|\r?\n)SMOKE_TEST_OK .*embeddedAssets=true/m.test(result.stdout || "")) {
    throw new Error(`桌面程序冒烟测试失败，退出码 ${result.status}`);
  }
}

function hashFile(filePath) {
  return crypto.createHash("sha256").update(fs.readFileSync(filePath)).digest("hex").toUpperCase();
}

function buildGuide(sha256, size) {
  return [
    `WRCG 无线仪表盘电脑端 ${VERSION}`,
    "",
    "适用系统：Windows 10/11 64 位",
    "",
    "使用方法",
    "1. 先启动一次 WRCG，让它生成游戏设置文件。",
    "2. 完全退出 WRCG。",
    "3. 双击 WRCG-Dash-Desktop.exe。",
    "4. 程序会自动启用 WRCG 遥测，并打开电脑端仪表窗口。",
    "5. 手机与电脑连接同一个 Wi-Fi 或手机热点。",
    "6. 在手机 App 的“设置 → 连接设置”中输入电脑窗口里标记为“手机仪表”的地址。",
    "7. 重新启动 WRCG 并进入赛段。",
    "",
    "注意",
    "- 不要关闭电脑端的黑色命令行窗口；关闭它会停止连接服务。",
    "- 第一次运行如出现 Windows 防火墙提示，请允许“专用网络”访问。",
    "- 此程序尚未购买商业代码签名证书，Windows 可能显示“未知发布者”。",
    "- 如果“手机仪表”地址仍无法访问，请关闭手机 VPN，并检查路由器是否开启了访客/AP 隔离。",
    "- 原始遥测包保存在 %LOCALAPPDATA%\\WRCG Dash\\captures。",
    "",
    `版本：${VERSION}`,
    `大小：${(size / 1024 / 1024).toFixed(1)} MB`,
    `SHA-256：${sha256}`,
    ""
  ].join("\r\n");
}

build().catch((error) => {
  console.error(`[build] ${error.message}`);
  process.exitCode = 1;
});
