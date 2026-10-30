"""Load the Compose-mounted token before starting Core."""

import hmac
import os
import sys


def read_secret(path: str, name: str) -> str:
    try:
        with open(path, encoding="ascii") as secret_file:
            token = secret_file.read().rstrip("\r\n")
    except (OSError, UnicodeError):
        raise SystemExit(f"{name} secret is unavailable") from None

    if not token or "\n" in token or "\r" in token:
        raise SystemExit(f"{name} secret is empty or malformed")
    return token


def main() -> None:
    android_token = read_secret("/run/secrets/atlas_token", "ATLAS_TOKEN")
    tool_token = read_secret("/run/secrets/atlas_tool_token", "ATLAS_TOOL_TOKEN")
    if hmac.compare_digest(android_token, tool_token):
        raise SystemExit("ATLAS_TOKEN and ATLAS_TOOL_TOKEN must be distinct")

    os.environ["ATLAS_TOKEN"] = android_token
    os.environ["ATLAS_TOOL_TOKEN"] = tool_token

    if len(sys.argv) < 2:
        raise SystemExit("No application command configured")
    os.execvp(sys.argv[1], sys.argv[1:])


if __name__ == "__main__":
    main()
