#!/usr/bin/env bash
# A3 historical validation of the image default entrypoint (which runs
# `openclaw doctor --fix`). V7-12 production deliberately uses
# `init: true` + `entrypoint: ["node", "/app/openclaw.mjs"]` to bypass that
# unattended repair on the live codex-plugin state; see README.md. This script
# must be adapted before being used as the production oracle.
set -Eeuo pipefail
set +x
umask 077

readonly IMAGE='ghcr.io/openclaw/openclaw@sha256:f40d1e66fd14d8c5b49c832f4100f83b53fdc01f5108f7db11de38fd0ee6dbe5'
readonly PORT=18790
readonly STATE_SOURCE='/home/nill/.openclaw'
readonly REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd -P)"
WORK=''
STATE_COPY=''
CAPTURE_COPY=''
CONTAINER=''
NETWORK=''
EGRESS_NETWORK=''
LIVE_PID=''

fail() { printf 'FAIL %s\n' "$1" >&2; exit 1; }

cleanup() {
  local result=$?
  local cleanup_status=0
  trap - EXIT
  trap - ERR
  if [[ -n "$CONTAINER" ]]; then
    docker stop --time 30 "$CONTAINER" >/dev/null 2>&1 || true
    docker rm "$CONTAINER" >/dev/null 2>&1 || cleanup_status=1
  fi
  if [[ -n "$NETWORK" ]]; then docker network rm "$NETWORK" >/dev/null 2>&1 || cleanup_status=1; fi
  if [[ -n "$EGRESS_NETWORK" ]]; then docker network rm "$EGRESS_NETWORK" >/dev/null 2>&1 || cleanup_status=1; fi
  if [[ -n "$WORK" && -d "$WORK" ]]; then
    case "$WORK" in
      /tmp/atlas-openclaw-a3.*) python3 - "$WORK" <<'PY' || cleanup_status=1
import pathlib, shutil, sys
target = pathlib.Path(sys.argv[1])
assert target.parent == pathlib.Path("/tmp") and target.name.startswith("atlas-openclaw-a3.")
shutil.rmtree(target)
PY
      ;;
      *) printf 'FAIL refusing cleanup outside private /tmp prefix\n' >&2; cleanup_status=1 ;;
    esac
  fi
  if (( cleanup_status == 0 )); then printf 'PASS cleanup (container, bridges, temporary state removed)\n';
  else printf 'FAIL cleanup status=%s\n' "$cleanup_status" >&2; fi
  if (( result == 0 && cleanup_status != 0 )); then result=1; fi
  return "$result"
}

trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
trap 'status=$?; printf "FAIL unexpected command failure (status=%s line=%s)\n" "$status" "$LINENO" >&2; exit "$status"' ERR

[[ "$(id -u)" == 1000 && "$(id -g)" == 1000 ]] || fail 'run as UID:GID 1000:1000'
[[ "$HOME" == /home/nill ]] || fail 'HOME must be /home/nill'
[[ -d "$STATE_SOURCE" && -f "$STATE_SOURCE/openclaw.json" ]] || fail 'live OpenClaw state/config not found'
[[ "$(readlink -f "$STATE_SOURCE")" == "$STATE_SOURCE" ]] || fail 'state path must resolve to /home/nill/.openclaw'
[[ "$STATE_SOURCE" != "$REPO_ROOT"/* ]] || fail 'live state must be outside the repository'
command -v docker >/dev/null || fail 'docker CLI unavailable'
command -v python3 >/dev/null || fail 'python3 unavailable'
command -v rsync >/dev/null || fail 'rsync unavailable'
command -v systemctl >/dev/null || fail 'systemctl unavailable'
command -v rg >/dev/null || fail 'ripgrep unavailable'

[[ "$(systemctl --user show openclaw-gateway.service --property=ActiveState --value)" == active ]] || fail 'openclaw-gateway.service is not active; no service action was taken'
LIVE_PID="$(systemctl --user show openclaw-gateway.service --property=MainPID --value)"
[[ "$LIVE_PID" =~ ^[1-9][0-9]*$ ]] || fail 'could not identify the live Gateway PID'
docker image inspect "$IMAGE" >/dev/null 2>&1 || fail 'pinned image is not present locally; pull it explicitly before retrying'
python3 - "$PORT" <<'PY' || fail 'validation host port is already in use'
import socket, sys
s = socket.socket()
try: s.bind(("127.0.0.1", int(sys.argv[1])))
finally: s.close()
PY

WORK="$(mktemp -d /tmp/atlas-openclaw-a3.XXXXXX)"
chmod 0700 "$WORK"
STATE_COPY="$WORK/state"
mkdir -m 0700 "$STATE_COPY"
CAPTURE_COPY="$WORK/update-captures"
mkdir -m 0700 "$CAPTURE_COPY"

cat >"$WORK/checks.py" <<'PY'
import hashlib, json, os, pathlib, re, sqlite3, stat, subprocess, sys

def tree(root):
    root = pathlib.Path(root)
    rows, total, files = [], 0, 0
    for base, dirs, names in os.walk(root, followlinks=False):
        dirs.sort(); names.sort()
        for name in dirs + names:
            path = pathlib.Path(base) / name
            rel = path.relative_to(root).as_posix()
            st = path.lstat()
            if stat.S_ISREG(st.st_mode) and rel.endswith("-shm"):
                continue  # SQLite shared-memory lock/index bytes are ephemeral.
            row = [rel, stat.S_IFMT(st.st_mode), stat.S_IMODE(st.st_mode), st.st_uid, st.st_gid]
            if stat.S_ISREG(st.st_mode):
                digest = hashlib.sha256()
                with path.open("rb") as stream:
                    for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                        digest.update(chunk)
                after = path.lstat()
                if (st.st_dev, st.st_ino, st.st_size, st.st_mtime_ns, st.st_ctime_ns) != (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns, after.st_ctime_ns):
                    raise RuntimeError("file changed while hashing")
                row += [st.st_size, digest.hexdigest()]
                total += st.st_size; files += 1
            elif stat.S_ISLNK(st.st_mode):
                row += [hashlib.sha256(os.readlink(path).encode()).hexdigest()]
            rows.append(row)
    payload = json.dumps(rows, separators=(",", ":"), ensure_ascii=False).encode()
    return {"digest": hashlib.sha256(payload).hexdigest(), "files": files, "bytes": total, "entries": rows}

def objects(value):
    if isinstance(value, dict):
        yield value
        for child in value.values(): yield from objects(child)
    elif isinstance(value, list):
        for child in value: yield from objects(child)

def read_cli(path):
    lines = pathlib.Path(path).read_text().splitlines()
    for index, line in enumerate(lines):
        if line.lstrip().startswith(("{", "[")):
            try: return json.loads("\n".join(lines[index:]))
            except json.JSONDecodeError: continue
    raise AssertionError("JSON output unavailable")

def main():
    command, *args = sys.argv[1:]
    if command == "manifest":
        print(json.dumps(tree(args[0]), separators=(",", ":")))
    elif command == "same-tree":
        left, right = tree(args[0]), tree(args[1])
        assert left == right
        print("PASS provider auth/session tree copied byte-for-byte")
    elif command == "config":
        config_path, root_path = map(pathlib.Path, args)
        config = json.loads(config_path.read_text())
        agents = config.get("agents", {})
        atlas = agents.get("entries", {}).get("atlas", {})
        deny = set(atlas.get("tools", {}).get("deny", []))
        required = {
            "gateway", "session_status", "presence", "exec", "process",
            "code_execution", "wait", "group:plugins", "group:fs", "group:runtime",
            "group:web", "group:sessions", "group:nodes", "group:ui",
            "sessions_spawn", "subagents",
        }
        plugin = config.get("plugins", {}).get("entries", {}).get("atlas_core_tools", {})
        auth = config.get("gateway", {}).get("auth", {})
        assert agents.get("defaults", {}).get("model", {}).get("primary") == "openai/gpt-6-luna"
        assert atlas.get("model") in (None, "openai/gpt-6-luna")
        assert atlas.get("tools", {}).get("profile") == "minimal"
        assert atlas.get("tools", {}).get("codeMode", {}).get("enabled") is False
        assert required.issubset(deny)
        assert plugin.get("enabled") is False and plugin.get("config", {}).get("enabled") is False
        assert auth.get("mode") == "token" and bool(auth.get("token"))
        assert config.get("gateway", {}).get("bind") == "loopback"
        for key in ("workspace", "agentDir"):
            value = pathlib.Path(atlas.get(key, ""))
            assert value.is_absolute() and str(value).startswith("/home/nill/.openclaw/")
            assert (root_path / str(value).removeprefix("/home/nill/.openclaw/")).exists()
        token_file = plugin.get("config", {}).get("tokenFile")
        assert isinstance(token_file, str) and token_file.startswith("/home/nill/.openclaw/")
        assert (root_path / token_file.removeprefix("/home/nill/.openclaw/")).is_file()
        print("PASS config: model, atlas/minimal, deny-list, disabled plugin, token auth, absolute paths")
    elif command == "main-session":
        p = pathlib.Path(args[0])
        c = sqlite3.connect("file:" + p.as_posix() + "?mode=ro", uri=True, timeout=10)
        try:
            assert c.execute("SELECT count(*) FROM session_nodes WHERE session_key='agent:atlas:main'").fetchone()[0] == 1
        finally: c.close()
    elif command == "cli":
        agents, plugins, auth_path, tools_path = args
        agent_data = read_cli(agents); plugin_data = read_cli(plugins); auth_data = read_cli(auth_path); tool_data = read_cli(tools_path)
        atlas_entries = [obj for obj in objects(agent_data) if obj.get("id") == "atlas"]
        assert atlas_entries and any(obj.get("model") == "openai/gpt-6-luna" for obj in atlas_entries)
        for obj in objects(plugin_data):
            if obj.get("id") == "atlas_core_tools" or obj.get("name") == "atlas_core_tools":
                assert obj.get("enabled") is not True
        assert any(obj.get("provider") == "openai" and (obj.get("mode") == "oauth" or obj.get("type") == "oauth") for obj in objects(auth_data))
        assert any(isinstance(obj.get("groups"), list) for obj in objects(tool_data))
        assert not any(obj.get("ok") is False for obj in objects(tool_data))
        tool_entries = [obj for obj in objects(tool_data) if "id" in obj]
        denied = {"gateway", "session_status", "presence", "exec", "process", "code_execution", "wait", "sessions_spawn", "subagents"}
        denied_excluded = 0
        for obj in tool_entries:
            if str(obj.get("id")) not in denied: continue
            assert obj.get("status") == "excluded"
            reasons = obj.get("reasons", [])
            assert any(reason.get("kind") == "deny" for reason in reasons if isinstance(reason, dict))
            denied_excluded += 1
        print(f"PASS listings: atlas/openai-gpt-6-luna; OpenAI OAuth profile; atlas_core_tools disabled/absent; denied tools excluded (explicit={denied_excluded}, entries={len(tool_entries)})")
    elif command == "sqlite":
        root = pathlib.Path(args[0])
        dbs = sorted(p for p in root.rglob("*") if p.is_file() and p.suffix.lower() in {".sqlite", ".sqlite3", ".db"} and p.stat().st_size > 0)
        sessions = auth_profiles = 0
        rows = []
        for p in dbs:
            rel = p.relative_to(root).as_posix()
            c = sqlite3.connect("file:" + p.as_posix() + "?mode=ro", uri=True, timeout=10)
            try:
                c.execute("PRAGMA query_only=ON")
                assert c.execute("PRAGMA quick_check").fetchone()[0] == "ok"
                tables = {row[0] for row in c.execute("SELECT name FROM sqlite_master WHERE type='table'")}
                if rel == "agents/atlas/agent/openclaw-agent.sqlite":
                    assert "session_nodes" in tables and "auth_profile_store" in tables
                    sessions = c.execute("SELECT count(*) FROM session_nodes").fetchone()[0]
                    auth_profiles = c.execute("SELECT count(*) FROM auth_profile_store").fetchone()[0]
            finally: c.close()
            rows.append((rel, p.stat().st_size))
        assert sessions > 0 and auth_profiles > 0
        digest = hashlib.sha256(json.dumps(rows, separators=(",", ":")).encode()).hexdigest()
        print(json.dumps({"sqlite_files": len(dbs), "atlas_sessions": sessions, "auth_profile_rows": auth_profiles, "digest": digest}, separators=(",", ":")))
    elif command == "compare-json":
        before, after = (json.loads(pathlib.Path(p).read_text()) for p in args)
        if "sqlite_files" in before:
            assert before["sqlite_files"] == after["sqlite_files"]
            assert before["atlas_sessions"] == after["atlas_sessions"]
            assert before["auth_profile_rows"] == after["auth_profile_rows"]
        elif "entries" in before:
            left = {row[0]: row[1:] for row in before["entries"]}
            right = {row[0]: row[1:] for row in after["entries"]}
            changed = sorted(path for path in set(left) | set(right) if left.get(path) != right.get(path))
            if changed:
                counts = {}
                for path in changed:
                    root = path.split("/", 1)[0]
                    counts[root] = counts.get(root, 0) + 1
                summary = ",".join(f"{root}:{counts[root]}" for root in sorted(counts))
                print(f"LIVE_MANIFEST_DELTA entries={len(changed)} top_level={summary}")
                raise AssertionError("manifest changed")
            assert before["digest"] == after["digest"] and before["files"] == after["files"] and before["bytes"] == after["bytes"]
        else:
            assert before == after
    elif command == "inspect":
        name, network, egress, state, capture = args
        obj = json.loads(subprocess.check_output(["docker", "inspect", name], text=True))[0]
        cfg, host = obj["Config"], obj["HostConfig"]
        assert cfg["User"] == "1000:1000"
        assert "HOME=/home/nill" in cfg["Env"]
        assert "OPENCLAW_STATE_DIR=/home/nill/.openclaw" in cfg["Env"]
        assert "OPENCLAW_CONFIG_PATH=/home/nill/.openclaw/openclaw.json" in cfg["Env"]
        assert cfg["Cmd"] == ["node", "openclaw.mjs", "gateway", "--port", "18789", "--bind", "lan"]
        assert host["ReadonlyRootfs"] is True and host["Privileged"] is False
        assert "ALL" in (host.get("CapDrop") or [])
        assert "no-new-privileges:true" in (host.get("SecurityOpt") or [])
        assert host["NetworkMode"] == egress
        assert host["PortBindings"] == {"18789/tcp": [{"HostIp": "127.0.0.1", "HostPort": "18790"}]}
        mounts = obj["Mounts"]
        tmpfs = host.get("Tmpfs") or {}
        assert any(m["Type"] == "bind" and m["Source"] == state and m["Destination"] == "/home/nill/.openclaw" and m["RW"] for m in mounts)
        assert "/tmp" in tmpfs and "/home/nill/.cache" in tmpfs
        assert any(m["Type"] == "bind" and m["Source"] == capture and m["Destination"] == "/home/nill/.openclaw.update-captures" and m["RW"] for m in mounts)
        assert all("docker.sock" not in (m.get("Source", "") + m.get("Destination", "")) for m in mounts)
        attached = set(obj["NetworkSettings"]["Networks"])
        assert attached == {network, egress}
        internal_info = json.loads(subprocess.check_output(["docker", "network", "inspect", network], text=True))[0]
        egress_info = json.loads(subprocess.check_output(["docker", "network", "inspect", egress], text=True))[0]
        assert internal_info["Internal"] is True and internal_info["Driver"] == "bridge"
        assert egress_info["Internal"] is False and egress_info["Driver"] == "bridge"
        print("PASS Docker security, identity, mount, internal/egress networks and publication contract")
    elif command == "summarize":
        text = pathlib.Path(args[0]).read_text(errors="replace")
        codes = sorted(set(re.findall(r"\b(?:EROFS|EACCES|ENOENT|ECONNREFUSED|ECONNRESET|ETIMEDOUT|EADDRINUSE|EADDRNOTAVAIL)\b", text)))
        print("HEALTH_DIAGNOSTIC codes=" + (",".join(codes) if codes else "none") + f" bytes={len(text)}")
        for line in [line for line in text.splitlines() if line.strip()][:3]:
            line = re.sub(r"(?i)(token|secret|password|authorization|api[-_]?key)([=: ]+)\S+", r"\1\2[REDACTED]", line)
            line = re.sub(r"[\w.+-]+@[\w.-]+", "[ID]", line)
            line = re.sub(r"/(?:[^\s,;]+)", "[PATH]", line)
            line = re.sub(r"(?<!\w)[A-Za-z0-9_-]{36,}", "[REDACTED]", line)
            print("HEALTH_LINE", line[:180])
    else:
        raise SystemExit("unknown validation helper command")

if __name__ == "__main__": main()
PY
chmod 0600 "$WORK/checks.py"

# Backups and the root transient capture directory are not needed by this test; the
# persistent OpenClaw tree and the agent's SQLite auth/session store are copied.
rsync -a --quiet --exclude='/backups/***' --exclude='/tmp/***' --exclude='/npm/***' \
  "$STATE_SOURCE/" "$STATE_COPY/" || fail 'could not copy OpenClaw state to the private temporary directory'
# Refresh the active SQLite trees after the larger plugin/workspace copy so an
# otherwise idle live Gateway cannot leave a torn WAL copy behind.
rsync -a --quiet "$STATE_SOURCE/state/" "$STATE_COPY/state/" || fail 'could not refresh isolated state database copy'
rsync -a --quiet "$STATE_SOURCE/agents/" "$STATE_COPY/agents/" || fail 'could not refresh isolated agent/session/auth copy'
mkdir -p "$STATE_COPY/tmp"
chmod 0700 "$STATE_COPY/tmp"
[[ -f "$STATE_COPY/openclaw.json" && -d "$STATE_COPY/state" && -d "$STATE_COPY/agents" && \
   -d "$STATE_COPY/workspaces" && -d "$STATE_COPY/workspace" && -d "$STATE_COPY/secrets" && \
   -d "$STATE_COPY/extensions" && -d "$STATE_COPY/plugin-sources" ]] || fail 'isolated state copy is incomplete'
python3 "$WORK/checks.py" config "$STATE_COPY/openclaw.json" "$STATE_COPY" || fail 'isolated config does not satisfy the A3 preservation contract'
for part in state agents workspaces workspace secrets extensions plugin-sources plugin-skills cache logs migration media locks completions; do
  python3 "$WORK/checks.py" same-tree "$STATE_SOURCE/$part" "$STATE_COPY/$part" >/dev/null || fail "isolated state copy differs for $part"
done
printf 'PASS copied OpenClaw config/state/workspaces/secrets/extensions/plugin sources; SQLite auth/session bytes match\n'
python3 "$WORK/checks.py" main-session "$STATE_COPY/agents/atlas/agent/openclaw-agent.sqlite" || fail 'existing atlas session store is missing'
cp -- "$STATE_COPY/openclaw.json" "$WORK/openclaw.json.baseline"
chmod 0600 "$WORK/openclaw.json.baseline"

python3 "$WORK/checks.py" manifest "$STATE_SOURCE" >"$WORK/live-before.json" || fail 'could not hash live OpenClaw state before test'
chmod 0600 "$WORK/live-before.json"
python3 - "$WORK/live-before.json" <<'PY'
import json, sys
d = json.load(open(sys.argv[1]))
print(f"LIVE_MANIFEST_BEFORE files={d['files']} bytes={d['bytes']} sha256={d['digest']}")
PY

NETWORK="atlas-openclaw-a3-net-$$"
EGRESS_NETWORK="atlas-openclaw-a3-egress-$$"
CONTAINER="atlas-openclaw-a3-$$"
docker network create --driver bridge --internal --label atlas.task=V7-12-A3 "$NETWORK" >/dev/null 2>&1 || fail 'could not create isolated internal Docker bridge'
docker network create --driver bridge --label atlas.task=V7-12-A3 "$EGRESS_NETWORK" >/dev/null 2>&1 || fail 'could not create isolated egress Docker bridge'
if ! docker run --detach --name "$CONTAINER" \
  --label atlas.task=V7-12-A3 \
  --network "$EGRESS_NETWORK" \
  --publish "127.0.0.1:${PORT}:18789" \
  --user 1000:1000 --read-only --cap-drop ALL --security-opt no-new-privileges:true \
  --restart unless-stopped \
  --log-driver json-file --log-opt max-size=10m --log-opt max-file=3 \
  --tmpfs /tmp:rw,nosuid,nodev,size=512m,mode=1777 \
  --tmpfs /home/nill/.cache:rw,nosuid,nodev,size=128m,uid=1000,gid=1000,mode=0700 \
  --mount "type=bind,source=${STATE_COPY},destination=/home/nill/.openclaw" \
  --mount "type=bind,source=${CAPTURE_COPY},destination=/home/nill/.openclaw.update-captures" \
  --env HOME=/home/nill \
  --env OPENCLAW_STATE_DIR=/home/nill/.openclaw \
  --env OPENCLAW_CONFIG_PATH=/home/nill/.openclaw/openclaw.json \
  --env TMPDIR=/tmp --env XDG_CACHE_HOME=/home/nill/.cache \
  --health-cmd 'node openclaw.mjs gateway health --port 18789 --timeout 5000' \
  --health-interval 10s --health-timeout 21s --health-retries 12 --health-start-period 20s \
  "$IMAGE" node openclaw.mjs gateway --port 18789 --bind lan \
  >"$WORK/docker-run.out" 2>"$WORK/docker-run.err"; then
  sed "s|$WORK|<private-temp>|g" "$WORK/docker-run.err" >&2
  fail 'container did not start'
fi
docker network connect --alias openclaw-gateway "$NETWORK" "$CONTAINER" || fail 'could not attach Gateway to the internal bridge'

wait_healthy() {
  local i status
  for ((i = 0; i < 90; i++)); do
    status="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}missing{{end}}' "$CONTAINER" 2>/dev/null || true)"
    [[ "$status" == healthy ]] && return 0
    [[ "$status" == unhealthy || "$status" == missing ]] && return 1
    sleep 2
  done
  return 1
}
diagnose_health() {
  docker inspect --format 'CONTAINER state={{.State.Status}} exit={{.State.ExitCode}} health={{if .State.Health}}{{.State.Health.Status}}{{else}}missing{{end}}' "$CONTAINER" 2>/dev/null || true
  docker logs --tail 60 "$CONTAINER" 2>&1 | python3 -c '
import re,sys
for line in sys.stdin.read().splitlines():
 if not re.search(r"error|failed|listen|bind|readonly|read.only|permission|EROFS|EACCES|ENOENT|ECONN|EADDR|ETIMEDOUT|doctor|capture|bootstrap|maintenance|gateway",line,re.I): continue
 line=re.sub(r"(?i)(token|secret|password|authorization|api[-_]?key)([=: ]+)\S+",r"\1\2[REDACTED]",line)
 line=re.sub(r"[\w.+-]+@[\w.-]+","[ID]",line)
 line=re.sub(r"/(?:[^\s,;]+)","[PATH]",line)
 line=re.sub(r"(?<!\w)[A-Za-z0-9_-]{36,}","[REDACTED]",line)
 print("STARTUP",line[:180])
'
  docker exec "$CONTAINER" node openclaw.mjs gateway health --port 18789 --timeout 5000 \
    >"$WORK/health-diagnostic.out" 2>&1 || true
  python3 "$WORK/checks.py" summarize "$WORK/health-diagnostic.out"
}
wait_healthy || { diagnose_health; fail 'container did not become healthy within 120 seconds'; }
docker exec "$CONTAINER" node openclaw.mjs gateway health --port 18789 --timeout 5000 \
  >"$WORK/health.out" 2>"$WORK/health.err" || fail 'requested gateway health command failed'
printf 'PASS Docker healthcheck and gateway health --port 18789 --timeout 5000\n'
docker exec "$CONTAINER" node openclaw.mjs config validate >"$WORK/config.out" 2>"$WORK/config.err" || fail 'OpenClaw config validation failed'
docker exec "$CONTAINER" node openclaw.mjs agents list --json >"$WORK/agents.out" 2>"$WORK/agents.err" || fail 'agent listing failed'
docker exec "$CONTAINER" node openclaw.mjs plugins list --json >"$WORK/plugins.out" 2>"$WORK/plugins.err" || fail 'plugin listing failed'
docker exec "$CONTAINER" node openclaw.mjs models auth list --agent atlas --json >"$WORK/auth.out" 2>"$WORK/auth.err" || fail 'provider auth listing failed'
docker exec "$CONTAINER" node -e '
const { spawnSync } = require("node:child_process");
const params = JSON.stringify({ agentId: "atlas", sessionKey: "agent:atlas:main" });
const result = spawnSync(process.execPath, ["openclaw.mjs", "gateway", "call", "--json", "--port", "18789", "--timeout", "5000", "tools.effective", "--params", params], { encoding: "utf8" });
process.stdout.write(result.stdout || ""); process.stderr.write(result.stderr || ""); process.exit(result.status ?? 1);
' >"$WORK/tools.out" 2>"$WORK/tools.err" || fail 'effective tools inventory RPC failed'
python3 "$WORK/checks.py" cli "$WORK/agents.out" "$WORK/plugins.out" "$WORK/auth.out" "$WORK/tools.out" || fail 'runtime agent/plugin/provider/tool inventory failed'

docker exec "$CONTAINER" node -e '
const fs = require("node:fs");
if (fs.existsSync("/var/run/docker.sock")) process.exit(3);
for (const p of ["/tmp/.a3-write-probe", "/home/nill/.cache/.a3-write-probe", "/home/nill/.openclaw/.a3-write-probe", "/home/nill/.openclaw.update-captures/.a3-write-probe"]) {
  fs.writeFileSync(p, "probe", { mode: 0o600 }); fs.unlinkSync(p);
}
try { fs.writeFileSync("/app/.a3-readonly-probe", "probe"); process.exit(4); }
catch (e) { if (e.code !== "EROFS") process.exit(5); }
' >/dev/null 2>"$WORK/write-probe.err" || fail 'tmpfs/state writability, read-only root, or docker.sock absence check failed'
docker diff "$CONTAINER" >"$WORK/docker-diff.out"
python3 - "$WORK/docker-diff.out" <<'PY' || fail 'container wrote to its overlay outside bind/tmpfs mounts'
import pathlib, sys
actual = set(pathlib.Path(sys.argv[1]).read_text().splitlines())
# Docker creates these empty mountpoint ancestors in its overlay even with a
# read-only rootfs; they are scaffolding, not application writes.
expected = {
    "C /home", "A /home/nill", "A /home/nill/.cache",
    "A /home/nill/.openclaw", "A /home/nill/.openclaw.update-captures",
}
unexpected = actual - expected
if unexpected:
    print("unexpected_overlay_changes=" + ",".join(sorted(unexpected)))
    raise SystemExit(1)
print("PASS overlay diff limited to Docker-created mountpoint scaffolding")
PY
printf 'PASS read_only root; writable paths limited to state, update-captures, /tmp and cache mounts; no docker.sock\n'

python3 - "$PORT" <<'PY' || fail 'loopback-only published validation port is unreachable'
import socket, sys
with socket.create_connection(("127.0.0.1", int(sys.argv[1])), timeout=5): pass
PY
docker run --rm --network "$NETWORK" --entrypoint node "$IMAGE" -e '
const net = require("node:net");
const socket = net.createConnection({ host: "openclaw-gateway", port: 18789, timeout: 5000 });
socket.once("connect", () => { console.log("PASS bridge connection"); socket.destroy(); });
socket.once("timeout", () => { socket.destroy(); process.exit(2); });
socket.once("error", () => process.exit(3));
' >"$WORK/network.out" 2>"$WORK/network.err" || fail 'Gateway is not reachable on the isolated Docker bridge with --bind lan'
python3 "$WORK/checks.py" inspect "$CONTAINER" "$NETWORK" "$EGRESS_NETWORK" "$STATE_COPY" "$CAPTURE_COPY"

python3 "$WORK/checks.py" config "$STATE_COPY/openclaw.json" "$STATE_COPY" || fail 'container changed a protected OpenClaw config setting'
python3 - "$STATE_COPY/openclaw.json" "$WORK/openclaw.json.baseline" <<'PY' || fail 'container changed a protected OpenClaw config setting'
import json, pathlib, sys
before, after = (json.loads(pathlib.Path(p).read_text()) for p in sys.argv[1:])
def protected(c):
    agents = c.get("agents", {})
    atlas = agents.get("entries", {}).get("atlas", {})
    plugin = c.get("plugins", {}).get("entries", {}).get("atlas_core_tools", {})
    gateway = c.get("gateway", {})
    return {
        "primary_model": agents.get("defaults", {}).get("model", {}).get("primary"),
        "atlas_model": atlas.get("model"),
        "atlas_workspace": atlas.get("workspace"),
        "atlas_agent_dir": atlas.get("agentDir"),
        "atlas_tools": atlas.get("tools"),
        "plugin": plugin,
        "gateway_bind": gateway.get("bind"),
        "gateway_auth": gateway.get("auth"),
    }
assert protected(before) == protected(after)
changed_roots = sorted(k for k in set(before) | set(after) if before.get(k) != after.get(k))
print("PASS copied config preserved protected settings" + ("; normalized roots=" + ",".join(changed_roots) if changed_roots else "; byte-identical"))
PY

python3 "$WORK/checks.py" sqlite "$STATE_COPY" >"$WORK/sqlite-before.json" || fail 'isolated SQLite integrity/session/auth baseline failed'
chmod 0600 "$WORK/sqlite-before.json"
python3 - "$WORK/sqlite-before.json" <<'PY'
import json, sys
d = json.load(open(sys.argv[1]))
print(f"PASS SQLite before restart: databases={d['sqlite_files']} atlas_sessions={d['atlas_sessions']} auth_profile_rows={d['auth_profile_rows']}")
PY

docker stop --time 30 "$CONTAINER" >/dev/null 2>&1 || fail 'SIGTERM stop timed out or failed'
EXIT_CODE="$(docker inspect --format '{{.State.ExitCode}}' "$CONTAINER")"
OOM="$(docker inspect --format '{{.State.OOMKilled}}' "$CONTAINER")"
[[ "$OOM" == false && ( "$EXIT_CODE" == 0 || "$EXIT_CODE" == 143 ) ]] || fail 'container did not stop cleanly on SIGTERM'
docker start "$CONTAINER" >/dev/null 2>&1 || fail 'container restart failed'
wait_healthy || { diagnose_health; fail 'container did not return to healthy after SIGTERM/restart'; }
docker exec "$CONTAINER" node openclaw.mjs gateway health --port 18789 --timeout 5000 \
  >"$WORK/health-after-restart.out" 2>"$WORK/health-after-restart.err" || fail 'health command failed after restart'
python3 "$WORK/checks.py" sqlite "$STATE_COPY" >"$WORK/sqlite-after.json" || fail 'SQLite integrity/session check failed after restart'
python3 "$WORK/checks.py" compare-json "$WORK/sqlite-before.json" "$WORK/sqlite-after.json" || fail 'session/SQLite/auth persistence changed across restart'
docker exec "$CONTAINER" node -e '
const fs = require("node:fs");
let gateways = 0;
for (const pid of fs.readdirSync("/proc")) {
  if (!/^\d+$/.test(pid)) continue;
  try {
    const argv = fs.readFileSync(`/proc/${pid}/cmdline`, "utf8").split("\0").filter(Boolean);
    if (argv.slice(-6).join(" ") === "openclaw.mjs gateway --port 18789 --bind lan") gateways++;
  } catch {}
}
if (gateways !== 1) { console.error(`gateway_processes=${gateways}`); process.exit(1); }
console.log("PASS exactly one Gateway process after restart");
' || fail 'expected exactly one Gateway process after restart'
printf 'PASS SIGTERM (%s), restart healthy, SQLite quick_check OK, atlas sessions/auth intact, one Gateway process\n' "$EXIT_CODE"

[[ "$(systemctl --user show openclaw-gateway.service --property=ActiveState --value)" == active ]] || fail 'live systemd Gateway is no longer active'
[[ "$(systemctl --user show openclaw-gateway.service --property=MainPID --value)" == "$LIVE_PID" ]] || fail 'live systemd Gateway PID changed during isolated validation'
python3 "$WORK/checks.py" manifest "$STATE_SOURCE" >"$WORK/live-after.json" || fail 'could not hash live OpenClaw state after test'
python3 - "$WORK/live-after.json" <<'PY'
import json, sys
d = json.load(open(sys.argv[1]))
print(f"LIVE_MANIFEST_AFTER files={d['files']} bytes={d['bytes']} sha256={d['digest']}")
PY
python3 "$WORK/checks.py" compare-json "$WORK/live-before.json" "$WORK/live-after.json" || fail 'live OpenClaw persistent-state manifest changed'
printf 'PASS live ~/.openclaw manifest unchanged (SQLite -shm excluded as volatile)\n'
printf 'PASS systemd openclaw-gateway.service remained active with the same PID\n'
printf 'RESULT V7-12-A3 isolated container validation PASS\n'
