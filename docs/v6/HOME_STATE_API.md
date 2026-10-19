# API d’état pour HOME V6

Ce document décrit le point d’entrée que le lot D peut fournir au HOME V6. Le contrat est volontairement composé de données d’affichage et d’actions fermées : il ne transporte ni `AtlasController`, ni client réseau, ni commande Android.

## Modèle Kotlin

Le modèle public est défini dans `dev.atlas.coach.app.HomeAgentState` :

```kotlin
data class HomeAgentState(
    val directive: HomeDirective,
    val focusCard: HomeAgentCard? = null,
    val contentCard: HomeAgentCard? = null,
    val agentStatus: HomeAgentStatus = HomeAgentStatus.LOCAL_ONLY,
    val directiveProvenance: DirectiveProvenance,
    val backendState: AgentBackendState,
    val lastSuccessfulContactAtUtcMillis: Long?,
)

data class HomeDirective(
    val title: String,
    val body: String,
    val primaryAction: HomePrimaryAction,
)

data class HomeAgentCard(
    val title: String,
    val body: String,
    val actionLabel: String,
    val action: HomeCardAction,
)

enum class HomePrimaryAction { START_FOCUS, OPEN_COACH, NONE }
enum class HomeCardAction { OPEN_FOCUS, OPEN_COACH, OPEN_LIBRARY }
enum class HomeAgentStatus { CONNECTED, UNAVAILABLE, LOCAL_ONLY }
enum class DirectiveProvenance { REMOTE_AI, LOCAL_DEMO, MANUAL_ENTRY }
enum class AgentBackendState { REACHABLE, UNAVAILABLE, UNVERIFIED }
```

`LauncherHomeScreen` reçoit `agentState: HomeAgentState`. Si aucun fournisseur n’est passé, il utilise `HomeAgentState.localDefault(controller)`, qui lit la directive, le focus actif et le premier contenu local admissible. Aucune requête réseau n’est démarrée par le HOME.

La provenance de directive est persistée dans `AgentHomeState` (schéma local 2). Les directives agent déjà stockées au schéma 1 migrent en `REMOTE_AI`; une directive de base saisie localement est `MANUAL_ENTRY`. `AgentInboxRepository` conserve l’état de contact dans son fichier privé. L’observation est `REACHABLE` ou `UNAVAILABLE` pendant cinq minutes après la vérification; elle devient `UNVERIFIED` ensuite, ou si l’horloge recule. Le dernier contact réussi est conservé séparément. Le host doit recopier `AgentInboxRepository.backendObservation()` dans `AgentHomeState.backendObservation` lorsqu’il construit le modèle HOME.

## Règles d’affichage et d’action

- Le titre complet est conservé dans le modèle. Le HOME en affiche au plus 60 points de code et ouvre un détail accessible au toucher; le corps garde son texte complet dans ce détail.
- Une directive contient une seule action dominante. `START_FOCUS` ouvre l’écran Focus et laisse son bouton explicite « Démarrer » confirmer le démarrage; `OPEN_COACH` ouvre le coach; `NONE` garde le détail consultable sans action de démarrage.
- `focusCard` et `contentCard` sont facultatives. Les cartes utilisent les actions fermées de `HomeCardAction`; les favoris, la recherche du tiroir, le coach, la pause d’urgence et la sortie vers les réglages HOME restent gérés localement par ATLAS.
- Les données backend proposées par le lot D doivent être transformées en modèle d’affichage seulement après validation locale. Ne jamais placer de commande ou d’intent arbitraire dans ce contrat.
- La provenance (`REMOTE_AI`, `LOCAL_DEMO`, `MANUAL_ENTRY`) décrit qui a fourni la directive; l’état backend (`REACHABLE`, `UNAVAILABLE`, `UNVERIFIED`) décrit le dernier contrôle du service. Ces deux champs ne sont pas interchangeables.
- `CONNECTED` ne peut être dérivé que d’un contact réussi encore frais. Une directive `REMOTE_AI` reste identifiée comme distante si le backend devient indisponible; elle ne doit pas être rebaptisée directive locale.
- L’UI doit afficher la provenance et le statut backend séparément. `agentStatus` reste une projection de compatibilité pour l’ancien badge HOME; il ne remplace pas les trois champs détaillés.

## Raccordement du lot D

Lors de l’intégration, construire `HomeAgentState` dans le host (`LauncherActivity` ou un adaptateur de présentation) puis le passer à `LauncherHomeScreen`. Garder `HomeAgentState.localDefault(controller)` comme valeur de repli synchrone. Une réponse réseau tardive peut remplacer cette valeur après l’affichage, mais ne doit pas bloquer l’ouverture, les favoris, le tiroir, le coach ni les actions de secours.

Le modèle V6 propose des sessions Focus par `ProposedFocusSession?`; leur affichage utilise une confirmation utilisateur séparée décrite dans `FocusSessionScreen`.
