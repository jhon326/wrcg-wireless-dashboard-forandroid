"use strict";

const crypto = require("node:crypto");
const { EventEmitter } = require("node:events");

const WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

function encodeFrame(message, opcode = 0x1) {
  const payload = Buffer.isBuffer(message) ? message : Buffer.from(String(message), "utf8");
  let header;
  if (payload.length < 126) {
    header = Buffer.from([0x80 | opcode, payload.length]);
  } else if (payload.length < 65536) {
    header = Buffer.alloc(4);
    header[0] = 0x80 | opcode;
    header[1] = 126;
    header.writeUInt16BE(payload.length, 2);
  } else {
    header = Buffer.alloc(10);
    header[0] = 0x80 | opcode;
    header[1] = 127;
    header.writeBigUInt64BE(BigInt(payload.length), 2);
  }
  return Buffer.concat([header, payload]);
}

class WebSocketClient extends EventEmitter {
  constructor(socket, request) {
    super();
    this.socket = socket;
    this.request = request;
    this.remoteAddress = request.socket.remoteAddress;
    this.buffer = Buffer.alloc(0);
    this.alive = true;
    this.closed = false;

    socket.setNoDelay(true);
    socket.on("data", (chunk) => this.onData(chunk));
    socket.on("close", () => this.close());
    socket.on("end", () => this.close());
    socket.on("error", () => this.close());
  }

  sendJson(value, maxBufferedBytes = 1024 * 1024) {
    if (this.closed || !this.socket.writable) return false;
    if (this.socket.writableLength > maxBufferedBytes) return false;
    this.socket.write(encodeFrame(JSON.stringify(value)));
    return true;
  }

  ping() {
    if (this.closed || !this.socket.writable) return;
    this.socket.write(encodeFrame(Buffer.alloc(0), 0x9));
  }

  onData(chunk) {
    this.buffer = Buffer.concat([this.buffer, chunk]);
    while (this.buffer.length >= 2) {
      const first = this.buffer[0];
      const second = this.buffer[1];
      const opcode = first & 0x0f;
      const masked = (second & 0x80) !== 0;
      let payloadLength = second & 0x7f;
      let offset = 2;

      if (payloadLength === 126) {
        if (this.buffer.length < 4) return;
        payloadLength = this.buffer.readUInt16BE(2);
        offset = 4;
      } else if (payloadLength === 127) {
        if (this.buffer.length < 10) return;
        const largeLength = this.buffer.readBigUInt64BE(2);
        if (largeLength > BigInt(1024 * 1024)) return this.close();
        payloadLength = Number(largeLength);
        offset = 10;
      }

      const maskLength = masked ? 4 : 0;
      if (this.buffer.length < offset + maskLength + payloadLength) return;

      let payload = this.buffer.subarray(offset + maskLength, offset + maskLength + payloadLength);
      if (masked) {
        const mask = this.buffer.subarray(offset, offset + 4);
        payload = Buffer.from(payload);
        for (let index = 0; index < payload.length; index += 1) {
          payload[index] ^= mask[index % 4];
        }
      }
      this.buffer = this.buffer.subarray(offset + maskLength + payloadLength);

      if (opcode === 0x8) {
        this.close();
        return;
      }
      if (opcode === 0x9) {
        this.socket.write(encodeFrame(payload, 0xA));
        continue;
      }
      if (opcode === 0xA) {
        this.alive = true;
        continue;
      }
      if (opcode === 0x1) {
        this.emit("message", payload.toString("utf8"));
      }
    }
  }

  close() {
    if (this.closed) return;
    this.closed = true;
    this.emit("closed");
    if (this.socket.writable) this.socket.end();
  }
}

class WebSocketHub {
  constructor(options = {}) {
    this.path = options.path || "/ws";
    this.clients = new Set();
    this.onMessage = options.onMessage || (() => {});
    this.heartbeat = setInterval(() => this.checkClients(), 15000);
    this.heartbeat.unref();
  }

  attach(server) {
    server.on("upgrade", (request, socket) => this.handleUpgrade(request, socket));
  }

  handleUpgrade(request, socket) {
    const url = new URL(request.url, "http://localhost");
    const key = request.headers["sec-websocket-key"];
    if (url.pathname !== this.path || !key) {
      socket.destroy();
      return;
    }

    const accept = crypto.createHash("sha1").update(`${key}${WS_GUID}`).digest("base64");
    socket.write([
      "HTTP/1.1 101 Switching Protocols",
      "Upgrade: websocket",
      "Connection: Upgrade",
      `Sec-WebSocket-Accept: ${accept}`,
      "\r\n"
    ].join("\r\n"));

    const client = new WebSocketClient(socket, request);
    this.clients.add(client);
    client.on("message", (message) => this.onMessage(client, message));
    client.on("closed", () => this.clients.delete(client));
    this.emitClientCount();
  }

  emitClientCount() {
    this.onMessage(null, JSON.stringify({ type: "presence", clients: this.clients.size }));
  }

  broadcast(value, options = {}) {
    let sent = 0;
    let skipped = 0;
    for (const client of this.clients) {
      if (client.sendJson(value, options.maxBufferedBytes)) sent += 1;
      else skipped += 1;
    }
    return { sent, skipped };
  }

  checkClients() {
    for (const client of this.clients) {
      if (!client.alive) {
        client.close();
        continue;
      }
      client.alive = false;
      client.ping();
    }
  }

  close() {
    clearInterval(this.heartbeat);
    for (const client of this.clients) client.close();
    this.clients.clear();
  }
}

module.exports = { WebSocketHub, encodeFrame };
