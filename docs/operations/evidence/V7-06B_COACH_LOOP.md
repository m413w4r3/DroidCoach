# V7-06B — boucle Coach Android

## Ticket and scope

- **TASK_ID:** `V7-06B-ANDROID-QA`
- **Statut:** `REVIEW_REQUIRED` — harnais implémenté, validation parent requise
- **BASE_SHA:** `b55062e1890ff10139ba5ce4a1384cb729c5c0f1`
- **Branche/worktree:** `test/v7-06b-android-qa` / `/tmp/atlas-v7-06b-android`
- **Profil demandé:** `codex:gpt-6-luna` / `xhigh` (les métadonnées de ce profil ne sont pas exposées à cette session)
- **Écriture exclusive:** test instrumenté V7-06B, `scripts/v7-06b/android/**`, ce brouillon
- **Interdictions:** code produit, Gradle/manifests, tests existants, scripts backend, ADB/AVD, push/PR/merge distant
- **Oracle:** APK QA + APK AndroidTest compilés hors ligne, classe instrumentée présente, tests JVM demandés, `git diff --check`

## Contenu livré

Le test `V7_06B_CoachLoopInstrumentedTest` exige `test_action=apply|reject`, `relay_url`,
`server_id` et `server_public_key`. Il refuse les packages hors espace QA V7-06B, les
consentements autres que `UNKNOWN v1`, les plans persistés, une inbox préexistante, une
conversation précédente et un pairing déjà chargé. Il importe le pairing privé dans le
Keystore puis supprime le fichier.

Le test crée un plan local du jour version 1, ajoute un unique Focus synthétique et confirme
son aperçu pour obtenir le plan approuvé v2. Il configure le relais et le Coach V4 en
mandat `PROPOSALS_ONLY`, accorde les deux consentements, vérifie leur reçu, le reçu du plan
v2, `FRESH` et `SYNCED`, puis envoie le scénario contrôlé à `sendAgentMessage`. Le contrôleur
crée son `request_id` V4/clé d’idempotence UUID à chaque envoi; le harnais génère aussi un
`run_id` et une clé d’idempotence de proposition à placer dans le prompt. Core remplace cette
dernière par sa propre UUIDv5, dérivée du `request_id`, du device et du corps canonique; le test
vérifie la clé réellement stockée sans la confondre avec celle du prompt.

La réponse doit être connectée, avoir l’état d’outil `completed`, compter au moins deux
appels et annoncer un seul identifiant de proposition. Le test ne déduit aucun nom d’outil
du texte assistant. Il vérifie ensuite l’entrée inbox A0 / `NONE_AT_CORE`, sa révision
unique, son absence de conflits et le décalage de dix minutes. La durée peut être omise dans
`changes`, mais le `diff.after.duration_minutes` doit rester strictement égal à la durée
d’origine. APPLY passe par
`PlanController.applyInboxProposal` comme `PlanScreen` (A0 suit `PlanRevisionEngine`, sans
consentement A1); REJECT passe par
`rejectInboxProposal`. Les deux chemins vérifient l’ACK durable après relecture disque, puis
une seconde passe de sync sans nouvelle proposition, révision ou ACK. La version finale du
plan et le reçu Core sont vérifiés avant la révocation synchronisée des deux consentements.

`run_qa.py build` ne touche pas ADB. `run_qa.py run` exige un serial explicite et le
`stack.json` privé du parent, refuse un package déjà installé et les appareils physiques par
défaut, n’utilise ni `pm clear` ni `uninstall`, et ne peut cibler que
`dev.atlas.coach.qa.v706b.*`. Le pairing et le logcat sont expurgés des secrets. Le runner
valide `core_db`, les fichiers de jetons, `proxy_trace` et `private_root`, mais ne démarre ni
n’arrête de services.

## Preuves

| Oracle | Résultat | Preuve |
|---|---|---|
| `:app:assembleQa :app:assembleQaAndroidTest -PqaTestBuildType=qa -PqaSuffix=.qa.v706b.compile --offline` | `PASS` | `GRADLE_USER_HOME=/tmp/atlas-gradle-home ./gradlew :app:assembleQa :app:assembleQaAndroidTest -PqaTestBuildType=qa -PqaSuffix=.qa.v706b.compile --offline`; `BUILD SUCCESSFUL` en 2 s, 67 tâches actionable (4 exécutées, dont `compileQaAndroidTestKotlin`) |
| Présence de `V7_06B_CoachLoopInstrumentedTest` dans l’APK AndroidTest | `PASS` | Scan ZIP de `android/app/build/outputs/apk/androidTest/qa/app-qa-androidTest.apk`: classe trouvée dans `classes7.dex` (12 fichiers DEX); IDs vérifiés: `dev.atlas.coach.qa.v706b.compile` et `.test` |
| `:app:testDebugUnitTest --offline --rerun-tasks` | `PASS` | `GRADLE_USER_HOME=/tmp/atlas-gradle-home ./gradlew :app:testDebugUnitTest --offline --rerun-tasks`; `BUILD SUCCESSFUL` en 34 s, 24 tâches exécutées |
| Runtime ADB/AVD APPLY | `NOT_RUN` | Aucun ADB/AVD lancé par cette session |
| Runtime ADB/AVD REJECT | `NOT_RUN` | Aucun ADB/AVD lancé par cette session |
| Runner Python (`py_compile`, aide CLI, parser résultat) | `PASS` | Syntaxe compilée; aides CLI valides; simulation de 8 sorties: `INSTRUMENTATION_CODE: -1` et `OK (n test[s])` acceptés, marqueurs d’échec/code retour non nul/sortie sans succès refusés |
| `git diff --check` | `PASS` | Vérification whitespace du diff final; aucun écart |

## Limites et reprise

Le runner est prêt pour un opérateur ADB unique. La commande de pairing utilise le flux
standard de `adb shell -T` directement vers `run-as`; les valeurs de jetons du pairing/Core
sont masquées dans la sortie d’instrumentation et le logcat sauvegardé. Les parcours
APPLY/REJECT nécessitent un Core/relay/OpenClaw isolé répondant aux contrats V4 et un pairing
synthétique éphémère. Aucune preuve de runtime, aucun test Pixel et aucune commande de
déploiement ne sont revendiqués.
