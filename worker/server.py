import json
import os
import shutil
import subprocess
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


HOST = "0.0.0.0"
PORT = int(os.getenv("PUBLIC_TASK_WORKER_PORT", "8090"))
CONTAINER_WORKSPACE_ROOT = "/workspace"


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

            resolved_work_dir = self._resolve_host_path(work_dir)
            if not resolved_work_dir.exists():
                self._send_json(400, {"error": f"workDir does not exist: {resolved_work_dir}"})
                return

            env = os.environ.copy()
            for key, value in env_overrides.items():
                if value is not None:
                    env[str(key)] = str(value)

            resolved_command = [self._normalize_command_part(part, env) for part in command]

            try:
                completed = subprocess.run(
                    resolved_command,
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
        if explicit and not self._looks_like_invalid_windows_drive(explicit):
            return explicit
        if os.name == "nt":
            chrome_candidates = [
                Path(os.getenv("ProgramFiles", "")) / "Google/Chrome/Application/chrome.exe",
                Path(os.getenv("ProgramFiles(x86)", "")) / "Google/Chrome/Application/chrome.exe",
                Path(os.getenv("LocalAppData", "")) / "Google/Chrome/Application/chrome.exe",
            ]
            for candidate in chrome_candidates:
                if str(candidate).strip() and candidate.exists():
                    return str(candidate)
        return shutil.which("chromium") or shutil.which("google-chrome") or ""

    def _looks_like_invalid_windows_drive(self, value):
        normalized = str(value).strip()
        if len(normalized) == 1 and normalized.isalpha():
            return True
        return len(normalized) <= 3 and normalized[:1].isalpha() and normalized.endswith(":")

    def _workspace_root(self):
        configured = os.getenv("PUBLIC_TASK_WORKSPACE_ROOT", "").strip()
        if configured:
            return Path(configured).resolve()
        return Path.cwd().resolve()

    def _resolve_host_path(self, value):
        if value is None:
            return self._workspace_root()
        text = str(value).strip()
        if not text:
            return self._workspace_root()
        if text.startswith(CONTAINER_WORKSPACE_ROOT):
            relative = text[len(CONTAINER_WORKSPACE_ROOT):].lstrip("/\\")
            return (self._workspace_root() / Path(relative)).resolve()
        candidate = Path(text)
        if candidate.is_absolute():
            return candidate.resolve()
        return (self._workspace_root() / candidate).resolve()

    def _normalize_command_part(self, value, env):
        text = str(value)
        lowered = text.lower()
        if os.name == "nt":
            if lowered == "python3":
                preferred = env.get("PUBLIC_TASK_PYTHON", "").strip()
                return preferred or shutil.which("python") or "python"
            if lowered == "python.exe":
                preferred = env.get("PUBLIC_TASK_PYTHON", "").strip()
                return preferred or shutil.which("python") or "python"
        if text.startswith(CONTAINER_WORKSPACE_ROOT):
            return str(self._resolve_host_path(text))
        return text

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
