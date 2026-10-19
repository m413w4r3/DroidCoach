"""Strict V2 agent contracts shared by the API and policy engine."""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Annotated, Literal
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    StrictInt,
    StrictStr,
    field_validator,
    model_validator,
)

ActionType = Literal[
    "home.set_directive",
    "home.reorder_cards",
    "focus.propose",
    "focus.plan",
    "focus.start",
    "focus.pause",
    "habit.suggest",
    "app_policy.propose",
    "app_policy.adjust",
    "content.recommend",
]
ContextCategory = Literal[
    "goals",
    "projects",
    "availability",
    "preferences",
    "package_names",
    "urls",
    "habit_titles",
]


class StrictV2Model(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, str_strip_whitespace=True)


class DirectiveParameters(StrictV2Model):
    title: StrictStr = Field(min_length=1, max_length=60)
    body: StrictStr = Field(min_length=1, max_length=200)
    primary_action: Literal["focus.start", "open_coach", "none"]
    focus_proposal_id: StrictStr | None = Field(default=None, min_length=1, max_length=80)


class ReorderCardsParameters(StrictV2Model):
    order: list[Literal["directive", "focus", "content"]] = Field(min_length=1, max_length=3)

    @field_validator("order")
    @classmethod
    def unique_cards(cls, value: list[str]) -> list[str]:
        if len(set(value)) != len(value):
            raise ValueError("order must not repeat cards")
        return value


class FocusProposalParameters(StrictV2Model):
    focus_minutes: StrictInt = Field(ge=1, le=180)
    break_minutes: StrictInt = Field(ge=0, le=60)
    cycles: StrictInt = Field(ge=1, le=8)
    label: StrictStr | None = Field(default=None, max_length=60)


class FocusPlanParameters(FocusProposalParameters):
    start_at: datetime
    timezone: StrictStr = Field(min_length=1, max_length=64)

    @field_validator("start_at")
    @classmethod
    def start_at_is_utc(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("start_at must include a timezone")
        return value.astimezone(timezone.utc)

    @field_validator("timezone")
    @classmethod
    def timezone_is_known(cls, value: str) -> str:
        try:
            ZoneInfo(value)
        except ZoneInfoNotFoundError as exc:
            raise ValueError("timezone must be an IANA timezone") from exc
        return value


class FocusSessionParameters(StrictV2Model):
    session_ref: StrictStr = Field(min_length=1, max_length=80)


class HabitSuggestParameters(StrictV2Model):
    name: StrictStr = Field(min_length=1, max_length=60)
    schedule: Literal["daily", "weekdays"]


class AppPolicyWindow(StrictV2Model):
    starts_at: StrictStr = Field(pattern=r"^(?:[01]\d|2[0-3]):[0-5]\d$")
    ends_at: StrictStr = Field(pattern=r"^(?:[01]\d|2[0-3]):[0-5]\d$")
    days: list[Literal["monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"]] = Field(
        default_factory=list, max_length=7
    )

    @field_validator("days")
    @classmethod
    def unique_days(cls, value: list[str]) -> list[str]:
        if len(set(value)) != len(value):
            raise ValueError("days must not repeat")
        return value

    @model_validator(mode="after")
    def ordered_window(self) -> AppPolicyWindow:
        if self.starts_at >= self.ends_at:
            raise ValueError("window must end after it starts on the same day")
        return self


class AppPolicyParameters(StrictV2Model):
    package_ref: StrictStr = Field(min_length=1, max_length=80, pattern=r"^[a-zA-Z0-9_.-]+$")
    daily_limit_minutes: StrictInt = Field(ge=5, le=480)
    window: AppPolicyWindow | None = None
    mode: Literal["soft_reminder", "friction"]
    valid_until: datetime

    @field_validator("valid_until")
    @classmethod
    def valid_until_is_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("valid_until must include a timezone")
        return value.astimezone(timezone.utc)


class ContentRecommendationParameters(StrictV2Model):
    content_id: StrictStr | None = Field(default=None, min_length=1, max_length=64)
    url: StrictStr | None = Field(default=None, min_length=1, max_length=2048)
    title: StrictStr = Field(min_length=1, max_length=120)

    @model_validator(mode="after")
    def exactly_one_content_reference(self) -> ContentRecommendationParameters:
        if (self.content_id is None) == (self.url is None):
            raise ValueError("exactly one of content_id or url is required")
        return self


class CandidateBase(StrictV2Model):
    reason: StrictStr = Field(min_length=1, max_length=240)


class DirectiveCandidate(CandidateBase):
    action_type: Literal["home.set_directive"]
    parameters: DirectiveParameters


class ReorderCandidate(CandidateBase):
    action_type: Literal["home.reorder_cards"]
    parameters: ReorderCardsParameters


class FocusProposeCandidate(CandidateBase):
    action_type: Literal["focus.propose"]
    parameters: FocusProposalParameters


class FocusPlanCandidate(CandidateBase):
    action_type: Literal["focus.plan"]
    parameters: FocusPlanParameters


class FocusStartCandidate(CandidateBase):
    action_type: Literal["focus.start"]
    parameters: FocusSessionParameters


class FocusPauseCandidate(CandidateBase):
    action_type: Literal["focus.pause"]
    parameters: FocusSessionParameters


class HabitCandidate(CandidateBase):
    action_type: Literal["habit.suggest"]
    parameters: HabitSuggestParameters


class AppPolicyProposeCandidate(CandidateBase):
    action_type: Literal["app_policy.propose"]
    parameters: AppPolicyParameters


class AppPolicyAdjustCandidate(CandidateBase):
    action_type: Literal["app_policy.adjust"]
    parameters: AppPolicyParameters


class ContentCandidate(CandidateBase):
    action_type: Literal["content.recommend"]
    parameters: ContentRecommendationParameters


ProposalCandidate = Annotated[
    DirectiveCandidate | ReorderCandidate | FocusProposeCandidate | FocusPlanCandidate | FocusStartCandidate | FocusPauseCandidate | HabitCandidate | AppPolicyProposeCandidate | AppPolicyAdjustCandidate | ContentCandidate,
    Field(discriminator="action_type"),
]


class ProposalFields(StrictV2Model):
    schema_version: Literal[2] = 2
    proposal_id: StrictStr = Field(pattern=r"^[0-9a-fA-F-]{36}$")
    idempotency_key: StrictStr = Field(min_length=1, max_length=80)
    conversation_id: StrictStr | None = Field(default=None, max_length=80)
    mandate_id: StrictStr = Field(min_length=1, max_length=80)
    mandate_version: StrictInt = Field(ge=1)
    context_version: StrictStr = Field(min_length=1, max_length=80)
    created_at: datetime
    expires_at: datetime
    required_level: Literal["proposal_only", "confirm", "preauthorized_ok"]
    state: Literal[
        "proposed",
        "pending_confirmation",
        "accepted",
        "executed",
        "rejected",
        "expired",
        "cancelled",
    ] = "proposed"

    @field_validator("created_at", "expires_at")
    @classmethod
    def proposal_timestamps_are_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("proposal timestamps must include a timezone")
        return value.astimezone(timezone.utc)

    @model_validator(mode="after")
    def expiry_follows_creation(self) -> ProposalFields:
        if self.expires_at <= self.created_at:
            raise ValueError("expires_at must follow created_at")
        return self


class ProposalDirective(ProposalFields):
    action_type: Literal["home.set_directive"]
    parameters: DirectiveParameters
    reason: StrictStr = Field(min_length=1, max_length=240)


class ProposalReorder(ProposalFields):
    action_type: Literal["home.reorder_cards"]
    parameters: ReorderCardsParameters
    reason: StrictStr = Field(min_length=1, max_length=240)


class ProposalFocusPropose(ProposalFields):
    action_type: Literal["focus.propose"]
    parameters: FocusProposalParameters
    reason: StrictStr = Field(min_length=1, max_length=240)


class ProposalFocusPlan(ProposalFields):
    action_type: Literal["focus.plan"]
    parameters: FocusPlanParameters
    reason: StrictStr = Field(min_length=1, max_length=240)


class ProposalFocusStart(ProposalFields):
    action_type: Literal["focus.start"]
    parameters: FocusSessionParameters
    reason: StrictStr = Field(min_length=1, max_length=240)


class ProposalFocusPause(ProposalFields):
    action_type: Literal["focus.pause"]
    parameters: FocusSessionParameters
    reason: StrictStr = Field(min_length=1, max_length=240)


class ProposalHabit(ProposalFields):
    action_type: Literal["habit.suggest"]
    parameters: HabitSuggestParameters
    reason: StrictStr = Field(min_length=1, max_length=240)


class ProposalAppPolicyPropose(ProposalFields):
    action_type: Literal["app_policy.propose"]
    parameters: AppPolicyParameters
    reason: StrictStr = Field(min_length=1, max_length=240)


class ProposalAppPolicyAdjust(ProposalFields):
    action_type: Literal["app_policy.adjust"]
    parameters: AppPolicyParameters
    reason: StrictStr = Field(min_length=1, max_length=240)


class ProposalContent(ProposalFields):
    action_type: Literal["content.recommend"]
    parameters: ContentRecommendationParameters
    reason: StrictStr = Field(min_length=1, max_length=240)


ProposalV2 = Annotated[
    ProposalDirective | ProposalReorder | ProposalFocusPropose | ProposalFocusPlan | ProposalFocusStart | ProposalFocusPause | ProposalHabit | ProposalAppPolicyPropose | ProposalAppPolicyAdjust | ProposalContent,
    Field(discriminator="action_type"),
]


class AgentOutputV2(StrictV2Model):
    reply: StrictStr = Field(max_length=2000)
    proposals: list[ProposalCandidate] = Field(max_length=5)


class AgentOutputEnvelope(StrictV2Model):
    reply: StrictStr = Field(max_length=2000)
    proposals: list[ProposalCandidate] = Field(max_length=5)


class SharedContext(StrictV2Model):
    consented_categories: list[ContextCategory] = Field(default_factory=list, max_length=7)
    categories: dict[ContextCategory, list[StrictStr]] = Field(default_factory=dict, max_length=7)

    @field_validator("consented_categories")
    @classmethod
    def unique_categories(cls, value: list[str]) -> list[str]:
        if len(set(value)) != len(value):
            raise ValueError("consented_categories must not repeat")
        return value

    @field_validator("categories")
    @classmethod
    def bounded_values(cls, value: dict[str, list[str]]) -> dict[str, list[str]]:
        if any(len(items) > 20 or any(len(item) > 300 for item in items) for items in value.values()):
            raise ValueError("shared context values exceed their limits")
        return value

    def minimized(self) -> dict[str, list[str]]:
        consented = set(self.consented_categories)
        all_categories = set(ContextCategory.__args__)
        return {
            category: list(self.categories.get(category, [])) if category in consented else []
            for category in sorted(all_categories)
        }


class FocusWindow(StrictV2Model):
    days: list[Literal["monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"]] = Field(
        min_length=1, max_length=7
    )
    starts_at: StrictStr = Field(pattern=r"^(?:[01]\d|2[0-3]):[0-5]\d$")
    ends_at: StrictStr = Field(pattern=r"^(?:[01]\d|2[0-3]):[0-5]\d$")
    timezone: StrictStr = Field(min_length=1, max_length=64)
    max_minutes_per_session: StrictInt = Field(ge=1, le=180)
    max_sessions_per_day: StrictInt = Field(ge=1, le=8)

    @field_validator("days")
    @classmethod
    def unique_window_days(cls, value: list[str]) -> list[str]:
        if len(set(value)) != len(value):
            raise ValueError("focus window days must not repeat")
        return value

    @field_validator("timezone")
    @classmethod
    def focus_timezone_is_known(cls, value: str) -> str:
        try:
            ZoneInfo(value)
        except ZoneInfoNotFoundError as exc:
            raise ValueError("timezone must be an IANA timezone") from exc
        return value

    @model_validator(mode="after")
    def ordered_focus_window(self) -> FocusWindow:
        if self.starts_at >= self.ends_at:
            raise ValueError("focus window must end after it starts on the same day")
        return self


class MandateSummary(StrictV2Model):
    id: StrictStr = Field(min_length=1, max_length=80)
    version: StrictInt = Field(ge=1)
    allowed_actions: list[ActionType] = Field(default_factory=list, max_length=10)
    mode: Literal["proposals_only", "preauthorized"] = "proposals_only"
    focus_window: FocusWindow | None = None
    app_policy_bounds: dict[StrictStr, dict[StrictStr, StrictStr | StrictInt]] = Field(
        default_factory=dict, max_length=50
    )

    @field_validator("allowed_actions")
    @classmethod
    def unique_allowed_actions(cls, value: list[str]) -> list[str]:
        if len(set(value)) != len(value):
            raise ValueError("allowed_actions must not repeat")
        return value


class AgentChatRequest(StrictV2Model):
    conversation_id: StrictStr = Field(min_length=1, max_length=120)
    message: StrictStr = Field(min_length=1, max_length=2000)
    shared_context: SharedContext = Field(default_factory=SharedContext)
    mandate_summary: MandateSummary


class AckRequest(StrictV2Model):
    state: Literal[
        "pending_confirmation",
        "accepted",
        "executed",
        "rejected",
        "expired",
        "cancelled",
    ]
    effect: StrictStr | None = Field(default=None, max_length=500)
    at: datetime = Field(strict=False)

    @field_validator("at")
    @classmethod
    def ack_time_is_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("at must include a timezone")
        return value.astimezone(timezone.utc)


class MemoryEntry(StrictV2Model):
    id: StrictStr = Field(min_length=1, max_length=80, pattern=r"^[a-zA-Z0-9_-]+$")
    value: StrictStr = Field(min_length=1, max_length=500)


class ProjectMemory(StrictV2Model):
    goals: list[MemoryEntry] = Field(default_factory=list, max_length=20)
    projects: list[MemoryEntry] = Field(default_factory=list, max_length=20)
    availability: list[MemoryEntry] = Field(default_factory=list, max_length=20)
    preferences: list[MemoryEntry] = Field(default_factory=list, max_length=20)

    @model_validator(mode="after")
    def ids_unique_by_category(self) -> ProjectMemory:
        for entries in (self.goals, self.projects, self.availability, self.preferences):
            ids = [entry.id for entry in entries]
            if len(ids) != len(set(ids)):
                raise ValueError("memory entry ids must be unique within a category")
        return self
