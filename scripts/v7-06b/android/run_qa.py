#!/usr/bin/env python3
"""Build or run the isolated V7-06B Android QA package."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import selectors
import shlex
import shutil
import stat
import subprocess
import sys
import time
import uuid
import zipfile
from pathlib import Path
from typing import Sequence
from urllib.parse import urlsplit


ROOT = Path(__file__).resolve().parents[3]
ANDROID_ROOT = ROOT / "android"
OUTPUT_ROOT = ANDROID_ROOT / "app" / "build" / "outputs" / "apk"
TEST_CLASS = "dev.atlas.coach.e2e.V7_06B_CoachLoopInstrumentedTest"
TEST_RUNNER = "androidx.test.runner.AndroidJUnitRunner"
PROVENANCE_ASSET = "assets/atlas-build-provenance.json"
ADB_TIMEOUT_SECONDS = 30
INSTALL_TIMEOUT_SECONDS = 120
INSTRUMENTATION_TIMEOUT_SECONDS = 1800
EXPECTED_INSTRUMENTATION_TESTS = 1
PROVENANCE_FIELDS = {
    "source_sha", "source_tree_sha", "worktree_clean", "variant", "application_id",
    "test_application_id", "version_code", "version_name", "compile_sdk", "min_sdk",
    "target_sdk", "scenario_id",
}
QA_PROOF_FIELDS = {
    "runtime_sdk", "app_apk_sha256", "android_test_apk_sha256", "signing_cert_sha256",
    "build_provenance_sha256", "android_manifest_sha256", "android_test_manifest_sha256",
    "network", "endpoint", "installed_apk_verified", "installed_apk_sha256", "verification_result",
}
APK_SHA_RE = re.compile(r"[0-9a-f]{64}")
LOG_TAG = "ATLAS_V7_06B"
PAIRING_NAME = "pairing.json"
PRODUCT_PACKAGE = "dev.atlas.coach"
SUFFIX_PATTERN = re.compile(r"^\.qa\.v706b\.[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)*$")
QA_PACKAGE_PATTERN = re.compile(r"^dev\.atlas\.coach\.qa\.v706b\.[A-Za-z][A-Za-z0-9_.]*$")
SECRET_FIELD_PATTERN = re.compile(r"(?i)\b(?:device_)?token\b\s*[:=]\s*([^\s,}]+)")
BEARER_PATTERN = re.compile(r"(?i)\bBearer\s+[^\s,}]+")


class QaError(RuntimeError):
    pass


def command_output(
    command: Sequence[str], *, cwd: Path | None = None, env: dict[str, str] | None = None,
    timeout: int = ADB_TIMEOUT_SECONDS,
) -> str:
    try:
        result = subprocess.run(
            command, cwd=cwd, env=env, text=True, capture_output=True,
            timeout=timeout, check=False,
        )
    except subprocess.TimeoutExpired:
        raise QaError("COMMAND_TIMEOUT") from None
    if result.returncode != 0:
        raise QaError("COMMAND_FAILED")
    return result.stdout.strip()


def execute(command: Sequence[str], *, cwd: Path | None = None, env: dict[str, str] | None = None) -> None:
    try:
        result = subprocess.run(command, cwd=cwd, env=env, text=True, timeout=1800, check=False)
    except subprocess.TimeoutExpired:
        raise QaError("BUILD_TIMEOUT") from None
    if result.returncode != 0:
        raise QaError("BUILD_FAILED")


def install_apk(serial: str, apk: Path) -> None:
    for attempt in range(4):
        try:
            result = subprocess.run(
                ["adb", "-s", serial, "install", str(apk)],
                text=True, capture_output=True, timeout=INSTALL_TIMEOUT_SECONDS, check=False,
            )
        except subprocess.TimeoutExpired:
            raise QaError("ADB_INSTALL_TIMEOUT") from None
        if result.returncode == 0 and "Success" in result.stdout:
            return
        if attempt < 3:
            time.sleep(1.0 + attempt)
    raise QaError("ADB_INSTALL_FAILED")


def canonical_json_bytes(value: dict) -> bytes:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def verify_build_provenance(
    provenance: dict, expected: dict, *, secrets: Sequence[str] = (),
) -> str:
    if (not isinstance(provenance, dict) or PROVENANCE_FIELDS - provenance.keys()
            or provenance.keys() - PROVENANCE_FIELDS):
        raise QaError("PROVENANCE_FIELD_MISSING")
    for field in ("source_sha", "source_tree_sha"):
        if not isinstance(provenance[field], str) or not re.fullmatch(r"(?:[0-9a-f]{40}|[0-9a-f]{64})", provenance[field]):
            raise QaError("PROVENANCE_GIT_ID_INVALID")
    if provenance["worktree_clean"] is not True:
        raise QaError("PROVENANCE_DIRTY_WORKTREE")
    if (not isinstance(provenance["variant"], str) or not provenance["variant"]
            or not isinstance(provenance["application_id"], str)
            or not isinstance(provenance["test_application_id"], str)
            or not isinstance(provenance["version_code"], int)
            or isinstance(provenance["version_code"], bool) or provenance["version_code"] < 0
            or not isinstance(provenance["version_name"], str)
            or not isinstance(provenance["compile_sdk"], str)
            or not re.fullmatch(r"[0-9]+(?:\.[0-9]+)?", provenance["compile_sdk"])
            or not isinstance(provenance["min_sdk"], int)
            or not isinstance(provenance["target_sdk"], int)
            or not isinstance(provenance["scenario_id"], str)):
        raise QaError("PROVENANCE_FIELD_INVALID")
    if any(key not in provenance or provenance[key] != value for key, value in expected.items()):
        raise QaError("PROVENANCE_REQUEST_MISMATCH")
    encoded = canonical_json_bytes(provenance)
    if any(value and value.encode("utf-8") in encoded for value in secrets):
        raise QaError("PROVENANCE_PRIVATE_VALUE_LEAK")
    return hashlib.sha256(encoded).hexdigest()


def read_apk_pair_provenance(app_apk: Path, test_apk: Path) -> dict:
    records: list[tuple[dict, bytes, str]] = []
    for apk in (app_apk, test_apk):
        if not apk.is_file():
            raise QaError("APK_MISSING")
        try:
            with zipfile.ZipFile(apk) as archive:
                if (archive.namelist().count(PROVENANCE_ASSET) != 1
                        or archive.namelist().count("AndroidManifest.xml") != 1):
                    raise QaError("APK_PROVENANCE_MISSING_OR_INVALID")
                raw = archive.read(PROVENANCE_ASSET)
                manifest_bytes = archive.read("AndroidManifest.xml")
            parsed = json.loads(raw.decode("utf-8"))
        except (OSError, zipfile.BadZipFile, KeyError, UnicodeDecodeError, json.JSONDecodeError):
            raise QaError("APK_PROVENANCE_MISSING_OR_INVALID") from None
        if not isinstance(parsed, dict):
            raise QaError("APK_PROVENANCE_INVALID")
        records.append((parsed, raw, hashlib.sha256(manifest_bytes).hexdigest()))
    if records[0][0] != records[1][0] or records[0][1] != records[1][1]:
        raise QaError("APK_PROVENANCE_PAIR_MISMATCH")
    return {
        "manifest": records[0][0],
        "android_manifest_sha256": records[0][2],
        "android_test_manifest_sha256": records[1][2],
    }


def verify_installed_hashes(built_hashes: dict[str, str], installed_hashes: dict[str, str]) -> None:
    required = {"app", "android_test"}
    if set(built_hashes) != required or set(installed_hashes) != required:
        raise QaError("INSTALLED_APK_HASH_MISSING")
    if any(not APK_SHA_RE.fullmatch(str(value)) for value in (*built_hashes.values(), *installed_hashes.values())):
        raise QaError("INSTALLED_APK_HASH_INVALID")
    if built_hashes != installed_hashes:
        raise QaError("INSTALLED_APK_HASH_MISMATCH")


def verify_signing_certificate(expected: str, actual: str) -> None:
    if (not isinstance(expected, str) or not APK_SHA_RE.fullmatch(expected)
            or not isinstance(actual, str) or not APK_SHA_RE.fullmatch(actual)
            or expected != actual):
        raise QaError("SIGNING_CERTIFICATE_MISMATCH")


def verify_complete_proof_provenance(provenance: dict, *, private_values: Sequence[str] = ()) -> None:
    required = PROVENANCE_FIELDS | QA_PROOF_FIELDS
    if (not isinstance(provenance, dict) or required - provenance.keys()
            or provenance.keys() - required):
        raise QaError("PROOF_PROVENANCE_FIELD_MISSING")
    for field in ("source_sha", "source_tree_sha"):
        if not isinstance(provenance[field], str) or not re.fullmatch(r"(?:[0-9a-f]{40}|[0-9a-f]{64})", provenance[field]):
            raise QaError("PROOF_PROVENANCE_SOURCE_INVALID")
    for field in (
        "app_apk_sha256", "android_test_apk_sha256", "signing_cert_sha256",
        "build_provenance_sha256", "android_manifest_sha256", "android_test_manifest_sha256",
    ):
        if not isinstance(provenance[field], str) or not APK_SHA_RE.fullmatch(provenance[field]):
            raise QaError("PROOF_PROVENANCE_HASH_MISSING")
    installed_hashes = provenance["installed_apk_sha256"]
    if (not isinstance(installed_hashes, dict)
            or set(installed_hashes) != {"app", "android_test"}
            or any(not isinstance(value, str) or not APK_SHA_RE.fullmatch(value)
                   for value in installed_hashes.values())):
        raise QaError("PROOF_PROVENANCE_INSTALLED_APK_HASH_INVALID")
    if (installed_hashes["app"] != provenance["app_apk_sha256"]
            or installed_hashes["android_test"] != provenance["android_test_apk_sha256"]):
        raise QaError("PROOF_PROVENANCE_INSTALLED_APK_HASH_MISMATCH")
    endpoint = provenance["endpoint"]
    if (provenance["worktree_clean"] is not True or provenance["variant"] != "qa"
            or not isinstance(provenance["application_id"], str)
            or not isinstance(provenance["test_application_id"], str)
            or provenance["test_application_id"] != f"{provenance['application_id']}.test"
            or not isinstance(provenance["version_code"], int)
            or isinstance(provenance["version_code"], bool) or provenance["version_code"] < 0
            or not isinstance(provenance["version_name"], str)
            or not isinstance(provenance["compile_sdk"], str)
            or not re.fullmatch(r"[0-9]+(?:\.[0-9]+)?", provenance["compile_sdk"])
            or not isinstance(provenance["min_sdk"], int)
            or not isinstance(provenance["target_sdk"], int)
            or not isinstance(provenance["runtime_sdk"], int)
            or isinstance(provenance["runtime_sdk"], bool) or provenance["runtime_sdk"] <= 0
            or not isinstance(provenance["scenario_id"], str) or not provenance["scenario_id"]
            or provenance["network"] not in {"local_emulator_bridge", "local_adb_reverse", "public_wss"}
            or not isinstance(endpoint, dict) or set(endpoint) != {"scheme", "host", "port"}
            or endpoint.get("scheme") not in {"ws", "wss"}
            or not isinstance(endpoint.get("host"), str) or not endpoint["host"]
            or not isinstance(endpoint.get("port"), int) or isinstance(endpoint.get("port"), bool)
            or not 1 <= endpoint["port"] <= 65535):
        raise QaError("PROOF_PROVENANCE_FIELD_INVALID")
    if provenance["installed_apk_verified"] is not True or provenance["verification_result"] != "VERIFIED":
        raise QaError("PROOF_PROVENANCE_NOT_VERIFIED")
    encoded = json.dumps(provenance, ensure_ascii=False, sort_keys=True)
    if (any(value and value in encoded for value in private_values)
            or SECRET_FIELD_PATTERN.search(encoded) or BEARER_PATTERN.search(encoded)):
        raise QaError("PROOF_PROVENANCE_PRIVATE_VALUE_LEAK")


def instrument_after_verification(
    serial: str, app_id: str, test_id: str, app_apk: Path | str, test_apk: Path | str,
    manifest: dict, built_hashes: dict[str, str], cert_sha256: str, instrumenter,
):
    installed = verify_installed_artifacts(
        serial, app_id, test_id, Path(app_apk), Path(test_apk), manifest, built_hashes, cert_sha256,
    )
    return instrumenter(installed)


def validate_suffix(value: str) -> str:
    if not SUFFIX_PATTERN.fullmatch(value):
        raise QaError("qa suffix must start with .qa.v706b. and contain only valid package segments")
    package = f"{PRODUCT_PACKAGE}{value}"
    if package == PRODUCT_PACKAGE or not QA_PACKAGE_PATTERN.fullmatch(package):
        raise QaError("refusing a package outside the isolated dev.atlas.coach.qa.v706b namespace")
    return value


def validate_scenario_id(value: str) -> str:
    if (not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,79}", value)
            or re.search(r"(?i)(token|secret|serial|bearer)", value)):
        raise QaError("SCENARIO_ID_INVALID_OR_SENSITIVE")
    return value


def resolve_network_endpoint(relay_url: str, network: str | None, endpoint_json: str | None) -> tuple[str, dict]:
    if endpoint_json:
        try:
            endpoint = json.loads(endpoint_json)
        except json.JSONDecodeError:
            raise QaError("PROOF_ENDPOINT_INVALID") from None
        if not isinstance(endpoint, dict):
            raise QaError("PROOF_ENDPOINT_INVALID")
    else:
        try:
            parsed = urlsplit(relay_url)
            if (parsed.scheme not in {"ws", "wss"} or not parsed.hostname
                    or parsed.username is not None or parsed.password is not None
                    or parsed.query or parsed.fragment):
                raise ValueError
            endpoint = {
                "scheme": parsed.scheme,
                "host": parsed.hostname,
                "port": parsed.port or (443 if parsed.scheme == "wss" else 80),
            }
        except (TypeError, ValueError):
            raise QaError("PROOF_ENDPOINT_INVALID") from None
    resolved_network = network or (
        "public_wss" if endpoint.get("scheme") == "wss" else
        "local_emulator_bridge" if endpoint.get("host") == "10.0.2.2" else
        "local_adb_reverse"
    )
    return resolved_network, endpoint


def git_checkout_identity() -> dict[str, object]:
    source_sha = command_output(["git", "rev-parse", "HEAD"], cwd=ROOT)
    source_tree_sha = command_output(["git", "rev-parse", "HEAD^{tree}"], cwd=ROOT)
    status = command_output(
        ["git", "status", "--porcelain", "--untracked-files=all", "--", ".", ":!**/build/**"],
        cwd=ROOT,
    )
    if (not re.fullmatch(r"(?:[0-9a-f]{40}|[0-9a-f]{64})", source_sha)
            or not re.fullmatch(r"(?:[0-9a-f]{40}|[0-9a-f]{64})", source_tree_sha)):
        raise QaError("GIT_PROVENANCE_UNAVAILABLE")
    return {
        "source_sha": source_sha,
        "source_tree_sha": source_tree_sha,
        "worktree_clean": not bool(status),
    }


def build(suffix: str, scenario_id: str = "unspecified") -> tuple[Path, Path, str]:
    suffix = validate_suffix(suffix)
    scenario_id = validate_scenario_id(scenario_id)
    command = [
        "./gradlew",
        ":app:assembleQa",
        ":app:assembleQaAndroidTest",
        "-PqaTestBuildType=qa",
        f"-PqaSuffix={suffix}",
        f"-PatlasScenarioId={scenario_id}",
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


def sdk_build_tools() -> list[Path]:
    roots = [os.environ.get("ANDROID_SDK_ROOT"), os.environ.get("ANDROID_HOME")]
    local_properties = ANDROID_ROOT / "local.properties"
    if local_properties.is_file():
        for line in local_properties.read_text(encoding="utf-8").splitlines():
            if line.startswith("sdk.dir="):
                value = line.partition("=")[2].replace("\\:", ":").replace("\\\\", "\\")
                roots.append(value)
                break
    result: list[Path] = []
    for root in filter(None, roots):
        directory = Path(root) / "build-tools"
        if directory.is_dir():
            result.extend(path for path in directory.iterdir() if path.is_dir())
    return sorted(result, key=lambda path: path.name, reverse=True)


def locate_apksigner() -> Path:
    executable = shutil.which("apksigner")
    candidates = [Path(executable)] if executable else []
    candidates.extend(directory / "apksigner" for directory in sdk_build_tools())
    for candidate in candidates:
        if candidate.is_file():
            return candidate
    raise QaError("APKSIGNER_MISSING")


def apk_metadata(apk: Path, aapt: Path | None = None) -> dict[str, str | int | None]:
    aapt = aapt or locate_aapt()
    output = command_output([str(aapt), "dump", "badging", str(apk)])
    package = re.search(r"^package: name='([^']+)'", output, re.MULTILINE)
    version_code = re.search(r"\bversionCode='([^']*)'", output)
    version_name = re.search(r"\bversionName='([^']*)'", output)
    min_sdk = re.search(r"^sdkVersion:'([^']+)'", output, re.MULTILINE)
    target_sdk = re.search(r"^targetSdkVersion:'([^']+)'", output, re.MULTILINE)
    compile_sdk = re.search(r"\bcompileSdkVersion='([^']+)'", output)
    if not package or not version_code or not version_name or not min_sdk or not target_sdk or not compile_sdk:
        raise QaError("APK_METADATA_UNAVAILABLE")
    try:
        parsed_version_code = int(version_code.group(1)) if version_code.group(1) else None
        parsed_min_sdk = int(min_sdk.group(1))
        parsed_target_sdk = int(target_sdk.group(1))
        parsed_compile_sdk = int(compile_sdk.group(1))
    except ValueError:
        raise QaError("APK_METADATA_INVALID") from None
    return {
        "application_id": package.group(1),
        "version_code": parsed_version_code,
        "version_name": version_name.group(1) or None,
        "min_sdk": parsed_min_sdk,
        "target_sdk": parsed_target_sdk,
        "compile_sdk": parsed_compile_sdk,
    }


def apk_signing_certificate_sha256(apk: Path) -> str:
    output = command_output([str(locate_apksigner()), "verify", "--print-certs", str(apk)])
    match = re.search(
        r"(?:Signer #\d+|V\d+ Signer): certificate SHA-256 digest:\s*([0-9a-fA-F:]+)",
        output,
    )
    if not match:
        raise QaError("SIGNING_CERTIFICATE_UNAVAILABLE")
    digest = match.group(1).replace(":", "").lower()
    if not re.fullmatch(r"[0-9a-f]{64}", digest):
        raise QaError("SIGNING_CERTIFICATE_INVALID")
    return digest


def apk_package(apk: Path, aapt: Path) -> str:
    return str(apk_metadata(apk, aapt)["application_id"])


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


def instrumentation_failure_reason(
    returncode: int, output: str, *, expected_count: int = EXPECTED_INSTRUMENTATION_TESTS,
) -> str | None:
    if returncode != 0:
        return f"adb exited with status {returncode}"
    if re.search(r"FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed", output, re.IGNORECASE):
        return "instrumentation output contains a failure marker"
    summary = re.search(r"\bOK\s+\((\d+)\s+tests?\)", output)
    if summary is None or int(summary.group(1)) != expected_count:
        return f"instrumentation output does not report exactly {expected_count} successful test"
    return None


def adb(serial: str, *arguments: str) -> str:
    return command_output(["adb", "-s", serial, *arguments])


def _adb_stream_sha256(serial: str, *arguments: str, timeout: int = 60) -> str:
    digest = hashlib.sha256()
    stderr = bytearray()
    process = None
    selector = selectors.DefaultSelector()
    try:
        process = subprocess.Popen(
            ["adb", "-s", serial, *arguments], stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        )
        assert process.stdout is not None and process.stderr is not None
        selector.register(process.stdout, selectors.EVENT_READ, "stdout")
        selector.register(process.stderr, selectors.EVENT_READ, "stderr")
        deadline = time.monotonic() + timeout
        while selector.get_map():
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise QaError("ADB_COMMAND_TIMEOUT")
            for key, _ in selector.select(min(remaining, 1.0)):
                chunk = os.read(key.fileobj.fileno(), 1024 * 1024)
                if not chunk:
                    selector.unregister(key.fileobj)
                    continue
                if key.data == "stdout":
                    digest.update(chunk)
                elif len(stderr) < 4096:
                    stderr.extend(chunk[:4096 - len(stderr)])
        if process.wait(timeout=max(0.1, deadline - time.monotonic())) != 0:
            raise QaError("ADB_COMMAND_FAILED")
        return digest.hexdigest()
    except subprocess.TimeoutExpired:
        raise QaError("ADB_COMMAND_TIMEOUT") from None
    except OSError:
        raise QaError("ADB_COMMAND_FAILED") from None
    finally:
        selector.close()
        if process is not None and process.poll() is None:
            process.kill()
            process.wait()


def _installed_package_paths(serial: str, package: str) -> list[str]:
    output = adb(serial, "shell", "pm", "path", package)
    paths = [line.partition(":")[2].strip() for line in output.splitlines() if line.startswith("package:")]
    base_paths = [path for path in paths if path.endswith("/base.apk")]
    if (len(base_paths) != 1
            or not re.fullmatch(r"/data/app/[A-Za-z0-9_./~=-]+/base\.apk", base_paths[0])):
        raise QaError("INSTALLED_APK_PATH_INVALID")
    return base_paths


def installed_apk_sha256(serial: str, package: str) -> str:
    remote_path = _installed_package_paths(serial, package)[0]
    return _adb_stream_sha256(serial, "exec-out", "cat", remote_path)


def _package_dump(serial: str, package: str) -> str:
    output = adb(serial, "shell", "dumpsys", "package", package)
    if not re.search(rf"(?m)^\s*Package \[{re.escape(package)}\](?:\s|\()", output):
        raise QaError("INSTALLED_PACKAGE_MISSING")
    return output


def _package_version(output: str) -> tuple[int, str]:
    code = re.search(r"\bversionCode=(\d+)\b", output)
    name = re.search(r"\bversionName=([^\s]+)", output)
    if not code or not name:
        raise QaError("INSTALLED_PACKAGE_VERSION_UNAVAILABLE")
    return int(code.group(1)), name.group(1)


def _package_signing_certificate(output: str) -> str | None:
    match = re.search(
        r"(?i)(?:certificate|cert)[^\n]*?sha-?256(?:\s+digest)?\s*[:=]\s*([0-9a-f:]{64,95})",
        output,
    )
    if not match:
        return None
    value = match.group(1).replace(":", "").lower()
    return value if re.fullmatch(r"[0-9a-f]{64}", value) else None


def verify_installed_artifacts(
    serial: str, app_id: str, test_id: str, app_apk: Path, test_apk: Path,
    manifest: dict, built_hashes: dict[str, str], cert_sha256: str,
) -> dict:
    if not app_apk.is_file() or not test_apk.is_file():
        raise QaError("APK_MISSING")
    if manifest.get("application_id") != app_id or manifest.get("test_application_id") != test_id:
        raise QaError("PROVENANCE_PACKAGE_MISMATCH")
    app_dump = _package_dump(serial, app_id)
    test_dump = _package_dump(serial, test_id)
    app_version = _package_version(app_dump)
    if (app_version[0] != manifest.get("version_code")
            or app_version[1] != manifest.get("version_name")):
        raise QaError("INSTALLED_PACKAGE_VERSION_MISMATCH")
    installed_hashes = {
        "app": installed_apk_sha256(serial, app_id),
        "android_test": installed_apk_sha256(serial, test_id),
    }
    verify_installed_hashes(built_hashes, installed_hashes)
    for installed_dump in (app_dump, test_dump):
        installed_cert = _package_signing_certificate(installed_dump)
        if installed_cert is not None:
            verify_signing_certificate(cert_sha256, installed_cert)
    return {
        "installed_apk_verified": True,
        "installed_apk_sha256": installed_hashes,
        "verification_result": "VERIFIED",
    }


def verify_built_apk_metadata(
    app_apk: Path, test_apk: Path, manifest: dict, aapt: Path | None = None,
) -> None:
    app_metadata = apk_metadata(app_apk, aapt)
    test_metadata = apk_metadata(test_apk, aapt)
    if (app_metadata["application_id"] != manifest["application_id"]
            or app_metadata["version_code"] != manifest["version_code"]
            or app_metadata["version_name"] != manifest["version_name"]
            or app_metadata["min_sdk"] != manifest["min_sdk"]
            or app_metadata["target_sdk"] != manifest["target_sdk"]
            or app_metadata["compile_sdk"] != int(str(manifest["compile_sdk"]).split(".", 1)[0])):
        raise QaError("BUILT_APP_METADATA_MISMATCH")
    if (test_metadata["application_id"] != manifest["test_application_id"]
            or test_metadata["min_sdk"] != manifest["min_sdk"]
            or test_metadata["target_sdk"] != manifest["target_sdk"]
            or test_metadata["compile_sdk"] != int(str(manifest["compile_sdk"]).split(".", 1)[0])):
        raise QaError("BUILT_TEST_PACKAGE_MISMATCH")


def verify_device(serial: str) -> int:
    if not serial.strip() or any(character.isspace() for character in serial):
        raise QaError("an explicit adb serial is required")
    state = adb(serial, "get-state")
    if state != "device":
        raise QaError(f"explicit adb target is not ready (state={state})")
    is_emulator = adb(serial, "shell", "getprop", "ro.kernel.qemu").strip() == "1"
    if not is_emulator and os.environ.get("ATLAS_ALLOW_PHYSICAL_QA") != "1":
        raise QaError("physical QA is disabled; set ATLAS_ALLOW_PHYSICAL_QA=1 for this explicit serial")
    try:
        return int(adb(serial, "shell", "getprop", "ro.build.version.sdk"))
    except ValueError:
        raise QaError("DEVICE_RUNTIME_SDK_UNAVAILABLE") from None


def require_not_installed(serial: str, package: str) -> None:
    if package == PRODUCT_PACKAGE or not QA_PACKAGE_PATTERN.fullmatch(package):
        raise QaError("refusing a product or non-V7-06B QA package target")
    try:
        result = subprocess.run(
            ["adb", "-s", serial, "shell", "pm", "path", package],
            text=True, capture_output=True, timeout=ADB_TIMEOUT_SECONDS, check=False,
        )
    except subprocess.TimeoutExpired:
        raise QaError("ADB_COMMAND_TIMEOUT") from None
    if result.returncode == 0 and result.stdout.strip().startswith("package:"):
        raise QaError("QA_PACKAGE_ALREADY_INSTALLED")


def push_private_pairing(serial: str, package: str, pairing_path: Path) -> None:
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        ready = subprocess.run(
            ["adb", "-s", serial, "shell", f"run-as {shlex.quote(package)} true"],
            capture_output=True,
            timeout=ADB_TIMEOUT_SECONDS,
            check=False,
        )
        if ready.returncode == 0:
            break
        time.sleep(0.5)
    else:
        raise QaError("QA app storage did not become available within the bounded wait")
    inner = f"umask 077; mkdir -p files && cat > files/{PAIRING_NAME} && chmod 600 files/{PAIRING_NAME}"
    shell_command = f"run-as {shlex.quote(package)} sh -c {shlex.quote(inner)}"
    result = None
    for attempt in range(4):
        result = subprocess.run(
            ["adb", "-s", serial, "shell", "-T", shell_command],
            input=pairing_path.read_bytes(),
            capture_output=True,
            timeout=ADB_TIMEOUT_SECONDS,
            check=False,
        )
        if result.returncode == 0:
            break
        if attempt < 3:
            time.sleep(1.0)
    if result is None or result.returncode != 0:
        subprocess.run(
            ["adb", "-s", serial, "shell", f"run-as {shlex.quote(package)} rm -f files/{PAIRING_NAME}"],
            check=False,
            capture_output=True,
            timeout=ADB_TIMEOUT_SECONDS,
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
        timeout=ADB_TIMEOUT_SECONDS,
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
    pairing, token = read_pairing(pairing_path)
    secret_values = [token]
    for secret_name in ("core_token_file", "tool_token_file"):
        token_value = Path(stack[secret_name]).read_text(encoding="utf-8").strip()
        if token_value:
            secret_values.append(token_value)
    scenario_id = validate_scenario_id(args.scenario_id or f"v7-06c-android-{args.action}")
    runtime_sdk = verify_device(args.serial)
    if args.expected_runtime_sdk is not None and runtime_sdk != args.expected_runtime_sdk:
        raise QaError("DEVICE_RUNTIME_SDK_MISMATCH")
    network, endpoint = resolve_network_endpoint(relay_url, args.network, args.endpoint_json)
    require_not_installed(args.serial, expected_app_id)
    require_not_installed(args.serial, f"{expected_app_id}.test")
    checkout = git_checkout_identity()
    app_apk, test_apk, app_id = build(suffix, scenario_id)
    if app_id != expected_app_id or not QA_PACKAGE_PATTERN.fullmatch(app_id):
        raise QaError("BUILT_APP_PACKAGE_MISMATCH")
    app_test_id = f"{app_id}.test"
    require_not_installed(args.serial, app_id)
    require_not_installed(args.serial, app_test_id)

    embedded = read_apk_pair_provenance(app_apk, test_apk)
    manifest = embedded["manifest"]
    expected_provenance = {
        **checkout,
        "variant": "qa",
        "application_id": app_id,
        "test_application_id": app_test_id,
        "scenario_id": scenario_id,
    }
    build_provenance_sha256 = verify_build_provenance(
        manifest, expected_provenance, secrets=(*secret_values, args.serial, pairing["device_id"]),
    )
    verify_built_apk_metadata(app_apk, test_apk, manifest)
    app_cert_sha256 = apk_signing_certificate_sha256(app_apk)
    test_cert_sha256 = apk_signing_certificate_sha256(test_apk)
    verify_signing_certificate(app_cert_sha256, test_cert_sha256)
    built_hashes = {
        "app": sha256_file(app_apk),
        "android_test": sha256_file(test_apk),
    }
    provenance_result = {
        **manifest,
        "runtime_sdk": runtime_sdk,
        "app_apk_sha256": built_hashes["app"],
        "android_test_apk_sha256": built_hashes["android_test"],
        "signing_cert_sha256": app_cert_sha256,
        "build_provenance_sha256": build_provenance_sha256,
        "android_manifest_sha256": embedded["android_manifest_sha256"],
        "android_test_manifest_sha256": embedded["android_test_manifest_sha256"],
        "scenario_id": scenario_id,
        "network": network,
        "endpoint": endpoint,
        "installed_apk_verified": False,
        "verification_result": "NOT_VERIFIED",
    }

    print(f"install_target={args.serial} package={app_id} action={args.action}")
    install_apk(args.serial, app_apk)
    install_apk(args.serial, test_apk)

    shell_package = f"{app_id}.test/{TEST_RUNNER}"
    if not QA_PACKAGE_PATTERN.fullmatch(app_id) or app_id == PRODUCT_PACKAGE:
        raise QaError("INSTRUMENTATION_PACKAGE_REFUSED")
    log_path = private_root / f"android-{args.action}-{suffix.rsplit('.', 1)[-1]}.logcat.txt"
    test_result: subprocess.CompletedProcess[str] | None = None
    installed: dict | None = None
    try:
        def start_instrumentation(verification: dict) -> subprocess.CompletedProcess[str]:
            nonlocal installed
            installed = verification
            provenance_result.update(verification)
            provenance_result["verification_result"] = "VERIFIED"
            provenance_result["installed_apk_verified"] = True
            verify_complete_proof_provenance(
                provenance_result, private_values=(*secret_values, args.serial, pairing["device_id"]),
            )
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
            return subprocess.run(
                ["adb", "-s", args.serial, "shell", " ".join(shell_arguments)],
                text=True, capture_output=True, timeout=INSTRUMENTATION_TIMEOUT_SECONDS, check=False,
            )

        test_result = instrument_after_verification(
            args.serial, app_id, app_test_id, app_apk, test_apk, manifest,
            built_hashes, app_cert_sha256, start_instrumentation,
        )
    except subprocess.TimeoutExpired:
        raise QaError("ADB_INSTRUMENTATION_TIMEOUT") from None
    finally:
        if installed is not None:
            try:
                subprocess.run(
                    ["adb", "-s", args.serial, "shell", f"run-as {shlex.quote(app_id)} rm -f files/{PAIRING_NAME}"],
                    check=False, capture_output=True, timeout=ADB_TIMEOUT_SECONDS,
                )
                capture_logcat(args.serial, secret_values, log_path)
            except (OSError, subprocess.TimeoutExpired, QaError):
                raise QaError("ADB_CLEANUP_OR_LOGCAT_FAILED") from None

    if test_result is None:
        raise QaError("instrumentation did not start")
    if test_result.stdout:
        print(sanitized(test_result.stdout, secret_values), end="")
    if test_result.stderr:
        print(sanitized(test_result.stderr, secret_values), file=sys.stderr, end="")
    print(f"logcat_redacted={log_path}")
    instrumentation_output = test_result.stdout + test_result.stderr
    failure_reason = instrumentation_failure_reason(
        test_result.returncode, instrumentation_output, expected_count=EXPECTED_INSTRUMENTATION_TESTS,
    )
    if failure_reason is not None:
        raise QaError("INSTRUMENTATION_RESULT_INVALID")
    print("QA_PROVENANCE_JSON=" + json.dumps(provenance_result, ensure_ascii=False, sort_keys=True, separators=(",", ":")))
    print(f"V7_06B_RUNTIME_PASS action={args.action} package={app_id}")
    return 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    build_parser = subparsers.add_parser("build", help="build the QA app and AndroidTest APKs without ADB")
    build_parser.add_argument("--suffix", required=True, help="unique applicationId suffix, e.g. .qa.v706b.compile")
    build_parser.add_argument("--scenario-id", default="unspecified")

    run_parser = subparsers.add_parser("run", help="build, install and run one disposable QA scenario")
    run_parser.add_argument("--serial", required=True, help="explicit adb serial; no implicit device selection")
    run_parser.add_argument("--action", required=True, choices=("apply", "reject"))
    run_parser.add_argument("--suffix", help="unique suffix; generated if omitted")
    run_parser.add_argument("--stack-file", required=True, type=Path, help="private JSON produced by the isolated backend stack")
    run_parser.add_argument("--scenario-id", help="non-sensitive build scenario label")
    run_parser.add_argument("--network", choices=("local_emulator_bridge", "local_adb_reverse", "public_wss"))
    run_parser.add_argument("--endpoint-json", help="JSON object with scheme, host and port")
    run_parser.add_argument("--expected-runtime-sdk", type=int)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        if args.command == "build":
            build(args.suffix, args.scenario_id)
            return 0
        return run(args)
    except (QaError, OSError) as error:
        print(f"V7-06B QA ERROR: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
