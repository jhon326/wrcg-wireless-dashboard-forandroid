"use strict";

const elements = {
  connectionBadge: document.getElementById("connectionBadge"),
  connectionText: document.getElementById("connectionText"),
  rpmBands: [...document.querySelectorAll(".rpm-band")],
  rpm: document.getElementById("rpmValue"),
  maxRpm: document.getElementById("maxRpmValue"),
  gearStage: document.getElementById("gearStage"),
  gear: document.getElementById("gearValue"),
  speed: document.getElementById("speedValue"),
  redlineFlash: document.getElementById("redlineFlash"),
  diagnosticsButton: document.getElementById("diagnosticsButton"),
  diagnosticsPanel: document.getElementById("diagnosticsPanel"),
  closeDiagnostics: document.getElementById("closeDiagnostics"),
  demoButton: document.getElementById("demoButton"),
  fullscreenButton: document.getElementById("fullscreenButton"),
  diagServer: document.getElementById("diagServer"),
  diagMode: document.getElementById("diagMode"),
  diagRate: document.getElementById("diagRate"),
  diagLatency: document.getElementById("diagLatency"),
  diagFrameAge: document.getElementById("diagFrameAge"),
  diagRender: document.getElementById("diagRender"),
  diagEventLoop: document.getElementById("diagEventLoop"),
  diagFps: document.getElementById("diagFps"),
  diagQueue: document.getElementById("diagQueue"),
  diagUdp: document.getElementById("diagUdp"),
  toast: document.getElementById("toast")
};

const view = {
  target: {
    speedKph: 0,
    rpm: 0,
    maxRpm: 8000,
    gear: "N",
    source: "demo"
  },
  mode: "demo",
  socket: null,
  status: null,
  latency: null,
  frameAge: null,
  renderLag: null,
  clockOffset: null,
  lastFrameAt: 0,
  lastReceiveAt: 0,
  lastPingAt: 0,
  reconnectTimer: null,
  reconnectDelay: 500,
  renderScheduled: false,
  staleTimer: null,
  diagnosticTimer: null,
  lastBandState: "",
  lastRpmText: "",
  lastMaxRpmText: "",
  lastGearText: "",
  lastSpeedText: "",
  lastRedlineState: null,
  diagnosticFrames: 0,
  lastMetricTick: performance.now(),
  eventLoopLag: null,
  receivedHz: null
};

function clamp(value, min = 0, max = 1) {
  const number = Number(value);
  if (!Number.isFinite(number)) return min;
  return Math.min(max, Math.max(min, number));
}

function connect() {
  clearTimeout(view.reconnectTimer);
  setConnection("connecting", "连接中");

  const protocol = location.protocol === "https:" ? "wss:" : "ws:";
  const socket = new WebSocket(`${protocol}//${location.host}/ws`);
  view.socket = socket;

  socket.addEventListener("open", () => {
    view.reconnectDelay = 500;
    socket.send(JSON.stringify({ type: "hello" }));
    setConnection(view.mode === "live" ? "live" : "demo", view.mode === "live" ? "实时" : "演示");
    requestWakeLock();
  });

  socket.addEventListener("message", (event) => {
    let message;
    try {
      message = JSON.parse(event.data);
    } catch {
      return;
    }

    if (message.type === "telemetry" && message.frame) {
      const receivedAt = performance.now();
      view.target = message.frame;
      view.lastFrameAt = receivedAt;
      view.lastReceiveAt = receivedAt;
      view.diagnosticFrames += 1;
      if (Number.isFinite(message.frame.sentAt) && view.clockOffset !== null) {
        view.frameAge = Math.max(0, Date.now() + view.clockOffset - message.frame.sentAt);
      }
      clearTimeout(view.staleTimer);
      view.staleTimer = setTimeout(scheduleRender, 650);
      scheduleRender();
    } else if (message.type === "status" && message.status) {
      applyStatus(message.status);
      scheduleRender();
    } else if (message.type === "adapter") {
      applyMode("capture");
    } else if (message.type === "pong") {
      const receivedAt = Date.now();
      view.latency = Math.max(0, receivedAt - message.sentAt);
      view.clockOffset = message.serverAt - (message.sentAt + view.latency / 2);
    } else if (message.type === "notice") {
      showToast(message.message || "操作完成");
    } else if (message.type === "error") {
      showToast(message.message || "连接错误");
    }
  });

  socket.addEventListener("close", () => {
    setConnection("connecting", "重连中");
    clearTimeout(view.reconnectTimer);
    view.reconnectTimer = setTimeout(connect, view.reconnectDelay);
    view.reconnectDelay = Math.min(8000, view.reconnectDelay * 1.7);
  });

  socket.addEventListener("error", () => socket.close());
}

function applyStatus(status) {
  view.status = status;
  applyMode(status.mode);
  elements.diagServer.textContent = status.server === "online" ? "在线" : "异常";
  elements.diagMode.textContent = `${String(status.source || status.mode).toUpperCase()} · ${status.mode.toUpperCase()}`;
  elements.diagRate.textContent = status.mode === "demo" ? "30 Hz" : `${status.udp?.packetsPerSecond || 0} Hz`;
  elements.diagUdp.textContent = status.udp?.lastPacketAt
    ? `${status.udp.packets} 包 · ${status.udp.lastPacketSize || "--"} bytes`
    : `监听 :${status.udp?.port || 20777}`;
}

function applyMode(mode) {
  view.mode = mode === "live" ? "live" : mode === "capture" ? "capture" : "demo";
  if (view.mode === "live") setConnection("live", "实时");
  else if (view.mode === "capture") setConnection("connecting", "采集");
  else setConnection("demo", "演示");
}

function setConnection(kind, text) {
  elements.connectionBadge.className = `connection-badge is-${kind}`;
  elements.connectionText.textContent = text;
}

function scheduleRender() {
  if (view.renderScheduled) return;
  view.renderScheduled = true;
  requestAnimationFrame(() => {
    view.renderScheduled = false;
    render();
  });
}

function render() {
  const frame = view.target;
  const rpm = Math.max(0, Number(frame.rpm) || 0);
  const maxRpm = Math.max(1000, Number(frame.maxRpm) || 8000);
  const rpmRatio = clamp(rpm / maxRpm);
  const speed = Math.max(0, Math.round(Number(frame.speedKph) || 0));
  const frameFresh = performance.now() - view.lastFrameAt < 600;
  const activeTelemetry = view.mode !== "capture" && frameFresh;
  const redline = activeTelemetry && rpmRatio >= 0.88;

  setText(elements.rpm, Math.round(rpm).toLocaleString("en-US"), "lastRpmText");
  setText(elements.maxRpm, `/ ${Math.round(maxRpm).toLocaleString("en-US")}`, "lastMaxRpmText");
  setText(elements.gear, frame.gear || "N", "lastGearText");
  setText(elements.speed, String(speed).padStart(3, "0"), "lastSpeedText");
  updateRpmBands(rpmRatio, redline);

  if (view.lastRedlineState !== redline) {
    elements.gearStage.classList.toggle("is-redline", redline);
    elements.redlineFlash.classList.toggle("is-active", redline);
    view.lastRedlineState = redline;
  }
  elements.diagLatency.textContent = view.latency === null ? "-- ms" : `${view.latency} ms`;
  if (!frameFresh) view.frameAge = null;
  elements.diagFrameAge.textContent = view.frameAge === null ? "-- ms" : `${Math.round(view.frameAge)} ms`;
  view.renderLag = frameFresh ? performance.now() - view.lastReceiveAt : null;
  elements.diagRender.textContent = Number.isFinite(view.renderLag) ? `${Math.round(view.renderLag)} ms` : "-- ms";
  elements.diagEventLoop.textContent = view.eventLoopLag === null ? "-- ms" : `${Math.round(view.eventLoopLag)} ms`;
  elements.diagFps.textContent = view.receivedHz === null ? "-- Hz" : `${view.receivedHz.toFixed(1)} Hz`;
  elements.diagQueue.textContent = view.status?.transport
    ? `${view.status.transport.maxClientQueueBytes || 0} bytes / 丢弃 ${view.status.transport.droppedTelemetryFrames || 0}`
    : "-- bytes";
}

function updateRpmBands(rpmRatio, redline) {
  const thresholds = [0.46, 0.61, 0.76];
  const pairs = [[0, 5], [1, 4], [2, 3]];
  const litPairs = redline
    ? pairs.length
    : pairs.reduce((count, pair, index) => count + (rpmRatio >= thresholds[index] ? 1 : 0), 0);
  const stateKey = `${redline ? "red" : "normal"}:${litPairs}`;
  if (view.lastBandState === stateKey) return;
  view.lastBandState = stateKey;

  if (redline) {
    elements.rpmBands.forEach((band) => {
      band.classList.remove("is-lit", "is-redline");
      band.classList.add("is-lit", "is-redline");
    });
    return;
  }

  elements.rpmBands.forEach((band) => band.classList.remove("is-lit", "is-redline"));
  pairs.forEach((pair, pairIndex) => {
    if (pairIndex >= litPairs) return;
    pair.forEach((bandIndex) => elements.rpmBands[bandIndex].classList.add("is-lit"));
  });
}

function setText(element, value, cacheKey) {
  if (view[cacheKey] === value) return;
  view[cacheKey] = value;
  element.textContent = value;
}

function reportDiagnostics() {
  if (view.socket?.readyState !== WebSocket.OPEN) return;
  const now = performance.now();
  const elapsed = Math.max(1, now - view.lastMetricTick);
  view.eventLoopLag = Math.max(0, elapsed - 5000);
  view.receivedHz = view.diagnosticFrames * 1000 / elapsed;
  view.diagnosticFrames = 0;
  view.lastMetricTick = now;
  const fresh = now - view.lastFrameAt < 1000;
  scheduleRender();
  const payload = {
    type: "client-metrics",
    latencyMs: fresh ? view.latency : null,
    frameAgeMs: fresh ? view.frameAge : null,
    renderLagMs: fresh ? view.renderLag : null,
    eventLoopLagMs: view.eventLoopLag,
    receivedHz: view.receivedHz,
    droppedFrames: view.status?.transport?.droppedTelemetryFrames ?? null,
    maxQueueBytes: view.status?.transport?.maxClientQueueBytes ?? null,
    viewport: `${innerWidth}x${innerHeight}@${devicePixelRatio}`,
    visibility: document.visibilityState,
    mode: view.mode
  };
  fetch("/api/client-log", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
    keepalive: payload.visibility === "hidden"
  }).catch(() => {});
}

function showToast(message, duration = 2300) {
  elements.toast.textContent = message;
  elements.toast.classList.add("is-visible");
  clearTimeout(showToast.timer);
  showToast.timer = setTimeout(() => elements.toast.classList.remove("is-visible"), duration);
}

function toggleDiagnostics(force) {
  elements.diagnosticsPanel.hidden = !(force ?? elements.diagnosticsPanel.hidden);
}

function send(message) {
  if (view.socket?.readyState !== WebSocket.OPEN) {
    showToast("服务尚未连接");
    return false;
  }
  view.socket.send(JSON.stringify(message));
  return true;
}

async function toggleFullscreen() {
  try {
    if (!document.fullscreenElement) await document.documentElement.requestFullscreen();
    else await document.exitFullscreen();
    await requestWakeLock();
  } catch {
    showToast("当前浏览器不允许自动全屏");
  }
}

async function requestWakeLock() {
  if (!("wakeLock" in navigator) || document.visibilityState !== "visible") return;
  try {
    await navigator.wakeLock.request("screen");
  } catch {
    // Wake Lock is optional.
  }
}

elements.diagnosticsButton.addEventListener("click", () => toggleDiagnostics());
elements.closeDiagnostics.addEventListener("click", () => toggleDiagnostics(false));
elements.fullscreenButton.addEventListener("click", toggleFullscreen);
elements.demoButton.addEventListener("click", () => {
  if (send({ type: "set-mode", mode: "demo" })) {
    applyMode("demo");
    showToast("已切换模拟数据");
  }
});

setInterval(() => {
  if (view.socket?.readyState === WebSocket.OPEN) {
    const now = Date.now();
    if (now - view.lastPingAt > 2000) {
      view.lastPingAt = now;
      send({ type: "ping", sentAt: now });
    }
  }
}, 1000);

view.diagnosticTimer = setInterval(reportDiagnostics, 5000);

document.addEventListener("visibilitychange", () => {
  if (document.visibilityState === "visible") requestWakeLock();
  else reportDiagnostics();
});

if ("serviceWorker" in navigator) {
  window.addEventListener("load", () => navigator.serviceWorker.register("/sw.js").catch(() => {}));
}

scheduleRender();
connect();
