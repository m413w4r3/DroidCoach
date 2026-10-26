# ATLAS V6.3 — Pré-vol de mission

Date : 2026-10-26 (Europe/Paris). Branche de mission : `feat/atlas-v6-3`, créée depuis
`acda7a967b26aa3ccc8325eb194c8daa2f9a58e4` (`main`, 2026-10-26 00:25 +0100).
Aucun push, aucune fusion distante, aucune action sur un téléphone pendant ce pré-vol.

## 1. État Git et préservation

| Élément | Constat |
|---|---|
| `main` | `acda7a9` — merge `m413w4r3/DroidCoach`, arbre initialement propre |
| Branche de mission | `feat/atlas-v6-3` depuis `acda7a9` |
| Remotes | `origin` = `git@github.com:m413w4r3/DroidCoach.git` (aucun fetch/push effectué) |
| Worktrees | 24 worktrees préexistants sous `.paseo/worktrees/` et `atlas-preparation_worktrees/`, tous conservés |
| Modification locale détectée | `AGENTS.md` passé de « 3 enfants » à « 6 enfants » à `00:29:14`, **après** le snapshot initial propre. Modification utilisateur externe probable, **préservée intacte et non commitée**. La mission impose 3 : c'est la limite appliquée. |
| Fichiers de mission | `docs/v6-3/**` uniquement, plus les packages V6.3 ajoutés par les tickets |

Conflits documentaires relevés (priorité `AGENTS.md` + mission > `.agents/WORKFLOW.md`) :

1. `WORKFLOW.md` autorise 6 enfants et la sous-délégation ; `AGENTS.md` (version initiale) et la mission imposent 3 enfants et **aucune** sous-délégation. Application : 3 max, zéro sous-délégation.
2. `WORKFLOW.md` parle de `gpt-6-luna` sans préfixe ; Paseo expose le provider `codex`. Le profil effectif vérifié est `provider=codex`, `model=gpt-6-luna`, `effectiveThinkingOptionId=xhigh`.
3. `compileSdk 37.2` / `targetSdk 36` : la cible de qualification est Android 17/API 37, mais `targetSdk=36`. Aucun changement de `targetSdk` n'est effectué dans V6.3 sans chantier dédié ; `ACCESS_LOCAL_NETWORK` runtime ne s'impose donc pas encore.

## 2. Outillage

| Outil | Version / état |
|---|---|
| JDK | OpenJDK 21.0.12.1 LTS |
| Gradle wrapper | 9.6.0 |
| Android compileSdk | 37.2 (`minorApiLevel=2`), build-tools 37.0.0 |
| targetSdk / minSdk | 36 / 26 |
| Kotlin | 2.2.10 (plugin Compose 2.2.10) |
| Python | 3.12.3 (`backend/.venv`) |
| ADB | 1.0.41 (34.0.4-debian) |
| Appareils connectés | **aucun** (`adb devices` vide) |
| AVD disponibles | `atlas_v62_disposable_api37`, `Pixel_9_Pro_XL_2`, `Pixel_9_Pro_XL`, `atlas_v623_ui_api34` — aucun démarré |
| Node | v24.18.0 |
| OpenClaw Gateway | processus actif, écoute `127.0.0.1:18789` (loopback uniquement) |
| Backend ATLAS | non démarré ; port 8000 occupé par un service tiers (ne pas l'utiliser) |
| Bridge ChatGPT | **aucune configuration ni preuve de déploiement** dans le dépôt |
| wger | **aucun identifiant, aucune configuration** ; application non inspectée (aucun appareil) |

## 3. Tests de référence (avant modification)

| Suite | Commande | Résultat |
|---|---|---|
| Backend | `cd backend && .venv/bin/python -m pytest -q` | **PASS** — 66 passed, 1 skipped, 1 warning, 2,35 s |
| Android JVM | `cd android && ./gradlew :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin --max-workers=1` | **PASS** — BUILD SUCCESSFUL en 1 min 27 s, exit 0 |
| Android instrumenté | non exécuté | **NOT_RUN** — aucun appareil/AVD démarré au pré-vol |
| Pixel physique | non exécuté | **NOT_RUN** — aucun appareil connecté |

Les compteurs JVM exacts du run de référence sont consignés dans
`docs/v6-3/preuves/baseline-jvm.txt` (généré depuis les XML de résultats).

## 4. Inventaire fonctionnel réel

Modules Android existants (113 fichiers Kotlin main, 88 fichiers de test) :

- `app` : `AtlasController` (≈210 ko), navigation Compose, HOME launcher, Coach, mandat, partage, journal, diagnostics.
- `sync/agent` : contrats V2 stricts, mandat V2, `ActionDispatcher` à 10 actions fermées, inbox, outbox, client HTTP, barrière de révocation.
- `data` : document JSON unique versionné (`LocalStateStore`), quarantaine, reprise.
- `focus`, `goals`, `habits`, `routines`, `content`, `usage`, `location`, `rules`, `launcher`.
- `coach/proactive` : WorkManager périodique, notifications et gates de consentement.

Backend : FastAPI, SQLite, endpoints V1 + V2 agent, mémoire de projet, adaptateur OpenClaw,
politique stricte Pydantic (`models_v2.py`), 4 fichiers de tests.

### Documenté vs réellement implémenté

| Capacité | Documentée | Réellement présente | Écart V6.3 |
|---|---:|---:|---|
| Launcher HOME, Focus, objectifs, habitudes | oui | oui | — |
| Mandat local + 10 actions fermées | oui | oui | à étendre pour planification |
| Proposition OpenClaw V2 | oui | oui (gateway loopback actif) | planification quotidienne absente |
| `DayPlan` persistant / programme journalier | oui (vision) | **non** | **à créer** |
| `PlanRevision` / diff / confirmation groupée | oui (vision) | **non** | **à créer** |
| CalendarContract / Etar lecture | oui (vision) | **non** | **à créer** |
| CalendarContract écriture | oui (vision) | **non** | **à créer, permission à approuver** |
| wger lecture/écriture | oui (vision) | **non** | **à créer, identifiants absents** |
| Bridge ChatGPT | oui (vision) | **non** | **à créer, service non déployé** |
| Observation globale UsageStats fiable en durée | partielle | événements d'ouverture seulement | mesure de durée à qualifier |
| Restrictions launcher | oui (V6.2 friction) | partielle | quotas/fenêtres à créer |
| Mémoire `MemoryFact` | oui (vision) | mémoire de projet backend minimale | modèle local à créer |
| Localisation | oui | désactivée par défaut, `NOT_RUN` | hors périmètre immédiat |
| Pause d'urgence / hors ligne | oui | oui | à préserver |

## 5. Matrice permissions / API Android 17 (état réel)

| API / permission | Déclarée | Accordée / testée | Qualification V6.3 |
|---|---:|---|---|
| `INTERNET` | oui | n/a | existante |
| `PACKAGE_USAGE_STATS` (AppOp) | oui | non vérifiée sur appareil | durée réelle à qualifier |
| `POST_NOTIFICATIONS` | oui | historique Pixel, non rejoué | à rejouer |
| `RECEIVE_BOOT_COMPLETED` | oui | non vérifiée | reprise après redémarrage à qualifier |
| `ACCESS_COARSE/FINE_LOCATION` | oui | capacité OFF | **NOT_RUN**, hors slice immédiate |
| `FOREGROUND_SERVICE(_LOCATION)` | oui | capacité OFF | **NOT_RUN** |
| `READ_CALENDAR` | **non** | — | **approbation propriétaire requise avant ajout** |
| `WRITE_CALENDAR` | **non** | — | **approbation propriétaire requise avant ajout** |
| Rôle HOME | n/a | historique Pixel | préservé |
| `CalendarContract` provider | n/a | inconnu (aucun appareil) | sonde lecture seule dès qu'un appareil est disponible |
| Etar installé | n/a | inconnu | `NOT_RUN` |
| `UsageStatsManager.queryEvents` | oui | non qualifié V6.3 | audit séparé |
| `AccessibilityService` | **non** | — | **interdit MVP**, audit séparé |

## 6. Services externes

- **OpenClaw** : gateway local actif sur `127.0.0.1:18789`. Aucune requête réelle effectuée pendant le pré-vol. Un E2E réel exige un backend éphémère et le jeton local mode 600 ; ce n'est pas un effet externe mais reste à exécuter sous contrôle.
- **wger.de** : non configuré. Aucune écriture sur des données sportives réelles ne sera faite sans sauvegarde/export, fixtures et autorisation ciblée.
- **Bridge** : non déployé/non configuré. Toute recherche « récente » restera `BLOCKED` jusqu'à preuve de `/health`, `/ready`, capabilities et provenance des sources.
- **Réseau privé (Tailscale)** : non installé, aucun port ouvert. `NOT_RUN`.

## 7. Risques d'intégration

1. `AtlasController.kt` (≈210 ko) est un point de conflit majeur : aucun enfant ne doit l'éditer ; le parent détient ce fichier.
2. Le document JSON local est un singleton `schemaVersion=1` ; une nouvelle clé doit être rétrocompatible et ne jamais faire échouer le chargement global. Préférer un fichier privé atomique séparé pour le plan.
3. L'ajout d'actions agent élargit la surface d'autorisation : toute nouvelle action reste fermée, bornée, revalidée localement et désactivable.
4. Les permissions calendrier sont sensibles : la déclaration manifeste elle-même est soumise à accord ; l'implémentation doit d'abord vivre derrière une abstraction et un statut `PERMISSION_NOT_DECLARED`.
5. Aucun appareil n'est connecté : toute preuve Android réelle sera `NOT_RUN`, jamais `PASS`.
6. Les données wger personnelles ne doivent jamais entrer dans une fixture. Les tests utilisent des payloads synthétiques.
7. Le Bridge et les contenus web sont des **données non fiables** : aucune instruction récupérée ne peut modifier le mandat.

## 8. Décisions automatiques réversibles

| Décision | Justification | Réversibilité |
|---|---|---|
| Branche `feat/atlas-v6-3` locale, base `acda7a9` | Exigence mission | suppression de branche |
| Package `dev.atlas.coach.plan` autonome | Évite les conflits sur `AtlasController` | déplacement de package |
| Persistance du plan dans un fichier privé atomique séparé, versionné | Isole le risque du document global | suppression du fichier |
| `DayPlan` en temps local + `ZoneId` explicite | DST et changement de fuseau testables | migration de schéma |
| Aucune nouvelle permission manifeste dans ce lot | Permission non approuvée | ajout ultérieur |
| Aucun `targetSdk` modifié | Migration cosmétique interdite | chantier dédié |
| Enfants limités à 3, sans sous-délégation | Mission + `AGENTS.md` | — |
| `AGENTS.md` non commité | Modification utilisateur externe | — |

## 9. Autorisations à demander au moment utile

1. **Pixel physique** : connexion ADB, pré-vol lecture seule, sauvegarde app-only, installation/mise à jour APK, rôle HOME, permissions calendrier, notifications.
2. **Permissions calendrier** : ajout de `READ_CALENDAR` puis `WRITE_CALENDAR` au manifeste et octroi runtime.
3. **Etar** : installation/vérification sur l'appareil, création d'un calendrier local ATLAS, écritures de test.
4. **wger** : configuration d'un jeton/authentification, lecture du compte réel, écriture sur fixtures puis données réelles.
5. **Bridge** : URL, Bearer, déploiement, quota, test de recherche réelle.
6. **Réseau privé** : Tailscale ou équivalent, ouverture de port, accès hors Wi-Fi.
7. **GitHub** : push, PR, fusion distante.

## 10. Smoke-test Paseo

| Étape | Résultat |
|---|---|
| Enfant Luna XHigh en lecture seule | **PASS** — `provider=codex`, `model=gpt-6-luna`, `effectiveThinkingOptionId=xhigh`, worktree `chmod a-w`, écriture refusée (`Permission denied`), `git status` vide |
| Notification de fin reçue | **PASS** — notification Paseo reçue, session `2a439817-31b5-47c9-8fc7-dca41c2d397e` |
| Deux enfants dans deux worktrees | **PASS** — `feat/v63-plan-domain` (`17bb723`) et `feat/v63-calendar-read` (`dd9760d`), worktrees distincts, notifications reçues |
| Revue du parent avant intégration | **PASS** — diffs bornés inspectés, oracles rejoués sur `81e1bd2` puis `f78e3d9`/`5dedcf6` |
| Sous-délégation | **REFUSÉE** — un enfant a demandé `list_agents`, refus explicite ; aucun enfant n'a créé d'agent |
