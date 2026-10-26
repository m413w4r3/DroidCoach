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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.atlas.coach.app.ui.AtlasSection
import dev.atlas.coach.app.ui.AtlasSpacing
import dev.atlas.coach.app.ui.AtlasStatus
import dev.atlas.coach.app.ui.AtlasStatusKind
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
) {
    val screenState = when {
        demoMode -> demoSportPlanState()
        state != null -> state
        else -> SportPlanUiState(plan = null, syncState = SyncState.UNAVAILABLE)
    }
    Column(verticalArrangement = Arrangement.spacedBy(AtlasSpacing.medium)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Sport", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "Séance prévue et résultats déclarés restent séparés. Aucune écriture wger n'est effectuée.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SportSourceAndSync(screenState)
        SportSyncBanner(screenState.syncState)

        val plan = screenState.plan
        if (plan == null) {
            SportErrorCard(availabilityMessage(screenState.syncState))
            return@Column
        }

        SportPlannedSection(plan)
        SportPerformedSection(plan)

        Text(
            "Source : ${sourceLabel(screenState.source)} · ${syncLabel(screenState.syncState)}. " +
                "Une séance planifiée n'est marquée effectuée qu'avec un résultat déclaré.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SportSourceAndSync(state: SportPlanUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "Source : ${sourceLabel(state.source)} · synchronisation ${syncLabel(state.syncState)}",
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
private fun SportSyncBanner(syncState: SyncState) {
    if (syncState == SyncState.FRESH) return
    val semantic = LocalAtlasSemanticColors.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = semantic.warning.container),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(AtlasSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "Synchronisation sportive : ${syncLabel(syncState)}",
                style = MaterialTheme.typography.titleSmall,
                color = semantic.warning.onContainer,
            )
            Text(
                "La séance affichée peut être incomplète ou périmée. Aucun résultat n'est inventé.",
                style = MaterialTheme.typography.bodySmall,
                color = semantic.warning.onContainer,
            )
        }
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

@Composable
private fun SportErrorCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Text(
            message,
            modifier = Modifier.padding(AtlasSpacing.medium),
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
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

private fun availabilityMessage(state: SyncState): String = when (state) {
    SyncState.FRESH -> "Aucune séance prévue pour cette date."
    SyncState.STALE -> "Les données sportives sont périmées. Réessayez plus tard."
    SyncState.UNAVAILABLE -> "Les données sportives sont indisponibles. Réessayez plus tard."
    SyncState.MISMATCH -> "La réponse sportive ne correspond pas au contrat attendu."
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
