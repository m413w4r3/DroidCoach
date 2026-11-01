# ATLAS V7-07 — Préproduction et pilote quotidien : registre de campagne

**Mission :** déterminer si l'application ATLAS réellement installée sur le Pixel
peut devenir un coach quotidien, préparer un candidat produit et un pilote
réversible, sans installer ni modifier le produit avant autorisation ciblée.

**BASE_SHA :** `32e7c70c78fbe295f66f67d6be50dc797a3f6e6d` (local = `origin/main`
vérifié le 2026-11-01).
**Branche de campagne :** `feat/atlas-v7-07-preproduction` (aucun push, PR ou
merge distant).
**Date :** 2026-11-01.
**Environnement cible de clôture :** Pixel physique Android 17 / API 37 ; aucun
appareil connecté au lancement (`adb devices` vide).

**Statut global :** `PRÉPARATION EN COURS`. Aucune installation produit, aucune
modification live, aucun ADB sur le Pixel, aucune révocation, aucune rotation de
secret. Les gates de données, de configuration live et de pilote restent ouverts
tant que les preuves correspondantes ne sont pas exécutées.

## 1. Contraintes d'exécution

- Parent Paseo : orchestration, pré-vol live en lecture seule, revue, intégration
  locale et demandes d'autorisation.
- **Tous** les sous-agents : `provider=codex`, `model=gpt-6-luna`, effort effectif
  `xhigh`, vérifié dans les métadonnées Paseo de chaque session.
- 6 enfants simultanés maximum, 3 implémentations maximum, aucune
  sous-délégation, un propriétaire d'écriture par fichier, un seul opérateur
  ADB/AVD à la fois.
- Audits en worktrees détachés verrouillés `a-w` ; aucun ADB, Docker, réseau ou
  test live pour les audits.
- Aucun secret, token, identifiant brut, contenu personnel ou contenu de
  sauvegarde dans les preuves.
- Une notification de fin n'est pas une validation : le parent relit le diff, les
  commandes et les preuves avant `ACCEPTED`.
- Aucun push, PR, merge distant, redémarrage live, révocation ou installation
  produit sans autorisation explicite et ciblée.

## 2. Pré-vol V7-07-00 (lecture seule, 2026-11-01)

| Élément | Constat |
|---|---|
| Git local | `HEAD=32e7c70c78fbe295f66f67d6be50dc797a3f6e6d`, arbre propre |
| Git distant | `origin/main=32e7c70…` (identique) |
| CI GitHub | run `41350216879`, workflow `CI`, conclusion `success` |
| JDK | OpenJDK `21.0.12.1` |
| Android SDK | platforms `android-37.0/37.1/37.2`, build-tools `37.0.0` |
| Node / Python / Docker | `v24.18.0` / `3.12.3` / `27.1.1` |
| Paseo | provider `codex`, modèle `gpt-6-luna`, effort `xhigh` disponibles |
| Appareil physique | **non connecté** (`adb devices` vide) — toute preuve produit actuelle est `BLOCKED_DEVICE_ABSENT` |
| Appareil AVD | images API 37.1/37.2 et AVD existants ; serveur ADB hôte `5037` sans appareil |
| Core live | conteneur `atlas-core-atlas-core-1`, image `00b88ef…`, `healthy`, network host, DB `/data/atlas.sqlite3`, `mode=openclaw` |
| Gateway live | service utilisateur `openclaw-gateway.service` actif, loopback `127.0.0.1:18789` |
| Relay live | conteneur `atlas-v7-relay` sain sur `127.0.0.1:4402` ; connecteur PC **arrêté** |
| Entrée publique | Funnel `tcp://all.taildc7886.ts.net:8443 -> tcp://127.0.0.1:4400` ; **aucun listener 4400** ; sonde TLS `/health` en échec |
| Identités | `devices.json` : 19 entrées, 2 non révoquées (`pixel-v7`, `r3c-pixel-final`) |
| Consentements Core | 2 lignes `GRANTED` v2 pour `device_ref` `android-9a3f0f75…`, `updated_at=2026-10-31T00:23:51Z` |
| Propositions/ACK live | `0` proposition, `0` événement, `0` snapshot, `0` ACK |

**Décision de pré-vol :** les audits A1–A3 sont lancés en lecture seule. Les
tickets d'implémentation sont limités à trois : tests P2 du parseur, outillage
backup/restore, build du candidat sans installation.

## 3. Faits live vérifiés par le parent (lecture seule)

Aucune écriture, aucun redémarrage, aucun ADB, aucun `docker compose`.

| Ressource | Constat | Statut |
|---|---|---|
| Core `/health` | HTTP 200, `{"status":"ok","mode":"openclaw"}` | **PROVEN** |
| Core capabilities | HTTP 200 avec token outil et en-têtes V4 ; 7 capabilities `true` | **PROVEN** |
| Core SQLite | tables V4 présentes ; 2 consentements, 0 projection/proposition/ACK | **PROVEN** |
| Tokens Core | Android, outil et Gateway distincts ; fichier plugin = token outil (hash identique) | **PROVEN** |
| Gateway `/health` | HTTP 200, `status=live` | **PROVEN** |
| Gateway config | modèle `openai/gpt-6-luna`, agent `atlas`, plugin `atlas_core_tools`, `coreBaseUrl=127.0.0.1:8100` | **PROVEN (statique)** |
| Gateway outils | `profile=full`, `allow=[atlas_core_tools]`, `deny=[gateway,session_status,presence,exec,process,code_execution,wait]`, `codeMode=false` | **PROVEN (statique)** |
| Gateway outils effectifs | non exécuté ; plugin natif concurrent activé | **NOT_RUN / P1** |
| Relay container | HTTP 200 sur `127.0.0.1:4402/health` | **PROVEN** |
| Connecteur PC | PID obsolète, processus absent, aucun listener 4400 | **FAIL (disponibilité)** |
| Entrée publique | TLS vers 8443 sans backend ; `SSL_ERROR_SYSCALL` | **FAIL (disponibilité)** |
| Identités Relay | 19 entrées, 2 actives ; un seul bearer Android partagé côté connecteur | **PROVEN ; P1 de séparation** |
| Core consents | `DAY_PLAN` et `ACTIVITY_STATUS` `GRANTED` v2 pour `android-9a3f0f75…` | **PROVEN** |

## 4. Tickets

| TASK_ID | Objectif | Type | Session Paseo | Worktree | Statut |
|---|---|---|---|---|---|
| V7-07-00-PREFLIGHT | SHA, CI, outils, live lecture seule, registre | parent | parent | racine | **ACCEPTED (lecture seule)** |
| V7-07-A1-PRODUCT-DATA | Modèle de données produit, migrations, backup/restore | audit RO | `d459824a-c98c-4bad-ad34-2b272152f729` | `/tmp/atlas-v7-07-a1-product-ro` | **ACCEPTED (rapport reçu, à intégrer)** |
| V7-07-A2-LIVE-CONFIG | Core/Gateway/Relay, modèle, outils, config candidate | audit RO | `47a96ff4-6f89-4fdd-98d6-af66dcaabf37` | `/tmp/atlas-v7-07-a2-live-ro` | **ACCEPTED (rapport reçu, à intégrer)** |
| V7-07-A3-BACKUP-DESIGN | Procédure backup/restore/rollback et essai jetable | audit RO | `42f086e3-2829-4452-9748-c5e401299242` | `/tmp/atlas-v7-07-a3-backup-ro` | **ACCEPTED (rapport reçu, à intégrer)** |
| V7-07-P2-PARSER-TESTS | Tests P2 parseur F1, sans code produit | implémentation | `f8049778-98a8-4e06-b28a-9722f38bde75` | `/tmp/atlas-v7-07-parser-p2` | **RUNNING** (ciblé vert, suite 915 PASS, commit en cours) |
| V7-07-CANDIDATE-BUILD | Build APK produit, vérifications, sans installation | build | `e15d44a1-219c-456b-bc5f-7f0857b78b25` | `/tmp/atlas-v7-07-candidate` | **ACCEPTED (build)** — APK `d32efc76…`, JVM 912/912, lint 0 erreur, signature et permissions vérifiées |
| V7-07-BACKUP-TOOL | Outillage backup/restore sûr + tests hors ligne | implémentation | `20ecbdf6-43be-44c2-8091-1f67df30922c` | `/tmp/atlas-v7-07-backup-tool` | **ACCEPTED (outil)** — commit `2517097`, 15 tests hors ligne PASS |
| V7-07-AVD-RESTORE | Essai AVD jetable : backup, restore, update in-place, HOME | exécution parent | parent (unique opérateur ADB) | AVD API 35 jetable | **PASS (AVD API 35, synthétique)** — backup/restore/verify, HOME, update et rollback ; Keystore NOT_RUN ; Pixel NOT_RUN |
| V7-07-REVIEW | Revue indépendante sécurité/perte de données | revue RO | à créer après intégration | worktree détaché | **PENDING** |

## 5. Journal des sessions

| Date | Session | Rôle | Provider/modèle/effort constatés | Résultat |
|---|---|---|---|---|
| 2026-11-01 | parent | Pré-vol V7-07-00 | parent `deepseek-flash[1m]` / max | **ACCEPTED** lecture seule ; branche créée ; aucun ADB/live |
| 2026-11-01 | `d459824a-c98c-4bad-ad34-2b272152f729` | A1 produit/données | codex / `gpt-6-luna` / `xhigh` | **ACCEPTED** — 24 SharedPreferences, fichiers, WorkManager, Keystore ; P1 `atlas_core_v4_sync`, restauration non prouvée |
| 2026-11-01 | `47a96ff4-6f89-4fdd-98d6-af66dcaabf37` | A2 configuration live | codex / `gpt-6-luna` / `xhigh` | **ACCEPTED** — plugin natif concurrent, `profile=full`, modèle personnel non qualifié, transport NO-GO |
| 2026-11-01 | `42f086e3-2829-4452-9748-c5e401299242` | A3 backup/rollback | codex / `gpt-6-luna` / `xhigh` | **ACCEPTED** — procédure `run-as`, matrice, test AVD, export/import manquant |
| 2026-11-01 | `f8049778-98a8-4e06-b28a-9722f38bde75` | P2 parseur | codex / `gpt-6-luna` / `xhigh` | **RUNNING** — ciblé 6/6 vert, suite 915 PASS, commit local en cours |
| 2026-11-01 | `e15d44a1-219c-456b-bc5f-7f0857b78b25` | Build candidat | codex / `gpt-6-luna` / `xhigh` | **ACCEPTED (build)** — `GO_PRODUCT_APK_BUILD`, APK `d32efc76…`, JVM 912/912, lint 0 erreur, provenance `32e7c70` |
| 2026-11-01 | `20ecbdf6-43be-44c2-8091-1f67df30922c` | Outillage backup | codex / `gpt-6-luna` / `xhigh` | **ACCEPTED (outil)** — commits `2517097`, `30f1d63`, `2d49705` ; 17 tests hors ligne PASS ; aucun ADB |
| 2026-11-01 | parent | Essai AVD API 35 | ADB isolé `5038`, émulateur jetable | **PASS (synthétique)** — backup/restore/verify, HOME, update in-place, rollback ; nettoyage AVD ; preuve `evidence/V7-07_AVD_BACKUP_RESTORE.md` |

## 6. Règles de preuve

- Chaque résultat porte `PASS`, `FAIL`, `BLOCKED`, `NOT_RUN` ou `INCONCLUSIVE`,
  lié au SHA exact, à l'environnement et à la commande.
- Une archive hashée ne prouve pas une restauration ; une compilation ne prouve
  pas une exécution ; un PASS DeepSeek isolé ne prouve pas le modèle personnel.
- Aucun `GO_PRODUCT_INSTALL`, `GO_PRODUCT_REMOTE` ou `GO_DAILY_PILOT` n'est
  prononcé dans cette phase préparatoire.
- Les preuves historiques V7-06D restent valides dans leur périmètre QA, pas
  comme preuve produit.
- Toute action live ou physique exige une autorisation ciblée séparée, avec
  sauvegarde, rollback et opérateur unique.

## 7. Verdicts de la phase préparatoire

| Verdict | État | Portée |
|---|---|---|
| `GO_SOURCE_AND_CI` | **GO** | `32e7c70`, CI `success`, arbre propre |
| `GO_DATA_BACKUP` | **GO (AVD API 35, synthétique) / NOT_RUN Pixel** | outil et archive validés sur AVD ; aucune sauvegarde du produit réel |
| `GO_DATA_RESTORE` | **GO (AVD API 35, synthétique) / NOT_RUN Pixel** | restauration vérifiée sur AVD ; produit réel non restauré |
| `GO_HOME_ROLLBACK` | **GO (AVD API 35, synthétique) / NOT_RUN Pixel** | rôle HOME posé/restauré sur AVD ; Settings à rejouer sur Pixel |
| `GO_LIVE_OPENCLAW_CONFIG` | **NO-GO** | plugin natif concurrent, `profile=full`, modèle non qualifié |
| `GO_LIVE_SECURITY` | **NO-GO / BLOCKED** | transport distant indisponible ; identités de test actives ; bearer Android partagé |
| `GO_PRODUCT_APK_CANDIDATE` | **PENDING** | build GO, revue indépendante et décision de risque P1 en attente |
| `GO_PRODUCT_INSTALL` | **NO-GO** | aucune autorisation, backup/restore non démontré |
| `GO_PRODUCT_LOCAL_SMOKE` | **NO-GO / NOT_RUN** | produit non installé/testé |
| `GO_PRODUCT_REMOTE_CHAT` | **NO-GO / NOT_RUN** | produit non appairé ; transport arrêté |
| `GO_PRODUCT_PLAN_REVISION` | **NO-GO / NOT_RUN** | produit non testé |
| `GO_PRODUCT_REMOTE` | **NO-GO** | aucun parcours produit |
| `GO_DAILY_PILOT` | **NO-GO** | gate de données et de configuration non franchi |

Ces verdicts seront mis à jour uniquement sur preuve exécutée, jamais par
assimilation.
