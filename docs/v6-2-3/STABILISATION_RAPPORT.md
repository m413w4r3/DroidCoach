# Stabilisation produit ATLAS V6.2.3 — PROD

Date : 2026-10-21  
Base vérifiée : `aa9b2daf703d7653b33d6433e3d97b915c955731` (`aa9b2da`)  
Branche de ce worktree : `worktree/ancient-trail`. La branche `feat/atlas-v6-2-3` vérifiée dans le checkout principal pointait aussi sur `aa9b2da`. `docs/v6-2/` était présent ; aucun rebase n’a été nécessaire.

## Résultat

Aucun défaut produit n’a été reproduit. Les tests avant et après les ajouts passent ; aucun fichier de production n’a été modifié. Les quatre nouveaux tests couvrent la reprise après interruption de transaction, le contexte Coach avec révocation pendant une requête, et l’idempotence de publication de notification.

| Parcours vérifié | Statut | Preuve |
|---|---|---|
| Persistance et reprise locale, y compris interruption après commit racine et avant publication mémoire | PASS | `GoalFocusPersistenceRecoveryTest.linkedFocusRootCommitSurvivesRestartBeforePublicationAndCreditsWorkOnceOffline` ; tests de récupération `AtlasControllerInitialStateRecoveryTest` |
| Focus lié, crédit unique après fin, doublon session courante/historique, répétitions de lecture | PASS | Nouveau test de reprise ci-dessus et `GoalFocusStartTransactionTest.controlledClockCreditsOnlyCompletedLinkedWorkAndRepeatedReadsDoNotDoubleCredit` |
| Annulation sans crédit et double démarrage sans deuxième session | PASS | `GoalFocusStartTransactionTest.unlinkedSessionAndCancelledLinkedSessionNeverChangeFocusGoalProgress` et `concurrentDoubleTapStartRequestsCommitOnlyOneFocusSession` |
| Coach reçoit Focus + objectifs consentis ; retrait d’une catégorie coupe le partage aux requêtes suivantes | PASS | Nouveau test de contexte et `AgentCategoryRevocationTest.revokingGoalsDuringAnInFlightCoachRequestRemovesThemFromTheNextRequest` |
| Quarantaine après corruption sans valeur brute | PASS | `AtlasControllerInitialStateRecoveryTest.unparseableDocumentIsQuarantinedAsValueFreeEnvelopeThenGoalPersistsAcrossRecreation` et `unknownSchemaQuarantinesSanitizedCopyAndDoesNotRestoreSharedPayload` |
| Notifications et proactivité : réservation, révocation en vol, non-répétition après livraison/recréation | PASS | `ProactiveCoachSchedulerTest.successfullyDeliveredNotificationIsNotRepeatedAfterSchedulerRecreation` et tests existants de révocation en vol ; tests instrumentés de publication et worker |
| Localisation : consentements distincts, données grossières, révocation OS/partage pendant acquisition | PASS | `LocationPrivacyTest`, `LocationRepositoryTest` et trois tests instrumentés de permission/timeout |
| Rôle HOME, retour au launcher précédent, pause d’urgence et persistance hors réseau | PASS | Tests instrumentés `LauncherPresentationTest` et `AtlasLaunchTest.homeRoleSettingsAreSeparateFromTheCoachDashboardAndLicenseAttributionIsVisible`, plus vérification manuelle sur AVD jetable API 34 |
| Boucle locale objectif → Focus lié → progression → redémarrage, sans backend | PASS | Nouveau test JVM de reprise ; parcours `AtlasLaunchTest.directiveFocusHabitSimulatorAndLibraryJourneysWorkOffline` et `V62RealGatewayContextBridgeTest.localCoachRendersGoalAndAcceptsProposalAfterKeyboardDismissal` |
| API 37 | NOT_RUN | Cette mission a utilisé uniquement l’AVD jetable API 34. |
| Gateway réel / serveur externe | NOT_RUN | Les preuves de boucle locale utilisent stockage local et adaptateurs de test ; aucun service externe n’a été sollicité. |
| Injection d’une position GPS hôte dans le parcours mobilité complet | NOT_RUN | Les méthodes nécessitant le harnais d’injection hôte ne disposaient pas de ce harnais dans cette exécution. Les tests de permission, timeout, capture et révocation ont été exécutés. |
| Téléphone réel | NOT_RUN | Aucun téléphone n’a été utilisé. |
| `lintDebug` | NOT_RUN | Hors vérification demandée ; les compilations debug et androidTest ont réussi. |

Une révocation pendant une requête ne peut pas retirer les octets déjà envoyés au serveur. Le test vérifie que la requête suivante reconstruit son contexte depuis le mandat à jour et n’inclut plus la catégorie révoquée ; la réponse tardive de la requête en cours est traitée selon les barrières d’autorisation existantes.

## Défauts et changements

Défauts reproduits : aucun. En conséquence, aucun correctif de production n’a été appliqué. Aucun changement n’a été nécessaire dans un fichier d’intégration interdit ; aucune demande d’intégration séparée n’est ouverte.

Tests ajoutés :

- `GoalFocusPersistenceRecoveryTest`: commit atomique Focus + objectif, arrêt simulé avant publication des caches, reprise après recréation, fin de séance, déduplication entre session courante et historique, lectures répétées ; le test n’utilise aucun réseau.
- `AgentContextAssemblerTest.consentedCoachContextCarriesLinkedFocusAndGoalProgressAndDropsRevokedGoals`: Focus et progression dans le contexte consenti, puis retrait des objectifs après révocation, tout en conservant Focus consenti.
- `AgentCategoryRevocationTest.revokingGoalsDuringAnInFlightCoachRequestRemovesThemFromTheNextRequest`: serveur loopback local ; révocation alors que la première réponse est bloquée, vérification des corps de la requête initiale puis de la requête suivante.
- `ProactiveCoachSchedulerTest.successfullyDeliveredNotificationIsNotRepeatedAfterSchedulerRecreation`: état `DELIVERED` relu après recréation du scheduler et absence de seconde requête/notification.

Les tests de récupération existants vérifient déjà que le contrôleur remet à la quarantaine une enveloppe sans valeur brute. Le test bas niveau `LocalStateStoreTest.quarantineKeepsRawBlobBeforeAReplacementIsWritten` couvre le contrat interne de stockage isolé ; la garantie de confidentialité est vérifiée au point d’entrée contrôleur, qui ne lui transmet pas la charge brute.

## Baseline JVM et résultat final

Commande de baseline sur `aa9b2da`, avant ajout :

```sh
./gradlew --rerun-tasks :app:testDebugUnitTest --console=plain
```

Sortie observée : `BUILD SUCCESSFUL in 1m 9s`, 24 tâches exécutées. Rapport XML : 57 fichiers, 317 tests, 0 échec, 0 erreur, 0 ignoré. Statut : PASS.

Tests ciblés après ajout :

```sh
./gradlew --rerun-tasks :app:testDebugUnitTest --tests 'dev.atlas.coach.goals.GoalFocusPersistenceRecoveryTest' --tests 'dev.atlas.coach.sync.agent.context.AgentContextAssemblerTest' --tests 'dev.atlas.coach.sync.agent.coach.AgentCategoryRevocationTest' --tests 'dev.atlas.coach.coach.proactive.ProactiveCoachSchedulerTest' --console=plain
```

Sortie observée : `BUILD SUCCESSFUL in 26s`. Rapport XML : 4 classes, 45 tests, 0 échec, 0 erreur, 0 ignoré. Statut : PASS.

Suite JVM complète relancée après la dernière modification :

```sh
./gradlew --rerun-tasks :app:testDebugUnitTest --console=plain
```

Sortie observée : `BUILD SUCCESSFUL in 18s`, 24 tâches exécutées. Rapport XML : 58 fichiers, 321 tests, 0 échec, 0 erreur, 0 ignoré (baseline + 4). Statut : PASS.

Compilation des APK :

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
```

Sortie observée : `BUILD SUCCESSFUL in 19s`, 67 tâches actionnables, 48 exécutées et 19 à jour. Statut : PASS.

## Instrumentation sur AVD jetable

AVD créé uniquement pour cette mission sur le port autorisé 5584 :

```sh
/home/nill/Android/Sdk/cmdline-tools/latest/bin/avdmanager create avd -n atlas_v623_prod_api34 -k 'system-images;android-34;google_apis;x86_64' -d pixel_8 -c 4096M
/home/nill/Android/Sdk/emulator/emulator -avd atlas_v623_prod_api34 -port 5584 -no-snapshot -no-snapshot-save -wipe-data -no-boot-anim -no-window -no-audio
adb -s emulator-5584 emu avd name
adb -s emulator-5584 shell getprop sys.boot_completed
```

L’identité retournée était `atlas_v623_prod_api34` et `sys.boot_completed` valait `1`. APK installés sur ce seul serial :

```sh
adb -s emulator-5584 install -r android/app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5584 install -r android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

Les deux sorties étaient `Success`. Commandes instrumentées exécutées ; chacune s’est terminée par `OK (N tests)` :

```sh
adb -s emulator-5584 shell am instrument -w -e class dev.atlas.coach.app.GoalFocusStartInstrumentationTest dev.atlas.coach.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5584 shell am instrument -w -e class dev.atlas.coach.FocusIntegrationInstrumentationTest dev.atlas.coach.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5584 shell am instrument -w -e class dev.atlas.coach.app.ProactiveCoachNotificationPublisherInstrumentationTest dev.atlas.coach.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5584 shell am instrument -w -e class dev.atlas.coach.app.ProactiveCoachNotificationInstrumentationTest dev.atlas.coach.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5584 shell am instrument -w -e class dev.atlas.coach.app.LauncherPresentationTest dev.atlas.coach.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5584 shell am instrument -w -e class dev.atlas.coach.AtlasLaunchTest#homeRoleSettingsAreSeparateFromTheCoachDashboardAndLicenseAttributionIsVisible dev.atlas.coach.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5584 shell am instrument -w -e class dev.atlas.coach.AtlasLaunchTest#directiveFocusHabitSimulatorAndLibraryJourneysWorkOffline dev.atlas.coach.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5584 shell am instrument -w -e class dev.atlas.coach.app.V62RealGatewayContextBridgeTest#localCoachRendersGoalAndAcceptsProposalAfterKeyboardDismissal dev.atlas.coach.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5584 shell am instrument -w -e class dev.atlas.coach.app.ProactiveControllerPublicationAuthorizationInstrumentationTest dev.atlas.coach.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5584 shell am instrument -w -e class dev.atlas.coach.coach.proactive.android.ProactiveCoachWorkerInstrumentationTest dev.atlas.coach.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5584 shell am instrument -w -e class dev.atlas.coach.location.V62LocationCaptureInstrumentedTest#deniedPermissionStateReturnsFunctionalErrorWithoutCrashing dev.atlas.coach.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5584 shell am instrument -w -e class dev.atlas.coach.location.V62LocationCaptureInstrumentedTest#api26Through29UseListenerAndReturnTimeoutOrUnavailableCodes dev.atlas.coach.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5584 shell am instrument -w -e class dev.atlas.coach.location.V62LocationCaptureInstrumentedTest#permissionRevocationDuringAcquisitionIsRecheckedBeforeReturningFix dev.atlas.coach.test/androidx.test.runner.AndroidJUnitRunner
```

Comptes observés par classe, tous PASS : 7 + 1 + 2 + 6 + 2 + 1 + 1 + 1 + 5 + 2 + 1 + 1 + 1 = 31 tests, 0 échec.

Vérification manuelle HOME : dans les réglages Android des applications Home, ATLAS a été sélectionné, la touche Home a ouvert le paquet `dev.atlas.coach`, puis l’action « Launcher précédent » a lancé Pixel Launcher. Pixel Launcher a été remis comme rôle HOME par défaut (`com.google.android.apps.nexuslauncher`). La pause d’urgence a été activée, le processus arrêté par `adb -s emulator-5584 shell am force-stop dev.atlas.coach`, puis la touche Home a réouvert ATLAS avec l’action de fin de pause encore visible.

Vérification hors réseau sur le même AVD : Wi-Fi et données ont été désactivés avec `adb -s emulator-5584 shell svc wifi disable` et `adb -s emulator-5584 shell svc data disable`; après sélection d’ATLAS dans les réglages HOME et touche Home, l’écran ATLAS s’est affiché et la pause d’urgence restait active et accessible. Aucun téléphone réel n’a reçu de commande. Un Pixel connecté était visible dans `adb devices`, mais n’a pas été utilisé.

## Limites restantes pour V6.3

- Couvrir API 37 avec un AVD jetable et conserver les preuves séparées par niveau d’API.
- Ajouter ou documenter le harnais d’injection de position hôte pour la mobilité et exécuter les scénarios de permissions/révocation sur celui-ci.
- Faire la validation QA sur téléphone secondaire prévue par le chantier ; ce rapport ne revendique aucune validation sur Pixel réel.
- Tester les intégrations Gateway réelles séparément, sans substituer leurs résultats aux parcours locaux hors ligne.
- Continuer à vérifier la barrière de révocation pour chaque catégorie et chaque callback asynchrone avant publication ou transport.

## Nettoyage de l’AVD

Après les validations, Pixel Launcher a été remis comme rôle HOME, puis seul l’AVD `atlas_v623_prod_api34` a été arrêté et supprimé :

```sh
adb -s emulator-5584 emu kill
/home/nill/Android/Sdk/cmdline-tools/latest/bin/avdmanager delete avd -n atlas_v623_prod_api34
```

Sorties observées : `OK: killing emulator, bye bye` puis `AVD 'atlas_v623_prod_api34' deleted.` Statut : PASS. Aucun autre AVD ni appareil visible n’a été modifié.
