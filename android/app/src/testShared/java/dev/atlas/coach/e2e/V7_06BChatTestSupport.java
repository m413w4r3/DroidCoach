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
                    + "([^.]*)\\.\\s*"
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

    /** Parses the dedicated diagnostic field; user-visible assistant text contains no tool metadata. */
    public static ToolMetadata parseToolMetadata(String diagnosticDetails) {
        if (diagnosticDetails == null || diagnosticDetails.isBlank()
                || !diagnosticDetails.contains(TOOLS_PROVENANCE_LABEL)) {
            throw new IllegalStateException("V4 tool metadata is missing from diagnostic details");
        }
        String suffix = diagnosticDetails.stripTrailing();
        String toolState;
        int toolCallCount;
        int proposalCount;
        String proposalIdsText;

        Matcher zeroMatch = ZERO_PROPOSAL_METADATA_PATTERN.matcher(suffix);
        if (zeroMatch.matches()) {
            toolState = zeroMatch.group(1);
            toolCallCount = Integer.parseInt(zeroMatch.group(2));
            proposalCount = 0;
            proposalIdsText = "";
        } else {
            Matcher match = TOOL_METADATA_SUFFIX_PATTERN.matcher(suffix);
            if (!match.matches()) {
                throw new IllegalStateException("V4 diagnostic tool metadata is malformed");
            }
            toolState = match.group(1);
            toolCallCount = Integer.parseInt(match.group(2));
            proposalCount = Integer.parseInt(match.group(3));
            proposalIdsText = match.group(4);
        }

        if (!"completed".equalsIgnoreCase(toolState)) {
            throw new IllegalStateException("TOOL_STATE_NOT_COMPLETED");
        }

        List<String> proposalIds = new ArrayList<>();
        if (!proposalIdsText.trim().isEmpty()) {
            for (String id : proposalIdsText.split(",", -1)) {
                proposalIds.add(id.trim());
            }
        }
        if (proposalCount != proposalIds.size()) {
            throw new IllegalStateException("PROPOSAL_COUNT_MISMATCH");
        }
        if (proposalCount == 0) {
            throw new IllegalStateException("PROPOSAL_MISSING");
        }
        for (int index = 0; index < proposalIds.size(); index++) {
            String proposalId = proposalIds.get(index);
            if (!isCanonicalUuid(proposalId)) {
                throw new IllegalStateException("PROPOSAL_ID_INVALID");
            }
            if (proposalIds.subList(0, index).contains(proposalId)) {
                throw new IllegalStateException("PROPOSAL_ID_DUPLICATE");
            }
        }
        return new ToolMetadata(toolState.toLowerCase(Locale.ROOT), toolCallCount, proposalIds);
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
