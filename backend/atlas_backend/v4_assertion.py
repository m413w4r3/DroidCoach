"""Signed Relay assertion validation at the Core V4 HTTP boundary."""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import stat
import threading
import time
from collections import OrderedDict
from pathlib import Path
from typing import Mapping


HEADER_NAMES = {
    "device_id": "x-atlas-relay-device-id",
    "key_id": "x-atlas-relay-key-id",
    "timestamp": "x-atlas-relay-time",
    "nonce": "x-atlas-relay-nonce",
    "signature": "x-atlas-relay-signature",
}


class RelayAssertionError(ValueError):
    pass


def parse_assertion_keys(raw: str | None) -> dict[str, bytes]:
    if not raw:
        return {}
    try:
        value = json.loads(raw)
    except (TypeError, json.JSONDecodeError):
        raise ValueError("ATLAS_V4_RELAY_ASSERTION_KEYS_JSON invalide") from None
    if not isinstance(value, dict) or not value or len(value) > 2:
        raise ValueError("ATLAS_V4_RELAY_ASSERTION_KEYS_JSON doit contenir une ou deux clés")
    result: dict[str, bytes] = {}
    for key_id, encoded in value.items():
        if not isinstance(key_id, str) or not key_id or len(key_id) > 64 or not isinstance(encoded, str):
            raise ValueError("identifiant ou secret de clé d’assertion invalide")
        try:
            secret = base64.urlsafe_b64decode(encoded + "=" * (-len(encoded) % 4))
        except (ValueError, TypeError):
            raise ValueError("secret de clé d’assertion invalide") from None
        if len(secret) < 32:
            raise ValueError("chaque clé d’assertion doit contenir au moins 32 octets")
        result[key_id] = secret
    return result


def load_assertion_keys(*, raw_json: str | None, file_path: str | None, testing: bool = False) -> dict[str, bytes]:
    if raw_json is not None and file_path is not None:
        raise ValueError("configure one Relay assertion key source")
    if file_path is not None:
        path = Path(file_path)
        if not path.is_absolute():
            raise ValueError("ATLAS_V4_RELAY_ASSERTION_KEYS_FILE must be absolute")
        try:
            before = path.lstat()
        except OSError:
            raise ValueError("Relay assertion key file unavailable") from None
        if stat.S_ISLNK(before.st_mode) or not stat.S_ISREG(before.st_mode):
            raise ValueError("Relay assertion key file must be a regular non-symlink file")
        if before.st_uid != os.getuid() or stat.S_IMODE(before.st_mode) != 0o600:
            raise ValueError("Relay assertion key file must be owner-only mode 0600")
        if before.st_size > 16_384:
            raise ValueError("Relay assertion key file is too large")
        flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
        try:
            descriptor = os.open(path, flags)
            try:
                opened = os.fstat(descriptor)
                if (not stat.S_ISREG(opened.st_mode) or opened.st_dev != before.st_dev
                        or opened.st_ino != before.st_ino or opened.st_uid != os.getuid()
                        or stat.S_IMODE(opened.st_mode) != 0o600 or opened.st_size > 16_384):
                    raise ValueError("Relay assertion key file changed or is unsafe")
                chunks = bytearray()
                while len(chunks) <= 16_384:
                    part = os.read(descriptor, min(4096, 16_385 - len(chunks)))
                    if not part:
                        break
                    chunks.extend(part)
                after = path.lstat()
                final = os.fstat(descriptor)
                if (len(chunks) > 16_384 or after.st_dev != before.st_dev or after.st_ino != before.st_ino
                        or final.st_dev != before.st_dev or final.st_ino != before.st_ino):
                    raise ValueError("Relay assertion key file changed or is too large")
            finally:
                os.close(descriptor)
        except OSError:
            raise ValueError("Relay assertion key file unavailable") from None
        try:
            raw_json = bytes(chunks).decode("utf-8", errors="strict")
        except UnicodeDecodeError:
            raise ValueError("Relay assertion key file must contain UTF-8 JSON") from None
        return parse_assertion_keys(raw_json)
    if raw_json is not None and not testing:
        raise ValueError("Relay assertion keys from environment are allowed only in tests")
    return parse_assertion_keys(raw_json)


def canonical_message(method: str, path: str, timestamp: str, nonce: str, device_id: str, body: bytes) -> bytes:
    body_hash = hashlib.sha256(body).hexdigest()
    return f"v1\n{method.upper()}\n{path}\n{timestamp}\n{nonce}\n{device_id}\n{body_hash}".encode("utf-8")


class ReplayNonceCache:
    """Bounded process-memory replay cache; a full live cache fails closed."""

    def __init__(self, *, capacity: int = 8192, ttl_seconds: int = 300) -> None:
        self.capacity = capacity
        self.ttl_seconds = ttl_seconds
        self._items: OrderedDict[tuple[str, str], float] = OrderedDict()
        self._lock = threading.Lock()

    def reserve(self, key_id: str, nonce: str, *, now: float | None = None) -> bool:
        now = time.time() if now is None else now
        expires_before = now - self.ttl_seconds
        with self._lock:
            while self._items:
                _key, added_at = next(iter(self._items.items()))
                if added_at > expires_before:
                    break
                self._items.popitem(last=False)
            key = (key_id, nonce)
            if key in self._items:
                return False
            if len(self._items) >= self.capacity:
                return False
            self._items[key] = now
            return True


def verify_relay_assertion(
    headers: Mapping[str, str],
    *,
    method: str,
    path: str,
    body: bytes,
    keys: Mapping[str, bytes],
    replay_cache: ReplayNonceCache,
    now_ms: int | None = None,
) -> str:
    values = {name: headers.get(header) for name, header in HEADER_NAMES.items()}
    if any(not isinstance(value, str) or not value for value in values.values()):
        raise RelayAssertionError("assertion absente")
    device_id = str(values["device_id"])
    key_id = str(values["key_id"])
    timestamp = str(values["timestamp"])
    nonce = str(values["nonce"])
    supplied = str(values["signature"])
    if len(device_id) > 256 or len(nonce) > 128 or not nonce.isascii():
        raise RelayAssertionError("assertion invalide")
    try:
        timestamp_number = int(timestamp)
    except ValueError:
        raise RelayAssertionError("assertion invalide") from None
    now_ms = int(time.time() * 1000) if now_ms is None else now_ms
    if abs(now_ms - timestamp_number) > 120_000:
        raise RelayAssertionError("assertion expirée")
    secret = keys.get(key_id)
    if secret is None:
        raise RelayAssertionError("assertion invalide")
    expected = "v1=" + base64.urlsafe_b64encode(
        hmac.new(secret, canonical_message(method, path, timestamp, nonce, device_id, body), hashlib.sha256).digest()
    ).decode("ascii").rstrip("=")
    if not hmac.compare_digest(expected, supplied):
        raise RelayAssertionError("assertion invalide")
    if not replay_cache.reserve(key_id, nonce, now=now_ms / 1000):
        raise RelayAssertionError("assertion rejouée")
    return device_id
