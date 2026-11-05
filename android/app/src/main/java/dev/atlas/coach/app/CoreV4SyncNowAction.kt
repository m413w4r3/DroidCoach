package dev.atlas.coach.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Observer
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.atlas.coach.sync.AndroidSyncStorage
import dev.atlas.coach.sync.relay.RelayConnectionState
import dev.atlas.coach.sync.relay.RelayTransportMode
import dev.atlas.coach.sync.v4.CoreV4SyncScheduler
import kotlinx.coroutines.delay

@Composable
internal fun CoreV4SyncNowAction(
    controller: AtlasController,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String = "core_v4_sync_now",
) {
    val context = LocalContext.current.applicationContext
    val workManager = remember(context) { WorkManager.getInstance(context) }
    val store = remember(context) { AndroidSyncStorage.v4(context) }
    val work = remember(workManager) {
        workManager.getWorkInfosForUniqueWorkLiveData(CoreV4SyncScheduler.IMMEDIATE_WORK)
    }
    var workInfos by remember(work) { mutableStateOf(emptyList<WorkInfo>()) }
    var storeMessage by remember(store) { mutableStateOf(store.presentation().message) }
    var storeCode by remember(store) { mutableStateOf(store.presentation().code) }
    var localBusy by remember { mutableStateOf(false) }
    var enqueueFailed by remember { mutableStateOf(false) }

    DisposableEffect(work) {
        val observer = Observer<List<WorkInfo>> { infos ->
            workInfos = infos.orEmpty()
            val presentation = store.presentation()
            storeMessage = presentation.message
            storeCode = presentation.code
            val waitingOrRunning = infos.orEmpty().any {
                it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING
            }
            if (!waitingOrRunning) {
                CoreV4SyncScheduler.finishRequestNow()
                localBusy = false
            }
            enqueueFailed = false
        }
        work.observeForever(observer)
        onDispose { work.removeObserver(observer) }
    }

    val currentWork = workInfos.firstOrNull { it.state.isActiveCoreV4Sync() }
        ?: workInfos.firstOrNull()
    val workState = currentWork?.state
    val relayMode = controller.relaySettings.mode
    val relayState = controller.relaySettings.state
    val connectionUnavailable = when (relayMode) {
        RelayTransportMode.RELAY -> relayState != RelayConnectionState.RELAY_CONNECTED
        RelayTransportMode.DIRECT -> controller.syncBaseUrl.isBlank() || !controller.syncTokenConfigured
        RelayTransportMode.OFFLINE -> true
    }
    val isBusy = coreV4SyncActionIsBusy(
        localBusy = localBusy,
        requestDebounce = CoreV4SyncScheduler.isRequestNowBusy(),
        workState = workState,
    )
    val status = when {
        enqueueFailed -> "La synchronisation n’a pas pu être mise en attente. Réessayez."
        connectionUnavailable -> when (relayMode) {
            RelayTransportMode.RELAY -> when (relayState) {
                RelayConnectionState.PAIRING_REQUIRED -> "Appairage du relais requis pour synchroniser."
                else -> "Relais déconnecté. Reconnectez-vous dans les réglages."
            }
            RelayTransportMode.DIRECT -> "Connexion directe incomplète. Vérifiez les réglages."
            RelayTransportMode.OFFLINE -> "Mode hors ligne. Choisissez une connexion dans les réglages."
        }
        else -> coreV4SyncActionStatus(workState, storeCode, storeMessage)
    }
    val settingsNeeded = connectionUnavailable || workState == WorkInfo.State.FAILED ||
        coreV4SyncFailureNeedsSettings(storeCode)

    LaunchedEffect(localBusy) {
        if (localBusy) {
            delay(CoreV4SyncScheduler.REQUEST_NOW_DEBOUNCE_MILLIS)
            localBusy = false
        }
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            status,
            modifier = Modifier.testTag("${testTag}_status").semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp).testTag(testTag),
                enabled = !isBusy && !connectionUnavailable,
                onClick = {
                    localBusy = true
                    val accepted = CoreV4SyncScheduler.requestNow(context)
                    enqueueFailed = !accepted && !CoreV4SyncScheduler.isRequestNowBusy()
                    if (enqueueFailed) localBusy = false
                },
            ) {
                Text(if (isBusy) "Synchronisation en cours…" else "Synchroniser maintenant")
            }
            if (settingsNeeded) {
                TextButton(
                    modifier = Modifier.sizeIn(minHeight = 48.dp).testTag("${testTag}_settings"),
                    onClick = onOpenSettings,
                ) { Text("Réglages de connexion") }
            } else if (isBusy) {
                OutlinedButton(
                    modifier = Modifier.sizeIn(minHeight = 48.dp),
                    enabled = false,
                    onClick = {},
                ) { Text("En attente") }
            }
        }
    }
}

internal fun coreV4SyncActionStatus(
    workState: WorkInfo.State?,
    storeCode: String,
    storeMessage: String,
): String = when (workState) {
    WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> "Synchronisation en attente."
    WorkInfo.State.RUNNING -> "Synchronisation en cours…"
    WorkInfo.State.SUCCEEDED -> when {
        storeCode == "NO_PENDING_SOURCE" -> "Synchronisation terminée. Aucune projection à envoyer."
        coreV4SyncStatusIsFailure(storeCode) -> coreV4SyncFailureMessage(storeCode)
        else -> "Synchronisation terminée."
    }
    WorkInfo.State.FAILED -> "Échec de la synchronisation. Vérifiez la connexion puis réessayez."
    WorkInfo.State.CANCELLED -> "Synchronisation annulée."
    null -> if (storeCode == "NO_PENDING_SOURCE") {
        "Aucune projection à envoyer."
    } else if (coreV4SyncStatusIsFailure(storeCode)) {
        coreV4SyncFailureMessage(storeCode)
    } else {
        storeMessage.takeIf { storeCode !in setOf("", "UNKNOWN", "IDLE") }
            ?: "La synchronisation est prête."
    }
}

internal fun coreV4SyncActionIsBusy(
    localBusy: Boolean,
    requestDebounce: Boolean,
    workState: WorkInfo.State?,
): Boolean = localBusy || requestDebounce || workState == WorkInfo.State.RUNNING

internal fun coreV4SyncFailureNeedsSettings(storeCode: String): Boolean =
    storeCode.uppercase() in setOf("AUTHENTICATION", "OFFLINE", "UNAVAILABLE", "CONFIGURATION")

private fun coreV4SyncStatusIsFailure(storeCode: String): Boolean = storeCode.uppercase() in setOf(
    "AUTHENTICATION", "CONSENT", "CONFLICT", "REJECTED", "UNAVAILABLE", "OFFLINE",
    "PROTOCOL", "CONFIGURATION", "IDEMPOTENCY_CONFLICT", "CONSENT_PENDING",
    "INBOX_LOCAL_ERROR", "LOCAL_ERROR", "ERROR", "STALE", "MISMATCH", "UNKNOWN",
)

private fun coreV4SyncFailureMessage(storeCode: String): String = when (storeCode.uppercase()) {
    "AUTHENTICATION" -> "Échec de synchronisation. Reconnectez le relais dans les réglages."
    "OFFLINE", "UNAVAILABLE" -> "Relais hors ligne. Reconnectez-vous dans les réglages puis réessayez."
    "CONSENT", "CONSENT_PENDING" -> "Échec de synchronisation. Vérifiez les autorisations puis réessayez."
    else -> "Échec de la synchronisation. Vérifiez la connexion puis réessayez."
}

private fun WorkInfo.State.isActiveCoreV4Sync(): Boolean =
    this == WorkInfo.State.ENQUEUED || this == WorkInfo.State.BLOCKED || this == WorkInfo.State.RUNNING
