package dev.atlas.coach.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.core.view.WindowCompat

object AtlasSpacing {
    val xSmall = 4.dp
    val small = 8.dp
    val medium = 16.dp
    val large = 24.dp
    val page = 20.dp
    val section = 24.dp
}

enum class AtlasStatusKind { ACTIVE, SIMULATED, AUTHORIZED, UNAVAILABLE }

@Composable
fun AtlasStatus(label: String, kind: AtlasStatusKind, modifier: Modifier = Modifier) {
    val colors = androidx.compose.material3.MaterialTheme.colorScheme
    val background = when (kind) {
        AtlasStatusKind.ACTIVE, AtlasStatusKind.AUTHORIZED -> colors.primaryContainer
        AtlasStatusKind.SIMULATED -> colors.surfaceVariant
        AtlasStatusKind.UNAVAILABLE -> colors.errorContainer
    }
    val foreground = when (kind) {
        AtlasStatusKind.ACTIVE, AtlasStatusKind.AUTHORIZED -> colors.onPrimaryContainer
        AtlasStatusKind.SIMULATED -> colors.onSurfaceVariant
        AtlasStatusKind.UNAVAILABLE -> colors.onErrorContainer
    }
    Surface(
        modifier = modifier.semantics { contentDescription = "État : $label" },
        color = background,
        contentColor = foreground,
        shape = androidx.compose.material3.MaterialTheme.shapes.small,
    ) {
        Text(label, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun AtlasSection(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AtlasSpacing.small)) {
        Text(title, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable
fun AtlasSettingRow(
    title: String,
    description: String? = null,
    status: String? = null,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val cardModifier = modifier.fillMaxWidth().sizeIn(minHeight = 56.dp)
    val cardColors = CardDefaults.cardColors(containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant)
    val cardContent: @Composable ColumnScope.() -> Unit = {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = AtlasSpacing.medium, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(AtlasSpacing.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = androidx.compose.material3.MaterialTheme.typography.bodyLarge)
                description?.let { Text(it, style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant) }
                status?.let { Text(it, style = androidx.compose.material3.MaterialTheme.typography.labelMedium, color = androidx.compose.material3.MaterialTheme.colorScheme.primary) }
            }
            trailing()
        }
    }
    if (onClick == null) {
        Card(modifier = cardModifier, colors = cardColors, content = cardContent)
    } else {
        Card(modifier = cardModifier, onClick = onClick, colors = cardColors, content = cardContent)
    }
}

@Composable
fun AtlasPrimaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(modifier = modifier.fillMaxWidth().sizeIn(minHeight = 52.dp).semantics { contentDescription = label }, enabled = enabled, onClick = onClick) {
        Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun AtlasSecondaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(modifier = modifier.sizeIn(minHeight = 48.dp).semantics { contentDescription = label }, enabled = enabled, onClick = onClick) {
        Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun AtlasTertiaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    TextButton(modifier = modifier.sizeIn(minHeight = 48.dp).semantics { contentDescription = label }, enabled = enabled, onClick = onClick) {
        Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Common edge-to-edge host for every top-level screen, including long settings forms. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AtlasScreenFrame(
    title: String,
    activeDestination: String,
    canGoBack: Boolean,
    emergencyPaused: Boolean,
    onBack: () -> Unit,
    onEmergencyPause: () -> Unit,
    onNavigate: (String) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val view = LocalView.current
    val activity = androidx.compose.runtime.remember(view) { view.context.findActivity() }
    val darkTheme = isSystemInDarkTheme()
    SideEffect {
        activity?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    val fontScale = LocalDensity.current.fontScale
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            AtlasTopBar(title, canGoBack, emergencyPaused, onBack, onEmergencyPause)
        },
        bottomBar = {
            Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.surface) {
                Column(modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars)) {
                    if (fontScale >= 1.6f) {
                        Row(Modifier.fillMaxWidth()) {
                            AtlasNavItem("Tableau", AtlasRoutes.HOME, activeDestination, onNavigate)
                            AtlasNavItem("Focus", AtlasRoutes.FOCUS, activeDestination, onNavigate)
                        }
                        Row(Modifier.fillMaxWidth()) {
                            AtlasNavItem("Habitudes", AtlasRoutes.HABITS, activeDestination, onNavigate)
                            AtlasNavItem("Plus", AtlasRoutes.MORE, activeDestination, onNavigate)
                        }
                    } else {
                        Row(Modifier.fillMaxWidth()) {
                            AtlasNavItem("Tableau", AtlasRoutes.HOME, activeDestination, onNavigate)
                            AtlasNavItem("Focus", AtlasRoutes.FOCUS, activeDestination, onNavigate)
                            AtlasNavItem("Habitudes", AtlasRoutes.HABITS, activeDestination, onNavigate)
                            AtlasNavItem("Plus", AtlasRoutes.MORE, activeDestination, onNavigate)
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        ) {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                    .imePadding().padding(horizontal = AtlasSpacing.page, vertical = AtlasSpacing.medium),
                verticalArrangement = Arrangement.spacedBy(AtlasSpacing.medium),
                content = content,
            )
        }
    }
}

@Composable
private fun AtlasTopBar(
    title: String,
    canGoBack: Boolean,
    emergencyPaused: Boolean,
    onBack: () -> Unit,
    onEmergencyPause: () -> Unit,
) {
    Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.background) {
        Row(
            modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (canGoBack) {
                TextButton(onClick = onBack, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) { Text("Retour") }
            }
            Text(
                title,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Clip,
            )
            TextButton(
                onClick = onEmergencyPause,
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics {
                    contentDescription = if (emergencyPaused) "Terminer la pause d'urgence" else "Activer la pause d'urgence de 30 minutes"
                },
                colors = ButtonDefaults.textButtonColors(contentColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant),
            ) { Text(if (emergencyPaused) "Fin" else "Urgence", maxLines = 1) }
        }
    }
}

object AtlasRoutes {
    const val HOME = "home"
    const val FOCUS = "focus"
    const val HABITS = "habits"
    const val MORE = "more"
}

@Composable
private fun RowScope.AtlasNavItem(label: String, destination: String, active: String, onNavigate: (String) -> Unit) {
    TextButton(
        onClick = { onNavigate(destination) },
        modifier = Modifier.weight(1f).sizeIn(minHeight = 56.dp).semantics {
            contentDescription = "Ouvrir $label"
            role = Role.Tab
            selected = active == destination
        },
        colors = ButtonDefaults.textButtonColors(
            contentColor = if (active == destination) androidx.compose.material3.MaterialTheme.colorScheme.primary else androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Text(label, maxLines = 1, overflow = TextOverflow.Clip, fontWeight = if (active == destination) FontWeight.SemiBold else FontWeight.Normal)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
