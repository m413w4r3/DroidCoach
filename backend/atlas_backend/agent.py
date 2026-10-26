"""Agent adapter interfaces and the deterministic offline adapter."""

from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import Any

from pydantic import BaseModel, ConfigDict, Field, StrictStr

from .models_v2 import AgentOutputV2, MandateSummary, SharedContext


class ProposedAction(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    action: StrictStr = Field(min_length=1, max_length=80)
    summary: StrictStr = Field(min_length=1, max_length=240)
    parameters: dict[str, Any] = Field(default_factory=dict, max_length=10)


@dataclass(frozen=True)
class AgentStatus:
    adapter: str
    connected: bool
    breaker: str
    quota: dict[str, int | bool]


class AgentAdapter(ABC):
    """Interface for proposal-only adapters; no adapter can execute actions."""

    name = "mock"

    @abstractmethod
    def propose(
        self,
        context: str,
        mandate: dict[str, Any],
        capabilities: dict[str, bool],
    ) -> ProposedAction:
        """Return a legacy V1 proposal without performing side effects."""

    @abstractmethod
    def chat_v2(
        self,
        conversation_id: str,
        message: str,
        shared_context: SharedContext,
        mandate: MandateSummary,
        intent: str = "chat",
    ) -> AgentOutputV2:
        """Return untrusted V2 proposal candidates for policy validation."""

    @abstractmethod
    def status(self) -> AgentStatus:
        """Expose non-sensitive adapter health and quota information."""


class MockAgentAdapter(AgentAdapter):
    """Deterministic, offline adapter that does not retain or echo user text."""

    def propose(
        self,
        context: str,
        mandate: dict[str, Any],
        capabilities: dict[str, bool],
    ) -> ProposedAction:
        del context, mandate, capabilities
        return ProposedAction(
            action="start_focus_session",
            summary="Proposer un focus de 10 minutes.",
            parameters={"duration_minutes": 10},
        )

    def chat_v2(
        self,
        conversation_id: str,
        message: str,
        shared_context: SharedContext,
        mandate: MandateSummary,
        intent: str = "chat",
    ) -> AgentOutputV2:
        del conversation_id, message, shared_context, mandate, intent
        return AgentOutputV2.model_validate(
            {
                "reply": "Je te propose de choisir une petite priorité et de lancer un court focus.",
                "proposals": [
                    {
                        "action_type": "home.set_directive",
                        "parameters": {
                            "title": "Choisir une priorité",
                            "body": "Prends une petite étape que tu peux commencer maintenant.",
                            "primary_action": "focus.start",
                        },
                        "reason": "Une prochaine étape claire facilite le démarrage.",
                    },
                    {
                        "action_type": "focus.propose",
                        "parameters": {
                            "focus_minutes": 10,
                            "break_minutes": 5,
                            "cycles": 1,
                            "label": "Premier focus",
                        },
                        "reason": "Une session courte permet de commencer sans engagement long.",
                    },
                ],
            }
        )

    def propose_plan_revision(
        self,
        *,
        conversation_id: str | None,
        message: str,
        plan: dict[str, Any],
        mandate: MandateSummary,
    ) -> dict[str, Any]:
        """Return one deterministic, display-only V3 proposal.

        The demo adapter never calls a model and never executes an action. It
        shifts the first activity by 30 minutes when that stays inside the day,
        otherwise by -30 minutes, leaving every other field untouched. Android
        still validates the proposal and remains the only execution authority.
        """
        del conversation_id, message, mandate
        raw_activities = plan.get("activities") or []
        activities = [item for item in raw_activities if isinstance(item, dict)]
        shift = 0
        if activities:
            first = activities[0]
            start = first.get("start_minute")
            duration = first.get("duration_minutes")
            if isinstance(start, int) and isinstance(duration, int):
                shift = 30 if start + duration + 30 <= 1440 else -30

        proposed: list[dict[str, Any]] = []
        for index, activity in enumerate(activities):
            start = activity.get("start_minute")
            if not isinstance(start, int):
                continue
            shifted = start + (shift if index == 0 else 0)
            if shifted < 0 or shifted > 1439:
                shifted = start
            proposed.append(
                {
                    "client_ref": f"slot-{index + 1}",
                    "activity_id": activity.get("activity_id"),
                    "kind": activity.get("kind"),
                    "title": activity.get("title"),
                    "local_date": plan.get("local_date"),
                    "start_minute": shifted,
                    "duration_minutes": activity.get("duration_minutes"),
                    "timezone": plan.get("timezone"),
                    "priority": activity.get("priority"),
                    "goal_id": activity.get("goal_id"),
                    "sub_goal_id": None,
                    "focus": None,
                    "training_ref": None,
                    "notes": None,
                }
            )

        summary = (
            "Décaler le premier bloc de 30 minutes."
            if shift == 30
            else "Avancer le premier bloc de 30 minutes."
            if shift == -30
            else "Conserver les blocs proposés."
        )
        return {
            "reply": "Je propose un petit décalage du premier bloc, sans exécuter de changement.",
            "proposal": {
                "action_type": "plan.revise_day",
                "reason": "Le décalage laisse le reste de la journée inchangé.",
                "parameters": {
                    "plan_id": plan.get("plan_id"),
                    "base_version": plan.get("version"),
                    "local_date": plan.get("local_date"),
                    "timezone": plan.get("timezone"),
                    "summary": summary,
                    "activities": proposed,
                },
            },
        }

    def status(self) -> AgentStatus:
        return AgentStatus(
            adapter="mock",
            connected=True,
            breaker="closed",
            quota={"calls_last_minute": 0, "daily_tokens": 0, "limited": False},
        )
