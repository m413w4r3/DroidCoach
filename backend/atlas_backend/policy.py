"""Deterministic mandate and capability checks for proposed actions."""

from __future__ import annotations

import json
import os
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Any
from urllib.parse import urlsplit
from zoneinfo import ZoneInfo

from pydantic import ValidationError

from .content import DEMO_CONTENT, ContentItem
from .models_v2 import MandateSummary, ProposalCandidate

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
    "home_directives": True,
    "home_layout": True,
    "habits": True,
    "app_policy": True,
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
V2_ACTION_CAPABILITY: dict[str, str] = {
    "home.set_directive": "home_directives",
    "home.reorder_cards": "home_layout",
    "focus.propose": "focus_sessions",
    "focus.plan": "focus_sessions",
    "focus.start": "focus_sessions",
    "focus.pause": "focus_sessions",
    "habit.suggest": "habits",
    "app_policy.propose": "app_policy",
    "app_policy.adjust": "app_policy",
    "content.recommend": "content_recommendations",
}
V2_ACTIONS = frozenset(V2_ACTION_CAPABILITY)
SYSTEM_PACKAGE_PREFIXES = (
    "android.",
    "com.android.",
    "com.google.android.",
    "com.google.android.permissioncontroller",
    "com.android.systemui",
    "com.samsung.android.",
    "com.miui.",
    "com.xiaomi.",
    "com.oppo.",
    "com.oneplus.",
    "com.huawei.",
    "com.hihonor.",
    "com.motorola.",
    "com.lge.",
)
SYSTEM_PACKAGE_NAMES = frozenset(
    {"settings", "systemui", "launcher", "phone", "dialer", "emergency", "devicepolicy", "permissioncontroller"}
)
DEFAULT_CONTENT_DOMAINS = frozenset(
    (urlsplit(item.url).hostname or "").lower() for item in DEMO_CONTENT
)


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
        raise TypeError("ATLAS_CAPABILITIES_JSON must be a JSON object")
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

    def validate_v2(
        self,
        proposal: ProposalCandidate,
        mandate: MandateSummary,
        *,
        now: datetime | None = None,
        daily_plan_count: int = 0,
        accepted_focus_refs: set[str] | None = None,
    ) -> PolicyDecision:
        """Apply the closed server policy to a typed V2 candidate."""
        action = proposal.action_type
        if action not in V2_ACTIONS:
            return PolicyDecision(False, "action V2 hors liste fermée")
        if action not in mandate.allowed_actions:
            return PolicyDecision(False, "action absente du mandat fourni")
        capability = V2_ACTION_CAPABILITY[action]
        if not self.capabilities.get(capability, False):
            return PolicyDecision(False, f"capability désactivée : {capability}")

        params = proposal.parameters.model_dump(mode="json", exclude_none=True)
        current_time = now or datetime.now(timezone.utc)
        if action in {"focus.propose", "focus.plan"} and mandate.focus_window is not None:
            session_minutes = (
                proposal.parameters.focus_minutes * proposal.parameters.cycles
                + proposal.parameters.break_minutes * max(0, proposal.parameters.cycles - 1)
            )
            if session_minutes > mandate.focus_window.max_minutes_per_session:
                return PolicyDecision(False, "durée supérieure à la limite de session du mandat")
        if action in {"app_policy.propose", "app_policy.adjust"}:
            reason = self._validate_app_policy(params, mandate, current_time)
            if reason:
                return PolicyDecision(False, reason)
        if action == "content.recommend":
            reason = self._validate_content(params)
            if reason:
                return PolicyDecision(False, reason)
        if action == "focus.plan":
            start_at = proposal.parameters.start_at
            if start_at < current_time.astimezone(timezone.utc):
                return PolicyDecision(False, "planification dans le passé")
            if start_at > current_time.astimezone(timezone.utc) + timedelta(days=7):
                return PolicyDecision(False, "planification trop éloignée")
            window = mandate.focus_window
            if window is not None:
                if proposal.parameters.timezone != window.timezone:
                    return PolicyDecision(False, "fuseau horaire différent du mandat focus")
                local_start = start_at.astimezone(ZoneInfo(window.timezone))
                weekday = local_start.strftime("%A").lower()
                local_time = local_start.strftime("%H:%M")
                if weekday not in window.days or not window.starts_at <= local_time < window.ends_at:
                    return PolicyDecision(False, "planification hors de la fenêtre focus")
                session_minutes = (
                    proposal.parameters.focus_minutes * proposal.parameters.cycles
                    + proposal.parameters.break_minutes * max(0, proposal.parameters.cycles - 1)
                )
                local_end = (start_at + timedelta(minutes=session_minutes)).astimezone(ZoneInfo(window.timezone))
                if local_end.date() != local_start.date() or local_end.strftime("%H:%M") > window.ends_at:
                    return PolicyDecision(False, "session focus hors de la fenêtre du mandat")
                if daily_plan_count >= window.max_sessions_per_day:
                    return PolicyDecision(False, "quota de sessions focus du mandat atteint")
        if action in {"focus.start", "focus.pause"}:
            if len(proposal.parameters.session_ref) < 16:
                return PolicyDecision(False, "référence de session invalide")
            if proposal.parameters.session_ref not in (accepted_focus_refs or set()):
                return PolicyDecision(False, "référence de session focus non acceptée")
        if (
            action == "home.set_directive"
            and proposal.parameters.focus_proposal_id
            and len(proposal.parameters.focus_proposal_id) < 16
        ):
            return PolicyDecision(False, "référence de proposition invalide")
        return PolicyDecision(True, "proposition autorisée par la policy V2")

    @staticmethod
    def _validate_app_policy(
        params: dict[str, Any], mandate: MandateSummary, now: datetime
    ) -> str | None:
        package_ref = params["package_ref"]
        lowered = package_ref.lower()
        if (
            lowered == "android"
            or lowered in SYSTEM_PACKAGE_NAMES
            or any(lowered.startswith(prefix) for prefix in SYSTEM_PACKAGE_PREFIXES)
        ):
            return "package système interdit"
        bounds = mandate.app_policy_bounds.get(package_ref)
        if bounds is None:
            return "package_ref absent des bornes consenties"
        valid_until = datetime.fromisoformat(params["valid_until"].replace("Z", "+00:00"))
        if valid_until <= now.astimezone(timezone.utc) or valid_until > now.astimezone(timezone.utc) + timedelta(days=14):
            return "valid_until doit être dans les 14 prochains jours"
        minimum_limit = bounds.get("min_limit_minutes")
        if type(minimum_limit) is not int or params["daily_limit_minutes"] < minimum_limit:
            return "daily_limit_minutes hors des bornes du mandat"
        max_mode = bounds.get("max_mode")
        mode_rank = {"soft_reminder": 0, "friction": 1}
        if max_mode not in mode_rank or mode_rank[params["mode"]] > mode_rank[max_mode]:
            return "mode hors des bornes du mandat"
        max_valid_days = bounds.get("max_valid_days")
        duration_days = (valid_until - now.astimezone(timezone.utc)).total_seconds() / 86400
        if type(max_valid_days) is not int or duration_days > min(max_valid_days, 14):
            return "durée hors des bornes du mandat"
        return None

    @staticmethod
    def _validate_content(params: dict[str, Any]) -> str | None:
        if params.get("content_id") is not None:
            item = next((item for item in DEMO_CONTENT if item.id == params["content_id"]), None)
            if item is None:
                return "content_id inconnu"
            try:
                ContentItem(id=item.id, title=params["title"], url=item.url)
            except ValidationError:
                return "titre de contenu invalide"
            return None
        try:
            item = ContentItem(id="recommendation", title=params["title"], url=params["url"])
        except ValidationError:
            return "URL de contenu invalide"
        allowed = {
            domain.strip().lower()
            for domain in os.getenv("ATLAS_CONTENT_ALLOWED_DOMAINS", ",".join(sorted(DEFAULT_CONTENT_DOMAINS))).split(",")
            if domain.strip()
        }
        hostname = (urlsplit(item.url).hostname or "").lower()
        if hostname not in allowed:
            return "domaine de contenu hors allowlist"
        return None

    @staticmethod
    def required_level(proposal: ProposalCandidate, mandate: MandateSummary) -> str:
        action = proposal.action_type
        if action in {"focus.plan", "focus.start", "focus.pause", "app_policy.propose"}:
            return "confirm"
        if action == "app_policy.adjust":
            return "preauthorized_ok" if mandate.mode == "preauthorized" else "confirm"
        if action in {"home.set_directive", "home.reorder_cards", "content.recommend"}:
            return "preauthorized_ok"
        if action in {"focus.propose", "habit.suggest"}:
            return "proposal_only"
        if mandate.mode == "preauthorized" and action in mandate.allowed_actions:
            return "preauthorized_ok"
        return "confirm"

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
                "active_adapter": os.getenv("ATLAS_AGENT_ADAPTER", "mock"),
            },
        }
