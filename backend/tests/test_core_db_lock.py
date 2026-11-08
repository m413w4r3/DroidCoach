from __future__ import annotations

import asyncio
import stat
from pathlib import Path

import pytest

from atlas_backend.main import create_app
from atlas_backend.store import SQLiteStore


def make_app(database: Path | str, monkeypatch):
    monkeypatch.setenv("ATLAS_V4_PROPOSAL_EXPIRY_SWEEPER_ENABLED", "0")
    monkeypatch.setenv("ATLAS_AGENT_ADAPTER", "mock")
    monkeypatch.setenv("ATLAS_V4_AUTH_MODE", "legacy")
    return create_app(db_path=database, token="synthetic-core-database-lock-token")


def test_same_database_lifespan_is_exclusive_and_released(tmp_path, monkeypatch):
    database = tmp_path / "core.sqlite3"
    first = make_app(database, monkeypatch)
    second = make_app(database, monkeypatch)
    lock_file = Path(f"{database}.lock")

    async def exercise_lifespans():
        async with first.router.lifespan_context(first):
            with pytest.raises(RuntimeError, match="already in use"):
                async with second.router.lifespan_context(second):
                    pass
            assert lock_file.exists()
            assert not lock_file.is_symlink()
            assert stat.S_IMODE(lock_file.stat().st_mode) == 0o600
            assert lock_file.read_bytes() == b""

        offline_store = SQLiteStore(database)
        with offline_store.connect() as connection:
            assert connection.execute("PRAGMA integrity_check").fetchone()[0] == "ok"

        async with second.router.lifespan_context(second):
            assert lock_file.exists()

    asyncio.run(exercise_lifespans())


def test_different_databases_can_start_together(tmp_path, monkeypatch):
    first = make_app(tmp_path / "first.sqlite3", monkeypatch)
    second = make_app(tmp_path / "second.sqlite3", monkeypatch)

    async def exercise_lifespans():
        async with first.router.lifespan_context(first):
            async with second.router.lifespan_context(second):
                assert Path(f"{first.state.store.path}.lock").exists()
                assert Path(f"{second.state.store.path}.lock").exists()

    asyncio.run(exercise_lifespans())


def test_database_symlink_aliases_share_the_same_lock(tmp_path, monkeypatch):
    database = tmp_path / "core.sqlite3"
    alias = tmp_path / "core-alias.sqlite3"
    first = make_app(database, monkeypatch)
    alias.symlink_to(database)
    second = make_app(alias, monkeypatch)

    async def exercise_lifespans():
        async with first.router.lifespan_context(first):
            with pytest.raises(RuntimeError, match="already in use"):
                async with second.router.lifespan_context(second):
                    pass

    asyncio.run(exercise_lifespans())


def test_database_lock_refuses_symlink(tmp_path, monkeypatch):
    database = tmp_path / "core.sqlite3"
    app = make_app(database, monkeypatch)
    lock_file = Path(f"{database}.lock")
    target = tmp_path / "lock-target"
    target.write_bytes(b"")
    lock_file.symlink_to(target)

    async def enter_lifespan():
        async with app.router.lifespan_context(app):
            pass

    with pytest.raises(RuntimeError, match="must not be a symlink"):
        asyncio.run(enter_lifespan())
    assert target.read_bytes() == b""


def test_memory_database_does_not_create_adjacent_lock(tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)
    app = make_app(":memory:", monkeypatch)

    async def enter_lifespan():
        async with app.router.lifespan_context(app):
            assert not Path(":memory:.lock").exists()

    asyncio.run(enter_lifespan())
