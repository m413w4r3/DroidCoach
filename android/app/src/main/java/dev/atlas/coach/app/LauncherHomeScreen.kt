/*
 * ATLAS-owned Compose HOME. Its historical Olauncher adaptation is limited to
 * the text-first presentation/model recorded in docs/AUDIT_CIBLE.md and
 * THIRD_PARTY_NOTICES.md; this V5 screen adds no copied third-party source.
 */
package dev.atlas.coach.app

import android.content.Context
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.heightIn
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.atlas.coach.app.ui.AtlasPrimaryButton
import dev.atlas.coach.app.ui.AtlasEngravingMark
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
    openFocus: () -> Unit = openCoach,
    openLibrary: () -> Unit = openCoach,
    openPreviousLauncher: () -> Unit,
    openHomeSettings: () -> Unit,
    agentState: HomeAgentState = HomeAgentState.localDefault(controller),
    drawerAppsOverrideForTests: List<LauncherAppModel>? = null,
) {
    if (CapabilityRules.mayRenderLauncherHome(controller.capabilities, isHomeRoleHeld)) {
        LauncherHomeContent(
            controller = controller,
            context = context,
            openCoach = openCoach,
            openFocus = openFocus,
            openLibrary = openLibrary,
            openPreviousLauncher = openPreviousLauncher,
            openHomeSettings = openHomeSettings,
            agentState = agentState,
            drawerAppsOverrideForTests = drawerAppsOverrideForTests,
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
    openFocus: () -> Unit,
    openLibrary: () -> Unit,
    openPreviousLauncher: () -> Unit,
    openHomeSettings: () -> Unit,
    agentState: HomeAgentState,
    drawerAppsOverrideForTests: List<LauncherAppModel>?,
) {
    val catalog = remember(context) { AndroidLauncherCatalog(context) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var refreshToken by remember { mutableIntStateOf(0) }
    var apps by remember { mutableStateOf<List<LauncherAppModel>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf(false) }
    var clock by remember { mutableStateOf(currentClockText()) }
    var search by remember { mutableStateOf("") }
    var drawerOpen by remember { mutableStateOf(false) }
    var showHomeMenu by remember { mutableStateOf(false) }
    var launcherMessage by remember { mutableStateOf<String?>(null) }
    var showDirectiveDetails by remember { mutableStateOf(false) }

    BackHandler(enabled = true) {
        when {
            showHomeMenu -> showHomeMenu = false
            drawerOpen -> drawerOpen = false
        }
        // Android HOME stays open when Back is pressed while its drawer is closed.
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshToken++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(refreshToken, catalog, drawerAppsOverrideForTests) {
        if (drawerAppsOverrideForTests != null) {
            apps = drawerAppsOverrideForTests
            loadError = false
            loading = false
        } else {
            loading = true
            loadError = false
            val result = withContext(Dispatchers.IO) { runCatching { catalog.list() } }
            apps = result.getOrElse { loadError = true; emptyList() }
            loading = false
        }
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
    val filteredApps = LauncherAppRules.filter(apps, search, controller.launcherPinnedPackages)
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
            .padding(horizontal = 12.dp),
    ) {
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val favoriteLimit = when {
                configuration.orientation == Configuration.ORIENTATION_LANDSCAPE || density.fontScale >= 1.75f || maxHeight < 460.dp -> 4
                density.fontScale >= 1.3f || maxHeight < 620.dp -> 5
                else -> 6
            }
            val activeFocus = agentState.focusCard?.takeIf(HomeAgentCard::isActiveFocus)

            if (drawerOpen) {
                Box(
                    modifier = Modifier.fillMaxSize().clipToBounds()
                        .testTag("launcher_home_scroll_region")
                        .padding(top = 18.dp, bottom = 12.dp),
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().testTag("launcher_drawer_list"),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item(key = "drawer_header") {
                            Column(
                                modifier = Modifier.fillMaxWidth().verticalHomeSwipe(density, drawerOpen, onToggleDrawer)
                                    .testTag("launcher_drawer_header"),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text("Applications", style = MaterialTheme.typography.headlineMedium)
                                Text("Glissez vers le bas pour revenir à l'accueil.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        item(key = "drawer_search") {
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
                        }
                        when {
                            loading -> item(key = "drawer_loading") {
                                Text("Chargement des applications…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            loadError -> item(key = "drawer_error") {
                                Text("La liste n'a pas pu être chargée. Revenez ici pour réessayer.", color = MaterialTheme.colorScheme.error)
                            }
                            apps.isEmpty() -> item(key = "drawer_empty") {
                                Text("Aucune application avec un raccourci d'accueil n'est visible.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            filteredApps.isEmpty() -> item(key = "drawer_filtered_empty") {
                                Text("Aucune application ne correspond à cette recherche.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            else -> items(items = filteredApps, key = { it.appPackage }) { app ->
                                LauncherAppRow(
                                    app = app,
                                    pinned = app.appPackage in controller.launcherPinnedPackages,
                                    onLaunch = { onLaunchApp(app) },
                                    onTogglePin = { controller.toggleLauncherPin(app.appPackage) },
                                )
                            }
                        }
                        launcherMessage?.let { message ->
                            item(key = "drawer_message") {
                                Text(message, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            } else if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                Row(
                    modifier = Modifier.fillMaxSize().clipToBounds()
                        .testTag("launcher_home_scroll_region")
                        .padding(top = 8.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Column(
                        modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().verticalHomeSwipe(density, drawerOpen, onToggleDrawer)
                                .testTag("launcher_swipe_header"),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text("ATLAS", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 2.sp)
                                AtlasEngravingMark()
                            }
                            Text(
                                clock,
                                style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Serif, fontSize = 32.sp, lineHeight = 36.sp),
                                fontWeight = FontWeight.Light,
                                letterSpacing = 1.sp,
                            )
                            Text(
                                LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRANCE))
                                    .replaceFirstChar { it.uppercase(Locale.FRANCE) },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }

                        activeFocus?.let { card ->
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("FOCUS ACTIF", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(card.body, style = MaterialTheme.typography.bodyMedium)
                                HomeDirectiveAction(
                                    label = card.actionLabel,
                                    onClick = openFocus,
                                    enabled = true,
                                    emphasized = true,
                                    testTag = "launcher_focus_card_action",
                                )
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("AUJOURD'HUI", modifier = Modifier.testTag("launcher_next_step_heading"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                agentState.directiveStatusLabel(),
                                modifier = Modifier.testTag("launcher_directive_status"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(
                                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)
                                    .semantics {
                                        contentDescription = "Directive : ${truncateHomeDirectiveTitle(agentState.directive.title)}. Afficher le détail."
                                    },
                                onClick = { showDirectiveDetails = true },
                            ) {
                                Text(
                                    truncateHomeDirectiveTitle(agentState.directive.title),
                                    modifier = Modifier.fillMaxWidth(),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (agentState.directive.body.isNotBlank()) {
                                Text(
                                    agentState.directive.body,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            val directiveAction: @Composable () -> Unit = {
                                val action: () -> Unit = {
                                    when (agentState.directive.primaryAction) {
                                        HomePrimaryAction.START_FOCUS -> openFocus()
                                        HomePrimaryAction.OPEN_COACH -> openCoach()
                                        HomePrimaryAction.NONE -> showDirectiveDetails = true
                                    }
                                }
                                HomeDirectiveAction(
                                    label = agentState.directive.primaryAction.homeActionLabel(),
                                    onClick = action,
                                    enabled = agentState.directive.primaryAction != HomePrimaryAction.NONE,
                                    emphasized = activeFocus == null,
                                )
                            }
                            directiveAction()
                        }

                        agentState.focusCard?.takeUnless(HomeAgentCard::isActiveFocus)?.let { card ->
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(card.title, style = MaterialTheme.typography.titleSmall)
                                Text(card.body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TextButton(modifier = Modifier.sizeIn(minHeight = 48.dp), onClick = {
                                    openHomeCard(card.action, openFocus, openCoach, openLibrary)
                                }) { Text(card.actionLabel, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                        launcherMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }

                    Column(
                        modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text("APPLICATIONS", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (pinnedApps.isEmpty()) {
                            Text(
                                "Aucun favori.",
                                modifier = Modifier.testTag("launcher_favorites_empty"),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            pinnedApps.take(favoriteLimit).forEach { app ->
                                TextButton(
                                    modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTag("launcher_favorite_${app.appPackage}")
                                        .semantics {
                                            contentDescription = "Ouvrir l'application favorite ${app.appLabel}"
                                            customActions = listOf(CustomAccessibilityAction("Retirer ${app.appLabel} des favoris") {
                                                controller.toggleLauncherPin(app.appPackage)
                                                true
                                            })
                                        },
                                    onClick = { onLaunchApp(app) },
                                ) {
                                    Text(app.appLabel, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                                }
                            }
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize().clipToBounds()
                        .verticalScroll(rememberScrollState())
                        .testTag("launcher_home_scroll_region")
                        .padding(top = 18.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().verticalHomeSwipe(density, drawerOpen, onToggleDrawer)
                            .testTag("launcher_swipe_header"),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("ATLAS", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 2.sp)
                            AtlasEngravingMark()
                        }
                        Text(
                            clock,
                            style = MaterialTheme.typography.headlineLarge.copy(fontFamily = FontFamily.Serif, fontSize = 46.sp),
                            fontWeight = FontWeight.Light,
                            letterSpacing = 1.sp,
                        )
                        Text(
                            LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRANCE))
                                .replaceFirstChar { it.uppercase(Locale.FRANCE) },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }

                    activeFocus?.let { card ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("FOCUS ACTIF", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(card.body, style = MaterialTheme.typography.bodyLarge)
                            HomeDirectiveAction(
                                label = card.actionLabel,
                                onClick = openFocus,
                                enabled = true,
                                emphasized = true,
                                testTag = "launcher_focus_card_action",
                            )
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("AUJOURD'HUI", modifier = Modifier.testTag("launcher_next_step_heading"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            agentState.directiveStatusLabel(),
                            modifier = Modifier.testTag("launcher_directive_status"),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(
                            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)
                                .semantics {
                                    contentDescription = "Directive : ${truncateHomeDirectiveTitle(agentState.directive.title)}. Afficher le détail."
                                },
                            onClick = { showDirectiveDetails = true },
                        ) {
                            Text(
                                truncateHomeDirectiveTitle(agentState.directive.title),
                                modifier = Modifier.fillMaxWidth(),
                                style = MaterialTheme.typography.headlineSmall.copy(fontSize = 22.sp),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (agentState.directive.body.isNotBlank()) {
                            Text(
                                agentState.directive.body,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        val directiveAction: @Composable () -> Unit = {
                            val action: () -> Unit = {
                                when (agentState.directive.primaryAction) {
                                    HomePrimaryAction.START_FOCUS -> openFocus()
                                    HomePrimaryAction.OPEN_COACH -> openCoach()
                                    HomePrimaryAction.NONE -> showDirectiveDetails = true
                                }
                            }
                            HomeDirectiveAction(
                                label = agentState.directive.primaryAction.homeActionLabel(),
                                onClick = action,
                                enabled = agentState.directive.primaryAction != HomePrimaryAction.NONE,
                                emphasized = activeFocus == null,
                            )
                        }
                        directiveAction()
                    }

                    agentState.focusCard?.takeUnless(HomeAgentCard::isActiveFocus)?.let { card ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(card.title, style = MaterialTheme.typography.titleSmall)
                            Text(card.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(modifier = Modifier.sizeIn(minHeight = 48.dp), onClick = {
                                openHomeCard(card.action, openFocus, openCoach, openLibrary)
                            }) { Text(card.actionLabel, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("APPLICATIONS", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (pinnedApps.isEmpty()) {
                            Text(
                                "Aucun favori.",
                                modifier = Modifier.testTag("launcher_favorites_empty"),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            pinnedApps.take(favoriteLimit).forEach { app ->
                                TextButton(
                                    modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTag("launcher_favorite_${app.appPackage}")
                                        .semantics {
                                            contentDescription = "Ouvrir l'application favorite ${app.appLabel}"
                                            customActions = listOf(CustomAccessibilityAction("Retirer ${app.appLabel} des favoris") {
                                                controller.toggleLauncherPin(app.appPackage)
                                                true
                                            })
                                        },
                                    onClick = { onLaunchApp(app) },
                                ) {
                                    Text(app.appLabel, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                                }
                            }
                        }
                    }
                    launcherMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        }

        val compactFooter = configuration.screenWidthDp <= 320 ||
            (configuration.screenWidthDp <= 360 && density.fontScale >= 1.5f)
        Column(
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp).testTag("launcher_fixed_footer"),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (compactFooter) 8.dp else 0.dp),
            ) {
                HomeFooterAction(
                    label = "Apps",
                    description = if (drawerOpen) "Revenir à l'accueil ATLAS" else "Ouvrir Applications et recherche",
                    tag = "launcher_open_drawer",
                    modifier = Modifier.weight(1f),
                    onClick = onToggleDrawer,
                )
                HomeFooterAction(
                    label = "Coach",
                    description = "Ouvrir le Coach",
                    tag = "launcher_open_coach",
                    modifier = Modifier.weight(1f),
                    onClick = openCoach,
                )
                if (!compactFooter) {
                    HomeFooterAction(
                        label = if (controller.isEmergencyPaused) "Fin" else "Pause",
                        description = if (controller.isEmergencyPaused) "Terminer la pause d'urgence" else "Activer la pause d'urgence hors ligne pendant 30 minutes",
                        tag = "launcher_emergency_pause",
                        modifier = Modifier.weight(1f),
                        onClick = { if (controller.isEmergencyPaused) controller.endEmergencyPause() else controller.setEmergencyPause() },
                    )
                    HomeFooterMore(showHomeMenu, { showHomeMenu = true }, { showHomeMenu = false }, openPreviousLauncher, openHomeSettings, Modifier.weight(1f))
                }
            }
            if (compactFooter) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    HomeFooterAction(
                        label = if (controller.isEmergencyPaused) "Fin" else "Pause",
                        description = if (controller.isEmergencyPaused) "Terminer la pause d'urgence" else "Activer la pause d'urgence hors ligne pendant 30 minutes",
                        tag = "launcher_emergency_pause",
                        modifier = Modifier.weight(1f),
                        onClick = { if (controller.isEmergencyPaused) controller.endEmergencyPause() else controller.setEmergencyPause() },
                    )
                    HomeFooterMore(showHomeMenu, { showHomeMenu = true }, { showHomeMenu = false }, openPreviousLauncher, openHomeSettings, Modifier.weight(1f))
                }
            }
        }
    }

    if (showDirectiveDetails) {
        AlertDialog(
            onDismissRequest = { showDirectiveDetails = false },
            title = { Text(agentState.directive.title) },
            text = {
                Column(modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Text(agentState.directive.body.ifBlank { "Aucun détail supplémentaire." })
                }
            },
            confirmButton = {
                TextButton(onClick = { showDirectiveDetails = false }) { Text("Fermer") }
            },
        )
    }
}

@Composable
private fun HomeDirectiveAction(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean,
    emphasized: Boolean,
    testTag: String = "launcher_directive_cta",
) {
    val ink = if (emphasized) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    TextButton(
        modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTag(testTag),
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 6.dp),
        onClick = onClick,
        enabled = enabled,
    ) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.titleSmall, color = ink, fontWeight = if (emphasized) FontWeight.Medium else FontWeight.Normal)
            Box(
                Modifier.width(if (emphasized) 96.dp else 52.dp).height(1.dp)
                    .background(if (emphasized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)),
            )
        }
    }
}

@Composable
private fun HomeFooterMore(
    expanded: Boolean,
    onExpand: () -> Unit,
    onDismiss: () -> Unit,
    openPreviousLauncher: () -> Unit,
    openHomeSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        HomeFooterAction(
            label = "Plus",
            description = "Plus d'options HOME",
            tag = "launcher_more",
            modifier = Modifier.fillMaxWidth(),
            onClick = onExpand,
        )
        DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
            DropdownMenuItem(
                modifier = Modifier.sizeIn(minHeight = 48.dp).testTag("launcher_previous_launcher"),
                text = { Text("Launcher précédent") },
                onClick = { onDismiss(); openPreviousLauncher() },
            )
            DropdownMenuItem(
                modifier = Modifier.sizeIn(minHeight = 48.dp).testTag("launcher_home_settings"),
                text = { Text("Réglages HOME") },
                onClick = { onDismiss(); openHomeSettings() },
            )
        }
    }
}

@Composable
private fun HomeFooterAction(label: String, description: String, tag: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    TextButton(
        modifier = modifier.fillMaxWidth().sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .semantics { contentDescription = description }
            .testTag(tag),
        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp),
        onClick = onClick,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun openHomeCard(
    action: HomeCardAction,
    openFocus: () -> Unit,
    openCoach: () -> Unit,
    openLibrary: () -> Unit,
) {
    when (action) {
        HomeCardAction.OPEN_FOCUS -> openFocus()
        HomeCardAction.OPEN_COACH -> openCoach()
        HomeCardAction.OPEN_LIBRARY -> openLibrary()
    }
}

private fun HomePrimaryAction.homeActionLabel(): String = when (this) {
    HomePrimaryAction.START_FOCUS -> "Préparer le focus"
    HomePrimaryAction.OPEN_COACH -> "Ouvrir le coach"
    HomePrimaryAction.NONE -> "Aucune action proposée"
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
        ) { Text(app.appLabel, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface) }
        TextButton(
            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .semantics {
                    contentDescription = if (pinned) "Retirer ${app.appLabel} des favoris" else "Épingler ${app.appLabel} parmi les favoris"
                },
            onClick = onTogglePin,
        ) { Text(if (pinned) "Favori" else "Épingler", color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
