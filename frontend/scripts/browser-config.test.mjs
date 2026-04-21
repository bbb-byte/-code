import test from "node:test";
import assert from "node:assert/strict";

import {
  connectOverCdpWithFallback,
  classifyCdpConnectionError,
  normalizeCdpUrl,
  resolveCdpEndpointCandidates,
} from "./browser-config.mjs";

test("normalizeCdpUrl normalizes host aliases and keeps empty input empty", () => {
  assert.equal(normalizeCdpUrl("host.docker.internal:9222"), "http://host.docker.internal:9222");
  assert.equal(normalizeCdpUrl("http://host.docker.internal:9222"), "http://host.docker.internal:9222");
  assert.equal(normalizeCdpUrl("127.0.0.1:9222"), "http://127.0.0.1:9222");
  assert.equal(normalizeCdpUrl(""), "");
});

test("resolveCdpEndpointCandidates adds IPv4 fallbacks for hostnames", async () => {
  const candidates = await resolveCdpEndpointCandidates(
    "host.docker.internal:9222",
    async (hostname) => (hostname === "host.docker.internal" ? ["192.168.65.2"] : []),
  );

  assert.deepEqual(candidates, [
    "http://host.docker.internal:9222",
    "http://192.168.65.2:9222",
  ]);
});

test("resolveCdpEndpointCandidates does not duplicate direct IPv4 targets", async () => {
  const candidates = await resolveCdpEndpointCandidates("127.0.0.1:9222");
  assert.deepEqual(candidates, ["http://127.0.0.1:9222"]);
});

test("classifyCdpConnectionError recognizes common CDP transport failures", () => {
  assert.equal(classifyCdpConnectionError(new Error("connect ENETUNREACH fdc4:f303:9324::254:9222")), "ipv6_unreachable");
  assert.equal(classifyCdpConnectionError(new Error("connect ECONNREFUSED 127.0.0.1:9222")), "connection_refused");
  assert.equal(
    classifyCdpConnectionError(new Error("retrieving websocket url from http://host.docker.internal:9222 connect ENETUNREACH fdc4:f303:9324::254:9222")),
    "websocket_endpoint_fetch_failed_ipv6_unreachable",
  );
});

test("connectOverCdpWithFallback surfaces actionable guidance for host gateway ECONNREFUSED", async () => {
  const chromium = {
    connectOverCDP: async (url) => {
      throw new Error(`browserType.connectOverCDP: connect ECONNREFUSED ${new URL(url).host}`);
    },
  };

  await assert.rejects(
    () => connectOverCdpWithFallback(chromium, "http://host.docker.internal:9222", { log() {}, warn() {} }),
    (error) => {
      assert.match(error.message, /--remote-debugging-address=0\.0\.0\.0/);
      assert.match(error.message, /host gateway is reachable/i);
      return true;
    },
  );
});
