package dev.atlas.coach.e2e;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure-JVM support shared by the local oracle and the QA instrumentation. */
public final class V7_06BChatTestSupport {
    private static final String TOOLS_PROVENANCE_LABEL = "État des outils";
    private static final Pattern TOOL_METADATA_SUFFIX_PATTERN = Pattern.compile(
            "État des outils\\s*:\\s*([a-z_]+)\\s*\\((\\d+) appel\\(s\\)\\)\\.\\s*"
                    + "Proposition\\(s\\)\\s*\\((\\d+)\\)\\s*—\\s*identifiants\\s*:\\s*"
                    + "([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(?:,\\s*[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})*)\\.\\s*"
                    + "Contenu à synchroniser via la boîte de réception\\.",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ZERO_PROPOSAL_METADATA_PATTERN = Pattern.compile(
            "État des outils\\s*:\\s*([a-z_]+)\\s*\\((\\d+) appel\\(s\\)\\)\\.\\s*"
                    + "Aucune proposition\\s*\\(0\\)\\.",
            Pattern.CASE_INSENSITIVE);

    private V7_06BChatTestSupport() {}

    public static String syntheticCoachMessage(
            String localDate,
            String planId,
            int baseVersion,
            String activityId,
            int targetStartMinute,
            int durationMinutes,
            String runId) {
        return "Décale mon activité de travail de 10 minutes et présente-moi la modification à confirmer. "
                + "run_id=" + runId + ". Appelle atlas_get_day_plan pour local_date=" + localDate
                + "; vérifie exactement plan_id=" + planId + " et version=" + baseVersion + ". Ensuite appelle "
                + "atlas_propose_plan_revision exactement une fois avec plan_id=" + planId
                + ", base_version=" + baseVersion + ", changes: [{\"op\": \"reschedule\", "
                + "\"activity_id\": \"" + activityId + "\", \"target_start_minute\": " + targetStartMinute
                + ", \"duration_minutes\": " + durationMinutes + "}], et justification: "
                + "\"Décaler cette activité de 10 minutes.\". Ne crée aucune autre proposition et n'applique rien.";
    }

    public static ToolMetadata parseToolMetadata(String text) {
        int metadataIndex = text.lastIndexOf(TOOLS_PROVENANCE_LABEL);
        if (metadataIndex < 0) {
            throw new IllegalStateException("V4 tool metadata is missing from the assistant response");
        }
        String suffix = text.substring(metadataIndex).stripTrailing();
        if (ZERO_PROPOSAL_METADATA_PATTERN.matcher(suffix).matches()) {
            throw new IllegalStateException("PROPOSAL_MISSING");
        }
        Matcher match = TOOL_METADATA_SUFFIX_PATTERN.matcher(suffix);
        if (!match.matches()) {
            throw new IllegalStateException("V4 tool metadata is not the generated suffix of the assistant response");
        }

        int proposalCount = Integer.parseInt(match.group(3));
        List<String> proposalIds = new ArrayList<>();
        if (proposalCount > 0) {
            for (String id : match.group(4).split(",")) {
                proposalIds.add(id.trim());
            }
        }
        if (proposalCount != proposalIds.size()) {
            throw new AssertionError("metadata proposal count must match the returned ID list");
        }
        if (!proposalIds.stream().allMatch(V7_06BChatTestSupport::isCanonicalUuid)) {
            throw new AssertionError("proposal IDs must be canonical UUIDs");
        }
        return new ToolMetadata(match.group(1), Integer.parseInt(match.group(2)), proposalIds);
    }

    private static boolean isCanonicalUuid(String value) {
        try {
            return UUID.fromString(value).toString().equals(value.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException failure) {
            return false;
        }
    }

    public static final class ToolMetadata {
        private final String toolState;
        private final int toolCallCount;
        private final List<String> proposalIds;

        private ToolMetadata(String toolState, int toolCallCount, List<String> proposalIds) {
            this.toolState = toolState;
            this.toolCallCount = toolCallCount;
            this.proposalIds = Collections.unmodifiableList(new ArrayList<>(proposalIds));
        }

        public String getToolState() {
            return toolState;
        }

        public int getToolCallCount() {
            return toolCallCount;
        }

        public List<String> getProposalIds() {
            return proposalIds;
        }
    }
}
