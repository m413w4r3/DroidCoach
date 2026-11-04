from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path

import pytest

from atlas_backend import openclaw_adapter, v4_router
from atlas_backend.openclaw_adapter import _env_float


BACKEND_ROOT = Path(__file__).resolve().parents[1]


@pytest.mark.parametrize("configured", [None, "", "   "])
def test_deadline_defaults_to_historical_twenty_seconds(monkeypatch, configured):
    if configured is None:
        monkeypatch.delenv("ATLAS_CHAT_V4_DEADLINE_S", raising=False)
    else:
        monkeypatch.setenv("ATLAS_CHAT_V4_DEADLINE_S", configured)

    assert _env_float("ATLAS_CHAT_V4_DEADLINE_S", 20.0, minimum=1.0, maximum=50.0) == 20.0


def test_deadline_override_is_read_and_bounded(monkeypatch):
    monkeypatch.setenv("ATLAS_CHAT_V4_DEADLINE_S", "40")
    assert _env_float("ATLAS_CHAT_V4_DEADLINE_S", 20.0, minimum=1.0, maximum=50.0) == 40.0

    monkeypatch.setenv("ATLAS_CHAT_V4_DEADLINE_S", " 40.5 ")
    assert _env_float("ATLAS_CHAT_V4_DEADLINE_S", 20.0, minimum=1.0, maximum=50.0) == 40.5


@pytest.mark.parametrize("invalid", ["not-a-number", "0", "-1", "51", "nan", "inf"])
def test_invalid_deadline_fails_explicitly(monkeypatch, invalid):
    monkeypatch.setenv("ATLAS_CHAT_V4_DEADLINE_S", invalid)
    with pytest.raises(ValueError, match="ATLAS_CHAT_V4_DEADLINE_S"):
        _env_float("ATLAS_CHAT_V4_DEADLINE_S", 20.0, minimum=1.0, maximum=50.0)


def _deadline_from_fresh_process(configured: str | None) -> subprocess.CompletedProcess[str]:
    environment = {key: value for key, value in os.environ.items() if key != "ATLAS_CHAT_V4_DEADLINE_S"}
    if configured is not None:
        environment["ATLAS_CHAT_V4_DEADLINE_S"] = configured
    return subprocess.run(
        [
            sys.executable,
            "-c",
            "import atlas_backend.openclaw_adapter as m; print(m.CHAT_V4_DEADLINE_SECONDS)",
        ],
        cwd=BACKEND_ROOT,
        env=environment,
        capture_output=True,
        text=True,
        check=False,
        timeout=30,
    )


def test_module_constant_defaults_to_twenty_in_fresh_process():
    result = _deadline_from_fresh_process(None)
    assert result.returncode == 0, result.stderr
    assert result.stdout.strip() == "20.0"


def test_module_constant_reads_personal_override_in_fresh_process():
    result = _deadline_from_fresh_process("40")
    assert result.returncode == 0, result.stderr
    assert result.stdout.strip() == "40.0"


def test_module_constant_rejects_invalid_override_in_fresh_process():
    result = _deadline_from_fresh_process("not-a-number")
    assert result.returncode != 0
    assert "ATLAS_CHAT_V4_DEADLINE_S" in result.stderr


def test_router_uses_the_adapter_deadline_binding():
    # v4_router imports the constant at module import. This guards the live
    # propagation path: a stale or diverging binding would silently keep 20 s.
    assert v4_router.CHAT_V4_DEADLINE_SECONDS == openclaw_adapter.CHAT_V4_DEADLINE_SECONDS
