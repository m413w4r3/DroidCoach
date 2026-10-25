package dev.atlas.coach.calendar

data class EtarProbeResult(
    val installed: Boolean,
    val packagesChecked: List<String>,
    val errors: List<CalendarProbeError>,
)

/**
 * Checks only the configured package names. Etar's release and debug application IDs are
 * ws.xsoh.etar and ws.xsoh.etar.debug; package visibility can still hide an installed app.
 */
class EtarProbe(
    private val provider: CalendarProvider,
    packageNames: List<String> = DEFAULT_PACKAGE_CANDIDATES,
) {
    private val packageNames = packageNames.distinct()

    init {
        require(this.packageNames.all(String::isNotBlank)) { "package names must not be blank" }
    }

    fun probe(): EtarProbeResult {
        val checked = mutableListOf<String>()
        val errors = mutableListOf<CalendarProbeError>()
        for (packageName in packageNames) {
            checked += packageName
            try {
                if (provider.isPackageInstalled(packageName)) {
                    return EtarProbeResult(true, checked.toList(), errors.boundedDistinct())
                }
            } catch (_: SecurityException) {
                errors += CalendarProbeError.ETAR_PACKAGE_PROBE_FAILED
            } catch (_: Exception) {
                errors += CalendarProbeError.ETAR_PACKAGE_PROBE_FAILED
            }
        }
        return EtarProbeResult(false, checked.toList(), errors.boundedDistinct())
    }

    companion object {
        val DEFAULT_PACKAGE_CANDIDATES: List<String> = listOf("ws.xsoh.etar", "ws.xsoh.etar.debug")
    }
}

internal fun Iterable<CalendarProbeError>.boundedDistinct(): List<CalendarProbeError> =
    distinct().take(MAX_CALENDAR_PROBE_ERRORS)
