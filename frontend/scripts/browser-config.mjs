import dns from "node:dns/promises";
import net from "node:net";
import path from "node:path";

const DEFAULT_BROWSER_ARGS = [
  "--disable-blink-features=AutomationControlled",
  "--disable-infobars",
  "--no-first-run",
  "--no-default-browser-check",
];
export const DEFAULT_PUBLIC_TASK_CDP_URL = "http://host.docker.internal:9222";

export function resolveBrowserConfig(frontendDir) {
  const browserPath = firstNonEmpty([
    process.env.PUBLIC_TASK_BROWSER_PATH,
    process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH,
    process.env.CHROME_PATH,
    process.env.CHROMIUM_PATH,
  ]);
  const browserChannel = firstNonEmpty([
    process.env.PUBLIC_TASK_BROWSER_CHANNEL,
    process.env.PLAYWRIGHT_BROWSER_CHANNEL,
  ]);
  const profileDir = firstNonEmpty([
    process.env.PUBLIC_TASK_BROWSER_PROFILE_DIR,
    path.join(frontendDir, ".runtime", "browser-profile"),
  ]);

  return {
    browserPath,
    browserChannel,
    profileDir,
  };
}

export function buildPersistentContextOptions({ browserPath, browserChannel, headless }) {
  const options = {
    headless,
    viewport: { width: 1440, height: 960 },
    locale: "zh-CN",
    args: DEFAULT_BROWSER_ARGS,
    ignoreDefaultArgs: ["--enable-automation"],
  };

  if (browserPath) {
    options.executablePath = browserPath;
  } else if (browserChannel) {
    options.channel = browserChannel;
  }

  return options;
}

export function normalizeCdpUrl(rawUrl) {
  const trimmed = String(rawUrl || "").trim();
  if (!trimmed) return "";
  const withProtocol = /^[a-z]+:\/\//i.test(trimmed) ? trimmed : `http://${trimmed}`;
  const parsed = new URL(withProtocol);
  if (!parsed.port) {
    parsed.port = "9222";
  }
  return `${parsed.protocol}//${parsed.host}`;
}

export async function resolveCdpEndpointCandidates(rawUrl, lookupIpv4 = lookupIpv4Hosts) {
  const normalizedUrl = normalizeCdpUrl(rawUrl);
  if (!normalizedUrl) {
    return [];
  }

  const parsed = new URL(normalizedUrl);
  const candidates = [normalizedUrl];
  if (parsed.hostname && !net.isIP(parsed.hostname)) {
    // On Windows, host.docker.internal resolves to the machine's LAN IP (e.g. 192.168.0.x).
    // Connecting from Windows to its own LAN IP is often blocked by the OS/firewall.
    // Insert 127.0.0.1 as a high-priority fallback so the relay can be reached via loopback.
    const loopbackUrl = `${parsed.protocol}//127.0.0.1:${parsed.port}`;
    if (!candidates.includes(loopbackUrl)) {
      candidates.push(loopbackUrl);
    }
    const ipv4Addresses = await lookupIpv4(parsed.hostname);
    for (const address of ipv4Addresses) {
      const candidateUrl = `${parsed.protocol}//${address}:${parsed.port}`;
      if (!candidates.includes(candidateUrl)) {
        candidates.push(candidateUrl);
      }
    }
  }

  return candidates;
}


export function classifyCdpConnectionError(error) {
  const message = String(error?.message || error || "");
  const failedWhileFetchingWebsocket = message.includes("retrieving websocket url");
  if (failedWhileFetchingWebsocket && message.includes("ENETUNREACH")) {
    return "websocket_endpoint_fetch_failed_ipv6_unreachable";
  }
  if (failedWhileFetchingWebsocket && (message.includes("ENOTFOUND") || message.includes("EAI_AGAIN"))) {
    return "websocket_endpoint_fetch_failed_host_resolution_failed";
  }
  if (failedWhileFetchingWebsocket && (
    message.includes("socket hang up")
    || message.includes("Internal Server Error")
    || message.includes("Remote end closed connection without response")
  )) {
    return "websocket_endpoint_fetch_failed_transport_closed";
  }
  if (failedWhileFetchingWebsocket) {
    return "websocket_endpoint_fetch_failed";
  }
  if (message.includes("ENETUNREACH")) {
    return "ipv6_unreachable";
  }
  if (message.includes("ECONNREFUSED")) {
    return "connection_refused";
  }
  if (message.includes("ENOTFOUND") || message.includes("EAI_AGAIN")) {
    return "host_resolution_failed";
  }
  return "unknown_connect_error";
}

export async function connectOverCdpWithFallback(playwrightChromium, rawUrl, logger = console) {
  const normalizedUrl = normalizeCdpUrl(rawUrl);
  if (!normalizedUrl) {
    throw new Error("CDP URL is empty.");
  }

  const attempts = await resolveCdpEndpointCandidates(normalizedUrl);
  const requestedHost = new URL(normalizedUrl).hostname;
  let lastError = null;

  for (let index = 0; index < attempts.length; index += 1) {
    const candidateUrl = attempts[index];
    const attemptLabel = `${index + 1}/${attempts.length}`;
    logger.log(`Connecting to host browser via CDP [${attemptLabel}]: ${candidateUrl} (requested: ${normalizedUrl})`);
    try {
      const browser = await playwrightChromium.connectOverCDP(candidateUrl);
      return {
        browser,
        requestedUrl: normalizedUrl,
        connectedUrl: candidateUrl,
        attemptedUrls: attempts,
      };
    } catch (error) {
      lastError = error;
      const errorType = classifyCdpConnectionError(error);
      logger.warn(`CDP connection failed [${errorType}] for ${candidateUrl}: ${error.message}`);
      if (index === attempts.length - 1) {
        break;
      }
    }
  }

  const finalErrorType = classifyCdpConnectionError(lastError);
  const finalMessage = String(lastError?.message || "");
  const connectionRefused = finalMessage.includes("ECONNREFUSED");
  const transportClosed = finalMessage.includes("socket hang up")
    || finalMessage.includes("Internal Server Error")
    || finalMessage.includes("Remote end closed connection without response");
  const guidance = requestedHost === "host.docker.internal"
    ? (
      (connectionRefused || transportClosed)
        ? "The host gateway is reachable, but Chrome DevTools is only responding on localhost or is closing non-local CDP connections. Relaunch Chrome with --remote-debugging-address=0.0.0.0 --remote-debugging-port=9222 --user-data-dir=C:\\chrome-jd-profile, then log in again and retry."
        : "Please confirm the public-task-worker container has host.docker.internal mapped to the host gateway and Chrome is running with --remote-debugging-address=0.0.0.0 --remote-debugging-port=9222."
    )
    : "Please confirm the CDP address is reachable and Chrome is running with --remote-debugging-address=0.0.0.0 --remote-debugging-port=9222.";
  throw new Error(
    `Unable to connect to CDP browser. Requested: ${normalizedUrl}. Attempted: ${attempts.join(", ")}. `
    + `Final error [${finalErrorType}]: ${lastError?.message || "unknown error"}. ${guidance}`,
    { cause: lastError },
  );
}

function firstNonEmpty(values) {
  for (const value of values) {
    if (value && String(value).trim()) {
      return String(value).trim();
    }
  }
  return "";
}

async function lookupIpv4Hosts(hostname) {
  try {
    const records = await dns.lookup(hostname, { family: 4, all: true });
    return Array.from(new Set(records.map((record) => record.address).filter(Boolean)));
  } catch {
    return [];
  }
}
