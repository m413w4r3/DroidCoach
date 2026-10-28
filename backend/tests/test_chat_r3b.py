from __future__ import annotations

from copy import deepcopy

import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from atlas_backend.agent import MockAgentAdapter
from atlas_backend.main import create_app
from atlas_backend.models_v2 import AgentChatRequest


def chat_body() -> dict:
    return {
        "conversation_id": "synthetic-conversation-r3b",
        "message": "Propose une prochaine étape de travail.",
        "shared_context": {
            "consented_categories": ["projects"],
            "categories": {"projects": ["Projet synthétique"]},
        },
        "mandate_summary": {
            "id": "synthetic-mandate-r3b",
            "version": 1,
            "allowed_actions": ["home.set_directive", "focus.propose"],
            "mode": "proposals_only",
            "focus_window": None,
            "app_policy_bounds": {},
        },
    }


class CountingMockAgent(MockAgentAdapter):
    def __init__(self) -> None:
        self.calls = 0

    def chat_v2(self, *args, **kwargs):
        self.calls += 1
        return super().chat_v2(*args, **kwargs)


@pytest.mark.parametrize(
    "mutate",
    [
        lambda body: body.update(message="x" * 2001),
        lambda body: body.update(conversation_id=None),
        lambda body: body.update(unexpected="rejected"),
        lambda body: body["mandate_summary"].update(mode="unbounded"),
        lambda body: body["shared_context"].update(categories={"unknown": ["rejected"]}),
        lambda body: body["shared_context"].update(
            consented_categories=[
                "focus", "habit", "content", "coach_interaction", "app_usage_summary", "goals",
                "location_summary", "projects", "availability", "preferences", "package_names", "urls", "habit_titles",
            ],
            categories={
                category: ["x" * 200 for _ in range(10)]
                for category in [
                    "focus", "habit", "content", "coach_interaction", "app_usage_summary", "goals",
                    "location_summary", "projects", "availability", "preferences", "package_names", "urls", "habit_titles",
                ]
            },
        ),
    ],
)
def test_chat_contract_rejects_invalid_limits_and_fields(mutate) -> None:
    body = deepcopy(chat_body())
    mutate(body)
    with pytest.raises(ValidationError):
        AgentChatRequest.model_validate(body)


def test_chat_accepts_the_2000_character_message_limit() -> None:
    body = chat_body()
    body["message"] = "x" * 2000
    assert AgentChatRequest.model_validate(body).message == body["message"]


def test_chat_backend_returns_only_policy_validated_proposals(tmp_path) -> None:
    agent = CountingMockAgent()
    app = create_app(db_path=tmp_path / "chat-r3b.sqlite3", token="synthetic-service-token", agent=agent)
    with TestClient(app) as client:
        response = client.post(
            "/v2/agent/chat",
            json=chat_body(),
            headers={"Authorization": "Bearer synthetic-service-token"},
        )

    assert response.status_code == 200
    result = response.json()
    assert set(result) == {"reply", "proposals", "agent_status"}
    assert result["agent_status"] == "mock"
    assert all(proposal["state"] == "proposed" for proposal in result["proposals"])
    assert agent.calls == 1


def test_chat_respects_the_backend_coach_messages_capability(tmp_path) -> None:
    agent = CountingMockAgent()
    app = create_app(
        db_path=tmp_path / "chat-r3b-disabled.sqlite3",
        token="synthetic-service-token",
        agent=agent,
        capabilities={"coach_messages": False},
    )
    with TestClient(app) as client:
        response = client.post(
            "/v2/agent/chat",
            json=chat_body(),
            headers={"Authorization": "Bearer synthetic-service-token"},
        )

    assert response.status_code == 403
    assert response.json()["error"]["code"] == "policy_denied"
    assert agent.calls == 0
