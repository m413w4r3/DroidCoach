from __future__ import annotations

import sys
import time
from pathlib import Path

import httpx
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from atlas_backend.models_v2 import MandateSummary, SharedContext
from atlas_backend.openclaw_adapter import (
    ChatOrchestrationFailure,
    OpenClawAgentAdapter,
)


def successful_response(request: httpx.Request) -> httpx.Response:
    return httpx.Response(
        200,
        json={
            "choices": [{
                "message": {
                    "content": (
                        '{"reply":"Une étape courte.","proposals":[{"action_type":"focus.propose",'
                        '"parameters":{"focus_minutes":10,"break_minutes":5,"cycles":1},'
                        '"reason":"Commencer simplement."}]}'
                    )
                }
            }],
            "usage": {"prompt_tokens": 30, "completion_tokens": 20},
        },
        request=request,
    )


def make_adapter(
    monkeypatch,
    handler,
    *,
    enforced: str | None,
    daily_tokens: int = 100_000,
    rpm: int = 30,
    timeout_s: float = 0.01,
) -> OpenClawAgentAdapter:
    monkeypatch.delenv("ATLAS_AGENT_DAILY_TOKENS", raising=False)
    if enforced is None:
        monkeypatch.delenv("ATLAS_AGENT_DAILY_TOKENS_ENFORCED", raising=False)
    else:
        monkeypatch.setenv("ATLAS_AGENT_DAILY_TOKENS_ENFORCED", enforced)
    return OpenClawAgentAdapter(
        base_url="http://provider.invalid",
        token="synthetic-provider-token",
        timeout_s=timeout_s,
        rpm=rpm,
        daily_tokens=daily_tokens,
        transport=httpx.MockTransport(handler),
    )


def chat(adapter: OpenClawAgentAdapter):
    return adapter.chat_v2(
        "conv_0123456789abcdef0123456789abcdef",
        "Propose une petite étape.",
        SharedContext.model_validate({"consented_categories": [], "categories": {}}),
        MandateSummary.model_validate({
            "id": "mandate-fixture",
            "version": 1,
            "allowed_actions": ["focus.propose"],
        }),
    )


@pytest.mark.parametrize("configured", [None, "true", "TRUE"])
def test_daily_token_cap_defaults_to_true_and_retains_historical_block(
    monkeypatch, configured
):
    calls = []
    adapter = make_adapter(
        monkeypatch,
        lambda request: calls.append(request) or successful_response(request),
        enforced=configured,
        daily_tokens=10,
    )

    assert chat(adapter).proposals == []
    status = adapter.status().quota
    assert calls == []
    assert status["daily_token_limit_enforced"] is True
    assert status["daily_token_limit"] == 10
    assert status["limited"] is True


def test_missing_enforcement_variable_means_true_with_historical_default_limit(monkeypatch):
    adapter = make_adapter(
        monkeypatch,
        lambda request: successful_response(request),
        enforced=None,
    )

    assert adapter.daily_token_limit == 100_000
    assert adapter.status().quota["daily_token_limit_enforced"] is True


@pytest.mark.parametrize("invalid", ["", "1", "yes", "disabled"])
def test_invalid_enforcement_value_fails_at_construction(monkeypatch, invalid):
    monkeypatch.setenv("ATLAS_AGENT_DAILY_TOKENS_ENFORCED", invalid)

    with pytest.raises(ValueError, match="ATLAS_AGENT_DAILY_TOKENS_ENFORCED"):
        OpenClawAgentAdapter(
            base_url="http://provider.invalid",
            token="synthetic-provider-token",
            transport=httpx.MockTransport(successful_response),
        )


def test_false_allows_provider_above_daily_limit_and_keeps_usage_accounting(monkeypatch):
    calls = []

    def provider(request: httpx.Request) -> httpx.Response:
        calls.append(request)
        return successful_response(request)

    adapter = make_adapter(monkeypatch, provider, enforced="false")
    adapter._daily_tokens = 100_000

    assert chat(adapter).proposals
    status = adapter.status().quota
    assert len(calls) == 1
    assert status["daily_token_limit_enforced"] is False
    assert status["daily_tokens"] == 100_050
    assert status["daily_token_limit"] == 100_000
    assert status["limited"] is False


def test_false_keeps_rpm_limit_active(monkeypatch):
    calls = []

    def provider(request: httpx.Request) -> httpx.Response:
        calls.append(request)
        return successful_response(request)

    adapter = make_adapter(monkeypatch, provider, enforced="false", rpm=1)
    adapter._daily_tokens = 100_001

    assert chat(adapter).proposals
    assert chat(adapter).proposals == []
    status = adapter.status().quota
    assert len(calls) == 1
    assert status["calls_last_minute"] == 1
    assert status["calls_per_minute_limit"] == 1
    assert status["daily_tokens"] > status["daily_token_limit"]
    assert status["limited"] is True


def test_false_keeps_bounded_retries_and_circuit_breaker_active(monkeypatch):
    calls = []

    def provider(request: httpx.Request) -> httpx.Response:
        calls.append(request)
        return httpx.Response(503, request=request)

    adapter = make_adapter(monkeypatch, provider, enforced="false", rpm=60)
    adapter._daily_tokens = 100_001

    for _ in range(3):
        assert chat(adapter).proposals == []
    assert len(calls) == 9
    assert adapter.status().breaker == "open"
    assert adapter.chat_v2(
        "conv_0123456789abcdef0123456789abcdef",
        "another request",
        SharedContext.model_validate({"consented_categories": [], "categories": {}}),
        MandateSummary.model_validate({
            "id": "mandate-fixture",
            "version": 1,
            "allowed_actions": ["focus.propose"],
        }),
    ).proposals == []
    assert len(calls) == 9
    assert adapter.status().quota["daily_tokens"] > adapter.daily_token_limit


def test_false_keeps_per_request_timeout_and_bounded_timeout_retries(monkeypatch):
    calls = []

    def timeout(request: httpx.Request) -> httpx.Response:
        calls.append(request)
        raise httpx.ReadTimeout("synthetic timeout", request=request)

    adapter = make_adapter(
        monkeypatch,
        timeout,
        enforced="false",
        timeout_s=0.001,
    )
    adapter._daily_tokens = 100_001

    assert chat(adapter).proposals == []
    assert len(calls) == 3
    assert adapter.timeout_s == 0.001
    assert adapter.status().quota["daily_tokens"] > adapter.daily_token_limit


def test_false_preserves_provider_429_mapping(monkeypatch):
    calls = []

    def rate_limited(request: httpx.Request) -> httpx.Response:
        calls.append(request)
        return httpx.Response(429, request=request)

    adapter = make_adapter(monkeypatch, rate_limited, enforced="false")
    adapter._daily_tokens = 100_001

    with pytest.raises(ChatOrchestrationFailure) as error:
        adapter._chat_v4_completion(
            {"messages": [{"role": "user", "content": "synthetic"}]},
            deadline=time.monotonic() + 5,
            tool_call_count=0,
            completed_calls=0,
            proposal_ids=[],
        )

    assert error.value.code == "RATE_LIMITED"
    assert len(calls) == 1
    assert adapter.status().quota["daily_tokens"] > adapter.daily_token_limit


def test_rollback_to_true_blocks_again_with_accumulated_daily_usage(monkeypatch):
    calls = []

    def provider(request: httpx.Request) -> httpx.Response:
        calls.append(request)
        return successful_response(request)

    adapter = make_adapter(monkeypatch, provider, enforced="false")
    adapter._daily_tokens = 100_000
    assert chat(adapter).proposals
    accumulated_tokens = adapter.status().quota["daily_tokens"]
    assert accumulated_tokens == 100_050

    restored = make_adapter(monkeypatch, provider, enforced="true")
    # Preserve the in-process count to isolate the enforcement policy change.
    restored._daily_tokens = accumulated_tokens

    assert chat(restored).proposals == []
    status = restored.status().quota
    assert len(calls) == 1
    assert status["daily_tokens"] == accumulated_tokens
    assert status["daily_token_limit_enforced"] is True
    assert status["limited"] is True
