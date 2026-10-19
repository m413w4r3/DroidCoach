# Lot E — Mise en œuvre UX

**Date :** 2026-10-19
**Branche :** `feat/atlas-v6-1-stabilisation`
**Périmètre :** intégration Android UX issue de l’audit Lot D, sans modification de branche ni commit.

## Changements, défauts retenus et fichiers

| Changement | Défaut retenu | Fichiers principaux |
|---|---|---|
| HOME : directive en premier, provenance, état backend et dernier contact; une seule carte Focus/proposition sous la directive; suggestion de contenu retirée; favoris textuels et état vide réduit | En l’absence d’état agent distant, conserver une directive et une proposition locale démontrables hors ligne; priorité à la session Focus déjà active | `LauncherHomeScreen.kt`, `HomeAgentState.kt`, `AgentHomeState.kt`, `AtlasController.kt` |
| HOME : pied fixe compact avec pause d’urgence et accès Launcher, Réglages, Coach; contenu défilable au-dessus; cibles tactiles d’au moins 48 dp | Abréger les libellés en paysage ou à partir d’un `fontScale` de 1,5; conserver les descriptions accessibles complètes | `LauncherHomeScreen.kt` |
| Coach : conversation, proposition prioritaire puis Accepter / Modifier / Plus tard / Refuser; mandat, partage et journal sur trois destinations dédiées | Garder le Coach utilisable hors ligne avec l’adaptateur local; afficher les actions en langage lisible sans JSON brut | `AgentCoachScreen.kt`, `AtlasApp.kt`, `AtlasScreenId.kt`, `AtlasController.kt`, `AtlasNavigationState.kt` |
| Pomodoro : titre de directive prérempli et modifiable, durée, temps restant, phase et cycles; démarrage, pause et annulation; paramètres avancés en feuille modale | Réglages avancés initiaux : pause longue de 15 min tous les 4 cycles, mode continu désactivé; valeurs persistées anciennes migrées avec repli sûr | `PomodoroSessionSetup.kt`, `FocusRoutinesScreen.kt`, `AtlasScreens.kt`, `AtlasController.kt`, `FocusModels.kt`, `AgentHomeState.kt`, `ActionDispatcher.kt` |
| Démo Focus déplacée hors du parcours quotidien; thème AMOLED conserve un retour tactile non animé | La démo reste dans Diagnostic avancé; le noir AMOLED utilise un retour statique au toucher | `AtlasScreens.kt`, `AtlasTheme.kt` |
| Réparations de compilation et contrats de validation | Importer `testTag`, déclarer l’opt-in Material 3 expérimental et retirer l’import `drawRect` non résolu; transmettre les réglages Pomodoro modifiés au moteur local | `FocusRoutinesScreen.kt`, `PomodoroSessionSetup.kt`, `AtlasTheme.kt`, tests Android et unitaires |

Les décisions réversibles sont consignées dans [`DECISIONS_AUTOMATIQUES.md`](../DECISIONS_AUTOMATIQUES.md), section « V6.1 — Lot E UX ». La branche a été gardée telle quelle. Aucun OSS adapté ou nouvelle dépendance n’est ajouté par ce lot : les composants existants Material 3 / AndroidX sont utilisés. Les références et reprises antérieures sont tracées séparément dans `THIRD_PARTY_NOTICES.md`.

## Validation exécutée

| Commande / périmètre | Résultat |
|---|---|
| `cd android && ./gradlew testDebugUnitTest lintDebug assembleDebug` | **VERT**, BUILD SUCCESSFUL |
| `backend/.venv/bin/python -m pytest -q` | **VERT**, 60 passed, 1 skipped; deux avertissements de dépendance/marker pytest sans échec |
| `cd android && ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.atlas.coach.app.LotEUiInstrumentationTest` | **VERT**, 5 tests : HOME synthétique sombre/AMOLED, proposition Pomodoro, navigation Coach dédiée, persistance provenance, HOME en paysage à 2× |
| Même tâche avec `class=dev.atlas.coach.app.AtlasPresentationTest` | **VERT**, 4 tests : navigation système, HOME et grands textes |
| Même tâche avec `class=dev.atlas.coach.app.LauncherPresentationTest` | **VERT**, 2 tests : tiroir/recherche/favoris et pied à 2× |
| Même tâche avec `class=dev.atlas.coach.sync.agent.AgentContractsInstrumentationTest` | **VERT**, 1 test de round-trip Android |

Ces classes ont été lancées séparément avec un filtre positif `class=`. Les tests UI de présentation utilisent maintenant le contexte du paquet de test pour isoler l’état local; `LauncherPresentationTest` ne lance pas l’application sélectionnée. **Aucun test backend réel n’a été lancé** : aucun scénario avec URL et jeton backend n’a été fourni. Les classes d’intégration qui effacent l’état de l’application, modifient AppOps, accordent des permissions ou configurent un jeton n’ont pas été incluses dans les exécutions positives finales.

## Écart de validation et état de l’AVD

La première tentative de `connectedDebugAndroidTest` a utilisé un argument `notClass` qui n’a pas filtré le runner. Il a exécuté 26 tests et en a rapporté 5 en échec : une assertion de texte obsolète dans `AtlasPresentationTest`, une dans `LauncherPresentationTest`, deux dans les nouvelles assertions Lot E, et une dans `AtlasVisualCaptureTest`. Cette dernière classe a donc été lancée une fois par erreur, malgré la consigne explicite de ne pas la lancer sur l’AVD. Elle n’a pas été relancée. Les assertions in-scope ont été corrigées puis les classes sûres ci-dessus sont vertes; la capture visuelle n’est pas validée après les changements.

Le premier rapport contient aussi des classes qui effacent `atlas_local_state` avec le contexte cible ou modifient AppOps/permissions. Je n’avais pas de snapshot préalable de ces données; **je ne peux pas rétablir leur état antérieur**. Après les tests, le contrôle en lecture seule a confirmé que le rôle HOME est `com.google.android.apps.nexuslauncher`, que `fontScale` vaut `1.0` et que l’AVD est démarré. La requête de paquets ne retrouve plus `dev.atlas.coach`; je ne l’ai pas réinstallé.

## NON TESTÉ / limites

- Capture `AtlasVisualCaptureTest` après changements : non testée; seule l’exécution accidentelle antérieure est signalée ci-dessus.
- Scénarios OpenClaw/FastAPI réels et tests dépendant de `backend_url` / `backend_token` : non testés.
- Reste de la suite instrumentation : non exécuté dans le filtre positif final, car plusieurs tests ciblent les préférences de l’application ou changent les AppOps/permissions de l’AVD.
- Appareil physique, TalkBack, appareils de petite taille et mesures d’énergie/performance : non testés.
- L’API `UsageStatsManager` reste retardée et non déterministe; aucune interception instantanée n’est promise.

Les sorties backend complètes, y compris les deux avertissements, sont disponibles dans le terminal de cette session. Aucun commit, push ou merge n’a été fait.

## E2 — Corrections visuelles ciblées (2026-10-19)

**Périmètre :** les trois défauts de la section « Défauts encore présents » de la revue visuelle : HOME en paysage, badge Sync, libellé d’action dans l’éditeur de proposition. Aucun autre parcours n’a été modifié pour E2.

- **HOME paysage :** la zone défilable est bornée/clippée au-dessus du pied et séparée de celui-ci; le pied garde la pause d’urgence sur une ligne dédiée et place Launcher précédent, Réglages HOME et Coach sur une ligne compacte. Les quatre contrôles conservent une hauteur minimale de 48 dp. `HomeLandscapeFooterInstrumentationTest` mesure la séparation entre le CTA et le pied à fontScale 1.0 et 2.0.
- **État Sync :** `SyncStatusPresentation` dérive un texte cohérent depuis l’activation, le consentement de catégorie, l’URL/jeton configurés, l’état de transport et l’observation du Lot C. Les états visibles distinguent désactivé, activé sans consentement, configuration requise, contact en cours, joignable, observation expirée, hors ligne, authentification requise et erreur. La fraîcheur de l’observation reste celle du Lot C (cinq minutes).
- **Action principale :** l’éditeur montre des libellés français, dont `open_coach` → « Ouvrir le Coach » et `focus.start` → « Démarrer un focus ». À l’enregistrement, le libellé est reconverti en identifiant existant; le contrat réseau n’est pas modifié. Les IDs inconnus restent non réécrits s’ils ne sont pas édités.

### Validation E2

| Commande / périmètre | Résultat |
|---|---|
| `cd android && ./gradlew testDebugUnitTest lintDebug assembleDebug` | **VERT**, BUILD SUCCESSFUL; 169 tests JVM, 0 échec, lint et assemble réussis |
| `connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.atlas.coach.app.HomeLandscapeFooterInstrumentationTest` | **VERT**, 2 tests; lancé sur `Pixel_9_Pro_XL_2` avec viewport paysage temporaire `wm size 2992x1344` |
| Même tâche, `class=dev.atlas.coach.app.V61LandscapeCaptureTest` | **VERT**, 1 capture 16 et assertions de pied en paysage |
| Même tâche, `class=dev.atlas.coach.app.V61PortraitCaptureTest` | **VERT**, 1 test; états Sync et éditeur local, captures 20 et 22 |

Les trois classes Android ont été sélectionnées séparément avec `class=`; aucun `notClass` ni `AtlasVisualCaptureTest` n’a été utilisé. Les tests de présentation et leurs fixtures écrivent dans le contexte du paquet de test. L’AVD contenait des données d’application utiles au début du travail, donc AtlasVisualCaptureTest a été évité. Aucun `wipe-data` ni `pm clear` n’a été exécuté.

Les PNG 16, 20 et 22 sont des sorties directes de `screencap -p`, sans retouche. 16 mesure 2992 × 1344; 20 et 22 mesurent 1344 × 2992. En 22, la capture utilise une fixture `ProposalV2` locale déterministe. Un processus Gateway OpenClaw était présent, mais la classe de capture ne l’a pas appelé; cette image ne valide pas une proposition nouvellement produite par une Gateway. Le statut backend réel et les échanges réseau restent non testés.

Les premiers essais de validation E2 ont échoué avant le passage final : une assertion JVM attendait le mauvais type d’exception; un premier test paysage a été lancé avec l’AVD en portrait; une première capture combinée a rencontré une transition de configuration/une assertion de sémantique Compose trop stricte. L’assertion JVM a été corrigée et les captures ont été séparées en classes paysage/portrait. Les exécutions finales listées ci-dessus sont vertes.

À la fin, le viewport a été remis à sa taille normale, `fontScale=1.0`, rotation automatique activée et Pixel Launcher est le titulaire du rôle HOME. `dev.atlas.coach` n’apparaissait plus dans `pm list packages` après les tests ciblés; l’APK debug a été installé à nouveau avec `adb install -r`, sans `pm clear` ni wipe. Le rôle HOME est resté `com.google.android.apps.nexuslauncher`. Aucun commit, push ou merge n’a été fait.

### NON TESTÉ en E2

- Contact réel avec la Gateway/backend, authentification et états joignable/hors ligne observés sur un serveur.
- TalkBack, Switch Access, écran de petite taille et appareil physique.
- Autres tests instrumentés que les trois classes explicitement listées.
- Comportement sur les appareils dont les dimensions ou densités diffèrent de l’AVD.
