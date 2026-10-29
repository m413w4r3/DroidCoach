# ATLAS Core Tools for OpenClaw

This local, tool-only plugin exposes the six ATLAS V4 tools. It does not install or activate itself. The plugin-level `enabled` flag defaults to `false`; each capability flag also defaults to `false`. A disabled plugin registers no tools, while an enabled plugin returns a typed `CAPABILITY_UNAVAILABLE` result for an individually disabled capability.

The Core destination defaults to `http://127.0.0.1:8100`. Configuration accepts only numeric IPv4 loopback addresses in `127.0.0.0/8` or IPv6 `::1`, over HTTP, with no URL credentials, path, query, or fragment. Tool arguments cannot select a URL, host, route, HTTP method, or header. The six routes are fixed from `ARCHITECTURE_V63.md` section 3.1.

Authentication uses `ATLAS_CORE_TOOLS_TOKEN` from the Gateway process environment, or `tokenFile` if the environment value is absent. The file must be a regular file owned by the Gateway user with mode `0600` and contain only a bearer token. No credential value belongs in this package or OpenClaw's plugin config.

Calls use fixed request headers, no redirects, a 2 second wait for response headers, a 5 second response body deadline, a 64 KiB request bound, and a 256 KiB response bound. The tool factory binds a six-call limit and one-proposal limit to OpenClaw's current tool-context object. OpenClaw 2026.10.5 provides no `turnId` to the direct execution callback; this relies on the documented per-turn factory context lifetime and must be confirmed in an isolated Gateway before activation.

GET tools map only their contract fields to fixed query parameters. The proposal tool sends its strict V4 argument object to `POST /internal/v4/plan-revision-proposals`. Requests include the V4 contract/schema versions, request id, and capability in fixed `X-Atlas-*` headers.

The `atlas_propose_plan_revision` endpoint only queues a proposal. The plugin never writes the DayPlan. Android remains responsible for revalidation and any user confirmation.

```sh
npm test
npm run build
npm run validate
```

The copied files in `contracts/v4/` preserve the V4 request, result, and error schemas used at runtime. Tests compare them with the repository contracts.
