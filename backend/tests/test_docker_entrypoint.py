import importlib.util
import os
from pathlib import Path

import pytest


ENTRYPOINT_PATH = Path(__file__).parents[1] / "docker-entrypoint.py"
ENTRYPOINT_SPEC = importlib.util.spec_from_file_location("atlas_docker_entrypoint", ENTRYPOINT_PATH)
assert ENTRYPOINT_SPEC is not None and ENTRYPOINT_SPEC.loader is not None
ENTRYPOINT = importlib.util.module_from_spec(ENTRYPOINT_SPEC)
ENTRYPOINT_SPEC.loader.exec_module(ENTRYPOINT)


def write_core_secrets(
    secrets_dir: Path,
    *,
    android_token: str = "synthetic-android-token",
    tool_token: str = "synthetic-tool-token",
) -> None:
    secrets_dir.mkdir(parents=True, exist_ok=True)
    (secrets_dir / "atlas_token").write_text(android_token, encoding="ascii")
    (secrets_dir / "atlas_tool_token").write_text(tool_token, encoding="ascii")


def clear_token_environment(monkeypatch) -> None:
    for name in ("ATLAS_TOKEN", "ATLAS_TOOL_TOKEN", "OPENCLAW_GATEWAY_TOKEN"):
        monkeypatch.delenv(name, raising=False)


def test_required_secrets_are_loaded_when_present_and_distinct(tmp_path, monkeypatch):
    clear_token_environment(monkeypatch)
    write_core_secrets(tmp_path)

    ENTRYPOINT.load_and_export_secrets(tmp_path)

    assert os.environ["ATLAS_TOKEN"] == "synthetic-android-token"
    assert os.environ["ATLAS_TOOL_TOKEN"] == "synthetic-tool-token"


def test_identical_required_secrets_fail_without_exporting_values(tmp_path, monkeypatch):
    clear_token_environment(monkeypatch)
    write_core_secrets(tmp_path, android_token="same-synthetic-token", tool_token="same-synthetic-token")

    with pytest.raises(SystemExit, match="must be distinct"):
        ENTRYPOINT.load_and_export_secrets(tmp_path)

    assert "ATLAS_TOKEN" not in os.environ
    assert "ATLAS_TOOL_TOKEN" not in os.environ


def test_optional_gateway_secret_absent_is_success_and_unsets_gateway_variable(tmp_path, monkeypatch):
    clear_token_environment(monkeypatch)
    monkeypatch.setenv("OPENCLAW_GATEWAY_TOKEN", "stale-synthetic-token")
    write_core_secrets(tmp_path)

    ENTRYPOINT.load_and_export_secrets(tmp_path)

    assert "OPENCLAW_GATEWAY_TOKEN" not in os.environ


def test_optional_gateway_secret_is_exported_without_printing_its_value(tmp_path, monkeypatch, capsys):
    clear_token_environment(monkeypatch)
    write_core_secrets(tmp_path)
    gateway_token = "synthetic-openclaw-gateway-token"
    (tmp_path / "openclaw_gateway_token").write_text(f"{gateway_token}\r\n", encoding="ascii")

    ENTRYPOINT.load_and_export_secrets(tmp_path)

    assert os.environ["OPENCLAW_GATEWAY_TOKEN"] == gateway_token
    captured = capsys.readouterr()
    assert gateway_token not in captured.out
    assert gateway_token not in captured.err


@pytest.mark.parametrize("secret_bytes", [b"", b"gateway\nforged", b"gateway\x00forged", b"\xff"])
def test_optional_gateway_secret_empty_or_malformed_fails(tmp_path, monkeypatch, secret_bytes):
    clear_token_environment(monkeypatch)
    write_core_secrets(tmp_path)
    (tmp_path / "openclaw_gateway_token").write_bytes(secret_bytes)

    with pytest.raises(SystemExit, match="empty or malformed"):
        ENTRYPOINT.load_and_export_secrets(tmp_path)

    assert "ATLAS_TOKEN" not in os.environ
    assert "ATLAS_TOOL_TOKEN" not in os.environ
    assert "OPENCLAW_GATEWAY_TOKEN" not in os.environ


@pytest.mark.parametrize("duplicate", ["synthetic-android-token", "synthetic-tool-token"])
def test_gateway_secret_must_be_distinct_from_both_core_tokens(tmp_path, monkeypatch, duplicate):
    clear_token_environment(monkeypatch)
    write_core_secrets(tmp_path)
    (tmp_path / "openclaw_gateway_token").write_text(duplicate, encoding="ascii")

    with pytest.raises(SystemExit, match="must be distinct"):
        ENTRYPOINT.load_and_export_secrets(tmp_path)

    assert "ATLAS_TOKEN" not in os.environ
    assert "ATLAS_TOOL_TOKEN" not in os.environ
    assert "OPENCLAW_GATEWAY_TOKEN" not in os.environ
