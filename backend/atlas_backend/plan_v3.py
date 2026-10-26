"""Strict V6.3 planning contract.

This module is deliberately **not wired into the live V2 agent endpoints yet**.
It defines the wire shape and deterministic validation for the future
``plan.revise_day`` proposal (L3). Importing it cannot change an existing
proposal or grant any authority. Android remains the final authority: it
recomputes the diff, the effect set and the confirmation scope locally.

Design rules:
- unknown fields are rejected (``extra="forbid"``);
- the plan id and base version are mandatory (stale revisions cannot apply);
- existing activity ids are references, never created by the model;
- new activities use a proposal-local ``client_ref``; Android assigns stable
  UUIDs only after confirmation;
- overlapping activities are allowed and surfaced as a warning, not silently
  removed;
- an empty activity list is a valid "rest day" proposal.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import date, datetime, timezone
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

UUID_PATTERN = r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
DATE_PATTERN = r"^\d{4}-\d{2}-\d{2}$"
ActivityKind = Literal[
    "FOCUS",
    "TRAINING",
    "APPOINTMENT",
    "ADMIN",
    "MEAL",
    "REST",
    "BUFFER",
    "OTHER",
]
ActivityPriority = Literal["REQUIRED", "IMPORTANT", "OPTIONAL"]
MAX_ACTIVITIES = 32
MAX_PLAN_MINUTES = 1440


class StrictV3Model(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, str_strip_whitespace=True)


def _plain_text(value: str) -> str:
    if any(ord(character) < 0x20 for character in value):
        raise ValueError("control characters are not allowed")
    return value


class FocusSpecV3(StrictV3Model):
    focus_minutes: StrictInt = Field(ge=1, le=180)
    break_minutes: StrictInt = Field(ge=0, le=60)
    cycles: StrictInt = Field(ge=1, le=8)


class TrainingReferenceV3(StrictV3Model):
    provider: Literal["wger"] = "wger"
    routine_id: StrictInt = Field(ge=1)
    day_id: StrictInt | None = Field(default=None, ge=1)
    slot_id: StrictInt | None = Field(default=None, ge=1)


class PlanActivityV3(StrictV3Model):
    """One activity proposed for a day plan.

    ``activity_id`` references an activity that already exists in the current
    plan. ``client_ref`` is a stable label inside this proposal only; it is
    mandatory and unique even for existing activities so that a diff can be
    rendered deterministically.
    """

    client_ref: StrictStr = Field(min_length=1, max_length=40, pattern=r"^[a-zA-Z0-9_.-]+$")
    activity_id: StrictStr | None = Field(default=None, pattern=UUID_PATTERN)
    kind: ActivityKind
    title: StrictStr = Field(min_length=1, max_length=80)
    local_date: StrictStr = Field(pattern=DATE_PATTERN)
    start_minute: StrictInt = Field(ge=0, le=1439)
    duration_minutes: StrictInt = Field(ge=1, le=1440)
    timezone: StrictStr = Field(min_length=1, max_length=64)
    priority: ActivityPriority = "IMPORTANT"
    goal_id: StrictStr | None = Field(default=None, pattern=UUID_PATTERN)
    sub_goal_id: StrictStr | None = Field(default=None, pattern=UUID_PATTERN)
    focus: FocusSpecV3 | None = None
    training_ref: TrainingReferenceV3 | None = None
    notes: StrictStr | None = Field(default=None, max_length=280)

    @field_validator("title", "notes")
    @classmethod
    def title_is_plain(cls, value: str | None) -> str | None:
        return None if value is None else _plain_text(value)

    @field_validator("local_date")
    @classmethod
    def local_date_is_valid(cls, value: str) -> str:
        try:
            date.fromisoformat(value)
        except ValueError as exc:
            raise ValueError("local_date must be an ISO-8601 date") from exc
        return value

    @field_validator("timezone")
    @classmethod
    def timezone_is_known(cls, value: str) -> str:
        try:
            ZoneInfo(value)
        except ZoneInfoNotFoundError as exc:
            raise ValueError("timezone must be an IANA timezone") from exc
        return value

    @model_validator(mode="after")
    def activity_fits_in_day(self) -> PlanActivityV3:
        if self.start_minute + self.duration_minutes > MAX_PLAN_MINUTES:
            raise ValueError("activity must not cross the local day boundary")
        return self

    @property
    def end_minute(self) -> int:
        return self.start_minute + self.duration_minutes


class PlanRevisionParametersV3(StrictV3Model):
    plan_id: StrictStr = Field(pattern=UUID_PATTERN)
    base_version: StrictInt = Field(ge=1)
    local_date: StrictStr = Field(pattern=DATE_PATTERN)
    timezone: StrictStr = Field(min_length=1, max_length=64)
    summary: StrictStr = Field(min_length=1, max_length=240)
    activities: list[PlanActivityV3] = Field(max_length=MAX_ACTIVITIES)

    @field_validator("local_date")
    @classmethod
    def local_date_is_valid(cls, value: str) -> str:
        try:
            date.fromisoformat(value)
        except ValueError as exc:
            raise ValueError("local_date must be an ISO-8601 date") from exc
        return value

    @field_validator("timezone")
    @classmethod
    def timezone_is_known(cls, value: str) -> str:
        try:
            ZoneInfo(value)
        except ZoneInfoNotFoundError as exc:
            raise ValueError("timezone must be an IANA timezone") from exc
        return value

    @field_validator("summary")
    @classmethod
    def summary_is_plain(cls, value: str) -> str:
        return _plain_text(value)

    @model_validator(mode="after")
    def activities_are_consistent(self) -> PlanRevisionParametersV3:
        refs = [activity.client_ref for activity in self.activities]
        if len(refs) != len(set(refs)):
            raise ValueError("client_ref must be unique")
        ids = [activity.activity_id for activity in self.activities if activity.activity_id]
        if len(ids) != len(set(ids)):
            raise ValueError("activity_id must be unique")
        for activity in self.activities:
            if activity.local_date != self.local_date:
                raise ValueError("every activity must use the plan local_date")
            if activity.timezone != self.timezone:
                raise ValueError("every activity must use the plan timezone")
        if sum(activity.duration_minutes for activity in self.activities) > MAX_PLAN_MINUTES:
            raise ValueError("total scheduled minutes exceed one day")
        return self

    def overlaps(self) -> list[tuple[str, str]]:
        """Return every overlapping pair, including intervals nested in a longer one."""
        ordered = sorted(
            self.activities,
            key=lambda activity: (activity.start_minute, activity.end_minute),
        )
        result: list[tuple[str, str]] = []
        for index, left in enumerate(ordered):
            for right in ordered[index + 1:]:
                if right.start_minute >= left.end_minute:
                    break
                result.append((left.client_ref, right.client_ref))
        return result


class PlanRevisionCandidateV3(StrictV3Model):
    action_type: Literal["plan.revise_day"]
    reason: StrictStr = Field(min_length=1, max_length=240)
    parameters: PlanRevisionParametersV3


class PlanProposalEnvelopeV3(StrictV3Model):
    """Proposal envelope for the future V3 inbox.

    It is not accepted by the live V2 endpoint; a client that does not know
    ``schema_version: 3`` must reject it rather than ignore it.
    """

    schema_version: Literal[3] = 3
    proposal_id: StrictStr = Field(pattern=UUID_PATTERN)
    idempotency_key: StrictStr = Field(min_length=1, max_length=80)
    conversation_id: StrictStr | None = Field(default=None, max_length=80)
    mandate_id: StrictStr = Field(min_length=1, max_length=80)
    mandate_version: StrictInt = Field(ge=1)
    created_at: datetime
    expires_at: datetime
    required_level: Literal["proposal_only", "confirm", "preauthorized_ok"] = "confirm"
    state: Literal["proposed"] = "proposed"
    proposal: PlanRevisionCandidateV3

    @field_validator("created_at", "expires_at")
    @classmethod
    def timestamps_are_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("timestamps must include a timezone")
        return value.astimezone(timezone.utc)

    @model_validator(mode="after")
    def expiry_follows_creation(self) -> PlanProposalEnvelopeV3:
        if self.expires_at <= self.created_at:
            raise ValueError("expires_at must follow created_at")
        return self


@dataclass(frozen=True)
class PlanRevisionDecision:
    accepted: bool
    reason: str


def validate_plan_revision(
    proposal: PlanProposalEnvelopeV3,
    *,
    plan_id: str,
    current_version: int,
    known_activity_ids: set[str],
    now: datetime | None = None,
) -> PlanRevisionDecision:
    """Deterministic server pre-check. Android still revalidates everything."""
    current_time = (now or datetime.now(timezone.utc)).astimezone(timezone.utc)
    if proposal.expires_at <= current_time:
        return PlanRevisionDecision(False, "proposal expirée")
    parameters = proposal.proposal.parameters
    if parameters.plan_id.lower() != plan_id.lower():
        return PlanRevisionDecision(False, "plan_id différent du plan courant")
    if parameters.base_version != current_version:
        return PlanRevisionDecision(False, "révision basée sur une version périmée")
    known = {value.lower() for value in known_activity_ids}
    for activity in parameters.activities:
        if activity.activity_id is not None and activity.activity_id.lower() not in known:
            return PlanRevisionDecision(False, "activity_id inconnu du plan courant")
    return PlanRevisionDecision(True, "révision cohérente avec la version fournie")
