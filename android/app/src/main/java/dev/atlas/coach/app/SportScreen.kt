package dev.atlas.coach.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.atlas.coach.app.ui.AtlasSection
import dev.atlas.coach.app.ui.AtlasSpacing
import dev.atlas.coach.app.ui.LocalAtlasSemanticColors
import dev.atlas.coach.training.PlannedExercise
import dev.atlas.coach.training.SportPlan
import dev.atlas.coach.training.SportPlanFixture
import dev.atlas.coach.training.SportPlanJson
import dev.atlas.coach.training.SportPlanSource
import dev.atlas.coach.training.SportPlanUiState
import dev.atlas.coach.training.SyncState

/**
 * Read-only sport surface. It shows a prescription and, separately, any
 * declared result. A planned session is never presented as performed.
 */
@Composable
internal fun SportScreen(
    state: SportPlanUiState? = null,
    demoMode: Boolean = false,
    busy: Boolean = false,
    message: String? = null,
    onRefresh: (() -> Unit)? = null,
    onToggleDemoMode: (() -> Unit)? = null,
) {
    var detailsExpanded by rememberSaveable { mutableStateOf(false) }
    val screenState = when {
        demoMode -> demoSportPlanState()
        state != null -> state
        else -> SportPlanUiState(plan = null, syncState = SyncState.UNAVAILABLE)
    }
    Column(verticalArrangement = Arrangement.spacedBy(AtlasSpacing.medium)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Sport", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "Une séance prévue n'est pas un résultat déclaré.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val statusColor = when {
                screenState.syncState == SyncState.STALE -> LocalAtlasSemanticColors.current.warning.onContainer
                screenState.syncState == SyncState.MISMATCH -> MaterialTheme.colorScheme.error
                screenState.syncState == SyncState.FRESH && screenState.hasPlannedSession() -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(
                sportStatusLabel(screenState),
                style = MaterialTheme.typography.titleSmall,
                color = statusColor,
            )
            message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                dev.atlas.coach.app.ui.AtlasSecondaryButton(
                    label = if (busy) "Chargement…" else "Actualiser",
                    enabled = !busy && onRefresh != null,
                    onClick = { onRefresh?.invoke() },
                )
                androidx.compose.material3.FilterChip(
                    selected = demoMode,
                    onClick = { onToggleDemoMode?.invoke() },
                    enabled = onToggleDemoMode != null,
                    label = { Text("Démo") },
                )
            }
        }

        val plan = screenState.plan
        if (plan != null) {
            SportPlannedSection(plan)
            SportPerformedSection(plan)
        }

        SportDetails(
            state = screenState,
            expanded = detailsExpanded,
            onToggle = { detailsExpanded = !detailsExpanded },
        )
    }
}

@Composable
private fun SportDetails(
    state: SportPlanUiState,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    TextButton(
        modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTag("sport_details_toggle"),
        onClick = onToggle,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Détails", style = MaterialTheme.typography.titleSmall)
            Text(if (expanded) "Masquer" else "Afficher", style = MaterialTheme.typography.labelMedium)
        }
    }
    if (expanded) {
        Text(
            "Aucune écriture wger n'est effectuée.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SportSourceAndSync(state)
    }
}

@Composable
private fun SportSourceAndSync(state: SportPlanUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "Source : ${sourceLabel(state.source)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Synchronisation : ${syncLabel(state.syncState)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Dernière synchronisation : ${state.lastSyncAt ?: "inconnue"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SportPlannedSection(plan: SportPlan) {
    AtlasSection("Séance prévue") {
        if (!plan.planned || plan.exercises.isEmpty()) {
            Text(
                "Aucune séance prévue aujourd'hui.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@AtlasSection
        }
        val title = plan.dayName?.takeIf(String::isNotBlank) ?: "Séance du jour"
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (plan.routineId != null) {
            Text(
                "Routine ${plan.routineId}${plan.dayId?.let { " · jour $it" }.orEmpty()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        plan.exercises.forEach { exercise ->
            SportExerciseRow(exercise)
        }
        if (plan.limits.truncated) {
            Text(
                "Liste tronquée à ${plan.limits.maxExercises} exercices.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SportExerciseRow(exercise: PlannedExercise) {
    val name = exercise.name?.takeIf(String::isNotBlank) ?: "Exercice ${exercise.exerciseId}"
    val prescription = formatPrescription(exercise)
    val accessibility = buildString {
        append(name).append(". ").append(prescription)
        exercise.lastPerformance?.let { append(". Dernière performance : ").append(it.performedAt) }
    }
    Card(
        modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 72.dp)
            .semantics(mergeDescendants = true) { contentDescription = accessibility },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = AtlasSpacing.medium, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            Text(prescription, style = MaterialTheme.typography.bodyMedium)
            exercise.lastPerformance?.let { last ->
                Text(
                    "Dernière performance connue · ${last.performedAt}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SportPerformedSection(plan: SportPlan) {
    AtlasSection("Résultats déclarés") {
        if (plan.performed.isEmpty()) {
            Text(
                "Aucun résultat déclaré. Une séance prévue n'est pas une séance effectuée.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@AtlasSection
        }
        plan.performed.forEach { result ->
            Row(
                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Résultat ${result.resultId}", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${result.performedAt} · ${result.source.name} · ${result.verification.name}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                result.durationMinutes?.let {
                    Text("$it min", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

internal fun formatPrescription(exercise: PlannedExercise): String = buildString {
    append(exercise.sets).append(" séries × ").append(exercise.reps).append(" répétitions")
    exercise.weightKg?.let { append(" · ").append(formatWeight(it)).append(" kg") }
    exercise.restSeconds?.let { append(" · repos ").append(it).append(" s") }
}

internal fun formatWeight(weightKg: Double): String =
    if (weightKg % 1.0 == 0.0) weightKg.toInt().toString() else String.format(java.util.Locale.ROOT, "%.1f", weightKg)

internal fun syncLabel(state: SyncState): String = when (state) {
    SyncState.FRESH -> "à jour"
    SyncState.STALE -> "périmée"
    SyncState.UNAVAILABLE -> "indisponible"
    SyncState.MISMATCH -> "incohérente"
}

internal fun sourceLabel(source: SportPlanSource): String = when (source) {
    SportPlanSource.WGER_READ -> "wger en lecture"
    SportPlanSource.FIXTURE -> "fixture de démonstration"
}

internal fun sourceLabel(plan: SportPlan): String = sourceLabel(plan.source)

private fun SportPlanUiState.hasPlannedSession(): Boolean =
    plan?.let { it.planned && it.exercises.isNotEmpty() } == true

private fun sportStatusLabel(state: SportPlanUiState): String = when (state.syncState) {
    SyncState.STALE -> "Données peut-être périmées"
    SyncState.MISMATCH -> "Format inattendu"
    SyncState.UNAVAILABLE -> "Aucune séance disponible"
    SyncState.FRESH -> if (state.hasPlannedSession()) "À jour" else "Aucune séance disponible"
}

private fun demoSportPlanState(): SportPlanUiState = when (val result = SportPlanJson.parse(SportPlanFixture.json)) {
    is dev.atlas.coach.training.SportPlanParseResult.Valid -> SportPlanUiState(
        plan = result.plan,
        syncState = result.plan.syncState,
        lastSyncAt = result.plan.generatedAt,
        source = result.plan.source,
    )
    is dev.atlas.coach.training.SportPlanParseResult.Invalid ->
        SportPlanUiState(plan = null, syncState = SyncState.MISMATCH, source = SportPlanSource.FIXTURE)
}
