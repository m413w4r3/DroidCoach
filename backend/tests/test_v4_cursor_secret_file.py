from __future__ import annotations

import os
from pathlib import Path

import pytest

from atlas_backend import v4_router
from atlas_backend.store import SQLiteStore


SECRET = "synthetic-cursor-secret-32-bytes-minimum"


def private_file(path: Path, payload: bytes) -> Path:
    path.write_bytes(payload)
    path.chmod(0o600)
    return path


def clear_sources(monkeypatch) -> None:
    monkeypatch.delenv("ATLAS_V4_CURSOR_SECRET", raising=False)
    monkeypatch.delenv("ATLAS_V4_CURSOR_SECRET_FILE", raising=False)


def test_cursor_secret_file_accepts_private_utf8_and_one_final_line_ending(tmp_path, monkeypatch):
    path = private_file(tmp_path / "cursor", (SECRET + "\r\n").encode("utf-8"))
    monkeypatch.setenv("ATLAS_V4_CURSOR_SECRET_FILE", str(path))
    monkeypatch.delenv("ATLAS_V4_CURSOR_SECRET", raising=False)

    assert v4_router._configured_cursor_secret("dual") == SECRET.encode("utf-8")
    assert v4_router._configured_cursor_secret("principal") == SECRET.encode("utf-8")


def test_cursor_secret_environment_source_remains_supported_and_empty_is_invalid(monkeypatch):
    clear_sources(monkeypatch)
    monkeypatch.setenv("ATLAS_V4_CURSOR_SECRET", SECRET)
    assert v4_router._configured_cursor_secret("dual") == SECRET.encode("utf-8")
    monkeypatch.setenv("ATLAS_V4_CURSOR_SECRET", "")
    with pytest.raises(ValueError):
        v4_router._configured_cursor_secret("dual")


def test_cursor_secret_sources_are_exclusive_even_when_one_is_empty(tmp_path, monkeypatch):
    path = private_file(tmp_path / "cursor", SECRET.encode("utf-8"))
    monkeypatch.setenv("ATLAS_V4_CURSOR_SECRET", "")
    monkeypatch.setenv("ATLAS_V4_CURSOR_SECRET_FILE", str(path))
    with pytest.raises(ValueError, match="only one"):
        v4_router._configured_cursor_secret("dual")


def test_cursor_secret_missing_is_allowed_only_outside_principal_mode(tmp_path, monkeypatch):
    clear_sources(monkeypatch)
    assert v4_router._configured_cursor_secret("dual") is None
    store = SQLiteStore(tmp_path / "cursor-principal.sqlite3")
    monkeypatch.setenv("ATLAS_V4_AUTH_MODE", "principal")
    with pytest.raises(ValueError, match="required"):
        v4_router.create_v4_router(store)


@pytest.mark.parametrize(
    "payload",
    [b"x" * 31, b"\xff" + b"x" * 40, b"x" * 4097],
    ids=["too-short", "invalid-utf8", "too-large"],
)
def test_cursor_secret_file_rejects_bad_contents(tmp_path, payload):
    path = private_file(tmp_path / "cursor", payload)
    with pytest.raises(ValueError):
        v4_router._read_cursor_secret_file(str(path))


def test_cursor_secret_file_rejects_relative_paths(tmp_path):
    relative = os.path.relpath(tmp_path / "cursor", Path.cwd())
    with pytest.raises(ValueError, match="absolute"):
        v4_router._read_cursor_secret_file(relative)


def test_cursor_secret_file_rejects_missing_path(tmp_path):
    with pytest.raises(ValueError, match="unavailable"):
        v4_router._read_cursor_secret_file(str(tmp_path / "missing-cursor"))


def test_cursor_secret_file_rejects_symlink_and_non_regular_file(tmp_path):
    target = private_file(tmp_path / "target", SECRET.encode("utf-8"))
    link = tmp_path / "link"
    link.symlink_to(target)
    with pytest.raises(ValueError):
        v4_router._read_cursor_secret_file(str(link))
    with pytest.raises(ValueError):
        v4_router._read_cursor_secret_file(str(tmp_path))


def test_cursor_secret_file_requires_exact_private_mode(tmp_path):
    path = private_file(tmp_path / "cursor", SECRET.encode("utf-8"))
    path.chmod(0o640)
    with pytest.raises(ValueError, match="mode 0600"):
        v4_router._read_cursor_secret_file(str(path))


def test_cursor_secret_file_requires_effective_process_owner(tmp_path, monkeypatch):
    path = private_file(tmp_path / "cursor", SECRET.encode("utf-8"))
    actual_owner = os.geteuid()
    monkeypatch.setattr(v4_router.os, "geteuid", lambda: actual_owner + 1)
    with pytest.raises(ValueError, match="owned"):
        v4_router._read_cursor_secret_file(str(path))
