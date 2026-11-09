"""Signed Relay assertion validation at the Core V4 HTTP boundary."""

from __future__ import annotations

import base64
import fcntl
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


def _unique_json_object(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate JSON key")
        result[key] = value
    return result


def parse_assertion_keys(raw: str | None) -> dict[str, bytes]:
    if not raw:
        return {}
    try:
        value = json.loads(raw, object_pairs_hook=_unique_json_object)
    except (TypeError, json.JSONDecodeError, ValueError):
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
    if len(set(result.values())) != len(result):
        raise ValueError("chaque clé d’assertion doit utiliser un secret distinct")
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


class FileReplayNonceCache:
    """Durable, process-shared nonce reservations in a private directory."""

    _LOCK_NAME = ".lock"
    _MAX_EXPIRY_BYTES = 32

    def __init__(
        self,
        directory: str | os.PathLike[str],
        *,
        capacity: int = 8192,
        ttl_seconds: int = 300,
        cleanup_scan_limit: int = 128,
    ) -> None:
        if capacity < 1 or ttl_seconds < 1 or cleanup_scan_limit < 1:
            raise ValueError("Replay cache limits must be positive")
        if not hasattr(os, "O_NOFOLLOW") or not hasattr(os, "O_DIRECTORY"):
            raise ValueError("File replay cache requires O_NOFOLLOW and O_DIRECTORY")
        self.capacity = capacity
        self.ttl_seconds = ttl_seconds
        self.cleanup_scan_limit = cleanup_scan_limit
        self.directory = self._validate_directory_path(directory)
        self._directory_fd = self._open_private_directory()

    @staticmethod
    def _validate_directory_path(directory: str | os.PathLike[str]) -> Path:
        try:
            raw_path = os.fspath(directory)
        except TypeError:
            raise ValueError("ATLAS_V4_REPLAY_CACHE_DIR must be an absolute path") from None
        if not isinstance(raw_path, str) or not os.path.isabs(raw_path):
            raise ValueError("ATLAS_V4_REPLAY_CACHE_DIR must be an absolute path")
        path = Path(os.path.abspath(raw_path))
        repository_root = Path(__file__).resolve().parents[2]
        try:
            path.relative_to(repository_root)
        except ValueError:
            return path
        raise ValueError("ATLAS_V4_REPLAY_CACHE_DIR must be outside the repository")

    def _open_private_directory(self) -> int:
        flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | getattr(os, "O_CLOEXEC", 0)
        try:
            directory_fd = os.open(os.sep, flags)
            components = self.directory.parts[1:]
            for index, component in enumerate(components):
                is_target = index == len(components) - 1
                created = False
                try:
                    child_fd = os.open(component, flags, dir_fd=directory_fd)
                except FileNotFoundError:
                    try:
                        os.mkdir(component, mode=0o700, dir_fd=directory_fd)
                        created = True
                    except FileExistsError:
                        pass
                    child_fd = os.open(component, flags, dir_fd=directory_fd)
                try:
                    if created:
                        os.fchmod(child_fd, 0o700)
                    info = os.fstat(child_fd)
                    if not stat.S_ISDIR(info.st_mode):
                        raise ValueError("ATLAS_V4_REPLAY_CACHE_DIR contains a non-directory component")
                    if is_target and (info.st_uid != os.geteuid() or stat.S_IMODE(info.st_mode) != 0o700):
                        raise ValueError("ATLAS_V4_REPLAY_CACHE_DIR must be owned by Core and mode 0700")
                except (OSError, ValueError):
                    try:
                        os.close(child_fd)
                    except OSError:
                        pass
                    raise
                os.close(directory_fd)
                directory_fd = child_fd
            if not components:
                raise ValueError("ATLAS_V4_REPLAY_CACHE_DIR cannot be the filesystem root")
            return directory_fd
        except (OSError, ValueError):
            try:
                os.close(directory_fd)
            except (OSError, UnboundLocalError):
                pass
            raise

    @staticmethod
    def _nonce_name(key_id: str, nonce: str) -> str:
        if not isinstance(key_id, str) or not isinstance(nonce, str) or "\0" in key_id or "\0" in nonce:
            raise ValueError("invalid replay cache key")
        canonical = f"{key_id}\0{nonce}".encode("utf-8", errors="strict")
        return hashlib.sha256(canonical).hexdigest()

    @staticmethod
    def _is_nonce_name(name: str) -> bool:
        return len(name) == 64 and all(character in "0123456789abcdef" for character in name)

    def _acquire_directory_lock(self) -> int | None:
        if not isinstance(self._directory_fd, int):
            return None
        flags = os.O_RDWR | os.O_NOFOLLOW | getattr(os, "O_CLOEXEC", 0)
        descriptor: int | None = None
        created = False
        try:
            try:
                descriptor = os.open(
                    self._LOCK_NAME,
                    flags | os.O_CREAT | os.O_EXCL,
                    0o600,
                    dir_fd=self._directory_fd,
                )
                created = True
            except FileExistsError:
                descriptor = os.open(self._LOCK_NAME, flags, dir_fd=self._directory_fd)
            info = os.fstat(descriptor)
            if (
                not stat.S_ISREG(info.st_mode)
                or info.st_uid != os.geteuid()
                or stat.S_IMODE(info.st_mode) != 0o600
            ):
                os.close(descriptor)
                return None
            if created:
                os.fsync(descriptor)
                os.fsync(self._directory_fd)
            fcntl.flock(descriptor, fcntl.LOCK_EX)
            return descriptor
        except OSError:
            if descriptor is not None:
                try:
                    os.close(descriptor)
                except OSError:
                    pass
            return None

    def _read_expiration(self, name: str) -> int | None:
        flags = os.O_RDONLY | os.O_NOFOLLOW | getattr(os, "O_CLOEXEC", 0)
        descriptor = os.open(name, flags, dir_fd=self._directory_fd)
        try:
            info = os.fstat(descriptor)
            if (
                not stat.S_ISREG(info.st_mode)
                or info.st_uid != os.geteuid()
                or stat.S_IMODE(info.st_mode) != 0o600
            ):
                return None
            contents = bytearray()
            while len(contents) <= self._MAX_EXPIRY_BYTES:
                part = os.read(descriptor, self._MAX_EXPIRY_BYTES + 1 - len(contents))
                if not part:
                    break
                contents.extend(part)
            if len(contents) > self._MAX_EXPIRY_BYTES or not contents or not contents.isdigit():
                return None
            return int(contents)
        finally:
            os.close(descriptor)

    def _discard_expired_target(self, name: str, now_ms: int) -> bool:
        try:
            expiration_ms = self._read_expiration(name)
        except FileNotFoundError:
            return True
        except OSError:
            return False
        if expiration_ms is None:
            return False
        if expiration_ms > now_ms:
            return True
        try:
            os.unlink(name, dir_fd=self._directory_fd)
            os.fsync(self._directory_fd)
            return True
        except OSError:
            return False

    def _cleanup_expired(self, now_ms: int) -> bool:
        scan_fd: int | None = None
        removed_any = False
        try:
            scan_fd = os.dup(self._directory_fd)
            with os.scandir(scan_fd) as entries:
                for index, entry in enumerate(entries):
                    if index >= self.cleanup_scan_limit:
                        break
                    name = entry.name
                    if not self._is_nonce_name(name):
                        continue
                    expiration_ms = self._read_expiration(name)
                    if expiration_ms is None:
                        return False
                    if expiration_ms <= now_ms:
                        os.unlink(name, dir_fd=self._directory_fd)
                        removed_any = True
            if removed_any:
                os.fsync(self._directory_fd)
            return True
        except OSError:
            return False
        finally:
            if scan_fd is not None:
                try:
                    os.close(scan_fd)
                except OSError:
                    pass

    def _nonce_file_count(self) -> int | None:
        scan_fd: int | None = None
        count = 0
        max_scanned = self.capacity + self.cleanup_scan_limit + 2
        try:
            scan_fd = os.dup(self._directory_fd)
            with os.scandir(scan_fd) as entries:
                for index, entry in enumerate(entries):
                    if index >= max_scanned:
                        return self.capacity
                    if self._is_nonce_name(entry.name):
                        count += 1
                        if count >= self.capacity:
                            return count
            return count
        except OSError:
            return None
        finally:
            if scan_fd is not None:
                try:
                    os.close(scan_fd)
                except OSError:
                    pass

    def _create_nonce_file(self, name: str, expiration_ms: int) -> bool:
        flags = (
            os.O_WRONLY
            | os.O_CREAT
            | os.O_EXCL
            | os.O_NOFOLLOW
            | getattr(os, "O_CLOEXEC", 0)
        )
        descriptor: int | None = None
        try:
            descriptor = os.open(name, flags, 0o600, dir_fd=self._directory_fd)
            info = os.fstat(descriptor)
            if (
                not stat.S_ISREG(info.st_mode)
                or info.st_uid != os.geteuid()
                or stat.S_IMODE(info.st_mode) != 0o600
            ):
                return False
            payload = str(expiration_ms).encode("ascii")
            offset = 0
            while offset < len(payload):
                written = os.write(descriptor, payload[offset:])
                if written <= 0:
                    return False
                offset += written
            os.fsync(descriptor)
            os.fsync(self._directory_fd)
            return True
        except FileExistsError:
            return False
        except OSError:
            # Keep a partially created marker in place: EEXIST then fails closed.
            return False
        finally:
            if descriptor is not None:
                try:
                    os.close(descriptor)
                except OSError:
                    pass

    def reserve(self, key_id: str, nonce: str, *, now: float | None = None) -> bool:
        """Atomically reserve a nonce; I/O, capacity, and cleanup failures deny it."""
        try:
            name = self._nonce_name(key_id, nonce)
            now_seconds = time.time() if now is None else now
            now_ms = int(round(now_seconds * 1000))
            expiration_ms = now_ms + self.ttl_seconds * 1000
        except (OverflowError, TypeError, ValueError, UnicodeError):
            return False

        lock_fd = self._acquire_directory_lock()
        if lock_fd is None:
            return False
        try:
            if not self._cleanup_expired(now_ms):
                return False
            if not self._discard_expired_target(name, now_ms):
                return False
            count = self._nonce_file_count()
            if count is None or count >= self.capacity:
                return False
            return self._create_nonce_file(name, expiration_ms)
        finally:
            try:
                fcntl.flock(lock_fd, fcntl.LOCK_UN)
            except OSError:
                pass
            try:
                os.close(lock_fd)
            except OSError:
                pass

    def close(self) -> None:
        descriptor = getattr(self, "_directory_fd", None)
        if descriptor is not None:
            self._directory_fd = None
            os.close(descriptor)

    def __del__(self) -> None:
        try:
            self.close()
        except OSError:
            pass


def verify_relay_assertion(
    headers: Mapping[str, str],
    *,
    method: str,
    path: str,
    body: bytes,
    keys: Mapping[str, bytes],
    replay_cache: ReplayNonceCache | FileReplayNonceCache,
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
