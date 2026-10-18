/*
 * Accueil textuel ATLAS adapted from Olauncher@f8e8d6497c457734c7a3171d36374157678e1f33,
 * GPL-3.0, app/src/main/java/app/olauncher/ui/HomeFragment.kt. Uses Compose and leaves
 * launcher selection, app launching and emergency pause under the user's control.
 */
package dev.atlas.coach.app

import android.content.Context
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.atlas.coach.launcher.AndroidLauncherCatalog
import dev.atlas.coach.launcher.LauncherAppModel
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlinx.coroutines.delay

@Composable
internal fun LauncherHomeScreen(controller: AtlasController, context: Context, openSystemHome: () -> Unit) {
    val catalog = remember(context) { AndroidLauncherCatalog(context) }
    val apps = remember(catalog) { catalog.list() }
    var clock by remember { mutableStateOf(currentClockText()) }
    var showAllApps by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            clock = currentClockText()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("ATLAS", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, letterSpacing = 3.sp)
        Text(
            clock,
            modifier = Modifier.pointerInput(controller) {
                var totalX = 0f
                var totalY = 0f
                detectDragGestures(
                    onDragStart = { totalX = 0f; totalY = 0f },
                    onDragEnd = {
                        when {
                            abs(totalX) > abs(totalY) && totalX < -80f -> controller.navigate(AtlasController.SCREEN_FOCUS)
                            abs(totalX) > abs(totalY) && totalX > 80f -> controller.navigate(AtlasController.SCREEN_HABITS)
                            totalY < -80f -> showAllApps = true
                            totalY > 80f -> showAllApps = false
                        }
                    },
                ) { change, amount ->
                    totalX += amount.x
                    totalY += amount.y
                    change.consume()
                }
            },
            fontSize = 58.sp,
            fontWeight = FontWeight.Light,
            letterSpacing = 2.sp,
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth().clickable { controller.navigate(AtlasController.SCREEN_HOME) }.sizeIn(minHeight = 120.dp), verticalArrangement = Arrangement.Center) {
                Text("Directive du jour", modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(controller.directive.title, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(controller.directive.reason, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { controller.navigate(AtlasController.SCREEN_FOCUS) }) { Text("Focus") }
            TextButton(onClick = { controller.navigate(AtlasController.SCREEN_HABITS) }) { Text("Habitudes") }
        }
        Spacer(Modifier.height(4.dp))
        val pinnedApps = apps.filter { it.appPackage in controller.launcherPinnedPackages }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (showAllApps) "Applications" else "Épinglées", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { showAllApps = !showAllApps }) {
                Text(if (showAllApps) "Épinglées" else "Toutes les applications")
            }
        }
        val visibleApps = if (showAllApps) apps else pinnedApps
        if (visibleApps.isEmpty()) {
            Text(if (showAllApps) "Aucune application avec un raccourci d'accueil n'est visible." else "Épinglez une application après avoir ouvert la liste complète.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        visibleApps.forEach { app -> LauncherAppRow(app, app.appPackage in controller.launcherPinnedPackages, catalog, controller) }
        OutlinedButton(modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp), onClick = openSystemHome) {
            Text("Choisir un autre accueil Android")
        }
        Text("Liste locale des applications accessibles depuis un raccourci d'accueil. Aucun usage n'est observé.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LauncherAppRow(app: LauncherAppModel, pinned: Boolean, catalog: AndroidLauncherCatalog, controller: AtlasController) {
    Row(modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(modifier = Modifier.weight(1f), onClick = {
            if (!catalog.launch(app)) controller.reportMessage("Impossible d'ouvrir ${app.appLabel}.")
        }) { Text(app.appLabel, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Start) }
        TextButton(onClick = { controller.toggleLauncherPin(app.appPackage) }) { Text(if (pinned) "Épinglée" else "Épingler") }
    }
}

private fun currentClockText(): String = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
