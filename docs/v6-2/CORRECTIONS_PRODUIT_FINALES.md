# ATLAS V6.2 — Corrections produit finales

Date : 20 octobre 2026. Périmètre : rendu Focus, restauration locale et instantané des interventions. Aucun contrat backend, écran HOME, manifeste ou module non attribué n’a été modifié.

## Corrections

### Focus désactivé

Dans [AtlasScreens.kt](../../android/app/src/main/java/dev/atlas/coach/app/AtlasScreens.kt), `FocusScreen` affiche maintenant la session conservée lorsque Focus est désactivé : tâche, phase en pause, temps restant et dernier message du contrôleur. L’écran n’offre ni reprise ni nouvelle session dans cet état. Annuler reste accessible ; terminer reste possible. À la restauration, [AtlasController.kt](../../android/app/src/main/java/dev/atlas/coach/app/AtlasController.kt) met en pause une session encore marquée RUNNING si la capability Focus est désactivée, puis enregistre son état.

Le test `disablingFocusPausesSessionAndDisablingHabitsRemovesLinkButton` dans [AtlasLaunchTest.kt](../../android/app/src/androidTest/java/dev/atlas/coach/AtlasLaunchTest.kt) vérifie l’état PAUSED dans le stockage, recrée l’activité et confirme la restauration du même identifiant et du même temps restant. Il vérifie aussi le message Focus, l’action Annuler et l’absence des actions Démarrer/Reprendre.

### Quarantaine, migrations et persistance

La cause de l’échec au démarrage était l’ordre d’initialisation Kotlin dans [AtlasController.kt](../../android/app/src/main/java/dev/atlas/coach/app/AtlasController.kt). `AgentContextCoordinator` ([source](../../android/app/src/main/java/dev/atlas/coach/sync/agent/coach/AgentContextCoordinator.kt)) est construit avant `GoalPanelCoordinator` ([source](../../android/app/src/main/java/dev/atlas/coach/app/GoalPanelCoordinator.kt)) ; sa migration remplit les dates en mémoire et ne déclenche pas le callback de persistance dans son constructeur. L’écriture prématurée vient du callback appelé par `GoalRepository` ([source](../../android/app/src/main/java/dev/atlas/coach/goals/GoalRepository.kt)) lors de sa construction dans `GoalPanelCoordinator`. Ce callback entre dans `persist()` avant que le bloc `init` d’`AtlasController` n’assigne `habitLinkPolicy`. `stateMap()` accède ensuite à `habitLinkPolicy.toMap()`, ce qui déclenche une `NullPointerException` sur ce champ non encore initialisé. Le `catch` de persistance avalait cette exception et remplaçait l’avis de quarantaine par le message générique d’échec. La migration de consentement reste marquée en attente et est écrite après la restauration, dans le bloc `init`.

Les callbacks d’initialisation demandent désormais une écriture différée. Le contrôleur la réalise après que son graphe et les coordinators sont prêts. Une erreur de préparation du snapshot et une vraie erreur d’écriture sont journalisées séparément avec la classe d’exception uniquement. Une vraie erreur d’écriture conserve un message lisible dans `latestMessage` ; elle ne laisse pas l’avis de récupération masquer l’échec.

Les tests instrumentés dans [AtlasLaunchTest.kt](../../android/app/src/androidTest/java/dev/atlas/coach/AtlasLaunchTest.kt) couvrent le sous-état invalide et le schéma inconnu, la quarantaine, la migration des anciens consentements Focus/objectifs, la création d’un objectif, puis la recréation du contrôleur avec les consentements et l’objectif restaurés.

### Instantané des interventions

Après chaque observation, le contrôleur appelle `refreshDomainSnapshots()` avant de notifier l’interface, indépendamment de l’activation de la proactivité.

Le test fixture dans [UsageFixtureInstrumentationTest.kt](../../android/app/src/androidTest/java/dev/atlas/coach/usage/UsageFixtureInstrumentationTest.kt) inspectait auparavant un `AtlasController` séparé de celui utilisé par `MainActivity`. L’historique partagé montrait les ouvertures observées par l’activité tandis que l’instantané lu par le test appartenait à un évaluateur de règles qui n’avait pas effectué le polling. Le test utilise maintenant le contrôleur singleton de l’application. Il attend l’instantané réel et journalise la politique effective et les horodatages synthétiques si l’assertion échoue.

Le run final a observé quatre ouvertures, une intervention, une notification active, l’ouverture de l’écran Intention et la révocation avec historique purgé. Politique constatée : seuil 4, fenêtre 1 500 000 ms, cooldown 900 000 ms, sans heures calmes ni pause, une application autorisée. Les quatre horodatages et l’horodatage de l’intervention figurent dans [fixture-logcat.txt](../../logs/corrections-finales/20261020T005633Z/fixture-logcat.txt).

## Validation finale

Environnement : JDK 21, Gradle 9.6, Python 3.12 ; un seul appareil était connecté, l’AVD jetable `atlas_v62_disposable_api34`, API 34, `emulator-5554`. Aucun téléphone Pixel physique n’a été utilisé.

Commandes et résultats :

- `cd android && ./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --console=plain` — succès.
- Les trois cas AtlasLaunch qui échouaient au départ, ainsi que le test de migration/recréation, passent individuellement. La suite complète finale contient 12 cas AtlasLaunch, tous réussis.
- L’exécution de [scripts/v62-complete-instrumentation.sh](../../scripts/v62-complete-instrumentation.sh) via `bash` a réussi sur l’AVD identifié : **48/48 cas génériques réussis**, 17 classes, 0 échec, 0 erreur, 0 ignoré ; **10/10 reprises ciblées réussies** ; 21 captures PNG valides.
- Les cas AppOps refusé et fixture réelle sont filtrés du run générique faute de leurs arguments hôte, puis exécutés par le harnais standard ADB et réussis. Aucun root n’a été utilisé.

Les quatre scénarios backend réel restent filtrés faute d’adaptateur/jeton. Aucun appel Gateway ou modèle n’a été effectué. Les deux méthodes d’injection géographique coarse restent filtrées faute du harnais synchronisé ; les preuves S11 consignées dans [REGRESSIONS_INSTRUMENTATION.md](REGRESSIONS_INSTRUMENTATION.md) (fix d’âge 5 ms, précision rapportée 2 000 m, puis timeout réel) n’ont pas été rejouées.

Le premier lancement du script a été arrêté en prévol (`77`) parce que Gradle avait désinstallé l’APK cible et que le script ne pouvait pas lire son état de permissions ; aucune modification ADB n’avait encore eu lieu. L’APK a été réinstallé sur l’AVD jetable et l’état de test documenté a été appliqué avant le run final. Le script a restauré coarse accordée, fine refusée, notifications accordées, AppOps UID/package par défaut, localisation activée et géométrie 1080×2400 ; l’APK fixture a été désinstallée.

Pendant les tests instrumentés de suivi, un log class-only `Unable to write local state (IllegalStateException)` est apparu à 02:58:52 UTC. Le chemin utilisateur conserve alors le message d’échec de persistance, et aucun test n’a échoué. Le journal ne contient volontairement ni message d’exception ni stack trace ; la cause de cette occurrence d’écriture distincte reste à suivre si elle se reproduit.

## Journaux

- [Résumé recette](../../logs/corrections-finales/20261020T005633Z/recipe-result.txt)
- [Comptage et préconditions filtrées](../../logs/corrections-finales/20261020T005633Z/counts.txt)
- [XML des tests instrumentés](../../logs/corrections-finales/20261020T005633Z/connected-results.xml)
- [Statut externe AppOps/fixture/paysage](../../logs/corrections-finales/20261020T005633Z/external-test-status.txt)
- [État AVD restauré](../../logs/corrections-finales/20261020T005633Z/state-restored.txt)
- [Vérification AVD après le script](../../logs/corrections-finales/20261020T005633Z/avd-postrun-check.txt)
- [JVM, lint et assemblages](../../logs/corrections-finales/20261020T005633Z/gradle-final.log)
- [Focus persisté après recréation](../../logs/corrections-finales/20261020-targeted-atlas-launch/focus-persisted-after-restart.log)
- [Quarantaine sous-état invalide](../../logs/corrections-finales/20261020-targeted-atlas-launch/malformed-recovery.log)
- [Quarantaine schéma inconnu](../../logs/corrections-finales/20261020-targeted-atlas-launch/unknown-schema-recovery.log)
- [Consentements et objectif après recréation](../../logs/corrections-finales/20261020-targeted-atlas-launch/migration-restart.log)
- [Prévol initial arrêté sans modification](../../logs/regressions-instrumentation/20261020T004744Z/preflight.txt)
- [Échec fixture avant correction du contrôleur de test](../../logs/regressions-instrumentation/20261020T004807Z/usage-four-fixture-opens.log)
