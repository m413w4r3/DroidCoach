package dev.atlas.coach.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Parent wiring for the independent global usage summary; collection is always user-triggered. */
@Composable
internal fun GlobalUsageRoute(
    controller: GlobalUsageController,
    openUsageAccessSettings: () -> Unit,
) {
    var state by remember(controller) { mutableStateOf(controller.state()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(controller) { state = controller.state() }

    GlobalUsageScreen(
        state = state,
        onSaveConsent = { includePackageNames ->
            controller.grantConsent(includePackageNames)
            state = controller.state()
        },
        onOpenUsageAccessSettings = openUsageAccessSettings,
        onRefresh = {
            scope.launch {
                state = withContext(Dispatchers.IO) { controller.refresh() }
            }
        },
        onRevokeConsent = {
            controller.revokeConsent()
            state = controller.state()
        },
        onSaveQuota = { minutes, enabled ->
            controller.saveDailyQuota(minutes, enabled)
            state = controller.state()
        },
    )
}
