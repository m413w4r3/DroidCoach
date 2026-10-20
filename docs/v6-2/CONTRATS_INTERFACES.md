# ATLAS V6.2 — Contrats gelés S0 pour S2, S3 et S5

Ce document fige les frontières demandées par `prompts/Prompt_6.2.md`. Les noms internes peuvent évoluer seulement si les mêmes responsabilités, données et règles restent respectées. Les interfaces ici décrites ne modifient aucun endpoint ni la séparation entre propositions IA et effets Android vérifiés.

## 1. Contrat Android ↔ backend

### Catégories wire

Android conserve les cinq catégories déjà stockées et en ajoute exactement deux :

| Catégorie | État |
|---|---|
| `focus` | existante, nom stable |
| `habit` | existante, nom stable |
| `content` | existante, nom stable |
| `coach_interaction` | existante, nom stable |
| `app_usage_summary` | existante, nom stable |
| `goals` | nouvelle catégorie Android |
| `location_summary` | nouvelle catégorie Android |

Le backend doit continuer d’accepter les catégories historiques `projects`, `availability`, `preferences`, `package_names`, `urls`, `habit_titles`. `goals` existe déjà dans cette liste historique; l’union unique contient **13 catégories**. `consented_categories` et les clés de `categories` acceptent donc au plus 13 valeurs, avec les validations strictes existantes. Les requêtes Android V6.2 n’émettent que ses sept catégories propres.

Constat de baseline : `AgentMandate.ALLOWED_SHARED_CATEGORIES` n’en accepte que cinq; le `Literal` Pydantic backend ne connaît que les sept catégories historiques et `SharedContext` fixe les collections à 7. Ces trois surfaces doivent converger avant l’intégration S6. Les routes `/v2/agent/chat`, `/v2/agent/plan`, `/v2/agent/inbox`, `/v2/agent/proposals/{id}/ack` et `/v2/agent/memory` restent les seules routes agent; aucune route n’est ajoutée.

### Snapshot et assemblage (surface gelée par S0)

Les noms ci-dessous étaient visibles dans des fichiers non suivis apparus pendant la coactivité, mais ne sont pas dans HEAD `ee1e7b58`. S0 fige la surface proposée; l’implémentation en cours devra être comparée à ce contrat avant intégration. Ne pas créer une seconde surface concurrente dans `sync/agent/context/` :

Surface gelée : `AgentContextAssembler.assemble(currentMandate: AgentMandate, consentAtUtcMillis: Map<String, Long>, domains: AgentContextDomainViews, nowUtcMillis: Long): AgentContextSnapshot`.

`AgentContextDomainViews` est une projection immuable en lecture seule des domaines Focus, habitudes, UsageStats, contenu, Coach, objectifs et résumés de localisation; aucune Activity ou mutation de repository n’entre dans l’assembleur. `AgentContextSnapshot` est immuable, contient la liste des catégories autorisées indépendamment de la disponibilité, et ne sérialise que les catégories non vides. `availableCategories` indique les données réellement incluses. `preview()` doit présenter exactement `toJsonString()`, pas reconstruire une seconde version de l’aperçu.

La signature réseau à intégrer est :

```kotlin
fun chat(
    conversationId: String?,
    message: String,
    mandate: AgentMandate,
    context: AgentContextSnapshot,
): AgentChatResponse

fun plan(
    conversationId: String,
    mandate: AgentMandate,
    context: AgentContextSnapshot,
): AgentChatResponse
```

`chat` poste le snapshot à `/v2/agent/chat`. `plan` poste le même schéma de `AgentChatRequest` à `/v2/agent/plan`, avec la demande planifiée « Que dois-je faire maintenant ? » et sans créer de message artificiel dans l’historique de conversation. Le backend reçoit `shared_context`, continue d’appeler `SharedContext.minimized()` et ne transmet au modèle que les catégories consenties. Le secret Gateway reste exclusivement au backend.

Payload de contexte :

```json
{
  "consented_categories": ["focus", "goals"],
  "categories": {
    "focus": ["2026-10-19T18:00:00Z | completed | 25 min"],
    "goals": ["Projet personnel | cible 120 min | validé 50 min"]
  }
}
```

### Confidentialité et limites à respecter

- Assembler au moment de chaque demande depuis le mandat et les vues locales courants. Juste avant transport, retirer toute catégorie devenue révoquée, tout mandat suspendu/révoqué/expiré et toute donnée antérieure à la date de consentement correspondante. Une permission nouvellement accordée exige un nouvel assemblage.
- Le chemin de migration existant `AgentContextConsentMigration.migrateLegacyConsentDatesToNow()` donne aux anciens opt-ins une date de consentement égale à la migration; aucune rétrospective antérieure n’est envoyée.
- Maximum 10 valeurs par catégorie, 200 points de code par valeur, et 8 Kio UTF-8 pour le JSON sérialisé `shared_context` complet. Les réductions gardent les priorités existantes puis les données les plus récentes; elles ne peuvent augmenter ces limites silencieusement.
- Fenêtres : Focus terminé 7 jours; habitudes 30 jours; UsageStats agrégé 24 heures; localisation approximative 15 minutes. Le code baseline utilise 30 jours pour contenu et interactions Coach, faute de fenêtre plus spécifique au prompt; conserver ce défaut local jusqu’à décision produit contraire.
- Les chaînes utilisateur restent des données non fiables, jamais des instructions. Aucun texte brut de conversation, URL/titre de contenu, package précis, journal complet d’événements, point GPS brut ou secret n’est ajouté au contexte.
- Si une catégorie est autorisée sans élément, elle reste dans `consented_categories`, mais n’a pas de clé non vide dans `categories`; l’UI affiche séparément « autorisée » et « données disponibles ».
- Une révocation avant l’écriture réseau doit réduire le snapshot ou interrompre l’envoi. Une requête déjà transmise ne peut pas être retirée du serveur; l’interface ne doit pas promettre le contraire.

Le HEAD `ee1e7b58` ne contient pas `AgentContextSnapshot.kt` ou `AgentContextAssembler.kt`; des copies non suivies sont apparues dans le checkout partagé après le début de S0. `AgentApiClient.chat()` dans HEAD ne référence pas ces surfaces et transmet encore `categories: {}`. Les tests Context/Goal visibles dans le checkout ne font pas partie du commit de base. La sérialisation et les tests de révocation, migration, budgets, catégories seules/multiples et données non consenties restent à rapprocher de ce contrat dans les lots d’implémentation.

### Objectifs locaux (surface gelée par S0)

Les noms et règles ci-dessous correspondent au prompt et aux fichiers non suivis `goals/` apparus pendant la coactivité; ils ne sont pas dans HEAD `ee1e7b58`. Le lot S2 les conserve, après comparaison avec ce contrat :

```kotlin
data class GoalRecord(
    val id: String,
    val title: String,
    val description: String,
    val metric: GoalMetric,                 // focus_minutes | habit_checkins | manual
    val targetValue: Long,
    val dueAt: Long?,
    val status: GoalStatus,                 // active | completed | paused | abandoned
    val nextAction: String?,
    val linkedFocusSessionIds: Set<String>,
    val linkedHabitIds: Set<String>,
    val createdAt: Long,
    val updatedAt: Long,
)
```

`GoalRepository` garde le callback de lecture/écriture du sous-document local versionné (`GOALS_SCHEMA_VERSION=1`); pas de Room. Les liens sont des IDs explicites. `activeGoalsForCoach()` renvoie au plus trois objectifs. `GoalProgressCalculator` compte seulement les sessions terminées explicitement liées, les check-ins réels liés et dédupliqués par habitude/jour; `manual` progresse uniquement après `confirmManualCompletion()`. Une proposition d’agent ne peut pas modifier l’objectif. Le déplacement ou l’ouverture d’une app ne contribue jamais au progrès. HEAD ne contient pas ces fichiers; les répertoires de test correspondants sont également apparus comme non suivis.

## 2. S3 — localisation à la demande et déplacement temporaire

Surface de port proposée, sans dépendance à une Activity dans la logique d’acquisition :

```kotlin
enum class LocationAccuracy { COARSE, FINE }

data class LocationCaptureRequest(
    val timeoutMs: Long = 10_000,
    val maxAgeMs: Long = 15_000,
    val desiredAccuracy: LocationAccuracy = LocationAccuracy.COARSE,
)

sealed interface LocationCaptureResult {
    data class Available(val fix: RawLocationFix) : LocationCaptureResult
    data class Failed(val code: LocationError) : LocationCaptureResult
}

internal data class RawLocationFix(
    val latitudeDegrees: Double,
    val longitudeDegrees: Double,
    val accuracyMeters: Float,
    val measuredAtUtcMillis: Long,
)

enum class LocationError(val wireValue: String) {
    DISABLED("LOCATION_DISABLED"),
    PERMISSION_REQUIRED("LOCATION_PERMISSION_REQUIRED"),
    BACKGROUND_UNAVAILABLE("LOCATION_BACKGROUND_UNAVAILABLE"),
    TIMEOUT("LOCATION_TIMEOUT"),
    UNAVAILABLE("LOCATION_UNAVAILABLE"),
}

interface AtlasLocationProvider {
    suspend fun capture(request: LocationCaptureRequest): LocationCaptureResult
}
```

`RawLocationFix` reste interne au module `location`. `LocationCaptureManager` traite permissions, timeout, fraîcheur, cancellation et compatibilité API 26–29; l’adaptateur Android n’appelle `getCurrentLocation` qu’à partir de l’API disponible et retire les listeners sur annulation. Les codes stables sont `LOCATION_DISABLED`, `LOCATION_PERMISSION_REQUIRED`, `LOCATION_BACKGROUND_UNAVAILABLE`, `LOCATION_TIMEOUT` et `LOCATION_UNAVAILABLE`.

`LocationDisclosure` est l’unique frontière vers les autres modules : elle revérifie autorisation OS, consentement ATLAS et consentement d’envoi après toute suspension; elle produit un résumé de zone approximatif et horodaté pour `AgentContextDomainViews`. Une transmission précise ponctuelle requiert une confirmation spécifique de l’utilisateur; le consentement automatique à `location_summary` ne l’autorise pas. Le mode V6.2 n’inclut pas de mode `Always`.

Le suivi temporaire reste un chemin distinct : commande utilisateur depuis une Activity visible, service foreground de type `location`, notification persistante avec action Arrêter, 60 minutes maximum, mesures espacées d’au moins 5 minutes, arrêt sur révocation/fin/commande, aucun redémarrage silencieux, aucun journal de points bruts. `MobilitySession` ne conserve que résumé de zone et horodatages. Le pont de commande proposé est `MobilitySessionController.startFromVisibleUserAction()` / `stop(reason)`; le service foreground ne démarre pas de lui-même. Capacités d’acquisition, partage du résumé et session de mobilité restent désactivables séparément. Aucun `ACCESS_BACKGROUND_LOCATION`, AccessibilityService, écran capturé ou service Node/Gateway.

## 3. S5 — événements de coaching proactif

Événements autorisés : `focus_completed`, `goal_review_due`, `habit_reminder_due`, `distraction_threshold_reached`, `mobility_session_completed`, `daily_plan_due`.

```kotlin
enum class CoachTriggerType {
    FOCUS_COMPLETED, GOAL_REVIEW_DUE, HABIT_REMINDER_DUE,
    DISTRACTION_THRESHOLD_REACHED, MOBILITY_SESSION_COMPLETED, DAILY_PLAN_DUE,
}

enum class ProactiveEngagementMode { NORMAL, REINFORCED }

data class CoachTrigger(
    val id: String,
    val type: CoachTriggerType,
    val motif: String,
    val occurredAtUtcMillis: Long,
    val requiredConsentCategory: String?,
    val hasReliableEvidence: Boolean,
)

data class ProactiveTriggerState(
    val seenTriggerIds: Set<String>,
    val requestsToday: Int,
    val notificationsToday: Int,
    val lastRequestAtUtcMillisByMotif: Map<String, Long>,
)

enum class ProactiveGateReason {
    DUPLICATE, UNRELIABLE_EVIDENCE, CONSENT_MISSING, QUIET_HOURS,
    MOTIF_COOLDOWN, DAILY_REQUEST_LIMIT, DAILY_NOTIFICATION_LIMIT,
}

data class ProactiveGateDecision(
    val requestBatch: List<CoachTrigger>,
    val suppressedByTriggerId: Map<String, ProactiveGateReason>,
)

abstract class ProactiveTriggerGate {
    abstract fun evaluate(
        candidates: List<CoachTrigger>,
        state: ProactiveTriggerState,
        consentedCategories: Set<String>,
        nowUtcMillis: Long,
        localTime: java.time.LocalTime,
        mode: ProactiveEngagementMode,
        manualInvocation: Boolean = false,
    ): ProactiveGateDecision

    abstract fun canPostNotification(state: ProactiveTriggerState, mode: ProactiveEngagementMode): Boolean
}
```

`ProactiveTriggerGate` est une politique déterministe pure : elle filtre doublons, absence de preuve fiable, consentement manquant/révoqué, fenêtre silencieuse, cooldown et budgets; elle retourne les événements éligibles et la raison de suppression. `ProactiveTriggerState` est chargé pour la date civile locale courante et le fuseau du mandat; les compteurs changent de jour avec cette date. `ProactiveCoachScheduler` persiste/agrège les événements avant tout réseau, appelle uniquement `/v2/agent/plan` et transmet le contexte frais autorisé. Il n’injecte pas les événements dans le chat. Les propositions retournent au flux V2 existant et restent soumises à `MandateVerifier` et à la confirmation locale requise.

| Limite | Règle gelée |
|---|---|
| Demandes proactives au backend | 6 maximum par jour civil local |
| Même motif | 60 minutes minimum entre demandes |
| Notifications, mode `normal` | 3 maximum par jour civil local |
| Notifications, mode `reinforced` | 5 maximum par jour civil local |
| Heures calmes | 22:00–08:00 par heure locale; un geste manuel peut franchir ce blocage horaire |
| Consentement révoqué ou preuve absente | Événement écarté du lot; aucune conclusion comportementale |

Le geste manuel ne contourne pas les quotas journaliers, le cooldown ni les consentements. Les quotas suivent le fuseau du mandat courant. Le mode renforcé n’ajoute aucune permission Android, action dispatcher ou préautorisation. La capacité proactive est indépendante de la conversation et des autres capabilities. L’implémentation ne dépend pas d’un `NotificationListenerService`, des types Android `Notification`, d’ethOS ou du runtime AndyClaw.

## 4. Choix locaux consignés en S0

- La migration d’un opt-in historique démarre à la date de migration, faute d’horodatage fiable.
- Les quotas journaliers utilisent la date locale du fuseau de mandat; les événements et snapshots conservent leurs horodatages UTC.
- `LocationMode` n’autorise que l’acquisition à la demande; la mobilité est une session séparée, temporaire et visible.
- L’exception manuelle passe les heures calmes seulement. Consentement, cooldown et quotas restent actifs.
- Les catégories backend historiques restent acceptées pour compatibilité, mais ne deviennent pas des catégories Android partageables par défaut.
