#!/usr/bin/env python3
"""Convert AndroidJUnitRunner `am instrument -w -r` output to JUnit XML."""

from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict
from pathlib import Path


def main() -> int:
    if len(sys.argv) != 3:
        print("usage: v623-parse-instrumentation.py RUNNER_OUTPUT OUTPUT_DIR", file=sys.stderr)
        return 2

    source = Path(sys.argv[1])
    output = Path(sys.argv[2])
    output.mkdir(parents=True, exist_ok=True)
    for old in output.glob("TEST-*.xml"):
        old.unlink()

    records: dict[tuple[str, str], dict[str, str]] = {}
    active: tuple[str, str] | None = None
    fields: dict[str, str] = {}
    text = source.read_text(encoding="utf-8", errors="replace")

    def consume(code: int, values: dict[str, str]) -> None:
        nonlocal active
        classname = values.get("class", "").strip()
        testname = values.get("test", "").strip()
        if code == 1:
            if classname and testname:
                active = (classname, testname)
                records.setdefault(active, {"status": "started", "details": ""})
            return
        if code not in (0, -2, -3, -4):
            return
        key = (classname, testname) if classname and testname else active
        if key is None:
            return
        details = values.get("stack", "") or values.get("stream", "")
        if code == 0:
            result = "passed"
        elif code in (-3, -4):
            result = "skipped"
        else:
            result = "failed"
        records[key] = {"status": result, "details": details}
        active = None

    for line in text.splitlines():
        if line.startswith("INSTRUMENTATION_STATUS: "):
            payload = line[len("INSTRUMENTATION_STATUS: "):]
            key, sep, value = payload.partition("=")
            if sep:
                if key in fields and key in {"stack", "stream"}:
                    fields[key] += "\n" + value
                else:
                    fields[key] = value
            elif fields:
                fields[next(reversed(fields))] += "\n" + payload
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            try:
                code = int(line.rsplit(" ", 1)[1])
            except ValueError:
                fields = {}
                continue
            consume(code, fields)
            fields = {}

    suites: dict[str, list[tuple[str, dict[str, str]]]] = defaultdict(list)
    for (classname, testname), outcome in records.items():
        suites[classname].append((testname, outcome))

    total = passed = failed = skipped = 0
    for index, classname in enumerate(sorted(suites), start=1):
        cases = suites[classname]
        suite = ET.Element(
            "testsuite",
            {
                "name": classname,
                "tests": str(len(cases)),
                "failures": str(sum(1 for _, item in cases if item["status"] == "failed")),
                "errors": "0",
                "skipped": str(sum(1 for _, item in cases if item["status"] == "skipped")),
            },
        )
        for testname, outcome in sorted(cases):
            ET.SubElement(suite, "testcase", {"classname": classname, "name": testname})
            case = suite[-1]
            if outcome["status"] == "failed":
                node = ET.SubElement(case, "failure", {"message": "AndroidJUnitRunner reported test failure"})
                node.text = outcome.get("details", "")
                failed += 1
            elif outcome["status"] == "skipped":
                node = ET.SubElement(case, "skipped", {"message": "AndroidJUnitRunner reported skipped/assumption"})
                node.text = outcome.get("details", "")
                skipped += 1
            elif outcome["status"] == "passed":
                passed += 1
            else:
                node = ET.SubElement(case, "error", {"message": "runner started test but emitted no final status"})
                node.text = outcome.get("details", "")
                failed += 1
        ET.indent(suite, space="  ")
        ET.ElementTree(suite).write(
            output / f"TEST-{index:03d}-{re.sub(r'[^A-Za-z0-9_.-]', '_', classname)}.xml",
            encoding="utf-8",
            xml_declaration=True,
        )
        total += len(cases)

    complete = "INSTRUMENTATION_CODE: -1" in text and total > 0
    aborted = any(
        marker in text
        for marker in (
            "INSTRUMENTATION_ABORTED",
            "System has crashed",
            "shortMsg=Process crashed",
            "device offline",
            "Instrumentation run failed due to",
        )
    )
    classes = len(suites)
    (output / "am-instrument-summary.txt").write_text(
        f"classes={classes}\ntests={total}\npassed={passed}\nfailed={failed}\n"
        f"skipped={skipped}\nrunner_complete={str(complete).lower()}\n"
        f"aborted={str(aborted).lower()}\n",
        encoding="utf-8",
    )
    print(
        f"AM_INSTRUMENT_CLASSES={classes} TESTS={total} PASSED={passed} "
        f"FAILURES={failed} SKIPPED={skipped} RUNNER_COMPLETE={str(complete).lower()} "
        f"ABORTED={str(aborted).lower()} XML_DIR={output}"
    )
    return 0 if complete and not aborted else 1


if __name__ == "__main__":
    raise SystemExit(main())
