import { defineToolPlugin } from "openclaw/plugin-sdk/tool-plugin";
import { jsonResult } from "openclaw/plugin-sdk/tool-results";
import type { AnyAgentTool } from "openclaw/plugin-sdk/plugin-entry";
import type { ToolPluginFactoryContext } from "openclaw/plugin-sdk/tool-plugin";
import {
  ARGUMENT_SCHEMAS,
  OUTPUT_SCHEMAS,
  TOOL_NAMES,
  type ToolName,
} from "./contracts.js";
import {
  AtlasCoreToolsConfigSchema,
  createAtlasToolExecutor,
  createTurnQuota,
  type AtlasCoreToolsConfig,
  type TurnQuota,
} from "./core-client.js";

const toolDescriptions: Record<ToolName, string> = {
  atlas_get_day_plan: "Read the bounded ATLAS day-plan projection for a local date. The response is a timestamped snapshot, not a guarantee of current Android state.",
  atlas_get_activity_status: "Read one ATLAS activity status and its recorded provenance from the local Core projection.",
  atlas_propose_plan_revision: "Submit a bounded plan-revision proposal to the ATLAS Core inbox. This never changes the DayPlan; Android revalidates it and may require user confirmation.",
  atlas_get_usage_summary: "Read a consented, aggregated ATLAS usage summary for a window of at most 14 days. No screen or message content is returned.",
  atlas_get_goal_progress: "Read the known ATLAS goal-progress projection, including missing-data markers and freshness.",
  atlas_get_training_plan: "Read the available wger training-plan projection, its sync state, and explicitly recorded results.",
};

const toolLabels: Record<ToolName, string> = {
  atlas_get_day_plan: "ATLAS Day Plan",
  atlas_get_activity_status: "ATLAS Activity Status",
  atlas_propose_plan_revision: "Propose ATLAS Plan Revision",
  atlas_get_usage_summary: "ATLAS Usage Summary",
  atlas_get_goal_progress: "ATLAS Goal Progress",
  atlas_get_training_plan: "ATLAS Training Plan",
};

const quotaByToolContext = new WeakMap<object, TurnQuota>();

function quotaForTurnContext(toolContext: object): TurnQuota {
  const existing = quotaByToolContext.get(toolContext);
  if (existing) return existing;
  const quota = createTurnQuota();
  quotaByToolContext.set(toolContext, quota);
  return quota;
}

export default defineToolPlugin({
  id: "atlas_core_tools",
  name: "ATLAS Core Tools",
  description: "Six bounded ATLAS V4 projection and proposal tools backed by a dedicated local Core bearer.",
  activation: { onStartup: true },
  configSchema: AtlasCoreToolsConfigSchema,
  tools: (tool) => TOOL_NAMES.map((toolName) => {
    const parameters = ARGUMENT_SCHEMAS[toolName];
    const outputSchema = OUTPUT_SCHEMAS[toolName];
    return tool({
      name: toolName,
      label: toolLabels[toolName],
      description: toolDescriptions[toolName],
      parameters,
      optional: true,
      factory: (context: ToolPluginFactoryContext<AtlasCoreToolsConfig>): AnyAgentTool | null => {
        const { config, toolContext } = context;
        if (config.enabled !== true) return null;
        const quota = quotaForTurnContext(toolContext);
        const executeAtlasTool = createAtlasToolExecutor(config);
        return {
          name: toolName,
          label: toolLabels[toolName],
          description: toolDescriptions[toolName],
          parameters,
          outputSchema,
          execute: async (toolCallId, params, signal) => jsonResult(
            await executeAtlasTool(toolName, params, { toolCallId, signal, quota }),
          ),
        } as AnyAgentTool;
      },
    });
  }),
});
