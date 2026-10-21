#!/usr/bin/env python3
"""Pure host regressions for D3 metric validity; does not require Android SDK or ADB."""

from __future__ import annotations

import ast
import argparse
import importlib.util
import json
import pathlib
import tempfile
import unittest
from unittest import mock


SCRIPT = pathlib.Path(__file__).with_name("v622-d3-rest-recipe.py")
SPEC = importlib.util.spec_from_file_location("v622_d3_rest_recipe", SCRIPT)
assert SPEC and SPEC.loader
D3 = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(D3)


def proc_stat(pid: int, *, comm: str = "atlas worker", utime: int = 0, stime: int = 0, starttime: int = 1) -> str:
    fields = ["0"] * 20  # Linux stat fields 3 through 22.
    fields[11] = str(utime)
    fields[12] = str(stime)
    fields[19] = str(starttime)
    return f"{pid} ({comm}) " + " ".join(fields)


def cpu(processes: dict[str, tuple[int, int]], threads: dict[str, tuple[int, int, int]] | None = None) -> dict[str, object]:
    return {
        "cpu": {
            "available": True,
            "process_count": len(processes),
            "processes": {pid: {"starttime": start, "ticks": ticks} for pid, (start, ticks) in processes.items()},
            "threads": {
                tid: {"parent_starttime": parent, "starttime": start, "ticks": ticks}
                for tid, (parent, start, ticks) in (threads or {}).items()
            },
        }
    }


def paired(screen: object = "screen-off") -> tuple[dict[str, object], dict[str, object]]:
    common = {
        "candidate_app_sha256": "a" * 64,
        "requested_duration_seconds": 60,
        "actual_duration_seconds": 60.1,
        "screen_state": screen,
    }
    return ({**common, "mode": "local"}, {**common, "mode": "coaching"})


def attestations(
    mode: str,
    *,
    agent: str,
    proactive: str,
    usage_consent: str,
    usage_background: str,
    backend: str,
) -> argparse.Namespace:
    return argparse.Namespace(
        mode=mode,
        agent_state=agent,
        proactive_state=proactive,
        usage_consent=usage_consent,
        usage_background_state=usage_background,
        backend_state=backend,
        confirm_no_focus=True,
        confirm_no_mobility=True,
        confirm_no_e2e=True,
        emergency_pause="off",
    )


class D3RestRecipeTests(unittest.TestCase):
    def test_appops_parser_prefers_package_mode_over_uid_mode(self) -> None:
        output = "Uid mode: GET_USAGE_STATS: allow\nGET_USAGE_STATS: ignore; time=+1h\n"
        self.assertEqual(D3.parse_appops_usage_mode(output), "ignore")

    def test_appops_parser_falls_back_to_uid_then_default(self) -> None:
        self.assertEqual(D3.parse_appops_usage_mode("Uid mode: GET_USAGE_STATS: allow\n"), "allow")
        self.assertEqual(D3.parse_appops_usage_mode("Default mode: ignore\n"), "ignore")

    def test_screen_parser_reads_wakefulness_when_minteractive_is_absent(self) -> None:
        self.assertIs(D3.parse_interactive_state("mWakefulness=Awake\n"), True)
        self.assertIs(D3.parse_interactive_state("mWakefulness=Asleep\n"), False)

    def test_screen_parser_keeps_unknown_wakefulness_unavailable(self) -> None:
        self.assertIsNone(D3.parse_interactive_state("mWakefulness=Dozing\n"))

    def test_proc_stat_preserves_pid_cpu_ticks_and_starttime(self) -> None:
        self.assertEqual(D3.parse_cpu_stat(proc_stat(123, comm="worker ) main", utime=7, stime=3, starttime=99)), (123, 10, 99))

    def test_all_adb_subprocess_calls_route_through_bounded_wrapper(self) -> None:
        tree = ast.parse(SCRIPT.read_text(encoding="utf-8"))
        direct_run_calls = [
            node for node in ast.walk(tree)
            if isinstance(node, ast.Call)
            and isinstance(node.func, ast.Attribute)
            and node.func.attr == "run"
            and isinstance(node.func.value, ast.Name)
            and node.func.value.id == "subprocess"
        ]
        self.assertEqual(len(direct_run_calls), 1)
        run_function = next(
            node for node in tree.body if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) and node.name == "run_bounded"
        )
        self.assertTrue(any(call is direct_run_calls[0] for call in ast.walk(run_function)))

    def test_adb_timeout_becomes_sanitized_blocked(self) -> None:
        with mock.patch.object(D3.subprocess, "run", side_effect=D3.subprocess.TimeoutExpired(cmd="adb private-args", timeout=2)) as run:
            with self.assertRaisesRegex(D3.Blocked, "timed out") as caught:
                D3.run_bounded(["adb", "private-args"], timeout=2, text=True)
        self.assertNotIn("private-args", str(caught.exception))
        self.assertEqual(run.call_args.kwargs["timeout"], 2)

    def test_cpu_delta_accepts_stable_identity_and_uses_exact_counter_delta(self) -> None:
        before = cpu({"100": (500, 20)}, {"100:100": (500, 50, 10)})
        after = cpu({"100": (500, 35)}, {"100:100": (500, 50, 20)})
        self.assertEqual(D3.cpu_delta(before, after, "processes"), round(15 / D3.os.sysconf("SC_CLK_TCK"), 3))
        self.assertEqual(D3.cpu_delta(before, after, "threads"), round(10 / D3.os.sysconf("SC_CLK_TCK"), 3))

    def test_cpu_delta_rejects_pid_reuse_or_process_set_change(self) -> None:
        before = cpu({"100": (500, 20)})
        reused_pid = cpu({"100": (900, 25)})
        changed_set = cpu({"100": (500, 25), "101": (700, 1)})
        self.assertEqual(D3.cpu_delta(before, reused_pid, "processes"), "unavailable_process_identity_changed")
        self.assertEqual(D3.cpu_delta(before, changed_set, "processes"), "unavailable_process_identity_changed")

    def test_thread_metric_rejects_thread_identity_change(self) -> None:
        before = cpu({"100": (500, 20)}, {"100:100": (500, 50, 10)})
        after = cpu({"100": (500, 25)}, {"100:100": (500, 50, 15), "100:102": (500, 80, 2)})
        self.assertEqual(D3.cpu_delta(before, after, "processes"), round(5 / D3.os.sysconf("SC_CLK_TCK"), 3))
        self.assertEqual(D3.cpu_delta(before, after, "threads"), "unavailable_thread_identity_changed")

    def test_cpu_counter_decrease_is_unavailable(self) -> None:
        before = cpu({"100": (500, 20), "101": (700, 10)})
        after = cpu({"100": (500, 19), "101": (700, 20)})
        self.assertEqual(D3.cpu_delta(before, after, "processes"), "unavailable_counter_decreased")

    def test_network_counter_decrease_is_not_reported_as_zero(self) -> None:
        before = {
            "available": True, "source": "xt_qtaguid", "rows": 2, "rx_bytes": 110, "tx_bytes": 60,
            "counters": {"row-a": {"rx_bytes": 100, "tx_bytes": 50}, "row-b": {"rx_bytes": 10, "tx_bytes": 10}},
        }
        after = {
            "available": True, "source": "xt_qtaguid", "rows": 2, "rx_bytes": 120, "tx_bytes": 60,
            "counters": {"row-a": {"rx_bytes": 90, "tx_bytes": 50}, "row-b": {"rx_bytes": 30, "tx_bytes": 10}},
        }
        delta = D3.network_delta(before, after)
        self.assertFalse(delta["available"])
        self.assertEqual(delta["rx_bytes"], "unavailable_counter_decreased")
        self.assertEqual(delta["tx_bytes"], 0)

    def test_network_source_change_is_unavailable_and_real_zero_remains_zero(self) -> None:
        before = {
            "available": True, "source": "xt_qtaguid", "rows": 1, "rx_bytes": 100, "tx_bytes": 50,
            "counters": {"row-a": {"rx_bytes": 100, "tx_bytes": 50}},
        }
        same = {**before}
        changed = {**before, "source": "netstats_uid_detail"}
        self.assertEqual(D3.network_delta(before, same)["rx_bytes"], 0)
        self.assertFalse(D3.network_delta(before, changed)["available"])

    def test_unknown_screen_state_cannot_pass_pair_comparison(self) -> None:
        local, coaching = paired("unavailable")
        result = D3.evaluate_pair(local, coaching, 60)
        self.assertEqual(result["status"], "FAIL")
        self.assertIn("screen_state_unavailable", result["errors"])

    def test_matching_known_screen_state_can_pass_pair_comparison(self) -> None:
        local, coaching = paired("screen-off")
        result = D3.evaluate_pair(local, coaching, 60)
        self.assertEqual(result["status"], "PASS")
        self.assertEqual(result["screen_state"], "screen-off")

    def test_pair_writer_returns_blocked_for_unknown_screen(self) -> None:
        local, coaching = paired("unavailable")
        with tempfile.TemporaryDirectory() as temporary:
            pair_dir = pathlib.Path(temporary)
            (pair_dir / "local.json").write_text(json.dumps(local), encoding="utf-8")
            (pair_dir / "coaching.json").write_text(json.dumps(coaching), encoding="utf-8")
            with self.assertRaisesRegex(D3.Blocked, "not comparable"):
                D3.check_pair(pair_dir, 60)
            summary = json.loads((pair_dir / "pair-summary.json").read_text(encoding="utf-8"))
            self.assertEqual(summary["status"], "FAIL")

    def test_local_attestations_require_usage_consent_and_background_work_off(self) -> None:
        args = attestations("local", agent="off", proactive="off", usage_consent="off", usage_background="off", backend="offline")
        D3.validate_attestations(args, "ignore")
        args.usage_background_state = "on"
        with self.assertRaisesRegex(D3.Blocked, "do not match"):
            D3.validate_attestations(args, "ignore")
        args.usage_background_state = "off"
        with self.assertRaisesRegex(D3.Blocked, "not explicitly denied"):
            D3.validate_attestations(args, "default")

    def test_coaching_attestations_require_consent_background_work_and_real_appop(self) -> None:
        args = attestations("coaching", agent="on", proactive="on", usage_consent="on", usage_background="on", backend="configured-no-request")
        D3.validate_attestations(args, "allow")
        args.usage_background_state = "off"
        with self.assertRaisesRegex(D3.Blocked, "do not match"):
            D3.validate_attestations(args, "allow")
        args.usage_background_state = "on"
        with self.assertRaisesRegex(D3.Blocked, "not actually allowed"):
            D3.validate_attestations(args, "ignore")
        with self.assertRaisesRegex(D3.Blocked, "not actually allowed"):
            D3.validate_attestations(args, "foreground")

    def test_local_usage_access_must_be_revoked_in_android(self) -> None:
        args = attestations("local", agent="off", proactive="off", usage_consent="off", usage_background="off", backend="offline")
        with self.assertRaisesRegex(D3.Blocked, "not explicitly denied"):
            D3.validate_attestations(args, "allow")
        with self.assertRaisesRegex(D3.Blocked, "not explicitly denied"):
            D3.validate_attestations(args, "foreground")


if __name__ == "__main__":
    unittest.main(verbosity=2)
