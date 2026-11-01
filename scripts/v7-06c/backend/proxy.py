#!/usr/bin/env python3
"""Loopback OpenClaw recording proxy; stores metadata only, never payload content."""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import os
from pathlib import Path
import threading
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urljoin
from urllib.request import Request, urlopen


HOP_BY_HOP = {
    "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
    "te", "trailers", "transfer-encoding", "upgrade", "host", "content-length",
}


def digest(value: object) -> str:
    return hashlib.sha256(str(value).encode("utf-8", errors="replace")).hexdigest()[:16]


def call_metadata(request: dict) -> dict:
    messages = request.get("messages")
    roles: list[str] = []
    tool_ids: list[str] = []
    has_tool_role = False
    if isinstance(messages, list):
        for message in messages:
            if not isinstance(message, dict):
                continue
            role = message.get("role")
            if isinstance(role, str):
                roles.append(role)
                has_tool_role = has_tool_role or role == "tool"
            tool_call_id = message.get("tool_call_id")
            if isinstance(tool_call_id, str):
                tool_ids.append(digest(tool_call_id))

    definitions = request.get("tools")
    requested_names: list[str] = []
    if isinstance(definitions, list):
        for item in definitions:
            function = item.get("function") if isinstance(item, dict) else None
            name = function.get("name") if isinstance(function, dict) else None
            if isinstance(name, str):
                requested_names.append(name)

    return {
        "model": request.get("model") if isinstance(request.get("model"), str) else None,
        "request_id_hash": digest(request["request_id"]) if isinstance(request.get("request_id"), str) else None,
        "conversation_id_hash": digest(request["conversation_id"]) if isinstance(request.get("conversation_id"), str) else None,
        "message_roles": roles,
        "role_tool_present": has_tool_role,
        "role_tool_call_id_hashes": tool_ids,
        "requested_tool_names": requested_names,
        "requested_tool_count": len(requested_names),
    }


def completion_metadata(response: dict) -> dict:
    choices = response.get("choices")
    finish_reason = None
    returned_names: list[str] = []
    returned_ids: list[str] = []
    if isinstance(choices, list) and choices and isinstance(choices[0], dict):
        choice = choices[0]
        finish_reason = choice.get("finish_reason")
        message = choice.get("message")
        calls = message.get("tool_calls") if isinstance(message, dict) else None
        if isinstance(calls, list):
            for call in calls:
                if not isinstance(call, dict):
                    continue
                name = call.get("function", {}).get("name") if isinstance(call.get("function"), dict) else None
                call_id = call.get("id")
                if isinstance(name, str):
                    returned_names.append(name)
                if isinstance(call_id, str):
                    returned_ids.append(digest(call_id))
    return {
        "finish_reason": finish_reason if isinstance(finish_reason, str) else None,
        "returned_tool_names": returned_names,
        "returned_tool_call_id_hashes": returned_ids,
    }


class RecordingServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = False

    def __init__(self, address: tuple[str, int], target: str, trace_path: Path):
        self.target = target.rstrip("/")
        self.trace_path = trace_path
        self.trace_lock = threading.Lock()
        super().__init__(address, RecordingHandler)

class RecordingHandler(BaseHTTPRequestHandler):
    server: RecordingServer
    protocol_version = "HTTP/1.1"

    def log_message(self, _format: str, *_args: object) -> None:
        return

    def do_GET(self) -> None:  # noqa: N802
        if self.path in {"/health", "/healthz"}:
            payload = b'{"status":"ok"}'
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
            return
        self._forward(trace=False)

    def do_POST(self) -> None:  # noqa: N802
        self._forward(trace=self.path.split("?", 1)[0] == "/v1/chat/completions")

    def _forward(self, *, trace: bool) -> None:
        started = time.monotonic()
        raw = b""
        request_meta: dict = {}
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if length < 0 or length > 2_000_000:
                self.send_error(413)
                return
            raw = self.rfile.read(length)
            if trace:
                try:
                    decoded = json.loads(raw)
                    if isinstance(decoded, dict):
                        request_meta = call_metadata(decoded)
                except (UnicodeError, json.JSONDecodeError):
                    request_meta = {"request_json_valid": False}
            headers = {
                key: value for key, value in self.headers.items()
                if key.lower() not in HOP_BY_HOP
            }
            request = Request(
                urljoin(self.server.target + "/", self.path.lstrip("/")),
                data=raw if self.command in {"POST", "PUT", "PATCH"} else None,
                headers=headers,
                method=self.command,
            )
            try:
                upstream = urlopen(request, timeout=90)
                status = upstream.status
                response_body = upstream.read(2_000_000 + 1)
                response_headers = upstream.headers
            except HTTPError as error:
                status = error.code
                response_body = error.read(2_000_000 + 1)
                response_headers = error.headers
            if len(response_body) > 2_000_000:
                status = 502
                response_body = b'{"error":"upstream response too large"}'
            if trace:
                response_meta: dict = {}
                try:
                    decoded_response = json.loads(response_body)
                    if isinstance(decoded_response, dict):
                        response_meta = completion_metadata(decoded_response)
                except (UnicodeError, json.JSONDecodeError):
                    response_meta = {"response_json_valid": False}
                # The index is assigned under the same lock used to append JSONL.
                self._append_chat_trace({
                    **request_meta,
                    **response_meta,
                    "http_status": status,
                    "request_bytes": len(raw),
                    "response_bytes": len(response_body),
                    "duration_ms": round((time.monotonic() - started) * 1000, 2),
                    "recorded_at": datetime.now(timezone.utc).isoformat(timespec="milliseconds"),
                })
            self.send_response(status)
            for key, value in response_headers.items():
                if key.lower() in {"content-type", "cache-control", "retry-after"}:
                    self.send_header(key, value)
            self.send_header("Content-Length", str(len(response_body)))
            self.end_headers()
            self.wfile.write(response_body)
        except (OSError, URLError, ValueError):
            if trace:
                self._append_chat_trace({
                    **request_meta,
                    "http_status": 502,
                    "request_bytes": len(raw),
                    "response_bytes": 0,
                    "duration_ms": round((time.monotonic() - started) * 1000, 2),
                    "upstream_error": True,
                    "recorded_at": datetime.now(timezone.utc).isoformat(timespec="milliseconds"),
                })
            try:
                self.send_error(502)
            except OSError:
                pass

    def _append_chat_trace(self, record: dict) -> None:
        with self.server.trace_lock:
            try:
                with self.server.trace_path.open("rb") as stream:
                    index = sum(1 for _ in stream) + 1
            except FileNotFoundError:
                index = 1
            record["index"] = index
            line = json.dumps(record, ensure_ascii=True, sort_keys=True, separators=(",", ":")) + "\n"
            fd = os.open(self.server.trace_path, os.O_WRONLY | os.O_APPEND | os.O_CREAT, 0o600)
            try:
                os.write(fd, line.encode("utf-8"))
                os.fsync(fd)
            finally:
                os.close(fd)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--target", required=True)
    parser.add_argument("--trace", type=Path, required=True)
    args = parser.parse_args()
    args.trace.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    args.trace.touch(mode=0o600, exist_ok=True)
    os.chmod(args.trace, 0o600)
    server = RecordingServer(("127.0.0.1", args.port), args.target, args.trace)
    try:
        server.serve_forever(poll_interval=0.2)
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
