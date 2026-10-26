#!/usr/bin/env python3
"""Redacted, read-only connectivity diagnostic for wger."""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path

import httpx

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from atlas_backend.config import load_dotenv_if_enabled, resolve_wger_auth_mode
from atlas_backend.integrations.wger import (
    ApiTokenWgerAuth,
    WgerClient,
    WgerError,
    WgerLimits,
    WgerResourceProbe,
)


class _RedactedArgumentParser(argparse.ArgumentParser):
    def error(self, message: str) -> None:
        del message
        self.print_usage(sys.stderr)
        self.exit(2, f"{self.prog}: invalid arguments\n")


def _line(mode: str, result: WgerResourceProbe) -> str:
    return json.dumps(
        {
            "mode": mode,
            "status_http": result.status_http,
            "error_category": result.error_category,
            "element_count": result.element_count,
            "pagination_present": result.pagination_present,
        },
        separators=(",", ":"),
    )


def main(argv: list[str] | None = None) -> int:
    parser = _RedactedArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=("api_token", "jwt_refresh"))
    parser.add_argument("--read-only", action="store_true", required=True)
    parser.add_argument("--env-file", type=Path)
    args = parser.parse_args(argv)

    mode = args.mode or "unconfigured"
    try:
        load_dotenv_if_enabled(args.env_file)
        configured_mode = resolve_wger_auth_mode()
        mode = args.mode or configured_mode
        if args.mode is not None and args.mode != configured_mode:
            raise WgerError("CONFIG_REQUIRED")
        if args.mode == "jwt_refresh" or mode == "jwt_refresh":
            raise WgerError("CONFIG_REQUIRED")
        if mode != "api_token":
            raise WgerError("CONFIG_REQUIRED")
        token = os.environ.get("WGER_API_TOKEN")
        if not token:
            raise WgerError("CONFIG_REQUIRED")
        auth = ApiTokenWgerAuth(token)
        limits = WgerLimits(page_size=1, max_pages=1, max_items=1)
        base_url = os.environ.get("WGER_BASE_URL", "https://wger.de")
        with httpx.Client(follow_redirects=False) as http:
            probe = WgerClient(base_url, auth, http, limits).probe()
        print(_line(mode, probe.routines))
        print(_line(mode, probe.exercises))
        return 0
    except WgerError as error:
        unavailable = WgerResourceProbe(
            status_http=error.status_code,
            error_category=error.code,
            element_count=0,
            pagination_present=False,
        )
        print(_line(mode, unavailable))
        print(_line(mode, unavailable))
        return 2
    except (OSError, RuntimeError, ValueError):
        unavailable = WgerResourceProbe(
            status_http=None,
            error_category="CONFIG_REQUIRED",
            element_count=0,
            pagination_present=False,
        )
        print(_line(mode, unavailable))
        print(_line(mode, unavailable))
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
