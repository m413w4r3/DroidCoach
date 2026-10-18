# 08 — Phase 1 PRÉANALYSÉE : décisions d'intégration et références (18 octobre 2026)

> **Statut** : préanalyse fondée sur les README, manifests, arborescences et quelques fichiers Kotlin directement consultés dans les dépôts publics. **Ce n'est pas un audit de compilation, de performances ou de comportement sur appareil.** L'agent de code n'a pas à refaire une revue générale de ces dépôts avant de construire VM-1. Il ne doit inspecter ou cloner du code externe que pour une question d'implémentation concrète.

## Décision produit déjà arrêtée pour VM-1

- Une application **ATLAS Android** à interface minimaliste originale ; un backend **ATLAS Core** découplé, ajouté après le jalon VM-1.
- **Ne pas fusionner/forker** Reef, Lawnchair, Olauncher, Loop ni ReVanced pour la première démonstration.
- Implémenter **localement** les petits domaines indispensables : directive, Pomodoro, habitudes simples, règle déterministe à événements simulés, bibliothèque de trois liens et stockage local.
- Sans agent IA, sans serveur et sans autorisations de surveillance au démarrage : le parcours est utilisable.
- Les fonctions d'IA, de synchronisation et d'intégration tierce sont branchées ultérieurement sur des **interfaces remplaçables**.
- Usage **personnel/local** envisagé. Les contraintes de redistribution sont secondaires à ce stade, mais conserver les avis de copyright/attribution pour tout emprunt de code ; un partage ultérieur impose une nouvelle vérification.

## Tableau décisionnel (liens directs)

| Référence | Dépôt et points d'entrée concrets | Faits consultés | Choix VM-1 | Inconnues techniques réservées |
|---|---|---|---|---|
| **Olauncher** | https://github.com/tanujnotes/Olauncher · [README](https://github.com/tanujnotes/Olauncher/blob/master/README.md) | Launcher minimal Android, licence GPLv3, projet axé sobriété | **Inspiration UX prioritaire** uniquement : accueil textuel, peu d'entrées, une action dominante. Produire un design original Compose. | Gestes/accueil/récents complets sur Pixel ; évaluer plus tard si fork utile. |
| **Lawnchair** | https://github.com/LawnchairLauncher/lawnchair · [README `16-dev`](https://github.com/LawnchairLauncher/lawnchair/blob/16-dev/README.md) | Base Launcher3/Pixel, dépôt volumineux, branche 16-dev en développement | **Référence technique** ; ne pas cloner pour VM-1 ; activité HOME Compose minimale avec rôle demandé explicitement. | Widgets, raccourcis, dossiers, profils privés, gestion gestes/récents et compatibilité système. |
| **Reef** | https://github.com/aload0/Reef · [manifest](https://github.com/aload0/Reef/blob/main/Reef/src/main/AndroidManifest.xml) · [TimerState.kt](https://github.com/aload0/Reef/blob/main/Reef/src/main/java/dev/pranav/reef/timer/TimerState.kt) · [TimerScreen.kt](https://github.com/aload0/Reef/blob/main/Reef/src/main/java/dev/pranav/reef/timer/TimerScreen.kt) · [BlockerService.kt](https://github.com/aload0/Reef/blob/main/Reef/src/main/java/dev/pranav/reef/accessibility/BlockerService.kt) · [Routines.kt](https://github.com/aload0/Reef/blob/main/Reef/src/main/java/dev/pranav/reef/routine/Routines.kt) | Kotlin+Compose ; timer, modes focus/Pomodoro, routines, quotas, Mindful Launch. TimerState définit états et phases ; blocage app via AccessibilityService et permissions système sensibles. | **Référence technique prioritaire**, mais implémenter un moteur Pomodoro compact et testable soi-même en V0 ; **aucune récupération du bloqueur** ni service d'accessibilité dans VM-1. | Comportement après reboot, cycles timer, notifications répétées, efficacité des blocages sur Pixel, compatibilité Android et qualité des tests. [Incident pertinent](https://github.com/aload0/Reef/issues/91). |
| **Loop Habit Tracker** | https://github.com/iSoron/uhabits · [README](https://github.com/iSoron/uhabits/blob/dev/README.md) · [uhabits-core](https://github.com/iSoron/uhabits/tree/dev/uhabits-core) | Habitudes flexibles, rappels, export CSV/SQLite, ajout de validations depuis des apps tierces comme Tasker ; code Kotlin ; GPLv3. | **Petit tracker natif ATLAS** comme source de vérité, Loop est une **intégration externe facultative** future. Aucune synchronisation directe présumée. | Mécanisme exact Tasker/intent, import/export, stabilité d'une interop bidirectionnelle. |
| **ReVanced Manager** | https://github.com/ReVanced/revanced-manager · [README](https://github.com/ReVanced/revanced-manager/blob/main/README.md) | Gestion de téléchargements et patches APK, pas d'API de pilotage du visionnage garantie | **Laisse l'application existante indépendante**. ATLAS ouvre les URL vidéo avec Android `ACTION_VIEW` et choix utilisateur. | Quel package reçoit l'URL, support des deeplinks, réglages de Shorts/feed ; à tester sur appareil avec installation de l'utilisateur. |
| **ReVanced patches** | https://gitlab.com/revanced/revanced-patches · [état dépôt](https://github.com/ReVanced/where-is-revanced-patches) | Dépôt GitHub principal bloqué au moment de la vérification ; miroir GitLab indiqué | Hors périmètre VM-1 ; aucune compilation/patch à partir d'ATLAS | Documentation et disponibilité du miroir peuvent changer. |
| **AnkiDroid** | https://github.com/ankidroid/Anki-Android | Révisions espacées Android | Intégration différée ; liens ou actions Android si besoin | API d'automatisation et permissions. |
| **Miniflux** | https://github.com/miniflux/v2 · [API](https://miniflux.app/docs/api.html) | Serveur RSS avec API | Après VM-1, pour curation | Hébergement et authentification. |
| **OpenClaw** | https://docs.openclaw.ai/ · [Android](https://docs.openclaw.ai/platforms/android) | Agent / Gateway selon documentation | Après VM-1, derrière `AgentAdapter` interchangeable | Protocole réel, sécurité, maintenance, hébergement. |

## Conclusion d'architecture : une V0 originale est moins risquée qu'une fusion

**Il ne faut pas dépenser les premières sessions à fusionner des applications entières.** Le coût de fusion des cycles de vie Android, fichiers Gradle, services d'accessibilité, manifests et UI serait supérieur à celui d'un Pomodoro et d'un tracker natifs minimaux. Cette décision pourra être revue **après** validation sur émulateur si une fonction sophistiquée manque.

### Distinction concrète

1. **Inspirer** : Olauncher -> choix de présentation, **sans copier de fichiers**.
2. **Étudier** : Reef -> états et stratégies à comparer, **sans intégrer le service de blocage**.
3. **Interopérer** : Loop et ReVanced -> une autre app, via intents ou export documentés quand cela fonctionne ; **aucune écriture dans leur stockage privé**.
4. **Réutiliser du code** : seulement après preuve d'un besoin spécifique et petit audit ciblé : fichier(s), dépendances, copyright, tests, maintenance.

## Ce qu'il reste légitimement à l'agent dans la « phase 1 »

L'agent effectue une vérification **courte et orientée blocage**, pas une nouvelle étude générale :

- vérifier sur sa machine la version de Java/SDK, la possibilité de construire Compose et le lancement AVD ;
- récupérer les SHA Git des **seuls dépôts qu'il inspecte réellement** si une difficulté précise apparaît ;
- découvrir les signatures réelles des outils Nimbalyst `spawn_session`, `send_prompt`, `get_session_status`, `get_session_result`, `list_spawned_sessions` avant appel ;
- confronter les décisions ci-dessus à **un obstacle concret**, documenter l'écart éventuel en quelques lignes ;
- conserver en priorité **VM-0 (hello Android)** puis **VM-1 (parcours local)**.

**Ne pas demander à une session xhigh de rédiger un rapport générique de plusieurs pages sur tous les dépôts.** Si l'agent découvre un obstacle qui impose un fork, il doit expliquer pourquoi et proposer un audit ciblé, sans bloquer le MVP.

## Liens système officiels à employer durant le développement

- Rôle HOME : https://developer.android.com/reference/android/app/role/RoleManager
- Mesure d'usage : https://developer.android.com/reference/android/app/usage/UsageStatsManager
- Notifications : https://developer.android.com/develop/ui/views/notifications/notification-permission
- Émulateur : https://developer.android.com/studio/run/emulator-commandline
- Accélération Linux : https://developer.android.com/studio/run/emulator-acceleration
- Nimbalyst : https://github.com/nimbalyst/nimbalyst/blob/main/docs/THE_HARNESS.md
- GPL — usage privé : https://www.gnu.org/licenses/gpl-faq.fr.html#GPLRequireSourcePostedPublic

## Provenance et limite

Péremption possible des HEAD, README et des règles Android : URLs et caractéristiques contrôlées le 18/10/2026, pas de commits figés ni de tests d'appareil effectués ici. **Si aucune source tierce n'est copiée, il n'est pas nécessaire de cloner toutes ces références.**
