"""Static demo content and strict URL validation. No remote content is fetched."""

from __future__ import annotations

import ipaddress
import re
from urllib.parse import urlsplit

from pydantic import BaseModel, ConfigDict, Field, StrictStr, field_validator


_CONTROL_CHARS = re.compile(r"[\x00-\x1f\x7f]")


class ContentItem(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, str_strip_whitespace=True)

    id: StrictStr = Field(min_length=1, max_length=64, pattern=r"^[a-zA-Z0-9_-]+$")
    title: StrictStr = Field(min_length=1, max_length=160)
    url: StrictStr = Field(min_length=1, max_length=2048)

    @field_validator("title")
    @classmethod
    def title_is_plain_text(cls, value: str) -> str:
        if _CONTROL_CHARS.search(value) or "<" in value or ">" in value:
            raise ValueError("title must be plain text")
        return value

    @field_validator("url")
    @classmethod
    def url_is_safe_https(cls, value: str) -> str:
        if _CONTROL_CHARS.search(value) or any(ch.isspace() for ch in value):
            raise ValueError("URL contains whitespace or control characters")
        try:
            parsed = urlsplit(value)
            hostname = parsed.hostname
            port = parsed.port
        except ValueError as exc:
            raise ValueError("invalid URL") from exc
        if parsed.scheme.lower() != "https" or not parsed.netloc or not hostname:
            raise ValueError("URL must use HTTPS and include a host")
        if parsed.username is not None or parsed.password is not None:
            raise ValueError("URL credentials are not allowed")
        if port not in (None, 443):
            raise ValueError("non-standard URL ports are not allowed")
        if parsed.fragment:
            raise ValueError("URL fragments are not allowed")
        try:
            address = ipaddress.ip_address(hostname)
        except ValueError:
            address = None
        if address is not None and not address.is_global:
            raise ValueError("non-public IP addresses are not allowed")
        return value


DEMO_CONTENT: tuple[ContentItem, ...] = (
    ContentItem(
        id="focus-basics",
        title="Recherche démo : bases de la concentration",
        url="https://www.youtube.com/results?search_query=bases+concentration",
    ),
    ContentItem(
        id="learning-break",
        title="Recherche démo : pause et apprentissage",
        url="https://www.youtube.com/results?search_query=pause+apprentissage",
    ),
    ContentItem(
        id="study-methods",
        title="Recherche démo : méthodes de travail",
        url="https://www.youtube.com/results?search_query=methodes+travail",
    ),
)
