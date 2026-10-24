# ATLAS V6.2 — Registre unique des tâches de clôture

**Mission :** clôturer V6.2 avant V6.3 (prompt maître `prompts/Prompt_Cloture_V6.2_Avant_V6.3.md`).
**Statut global :** `NOT_CLOSED` — correctif P1 intégré; deux preuves finales en attente (revue indépendante du SHA final et réexécution OpenClaw Android).
**Branche de clôture :** `chore/cloture-v62-android17` (locale, aucun push).
**BASE_SHA :** `8890046012965f3a3112ff0c419014c74e189d11` (`main` au démarrage, 2026-10-25).
**Intégrateur :** orchestrateur parent unique.
**Concurrence :** 3 enfants Paseo maximum, aucune sous-délégation, 1 propriétaire ADB.

## 1. Environnement constaté

| Élément | Valeur | Preuve |
|---|---|---|
| Dépôt | `/home/nill/perso/AndroCoach/atlas-preparation` | `pwd` |
| Branche de travail | `chore/cloture-v62-android17` | `git branch --show-current` |
| HEAD de base | `8890046012965f3a3112ff0c419014c74e189d11` | `git rev-parse HEAD` |
| État Git initial | propre | `git status --short` vide |
| JDK | OpenJDK 21.0.12.1 LTS | `java -version` |
| Gradle | 9.6.0 (wrapper `android/gradlew`) | `./gradlew --version` |
| Kotlin | 2.3.21 | `./gradlew --version` |
| SDK Android | platforms `android-37.0`, `37.1`, `37.2` ; build-tools `37.0.0` | `$ANDROID_HOME/platforms` |
| Python | 3.12.3 | `python3 --version` |
| ADB | 1.0.41 (34.0.4-debian) | `adb version` |
| Pixel physique | Pixel 9 Pro XL (`komodo`), Android 17/API 37, build `CP3A.260905.009` | `adb shell getprop` (numéro de série non consigné) |
| Batterie au pré-vol | 21 % | `dumpsys battery` |
| Stockage `/data` | 31 Go libres (72 % utilisés) | `df -h /data` |
| ATLAS installé | `dev.atlas.coach`, versionName `0.1.0`, versionCode 1, targetSdk 36, **debuggable** | `dumpsys package` |
| Signature APK installé | certificat debug SHA-256 `da3014662eb65e50a87ef364a2e583c331398795b222e3b37b1a16553a00eeee` | `apksigner verify --print-certs` |
| Keystore debug local | même empreinte SHA-256 que l'APK installé | `keytool -list -v` |
| HOME actuel | `com.google.android.apps.nexuslauncher` (Pixel Launcher) | `cmd role get-role-holders` |
| Permissions ATLAS | `POST_NOTIFICATIONS` refusée, localisation précise/approx. refusées, `ACCESS_LOCAL_NETWORK` accordée | `dumpsys package` |
| Processus ATLAS | non démarré au pré-vol | `pidof dev.atlas.coach` |
| Sauvegarde applicative | archive hors dépôt et hors `/tmp`, SHA-256 `26912d37b7f98ca88d9e1c42076698120c25e0bfb4de867a096d4609dc3160e0` | stockage privé hors dépôt (chemin non versionné) |
| Sauvegarde vérifiée | 12 entrées, 8 fichiers réguliers, extraction locale OK, manifeste canonique `a0c67cc6ee124241336a6b11869137fd6ffeb641b6b79aeda1a13a7498dc924b` | extraction de vérification hors dépôt, aucune réinjection |
| APK installé archivé | SHA-256 `d6f8d4d9c94523a94759fe7a4343bafd12cd6c4ba143b946f8af7020e0189feb` | stockage privé hors dépôt |
| APK HEAD 8890046 | SHA-256 `48c7766e9090010692f0eaafc1d0cbaf4b22e51259847f94b642a2d060b33583`, certificat debug identique à l'installé | `android/app/build/outputs/apk/debug/app-debug.apk` |
| OpenClaw gateway | processus local `gateway --port 18789`, `/health` = HTTP 200, jeton `~/.config/atlas/openclaw-gateway-token` mode 600 | lecture seule, aucune requête métier envoyée |
| Backend ATLAS | aucun processus ATLAS local ; le port 8000 est occupé par un service tiers (`cti_app`), ne pas l'utiliser | `ss -ltnp`, `ps` |

## 2. Réalité Paseo constatée

| Élément | Constat |
|---|---|
| Provider Paseo | `codex` (le littéral `openai-codex` n'est pas un ID de provider sur cet hôte) |
| Modèle | `gpt-6-luna` |
| Effort effectif | `xhigh` (`thinkingOptionId` et `effectiveThinkingOptionId`) |
| Modes Codex disponibles | `auto`, `auto-review`, `full-access` — **aucun mode lecture seule natif** |
| Lecture seule relecteurs | barrière technique appliquée : worktree isolé + `chmod -R a-w` + contrôle `git status`/HEAD après exécution ; limite consignée |
| Hôtes Paseo | un seul hôte (`srv_ye_FTq15PUEs`), pas d'hôte distant |
| Concurrence enfants | **6 maximum** (instruction utilisateur du 2026-10-25, remplace la limite de 3) |
| AVD API 37 | autorisé par l'utilisateur pour les tests restants ; aucune suite Android 14 |

## 3. Registre des tâches

| TASK_ID | Catégorie | Objectif | Base SHA | Worktree / session | Statut | Oracle | Preuve |
|---|---|---|---|---|---|---|---|
| V62-SMOKE-A | scout docs, lecture seule | État revendiqué vs prouvé, gates restants | 8890046 | `v62-smoke-scout-docs` / `8fb38cef…` | `ACCEPTED` | rapport cité `chemin:ligne`, zéro écriture | `READ_ONLY_OK`; HEAD inchangé, worktree propre, 0 fichier inscriptible; modèle/effort vérifiés |
| V62-SMOKE-B | scout harnais, lecture seule | Allowlist tests non destructifs Pixel | 8890046 | `v62-smoke-scout-harness` / `6ee537cf…` | `ACCEPTED` | inventaire effets de bord + commandes exactes | `READ_ONLY_OK`; HEAD inchangé, worktree propre, 0 fichier inscriptible; 6 tests autorisés, majorité des classes exclue (effacement des prefs ATLAS) |
| V62-BASE-01 | test | JVM + lint sur HEAD | 8890046 | worktree parent | `PASS` | `./gradlew :app:testDebugUnitTest :app:lintDebug` | 321 tests, 0 échec, 0 erreur, 0 ignoré (tâche UP-TO-DATE) ; lint 0 erreur, 53 warnings ; log local hors dépôt |
| V62-CI-02 | test | Build debug + backend pytest | 8890046 | worktree parent | `PASS` | `assembleDebug` + `pytest -q` | build successful ; APK `48c7766e…` ; certificat `da3014…eeee` identique à l'installé ; pytest 66 passed, 1 skipped |
| V62-PIXEL-PREFLIGHT | QA lecture seule | Identité, HOME, permissions, stockage | 8890046 | parent (ADB unique) | `DONE` | lecture seule sans modification | section 1 |
| V62-PIXEL-BACKUP | QA | Sauvegarde données ATLAS | 8890046 | parent (ADB unique) | `DONE` | tar lisible + SHA-256 hors dépôt | section 1 |
| V62-PIXEL-INSTALL | QA sensible | Mise à jour par remplacement | 8890046 | parent (ADB unique) | `PASS` | `adb install -r`, données et signature préservées | consentement obtenu ; APK installé `48c7766e…` ; 8 empreintes de données identiques à la sauvegarde ; aucun uninstall/`pm clear` |
| V62-PIXEL-COLDSTART | QA | Démarrage à froid | 8890046 | parent (ADB unique) | `PASS` | `am start -W` Status ok / COLD | 548 ms, `MainActivity` au premier plan, aucun crash logcat ; session Focus utilisateur restaurée en pause (non modifiée) |
| V62-PIXEL-HOME | QA sensible | Rôle HOME, apps, recherche, favoris, sortie | 8890046 | parent (ADB unique) | `PASS` | parcours manuel + restauration Pixel Launcher | ATLAS HOME, tiroir et recherche OK, épinglage/désépinglage OK, ouverture Brave et retour, « Launcher précédent » ouvre Pixel Launcher, rôle restauré ; 1 irritant UX candidat |
| V62-PIXEL-PAUSE | QA | Pause d'urgence hors ligne | 8890046 | parent (ADB unique) | `PASS` | activation immédiate + bouton Fin | message « Pause d'urgence active pendant 30 minutes », accès « Fin », pause terminée |
| V62-INSTRUMENT-ALLOWLIST | revue | Allowlist Pixel non destructive | 8890046 | scout B | `ACCEPTED` | 6 méthodes explicitement autorisées | exclut les classes qui vident `atlas_local_state`, les captures `/sdcard`, HOME, rotation, réseau, GPS réel et mobilité |
| V62-MATRIX-A17 | QA | Matrice P0/P1 Android 17 | 8890046 / 1251009 | parent (ADB unique) + AVD | `PASS/PARTIEL` | scénarios du prompt §4 | Pixel PASS sur installation, HOME, hors ligne, Pause, Focus/objectif, notifications, rotation/police/TalkBack ; localisation/mobilité NOT_RUN mais désactivées par défaut ; détails dans `MATRICE_ANDROID17_PIXEL.md` |
| V62-AVD-API37 | test | Suite instrumentée sur AVD API 37 | 8890046 | `v62-avd-api37-suite` / `84d00efb…` | `PASS` | boot API 37 + XML tests > 0, ou `AVD_ENVIRONMENT_BLOCKED` | **78/78 méthodes locales PASS effectifs** sur `emulator-5554` API 37 : 77 PASS + 1 échec de harnais (`expected_system_font_scale`), retest ciblé OK ; 16 méthodes backend hors suite ; ancienne tentative 5580 bloquée avant installation |
| V62-AVD-FINAL-FIX | test | Suite locale sur APK final | 1251009 | parent, `emulator-5554` | `PASS` | 78/78 effectifs | APK `80b1d004…` ; 77 PASS + 1 échec de harnais, retest font scale PASS ; `emulator-5554` uniquement |
| V62-OPENCLAW-ANDROID | test | Test Android OpenClaw sur AVD API 37 | 8890046 | parent, `emulator-5554` | `PASS` | `agent_status=CONNECTED`, proposition acceptée/EXECUTED, mandat, ACK, effet | `V62RealGatewayContextBridgeTest#realGatewayUsesAccelerated25MinuteFocusContextAndAcceptedProposal` : **OK (1 test), 11.143 s**, backend `10.0.2.2` éphémère, gateway réel ; preuve `preuves-openclaw/android-avd-run.txt` |
| V62-OPENCLAW-FINAL-FIX | test | Réexécution OpenClaw Android sur SHA final | 1251009 | parent, `emulator-5554` | `BLOCKED_ENVIRONMENT` | gateway réel | backend `POST /v2/agent/chat` 200 mais gateway `HTTP 429` « usage limit reached » ; quota annoncé à 02:44 ; aucun échec de code démontré |
| V62-E2E-OPENCLAW | test | Backend réel + adaptateur OpenClaw | 8890046 | `v62-openclaw-e2e` / `5c36570c…` | `PASS` | gateway réel + contexte synthétique + mandat/effet/idempotence/révocation | host-side PASS : réponse 25/120, propositions dans le mandat, ACK unique, révocation `focus` seul, fallback propre ; Android PASS sur AVD (voir V62-OPENCLAW-ANDROID) |
| V62-CI-FINAL | test | CI reproductible sur HEAD | 8890046 | `v62-ci-final` / `35681c1b…` | `PASS` | `--rerun-tasks` + 0 échec + lint 0 erreur + pytest | Android PASS : 321 tests, 0 échec, lint 0 erreur/52 warnings, BUILD SUCCESSFUL ; backend parent frais 66 passed, 1 skipped (2.45 s) ; runner pytest de l'agent bloqué après 37 résultats, classé artefact d'environnement |
| V62-CI-FINAL-FIX | test | CI sur SHA final corrigé | 1251009 | parent | `PASS` | JVM + lint + build + backend | JVM 328 tests, 0 échec ; lint 0 erreur/47 warnings ; BUILD SUCCESSFUL ; backend 66 passed, 1 skipped (2.64 s) ; APK `80b1d004…` |
| V62-FIX-MANDATE-BOUNDS | fix | Bornes Focus du mandat (P1 sécurité) | 8890046 | `v62-mandate-focus-bounds` / `5a3a84e0…` | `PASS` | test rouge→vert + suite JVM | commits `4aff91a` (durée totale + fin de fenêtre) et `1251009` (traversée de minuit) ; 328 tests, 0 échec |
| V62-DOCS-CLOSURE | docs | Six pièces de clôture | 8890046 | `v62-closure-pack` / `1841f016…` | `ACCEPTED` | six fichiers, PASS liés à preuve, PENDING explicites | commit `d4705bf…` ; intégré dans la branche parente, à mettre à jour avec E2E local/AVD/OpenClaw Android |
| V62-PIXEL-ACCESSIBILITY | QA | Police 2×, rotation, TalkBack | 8890046 | parent (ADB unique) | `PASS` | actions critiques accessibles + restauration | police 2× : contenu défilable, actions atteignables ; rotation paysage OK ; TalkBack OK ; réglages restaurés ; observation P2 sous nav 2× documentée |
| V62-PIXEL-NOTIFICATIONS | QA | Permission refusée/accordée, notification, révocation | 8890046 | parent (ADB unique) | `PASS` | `POST_NOTIFICATIONS` avant/après + notification réelle | publisher 2/2 PASS, routage tap 6/6 PASS, permission restaurée refusée, inbox fixture supprimée |
| V62-PIXEL-OFFLINE | QA | Mode avion, HOME/Pause/Focus locaux | 8890046 | parent (ADB unique) | `PASS` | mode avion actif, UI locale, restauration | ATLAS HOME hors ligne, directive locale et Pause d'urgence « Fin » ; réseau et Pixel Launcher restaurés |
| V62-E2E-LOCAL | test | Objectif 120 min + Focus 25 min, redémarrage, annulation | 8890046 | parent (ADB unique) | `PASS` | progression 0 → 25 → 25 après relance + annulation sans crédit | objectif synthétique 120 min ; Focus lié 25 min ; 0/120 avant fin ; 25/120 après fin et après relance à froid (583 ms) ; nouvelle session annulée, toujours 25/120 |
| V62-UX | QA | Parcours réels, 3 irritants max | 8890046 | parent + utilisateur | `PASS` | retour utilisateur explicite | propriétaire : « aucun irritant bloquant » ; 3 observations P2 documentées (HOME depuis tiroir, contenu sous nav 2×, bandeau Focus démarré) |
| V62-REVIEW-FUNC | revue | Conformité fonctionnelle indépendante | 8890046 | `v62-review-functional` / `71773bd5…` | `NO_GO_BASE / PENDING_FINAL` | grille P0/P1 + PASS prouvés, zéro P0 | rapport récupéré : NO_GO sur `8890046…` (E2E local et révocation en vol non conclus) ; E2E local désormais PASS ; revue du SHA final à rejouer |
| V62-REVIEW-SEC | revue | Qualité/sécurité indépendante | 8890046 | `v62-review-security` / `885b3282…` | `P1_FIXED / PENDING_FINAL` | constats P0/P1/P2 `chemin:ligne`, secrets/privacy | P1 bornes Focus corrigé ; P2 OpenClaw URL sans garde de transport ouvert ; revue du SHA final à rejouer |
| V62-REVIEW-FINAL | revue | Revue indépendante du SHA final | 1251009 | Luna XHigh, à relancer après 03:00 | `PENDING` | diff `4aff91a..1251009`, zéro P0 | reviewers indisponibles (quota Codex) ; rapports de base conservés hors dépôt |
| V62-UI-CAPTURES | docs | Captures UI sur AVD API 37 | 8890046 | `v62-ui-captures` / `38d8e196…` | `PASS` | ≥10 PNG réels + index | commit `a523317` ; 15 PNG réels sur `emulator-5554`, données synthétiques, aucun accès Pixel |
| V62-RESTORE-DECISION | QA | Restauration finale Pixel | 8890046 | propriétaire | `DECISION_PROPRIETAIRE` | choix explicite | « Garder l'état QA » : données/réglages non restaurés ; sauvegarde d'origine intacte hors dépôt |

## 4. Consentements requis avant actions sensibles

1. Installation par remplacement de l'APK debug sur le Pixel (même package, même certificat) — donnée utilisateur préservée par sauvegarde vérifiée.
2. Changement temporaire du rôle HOME vers ATLAS, puis retour à Pixel Launcher.
3. Octroi/révocation de permissions notifications et localisation.
4. Rotation, taille de police ou autres réglages globaux.
5. Test de mobilité / service de localisation.
6. Connexion au backend local ou externe et partage de contexte synthétique.
7. Toute action non listée dans le prompt maître.

## 5. Interdits permanents

- `adb uninstall`, `pm clear`, factory reset, root, Device Owner, accès privilégié.
- Écrasement de la sauvegarde ou publication de données personnelles, jetons, numéros de série.
- Push, merge distant, PR ou fermeture d'issue sans autorisation explicite.
- Suite instrumentée Android 14/API 34.
- Polling de statut ou boucle `sleep`.

## 6. Journal

| Horodatage UTC | Événement |
|---|---|
| 2026-10-24T22:04Z | Smoke-test Paseo : deux Luna XHigh lancés, métadonnées vérifiées. |
| 2026-10-24T22:06Z | Pré-vol Pixel lecture seule + sauvegarde ATLAS + vérification signature. |
| 2026-10-24T22:07Z | Baseline JVM/lint lancée sur `8890046`. |
