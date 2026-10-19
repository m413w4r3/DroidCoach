/*
 * Compose launcher for ATLAS, refondu au lot G à partir de l'adaptation
 * historique d'Olauncher@f8e8d6497c457734c7a3171d36374157678e1f33
 * (GPL-3.0, ui/HomeFragment.kt). Le lot G ajoute une présentation ATLAS sans
 * nouvelle copie de code ou d'asset tiers. Sources et licence :
 * THIRD_PARTY_NOTICES.md et docs/AUDIT_CIBLE.md.
 */
package dev.atlas.coach.app

import android.content.Context
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.atlas.coach.app.ui.AtlasPrimaryButton
import dev.atlas.coach.app.ui.AtlasSection
import dev.atlas.coach.launcher.AndroidLauncherCatalog
import dev.atlas.coach.launcher.LauncherAppModel
import java.text.Normalizer
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
internal fun LauncherHomeScreen(controller: AtlasController, context: Context, openSystemHome: () -> Unit) {
    val catalog = remember(context) { AndroidLauncherCatalog(context) }
    val lifecycleOwner = LocalLifecycleOwner.current
    var refreshToken by remember { mutableIntStateOf(0) }
    var apps by remember { mutableStateOf<List<LauncherAppModel>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf(false) }
    var clock by remember { mutableStateOf(currentClockText()) }
    var search by remember { mutableStateOf("") }
    var showAllApps by remember { mutableStateOf(false) }
    val density = LocalDensity.current

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

    val pinnedApps = apps.filter { it.appPackage in controller.launcherPinnedPackages }
    val filteredApps = apps.filter { app -> normalize(app.appLabel).contains(normalize(search).trim()) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(
            modifier = Modifier.pointerInput(density, showAllApps) {
                var totalY = 0f
                detectVerticalDragGestures(
                    onDragStart = { totalY = 0f },
                    onDragEnd = {
                        val threshold = with(density) { 72.dp.toPx() }
                        if (totalY < -threshold) showAllApps = true
                        if (totalY > threshold) showAllApps = false
                    },
                    onVerticalDrag = { change, amount ->
                        totalY += amount
                        change.consume()
                    },
                )
            }.testTag("launcher_swipe_header"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("ATLAS · accueil", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, letterSpacing = 2.sp)
            Text(clock, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Light, letterSpacing = 1.sp)
            Text(LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRANCE)).replaceFirstChar { it.uppercase(Locale.FRANCE) }, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Glissez vers le haut pour ouvrir la liste des applications.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        AtlasSection("Directive du jour") {
            Text(controller.directive.title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
            Text(controller.directive.reason, color = MaterialTheme.colorScheme.onSurfaceVariant)
            AtlasPrimaryButton("Revenir au coach", onClick = { controller.navigationState.replace(AtlasScreenId.HOME) })
        }

        AtlasSection("Applications favorites") {
            if (pinnedApps.isEmpty()) {
                Text("Aucun favori pour le moment. Ouvrez la liste complète et choisissez les applications que vous utilisez.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                pinnedApps.forEach { app ->
                    TextButton(
                        modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)
                            .semantics { contentDescription = "Ouvrir l'application favorite ${app.appLabel}" },
                        onClick = { launchApp(app, catalog, controller) },
                    ) { Text(app.appLabel, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start) }
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (showAllApps) "Toutes les applications" else "Épinglées", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { showAllApps = !showAllApps }) { Text(if (showAllApps) "Favoris" else "Tout voir") }
        }
        if (showAllApps) {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth().testTag("launcher_search"),
                value = search,
                onValueChange = { search = it },
                label = { Text("Rechercher une application") },
                placeholder = { Text("Nom de l'application") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )
            when {
                loading -> Text("Chargement des applications…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                loadError -> Text("La liste n'a pas pu être chargée. Revenez ici pour réessayer.", color = MaterialTheme.colorScheme.error)
                apps.isEmpty() -> Text("Aucune application avec un raccourci d'accueil n'est visible.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                filteredApps.isEmpty() -> Text("Aucune application ne correspond à cette recherche.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> filteredApps.forEach { app -> LauncherAppRow(app, app.appPackage in controller.launcherPinnedPackages, catalog, controller) }
            }
        }

        OutlinedButton(modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp), onClick = openSystemHome) {
            Text("Ouvrir le launcher système", maxLines = 2)
        }
        Text("Le launcher système et les applications essentielles restent accessibles. Aucun usage n'est observé ici.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
    }

}

@Composable
private fun LauncherAppRow(app: LauncherAppModel, pinned: Boolean, catalog: AndroidLauncherCatalog, controller: AtlasController) {
    Row(modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(
            modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp)
                .semantics { contentDescription = "Ouvrir ${app.appLabel}" },
            onClick = { launchApp(app, catalog, controller) },
        ) { Text(app.appLabel, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start) }
        TextButton(
            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .semantics { contentDescription = if (pinned) "Retirer ${app.appLabel} des favoris" else "Épingler ${app.appLabel} parmi les favoris" },
            onClick = { controller.toggleLauncherPin(app.appPackage) },
        ) { Text(if (pinned) "Épinglée" else "Épingler", maxLines = 1) }
    }
}

private fun launchApp(app: LauncherAppModel, catalog: AndroidLauncherCatalog, controller: AtlasController) {
    if (!catalog.launch(app)) controller.reportMessage("Impossible d'ouvrir ${app.appLabel}.")
}

private fun normalize(value: String): String = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
    .replace("\\p{Mn}+".toRegex(), "")

private fun currentClockText(): String = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm", Locale.FRANCE))
