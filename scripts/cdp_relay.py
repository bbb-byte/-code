"""
CDP HTTP-aware relay for Docker environments.

Problem: Chrome's CDP endpoint serves a JSON response containing
  "webSocketDebuggerUrl": "ws://localhost:9222/..."
When Playwright (running inside Docker) receives this URL, it tries to
connect to localhost:9222 *inside the container*, which fails.

This relay:
  1. Listens on 0.0.0.0:9223 (reachable from Docker via host.docker.internal)
  2. Rewrites the HTTP "Host" header to "localhost:9222" (Chrome security check)
  3. Rewrites ws://localhost:9222 and ws://127.0.0.1:9222 in CDP JSON
     responses to ws://host.docker.internal:9223 so Playwright re-connects
     through this relay.
  4. For WebSocket Upgrade requests: tunnels bidirectionally after the
     HTTP upgrade handshake (no body rewriting needed at this stage).
"""

import asyncio
import re
import sys
from pathlib import Path

LISTEN_HOST = "0.0.0.0"
LISTEN_PORT = 9223
TARGET_HOST = "127.0.0.1"
TARGET_PORT = 9222
# Public address that Docker clients use to reach this relay.
RELAY_PUBLIC_HOST = "host.docker.internal"
RELAY_HOST_HEADER = f"localhost:{TARGET_PORT}"

LOG_FILE = Path(__file__).resolve().parents[1] / "runtime" / "cdp-relay.log"


def log(message: str) -> None:
    print(message, flush=True)
    try:
        LOG_FILE.parent.mkdir(parents=True, exist_ok=True)
        with LOG_FILE.open("a", encoding="utf-8") as handle:
            handle.write(message + "\n")
    except OSError:
        pass


def rewrite_cdp_body(body: bytes) -> bytes:
    """Replace localhost/127.0.0.1 WebSocket URLs with the relay's public address."""
    try:
        text = body.decode("utf-8")
        # ws://localhost:9222  or  ws://127.0.0.1:9222  ->  ws://host.docker.internal:9223
        text = re.sub(
            r'ws://(localhost|127\.0\.0\.1):' + str(TARGET_PORT),
            f'ws://{RELAY_PUBLIC_HOST}:{LISTEN_PORT}',
            text,
        )
        # http://localhost:9222  or  http://127.0.0.1:9222  ->  http://host.docker.internal:9223
        text = re.sub(
            r'http://(localhost|127\.0\.0\.1):' + str(TARGET_PORT),
            f'http://{RELAY_PUBLIC_HOST}:{LISTEN_PORT}',
            text,
        )
        return text.encode("utf-8")
    except Exception:
        return body


async def pipe(reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
    try:
        while True:
            data = await reader.read(65536)
            if not data:
                break
            writer.write(data)
            await writer.drain()
    except (asyncio.IncompleteReadError, ConnectionResetError, BrokenPipeError, OSError):
        pass
    finally:
        try:
            writer.close()
        except Exception:
            pass


async def read_http_headers(reader: asyncio.StreamReader) -> bytes:
    """Read HTTP headers line-by-line until the blank line."""
    lines = []
    while True:
        line = await reader.readuntil(b"\r\n")
        lines.append(line)
        if line == b"\r\n":
            break
    return b"".join(lines)


async def handle_connection(
    client_reader: asyncio.StreamReader,
    client_writer: asyncio.StreamWriter,
) -> None:
    peer = client_writer.get_extra_info("peername")
    target_writer = None
    try:
        # ── 1. Read the incoming HTTP request headers ────────────────────
        raw = await read_http_headers(client_reader)
        if not raw:
            return

        headers_text = raw.decode("utf-8", errors="replace")

        # ── 2. Rewrite Host header for Chrome's DNS-rebinding protection ─
        rewritten_text = re.sub(
            r'(?m)^Host:[ \t]*.+\r\n',
            f'Host: {RELAY_HOST_HEADER}\r\n',
            headers_text,
        )

        is_websocket = bool(re.search(r'(?im)^upgrade:\s*websocket', headers_text))
        is_json_endpoint = bool(re.search(r'(?im)^GET /json', headers_text))

        log(f"[{peer}] {'WS' if is_websocket else 'HTTP'} {headers_text.splitlines()[0]}")

        # ── 3. Connect to Chrome ─────────────────────────────────────────
        target_reader, target_writer = await asyncio.open_connection(TARGET_HOST, TARGET_PORT)
        target_writer.write(rewritten_text.encode("utf-8"))
        await target_writer.drain()

        if is_websocket:
            # WebSocket: pipe remaining bytes bidirectionally
            await asyncio.gather(
                pipe(client_reader, target_writer),
                pipe(target_reader, client_writer),
                return_exceptions=True,
            )
        else:
            # ── 4. Read Chrome's HTTP response headers ───────────────────
            resp_raw = await read_http_headers(target_reader)
            resp_text = resp_raw.decode("utf-8", errors="replace")

            # ── 5. Read response body (Content-Length only) ──────────────
            cl_match = re.search(r'(?im)^Content-Length:\s*(\d+)', resp_text)
            if cl_match:
                content_length = int(cl_match.group(1))
                body = await target_reader.readexactly(content_length)

                # Rewrite CDP JSON responses so WebSocket URLs point to relay
                if is_json_endpoint:
                    body = rewrite_cdp_body(body)
                    resp_text = re.sub(
                        r'(?im)^Content-Length:\s*\d+\r\n',
                        f'Content-Length: {len(body)}\r\n',
                        resp_text,
                    )

                client_writer.write(resp_text.encode("utf-8") + body)
                await client_writer.drain()
            else:
                # Chunked / no body: send headers then pipe the rest
                client_writer.write(resp_raw)
                await client_writer.drain()
                await pipe(target_reader, client_writer)

    except (asyncio.IncompleteReadError, ConnectionResetError, BrokenPipeError, OSError) as exc:
        log(f"[{peer}] connection error: {exc}")
    except Exception as exc:
        log(f"[{peer}] unexpected error: {exc}")
    finally:
        try:
            client_writer.close()
        except Exception:
            pass
        if target_writer:
            try:
                target_writer.close()
            except Exception:
                pass


async def main() -> None:
    server = await asyncio.start_server(handle_connection, LISTEN_HOST, LISTEN_PORT)
    log(
        f"CDP relay (HTTP-aware) listening on "
        f"{LISTEN_HOST}:{LISTEN_PORT} -> {TARGET_HOST}:{TARGET_PORT}"
    )
    log(f"  Host header rewrite : * -> {RELAY_HOST_HEADER}")
    log(f"  WS URL rewrite      : ws://localhost:{TARGET_PORT} -> ws://{RELAY_PUBLIC_HOST}:{LISTEN_PORT}")
    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        log("Relay stopped.")
        sys.exit(0)
