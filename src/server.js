"use strict";

const dgram = require("node:dgram");
const fs = require("node:fs");
const http = require("node:http");
const os = require("node:os");
const path = require("node:path");
const { spawn } = require("node:child_process");
const { DemoTelemetry } = require("./demo");
const { PacketCapture } = require("./capture");
const { normalizeTelemetry, parseDatagram } = require("./protocol");
const { WebSocketHub } = require("./websocket");

const SOURCE_PUBLIC_DIR = path.resolve(__dirname, "..", "public");
const MIME_TYPES = {
  ".html": "text/html; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".webmanifest": "application/manifest+json; charset=utf-8",
  ".svg": "image/svg+xml",
  ".png": "image/png",
  ".ico": "image/x-icon"
};

function parseArgs(argv = process.argv.slice(2)) {
  const options = {
    port: Number(process.env.WRCG_DASHBOARD_PORT || 8787),
    udpPort: Number(process.env.WRCG_TELEMETRY_PORT || 20777),
    host: process.env.WRCG_DASHBOARD_HOST || "0.0.0.0",
    open: false
  };
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    if (arg === "--port") options.port = Number(argv[++index]);
    else if (arg === "--udp-port") options.udpPort = Number(argv[++index]);
    else if (arg === "--host") options.host = argv[++index];
    else if (arg === "--open") options.open = true;
  }
  return options;
}

function getLanEndpoints() {
  const endpoints = [];
  for (const [name, entries] of Object.entries(os.networkInterfaces())) {
    for (const entry of entries || []) {
      if (entry.family !== "IPv4" || entry.internal) continue;
      let score = 100;
      if (/wlan|wi-?fi|wireless/i.test(name)) score -= 80;
      else if (/ethernet|以太网/i.test(name)) score -= 60;
      if (/wintun|tailscale|zerotier|vpn|clash|wireguard|openvpn|tun\b|tap\b|virtual|vmware|virtualbox|hyper-v|vethernet|vgate/i.test(name)) {
        score += 500;
      }
      if (entry.netmask === "255.255.255.255") score += 200;
      endpoints.push({ name, address: entry.address, netmask: entry.netmask, score });
    }
  }
  return endpoints
    .sort((left, right) => left.score - right.score || left.name.localeCompare(right.name))
    .filter((entry, index, list) => list.findIndex((item) => item.address === entry.address) === index);
}

function getLanAddresses() {
  return getLanEndpoints().map((entry) => entry.address);
}

function sendJson(response, status, value) {
  const body = JSON.stringify(value);
  response.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Content-Length": Buffer.byteLength(body),
    "Cache-Control": "no-store"
  });
  response.end(body);
}

function readJsonBody(request, limit = 64 * 1024) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    request.on("data", (chunk) => {
      size += chunk.length;
      if (size > limit) {
        reject(new Error("Request too large"));
        request.destroy();
        return;
      }
      chunks.push(chunk);
    });
    request.on("end", () => {
      try {
        resolve(chunks.length ? JSON.parse(Buffer.concat(chunks).toString("utf8")) : {});
      } catch {
        reject(new Error("Invalid JSON"));
      }
    });
    request.on("error", reject);
  });
}

function findPublicAsset(publicAssets, relativePath) {
  if (!publicAssets) return null;
  const value = publicAssets instanceof Map
    ? publicAssets.get(relativePath)
    : Object.prototype.hasOwnProperty.call(publicAssets, relativePath)
      ? publicAssets[relativePath]
      : null;
  if (value === null || value === undefined) return null;
  const content = value && typeof value === "object" && !Buffer.isBuffer(value)
    ? value.content
    : value;
  if (content === null || content === undefined) return null;
  return Buffer.isBuffer(content) ? content : Buffer.from(String(content), "utf8");
}

function serveStatic(request, response, pathname, options) {
  let relativePath = pathname === "/" ? "index.html" : pathname.replace(/^\/+/, "");
  try {
    relativePath = decodeURIComponent(relativePath);
  } catch {
    response.writeHead(400);
    response.end("Bad request");
    return;
  }
  relativePath = relativePath.replace(/\\/g, "/");

  const asset = findPublicAsset(options.publicAssets, relativePath);
  if (asset) {
    const extension = path.extname(relativePath).toLowerCase();
    const noCache = [".html", ".css", ".js", ".webmanifest"].includes(extension);
    response.writeHead(200, {
      "Content-Type": MIME_TYPES[extension] || "application/octet-stream",
      "Content-Length": asset.length,
      "Cache-Control": noCache ? "no-cache" : "public, max-age=300"
    });
    response.end(request.method === "HEAD" ? undefined : asset);
    return;
  }

  if (options.publicAssets) {
    response.writeHead(404, { "Content-Type": "text/plain; charset=utf-8" });
    response.end("Not found");
    return;
  }

  const publicDirectory = options.publicDirectory || SOURCE_PUBLIC_DIR;
  const filePath = path.resolve(publicDirectory, relativePath);
  if (!filePath.startsWith(`${publicDirectory}${path.sep}`) && filePath !== publicDirectory) {
    response.writeHead(403);
    response.end("Forbidden");
    return;
  }

  fs.stat(filePath, (error, stats) => {
    if (error || !stats.isFile()) {
      response.writeHead(404, { "Content-Type": "text/plain; charset=utf-8" });
      response.end("Not found");
      return;
    }

    const extension = path.extname(filePath).toLowerCase();
    const noCache = [".html", ".css", ".js", ".webmanifest"].includes(extension);
    response.writeHead(200, {
      "Content-Type": MIME_TYPES[extension] || "application/octet-stream",
      "Content-Length": stats.size,
      "Cache-Control": noCache ? "no-cache" : "public, max-age=300"
    });
    if (request.method === "HEAD") response.end();
    else fs.createReadStream(filePath).pipe(response);
  });
}

function createDashboardServer(options = parseArgs()) {
  const startedAt = Date.now();
  const demo = new DemoTelemetry(startedAt);
  const capture = new PacketCapture({
    directory: options.captureDirectory || path.resolve(__dirname, "..", ".captures")
  });

  const state = {
    mode: "demo",
    source: "demo",
    sequence: 0,
    frame: demo.next(startedAt),
    clients: 0,
    lastBroadcastAt: 0,
    lastUdpAt: 0,
    lastUdpSize: 0,
    lastUdpSource: null,
    packetCount: 0,
    unknownPacketCount: 0,
    packetCountAtLastSecond: 0,
    packetRate: 0,
    lastAdapterBroadcastAt: 0,
    droppedTelemetryFrames: 0,
    maxClientQueueBytes: 0,
    lastMotorBattery: null,
    motorMotion: "idle",
    motorMotionUntil: 0,
    lastClientMetrics: null,
    lastError: null
  };

  const hub = new WebSocketHub({
    path: "/ws",
    onMessage: (client, rawMessage) => {
      if (!client) return;
      handleClientMessage(client, rawMessage);
    }
  });

  function buildStatus() {
    return {
      server: "online",
      version: "0.1.0",
      mode: state.mode,
      source: state.source,
      uptimeSeconds: Math.floor((Date.now() - startedAt) / 1000),
      clients: hub.clients.size,
      clientAddresses: [...hub.clients].map((client) => client.remoteAddress),
      transport: {
        droppedTelemetryFrames: state.droppedTelemetryFrames,
        maxClientQueueBytes: state.maxClientQueueBytes,
        lastClientMetrics: state.lastClientMetrics
      },
      udp: {
        port: options.udpPort,
        packets: state.packetCount,
        unknownPackets: state.unknownPacketCount,
        packetsPerSecond: state.packetRate,
        lastPacketAt: state.lastUdpAt || null,
        lastPacketSize: state.lastUdpSize || null,
        lastSource: state.lastUdpSource,
        capture: capture.status()
      },
      lastError: state.lastError
    };
  }

  function broadcastStatus() {
    hub.broadcast({ type: "status", status: buildStatus() }, { maxBufferedBytes: 64 * 1024 });
  }

  function broadcastFrame(force = false) {
    const now = Date.now();
    if (!force && now - state.lastBroadcastAt < 30) return;
    state.lastBroadcastAt = now;
    const queueBytes = Math.max(0, ...[...hub.clients].map((client) => client.socket.writableLength || 0));
    state.maxClientQueueBytes = Math.max(state.maxClientQueueBytes, queueBytes);
    const result = hub.broadcast({
      type: "telemetry",
      frame: compactFrame(state.frame, now)
    }, { maxBufferedBytes: 2 * 1024 });
    state.droppedTelemetryFrames += result.skipped;
  }

  function compactFrame(frame, sentAt) {
    return {
      speedKph: frame.speedKph,
      rpm: frame.rpm,
      maxRpm: frame.maxRpm,
      gear: frame.gear,
      sentAt
    };
  }

  function setDemoMode() {
    state.mode = "demo";
    state.source = "demo";
    demo.startedAt = Date.now() - (state.frame.stageTimeMs || 0);
    state.frame = demo.next();
    broadcastFrame(true);
    broadcastStatus();
  }

  function handleClientMessage(client, rawMessage) {
    let message;
    try {
      message = JSON.parse(rawMessage);
    } catch {
      client.sendJson({ type: "error", message: "Invalid client message" });
      return;
    }

    if (message.type === "hello") {
      client.sendJson({ type: "status", status: buildStatus() });
      client.sendJson({ type: "telemetry", frame: compactFrame(state.frame, Date.now()) });
      return;
    }

    if (message.type === "set-mode" && message.mode === "demo") {
      setDemoMode();
      return;
    }

    if (message.type === "capture-restart") {
      const file = capture.restart();
      broadcastStatus();
      client.sendJson({ type: "notice", message: "新的原始数据包录制已开始", file });
      return;
    }

    if (message.type === "ping") {
      client.sendJson({ type: "pong", sentAt: message.sentAt, serverAt: Date.now() });
    }
  }

  const server = http.createServer(async (request, response) => {
    const url = new URL(request.url, "http://localhost");
    try {
      if (request.method === "GET" && url.pathname === "/api/status") {
        sendJson(response, 200, buildStatus());
        return;
      }

      if (request.method === "GET" && url.pathname === "/healthz") {
        sendJson(response, 200, { ok: true });
        return;
      }

      if (request.method === "POST" && url.pathname === "/api/demo") {
        setDemoMode();
        sendJson(response, 200, buildStatus());
        return;
      }

      if (request.method === "POST" && url.pathname === "/api/capture/restart") {
        const file = capture.restart();
        broadcastStatus();
        sendJson(response, 200, { ok: true, file });
        return;
      }

      if (request.method === "POST" && url.pathname === "/api/client-log") {
        const body = await readJsonBody(request);
        state.lastClientMetrics = { receivedAt: Date.now(), ...body };
        console.log("[client]", body.message || JSON.stringify(body));
        sendJson(response, 200, { ok: true });
        return;
      }

      if (request.method === "GET" || request.method === "HEAD") {
        serveStatic(request, response, url.pathname, options);
        return;
      }

      sendJson(response, 405, { error: "Method not allowed" });
    } catch (error) {
      state.lastError = error.message;
      sendJson(response, 400, { error: error.message });
    }
  });

  hub.attach(server);

  const udpSocket = dgram.createSocket({ type: "udp4", reuseAddr: true });
  let udpListening = false;
  udpSocket.on("message", (buffer, rinfo) => {
    const now = Date.now();
    const startsNewSession = state.lastUdpAt === 0 || now - state.lastUdpAt > 2000;
    const parsed = parseDatagram(buffer);
    state.packetCount += 1;
    state.lastUdpAt = now;
    state.lastUdpSize = buffer.length;
    state.lastUdpSource = `${rinfo.address}:${rinfo.port}`;

    if (parsed.ok) {
      state.mode = "live";
      state.source = parsed.telemetry.source;
      state.sequence += 1;
      const frame = normalizeTelemetry(parsed.telemetry, {
        source: parsed.telemetry.source,
        receivedAt: now,
        sequence: state.sequence
      });
      frame.hybridState = inferHybridState(frame, now);
      state.frame = frame;
      state.lastError = null;
      if (options.capture !== false) {
        if (startsNewSession) capture.restart(now);
        capture.record(buffer, rinfo, now);
      }
      broadcastFrame();
    } else {
      state.unknownPacketCount += 1;
      state.mode = "capture";
      state.source = "wrcg-raw";
      const recorded = capture.record(buffer, rinfo, now);
      if (now - state.lastAdapterBroadcastAt > 250) {
        state.lastAdapterBroadcastAt = now;
        hub.broadcast({
          type: "adapter",
          adapter: {
            status: "packet-detected",
            packetSize: buffer.length,
            packetRate: state.packetRate,
            recorded,
            capture: capture.status(),
            message: "已收到 WRCG UDP 数据，正在等待字段映射"
          }
        }, { maxBufferedBytes: 64 * 1024 });
      }
    }
  });
  udpSocket.on("error", (error) => {
    state.lastError = `UDP ${options.udpPort}: ${error.message}`;
    console.error(`[udp] ${error.message}`);
  });
  udpSocket.bind(options.udpPort, "0.0.0.0", () => {
    udpListening = true;
    udpSocket.setRecvBufferSize(1024 * 1024);
    console.log(`[udp] listening on 0.0.0.0:${options.udpPort}`);
  });

  function inferHybridState(frame, now) {
    if (frame.batteryPercent === null) {
      state.lastMotorBattery = null;
      state.motorMotion = "idle";
      return null;
    }

    if (state.lastMotorBattery === null || frame.source !== state.frame.source) {
      state.lastMotorBattery = frame.batteryPercent;
      return "idle";
    }

    const delta = frame.batteryPercent - state.lastMotorBattery;
    state.lastMotorBattery = frame.batteryPercent;
    if (delta > 0.00001) {
      state.motorMotion = "charge";
      state.motorMotionUntil = now + 1000;
    } else if (delta < -0.00001) {
      state.motorMotion = "discharge";
      state.motorMotionUntil = now + 1000;
    } else if (now > state.motorMotionUntil) {
      state.motorMotion = "idle";
    }
    return state.motorMotion;
  }

  const demoInterval = setInterval(() => {
    if (state.mode !== "demo") return;
    state.frame = demo.next();
    state.sequence += 1;
    broadcastFrame();
  }, 8);

  const statsInterval = setInterval(() => {
    state.packetRate = state.packetCount - state.packetCountAtLastSecond;
    state.packetCountAtLastSecond = state.packetCount;
    if (udpListening) broadcastStatus();
  }, 1000);
  statsInterval.unref();

  server.on("listening", () => {
    const httpPort = server.address().port;
    console.log("");
    console.log("WRCG 无线仪表盘原型已启动");
    console.log(`  本机仪表:  http://127.0.0.1:${httpPort}`);
    for (const endpoint of getLanEndpoints()) {
      const label = endpoint.score >= 200 ? "虚拟网卡" : "手机仪表";
      console.log(`  ${label}:  http://${endpoint.address}:${httpPort}  (${endpoint.name})`);
    }
    console.log(`  WRCG UDP:  0.0.0.0:${options.udpPort} -> WebSocket 30 Hz`);
    console.log("  按 Ctrl+C 停止");
    console.log("");
    if (options.open) {
      const url = `http://127.0.0.1:${options.port}`;
      const command = process.platform === "win32" ? "cmd" : process.platform === "darwin" ? "open" : "xdg-open";
      const args = process.platform === "win32" ? ["/c", "start", "", url] : [url];
      spawn(command, args, { detached: true, stdio: "ignore" }).unref();
    }
  });

  function close() {
    clearInterval(demoInterval);
    clearInterval(statsInterval);
    hub.close();
    try {
      udpSocket.close();
    } catch {
      // Socket may already be closed.
    }
    return new Promise((resolve) => {
      if (!server.listening) return resolve();
      server.close(resolve);
    });
  }

  return { server, udpSocket, hub, state, capture, close };
}

if (require.main === module) {
  const options = parseArgs();
  const app = createDashboardServer(options);
  app.server.listen(options.port, options.host);

  const shutdown = async () => {
    console.log("\n正在停止服务...");
    await app.close();
    process.exit(0);
  };
  process.on("SIGINT", shutdown);
  process.on("SIGTERM", shutdown);
}

module.exports = { createDashboardServer, getLanAddresses, getLanEndpoints, parseArgs };
