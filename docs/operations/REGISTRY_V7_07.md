# ATLAS V7-07 — Préproduction et pilote quotidien : registre de campagne

**Mission :** déterminer si l'application ATLAS réellement installée sur le Pixel
peut devenir un coach quotidien, préparer un candidat produit et un pilote
réversible, sans installer ni modifier le produit avant autorisation ciblée.

**BASE_SHA :** `32e7c70c78fbe295f66f67d6be50dc797a3f6e6d` (local = `origin/main`
vérifié le 2026-11-01).
**Branche de campagne :** `feat/atlas-v7-07-preproduction` (aucun push, PR ou
merge distant).
**Date :** 2026-11-01 (mise à jour 2026-11-02).
**Environnement cible de clôture :** Pixel physique Android 17 / API 37 ; aucun
appareil connecté au lancement (`adb devices` vide).

**Statut global :** `PRÉPARATION AVANCÉE — PRÊTE POUR DEMANDE D'AUTORISATION
CIBLÉE`. Aucune installation produit, aucune modification live, aucun ADB sur le
Pixel, aucune révocation, aucune rotation de secret. Les gates de données, de
configuration live et de pilote restent ouverts tant que les preuves
correspondantes ne sont pas exécutées.

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

**Lignée post-revue :** la branche de campagne intègre localement l'outillage
backup (`4046f1a`), le parseur delta UUID (`cc47e5c`) et le correctif V4
`dd343c2` (merge `b9f17f4`). Le seul changement de code produit vs `32e7c70`
est `AndroidSyncStorage.kt` (quarantaine V4) ; les tests, scripts et documents
n'affectent pas l'APK. Le candidat final est reconstruit depuis `b9f17f4`.

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

## 3bis. Recontrôle final en lecture seule (2026-11-02)

Aucune écriture, aucun redémarrage, aucun ADB, aucun `docker compose`.

| Ressource | Constat final | Statut |
|---|---|---|
| Core | conteneur `running/healthy`, image `00b88ef…`, démarré `2026-10-30T22:16:54Z`, `/health` 200 `mode=openclaw`, 7/7 capabilities, 2 consentements, 0 proposition/ACK | **inchangé** |
| Gateway | service utilisateur `active`, `openclaw.json` `0600`, mtime `2026-10-30 22:46:57`, hash `8b8d59d1…` | **inchangé** |
| Connecteur | processus absent, aucun listener 4400/4401 | **inchangé (arrêté)** |
| Relay | conteneur `atlas-v7-relay` actif sur `127.0.0.1:4402` | **inchangé** |
| Entrée publique | Funnel 8443 sans backend, `SSL_ERROR_SYSCALL` | **inchangé (indisponible)** |
| Identités | `devices.json` 19 entrées, 2 actives, hash `c0298ce1…`, mtime `2026-10-30 23:30:54` | **inchangé** |
| ADB | aucun appareil | **inchangé** |
| Git | arbre propre, aucun push | **inchangé** |

Ce recontrôle confirme que la préparation n'a modifié aucune ressource live.

## 4. Tickets

| TASK_ID | Objectif | Type | Session Paseo | Worktree | Statut |
|---|---|---|---|---|---|
| V7-07-00-PREFLIGHT | SHA, CI, outils, live lecture seule, registre | parent | parent | racine | **ACCEPTED (lecture seule)** |
| V7-07-A1-PRODUCT-DATA | Modèle de données produit, migrations, backup/restore | audit RO | `d459824a-c98c-4bad-ad34-2b272152f729` | `/tmp/atlas-v7-07-a1-product-ro` | **ACCEPTED (lecture seule)** — 24 SharedPreferences, fichiers, WorkManager, Keystore ; P1 `atlas_core_v4_sync` |
| V7-07-A2-LIVE-CONFIG | Core/Gateway/Relay, modèle, outils, config candidate | audit RO | `47a96ff4-6f89-4fdd-98d6-af66dcaabf37` | `/tmp/atlas-v7-07-a2-live-ro` | **ACCEPTED (lecture seule)** — plugin natif concurrent, `profile=full`, modèle non qualifié, transport NO-GO |
| V7-07-A3-BACKUP-DESIGN | Procédure backup/restore/rollback et essai jetable | audit RO | `42f086e3-2829-4452-9748-c5e401299242` | `/tmp/atlas-v7-07-a3-backup-ro` | **ACCEPTED (lecture seule)** — procédure `run-as`, matrice, test AVD, export/import manquant |
| V7-07-P2-PARSER-TESTS | Tests P2 parseur F1, sans code produit | implémentation | `f8049778-98a8-4e06-b28a-9722f38bde75` | `/tmp/atlas-v7-07-parser-p2` | **ACCEPTED (test-only)** — `fae8caa` + `5412050` (UUID invalide) ; ciblé 8/8, suite 917/917 |
| V7-07-CANDIDATE-BUILD | Build APK produit, vérifications, sans installation | build | `e15d44a1-219c-456b-bc5f-7f0857b78b25` | `/tmp/atlas-v7-07-candidate` | **SUPERSEDED** — ancien APK `d32efc76…` (source `32e7c70`, sans V4), non installable |
| V7-07-CANDIDATE2-BUILD | Rebuild final depuis `b9f17f4` (V4 inclus) | build | `30d078f8-bc51-437a-8aa4-80d1bfcd51f9` | `/tmp/atlas-v7-07-candidate2` | **ACCEPTED (build)** — `c7b0f71f…`, JVM 922/922, lint 0 erreur, provenance `worktree_clean=true`, source `b9f17f4` ; premier APK `dba3509…` rejeté (`worktree_clean=false`) |
| V7-07-BACKUP-TOOL | Outillage backup/restore sûr + tests hors ligne | implémentation | `20ecbdf6-43be-44c2-8091-1f67df30922c` | `/tmp/atlas-v7-07-backup-tool` | **ACCEPTED (outil)** — commits `2517097`, `30f1d63`, `2d49705`, `acd765b` ; 18 tests hors ligne PASS ; refus cross-serial |
| V7-07-B1-V4-QUARANTINE | Quarantaine fail-closed de l'état V4 invalide | implémentation | `c6cf7e09-f832-4769-a488-efb298f6f7ac` | `/tmp/atlas-v7-07-v4-quarantine` | **ACCEPTED (code)** — `dd343c2`, rouge→vert, JVM 920/920, lint 0 erreur, assemble PASS ; intégré `b9f17f4` |
| V7-07-AVD-RESTORE | Essai AVD jetable : backup, restore, update in-place, HOME | exécution parent | parent (unique opérateur ADB) | AVD API 35 jetable | **PASS (AVD API 35, synthétique)** — backup/restore/verify, HOME, update et rollback ; Keystore NOT_RUN ; Pixel NOT_RUN |
| V7-07-REVIEW | Revue indépendante sécurité/perte de données | revue RO | `ed7f0c88-9243-474a-8083-12478df0eb21` | `/tmp/atlas-v7-07-review-ro` | **ACCEPTED_WITH_LIMITATIONS** — aucun P0 ; 5 P1, 5 P2 ; corrections appliquées |
| V7-07-REVIEW-DELTA | Confirmation delta du SHA final | revue RO | même session `ed7f0c88…` | `0203257` → `cd45015` | **ACCEPTED** — P1 provenance fermé ; P2 HEAD documentaire qualifié d'état intermédiaire ; aucun P0/P1/P2 ouvert ; aucun ADB/live |

## 5. Journal des sessions

| Date | Session | Rôle | Provider/modèle/effort constatés | Résultat |
|---|---|---|---|---|
| 2026-11-01 | parent | Pré-vol V7-07-00 | parent `deepseek-flash[1m]` / max | **ACCEPTED** lecture seule ; branche créée ; aucun ADB/live |
| 2026-11-01 | `d459824a-c98c-4bad-ad34-2b272152f729` | A1 produit/données | codex / `gpt-6-luna` / `xhigh` | **ACCEPTED** — 24 SharedPreferences, fichiers, WorkManager, Keystore ; P1 `atlas_core_v4_sync`, restauration non prouvée |
| 2026-11-01 | `47a96ff4-6f89-4fdd-98d6-af66dcaabf37` | A2 configuration live | codex / `gpt-6-luna` / `xhigh` | **ACCEPTED** — plugin natif concurrent, `profile=full`, modèle personnel non qualifié, transport NO-GO |
| 2026-11-01 | `42f086e3-2829-4452-9748-c5e401299242` | A3 backup/rollback | codex / `gpt-6-luna` / `xhigh` | **ACCEPTED** — procédure `run-as`, matrice, test AVD, export/import manquant |
| 2026-11-01 | `f8049778-98a8-4e06-b28a-9722f38bde75` | P2 parseur | codex / `gpt-6-luna` / `xhigh` | **ACCEPTED (test-only)** — `fae8caa` + `5412050` ; ciblé 8/8, suite 917/917 ; aucun code produit |
| 2026-11-01 | `e15d44a1-219c-456b-bc5f-7f0857b78b25` | Build candidat | codex / `gpt-6-luna` / `xhigh` | **ACCEPTED (build)** — `GO_PRODUCT_APK_BUILD`, APK `d32efc76…`, JVM 912/912, lint 0 erreur, provenance `32e7c70` |
| 2026-11-01 | `20ecbdf6-43be-44c2-8091-1f67df30922c` | Outillage backup | codex / `gpt-6-luna` / `xhigh` | **ACCEPTED (outil)** — commits `2517097`, `30f1d63`, `2d49705`, `acd765b` ; 18 tests hors ligne PASS ; aucun ADB |
| 2026-11-01 | parent | Essai AVD API 35 | ADB isolé `5038`, émulateur jetable | **PASS (synthétique)** — backup/restore/verify, HOME, update in-place, rollback ; nettoyage AVD ; preuve `evidence/V7-07_AVD_BACKUP_RESTORE.md` |
| 2026-11-02 | `ed7f0c88-9243-474a-8083-12478df0eb21` | Revue indépendante | codex / `gpt-6-luna` / `xhigh`, worktree `a-w` | **ACCEPTED_WITH_LIMITATIONS** — aucun P0 ; 5 P1 (V4, config, identités, transport, état produit), 5 P2 ; worktree intact, aucun ADB/live |
| 2026-11-02 | `c6cf7e09-f832-4769-a488-efb298f6f7ac` | Correction V4 | codex / `gpt-6-luna` / `xhigh` | **ACCEPTED (code)** — `dd343c2`, quarantaine fail-closed, rouge→vert, JVM 920/920, lint 0 erreur, assemble PASS ; intégré `b9f17f4` |
| 2026-11-02 | `30d078f8-bc51-437a-8aa4-80d1bfcd51f9` | Build final tentative 1 | codex / `gpt-6-luna` / `xhigh` | **REJECTED (provenance)** — APK `dba3509…` avec `worktree_clean=false` (log dans le worktree) ; rebuild propre demandé |
| 2026-11-02 | `30d078f8-bc51-437a-8aa4-80d1bfcd51f9` | Build final propre | codex / `gpt-6-luna` / `xhigh` | **ACCEPTED (build)** — APK `c7b0f71f…`, test `36adef05…`, JVM 922/922, lint 0 erreur, provenance `b9f17f4`/`worktree_clean=true` |
| 2026-11-02 | parent | AVD final après V4 | ADB isolé `5038`, AVD API 35 jetable | **PASS (synthétique)** — update final, backup/verify `d3d9fb91…`, restauration `30b3f045…`, HOME, rollback ; Keystore/Pixel NOT_RUN |
| 2026-11-02 | parent | Suite JVM intermédiaire `34c949d` | JVM 915/915 | **PASS (superseded par le build final 922/922)** — 120 fichiers, 0 échec/erreur/ignoré |

## 5bis. Revue indépendante

Session `ed7f0c88-9243-474a-8083-12478df0eb21`, `codex/gpt-6-luna`,
effort effectif `xhigh`, worktree `/tmp/atlas-v7-07-review-ro` détaché à
`34c949d` et `chmod -R a-w`. Aucun fichier modifié ; aucun ADB réel, aucune
action live, aucun réseau, aucun Docker.

**Verdict : `ACCEPTED_WITH_LIMITATIONS` — aucun P0.**

P1 confirmés :

1. `atlas_core_v4_sync` : une racine invalide/schéma inconnu est remplacée en
   mémoire par un état vide, puis une mutation peut écraser l'état persistant.
2. Configuration Gateway non qualifiée (`profile=full`, plugin natif concurrent,
   modèle personnel non testé).
3. Identités/consentements non réconciliés ; bearer Android partagé.
4. Transport distant indisponible ; `training_plan.read` non qualifié.
5. État produit Pixel non observé (paquet, données, HOME, permissions).

P2 : statut APK périmé, registre non réconcilié, procédure API 37/API 35
ambiguë, serials d'émulateur littéraux, cas UUID invalide non couvert.

Recommandations : `GO_SOURCE_AND_CI` **GO_WITH_LIMITATIONS** pour la base
`32e7c70` (CI `41350216879` verte) et le SHA final `b9f17f4` (JVM/lint/build
locaux, sans run CI car non poussé) ; `GO_DATA_BACKUP`/`RESTORE`/
`HOME_ROLLBACK` GO limités à l'AVD API 35 synthétique ; `GO_LIVE_*` NO-GO ;
`GO_PRODUCT_APK_CANDIDATE` **GO_WITH_LIMITATIONS** après le build final
`c7b0f71f…`, la revalidation AVD et la confirmation delta `ACCEPTED` sur
`cd45015` ; `GO_PRODUCT_INSTALL` et suivants NO-GO. Les corrections P2 et le
P1 V4 sont traitées dans la lignée `fix/v7-07-v4-quarantine` ; la revue delta
finale ne laisse aucun P0/P1/P2 ouvert.

**Confirmation delta (2026-11-02).** Même session
`ed7f0c88-9243-474a-8083-12478df0eb21`, provider `codex`, modèle
`gpt-6-luna`, effort effectif `xhigh` (statut Paseo parent vérifié). Revue du
commit `0203257` : P1 de provenance fermé, P2 de HEAD documentaire signalé puis
corrigé en `cd45015` (référence qualifiée d'état intermédiaire) ; spot-check
`ACCEPTED`. Les hashes APK/AndroidTest, la signature, les 16 permissions et les
manifestes de provenance ont été revérifiés sur les artefacts conservés. Aucun
fichier modifié, aucun ADB/Docker/live.

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
| `GO_SOURCE_AND_CI` | **GO_WITH_LIMITATIONS** | CI `41350216879` `success` sur la base `32e7c70` ; le SHA final `b9f17f4` n'a pas de run CI car il n'est pas poussé ; JVM **922/922**, lint 0 erreur, build PASS localement ; diff produit vs base limité au correctif V4 `dd343c2` |
| `GO_DATA_BACKUP` | **GO_WITH_LIMITATIONS (AVD API 35, synthétique) / NOT_RUN Pixel** | outil et archive validés sur AVD ; aucune sauvegarde du produit réel |
| `GO_DATA_RESTORE` | **GO_WITH_LIMITATIONS (AVD API 35, synthétique) / NOT_RUN Pixel** | restauration vérifiée sur AVD ; produit réel non restauré |
| `GO_HOME_ROLLBACK` | **GO_WITH_LIMITATIONS (AVD API 35, synthétique) / NOT_RUN Pixel** | rôle HOME posé/restauré sur AVD ; Settings à rejouer sur Pixel |
| `GO_LIVE_OPENCLAW_CONFIG` | **NO-GO** | plugin natif concurrent, `profile=full`, modèle non qualifié |
| `GO_LIVE_SECURITY` | **NO-GO / BLOCKED** | transport distant indisponible ; identités de test actives ; bearer Android partagé |
| `GO_PRODUCT_APK_CANDIDATE` | **GO_WITH_LIMITATIONS (delta ACCEPTED)** | APK final `c7b0f71f…`, JVM 922/922, provenance `b9f17f4` propre, AVD final PASS, revue delta `ACCEPTED` ; Pixel/Keystore/restauration produit NOT_RUN |
| `GO_PRODUCT_INSTALL` | **NO-GO** | aucune autorisation, backup/restore non démontré |
| `GO_PRODUCT_LOCAL_SMOKE` | **NO-GO / NOT_RUN** | produit non installé/testé |
| `GO_PRODUCT_REMOTE_CHAT` | **NO-GO / NOT_RUN** | produit non appairé ; transport arrêté |
| `GO_PRODUCT_PLAN_REVISION` | **NO-GO / NOT_RUN** | produit non testé |
| `GO_PRODUCT_REMOTE` | **NO-GO** | aucun parcours produit |
| `GO_DAILY_PILOT` | **NO-GO** | gate de données et de configuration non franchi |

Ces verdicts seront mis à jour uniquement sur preuve exécutée, jamais par
assimilation.
