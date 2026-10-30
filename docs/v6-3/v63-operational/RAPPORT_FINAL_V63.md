# ATLAS V6.3 — Rapport final de consolidation

Date : **2026-10-30**
SHA du code intégré : `d7a30c1697858e0c10d8a8496997dc4c0a321bcf`
Branche du code : `v63/integration-core-v4`
Base wave9 : `92ce000745f8188153fff4ab60933da0287fdc89`

## Verdict

**GO local du code** au SHA `d7a30c1697858e0c10d8a8496997dc4c0a321bcf` : les suites locales backend, relais PC et Android passent, les tâches lint/build indiquées passent et la revue de code est `ACCEPTED — GO`, sans P0/P1. La revue de code était statique ; le relecteur n'a pas relancé les tests.

La qualification Pixel de cet APK est **PASS_PARTIEL** (installation, smoke ciblé et un test instrumenté), sans qualification complète. La preuve runtime des six outils via Gateway réel est **GO_OPENCLAW_TOOLS ACCEPTED, portée limitée** : Core synthétique seulement, production indisponible. A4-4 reste **BLOCKED** par la transformation amont 4001→1012. La clôture runtime complète V6.3 n'est pas prononcée ; plusieurs oracles R01-R12 restent partiels, NOT_RUN ou BLOCKED.

## Périmètre livré et provenance

- Intégration finale locale à partir de la base wave9 `92ce000745f8188153fff4ab60933da0287fdc89`.
- Merge `6d79755` pour sync V5, depuis `e77064856bdfccac6ee18afb81ac1df38ecd3e50`.
- Merge `82e6af2` pour A1 ACK, depuis `f1e424f46548090110a4102048550d539bd54c10`.
- Merge `d7a30c1` du harnais E2E, depuis `c83f87f78f79b8d74427266df9772c7bd36b4bfd`. Les commits `a27b3a7`, `3aad1d3`, `ced76d4` et `c83f87f` sont test-only.
- Le harnais ciblé passe 3/3 et la suite JVM 863/863 sur `c83f87f` ; ces tests sont inclus dans les 865/865 au SHA final. L'E2E backend est inclus dans les 315 tests backend passés. Cela valide des tests locaux et ne prouve pas un fil rouge réseau/Gateway/ACK runtime.
- Le plugin `atlas_core_tools` 0.1.0 a été activé et chargé sur le Gateway OpenClaw pour la preuve runtime avec Core synthétique. Le Core de test a ensuite été arrêté ; le plugin reste configuré sur `127.0.0.1:18100`, où aucun Core n'écoute actuellement.
- Les intégrations Etar, wger et localisation ne sont pas livrées ni déclarées PASS.

## Sessions finales

| Rôle | Tâche / session | Modèle et effort effectifs |
|---|---|---|
| Android JVM et builds | `V63-FINAL-ANDROID-TEST` — `d9a1ad03-b7ba-43f2-a20b-ac7e66aa6ea6` | `codex/gpt-6-luna / xhigh` |
| Backend | `V63-FINAL-BACKEND-TEST` — `ff5f4f41-e72a-4a32-b708-3bebed1b9d3c` | `codex/gpt-6-luna / xhigh` |
| Relais PC | `V63-FINAL-RELAY-TEST` — `7114dedd-7bc8-49ea-9d77-a544e65baf37` | `codex/gpt-6-luna / xhigh` |
| Revue finale du code | `V63-REVIEW-FINAL-CODE` — `8c374793-f73b-4e94-a409-67e3a9d7aa2f` | `codex/gpt-6-luna / xhigh` |
| Harnais E2E | `V63-E2E-HARNESS` — `fed39621-d635-41bd-bd83-006f47a4c159` | `codex/gpt-6-luna / xhigh` |
| Qualification Pixel finale | `a363ffe5-ec91-4982-a772-cee217ff73af` | Profil non consigné |
| Preuve runtime OpenClaw | `765804f3-6467-4902-957d-525ef72ee41a` | Profil non consigné |
| Revue indépendante runtime R2 | `a4920cea-3024-4712-8e09-349d27c97832` | `codex/gpt-6-luna / xhigh` |

## Tableau de preuves locales

| Domaine | Résultat au SHA final | Environnement et commande | Portée / limite |
|---|---|---|---|
| Backend | 316 collectés, 315 passed, 0 failed, 1 skipped préexistant ; 21,91 s | Depuis `backend/`, Python 3.12.3, pytest 8.4.0, environnement isolé ; `/tmp/atlas-test-venv/bin/python -m pytest -ra` (lanceur avec `/usr/bin/time`) | Session `V63-FINAL-BACKEND-TEST`, `ff5f4f41-e72a-4a32-b708-3bebed1b9d3c`, `codex/gpt-6-luna / xhigh`. Skip : `test_real_gateway_plan_returns_valid_proposals`, qui requiert `ATLAS_REAL_GATEWAY=1`. Aucun accès Gateway réel. |
| Relais PC | Typecheck PASS ; build PASS ; 17 fichiers, 89 passed / 0 failed / 0 skipped, 5,89 s total | Depuis `integrations/relay/pc`: `./node_modules/.bin/tsc -p tsconfig.json --noEmit`, `npm run build`, `npm test` ; relais local `127.0.0.1:4400` | Session `V63-FINAL-RELAY-TEST`, `7114dedd-7bc8-49ea-9d77-a544e65baf37`, `codex/gpt-6-luna / xhigh`. Vérification locale seulement. Le flake historique `session.test.ts` n'a pas été reproduit dans le run final 89/89. |
| Android JVM | 865 tests ; 0 échec, 0 erreur, 0 ignoré ; 114 suites XML ; 26 s | Depuis `android/`, `GRADLE_USER_HOME=/tmp/atlas-gradle-home ./gradlew :app:testDebugUnitTest` | Session `V63-FINAL-ANDROID-TEST`, `d9a1ad03-b7ba-43f2-a20b-ac7e66aa6ea6`, `codex/gpt-6-luna / xhigh`. Aucun test instrumenté inclus. |
| `assembleDebugAndroidTest` | PASS, 19 s | Depuis `android/`, `GRADLE_USER_HOME=/tmp/atlas-gradle-home ./gradlew :app:assembleDebugAndroidTest` | Session Android `d9a1ad03-b7ba-43f2-a20b-ac7e66aa6ea6`, `codex/gpt-6-luna / xhigh`. Aucun test instrumenté n'a été exécuté. Avertissement de dépréciation `MODE_BACKGROUND_ACTIVITY_START_ALLOWED`. |
| `lintDebug` | PASS, 0 erreur / 87 avertissements | Depuis `android/`, `GRADLE_USER_HOME=/tmp/atlas-gradle-home ./gradlew :app:lintDebug` | Session Android `d9a1ad03-b7ba-43f2-a20b-ac7e66aa6ea6`, `codex/gpt-6-luna / xhigh`. UseKtx 42, ApplySharedPref 12, ObsoleteSdkInt 14, GradleDependency 7, SetTextI18n 6, DiscouragedApi 2, NewerVersionAvailable 2, AndroidGradlePluginVersion 1, OldTargetApi 1. |
| `assembleDebug` | PASS, 140 s | Depuis `android/`, `GRADLE_USER_HOME=/tmp/atlas-gradle-home ./gradlew :app:assembleDebug` | Session Android `d9a1ad03-b7ba-43f2-a20b-ac7e66aa6ea6`, `codex/gpt-6-luna / xhigh`. Produit l'APK hashé décrit ci-dessous. |
| Revue indépendante | `ACCEPTED — GO`, aucun P0/P1 | Revue statique sur `d7a30c1` | Session `V63-REVIEW-FINAL-CODE`, `8c374793-f73b-4e94-a409-67e3a9d7aa2f`, `codex/gpt-6-luna / xhigh`. Le relecteur n'a pas relancé les tests. |

Le harnais E2E (ciblé 3/3 et JVM 863/863 sur `c83f87f`) est rattaché à la session `V63-E2E-HARNESS`, `fed39621-d635-41bd-bd83-006f47a4c159`, `codex/gpt-6-luna / xhigh`.

## Preuves runtime finales

### Pixel 9 Pro XL — qualification partielle

La preuve est liée au SHA source `d7a30c1697858e0c10d8a8496997dc4c0a321bcf` et à un seul Pixel 9 Pro XL, Android 17/API 37 (`sdk_full=37.1`, correctif de sécurité 2026-09-05). Le serial est masqué.

- APK production installé : versionName `0.1.0`, versionCode `1`, targetSdk `36`, 15 740 291 octets, SHA-256 `08f45c3743df80b57f653ba41472514e8d006fc3068aca47be041f96a20975c9`, certificat V2 `da3014662eb65e50a87ef364a2e583c331398795b222e3b37b1a16553a00eeee`. `adb install -r` a réussi ; l'APK récupéré après installation est identique bit à bit au candidat. L'APK précédemment installé faisait 15 527 299 octets, SHA-256 `688e2da357d4e6d1dc163675514ee1df28378acadab02e505f5310d1b146cff1`.
- Une sauvegarde privée app-only antérieure à l'installation (336 896 octets, 34 entrées) a été vérifiée ; extraction hors production vérifiée puis temporaire supprimée. Aucun chemin ni empreinte de sauvegarde privée n'est publié.
- HOME reste `dev.atlas.coach` avant/après. L'état des permissions n'a pas été modifié : notifications, calendriers et localisation restent refusés ; `GET_USAGE_STATS` reste dans son état par défaut, sans entrée explicite.
- Smoke : Accueil/Aujourd'hui PASS ; Plan puis retour Accueil PASS ; Focus NOT_RUN. Un logcat borné ne montre aucun crash fatal ATLAS. Aucune capture n'a été produite.
- APK de test instrumenté : 1 490 836 octets, SHA-256 `11df52f2cc36d826875430f46c172cca251179f8f41178b69cbb4dbd7aa3d7dd`, package `dev.atlas.coach.test`, cible `dev.atlas.coach`, certificat identique. `E2eV63InstrumentationTest` exécuté seul : `OK (1 test)`, 1/1 PASS ; la production est restée inchangée.
- `FocusGaugeInstrumentationTest` est BLOCKED/NOT_RUN. `AtlasController.refreshBackgroundUsageWork()` (`AtlasController.kt:743`) peut appeler `UsageObservationWorkScheduler.cancel()` (`AtlasController.kt:1425`) et annuler le WorkManager UsageStats de production ; l'isolation du test ne couvre pas WorkManager. C'est une limite du test, pas un défaut produit démontré. Aucune commande `am instrument` n'a été lancée pour ce test.

La qualification Pixel reste **PARTIELLE** : Focus manuel, notifications, calendrier, localisation, offline, accessibilité et parcours A2/A1 complet restent non exécutés.

### OpenClaw — preuve runtime limitée à Core synthétique

OpenClaw `2026.10.5` build `1b58cb9` : `openclaw-gateway.service` actif, health OK et stable pendant le suivi. Le plugin `atlas_core_tools` est installé, activé et chargé. Configuration validée : `coreBaseUrl=http://127.0.0.1:18100`, profil `minimal`, `alsoAllow` contient exactement les six outils imposés, `deny` couvre Gateway/session_status/presence, aucune clé `allow`.

Les fichiers installés correspondent aux builds liés à `d7a30c1` : `contracts.js` SHA-256 `f1f2dd020e16070016270b2e37301a6785a5962b505f0b631c9e132c530e2d52`, `core-client.js` `9ebcd06fec80139af9904d46570bc46a05acd05589654fa7d7f58b40f9a87eca`, `index.js` `ff418b3493c6c680a51b9eb51356695071c6b165e23758d055fc75f638a3cb0a`; plugin installé 84 696 943 octets, SHA-256 `4286e9171da52f40ad3f6e45c501b23b14704037cce476752e8e816c5b624926` ; hash de la configuration initiale `f35713bcd6bc56bd7081b7c38c3d4fd129c2bd1fd5822fe07602b7d5ed46265c`. Le résumé public assaini `/tmp/v63-openclaw-runtime-proof-summary.json` a le SHA-256 `c971a36e079976b564338b33090785347170a754dbfab1977aa1a75cea822630`.

Les six outils ont été découverts et invoqués via le Gateway réel avec un Core isolé synthétique : `atlas_get_day_plan` a retourné READY/FRESH v2 ; `atlas_get_activity_status`, PLANNED/FRESH/ANDROID_SYNC ; `atlas_propose_plan_revision`, PENDING_ANDROID/A0_USER_REVIEW/NONE_AT_CORE. `atlas_get_usage_summary`, `atlas_get_goal_progress` et `atlas_get_training_plan` ont retourné un `DATA_UNAVAILABLE` structuré sur projection synthétique vide. Aucun outil interdit n'a été exposé ; aucune mutation Android et aucun ACK (0) n'ont eu lieu.

La revue indépendante runtime R2 (`a4920cea-3024-4712-8e09-349d27c97832`, `codex/gpt-6-luna / xhigh`) conclut **GO_OPENCLAW_TOOLS ACCEPTED, portée limitée**. Le Core synthétique a été arrêté par SIGTERM ; aucun listener ne restait sur `18100` à 14:08:57. Le Gateway est resté actif et health OK ; son PID `93429` est resté stable pendant le suivi de revue, sans redémarrage de suivi. Le plugin reste activé et configuré sur `18100`, mais aucun Core réel compatible n'est disponible sur 18100 ; les outils ne sont donc pas disponibles en production. Le Core live `8100` n'a pas été utilisé. Cette preuve ne qualifie ni la disponibilité production ni le chemin Android→Core/Gateway ; R01 reste partiel.

## APK final conservé

- Nom : `app-debug.apk`; `versionName=0.1.0`, `versionCode=1`.
- Taille : **15 740 291 octets**.
- SHA-256 : `08f45c3743df80b57f653ba41472514e8d006fc3068aca47be041f96a20975c9`.
- Copie durable : espace d’artefacts QA privé hors dépôt, sous-dossier `V63-FINAL-d7a30c1/app-debug.apk`.
- Permissions : dossier `0700`, fichier `0600` ; `cmp` a confirmé une copie identique.

Cet APK exact a été installé pendant la qualification Pixel partielle ; cela ne signifie pas que le parcours complet est qualifié.

## Sécurité, consentement et autorité locale

- Android reste l'autorité du plan local et des effets appliqués ; un état projeté par le Core n'est pas une preuve de l'état courant du téléphone.
- Les propositions IA ne suffisent pas à autoriser une mutation. A0 reste le défaut ; A1 dépend d'un consentement distinct, révocable et vérifié par des règles déterministes. Refus ou révocation ne doivent pas produire d'effet.
- Les catégories protégées ou non classées restent verrouillées. Les limites locales A1 décrites par D63-36 — quotas, versions, périmètre et annulation — restent bornées ; cette preuve locale ne remplace pas une qualification runtime.
- Le plugin OpenClaw est activé avec allowlist des six outils imposés ; la preuve d'invocation via Gateway est limitée au Core synthétique. Aucun outil interdit, shell ou contrôle Android n'a été exposé.
- Aucune donnée personnelle, secret, token ou contenu de sauvegarde n'est reproduit dans ce rapport. Une sauvegarde privée antérieure au run Pixel existe et est référencée dans le journal QA privé ; aucun contenu, chemin personnel ni empreinte de sauvegarde n'est reproduit ici.

## Limites runtime restantes

### Pixel Android 17 / API 37

La qualification du Pixel final est **PASS_PARTIEL**, liée à l'APK final hashé. Installation, identité de l'APK, smoke Aujourd'hui/Plan et E2E instrumenté 1/1 sont prouvés. Focus, notifications, calendrier, localisation, offline, accessibilité et parcours A1/A2 complets restent non exécutés ; ne pas prononcer une qualification Pixel complète.

### OpenClaw

**GO_OPENCLAW_TOOLS ACCEPTED, portée limitée** après revue indépendante R2 : six outils découverts et invoqués via Gateway réel avec Core synthétique. La disponibilité production n'est pas démontrée : Core test arrêté, aucun listener sur `18100`, aucun Core réel compatible sur 18100 et plugin activé toujours configuré vers cette adresse. Aucun fil Android→Core réel, aucune mutation Android et aucun ACK ne sont prouvés. La preuve de cas négatifs, idempotence et redémarrage reste à compléter pour R01.

### Relais A4-4

A4-4 reste **BLOCKED**. L'image amont transforme le protocole 4001 en 1012 ; le proxy local préserve 4001. La preuve locale du proxy ne corrige ni ne valide l'image amont.

## P2 et limites acceptées

### Expiration inbox et reçu durable

P2 non bloquant accepté dans D63-39 : après le commit local A1 et l'écriture durable du reçu, si l'ACK APPLIED échoue puis le repli UNCERTAIN échoue aussi, l'entrée reste RECEIVED. Le worker peut ensuite la clore EXPIRED sans consulter le reçu (`CoreV4SyncWorker.kt:221`, `InboxV4Repository.kt:327`) ; `applyInboxProposal` traite EXPIRED avant la preuve durable (`PlanController.kt:518/529`).

Impact constaté : divergence possible entre le journal ACK et le plan local. Aucun rejeu d'effet n'est identifié ; le consentement est revérifié et Android reste l'autorité. Correctif futur : réconcilier le reçu avant toute expiration dans les deux chemins ; si l'état reste inconnu, laisser l'entrée en attente.

### Autres limites P2

- La rétention de l'inbox et de la quarantaine n'est pas bornée.
- Le transport chat V4 n'est pas prouvé au niveau d'un socket réel ; le fil HTTPX chat est persistant.
- L'isolation des tests de notifications reste une limite connue.
- Le verrou de synchronisation est limité au processus.
- L'idempotence de l'enveloppe relais V1↔V2 et la couverture négative du relais restent à compléter.
- `session.test.ts` avait un flake historique ; il ne s'est pas reproduit dans le résultat final 89/89.
- La tâche locale `assembleDebugAndroidTest` a construit l'APK de test sans exécuter d'instrumentation. Une session Pixel distincte a ensuite exécuté uniquement `E2eV63InstrumentationTest` (1/1) ; aucun autre test instrumenté n'a tourné.
- Les 87 avertissements lint restent présents, selon la répartition du tableau de preuves.

## Commandes de reproduction consignées

Commandes consignées, avec leur répertoire de travail :

```sh
# Depuis backend/ ; le lanceur ajoutait seulement /usr/bin/time
/tmp/atlas-test-venv/bin/python -m pytest -ra

# Depuis integrations/relay/pc/
./node_modules/.bin/tsc -p tsconfig.json --noEmit
npm run build
npm test

# Depuis android/ ; les commandes ont été lancées dans cet ordre
GRADLE_USER_HOME=/tmp/atlas-gradle-home ./gradlew :app:testDebugUnitTest
GRADLE_USER_HOME=/tmp/atlas-gradle-home ./gradlew :app:assembleDebugAndroidTest
GRADLE_USER_HOME=/tmp/atlas-gradle-home ./gradlew :app:lintDebug
GRADLE_USER_HOME=/tmp/atlas-gradle-home ./gradlew :app:assembleDebug
```

Les opérations runtime rapportées proviennent des sessions Pixel `a363ffe5-ec91-4982-a772-cee217ff73af`, OpenClaw `765804f3-6467-4902-957d-525ef72ee41a` et revue R2 `a4920cea-3024-4712-8e09-349d27c97832`. Le dossier ne fournit pas de ligne de commande littérale complète pour l'installation Pixel ni pour les appels Gateway ; seule l'opération `adb install -r` est attestée PASS, et l'instrumentation est identifiée par `E2eV63InstrumentationTest`. Aucun chemin privé ni serial n'est nécessaire pour cette reproduction documentaire. `FocusGaugeInstrumentationTest` est resté non lancé ; aucune invocation `am instrument` n'a été exécutée pour ce test.

Ce rapport documentaire n'a pas relancé les suites.

Le résultat final des matrices R01-R12 et T01-T12 se trouve dans [RECETTE_V63.md](RECETTE_V63.md) et dans la matrice canonique [ARCHITECTURE_V63.md §16](ARCHITECTURE_V63.md). R01, R10 et R12 sont partiels ; R02/R03/R06/R07/R08 restent BLOCKED et R04/R05/R09/R11 restent NOT_RUN. Aucun PASS runtime complet n'est déclaré.

## Étapes de clôture restantes

1. Compléter sur l'APK final les parcours Pixel restants : Focus, notifications, calendrier, localisation, offline, accessibilité et A1/A2.
2. Rendre disponible un Core réel compatible sur l'endpoint attendu, prouver la disponibilité production et le chemin Android→Core/Gateway, puis compléter R01 (cas négatifs, idempotence, redémarrage) sans extrapoler du Core synthétique.
3. Corriger l'image amont A4-4 pour préserver le protocole 4001, puis refaire le contrôle relais correspondant.
4. Corriger le P2 d'expiration en réconciliant le reçu avant toute expiration et en laissant un état inconnu en attente.
5. Compléter les autres recettes NOT_RUN. Les intégrations Etar, wger et localisation demeurent hors du périmètre livré.
