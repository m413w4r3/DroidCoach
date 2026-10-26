package dev.atlas.coach.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.atlas.coach.app.ui.LocalAtlasSemanticColors
import dev.atlas.coach.habits.HabitHistory
import dev.atlas.coach.habits.HabitHistoryDay
import java.time.LocalDate

@Composable
internal fun HabitHistoryGrid(
    history: HabitHistory,
    today: LocalDate,
    compact: Boolean = false,
    createdDate: LocalDate? = null,
) {
    val days = if (compact) history.days.takeLast(7) else history.days
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(
            if (compact) "7 derniers jours" else "Historique · ${days.size} jours",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        days.chunked(7).forEach { week ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                week.forEach { day ->
                    HabitHistoryCell(
                        day = day,
                        today = today,
                        createdDate = createdDate,
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat((7 - week.size).coerceAtLeast(0)) { SpacerCell(Modifier.weight(1f)) }
            }
        }
        if (!compact) HabitHistoryLegend()
    }
}

@Composable
private fun HabitHistoryCell(day: HabitHistoryDay, today: LocalDate, createdDate: LocalDate?, modifier: Modifier = Modifier) {
    val state = day.uiState(today, createdDate)
    val scheme = MaterialTheme.colorScheme
    val semantic = LocalAtlasSemanticColors.current
    val (glyph, label, shape, color, contentColor, borderColor) = when (state) {
        HabitDayUiState.DONE -> Sextuple("✓", "validé", CircleShape, scheme.primaryContainer, scheme.onPrimaryContainer, null)
        HabitDayUiState.MISSED -> Sextuple("!", "dû et manqué", RoundedCornerShape(4.dp), scheme.errorContainer, scheme.onErrorContainer, null)
        HabitDayUiState.NOT_PLANNED -> Sextuple("–", "non planifié", CircleShape, scheme.surface, scheme.onSurfaceVariant, scheme.outline)
        HabitDayUiState.BEFORE_START -> Sextuple("·", "avant le début", CutCornerShape(2.dp), scheme.surface, scheme.onSurfaceVariant, scheme.outlineVariant)
        HabitDayUiState.DUE_TODAY -> Sextuple("·", "à faire aujourd'hui", CutCornerShape(6.dp), semantic.warning.container, semantic.warning.onContainer, null)
        HabitDayUiState.SCHEDULED -> Sextuple("›", "prévu", RoundedCornerShape(4.dp), scheme.surfaceVariant, scheme.onSurfaceVariant, scheme.outlineVariant)
    }
    Column(
        modifier = modifier.semantics {
            contentDescription = "${day.date}, $label${if (day.date == today) ", aujourd'hui" else ""}"
        },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Surface(
            modifier = Modifier.sizeIn(minWidth = 24.dp, minHeight = 24.dp),
            shape = shape,
            color = color,
            contentColor = contentColor,
            border = borderColor?.let { BorderStroke(1.dp, it) },
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)) {
                Text(glyph, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
            }
        }
        Text(day.date.dayOfMonth.toString(), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
    }
}

@Composable
private fun SpacerCell(modifier: Modifier) {
    Box(modifier = modifier)
}

@Composable
private fun HabitHistoryLegend() {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Légende", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("✓ Validé", style = MaterialTheme.typography.bodySmall)
            Text("! Dû et manqué", style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("– Non planifié", style = MaterialTheme.typography.bodySmall)
            Text("· À faire aujourd'hui", style = MaterialTheme.typography.bodySmall)
        }
        Text("› À venir", style = MaterialTheme.typography.bodySmall)
        Text("Avant le début · non compté", style = MaterialTheme.typography.bodySmall)
    }
}

private data class Sextuple<A, B, C, D, E, F>(val first: A, val second: B, val third: C, val fourth: D, val fifth: E, val sixth: F)
