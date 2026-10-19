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
```

`LauncherHomeScreen` reçoit `agentState: HomeAgentState`. Si aucun fournisseur n’est passé, il utilise `HomeAgentState.localDefault(controller)`, qui lit la directive, le focus actif et le premier contenu local admissible. Le statut reste `LOCAL_ONLY`; aucune requête réseau n’est démarrée par le HOME.

## Règles d’affichage et d’action

- Le titre complet est conservé dans le modèle. Le HOME en affiche au plus 60 points de code et ouvre un détail accessible au toucher; le corps garde son texte complet dans ce détail.
- Une directive contient une seule action dominante. `START_FOCUS` ouvre l’écran Focus et laisse son bouton explicite « Démarrer » confirmer le démarrage; `OPEN_COACH` ouvre le coach; `NONE` garde le détail consultable sans action de démarrage.
- `focusCard` et `contentCard` sont facultatives. Les cartes utilisent les actions fermées de `HomeCardAction`; les favoris, la recherche du tiroir, le coach, la pause d’urgence et la sortie vers les réglages HOME restent gérés localement par ATLAS.
- Les données backend proposées par le lot D doivent être transformées en modèle d’affichage seulement après validation locale. Ne jamais placer de commande ou d’intent arbitraire dans ce contrat.
- États de disponibilité : `CONNECTED` indique une source distante joignable; `UNAVAILABLE` indique son indisponibilité; `LOCAL_ONLY` signifie que l’état provient du comportement local/offline. Dans les deux derniers cas, le HOME reste immédiatement utilisable.

## Raccordement du lot D

Lors de l’intégration, construire `HomeAgentState` dans le host (`LauncherActivity` ou un adaptateur de présentation) puis le passer à `LauncherHomeScreen`. Garder `HomeAgentState.localDefault(controller)` comme valeur de repli synchrone. Une réponse réseau tardive peut remplacer cette valeur après l’affichage, mais ne doit pas bloquer l’ouverture, les favoris, le tiroir, le coach ni les actions de secours.

Le modèle V6 propose des sessions Focus par `ProposedFocusSession?`; leur affichage utilise une confirmation utilisateur séparée décrite dans `FocusSessionScreen`.
