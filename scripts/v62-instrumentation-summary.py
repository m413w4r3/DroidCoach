#!/usr/bin/env python3
"""Strict, privacy-minimizing verdict for the V6.2.2 API 34 instrumentation recipe."""

from __future__ import annotations

import argparse
import pathlib
import re
import sys
import xml.etree.ElementTree as ET


EXPECTED_MAIN_TESTS = 75
EXPECTED_ADDITIONAL_TESTS = 10
EXPECTED_CAPTURES = 21

EXPECTED_ADDITIONAL_LOGS = {
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
}

EXPECTED_FOLLOW_UP_STATUSES = {
    "api26Through29UseListenerAndReturnTimeoutOrUnavailableCodes_exit": 0,
    "permissionRevocationDuringAcquisitionIsRecheckedBeforeReturningFix_exit": 0,
    "deniedPermissionStateReturnsFunctionalErrorWithoutCrashing_exit": 0,
}

EXPECTED_EXTERNAL_STATUSES = {
    "UsageFixtureInstrumentationTest#actualAppOpsDenialStopsBeforeQueryingUsageStats_exit": 0,
    "UsageFixtureInstrumentationTest#fourRealFixtureOpensReachTheRuleAfterReturningToAtlas_exit": 0,
    "fixture_build_exit": 0,
    "fixture_install_exit": 0,
    "V61LandscapeCaptureTest#capturesHomeWithDirectiveCtaAboveFixedFooter_exit": 0,
}

EXPECTED_HOST_PRECONDITIONS = {
    "app_install": 0,
    "test_apk_install": 0,
    "fine_permission": 0,
    "coarse_permission": 0,
    "notification_permission": 0,
    "location_enabled": 0,
}

EXPECTED_EXTERNAL_FILTERS = {
    "V62LocationCaptureInstrumentedTest#prepareSharingConsentBeforeHostInjectsFreshEmulatorFix",
    "V62LocationCaptureInstrumentedTest#injectedCoarseFixIsMinimizedAndStoredOnlyAsAZoneSummary",
    "V61LandscapeCaptureTest#capturesHomeWithDirectiveCtaAboveFixedFooter",
}

EXPECTED_RUNNER_FILTERS = {
    "dev.atlas.coach.app.V61LandscapeCaptureTest#capturesHomeWithDirectiveCtaAboveFixedFooter",
    "dev.atlas.coach.app.V62OpenClawClientInstrumentationTest#chatUsesRealGoalProgressAndPlanDropsGoalsAfterRevocation",
    "dev.atlas.coach.app.V6RealAgentE2eInstrumentationTest#restoredFocusCanPauseResumeAndCancel",
    "dev.atlas.coach.app.V6RealAgentE2eInstrumentationTest#realCoachResponseCreatesDirectiveAndStartsFocus",
    "dev.atlas.coach.location.V62LocationCaptureInstrumentedTest#injectedCoarseFixIsMinimizedAndStoredOnlyAsAZoneSummary",
    "dev.atlas.coach.location.V62LocationCaptureInstrumentedTest#prepareSharingConsentBeforeHostInjectsFreshEmulatorFix",
    "dev.atlas.coach.location.V62MobilityServiceInstrumentedTest#visibleStartShowsStopNotificationAndServiceStopsWithoutRawTrail",
    "dev.atlas.coach.sync.RemoteBackendE2eInstrumentationTest#runRequestedBackendScenario",
    "dev.atlas.coach.usage.UsageFixtureInstrumentationTest#actualAppOpsDenialStopsBeforeQueryingUsageStats",
    "dev.atlas.coach.usage.UsageFixtureInstrumentationTest#fourRealFixtureOpensReachTheRuleAfterReturningToAtlas",
}

FOLLOW_UP_FILTER_PREFIXES = (
    "follow-up-clear-",
    "follow-up-enable-",
    "follow-up-grant-",
    "follow-up-revoke-",
    "follow-up-ensure-",
)


def read_key_values(path: pathlib.Path, errors: list[str], label: str) -> dict[str, str]:
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError:
        errors.append(f"missing_or_unreadable:{label}")
        return {}
    values: dict[str, str] = {}
    for line_number, line in enumerate(lines, 1):
        if not line.strip():
            continue
        if "=" not in line:
            errors.append(f"malformed_status:{label}:{line_number}")
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        value = value.strip()
        if not key or key in values:
            errors.append(f"duplicate_or_empty_status_key:{label}:{line_number}")
            continue
        values[key] = value
    return values


def check_status_file(
    root: pathlib.Path,
    name: str,
    expected: dict[str, int],
    errors: list[str],
) -> dict[str, str]:
    values = read_key_values(root / name, errors, name)
    if set(values) != set(expected):
        errors.append(f"status_keys_mismatch:{name}")
    for key, wanted in expected.items():
        try:
            actual = int(values[key])
        except (KeyError, ValueError):
            errors.append(f"status_missing_or_non_numeric:{name}:{key}")
            continue
        if actual != wanted:
            errors.append(f"status_not_expected:{name}:{key}")
    return values


def parse_main_xml(root: pathlib.Path, errors: list[str]) -> tuple[int, int, int, int, int, int, dict[str, int]]:
    xml_root = root / "android-test-results"
    try:
        files = sorted(path for path in xml_root.rglob("*.xml") if path.name.startswith("TEST-"))
    except OSError:
        errors.append("xml_directory_unreadable")
        files = []
    if not files:
        errors.append("main_xml_missing")

    tests = failures = test_errors = skipped = malformed = 0
    seen_cases: set[tuple[str, str]] = set()
    class_counts: dict[str, int] = {}
    for path in files:
        try:
            tree = ET.parse(path)
        except (OSError, ET.ParseError):
            malformed += 1
            errors.append(f"main_xml_unreadable_or_invalid:{path.name}")
            continue
        cases = list(tree.iter("testcase"))
        tests += len(cases)
        for case in cases:
            identity = (case.attrib.get("classname", ""), case.attrib.get("name", ""))
            if identity in seen_cases:
                errors.append("duplicate_main_testcase")
            seen_cases.add(identity)
            class_name = identity[0] or "unknown"
            class_counts[class_name] = class_counts.get(class_name, 0) + 1
            failures += case.find("failure") is not None
            test_errors += case.find("error") is not None
            skipped += case.find("skipped") is not None
        for suite in (tree.getroot(), *tree.getroot().iter("testsuite")):
            cases_in_suite = list(suite.iter("testcase"))
            declared_values = {
                "tests": len(cases_in_suite),
                "failures": sum(case.find("failure") is not None for case in cases_in_suite),
                "errors": sum(case.find("error") is not None for case in cases_in_suite),
                "skipped": sum(case.find("skipped") is not None for case in cases_in_suite),
            }
            for attribute, actual_count in declared_values.items():
                declared = suite.attrib.get(attribute)
                if declared is None:
                    continue
                try:
                    parsed_count = int(declared)
                except ValueError:
                    errors.append(f"main_xml_invalid_declared_{attribute}:{path.name}")
                    continue
                if parsed_count != actual_count:
                    errors.append(f"main_xml_declared_{attribute}_mismatch:{path.name}")
    if tests == 0:
        errors.append("main_test_count_zero")
    if tests != EXPECTED_MAIN_TESTS:
        errors.append(f"main_test_count_unexpected:{tests}")
    return len(files), tests, failures, test_errors, skipped, malformed, class_counts


def parse_runner_log(path: pathlib.Path) -> tuple[int, int, int, int, str | None]:
    try:
        body = path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return 0, 0, 0, 0, "unreadable"

    summaries: list[tuple[int, int, int, int]] = []
    for match in re.finditer(
        r"Tests run:\s*(\d+),\s*Failures:\s*(\d+)"
        r"(?:,\s*Errors:\s*(\d+))?(?:,\s*(?:Skipped|Ignored):\s*(\d+))?",
        body,
    ):
        summaries.append(tuple(int(value or 0) for value in match.groups()))
    for match in re.finditer(r"\bOK\s*\((\d+)\s+tests?\)", body):
        summaries.append((int(match.group(1)), 0, 0, 0))

    if len(summaries) != 1:
        return 0, 0, 0, 0, "missing_or_ambiguous_runner_summary"
    tests, failures, errors, skipped = summaries[0]
    if re.search(r"INSTRUMENTATION_FAILED|INSTRUMENTATION_RESULT:\s*shortMsg", body):
        return tests, failures, max(errors, 1), skipped, "instrumentation_failed"
    if re.search(r"^INSTRUMENTATION_STATUS_CODE:\s*-2\s*$", body, re.MULTILINE):
        return tests, failures, errors, max(skipped, 1), "runner_reported_skipped"
    if tests != 1:
        return tests, failures, errors, skipped, "complement_log_test_count_not_one"
    if failures or errors or skipped:
        return tests, failures, errors, skipped, "complement_log_not_passing"
    return tests, failures, errors, skipped, None


def parse_filter_lines(path: pathlib.Path, errors: list[str], label: str) -> list[str]:
    try:
        lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError:
        errors.append(f"missing_or_unreadable:{label}")
        return []
    return [line for line in lines if "Filtered " in line]


def evaluate(root: pathlib.Path, args: argparse.Namespace) -> tuple[list[str], list[str]]:
    errors: list[str] = []
    report: list[str] = []

    xml_files, main_tests, main_failures, main_errors, main_skipped, malformed_xml, class_counts = parse_main_xml(root, errors)
    report.extend(
        [
            f"main_xml_files={xml_files}",
            f"main_xml_invalid_or_unreadable={malformed_xml}",
            f"full_suite_tests={main_tests}/{EXPECTED_MAIN_TESTS}",
            f"full_suite_failures={main_failures}",
            f"full_suite_errors={main_errors}",
            f"full_suite_skipped={main_skipped}",
            f"full_suite_classes_with_executed_cases={len(class_counts)}",
        ]
    )
    report.extend(
        f"full_suite_class={class_name} cases={count}"
        for class_name, count in sorted(class_counts.items())
    )
    if main_failures or main_errors or main_skipped:
        errors.append("main_suite_has_failed_error_or_skipped_case")

    main_exit = int(args.main_exit)
    report.append(f"connectedDebugAndroidTest_exit={main_exit}")
    if main_exit != 0:
        errors.append("main_runner_exit_not_zero")

    native_statuses = {
        "native_home_360dp_exit": int(args.native_home_360dp),
        "native_coach_360dp_exit": int(args.native_coach_360dp),
        "native_home_320dp_exit": int(args.native_home_320dp),
        "native_coach_320dp_exit": int(args.native_coach_320dp),
    }
    for name, value in native_statuses.items():
        report.append(f"{name}={value}")
        if value != 0:
            errors.append(f"targeted_runner_exit_not_zero:{name}")

    follow_values = check_status_file(root, "follow-up-status.txt", EXPECTED_FOLLOW_UP_STATUSES, errors)
    external_values = check_status_file(root, "external-test-status.txt", EXPECTED_EXTERNAL_STATUSES, errors)
    host_values = check_status_file(root, "host-test-precondition-status.txt", EXPECTED_HOST_PRECONDITIONS, errors)
    native_file_values = check_status_file(root, "native-test-status.txt", native_statuses, errors)
    report.append(f"follow_up_status_entries={len(follow_values)}")
    report.append(f"external_status_entries={len(external_values)}")
    report.append(f"host_precondition_entries={len(host_values)}")
    report.append(f"native_status_entries={len(native_file_values)}")

    external_filters = read_key_values(root / "external-preconditions.txt", errors, "external-preconditions.txt")
    external_filter_names = set(external_filters)
    if external_filter_names != EXPECTED_EXTERNAL_FILTERS:
        errors.append("external_filter_preconditions_mismatch")
    report.append(f"external_filtered_preconditions={len(external_filter_names)}")
    if any("filtered" not in value.lower() for value in external_filters.values()):
        errors.append("external_precondition_status_not_explicitly_filtered")

    follow_filter_lines = parse_filter_lines(root / "follow-up-filter.log", errors, "follow-up-filter.log")
    backend_filter_lines = parse_filter_lines(root / "backend-filter.log", errors, "backend-filter.log")
    parsed_filter_cases: set[str] = set()
    for line in backend_filter_lines:
        match = re.search(r"Filtered\s+([^\s(]+)\(([\w.$]+)\):", line)
        if match:
            parsed_filter_cases.add(f"{match.group(2)}#{match.group(1)}")
        else:
            errors.append("runner_filter_entry_unparsed")
    if parsed_filter_cases != EXPECTED_RUNNER_FILTERS:
        errors.append("runner_filter_prerequisites_mismatch")
    report.append(f"runner_filtered_cases={len(backend_filter_lines)}")
    report.append(f"runner_filter_cases_parsed={len(parsed_filter_cases)}/{len(EXPECTED_RUNNER_FILTERS)}")
    report.extend(f"runner_filter_case={name}" for name in sorted(parsed_filter_cases))
    report.append(f"follow_up_filtered_log_entries={len(follow_filter_lines)}")
    report.append("filtered_cases_counted_as_pass=0")
    report.append("TEST_SYSTEM_BLOCKED_status=separate_location_recipe")

    excluded = FOLLOW_UP_FILTER_PREFIXES + ("follow-up-filter.log",)
    actual_additional_logs = {
        path.name
        for path in root.glob("follow-up-*.log")
        if not path.name.startswith(excluded)
    }
    actual_additional_logs.update(path.name for path in root.glob("usage-*.log"))
    actual_additional_logs.update(path.name for path in root.glob("native-*.log"))
    if actual_additional_logs != EXPECTED_ADDITIONAL_LOGS:
        missing = EXPECTED_ADDITIONAL_LOGS - actual_additional_logs
        unexpected = actual_additional_logs - EXPECTED_ADDITIONAL_LOGS
        if missing:
            errors.append(f"complement_logs_missing:{len(missing)}")
        if unexpected:
            errors.append(f"complement_logs_unexpected:{len(unexpected)}")

    additional_tests = additional_failures = additional_errors = additional_skipped = unparsed = 0
    for name in sorted(actual_additional_logs):
        path = root / name
        tests, failures, test_errors, skipped, parse_error = parse_runner_log(path)
        additional_tests += tests
        additional_failures += failures
        additional_errors += test_errors
        additional_skipped += skipped
        if parse_error:
            unparsed += 1
            errors.append(f"complement_log_invalid:{name}:{parse_error}")
        report.append(
            f"additional_log={name} tests={tests} failures={failures} errors={test_errors} skipped={skipped}"
        )

    if unparsed:
        errors.append(f"complement_logs_without_valid_summary:{unparsed}")
    if additional_tests != EXPECTED_ADDITIONAL_TESTS:
        errors.append(f"additional_test_count_unexpected:{additional_tests}")
    report.extend(
        [
            f"additional_tests={additional_tests}/{EXPECTED_ADDITIONAL_TESTS}",
            f"additional_passed={additional_tests - additional_failures - additional_errors - additional_skipped}",
            f"additional_failures={additional_failures}",
            f"additional_errors={additional_errors}",
            f"additional_skipped={additional_skipped}",
            f"additional_logs_without_summary={unparsed}",
        ]
    )
    if additional_failures or additional_errors or additional_skipped:
        errors.append("additional_suite_has_failed_error_or_skipped_case")

    captures_dir = root / "captures"
    try:
        capture_paths = list(captures_dir.glob("*.png"))
    except OSError:
        capture_paths = []
    capture_count = len(capture_paths)
    report.append(f"capture_png_files={capture_count}/{EXPECTED_CAPTURES}")
    if capture_count != EXPECTED_CAPTURES:
        errors.append("capture_count_unexpected")

    report.extend(f"verdict_error={error}" for error in errors)
    report.append(f"recipe={'FAIL' if errors else 'PASS'}")
    return report, errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", required=True, type=pathlib.Path)
    parser.add_argument("--main-exit", required=True, type=int)
    parser.add_argument("--native-home-360dp", required=True, type=int)
    parser.add_argument("--native-coach-360dp", required=True, type=int)
    parser.add_argument("--native-home-320dp", required=True, type=int)
    parser.add_argument("--native-coach-320dp", required=True, type=int)
    args = parser.parse_args()

    report, _ = evaluate(args.root, args)
    counts_path = args.root / "counts.txt"
    result_path = args.root / "recipe-result.txt"
    try:
        counts_path.write_text("\n".join(report) + "\n", encoding="utf-8")
        result_path.write_text("\n".join(report) + "\n", encoding="utf-8")
    except OSError as error:
        print(f"summary_output_error={error.__class__.__name__}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
