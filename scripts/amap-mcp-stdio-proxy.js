#!/usr/bin/env node

const { spawn } = require("node:child_process");
const fs = require("node:fs");
const path = require("node:path");

const mcpPackage = process.argv[2] || "@amap/amap-maps-mcp-server";
const bundledNpx = path.join(path.dirname(process.execPath), "npx");
const npxCommand = process.env.NPX_COMMAND || (fs.existsSync(bundledNpx) ? bundledNpx : "npx");
process.stderr.write(`[amap-mcp-proxy] starting wrapper for package=${mcpPackage}\n`);
process.stderr.write(`[amap-mcp-proxy] using npx command=${npxCommand}\n`);
const child = spawn(npxCommand, ["-y", mcpPackage], {
  stdio: ["pipe", "pipe", "pipe"],
  env: process.env,
});

let stdoutBuffer = "";

function isJsonRpcMessage(line) {
  try {
    const parsed = JSON.parse(line);
    if (!parsed || typeof parsed !== "object") {
      return false;
    }
    // Accept both requests and responses used by JSON-RPC over stdio.
    return (
      Object.prototype.hasOwnProperty.call(parsed, "jsonrpc") ||
      Object.prototype.hasOwnProperty.call(parsed, "method") ||
      Object.prototype.hasOwnProperty.call(parsed, "id") ||
      Object.prototype.hasOwnProperty.call(parsed, "result") ||
      Object.prototype.hasOwnProperty.call(parsed, "error")
    );
  } catch {
    return false;
  }
}

child.stdout.on("data", (chunk) => {
  stdoutBuffer += chunk.toString("utf8");
  const lines = stdoutBuffer.split(/\r?\n/);
  stdoutBuffer = lines.pop() || "";

  for (const raw of lines) {
    const line = raw.trim();
    if (!line) {
      continue;
    }
    if (isJsonRpcMessage(line)) {
      process.stdout.write(raw + "\n");
    } else {
      process.stderr.write(`[amap-mcp-proxy] non-jsonrpc stdout forwarded to stderr: ${raw}\n`);
    }
  }
});

child.stderr.on("data", (chunk) => {
  process.stderr.write(chunk);
});

process.stdin.on("data", (chunk) => {
  child.stdin.write(chunk);
});

process.stdin.on("end", () => {
  child.stdin.end();
});

child.on("close", (code, signal) => {
  if (stdoutBuffer.trim()) {
    const line = stdoutBuffer.trim();
    if (isJsonRpcMessage(line)) {
      process.stdout.write(stdoutBuffer.endsWith("\n") ? stdoutBuffer : stdoutBuffer + "\n");
    } else {
      process.stderr.write(`[amap-mcp-proxy] trailing non-jsonrpc stdout: ${stdoutBuffer}\n`);
    }
  }

  if (signal) {
    process.stderr.write(`[amap-mcp-proxy] child exited due to signal: ${signal}\n`);
    process.exit(1);
    return;
  }
  process.exit(code ?? 1);
});

child.on("error", (err) => {
  process.stderr.write(`[amap-mcp-proxy] failed to start child process: ${err.message}\n`);
  process.exit(1);
});
