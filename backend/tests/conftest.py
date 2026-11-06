from __future__ import annotations

import secrets
import sys
import atexit
import os
import tempfile
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

# main.py exposes a module-level ASGI app; isolate its import-time SQLite
# initialization from ATLAS_DB_PATH values that may point at an operator database.
_fd, _import_test_db = tempfile.mkstemp(prefix="atlas-backend-test-global-", suffix=".sqlite3")
os.close(_fd)
os.environ["ATLAS_DB_PATH"] = _import_test_db
os.environ.setdefault("ATLAS_V4_PROPOSAL_EXPIRY_SWEEPER_ENABLED", "0")
atexit.register(lambda: Path(_import_test_db).unlink(missing_ok=True))
atexit.register(lambda: Path(f"{_import_test_db}.pre-v4.bak").unlink(missing_ok=True))

from atlas_backend.main import create_app


@pytest.fixture
def token() -> str:
    return secrets.token_urlsafe(32)


@pytest.fixture
def client(tmp_path, token):
    app = create_app(db_path=tmp_path / "atlas-test.sqlite3", token=token)
    with TestClient(app) as test_client:
        yield test_client


@pytest.fixture
def auth_headers(token):
    return {"Authorization": f"Bearer {token}"}
