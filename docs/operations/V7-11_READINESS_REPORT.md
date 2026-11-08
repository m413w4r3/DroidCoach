# V7-11A — Rapport de readiness et gates

**Date :** 2026-11-08
**Branche d'intégration :** `feat/atlas-v7-11-migration-readiness`
**BASE_SHA :** `8dac699cdb37e2f858a76e4cf7f9fc3bcd613e9c`
**SHA final d'intégration :** `<FINAL_SHA>` (commit de clôture)
**Frontière :** V7-11A uniquement. Aucune mutation live, aucun Pixel, aucun push.

## Résumé exécutif

V7-11A a qualifié le code V7-10/V7-11 sur base synthétique, reconstruit l'APK et l'image Core, rejoué la migration SQLite v3→v5, testé l'authentification négative, l'anti-rejeu, l'expiration et la préservation Android sur AVD API 37. Le code local est globalement sain, mais **le cutover live reste NO-GO** : la CI distante est rouge, le code V7-11 n'est pas déployé, le Relay/connecteur n'est pas administrable, l'identité/binding `pixel-v7` n'est pas vérifiée, l'anti-rejeu multi-worker n'est pas fermé et la bascule Android n'est pas définie au niveau opérateur.

| Axe | Verdict |
|---|---|
| Code et tests locaux | **GO local** (suites vertes après correctifs) |
| Migration/backfill/expiration sur copie | **GO local** |
| APK et préservation AVD | **GO local sous réserve HOME/A0** |
| Sécurité indépendante | **GO avec réserves P2** (A4-F1 sur `65759dd`) |
| CI distante | **NO-GO** (run rouge, aucun push autorisé) |
| Cutover live / Pixel / révocation | **NO-GO / NOT_RUN** |
| Pilote E 7 jours | **INTERDIT** |

## Git et CI

| Élément | Constat |
|---|---|
| `main` local / `origin/main` | `8dac699cdb37e2f858a76e4cf7f9fc3bcd613e9c` |
| Commit V7-10 final | `3123dba4f89579d414406d8c790609d1e4a5adf6` |
| CI GitHub du `8dac699` | Run `42467740775` : `relay-node` success, `verify` failure sur `Run backend tests` |
| Cause | 2 tests `scripts/v7-06c/tests/test_harness.py` : 10 valeurs dans `agent_proposals_v4` (11 colonnes) |
| Correctif V7-11 | `850f6a2` — 11e valeur `synthetic-device`, trigger inchangé |
| Suite racine après correctif | **499 passed, 1 skipped, 48 subtests** (rerun parent sur `65759dd`) |
| CI distante après correctif | **NOT_RUN** — aucun push autorisé ; le run distant reste rouge sur `8dac699` |

## Agents et worktrees

| Ticket | Agent | Worktree | Sortie | État |
|---|---|---|---|---|
| A1 | `d47863cc…` | `audit-v7-11-a1-core-migration` | `b9392a5`, `2c3e178` | ACCEPTED / INTEGRATED |
| A2 | `df882229…` | `audit-v7-11-a2-relay-replay` | `e4a4461`, `291b5f7` | ACCEPTED / INTEGRATED |
| A3 | `854a4c2b…` | `audit-v7-11-a3-android-candidate` | `57b5c19` | ACCEPTED / F1 en cours |
| A4 | `8e0d5e1f…` | `audit-v7-11-a4-security-review` | `81dc085` | ACCEPTED vague 1 / F1 en cours |
| A5 | `e540dcaa…` | `audit-v7-11-a5-ci-provenance` | `f37412a`, `b6c2cc0` | ACCEPTED / INTEGRATED |
| A6 | `87cdcf14…` | `audit-v7-11-a6-cutover-gates` | rapport | ACCEPTED audit / revue finale à faire |

Tous : `provider=codex`, `model=gpt-6-luna`, `thinkingOptionId=xhigh`, vérifiés par les métadonnées Paseo du parent. Aucune sous-délégation. Worktrees et fichiers d'écriture disjoints.

## Preuves et résultats

### Backend / Core

- Suite backend : **451 passed, 1 skipped** sur le code V7-10 ; **454 passed, 1 skipped** après A1-F2.
- Suite racine : **496 passed** après correction harness ; **499 passed** après A1-F2.
- Migration v3→v5 synthétique : PASS (intégrité, FK, checksums, idempotence).
- Import LEGACY, principaux `pixel-v7`/TOOL, credentials DIRECT/RELAY/TOOL, grants minimaux, binding : PASS.
- Backfill dry-run/apply avec backup vérifié : PASS, 0 ligne sans propriétaire après apply.
- Expiration par lots : PASS, 2 expirations/audits, `UNCERTAIN` conservé.
- Isolation propriétaire et triggers : PASS.

### Relay / anti-rejeu

- Connecteur : **131/131**.
- Proxy : **18/18**, cas épinglé 4402 exclu.
- Rejeu mono-cache : refusé. Rejeu inter-cache et après redémarrage : **accepté — P2 confirmé**.
- Fenêtre ±120 s, nonce, key_id, rotation à deux clés, cache saturé : PASS/fail-closed.
- Topologie live : Core 1 worker, Relay 4402, proxy 4400 et connecteur 4401 **absents**.

### Sécurité indépendante

- A4 vague 1 : **NO_GO** — 0 P0, 1 P1 (legacy réaccepte un bearer révoqué), 4 P2.
- P1 confirmé indépendamment : legacy=200, dual=401, principal=401.
- Correctif A1-F2 : garde `credential_state()` + 3 tests ; suite backend/racine verte.
- Re-revue A4-F1 sur `65759dd` : **GO avec réserves P2**. P1 fermé ; scénarios a–h PASS (LEGACY actif 200, révoqué 401, token inconnu 200, table absente 200 sans 500, autre audience 200, principal révoqué 401, dual/principal inchangés).
- P2 restants : capabilities vides, cache process-local, permissions SQLite, résidu historique des chemins/IDs sanitisés, intégrité du tombstone sous écriture SQLite directe (`audience`/`token_hash` non immuables).

### Android

- APK debug reconstruit : package `dev.atlas.coach`, version `1/0.1.0`, minSdk 26, targetSdk 36, allowBackup=false, debuggable=true.
- Certificat : `da3014662eb65e50a87ef364a2e583c331398795b222e3b37b1a16553a00eeee`.
- Hash A3 (testé AVD, source 8dac699) : `97ac580d2cdcc5384a1f1ab8e15f2f3b4d57ca468177dede55166be154c321d5`.
- Hash A5 (build intégré, source 04fdc77) : `27baca26312fa6981b8e11fc846eb32b08f41c7a631df36a69df1c986239d13e`.
- AVD API 37 : install -r, firstInstallTime stable, 7 fixtures préservées, cold start OK, Coach 9/9, scheduler 1/1, rollback PASS.
- JVM/lint/build indépendants A5 : **934 tests, 0 échec, lint 0 erreur/80 avertissements**.
- HOME non exercé et A0 isolé NOT_RUN dans la première passe ; suivi A3-F1 en cours.
- Image Core : `sha256:2c5ae216e1ed292cd11cbc87ec392b9e546187a52727156754afb440c2f1cd57` (150 498 024 octets, v4 présent).

### Live (lecture seule)

- Core pré-V7-10, 1 uvicorn, DB `user_version=3`, integrity ok, 3 propositions / 2 ACK.
- Relay `r3a` 4402 ; proxy 4400 et connecteur 4401 absents ; Funnel 8443→4400 inopérant.
- Gateway 18789 actif ; Pixel présent sur ADB USB mais nœud Tailscale offline depuis 33 j.
- Aucune mutation live, aucun redémarrage.

## Gates V7-11

| Gate | Statut V7-11A | Portée |
|---|---|---|
| `GO_V7_11_MAIN_AND_CI` | **NO-GO (remote) / PASS local** | Suite locale verte ; CI distante rouge et non poussée. |
| `GO_V7_11_CODE_PROVENANCE` | **GO local** | SHA source, APK reconstruits, certificat, image Core ; artefact final à rebuilder au SHA final. |
| `GO_V7_11_ANTI_REPLAY_TOPOLOGY` | **NO-GO live** | Rejeu inter-worker/après redémarrage P2 confirmé ; mitigation non tranchée. |
| `GO_V7_11_AUTH_STAGING` | **GO local partiel** | Tests négatifs composants PASS ; stack Core+Relay complète NOT_RUN. |
| `GO_V7_11_DEVICE_ISOLATION` | **GO local** | A/B refusé, ownership et isolation testés ; live NOT_RUN. |
| `GO_V7_11_LEGACY_COMPATIBILITY` | **GO local sous réserve A4-F1** | P1 legacy fermé par A1-F2 ; re-revue en cours. |
| `GO_V7_11_SQLITE_MIGRATION_DRY_RUN` | **GO local** | Migration v3→v5 sur copie synthétique. |
| `GO_V7_11_HISTORICAL_BACKFILL_DRY_RUN` | **GO local** | Dry-run + apply sur copie ; attribution live ouverte. |
| `GO_V7_11_EXPIRATION_DRY_RUN` | **GO local** | Expiration/réconciliation sur copie ; `UNCERTAIN` préservé. |
| `GO_V7_11_ANDROID_APK_CANDIDATE` | **GO local** | Code/cert/version/manifeste ; artefact final à rebuilder. |
| `GO_V7_11_ANDROID_PRESERVATION_AVD` | **GO local sous réserve A3-F1** | Update/preservation/rollback PASS ; HOME/A0 en cours. |
| `GO_V7_11_RELAY_SYNC_AVD` | **NOT_RUN** | Aucun Relay/Core E2E ; scheduler local seulement. |
| `GO_V7_11_BACKUP_ROLLBACK_PLAN` | **GO local avec limitations** | Plan audité ; aucun backup/restore Pixel ou live exécuté. |
| `GO_V7_11_INDEPENDENT_SECURITY_REVIEW` | **GO avec réserves P2** | A4-F1 sur `65759dd` : 0 P0/P1, 5 P2 ; P1 legacy fermé. |
| `GO_V7_11_LIVE_CHANGE_READINESS` | **NO-GO / dossier incomplet** | Relay non administrable, identité/binding inconnus, anti-rejeu, bascule Android, backup live. |
| `GO_PRODUCT_INSTALL` | **NO-GO / NOT_RUN** | Aucune installation Pixel. |
| `GO_LIVE_SECURITY` | **NO-GO / NOT_RUN** | Aucun test live. |
| `GO_DAILY_PILOT` | **NO-GO / NOT_RUN** | Pilote E interdit. |

## Risques ouverts

### P0
Aucun.

### P1
1. **CI distante rouge** : le correctif local n'est pas poussé ; la CI de `8dac699` reste en échec. Un push/PR nécessite l'accord explicite de l'utilisateur.
2. **Relay live non administrable** : conteneur sans labels Compose, sans volume, sans unité systemd ; le handoff V7-10 utilise `$RELAY_UNIT` et un script de backup qui ne correspondent pas.
3. **Identité/binding `pixel-v7` inconnus** : non observables sans accès live/ADB supplémentaire ; un binding révoqué ne peut pas être réactivé.
4. **Bascule Android non définie** : `install -r` ne change pas l'identité ; il faut un geste UI/API Relay ou DIRECT et un état local/offline fail-closed.
5. **Anti-rejeu non fermé** : cache process-local ; choisir cache partagé durable ou worker unique renforcé.
6. **Backup/restore live non testés** : aucun snapshot Core/Relay/Pixel réel, aucune restauration in-situ.

### P2
- `ATLAS_V4_CAPABILITIES_JSON=""` équivaut à absent et conserve les mutations par défaut.
- `SQLiteStore` ne force pas `0700/0600` ni le refus de symlink.
- Intégrité du tombstone : une écriture SQLite directe des colonnes `audience`/`token_hash` peut rendre un tombstone invisible. Nécessite un accès en écriture à la base ; garder le fichier inaccessible hors Core et envisager des triggers d'immutabilité.
- Résidu historique Git des chemins/IDs sanitisés.
- APK non bit-reproductible entre SHAs (provenance embarquée) ; l'artefact final doit être rebuild et re-testé.
- `UNCERTAIN` sans terminaison automatique ; réconciliation explicite requise.
- Preuve AVD limitée : pas de Pixel physique, pas de Relay réel.

## Ce qui est prêt pour V7-11B

- Procédure de migration/backfill/expiration sur copie, avec backup vérifié.
- Provisionnement offline des principaux/credentials/grants/binding.
- Correctif P1 legacy et garde anti-tombstone.
- Artefact APK de code qualifié sur AVD API 37, certificat produit conservé.
- Image Core candidate construite avec les fichiers V4.
- Plan de changement et matrice de rollback corrigés.

## Ce qui doit être fait avant toute autorisation live

1. Pousser la branche et obtenir une CI verte (accord utilisateur requis).
2. Identifier et rendre administrable le Relay (propriétaire, config, volume/état, commande de recréation), puis rétablir proxy 4400 et connecteur 4401.
3. Vérifier l'identité Relay réelle et le binding `pixel-v7` ; ne pas révoquer ce binding pour un test.
4. Trancher l'anti-rejeu : cache partagé durable ou worker unique renforcé + test après redémarrage.
5. Décrire le geste Android de bascule Relay/DIRECT et l'état local/offline en cas de refus Core.
6. Exécuter le backup live, le test de restauration et les gates Pixel sous autorisation ciblée.
7. Rebuild l'APK et l'image Core au SHA final, puis re-tester.

## Autorisations V7-11B à demander séparément

1. **Réversible avant cutover** : backup, copie DB, provisioning sur copie, tests isolés, build APK/image.
2. **Peut interrompre le Coach** : arrêt/redémarrage Core, arrêt/redémarrage connecteur, bascule dual, redémarrage Relay.
3. **Nécessite le Pixel physique** : inventaire ADB, backup app-only, installation in-place, vérification HOME/Keystore/consentements, tests de connexion.
4. **Irréversible** : révocation du bearer LEGACY, rotation de credential, révocation Relay, passage en `principal`, restauration écrasante.
5. **Risque résiduel** : anti-rejeu, `UNCERTAIN`, backfill ambigu, restauration app-only liée au serial, perte Keystore, coupure Funnel/connecteur, rollback après révocation.

## Verdict

**V7-11A est terminé sur le plan local avec des preuves solides, mais le cutover live est NO-GO.** La qualification a produit des correctifs et des artefacts vérifiables ; elle a aussi mis en évidence des bloqueurs d'exploitation qui ne peuvent pas être résolus sans autorisation live ciblée. Aucune opération V7-11B n'a été franchie. Le dossier d'autorisation ci-dessus doit être complété par le SHA final, la CI verte, l'identité Relay/binding réelle et le choix anti-rejeu avant toute fenêtre de migration.
