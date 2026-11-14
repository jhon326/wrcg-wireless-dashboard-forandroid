"use strict";

const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { spawn } = require("node:child_process");

const edgePath = process.env.EDGE_PATH || "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe";
const url = process.argv[2] || "http://127.0.0.1:8787";
const width = Number(process.argv[3] || 2800);
const height = Number(process.argv[4] || 1272);
const screenshotPath = process.argv[5] || "";
const waitMs = Number(process.argv[6] || 3000);
const deviceScaleFactor = Number(process.argv[7] || 1);
const mobile = process.argv[8] === "mobile";
const debugPort = 9300 + Math.floor(Math.random() * 400);
const profileDir = fs.mkdtempSync(path.join(os.tmpdir(), "wrcg-dashboard-edge-"));

const browser = spawn(edgePath, [
  "--headless=new",
  "--disable-gpu",
  "--hide-scrollbars",
  `--remote-debugging-port=${debugPort}`,
  `--user-data-dir=${profileDir}`,
  `--window-size=${width},${height}`,
  url
], { stdio: "ignore", windowsHide: true });

check().catch((error) => {
  console.error(error);
  browser.kill();
  process.exitCode = 1;
});

async function check() {
  const page = await waitForPage();
  const socket = new WebSocket(page.webSocketDebuggerUrl);
  await once(socket, "open");
  let sequence = 0;
  const pending = new Map();
  socket.addEventListener("message", (event) => {
    const message = JSON.parse(event.data);
    if (!message.id || !pending.has(message.id)) return;
    const { resolve, reject } = pending.get(message.id);
    pending.delete(message.id);
    if (message.error) reject(new Error(message.error.message));
    else resolve(message.result);
  });

  const send = (method, params = {}) => new Promise((resolve, reject) => {
    const id = ++sequence;
    pending.set(id, { resolve, reject });
    socket.send(JSON.stringify({ id, method, params }));
  });

  await send("Runtime.enable");
  await send("Page.enable");
  await send("Emulation.setDeviceMetricsOverride", {
    width,
    height,
    deviceScaleFactor,
    mobile
  });
  await send("Page.reload", { ignoreCache: true });
  await new Promise((resolve) => setTimeout(resolve, waitMs));

  const result = await send("Runtime.evaluate", {
    returnByValue: true,
    expression: `(() => {
      const rect = (selector) => {
        const element = document.querySelector(selector);
        if (!element) return null;
        const box = element.getBoundingClientRect();
        return { x: box.x, y: box.y, width: box.width, height: box.height };
      };
      return {
        viewport: { width: innerWidth, height: innerHeight, dpr: devicePixelRatio },
        scroll: {
          width: document.documentElement.scrollWidth,
          height: document.documentElement.scrollHeight
        },
        gear: document.querySelector("#gearValue")?.textContent,
        speed: document.querySelector("#speedValue")?.textContent,
        rpm: document.querySelector("#rpmValue")?.textContent,
        maxRpm: document.querySelector("#maxRpmValue")?.textContent,
        diagLatency: document.querySelector("#diagLatency")?.textContent,
        diagFrameAge: document.querySelector("#diagFrameAge")?.textContent,
        diagRender: document.querySelector("#diagRender")?.textContent,
        diagQueue: document.querySelector("#diagQueue")?.textContent,
        redline: document.querySelector("#redlineFlash")?.classList.contains("is-active"),
        redlineOpacity: getComputedStyle(document.querySelector("#redlineFlash")).opacity,
        motorBoost: document.querySelector("#motorBoostFlash")?.classList.contains("is-active"),
        motorCharge: document.querySelector("#motorChargeEdge")?.classList.contains("is-active"),
        brand: rect(".brand-mini"),
        actions: rect(".hud-actions"),
        rpmConsole: rect(".rpm-screen"),
        shiftLights: rect(".rpm-band"),
        litBands: document.querySelectorAll(".rpm-band.is-lit").length,
        turbo: rect(".turbo-panel"),
        gearPanel: rect(".gear-stage"),
        gearText: rect(".gear-value"),
        motor: rect(".motor-panel"),
        speedBox: rect(".speed-inline"),
        pedals: rect(".pedal-deck"),
        turboState: document.querySelector("#turboState")?.textContent,
        motorState: document.querySelector("#motorState")?.textContent
      };
    })()`
  });
  console.log(JSON.stringify(result.result.value, null, 2));

  if (screenshotPath) {
    if (result.result.value.redline) {
      await send("Runtime.evaluate", {
        expression: `(() => {
          const flash = document.querySelector("#redlineFlash");
          flash.style.animation = "none";
          flash.style.opacity = "0.8";
        })()`
      });
    }
    if (result.result.value.motorBoost) {
      await send("Runtime.evaluate", {
        expression: `(() => {
          const flash = document.querySelector("#motorBoostFlash");
          flash.style.animation = "none";
          flash.style.opacity = "0.8";
        })()`
      });
    }
    const screenshot = await send("Page.captureScreenshot", {
      format: "png",
      captureBeyondViewport: false
    });
    fs.writeFileSync(path.resolve(screenshotPath), Buffer.from(screenshot.data, "base64"));
  }

  await send("Browser.close");
  socket.close();
  browser.kill();
}

async function waitForPage() {
  const deadline = Date.now() + 10000;
  while (Date.now() < deadline) {
    try {
      const pages = await fetch(`http://127.0.0.1:${debugPort}/json/list`).then((response) => response.json());
      const page = pages.find((entry) => entry.type === "page" && entry.url.startsWith("http"));
      if (page) return page;
    } catch {
      // Browser debugging endpoint is still starting.
    }
    await new Promise((resolve) => setTimeout(resolve, 200));
  }
  throw new Error("Timed out waiting for the browser page");
}

function once(target, event) {
  return new Promise((resolve, reject) => {
    target.addEventListener(event, resolve, { once: true });
    target.addEventListener("error", reject, { once: true });
  });
}
