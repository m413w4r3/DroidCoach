package dev.atlas.coach.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.atlas.coach.app.ui.AtlasRule
import dev.atlas.coach.usage.QuotaStatus
import dev.atlas.coach.usage.UsageReliability
import dev.atlas.coach.usage.UsageSummary
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun GlobalUsageScreen(
    state: GlobalUsageScreenState,
    onSaveConsent: (includePackageNames: Boolean) -> Unit,
    onOpenUsageAccessSettings: () -> Unit,
    onRefresh: () -> Unit,
    onRevokeConsent: () -> Unit,
    onSaveQuota: (limitMinutes: Int, enabled: Boolean) -> Unit,
) {
    var includePackageNames by remember(state.packageNamesConsented) {
        mutableStateOf(state.packageNamesConsented)
    }
    var quotaMinutes by remember(state.dailyQuota?.limitMs) {
        mutableStateOf(
            ((state.dailyQuota?.limitMs ?: DEFAULT_DAILY_QUOTA_MINUTES * 60_000L) / 60_000L).toString(),
        )
    }
    var quotaEnabled by remember(state.dailyQuota?.enabled) {
        mutableStateOf(state.dailyQuota?.enabled ?: true)
    }
    val quotaValue = quotaMinutes.toIntOrNull()
    val quotaValid = quotaValue != null && quotaValue in 1..GlobalUsageController.MAX_DAILY_QUOTA_MINUTES

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Usage global", style = MaterialTheme.typography.headlineSmall)
        Text(
            "ATLAS résume les transitions d’applications au premier plan pour toutes les applications. " +
                "Cette fonction ne lit aucun contenu d’écran, notification, URL ni message. Les durées sont estimées ; " +
                "Android peut retarder ou omettre des événements.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Accès Usage Access Android : ${if (state.usageAccessGranted) "autorisé" else "à autoriser dans Android"}",
            style = MaterialTheme.typography.titleMedium,
        )
        if (!state.usageAccessGranted) {
            OutlinedButton(
                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
                onClick = onOpenUsageAccessSettings,
            ) { Text("Ouvrir les réglages Usage Access") }
        }

        Text(
            if (state.hasGlobalConsent) {
                "Consentement global ATLAS actif. La collecte reste manuelle et est limitée à une fois toutes les 15 minutes."
            } else {
                "Le consentement ATLAS est séparé de l’autorisation Android. Il couvre l’observation globale et reste révocable."
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Checkbox(
                checked = includePackageNames,
                onCheckedChange = { includePackageNames = it },
            )
            Column(modifier = Modifier.weight(1f).padding(top = 10.dp)) {
                Text("Autoriser la catégorie package_names")
                Text(
                    "Affiche les noms d’applications. Sans cet accord, ATLAS affiche seulement un identifiant pseudonyme stable.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Button(
            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 52.dp),
            onClick = { onSaveConsent(includePackageNames) },
        ) {
            Text(if (state.hasGlobalConsent) "Enregistrer le consentement" else "Consentir à l’observation globale")
        }
        if (state.hasGlobalConsent) {
            OutlinedButton(
                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
                enabled = state.usageAccessGranted,
                onClick = onRefresh,
            ) { Text("Actualiser le résumé") }
            OutlinedButton(
                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
                onClick = onRevokeConsent,
            ) { Text("Révoquer et purger le résumé") }
        }
        observationStatusLabel(state.observationStatus)?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }

        AtlasRule()
        val summary = state.summary
        if (summary == null) {
            Text("Aucun résumé disponible. Autorisez Usage Access, donnez votre consentement, puis actualisez.")
        } else {
            SummarySection(summary, state.packageNamesConsented)
        }

        AtlasRule()
        Text("Quotas", style = MaterialTheme.typography.titleMedium)
        Text(
            "Quota quotidien global (minutes). Il est local, désactivable et évalué uniquement sur une fenêtre pleinement observée.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = quotaMinutes,
            onValueChange = { value ->
                if (value.length <= 4 && value.all(Char::isDigit)) quotaMinutes = value
            },
            label = { Text("Limite en minutes") },
            singleLine = true,
            isError = quotaMinutes.isNotEmpty() && !quotaValid,
            supportingText = {
                if (quotaMinutes.isNotEmpty() && !quotaValid) {
                    Text("La limite doit être comprise entre 1 et ${GlobalUsageController.MAX_DAILY_QUOTA_MINUTES} minutes.")
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Checkbox(checked = quotaEnabled, onCheckedChange = { quotaEnabled = it })
            Column(modifier = Modifier.weight(1f).padding(top = 10.dp)) {
                Text("Activer ce quota")
                Text(
                    "Un quota désactivé n’est pas évalué et n’influence pas la fenêtre de collecte.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Button(
            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
            enabled = quotaValid,
            onClick = { quotaValue?.let { onSaveQuota(it, quotaEnabled) } },
        ) { Text("Enregistrer le quota") }
        if (state.quotas.isEmpty()) {
            Text("Aucun quota enregistré.", style = MaterialTheme.typography.bodySmall)
        } else {
            state.quotas.forEach { evaluation ->
                Text(
                    "${evaluation.quotaId} · ${quotaStatusLabel(evaluation.status)} · " +
                        "${evaluation.observedMinutes?.let { "$it min" } ?: "donnée indisponible"} / ${evaluation.limitMinutes} min",
                )
            }
        }
    }
}

internal fun quotaStatusLabel(status: QuotaStatus): String = when (status) {
    QuotaStatus.DISABLED -> "Désactivé"
    else -> status.name
}

@Composable
private fun SummarySection(summary: UsageSummary, packageNamesConsented: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Fenêtre bornée · ${formatDate(summary.windowStart, summary.timezone)} – ${formatDate(summary.windowEnd, summary.timezone)}")
        Text("Usage total estimé : ${formatDuration(summary.totalForegroundMs)}", style = MaterialTheme.typography.titleMedium)
        Text("Dernière mise à jour : ${formatDate(summary.generatedAt, summary.timezone)}")
        val reliability = when {
            summary.coverage.truncated || summary.coverage.blindMs == null || summary.coverage.blindMs > 0L ->
                UsageReliability.LOW

            summary.apps.isEmpty() -> UsageReliability.MEDIUM
            else -> UsageReliability.entries[summary.apps.maxOf { it.reliability.ordinal }]
        }
        Text("Fiabilité globale : ${reliability.name}")
        val blind = summary.coverage.blindMs?.let(::formatDuration) ?: "inconnue"
        Text(
            "Couverture de la fenêtre : ${formatDuration(summary.coverage.observedMs)} interrogées, " +
                "$blind aveugles · ${summary.coverage.eventsSeen} événements" +
                if (summary.coverage.truncated) " · fenêtre ou résultats tronqués" else "",
            style = MaterialTheme.typography.bodySmall,
        )
        if (summary.apps.isEmpty()) {
            Text("Aucune transition d’application observée dans cette fenêtre.")
        } else {
            Text("Applications", style = MaterialTheme.typography.titleSmall)
            summary.apps.forEach { app ->
                val label = app.displayName.takeIf { packageNamesConsented } ?: app.appRef
                Text(
                    "$label · ${formatDuration(app.foregroundMs)} · ${app.launchCount} ouverture(s) · fiabilité ${app.reliability.name}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun observationStatusLabel(status: dev.atlas.coach.usage.GlobalUsageObservationStatus?): String? = when (status) {
    null -> null
    dev.atlas.coach.usage.GlobalUsageObservationStatus.PROCESSED -> "Résumé actualisé."
    dev.atlas.coach.usage.GlobalUsageObservationStatus.DISABLED -> "Consentement désactivé ; les données locales ont été purgées."
    dev.atlas.coach.usage.GlobalUsageObservationStatus.PERMISSION_REQUIRED -> "Usage Access est refusé ; aucune lecture n’a été effectuée."
    dev.atlas.coach.usage.GlobalUsageObservationStatus.BASELINE_ESTABLISHED -> "Point de départ établi ; aucune donnée antérieure au consentement n’est incluse."
    dev.atlas.coach.usage.GlobalUsageObservationStatus.RATE_LIMITED -> "Actualisation limitée : attendez 15 minutes entre les collectes."
}

private fun formatDate(timestampUtc: Long, timezone: String): String = runCatching {
    val formatter = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm", Locale.getDefault())
        .withZone(ZoneId.of(timezone))
    formatter.format(Instant.ofEpochMilli(timestampUtc))
}.getOrElse { "—" }

private const val DEFAULT_DAILY_QUOTA_MINUTES = 120

private fun formatDuration(durationMs: Long): String {
    val minutes = (durationMs.coerceAtLeast(0L) / 60_000L)
    return when {
        minutes == 0L && durationMs > 0L -> "moins d’une min"
        minutes < 60L -> "$minutes min"
        else -> "${minutes / 60L} h ${minutes % 60L} min"
    }
}
