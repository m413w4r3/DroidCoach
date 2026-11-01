#!/usr/bin/env python3
"""Build or run the isolated V7-06B Android QA package."""

from __future__ import annotations

import argparse
import json
import os
import re
import shlex
import shutil
import stat
import subprocess
import sys
import uuid
from pathlib import Path
from typing import Sequence


ROOT = Path(__file__).resolve().parents[3]
ANDROID_ROOT = ROOT / "android"
OUTPUT_ROOT = ANDROID_ROOT / "app" / "build" / "outputs" / "apk"
TEST_CLASS = "dev.atlas.coach.e2e.V7_06B_CoachLoopInstrumentedTest"
TEST_RUNNER = "androidx.test.runner.AndroidJUnitRunner"
LOG_TAG = "ATLAS_V7_06B"
PAIRING_NAME = "pairing.json"
PRODUCT_PACKAGE = "dev.atlas.coach"
SUFFIX_PATTERN = re.compile(r"^\.qa\.v706b\.[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)*$")
QA_PACKAGE_PATTERN = re.compile(r"^dev\.atlas\.coach\.qa\.v706b\.[A-Za-z][A-Za-z0-9_.]*$")
SECRET_FIELD_PATTERN = re.compile(r"(?i)\b(?:device_)?token\b\s*[:=]\s*([^\s,}]+)")
BEARER_PATTERN = re.compile(r"(?i)\bBearer\s+[^\s,}]+")


class QaError(RuntimeError):
    pass


def command_output(command: Sequence[str], *, cwd: Path | None = None, env: dict[str, str] | None = None) -> str:
    result = subprocess.run(command, cwd=cwd, env=env, text=True, capture_output=True, check=False)
    if result.returncode != 0:
        raise QaError(f"{Path(command[0]).name} failed with status {result.returncode}")
    return result.stdout.strip()


def execute(command: Sequence[str], *, cwd: Path | None = None, env: dict[str, str] | None = None) -> None:
    result = subprocess.run(command, cwd=cwd, env=env, text=True, check=False)
    if result.returncode != 0:
        raise QaError(f"{Path(command[0]).name} failed with status {result.returncode}")


def validate_suffix(value: str) -> str:
    if not SUFFIX_PATTERN.fullmatch(value):
        raise QaError("qa suffix must start with .qa.v706b. and contain only valid package segments")
    package = f"{PRODUCT_PACKAGE}{value}"
    if package == PRODUCT_PACKAGE or not QA_PACKAGE_PATTERN.fullmatch(package):
        raise QaError("refusing a package outside the isolated dev.atlas.coach.qa.v706b namespace")
    return value


def build(suffix: str) -> tuple[Path, Path, str]:
    suffix = validate_suffix(suffix)
    command = [
        "./gradlew",
        ":app:assembleQa",
        ":app:assembleQaAndroidTest",
        "-PqaTestBuildType=qa",
        f"-PqaSuffix={suffix}",
        "--offline",
    ]
    execute(command, cwd=ANDROID_ROOT)
    app_id = f"{PRODUCT_PACKAGE}{suffix}"
    app_apk, test_apk = locate_apks(app_id)
    print(f"QA_BUILD_SUCCESS app_id={app_id}")
    print(f"app_apk={app_apk}")
    print(f"android_test_apk={test_apk}")
    return app_apk, test_apk, app_id


def locate_aapt() -> Path:
    candidates: list[Path] = []
    executable = shutil.which("aapt")
    if executable:
        candidates.append(Path(executable))

    sdk_roots = [os.environ.get("ANDROID_SDK_ROOT"), os.environ.get("ANDROID_HOME")]
    local_properties = ANDROID_ROOT / "local.properties"
    if local_properties.is_file():
        for line in local_properties.read_text(encoding="utf-8").splitlines():
            if line.startswith("sdk.dir="):
                value = line.partition("=")[2].replace("\\:", ":").replace("\\\\", "\\")
                sdk_roots.append(value)
                break
    for root in filter(None, sdk_roots):
        build_tools = Path(root) / "build-tools"
        if build_tools.is_dir():
            for version_dir in sorted(build_tools.iterdir(), key=lambda item: item.name, reverse=True):
                candidate = version_dir / "aapt"
                if candidate.is_file():
                    candidates.append(candidate)
    for candidate in candidates:
        if candidate.is_file():
            return candidate
    raise QaError("Android SDK aapt is required to verify the built APK package IDs")


def apk_package(apk: Path, aapt: Path) -> str:
    output = command_output([str(aapt), "dump", "badging", str(apk)])
    match = re.search(r"^package: name='([^']+)'", output, re.MULTILINE)
    if not match:
        raise QaError(f"cannot read application ID from {apk.name}")
    return match.group(1)


def locate_apks(expected_app_id: str) -> tuple[Path, Path]:
    if not OUTPUT_ROOT.is_dir():
        raise QaError("Gradle APK output directory is missing")
    aapt = locate_aapt()
    app_matches: list[Path] = []
    test_matches: list[Path] = []
    for apk in sorted(OUTPUT_ROOT.rglob("*.apk")):
        try:
            package = apk_package(apk, aapt)
        except QaError:
            continue
        if package == expected_app_id:
            app_matches.append(apk)
        elif package == f"{expected_app_id}.test":
            test_matches.append(apk)
    if len(app_matches) != 1 or len(test_matches) != 1:
        raise QaError(
            "expected exactly one QA app APK and one QA AndroidTest APK for "
            f"{expected_app_id}; found app={len(app_matches)} test={len(test_matches)}"
        )
    return app_matches[0], test_matches[0]


def read_pairing(path: Path) -> tuple[dict[str, str], str]:
    if not path.is_file():
        raise QaError("pairing file does not exist")
    if stat.S_IMODE(path.stat().st_mode) != 0o600:
        raise QaError("pairing file must have mode 0600")
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        raise QaError("pairing file is unreadable or invalid JSON") from None
    if not isinstance(value, dict):
        raise QaError("pairing file must contain a JSON object")
    device_id = value.get("device_id")
    token = value.get("token")
    if not isinstance(device_id, str) or not device_id.strip() or not isinstance(token, str) or not token.strip():
        raise QaError("pairing file must contain non-empty device_id and token fields")
    return {"device_id": device_id, "token": token}, token


def sanitized(text: str, secrets: Sequence[str]) -> str:
    for secret in sorted((value for value in secrets if value), key=len, reverse=True):
        text = text.replace(secret, "<redacted>")
    text = SECRET_FIELD_PATTERN.sub(lambda match: match.group(0).split(match.group(1))[0] + "<redacted>", text)
    return BEARER_PATTERN.sub("Bearer <redacted>", text)


def adb(serial: str, *arguments: str) -> str:
    return command_output(["adb", "-s", serial, *arguments])


def verify_device(serial: str) -> None:
    if not serial.strip() or any(character.isspace() for character in serial):
        raise QaError("an explicit adb serial is required")
    state = adb(serial, "get-state")
    if state != "device":
        raise QaError(f"explicit adb target is not ready (state={state})")
    is_emulator = adb(serial, "shell", "getprop", "ro.kernel.qemu").strip() == "1"
    if not is_emulator and os.environ.get("ATLAS_ALLOW_PHYSICAL_QA") != "1":
        raise QaError("physical QA is disabled; set ATLAS_ALLOW_PHYSICAL_QA=1 for this explicit serial")


def require_not_installed(serial: str, package: str) -> None:
    if package == PRODUCT_PACKAGE or not QA_PACKAGE_PATTERN.fullmatch(package):
        raise QaError("refusing a product or non-V7-06B QA package target")
    result = subprocess.run(
        ["adb", "-s", serial, "shell", "pm", "path", package],
        text=True,
        capture_output=True,
        check=False,
    )
    if result.returncode == 0 and result.stdout.strip().startswith("package:"):
        raise QaError(f"QA package already exists on this target; select a fresh suffix ({package})")


def push_private_pairing(serial: str, package: str, pairing_path: Path) -> None:
    inner = f"umask 077; cat > files/{PAIRING_NAME} && chmod 600 files/{PAIRING_NAME}"
    shell_command = f"run-as {shlex.quote(package)} sh -c {shlex.quote(inner)}"
    result = subprocess.run(
        ["adb", "-s", serial, "shell", "-T", shell_command],
        input=pairing_path.read_bytes(),
        capture_output=True,
        check=False,
    )
    if result.returncode != 0:
        subprocess.run(
            ["adb", "-s", serial, "shell", f"run-as {shlex.quote(package)} rm -f files/{PAIRING_NAME}"],
            check=False,
            capture_output=True,
        )
        raise QaError("could not place the private pairing file in QA app storage")
    mode = adb(
        serial,
        "shell",
        f"run-as {shlex.quote(package)} stat -c %a files/{PAIRING_NAME}",
    ).strip()
    if mode != "600":
        raise QaError("private app pairing file was not installed with mode 0600")


def capture_logcat(serial: str, secrets: Sequence[str], destination: Path) -> None:
    result = subprocess.run(
        ["adb", "-s", serial, "logcat", "-d", "-v", "threadtime", "-s", f"{LOG_TAG}:I"],
        text=True,
        capture_output=True,
        check=False,
    )
    if result.returncode != 0:
        raise QaError("logcat capture failed")
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(sanitized(result.stdout, secrets), encoding="utf-8")
    destination.chmod(0o600)


def read_stack(path: Path) -> dict[str, str | Path]:
    stack_path = path.resolve()
    if not stack_path.is_file() or stat.S_IMODE(stack_path.stat().st_mode) != 0o600:
        raise QaError("stack.json must be a readable private file with mode 0600")
    try:
        raw = json.loads(stack_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        raise QaError("stack.json is unreadable or invalid JSON") from None
    if not isinstance(raw, dict):
        raise QaError("stack.json must contain a JSON object")

    string_fields = ("relay_url_android", "server_id", "server_public_key")
    path_fields = (
        "pairing_file",
        "core_db",
        "core_token_file",
        "tool_token_file",
        "proxy_trace",
        "private_root",
    )
    stack: dict[str, str | Path] = {}
    for name in string_fields:
        value = raw.get(name)
        if not isinstance(value, str) or not value.strip():
            raise QaError(f"stack.json field {name} is required")
        stack[name] = value.strip()
    for name in path_fields:
        value = raw.get(name)
        if not isinstance(value, str) or not value.strip():
            raise QaError(f"stack.json field {name} is required")
        candidate = Path(value).expanduser()
        if not candidate.is_absolute():
            candidate = stack_path.parent / candidate
        stack[name] = candidate.resolve()

    pairing_path = Path(stack["pairing_file"])
    core_db = Path(stack["core_db"])
    core_token_file = Path(stack["core_token_file"])
    tool_token_file = Path(stack["tool_token_file"])
    proxy_trace = Path(stack["proxy_trace"])
    private_root = Path(stack["private_root"])
    for name, path_value in (
        ("pairing_file", pairing_path),
        ("core_token_file", core_token_file),
        ("tool_token_file", tool_token_file),
    ):
        if not path_value.is_file() or stat.S_IMODE(path_value.stat().st_mode) != 0o600:
            raise QaError(f"stack.json field {name} must point to an existing mode-0600 file")
    if not core_db.is_file():
        raise QaError("stack.json field core_db must point to an existing database file")
    if not private_root.is_dir() or stat.S_IMODE(private_root.stat().st_mode) & 0o077:
        raise QaError("stack.json field private_root must be a private directory")
    if proxy_trace.exists() and not proxy_trace.is_file():
        raise QaError("stack.json field proxy_trace must be a file path")
    return stack


def run(args: argparse.Namespace) -> int:
    suffix = validate_suffix(args.suffix or f".qa.v706b.{args.action}.{uuid.uuid4().hex[:12]}")
    expected_app_id = f"{PRODUCT_PACKAGE}{suffix}"
    if args.action not in {"apply", "reject"}:
        raise QaError("action must be apply or reject")
    if not args.serial:
        raise QaError("installation/test requires an explicit --serial")
    stack = read_stack(args.stack_file)
    relay_url = str(stack["relay_url_android"])
    server_id = str(stack["server_id"])
    server_public_key = str(stack["server_public_key"])
    pairing_path = Path(stack["pairing_file"])
    private_root = Path(stack["private_root"])
    _, token = read_pairing(pairing_path)
    secret_values = [token]
    for secret_name in ("core_token_file", "tool_token_file"):
        token_value = Path(stack[secret_name]).read_text(encoding="utf-8").strip()
        if token_value:
            secret_values.append(token_value)
    verify_device(args.serial)
    require_not_installed(args.serial, expected_app_id)
    require_not_installed(args.serial, f"{expected_app_id}.test")
    app_apk, test_apk, app_id = build(suffix)
    if app_id != expected_app_id or not QA_PACKAGE_PATTERN.fullmatch(app_id):
        raise QaError("built app package did not match the requested QA suffix")
    require_not_installed(args.serial, app_id)
    require_not_installed(args.serial, f"{app_id}.test")

    print(f"install_target={args.serial} package={app_id} action={args.action}")
    execute(["adb", "-s", args.serial, "install", str(app_apk)])
    execute(["adb", "-s", args.serial, "install", str(test_apk)])

    shell_package = f"{app_id}.test/{TEST_RUNNER}"
    if not QA_PACKAGE_PATTERN.fullmatch(app_id) or app_id == PRODUCT_PACKAGE:
        raise QaError("refusing to instrument the product package")
    log_path = private_root / f"android-{args.action}-{suffix.rsplit('.', 1)[-1]}.logcat.txt"
    test_result: subprocess.CompletedProcess[str] | None = None
    try:
        push_private_pairing(args.serial, app_id, pairing_path)
        shell_arguments = [
            "am instrument -w -r",
            f"-e class {shlex.quote(TEST_CLASS)}",
            f"-e test_action {shlex.quote(args.action)}",
            f"-e relay_url {shlex.quote(relay_url)}",
            f"-e server_id {shlex.quote(server_id)}",
            f"-e server_public_key {shlex.quote(server_public_key)}",
            shlex.quote(shell_package),
        ]
        command = " ".join(shell_arguments)
        test_result = subprocess.run(
            ["adb", "-s", args.serial, "shell", command],
            text=True,
            capture_output=True,
            check=False,
        )
    finally:
        subprocess.run(
            ["adb", "-s", args.serial, "shell", f"run-as {shlex.quote(app_id)} rm -f files/{PAIRING_NAME}"],
            check=False,
            capture_output=True,
        )
        capture_logcat(args.serial, secret_values, log_path)

    if test_result is None:
        raise QaError("instrumentation did not start")
    if test_result.stdout:
        print(sanitized(test_result.stdout, secret_values), end="")
    if test_result.stderr:
        print(sanitized(test_result.stderr, secret_values), file=sys.stderr, end="")
    print(f"logcat_redacted={log_path}")
    instrumentation_output = test_result.stdout + test_result.stderr
    if test_result.returncode != 0 or "INSTRUMENTATION_CODE: -1" in instrumentation_output or "FAILURES!!!" in instrumentation_output:
        raise QaError(f"instrumentation failed with status {test_result.returncode}")
    print(f"V7_06B_RUNTIME_PASS action={args.action} package={app_id}")
    return 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    build_parser = subparsers.add_parser("build", help="build the QA app and AndroidTest APKs without ADB")
    build_parser.add_argument("--suffix", required=True, help="unique applicationId suffix, e.g. .qa.v706b.compile")

    run_parser = subparsers.add_parser("run", help="build, install and run one disposable QA scenario")
    run_parser.add_argument("--serial", required=True, help="explicit adb serial; no implicit device selection")
    run_parser.add_argument("--action", required=True, choices=("apply", "reject"))
    run_parser.add_argument("--suffix", help="unique suffix; generated if omitted")
    run_parser.add_argument("--stack-file", required=True, type=Path, help="private JSON produced by the isolated backend stack")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        if args.command == "build":
            build(args.suffix)
            return 0
        return run(args)
    except (QaError, OSError) as error:
        print(f"V7-06B QA ERROR: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
