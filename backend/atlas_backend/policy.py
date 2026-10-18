"""Deterministic mandate and capability checks for proposed actions."""

from __future__ import annotations

import json
import os
from dataclasses import dataclass
from typing import Any

DEFAULT_CAPABILITIES: dict[str, bool] = {
    "focus_sessions": True,
    "discussion": True,
    "alternatives": True,
    "postpone": True,
    "pause": True,
    "event_ingest": True,
    "sync": True,
    "content_recommendations": True,
    "coach_messages": True,
    "export": True,
}

MANDATE: dict[str, Any] = {
    "id": "local-coaching-v1",
    "purpose": "Proposer des étapes de coaching personnel, sans contrôle du système.",
    "allowed_actions": [
        "start_focus_session",
        "discuss",
        "suggest_alternative",
        "postpone",
        "pause",
    ],
}

ACTION_CAPABILITY: dict[str, str] = {
    "start_focus_session": "focus_sessions",
    "discuss": "discussion",
    "suggest_alternative": "alternatives",
    "postpone": "postpone",
    "pause": "pause",
}
ACTION_PARAMETERS: dict[str, dict[str, tuple[int, int]]] = {
    "start_focus_session": {"duration_minutes": (1, 180)},
    "discuss": {},
    "suggest_alternative": {"duration_minutes": (1, 180)},
    "postpone": {"delay_minutes": (1, 1440)},
    "pause": {"duration_minutes": (1, 180)},
}


@dataclass(frozen=True)
class PolicyDecision:
    accepted: bool
    reason: str


def configured_capabilities(raw: str | None = None) -> dict[str, bool]:
    """Merge optional independent flags from strict JSON configuration."""
    value = raw if raw is not None else os.getenv("ATLAS_CAPABILITIES_JSON")
    result = dict(DEFAULT_CAPABILITIES)
    if value is None or value.strip() == "":
        return result
    try:
        overrides = json.loads(value)
    except json.JSONDecodeError as exc:
        raise ValueError("ATLAS_CAPABILITIES_JSON must be a JSON object") from exc
    if not isinstance(overrides, dict):
        raise ValueError("ATLAS_CAPABILITIES_JSON must be a JSON object")
    unknown = set(overrides) - set(DEFAULT_CAPABILITIES)
    if unknown:
        raise ValueError(f"unknown capability flags: {', '.join(sorted(unknown))}")
    if any(not isinstance(flag, bool) for flag in overrides.values()):
        raise ValueError("capability values must be booleans")
    result.update(overrides)
    return result


class PolicyEngine:
    def __init__(self, capabilities: dict[str, bool] | None = None) -> None:
        self.capabilities = dict(DEFAULT_CAPABILITIES if capabilities is None else capabilities)

    def validate(self, proposal: Any) -> PolicyDecision:
        action = getattr(proposal, "action", None)
        if not isinstance(action, str) or action not in MANDATE["allowed_actions"]:
            return PolicyDecision(False, "action hors du mandat explicite")
        required = ACTION_CAPABILITY[action]
        if not self.capabilities.get(required, False):
            return PolicyDecision(False, f"capability désactivée : {required}")
        parameters = getattr(proposal, "parameters", {})
        allowed_parameters = ACTION_PARAMETERS[action]
        if not isinstance(parameters, dict) or set(parameters) - set(allowed_parameters):
            return PolicyDecision(False, "paramètres hors du mandat explicite")
        for name, value in parameters.items():
            minimum, maximum = allowed_parameters[name]
            if isinstance(value, bool) or not isinstance(value, int) or not minimum <= value <= maximum:
                return PolicyDecision(False, f"paramètre invalide : {name}")
        return PolicyDecision(True, "proposition autorisée par le mandat")

    def require_capability(self, capability: str) -> None:
        if not self.capabilities.get(capability, False):
            from fastapi import HTTPException

            raise HTTPException(status_code=403, detail=f"capability désactivée : {capability}")

    def as_dict(self) -> dict[str, Any]:
        return {
            "schema_version": 1,
            "mandate": MANDATE,
            "capabilities": self.capabilities,
            "rules": {
                "agent_outputs_are_proposals": True,
                "actions_executed_by_agent": False,
                "deterministic_policy_validation": True,
            },
            "agent": {
                "active_adapter": "mock",
                "openclaw": {
                    "status": "unsupported",
                    "detail": "adaptateur non implémenté",
                },
            },
        }
