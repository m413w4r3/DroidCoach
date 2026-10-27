package dev.atlas.coach.sync.relay

/** Build policy is present only in the debug source set; a release has no marker and fails closed. */
internal object RelayBuildPolicy {
    private const val DEBUG_MARKER = "dev.atlas.coach.sync.relay.RelayDebugBuildMarker"

    fun isDebugBuild(): Boolean = runCatching {
        Class.forName(DEBUG_MARKER, false, RelayBuildPolicy::class.java.classLoader)
    }.isSuccess
}

internal fun RelayConfig.requireUrlAllowedForCurrentBuild(debugBuild: Boolean = RelayBuildPolicy.isDebugBuild()) {
    val error = RelayConfigurationValidator.relayUrlError(relayUrl, debugBuild)
    require(error == null) { "invalid relay URL policy" }
}
