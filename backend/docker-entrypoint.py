"""Load the Compose-mounted token before starting Core."""

import hmac
import os
import sys
from pathlib import Path

DEFAULT_SECRETS_DIR = Path("/run/secrets")


def read_secret(path: Path, name: str, *, optional: bool = False) -> str | None:
    try:
        with open(path, encoding="ascii") as secret_file:
            token = secret_file.read().strip()
    except FileNotFoundError:
        if optional:
            return None
        raise SystemExit(f"{name} secret is unavailable") from None
    except UnicodeError:
        raise SystemExit(f"{name} secret is empty or malformed") from None
    except OSError:
        raise SystemExit(f"{name} secret is unavailable") from None

    if not token or any(
        character.isspace() or ord(character) < 0x20 or ord(character) == 0x7F
        for character in token
    ):
        raise SystemExit(f"{name} secret is empty or malformed")
    return token


def load_secrets(secrets_dir: str | os.PathLike[str] = DEFAULT_SECRETS_DIR) -> dict[str, str]:
    secret_path = Path(secrets_dir)
    android_token = read_secret(secret_path / "atlas_token", "ATLAS_TOKEN")
    tool_token = read_secret(secret_path / "atlas_tool_token", "ATLAS_TOOL_TOKEN")
    assert android_token is not None and tool_token is not None
    if hmac.compare_digest(android_token, tool_token):
        raise SystemExit("ATLAS_TOKEN and ATLAS_TOOL_TOKEN must be distinct")

    tokens = {"ATLAS_TOKEN": android_token, "ATLAS_TOOL_TOKEN": tool_token}
    gateway_token = read_secret(
        secret_path / "openclaw_gateway_token",
        "OPENCLAW_GATEWAY_TOKEN",
        optional=True,
    )
    if gateway_token is not None:
        if hmac.compare_digest(gateway_token, android_token):
            raise SystemExit("OPENCLAW_GATEWAY_TOKEN and ATLAS_TOKEN must be distinct")
        if hmac.compare_digest(gateway_token, tool_token):
            raise SystemExit("OPENCLAW_GATEWAY_TOKEN and ATLAS_TOOL_TOKEN must be distinct")
        tokens["OPENCLAW_GATEWAY_TOKEN"] = gateway_token
    return tokens


def load_and_export_secrets(secrets_dir: str | os.PathLike[str] = DEFAULT_SECRETS_DIR) -> None:
    tokens = load_secrets(secrets_dir)
    if "OPENCLAW_GATEWAY_TOKEN" not in tokens:
        os.environ.pop("OPENCLAW_GATEWAY_TOKEN", None)
    os.environ.update(tokens)


def main(secrets_dir: str | os.PathLike[str] = DEFAULT_SECRETS_DIR) -> None:
    load_and_export_secrets(secrets_dir)

    if len(sys.argv) < 2:
        raise SystemExit("No application command configured")
    os.execvp(sys.argv[1], sys.argv[1:])


if __name__ == "__main__":
    main()
