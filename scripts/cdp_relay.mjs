/**
 * CDP HTTP-aware relay (Node.js version).
 *
 * Problem: Chrome 147 on Windows binds CDP only to 127.0.0.1, so Docker
 * containers and scripts that reach Chrome via host.docker.internal / LAN IP
 * all fail with "socket hang up" or ECONNRESET.
 *
 * This relay:
 *   1. Listens on 0.0.0.0:9223 (reachable from anywhere on the machine).
 *   2. Rewrites the HTTP "Host" header to "localhost:9222" (Chrome security).
 *   3. Rewrites ws:// / http:// URLs in CDP JSON responses so the returned
 *      webSocketDebuggerUrl points back to this relay (host.docker.internal:9223).
 *   4. Tunnels WebSocket upgrade connections bidirectionally via raw TCP.
 */

import http from "node:http";
import net from "node:net";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const LISTEN_HOST = "0.0.0.0";
const LISTEN_PORT = 9223;
const TARGET_HOST = "127.0.0.1";
const TARGET_PORT = 9222;
const HOST_HEADER = `localhost:${TARGET_PORT}`;
// Rewrite ws/http URLs in CDP JSON so Playwright running inside Docker
// reconnects through the host gateway instead of the container loopback.
const RELAY_PUBLIC_HOST = "host.docker.internal";

const LOG_DIR = path.join(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
  "runtime"
);
const LOG_FILE = path.join(LOG_DIR, "cdp-relay.log");

function log(msg) {
  const line = `${new Date().toISOString()} ${msg}`;
  console.log(line);
  try {
    fs.mkdirSync(LOG_DIR, { recursive: true });
    fs.appendFileSync(LOG_FILE, line + "\n");
  } catch (_) {}
}

function rewriteBody(text) {
  return text
    .replace(
      /ws:\/\/(localhost|127\.0\.0\.1):9222/g,
      `ws://${RELAY_PUBLIC_HOST}:${LISTEN_PORT}`
    )
    .replace(
      /http:\/\/(localhost|127\.0\.0\.1):9222/g,
      `http://${RELAY_PUBLIC_HOST}:${LISTEN_PORT}`
    );
}

// ── Regular HTTP requests (CDP JSON discovery endpoints) ──────────────────
const server = http.createServer((clientReq, clientRes) => {
  const isCdpJson = clientReq.url.startsWith("/json");
  log(`HTTP ${clientReq.method} ${clientReq.url} from ${clientReq.socket.remoteAddress}`);

  const proxyReq = http.request(
    {
      host: TARGET_HOST,
      port: TARGET_PORT,
      path: clientReq.url,
      method: clientReq.method,
      headers: { ...clientReq.headers, host: HOST_HEADER },
    },
    (proxyRes) => {
      const chunks = [];
      proxyRes.on("data", (c) => chunks.push(c));
      proxyRes.on("end", () => {
        let body = Buffer.concat(chunks);

        const headers = { ...proxyRes.headers };

        if (isCdpJson) {
          const rewritten = rewriteBody(body.toString("utf8"));
          body = Buffer.from(rewritten, "utf8");
          headers["content-length"] = String(body.length);
        }

        clientRes.writeHead(proxyRes.statusCode, headers);
        clientRes.end(body);
      });
    }
  );

  proxyReq.on("error", (err) => {
    log(`Proxy request error: ${err.message}`);
    if (!clientRes.headersSent) {
      clientRes.writeHead(502);
      clientRes.end("Bad Gateway");
    }
  });

  clientReq.pipe(proxyReq);
});

// ── WebSocket upgrade (CDP session tunnel) ────────────────────────────────
server.on("upgrade", (clientReq, clientSocket, head) => {
  log(`WS  ${clientReq.url} from ${clientSocket.remoteAddress}`);

  const targetSocket = net.connect(TARGET_PORT, TARGET_HOST, () => {
    // Rebuild the upgrade request with the rewritten Host header
    const headers = { ...clientReq.headers, host: HOST_HEADER };
    const headerLines = Object.entries(headers)
      .map(([k, v]) => `${k}: ${v}`)
      .join("\r\n");
    const upgradeReq =
      `${clientReq.method} ${clientReq.url} HTTP/${clientReq.httpVersion}\r\n` +
      `${headerLines}\r\n\r\n`;

    targetSocket.write(upgradeReq);
    if (head && head.length > 0) {
      targetSocket.write(head);
    }
  });

  targetSocket.on("error", (err) => {
    log(`WS target error: ${err.message}`);
    clientSocket.destroy();
  });
  clientSocket.on("error", (err) => {
    log(`WS client error: ${err.message}`);
    targetSocket.destroy();
  });
  targetSocket.on("close", () => clientSocket.destroy());
  clientSocket.on("close", () => targetSocket.destroy());

  // Bidirectional pipe
  targetSocket.pipe(clientSocket);
  clientSocket.pipe(targetSocket);
});

server.on("error", (err) => {
  log(`Server error: ${err.message}`);
});

server.listen(LISTEN_PORT, LISTEN_HOST, () => {
  log(`CDP relay (Node.js) listening on ${LISTEN_HOST}:${LISTEN_PORT} -> ${TARGET_HOST}:${TARGET_PORT}`);
  log(`  Host header : * -> ${HOST_HEADER}`);
  log(`  WS URL rewrite: ws://localhost:${TARGET_PORT} -> ws://${RELAY_PUBLIC_HOST}:${LISTEN_PORT}`);
});

process.on("SIGINT", () => { log("Relay stopped."); process.exit(0); });
process.on("SIGTERM", () => { log("Relay stopped."); process.exit(0); });
