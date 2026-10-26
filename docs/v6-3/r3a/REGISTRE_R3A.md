# ATLAS V6.3 — Campagne R3-A — Registre

Statut : **RUNNING**. Ce registre est la source de vérité R3-A.

## 1. Base et environnement

| Élément | Valeur |
|---|---|
| Date de lancement | 2026-10-26 |
| Branche de campagne | `feat/atlas-v6-3-r3a-relay-calendar` |
| Base `main` vérifiée | `6a22c12bc5a2c63c3d3ab56253679c897fadb75d` |
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
| `R3-RELAY-PC-01` | connecteur PC Node sur Paseo e2ee | `integrations/relay/pc/**` | tests Node + interop Noise + relais réel | **READY** |
| `R3-RELAY-ANDROID-01` | client Kotlin Noise/WebSocket/protocole | `android/app/src/main/java/dev/atlas/coach/sync/relay/**`, `android/app/src/test/java/dev/atlas/coach/sync/relay/**` | vecteurs upstream + tests JVM + records malformés | **READY** |
| `R3-CALENDAR-01` | journal durable, liens, reprise, divergence | `android/app/src/main/java/dev/atlas/coach/calendar/write/**`, `android/app/src/test/java/dev/atlas/coach/calendar/write/**` | tests JVM crash/reprise/idempotence | **READY** |
| `R3-CALENDAR-LOCAL-01` | compte local + sync adapter + manifeste | parent : `calendar/local/**`, manifeste, XML | création provider + relecture | **READY (parent)** |
| `R3-RELAY-E2E-01` | raccordement Android ↔ relais ↔ PC ↔ FastAPI | parent : fichiers communs UI/config | E2E émulateur sans `adb reverse` | **PLANNED** |
| `R3-CALENDAR-ETAR-01` | DayPlan ↔ calendrier ↔ Etar | parent : `PlanScreen.kt`, écrans calendrier | 22 scénarios calendrier | **PLANNED** |
| `R3-SPORT-WIRE-01` | wger lecture → FastAPI → Android, `trainingRef` | selon ticket | backend + JVM + UI | **PLANNED** |
| `R3-MEMORY-UI-01` | parcours mémoire complet | selon ticket | JVM + UI | **PLANNED** |
| `R3-USAGE-UI-01` | Usage Access réel sur émulateur | selon ticket | permission + statistiques | **PLANNED** |
| `R3-REVIEW-01` | revue indépendante lecture seule | aucun | grille de revue | **PLANNED** |

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
| 2026-10-26 | Build image relais Elixir | **PASS** |
| 2026-10-26 | Vague A lancée | à compléter |

## 5. Preuves

Les preuves versionnées vivent dans `docs/v6-3/r3a/preuves/`. Elles ne contiennent
aucun secret, token, identifiant matériel, compte, chemin privé ni donnée
personnelle. Les captures utilisent uniquement des fixtures synthétiques.
