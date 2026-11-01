from __future__ import annotations

import importlib.util
import io
import json
import os
import shutil
import subprocess
import sys
import tarfile
import tempfile
import unittest
from pathlib import Path


HERE = Path(__file__).resolve().parent
SCRIPT = HERE.parent / "atlas-product-backup.sh"
PYTHON_HELPER = HERE.parent / "atlas-product-backup.py"
SPEC = importlib.util.spec_from_file_location("atlas_product_backup", PYTHON_HELPER)
assert SPEC is not None and SPEC.loader is not None
backup_tool = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = backup_tool
SPEC.loader.exec_module(backup_tool)


def make_tar(path: Path, files: dict[str, bytes]) -> None:
    with tarfile.open(path, "w") as archive:
        root = tarfile.TarInfo(".")
        root.type = tarfile.DIRTYPE
        root.mode = 0o700
        archive.addfile(root)
        for name, data in files.items():
            info = tarfile.TarInfo(f"./{name}")
            info.size = len(data)
            info.mode = 0o600
            archive.addfile(info, io.BytesIO(data))
    path.chmod(0o600)


class BackupToolTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory(prefix="atlas-v7-07-test-")
        self.root = Path(self.temporary.name)
        self.root.chmod(0o700)
        self.fixture_counter = 0
        self.bin = self.root / "bin"
        self.bin.mkdir(mode=0o700)
        self.apk = self.root / "atlas-debug.apk"
        self.apk.write_bytes(b"synthetic apk placeholder")
        self.installed_apk = self.root / "installed-base.apk"
        self.installed_apk.write_bytes(b"synthetic installed apk placeholder")
        self.device_tar = self.root / "device.tar"
        make_tar(self.device_tar, {"shared_prefs/example.xml": b"<map/>\n", "files/plans/day-plan-test.json": b"{}\n"})
        self.adb_log = self.root / "adb.jsonl"
        self._write_fake_tools()
        self.env = os.environ.copy()
        self.env.update(
            {
                "PATH": f"{self.bin}{os.pathsep}{self.env.get('PATH', '')}",
                "MOCK_ADB_LOG": str(self.adb_log),
                "MOCK_INSTALLED_APK": str(self.installed_apk),
                "MOCK_DEVICE_TAR": str(self.device_tar),
                "MOCK_DEVICE_LINES": "atlas-test-01 device product:atlas model:AtlasTest",
                "MOCK_SDK": "37",
                "MOCK_VERSION_CODE": "1",
                "MOCK_VERSION_NAME": "0.1.0",
                "MOCK_APK_PACKAGE": "dev.atlas.coach",
                "MOCK_APK_VERSION_CODE": "1",
                "MOCK_APK_VERSION_NAME": "0.1.0",
                "MOCK_CERT": backup_tool.EXPECTED_CERT_SHA256,
                "MOCK_INSTALLED_CERT": backup_tool.EXPECTED_CERT_SHA256,
                "MOCK_LOCAL_CERT": backup_tool.EXPECTED_CERT_SHA256,
            }
        )
        self.assertEqual(Path(shutil.which("adb", path=self.env["PATH"])).resolve(), (self.bin / "adb").resolve())

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def _write_executable(self, name: str, contents: str) -> None:
        path = self.bin / name
        path.write_text(contents, encoding="utf-8")
        path.chmod(0o700)

    def _write_fake_tools(self) -> None:
        self._write_executable(
            "adb",
            """#!/usr/bin/env python3
import json, os, shutil, sys
args = sys.argv[1:]
with open(os.environ['MOCK_ADB_LOG'], 'a', encoding='utf-8') as log:
    log.write(json.dumps(args) + '\\n')
def emit(value):
    sys.stdout.write(value)
if args == ['devices', '-l']:
    emit('List of devices attached\\n' + os.environ['MOCK_DEVICE_LINES'] + '\\n')
    raise SystemExit(0)
serial_args = args[2:] if len(args) >= 2 and args[0] == '-s' else []
if not serial_args:
    raise SystemExit(9)
if serial_args[:3] == ['shell', 'getprop', 'ro.build.version.sdk']:
    emit(os.environ.get('MOCK_SDK', '37') + '\\n')
elif serial_args[:3] == ['shell', 'pm', 'path']:
    emit('package:/data/app/~~fixture==/dev.atlas.coach-fixture==/base.apk\\n')
elif serial_args[:3] == ['shell', 'dumpsys', 'package']:
    emit('Package [dev.atlas.coach] (fixture):\\n versionCode=' + os.environ.get('MOCK_VERSION_CODE', '1') + ' minSdk=26 targetSdk=36\\n versionName=' + os.environ.get('MOCK_VERSION_NAME', '0.1.0') + '\\n')
elif serial_args[0] == 'pull':
    shutil.copyfile(os.environ['MOCK_INSTALLED_APK'], serial_args[2])
elif serial_args[:4] == ['shell', 'run-as', 'dev.atlas.coach', 'id']:
    emit('uid=10123(u0_a123) gid=10123(u0_a123)\\n')
elif serial_args[:4] == ['shell', 'run-as', 'dev.atlas.coach', 'pwd']:
    emit('/data/user/0/dev.atlas.coach\\n')
elif serial_args[:3] == ['shell', 'df', '-k']:
    emit('Filesystem 1K-blocks Used Available Use% Mounted on\\n/dev/block/dm-0 1000000 1000 900000 1% /data\\n')
elif serial_args[:4] == ['shell', 'run-as', 'dev.atlas.coach', 'du']:
    emit('2048 .\\n')
elif serial_args[0] == 'exec-out' and serial_args[1:4] == ['run-as', 'dev.atlas.coach', 'tar']:
    with open(os.environ['MOCK_DEVICE_TAR'], 'rb') as source:
        sys.stdout.buffer.write(source.read())
elif serial_args[:5] == ['shell', 'run-as', 'dev.atlas.coach', 'tar', '-xf']:
    with open(os.environ['MOCK_DEVICE_TAR'], 'wb') as destination:
        destination.write(sys.stdin.buffer.read())
elif serial_args[:3] == ['shell', 'am', 'start']:
    counter = os.environ.get('MOCK_START_COUNT_FILE')
    count = 0
    if counter:
        try:
            with open(counter, 'r', encoding='utf-8') as source:
                count = int(source.read())
        except FileNotFoundError:
            pass
        count += 1
        with open(counter, 'w', encoding='utf-8') as destination:
            destination.write(str(count))
    if os.environ.get('MOCK_FAIL_FIRST_START') == '1' and count == 1:
        emit('Status: error\\n')
    else:
        emit('Status: ok\\nTotalTime: 25\\n')
elif serial_args[:2] == ['shell', 'pidof']:
    emit('12345\\n')
elif serial_args[:3] == ['shell', 'am', 'force-stop']:
    pass
elif serial_args[:3] == ['shell', 'run-as', 'dev.atlas.coach']:
    pass
else:
    sys.stderr.write('unexpected mocked adb command')
    raise SystemExit(8)
""",
        )
        self._write_executable(
            "apksigner",
            """#!/usr/bin/env python3
import os, sys
apk = sys.argv[-1]
cert = os.environ.get('MOCK_INSTALLED_CERT', os.environ['MOCK_CERT']) if apk.endswith('installed-base.apk') else os.environ.get('MOCK_LOCAL_CERT', os.environ['MOCK_CERT'])
print('Signer #1 certificate SHA-256 digest: ' + cert)
""",
        )
        self._write_executable(
            "aapt",
            """#!/usr/bin/env python3
import os
print("package: name='" + os.environ.get('MOCK_APK_PACKAGE', 'dev.atlas.coach') + "' versionCode='" + os.environ.get('MOCK_APK_VERSION_CODE', '1') + "' versionName='" + os.environ.get('MOCK_APK_VERSION_NAME', '0.1.0') + "'")
""",
        )

    def run_tool(self, *arguments: str, env: dict[str, str] | None = None) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [str(SCRIPT), *arguments],
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            env=env or self.env,
            check=False,
        )

    def fixture_pair(self, files: dict[str, bytes] | None = None) -> tuple[Path, Path]:
        self.fixture_counter += 1
        directory = self.root / f"fixture-{self.fixture_counter}"
        directory.mkdir(mode=0o700)
        archive = directory / "dev.atlas.coach-appdata.tar"
        manifest_path = directory / "manifest.json"
        make_tar(archive, files or {"shared_prefs/example.xml": b"<map/>\n"})
        metadata = {
            "version_code": 1,
            "version_name": "0.1.0",
            "certificate_sha256": backup_tool.EXPECTED_CERT_SHA256,
            "android_sdk": 37,
        }
        backup_tool.write_manifest(manifest_path, backup_tool.build_manifest(archive, metadata, "atlas-test-01"))
        return archive, manifest_path

    def log_entries(self) -> list[list[str]]:
        if not self.adb_log.exists():
            return []
        return [json.loads(line) for line in self.adb_log.read_text(encoding="utf-8").splitlines()]

    def test_preflight_is_read_only_and_checks_expected_apk(self) -> None:
        result = self.run_tool("preflight", "--serial", "atlas-test-01", "--apk", str(self.apk))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("PREFLIGHT_OK", result.stdout)
        self.assertIn("version_code=1", result.stdout)
        self.assertIn("serial_sha256=", result.stdout)
        self.assertNotIn("atlas-test-01", result.stdout)
        self.assertFalse(any("force-stop" in command or "install" in command for command in self.log_entries()))

    def test_preflight_refuses_multiple_devices(self) -> None:
        env = self.env | {"MOCK_DEVICE_LINES": "atlas-test-01 device\natlas-test-02 device"}
        result = self.run_tool("preflight", "--serial", "atlas-test-01", "--apk", str(self.apk), env=env)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("exactement un appareil", result.stderr)
        self.assertEqual(self.log_entries(), [["devices", "-l"]])

    def test_preflight_refuses_wrong_package_and_certificate(self) -> None:
        wrong_package = self.env | {"MOCK_APK_PACKAGE": "com.example.other"}
        result = self.run_tool("preflight", "--serial", "atlas-test-01", "--apk", str(self.apk), env=wrong_package)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("paquet APK incorrect", result.stderr)

        bad_cert = "0" * 64
        wrong_cert = self.env | {"MOCK_LOCAL_CERT": bad_cert, "MOCK_INSTALLED_CERT": bad_cert}
        result = self.run_tool("preflight", "--serial", "atlas-test-01", "--apk", str(self.apk), env=wrong_cert)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("certificat", result.stderr.lower())

    def test_preflight_refuses_inconsistent_version(self) -> None:
        env = self.env | {"MOCK_APK_VERSION_NAME": "0.2.0"}
        result = self.run_tool("preflight", "--serial", "atlas-test-01", "--apk", str(self.apk), env=env)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("version", result.stderr.lower())

    def test_backup_refuses_non_private_destination(self) -> None:
        destination = self.root / "public-output"
        destination.mkdir(mode=0o755)
        destination.chmod(0o755)
        result = self.run_tool("backup", "--serial", "atlas-test-01", "--backup-dir", str(destination))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("privée", result.stderr.lower())
        self.assertFalse(any("force-stop" in command for command in self.log_entries()))

    def test_backup_creates_private_archive_and_manifest_without_raw_serial(self) -> None:
        destination = self.root / "backup-output"
        result = self.run_tool("backup", "--serial", "atlas-test-01", "--backup-dir", str(destination))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("BACKUP_OK", result.stdout)
        archive = destination / "dev.atlas.coach-appdata.tar"
        manifest_path = destination / "manifest.json"
        self.assertEqual(destination.stat().st_mode & 0o777, 0o700)
        self.assertEqual(archive.stat().st_mode & 0o777, 0o600)
        self.assertEqual(manifest_path.stat().st_mode & 0o777, 0o600)
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        self.assertNotIn("atlas-test-01", manifest_path.read_text(encoding="utf-8"))
        self.assertEqual(manifest["serial_sha256"], backup_tool.serial_digest("atlas-test-01"))
        backup_tool.verify_archive(archive, manifest_path)
        self.assertTrue(any("force-stop" in command for command in self.log_entries()))

    def test_verify_accepts_synthetic_tar_and_rejects_changed_archive(self) -> None:
        archive, manifest = self.fixture_pair()
        result = self.run_tool("verify", "--archive", str(archive), "--manifest", str(manifest))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("VERIFY_OK", result.stdout)
        make_tar(archive, {"shared_prefs/example.xml": b"different but valid tar\n"})
        result = self.run_tool("verify", "--archive", str(archive), "--manifest", str(manifest))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("empreinte/taille", result.stderr)

        make_tar(archive, {"shared_prefs/example.xml": b"<map/>\n"})
        with archive.open("r+b") as stream:
            stream.write(b"X")
        result = self.run_tool("verify", "--archive", str(archive), "--manifest", str(manifest))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("tar invalide", result.stderr.lower())

    def test_verify_rejects_cache_and_manifest_tampering(self) -> None:
        archive, manifest = self.fixture_pair()
        cache_archive = self.root / "cache.tar"
        make_tar(cache_archive, {"cache/private.tmp": b"must not be archived"})
        result = self.run_tool("verify", "--archive", str(cache_archive), "--manifest", str(manifest))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("cache/code_cache", result.stderr)

        value = json.loads(manifest.read_text(encoding="utf-8"))
        value["entries"][0]["sha256"] = "0" * 64
        manifest.write_text(json.dumps(value), encoding="utf-8")
        manifest.chmod(0o600)
        result = self.run_tool("verify", "--archive", str(archive), "--manifest", str(manifest))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("manifeste altéré", result.stderr)

    def test_verify_refuses_manifest_for_another_package_or_certificate(self) -> None:
        for field, value, expected_message in (
            ("package", "com.example.other", "paquet du manifeste incorrect"),
            ("certificate_sha256", "0" * 64, "certificat du manifeste différent"),
        ):
            with self.subTest(field=field):
                archive, manifest_path = self.fixture_pair()
                manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
                manifest[field] = value
                manifest["integrity_sha256"] = backup_tool.integrity_digest(manifest)
                manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
                manifest_path.chmod(0o600)
                result = self.run_tool("verify", "--archive", str(archive), "--manifest", str(manifest_path))
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(expected_message, result.stderr)

    def test_verify_rejects_symlinks(self) -> None:
        directory = self.root / "symlink-fixture"
        directory.mkdir(mode=0o700)
        archive = directory / "symlink.tar"
        with tarfile.open(archive, "w") as tar:
            link = tarfile.TarInfo("./files/redirect")
            link.type = tarfile.SYMTYPE
            link.linkname = "../../outside"
            tar.addfile(link)
        archive.chmod(0o600)
        _, manifest = self.fixture_pair()
        result = self.run_tool("verify", "--archive", str(archive), "--manifest", str(manifest))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("lien symbolique", result.stderr)

    def test_verify_refuses_absolute_and_parent_paths(self) -> None:
        _, known_manifest = self.fixture_pair()
        for unsafe in ("/etc/passwd", "../outside", "files/../../outside"):
            with self.subTest(path=unsafe):
                directory = self.root / f"unsafe-{len(unsafe)}"
                directory.mkdir(mode=0o700)
                archive = directory / "bad.tar"
                make_tar(archive, {unsafe: b"bad"})
                result = self.run_tool("verify", "--archive", str(archive), "--manifest", str(known_manifest))
                self.assertNotEqual(result.returncode, 0)
                self.assertTrue("chemin" in result.stderr or "composant parent" in result.stderr)

    def test_restore_requires_argument_and_exact_environment_confirmation_before_adb(self) -> None:
        archive, manifest = self.fixture_pair()
        common = ("restore", "--serial", "atlas-test-01", "--archive", str(archive), "--manifest", str(manifest))
        result = self.run_tool(*common)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("--confirm-restore", result.stderr)
        self.assertEqual(self.log_entries(), [])

        result = self.run_tool(*common, "--confirm-restore")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("confirmation explicite", result.stderr)
        self.assertEqual(self.log_entries(), [])

        wrong_env = self.env | {"ATLAS_CONFIRM_RESTORE": "yes"}
        result = self.run_tool(*common, "--confirm-restore", env=wrong_env)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("exactement", result.stderr)
        self.assertEqual(self.log_entries(), [])

    def test_restore_success_keeps_verified_rollback_and_only_targets_atlas(self) -> None:
        original_tar = self.device_tar.read_bytes()
        archive, manifest = self.fixture_pair({"shared_prefs/new.xml": b"<map><boolean name='new'/></map>\n"})
        backup_root = self.root / "rollback-root"
        env = self.env | {
            "ATLAS_CONFIRM_RESTORE": "RESTORE dev.atlas.coach",
            "ATLAS_BACKUP_ROOT": str(backup_root),
        }
        result = self.run_tool(
            "restore",
            "--serial",
            "atlas-test-01",
            "--archive",
            str(archive),
            "--manifest",
            str(manifest),
            "--confirm-restore",
            env=env,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("RESTORE_OK", result.stdout)
        self.assertEqual(self.device_tar.read_bytes(), archive.read_bytes())
        rollback_dirs = list(backup_root.glob("pre-restore-*"))
        self.assertEqual(len(rollback_dirs), 1)
        rollback_archive = rollback_dirs[0] / "dev.atlas.coach-appdata.tar"
        rollback_manifest = rollback_dirs[0] / "manifest.json"
        self.assertEqual(rollback_archive.read_bytes(), original_tar)
        backup_tool.verify_archive(rollback_archive, rollback_manifest)
        commands = [" ".join(command) for command in self.log_entries()]
        self.assertTrue(any("run-as dev.atlas.coach" in command and "find" in command for command in commands))
        self.assertTrue(all("-s atlas-test-01" in command for command in commands if command != "devices -l"))
        self.assertFalse(any("pm clear" in command or "uninstall" in command for command in commands))

    def test_restore_failure_reports_and_reapplies_rollback(self) -> None:
        original_tar = self.device_tar.read_bytes()
        archive, manifest = self.fixture_pair({"shared_prefs/new.xml": b"target data\n"})
        backup_root = self.root / "rollback-root"
        env = self.env | {
            "ATLAS_CONFIRM_RESTORE": "RESTORE dev.atlas.coach",
            "ATLAS_BACKUP_ROOT": str(backup_root),
            "MOCK_FAIL_FIRST_START": "1",
            "MOCK_START_COUNT_FILE": str(self.root / "start-count"),
        }
        result = self.run_tool(
            "restore",
            "--serial",
            "atlas-test-01",
            "--archive",
            str(archive),
            "--manifest",
            str(manifest),
            "--confirm-restore",
            env=env,
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("restauration automatique de l'état précédent réussie", result.stderr)
        self.assertIn("sauvegarde de sécurité:", result.stderr)
        self.assertEqual(self.device_tar.read_bytes(), original_tar)

    def test_plan_rollback_is_display_only(self) -> None:
        directory = self.root / "backup-output"
        directory.mkdir(mode=0o700)
        result = self.run_tool("plan-rollback", "--backup-dir", str(directory))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("aucune commande exécutée", result.stdout)
        self.assertIn("Application d'accueil", result.stdout)
        self.assertEqual(self.log_entries(), [])


if __name__ == "__main__":
    unittest.main()
