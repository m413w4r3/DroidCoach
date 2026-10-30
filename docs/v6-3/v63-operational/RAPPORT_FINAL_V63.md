# ATLAS V6.3 — Rapport final de consolidation

Date : **2026-10-30**
SHA du code final UI intégré : `43214ad6678a1c2576783d3af6a38559de168053`
Branche du code : `v63/integration-core-v4`
Base wave9 : `92ce000745f8188153fff4ab60933da0287fdc89`

## Verdict

**GO local du code** au SHA `43214ad6678a1c2576783d3af6a38559de168053` : le lot UI est intégré localement ; la JVM Android compte 866 tests, sans échec ni test ignoré ; lint, compilation AndroidTest et build de l’APK passent. Les preuves backend/relais antérieures restent liées à `d7a30c1697858e0c10d8a8496997dc4c0a321bcf` et n’ont pas été relancées sur le SHA UI.

Pixel est **PASS_PARTIEL** pour l’APK UI final : installation et smoke ciblé PASS, mais aucun test instrumenté n’a été exécuté sur cet APK et la qualification complète reste ouverte. Le test instrumenté E2E 1/1 documenté précédemment concernait l’APK `d7a30c1`, pas celui de `43214ad`. La preuve runtime OpenClaw antérieure est **INVALIDÉE** après traces `exec` et réponses `AUTH_REQUIRED`. Lors de la nouvelle tentative, le Gateway a redémarré, le plugin était chargé et l’agent Atlas visible ; `tools.catalog` listait 15 outils de plugin, dont les six outils Atlas, mais `tools.effective` pour Atlas était vide (0 outil). Aucun tour Atlas ni appel d’outil n’a été exécuté. La variable `ATLAS_CORE_TOOLS_TOKEN` était absente. `GO_OPENCLAW_TOOLS` reste **BLOCKED**, sans GO ; la configuration a été restaurée byte-for-byte vers `18100` (hash `1d78935…`), Gateway actif/health OK. Le diagnostic indique que le profil exclut les 66 outils cœur, dont exec/process/FS/web fetch, mais le catalogue effectif vide ne certifie pas la surface. L’inventaire runtime vide et le signal `exec` antérieur restent un risque **P1 à auditer** ; P2 : le Core de la tentative était mock/vide. Le Core-only Docker protégé est actif avec verdict **GO limité** : deux secrets distincts Android/outil. La route V4 outil est protégée ; la revue Docker est **ACCEPTED_LIMITED** avec P2 et le redémarrage hôte non testé. Relais/proxy restent non résolus et A4-4 est **BLOCKED** (4001→1012). La clôture V6.3 reste non prononcée.

## Périmètre livré et provenance

- Intégration finale locale à partir de la base wave9 `92ce000745f8188153fff4ab60933da0287fdc89`.
- Merge `6d79755` pour sync V5, depuis `e77064856bdfccac6ee18afb81ac1df38ecd3e50`.
- Merge `82e6af2` pour A1 ACK, depuis `f1e424f46548090110a4102048550d539bd54c10`.
- Merge `d7a30c1` du harnais E2E, depuis `c83f87f78f79b8d74427266df9772c7bd36b4bfd`. Les commits `a27b3a7`, `3aad1d3`, `ced76d4` et `c83f87f` sont test-only.
- Les lots UI intégrés après `d7a30c1` : Accueil/Focus/A1 (`2ecb10f`), Coach (`e59b1ff`), Connexion/appairage (`22856c6`) et correction de texte (`9e0863c`). Le code final est `43214ad6678a1c2576783d3af6a38559de168053`.
- Le harnais ciblé passe 3/3 et la suite JVM 863/863 sur `c83f87f` ; ces tests sont inclus dans 865/865 au SHA `d7a30c1`. La JVM UI finale compte 866 tests au SHA `43214ad`. L’E2E backend est inclus dans les 315 tests backend passés au SHA d7a30c1. Ces résultats locaux ne prouvent pas un fil rouge réseau/Gateway/ACK runtime.
- Le plugin `atlas_core_tools` 0.1.0 est chargé sur le Gateway OpenClaw et l’agent Atlas était visible lors de la tentative finale. `tools.catalog` listait 15 outils de plugin dont les six Atlas, mais `tools.effective` pour Atlas était vide (0 outil) ; aucun tour ni appel Atlas n’a eu lieu. `ATLAS_CORE_TOOLS_TOKEN` était absente. La tentative précédente et la revue R2 restent invalidées après traces `exec` et réponses `AUTH_REQUIRED`. La configuration a été restaurée byte-for-byte vers `127.0.0.1:18100` (hash `1d78935…`) ; Gateway actif/health OK. `GO_OPENCLAW_TOOLS` est **BLOCKED**. Le diagnostic du profil exclut les 66 outils cœur, dont exec/process/FS/web fetch, mais ne certifie pas la surface lorsque `tools.effective` est vide ; le risque P1 `exec` reste à auditer. Le Core de cette tentative était mock/vide (P2).
- Les intégrations Etar, wger et localisation ne sont pas livrées ni déclarées PASS.

## Sessions et provenance des preuves

| Rôle | Tâche / session | Modèle et effort effectifs |
|---|---|---|
| Android JVM et builds | `V63-FINAL-ANDROID-TEST` — `d9a1ad03-b7ba-43f2-a20b-ac7e66aa6ea6` | `codex/gpt-6-luna / xhigh` |
| Backend | `V63-FINAL-BACKEND-TEST` — `ff5f4f41-e72a-4a32-b708-3bebed1b9d3c` | `codex/gpt-6-luna / xhigh` |
| Relais PC | `V63-FINAL-RELAY-TEST` — `7114dedd-7bc8-49ea-9d77-a544e65baf37` | `codex/gpt-6-luna / xhigh` |
| Revue finale du code | `V63-REVIEW-FINAL-CODE` — `8c374793-f73b-4e94-a409-67e3a9d7aa2f` | `codex/gpt-6-luna / xhigh` |
| Harnais E2E | `V63-E2E-HARNESS` — `fed39621-d635-41bd-bd83-006f47a4c159` | `codex/gpt-6-luna / xhigh` |
| UI copy / Accueil-Focus-A1 | `38e59a4a-d330-4ec2-a211-7920f6898d92` | `codex/gpt-6-luna / xhigh` |
| UI Coach | `4421f8db-c232-4bff-bdb2-c2cd88e95631` | `codex/gpt-6-luna / xhigh` |
| UI Connexion | `0cf1ddb4-8939-49f3-a931-d55e482a37e5` | `codex/gpt-6-luna / xhigh` |
| Vérification Android/UI | `a6aa74e5-e8cd-449d-b05c-a47b90465dec` | `codex/gpt-6-luna / xhigh` |
| Qualification Pixel finale | `a363ffe5-ec91-4982-a772-cee217ff73af` | `codex/gpt-6-luna / xhigh` |
| Tentative runtime OpenClaw — INVALIDÉE | `765804f3-6467-4902-957d-525ef72ee41a` | `codex/gpt-6-luna / xhigh` |
| Revue runtime R2 — invalidée pour cette preuve | `a4920cea-3024-4712-8e09-349d27c97832` | `codex/gpt-6-luna / xhigh` |
| Tentative OpenClaw finale | Identifiant non indiqué dans le complément final ; `tools.effective` Atlas = 0, aucun tour exécuté | Résultat fourni par le complément final parent ; diagnostic : exclusion de 66 outils cœur, surface live non certifiée |

## Preuves locales par SHA

| Domaine | Résultat au SHA final | Environnement et commande | Portée / limite |
|---|---|---|---|
| Backend — preuve antérieure | 316 collectés, 315 passed, 0 failed, 1 skipped préexistant ; 21,91 s, au SHA `d7a30c1` | Depuis `backend/`, Python 3.12.3, pytest 8.4.0, environnement isolé ; `/tmp/atlas-test-venv/bin/python -m pytest -ra` (lanceur avec `/usr/bin/time`) | Session `V63-FINAL-BACKEND-TEST`, `ff5f4f41-e72a-4a32-b708-3bebed1b9d3c`, `codex/gpt-6-luna / xhigh`. Skip `test_real_gateway_plan_returns_valid_proposals` ; aucun Gateway réel. Non relancé pour le lot UI. |
| Relais PC — preuve antérieure | Typecheck/build PASS ; 17 fichiers, 89 passed / 0 failed / 0 skipped, au SHA `d7a30c1` | Depuis `integrations/relay/pc`: `./node_modules/.bin/tsc -p tsconfig.json --noEmit`, `npm run build`, `npm test` ; relais local `127.0.0.1:4400` | Session `V63-FINAL-RELAY-TEST`, `7114dedd-7bc8-49ea-9d77-a544e65baf37`, `codex/gpt-6-luna / xhigh`. Preuve locale seulement, non relancée pour le lot UI. |
| Android JVM UI | 866 tests ; 0 échec / 0 ignoré, au SHA `43214ad` | Depuis `android/`, `GRADLE_USER_HOME=/tmp/atlas-gradle-home ./gradlew :app:testDebugUnitTest` | Session de vérification `a6aa74e5-e8cd-449d-b05c-a47b90465dec`, `codex/gpt-6-luna / xhigh`. Aucun test instrumenté inclus. |
| `assembleDebugAndroidTest` — référence d7a30c1 | PASS, 19 s | Depuis `android/`, `GRADLE_USER_HOME=/tmp/atlas-gradle-home ./gradlew :app:assembleDebugAndroidTest` | Session Android `d9a1ad03-b7ba-43f2-a20b-ac7e66aa6ea6`, `codex/gpt-6-luna / xhigh`. Aucun test instrumenté exécuté. Avertissement `MODE_BACKGROUND_ACTIVITY_START_ALLOWED`. |
| `lintDebug` — référence d7a30c1 | PASS, 0 erreur / 87 avertissements | Depuis `android/`, `GRADLE_USER_HOME=/tmp/atlas-gradle-home ./gradlew :app:lintDebug` | Session Android `d9a1ad03-b7ba-43f2-a20b-ac7e66aa6ea6`, `codex/gpt-6-luna / xhigh`. UseKtx 42, ApplySharedPref 12, ObsoleteSdkInt 14, GradleDependency 7, SetTextI18n 6, DiscouragedApi 2, NewerVersionAvailable 2, AndroidGradlePluginVersion 1, OldTargetApi 1. |
| `assembleDebug` — référence d7a30c1 | PASS, 140 s | Depuis `android/`, `GRADLE_USER_HOME=/tmp/atlas-gradle-home ./gradlew :app:assembleDebug` | Session Android `d9a1ad03-b7ba-43f2-a20b-ac7e66aa6ea6`, `codex/gpt-6-luna / xhigh`. Produit l’ancien APK décrit ci-dessous. |
| Android UI au SHA `43214ad` | `assembleDebugAndroidTest` PASS (compilation seule), `lintDebug` PASS (0 erreur/87 avertissements), `assembleDebug` PASS | Depuis `android/` : `./gradlew :app:assembleDebugAndroidTest`, `./gradlew :app:lintDebug`, `./gradlew :app:assembleDebug` | Session de vérification `a6aa74e5-e8cd-449d-b05c-a47b90465dec`, `codex/gpt-6-luna / xhigh`. `assembleDebugAndroidTest` n’a exécuté aucun test instrumenté ; les tests Compose UI ont été compilés, pas exécutés. APK : 17 326 339 octets, version 0.1.0/versionCode 1, SHA-256 `a315ed0473593b2c38730eadd15cd18fa5aab721cc6d46ee3941be833fa2a515`. |
| Revue indépendante | `ACCEPTED — GO`, aucun P0/P1 | Revue statique sur `d7a30c1` | Session `V63-REVIEW-FINAL-CODE`, `8c374793-f73b-4e94-a409-67e3a9d7aa2f`, `codex/gpt-6-luna / xhigh`. Le relecteur n'a pas relancé les tests. |

Le harnais E2E (ciblé 3/3 et JVM 863/863 sur `c83f87f`) est rattaché à la session `V63-E2E-HARNESS`, `fed39621-d635-41bd-bd83-006f47a4c159`, `codex/gpt-6-luna / xhigh`.

## Preuves runtime et état post-redémarrage

### Pixel 9 Pro XL — smoke UI final au SHA 43214ad (`PASS_PARTIEL`)

L’APK final UI installé est lié au code intégré `43214ad6678a1c2576783d3af6a38559de168053` : version `0.1.0`, versionCode `1`, 17 326 339 octets, SHA-256 `a315ed0473593b2c38730eadd15cd18fa5aab721cc6d46ee3941be833fa2a515`. Pixel 9 Pro XL, Android 17/API 37, `sdk_full=37.1`. `adb install -r` a réussi ; l’APK relu après installation a le hash attendu. Certificat V2 identique à l’APK précédent (`da3014662eb65e50a87ef364a2e583c331398795b222e3b37b1a16553a00eeee`). HOME `dev.atlas.coach` et permissions inchangés ; permissions calendrier, notifications et localisation restent refusées. Une sauvegarde privée app-only a été vérifiée avant installation ; aucun chemin, contenu ni empreinte n’est publié.

Smoke UI après défilement, sans capture :

- Accueil — PASS : « Prêt à démarrer » absent ; carte et bouton Focus présents.
- Focus — PASS : texte « Titre modifiable avant le démarrage. » absent ; champ Tâche présent.
- Plan — PASS : « Réorganisation A1 », « Autoriser A1 » et « Configurer A1 » présents ; « Capability A1 » absent ; ouverture/fermeture sans modification.
- Coach — PASS pour le rendu et la garde de consentement : brouillon synthétique `test ui`, bouton Envoyer visible mais désactivé ; message « Consentement de partage manquant ou indisponible. Vérifiez les catégories de données partagées. » ; aucun envoi.
- Connexion relayée — PASS pour l’écran : « Appairer ce téléphone » et champs URL/serverId/X25519/deviceId/jeton visibles ; Connecter désactivé avec l’explication « Appairage absent : créez l'appareil sur le PC, puis saisissez ici les informations fournies. » ; aucune valeur saisie ni connexion.
- Partage backend — PASS pour l’écran : « Connexion au backend » et champs URL/jeton visibles ; aucune valeur saisie.
- Aucun crash fatal observé.

`assembleDebugAndroidTest` a compilé les tests, sans en exécuter. Les nouveaux tests Compose UI ont été compilés mais non exécutés. L’unique E2E instrumenté 1/1 indiqué dans la section suivante est lié à l’ancien APK `d7a30c1`, pas à celui de `43214ad`.

### Pixel 9 Pro XL — E2E instrumenté antérieur lié à d7a30c1

La preuve est liée au SHA source `d7a30c1697858e0c10d8a8496997dc4c0a321bcf` et à un seul Pixel 9 Pro XL, Android 17/API 37 (`sdk_full=37.1`, correctif de sécurité 2026-09-05).

- APK production installé : versionName `0.1.0`, versionCode `1`, targetSdk `36`, 15 740 291 octets, SHA-256 `08f45c3743df80b57f653ba41472514e8d006fc3068aca47be041f96a20975c9`, certificat V2 `da3014662eb65e50a87ef364a2e583c331398795b222e3b37b1a16553a00eeee`. `adb install -r` a réussi ; l'APK récupéré après installation est identique bit à bit au candidat. L'APK précédemment installé faisait 15 527 299 octets, SHA-256 `688e2da357d4e6d1dc163675514ee1df28378acadab02e505f5310d1b146cff1`.
- Une sauvegarde privée app-only antérieure à l'installation (336 896 octets, 34 entrées) a été vérifiée ; extraction hors production vérifiée puis temporaire supprimée. Aucun chemin ni empreinte de sauvegarde privée n'est publié.
- HOME reste `dev.atlas.coach` avant/après. L'état des permissions n'a pas été modifié : notifications, calendriers et localisation restent refusés ; `GET_USAGE_STATS` reste dans son état par défaut, sans entrée explicite.
- Smoke : Accueil/Aujourd'hui PASS ; Plan puis retour Accueil PASS ; Focus NOT_RUN. Un logcat borné ne montre aucun crash fatal ATLAS. Aucune capture n'a été produite.
- APK de test instrumenté : 1 490 836 octets, SHA-256 `11df52f2cc36d826875430f46c172cca251179f8f41178b69cbb4dbd7aa3d7dd`, package `dev.atlas.coach.test`, cible `dev.atlas.coach`, certificat identique. `E2eV63InstrumentationTest` exécuté seul : `OK (1 test)`, 1/1 PASS ; la production est restée inchangée.
- `FocusGaugeInstrumentationTest` est BLOCKED/NOT_RUN. `AtlasController.refreshBackgroundUsageWork()` (`AtlasController.kt:743`) peut appeler `UsageObservationWorkScheduler.cancel()` (`AtlasController.kt:1425`) et annuler le WorkManager UsageStats de production ; l'isolation du test ne couvre pas WorkManager. C'est une limite du test, pas un défaut produit démontré. Aucune commande `am instrument` n'a été lancée pour ce test.

La qualification Pixel reste **PARTIELLE** : Focus manuel, notifications, calendrier, localisation, offline, accessibilité et parcours A2/A1 complet restent non exécutés.

### OpenClaw — preuve antérieure invalidée ; tentative finale BLOCKED

La tentative OpenClaw liée à `d7a30c1` et sa revue R2 ne constituent plus une preuve runtime acceptée. Les traces comportent des appels `exec` et des réponses `AUTH_REQUIRED`. La tentative finale a redémarré le Gateway avec `coreBaseUrl=8100` et était configurée pour le token outil maître, mais `ATLAS_CORE_TOOLS_TOKEN` était absente. Gateway/plugin chargés, agent Atlas visible, `tools.catalog` à 15 outils de plugin dont six Atlas, mais `tools.effective` pour Atlas vide (0 outil). Aucun tour Atlas ni appel des six outils n’a été exécuté. La configuration a ensuite été restaurée byte-for-byte vers `18100` (hash `1d78935…`), Gateway actif/health OK. OpenClaw runtime et R01 restent **BLOCKED / INVALIDÉ** ; `GO_OPENCLAW_TOOLS` est **BLOCKED**.

Le diagnostic indique que le profil exclut les 66 outils cœur, dont exec/process/FS/web fetch. Le catalogue live Atlas est toutefois vide et ne certifie donc pas la surface effective ; les traces `exec` de la tentative antérieure restent un risque **P1 à auditer**, non résolu. La nouvelle observation ne prouve aucune invocation d’outil. P2 : le Core utilisé pour cette tentative était mock/vide. L’ancienne revue R2 est invalidée et ne peut pas accepter ce nouvel essai.

### OpenClaw — état courant

La configuration est restaurée byte-for-byte vers `18100` (hash `1d78935…`) ; le Gateway est actif/health OK et le plugin chargé. Aucun outil Atlas n’est effectif pour l’agent, aucun tour n’a été exécuté ; aucun parcours Android→Gateway ni disponibilité OpenClaw en production n’est démontré. `GO_OPENCLAW_TOOLS` reste **BLOCKED**.

### Exploitation backend/relais — Core-only Docker actif, GO limité

Le lot Docker Core-only est implémenté par `0f6f6ed`, `e4cbc04`, `0572c71` et `e32836c`, intégré par le merge `a863d66`. Cinq fichiers ont été ajoutés : `backend/Dockerfile`, `backend/.dockerignore`, `compose.yaml`, `backend/compose.env.example` et `backend/DOCKER_CORE.md`. Le projet Compose est `atlas-core`, avec volume stable `atlas-core-data`, SQLite sous `/data/atlas.sqlite3` et `restart: unless-stopped`. Le conteneur utilise `network_mode: host`, expose uniquement l’écoute loopback `127.0.0.1:8100` sans port publié, s’exécute en UID/GID 10001 sans `cap_add`, avec racine en lecture seule, `cap_drop: ALL` et `no-new-privileges`. Le healthcheck interroge `/health`.

Commandes consignées depuis la racine du dépôt : `docker compose config`, `docker compose build --no-cache`, `docker compose up -d`, `docker compose down`, puis `docker compose up -d`. La configuration Compose est valide ; build et démarrage ont réussi ; `/health` a répondu HTTP 200 et le conteneur était healthy. Après redémarrage du Core, la santé a été rétablie. Après `down` puis `up`, le volume stable a conservé le marqueur de vérification, ensuite supprimé ; aucun `down -v` n’a été utilisé. Une copie SQLite a été vérifiée par hash et `PRAGMA quick_check=ok`. L’ancien volume `v63-p1-arch-review_atlas-core-data` a été conservé comme repli. La revue Docker indépendante est **ACCEPTED_LIMITED** avec P2 sur format token, healthcheck et rotation ; le redémarrage hôte n’a pas été testé.

Les secrets Android et outil sont distincts. Les maîtres privés sont en mode `0600` hors dépôt ; les copies Compose en mode `0444` dans un répertoire `0700`. Aucune valeur n’est dans Git, l’image Docker ou `Config.Env`. La route `/internal/v4/capabilities` répond HTTP 200 avec le token outil et les en-têtes requis, et HTTP 401 avec le seul token Android ou sans token. `/health` répond 200 et le Core reste actif. La revue Docker indépendante est **ACCEPTED_LIMITED** ; ses P2 couvrent le format des tokens, le healthcheck et la rotation. Le redémarrage hôte n’a pas été testé. La santé et la conservation observée du volume bornent le verdict **GO limité** ; elles ne démontrent pas la persistance après redémarrage hôte.

Le Core utilise deux secrets distincts : un pour l’authentification Android et un pour l’outil V4. Aucun secret n’est reproduit. La route `/internal/v4/capabilities` renvoie HTTP 200 avec le token outil et les en-têtes requis ; elle renvoie HTTP 401 avec le seul token Android ou sans token. Ce contrôle établit la protection de la route Core, pas la réussite d’un appel depuis OpenClaw.

État courant consigné : Core actif sur `127.0.0.1:8100` avec le volume stable. `4400` (proxy), `4401` (métriques), `4402` (relais) et `8770` restent absents. Le Gateway OpenClaw est actif/health OK, plugin chargé, mais le catalogue live Atlas est vide ; sa configuration a été restaurée vers `18100`. `8000` est occupé par `cti-bulletin-backend-1`, hors ATLAS ; `18789` est le Gateway OpenClaw, hors Compose. Le port `8770` reste une route privée tailnet hors périmètre ATLAS, de propriétaire non identifié ; il ne doit pas être réattribué. Le Core sur `8100` ne valide pas l’intégration OpenClaw. Relais/proxy et `8770` restent non résolus ; A4-4 est **BLOCKED** par la transformation amont 4001→1012.

## APK antérieur d7a30c1 conservé

- Nom : `app-debug.apk`; `versionName=0.1.0`, `versionCode=1`.
- Taille : **15 740 291 octets**.
- SHA-256 : `08f45c3743df80b57f653ba41472514e8d006fc3068aca47be041f96a20975c9`.
- Copie durable : espace d’artefacts QA privé hors dépôt, sous-dossier `V63-FINAL-d7a30c1/app-debug.apk`.
- Permissions : dossier `0700`, fichier `0600` ; `cmp` a confirmé une copie identique.

Cet APK exact a été installé pendant la qualification Pixel partielle antérieure. L’APK UI final installé au SHA `43214ad` a un hash différent, donné dans la section smoke UI ci-dessus.

## Sécurité, consentement et autorité locale

- Android reste l'autorité du plan local et des effets appliqués ; un état projeté par le Core n'est pas une preuve de l'état courant du téléphone.
- Les propositions IA ne suffisent pas à autoriser une mutation. A0 reste le défaut ; A1 dépend d'un consentement distinct, révocable et vérifié par des règles déterministes. Refus ou révocation ne doivent pas produire d'effet.
- Les catégories protégées ou non classées restent verrouillées. Les limites locales A1 décrites par D63-36 — quotas, versions, périmètre et annulation — restent bornées ; cette preuve locale ne remplace pas une qualification runtime.
- La preuve OpenClaw antérieure et la revue R2 sont invalidées. La tentative finale est **BLOCKED** : catalogue statique de 15 outils de plugin dont six Atlas, mais `tools.effective` Atlas = 0 et aucun tour exécuté. `ATLAS_CORE_TOOLS_TOKEN` était absente ; le profil diagnostiqué exclut les 66 outils cœur, mais la surface n’est pas certifiée et le signal `exec` antérieur reste P1 à auditer. Le Core protégé utilise deux secrets distincts ; `/internal/v4/capabilities` répond 200 avec le token outil et les en-têtes requis, 401 avec le seul token Android ou sans token.
- Aucune donnée personnelle, secret, token ou contenu de sauvegarde n'est reproduit dans ce rapport. Une sauvegarde privée antérieure au run Pixel existe et est référencée dans le journal QA privé ; aucun contenu, chemin personnel ni empreinte de sauvegarde n'est reproduit ici.

## Limites runtime restantes

### Pixel Android 17 / API 37

La qualification de l'APK UI final au SHA `43214ad` est **PASS_PARTIEL** : installation, identité de l'APK et smoke UI ciblé sont prouvés ; aucun test instrumenté n'a été exécuté sur cet APK. L'E2E instrumenté 1/1 concerne uniquement l'APK antérieur `d7a30c1`. Focus manuel, notifications, calendrier, localisation, offline, accessibilité et parcours A1/A2 complets restent non exécutés ; ne pas prononcer une qualification Pixel complète.

### OpenClaw

**BLOCKED / INVALIDÉ** : la tentative antérieure et son acceptation R2 sont invalidées après traces `exec` et réponses `AUTH_REQUIRED`. La tentative finale a chargé Gateway/plugin et rendu l’agent visible ; le catalogue statique comptait 15 outils de plugin dont six Atlas, mais `tools.effective` Atlas était vide (0 outil), donc aucun tour ni appel n’a été exécuté. `ATLAS_CORE_TOOLS_TOKEN` était absente ; la configuration a été restaurée byte-for-byte vers `18100` (hash `1d78935…`), Gateway actif/health OK. `GO_OPENCLAW_TOOLS` reste **BLOCKED**. Le profil diagnostiqué exclut les 66 outils cœur, mais le catalogue vide ne certifie pas la surface ; le signal `exec` antérieur reste P1 à auditer. Le Core répond 200 sur `/internal/v4/capabilities` avec le token outil et les en-têtes requis, 401 avec le seul token Android ou sans token ; cela ne valide pas OpenClaw.

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

Les sessions Pixel `a363ffe5-ec91-4982-a772-cee217ff73af`, OpenClaw antérieure invalidée `765804f3-6467-4902-957d-525ef72ee41a` et revue R2 invalidée `a4920cea-3024-4712-8e09-349d27c97832` sont consignées. Le résultat final de la nouvelle tentative est fourni par le complément parent ; son identifiant et sa commande ne sont pas indiqués. Il rapporte un catalogue statique de 15 outils de plugin dont six Atlas, `tools.effective` Atlas vide et aucun tour exécuté. Pour Pixel, `adb install -r` est attesté PASS ; l’instrumentation précédente est identifiée par `E2eV63InstrumentationTest`. `FocusGaugeInstrumentationTest` est resté non lancé ; aucune invocation `am instrument` n'a été exécutée pour ce test. Aucun chemin ou contenu privé n’est requis pour cette reproduction documentaire.

Ce rapport documentaire n'a pas relancé les suites.

Le résultat final des matrices R01-R12 et T01-T12 se trouve dans [RECETTE_V63.md](RECETTE_V63.md) et dans la matrice canonique [ARCHITECTURE_V63.md §16](ARCHITECTURE_V63.md). R10 et R12 sont **PASS_PARTIEL** ; R01/R02/R03/R07/R08 restent **BLOCKED** et R04/R05/R06/R09/R11 restent **NOT_RUN**. Aucun PASS runtime complet n'est déclaré.

## Étapes de clôture restantes

1. Compléter sur l'APK final les parcours Pixel restants : Focus, notifications, calendrier, localisation, offline, accessibilité et A1/A2.
2. Auditer le signal P1 `exec`, rendre non vide et certifier le catalogue live OpenClaw, puis prouver les appels autorisés et le chemin Android→Core/Gateway ; compléter R01 (cas négatifs, idempotence, redémarrage) sans extrapoler du catalogue statique.
3. Corriger l'image amont A4-4 pour préserver le protocole 4001, puis refaire le contrôle relais correspondant.
4. Corriger le P2 d'expiration en réconciliant le reçu avant toute expiration et en laissant un état inconnu en attente.
5. Compléter les autres recettes NOT_RUN. Les intégrations Etar, wger et localisation demeurent hors du périmètre livré.
