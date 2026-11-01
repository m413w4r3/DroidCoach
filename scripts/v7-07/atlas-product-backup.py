#!/usr/bin/env python3
"""Private, app-only ATLAS data backup/restore helper used by the Bash CLI."""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import re
import shlex
import shutil
import stat
import subprocess
import sys
import tarfile
import tempfile
import uuid
from pathlib import Path, PurePosixPath
from typing import Any, BinaryIO


PACKAGE = "dev.atlas.coach"
LAUNCH_COMPONENT = "dev.atlas.coach/dev.atlas.coach.app.MainActivity"
EXPECTED_VERSION_CODE = 1
EXPECTED_VERSION_NAME = "0.1.0"
EXPECTED_CERT_SHA256 = "da3014662eb65e50a87ef364a2e583c331398795b222e3b37b1a16553a00eeee"
SCHEMA_VERSION = 1
APP_DATA_PATH = f"/data/user/0/{PACKAGE}"
CACHE_NAMES = {"cache", "code_cache"}
MANIFEST_FIELDS = {
    "schema_version",
    "package",
    "version_code",
    "version_name",
    "certificate_sha256",
    "android_sdk",
    "created_at",
    "serial_sha256",
    "archive_sha256",
    "archive_size_bytes",
    "entries",
    "integrity_sha256",
}


class BackupError(Exception):
    """An expected refusal or failed backup operation."""


def fail(message: str) -> None:
    raise BackupError(message)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def serial_digest(serial: str) -> str:
    return hashlib.sha256(serial.encode("utf-8")).hexdigest()


def canonical_json(value: Any) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode("utf-8")


def integrity_digest(manifest: dict[str, Any]) -> str:
    body = {key: value for key, value in manifest.items() if key != "integrity_sha256"}
    return hashlib.sha256(canonical_json(body)).hexdigest()


def reject_symlink_components(path: Path) -> None:
    absolute = path.absolute()
    current = Path(absolute.anchor)
    for part in absolute.parts[1:]:
        current = current / part
        try:
            mode = current.lstat().st_mode
        except FileNotFoundError:
            continue
        if stat.S_ISLNK(mode):
            fail(f"refus de chemin contenant un lien symbolique: {current}")


def require_private_directory(path: Path, *, create: bool = False) -> Path:
    reject_symlink_components(path)
    if create and not path.exists():
        try:
            path.mkdir(parents=True, mode=0o700)
        except FileExistsError:
            pass
    try:
        info = path.lstat()
    except FileNotFoundError:
        fail(f"répertoire inexistant: {path}")
    if not stat.S_ISDIR(info.st_mode) or stat.S_ISLNK(info.st_mode):
        fail(f"la destination n'est pas un répertoire privé: {path}")
    if info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) & 0o077:
        fail(f"la destination doit appartenir à l'utilisateur courant et être privée (0700): {path}")
    return path


def require_private_file(path: Path) -> None:
    reject_symlink_components(path)
    try:
        info = path.lstat()
    except FileNotFoundError:
        fail(f"fichier introuvable: {path}")
    if not stat.S_ISREG(info.st_mode) or stat.S_ISLNK(info.st_mode):
        fail(f"refus de fichier non régulier ou symbolique: {path}")
    if info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) & 0o077:
        fail(f"le fichier doit appartenir à l'utilisateur courant et être privé (0600): {path}")
    require_private_directory(path.parent)


def run_capture(command: list[str], *, label: str, input_data: bytes | None = None) -> bytes:
    try:
        result = subprocess.run(
            command,
            input=input_data,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )
    except FileNotFoundError:
        fail(f"outil requis introuvable pendant {label}: {command[0]}")
    if result.returncode != 0:
        fail(f"échec de {label} (code {result.returncode}); sortie masquée par sécurité")
    return result.stdout


def run_adb(serial: str | None, arguments: list[str], *, label: str, input_file: BinaryIO | None = None) -> bytes:
    command = ["adb"]
    if serial is not None:
        command.extend(["-s", serial])
    command.extend(arguments)
    if input_file is None:
        return run_capture(command, label=label)
    try:
        result = subprocess.run(
            command,
            stdin=input_file,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )
    except FileNotFoundError:
        fail(f"outil requis introuvable pendant {label}: adb")
    if result.returncode != 0:
        fail(f"échec de {label} (code {result.returncode}); sortie masquée par sécurité")
    return result.stdout


def run_adb_to_file(serial: str, arguments: list[str], destination: Path, *, label: str) -> None:
    command = ["adb", "-s", serial, *arguments]
    try:
        with destination.open("wb") as output:
            os.chmod(destination, 0o600)
            result = subprocess.run(command, stdout=output, stderr=subprocess.PIPE, check=False)
    except FileNotFoundError:
        fail(f"outil requis introuvable pendant {label}: adb")
    if result.returncode != 0:
        fail(f"échec de {label} (code {result.returncode}); sortie masquée par sécurité")


def ensure_adb_available() -> None:
    if shutil.which("adb") is None:
        fail("adb est introuvable dans PATH")


def validate_serial(serial: str) -> None:
    if not re.fullmatch(r"[A-Za-z0-9._:-]{1,128}", serial):
        fail("serial invalide")


def check_device_roster(serial: str) -> None:
    validate_serial(serial)
    ensure_adb_available()
    raw = run_adb(None, ["devices", "-l"], label="inventaire ADB")
    rows: list[tuple[str, str]] = []
    for line in raw.decode("utf-8", errors="replace").splitlines():
        line = line.strip()
        if not line or line.startswith("List of devices attached"):
            continue
        fields = line.split()
        if len(fields) >= 2:
            rows.append((fields[0], fields[1]))
        else:
            fail("inventaire ADB illisible")
    if len(rows) != 1:
        fail("refus: ADB doit exposer exactement un appareil, quel que soit son état")
    if rows[0] != (serial, "device"):
        fail("refus: le seul appareil ADB disponible n'est pas le serial ciblé et prêt")


def adb_text(serial: str, arguments: list[str], *, label: str) -> str:
    return run_adb(serial, arguments, label=label).decode("utf-8", errors="replace").strip()


def parse_app_version(dump: str) -> tuple[int, str]:
    code_match = re.search(r"\bversionCode=(\d+)\b", dump)
    name_match = re.search(r"\bversionName=([^\s]+)", dump)
    if not code_match or not name_match or name_match.group(1) in {"null", "(null)"}:
        fail("versionCode/versionName installés impossibles à lire")
    return int(code_match.group(1)), name_match.group(1)


def parse_disk_available(output: str, *, label: str) -> int:
    for line in reversed(output.splitlines()):
        fields = line.split()
        numbers = [int(value) for value in fields if value.isdigit()]
        if len(numbers) >= 3:
            return numbers[2] * 1024
    fail(f"espace libre illisible ({label})")


def parse_du_bytes(output: str) -> int:
    fields = output.strip().split()
    if not fields or not fields[0].isdigit():
        fail("taille des données applicatives impossible à lire")
    return int(fields[0]) * 1024


def parse_certificate(output: str) -> str:
    match = re.search(
        r"certificate\s+SHA-256(?:\s+digest)?\s*:\s*([0-9a-fA-F]{64})",
        output,
        flags=re.IGNORECASE,
    )
    if not match:
        fail("apksigner n'a pas produit d'empreinte SHA-256 de certificat")
    return match.group(1).lower()


def inspect_apk(apk: Path) -> tuple[str, int, str, str]:
    if apk.is_symlink() or not apk.is_file():
        fail(f"APK local non régulier ou symbolique: {apk}")
    apksigner = shutil.which("apksigner")
    aapt = shutil.which("aapt")
    if apksigner is None or aapt is None:
        fail("Android SDK incomplet: apksigner et aapt doivent être disponibles dans PATH")
    cert_output = run_capture([apksigner, "verify", "--print-certs", str(apk)], label="vérification de signature APK")
    cert = parse_certificate(cert_output.decode("utf-8", errors="replace"))
    badging = run_capture([aapt, "dump", "badging", str(apk)], label="lecture du manifeste APK").decode("utf-8", errors="replace")
    package_match = re.search(r"^package:\s+name='([^']+)'\s+versionCode='(\d+)'\s+versionName='([^']*)'", badging, re.MULTILINE)
    if not package_match:
        fail("aapt n'a pas produit package/versionCode/versionName")
    return package_match.group(1), int(package_match.group(2)), package_match.group(3), cert


def validate_supported_identity(version_code: int, version_name: str, cert: str) -> None:
    if (version_code, version_name) != (EXPECTED_VERSION_CODE, EXPECTED_VERSION_NAME):
        fail(f"version incohérente: attendu {EXPECTED_VERSION_CODE}/{EXPECTED_VERSION_NAME}, reçu {version_code}/{version_name}")
    if cert != EXPECTED_CERT_SHA256:
        fail("certificat différent du certificat debug ATLAS approuvé")


def inspect_installed(serial: str) -> dict[str, Any]:
    check_device_roster(serial)
    sdk_text = adb_text(serial, ["shell", "getprop", "ro.build.version.sdk"], label="lecture du SDK Android")
    if not sdk_text.isdigit() or int(sdk_text) < 26:
        fail("SDK Android invalide ou inférieur au minSdk du produit (26)")
    sdk = int(sdk_text)

    package_paths = adb_text(serial, ["shell", "pm", "path", PACKAGE], label="recherche du paquet installé")
    if not package_paths:
        fail(f"paquet absent: {PACKAGE}")
    base_paths = []
    for line in package_paths.splitlines():
        if not line.startswith("package:"):
            fail("sortie pm path illisible")
        remote = line[len("package:") :]
        if remote.endswith("/base.apk") and remote.startswith("/data/app/") and ".." not in PurePosixPath(remote).parts:
            base_paths.append(remote)
    if len(base_paths) != 1:
        fail("APK base installée introuvable ou chemin ambigu")

    dump = adb_text(serial, ["shell", "dumpsys", "package", PACKAGE], label="lecture de la version installée")
    version_code, version_name = parse_app_version(dump)
    with tempfile.TemporaryDirectory(prefix="atlas-v7-07-installed-apk-") as temporary:
        local_copy = Path(temporary) / "installed-base.apk"
        run_adb(serial, ["pull", base_paths[0], str(local_copy)], label="lecture de l'APK installé")
        if not local_copy.is_file() or local_copy.is_symlink():
            fail("lecture de l'APK installé incomplète")
        apksigner = shutil.which("apksigner")
        if apksigner is None:
            fail("apksigner est introuvable dans PATH")
        cert_output = run_capture([apksigner, "verify", "--print-certs", str(local_copy)], label="vérification du certificat installé")
        cert = parse_certificate(cert_output.decode("utf-8", errors="replace"))

    run_as_id = adb_text(serial, ["shell", "run-as", PACKAGE, "id"], label="vérification de run-as")
    uid_match = re.search(r"\buid=(\d+)\b", run_as_id)
    if not uid_match or int(uid_match.group(1)) == 0:
        fail("run-as indisponible ou identité applicative invalide")
    app_path = adb_text(serial, ["shell", "run-as", PACKAGE, "pwd"], label="vérification du chemin applicatif")
    if app_path != APP_DATA_PATH:
        fail(f"chemin applicatif inattendu: {app_path}")

    disk_output = adb_text(serial, ["shell", "run-as", PACKAGE, "df", "-k", "."], label="vérification de l'espace appareil")
    device_free = parse_disk_available(disk_output, label="appareil")
    size_output = adb_text(serial, ["shell", "run-as", PACKAGE, "du", "-sk", "."], label="estimation de la taille applicative")
    app_bytes = parse_du_bytes(size_output)
    host_free = shutil.disk_usage(tempfile.gettempdir()).free
    safety_margin = 1024 * 1024
    if device_free < safety_margin:
        fail("espace libre appareil inférieur à 1 MiB")
    if host_free < safety_margin:
        fail("espace libre hôte inférieur à 1 MiB")
    validate_supported_identity(version_code, version_name, cert)
    return {
        "package": PACKAGE,
        "version_code": version_code,
        "version_name": version_name,
        "certificate_sha256": cert,
        "android_sdk": sdk,
        "app_bytes_estimate": app_bytes,
        "device_available_bytes": device_free,
        "host_available_bytes": host_free,
    }


def preflight(serial: str, apk: Path) -> dict[str, Any]:
    installed = inspect_installed(serial)
    apk_package, apk_code, apk_name, apk_cert = inspect_apk(apk)
    if apk_package != PACKAGE:
        fail(f"paquet APK incorrect: attendu {PACKAGE}, reçu {apk_package}")
    if (apk_code, apk_name) != (installed["version_code"], installed["version_name"]):
        fail("version de l'APK local différente de la version installée")
    if apk_cert != installed["certificate_sha256"]:
        fail("certificat de l'APK local différent du paquet installé")
    validate_supported_identity(apk_code, apk_name, apk_cert)
    installed["serial_sha256"] = serial_digest(serial)
    return installed


def normalized_tar_path(raw_name: str) -> str:
    if not raw_name or raw_name.startswith(("/", "\\")) or "\\" in raw_name or "\x00" in raw_name:
        fail("archive refusée: chemin absolu ou suspect")
    if re.match(r"^[A-Za-z]:", raw_name) or "//" in raw_name:
        fail("archive refusée: chemin absolu ou suspect")
    if any(ord(character) < 32 or ord(character) == 127 for character in raw_name):
        fail("archive refusée: caractère de contrôle dans un chemin")
    components = raw_name.split("/")
    if any(component == ".." for component in components):
        fail("archive refusée: composant parent '..'")
    components = [component for component in components if component not in {"", "."}]
    if any(component.lower() in CACHE_NAMES for component in components):
        fail("archive refusée: entrée cache/code_cache détectée")
    return "/".join(components)


def inspect_archive(archive: Path) -> tuple[list[dict[str, Any]], str, int]:
    require_private_file(archive)
    archive_hash = sha256_file(archive)
    archive_size = archive.stat().st_size
    entries: list[dict[str, Any]] = []
    seen: set[str] = set()
    try:
        with tarfile.open(archive, mode="r:*") as tar:
            for member in tar:
                path = normalized_tar_path(member.name)
                if path in seen:
                    fail(f"archive refusée: entrée dupliquée ({path or '.'})")
                seen.add(path)
                if member.issym() or member.islnk():
                    fail(f"archive refusée: lien symbolique ou physique ({path or '.'})")
                mode = member.mode
                if mode < 0 or mode > 0o7777 or mode & 0o7000:
                    fail(f"archive refusée: mode spécial invalide ({path or '.'})")
                if member.isdir():
                    continue
                if not member.isfile():
                    fail(f"archive refusée: type d'entrée non régulier ({path or '.'})")
                if not path:
                    fail("archive refusée: le répertoire racine ne peut pas être un fichier")
                source = tar.extractfile(member)
                if source is None:
                    fail(f"archive refusée: contenu illisible ({path})")
                digest = hashlib.sha256()
                measured = 0
                with source:
                    for block in iter(lambda: source.read(1024 * 1024), b""):
                        measured += len(block)
                        digest.update(block)
                if measured != member.size:
                    fail(f"archive refusée: taille incohérente ({path})")
                entries.append({"path": path, "size_bytes": member.size, "sha256": digest.hexdigest(), "mode": mode})
    except (tarfile.TarError, OSError, EOFError) as error:
        fail(f"archive tar invalide ou tronquée ({type(error).__name__})")
    entries.sort(key=lambda item: item["path"])
    return entries, archive_hash, archive_size


def build_manifest(archive: Path, metadata: dict[str, Any], serial: str) -> dict[str, Any]:
    entries, archive_hash, archive_size = inspect_archive(archive)
    manifest: dict[str, Any] = {
        "schema_version": SCHEMA_VERSION,
        "package": PACKAGE,
        "version_code": int(metadata["version_code"]),
        "version_name": str(metadata["version_name"]),
        "certificate_sha256": str(metadata["certificate_sha256"]).lower(),
        "android_sdk": int(metadata["android_sdk"]),
        "created_at": dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
        "serial_sha256": serial_digest(serial),
        "archive_sha256": archive_hash,
        "archive_size_bytes": archive_size,
        "entries": entries,
    }
    manifest["integrity_sha256"] = integrity_digest(manifest)
    return manifest


def write_manifest(path: Path, manifest: dict[str, Any]) -> None:
    encoded = (json.dumps(manifest, sort_keys=True, indent=2, ensure_ascii=True) + "\n").encode("utf-8")
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(descriptor, "wb") as handle:
            handle.write(encoded)
            handle.flush()
            os.fsync(handle.fileno())
    except Exception:
        try:
            path.unlink()
        except OSError:
            pass
        raise
    os.chmod(path, 0o600)


def validate_manifest_shape(manifest: Any) -> dict[str, Any]:
    if not isinstance(manifest, dict) or set(manifest) != MANIFEST_FIELDS:
        fail("manifeste invalide: schéma incomplet ou champs inattendus")
    if manifest["schema_version"] != SCHEMA_VERSION:
        fail("version de schéma manifeste non prise en charge")
    if manifest["package"] != PACKAGE:
        fail(f"paquet du manifeste incorrect: {manifest['package']!r}")
    if manifest["version_code"] != EXPECTED_VERSION_CODE or manifest["version_name"] != EXPECTED_VERSION_NAME:
        fail("version du manifeste incohérente avec la version prise en charge")
    if manifest["certificate_sha256"] != EXPECTED_CERT_SHA256:
        fail("certificat du manifeste différent du certificat ATLAS approuvé")
    for key in ("archive_sha256", "serial_sha256", "integrity_sha256"):
        if not isinstance(manifest[key], str) or not re.fullmatch(r"[0-9a-f]{64}", manifest[key]):
            fail(f"manifeste invalide: {key}")
    for key in ("android_sdk", "archive_size_bytes"):
        if not isinstance(manifest[key], int) or isinstance(manifest[key], bool) or manifest[key] <= 0:
            fail(f"manifeste invalide: {key}")
    if manifest["android_sdk"] < 26:
        fail("SDK du manifeste inférieur au minSdk du produit")
    if not isinstance(manifest["created_at"], str) or not re.fullmatch(r"\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ", manifest["created_at"]):
        fail("date du manifeste invalide")
    entries = manifest["entries"]
    if not isinstance(entries, list):
        fail("manifeste invalide: entries")
    previous = ""
    for entry in entries:
        if not isinstance(entry, dict) or set(entry) != {"path", "size_bytes", "sha256", "mode"}:
            fail("manifeste invalide: entrée de fichier")
        path = entry["path"]
        if not isinstance(path, str) or not path or normalized_tar_path(path) != path or path <= previous:
            fail("manifeste invalide: chemin d'entrée")
        previous = path
        if not isinstance(entry["size_bytes"], int) or isinstance(entry["size_bytes"], bool) or entry["size_bytes"] < 0:
            fail(f"manifeste invalide: taille ({path})")
        if not isinstance(entry["mode"], int) or isinstance(entry["mode"], bool) or entry["mode"] < 0 or entry["mode"] > 0o7777 or entry["mode"] & 0o7000:
            fail(f"manifeste invalide: mode ({path})")
        if not isinstance(entry["sha256"], str) or not re.fullmatch(r"[0-9a-f]{64}", entry["sha256"]):
            fail(f"manifeste invalide: empreinte ({path})")
    if manifest["integrity_sha256"] != integrity_digest(manifest):
        fail("intégrité du manifeste invalide ou manifeste altéré")
    return manifest


def verify_archive(archive: Path, manifest_path: Path) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    entries, archive_hash, archive_size = inspect_archive(archive)
    require_private_file(manifest_path)
    try:
        raw = manifest_path.read_text(encoding="utf-8")
        manifest = json.loads(raw)
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        fail(f"manifeste JSON invalide ({type(error).__name__})")
    validate_manifest_shape(manifest)
    if archive_hash != manifest["archive_sha256"] or archive_size != manifest["archive_size_bytes"]:
        fail("empreinte/taille de l'archive différente du manifeste")
    if entries != manifest["entries"]:
        fail("listing ou hashes des fichiers différents du manifeste")
    return manifest, entries


def force_stop(serial: str) -> None:
    check_device_roster(serial)
    run_adb(serial, ["shell", "am", "force-stop", PACKAGE], label="arrêt de l'application")


def capture_device_archive(serial: str, destination: Path) -> None:
    command = [
        "exec-out",
        "run-as",
        PACKAGE,
        "tar",
        "-cf",
        "-",
        "--exclude=./cache",
        "--exclude=./code_cache",
        "--exclude=*/cache",
        "--exclude=*/code_cache",
        ".",
    ]
    run_adb_to_file(serial, command, destination, label="capture tar des données applicatives")
    os.chmod(destination, 0o600)


def backup_into(serial: str, directory: Path, metadata: dict[str, Any]) -> tuple[Path, Path, dict[str, Any]]:
    directory = require_private_directory(directory, create=True)
    archive = directory / f"{PACKAGE}-appdata.tar"
    manifest_path = directory / "manifest.json"
    for target in (archive, manifest_path):
        if target.exists() or target.is_symlink():
            fail(f"refus d'écraser un fichier existant: {target}")
    app_estimate = int(metadata["app_bytes_estimate"])
    required = app_estimate + 1024 * 1024
    if shutil.disk_usage(directory).free < required:
        fail("espace hôte insuffisant dans le répertoire de sauvegarde")
    descriptor = os.open(archive, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    os.close(descriptor)
    try:
        force_stop(serial)
        capture_device_archive(serial, archive)
        manifest = build_manifest(archive, metadata, serial)
        write_manifest(manifest_path, manifest)
    except Exception:
        for target in (manifest_path, archive):
            try:
                target.unlink()
            except OSError:
                pass
        raise
    return archive, manifest_path, manifest


def print_manifest_summary(manifest: dict[str, Any], archive: Path, manifest_path: Path, *, prefix: str) -> None:
    print(prefix)
    print(f"archive={archive}")
    print(f"manifest={manifest_path}")
    print(f"archive_sha256={manifest['archive_sha256']}")
    print(f"files={len(manifest['entries'])}")
    print(f"version={manifest['version_code']}/{manifest['version_name']}")
    print(f"certificate_sha256={manifest['certificate_sha256']}")
    print(f"serial_sha256={manifest['serial_sha256']}")


def check_manifest_matches_device(manifest: dict[str, Any], installed: dict[str, Any]) -> None:
    for key in ("package", "version_code", "version_name", "certificate_sha256"):
        if manifest[key] != installed[key]:
            fail(f"archive {key} différent du paquet installé")
    if manifest["serial_sha256"] != installed["serial_sha256"]:
        fail("archive créée pour un autre serial (empreinte du serial différente)")


def check_restore_space(
    manifest: dict[str, Any], installed: dict[str, Any], current_entries: list[dict[str, Any]]
) -> None:
    target_bytes = sum(entry["size_bytes"] for entry in manifest["entries"])
    reclaimable = sum(entry["size_bytes"] for entry in current_entries)
    available_after_clear = int(installed["device_available_bytes"]) + reclaimable
    if available_after_clear < target_bytes + 1024 * 1024:
        fail("espace appareil insuffisant pour restaurer les fichiers après remplacement")


def restore_confirmation() -> None:
    expected = f"RESTORE {PACKAGE}"
    supplied = os.environ.get("ATLAS_CONFIRM_RESTORE")
    if supplied is not None:
        if supplied != expected:
            fail(f"ATLAS_CONFIRM_RESTORE doit être exactement {expected!r}")
        return
    if not sys.stdin.isatty():
        fail(f"confirmation explicite requise: passez ATLAS_CONFIRM_RESTORE={expected!r} avec --confirm-restore")
    print(f"Cette opération remplacera les fichiers de {PACKAGE}. Tapez exactement {expected!r} pour continuer:", file=sys.stderr)
    answer = sys.stdin.readline().rstrip("\r\n")
    if answer != expected:
        fail("confirmation refusée")


def create_rollback_directory() -> Path:
    explicit_root = os.environ.get("ATLAS_BACKUP_ROOT")
    if explicit_root:
        root = Path(explicit_root).expanduser()
    else:
        state_root = Path(os.environ.get("XDG_STATE_HOME", str(Path.home() / ".local" / "state"))).expanduser()
        root = state_root / "atlas-product-backups"
    require_private_directory(root, create=True)
    timestamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    directory = root / f"pre-restore-{timestamp}-{uuid.uuid4().hex[:8]}"
    directory.mkdir(mode=0o700)
    os.chmod(directory, 0o700)
    return directory


def run_shell_script(serial: str, script: str, *, label: str) -> None:
    quoted_script = shlex.quote(script)
    run_adb(serial, ["shell", "run-as", PACKAGE, "sh", "-c", quoted_script], label=label)


def clear_non_cache_files(serial: str) -> None:
    check_device_roster(serial)
    script = "find . -type d \\( -name cache -o -name code_cache \\) -prune -o -type f -exec rm -f '{}' \\;"
    run_shell_script(serial, script, label="suppression des fichiers applicatifs courants")


def extract_archive(serial: str, archive: Path) -> None:
    check_device_roster(serial)
    with archive.open("rb") as source:
        run_adb(serial, ["shell", "run-as", PACKAGE, "tar", "-xf", "-"], label="extraction des fichiers applicatifs", input_file=source)


def verify_device_contents(serial: str, expected_entries: list[dict[str, Any]], scratch: Path) -> None:
    descriptor = os.open(scratch, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    os.close(descriptor)
    try:
        capture_device_archive(serial, scratch)
        observed, _, _ = inspect_archive(scratch)
        expected_hashes = [
            {key: entry[key] for key in ("path", "size_bytes", "sha256")}
            for entry in expected_entries
        ]
        observed_hashes = [
            {key: entry[key] for key in ("path", "size_bytes", "sha256")}
            for entry in observed
        ]
        if observed_hashes != expected_hashes:
            fail("hashes ou listing des fichiers après restauration différents de l'archive")
    finally:
        try:
            scratch.unlink()
        except OSError:
            pass


def start_and_check_app(serial: str) -> None:
    check_device_roster(serial)
    output = adb_text(
        serial,
        ["shell", "am", "start", "-W", "-n", LAUNCH_COMPONENT],
        label="démarrage de l'application",
    )
    if not re.search(r"\bStatus:\s*ok\b", output, flags=re.IGNORECASE):
        fail("l'activité de lancement ATLAS n'a pas confirmé son démarrage")
    pid = adb_text(serial, ["shell", "pidof", PACKAGE], label="vérification du processus applicatif")
    if not re.fullmatch(r"\d+(?:\s+\d+)*", pid):
        fail("le processus ATLAS ne répond pas après le démarrage")


def apply_and_verify(serial: str, archive: Path, expected_entries: list[dict[str, Any]], scratch: Path) -> None:
    force_stop(serial)
    clear_non_cache_files(serial)
    extract_archive(serial, archive)
    verify_device_contents(serial, expected_entries, scratch)
    start_and_check_app(serial)


def restore(serial: str, archive: Path, manifest_path: Path) -> None:
    manifest, entries = verify_archive(archive, manifest_path)
    restore_confirmation()
    installed = inspect_installed(serial)
    installed["serial_sha256"] = serial_digest(serial)
    check_manifest_matches_device(manifest, installed)
    rollback_directory = create_rollback_directory()
    try:
        rollback_archive, rollback_manifest_path, rollback_manifest = backup_into(serial, rollback_directory, installed)
    except Exception as original:
        try:
            start_and_check_app(serial)
        except Exception:
            pass
        raise BackupError(
            f"sauvegarde de sécurité impossible; aucune donnée restaurée: {original}; "
            f"répertoire rollback réservé: {rollback_directory}"
        ) from original

    try:
        check_restore_space(manifest, installed, rollback_manifest["entries"])
    except Exception as original:
        try:
            start_and_check_app(serial)
        except Exception:
            pass
        raise BackupError(
            f"restauration refusée avant modification: {original}; sauvegarde de sécurité conservée: {rollback_directory} "
            f"(manifest: {rollback_manifest_path})"
        ) from original

    mutated = False
    try:
        mutated = True
        apply_and_verify(serial, archive, entries, rollback_directory / ".post-restore-check.tar")
    except Exception as original:
        rollback_result = "échec; intervention manuelle requise"
        try:
            apply_and_verify(
                serial,
                rollback_archive,
                rollback_manifest["entries"],
                rollback_directory / ".rollback-check.tar",
            )
            rollback_result = "restauration automatique de l'état précédent réussie"
        except Exception as rollback_error:
            rollback_result = f"restauration automatique échouée ({rollback_error})"
        raise BackupError(
            f"restauration échouée: {original}; {rollback_result}; sauvegarde de sécurité: {rollback_directory} "
            f"(manifest: {rollback_manifest_path})"
        ) from original
    if mutated:
        print(f"RESTORE_OK package={PACKAGE}")
        print(f"rollback_backup={rollback_directory}")
        print(f"rollback_manifest={rollback_manifest_path}")


def do_preflight(args: argparse.Namespace) -> None:
    result = preflight(args.serial, args.apk)
    print("PREFLIGHT_OK")
    for key in (
        "package",
        "version_code",
        "version_name",
        "certificate_sha256",
        "android_sdk",
        "app_bytes_estimate",
        "device_available_bytes",
        "host_available_bytes",
        "serial_sha256",
    ):
        print(f"{key}={result[key]}")


def do_backup(args: argparse.Namespace) -> None:
    metadata = inspect_installed(args.serial)
    directory = require_private_directory(args.backup_dir, create=True)
    archive, manifest_path, manifest = backup_into(args.serial, directory, metadata)
    print_manifest_summary(manifest, archive, manifest_path, prefix="BACKUP_OK")


def do_verify(args: argparse.Namespace) -> None:
    manifest, _ = verify_archive(args.archive, args.manifest)
    print("VERIFY_OK")
    print(f"package={manifest['package']}")
    print(f"version={manifest['version_code']}/{manifest['version_name']}")
    print(f"archive_sha256={manifest['archive_sha256']}")
    print(f"files={len(manifest['entries'])}")


def do_plan_rollback(args: argparse.Namespace) -> None:
    directory = require_private_directory(args.backup_dir)
    print("PLAN_ROLLBACK (aucune commande exécutée)")
    print(f"sauvegarde_app={directory}")
    print("1. Vérifier que l'APK précédent est bien dev.atlas.coach, versionCode 1 / versionName 0.1.0.")
    print("2. Vérifier son certificat SHA-256: da3014662eb65e50a87ef364a2e583c331398795b222e3b37b1a16553a00eeee.")
    print("3. Sur l'appareil voulu uniquement: adb -s <SERIAL> install -r <APK_PRECEDENT>.")
    print("4. Dans Paramètres Android > Applications > Applications par défaut > Application d'accueil, sélectionner le launcher précédent.")
    print("5. Vérifier l'ouverture de l'écran d'accueil et conserver la sauvegarde app-only tant que les données ne sont pas validées.")


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(prog="atlas-product-backup.sh", description="Sauvegarde/restauration privée app-only d'ATLAS.")
    commands = root.add_subparsers(dest="command", required=True)

    pre = commands.add_parser("preflight", help="vérifie l'appareil et l'APK sans modifier l'appareil")
    pre.add_argument("--serial", required=True)
    pre.add_argument("--apk", required=True, type=Path)
    pre.set_defaults(handler=do_preflight)

    backup = commands.add_parser("backup", help="capture les données privées du paquet ATLAS")
    backup.add_argument("--serial", required=True)
    backup.add_argument("--backup-dir", required=True, type=Path)
    backup.set_defaults(handler=do_backup)

    verify = commands.add_parser("verify", help="vérifie le hash, le listing et le manifeste")
    verify.add_argument("--archive", required=True, type=Path)
    verify.add_argument("--manifest", required=True, type=Path)
    verify.set_defaults(handler=do_verify)

    restore_parser = commands.add_parser("restore", help="remplace les fichiers du seul paquet ATLAS")
    restore_parser.add_argument("--serial", required=True)
    restore_parser.add_argument("--archive", required=True, type=Path)
    restore_parser.add_argument("--manifest", required=True, type=Path)
    restore_parser.add_argument("--confirm-restore", required=True, action="store_true")
    restore_parser.set_defaults(handler=lambda args: restore(args.serial, args.archive, args.manifest))

    rollback = commands.add_parser("plan-rollback", help="affiche une procédure de retour APK/HOME sans l'exécuter")
    rollback.add_argument("--backup-dir", required=True, type=Path)
    rollback.set_defaults(handler=do_plan_rollback)
    return root


def main(argv: list[str] | None = None) -> int:
    args = parser().parse_args(argv)
    try:
        args.handler(args)
    except BackupError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 2
    except OSError as error:
        print(f"ERROR: erreur système ({type(error).__name__})", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
