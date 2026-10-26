package dev.atlas.coach.app

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.atlas.coach.app.ui.AtlasPrimaryButton
import dev.atlas.coach.app.ui.AtlasSecondaryButton
import dev.atlas.coach.app.ui.AtlasSection
import dev.atlas.coach.app.ui.AtlasStatus
import dev.atlas.coach.app.ui.AtlasStatusKind
import dev.atlas.coach.app.ui.AtlasTertiaryButton
import dev.atlas.coach.calendar.AndroidCalendarProvider
import dev.atlas.coach.calendar.CalendarDescriptor
import dev.atlas.coach.calendar.CalendarPermissionState
import dev.atlas.coach.calendar.CalendarReadConsentStore
import dev.atlas.coach.calendar.CalendarReadService
import dev.atlas.coach.calendar.ConsentGatedCalendarReadService
import dev.atlas.coach.calendar.EtarProbe
import dev.atlas.coach.calendar.SharedPreferencesCalendarReadConsentStore
import dev.atlas.coach.calendar.write.AndroidCalendarContractWriteProvider
import dev.atlas.coach.calendar.write.CalendarWriteCatalog
import dev.atlas.coach.calendar.write.CalendarWriteError
import dev.atlas.coach.calendar.write.CalendarWriteService
import dev.atlas.coach.calendar.write.InMemoryCalendarWriteJournal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class CalendarPermissionUiStatus {
    GRANTED,
    DENIED,
    NOT_DECLARED,
    UNAVAILABLE,
}

internal data class CalendarDiagnosticRow(
    val displayName: String,
    val accountLabel: String,
    val accessLabel: String,
    val visibilityLabel: String,
)

internal data class CalendarPermissionScreenState(
    val permission: CalendarPermissionState = CalendarPermissionState.UNKNOWN,
    val consentGranted: Boolean = false,
    val loading: Boolean = true,
    val catalogLoaded: Boolean = false,
    val calendars: List<CalendarDiagnosticRow> = emptyList(),
    val etarInstalled: Boolean? = null,
    val providerUnavailable: Boolean = false,
    val consentStorageUnavailable: Boolean = false,
)

internal data class CalendarWriteDiagnosticState(
    val loading: Boolean = true,
    val catalog: CalendarWriteCatalog? = null,
    val selectionError: CalendarWriteError? = null,
)

internal fun CalendarPermissionState.toUiStatus(): CalendarPermissionUiStatus = when (this) {
    CalendarPermissionState.GRANTED -> CalendarPermissionUiStatus.GRANTED
    CalendarPermissionState.DENIED -> CalendarPermissionUiStatus.DENIED
    CalendarPermissionState.NOT_DECLARED -> CalendarPermissionUiStatus.NOT_DECLARED
    CalendarPermissionState.UNKNOWN -> CalendarPermissionUiStatus.UNAVAILABLE
}

internal fun mayReadCalendarDiagnostics(
    permission: CalendarPermissionState,
    consentGranted: Boolean,
): Boolean = permission == CalendarPermissionState.GRANTED && consentGranted

internal fun shouldLaunchCalendarPermissionRequest(
    permission: CalendarPermissionState,
    consentGranted: Boolean,
    userExplicitlyRequested: Boolean,
): Boolean = userExplicitlyRequested && consentGranted && permission == CalendarPermissionState.DENIED

internal fun CalendarDescriptor.toDiagnosticRow(): CalendarDiagnosticRow = CalendarDiagnosticRow(
    displayName = diagnosticCalendarName(),
    // Account values may be email addresses or other identifiers; they never enter the UI model.
    accountLabel = "Compte expurgé",
    accessLabel = accessLevelLabel(accessLevel),
    visibilityLabel = if (visible) "Visible" else "Masqué",
)

private val emailIdentifierPattern = Regex("\\S+@\\S+")

private fun CalendarDescriptor.diagnosticCalendarName(): String = when {
    displayName.isBlank() -> "Calendrier sans nom"
    emailIdentifierPattern.containsMatchIn(displayName) -> "Calendrier expurgé"
    matchesAccountIdentifier(displayName, accountName) -> "Calendrier expurgé"
    matchesAccountIdentifier(displayName, ownerAccount) -> "Calendrier expurgé"
    else -> displayName
}

private fun matchesAccountIdentifier(displayName: String, accountIdentifier: String?): Boolean =
    accountIdentifier != null && displayName.trim().equals(accountIdentifier.trim(), ignoreCase = true)

private fun accessLevelLabel(accessLevel: Int): String = when (accessLevel) {
    in 0..99 -> "Aucun accès"
    in 100..199 -> "Disponibilités uniquement"
    in 200..299 -> "Lecture"
    in 300..399 -> "Lecture et réponse"
    in 400..499 -> "Lecture et gestion des réponses"
    in 500..599 -> "Contributeur"
    in 600..699 -> "Éditeur"
    in 700..799 -> "Propriétaire"
    else -> "Administrateur"
}

/** Diagnostic non connecté à la navigation globale; no events or account identifiers are shown. */
@Composable
fun CalendarPermissionScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val provider = remember(context) { AndroidCalendarProvider(context) }
    val service = remember(provider) { CalendarReadService(provider) }
    val consentStore: CalendarReadConsentStore = remember(context) {
        SharedPreferencesCalendarReadConsentStore(context)
    }
    val gatedService = remember(service, consentStore) {
        ConsentGatedCalendarReadService(service, consentStore)
    }
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(CalendarPermissionScreenState()) }
    var refreshGeneration by remember { mutableIntStateOf(0) }
    val writeProvider = remember(context) { AndroidCalendarContractWriteProvider(context) }
    val writeService = remember(writeProvider) {
        CalendarWriteService(writeProvider, InMemoryCalendarWriteJournal())
    }
    var writeDiagnostic by remember { mutableStateOf(CalendarWriteDiagnosticState()) }

    fun refresh() {
        refreshGeneration++
        val generation = refreshGeneration
        scope.launch {
            state = state.copy(loading = true)
            val refreshedState = withContext(Dispatchers.IO) {
                loadCalendarPermissionScreenState(provider, gatedService, consentStore)
            }
            if (refreshGeneration == generation) state = refreshedState
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        // The result is re-read from PackageManager; no read follows a denied result.
        refresh()
    }

    LaunchedEffect(provider, consentStore) { refresh() }

    LaunchedEffect(writeService, state.permission, state.consentGranted) {
        writeDiagnostic = if (state.consentGranted && state.permission == CalendarPermissionState.GRANTED) {
            withContext(Dispatchers.IO) {
                val catalog = writeService.inspectCatalog()
                CalendarWriteDiagnosticState(
                    loading = false,
                    catalog = catalog,
                    selectionError = writeService.selectCalendar(catalog, null).error,
                )
            }
        } else {
            CalendarWriteDiagnosticState(loading = false)
        }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Calendrier", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Ce diagnostic affiche uniquement les noms de calendriers, un compte expurgé, " +
                    "le niveau d’accès et la visibilité. Il ne consulte ni n’affiche aucun événement et n’écrit rien.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        AtlasSection("Consentement ATLAS") {
            Text("Le consentement local est distinct de la permission Android et peut être révoqué ici.")
            if (state.consentGranted) {
                AtlasStatus("Consentement donné sur cet appareil", AtlasStatusKind.AUTHORIZED)
                AtlasTertiaryButton(
                    label = "Révoquer le consentement calendrier",
                    onClick = {
                        refreshGeneration++
                        val permission = state.permission
                        state = CalendarPermissionScreenState(
                            permission = permission,
                            consentGranted = false,
                            loading = true,
                        )
                        scope.launch {
                            val revoked = try {
                                withContext(Dispatchers.IO) { consentStore.revoke() }
                            } catch (_: Exception) {
                                false
                            }
                            state = state.copy(loading = false, consentStorageUnavailable = !revoked)
                        }
                    },
                )
            } else {
                AtlasPrimaryButton(
                    label = "Consentir à la lecture des noms de calendriers",
                    enabled = !state.loading,
                    onClick = {
                        scope.launch {
                            val granted = try {
                                withContext(Dispatchers.IO) { consentStore.grant() }
                            } catch (_: Exception) {
                                false
                            }
                            if (granted) refresh()
                            else state = state.copy(consentStorageUnavailable = true, loading = false)
                        }
                    },
                )
            }
            if (state.consentStorageUnavailable) {
                Text("Le choix n’a pas pu être enregistré. Aucun calendrier n’a été lu.", color = MaterialTheme.colorScheme.error)
            }
        }

        AtlasSection("Permission Android") {
            Text("État : ${state.permission.toUiStatus().permissionLabel()}")
            when (state.permission.toUiStatus()) {
                CalendarPermissionUiStatus.GRANTED -> Text("Android autorise la lecture; le consentement ATLAS reste requis.")
                CalendarPermissionUiStatus.DENIED -> Text("La demande n’est envoyée qu’après votre action explicite.")
                CalendarPermissionUiStatus.NOT_DECLARED -> Text("READ_CALENDAR n’est pas déclaré dans cette version; aucune demande ne sera lancée.")
                CalendarPermissionUiStatus.UNAVAILABLE -> Text("L’état de la permission n’a pas pu être vérifié.")
            }
            if (state.permission.toUiStatus() == CalendarPermissionUiStatus.DENIED) {
                AtlasPrimaryButton(
                    label = "Autoriser la lecture du calendrier",
                    enabled = state.consentGranted && !state.loading,
                    onClick = {
                        if (shouldLaunchCalendarPermissionRequest(
                                permission = state.permission,
                                consentGranted = state.consentGranted,
                                userExplicitlyRequested = true,
                            )
                        ) {
                            permissionLauncher.launch(Manifest.permission.READ_CALENDAR)
                        }
                    },
                )
            }
            AtlasSecondaryButton(label = "Actualiser l’état", enabled = !state.loading, onClick = ::refresh)
        }

        AtlasSection("Calendriers détectés") {
            when {
                !state.consentGranted -> Text("Donnez d’abord le consentement ATLAS pour consulter la liste.")
                state.permission != CalendarPermissionState.GRANTED -> Text("La liste reste masquée tant que la permission Android n’est pas accordée.")
                state.loading -> Text("Vérification en cours…")
                state.providerUnavailable -> Text("Le fournisseur de calendrier est indisponible. Aucune donnée personnelle n’a été enregistrée.")
                !state.catalogLoaded -> Text("La liste n’a pas été consultée.")
                state.calendars.isEmpty() -> Text("Aucun calendrier lisible et visible n’a été détecté.")
                else -> state.calendars.forEach { calendar ->
                    AtlasSection(calendar.displayName) {
                        Text(calendar.accountLabel)
                        Text("Accès : ${calendar.accessLabel}")
                        Text("Visibilité : ${calendar.visibilityLabel}")
                    }
                }
            }
            if (state.etarInstalled != null && state.consentGranted && state.permission == CalendarPermissionState.GRANTED) {
                Text(
                    if (state.etarInstalled == true) {
                        "Etar détectée (ws.xsoh.etar ou ws.xsoh.etar.debug)."
                    } else {
                        "Etar non détectée (ws.xsoh.etar, ws.xsoh.etar.debug); la visibilité Android peut limiter cette vérification."
                    },
                )
            }
        }

        AtlasSection("Écriture calendrier (désactivée par défaut)") {
            AtlasStatus("Écriture désactivée par défaut", AtlasStatusKind.SIMULATED)
            Text(
                "Le moteur d'écriture est compilé et testé sur provider simulé. " +
                    "Aucune écriture réelle n'est effectuée par cet écran.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!state.consentGranted || state.permission != CalendarPermissionState.GRANTED) {
                Text("Le diagnostic d'écriture suit le consentement ATLAS et READ_CALENDAR; aucune écriture ne sera tentée.")
            } else if (writeDiagnostic.loading) {
                Text("Vérification en cours…")
            } else {
                val catalog = writeDiagnostic.catalog
                if (catalog == null) {
                    Text("Le fournisseur d'écriture est indisponible. Aucune écriture ne sera tentée.")
                } else {
                    val writeState = when (catalog.permissions.write) {
                        CalendarPermissionState.GRANTED -> "accordée"
                        CalendarPermissionState.DENIED -> "non accordée"
                        CalendarPermissionState.NOT_DECLARED -> "non déclarée"
                        CalendarPermissionState.UNKNOWN -> "inconnue"
                    }
                    Text("WRITE_CALENDAR : $writeState")
                    val localWritable = catalog.calendars.count {
                        it.accountType == "LOCAL" && it.visible && it.accessLevel >= 500
                    }
                    if (localWritable == 0) {
                        Text("Aucun calendrier local inscriptible détecté. Aucune écriture ne sera tentée; aucun fallback Google.")
                    } else {
                        Text("$localWritable calendrier(s) local(aux) inscriptible(s) détecté(s); l'écriture reste désactivée par défaut.")
                    }
                    writeDiagnostic.selectionError?.let { error ->
                        Text(
                            "Sélection : ${error.name}",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            Text(
                "WRITE_CALENDAR n'est pas déclarée dans le build courant. " +
                    "Toute activation réelle exige un accord ciblé et un calendrier synthétique.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun CalendarPermissionUiStatus.permissionLabel(): String = when (this) {
    CalendarPermissionUiStatus.GRANTED -> "Accordée"
    CalendarPermissionUiStatus.DENIED -> "Refusée"
    CalendarPermissionUiStatus.NOT_DECLARED -> "Non déclarée"
    CalendarPermissionUiStatus.UNAVAILABLE -> "Indisponible"
}

private fun loadCalendarPermissionScreenState(
    provider: AndroidCalendarProvider,
    gatedService: ConsentGatedCalendarReadService,
    consentStore: CalendarReadConsentStore,
): CalendarPermissionScreenState {
    val permission = try {
        provider.permissionState()
    } catch (_: Exception) {
        CalendarPermissionState.UNKNOWN
    }
    var consentStorageUnavailable = false
    val consentGranted = try {
        consentStore.isGranted()
    } catch (_: Exception) {
        consentStorageUnavailable = true
        false
    }
    val baseState = CalendarPermissionScreenState(
        permission = permission,
        consentGranted = consentGranted,
        loading = false,
        consentStorageUnavailable = consentStorageUnavailable,
    )
    if (!mayReadCalendarDiagnostics(permission, consentGranted)) return baseState

    val catalog = gatedService.inspectCalendars() ?: return baseState.copy(consentGranted = false)
    if (catalog.permission != CalendarPermissionState.GRANTED) {
        return baseState.copy(permission = catalog.permission)
    }
    val etarInstalled = try {
        EtarProbe(provider).probe().installed
    } catch (_: Exception) {
        false
    }
    if (!catalog.providerAvailable) {
        return baseState.copy(
            providerUnavailable = true,
            catalogLoaded = true,
            etarInstalled = etarInstalled,
        )
    }
    return baseState.copy(
        catalogLoaded = true,
        calendars = catalog.readableCalendars.map(CalendarDescriptor::toDiagnosticRow),
        etarInstalled = etarInstalled,
    )
}
