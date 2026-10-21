#!/usr/bin/env python3
"""Measure one consented idle window on the reserved API 34 AVD; never changes app/device state."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import pathlib
import re
import subprocess
import sys
import tempfile
import time


EXPECTED_AVD = "atlas_v622_s1_api34"
EXPECTED_SERIAL = "emulator-5580"
EXPECTED_ADB_SERVER_PORT = "5037"
ALLOWED_OUTPUT_ROOT = pathlib.Path("/tmp/atlas-v622-s6")
PACKAGE = "dev.atlas.coach"
ADB_COMMAND_TIMEOUT_SECONDS = 20
ADB_PULL_TIMEOUT_SECONDS = 45


class Blocked(RuntimeError):
    pass


def run_bounded(command: list[str], *, timeout: int, **kwargs: object) -> subprocess.CompletedProcess[str]:
    try:
        return subprocess.run(command, timeout=timeout, **kwargs)
    except subprocess.TimeoutExpired as error:
        raise Blocked("an assigned ADB read-only command timed out") from error
    except OSError as error:
        raise Blocked("an assigned ADB read-only command could not be started") from error


def adb_command(adb: pathlib.Path, *args: str, check: bool = True) -> str:
    result = run_bounded(
        [str(adb), "-s", EXPECTED_SERIAL, *args],
        timeout=ADB_COMMAND_TIMEOUT_SECONDS,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        check=False,
        env={**os.environ, "ADB_SERVER_PORT": EXPECTED_ADB_SERVER_PORT},
    )
    if check and result.returncode != 0:
        raise Blocked("an assigned ADB read-only command failed")
    return result.stdout.strip()


def adb_shell(adb: pathlib.Path, command: str, *, check: bool = True) -> str:
    return adb_command(adb, "shell", "sh", "-c", command, check=check)


def require_assigned_avd(adb: pathlib.Path) -> None:
    devices = run_bounded(
        [str(adb), "devices"],
        timeout=ADB_COMMAND_TIMEOUT_SECONDS,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        check=False,
        env={**os.environ, "ADB_SERVER_PORT": EXPECTED_ADB_SERVER_PORT},
    )
    rows = [line.split() for line in devices.stdout.splitlines()[1:] if line.strip()]
    if devices.returncode != 0 or rows != [[EXPECTED_SERIAL, "device"]]:
        raise Blocked("the assigned API 34 AVD must be the only connected ADB target (identifiers suppressed)")
    if adb_command(adb, "get-state") != "device":
        raise Blocked("the assigned API 34 AVD is unavailable")
    avd = adb_command(adb, "shell", "getprop", "ro.boot.qemu.avd_name")
    api = adb_command(adb, "shell", "getprop", "ro.build.version.sdk")
    if (avd, api) != (EXPECTED_AVD, "34"):
        raise Blocked("the assigned API 34 AVD identity does not match")


def parse_appops_usage_mode(output: str) -> str:
    match = re.search(r"(?m)^\s*GET_USAGE_STATS:\s*([a-z_]+)", output, re.IGNORECASE)
    if match:
        return match.group(1).lower()
    match = re.search(r"(?m)^\s*Uid mode:\s*GET_USAGE_STATS:\s*([a-z_]+)", output, re.IGNORECASE)
    if match:
        return match.group(1).lower()
    match = re.search(r"Default mode:\s*([a-z_]+)", output, re.IGNORECASE)
    return match.group(1).lower() if match else "unknown"


def appops_usage_mode(adb: pathlib.Path) -> str:
    output = adb_command(adb, "shell", "appops", "get", PACKAGE, "GET_USAGE_STATS", check=False)
    return parse_appops_usage_mode(output)


def verify_installed_candidate(adb: pathlib.Path, expected_sha256: str, pair_dir: pathlib.Path) -> None:
    paths = adb_command(adb, "shell", "pm", "path", PACKAGE).splitlines()
    base_paths = [line.removeprefix("package:").strip() for line in paths if line.rstrip().endswith("/base.apk")]
    if len(base_paths) != 1:
        raise Blocked("installed ATLAS base APK could not be identified")
    with tempfile.NamedTemporaryFile(prefix="installed-atlas-", suffix=".apk", dir=pair_dir, delete=False) as handle:
        local_apk = pathlib.Path(handle.name)
    try:
        os.chmod(local_apk, 0o600)
        result = run_bounded(
            [str(adb), "-s", EXPECTED_SERIAL, "pull", base_paths[0], str(local_apk)],
            timeout=ADB_PULL_TIMEOUT_SECONDS,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=False,
            env={**os.environ, "ADB_SERVER_PORT": EXPECTED_ADB_SERVER_PORT},
        )
        if result.returncode != 0:
            raise Blocked("installed ATLAS APK hash could not be read")
        digest = hashlib.sha256(local_apk.read_bytes()).hexdigest()
        if digest != expected_sha256:
            raise Blocked("installed ATLAS APK does not match the declared candidate hash")
    finally:
        local_apk.unlink(missing_ok=True)


def parse_cpu_stat(line: str) -> tuple[int, int, int]:
    close_paren = line.rfind(")")
    if close_paren < 0:
        raise ValueError("malformed proc stat")
    try:
        pid = int(line[:close_paren].split()[0])
    except (IndexError, ValueError) as error:
        raise ValueError("malformed proc stat pid") from error
    tail = line[close_paren + 2 :].split()
    if len(tail) < 20:
        raise ValueError("short proc stat")
    return pid, int(tail[11]) + int(tail[12]), int(tail[19])


def cpu_snapshot(adb: pathlib.Path) -> dict[str, object]:
    pids = sorted(set(value for value in adb_command(adb, "shell", "pidof", PACKAGE, check=False).split() if value.isdigit()))
    if not pids:
        return {"available": False, "process_count": 0, "processes": {}, "threads": {}}
    processes: dict[str, dict[str, int]] = {}
    threads: dict[str, dict[str, int]] = {}
    try:
        for pid in pids:
            process_pid, process_ticks, process_starttime = parse_cpu_stat(adb_shell(adb, f"cat /proc/{pid}/stat"))
            if str(process_pid) != pid:
                raise ValueError("process identity changed during snapshot")
            processes[pid] = {"starttime": process_starttime, "ticks": process_ticks}
            thread_output = adb_shell(adb, f"for f in /proc/{pid}/task/*/stat; do cat \"$f\"; done")
            for line in thread_output.splitlines():
                if not line.strip():
                    continue
                tid, ticks, thread_starttime = parse_cpu_stat(line)
                threads[f"{pid}:{tid}"] = {
                    "parent_starttime": process_starttime,
                    "starttime": thread_starttime,
                    "ticks": ticks,
                }
    except (Blocked, ValueError):
        return {"available": False, "process_count": len(pids), "processes": {}, "threads": {}}
    return {
        "available": True,
        "process_count": len(pids),
        "processes": processes,
        "threads": threads,
    }


def app_uid(adb: pathlib.Path) -> str | None:
    output = adb_command(adb, "shell", "pm", "list", "packages", "-U", PACKAGE, check=False)
    match = re.search(r"package:dev\.atlas\.coach\s+uid:(\d+)", output)
    return match.group(1) if match else None


def network_snapshot(adb: pathlib.Path, uid: str | None) -> dict[str, object]:
    if uid is None:
        return {"available": False, "source": "uid_unavailable"}
    output = adb_shell(adb, "cat /proc/net/xt_qtaguid/stats", check=False)
    totals = {"rx_bytes": 0, "tx_bytes": 0}
    counters: dict[str, dict[str, int]] = {}
    duplicate_counts: dict[str, int] = {}
    for line in output.splitlines()[1:]:
        fields = line.split()
        if len(fields) < 9 or fields[3] != uid:
            continue
        try:
            row = {"rx_bytes": int(fields[5]), "tx_bytes": int(fields[7])}
            row_identity = "|".join((fields[1], fields[2], fields[4]))
            occurrence = duplicate_counts.get(row_identity, 0)
            duplicate_counts[row_identity] = occurrence + 1
            row_key = f"{row_identity}:{occurrence}"
            counters[row_key] = row
            totals["rx_bytes"] += row["rx_bytes"]
            totals["tx_bytes"] += row["tx_bytes"]
        except ValueError:
            continue
    if counters:
        return {"available": True, "source": "xt_qtaguid", "rows": len(counters), "counters": counters, **totals}

    output = adb_command(adb, "shell", "dumpsys", "netstats", "detail", check=False)
    totals = {"rx_bytes": 0, "tx_bytes": 0}
    counters = {}
    duplicate_counts: dict[str, int] = {}
    for line in output.splitlines():
        if not re.search(rf"\buid={re.escape(uid)}(?:\b|-)", line):
            continue
        rx = re.search(r"\brxBytes=(\d+)", line)
        tx = re.search(r"\btxBytes=(\d+)", line)
        if rx or tx:
            row = {"rx_bytes": int(rx.group(1)) if rx else 0, "tx_bytes": int(tx.group(1)) if tx else 0}
            identity = re.sub(r"\b(?:rxBytes|txBytes)=\d+", "", line)
            identity_hash = hashlib.sha256(identity.encode("utf-8")).hexdigest()
            occurrence = duplicate_counts.get(identity_hash, 0)
            duplicate_counts[identity_hash] = occurrence + 1
            counters[f"{identity_hash}:{occurrence}"] = row
            totals["rx_bytes"] += row["rx_bytes"]
            totals["tx_bytes"] += row["tx_bytes"]
    if not counters:
        return {"available": False, "source": "unavailable"}
    return {"available": True, "source": "netstats_uid_detail", "rows": len(counters), "counters": counters, **totals}


def jobs_snapshot(adb: pathlib.Path) -> dict[str, int]:
    output = adb_command(adb, "shell", "dumpsys", "jobscheduler", check=False)
    package_lines = [line for line in output.splitlines() if PACKAGE in line]
    return {
        "package_lines": len(package_lines),
        "jobstatus_or_job_headers": sum("JobStatus" in line or "JOB #" in line for line in package_lines),
    }


def battery_record_snapshot(adb: pathlib.Path, uid: str | None) -> list[tuple[str, tuple[str, ...]]] | None:
    if uid is None:
        return None
    output = adb_command(adb, "shell", "dumpsys", "batterystats", "--checkin", check=False)
    if not output:
        return None
    records: list[tuple[str, tuple[str, ...]]] = []
    for line in output.splitlines():
        fields = line.split(",")
        if len(fields) > 3 and fields[1] == uid:
            records.append((fields[3], tuple(fields[4:])))
    return records


def parse_interactive_state(output: str) -> bool | None:
    match = re.search(r"\bmInteractive=(true|false)\b", output)
    if match:
        return match.group(1) == "true"
    match = re.search(r"\bmWakefulness=(Awake|Asleep)\b", output, re.IGNORECASE)
    if match:
        return match.group(1).lower() == "awake"
    return None


def interactive_state(adb: pathlib.Path) -> bool | None:
    output = adb_command(adb, "shell", "dumpsys", "power", check=False)
    return parse_interactive_state(output)


def snapshot(adb: pathlib.Path, uid: str | None) -> dict[str, object]:
    cpu = cpu_snapshot(adb)
    battery = battery_record_snapshot(adb, uid)
    return {
        "cpu": cpu,
        "jobs": jobs_snapshot(adb),
        "network": network_snapshot(adb, uid),
        "batterystats_record_count": None if battery is None else len(battery),
        "batterystats_fingerprint": None if battery is None else hashlib.sha256(
            json.dumps(battery, separators=(",", ":")).encode("utf-8")
        ).hexdigest(),
        "screen_interactive": interactive_state(adb),
    }


def cpu_delta(start: dict[str, object], end: dict[str, object], key: str) -> float | str:
    start_cpu = start["cpu"]
    end_cpu = end["cpu"]
    assert isinstance(start_cpu, dict) and isinstance(end_cpu, dict)
    if not start_cpu.get("available") or not end_cpu.get("available"):
        return "unavailable_snapshot"
    start_processes = start_cpu["processes"]
    end_processes = end_cpu["processes"]
    start_threads = start_cpu["threads"]
    end_threads = end_cpu["threads"]
    assert isinstance(start_processes, dict) and isinstance(end_processes, dict)
    assert isinstance(start_threads, dict) and isinstance(end_threads, dict)
    process_identities = lambda values: {pid: value["starttime"] for pid, value in values.items()}
    if process_identities(start_processes) != process_identities(end_processes):
        return "unavailable_process_identity_changed"
    if key == "processes":
        start_records, end_records = start_processes, end_processes
    elif key == "threads":
        thread_identities = lambda values: {
            tid: (value["parent_starttime"], value["starttime"]) for tid, value in values.items()
        }
        if thread_identities(start_threads) != thread_identities(end_threads):
            return "unavailable_thread_identity_changed"
        start_records, end_records = start_threads, end_threads
    else:
        raise ValueError(f"unsupported CPU counter: {key}")
    deltas = [int(end_records[identity]["ticks"]) - int(value["ticks"]) for identity, value in start_records.items()]
    if any(delta < 0 for delta in deltas):
        return "unavailable_counter_decreased"
    ticks = sum(deltas)
    return round(ticks / os.sysconf("SC_CLK_TCK"), 3)


def network_delta(start: dict[str, object], end: dict[str, object]) -> dict[str, object]:
    if not start.get("available") or not end.get("available"):
        return {"available": False, "source": "unavailable_snapshot", "rx_bytes": "unavailable", "tx_bytes": "unavailable"}
    if start.get("source") != end.get("source") or start.get("rows") != end.get("rows"):
        return {"available": False, "source": "source_or_counter_set_changed", "rx_bytes": "unavailable", "tx_bytes": "unavailable"}
    start_counters = start.get("counters")
    end_counters = end.get("counters")
    if not isinstance(start_counters, dict) or not isinstance(end_counters, dict) or set(start_counters) != set(end_counters):
        return {"available": False, "source": "counter_identity_changed", "rx_bytes": "unavailable", "tx_bytes": "unavailable"}
    deltas: dict[str, int | str] = {}
    for key in ("rx_bytes", "tx_bytes"):
        row_deltas = [
            int(end_counters[row_id][key]) - int(start_counters[row_id][key])
            for row_id in start_counters
        ]
        deltas[key] = sum(row_deltas) if all(delta >= 0 for delta in row_deltas) else "unavailable_counter_decreased"
    return {
        "available": all(isinstance(value, int) for value in deltas.values()),
        "source": start["source"],
        **deltas,
    }


def validate_attestations(args: argparse.Namespace, usage_mode: str) -> None:
    expected = {
        "local": ("off", "off", "off", "off", "offline"),
        "coaching": ("on", "on", "on", "on", "configured-no-request"),
    }[args.mode]
    observed = (
        args.agent_state,
        args.proactive_state,
        args.usage_consent,
        args.usage_background_state,
        args.backend_state,
    )
    if observed != expected:
        raise Blocked("manual app-state attestations do not match the requested mode")
    if not (args.confirm_no_focus and args.confirm_no_mobility and args.confirm_no_e2e):
        raise Blocked("confirm no active Focus, no mobility, and no E2E request before the window")
    if args.emergency_pause != "off":
        raise Blocked("emergency pause must already be inactive; the recipe never clears it")
    if args.mode == "coaching" and usage_mode != "allow":
        raise Blocked("Android Usage Access is not actually allowed for the coaching window")
    if args.mode == "local" and usage_mode != "ignore":
        raise Blocked("Android Usage Access is not explicitly denied for the local comparison window")


def run_window(args: argparse.Namespace) -> dict[str, object]:
    pair_dir = args.pair_dir.resolve()
    try:
        pair_dir.relative_to(ALLOWED_OUTPUT_ROOT)
    except ValueError as error:
        raise Blocked("proof output must stay under /tmp/atlas-v622-s6") from error
    pair_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(pair_dir, 0o700)
    destination = pair_dir / f"{args.mode}.json"
    if destination.exists():
        raise Blocked(f"{args.mode} window already exists in this pair directory")

    sdk = pathlib.Path(os.environ.get("ANDROID_HOME", os.environ.get("ANDROID_SDK_ROOT", str(pathlib.Path.home() / "Android/Sdk"))))
    adb = sdk / "platform-tools" / "adb"
    if not adb.is_file():
        raise Blocked("Android platform-tools adb is unavailable")
    require_assigned_avd(adb)
    verify_installed_candidate(adb, args.candidate_app_sha256, pair_dir)
    if adb_command(adb, "shell", "pidof", "dev.atlas.coach.test", check=False):
        raise Blocked("an instrumentation process is active")
    if "MobilityService" in adb_command(adb, "shell", "dumpsys", "activity", "services", PACKAGE, check=False):
        raise Blocked("a mobility service is active")

    usage_mode = appops_usage_mode(adb)
    if usage_mode == "unknown":
        raise Blocked("Android Usage Access mode could not be read")
    validate_attestations(args, usage_mode)

    uid = app_uid(adb)
    before = snapshot(adb, uid)
    start_monotonic = time.monotonic()
    deadline = start_monotonic + args.duration_seconds
    while True:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            break
        time.sleep(min(remaining, 0.25))
    end_monotonic = time.monotonic()
    after = snapshot(adb, uid)
    duration = end_monotonic - start_monotonic

    if duration < args.duration_seconds:
        raise Blocked("measured rest window was shorter than requested")
    if before["screen_interactive"] is None or after["screen_interactive"] is None:
        screen_state = "unavailable"
    elif before["screen_interactive"] == after["screen_interactive"]:
        screen_state = "interactive" if before["screen_interactive"] else "screen-off"
    else:
        raise Blocked("screen state changed during the rest window")

    start_network = before["network"]
    end_network = after["network"]
    assert isinstance(start_network, dict) and isinstance(end_network, dict)
    network_delta_result = network_delta(start_network, end_network)

    battery_changed: int | str
    if before["batterystats_fingerprint"] is None or after["batterystats_fingerprint"] is None:
        battery_changed = "unavailable"
    else:
        battery_changed = int(before["batterystats_fingerprint"] != after["batterystats_fingerprint"])

    result: dict[str, object] = {
        "mode": args.mode,
        "candidate_app_sha256": args.candidate_app_sha256,
        "avd": EXPECTED_AVD,
        "api": 34,
        "usage_access_appop": usage_mode,
        "agent_state_attested": args.agent_state,
        "proactive_state_attested": args.proactive_state,
        "usage_consent_attested": args.usage_consent,
        "usage_background_state_attested": args.usage_background_state,
        "emergency_pause_attested": args.emergency_pause,
        "no_focus_mobility_or_e2e_attested": True,
        "requested_duration_seconds": args.duration_seconds,
        "actual_duration_seconds": round(duration, 3),
        "screen_state": screen_state,
        "process_count_start_end": [before["cpu"]["process_count"], after["cpu"]["process_count"]],
        "process_cpu_seconds_delta": cpu_delta(before, after, "processes"),
        "thread_cpu_seconds_delta": cpu_delta(before, after, "threads"),
        "jobscheduler_package_lines_start_end": [before["jobs"]["package_lines"], after["jobs"]["package_lines"]],
        "jobscheduler_jobstatus_lines_start_end": [
            before["jobs"]["jobstatus_or_job_headers"], after["jobs"]["jobstatus_or_job_headers"]
        ],
        "workmanager_database": "not read; scheduled work observed through JobScheduler only",
        "network_uid_delta": network_delta_result,
        "batterystats_app_record_count_start_end": [before["batterystats_record_count"], after["batterystats_record_count"]],
        "batterystats_app_records_changed": battery_changed,
        "per_app_wakeup_delta": "unavailable_or_not_attributable_on_this_API34_AVD",
        "physical_device_or_battery_result": "NOT_RUN",
    }
    destination.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    os.chmod(destination, 0o600)
    check_pair(pair_dir, args.duration_seconds)
    print(f"window_recorded={args.mode}")
    print(f"actual_duration_seconds={result['actual_duration_seconds']}")
    print(f"proof_file={destination}")
    return result


def evaluate_pair(local: dict[str, object], coaching: dict[str, object], requested_duration: int) -> dict[str, object]:
    errors = []
    if local["mode"] != "local" or coaching["mode"] != "coaching":
        errors.append("mode_mismatch")
    if local["candidate_app_sha256"] != coaching["candidate_app_sha256"]:
        errors.append("candidate_mismatch")
    if local["requested_duration_seconds"] != coaching["requested_duration_seconds"]:
        errors.append("requested_duration_mismatch")
    if local["requested_duration_seconds"] != requested_duration or requested_duration < 60:
        errors.append("window_duration_below_60_or_mismatch")
    if local["actual_duration_seconds"] < 60 or coaching["actual_duration_seconds"] < 60:
        errors.append("actual_window_below_60_seconds")
    if abs(local["actual_duration_seconds"] - coaching["actual_duration_seconds"]) > 0.5:
        errors.append("actual_durations_not_comparable_within_500ms")
    allowed_screen_states = {"interactive", "screen-off"}
    local_screen = local.get("screen_state")
    coaching_screen = coaching.get("screen_state")
    local_screen_known = isinstance(local_screen, str) and local_screen in allowed_screen_states
    coaching_screen_known = isinstance(coaching_screen, str) and coaching_screen in allowed_screen_states
    if not local_screen_known or not coaching_screen_known:
        errors.append("screen_state_unavailable")
    if local_screen != coaching_screen:
        errors.append("screen_states_differ")
    return {
        "status": "FAIL" if errors else "PASS",
        "candidate_app_sha256": local.get("candidate_app_sha256"),
        "requested_duration_seconds": requested_duration,
        "actual_durations_seconds": {
            "local": local["actual_duration_seconds"],
            "coaching": coaching["actual_duration_seconds"],
        },
        "screen_state": local_screen if local_screen == coaching_screen and local_screen_known else "unavailable_or_mismatch",
        "comparison_tolerance_seconds": 0.5,
        "physical_device_or_battery_result": "NOT_RUN",
        "limitations": [
            "AVD-only observation; no phone or battery-life claim",
            "WorkManager database is not read",
            "JobScheduler rows describe scheduled work; they do not count wakeups",
            "per-app wakeup, alarm, and wakelock attribution is unavailable unless explicitly exposed",
            "network counters are reported only when UID counters are exposed",
        ],
        "errors": errors,
    }


def check_pair(pair_dir: pathlib.Path, requested_duration: int) -> None:
    paths = [pair_dir / "local.json", pair_dir / "coaching.json"]
    if not all(path.is_file() for path in paths):
        return
    local, coaching = (json.loads(path.read_text(encoding="utf-8")) for path in paths)
    pair = evaluate_pair(local, coaching, requested_duration)
    path = pair_dir / "pair-summary.json"
    path.write_text(json.dumps(pair, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    os.chmod(path, 0o600)
    if pair["status"] == "FAIL":
        raise Blocked("paired windows are not comparable; see sanitized pair-summary.json")
    print("pair_status=PASS")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", required=True, choices=("local", "coaching"))
    parser.add_argument("--pair-dir", required=True, type=pathlib.Path)
    parser.add_argument("--candidate-app-sha256", required=True, help="SHA-256 of the installed candidate base APK")
    parser.add_argument("--duration-seconds", type=int, default=60)
    parser.add_argument("--agent-state", required=True, choices=("on", "off"))
    parser.add_argument("--proactive-state", required=True, choices=("on", "off"))
    parser.add_argument("--usage-consent", required=True, choices=("on", "off"))
    parser.add_argument("--usage-background-state", required=True, choices=("on", "off"))
    parser.add_argument("--emergency-pause", required=True, choices=("on", "off"))
    parser.add_argument("--backend-state", required=True, choices=("offline", "configured-no-request"))
    parser.add_argument("--confirm-no-focus", action="store_true")
    parser.add_argument("--confirm-no-mobility", action="store_true")
    parser.add_argument("--confirm-no-e2e", action="store_true")
    args = parser.parse_args()

    if args.duration_seconds < 60:
        parser.error("--duration-seconds must be at least 60")
    if not re.fullmatch(r"[a-fA-F0-9]{64}", args.candidate_app_sha256):
        parser.error("--candidate-app-sha256 must be a 64-character hexadecimal SHA-256")
    args.candidate_app_sha256 = args.candidate_app_sha256.lower()
    try:
        run_window(args)
        return 0
    except Blocked as error:
        print(f"BLOCKED: {error}", file=sys.stderr)
        return 77


if __name__ == "__main__":
    raise SystemExit(main())
