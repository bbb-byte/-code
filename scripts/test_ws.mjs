/**
 * 测试 Chrome CDP WebSocket 连接：
 *   1. 直接连 Chrome (localhost:9222)
 *   2. 通过 relay (127.0.0.1:9223)
 */
import net from "node:net";
import http from "node:http";

function getJson(host, port, path = "/json/version") {
  return new Promise((resolve, reject) => {
    const req = http.request({ host, port, path }, (res) => {
      let data = "";
      res.on("data", (c) => (data += c));
      res.on("end", () => resolve(JSON.parse(data)));
    });
    req.on("error", reject);
    req.end();
  });
}

function testWs(label, host, port, wsPath) {
  return new Promise((resolve) => {
    const s = net.createConnection(port, host, () => {
      const req = [
        `GET ${wsPath} HTTP/1.1`,
        `Host: ${host}:${port}`,
        `Upgrade: websocket`,
        `Connection: Upgrade`,
        `Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==`,
        `Sec-WebSocket-Version: 13`,
        ``, ``,
      ].join("\r\n");
      s.write(req);
    });
    let got = "";
    s.on("data", (d) => {
      got += d.toString();
      const firstLine = got.split("\r\n")[0];
      console.log(`[${label}] Chrome/relay responded: ${firstLine}`);
      s.destroy();
      resolve(firstLine);
    });
    s.on("error", (e) => {
      console.log(`[${label}] Connection error: ${e.message}`);
      resolve("ERROR:" + e.message);
    });
    s.on("close", () => {
      if (!got) {
        console.log(`[${label}] Connection closed with no data (ECONNRESET likely)`);
        resolve("CLOSED_EMPTY");
      }
    });
    setTimeout(() => { s.destroy(); resolve("TIMEOUT"); }, 3000);
  });
}

// 1. 从 Chrome 直接拿 WS URL
console.log("=== 获取 Chrome WS URL ===");
let wsPath;
try {
  const info = await getJson("localhost", 9222);
  wsPath = new URL(info.webSocketDebuggerUrl).pathname;
  console.log("Chrome WS path:", wsPath);
} catch (e) {
  console.error("无法访问 Chrome CDP:", e.message);
  process.exit(1);
}

// 2. 直接连 Chrome WebSocket
console.log("\n=== 直接连 Chrome WebSocket (localhost:9222) ===");
await testWs("direct-chrome", "localhost", 9222, wsPath);

// 3. 通过 relay 连 WebSocket
console.log("\n=== 通过 relay 连 WebSocket (127.0.0.1:9223) ===");
// relay 的 response 中 ws URL 已被改写, 直接用相同路径测试
await testWs("relay", "127.0.0.1", 9223, wsPath);

console.log("\n=== 测试完成 ===");
