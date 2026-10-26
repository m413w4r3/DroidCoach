import { defineToolPlugin } from "openclaw/plugin-sdk/tool-plugin";
import { Type } from "typebox";
import { Value } from "typebox/value";
import { ResearchParametersSchema, SearchModeSchema, ResearchToolOutputSchema, isRecord, isSafeResearchText, parseResearchAnswer, validateResearchOutput, } from "./contracts.js";
import { BridgeClient, BridgeClientError, assertBridgeConfig, } from "./bridge-client.js";
function isHttpsUrl(value) {
    try {
        const url = new URL(value);
        return url.protocol === "https:" && Boolean(url.hostname) && !url.username && !url.password;
    }
    catch {
        return false;
    }
}
function searchModeFromMetadata(metadata) {
    const mode = metadata.web_search_mode;
    return Value.Check(SearchModeSchema, mode) ? mode : "untouched";
}
function citationsFromMetadata(metadata) {
    const raw = metadata.visible_citations;
    if (raw === undefined || raw === null)
        return [];
    if (!Array.isArray(raw))
        return undefined;
    const citations = [];
    for (const entry of raw) {
        if (!isRecord(entry) || typeof entry.label !== "string" || typeof entry.url !== "string") {
            return undefined;
        }
        const citation = {
            label: entry.label,
            url: entry.url,
        };
        if (entry.canonical_url !== undefined) {
            if (typeof entry.canonical_url !== "string")
                return undefined;
            citation.canonical_url = entry.canonical_url;
        }
        if (!isSafeResearchText(citation.label) || !isHttpsUrl(citation.url) || (citation.canonical_url && !isHttpsUrl(citation.canonical_url))) {
            return undefined;
        }
        citations.push(citation);
    }
    return citations;
}
function baseOutput(requestId, runId, status, summary, metadata = {}) {
    const searchMode = searchModeFromMetadata(metadata);
    const limitations = [];
    if (searchMode === "prompt_instructed") {
        limitations.push("La recherche a été demandée dans le prompt, sans activation vérifiée du contrôle UI.");
    }
    else if (searchMode === "ui_tool") {
        limitations.push("Le contrôle UI de recherche a été activé; cela ne garantit ni la fraîcheur ni la fiabilité des sources.");
    }
    else if (searchMode === "off" || searchMode === "off_unverified") {
        limitations.push("Le Bridge indique que la recherche web était désactivée ou non vérifiée.");
    }
    else {
        limitations.push("Le mode de recherche web n’a pas été confirmé par le Bridge.");
    }
    return {
        summary,
        facts: [],
        citations: [],
        search_mode: searchMode,
        verified_search: searchMode === "ui_tool",
        status,
        limitations,
        run_id: runId,
        request_id: requestId,
    };
}
function failureOutput(error, requestId) {
    const bridgeError = error instanceof BridgeClientError ? error : undefined;
    const code = bridgeError?.code;
    const uncertain = bridgeError?.ambiguous === true || code === "needs_review" || code === "deadline";
    let status = uncertain ? "uncertain" : "failed";
    let summary = "La recherche n’a pas pu être validée.";
    let limitation = "Le résultat a été refusé; aucune réponse partielle n’est fournie.";
    if (code === "configuration") {
        status = "unavailable";
        summary = "Le Bridge n’est pas configuré.";
        limitation = "Configurez bridgeUrl et la référence d’environnement BRIDGE_API_KEY.";
    }
    else if (code === "unauthorized") {
        status = uncertain ? "uncertain" : "unavailable";
        summary = "Le Bridge a refusé l’authentification.";
        limitation = "Vérifiez la configuration de l’API key du Bridge.";
    }
    else if (code === "rate_limited") {
        status = uncertain ? "uncertain" : "unavailable";
        summary = "Le Bridge a limité les requêtes.";
        limitation = "Réessayez après le délai indiqué par le Bridge.";
    }
    else if (code === "network" || code === "server_error" || code === "timeout") {
        status = uncertain ? "uncertain" : "unavailable";
        summary = "Le Bridge n’a pas fourni de résultat vérifiable.";
        limitation = uncertain
            ? "La soumission peut avoir été acceptée; le même run a été recherché par identifiant et aucune nouvelle génération n’a été lancée."
            : "Le service Bridge est momentanément indisponible.";
    }
    else if (code === "not_found") {
        status = "uncertain";
        summary = "Le run Bridge n’a pas pu être retrouvé.";
        limitation = "Aucune nouvelle génération n’a été lancée après la perte du run.";
    }
    else if (code === "needs_review") {
        status = "uncertain";
        summary = "Le Bridge a demandé une revue du résultat.";
        limitation = "Le contenu incomplet n’est pas retourné comme réponse.";
    }
    else if (code === "malformed_json" || code === "invalid_contract") {
        status = uncertain ? "uncertain" : "failed";
        summary = "La réponse Bridge ne respecte pas le contrat attendu.";
        limitation = "La réponse a été refusée sans en extraire de résultat partiel.";
    }
    else if (code === "aborted") {
        status = uncertain ? "uncertain" : "unavailable";
        summary = "La recherche a été annulée.";
        limitation = uncertain
            ? "Le run a peut-être déjà été accepté; aucun deuxième run n’a été soumis."
            : "Aucun résultat de recherche n’a été produit.";
    }
    else if (code === "conflict") {
        status = uncertain ? "uncertain" : "failed";
        summary = "La clé d’idempotence est associée à une requête différente.";
        limitation = "Le Bridge n’a pas lancé de génération supplémentaire.";
    }
    else if (code === "deadline") {
        status = "uncertain";
        summary = "Le délai maximal du run Bridge est dépassé.";
        limitation = "Le run est resté ambigu; aucune nouvelle génération n’a été lancée.";
    }
    const output = baseOutput(requestId, bridgeError?.runId ?? "", status, summary);
    output.limitations = [limitation];
    return validateResearchOutput(output);
}
function successfulOutput(run, requestId) {
    const output = baseOutput(requestId, run.id, "completed", "", run.metadata);
    if (run.status === "needs_review") {
        output.status = "uncertain";
        output.summary = "Le Bridge a demandé une revue du résultat.";
        output.limitations = ["Le contenu incomplet n’est pas retourné comme réponse."];
        return validateResearchOutput(output);
    }
    if (run.status === "failed") {
        const submissionState = run.metadata.submission_state;
        const ambiguous = submissionState === "submission_attempted" || submissionState === "post_submission";
        output.status = ambiguous ? "uncertain" : "failed";
        output.summary = ambiguous
            ? "La génération Bridge a échoué après une soumission ambiguë."
            : "La génération Bridge a échoué.";
        output.limitations = ["Aucune réponse partielle n’est fournie."];
        return validateResearchOutput(output);
    }
    const answer = typeof run.output_text === "string" ? parseResearchAnswer(run.output_text) : undefined;
    const citations = citationsFromMetadata(run.metadata);
    if (!answer || !citations) {
        output.status = "failed";
        output.summary = "La réponse Bridge ne respecte pas le contrat de recherche.";
        output.limitations = ["La réponse a été refusée sans en extraire de résultat partiel."];
        return validateResearchOutput(output);
    }
    output.summary = answer.summary;
    output.facts = answer.facts;
    output.citations = citations;
    if (citations.length === 0)
        output.limitations.push("Le Bridge n’a fourni aucune citation visible.");
    return validateResearchOutput(output);
}
export async function executeResearchTool(parameters, config, context, options = {}) {
    try {
        assertBridgeConfig(config);
        if (!Value.Check(ResearchParametersSchema, parameters)) {
            throw new BridgeClientError("invalid_contract", "Research parameters are invalid.");
        }
        const run = await new BridgeClient(config, options).research(parameters, context.toolCallId, context.signal);
        return successfulOutput(run, context.toolCallId);
    }
    catch (error) {
        return failureOutput(error, context.toolCallId);
    }
}
export const plugin = defineToolPlugin({
    id: "atlas_chatgpt_research",
    name: "ATLAS ChatGPT Research",
    description: "Research abstract topics through the local ChatGPT Bridge and return structured, qualified findings.",
    configSchema: Type.Object({
        bridgeUrl: Type.String({ minLength: 1 }),
        apiKey: Type.String({ minLength: 1 }),
    }, { additionalProperties: false }),
    tools: (tool) => [
        tool({
            name: "atlas_chatgpt_research",
            label: "ATLAS ChatGPT Research",
            description: "Search an abstract topic and return a structured answer with visible citations and explicit limits.",
            optional: true,
            parameters: ResearchParametersSchema,
            outputSchema: ResearchToolOutputSchema,
            async execute(parameters, config, context) {
                return executeResearchTool(parameters, config, {
                    toolCallId: context.toolCallId,
                    signal: context.signal,
                });
            },
        }),
    ],
});
export default plugin;
