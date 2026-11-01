#!/usr/bin/env python3
"""Start the loopback-only Core, pinned relay, and fresh connector for VM-1."""

from __future__ import annotations

import json
import os
import secrets
import socket
import stat
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path
from typing import Any


TASK_ID = "V7-06A-VM"
ROOT = Path(__file__).resolve().parents[2]
PRIVATE_ROOT = Path("/tmp/atlas-v7-06a-vm-private")
STATE_FILE = PRIVATE_ROOT / "stack-state.json"
PUBLIC_CONFIG_FILE = PRIVATE_ROOT / "public-config.json"
CORE_DB = ROOT / "core.sqlite3"
CONNECTOR_STATE = ROOT / "connector"
PAIRING_FILE = ROOT / "pairing.json"
CORE_TOKEN_FILE = PRIVATE_ROOT / "core-token"
TOOL_TOKEN_FILE = PRIVATE_ROOT / "tool-token"
CORE_LOG = PRIVATE_ROOT / "core.log"
CONNECTOR_LOG = PRIVATE_ROOT / "connector.stderr.log"
CONNECTOR_STDOUT = PRIVATE_ROOT / "connector.stdout.log"
RELAY_IMAGE = "atlas-paseo-relay:r3a"
RELAY_IMAGE_ID = "sha256:75d68fd8bdb9d60a26203cd7c49c07c035f1f400c47740770056814e76846889"
RELAY_NAME = f"atlas-v7-06a-relay-{os.getpid()}"
RELAY_URL_PC = "ws://127.0.0.1:4412"
RELAY_URL_ANDROID = "ws://10.0.2.2:4412"
SERVER_ID = "atlas-pc-v7-06a-vm"
CORE_CAPABILITIES = {
    "day_plan.read": True,
    "activity_status.read": True,
    "plan_revision.propose": True,
    "usage_summary.read": True,
    "goal_progress.read": True,
    "training_plan.read": True,
    "coach.chat": True,
}


def write_private(path: Path, content: str) -> os.stat_result:
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        os.write(descriptor, content.encode("utf-8"))
        os.fsync(descriptor)
    finally:
        os.close(descriptor)
    os.chmod(path, 0o600)
    return path.stat()


def write_state(state: dict[str, Any]) -> None:
    temporary = STATE_FILE.with_suffix(".tmp")
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    try:
        os.write(descriptor, (json.dumps(state, sort_keys=True) + "\n").encode("utf-8"))
        os.fsync(descriptor)
    finally:
        os.close(descriptor)
    os.replace(temporary, STATE_FILE)
    os.chmod(STATE_FILE, 0o600)


def checked(command: list[str], *, environment: dict[str, str] | None = None,
            cwd: Path | None = None) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(command, cwd=cwd, env=environment, text=True, capture_output=True, check=False)
    if result.returncode != 0:
        raise RuntimeError(f"{Path(command[0]).name} exited with status {result.returncode}")
    return result


def ensure_free_ports() -> None:
    for port in (4411, 4412, 8110):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as candidate:
            try:
                candidate.bind(("127.0.0.1", port))
            except OSError:
                raise RuntimeError(f"required isolated loopback port {port} is occupied") from None


def wait_http(url: str, *, headers: dict[str, str] | None = None,
              timeout_seconds: float = 30.0) -> bytes:
    deadline = time.monotonic() + timeout_seconds
    last_error = "unavailable"
    while time.monotonic() < deadline:
        try:
            request = urllib.request.Request(url, headers=headers or {})
            with urllib.request.urlopen(request, timeout=2) as response:
                if response.status == 200:
                    return response.read(65_536)
                last_error = f"HTTP {response.status}"
        except (OSError, urllib.error.URLError, TimeoutError):
            last_error = "unavailable"
        time.sleep(0.25)
    raise RuntimeError(f"health check timed out for {url}; last result={last_error}")


def save_log(path: Path):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
    os.chmod(path, 0o600)
    return os.fdopen(descriptor, "ab", buffering=0)


def local_environment(values: dict[str, str]) -> dict[str, str]:
    environment = {"PATH": os.environ.get("PATH", "/usr/bin:/bin")}
    for key in ("LANG", "LC_ALL"):
        if key in os.environ:
            environment[key] = os.environ[key]
    environment.update(values)
    return environment


def main() -> int:
    if STATE_FILE.exists() or PUBLIC_CONFIG_FILE.exists():
        raise RuntimeError("prior VM-1 stack state exists; inspect it and stop it before starting another stack")
    if CORE_DB.exists() or CONNECTOR_STATE.exists() or PAIRING_FILE.exists():
        raise RuntimeError("VM-1 database, connector state, or pairing file already exists; preserving existing state")
    if not (ROOT / "integrations/relay/pc/dist/main.js").is_file():
        raise RuntimeError("connector dist/main.js is missing; build the reviewed connector source first")

    PRIVATE_ROOT.mkdir(mode=0o700)
    os.chmod(PRIVATE_ROOT, 0o700)
    ensure_free_ports()
    image_id = checked(["docker", "image", "inspect", "--format", "{{.Id}}", RELAY_IMAGE]).stdout.strip()
    if image_id != RELAY_IMAGE_ID:
        raise RuntimeError("pinned relay image ID does not match the V7-06A VM plan")

    core_token = secrets.token_urlsafe(32)
    tool_token = secrets.token_urlsafe(32)
    if secrets.compare_digest(core_token, tool_token):
        raise RuntimeError("synthetic Core tokens unexpectedly matched")
    core_stat = write_private(CORE_TOKEN_FILE, core_token + "\n")
    tool_stat = write_private(TOOL_TOKEN_FILE, tool_token + "\n")
    del core_token, tool_token

    state: dict[str, Any] = {
        "task_id": TASK_ID,
        "core_pid": None,
        "connector_pid": None,
        "relay_container": None,
        "relay_image_id": image_id,
        "owned_files": {
            str(CORE_TOKEN_FILE): {"device": core_stat.st_dev, "inode": core_stat.st_ino},
            str(TOOL_TOKEN_FILE): {"device": tool_stat.st_dev, "inode": tool_stat.st_ino},
        },
    }
    write_state(state)

    core_value = CORE_TOKEN_FILE.read_text(encoding="utf-8").strip()
    tool_value = TOOL_TOKEN_FILE.read_text(encoding="utf-8").strip()
    core_environment = local_environment({
        "PYTHONPATH": str(ROOT / "backend"),
        "ATLAS_DB_PATH": str(CORE_DB),
        "ATLAS_TOKEN": core_value,
        "ATLAS_TOOL_TOKEN": tool_value,
        "ATLAS_V4_CAPABILITIES_JSON": json.dumps(CORE_CAPABILITIES, separators=(",", ":")),
    })
    del core_value
    with save_log(CORE_LOG) as core_log:
        core = subprocess.Popen(
            [sys.executable, "-m", "uvicorn", "atlas_backend.main:app", "--host", "127.0.0.1", "--port", "8110", "--log-level", "warning"],
            cwd=ROOT,
            env=core_environment,
            stdout=core_log,
            stderr=subprocess.STDOUT,
            start_new_session=True,
        )
    state["core_pid"] = core.pid
    write_state(state)
    wait_http("http://127.0.0.1:8110/health")

    capabilities_request = urllib.request.Request(
        "http://127.0.0.1:8110/internal/v4/capabilities",
        headers={
            "Authorization": f"Bearer {tool_value}",
            "X-Atlas-Contract-Version": "4",
            "X-Atlas-Schema-Version": "4",
            "X-Atlas-Request-Id": str(uuid.uuid4()),
            "X-Atlas-Capability": "tool_catalog.read",
        },
    )
    try:
        with urllib.request.urlopen(capabilities_request, timeout=5) as response:
            capability_payload = json.loads(response.read(65_536))
    except (OSError, urllib.error.URLError, TimeoutError, json.JSONDecodeError):
        raise RuntimeError("isolated Core capability check failed") from None
    finally:
        del capabilities_request
    if capability_payload.get("capabilities") != CORE_CAPABILITIES:
        raise RuntimeError("isolated Core did not return the required seven V4 capabilities")

    run_relay = checked([
        "docker", "run", "-d", "--pull", "never", "--name", RELAY_NAME,
        "-p", "127.0.0.1:4412:4000",
        "-e", "PASEO_RELAY_HOST=0.0.0.0",
        "-e", "PASEO_RELAY_PORT=4000",
        RELAY_IMAGE,
    ])
    if not run_relay.stdout.strip():
        raise RuntimeError("isolated relay container did not return an identifier")
    state["relay_container"] = RELAY_NAME
    write_state(state)
    wait_http("http://127.0.0.1:4412/health")

    connector_dir = ROOT / "integrations/relay/pc"
    identity_environment = local_environment({"ATLAS_RELAY_STATE_DIR": str(CONNECTOR_STATE)})
    connector_environment = local_environment({
        "ATLAS_RELAY_STATE_DIR": str(CONNECTOR_STATE),
        "ATLAS_RELAY_URL": RELAY_URL_PC,
        "ATLAS_ALLOW_LOOPBACK_WS": "1",
        "ATLAS_RELAY_SERVER_ID": SERVER_ID,
        "ATLAS_BACKEND_URL": "http://127.0.0.1:8110",
        "ATLAS_SERVICE_TOKEN": TOOL_TOKEN_FILE.read_text(encoding="utf-8").strip(),
        "ATLAS_V4_ENABLED": "1",
        "ATLAS_V4_ANDROID_TOKEN_FILE": str(CORE_TOKEN_FILE),
        "ATLAS_METRICS_PORT": "4411",
        "ATLAS_DIAG_TRACE": "1",
    })
    CONNECTOR_STATE.mkdir(mode=0o700)
    os.chmod(CONNECTOR_STATE, 0o700)
    identity_result = checked(["node", "dist/main.js", "show-identity"], environment=identity_environment, cwd=connector_dir)
    identity = json.loads(identity_result.stdout)
    if not isinstance(identity.get("public_key"), str) or not isinstance(identity.get("fingerprint"), str):
        raise RuntimeError("connector did not return a valid isolated public identity")
    pair_result = checked([
        "node", "dist/main.js", "pair-device", "--label", "v7-06a-vm", "--token-file", str(PAIRING_FILE),
    ], environment=identity_environment, cwd=connector_dir)
    pair_stat = PAIRING_FILE.stat()
    state["owned_files"][str(PAIRING_FILE)] = {"device": pair_stat.st_dev, "inode": pair_stat.st_ino}
    write_state(state)
    pairing = json.loads(pair_result.stdout)
    if stat.S_IMODE(pair_stat.st_mode) != 0o600:
        raise RuntimeError("synthetic pairing file is not mode 0600")
    if len(pairing.get("capabilities", [])) != 14:
        raise RuntimeError("synthetic pairing did not contain the expected 14 relay capabilities")

    public_config = {
        "relay_url": RELAY_URL_ANDROID,
        "server_id": SERVER_ID,
        "server_public_key": identity["public_key"],
        "device_id": pairing["device_id"],
        "capability_count": len(pairing["capabilities"]),
    }
    write_private(PUBLIC_CONFIG_FILE, json.dumps(public_config, sort_keys=True) + "\n")

    with save_log(CONNECTOR_LOG) as connector_log, save_log(CONNECTOR_STDOUT) as connector_stdout:
        connector = subprocess.Popen(
            ["node", "dist/main.js", "start"],
            cwd=connector_dir,
            env=local_environment({
                **connector_environment,
                "ATLAS_SERVICE_TOKEN": TOOL_TOKEN_FILE.read_text(encoding="utf-8").strip(),
            }),
            stdout=connector_stdout,
            stderr=connector_log,
            start_new_session=True,
        )
    state["connector_pid"] = connector.pid
    write_state(state)
    metrics = json.loads(wait_http("http://127.0.0.1:4411/metrics"))
    if metrics.get("relay_connected") is not True:
        raise RuntimeError("isolated connector metrics did not report an authenticated relay connection")

    print(json.dumps({
        "result": "READY",
        "core_pid": core.pid,
        "relay_container": RELAY_NAME,
        "connector_pid": connector.pid,
        "relay_connected": True,
        "capability_count": public_config["capability_count"],
        "pairing_file_mode": "0600",
        "android_relay_url": RELAY_URL_ANDROID,
    }, sort_keys=True))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as error:  # keep exception payloads and environment out of terminal output
        print(f"BLOCKED: {type(error).__name__}: {str(error)[:240]}", file=sys.stderr)
        sys.exit(1)
