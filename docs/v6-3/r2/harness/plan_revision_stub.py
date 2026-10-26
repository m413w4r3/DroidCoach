#!/usr/bin/env python3
"""Deterministic local V3 plan-revision stub for ATLAS emulator UI qualification.

This is NOT production code and never calls a model, wger, the Bridge or
OpenClaw. It binds loopback only and returns synthetic diffs so the Android
confirmation UI can be exercised with fixture data.

Usage:
    python3 docs/v6-3/r2/harness/plan_revision_stub.py --port 8010

The app must be configured with:
    URL   http://127.0.0.1:8010
    token r2-stub-token
and `adb reverse tcp:8010 tcp:8010`.

Scenario is selected from the user's message:
    contient "supprime"  -> suppression de la première activité
    contient "deplace"   -> déplacement de la deuxième activité
    contient "modifie"   -> modification du titre de la dernière activité
    contient "conflit"   -> ajout d'une activité chevauchante
    contient "ajoute"    -> ajout d'une pause
    sinon                -> déplacement + ajout (diff multiple)
"""

from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from uuid import uuid4

TOKEN = "r2-stub-token"
MAX_BODY = 64 * 1024


def _utc_now() -> datetime:
    return datetime.now(timezone.utc).replace(microsecond=0)


def _activity(
    *,
    client_ref: str,
    activity_id: str | None,
    kind: str,
    title: str,
    local_date: str,
    start_minute: int,
    duration_minutes: int,
    timezone_name: str,
    priority: str,
    notes: str | None = None,
) -> dict:
    return {
        "client_ref": client_ref,
        "activity_id": activity_id,
        "kind": kind,
        "title": title,
        "local_date": local_date,
        "start_minute": start_minute,
        "duration_minutes": duration_minutes,
        "timezone": timezone_name,
        "priority": priority,
        "goal_id": None,
        "sub_goal_id": None,
        "training_ref": None,
        "focus": None,
        "notes": notes,
    }


def _copy_request_activities(plan: dict) -> list[dict]:
    result = []
    for index, activity in enumerate(plan.get("activities", []), start=1):
        result.append(
            _activity(
                client_ref=f"a{index}",
                activity_id=activity["activity_id"],
                kind=activity["kind"],
                title=activity["title"],
                local_date=plan["local_date"],
                start_minute=int(activity["start_minute"]),
                duration_minutes=int(activity["duration_minutes"]),
                timezone_name=plan["timezone"],
                priority=activity["priority"],
                notes=activity.get("notes"),
            )
        )
    return result


def _scenario(message: str, plan: dict, activities: list[dict]) -> tuple[str, list[dict]]:
    text = message.lower()
    if "supprime" in text and activities:
        return "suppression d'une activité", activities[1:]
    if "deplace" in text and len(activities) >= 2:
        moved = activities[1]
        moved["start_minute"] = min(1439 - moved["duration_minutes"], moved["start_minute"] + 30)
        return "déplacement d'une activité", activities
    if "modifie" in text and activities:
        last = activities[-1]
        last["title"] = last["title"][:64] + " (ajustée)"
        last["priority"] = "IMPORTANT"
        return "modification d'une activité", activities
    if "conflit" in text and len(activities) >= 2:
        target = activities[1]
        conflict = _activity(
            client_ref="conflict",
            activity_id=None,
            kind="BUFFER",
            title="Pause en conflit",
            local_date=plan["local_date"],
            start_minute=max(0, target["start_minute"] + 10),
            duration_minutes=20,
            timezone_name=plan["timezone"],
            priority="OPTIONAL",
        )
        return "ajout d'une activité chevauchante", activities + [conflict]
    if "ajoute" in text:
        pause = _activity(
            client_ref="new-pause",
            activity_id=None,
            kind="BUFFER",
            title="Pause",
            local_date=plan["local_date"],
            start_minute=17 * 60 + 35,
            duration_minutes=15,
            timezone_name=plan["timezone"],
            priority="OPTIONAL",
        )
        return "ajout d'une pause", activities + [pause]

    if len(activities) >= 2:
        moved = activities[1]
        moved["start_minute"] = min(1439 - moved["duration_minutes"], moved["start_minute"] + 30)
    pause = _activity(
        client_ref="new-pause",
        activity_id=None,
        kind="BUFFER",
        title="Pause",
        local_date=plan["local_date"],
        start_minute=17 * 60 + 35,
        duration_minutes=15,
        timezone_name=plan["timezone"],
        priority="OPTIONAL",
    )
    return "déplacement et ajout", activities + [pause]


def build_response(body: dict) -> dict:
    request_id = str(body.get("request_id", ""))
    plan = body.get("plan") or {}
    message = str(body.get("message", ""))
    mandate = body.get("mandate_summary") or {}
    activities = _copy_request_activities(plan)
    summary, activities = _scenario(message, plan, activities)
    now = _utc_now()
    proposal_id = str(uuid4())
    return {
        "schema_version": 3,
        "reply": f"Proposition locale déterministe : {summary}. Rien n'est appliqué avant votre confirmation.",
        "agent_status": "ok",
        "proposal": {
            "schema_version": 3,
            "proposal_id": proposal_id,
            "idempotency_key": f"r2-stub-{request_id[:8]}",
            "conversation_id": body.get("conversation_id"),
            "mandate_id": str(mandate.get("id", "stub-mandate")),
            "mandate_version": int(mandate.get("version", 1)),
            "created_at": now.isoformat().replace("+00:00", "Z"),
            "expires_at": (now + timedelta(hours=1)).isoformat().replace("+00:00", "Z"),
            "required_level": "confirm",
            "state": "proposed",
            "proposal": {
                "action_type": "plan.revise_day",
                "reason": f"Scénario synthétique : {summary}.",
                "parameters": {
                    "plan_id": plan.get("plan_id"),
                    "base_version": int(plan.get("version", 1)),
                    "local_date": plan.get("local_date"),
                    "timezone": plan.get("timezone"),
                    "summary": f"{summary} (stub local)",
                    "activities": activities,
                },
            },
        },
        "overlaps": [],
    }


class Handler(BaseHTTPRequestHandler):
    server_version = "AtlasPlanStub/1.0"

    def log_message(self, fmt: str, *args) -> None:
        # Keep logs free of request bodies and tokens.
        sys.stderr.write("stub: " + fmt % args + "\n")

    def _send(self, status: int, payload: dict) -> None:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self) -> None:  # noqa: N802
        if self.path == "/health":
            self._send(200, {"status": "ok", "mode": "r2-plan-stub"})
        elif self.path == "/v2/agent/status":
            self._send(
                200,
                {
                    "adapter": "mock",
                    "connected": True,
                    "breaker": "closed",
                    "quota": {"requests_used": 0, "requests_limit": 30},
                },
            )
        else:
            self._send(404, {"detail": "not found"})

    def do_POST(self) -> None:  # noqa: N802
        if self.path != "/v3/agent/plan-revision":
            self._send(404, {"detail": "not found"})
            return
        if self.headers.get("Authorization") != f"Bearer {TOKEN}":
            self._send(401, {"detail": "unauthorized"})
            return
        length = self.headers.get("Content-Length")
        try:
            size = int(length or "0")
        except ValueError:
            self._send(400, {"detail": "invalid content length"})
            return
        if size <= 0 or size > MAX_BODY:
            self._send(413, {"detail": "invalid body size"})
            return
        raw = self.rfile.read(size)
        try:
            body = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            self._send(400, {"detail": "invalid json"})
            return
        if not isinstance(body, dict) or body.get("schema_version") != 3:
            self._send(422, {"detail": "unsupported request"})
            return
        self._send(200, build_response(body))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8010)
    args = parser.parse_args()
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"plan revision stub on http://{args.host}:{args.port} (loopback only)")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
