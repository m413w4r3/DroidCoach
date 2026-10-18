"""Agent proposals; adapters in this module never execute actions."""

from __future__ import annotations

from abc import ABC, abstractmethod
from typing import Any

from pydantic import BaseModel, ConfigDict, Field, StrictStr


class ProposedAction(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    action: StrictStr = Field(min_length=1, max_length=80)
    summary: StrictStr = Field(min_length=1, max_length=240)
    parameters: dict[str, Any] = Field(default_factory=dict, max_length=10)


class AgentAdapter(ABC):
    """Interface for a proposal-only agent adapter."""

    @abstractmethod
    def propose(
        self,
        context: str,
        mandate: dict[str, Any],
        capabilities: dict[str, bool],
    ) -> ProposedAction:
        """Return a proposal. Implementations must not perform side effects."""


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
