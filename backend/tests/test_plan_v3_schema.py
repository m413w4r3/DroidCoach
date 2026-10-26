"""The committed JSON schema must stay in sync with the Pydantic contract."""

from __future__ import annotations

import json
from pathlib import Path

from atlas_backend.plan_v3 import PlanProposalEnvelopeV3

SCHEMA_PATH = Path(__file__).resolve().parents[2] / "contracts" / "plan-revision-v3.schema.json"
METADATA_KEYS = {"$schema", "$id", "title", "description"}


def test_committed_schema_matches_the_model():
    committed = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
    generated = PlanProposalEnvelopeV3.model_json_schema()
    for key in METADATA_KEYS:
        committed.pop(key, None)
        generated.pop(key, None)
    assert committed == generated


def test_committed_schema_rejects_unknown_fields():
    committed = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
    assert committed["additionalProperties"] is False
    assert committed["$defs"]["PlanActivityV3"]["additionalProperties"] is False
