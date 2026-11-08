from __future__ import annotations

import json
import os
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import unittest
import uuid
from datetime import datetime, timedelta, timezone
from unittest.mock import patch


REPO_ROOT = Path(__file__).resolve().parents[3]
BACKEND_ROOT = REPO_ROOT / "backend"
BACKEND_SCRIPTS = REPO_ROOT / "scripts" / "v7-06c" / "backend"
ANDROID_SCRIPTS = REPO_ROOT / "scripts" / "v7-06c" / "android"
ANDROID_RUNNER_SCRIPTS = REPO_ROOT / "scripts" / "v7-06b" / "android"
for _path in (str(BACKEND_ROOT), str(BACKEND_SCRIPTS), str(ANDROID_SCRIPTS), str(ANDROID_RUNNER_SCRIPTS)):
    if _path not in sys.path:
        sys.path.insert(0, _path)

from atlas_backend.store import SQLiteStore  # noqa: E402
import run_scenario  # noqa: E402
import run_backend_e2e  # noqa: E402
import run_qa  # noqa: E402
import stack as stack_module  # noqa: E402
from stack import (  # noqa: E402
    PINNED_RELAY_IMAGE,
    Stack,
    StackError,
    choose_ports,
    load_and_verify_root,
    terminate_record,
    validate_android_relay_url,
)


class CoreOracleTests(unittest.TestCase):
    def make_database(self, directory: Path, action: str, *, mutation: str | None = None) -> Path:
        path = directory / "core.sqlite3"
        SQLiteStore(path)
        expected_proposal = "APPLIED" if action == "apply" else "REJECTED"
        expected_ack = expected_proposal
        applied_version = 3 if action == "apply" else None
        revision_id = str(uuid.uuid4()) if action == "apply" else None
        scope_hash = "sha256:" + "b" * 64 if action == "apply" else None
        effect_ids = ["PLAN_APPLY:synthetic"] if action == "apply" else []
        plan_id = "synthetic-plan"
        proposal_id = "synthetic-proposal"
        ack_id = "synthetic-ack"
        base_start = 599 if mutation == "modified_v2" else 600
        proposal_json = {
            "confirmation_mode": "A0_USER_REVIEW",
            "auto_application": "NONE_AT_CORE",
            "changes": [{"op": "reschedule", "target_start_minute": 610}],
        }
        connection = sqlite3.connect(path)
        connection.execute("PRAGMA foreign_keys=ON")
        connection.execute(
            "INSERT INTO agent_proposals_v4 VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            (proposal_id, "synthetic-idempotency", "a" * 64, plan_id, 2,
             "plan_revision.propose", "2026-01-01T10:00:00Z", "2026-01-01T10:01:00Z",
             expected_proposal, json.dumps(proposal_json, separators=(",", ":")), "synthetic-device"),
        )
        if mutation == "wrong_plan_version" and action == "apply":
            applied_version = 4
        connection.execute(
            "INSERT INTO agent_proposal_events_v4 VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
            (ack_id, proposal_id, 1, expected_ack, "APPLIED" if action == "apply" else "USER_REJECTED",
             applied_version, revision_id, scope_hash, json.dumps(effect_ids),
             "2026-01-01T10:00:30Z", "2026-01-01T10:00:31Z", "c" * 64),
        )
        if mutation == "second_ack":
            connection.execute(
                "INSERT INTO agent_proposal_events_v4 VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                ("synthetic-ack-2", proposal_id, 2, expected_ack,
                 "APPLIED" if action == "apply" else "USER_REJECTED", applied_version,
                 revision_id, scope_hash, json.dumps(effect_ids), "2026-01-01T10:00:32Z",
                 "2026-01-01T10:00:33Z", "d" * 64),
            )
        versions = [2, 3] if action == "apply" and mutation != "missing_v3" else [2]
        for version in versions:
            start_minute = base_start if version == 2 else 610
            snapshot_revision = "synthetic-revision-v2" if version == 2 else revision_id
            if version == 3 and mutation == "wrong_v3_revision":
                snapshot_revision = str(uuid.uuid4())
            observed_at = "2026-01-01T10:00:00Z"
            if version == 3 and mutation == "future_v3_observed_at":
                observed_at = (datetime.now(timezone.utc) + timedelta(minutes=5)).isoformat()
            projection = {"activities": [{"scheduled_local": {
                "start_minute": start_minute, "duration_minutes": 45,
            }}], "current_revision_id": snapshot_revision}
            connection.execute(
                "INSERT INTO day_plan_snapshots_v4 VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                ("synthetic-device", plan_id, "2026-01-01", "Europe/Paris", version,
                 snapshot_revision, 4, observed_at, "2026-01-01T10:00:01Z",
                 2, "e" * 64, json.dumps(projection, separators=(",", ":"))),
            )
        for category in ("DAY_PLAN", "ACTIVITY_STATUS"):
            for version, state in ((2, "GRANTED"), (3, "REVOKED")):
                connection.execute(
                    "INSERT INTO consent_state_events_v4 VALUES(?,?,?,?,?,?,?,?)",
                    (f"{category}-{version}", "synthetic-device", category, version, state,
                     "2026-01-01T10:00:00Z", "ANDROID", "f" * 64),
                )
            current_version, current_state = (3, "REVOKED")
            if mutation == "consent_not_revoked" and category == "DAY_PLAN":
                current_version, current_state = (2, "GRANTED")
            connection.execute(
                "INSERT INTO consent_states_v4 VALUES(?,?,?,?,?,?)",
                ("synthetic-device", category, current_version, current_state,
                 "2026-01-01T10:00:00Z", "ANDROID"),
            )
        connection.commit()
        connection.close()
        return path

    def test_apply_and_reject_core_oracles_pass(self) -> None:
        for action in ("apply", "reject"):
            with self.subTest(action=action), tempfile.TemporaryDirectory() as temporary:
                result = run_scenario._read_core_state(self.make_database(Path(temporary), action), action)
                self.assertEqual(result["proposal_state"], "APPLIED" if action == "apply" else "REJECTED")
                self.assertEqual(result["ack_event_count"], 1)
                self.assertEqual(result["pending_proposal_count"], 0)
                self.assertEqual(result["snapshot_versions"], [2, 3] if action == "apply" else [2])
                self.assertEqual(result["applied_plan_version"], 3 if action == "apply" else None)

    def test_core_oracle_rejects_required_falsifications(self) -> None:
        for action, mutation in (
            ("apply", "wrong_plan_version"),
            ("apply", "missing_v3"),
            ("apply", "second_ack"),
            ("apply", "modified_v2"),
            ("apply", "wrong_v3_revision"),
            ("apply", "future_v3_observed_at"),
            ("reject", "consent_not_revoked"),
        ):
            with self.subTest(action=action, mutation=mutation), tempfile.TemporaryDirectory() as temporary:
                database = self.make_database(Path(temporary), action, mutation=mutation)
                with self.assertRaises(run_scenario.ScenarioFailure):
                    run_scenario._read_core_state(database, action)


class LifecycleTests(unittest.TestCase):
    def _valid_root(self, namespace: Path, name: str = "stack") -> Path:
        namespace.mkdir(mode=0o700, exist_ok=True)
        root = namespace / name
        root.mkdir(mode=0o700)
        marker = "synthetic-owner-marker"
        (root / "owner.marker").write_text(marker + "\n", encoding="utf-8")
        (root / "lifecycle.json").write_text(json.dumps({
            "schema": 1, "scenario": "android-apply", "private_root": str(root),
            "marker": marker, "processes": {}, "relay_container": None,
        }), encoding="utf-8")
        os.chmod(root / "owner.marker", 0o600)
        os.chmod(root / "lifecycle.json", 0o600)
        return root

    def test_lifecycle_rejects_outside_namespace_symlink_and_marker_mismatch(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary) / "private"
            base.mkdir(mode=0o700)
            outside = Path(temporary) / "outside" / "stack"
            outside.parent.mkdir()
            outside.mkdir()
            with self.assertRaises(StackError):
                load_and_verify_root(outside, base=base)

            real_root = self._valid_root(base, "real")
            alias = base / "alias"
            alias.symlink_to(real_root, target_is_directory=True)
            with self.assertRaises(StackError):
                load_and_verify_root(alias, base=base)

            mismatched = self._valid_root(base, "mismatched")
            lifecycle_path = mismatched / "lifecycle.json"
            lifecycle = json.loads(lifecycle_path.read_text(encoding="utf-8"))
            lifecycle["marker"] = "different-marker"
            lifecycle_path.write_text(json.dumps(lifecycle), encoding="utf-8")
            os.chmod(lifecycle_path, 0o600)
            with self.assertRaises(StackError):
                load_and_verify_root(mismatched, base=base)

    def test_terminate_refuses_owner_environment_mismatch_without_signalling(self) -> None:
        record = {"name": "gateway", "pid": 111, "pgid": 111}
        with patch("stack.read_proc_identity", return_value=(set(), "openclaw-gateway")), \
                patch("stack.os.killpg") as kill_group:
            with self.assertRaises(StackError):
                terminate_record(record, "owner")
        kill_group.assert_not_called()


class StackPortTests(unittest.TestCase):
    def test_pinned_relay_port_validation_and_occupied_port_block_before_preflight(self) -> None:
        for value in (0, -1, 65536):
            with self.subTest(port=value), self.assertRaisesRegex(StackError, "BLOCKED_RELAY_PORT"):
                choose_ports(value)

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "stack"
            stack = Stack(root, "android-apply", relay_port=4400)
            with patch("stack.port_is_free", return_value=False), \
                    patch("stack.preflight") as preflight, \
                    patch.object(stack, "_create_root") as create_root:
                with self.assertRaisesRegex(StackError, "BLOCKED_RELAY_PORT"):
                    stack.start()
            preflight.assert_not_called()
            create_root.assert_not_called()
            self.assertFalse(root.exists())

    def test_pinned_relay_port_is_selected_and_propagated_to_connector(self) -> None:
        with patch("stack.port_is_free", return_value=True):
            default_ports = choose_ports()
        self.assertEqual(default_ports["relay"], 4413)

        with patch("stack.port_is_free", return_value=True):
            ports = choose_ports(4400)
        self.assertEqual(ports["relay"], 4400)
        self.assertEqual(len(set(ports.values())), len(ports))

        stack = Stack(Path("/unused"), "android-apply", relay_port=4400)
        stack._base_env = lambda: {}  # type: ignore[method-assign]
        stack.connector_state_dir = Path("/unused/connector")
        stack.server_id = "synthetic-server"
        stack.tool_token = "synthetic-tool-token"
        stack.core_token_file = Path("/unused/core-token")
        env = stack._connector_env(ports["relay"], ports["metrics"], ports["core"])
        self.assertEqual(env["ATLAS_RELAY_URL"], "ws://127.0.0.1:4400")

        with tempfile.TemporaryDirectory() as temporary:
            stack.root = Path(temporary)
            stack.relay_container = "synthetic-relay"
            calls: list[list[str]] = []

            def fake_run_capture(arguments: list[str], **_kwargs: object) -> subprocess.CompletedProcess[str]:
                calls.append(arguments)
                if arguments[:3] == ["docker", "image", "inspect"]:
                    return subprocess.CompletedProcess(arguments, 0, PINNED_RELAY_IMAGE + "\n", "")
                if arguments[1] == "run":
                    return subprocess.CompletedProcess(arguments, 0, "synthetic-container-id\n", "")
                return subprocess.CompletedProcess(
                    arguments, 0, f"{PINNED_RELAY_IMAGE}|{stack.marker}\n", "",
                )

            with patch("stack.run_capture", side_effect=fake_run_capture), \
                    patch("stack.wait_http") as wait_http:
                stack._start_relay_container(ports["relay"])
            publish = calls[1][calls[1].index("--publish") + 1]
            self.assertEqual(publish, "127.0.0.1:4400:4000/tcp")
            wait_http.assert_called_once_with("http://127.0.0.1:4400/health", timeout=40)

    def test_existing_root_rejects_different_pinned_relay_port(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "stack"
            root.mkdir()
            (root / "stack.json").write_text(json.dumps({
                "ports": {"relay": 4400},
                "relay_url_android": "ws://10.0.2.2:4400",
                "relay_transport": "loopback_ws",
            }), encoding="utf-8")
            lifecycle = {"scenario": "android-apply", "marker": "synthetic"}
            with patch("stack.validate_private_root", return_value=root), \
                    patch("stack.load_and_verify_root", return_value=lifecycle):
                with self.assertRaisesRegex(StackError, "RELAY_PORT_MISMATCH"):
                    stack_module.start_command(
                        "android-apply", str(root), "10.0.2.2", None, 4413,
                    )


class RedactionAndCliTests(unittest.TestCase):
    def test_backend_relay_client_status_handling(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            config = Path(temporary) / "client.json"
            config.write_text("{}", encoding="utf-8")
            completed = subprocess.CompletedProcess(
                args=["node", "client.mjs"], returncode=0,
                stdout=json.dumps({"status": "PASS", "error_code": None}), stderr="",
            )
            with patch("run_backend_e2e.subprocess.run", return_value=completed):
                self.assertEqual(run_backend_e2e.run_client(config)["status"], "PASS")

    def test_redaction_proof_uses_hashed_device_id_and_has_no_token(self) -> None:
        device_id = "synthetic-device-identity"
        token = "synthetic-bearer-token-value"
        proof = {"device_id_hash": run_scenario.sha256_text(device_id), "status": "PASS"}
        run_scenario._assert_no_secret_leak(proof, [token], {"device_id": device_id})
        encoded = json.dumps(proof)
        self.assertNotIn(device_id, encoded)
        self.assertNotIn(token, encoded)
        with self.assertRaises(run_scenario.ScenarioFailure):
            run_scenario._assert_no_secret_leak({**proof, "diagnostic": f"Bearer {token}"}, [token],
                                                 {"device_id": device_id})

    def test_suffix_and_package_validation(self) -> None:
        generated = run_scenario.action_suffix("apply")
        self.assertRegex(generated, r"^\.qa\.v706b\.v706capply\.[a-f][0-9a-f]{11}$")
        self.assertEqual(run_scenario.validate_target_package("dev.atlas.coach" + generated),
                         "dev.atlas.coach" + generated)
        for value in (
            "dev.atlas.coach",
            "dev.atlas.coach.qa.v706b.v706capply.invalid",
            "dev.atlas.coach.qa.other.v706creject.0123456789ab",
        ):
            with self.subTest(value=value), self.assertRaises(run_scenario.ScenarioFailure):
                run_scenario.validate_target_package(value)
        with self.assertRaises(run_scenario.ScenarioFailure):
            run_scenario.action_suffix("apply", ".qa.v706b.v706creject.0123456789ab")
        with self.assertRaises(run_scenario.ScenarioFailure):
            run_scenario.action_suffix("apply", ".qa.v706b.v706capply.0123456789ab")
        self.assertEqual(run_scenario.action_suffix("apply", ".qa.v706b.v706capply.a7f3c21e"),
                         ".qa.v706b.v706capply.a7f3c21e")
        self.assertEqual(
            run_scenario.validate_target_package("dev.atlas.coach.qa.v706b.v706creject.b4d9e602"),
            "dev.atlas.coach.qa.v706b.v706creject.b4d9e602",
        )
        with self.assertRaises(run_scenario.ScenarioFailure):
            run_scenario.action_suffix("apply", "")

    def test_public_wss_requires_explicit_no_reverse_and_records_endpoint(self) -> None:
        stack = {"relay_url_android": "wss://all.example.ts.net:8443", "relay_transport": "public_wss"}
        with self.assertRaises(run_scenario.ScenarioFailure):
            run_scenario._relay_mode(stack, type("Args", (), {"relay_url": None, "adb_reverse": None})())
        mode, endpoint = run_scenario._relay_mode(
            stack, type("Args", (), {"relay_url": None, "adb_reverse": False})(),
        )
        self.assertEqual(mode, "public_wss")
        self.assertEqual(endpoint, {"scheme": "wss", "host": "all.example.ts.net", "port": 8443})

    def test_ack_attestation_is_bound_to_core_ack_id_and_state(self) -> None:
        ack_a = "11111111-1111-4111-8111-111111111111"
        ack_b = "22222222-2222-4222-8222-222222222222"
        apply_line = (
            f"step=11 result=PASS ack_replay=identical ack_id={ack_a} ack_state=APPLIED"
        )
        reject_line = (
            f"step=11 result=PASS ack_replay=identical ack_id={ack_b} ack_state=REJECTED"
        )
        selected = run_scenario.select_ack_attestation(
            apply_line + "\n" + reject_line, ack_b, "REJECTED", [], "synthetic-serial", "synthetic-device",
        )
        self.assertEqual(selected, reject_line)
        self.assertEqual(
            run_scenario.select_ack_attestation(
                reject_line, ack_b, "REJECTED", [], "synthetic-serial", "synthetic-device",
            ),
            reject_line,
        )
        for wrong_ack_id, wrong_state in ((ack_a, "REJECTED"), (ack_b, "APPLIED")):
            with self.subTest(ack_id=wrong_ack_id, state=wrong_state), self.assertRaises(run_scenario.ScenarioFailure):
                run_scenario.select_ack_attestation(
                    reject_line, wrong_ack_id, wrong_state, [], "synthetic-serial", "synthetic-device",
                )

    def test_post_hoc_runner_log_requires_pass_marker(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            runner_log = root / "android-reject-b4d9e602.runner.log"
            runner_log.write_text("OK (1 test)\n", encoding="utf-8")
            os.chmod(runner_log, 0o600)
            with self.assertRaises(run_scenario.ScenarioFailure):
                run_scenario._read_existing_runner(
                    root, "reject", ".qa.v706b.v706creject.b4d9e602", "synthetic-serial",
                    "synthetic-device", [], None, None,
                )

    def test_android_relay_url_security_validation(self) -> None:
        self.assertEqual(validate_android_relay_url("ws://127.0.0.1:4413"), "ws://127.0.0.1:4413")
        self.assertEqual(validate_android_relay_url("wss://all.example.ts.net:8443"),
                         "wss://all.example.ts.net:8443")
        self.assertEqual(validate_android_relay_url("wss://all.example.ts.net:8443/relay"),
                         "wss://all.example.ts.net:8443/relay")
        for value in (
            "ws://relay.example:4413", "wss://relay.example:99999", "http://localhost:80",
            "wss://127.0.0.1:8443", "wss://relay.example:8443?", "wss://relay.example:8443#",
            "wss://relay.example:",
        ):
            with self.subTest(value=value), self.assertRaises(StackError):
                validate_android_relay_url(value)
        with self.assertRaises(run_scenario.ScenarioFailure):
            run_scenario.parse_android_relay_url("ws://relay.example:4413")
        with self.assertRaises(run_scenario.ScenarioFailure):
            run_scenario.parse_android_relay_url("wss://localhost:8443")
        for value in ("wss://relay.example:8443?", "wss://relay.example:8443#", "wss://relay.example:"):
            with self.subTest(value=value), self.assertRaises(run_scenario.ScenarioFailure):
                run_scenario.parse_android_relay_url(value)
        self.assertEqual(run_scenario.parse_android_relay_url("ws://10.0.2.2:4413")[0], "loopback_ws")
        self.assertEqual(
            run_scenario.parse_android_relay_url("wss://all.example.ts.net:8443/relay")[0], "public_wss",
        )

    def test_emulator_bridge_requires_selected_emulator(self) -> None:
        endpoint = {"scheme": "ws", "host": "10.0.2.2", "port": 4413}
        with self.assertRaises(run_scenario.ScenarioFailure):
            run_scenario._validate_device_relay(endpoint, {"is_emulator": False}, None)
        run_scenario._validate_device_relay(endpoint, {"is_emulator": True}, None)
        loopback = {"scheme": "ws", "host": "127.0.0.1", "port": 4413}
        with self.assertRaises(run_scenario.ScenarioFailure):
            run_scenario._validate_device_relay(loopback, {"is_emulator": True}, None)
        run_scenario._validate_device_relay(loopback, {"is_emulator": False}, True)

    def test_tool_trace_requires_exact_definitions_correlation_and_stop(self) -> None:
        tool_names = sorted(run_scenario.ALLOWED_TOOLS)
        first = {
            "model": "openclaw/synthetic-agent",
            "requested_tool_count": 6,
            "requested_tool_names": tool_names,
            "returned_tool_names": ["atlas_get_day_plan", "atlas_propose_plan_revision"],
            "returned_tool_call_id_hashes": ["hash-call-1"],
            "role_tool_call_id_hashes": [],
            "role_tool_present": False,
            "finish_reason": "tool_calls",
        }
        second = {
            "model": "openclaw/synthetic-agent",
            "requested_tool_count": 6,
            "requested_tool_names": tool_names,
            "returned_tool_names": [],
            "returned_tool_call_id_hashes": [],
            "role_tool_call_id_hashes": ["hash-call-1"],
            "role_tool_present": True,
            "finish_reason": "stop",
        }
        with tempfile.TemporaryDirectory() as temporary:
            trace = Path(temporary) / "trace.jsonl"
            trace.write_text("\n".join(json.dumps(item) for item in (first, second)) + "\n", encoding="utf-8")
            os.chmod(trace, 0o600)
            self.assertTrue(run_scenario.verify_tool_trace(trace, "synthetic-agent")["tool_call_ids_correlated"])
            invalid = [dict(first, requested_tool_names=["exec", *tool_names[1:]]) , second]
            trace.write_text("\n".join(json.dumps(item) for item in invalid) + "\n", encoding="utf-8")
            with self.assertRaises(run_scenario.ScenarioFailure):
                run_scenario.verify_tool_trace(trace, "synthetic-agent")
            invalid = [first, dict(second, requested_tool_names=tool_names[:5], requested_tool_count=5)]
            trace.write_text("\n".join(json.dumps(item) for item in invalid) + "\n", encoding="utf-8")
            with self.assertRaises(run_scenario.ScenarioFailure):
                run_scenario.verify_tool_trace(trace, "synthetic-agent")

    def test_static_python_and_node_syntax(self) -> None:
        python_files = [
            *sorted((REPO_ROOT / "scripts/v7-06c/backend").glob("*.py")),
            *sorted((REPO_ROOT / "scripts/v7-06c/android").glob("*.py")),
            *sorted((REPO_ROOT / "scripts/v7-06c/tests").glob("*.py")),
        ]
        compiled = subprocess.run(
            [sys.executable, "-m", "py_compile", *map(str, python_files)],
            cwd=REPO_ROOT, text=True, capture_output=True, check=False,
        )
        self.assertEqual(compiled.returncode, 0, compiled.stdout + compiled.stderr)
        checked = subprocess.run(
            ["node", "--check", str(REPO_ROOT / "integrations/relay/pc/scripts/v7-06c-relay-client.mjs")],
            cwd=REPO_ROOT, text=True, capture_output=True, check=False,
        )
        self.assertEqual(checked.returncode, 0, checked.stdout + checked.stderr)


class ApkProvenanceTests(unittest.TestCase):
    def valid_manifest(self) -> dict:
        return {
            "source_sha": "a" * 40,
            "source_tree_sha": "b" * 40,
            "worktree_clean": True,
            "variant": "qa",
            "application_id": "dev.atlas.coach.qa.v706b.v706capply.a123456789ab",
            "test_application_id": "dev.atlas.coach.qa.v706b.v706capply.a123456789ab.test",
            "version_code": 1,
            "version_name": "0.1.0-qa",
            "compile_sdk": "37.2",
            "min_sdk": 26,
            "target_sdk": 36,
            "scenario_id": "v7-06c-android-apply",
        }

    def test_dirty_source_tree_variant_package_and_version_are_rejected(self) -> None:
        manifest = self.valid_manifest()
        expected = dict(manifest)
        for field, wrong in (
            ("worktree_clean", False),
            ("source_sha", "c" * 40),
            ("source_tree_sha", "d" * 40),
            ("variant", "debug"),
            ("application_id", "dev.atlas.coach"),
            ("test_application_id", "dev.atlas.coach.test"),
            ("version_code", 9),
            ("version_name", "wrong"),
            ("scenario_id", "different-scenario"),
        ):
            with self.subTest(field=field):
                with self.assertRaises(run_qa.QaError):
                    run_qa.verify_build_provenance({**manifest, field: wrong}, expected)

    def test_missing_manifest_fields_and_secret_or_serial_in_scenario_are_rejected(self) -> None:
        manifest = self.valid_manifest()
        expected = dict(manifest)
        self.assertRegex(
            run_qa.verify_build_provenance(
                expected, expected, secrets=("synthetic-token-value", "synthetic-device-serial"),
            ),
            r"^[0-9a-f]{64}$",
        )
        del manifest["source_tree_sha"]
        with self.assertRaises(run_qa.QaError):
            run_qa.verify_build_provenance(manifest, expected)
        with self.assertRaises(run_qa.QaError):
            run_qa.verify_build_provenance(
                {**expected, "scenario_id": "v7-06c-android-apply"}, expected,
                secrets=("v7-06c-android-apply", "synthetic-device-serial"),
            )

    def test_missing_app_or_test_apk_and_missing_embedded_manifest_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            app = root / "app.apk"
            test = root / "test.apk"
            with self.assertRaisesRegex(run_qa.QaError, "APK_MISSING"):
                run_qa.read_apk_pair_provenance(app, test)
            import zipfile
            with zipfile.ZipFile(app, "w") as archive:
                archive.writestr("AndroidManifest.xml", b"manifest")
                archive.writestr(run_qa.PROVENANCE_ASSET, json.dumps(self.valid_manifest()))
            with self.assertRaisesRegex(run_qa.QaError, "APK_MISSING"):
                run_qa.read_apk_pair_provenance(app, test)

            for apk in (app, test):
                with zipfile.ZipFile(apk, "w") as archive:
                    archive.writestr("AndroidManifest.xml", b"manifest")
            with self.assertRaisesRegex(run_qa.QaError, "APK_PROVENANCE_MISSING_OR_INVALID"):
                run_qa.read_apk_pair_provenance(app, test)

            for apk, value in ((app, {"scenario_id": "apply"}), (test, {"scenario_id": "reject"})):
                with zipfile.ZipFile(apk, "w") as archive:
                    archive.writestr("AndroidManifest.xml", b"manifest")
                    archive.writestr(run_qa.PROVENANCE_ASSET, json.dumps(value))
            with self.assertRaisesRegex(run_qa.QaError, "APK_PROVENANCE_PAIR_MISMATCH"):
                run_qa.read_apk_pair_provenance(app, test)

    def test_installed_app_and_test_hashes_and_signing_certificate_must_match(self) -> None:
        manifest = self.valid_manifest()
        built = {"app": "1" * 64, "android_test": "2" * 64}
        run_qa.verify_installed_hashes(built, dict(built))
        with self.assertRaises(run_qa.QaError):
            run_qa.verify_installed_hashes(built, {"app": "3" * 64, "android_test": built["android_test"]})
        with self.assertRaises(run_qa.QaError):
            run_qa.verify_signing_certificate("a" * 64, "b" * 64)

    def test_complete_proof_gate_rejects_missing_field_before_instrumentation(self) -> None:
        app_id = "dev.atlas.coach.qa.v706b.v706capply.a123456789ab"
        provenance = {
            **self.valid_manifest(),
            "application_id": app_id,
            "test_application_id": f"{app_id}.test",
            "runtime_sdk": 37,
            "app_apk_sha256": "1" * 64,
            "android_test_apk_sha256": "2" * 64,
            "signing_cert_sha256": "3" * 64,
            "build_provenance_sha256": "4" * 64,
            "android_manifest_sha256": "5" * 64,
            "android_test_manifest_sha256": "6" * 64,
            "network": "local_emulator_bridge",
            "endpoint": {"scheme": "ws", "host": "10.0.2.2", "port": 4413},
            "installed_apk_verified": True,
            "installed_apk_sha256": {"app": "1" * 64, "android_test": "2" * 64},
            "verification_result": "VERIFIED",
        }
        run_qa.verify_complete_proof_provenance(provenance)
        instrument = unittest.mock.Mock()

        def invalid_provenance_start(_installed: dict) -> None:
            run_qa.verify_complete_proof_provenance(
                {key: value for key, value in provenance.items() if key != "source_tree_sha"},
            )
            instrument()

        with patch("run_qa.verify_installed_artifacts", return_value={"installed_apk_verified": True}):
            with self.assertRaisesRegex(run_qa.QaError, "PROOF_PROVENANCE_FIELD_MISSING"):
                run_qa.instrument_after_verification(
                    "synthetic-device-serial", app_id, f"{app_id}.test",
                    "app.apk", "test.apk", {}, {"app": "1" * 64, "android_test": "2" * 64},
                    "3" * 64, invalid_provenance_start,
                )
        instrument.assert_not_called()

    def test_complete_proof_gate_accepts_and_validates_installed_apk_hashes(self) -> None:
        app_id = "dev.atlas.coach.qa.v706b.v706capply.a123456789ab"
        verification = {
            "installed_apk_verified": True,
            "installed_apk_sha256": {"app": "1" * 64, "android_test": "2" * 64},
            "verification_result": "VERIFIED",
        }
        provenance = {
            **self.valid_manifest(),
            "runtime_sdk": 37,
            "app_apk_sha256": "1" * 64,
            "android_test_apk_sha256": "2" * 64,
            "signing_cert_sha256": "3" * 64,
            "build_provenance_sha256": "4" * 64,
            "android_manifest_sha256": "5" * 64,
            "android_test_manifest_sha256": "6" * 64,
            "network": "local_emulator_bridge",
            "endpoint": {"scheme": "ws", "host": "10.0.2.2", "port": 4413},
            **verification,
        }
        run_qa.verify_complete_proof_provenance(provenance)
        run_scenario.require_pass_provenance(provenance)
        with self.assertRaisesRegex(run_qa.QaError, "PROOF_PROVENANCE_FIELD_MISSING"):
            run_qa.verify_complete_proof_provenance({**provenance, "unexpected_field": "rejected"})
        with self.assertRaisesRegex(run_scenario.ScenarioFailure, "APK_PROVENANCE_FIELD_UNKNOWN"):
            run_scenario.require_pass_provenance({**provenance, "unexpected_field": "rejected"})

        with tempfile.TemporaryDirectory() as temporary:
            app_apk = Path(temporary) / "app.apk"
            test_apk = Path(temporary) / "test.apk"
            instrumenter = unittest.mock.Mock()

            def validate_before_instrumentation(installed: dict) -> None:
                complete_provenance = {**provenance, **installed}
                run_qa.verify_complete_proof_provenance(complete_provenance)
                run_scenario.require_pass_provenance(complete_provenance)
                instrumenter()

            with patch("run_qa.verify_installed_artifacts", return_value=verification):
                result = run_qa.instrument_after_verification(
                    "synthetic-device-serial", provenance["application_id"],
                    provenance["test_application_id"], app_apk, test_apk, provenance,
                    {"app": "1" * 64, "android_test": "2" * 64}, "3" * 64,
                    validate_before_instrumentation,
                )
            self.assertIsNone(result)
            instrumenter.assert_called_once()

        missing = dict(provenance)
        del missing["installed_apk_sha256"]
        with self.assertRaisesRegex(run_qa.QaError, "PROOF_PROVENANCE_FIELD_MISSING"):
            run_qa.verify_complete_proof_provenance(missing)
        with self.assertRaises(run_scenario.ScenarioFailure):
            run_scenario.require_pass_provenance(missing)

        for invalid_hashes in (
            None,
            {"app": "1" * 64},
            {"app": "1" * 64, "android_test": "Z" * 64},
            {"app": "9" * 64, "android_test": "2" * 64},
        ):
            with self.subTest(installed_apk_sha256=invalid_hashes):
                invalid = dict(provenance)
                invalid["installed_apk_sha256"] = invalid_hashes
                with self.assertRaises(run_qa.QaError):
                    run_qa.verify_complete_proof_provenance(invalid)
                with self.assertRaises(run_scenario.ScenarioFailure):
                    run_scenario.require_pass_provenance(invalid)

    def test_real_installed_artifact_verifier_result_passes_proof_gate(self) -> None:
        manifest = self.valid_manifest()
        app_id = manifest["application_id"]
        test_id = manifest["test_application_id"]
        built_hashes = {"app": "1" * 64, "android_test": "2" * 64}
        cert_sha256 = "3" * 64

        with tempfile.TemporaryDirectory() as temporary:
            app_apk = Path(temporary) / "app.apk"
            test_apk = Path(temporary) / "test.apk"
            app_apk.write_bytes(b"synthetic-app-apk")
            test_apk.write_bytes(b"synthetic-test-apk")
            with patch(
                "run_qa._package_dump",
                side_effect=[f"Package [{app_id}]\n", f"Package [{test_id}]\n"],
            ), patch("run_qa._package_version", return_value=(1, "0.1.0-qa")), \
                    patch("run_qa.installed_apk_sha256", side_effect=(built_hashes["app"], built_hashes["android_test"])), \
                    patch("run_qa._package_signing_certificate", return_value=cert_sha256):
                verification = run_qa.verify_installed_artifacts(
                    "synthetic-device-serial", app_id, test_id, app_apk, test_apk,
                    manifest, built_hashes, cert_sha256,
                )

        self.assertIs(verification["installed_apk_verified"], True)
        self.assertEqual(verification["verification_result"], "VERIFIED")
        self.assertEqual(verification["installed_apk_sha256"], built_hashes)
        provenance = {
            **manifest,
            "runtime_sdk": 37,
            "app_apk_sha256": built_hashes["app"],
            "android_test_apk_sha256": built_hashes["android_test"],
            "signing_cert_sha256": cert_sha256,
            "build_provenance_sha256": "4" * 64,
            "android_manifest_sha256": "5" * 64,
            "android_test_manifest_sha256": "6" * 64,
            "network": "local_emulator_bridge",
            "endpoint": {"scheme": "ws", "host": "10.0.2.2", "port": 4413},
            **verification,
        }
        run_qa.verify_complete_proof_provenance(provenance)
        run_scenario.require_pass_provenance(provenance)

    def test_failed_install_verification_never_calls_instrumentation(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            app_apk = Path(temporary) / "app.apk"
            test_apk = Path(temporary) / "test.apk"
            app_apk.write_bytes(b"synthetic-app-apk")
            test_apk.write_bytes(b"synthetic-test-apk")
            app_id = "dev.atlas.qa"
            test_id = "dev.atlas.qa.test"
            manifest = {
                "application_id": app_id, "test_application_id": test_id,
                "version_code": 1, "version_name": "0.1.0-qa",
            }
            for failure_code, app_version, app_cert, installed_hashes in (
                ("INSTALLED_PACKAGE_VERSION_MISMATCH", "versionCode=2", None, ("1" * 64, "2" * 64)),
                ("INSTALLED_APK_HASH_MISMATCH", "versionCode=1", None, ("3" * 64, "2" * 64)),
                ("SIGNING_CERTIFICATE_MISMATCH", "versionCode=1", "d" * 64, ("1" * 64, "2" * 64)),
            ):
                with self.subTest(failure_code=failure_code):
                    instrument = unittest.mock.Mock()

                    def package_dump(_serial: str, package: str) -> str:
                        dump = f"Package [{package}]\n {app_version} versionName=0.1.0-qa"
                        return dump + (f"\n certificate SHA-256 digest: {app_cert}" if app_cert else "")

                    with patch("run_qa._package_dump", side_effect=package_dump), \
                            patch("run_qa.installed_apk_sha256", side_effect=installed_hashes):
                        with self.assertRaisesRegex(run_qa.QaError, failure_code):
                            run_qa.instrument_after_verification(
                                "synthetic-device-serial", app_id, test_id,
                                app_apk, test_apk, manifest,
                                {"app": "1" * 64, "android_test": "2" * 64},
                                "c" * 64, instrument,
                            )
                    instrument.assert_not_called()

    def test_instrumentation_requires_exact_test_count_even_with_success_code(self) -> None:
        self.assertEqual(
            run_qa.instrumentation_failure_reason(0, "INSTRUMENTATION_CODE: -1", expected_count=1),
            "instrumentation output does not report exactly 1 successful test",
        )
        self.assertIsNotNone(run_qa.instrumentation_failure_reason(0, "OK (2 tests)", expected_count=1))
        self.assertIsNone(run_qa.instrumentation_failure_reason(0, "OK (1 test)\nINSTRUMENTATION_CODE: -1", expected_count=1))

    def test_post_hoc_provenance_is_inconclusive_and_cannot_be_pass(self) -> None:
        provenance = run_scenario.post_hoc_provenance(
            "public_wss", {"scheme": "wss", "host": "synthetic.example", "port": 8443},
        )
        self.assertEqual(set(provenance), run_scenario.PASS_PROVENANCE_FIELDS)
        self.assertEqual(provenance["verification_result"], "INCONCLUSIVE")
        self.assertFalse(provenance["installed_apk_verified"])
        self.assertIsNone(provenance["app_apk_sha256"])
        self.assertIsNone(provenance["android_test_apk_sha256"])
        self.assertIsNone(provenance["installed_apk_sha256"])
        with self.assertRaises(run_scenario.ScenarioFailure):
            run_scenario.require_pass_provenance(provenance)
        core = {
            "ack_event_count": 1, "proposal_id": "synthetic-proposal", "plan_id": "synthetic-plan",
            "ack_id": "synthetic-ack", "proposal_state": "REJECTED", "base_version": 2,
            "change_count": 1, "target_start_minute": 610, "duration_minutes": 45,
            "ack_state": "REJECTED", "ack_result_code": "USER_REJECTED",
            "applied_plan_version": None, "snapshot_versions": [2], "snapshot_starts": {"2": 600},
            "consent_states": {}, "consent_event_count": 4, "pending_proposal_count": 0,
            "snapshot_count": 1,
        }
        proof = run_scenario._build_proof(
            "reject", {}, {"device_id": "synthetic-device-id"}, {"package": "dev.atlas.qa"},
            "synthetic-runner-marker", core, {}, {}, provenance["endpoint"], provenance["network"],
            {}, "post_hoc_existing_stack", provenance,
        )
        self.assertEqual(proof["status"], "INCONCLUSIVE")
        run_scenario._assert_no_secret_leak(
            proof, ["synthetic-token-value"], {"device_id": "synthetic-device-id"},
            private_values=("synthetic-device-serial",),
        )


if __name__ == "__main__":
    unittest.main()
