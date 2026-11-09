from __future__ import annotations

import asyncio
import stat
from pathlib import Path

import pytest

from atlas_backend.main import (
    _acquire_core_database_lock,
    _release_core_database_lock,
    create_app,
)
from atlas_backend.store import SQLiteStore


def make_app(database: Path | str, monkeypatch, *, expiry_sweeper_enabled: bool = False):
    monkeypatch.setenv(
        "ATLAS_V4_PROPOSAL_EXPIRY_SWEEPER_ENABLED",
        "1" if expiry_sweeper_enabled else "0",
    )
    monkeypatch.setenv("ATLAS_AGENT_ADAPTER", "mock")
    monkeypatch.setenv("ATLAS_V4_AUTH_MODE", "legacy")

    async def run_inline(function, *args, **kwargs):
        return function(*args, **kwargs)

    monkeypatch.setattr("atlas_backend.main.asyncio.to_thread", run_inline)
    return create_app(db_path=database, token="synthetic-core-database-lock-token")


def run_bounded(coroutine):
    asyncio.run(asyncio.wait_for(coroutine, timeout=5))


def assert_no_lingering_tasks():
    current = asyncio.current_task()
    assert [task for task in asyncio.all_tasks() if task is not current and not task.done()] == []


def assert_database_lock_is_available(database: Path):
    lock_fd = _acquire_core_database_lock(database)
    assert lock_fd is not None
    _release_core_database_lock(lock_fd)


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
        assert_no_lingering_tasks()

    run_bounded(exercise_lifespans())


def test_different_databases_can_start_together(tmp_path, monkeypatch):
    first = make_app(tmp_path / "first.sqlite3", monkeypatch)
    second = make_app(tmp_path / "second.sqlite3", monkeypatch)

    async def exercise_lifespans():
        async with first.router.lifespan_context(first):
            async with second.router.lifespan_context(second):
                assert Path(f"{first.state.store.path}.lock").exists()
                assert Path(f"{second.state.store.path}.lock").exists()
        assert_no_lingering_tasks()

    run_bounded(exercise_lifespans())


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

    run_bounded(exercise_lifespans())


def test_database_lock_refuses_symlink(tmp_path):
    database = tmp_path / "core.sqlite3"
    lock_file = Path(f"{database}.lock")
    target = tmp_path / "lock-target"
    target.write_bytes(b"")
    lock_file.symlink_to(target)

    with pytest.raises(RuntimeError, match="must not be a symlink"):
        _acquire_core_database_lock(database)
    assert target.read_bytes() == b""


def test_memory_database_does_not_create_adjacent_lock(tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)
    app = make_app(":memory:", monkeypatch)

    async def enter_lifespan():
        async with app.router.lifespan_context(app):
            assert not Path(":memory:.lock").exists()

    run_bounded(enter_lifespan())


def test_store_initialization_runs_under_database_lock(tmp_path, monkeypatch):
    database = tmp_path / "initialization.sqlite3"
    original_initialize = SQLiteStore.initialize
    initialize_calls = 0

    def check_lock_before_initialize(store):
        nonlocal initialize_calls
        with pytest.raises(RuntimeError, match="already in use"):
            _acquire_core_database_lock(store.path)
        initialize_calls += 1
        return original_initialize(store)

    monkeypatch.setattr(SQLiteStore, "initialize", check_lock_before_initialize)
    make_app(database, monkeypatch)
    assert initialize_calls == 1
    assert_database_lock_is_available(database)


def test_database_lock_released_when_store_initialization_raises(tmp_path, monkeypatch):
    database = tmp_path / "initialization-error.sqlite3"

    def fail_initialize(_store):
        raise RuntimeError("synthetic store initialization failure")

    monkeypatch.setattr(SQLiteStore, "initialize", fail_initialize)
    with pytest.raises(RuntimeError, match="synthetic store initialization failure"):
        make_app(database, monkeypatch)

    assert_database_lock_is_available(database)


def test_database_lock_released_when_lifespan_startup_raises(tmp_path, monkeypatch):
    database = tmp_path / "startup-error.sqlite3"
    app = make_app(database, monkeypatch, expiry_sweeper_enabled=True)
    original_create_task = asyncio.create_task
    create_task_calls = 0

    def fail_create_task(coroutine, *args, **kwargs):
        nonlocal create_task_calls
        create_task_calls += 1
        if create_task_calls == 1:
            return original_create_task(coroutine, *args, **kwargs)
        coroutine.close()
        raise RuntimeError("synthetic lifespan startup failure")

    async def enter_lifespan():
        with pytest.raises(RuntimeError, match="synthetic lifespan startup failure"):
            async with app.router.lifespan_context(app):
                pass
        assert_no_lingering_tasks()

    with monkeypatch.context() as startup_patch:
        startup_patch.setattr("atlas_backend.main.asyncio.create_task", fail_create_task)
        run_bounded(enter_lifespan())

    assert create_task_calls == 2
    assert_database_lock_is_available(database)


def test_database_lock_released_when_lifespan_body_raises(tmp_path, monkeypatch):
    database = tmp_path / "lifespan-error.sqlite3"
    app = make_app(database, monkeypatch)

    async def raise_inside_lifespan():
        with pytest.raises(ValueError, match="synthetic lifespan failure"):
            async with app.router.lifespan_context(app):
                raise ValueError("synthetic lifespan failure")
        assert_no_lingering_tasks()

    run_bounded(raise_inside_lifespan())
    assert_database_lock_is_available(database)
