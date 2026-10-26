import { Type, type Static } from "typebox";
import { Value } from "typebox/value";

const TopicSchema = Type.String({ minLength: 1, maxLength: 500 });
const GoalSchema = Type.String({ maxLength: 120 });
const LanguageSchema = Type.String({ maxLength: 16 });
const FreshnessSchema = Type.Union([
  Type.Literal("any"),
  Type.Literal("week"),
  Type.Literal("month"),
  Type.Literal("year"),
]);
const MaxResultsSchema = Type.Integer({ minimum: 1, maximum: 8 });

const RequestOptionsSchema = {
  goal: Type.Optional(GoalSchema),
  language: Type.Optional(LanguageSchema),
  freshness: Type.Optional(FreshnessSchema),
  max_results: Type.Optional(MaxResultsSchema),
};

export const ResearchParametersSchema = Type.Union([
  Type.Object(
    { topic: TopicSchema, ...RequestOptionsSchema },
    { additionalProperties: false },
  ),
  Type.Object(
    { question: TopicSchema, ...RequestOptionsSchema },
    { additionalProperties: false },
  ),
]);

export type ResearchParameters = Static<typeof ResearchParametersSchema>;

export const SearchModeSchema = Type.Union([
  Type.Literal("ui_tool"),
  Type.Literal("prompt_instructed"),
  Type.Literal("off"),
  Type.Literal("off_unverified"),
  Type.Literal("untouched"),
]);

export const ResearchStatusSchema = Type.Union([
  Type.Literal("completed"),
  Type.Literal("failed"),
  Type.Literal("uncertain"),
  Type.Literal("unavailable"),
]);

export const CitationSchema = Type.Object(
  {
    label: Type.String({ minLength: 1 }),
    url: Type.String({ minLength: 1 }),
    canonical_url: Type.Optional(Type.String({ minLength: 1 })),
  },
  { additionalProperties: false },
);

export const ResearchToolOutputSchema = Type.Object(
  {
    summary: Type.String(),
    facts: Type.Array(Type.String()),
    citations: Type.Array(CitationSchema),
    search_mode: SearchModeSchema,
    verified_search: Type.Boolean(),
    status: ResearchStatusSchema,
    limitations: Type.Array(Type.String()),
    run_id: Type.String(),
    request_id: Type.String(),
  },
  { additionalProperties: false },
);

export type ResearchToolOutput = Static<typeof ResearchToolOutputSchema>;

export const ResearchAnswerSchema = Type.Object(
  {
    summary: Type.String({ minLength: 1 }),
    facts: Type.Array(Type.String()),
  },
  { additionalProperties: false },
);

export type ResearchAnswer = Static<typeof ResearchAnswerSchema>;

export function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

const UNSAFE_RESEARCH_TEXT_PATTERNS = [
  /```|<script\b/i,
  /\b(?:ignore|disregard)\s+(?:all\s+)?(?:previous|prior|system|developer)\s+(?:instructions|rules|prompts?)\b/i,
  /\b(?:run|execute|launch)\s+(?:this\s+)?(?:command|script|shell)\b/i,
  /\b(?:type|enter|paste)\s+(?:this\s+)?(?:command|script)\b/i,
  /\b(?:open\s+the\s+terminal|copy\s+and\s+paste|click\s+(?:this\s+)?link|download\s+(?:this\s+)?file|visit\s+https?:\/\/|go\s+to\s+https?:\/)/i,
  /\b(?:rm\s+-rf|curl\s+https?:\/\/|wget\s+https?:\/\/|powershell\s+-|sudo\s+|npm\s+(?:install|run)|python\s+-|bash\s+-c)\b/i,
];

export function isSafeResearchText(value: string): boolean {
  return UNSAFE_RESEARCH_TEXT_PATTERNS.every((pattern) => !pattern.test(value));
}

export function validateResearchOutput(value: unknown): ResearchToolOutput {
  if (!Value.Check(ResearchToolOutputSchema, value)) {
    throw new Error("Research output does not satisfy its contract.");
  }
  return value;
}

export function parseResearchAnswer(value: string): ResearchAnswer | undefined {
  try {
    const parsed: unknown = JSON.parse(value);
    if (!Value.Check(ResearchAnswerSchema, parsed)) return undefined;
    const answer = parsed as ResearchAnswer;
    const strings = [answer.summary, ...answer.facts];
    const unsafe = strings.some((entry) => !isSafeResearchText(entry));
    return unsafe ? undefined : answer;
  } catch {
    return undefined;
  }
}
