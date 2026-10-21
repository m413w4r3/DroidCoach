package dev.atlas.coach.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
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
    val foreground = when (kind) {
        AtlasStatusKind.ACTIVE, AtlasStatusKind.AUTHORIZED -> colors.primary
        AtlasStatusKind.SIMULATED -> colors.onSurfaceVariant
        AtlasStatusKind.UNAVAILABLE -> colors.error
    }
    Row(
        modifier = modifier.semantics { contentDescription = "État : $label" },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 2.dp, height = 16.dp).background(foreground))
        Text(label, style = androidx.compose.material3.MaterialTheme.typography.labelMedium, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun AtlasSection(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AtlasSpacing.small)) {
        Text(title, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
        AtlasRule()
        content()
    }
}

@Composable
fun AtlasRule(modifier: Modifier = Modifier, color: Color = androidx.compose.material3.MaterialTheme.colorScheme.outline.copy(alpha = 0.42f)) {
    Spacer(modifier.fillMaxWidth().height(1.dp).background(color))
}

/** An original, small botanical line drawing used as ATLAS's engraved signature. */
@Composable
fun AtlasEngravingMark(modifier: Modifier = Modifier) {
    val ink = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
    Canvas(modifier.size(44.dp).semantics { invisibleToUser() }) {
        val strokeWidth = 0.9.dp.toPx()
        val stem = Path().apply {
            moveTo(size.width * 0.18f, size.height * 0.87f)
            cubicTo(size.width * 0.43f, size.height * 0.66f, size.width * 0.51f, size.height * 0.34f, size.width * 0.79f, size.height * 0.12f)
        }
        drawPath(stem, ink, style = Stroke(width = strokeWidth))

        fun leaf(startX: Float, startY: Float, tipX: Float, tipY: Float, width: Float, side: Float) {
            val dx = tipX - startX
            val dy = tipY - startY
            val length = kotlin.math.hypot(dx, dy).coerceAtLeast(1f)
            val nx = -dy / length * width * side
            val ny = dx / length * width * side
            val leafPath = Path().apply {
                moveTo(startX, startY)
                cubicTo(startX + dx * 0.28f + nx, startY + dy * 0.28f + ny, startX + dx * 0.68f + nx, startY + dy * 0.68f + ny, tipX, tipY)
                cubicTo(startX + dx * 0.72f - nx * 0.65f, startY + dy * 0.72f - ny * 0.65f, startX + dx * 0.30f - nx * 0.45f, startY + dy * 0.30f - ny * 0.45f, startX, startY)
                close()
            }
            drawPath(leafPath, ink, style = Stroke(width = strokeWidth))
            drawLine(ink, androidx.compose.ui.geometry.Offset(startX, startY), androidx.compose.ui.geometry.Offset(tipX, tipY), strokeWidth * 0.75f)
            for (index in 1..3) {
                val t = index / 4f
                val x = startX + dx * t
                val y = startY + dy * t
                val hatch = width * (1f - t) * 0.28f
                drawLine(
                    ink.copy(alpha = 0.78f),
                    androidx.compose.ui.geometry.Offset(x - dy / length * hatch, y + dx / length * hatch),
                    androidx.compose.ui.geometry.Offset(x + dy / length * hatch * 0.6f, y - dx / length * hatch * 0.6f),
                    strokeWidth * 0.55f,
                )
            }
        }

        leaf(size.width * 0.39f, size.height * 0.64f, size.width * 0.15f, size.height * 0.53f, size.width * 0.16f, 1f)
        leaf(size.width * 0.48f, size.height * 0.51f, size.width * 0.73f, size.height * 0.54f, size.width * 0.14f, -1f)
        leaf(size.width * 0.59f, size.height * 0.36f, size.width * 0.36f, size.height * 0.23f, size.width * 0.14f, 1f)
        leaf(size.width * 0.69f, size.height * 0.23f, size.width * 0.91f, size.height * 0.29f, size.width * 0.12f, -1f)
        drawCircle(ink, radius = strokeWidth * 1.2f, center = androidx.compose.ui.geometry.Offset(size.width * 0.79f, size.height * 0.12f))
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
    val cardColors = CardDefaults.cardColors(containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerLow)
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
            Column(modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars)) {
                    AtlasRule(color = androidx.compose.material3.MaterialTheme.colorScheme.outline.copy(alpha = 0.55f))
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
    val largeSystemText = LocalDensity.current.fontScale >= 1.5f
    val titleStyle = androidx.compose.material3.MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Serif)
    Column(modifier = Modifier.fillMaxWidth()) {
        if (largeSystemText) {
            Column(
                modifier = Modifier.fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    title,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    style = titleStyle,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
                    softWrap = true,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (canGoBack) {
                        TextButton(onClick = onBack, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                            Text("Retour")
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    EmergencyPauseButton(emergencyPaused, onEmergencyPause)
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (canGoBack) {
                    TextButton(onClick = onBack, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                        Text("Retour")
                    }
                }
                Text(
                    title,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    style = titleStyle,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
                    softWrap = true,
                )
                EmergencyPauseButton(emergencyPaused, onEmergencyPause)
            }
        }
        AtlasRule(
            modifier = Modifier.padding(horizontal = 14.dp),
            color = androidx.compose.material3.MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
        )
    }
}

@Composable
private fun EmergencyPauseButton(emergencyPaused: Boolean, onEmergencyPause: () -> Unit) {
    TextButton(
        onClick = onEmergencyPause,
        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics {
            contentDescription = if (emergencyPaused) "Terminer la pause d'urgence" else "Activer la pause d'urgence de 30 minutes"
        },
        colors = ButtonDefaults.textButtonColors(contentColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant),
    ) {
        Text(if (emergencyPaused) "Fin" else "Urgence")
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
        colors = ButtonDefaults.textButtonColors(contentColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurface),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, maxLines = 1, overflow = TextOverflow.Clip, fontWeight = if (active == destination) FontWeight.SemiBold else FontWeight.Normal)
            androidx.compose.foundation.layout.Box(
                Modifier.width(20.dp).height(1.dp).background(
                    if (active == destination) androidx.compose.material3.MaterialTheme.colorScheme.primary
                    else Color.Transparent,
                ),
            )
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
