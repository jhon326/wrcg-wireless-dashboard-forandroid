"use strict";

const fs = require("node:fs");
const path = require("node:path");

class PacketCapture {
  constructor(options = {}) {
    this.directory = options.directory || path.resolve(".captures");
    this.maxRecords = options.maxRecords || 600;
    this.sampleIntervalMs = options.sampleIntervalMs || 16;
    this.records = 0;
    this.bytes = 0;
    this.filePath = null;
    this.lastSampleAt = 0;
    this.pending = [];
    this.writing = false;
  }

  restart(now = Date.now()) {
    fs.mkdirSync(this.directory, { recursive: true });
    const stamp = new Date(now).toISOString().replace(/[:.]/g, "-");
    this.filePath = path.join(this.directory, `wrcg-${stamp}.packets.jsonl`);
    this.records = 0;
    this.bytes = 0;
    this.lastSampleAt = 0;
    fs.writeFileSync(this.filePath, `${JSON.stringify({
      format: "wrcg-dashboard-packet-capture",
      version: 1,
      createdAt: new Date(now).toISOString(),
      note: "Each line contains one sampled UDP datagram encoded as base64."
    })}\n`, "utf8");
    return this.filePath;
  }

  record(buffer, rinfo, now = Date.now()) {
    if (this.records >= this.maxRecords) return false;
    if (now - this.lastSampleAt < this.sampleIntervalMs) return false;
    if (!this.filePath) this.restart(now);

    this.lastSampleAt = now;
    this.records += 1;
    this.bytes += buffer.length;
    const line = JSON.stringify({
      t: now,
      from: `${rinfo.address}:${rinfo.port}`,
      size: buffer.length,
      data: buffer.toString("base64")
    });
    this.pending.push(`${line}\n`);
    this.flush();
    return true;
  }

  flush() {
    if (this.writing || this.pending.length === 0 || !this.filePath) return;
    this.writing = true;
    const chunk = this.pending.join("");
    this.pending = [];
    fs.appendFile(this.filePath, chunk, "utf8", (error) => {
      this.writing = false;
      if (error) {
        console.error("[capture] write failed:", error.message);
        return;
      }
      this.flush();
    });
  }

  status() {
    return {
      active: this.records < this.maxRecords,
      file: this.filePath,
      records: this.records,
      maxRecords: this.maxRecords,
      bytes: this.bytes
    };
  }
}

module.exports = { PacketCapture };
