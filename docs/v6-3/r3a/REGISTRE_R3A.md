# ATLAS V6.3 — Campagne R3-A — Registre

Statut : **CLOSED_LOCAL — GO_WITH_LIMITATIONS** pour R3-A sur émulateur ;
**V6.3 global NO_GO**. Ce registre est la source de vérité R3-A.

## 1. Base et environnement

| Élément | Valeur |
|---|---|
| Date de lancement | 2026-10-26 |
| Branche de campagne | `feat/atlas-v6-3-r3a-relay-calendar` |
| Base `main` vérifiée | `6a22c12bc5a2c63c3d3ab56253679c897fadb75d` |
| SHA des contrats R3-A | `4f7b9263c76986c0a410f87a22268993568b3646` |
| `main` distant | identique au lancement |
| Arbre de travail | propre avant création de branche |
| JDK | OpenJDK 21.0.12.1 |
| Gradle wrapper | `android/gradlew` |
| Android SDK | `ANDROID_HOME=$HOME/Android/Sdk` |
| Python | 3.12.3, venv backend présent |
| Node / npm / pnpm | v24.18.0 / 11.16.0 / 11.20.0 |
| Docker | 27.1.1 |
| Émulateur autorisé | `emulator-5554`, API 37, `sdk_gphone16k_x86_64` |
| Etar | `ws.xsoh.etar` 1.0.56 |
| Provider calendrier | présent, **0 calendrier** au pré-vol |
| Pixel physique | hors périmètre, non touché |
| Paseo | `902b0c7…` (0.13.1), clone non shallow |
| Paseo Relay | `3fc41c96…` |
| Sauvegarde AVD | snapshot `atlas-r3a-pre` |
| Sauvegarde app ATLAS | `~/.local/share/atlas-v63-r3a/backups/atlas-app-data-pre-r3a.tar`, SHA-256 `41c1ad85700fdd57f8f0a8a9ba602a9ef443b6b89c117c1afb786e8521380eb1` |
| SHA source final | `02a5040283d087c4d401fd0f6e8f95f9cda752c3` |
| APK final | `8ad8d2d53de6794894219b13ca62641c59e96ffd4966f52c69309f2f4cfb4c8c` |
| SHA de la revue 2 | `e28b7a9bfc0d6405cd0d311eb87d4d2dd33f5832` (NO_GO : 3 P1, 4 P2) |
| SHA de la revue 3 | `7b3dc31f531fed94a6c1ef617c553a56e2f2d16d` / code `d89a626` (NO_GO : 1 P1, 2 P2, 1 P3) |
| SHA de la revue 4 | `bc6fb3ba028dc1ae3938b3dd4e2bff9fcf1691ea` / code `3811ea5` (NO_GO : 1 P1, 2 P2, 1 P3) |
| SHA de la revue 5 | `cf75d09884cf7381935143912e3fc9334268efdb` / code `c45b64f` (GO_WITH_LIMITATIONS : 1 P2, 3 P3) |

## 2. Règles appliquées

- Un seul parent orchestrateur, seul intégrateur et seul opérateur ADB.
- Six enfants actifs maximum, trois implémentations simultanées maximum.
- Tous les enfants : `provider=codex`, `model=gpt-6-luna`, effort `xhigh`,
  vérifié dans les métadonnées de session. Aucune sous-délégation.
- Worktrees et branches distincts, un propriétaire d'écriture par fichier.
- Une notification n'est pas une acceptation : le parent inspecte le diff, rejoue
  l'oracle sur le SHA intégré et met à jour ce registre.
- Aucun push, PR, fusion distante, action Pixel, modification OpenClaw active,
  écriture wger réelle ou déploiement public.
- Toute preuve est liée à un SHA, un environnement, une commande et un statut
  `PASS` / `FAIL` / `BLOCKED` / `NOT_RUN`.

## 3. Vagues et tickets

| TASK_ID | Objectif | Scope d'écriture exclusif | Oracle | Statut |
|---|---|---|---|---|
| `R3-PREFLIGHT-01` | pré-vol, backups, contrats, relais local | `docs/v6-3/r3a/**`, `contracts/atlas-remote-v1.schema.json` | pré-vol + build relais + doc | **RUNNING** |
| `R3-RELAY-PC-01` | connecteur PC Node sur Paseo e2ee | `integrations/relay/pc/**` | tests Node + interop Noise + relais réel | **ACCEPTED / INTEGRATED** — `fd66b0d`, merge `43c1204`, 23 tests + relais réel + live FastAPI |
| `R3-RELAY-ANDROID-01` | client Kotlin Noise/WebSocket/protocole | `android/app/src/main/java/dev/atlas/coach/sync/relay/**`, `android/app/src/test/java/dev/atlas/coach/sync/relay/**` | vecteurs upstream + tests JVM + records malformés | **ACCEPTED / INTEGRATED** — `8199350`, merge `8b0c9bd`, 25 tests JVM, vecteurs upstream + cross-vector Node |
| `R3-CALENDAR-01` | journal durable, liens, reprise, divergence | `android/app/src/main/java/dev/atlas/coach/calendar/write/**`, `android/app/src/test/java/dev/atlas/coach/calendar/write/**` | tests JVM crash/reprise/idempotence | **ACCEPTED_WITH_P2 / INTEGRATED** — `fb335ee`, merge `b9a527a`, 33 ciblés + 578 JVM ; P2 atomicité inter-fichiers ; correctif parent type de compte local |
| `R3-CALENDAR-LOCAL-01` | compte local + sync adapter + authentificateur + manifeste | parent : `calendar/local/**`, manifeste, XML | création provider + relecture + visibilité Etar | **ACCEPTED (parent)** — voir `preuves/calendar-local.txt` |
| `R3-RELAY-E2E-01` | raccordement Android ↔ relais ↔ PC ↔ FastAPI | parent : fichiers communs UI/config | E2E émulateur sans `adb reverse` | **ACCEPTED (parent)** — 4 phases + app controller, états UI |
| `R3-CALENDAR-ETAR-01` | DayPlan ↔ calendrier ↔ Etar | `PlanScreen.kt`, `CalendarPermissionScreen.kt`, nouveaux `Calendar*.kt` | 22 scénarios calendrier | **ACCEPTED_WITH_P1_FIXED / INTEGRATED** — `29b7439`, merge `eaad907` ; 18 ciblés, 589 JVM ; correctifs parent scroll imbriqué + init fail-closed |
| `R3-SPORT-WIRE-01` | wger lecture → FastAPI → Android, `trainingRef` | `backend/...`, `training/**`, `plan/{Models,Codec,Engine}`, `SportScreen.kt` | backend + JVM + UI | **ACCEPTED / INTEGRATED** — `235faff`, merge `50fde6f`, UI `4d6a875` ; 7 backend ciblés, 187 backend, 41 Android ciblés, 588 JVM ; câblage UI vérifié (UNAVAILABLE + démo explicite) |
| `R3-MEMORY-UI-01` | parcours mémoire complet | `memory/**`, `app/Memory*.kt` | tests JVM + UI réelle + persistance | **ACCEPTED (parent)** — `ee085a8`, merge `06c1e2e` ; tests JVM, ajout/consentement/affichage, force-stop/relance, capture |
| `R3-USAGE-UI-01` | Usage Access réel sur émulateur | `usage/**`, `app/GlobalUsage*.kt` | permission + statistiques | **ACCEPTED (parent)** — `7e65b9f`, merge `b9ba1ef` ; `GET_USAGE_STATS: allow`, 6 événements finaux, quota 90 min configuré et validé, révocation/purge, persistance |
| `R3-REVIEW-01` | revue indépendante lecture seule | aucun | grille de revue | **GO_WITH_LIMITATIONS / DELTA_CONFIRMED** — 1 P2 + 3 P3 de `cf75d09`/`c45b64f` ; P2 registre corrigé et confirmé sur `02a5040` ; P3 test d'arrivée tardive accepté comme limitation de test |

## 4. Journal

| Date | Événement | Résultat |
|---|---|---|
| 2026-10-26 | Pré-vol Git : base `6a22c12`, arbre propre, remote identique | **PASS** |
| 2026-10-26 | Pré-vol outils : JDK 21, Python 3.12, Node 24, Docker 27 | **PASS** |
| 2026-10-26 | Pré-vol émulateur API 37, Etar 1.0.56, provider calendrier | **PASS** — 0 calendrier |
| 2026-10-26 | Sauvegardes AVD + données ATLAS | **PASS** |
| 2026-10-26 | Clones Paseo / Paseo Relay | **PASS** — `902b0c7` / `3fc41c96` ; SHA mission `65ce4b5` absent |
| 2026-10-26 | Contrat `atlas.remote.v1` figé | **PASS (doc)** |
| 2026-10-26 | Build `@getpaseo/relay` | **PASS** |
| 2026-10-26 | Build image relais Elixir | **PASS** — `atlas-paseo-relay:r3a`, `/health` OK sur `127.0.0.1:4400` |
| 2026-10-26 | Vague A lancée | 3 sessions vérifiées `codex` / `gpt-6-luna` / `xhigh` : `b879770c…`, `7697f6dd…`, `cfb46cd6…` |
| 2026-10-26 | Compte local + calendrier ATLAS | **PASS** — auth + sync adapter enregistrés ; calendrier `_id=1`, `ATLAS R3A TEST`, type `dev.atlas.coach.calendar`, accès 700 |
| 2026-10-26 | Écriture/lecture provider réelle | **PASS** — événement `_id=2`, marqueurs `dev.atlas.coach` + URI Atlas, supprimé après le test de création |
| 2026-10-26 | Fixture Etar idempotente | **PASS** — événement `ATLAS TEST Etar visibility` visible dans Etar, capture `preuves/captures/etar-month-view.png` |
| 2026-10-26 | Crash processus AVD sous charge | **INCIDENT** — redémarrage sans wipe, image utilisateur conservée ; reconnexion en cours |
| 2026-10-26 | Connecteur PC : premier essai | **BLOCKED (outillage)** — lien `references/repos` absent dans le worktree ; corrigé par symlink parent, session relancée |
| 2026-10-26 | `R3-RELAY-PC-01` livré | `fd66b0d` ; build PASS, 23 tests PASS, test relais réel PASS ; scope conforme |
| 2026-10-26 | `R3-CALENDAR-01` livré | `fb335ee` ; 33 tests ciblés PASS, 578 JVM PASS ; P2 atomicité inter-fichiers documenté |
| 2026-10-26 | Intégration PC + calendrier | merges `43c1204` et `b9a527a` sur la branche de mission ; tests rejoués |
| 2026-10-26 | Correctif intégration calendrier | type `dev.atlas.coach.calendar` accepté avec `LOCAL`, test de type trompeur ajouté |
| 2026-10-26 | Backend démo V3 déterministe | ajout `MockAgentAdapter.propose_plan_revision`, 34 tests backend PASS |
| 2026-10-26 | Live PC : Node → relais → connecteur → FastAPI | **PASS** — `backend.health`, révision V3, rejeu idempotent, cache ; voir `preuves/pc-live-fastapi.txt` |
| 2026-10-26 | `R3-RELAY-ANDROID-01` livré | `8199350` ; 25 tests JVM PASS, compilation PASS, vecteurs upstream + Node cross-vector ; Bouncy Castle 1.86 + OkHttp 4.12.0 |
| 2026-10-26 | Client Android relayé intégré | merge `8b0c9bd` ; 25 tests rejoués PASS sur le SHA intégré |
| 2026-10-26 | Moteur durable sur provider réel | **PASS** — création, relecture, redémarrage, idempotence, divergence, suppression, reprise après crash injecté |
| 2026-10-26 | Permissions réelles `WRITE_CALENDAR` | **PASS** — `pm revoke` → refus sans effet ; `pm grant` → création/suppression ; processus neuf à chaque fois |
| 2026-10-26 | Modification manuelle dans Etar | **PASS** — titre édité dans `EditEventActivity`, marqueurs conservés, `verify` → `DIVERGED` ; capture `captures/etar-manual-edit.png` |
| 2026-10-26 | Vague B lancée | `R3-SPORT-WIRE-01` (`d2ad8ba9…`) et `R3-CALENDAR-ETAR-01` (`44871d16…`), profils vérifiés |
| 2026-10-27 | Second crash AVD pendant build Gradle | **INCIDENT** — APK déjà construit, redémarrage sans wipe, E2E relancé sans Gradle |
| 2026-10-27 | Connecteur PC + appareil synthétique | identité X25519 persistée, appareil `4812ce79…` appairé, capacités `plan.revision` |
| 2026-10-27 | E2E Android relayé phase 1 | **PASS** — Android → relais → Noise → connecteur → FastAPI, santé + révision V3 + rejeu |
| 2026-10-27 | Coupure / reconnexion relais | **PASS** — phase 2 hors ligne, phase 3 reconnexion, phase 4 redémarrage connecteur |
| 2026-10-27 | App controller en mode relais | **PASS** — `AtlasController.planRevisionClient()` → proposition FastAPI |
| 2026-10-27 | Écran de connexion relayée | **PASS** — états LOCAL / RELAY_CONNECTED / OFFLINE, identité masquée, dernière connexion ; captures `relay-settings-*.png` |
| 2026-10-27 | `R3-CALENDAR-ETAR-01` livré | `29b7439` ; 18 tests ciblés, 589 JVM, lint sans alerte sur les fichiers modifiés |
| 2026-10-27 | Intégration calendrier UI | merge `eaad907` ; régression P1 détectée par la suite R2 (double scroll) puis corrigée ; init fail-closed si stockage test indisponible |
| 2026-10-27 | Recette UI calendrier | **PASS** — plan v2→v3→v4, confirmations groupées, provider réel, Etar création/déplacement/suppression |
| 2026-10-27 | Suite instrumentée de référence (avant revue 2) | **PASS** — 17/17 sur l'APK intermédiaire |
| 2026-10-27 | Écriture durable config relay | **FIX** — `apply()` → `commit()` ; préférences vérifiées après seed |
| 2026-10-27 | APK intermédiaire | SHA-256 `3ee62e6d10b7b6e91954f85b003ef66dfc05a85cd79079dd0514ec9a08aa0d0f` ; E2E + app controller + calendrier PASS |
| 2026-10-27 | APK après correctifs revue 1 (remplacé) | SHA-256 `e8937c62f9096fbb01d5667a4eacba213140368119b32292d3bb2804355caebd` ; 17/17 + 4/4 + E2E relay + app controller + lifecycle 4/4 PASS |
| 2026-10-27 | Revue indépendante | **NO_GO** — P1 token CLI imprimé, P1 registre sans verrou, P1 preuves non liées au SHA ; P2 compte local dans une preuve, P2 re-création calendrier bloquée, P2 doc crypto Tink/Bouncy Castle |
| 2026-10-27 | Correctifs revue | token CLI écrit en 0600 sans stdout ; verrou registre inter-processus ; clé de re-création calendrier ; doc crypto corrigée ; preuve nominative redactée ; provenance SHA à régénérer |
| 2026-10-27 | Tests après correctifs | Node 24/24 ; `CalendarEffectControllerTest` 8/8 dont re-création après suppression externe |
| 2026-10-27 | Parcours mémoire réel | **PASS** — préférence synthétique ajoutée avec consentement, métadonnées affichées, persistée après `force-stop` ; captures `memory-preference.png`, `memory-persistence.png` |
| 2026-10-27 | Usage Access réel | **PASS** — appops allow, consentement + catégorie package_names, résumé 6 événements (ATLAS/Etar/Pixel Launcher), révocation et purge ; capture `usage-global-summary.png` |
| 2026-10-27 | Lifecycle calendrier (avant revue 2) | **PASS** — restore/move/delete/restore sur APK `e8937c62`, fixture `_id=18`, capture `etar-final-restored.png` |
| 2026-10-27 | Gates intermédiaires (avant revue 2) | **PASS** — backend 187/1 skip ; JVM 91 suites/649 tests ; lint 0 erreur ; builds ; Node 24 ; Bridge 50 |
| 2026-10-27 | Provenance des preuves | `preuves/PROVENANCE.md` distingue preuves finales et captures historiques |
| 2026-10-27 | Seconde revue indépendante | **NO_GO** sur `e28b7a9` — P1 verrou périmé, P1 UI calendrier non rejouée sur APK final, P1 aucun quota réel ; P2 semaine, Tink, authenticator exporté, index de preuves |
| 2026-10-27 | Correctifs de la seconde revue | verrou atomique avec vérification de vivacité et test inter-processus ; quota quotidien persisté + fenêtre 7 jours ; authenticator protégé par `ACCOUNT_MANAGER` + test réel ; Tink corrigé ; index synchronisé |
| 2026-10-27 | Rejeu UI calendrier final | **PASS** — création `_id=28`, idempotence, déplacement v3 même id, suppression v4 ; captures `calendar-final-*.png` |
| 2026-10-27 | Parcours mémoire final | **PASS** — trois préférences synthétiques, persistance après force-stop |
| 2026-10-27 | Usage final | **PASS observation** — 6 événements sur l'APK final (148 sur un build antérieur), quota 90 min persisté et validé, révocation/purge ; quota `UNKNOWN` tant que la fenêtre 24 h n'est pas pleine |
| 2026-10-27 | Instrumentés finaux | **PASS** — 17 R2 + 4 calendrier + 1 relay + 1 offline + 1 app controller + 4 lifecycle + 1 authenticator |
| 2026-10-27 | Troisième revue indépendante | **NO_GO** sur `d89a626` — P1 reprise concurrente du verrou ; P2 délais par défaut, P2 quota invalide ; P3 compte d'événements matrice |
| 2026-10-27 | Correctifs revue 3 | verrou bakery à records stables par nonce + stress 10 rounds ; timeout 15 s ; validation UI 1–1440 ; matrice synchronisée |
| 2026-10-27 | Quatrième revue indépendante | **NO_GO** sur `3811ea5` — P1 lecture du verrou en échec traitée comme absence de contention ; P2 stress non déterministe, P2 PID recyclé hors Linux ; P3 capture mémoire avec doublon |
| 2026-10-27 | Correctifs revue 4 | `readEntries` fail-closed (ENOENT par record seulement), tests EACCES/record invalide, barrière de course + arrivée après entrée, README Linux/liveness, capture mémoire expliquée |
| 2026-10-27 | Cinquième revue indépendante | **GO_WITH_LIMITATIONS** sur `c45b64f` — aucun P0/P1 ; P2 registre initialisé hors verrou, P3 tests verrou/capture/quota |
| 2026-10-27 | Correctif P2 revue 5 | `load()` sans effet de bord ; tests lecture concurrente/premier pair, EACCES record, arrivée synchronisée ; Node 35 |
| 2026-10-27 | Revue delta P2 | **GO_WITH_LIMITATIONS** — aucun P0/P1/P2 résiduel ; P3 de synchronisation du test d'arrivée tardive accepté |
| 2026-10-27 | Gates finales `02a5040` | backend 187/1 skip ; JVM 91/651 ; lint 0 erreur ; Node 35 ; Bridge 50 ; PC live PASS ; APK `8ad8d2d` ; voir `preuves/gates-final.txt` |
| 2026-10-27 | Lifecycle calendrier final `02a5040` | **PASS** — restore/move/delete/restore, fixture `_id=27`, capture `etar-final-restored.png` |
| 2026-10-27 | Nettoyage émulateur | **PASS** — données app restaurées, événements ATLAS supprimés, permissions révoquées, appops default, test APK désinstallé ; voir `preuves/cleanup-final.txt` |
| 2026-10-27 | Arrêt services | **PASS** — connecteur, FastAPI 8100 et relais arrêtés ; ports 4400/4401/8100 fermés |
| 2026-10-27 | Crash post-nettoyage | **INCIDENT** — émulateur arrêté après le nettoyage ; userdata restauré intact, aucun ADB attaché |

## 5. Preuves

Les preuves versionnées vivent dans `docs/v6-3/r3a/preuves/`. Elles ne contiennent
aucun secret, token, identifiant matériel, compte, chemin privé ni donnée
personnelle. Les captures utilisent uniquement des fixtures synthétiques.
