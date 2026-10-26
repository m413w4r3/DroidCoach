# ATLAS V6.3 — Campagne R2 (émulateur Android 17, UI et intégrations)

Statut : **RUNNING**. Ce registre est la source de vérité de R2. Il complète,
sans le remplacer, `docs/v6-3/REGISTRE_TACHES.md`.

## 1. Base et environnement vérifiés au lancement

| Élément | Valeur |
|---|---|
| Date | 2026-10-26 |
| Branche de campagne | `feat/atlas-v6-3-r2-emulator-ui` |
| Base `main` / SHA de campagne | `438616923a88eff814e96347d031506c6ea12f60` |
| Arbre de travail | propre avant création de la branche |
| JDK | OpenJDK 21.0.12.1 |
| Gradle wrapper | 9.6.0 |
| Android SDK | `ANDROID_HOME=$HOME/Android/Sdk` |
| Python | 3.12.3 |
| Node | v24.18.0 |
| Émulateur autorisé | `emulator-5554` |
| AVD | `Pixel_9_Pro_XL_2`, API 37.2 (Android 17), x86_64, 1344×2992 @480 dpi |
| Démarrage | headless, cold boot, `-no-snapshot-load -no-snapshot-save` |
| Sauvegarde AVD avant démarrage | `~/.local/share/atlas-v63-r2/avd-backup/userdata-qemu.img.qcow2.pre-r2`, SHA-256 `182de366…` |
| Sauvegarde données ATLAS avant essais | `~/.local/share/atlas-v63-r2/app-data/atlas-app-data-pre-r2.tar`, SHA-256 `674d17da…` |
| État AVD préexistant | ATLAS 0.1.0 installé, **ATLAS est le HOME par défaut**, 1 habitude de test, aucun plan persisté, aucun calendrier dans le provider |
| Périmètre physique | **hors campagne** : aucun appareil physique consulté, sollicité ou modifié |

Le Pixel physique du propriétaire reste indisponible et hors périmètre. Toute
preuve obtenue sur `emulator-5554` est libellée **émulateur Android 17 / API 37**,
jamais « Pixel ».

## 2. Règles de campagne appliquées

- Un seul parent orchestrateur, seul intégrateur et seul opérateur ADB/émulateur.
- Six enfants directs maximum, dont trois implémentations simultanées maximum.
- Aucune sous-délégation.
- Tous les enfants : `provider=codex`, `model=gpt-6-luna`, effort effectif
  `xhigh`, vérifié dans les métadonnées de session.
- Worktrees séparés, scope d'écriture exclusif par fichier, relecteurs en
  lecture seule effective.
- Une notification de fin n'est pas une acceptation : le parent inspecte le
  diff, rejoue l'oracle sur le SHA intégré et met à jour ce registre.
- Aucun push, PR, fusion distante, modification OpenClaw active, redémarrage du
  Gateway, écriture wger réelle ou action sur appareil physique.

## 3. Objectifs observables R2

1. Moteur d'écriture calendrier testable, fail-closed, sans écriture réelle sur
   un calendrier personnel.
2. Interface quotidienne simplifiée : Plan du jour et Habitudes, validée par
   captures avant/après sur l'émulateur.
3. Progression des intégrations : projection sportive wger en lecture seule,
   plugin OpenClaw revalidé hors activation, mémoire locale, observation
   d'usage globale.

## 4. Tickets R2

| TASK_ID | Objectif | Dépendances | Scope d'écriture exclusif | Oracle | Statut |
|---|---|---|---|---|---|
| `R2-PREFLIGHT-01` | Pré-vol, campagne documentaire, audit UI, fixtures, preuves | — | `docs/v6-3/r2/**` | pré-vol Git/outils/AVD + captures | **RUNNING** |
| `R2-CAL-01` | Moteur d'écriture CalendarContract : opérations, liens durables, idempotence, réconciliation, fake provider | préflight | `android/.../calendar/write/**` (main+test), fichiers neufs uniquement | JVM ciblés + suite ; compile debug | **ACCEPTED_WITH_P2 / INTEGRATED** (`595b224`) ; 18 ciblés, 514 JVM ; P2 rejeu APPLIED à réconcilier |
| `R2-UI-01` | Palettes d'accent, préférences indépendantes, sémantique, contrastes, primitives | préflight | `android/.../app/ui/**` (main+test), fichiers neufs ou existants listés | JVM contrastes + build | **ACCEPTED / INTEGRATED** (`3b06030` + `f0c136f`, correctifs parent `54b4183`) ; 11 tests UI |
| `R2-SPORT-01` | Projection sportive wger lecture seule, contrat borné, fixtures, modèles Android | préflight | `backend/.../integrations/wger_plan.py`, `contracts/sport-plan-v1.schema.json`, `android/.../training/**` (neufs), tests neufs | pytest ciblé + suite ; JVM ciblés | **ACCEPTED_WITH_P3 / INTEGRATED** (`005f4a9`) ; 8 backend, 178 backend, 7 JVM |
| `R2-PLAN-UI-01` | Refonte Plan du jour (hiérarchie, prochaine activité, menu secondaire, diff exact) | R2-UI-01 intégré | `android/.../app/PlanScreen.kt` + composants neufs `app/Plan*` | JVM + captures émulateur + sémantique | **READY (vague B)** |
| `R2-HABITS-UI-01` | Refonte Habitudes en 3 espaces, extraction de `AtlasScreens.kt` | R2-UI-01 intégré | `android/.../app/AtlasScreens.kt` puis nouveaux fichiers Habitudes | JVM + captures + 16 cas UI | **READY (vague B)** |
| `R2-MEMORY-01` | Noyau `MemoryFact` local : provenance, catégorie, rétention, révocation | préflight | `android/.../memory/**` (main+test), neufs | JVM | **READY (vague B)** |
| `R2-SPORT-WIRE-01` | Écran sport simple + séance visible dans le plan, fixture locale | R2-SPORT-01 | nouveaux fichiers UI sport + intégration parent | JVM + captures | **READY (vague C)** |
| `R2-USAGE-01` | Agrégation UsageStats, `UsageSummary` minimisé, quotas, consentement révocable | préflight | `android/.../usage/**` (main+test) | JVM synthétique + permission refusée | **READY (vague C)** |
| `R2-BRIDGE-QA-01` | Revalidation plugin contre faux Bridge, politique de provenance, procédure d'activation | préflight | `integrations/openclaw/**` (documentation/tests neufs) | npm test + build + validate | **READY (vague C)** |
| `R2-RECETTE-01` | Recette émulateur : build, installation, navigation, captures, accessibilité, thèmes, rotation, redémarrage, offline | vagues A–C | `docs/v6-3/r2/preuves/**`, captures | matrice R2 | **READY (vague D)** |
| `R2-REVIEW-01` | Relectures indépendantes lecture seule | tickets livrés | aucun | grille de revue | **READY** |

## 5. Vagues

- **Vague A** : `R2-CAL-01`, `R2-UI-01`, `R2-SPORT-01` en parallèle. Le parent
  conduit le pré-vol, l'audit UI avant, les fixtures et l'émulateur.
- **Vague B** : après intégration de `R2-UI-01`, `R2-PLAN-UI-01`,
  `R2-HABITS-UI-01`, `R2-MEMORY-01`. `AtlasScreens.kt` appartient
  exclusivement à `R2-HABITS-UI-01`.
- **Vague C** : raccordements parent (calendrier, sport), `R2-USAGE-01`,
  `R2-BRIDGE-QA-01`.
- **Vague D** : recette émulateur, captures après, tests instrumentés,
  accessibilité, revue finale et handoff Pixel.

## 6. Journal

| Date | Événement | Résultat |
|---|---|---|
| 2026-10-26 | Pré-vol Git, outils, AVD API 37 | **PASS** — base `4386169`, AVD API 37.2, sauvegardes effectuées |
| 2026-10-26 | Inventaire AVD | **PASS lecture** — ATLAS préinstallé, HOME ATLAS préexistant, aucun calendrier provider |
| 2026-10-26 | Baseline backend | **PASS** — `170 passed, 1 skipped` |
| 2026-10-26 | Audit confidentialité des preuves | **PASS avec correctifs** — serial, nom de calendrier personnel et chemins `$HOME` masqués ; historique Git non réécrit ; voir `PREUVES_CONFIDENTIALITE.md` |
| 2026-10-26 | Fixtures UI avant | **PASS** — plan vide, 0 puis 3 activités, habitudes 0/1/5, historique, thème, HOME |
| 2026-10-26 | Wave A lancée | 3 enfants `codex:gpt-6-luna` / `xhigh` vérifiés : `ed02e2cd…` (CAL), `2bb09cbf…` (UI), `ca7f5e7a…` (SPORT) |
| 2026-10-26 | R2-UI-01 livré | `3b06030` + correctif `f0c136f` ; intégré ; 2 correctifs de compilation parent `54b4183` ; tests UI ciblés PASS |
| 2026-10-26 | R2-CAL-01 livré | `595b224` ; 18 tests ciblés PASS, 496 JVM dans le worktree ; intégré ; P2 rejeu APPLIED documenté |
| 2026-10-26 | R2-SPORT-01 livré | `005f4a9` ; 8 tests backend, 178 backend + 1 skip, 7 JVM ; intégré ; P3 validateur de schéma local |
| 2026-10-26 | Gates vague A sur SHA intégré | backend `178 passed, 1 skipped` ; JVM `73 suites, 514 tests, 0 échec` ; lint/build/androidTest en cours |

## 7. Preuves

Les preuves versionnées vivent dans `docs/v6-3/r2/preuves/`. Elles ne contiennent
aucun identifiant matériel, compte, chemin privé, jeton ni donnée personnelle.
Les captures utilisent exclusivement des fixtures synthétiques.
