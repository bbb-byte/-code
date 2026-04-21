import json
import os
import shutil
import subprocess
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


HOST = "0.0.0.0"
PORT = 8090


class WorkerHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/health":
            self._send_json(
                200,
                {
                    "status": "ok",
                    "workspaceRoot": os.getenv("PUBLIC_TASK_WORKSPACE_ROOT", "/workspace"),
                    "python": shutil.which(os.getenv("PUBLIC_TASK_PYTHON", "python3")) or "",
                    "node": shutil.which("node") or shutil.which("nodejs") or "",
                    "browser": self._resolve_browser_binary(),
                },
            )
            return
        self._send_json(404, {"error": "not_found"})

    def do_POST(self):
        if self.path != "/execute":
            self._send_json(404, {"error": "not_found"})
            return

        try:
            payload = self._read_json()
            command = payload.get("command") or []
            work_dir = payload.get("workDir") or "/workspace"
            timeout_seconds = int(payload.get("timeoutSeconds") or 600)
            env_overrides = payload.get("env") or {}

            if not isinstance(command, list) or not command:
                self._send_json(400, {"error": "command must be a non-empty list"})
                return

            resolved_work_dir = Path(work_dir).resolve()
            if not resolved_work_dir.exists():
                self._send_json(400, {"error": f"workDir does not exist: {resolved_work_dir}"})
                return

            env = os.environ.copy()
            for key, value in env_overrides.items():
                if value is not None:
                    env[str(key)] = str(value)

            try:
                completed = subprocess.run(
                    command,
                    cwd=str(resolved_work_dir),
                    env=env,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.STDOUT,
                    text=True,
                    timeout=timeout_seconds,
                    encoding="utf-8",
                    errors="replace",
                )
                self._send_json(
                    200,
                    {
                        "exitCode": completed.returncode,
                        "finished": True,
                        "output": completed.stdout,
                    },
                )
            except subprocess.TimeoutExpired as exc:
                output = exc.stdout or ""
                if exc.stderr:
                    output += exc.stderr
                self._send_json(
                    200,
                    {
                        "exitCode": -1,
                        "finished": False,
                        "output": output,
                    },
                )
        except Exception as exc:
            self._send_json(500, {"error": str(exc)})

    def log_message(self, fmt, *args):
        return

    def _read_json(self):
        content_length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(content_length) if content_length > 0 else b"{}"
        return json.loads(raw.decode("utf-8"))

    def _resolve_browser_binary(self):
        explicit = os.getenv("PUBLIC_TASK_BROWSER_PATH", "").strip()
        if explicit:
            return explicit
        return shutil.which("chromium") or shutil.which("google-chrome") or ""

    def _send_json(self, status_code, payload):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status_code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


if __name__ == "__main__":
    server = ThreadingHTTPServer((HOST, PORT), WorkerHandler)
    server.serve_forever()
