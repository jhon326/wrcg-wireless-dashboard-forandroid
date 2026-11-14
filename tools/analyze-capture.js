"use strict";

const fs = require("node:fs");
const path = require("node:path");

const input = process.argv[2];
if (!input) {
  console.error("Usage: node tools/analyze-capture.js <capture.packets.jsonl>");
  process.exit(1);
}

const filePath = path.resolve(input);
const lines = fs.readFileSync(filePath, "utf8").trim().split(/\r?\n/);
const header = JSON.parse(lines.shift());
const packets = lines
  .filter(Boolean)
  .map((line) => JSON.parse(line))
  .map((record) => ({ ...record, bytes: Buffer.from(record.data, "base64") }));

if (packets.length === 0) {
  console.error("Capture contains no packets.");
  process.exit(1);
}

const sizes = [...new Set(packets.map((packet) => packet.bytes.length))].sort((a, b) => a - b);
const stride = 4;
const maxFloats = Math.floor(Math.min(...sizes) / stride);
const rows = [];

for (let index = 0; index < maxFloats; index += 1) {
  const offset = index * stride;
  const values = packets
    .filter((packet) => packet.bytes.length >= offset + stride)
    .map((packet) => packet.bytes.readFloatLE(offset))
    .filter(Number.isFinite);
  const unsigned = packets
    .filter((packet) => packet.bytes.length >= offset + stride)
    .map((packet) => packet.bytes.readUInt32LE(offset));
  const finiteRate = values.length / packets.length;
  const min = Math.min(...values);
  const max = Math.max(...values);
  const mean = values.reduce((sum, value) => sum + value, 0) / values.length;
  const spread = max - min;
  const integerRate = values.filter((value) => Math.abs(value - Math.round(value)) < 0.0001).length / values.length;
  const changingRate = new Set(values.map((value) => value.toPrecision(6))).size / values.length;
  rows.push({
    index,
    offset,
    finiteRate,
    min,
    max,
    mean,
    spread,
    integerRate,
    changingRate,
    u32Min: Math.min(...unsigned),
    u32Max: Math.max(...unsigned)
  });
}

console.log(JSON.stringify({
  header,
  file: filePath,
  packetCount: packets.length,
  sizes,
  firstPacketHex: packets[0].bytes.toString("hex")
}, null, 2));

console.log("\nFloat columns (offset, min, max, mean, spread, integerRate, changingRate):");
console.table(rows.map((row) => ({
  index: row.index,
  offset: row.offset,
  min: round(row.min),
  max: round(row.max),
  mean: round(row.mean),
  spread: round(row.spread),
  integer: round(row.integerRate),
  changing: round(row.changingRate)
})));

console.log("\nLikely continuously changing fields:");
console.table(rows
  .filter((row) => row.changingRate > 0.25 && row.finiteRate === 1 && row.spread > 0.001)
  .map((row) => ({
    index: row.index,
    offset: row.offset,
    min: round(row.min),
    max: round(row.max),
    mean: round(row.mean)
  })));

function round(value) {
  if (!Number.isFinite(value)) return String(value);
  if (Math.abs(value) >= 1000) return Math.round(value);
  return Number(value.toFixed(4));
}
