#!/usr/bin/env python3
"""Host-only regressions for the strict instrumentation verdict; no SDK or device used."""

from __future__ import annotations

import pathlib
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET


ROOT = pathlib.Path(__file__).resolve().parents[1]
SUMMARY = ROOT / "scripts" / "v62-instrumentation-summary.py"
ADDITIONAL_LOGS = (
    "follow-up-api26Through29UseListenerAndReturnTimeoutOrUnavailableCodes.log",
    "follow-up-deniedPermissionStateReturnsFunctionalErrorWithoutCrashing.log",
    "follow-up-permissionRevocationDuringAcquisitionIsRecheckedBeforeReturningFix.log",
    "follow-up-v61-landscape.log",
    "usage-actual-appops-denial.log",
    "usage-four-fixture-opens.log",
    "native-coach-320dp.log",
    "native-coach-360dp.log",
    "native-home-320dp.log",
    "native-home-360dp.log",
)
RUNNER_FILTERS = (
    "capturesHomeWithDirectiveCtaAboveFixedFooter(dev.atlas.coach.app.V61LandscapeCaptureTest)",
    "chatUsesRealGoalProgressAndPlanDropsGoalsAfterRevocation(dev.atlas.coach.app.V62OpenClawClientInstrumentationTest)",
    "restoredFocusCanPauseResumeAndCancel(dev.atlas.coach.app.V6RealAgentE2eInstrumentationTest)",
    "realCoachResponseCreatesDirectiveAndStartsFocus(dev.atlas.coach.app.V6RealAgentE2eInstrumentationTest)",
    "injectedCoarseFixIsMinimizedAndStoredOnlyAsAZoneSummary(dev.atlas.coach.location.V62LocationCaptureInstrumentedTest)",
    "prepareSharingConsentBeforeHostInjectsFreshEmulatorFix(dev.atlas.coach.location.V62LocationCaptureInstrumentedTest)",
    "visibleStartShowsStopNotificationAndServiceStopsWithoutRawTrail(dev.atlas.coach.location.V62MobilityServiceInstrumentedTest)",
    "runRequestedBackendScenario(dev.atlas.coach.sync.RemoteBackendE2eInstrumentationTest)",
    "actualAppOpsDenialStopsBeforeQueryingUsageStats(dev.atlas.coach.usage.UsageFixtureInstrumentationTest)",
    "fourRealFixtureOpensReachTheRuleAfterReturningToAtlas(dev.atlas.coach.usage.UsageFixtureInstrumentationTest)",
)


def write(path: pathlib.Path, value: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(value, encoding="utf-8")


def make_fixture(root: pathlib.Path) -> None:
    result_dir = root / "android-test-results" / "debug"
    result_dir.mkdir(parents=True)
    suite = ET.Element("testsuite", name="synthetic", tests="75", failures="0", errors="0", skipped="0")
    for index in range(75):
        ET.SubElement(suite, "testcase", name=f"case{index:02d}", classname="synthetic.Main")
    ET.ElementTree(suite).write(result_dir / "TEST-synthetic.xml", encoding="utf-8", xml_declaration=True)

    for name in ADDITIONAL_LOGS:
        write(root / name, "INSTRUMENTATION_STATUS_CODE: 0\nOK (1 test)\nINSTRUMENTATION_CODE: 0\n")

    write(
        root / "follow-up-status.txt",
        "api26Through29UseListenerAndReturnTimeoutOrUnavailableCodes_exit=0\n"
        "permissionRevocationDuringAcquisitionIsRecheckedBeforeReturningFix_exit=0\n"
        "deniedPermissionStateReturnsFunctionalErrorWithoutCrashing_exit=0\n",
    )
    write(
        root / "external-test-status.txt",
        "UsageFixtureInstrumentationTest#actualAppOpsDenialStopsBeforeQueryingUsageStats_exit=0\n"
        "UsageFixtureInstrumentationTest#fourRealFixtureOpensReachTheRuleAfterReturningToAtlas_exit=0\n"
        "fixture_build_exit=0\nfixture_install_exit=0\n"
        "V61LandscapeCaptureTest#capturesHomeWithDirectiveCtaAboveFixedFooter_exit=0\n",
    )
    write(
        root / "host-test-precondition-status.txt",
        "app_install=0\ntest_apk_install=0\nfine_permission=0\ncoarse_permission=0\n"
        "notification_permission=0\nlocation_enabled=0\n",
    )
    write(
        root / "native-test-status.txt",
        "native_home_360dp_exit=0\nnative_coach_360dp_exit=0\n"
        "native_home_320dp_exit=0\nnative_coach_320dp_exit=0\n",
    )
    write(
        root / "external-preconditions.txt",
        "V62LocationCaptureInstrumentedTest#prepareSharingConsentBeforeHostInjectsFreshEmulatorFix=filtered(no injection)\n"
        "V62LocationCaptureInstrumentedTest#injectedCoarseFixIsMinimizedAndStoredOnlyAsAZoneSummary=filtered(no injection)\n"
        "V61LandscapeCaptureTest#capturesHomeWithDirectiveCtaAboveFixedFooter=filtered(main portrait run)\n",
    )
    write(
        root / "backend-filter.log",
        "".join(f"ATLAS_TEST_FILTER: Filtered {case}: external precondition\n" for case in RUNNER_FILTERS),
    )
    write(root / "follow-up-filter.log", "")
    captures = root / "captures"
    captures.mkdir()
    for index in range(21):
        (captures / f"capture-{index:02d}.png").write_bytes(b"synthetic")


def run(root: pathlib.Path) -> str:
    completed = subprocess.run(
        [
            sys.executable,
            str(SUMMARY),
            "--root",
            str(root),
            "--main-exit",
            "0",
            "--native-home-360dp",
            "0",
            "--native-coach-360dp",
            "0",
            "--native-home-320dp",
            "0",
            "--native-coach-320dp",
            "0",
        ],
        capture_output=True,
        text=True,
        check=False,
    )
    if completed.returncode != 0:
        raise AssertionError(f"summary process failed unexpectedly: {completed.stderr}")
    return (root / "recipe-result.txt").read_text(encoding="utf-8")


def assert_verdict(label: str, output: str, expected: str) -> None:
    if f"recipe={expected}" not in output.splitlines():
        raise AssertionError(f"{label}: expected recipe={expected}; got {output}")
    if expected == "FAIL" and "verdict_error=" not in output:
        raise AssertionError(f"{label}: FAIL has no diagnostic reason")
    print(f"SELFTEST PASS case={label} verdict={expected}")


def reproduce_original_false_green() -> None:
    # Reproduce the old verdict predicate: parse errors are ignored and only nonzero
    # failure/error/skip counters, statuses, and capture count decide the result.
    with tempfile.TemporaryDirectory(prefix="v62-original-false-green-") as temporary:
        root = pathlib.Path(temporary)
        captures = root / "captures"
        captures.mkdir()
        for index in range(21):
            (captures / f"capture-{index:02d}.png").write_bytes(b"synthetic")
        tests = failures = errors = skipped = additional_failures = additional_errors = 0
        statuses = [0] * 11
        capture_count = len(list(captures.glob("*.png")))
        old_failed = any(
            (
                any(status != 0 for status in statuses),
                failures > 0,
                errors > 0,
                skipped > 0,
                additional_failures > 0,
                additional_errors > 0,
                capture_count != 21,
            )
        )
        if tests != 0 or old_failed:
            raise AssertionError("legacy synthetic setup no longer reproduces the zero-test false green")
        print("ORIGINAL_FALSE_GREEN reproduced: zero XML, zero tests, zero complements, 21 captures -> recipe=PASS")


def set_main_test_count(root: pathlib.Path, count: int) -> None:
    path = root / "android-test-results/debug/TEST-synthetic.xml"
    tree = ET.parse(path)
    suite = tree.getroot()
    cases = list(suite.findall("testcase"))
    while len(cases) > count:
        suite.remove(cases.pop())
    while len(cases) < count:
        index = len(cases)
        cases.append(ET.SubElement(suite, "testcase", name=f"case{index:02d}", classname="synthetic.Main"))
    suite.set("tests", str(count))
    tree.write(path, encoding="utf-8", xml_declaration=True)


def main() -> None:
    reproduce_original_false_green()
    with tempfile.TemporaryDirectory(prefix="v62-summary-selftest-") as temporary:
        base = pathlib.Path(temporary)
        valid = base / "valid"
        make_fixture(valid)
        valid_output = run(valid)
        assert_verdict("complete_synthetic_baseline", valid_output, "PASS")
        if "runner_filtered_cases=10" not in valid_output or "filtered_cases_counted_as_pass=0" not in valid_output:
            raise AssertionError("filter status was not kept separate from executed test counts")
        if "TEST_SYSTEM_BLOCKED_status=separate_location_recipe" not in valid_output:
            raise AssertionError("TEST_SYSTEM_BLOCKED status was collapsed into the filter count")

        scenarios = (
            ("missing_xml", lambda root: (root / "android-test-results").rename(root / "xml-removed")),
            (
                "unreadable_xml",
                lambda root: (root / "android-test-results/debug/TEST-unreadable.xml").mkdir(),
            ),
            (
                "malformed_xml",
                lambda root: (root / "android-test-results/debug/TEST-synthetic.xml").write_text(
                    "<testsuite", encoding="utf-8"
                ),
            ),
            ("zero_tests", lambda root: set_main_test_count(root, 0)),
            ("unexpected_main_count_low", lambda root: set_main_test_count(root, 74)),
            (
                "unexpected_main_count_high",
                lambda root: set_main_test_count(root, 76),
            ),
            (
                "complement_log_unparsed",
                lambda root: (root / ADDITIONAL_LOGS[0]).write_text("INSTRUMENTATION_CODE: 0\n", encoding="utf-8"),
            ),
            (
                "complement_count_mismatch",
                lambda root: (root / ADDITIONAL_LOGS[0]).write_text("OK (2 tests)\n", encoding="utf-8"),
            ),
            (
                "missing_complement_log",
                lambda root: (root / ADDITIONAL_LOGS[0]).unlink(),
            ),
            (
                "prerequisite_status_mismatch",
                lambda root: (root / "external-test-status.txt").write_text(
                    (root / "external-test-status.txt").read_text(encoding="utf-8").replace("fixture_install_exit=0", "fixture_install_exit=77"),
                    encoding="utf-8",
                ),
            ),
            (
                "filtered_case_is_not_execution",
                lambda root: (root / ADDITIONAL_LOGS[0]).write_text(
                    "ATLAS_TEST_FILTER: Filtered external prerequisite\n", encoding="utf-8"
                ),
            ),
            (
                "filtered_prerequisite_count_mismatch",
                lambda root: (root / "backend-filter.log").write_text(
                    "ATLAS_TEST_FILTER: Filtered " + RUNNER_FILTERS[0] + ": host prerequisite\n",
                    encoding="utf-8",
                ),
            ),
        )

        for label, mutate in scenarios:
            scenario_root = base / label
            make_fixture(scenario_root)
            mutate(scenario_root)
            assert_verdict(label, run(scenario_root), "FAIL")

    print("SELFTEST PASS cases=13 device_access=none")


if __name__ == "__main__":
    main()
