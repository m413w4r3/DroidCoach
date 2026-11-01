#!/usr/bin/env python3
"""Isolated V7-06C Core/OpenClaw/Relay stack lifecycle."""

from __future__ import annotations

import argparse
import base64
from datetime import datetime, timezone
import ipaddress
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import signal
import socket
import subprocess
import sys
import time
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import urlopen
import uuid


REPO_ROOT = Path(__file__).resolve().parents[3]
BACKEND_ROOT = REPO_ROOT / "backend"
PC_ROOT = REPO_ROOT / "integrations" / "relay" / "pc"
SCRIPT_DIR = Path(__file__).resolve().parent
PRIVATE_BASE = Path("/tmp/atlas-v7-06c-private")
PINNED_RELAY_IMAGE = "sha256:75d68fd8bdb9d60a26203cd7c49c07c035f1f400c47740770056814e76846889"
PINNED_RELAY_TAG = "atlas-paseo-relay:r3a"
SCENARIOS = ("backend-positive", "backend-negative", "android-apply", "android-reject")
DENIED_TOOLS = [
    "presence", "session_status", "gateway", "exec", "process", "code_execution",
    "wait", "ls", "read", "write", "edit", "apply_patch",
]
CAPABILITIES = {
    "day_plan.read": True,
    "activity_status.read": True,
    "plan_revision.propose": True,
    "usage_summary.read": True,
    "goal_progress.read": True,
    "training_plan.read": True,
    "coach.chat": True,
}
PREFERRED_PORTS = {"core": 8111, "relay": 4413, "metrics": 4414, "gateway": 19076, "proxy": 19075}
LIFECYCLE_NAME = "lifecycle.json"
STACK_NAME = "stack.json"
OWNER_NAME = "owner.marker"
OWNER_ENV = "ATLAS_V7_06C_OWNER"
EXPECTED_COMMANDS = {
    "gateway": "openclaw-gateway",
    "proxy": "proxy.py",
    "core": "uvicorn atlas_backend.main:app",
    "connector": "dist/main.js start",
}
PROCESS_MARKERS = {
    "gateway": ("openclaw-gateway", "openclaw gateway run"),
    "proxy": ("proxy.py",),
    "core": ("uvicorn atlas_backend.main:app",),
    "connector": ("dist/main.js start",),
}
SENSITIVE_TEXT = [
    re.compile(r"(?i)(authorization|api[_-]?key|token|secret)(\s*[:=]\s*)[^\s,;]+"),
    re.compile(r"(?i)\bsk-[a-z0-9_-]{8,}"),
    re.compile(r"\b[A-Za-z0-9_-]{32,}\b"),
]


class StackError(RuntimeError):
    pass


class JsonArgumentParser(argparse.ArgumentParser):
    def error(self, _message: str) -> None:
        self.exit(2, json.dumps({"status": "BLOCKED", "error_code": "CLI_ARGUMENTS_INVALID"}) + "\n")


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def atomic_json(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    temporary = path.with_name(path.name + f".{uuid.uuid4().hex}.tmp")
    fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            json.dump(value, stream, ensure_ascii=True, sort_keys=True, indent=2)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.chmod(temporary, 0o600)
        os.replace(temporary, path)
        os.chmod(path, 0o600)
    except Exception:
        try:
            temporary.unlink()
        except FileNotFoundError:
            pass
        raise


def write_private(path: Path, value: str | bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    data = value.encode("utf-8") if isinstance(value, str) else value
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
    except Exception:
        try:
            path.unlink()
        except FileNotFoundError:
            pass
        raise
    os.chmod(path, 0o600)


def append_redacted_log(path: Path, message: str) -> None:
    fd = os.open(path, os.O_WRONLY | os.O_APPEND | os.O_CREAT, 0o600)
    try:
        os.write(fd, f"{utc_now()} {message}\n".encode("utf-8"))
        os.fsync(fd)
    finally:
        os.close(fd)


def make_token() -> str:
    return base64.urlsafe_b64encode(secrets.token_bytes(32)).decode("ascii").rstrip("=")


def validate_android_relay_url(value: str) -> str:
    if not isinstance(value, str) or "?" in value or "#" in value:
        raise StackError("invalid Android Relay URL")
    try:
        parsed = urlsplit(value)
        hostname = parsed.hostname
        port = parsed.port
    except (TypeError, ValueError):
        raise StackError("invalid Android Relay URL") from None
    if (
        parsed.scheme not in {"ws", "wss"}
        or not hostname
        or parsed.username is not None
        or parsed.password is not None
        or parsed.query
        or parsed.fragment
        or parsed.netloc.endswith(":")
        or (port is not None and not 1 <= port <= 65535)
    ):
        raise StackError("invalid Android Relay URL")
    try:
        ipaddress.ip_address(hostname)
        valid_host = True
    except ValueError:
        try:
            ascii_host = hostname.encode("idna").decode("ascii")
        except UnicodeError:
            ascii_host = ""
        label = r"[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?"
        valid_host = bool(re.fullmatch(rf"{label}(?:\.{label})*\.?", ascii_host)) and len(ascii_host) <= 254
    if not valid_host:
        raise StackError("invalid Android Relay host")
    if parsed.scheme == "ws":
        try:
            is_loopback = ipaddress.ip_address(hostname).is_loopback
        except ValueError:
            is_loopback = hostname.lower() == "localhost"
        if not is_loopback:
            raise StackError("ws Android Relay URL must use a loopback host")
    else:
        try:
            public_host = ipaddress.ip_address(hostname).is_global
        except ValueError:
            normalized_host = hostname.rstrip(".").lower()
            public_host = not normalized_host.endswith((".localhost", ".local", ".internal")) and normalized_host != "localhost"
        if not public_host:
            raise StackError("public wss Android Relay URL must not use a local/private host")
    return value


def validate_android_relay_host(value: str) -> str:
    if not isinstance(value, str) or not re.fullmatch(r"(?:[A-Za-z0-9.-]+|\[[0-9A-Fa-f:]+\])", value):
        raise StackError("invalid Android Relay host")
    normalized = value[1:-1] if value.startswith("[") and value.endswith("]") else value
    try:
        is_loopback = ipaddress.ip_address(normalized).is_loopback
    except ValueError:
        is_loopback = normalized.lower() == "localhost"
    if not is_loopback and normalized != "10.0.2.2":
        raise StackError("Android Relay host must be loopback or emulator bridge 10.0.2.2")
    return value


def ensure_private_namespace(base: Path = PRIVATE_BASE) -> Path:
    if base.is_symlink():
        raise StackError("private namespace is a symlink; refusing lifecycle action")
    if not base.exists():
        base.mkdir(mode=0o700)
    metadata = base.stat()
    if not base.is_dir() or metadata.st_uid != os.getuid():
        raise StackError("private namespace owner or type mismatch")
    if metadata.st_mode & 0o777 != 0o700:
        raise StackError("private namespace permissions must be 0700")
    return base.resolve(strict=True)


def resolve_core_python() -> str:
    override = os.environ.get("ATLAS_V7_06C_PYTHON")
    candidates = [override] if override else []
    venv_python = BACKEND_ROOT / ".venv" / "bin" / "python"
    if venv_python.is_file() and os.access(venv_python, os.X_OK):
        candidates.append(str(venv_python))
    candidates.append(sys.executable)
    seen: set[str] = set()
    for candidate in candidates:
        if not candidate or candidate in seen:
            continue
        seen.add(candidate)
        try:
            checked = run_capture(
                [candidate, "-B", "-c", "import fastapi, uvicorn, atlas_backend"],
                cwd=BACKEND_ROOT,
                timeout=20,
            )
        except StackError:
            if override:
                raise StackError("BLOCKED_CORE_PYTHON_IMPORTS") from None
            continue
        if checked.returncode == 0:
            return candidate
        if override:
            raise StackError("BLOCKED_CORE_PYTHON_IMPORTS")
    raise StackError("BLOCKED_CORE_PYTHON_IMPORTS")


def preflight() -> None:
    if not os.environ.get("DEEPSEEK_API_KEY", "").strip():
        raise StackError("BLOCKED_CREDENTIALS")
    for command in ("openclaw", "docker", "node", "npm"):
        if shutil.which(command) is None:
            raise StackError(f"BLOCKED_MISSING_{command.upper()}")
    image = run_capture(["docker", "image", "inspect", PINNED_RELAY_TAG, "--format", "{{.Id}}"])
    if image.returncode != 0 or image.stdout.strip() != PINNED_RELAY_IMAGE:
        raise StackError("BLOCKED_RELAY_IMAGE_DIGEST")
    resolve_core_python()


def port_is_free(port: int) -> bool:
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        sock.bind(("127.0.0.1", port))
        return True
    except OSError:
        return False
    finally:
        sock.close()


def validate_relay_port(port: int | None) -> int | None:
    if port is None:
        return None
    if isinstance(port, bool) or not isinstance(port, int) or not 1 <= port <= 65535:
        raise StackError("BLOCKED_RELAY_PORT")
    return port


def choose_ports(relay_port: int | None = None) -> dict[str, int]:
    relay_port = validate_relay_port(relay_port)
    used: set[int] = set()
    ports: dict[str, int] = {}
    if relay_port is not None:
        if not port_is_free(relay_port):
            raise StackError("BLOCKED_RELAY_PORT")
        ports["relay"] = relay_port
        used.add(relay_port)
    for service, preferred in PREFERRED_PORTS.items():
        if service == "relay" and relay_port is not None:
            continue
        candidates = [preferred, *range(preferred + 1, preferred + 100)]
        chosen = next((port for port in candidates if port not in used and port_is_free(port)), None)
        if chosen is None:
            raise StackError(f"no free loopback port for {service}")
        used.add(chosen)
        ports[service] = chosen
    return ports


def run_capture(args: list[str], *, env: dict[str, str] | None = None, cwd: Path | None = None,
                timeout: float = 20) -> subprocess.CompletedProcess[str]:
    try:
        return subprocess.run(
            args, cwd=cwd, env=env, stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=timeout, check=False,
        )
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise StackError(f"command unavailable: {Path(args[0]).name}") from exc


def wait_http(url: str, process: subprocess.Popen | None = None, *, timeout: float = 60,
              service: str = "service") -> dict | None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if process is not None and process.poll() is not None:
            raise StackError(f"owned {service} process exited before health became ready")
        try:
            with urlopen(url, timeout=1) as response:
                raw = response.read(256_000)
                if 200 <= response.status < 300:
                    try:
                        decoded = json.loads(raw)
                        return decoded if isinstance(decoded, dict) else None
                    except (UnicodeError, json.JSONDecodeError):
                        return None
        except (HTTPError, URLError, TimeoutError, OSError):
            time.sleep(0.2)
    raise StackError("service health endpoint did not become ready")


def read_proc_identity(pid: int) -> tuple[set[str], str] | None:
    try:
        with open(f"/proc/{pid}/environ", "rb") as stream:
            environment = set(stream.read().split(b"\0"))
        with open(f"/proc/{pid}/cmdline", "rb") as stream:
            command = stream.read().replace(b"\0", b" ").decode("utf-8", errors="replace").strip()
        return {item.decode("utf-8", errors="replace") for item in environment if item}, command
    except (FileNotFoundError, PermissionError, ProcessLookupError):
        return None


def verify_process_record(record: dict[str, Any], marker: str) -> tuple[int, int, str] | None:
    pid = record.get("pid")
    pgid = record.get("pgid")
    name = record.get("name")
    if not isinstance(pid, int) or not isinstance(pgid, int) or name not in EXPECTED_COMMANDS:
        raise StackError("invalid process record; refusing to signal")
    identity = read_proc_identity(pid)
    if identity is None:
        return None
    environment, command = identity
    command_ok = any(signature in command for signature in PROCESS_MARKERS[name])
    if f"{OWNER_ENV}={marker}" not in environment or not command_ok:
        raise StackError(f"process identity mismatch for {name}; refusing to signal")
    if os.getpgid(pid) != pgid or pgid != pid:
        raise StackError(f"process group mismatch for {name}; refusing to signal")
    return pid, pgid, name


def terminate_record(record: dict[str, Any], marker: str) -> str:
    verified = verify_process_record(record, marker)
    if verified is None:
        return "already_stopped"
    pid, pgid, name = verified
    try:
        os.killpg(pgid, signal.SIGTERM)
    except ProcessLookupError:
        return "already_stopped"
    deadline = time.monotonic() + 8
    while time.monotonic() < deadline:
        try:
            os.kill(pid, 0)
        except ProcessLookupError:
            return "stopped"
        time.sleep(0.05)
    identity_after = read_proc_identity(pid)
    if identity_after is not None:
        env_after, command_after = identity_after
        command_ok = any(signature in command_after for signature in PROCESS_MARKERS[name])
        if f"{OWNER_ENV}={marker}" in env_after and command_ok:
            os.killpg(pgid, signal.SIGKILL)
            return "killed_after_timeout"
    return "stopped"


class Stack:
    def __init__(self, root: Path, scenario: str, *, android_relay_host: str = "10.0.2.2",
                 android_relay_url: str | None = None, relay_port: int | None = None):
        self.root = root
        self.scenario = scenario
        self.android_relay_host = validate_android_relay_host(android_relay_host)
        self.android_relay_url = validate_android_relay_url(android_relay_url) if android_relay_url is not None else None
        self.relay_port = validate_relay_port(relay_port)
        self.marker = secrets.token_hex(24)
        self.processes: dict[str, subprocess.Popen] = {}
        self.stderr_paths: dict[str, Path] = {}
        self.lifecycle: dict[str, Any] = {
            "schema": 1,
            "scenario": scenario,
            "private_root": str(root),
            "marker": self.marker,
            "relay_container": None,
            "relay_image_id": PINNED_RELAY_IMAGE,
            "processes": {},
            "created_at": utc_now(),
        }
        self.stack: dict[str, Any] = {}
        self.catalog_candidates: int | None = None

    def start(self) -> dict[str, Any]:
        ports = choose_ports(self.relay_port)
        preflight()
        previous_umask = os.umask(0o077)
        try:
            self._create_root()
            self._record_lifecycle()
            self._write_runtime_files(ports)
            self._start_relay_container(ports["relay"])
            self._start_gateway(ports["gateway"])
            self._verify_gateway(ports["gateway"])
            self._start_proxy(ports["proxy"], ports["gateway"])
            self._start_core(ports["core"], ports["proxy"])
            self._start_connector(ports["relay"], ports["metrics"], ports["core"])
            self._pair_device()
            self._finalize_stack_json()
            return self.stack
        except Exception:
            try:
                if self.root.exists():
                    if (self.root / LIFECYCLE_NAME).exists():
                        self.stop(remove_root=True)
                    else:
                        owner = self.root / OWNER_NAME
                        if owner.is_file() and owner.read_text(encoding="utf-8").strip() == self.marker:
                            shutil.rmtree(self.root)
            except Exception:
                pass
            raise
        finally:
            os.umask(previous_umask)

    def _create_root(self) -> None:
        namespace = ensure_private_namespace()
        if self.root.parent.resolve(strict=False) != namespace:
            raise StackError("private root must be a direct child of the V7-06C temporary namespace")
        if self.root.exists():
            raise StackError("private root already exists; use its existing stack.json or stop it first")
        self.root.mkdir(mode=0o700)
        os.chmod(self.root, 0o700)
        write_private(self.root / OWNER_NAME, self.marker + "\n")
        for name in (
            "home", "tmp", "openclaw", "openclaw/state", "openclaw/workspace",
            "openclaw/xdg-config", "openclaw/xdg-data", "openclaw/xdg-cache",
            "connector", "logs",
        ):
            path = self.root / name
            path.mkdir(parents=True, exist_ok=True, mode=0o700)
            os.chmod(path, 0o700)

    def _record_lifecycle(self) -> None:
        atomic_json(self.root / LIFECYCLE_NAME, self.lifecycle)

    def _write_runtime_files(self, ports: dict[str, int]) -> None:
        self.ports = ports
        self.gateway_token = make_token()
        self.core_token = make_token()
        self.tool_token = make_token()
        if len({self.gateway_token, self.core_token, self.tool_token}) != 3:
            raise StackError("synthetic bearer tokens were not distinct")
        self.gateway_token_file = self.root / "openclaw" / "gateway-token"
        self.core_token_file = self.root / "core-token"
        self.tool_token_file = self.root / "tool-token"
        self.pairing_file = self.root / "pairing.json"
        self.trace_path = self.root / "openclaw-proxy.jsonl"
        self.connector_state_dir = self.root / "connector"
        self.gateway_log = self.root / "logs" / "gateway.redacted.log"
        self.proxy_log = self.root / "logs" / "proxy.redacted.log"
        self.core_log = self.root / "logs" / "core.redacted.log"
        self.connector_log = self.root / "logs" / "connector.redacted.log"
        self.core_db = self.root / "atlas.sqlite3"
        write_private(self.gateway_token_file, self.gateway_token + "\n")
        write_private(self.core_token_file, self.core_token + "\n")
        write_private(self.tool_token_file, self.tool_token + "\n")
        write_private(self.trace_path, b"")
        for log in (self.gateway_log, self.proxy_log, self.core_log, self.connector_log):
            write_private(log, b"")
        self.agent_id = f"v706c_{self.scenario}_{os.getpid()}_{secrets.token_hex(3)}"
        self.server_id = f"v7-06c-{self.scenario}-{uuid.uuid4()}"
        self.relay_container = f"atlas-v7-06c-{self.scenario}-{os.getpid()}-{secrets.token_hex(2)}"
        self.openclaw_state_dir = self.root / "openclaw" / "state"
        self.openclaw_config_path = self.root / "openclaw" / "openclaw.json"
        gateway_config = {
            "gateway": {
                "mode": "local",
                "bind": "loopback",
                "port": ports["gateway"],
                "auth": {"mode": "token"},
                "http": {"endpoints": {"chatCompletions": {"enabled": True}}},
            },
            "models": {
                "mode": "merge",
                "providers": {
                    "deepseek": {
                        "baseUrl": "https://api.deepseek.com",
                        "api": "openai-completions",
                        "auth": "api-key",
                        "apiKey": {"source": "env", "provider": "deepseek", "id": "DEEPSEEK_API_KEY"},
                        "models": [{
                            "id": "deepseek-chat",
                            "name": "deepseek-chat",
                            "contextWindow": 65536,
                            "maxTokens": 8192,
                        }],
                    }
                },
            },
            "secrets": {"providers": {"deepseek": {"source": "env", "allowlist": ["DEEPSEEK_API_KEY"]}}},
            "agents": {
                "defaults": {
                    "workspace": str(self.root / "openclaw" / "workspace"),
                    "model": {"primary": "deepseek/deepseek-chat"},
                },
                "entries": {
                    self.agent_id: {
                        "name": "ATLAS V7-06C isolated",
                        "workspace": str(self.root / "openclaw" / "workspace"),
                        "identity": {"name": "ATLAS V7-06C isolated"},
                        "tools": {"profile": "minimal", "deny": DENIED_TOOLS,
                                  "codeMode": {"enabled": False}},
                    },
                },
            },
            "tools": {"profile": "minimal", "deny": DENIED_TOOLS,
                      "codeMode": {"enabled": False}},
        }
        atomic_json(self.openclaw_config_path, gateway_config)
        os.chmod(self.openclaw_config_path, 0o600)
        self.stack = {
            "private_root": str(self.root),
            "scenario": self.scenario,
            "core_url": f"http://127.0.0.1:{ports['core']}",
            "core_db": str(self.core_db),
            "core_token_file": str(self.core_token_file),
            "tool_token_file": str(self.tool_token_file),
            "relay_url_host": f"ws://127.0.0.1:{ports['relay']}",
            "relay_url_android": self.android_relay_url or f"ws://{self.android_relay_host}:{ports['relay']}",
            "relay_transport": "public_wss" if self.android_relay_url and self.android_relay_url.startswith("wss://") else "loopback_ws",
            "server_id": self.server_id,
            "server_public_key": None,
            "pairing_file": str(self.pairing_file),
            "connector_state_dir": str(self.connector_state_dir),
            "relay_container": self.relay_container,
            "proxy_trace": str(self.trace_path),
            "gateway_url": f"http://127.0.0.1:{ports['gateway']}",
            "gateway_agent": self.agent_id,
            "gateway_log": str(self.gateway_log),
            "gateway_tool_policy": "minimal+explicit-deny",
            "gateway_native_plugin": False,
            "pids": {},
            "owned_files": [],
            "relay_image_id": PINNED_RELAY_IMAGE,
            "ports": ports,
        }

    def _base_env(self) -> dict[str, str]:
        allowed_names = {
            "PATH", "LANG", "LC_ALL", "LC_CTYPE", "TZ",
            "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "NO_PROXY",
            "SSL_CERT_FILE", "SSL_CERT_DIR", "REQUESTS_CA_BUNDLE",
        }
        env = {key: value for key, value in os.environ.items()
               if key in allowed_names or key.startswith("LC_")}
        env[OWNER_ENV] = self.marker
        env["HOME"] = str(self.root / "home")
        env["TMPDIR"] = str(self.root / "tmp")
        env["XDG_CONFIG_HOME"] = str(self.root / "openclaw" / "xdg-config")
        env["XDG_DATA_HOME"] = str(self.root / "openclaw" / "xdg-data")
        env["XDG_STATE_HOME"] = str(self.root / "openclaw" / "state")
        env["XDG_CACHE_HOME"] = str(self.root / "openclaw" / "xdg-cache")
        env["PYTHONUNBUFFERED"] = "1"
        env["PYTHONDONTWRITEBYTECODE"] = "1"
        return env

    def _gateway_env(self) -> dict[str, str]:
        env = self._base_env()
        env.update({
            "OPENCLAW_STATE_DIR": str(self.openclaw_state_dir),
            "OPENCLAW_CONFIG_PATH": str(self.openclaw_config_path),
            "OPENCLAW_GATEWAY_TOKEN": self.gateway_token,
            "OPENCLAW_AGENT_ID": self.agent_id,
            "DEEPSEEK_API_KEY": os.environ.get("DEEPSEEK_API_KEY", ""),
        })
        return env

    def _spawn(self, name: str, args: list[str], env: dict[str, str], cwd: Path, log: Path) -> subprocess.Popen:
        stderr_path = log.with_name(f"{log.stem}.stderr.log")
        stderr_fd = os.open(stderr_path, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
        try:
            process = subprocess.Popen(
                args,
                cwd=cwd,
                env=env,
                stdin=subprocess.DEVNULL,
                stdout=subprocess.DEVNULL,
                stderr=stderr_fd,
                start_new_session=True,
                close_fds=True,
            )
        finally:
            os.close(stderr_fd)
        self.processes[name] = process
        self.stderr_paths[name] = stderr_path
        record = {
            "name": name,
            "pid": process.pid,
            "pgid": process.pid,
            "command_signature": EXPECTED_COMMANDS[name],
            "log": str(log),
            "stderr_log": str(stderr_path),
            "started_at": utc_now(),
        }
        self.lifecycle["processes"][name] = record
        self.stack.setdefault("pids", {})[name] = process.pid
        self._record_lifecycle()
        append_redacted_log(log, f"{name} process started pid={process.pid}")
        return process

    def _read_stderr_tail(self, name: str) -> str:
        path = self.stderr_paths.get(name)
        if path is None or not path.is_file():
            return "stderr_line=unavailable"
        try:
            raw = path.read_bytes()[-8192:].decode("utf-8", errors="replace")
        except OSError:
            return "stderr_line=unavailable"
        lines = [self._sanitize_stderr_line(line) for line in raw.splitlines() if line.strip()]
        return lines[-1] if lines else "stderr_line=unavailable"

    @staticmethod
    def _sanitize_stderr_line(line: str) -> str:
        line = line.replace("\x1b", "")
        line = " ".join(line.split())
        for pattern in SENSITIVE_TEXT:
            line = pattern.sub("[redacted]", line)
        return line[:240]

    def _record_startup_failure(self, name: str, process: subprocess.Popen, log: Path) -> None:
        code = process.poll()
        detail = self._read_stderr_tail(name)
        append_redacted_log(log, f"{name} startup failure exit_code={code} stderr={detail}")

    def _start_relay_container(self, port: int) -> None:
        image = run_capture(["docker", "image", "inspect", PINNED_RELAY_TAG, "--format", "{{.Id}}"])
        if image.returncode != 0 or image.stdout.strip() != PINNED_RELAY_IMAGE:
            raise StackError("local Relay image does not match the required immutable digest")
        command = [
            "docker", "run", "--detach", "--name", self.relay_container,
            "--label", f"atlas.v7-06c.owner={self.marker}",
            "--restart=no", "--publish", f"127.0.0.1:{port}:4000/tcp", PINNED_RELAY_IMAGE,
        ]
        created = run_capture(command, timeout=60)
        if created.returncode != 0:
            raise StackError("could not start isolated pinned Relay container")
        self.lifecycle["relay_container"] = self.relay_container
        self._record_lifecycle()
        inspect = run_capture([
            "docker", "inspect", "--format", '{{.Image}}|{{index .Config.Labels "atlas.v7-06c.owner"}}',
            self.relay_container,
        ])
        expected = f"{PINNED_RELAY_IMAGE}|{self.marker}"
        if inspect.returncode != 0 or inspect.stdout.strip() != expected:
            raise StackError("started Relay container failed image/owner identity verification")
        if self.stack:
            self.stack["relay_container"] = self.relay_container
        wait_http(f"http://127.0.0.1:{port}/health", timeout=40)

    def _start_gateway(self, port: int) -> None:
        process = self._spawn("gateway", [
            "openclaw", "gateway", "run", "--port", str(port), "--bind", "loopback",
            "--auth", "token", "--ws-log", "compact",
        ], self._gateway_env(), self.root, self.gateway_log)
        try:
            wait_http(f"http://127.0.0.1:{port}/health", process, service="gateway")
        except StackError:
            self._record_startup_failure("gateway", process, self.gateway_log)
            raise
        append_redacted_log(self.gateway_log, "health endpoint ready")

    def _verify_gateway(self, port: int) -> None:
        env = self._gateway_env()
        models = run_capture(["openclaw", "models", "list"], env=env)
        if models.returncode != 0:
            raise StackError("OpenClaw model inventory command failed")
        inventory = re.sub(r"\x1b\[[0-9;]*m", "", models.stdout + "\n" + models.stderr).lower()
        normalized = re.sub(r"[^a-z0-9]+", " ", inventory)
        model_visible = "deepseek/deepseek-chat" in inventory
        auth_yes_visible = re.search(r"\bauth\b.{0,80}\byes\b", normalized) is not None
        if not model_visible:
            raise StackError("OpenClaw global model inventory did not list deepseek/deepseek-chat")
        self.stack["gateway_model_inventory"] = "deepseek/deepseek-chat"
        self.stack["gateway_auth_inventory"] = "yes" if auth_yes_visible else "not_parsed"

        try:
            config = json.loads(self.openclaw_config_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            raise StackError("isolated OpenClaw config could not be re-read") from exc
        agents = config.get("agents") if isinstance(config, dict) else None
        entries = agents.get("entries") if isinstance(agents, dict) else None
        entry = entries.get(self.agent_id) if isinstance(entries, dict) else None
        agent_tools = entry.get("tools") if isinstance(entry, dict) else None
        global_tools = config.get("tools") if isinstance(config, dict) else None
        plugins = config.get("plugins", {}) if isinstance(config, dict) else {}
        plugin_text = json.dumps(plugins, sort_keys=True).lower()
        if (
            not isinstance(agent_tools, dict)
            or agent_tools.get("profile") != "minimal"
            or not set(DENIED_TOOLS).issubset(set(agent_tools.get("deny", [])))
            or agent_tools.get("codeMode", {}).get("enabled") is not False
            or not isinstance(global_tools, dict)
            or global_tools.get("profile") != "minimal"
            or not set(DENIED_TOOLS).issubset(set(global_tools.get("deny", [])))
            or global_tools.get("codeMode", {}).get("enabled") is not False
            or "atlas_core_tools" in plugin_text
        ):
            raise StackError("isolated agent tool policy or plugin exclusion is invalid")

        catalog = run_capture([
            "openclaw", "gateway", "call", "--port", str(port), "--json", "tools.catalog",
            "--params", json.dumps({"agentId": self.agent_id}, separators=(",", ":")),
        ], env=env, timeout=20)
        catalog_state = "unavailable"
        if catalog.returncode == 0:
            try:
                catalog_data = json.loads(catalog.stdout)
                self.catalog_candidates = self._catalog_candidate_count(catalog_data)
                catalog_state = f"candidate_groups={self.catalog_candidates}" if self.catalog_candidates is not None else "candidate_shape=unknown"
            except json.JSONDecodeError:
                catalog_state = "candidate_response=non_json"
        append_redacted_log(
            self.gateway_log,
            "model deepseek/deepseek-chat inventory visible; auth_inventory=" +
            ("yes" if auth_yes_visible else "not_parsed; runtime call required") +
            "; policy=minimal+deny; "
            "atlas_core_tools absent; tools.catalog is candidate-only " + catalog_state,
        )
        self.stack["tools_catalog_candidates"] = self.catalog_candidates

    @staticmethod
    def _find_deepseek_auth(value: Any) -> bool:
        if isinstance(value, dict):
            label = " ".join(str(value.get(key, "")) for key in ("id", "model", "name", "modelId")).lower()
            auth = value.get("auth")
            auth_ok = auth is True or (isinstance(auth, str) and auth.lower() in {"yes", "ok", "configured"})
            if "deepseek-chat" in label and auth_ok:
                return True
            return any(Stack._find_deepseek_auth(item) for item in value.values())
        if isinstance(value, list):
            return any(Stack._find_deepseek_auth(item) for item in value)
        return False

    @staticmethod
    def _catalog_candidate_count(value: Any) -> int | None:
        if isinstance(value, dict):
            groups = value.get("groups")
            if isinstance(groups, list):
                return len(groups)
            if isinstance(groups, dict):
                return len(groups)
            for key in ("result", "data", "catalog"):
                if key in value:
                    found = Stack._catalog_candidate_count(value[key])
                    if found is not None:
                        return found
        return None

    def _start_proxy(self, port: int, gateway_port: int) -> None:
        process = self._spawn("proxy", [
            sys.executable, str(SCRIPT_DIR / "proxy.py"), "--port", str(port),
            "--target", f"http://127.0.0.1:{gateway_port}", "--trace", str(self.trace_path),
        ], self._base_env(), self.root, self.proxy_log)
        try:
            wait_http(f"http://127.0.0.1:{port}/health", process, service="proxy")
        except StackError:
            self._record_startup_failure("proxy", process, self.proxy_log)
            raise
        append_redacted_log(self.proxy_log, "loopback proxy health ready")

    def _start_core(self, port: int, proxy_port: int) -> None:
        core_python = self._resolve_core_python()
        env = self._base_env()
        env.update({
            "ATLAS_AGENT_ADAPTER": "openclaw",
            "OPENCLAW_BASE_URL": f"http://127.0.0.1:{proxy_port}",
            "OPENCLAW_GATEWAY_TOKEN_FILE": str(self.gateway_token_file),
            "OPENCLAW_AGENT_ID": self.agent_id,
            "OPENCLAW_TIMEOUT_S": "60",
            "ATLAS_TOKEN": self.core_token,
            "ATLAS_TOOL_TOKEN": self.tool_token,
            "ATLAS_V4_CAPABILITIES_JSON": json.dumps(CAPABILITIES, separators=(",", ":")),
            "ATLAS_DB_PATH": str(self.core_db),
            "ATLAS_PORT": str(port),
        })
        process = self._spawn("core", [
            core_python, "-B", "-m", "uvicorn", "atlas_backend.main:app", "--host", "127.0.0.1",
            "--port", str(port), "--no-access-log",
        ], env, BACKEND_ROOT, self.core_log)
        try:
            wait_http(f"http://127.0.0.1:{port}/health", process, service="core")
        except StackError:
            self._record_startup_failure("core", process, self.core_log)
            raise
        append_redacted_log(self.core_log, "Core health endpoint ready; adapter=openclaw")

    def _resolve_core_python(self) -> str:
        return resolve_core_python()

    def _connector_env(self, relay_port: int, metrics_port: int, core_port: int) -> dict[str, str]:
        env = self._base_env()
        env.update({
            "ATLAS_RELAY_STATE_DIR": str(self.connector_state_dir),
            "ATLAS_RELAY_URL": f"ws://127.0.0.1:{relay_port}",
            "ATLAS_ALLOW_LOOPBACK_WS": "1",
            "ATLAS_RELAY_SERVER_ID": self.server_id,
            "ATLAS_BACKEND_URL": f"http://127.0.0.1:{core_port}",
            "ATLAS_SERVICE_TOKEN": self.tool_token,
            "ATLAS_V4_ENABLED": "1",
            "ATLAS_V4_ANDROID_TOKEN_FILE": str(self.core_token_file),
            "ATLAS_METRICS_PORT": str(metrics_port),
            "ATLAS_DIAG_TRACE": "1",
        })
        return env

    def _start_connector(self, relay_port: int, metrics_port: int, core_port: int) -> None:
        build = run_capture(["npm", "run", "build"], cwd=PC_ROOT, env=self._base_env(), timeout=240)
        if build.returncode != 0:
            raise StackError("BLOCKED_CONNECTOR_BUILD")
        env = self._connector_env(relay_port, metrics_port, core_port)
        process = self._spawn("connector", ["node", "dist/main.js", "start"], env, PC_ROOT, self.connector_log)
        metrics_url = f"http://127.0.0.1:{metrics_port}/metrics"
        deadline = time.monotonic() + 45
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise StackError("PC connector exited before relay_connected=true")
            try:
                with urlopen(metrics_url, timeout=1) as response:
                    status = json.loads(response.read(4096))
                if isinstance(status, dict) and status.get("relay_connected") is True:
                    append_redacted_log(self.connector_log, "metrics relay_connected=true")
                    return
            except (HTTPError, URLError, TimeoutError, OSError, json.JSONDecodeError):
                time.sleep(0.2)
        raise StackError("connector metrics did not report relay_connected=true")

    def _pair_device(self) -> None:
        label = f"v7-06c-{self.scenario}"
        result = run_capture([
            "node", "dist/main.js", "pair-device", "--label", label,
            "--token-file", str(self.pairing_file),
        ], env=self._connector_env(self.ports["relay"], self.ports["metrics"], self.ports["core"]), cwd=PC_ROOT)
        if result.returncode != 0:
            raise StackError("could not pair a fresh relay device")
        try:
            pairing = json.loads(self.pairing_file.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            raise StackError("pairing file was not created as JSON") from exc
        if not isinstance(pairing, dict) or not isinstance(pairing.get("device_id"), str):
            raise StackError("pairing file lacks a device id")
        mode = self.pairing_file.stat().st_mode & 0o777
        if mode != 0o600:
            raise StackError("pairing file permissions are not 0600")
        try:
            identity = json.loads((self.connector_state_dir / "identity.json").read_text(encoding="utf-8"))
            self.stack["server_public_key"] = identity["public_key"]
        except (OSError, KeyError, json.JSONDecodeError, TypeError) as exc:
            raise StackError("connector identity public key is unavailable") from exc

    def _finalize_stack_json(self) -> None:
        created_paths = [path for path in self.root.rglob("*") if path.is_file()]
        self.stack["owned_files"] = sorted({str(path) for path in created_paths} | {str(self.root / STACK_NAME)})
        self.stack["pids"] = {name: process.pid for name, process in self.processes.items()}
        encoded = json.dumps(self.stack, sort_keys=True)
        for secret in (self.gateway_token, self.core_token, self.tool_token):
            if secret in encoded:
                raise StackError("secret value would be exposed in stack.json")
        atomic_json(self.root / STACK_NAME, self.stack)
        self.lifecycle["ready_at"] = utc_now()
        self._record_lifecycle()

    def stop(self, *, remove_root: bool) -> dict[str, str]:
        results: dict[str, str] = {}
        for name in reversed(tuple(EXPECTED_COMMANDS)):
            record = self.lifecycle.get("processes", {}).get(name)
            if record:
                results[name] = terminate_record(record, self.marker)
        container = self.lifecycle.get("relay_container")
        if container:
            results["relay"] = stop_owned_container(container, self.marker, PINNED_RELAY_IMAGE)
        if remove_root:
            verified_root = load_and_verify_root(self.root, expected_marker=self.marker, missing_ok=True)
            if verified_root:
                shutil.rmtree(self.root)
                results["private_root"] = "removed"
            else:
                results["private_root"] = "already_removed"
        return results


def stop_owned_container(name: str, marker: str, expected_image: str) -> str:
    if not name.startswith("atlas-v7-06c-") or any(char not in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_." for char in name):
        raise StackError("container name is outside the V7-06C namespace; refusing to stop")
    inspect = run_capture([
        "docker", "inspect", "--format", '{{.Image}}|{{index .Config.Labels "atlas.v7-06c.owner"}}', name,
    ])
    if inspect.returncode != 0:
        listing = run_capture(["docker", "ps", "-aq", "--filter", f"name=^{name}$"])
        if listing.returncode != 0:
            raise StackError("Docker identity lookup failed; refusing cleanup")
        if not listing.stdout.strip():
            return "already_removed"
        raise StackError("Docker container exists but its identity could not be verified")
    if inspect.stdout.strip() != f"{expected_image}|{marker}":
        raise StackError("Docker image/owner marker mismatch; refusing to stop container")
    stopped = run_capture(["docker", "stop", "--time", "5", name], timeout=15)
    if stopped.returncode != 0:
        raise StackError("owned Relay container did not stop cleanly")
    removed = run_capture(["docker", "rm", name], timeout=15)
    if removed.returncode != 0:
        raise StackError("owned Relay container did not remove cleanly")
    return "stopped_and_removed"


def validate_private_root(root: Path, *, base: Path = PRIVATE_BASE, missing_ok: bool = False) -> Path | None:
    if root.is_symlink():
        raise StackError("private root is a symlink; refusing lifecycle action")
    if base.is_symlink():
        raise StackError("private namespace is a symlink; refusing lifecycle action")
    if base.exists():
        base_stat = base.stat()
        if not base.is_dir() or base_stat.st_uid != os.getuid() or base_stat.st_mode & 0o777 != 0o700:
            raise StackError("private namespace owner or permissions mismatch")
    base_path = base.resolve(strict=False)
    if root.parent.resolve(strict=False) != base_path:
        raise StackError("private root must be a direct child of the V7-06C temporary namespace")
    try:
        resolved = root.resolve(strict=True)
    except FileNotFoundError:
        if missing_ok:
            return None
        raise StackError("private root does not exist")
    if resolved.parent != base_path:
        raise StackError("private root is outside the V7-06C temporary namespace")
    root_stat = resolved.stat()
    if root_stat.st_uid != os.getuid() or root_stat.st_mode & 0o777 != 0o700:
        raise StackError("private root owner or permissions mismatch")
    return resolved


def load_and_verify_root(
    root: Path, *, expected_marker: str | None = None, missing_ok: bool = False, base: Path = PRIVATE_BASE,
) -> dict[str, Any] | None:
    resolved = validate_private_root(root, base=base, missing_ok=missing_ok)
    if resolved is None:
        return None
    lifecycle_path = resolved / LIFECYCLE_NAME
    owner_path = resolved / OWNER_NAME
    if (lifecycle_path.is_symlink() or owner_path.is_symlink()
            or not lifecycle_path.is_file() or not owner_path.is_file()):
        raise StackError("lifecycle identity files are not regular private files")
    if (lifecycle_path.stat().st_mode & 0o777 != 0o600
            or owner_path.stat().st_mode & 0o777 != 0o600):
        raise StackError("lifecycle identity permissions mismatch; refusing cleanup")
    try:
        lifecycle = json.loads(lifecycle_path.read_text(encoding="utf-8"))
        marker = owner_path.read_text(encoding="utf-8").strip()
    except FileNotFoundError:
        raise StackError("lifecycle identity files are missing; refusing cleanup")
    if not isinstance(lifecycle, dict) or not isinstance(lifecycle.get("marker"), str) or lifecycle.get("marker") != marker:
        raise StackError("lifecycle marker mismatch; refusing cleanup")
    if expected_marker is not None and marker != expected_marker:
        raise StackError("owner marker changed; refusing cleanup")
    if lifecycle.get("private_root") != str(resolved):
        raise StackError("lifecycle root mismatch; refusing cleanup")
    if lifecycle.get("scenario") not in SCENARIOS:
        raise StackError("lifecycle scenario mismatch; refusing cleanup")
    return lifecycle


def start_command(scenario: str, root_arg: str | None, relay_host: str,
                  android_relay_url: str | None = None, relay_port: int | None = None) -> dict[str, Any]:
    if scenario not in SCENARIOS:
        raise StackError("invalid scenario")
    relay_port = validate_relay_port(relay_port)
    root = Path(os.path.abspath(root_arg)) if root_arg else PRIVATE_BASE / f"{scenario}-{os.getpid()}-{secrets.token_hex(3)}"
    validate_private_root(root, missing_ok=True)
    relay_host = validate_android_relay_host(relay_host)
    if android_relay_url is not None:
        android_relay_url = validate_android_relay_url(android_relay_url)
    if root.exists():
        lifecycle = load_and_verify_root(root)
        if lifecycle.get("scenario") != scenario:
            raise StackError("private root scenario mismatch; refusing reuse")
        stack_path = root / STACK_NAME
        if stack_path.exists():
            existing = json.loads(stack_path.read_text(encoding="utf-8"))
            existing_relay_port = existing.get("ports", {}).get("relay")
            if relay_port is not None and existing_relay_port != relay_port:
                raise StackError("RELAY_PORT_MISMATCH")
            expected_url = android_relay_url or f"ws://{relay_host}:{existing.get('ports', {}).get('relay')}"
            expected_transport = "public_wss" if expected_url.startswith("wss://") else "loopback_ws"
            if existing.get("relay_url_android") != expected_url or existing.get("relay_transport") != expected_transport:
                raise StackError("private root Android Relay URL does not match requested configuration")
            process_records = lifecycle.get("processes")
            live = isinstance(process_records, dict) and set(process_records) == set(EXPECTED_COMMANDS)
            for name, record in (process_records.items() if isinstance(process_records, dict) else ()):
                try:
                    if verify_process_record(record, lifecycle["marker"]) is None:
                        live = False
                        break
                except (KeyError, StackError, TypeError, ValueError):
                    live = False
                    break
            if live:
                return {"status": "already_running", "stack": str(stack_path), "private_root": str(root), "ports": existing.get("ports")}
        raise StackError("existing private root is not a ready live stack; stop it before reuse")
    stack = Stack(root, scenario, android_relay_host=relay_host,
                  android_relay_url=android_relay_url, relay_port=relay_port)
    data = stack.start()
    result: dict[str, Any] = {"status": "started", "scenario": scenario,
                              "stack_file": str(root / STACK_NAME), "private_root": str(root),
                              "ports": data["ports"], "pids": data["pids"]}
    if scenario.startswith("backend-"):
        try:
            if str(SCRIPT_DIR) not in sys.path:
                sys.path.insert(0, str(SCRIPT_DIR))
            import run_backend_e2e
            result["e2e"] = run_backend_e2e.run_scenario(stack, scenario)
        except Exception:
            try:
                stack.stop(remove_root=True)
            except Exception:
                pass
            raise
    return result


def stop_command(root_arg: str) -> dict[str, Any]:
    root = Path(root_arg)
    if root.is_symlink():
        raise StackError("private root is a symlink; refusing lifecycle action")
    lifecycle = load_and_verify_root(root, missing_ok=True)
    if lifecycle is None:
        return {"status": "already_stopped", "private_root": str(root)}
    marker = lifecycle["marker"]
    stack = Stack(root, lifecycle.get("scenario", "unknown"))
    stack.marker = marker
    stack.lifecycle = lifecycle
    cleanup = stack.stop(remove_root=True)
    return {"status": "stopped", "private_root": str(root), "cleanup": cleanup}


def main() -> int:
    parser = JsonArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    start = commands.add_parser("start")
    start.add_argument("--scenario", choices=SCENARIOS, required=True)
    start.add_argument("--private-root")
    start.add_argument("--android-relay-host", default="10.0.2.2")
    start.add_argument("--android-relay-url")
    start.add_argument("--relay-port", type=int)
    stop = commands.add_parser("stop")
    stop.add_argument("--private-root", required=True)
    args = parser.parse_args()
    try:
        result = start_command(args.scenario, args.private_root, args.android_relay_host,
                               args.android_relay_url, args.relay_port) if args.command == "start" else stop_command(args.private_root)
        print(json.dumps(result, sort_keys=True))
        return 0
    except StackError as exc:
        code = str(exc).split(":", 1)[0].strip().replace(" ", "_").upper()
        print(json.dumps({"status": "BLOCKED", "error_code": code}, sort_keys=True))
        return 2
    except Exception as exc:
        code = getattr(exc, "code", None)
        if not isinstance(code, str) or not re.fullmatch(r"[A-Z0-9_]+", code):
            code = "HARNESS_FAILURE"
        print(json.dumps({"status": "FAIL", "error_code": code}, sort_keys=True))
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
