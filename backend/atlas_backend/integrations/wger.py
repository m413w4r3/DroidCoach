"""Read-only wger API client and explicit, fail-closed JWT token rotation."""

from __future__ import annotations

import contextlib
import fcntl
import json
import os
import tempfile
import unicodedata
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from email.utils import parsedate_to_datetime
from pathlib import Path
from typing import Any, Literal, Protocol
from urllib.parse import parse_qs, urljoin, urlsplit

import httpx

WGER_API_PREFIX = "/api/v2/"
DEFAULT_TOKEN_STORE = Path(__file__).resolve().parents[2] / "data" / "wger_tokens.json"
DEFAULT_JWT_REFRESH_URL = "https://wger.de/api/v2/token/refresh/"
MAX_RETRY_AFTER_SECONDS = 60
# HTTPX buffers response bodies; this bounds JSON parsing input, not total memory use.
MAX_RESPONSE_BYTES = 2_000_000
_SAFE_ERROR_MESSAGES = {
    "AUTH_INVALID": "wger authentication was rejected",
    "FORBIDDEN": "wger access is forbidden",
    "RATE_LIMITED": "wger request was rate limited",
    "SERVER_ERROR": "wger server error",
    "TIMEOUT": "wger request timed out",
    "SCHEMA_ERROR": "wger response schema is invalid",
    "UNAVAILABLE": "wger service is unavailable",
    "CONFIG_REQUIRED": "wger configuration is required",
    "ENV_PERMISSIONS": "environment file permissions are too open",
    "RECONCILIATION_REQUIRED": "wger token state requires reconciliation",
}


class WgerError(Exception):
    """Stable, redacted integration error; response bodies are never retained."""

    def __init__(
        self,
        code: str,
        *,
        status_code: int | None = None,
        retry_after: int | None = None,
    ) -> None:
        self.code = code if code in _SAFE_ERROR_MESSAGES else "UNAVAILABLE"
        self.status_code = status_code
        self.retry_after = retry_after
        super().__init__(_SAFE_ERROR_MESSAGES[self.code])

    def __repr__(self) -> str:
        return (
            f"WgerError(code={self.code!r}, status_code={self.status_code!r}, "
            f"retry_after={self.retry_after!r})"
        )


class WgerAuth(Protocol):
    def headers(self) -> dict[str, str]: ...


class WgerTokenStoreError(WgerError):
    def __init__(self, code: str = "UNAVAILABLE") -> None:
        super().__init__(code)


@dataclass(frozen=True, repr=False)
class WgerTokenRecord:
    generation: int
    access_token: str = field(repr=False)
    refresh_token: str = field(repr=False)
    obtained_at: datetime
    expires_at: datetime
    state: Literal["ACTIVE", "RECONCILIATION_REQUIRED"] = "ACTIVE"

    def __repr__(self) -> str:
        return (
            "WgerTokenRecord("
            f"generation={self.generation!r}, obtained_at={self.obtained_at!r}, "
            f"expires_at={self.expires_at!r}, state={self.state!r})"
        )


class WgerTokenStore:
    """0600 token storage with atomic replacement and a process lock."""

    def __init__(self, path: str | os.PathLike[str] = DEFAULT_TOKEN_STORE) -> None:
        self.path = Path(path)

    def __repr__(self) -> str:
        return "WgerTokenStore()"

    @contextlib.contextmanager
    def locked(self, *, blocking: bool = True):
        lock_path = self.path.with_name(self.path.name + ".lock")
        fd: int | None = None
        try:
            lock_path.parent.mkdir(parents=True, exist_ok=True)
            fd = os.open(lock_path, os.O_CREAT | os.O_RDWR, 0o600)
            if hasattr(os, "fchmod"):
                os.fchmod(fd, 0o600)
            operation = fcntl.LOCK_EX | (0 if blocking else fcntl.LOCK_NB)
            fcntl.flock(fd, operation)
        except BlockingIOError:
            if fd is not None:
                os.close(fd)
            raise WgerTokenStoreError() from None
        except OSError:
            if fd is not None:
                os.close(fd)
            raise WgerTokenStoreError() from None
        try:
            yield
        finally:
            try:
                fcntl.flock(fd, fcntl.LOCK_UN)
            finally:
                os.close(fd)

    def load(self) -> WgerTokenRecord | None:
        with self.locked():
            return self._load_unlocked()

    def save(self, record: WgerTokenRecord) -> None:
        self._validate_record(record)
        with self.locked():
            self._save_unlocked(record)

    def _load_unlocked(self) -> WgerTokenRecord | None:
        try:
            raw = self.path.read_text(encoding="utf-8")
        except FileNotFoundError:
            return None
        except OSError:
            raise WgerTokenStoreError() from None
        try:
            value = json.loads(raw)
            record = WgerTokenRecord(
                generation=value["generation"],
                access_token=value["access_token"],
                refresh_token=value["refresh_token"],
                obtained_at=_parse_utc(value["obtained_at"]),
                expires_at=_parse_utc(value["expires_at"]),
                state=value["state"],
            )
            self._validate_record(record)
            return record
        except (KeyError, TypeError, ValueError, OverflowError, json.JSONDecodeError):
            raise WgerTokenStoreError() from None

    def _save_unlocked(self, record: WgerTokenRecord) -> None:
        self._validate_record(record)
        payload = json.dumps(
            {
                "generation": record.generation,
                "access_token": record.access_token,
                "refresh_token": record.refresh_token,
                "obtained_at": record.obtained_at.isoformat(),
                "expires_at": record.expires_at.isoformat(),
                "state": record.state,
            },
            separators=(",", ":"),
        )
        temporary_path: str | None = None
        try:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            fd, temporary_path = tempfile.mkstemp(
                prefix=f".{self.path.name}.", suffix=".tmp", dir=self.path.parent
            )
            if hasattr(os, "fchmod"):
                os.fchmod(fd, 0o600)
            with os.fdopen(fd, "w", encoding="utf-8") as temporary:
                temporary.write(payload)
                temporary.flush()
                os.fsync(temporary.fileno())
            os.replace(temporary_path, self.path)
            temporary_path = None
            if hasattr(os, "chmod"):
                os.chmod(self.path, 0o600)
            directory_fd = os.open(self.path.parent, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
            try:
                os.fsync(directory_fd)
            finally:
                os.close(directory_fd)
        except OSError:
            raise WgerTokenStoreError() from None
        finally:
            if temporary_path is not None:
                with contextlib.suppress(OSError):
                    os.unlink(temporary_path)

    @staticmethod
    def _validate_record(record: WgerTokenRecord) -> None:
        if (
            not isinstance(record, WgerTokenRecord)
            or type(record.generation) is not int
            or record.generation < 1
            or not isinstance(record.access_token, str)
            or not _valid_token_text(record.access_token)
            or not isinstance(record.refresh_token, str)
            or not _valid_token_text(record.refresh_token)
            or record.state not in {"ACTIVE", "RECONCILIATION_REQUIRED"}
            or not _is_aware(record.obtained_at)
            or not _is_aware(record.expires_at)
            or record.expires_at <= record.obtained_at
        ):
            raise WgerTokenStoreError("SCHEMA_ERROR")


class ApiTokenWgerAuth:
    def __init__(self, token: str) -> None:
        if not isinstance(token, str) or not _valid_token_text(token):
            raise WgerError("CONFIG_REQUIRED")
        self._token = token

    def headers(self) -> dict[str, str]:
        return {"Authorization": f"Token {self._token}"}

    def __repr__(self) -> str:
        return "ApiTokenWgerAuth(token=[redacted])"


class RotatingJwtWgerAuth:
    """JWT auth whose refresh token is consumed only by an explicit refresh() call."""

    def __init__(
        self,
        store: WgerTokenStore,
        http: httpx.Client,
        now=None,
        *,
        token_url: str = DEFAULT_JWT_REFRESH_URL,
        timeout_seconds: float = 5.0,
    ) -> None:
        self.store = store
        self.http = http
        self.now = now or (lambda: datetime.now(timezone.utc))
        self.token_url = _validate_https_url(token_url)
        self.timeout_seconds = _bounded_number(timeout_seconds, minimum=0.1, maximum=30.0)
        self._blocked = False

    def headers(self) -> dict[str, str]:
        if self._blocked:
            raise WgerError("RECONCILIATION_REQUIRED")
        record = self.store.load()
        if record is None or record.state != "ACTIVE":
            raise WgerError("RECONCILIATION_REQUIRED")
        current_time = _aware_now(self.now)
        if record.expires_at <= current_time:
            raise WgerError("AUTH_INVALID", status_code=401)
        return {"Authorization": f"Bearer {record.access_token}"}

    def refresh(self) -> dict[str, str]:
        """Explicitly rotate a JWT pair; uncertain outcomes remain blocked."""
        if self._blocked:
            raise WgerError("RECONCILIATION_REQUIRED")
        with self.store.locked(blocking=False):
            record = self.store._load_unlocked()
            if record is None or record.state != "ACTIVE":
                self._blocked = True
                raise WgerError("RECONCILIATION_REQUIRED")
            current_time = _aware_now(self.now)
            reconciliation = WgerTokenRecord(
                generation=record.generation,
                access_token=record.access_token,
                refresh_token=record.refresh_token,
                obtained_at=record.obtained_at,
                expires_at=record.expires_at,
                state="RECONCILIATION_REQUIRED",
            )
            self.store._save_unlocked(reconciliation)
            self._blocked = True
            try:
                response = self.http.post(
                    self.token_url,
                    json={"refresh": record.refresh_token},
                    timeout=self.timeout_seconds,
                )
            except httpx.TimeoutException:
                raise WgerError("TIMEOUT") from None
            except httpx.RequestError:
                raise WgerError("UNAVAILABLE") from None
            if not 200 <= response.status_code < 300:
                raise _http_error(response)
            try:
                payload = response.json()
                access = payload["access"]
                refresh = payload["refresh"]
                expires_in = payload.get("expires_in", 300)
                if (
                    not isinstance(access, str)
                    or not _valid_token_text(access)
                    or not isinstance(refresh, str)
                    or not _valid_token_text(refresh)
                    or type(expires_in) is not int
                    or not 1 <= expires_in <= 86_400
                ):
                    raise ValueError
                updated = WgerTokenRecord(
                    generation=record.generation + 1,
                    access_token=access,
                    refresh_token=refresh,
                    obtained_at=current_time,
                    expires_at=current_time + timedelta(seconds=expires_in),
                    state="ACTIVE",
                )
                self.store._save_unlocked(updated)
            except WgerError:
                self._persist_reconciliation_unlocked(reconciliation)
                raise WgerError("UNAVAILABLE") from None
            except (ValueError, TypeError, KeyError):
                self._persist_reconciliation_unlocked(reconciliation)
                raise WgerError("SCHEMA_ERROR") from None
            self._blocked = False
        return self.headers()

    def _persist_reconciliation_unlocked(self, record: WgerTokenRecord) -> None:
        self._blocked = True
        with contextlib.suppress(WgerError):
            self.store._save_unlocked(record)

    def __repr__(self) -> str:
        return "RotatingJwtWgerAuth(store=[configured], http=[configured])"


@dataclass(frozen=True)
class WgerLimits:
    page_size: int = 50
    max_pages: int = 5
    max_items: int = 100
    timeout_seconds: float = 5.0

    def __post_init__(self) -> None:
        if type(self.page_size) is not int or not 1 <= self.page_size <= 100:
            raise ValueError("invalid wger limits")
        if type(self.max_pages) is not int or not 1 <= self.max_pages <= 10:
            raise ValueError("invalid wger limits")
        if type(self.max_items) is not int or not 1 <= self.max_items <= 500:
            raise ValueError("invalid wger limits")
        _bounded_number(self.timeout_seconds, minimum=0.1, maximum=30.0)


@dataclass(frozen=True)
class RoutineSummary:
    id: int
    name: str


@dataclass(frozen=True)
class ExerciseSummary:
    id: int
    name: str


@dataclass(frozen=True)
class WorkoutLogSummary:
    id: str
    date: str
    exercise_id: int | None
    routine_id: int | None
    session_id: str | None


@dataclass(frozen=True)
class WorkoutSessionSummary:
    id: str
    datetime_start: str
    datetime_end: str
    day_id: int | None
    routine_id: int | None


@dataclass(frozen=True)
class WgerResourceProbe:
    status_http: int | None
    error_category: str | None
    element_count: int
    pagination_present: bool


@dataclass(frozen=True)
class WgerProbe:
    routines: WgerResourceProbe
    exercises: WgerResourceProbe
    workout_logs: WgerResourceProbe
    workout_sessions: WgerResourceProbe


@dataclass(frozen=True)
class _Page:
    status_code: int
    count: int
    results: list[dict[str, Any]]
    next_url: str | None
    previous_url: str | None


class WgerClient:
    """Bounded read-only client. Every wger resource operation uses GET only."""

    def __init__(
        self,
        base_url: str,
        auth: WgerAuth,
        http: httpx.Client,
        limits: WgerLimits | None = None,
    ) -> None:
        self.base_url = _validate_https_url(base_url).rstrip("/")
        self.auth = auth
        self.http = http
        self.limits = limits or WgerLimits()

    def list_routines(self) -> list[RoutineSummary]:
        return self._list("routine", _routine_summary)

    def list_exercises(self) -> list[ExerciseSummary]:
        return self._list("exercise", _exercise_summary)

    def list_workout_logs(self) -> list[WorkoutLogSummary]:
        return self._list("workoutlog", _workout_log_summary)

    def list_workout_sessions(self) -> list[WorkoutSessionSummary]:
        return self._list("workoutsession", _workout_session_summary)

    def probe(self) -> WgerProbe:
        return WgerProbe(
            routines=self._probe_resource("routine"),
            exercises=self._probe_resource("exercise"),
            workout_logs=self._probe_resource("workoutlog"),
            workout_sessions=self._probe_resource("workoutsession"),
        )

    def _list(self, endpoint: str, parse_item):
        url = self._endpoint_url(endpoint)
        params: dict[str, int] | None = {"limit": self.limits.page_size, "offset": 0}
        seen: set[str] = set()
        items: list[Any] = []
        pages = 0
        while url and pages < self.limits.max_pages and len(items) < self.limits.max_items:
            page = self._read_page(endpoint, url, params=params)
            pages += 1
            for value in page.results:
                try:
                    items.append(parse_item(value))
                except (KeyError, TypeError, ValueError):
                    raise WgerError("SCHEMA_ERROR") from None
                if len(items) >= self.limits.max_items:
                    break
            next_url = page.next_url
            if next_url is None:
                if page.count > len(items) and pages < self.limits.max_pages and len(items) < self.limits.max_items:
                    raise WgerError("SCHEMA_ERROR")
                break
            if next_url in seen or next_url == url:
                raise WgerError("SCHEMA_ERROR")
            seen.add(next_url)
            url, params = next_url, None
        return items

    def _probe_resource(self, endpoint: str) -> WgerResourceProbe:
        try:
            page = self._read_page(
                endpoint,
                self._endpoint_url(endpoint),
                params={"limit": 1, "offset": 0},
            )
            return WgerResourceProbe(
                status_http=page.status_code,
                error_category=None,
                element_count=len(page.results),
                pagination_present=page.next_url is not None,
            )
        except WgerError as error:
            return WgerResourceProbe(
                status_http=error.status_code,
                error_category=error.code,
                element_count=0,
                pagination_present=False,
            )

    def _endpoint_url(self, endpoint: str) -> str:
        if endpoint not in {"routine", "exercise", "workoutlog", "workoutsession"}:
            raise WgerError("UNAVAILABLE")
        return f"{self.base_url}{WGER_API_PREFIX}{endpoint}/"

    def _read_page(self, endpoint: str, url: str, *, params=None) -> _Page:
        try:
            response = self.http.get(
                url,
                headers=self.auth.headers(),
                params=params,
                timeout=self.limits.timeout_seconds,
            )
        except httpx.TimeoutException:
            raise WgerError("TIMEOUT") from None
        except httpx.RequestError:
            raise WgerError("UNAVAILABLE") from None
        if not 200 <= response.status_code < 300:
            raise _http_error(response)
        # HTTPX has already buffered the body; this caps parsing input, not peak memory.
        if len(response.content) > MAX_RESPONSE_BYTES:
            raise WgerError("SCHEMA_ERROR")
        content_length = response.headers.get("Content-Length")
        if content_length is not None:
            try:
                declared_length = int(content_length)
            except (TypeError, ValueError):
                raise WgerError("SCHEMA_ERROR") from None
            if declared_length < 0 or declared_length > MAX_RESPONSE_BYTES:
                raise WgerError("SCHEMA_ERROR")
        try:
            payload = response.json()
            count = payload["count"]
            results = payload["results"]
            next_value = payload["next"]
            previous_value = payload["previous"]
            if (
                not isinstance(payload, dict)
                or type(count) is not int
                or count < 0
                or not isinstance(results, list)
                or any(not isinstance(item, dict) for item in results)
                or len(results) > self.limits.page_size
                or (next_value is not None and not isinstance(next_value, str))
                or (previous_value is not None and not isinstance(previous_value, str))
            ):
                raise ValueError
            next_url = self._validate_page_link(endpoint, next_value) if next_value is not None else None
            previous_url = self._validate_page_link(endpoint, previous_value) if previous_value is not None else None
            if count < len(results):
                raise ValueError
            if params is not None:
                current_offset = params.get("offset", 0)
            else:
                current_query = parse_qs(urlsplit(url).query, strict_parsing=True)
                current_offset = int(current_query["offset"][0])
            expected_next_offset = current_offset + len(results)
            if next_url is not None:
                next_query = parse_qs(urlsplit(next_url).query, strict_parsing=True)
                if (
                    not results
                    or int(next_query["offset"][0]) != expected_next_offset
                    or expected_next_offset >= count
                ):
                    raise ValueError
            elif expected_next_offset != count:
                raise ValueError
            return _Page(response.status_code, count, results, next_url, previous_url)
        except (ValueError, TypeError, KeyError):
            raise WgerError("SCHEMA_ERROR") from None

    def _validate_page_link(self, endpoint: str, value: str) -> str:
        origin = urlsplit(self.base_url)
        expected_path = f"{origin.path.rstrip('/')}{WGER_API_PREFIX}{endpoint}/"
        absolute = urljoin(self._endpoint_url(endpoint), value)
        parsed = urlsplit(absolute)
        if (
            parsed.scheme != origin.scheme
            or parsed.hostname != origin.hostname
            or (parsed.port or 443) != (origin.port or 443)
            or parsed.username is not None
            or parsed.password is not None
            or parsed.fragment
            or parsed.path != expected_path
        ):
            raise WgerError("SCHEMA_ERROR")
        query = parse_qs(parsed.query, strict_parsing=True)
        try:
            limit = int(query["limit"][0])
            offset = int(query["offset"][0])
        except (KeyError, ValueError, IndexError):
            raise WgerError("SCHEMA_ERROR") from None
        if (
            len(query.get("limit", [])) != 1
            or len(query.get("offset", [])) != 1
            or not 1 <= limit <= self.limits.page_size
            or offset < 0
        ):
            raise WgerError("SCHEMA_ERROR")
        return absolute


def _routine_summary(value: dict[str, Any]) -> RoutineSummary:
    return RoutineSummary(id=_positive_id(value["id"]), name=_plain_text(value["name"], 200))


def _exercise_summary(value: dict[str, Any]) -> ExerciseSummary:
    return ExerciseSummary(id=_positive_id(value["id"]), name=_plain_text(value["name"], 200))


def _workout_log_summary(value: dict[str, Any]) -> WorkoutLogSummary:
    return WorkoutLogSummary(
        id=_opaque_id(value["id"]),
        date=_plain_text(value["date"], 64),
        exercise_id=_optional_relation_id(value.get("exercise")),
        routine_id=_optional_relation_id(value.get("routine")),
        session_id=_optional_opaque_id(value.get("session")),
    )


def _workout_session_summary(value: dict[str, Any]) -> WorkoutSessionSummary:
    return WorkoutSessionSummary(
        id=_opaque_id(value["id"]),
        datetime_start=_iso_datetime(value["datetime_start"]),
        datetime_end=_iso_datetime(value["datetime_end"]),
        day_id=_optional_relation_id(value.get("day")),
        routine_id=_optional_relation_id(value.get("routine")),
    )


def _positive_id(value: Any) -> int:
    if type(value) is not int or value < 1:
        raise ValueError
    return value


def _optional_relation_id(value: Any) -> int | None:
    if isinstance(value, dict):
        value = value.get("id")
    return None if value is None else _positive_id(value)


def _optional_opaque_id(value: Any) -> str | None:
    return None if value is None else _opaque_id(value)


def _opaque_id(value: Any) -> str:
    if type(value) is int:
        if value < 1:
            raise ValueError
        value = str(value)
    if (
        not isinstance(value, str)
        or not 1 <= len(value) <= 64
        or any(unicodedata.category(char) == "Cc" for char in value)
    ):
        raise ValueError
    return value


def _iso_datetime(value: Any) -> str:
    text = _plain_text(value, 64)
    if "T" not in text and "t" not in text:
        raise ValueError
    normalized = text.replace("t", "T", 1)
    if normalized.endswith("Z"):
        normalized = f"{normalized[:-1]}+00:00"
    datetime.fromisoformat(normalized)
    return text


def _valid_token_text(value: str) -> bool:
    return bool(value) and not any(
        char.isspace() or ord(char) < 32 or ord(char) == 127 for char in value
    )


def _plain_text(value: Any, max_length: int) -> str:
    if (
        not isinstance(value, str)
        or len(value) > max_length
        or any(ord(char) < 32 and char not in "\t" for char in value)
    ):
        raise ValueError
    return value


def _http_error(response: httpx.Response) -> WgerError:
    status = response.status_code
    if status == 401:
        return WgerError("AUTH_INVALID", status_code=status)
    if status == 403:
        return WgerError("FORBIDDEN", status_code=status)
    if status == 429:
        return WgerError(
            "RATE_LIMITED",
            status_code=status,
            retry_after=_parse_retry_after(response.headers.get("Retry-After")),
        )
    if 500 <= status <= 599:
        return WgerError("SERVER_ERROR", status_code=status)
    if status == 408:
        return WgerError("TIMEOUT", status_code=status)
    return WgerError("UNAVAILABLE", status_code=status)


def _parse_retry_after(value: str | None) -> int | None:
    if value is None:
        return None
    try:
        seconds = float(value.strip())
    except ValueError:
        try:
            retry_time = parsedate_to_datetime(value)
            if retry_time.tzinfo is None:
                retry_time = retry_time.replace(tzinfo=timezone.utc)
            seconds = (retry_time - datetime.now(timezone.utc)).total_seconds()
        except (TypeError, ValueError, OverflowError):
            return None
    if seconds != seconds:
        return None
    if seconds <= 0:
        return 0
    if seconds >= MAX_RETRY_AFTER_SECONDS:
        return MAX_RETRY_AFTER_SECONDS
    return int(seconds)


def _validate_https_url(value: str) -> str:
    try:
        parsed = urlsplit(value)
        hostname = parsed.hostname
        port = parsed.port
    except (TypeError, ValueError):
        raise WgerError("CONFIG_REQUIRED") from None
    if (
        parsed.scheme != "https"
        or not parsed.netloc
        or not hostname
        or parsed.username is not None
        or parsed.password is not None
        or parsed.query
        or parsed.fragment
        or port not in (None, 443)
    ):
        raise WgerError("CONFIG_REQUIRED")
    return value.rstrip("/")


def _bounded_number(value: float, *, minimum: float, maximum: float) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not minimum <= value <= maximum:
        raise ValueError("invalid wger limits")
    return float(value)


def _is_aware(value: datetime) -> bool:
    return isinstance(value, datetime) and value.tzinfo is not None and value.utcoffset() is not None


def _parse_utc(value: str) -> datetime:
    if not isinstance(value, str):
        raise ValueError
    parsed = datetime.fromisoformat(value)
    if not _is_aware(parsed):
        raise ValueError
    return parsed.astimezone(timezone.utc)


def _aware_now(now) -> datetime:
    value = now()
    if not _is_aware(value):
        raise WgerError("CONFIG_REQUIRED")
    return value.astimezone(timezone.utc)
