"""Explicit, Git-guarded local environment loading for backend tools."""

from __future__ import annotations

import os
import re
import shlex
import subprocess
from collections.abc import MutableMapping
from pathlib import Path

from .integrations.wger import WgerError

_ENV_KEY = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")


def load_dotenv_if_enabled(
    env_file: str | os.PathLike[str] | None = None,
    *,
    environ: MutableMapping[str, str] | None = None,
    repo_root: str | os.PathLike[str] | None = None,
) -> bool:
    """Load an ignored, untracked env file only after explicit opt-in.

    Passing ``env_file`` is explicit opt-in. Otherwise ATLAS_LOAD_DOTENV must
    equal ``1``; an existing process variable always wins over file contents.
    """
    target_env = os.environ if environ is None else environ
    explicit = env_file is not None
    if not explicit and target_env.get("ATLAS_LOAD_DOTENV") != "1":
        return False
    root = Path(repo_root).resolve() if repo_root is not None else Path(__file__).resolve().parents[2]
    requested_path = env_file if explicit else target_env.get("ATLAS_ENV_FILE", root / ".env")
    path = Path(requested_path).expanduser()
    if not path.is_absolute():
        path = root / path
    path = path.resolve()
    relative = _ignored_untracked_relative_path(path, root)
    if relative is None:
        raise WgerError("CONFIG_REQUIRED")
    try:
        contents = path.read_text(encoding="utf-8")
    except OSError:
        raise WgerError("CONFIG_REQUIRED") from None
    try:
        parsed = _parse_env(contents)
    except ValueError:
        raise WgerError("CONFIG_REQUIRED") from None
    for key, value in parsed.items():
        if key not in target_env:
            target_env[key] = value
    return True


def resolve_wger_auth_mode(environ: MutableMapping[str, str] | None = None) -> str:
    """Resolve api_token or jwt_refresh without reading a refresh token."""
    source = os.environ if environ is None else environ
    configured = source.get("WGER_AUTH_MODE")
    if configured is None:
        if _has_api_token(source.get("WGER_API_TOKEN")):
            return "api_token"
        raise WgerError("CONFIG_REQUIRED")
    if not configured.strip():
        raise WgerError("CONFIG_REQUIRED")
    mode = configured.strip()
    if mode not in {"api_token", "jwt_refresh"}:
        raise WgerError("CONFIG_REQUIRED")
    if mode == "api_token" and not _has_api_token(source.get("WGER_API_TOKEN")):
        raise WgerError("CONFIG_REQUIRED")
    return mode


def _has_api_token(value: str | None) -> bool:
    return isinstance(value, str) and bool(value) and not any(char.isspace() for char in value)


def _ignored_untracked_relative_path(path: Path, root: Path) -> Path | None:
    try:
        relative = path.relative_to(root)
    except ValueError:
        return None
    if not path.is_file():
        return None
    try:
        tracked = subprocess.run(
            ["git", "-C", str(root), "ls-files", "--error-unmatch", "--", relative.as_posix()],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=False,
            timeout=3,
        )
        if tracked.returncode == 0:
            return None
        ignored = subprocess.run(
            ["git", "-C", str(root), "check-ignore", "--quiet", "--", relative.as_posix()],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=False,
            timeout=3,
        )
        return relative if ignored.returncode == 0 else None
    except (OSError, subprocess.SubprocessError):
        return None


def _parse_env(contents: str) -> dict[str, str]:
    values: dict[str, str] = {}
    for line in contents.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        if stripped.startswith("export "):
            stripped = stripped[7:].lstrip()
        key, separator, raw_value = stripped.partition("=")
        key = key.strip()
        if not separator or not _ENV_KEY.fullmatch(key):
            raise ValueError
        raw_value = raw_value.strip()
        if raw_value.startswith(("'", '"')):
            parsed = shlex.split(raw_value, comments=False, posix=True)
            if len(parsed) != 1:
                raise ValueError
            value = parsed[0]
        else:
            value = raw_value.split(" #", 1)[0].rstrip()
        values[key] = value
    return values
