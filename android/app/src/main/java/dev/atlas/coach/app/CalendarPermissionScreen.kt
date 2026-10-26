package dev.atlas.coach.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import dev.atlas.coach.calendar.local.AtlasLocalCalendar
import dev.atlas.coach.calendar.write.CalendarWriteOperation
import dev.atlas.coach.calendar.write.CalendarWriteOperationState
import dev.atlas.coach.calendar.write.CalendarWriteOperationType
import dev.atlas.coach.plan.DayPlan
import dev.atlas.coach.plan.DayPlanRepository
import dev.atlas.coach.plan.FilePlanBackend
import dev.atlas.coach.plan.PlanClock
import dev.atlas.coach.plan.PlanLoadResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

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
    val readPermission: CalendarPermissionState = CalendarPermissionState.UNKNOWN,
    val writePermission: CalendarPermissionState = CalendarPermissionState.UNKNOWN,
    val consentGranted: Boolean = false,
    val localCalendars: List<CalendarDescriptor> = emptyList(),
    val selectedCalendarId: Long? = null,
    val providerUnavailable: Boolean = false,
    val ensureMessage: String? = null,
    val operations: List<CalendarWriteOperation> = emptyList(),
    val verification: List<CalendarPlanVerification> = emptyList(),
    val planLoaded: Boolean = false,
    val lastStatus: CalendarEffectLastStatus? = null,
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

internal fun shouldLaunchCalendarWritePermissionRequest(
    readPermission: CalendarPermissionState,
    writePermission: CalendarPermissionState,
    readConsentGranted: Boolean,
    writeConsentGranted: Boolean,
    userExplicitlyRequested: Boolean,
): Boolean = userExplicitlyRequested &&
    readConsentGranted && writeConsentGranted &&
    readPermission == CalendarPermissionState.GRANTED &&
    writePermission == CalendarPermissionState.DENIED

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

/** Diagnostic séparé du HOME : aucune permission ni aucun calendrier n'est demandé automatiquement. */
@Composable
fun CalendarPermissionScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val provider = remember(context) { AndroidCalendarProvider(context) }
    val readService = remember(provider) { CalendarReadService(provider) }
    val readConsentStore: CalendarReadConsentStore = remember(context) {
        SharedPreferencesCalendarReadConsentStore(context)
    }
    val gatedReadService = remember(readService, readConsentStore) {
        ConsentGatedCalendarReadService(readService, readConsentStore)
    }
    val writeConsentStore = remember(context) { SharedPreferencesCalendarWriteConsentStore(context) }
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(CalendarPermissionScreenState()) }
    var writeState by remember { mutableStateOf(CalendarWriteDiagnosticState()) }
    var refreshGeneration by remember { mutableIntStateOf(0) }
    var effectController by remember { mutableStateOf<CalendarEffectController?>(null) }

    fun refresh(ensureMessage: String? = writeState.ensureMessage) {
        refreshGeneration++
        val generation = refreshGeneration
        val currentEffectController = effectController
        scope.launch {
            state = state.copy(loading = true)
            writeState = writeState.copy(loading = true)
            val refreshed = withContext(Dispatchers.IO) {
                loadCalendarPermissionScreenState(provider, gatedReadService, readConsentStore)
            }
            val refreshedWrite = withContext(Dispatchers.IO) {
                currentEffectController?.let {
                    loadCalendarWriteDiagnosticState(context, it, readConsentStore, writeConsentStore, ensureMessage)
                } ?: CalendarWriteDiagnosticState(
                    loading = false,
                    readPermission = refreshed.permission,
                    writePermission = manifestPermissionState(context, Manifest.permission.WRITE_CALENDAR),
                    consentGranted = runCatching(writeConsentStore::isGranted).getOrDefault(false),
                    ensureMessage = ensureMessage,
                )
            }
            if (refreshGeneration == generation) {
                state = refreshed
                writeState = refreshedWrite
            }
        }
    }

    val readPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        refresh()
    }
    val writePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        refresh()
    }

    LaunchedEffect(context) {
        effectController = withContext(Dispatchers.IO) { CalendarEffectController.fromContext(context) }
        refresh(ensureMessage = null)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Calendrier", style = MaterialTheme.typography.headlineMedium)
            Text(
                "La lecture et l’écriture ont des consentements ATLAS et des permissions Android distincts. " +
                    "Aucune demande ne part sans votre action.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        AtlasSection("Consentement de lecture") {
            Text("Ce choix autorise l’affichage des noms de calendriers; il n’autorise aucune écriture.")
            if (state.consentGranted) {
                AtlasStatus("Consentement de lecture donné sur cet appareil", AtlasStatusKind.AUTHORIZED)
                AtlasTertiaryButton(
                    label = "Révoquer le consentement de lecture",
                    onClick = {
                        scope.launch {
                            val revoked = withContext(Dispatchers.IO) { readConsentStore.revoke() }
                            refresh(if (revoked) null else "Le consentement de lecture n’a pas pu être révoqué.")
                        }
                    },
                )
            } else {
                AtlasPrimaryButton(
                    label = "Consentir à la lecture des noms",
                    enabled = !state.loading,
                    onClick = {
                        scope.launch {
                            val granted = withContext(Dispatchers.IO) { readConsentStore.grant() }
                            refresh(if (granted) null else "Le consentement de lecture n’a pas pu être enregistré.")
                        }
                    },
                )
            }
            if (state.consentStorageUnavailable) {
                Text("Le consentement n’a pas pu être enregistré.", color = MaterialTheme.colorScheme.error)
            }
        }

        AtlasSection("Permission Android de lecture") {
            Text("READ_CALENDAR : ${state.permission.permissionLabel()}")
            Text(state.permission.explanation("lecture"), style = MaterialTheme.typography.bodySmall)
            if (state.permission == CalendarPermissionState.DENIED) {
                AtlasPrimaryButton(
                    label = "Demander READ_CALENDAR",
                    enabled = state.consentGranted && !state.loading,
                    onClick = {
                        if (shouldLaunchCalendarPermissionRequest(state.permission, state.consentGranted, true)) {
                            readPermissionLauncher.launch(Manifest.permission.READ_CALENDAR)
                        }
                    },
                )
            }
        }

        AtlasSection("Calendriers lisibles") {
            when {
                !state.consentGranted -> Text("Donnez le consentement de lecture pour consulter les noms.")
                state.permission != CalendarPermissionState.GRANTED -> Text("Aucun calendrier n’est lu avant l’accord Android READ_CALENDAR.")
                state.loading -> Text("Vérification en cours…")
                state.providerUnavailable -> Text("Le fournisseur est indisponible. Aucun événement n’a été lu.")
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
                    if (state.etarInstalled == true) "Etar détectée (ws.xsoh.etar ou ws.xsoh.etar.debug)."
                    else "Etar non détectée (ws.xsoh.etar, ws.xsoh.etar.debug); la visibilité Android peut limiter cette vérification.",
                )
            }
        }

        AtlasSection("Consentement ATLAS d’écriture") {
            Text("Ce consentement est indépendant de READ_CALENDAR, WRITE_CALENDAR et du consentement de lecture.")
            if (writeState.consentGranted) {
                AtlasStatus("Consentement d’écriture donné sur cet appareil", AtlasStatusKind.AUTHORIZED)
                AtlasTertiaryButton(
                    label = "Révoquer immédiatement le consentement d’écriture",
                    onClick = {
                        // The store closes its in-process write gate synchronously before persisting revocation.
                        val revoked = writeConsentStore.revoke()
                        refresh(if (revoked) null else "Consentement révoqué en mémoire; vérifiez le stockage local.")
                    },
                )
            } else {
                AtlasPrimaryButton(
                    label = "Consentir à l’écriture calendrier",
                    enabled = !writeState.loading,
                    onClick = {
                        scope.launch {
                            val granted = withContext(Dispatchers.IO) { writeConsentStore.grant() }
                            refresh(if (granted) null else "Le consentement d’écriture n’a pas pu être enregistré.")
                        }
                    },
                )
            }
        }

        AtlasSection("Permission Android d’écriture") {
            Text("WRITE_CALENDAR : ${writeState.writePermission.permissionLabel()}")
            Text(writeState.writePermission.explanation("écriture"), style = MaterialTheme.typography.bodySmall)
            if (writeState.writePermission == CalendarPermissionState.DENIED) {
                AtlasPrimaryButton(
                    label = "Demander WRITE_CALENDAR",
                    enabled = !writeState.loading && writeState.consentGranted &&
                        state.consentGranted && state.permission == CalendarPermissionState.GRANTED,
                    onClick = {
                        if (shouldLaunchCalendarWritePermissionRequest(
                                readPermission = state.permission,
                                writePermission = writeState.writePermission,
                                readConsentGranted = state.consentGranted,
                                writeConsentGranted = writeState.consentGranted,
                                userExplicitlyRequested = true,
                            )
                        ) {
                            writePermissionLauncher.launch(Manifest.permission.WRITE_CALENDAR)
                        }
                    },
                )
            }
        }

        AtlasSection("Calendrier local sélectionné") {
            Text("ATLAS accepte uniquement ATLAS R3A TEST avec le compte local dédié, ou un compte de type LOCAL.")
            val selected = writeState.localCalendars.firstOrNull { it.id == writeState.selectedCalendarId }
            if (selected != null) {
                AtlasStatus("Sélection : ${selected.toDiagnosticRow().displayName}", AtlasStatusKind.AUTHORIZED)
                AtlasSecondaryButton(
                    label = "Effacer la sélection locale",
                    enabled = !writeState.loading,
                    onClick = {
                        val currentEffectController = effectController
                        scope.launch {
                            val cleared = withContext(Dispatchers.IO) { currentEffectController?.clearSelection() == true }
                            refresh(if (cleared) "Sélection effacée." else "La sélection n’a pas pu être effacée.")
                        }
                    },
                )
            } else {
                Text("Aucun calendrier local ATLAS ou LOCAL n’est sélectionné.")
            }
            when {
                !writeState.consentGranted || !state.consentGranted -> Text("Les deux consentements ATLAS sont requis pour configurer l’écriture.")
                writeState.readPermission != CalendarPermissionState.GRANTED || writeState.writePermission != CalendarPermissionState.GRANTED ->
                    Text("Accordez d’abord READ_CALENDAR puis WRITE_CALENDAR.")
                writeState.loading -> Text("Calendriers locaux en cours de vérification…")
                writeState.providerUnavailable -> Text("Le fournisseur calendrier est indisponible.")
                writeState.localCalendars.isEmpty() -> Text("Aucun calendrier local autorisé n’est disponible.")
                else -> writeState.localCalendars.forEach { calendar ->
                    val safeName = calendar.toDiagnosticRow().displayName
                    AtlasSecondaryButton(
                        label = if (calendar.id == writeState.selectedCalendarId) "Sélectionné : $safeName"
                        else "Sélectionner $safeName",
                        enabled = calendar.id != writeState.selectedCalendarId && !writeState.loading,
                        onClick = {
                            val currentEffectController = effectController
                            scope.launch {
                                val issue = withContext(Dispatchers.IO) {
                                    currentEffectController?.selectCalendar(calendar.id) ?: CalendarEffectIssue.PROVIDER_UNAVAILABLE
                                }
                                refresh(issue?.userLabel())
                            }
                        },
                    )
                }
            }
            AtlasPrimaryButton(
                label = "Créer ou vérifier ATLAS R3A TEST",
                enabled = !writeState.loading && writeState.consentGranted && state.consentGranted &&
                    writeState.readPermission == CalendarPermissionState.GRANTED &&
                    writeState.writePermission == CalendarPermissionState.GRANTED,
                onClick = {
                    // AtlasLocalCalendar.ensure is deliberately reachable only from this explicit action.
                    val currentEffectController = effectController
                    scope.launch {
                        val message = withContext(Dispatchers.IO) {
                            when (val ensured = AtlasLocalCalendar.ensure(context)) {
                                is AtlasLocalCalendar.Result.Ready -> {
                                    val issue = currentEffectController?.selectCalendar(ensured.calendarId)
                                    issue?.userLabel() ?: "${ensured.displayName} prêt et sélectionné."
                                }
                                is AtlasLocalCalendar.Result.Failed -> "Calendrier local non prêt (${ensured.code.name})."
                            }
                        }
                        refresh(message)
                    }
                },
            )
            writeState.ensureMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }

        AtlasSection("Journal d’effets et dernière vérification") {
            Text("Le journal durable n’affiche ni clés, ni titres d’événements, ni URL.")
            val last = writeState.lastStatus
            Text(
                if (last == null) "Dernier statut : aucun effet calendrier enregistré."
                else "Dernier statut : ${last.code} · ${last.completedCount}/${last.effectCount} effet(s) · ${formatTimestamp(last.atMillis)}",
            )
            Text("Opérations durables : ${writeState.operations.size}")
            writeState.operations.takeLast(8).asReversed().forEach { operation ->
                Text(
                    "${operation.type.toDiagnosticLabel()} · ${operation.state.toDiagnosticLabel()}" +
                        (operation.errorCode?.let { " · ${it.name}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            when {
                !writeState.consentGranted || !state.consentGranted -> Text("Les deux consentements sont requis pour vérifier le calendrier.")
                writeState.readPermission != CalendarPermissionState.GRANTED || writeState.writePermission != CalendarPermissionState.GRANTED ->
                    Text("La vérification attend READ_CALENDAR et WRITE_CALENDAR.")
                !writeState.planLoaded -> Text("Aucun programme local du jour à vérifier.")
                writeState.verification.isEmpty() -> Text("Aucun lien calendrier à vérifier.")
                else -> {
                    val counts = writeState.verification.groupingBy { it.state }.eachCount()
                    Text("État des liens : ${counts.entries.joinToString(" · ") { (status, count) -> "${status.name} $count" }}")
                    if (writeState.verification.any {
                            it.state in setOf(CalendarPlanVerificationState.DIVERGED, CalendarPlanVerificationState.ORPHANED)
                        }
                    ) {
                        AtlasStatus("Divergence ou lien orphelin détecté", AtlasStatusKind.UNAVAILABLE)
                    }
                }
            }
        }

        AtlasSecondaryButton(
            label = "Actualiser le diagnostic",
            enabled = !state.loading && !writeState.loading,
            onClick = { refresh() },
        )
    }
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
        return baseState.copy(providerUnavailable = true, catalogLoaded = true, etarInstalled = etarInstalled)
    }
    return baseState.copy(
        catalogLoaded = true,
        calendars = catalog.readableCalendars.map(CalendarDescriptor::toDiagnosticRow),
        etarInstalled = etarInstalled,
    )
}

private fun loadCalendarWriteDiagnosticState(
    context: Context,
    controller: CalendarEffectController,
    readConsentStore: CalendarReadConsentStore,
    writeConsentStore: CalendarWriteConsentStore,
    ensureMessage: String?,
): CalendarWriteDiagnosticState {
    val readPermission = try {
        AndroidCalendarProvider(context).permissionState()
    } catch (_: Exception) {
        CalendarPermissionState.UNKNOWN
    }
    val writePermission = manifestPermissionState(context, Manifest.permission.WRITE_CALENDAR)
    val writeConsent = runCatching(writeConsentStore::isGranted).getOrDefault(false)
    val readConsent = runCatching(readConsentStore::isGranted).getOrDefault(false)
    val canInspect = writeConsent && readConsent &&
        readPermission == CalendarPermissionState.GRANTED && writePermission == CalendarPermissionState.GRANTED
    val localCalendars = if (canInspect) controller.localCalendars() else emptyList()
    val plan = if (canInspect && controller.selectedCalendarId() != null) loadTodayPlan(context) else null
    val verification = if (plan != null) controller.verifyPlan(plan) else emptyList()
    return CalendarWriteDiagnosticState(
        loading = false,
        readPermission = readPermission,
        writePermission = writePermission,
        consentGranted = writeConsent,
        localCalendars = localCalendars,
        selectedCalendarId = controller.selectedCalendarId(),
        providerUnavailable = canInspect && !controller.providerAvailable(),
        ensureMessage = ensureMessage,
        operations = controller.operationHistory(),
        verification = verification,
        planLoaded = plan != null,
        lastStatus = controller.lastStatus(),
    )
}

private fun loadTodayPlan(context: Context): DayPlan? {
    val date = LocalDate.now(ZoneId.systemDefault())
    val file = File(File(context.filesDir, "plans"), "day-plan-$date.json")
    val repository = DayPlanRepository(FilePlanBackend(file), PlanClock { System.currentTimeMillis() })
    return (repository.load() as? PlanLoadResult.Loaded)?.plan
}

private fun manifestPermissionState(context: Context, permission: String): CalendarPermissionState {
    val requested = try {
        val packageInfo = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        }
        permission in packageInfo.requestedPermissions.orEmpty()
    } catch (_: Exception) {
        return CalendarPermissionState.UNKNOWN
    }
    if (!requested) return CalendarPermissionState.NOT_DECLARED
    return if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
        CalendarPermissionState.GRANTED
    } else {
        CalendarPermissionState.DENIED
    }
}

private fun CalendarPermissionState.permissionLabel(): String = when (this) {
    CalendarPermissionState.GRANTED -> "Accordée"
    CalendarPermissionState.DENIED -> "Refusée"
    CalendarPermissionState.NOT_DECLARED -> "Non déclarée"
    CalendarPermissionState.UNKNOWN -> "Indisponible"
}

private fun CalendarPermissionState.explanation(action: String): String = when (this) {
    CalendarPermissionState.GRANTED -> "Android autorise la $action. Le consentement ATLAS correspondant reste requis."
    CalendarPermissionState.DENIED -> "La demande n’est envoyée qu’après votre appui explicite."
    CalendarPermissionState.NOT_DECLARED -> "Cette permission n’est pas déclarée dans le build; aucune demande ne sera lancée."
    CalendarPermissionState.UNKNOWN -> "L’état de cette permission n’a pas pu être vérifié."
}

private fun CalendarWriteOperationType.toDiagnosticLabel(): String = when (this) {
    CalendarWriteOperationType.CREATE -> "Création"
    CalendarWriteOperationType.UPDATE -> "Mise à jour"
    CalendarWriteOperationType.DELETE -> "Suppression"
}

private fun CalendarWriteOperationState.toDiagnosticLabel(): String = when (this) {
    CalendarWriteOperationState.PREPARED -> "Préparée"
    CalendarWriteOperationState.APPLIED -> "Appliquée"
    CalendarWriteOperationState.NOT_APPLIED -> "Non appliquée"
    CalendarWriteOperationState.UNKNOWN -> "Inconnue"
    CalendarWriteOperationState.RECONCILED -> "Réconciliée"
    CalendarWriteOperationState.ABANDONED -> "Abandonnée"
}

private fun formatTimestamp(timestamp: Long): String = runCatching {
    Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm", Locale.getDefault()))
}.getOrDefault("heure inconnue")
