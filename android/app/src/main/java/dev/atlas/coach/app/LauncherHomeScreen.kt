/*
 * ATLAS-owned Compose HOME. Its historical Olauncher adaptation is limited to
 * the text-first presentation/model recorded in docs/AUDIT_CIBLE.md and
 * THIRD_PARTY_NOTICES.md; this V5 screen adds no copied third-party source.
 */
package dev.atlas.coach.app

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.atlas.coach.app.ui.AtlasPrimaryButton
import dev.atlas.coach.launcher.AndroidLauncherCatalog
import dev.atlas.coach.launcher.LauncherAppModel
import dev.atlas.coach.launcher.LauncherAppRules
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
internal fun LauncherHomeScreen(
    controller: AtlasController,
    context: Context,
    isHomeRoleHeld: Boolean,
    onRequestHomeRole: () -> Unit,
    openCoach: () -> Unit,
    openPreviousLauncher: () -> Unit,
    openHomeSettings: () -> Unit,
) {
    if (CapabilityRules.mayRenderLauncherHome(controller.capabilities, isHomeRoleHeld)) {
        LauncherHomeContent(
            controller = controller,
            context = context,
            openCoach = openCoach,
            openPreviousLauncher = openPreviousLauncher,
            openHomeSettings = openHomeSettings,
        )
    } else {
        LauncherRoleSetupScreen(onRequestHomeRole, openHomeSettings)
    }
}

@Composable
private fun LauncherRoleSetupScreen(onRequestHomeRole: () -> Unit, openHomeSettings: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Text("ATLAS · Accueil Android", style = MaterialTheme.typography.headlineMedium)
        Text("Choisissez ATLAS comme application d'accueil dans Android pour ouvrir cet écran avec le geste Accueil.")
        AtlasPrimaryButton("Choisir ATLAS comme accueil…", onClick = onRequestHomeRole)
        OutlinedButton(modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp), onClick = openHomeSettings) {
            Text("Gérer les réglages HOME")
        }
    }
}

@Composable
private fun LauncherHomeContent(
    controller: AtlasController,
    context: Context,
    openCoach: () -> Unit,
    openPreviousLauncher: () -> Unit,
    openHomeSettings: () -> Unit,
) {
    val catalog = remember(context) { AndroidLauncherCatalog(context) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val density = LocalDensity.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var refreshToken by remember { mutableIntStateOf(0) }
    var apps by remember { mutableStateOf<List<LauncherAppModel>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf(false) }
    var clock by remember { mutableStateOf(currentClockText()) }
    var search by remember { mutableStateOf("") }
    var drawerOpen by remember { mutableStateOf(false) }
    var launcherMessage by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = true) {
        if (drawerOpen) drawerOpen = false
        // Android HOME stays open when Back is pressed while its drawer is closed.
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshToken++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(refreshToken, catalog) {
        loading = true
        loadError = false
        val result = withContext(Dispatchers.IO) { runCatching { catalog.list() } }
        apps = result.getOrElse { loadError = true; emptyList() }
        loading = false
    }
    LaunchedEffect(Unit) {
        while (true) {
            val now = LocalTime.now()
            val millisToNextMinute = 60_000L - now.second * 1_000L - now.nano / 1_000_000L
            delay(millisToNextMinute.coerceAtLeast(1L))
            clock = currentClockText()
        }
    }

    val pinnedApps = LauncherAppRules.favorites(apps, controller.launcherPinnedPackages)
    val filteredApps = LauncherAppRules.filter(apps, search)
    val onToggleDrawer = { drawerOpen = !drawerOpen }
    val onLaunchApp: (LauncherAppModel) -> Unit = { app ->
        if (!catalog.launch(app)) {
            val message = "Impossible d'ouvrir ${app.appLabel}."
            launcherMessage = message
            controller.reportMessage(message)
        } else {
            launcherMessage = null
        }
    }

    Column(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
            .padding(horizontal = 20.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(top = 18.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            if (drawerOpen) {
                Column(
                    modifier = Modifier.fillMaxWidth().verticalHomeSwipe(density, drawerOpen, onToggleDrawer)
                        .testTag("launcher_drawer_header"),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Applications", style = MaterialTheme.typography.headlineMedium)
                    Text("Glissez vers le bas pour revenir à l'accueil.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth().testTag("launcher_search"),
                    value = search,
                    onValueChange = { search = it },
                    label = { Text("Rechercher une application") },
                    placeholder = { Text("Nom ou paquet") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() }),
                )
                when {
                    loading -> Text("Chargement des applications…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    loadError -> Text("La liste n'a pas pu être chargée. Revenez ici pour réessayer.", color = MaterialTheme.colorScheme.error)
                    apps.isEmpty() -> Text("Aucune application avec un raccourci d'accueil n'est visible.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    filteredApps.isEmpty() -> Text("Aucune application ne correspond à cette recherche.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> filteredApps.forEach { app ->
                        LauncherAppRow(
                            app = app,
                            pinned = app.appPackage in controller.launcherPinnedPackages,
                            onLaunch = { onLaunchApp(app) },
                            onTogglePin = { controller.toggleLauncherPin(app.appPackage) },
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth().verticalHomeSwipe(density, drawerOpen, onToggleDrawer)
                        .testTag("launcher_swipe_header"),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("ATLAS", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, letterSpacing = 2.sp)
                    Text(clock, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Light, letterSpacing = 1.sp)
                    Text(
                        LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRANCE))
                            .replaceFirstChar { it.uppercase(Locale.FRANCE) },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text("Glissez vers le haut pour ouvrir les applications.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(modifier = Modifier.sizeIn(minHeight = 48.dp), onClick = { drawerOpen = true }) {
                        Text("Ouvrir le tiroir d'applications")
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Directive du jour", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Text(controller.directive.title, style = MaterialTheme.typography.headlineSmall)
                    Text(controller.directive.reason, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Applications favorites", style = MaterialTheme.typography.titleMedium)
                    if (pinnedApps.isEmpty()) {
                        Text("Aucun favori. Ouvrez le tiroir et épinglez les applications que vous utilisez.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        pinnedApps.forEach { app ->
                            TextButton(
                                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 52.dp)
                                    .semantics {
                                        contentDescription = "Ouvrir l'application favorite ${app.appLabel}"
                                        customActions = listOf(CustomAccessibilityAction("Retirer ${app.appLabel} des favoris") {
                                            controller.toggleLauncherPin(app.appPackage)
                                            true
                                        })
                                    },
                                onClick = { onLaunchApp(app) },
                            ) {
                                Text(app.appLabel, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
            }
            launcherMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }

        Column(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Button(
                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 52.dp)
                    .semantics { contentDescription = if (controller.isEmergencyPaused) "Terminer la pause d'urgence" else "Activer la pause d'urgence hors ligne pendant 30 minutes" },
                onClick = { if (controller.isEmergencyPaused) controller.endEmergencyPause() else controller.setEmergencyPause() },
            ) {
                Text(if (controller.isEmergencyPaused) "Terminer la pause d'urgence" else "Pause d'urgence · 30 min")
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp),
                    onClick = openPreviousLauncher,
                ) { Text("Launcher précédent") }
                TextButton(
                    modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp),
                    onClick = openHomeSettings,
                ) { Text("Réglages HOME") }
            }
            TextButton(
                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
                onClick = openCoach,
            ) { Text("Coach") }
        }
    }
}

@Composable
private fun LauncherAppRow(
    app: LauncherAppModel,
    pinned: Boolean,
    onLaunch: () -> Unit,
    onTogglePin: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(
            modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp)
                .semantics {
                    contentDescription = "Ouvrir ${app.appLabel}"
                    customActions = listOf(CustomAccessibilityAction(
                        if (pinned) "Retirer ${app.appLabel} des favoris" else "Épingler ${app.appLabel} parmi les favoris",
                    ) {
                        onTogglePin()
                        true
                    })
                },
            onClick = onLaunch,
        ) { Text(app.appLabel, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge) }
        TextButton(
            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .semantics {
                    contentDescription = if (pinned) "Retirer ${app.appLabel} des favoris" else "Épingler ${app.appLabel} parmi les favoris"
                },
            onClick = onTogglePin,
        ) { Text(if (pinned) "Favori" else "Épingler") }
    }
}

private fun Modifier.verticalHomeSwipe(
    density: Density,
    drawerOpen: Boolean,
    toggleDrawer: () -> Unit,
): Modifier = pointerInput(density, drawerOpen) {
    var totalY = 0f
    val threshold = with(density) { 64.dp.toPx() }
    detectVerticalDragGestures(
        onDragStart = { totalY = 0f },
        onDragEnd = {
            if (!drawerOpen && totalY < -threshold) toggleDrawer()
            if (drawerOpen && totalY > threshold) toggleDrawer()
        },
        onVerticalDrag = { change, amount ->
            totalY += amount
            if (abs(totalY) >= threshold) change.consume()
        },
    )
}

private fun currentClockText(): String = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm", Locale.FRANCE))
