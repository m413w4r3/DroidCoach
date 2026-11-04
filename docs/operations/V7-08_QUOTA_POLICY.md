# V7-08 — Core daily token quota policy

## Configuration

`ATLAS_AGENT_DAILY_TOKENS_ENFORCED` controls only whether the Core locally
blocks requests at its daily token ceiling. Its accepted values are `true` and
`false`, case-insensitive; surrounding whitespace is trimmed. Any other value
causes adapter construction to fail explicitly.

The variable is optional and defaults to `true`, preserving the historical
policy. `ATLAS_AGENT_DAILY_TOKENS` remains the ceiling and defaults to `100000`.
The adapter status continues to report `daily_tokens` and
`daily_token_limit`, and adds `daily_token_limit_enforced` so operators can
confirm which policy the running Core loaded.

With enforcement set to `false`, exceeding the local daily ceiling alone no
longer blocks `_begin_attempt`; reservations, usage accounting, and status
reporting continue unchanged. The option does not disable the RPM limit, its
60-second window, request timeout or chat deadline, circuit breaker, bounded
retries, concurrency controls, tool/proposal limits, identity or consent
validation, or provider 429 handling. A real provider 429 remains a rate-limit
failure.

The local counter is in memory, as before. Disabling enforcement does not
clear or stop accounting for it. The existing UTC-day rollover and process
startup initialization are unchanged; no persistent quota store is added.

## Activation and deactivation

To keep or explicitly restore the historical cap, either leave the variable
unset or configure:

```yaml
ATLAS_AGENT_DAILY_TOKENS_ENFORCED: "true"
```

To disable only local daily-cap blocking for a controlled Core run, configure:

```yaml
ATLAS_AGENT_DAILY_TOKENS_ENFORCED: "false"
```

Apply the environment change through the deployment's normal Core restart
procedure. Confirm `daily_token_limit_enforced` in the running adapter status,
and check that `daily_tokens` continues to increase while requests remain
subject to the other limits above.

In this personal deployment, `compose.openclaw.yaml` sets
`ATLAS_AGENT_DAILY_TOKENS_ENFORCED: "false"` explicitly. The Core still reports
usage, but the local daily ceiling no longer rejects the Coach. Reverting that
line to `"true"` (or removing it) restores the historical hard stop; see the
rollback section below.

This change is code and documentation only. It does not modify or restart a
live Core.

## Rollback

Set the variable to `true` or remove it, then restart the Core using the normal
deployment procedure. Confirm that status reports
`daily_token_limit_enforced: true`. At or above the configured daily limit,
the next attempt is blocked by the local quota again. The rollback regression
is covered by `test_rollback_to_true_blocks_again_with_accumulated_daily_usage`
in `backend/tests/test_v7_08_quota_policy.py`.

The counter remains process-local and resets on process startup or the existing
UTC-day rollover, just as it did before this option existed. This option does
not add persistence across restarts.

## Verification evidence

The new policy tests use `httpx.MockTransport` to check the default and explicit
`true` behavior, invalid values, disabled-cap accounting above 100000 tokens,
RPM and circuit-breaker behavior, bounded timeout retries, provider 429
mapping, and rollback to `true` with the accumulated count preserved in the
test. On the implementation worktree, the commands produced these results:

```text
cd backend && .venv/bin/python -m pytest tests/test_agent_v2.py tests/test_chat_orchestration_v4.py -q
87 passed, 1 skipped, 1 warning

cd backend && .venv/bin/python -m pytest -q
388 passed, 1 skipped, 3 warnings
```

The policy-only file also passed independently: 14 passed, 1 warning. Warnings
were the Starlette `BlockingPortal` deprecation and two existing duplicate
FastAPI operation ID warnings in OpenAPI generation.
