import { describe, expect, it, vi } from "vitest";
import { randomUUID } from "node:crypto";
import { Value } from "typebox/value";
import { assertBridgeConfig, BridgeClientError, type BridgeConfig } from "./bridge-client.js";
import { ResearchParametersSchema, ResearchToolOutputSchema, validateResearchOutput } from "./contracts.js";
import { executeResearchTool } from "./index.js";

const validConfig: BridgeConfig = { bridgeUrl: "http://127.0.0.1:8001", apiKey: randomUUID() };

describe("plugin contracts and configuration", () => {
  it("requires exactly one bounded topic or question", () => {
    expect(Value.Check(ResearchParametersSchema, { topic: "A question" })).toBe(true);
    expect(Value.Check(ResearchParametersSchema, { question: "A question", max_results: 8 })).toBe(true);
    expect(Value.Check(ResearchParametersSchema, {})).toBe(false);
    expect(Value.Check(ResearchParametersSchema, { topic: "x", question: "y" })).toBe(false);
    expect(Value.Check(ResearchParametersSchema, { topic: "x".repeat(501) })).toBe(false);
    expect(Value.Check(ResearchParametersSchema, { topic: "x", max_results: 9 })).toBe(false);
  });

  it("rejects absent, blank, unresolved, and non-HTTP configuration with typed errors", () => {
    const cases: Array<BridgeConfig | undefined> = [
      undefined,
      { bridgeUrl: "http://127.0.0.1:8001", apiKey: "" },
      { bridgeUrl: "http://127.0.0.1:8001", apiKey: "${BRIDGE_API_KEY}" },
      { bridgeUrl: "file:///tmp/bridge", apiKey: randomUUID() },
    ];
    for (const config of cases) {
      expect(() => assertBridgeConfig(config)).toThrow(BridgeClientError);
      try {
        assertBridgeConfig(config);
      } catch (error) {
        if (config?.apiKey) expect(String(error)).not.toContain(config.apiKey);
        expect(String(error)).not.toContain("${BRIDGE_API_KEY}");
      }
    }
  });

  it("rejects output that contains unknown or malformed fields", () => {
    const valid = {
      summary: "Summary",
      facts: ["Fact"],
      citations: [],
      search_mode: "untouched",
      verified_search: false,
      status: "completed",
      limitations: [],
      run_id: "run-1",
      request_id: "tool-1",
    };
    expect(() => validateResearchOutput(valid)).not.toThrow();
    expect(Value.Check(ResearchToolOutputSchema, { ...valid, command: "echo unsafe" })).toBe(false);
  });

  it("takes citations only from Bridge metadata, never from generated answer fields", async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockImplementation(async () => new Response(JSON.stringify({
      id: "run-citations",
      status: "completed",
      output_text: JSON.stringify({
        summary: "Answer",
        facts: ["Fact"],
        citations: [{ label: "Invented", url: "https://invented.example" }],
      }),
      metadata: { web_search_mode: "prompt_instructed", visible_citations: [] },
    }), { status: 200 }));

    const result = await executeResearchTool(
      { topic: "Topic" },
      validConfig,
      { toolCallId: "call-citations" },
      { fetchImpl },
    );

    expect(result.citations).toEqual([]);
    expect(result.status).toBe("failed");
    expect(result.facts).toEqual([]);
  });
});
