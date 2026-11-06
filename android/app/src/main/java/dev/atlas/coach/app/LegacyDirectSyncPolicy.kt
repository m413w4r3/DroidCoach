package dev.atlas.coach.app

import dev.atlas.coach.sync.relay.RelayTransportMode

internal const val LEGACY_DIRECT_SYNC_LABEL = "Synchronisation directe (V1–V3)"

internal fun isLegacyDirectSyncAvailable(mode: RelayTransportMode): Boolean =
    mode == RelayTransportMode.DIRECT

internal inline fun dispatchLegacyDirectSync(
    mode: RelayTransportMode,
    action: () -> Unit,
): Boolean {
    if (!isLegacyDirectSyncAvailable(mode)) return false
    action()
    return true
}
