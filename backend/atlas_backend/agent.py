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

    def status(self) -> AgentStatus:
        return AgentStatus(
            adapter="mock",
            connected=True,
            breaker="closed",
            quota={"calls_last_minute": 0, "daily_tokens": 0, "limited": False},
        )
