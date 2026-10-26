package dev.atlas.coach.usage

/** Deliberately small, deterministic JSON writer for the minimized V6.3 usage contract. */
object UsageSummaryJson {
    private val pseudonymPattern = Regex("app:[0-9a-f]{24}")

    fun serialize(summary: UsageSummary, consent: GlobalUsageConsent): String {
        require(consent.isExplicit) { "Usage summary serialization requires current consent" }
        require(summary.consentVersion == consent.consentVersion) { "Summary consent version is stale" }
        require(consent.allowsPackageNames || summary.apps.none { it.displayName != null }) {
            "Display names require the package_names consent category"
        }
        require(summary.apps.all { pseudonymPattern.matches(it.appRef) }) {
            "app_ref must be a stable local pseudonym"
        }

        val appsJson = summary.apps.joinToString(separator = ",") { app ->
            buildString {
                append('{')
                append("\"app_ref\":").append(quote(app.appRef))
                if (consent.allowsPackageNames && app.displayName != null) {
                    append(",\"display_name\":").append(quote(app.displayName))
                }
                append(",\"foreground_ms\":").append(app.foregroundMs)
                append(",\"launch_count\":").append(app.launchCount)
                if (app.lastUsedAtUtc != null) append(",\"last_used_at\":").append(app.lastUsedAtUtc)
                append(",\"reliability\":").append(quote(app.reliability.name))
                append('}')
            }
        }

        val coverageJson = buildString {
            append('{')
            append("\"observed_ms\":").append(summary.coverage.observedMs)
            if (summary.coverage.blindMs != null) append(",\"blind_ms\":").append(summary.coverage.blindMs)
            append(",\"events_seen\":").append(summary.coverage.eventsSeen)
            append(",\"truncated\":").append(summary.coverage.truncated)
            append('}')
        }
        return buildString {
            append('{')
            append("\"window_start\":").append(summary.windowStart).append(',')
            append("\"window_end\":").append(summary.windowEnd).append(',')
            append("\"timezone\":").append(quote(summary.timezone)).append(',')
            append("\"total_foreground_ms\":").append(summary.totalForegroundMs).append(',')
            append("\"apps\":[").append(appsJson).append("],")
            append("\"coverage\":").append(coverageJson).append(',')
            append("\"generated_at\":").append(summary.generatedAt).append(',')
            append("\"consent_version\":").append(summary.consentVersion)
            append('}')
        }
    }

    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) {
                    append("\\u").append(char.code.toString(16).padStart(4, '0'))
                } else {
                    append(char)
                }
            }
        }
        append('"')
    }
}
