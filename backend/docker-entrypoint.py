"""Load the Compose-mounted token before starting Core."""

import os
import sys


def main() -> None:
    try:
        with open("/run/secrets/atlas_token", encoding="ascii") as secret_file:
            token = secret_file.read().rstrip("\r\n")
    except OSError:
        raise SystemExit("ATLAS_TOKEN secret is unavailable") from None

    if not token or "\n" in token or "\r" in token:
        raise SystemExit("ATLAS_TOKEN secret is empty or malformed")

    os.environ["ATLAS_TOKEN"] = token

    if len(sys.argv) < 2:
        raise SystemExit("No application command configured")
    os.execvp(sys.argv[1], sys.argv[1:])


if __name__ == "__main__":
    main()
