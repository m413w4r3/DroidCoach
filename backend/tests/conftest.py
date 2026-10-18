from __future__ import annotations

import secrets
import pytest
import sys
from pathlib import Path
from fastapi.testclient import TestClient

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

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
