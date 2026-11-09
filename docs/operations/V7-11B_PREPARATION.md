# V7-11B — Addendum de préparation

**Date :** 2026-11-08
**Branche :** `feat/atlas-v7-11-migration-readiness` (PR #3, ouverte, non fusionnée)
**SHA V7-11A (clôture initiale) :** `9f0c7834d5ea19d0bf6b9d688a722ea6361c7cd2` — code `65759dd`
**SHA code après correctifs préparatoires :** `a3d72db` (cache nonce durable + verrou/initialisation)
**Périmètre :** préparation uniquement. Aucune mutation live, aucun Pixel, aucune révocation, aucun backup live.

## 1. Objet

Cet addendum documente les travaux réalisés **après** la clôture V7-11A, dans le cadre autorisé par l'utilisateur (« procède à la suite ») : publication de la branche/PR, CI distante, verrou single-writer, garde anti-rejeu après redémarrage, rebuild exact des artefacts et reconnaissance de l'administration Relay. Il ne remplace pas le [rapport final V7-11A](V7-11_FINAL_REPORT.md) pour le code `65759dd` ; il fournit l'état de préparation V7-11B sur le code `c031cb5`.

## 2. Git, PR et CI

- Branche poussée : `feat/atlas-v7-11-migration-readiness`.
- PR : https://github.com/m413w4r3/DroidCoach/pull/3 — **ouverte, non fusionnée**.
- CI verte sur `9f0c783` :
  - Run PR `42478131908` : `verify` PASS (4m15s), `relay-node` PASS.
  - Run push `42478116796` : `verify` PASS (4m57s), `relay-node` PASS.
- Le nouveau code `c031cb5` (verrou + garde) doit être poussé et revalidé par la CI avant toute conclusion live.

## 3. Correctifs anti-rejeu

### 3.1 Verrou single-writer (`132f17b`)
`backend/atlas_backend/main.py` acquiert un `fcntl.flock(LOCK_EX|LOCK_NB)` sur `<db>.lock` (O_NOFOLLOW, 0600) pendant le lifespan et le libère à l'arrêt. Un second Core sur la même base échoue avec une erreur explicite. Les alias symlink de la base partagent le verrou ; un fichier `.lock` symlink est refusé ; `:memory:` n'crée pas de fichier. Les CLI offline ne prennent pas le verrou : elles doivent être utilisées Core arrêté.

Tests : `backend/tests/test_core_db_lock.py` (5 tests). Suite racine après intégration : **504 passed, 1 skipped, 48 subtests** (avant la garde A2).

**P7 (`6523161`)** : `create_app` acquiert le verrou **avant** de construire `SQLiteStore` (initialisation/migration), le libère en `finally` ; le lifespan protège le démarrage et libère après l'arrêt des tâches. Les tests sont bornés à 5 s, exécutent le cleanup inline et vérifient l'absence de tâches résiduelles ; 10/10 PASS. La cause exacte du blocage observé par A4 n'a pas été reproduite ; le test est désormais déterministe et borné.

### 3.2 Garde anti-rejeu après redémarrage (`c031cb5`)
`create_v4_router` capture `core_started_at_ms`. Pour une assertion Relay, après vérification HMAC/fenêtre/binding, `require_android` refuse si le timestamp de l'assertion est antérieur au démarrage moins `CLOCK_SKEW_MS = 5000`. La fenêtre ±120 s et le cache nonce mono-processus restent inchangés ; DIRECT sans assertion n'est pas affecté.

Tests : assertion pré-démarrage rejouée après recréation => **401** (avant : 200) ; assertion post-démarrage => 200 ; timestamp 3 s avant démarrage => 200 ; replay mono-cache et fenêtre ±120 s non régressés. Suite racine après intégration : **505 passed, 1 skipped, 48 subtests**.

**P6 (`b98065a`, intégré `a3d72db`)** : `FileReplayNonceCache` durable dans un répertoire privé partagé (`ATLAS_V4_REPLAY_CACHE_DIR`), réservation atomique `O_CREAT|O_EXCL`, fichiers 0600, répertoire 0700, TTL 300 s, capacité 8192, nettoyage, verrou de répertoire, fail-closed sur toute erreur. Il survit aux redémarrages/workers et ferme le rejeu lié au recul d'horloge : les tests couvrent deux instances, concurrence, TTL, erreurs et recul d'horloge 30 s. Pas de fallback mémoire si la variable est définie mais que le cache échoue. Suite racine après intégration : **513 passed, 1 skipped, 48 subtests**.

### 3.3 Revue A4-F2/A4-F3 et P2 résiduels
A4-F2 sur `c031cb5` : **GO avec 8 P2**. A4-F3 sur `a3d72db` (cache durable + verrou/initialisation) : **GO avec limitations P2**, 0 P0/P1. Les tests ciblés passent (35 passed) ; le test de verrou passe 10/10 ; la suite backend complète n'a pas abouti chez A4 (37 points, interrompue) — le rerun parent reste **513 passed, 1 skipped, 48 subtests**.

P2 actionnables et état :
1. **Recul d'horloge + purge TTL** : le cache durable ferme le rejeu inter-instances/redémarrage, mais après une purge TTL (300 s) puis un retour d'horloge à T, une assertion ancienne peut être réacceptée. **P2 résiduel** : exiger une horloge stable/NTP et interdire tout recul manuel pendant la fenêtre ; envisager un high-water mark persistant si le modèle de menace inclut la manipulation d'horloge.
2. **Alias de `key_id`** : la chaîne signée n'inclut pas le `key_id` ; deux IDs portant le même secret permettent la même signature une fois par ID. **Condition de déploiement** : secrets HMAC distincts par `key_id` (le keyring de rotation doit le garantir) ; validation supplémentaire recommandée.
3. **Marqueur corrompu** : un fichier de nonce vide/tronqué fait échouer fermé toutes les nouvelles réservations. **Fail-closed voulu** ; procédure de récupération : Core arrêté, supprimer le marqueur corrompu identifié, redémarrer. À documenter dans le runbook V7-11B.
4. **Initialisation SQLite avant le verrou** : fermé par P7 (verrou avant `SQLiteStore`, libération sur erreurs).
5. **Test deux bases bloqué** : fermé par P7 (test borné 5 s, cleanup inline, 10/10 PASS).
6. **CLI sans verrou** : règle opératoire — utiliser les CLI offline Core arrêté.
7. Réserves antérieures : capabilities explicitement vides, permissions/identité SQLite, chemins locaux historiques, modification directe SQLite des tombstones.

Le gate anti-rejeu est **GO local avec limitations P2** sous conditions live :
- `ATLAS_V4_REPLAY_CACHE_DIR` configuré sur un chemin absolu persistant, possédé par Core, mode 0700, même volume entre redémarrages ;
- un seul Core par volume/base, pas de scale/instance parallèle, verrou déployé ;
- secrets HMAC distincts par `key_id` ;
- horloge stable/NTP, pas de recul manuel ;
- procédure de récupération d'un marqueur corrompu.

## 4. Artefacts exacts

| Artefact | SHA / ID | Portée |
|---|---|---|
| APK debug exact au SHA code `65759dd` | `bb703ec770f4ebe7e20602db65fecfb75e7a783d1a30040f5f7d8796c29f1dff` | 15 838 971 octets, `source_sha=65759dd`, certificat `da301466…eeee` |
| AndroidTest APK exact `65759dd` | `6a99030de3cefd67dd65aa249fe53c83dcb6a73b4acc517dfe52cf0bd58d5f15` | `dev.atlas.coach.test` |
| Image Core au SHA code `c031cb5` (historique) | `sha256:49d26b7e3272a6e9fce944a899c929a0aa40f9ec8c8bfcdbb374200445e53c23` | 150 502 127 octets, CMD `uvicorn atlas_backend.main:app --host 127.0.0.1 --port 8100`, `v4_auth.py`/`v4_assertion.py`/verrou présents |
| **Image Core au SHA final `a3d72db`** | `sha256:528af66423839c189720982007e9ea6e16ef8eb463a8a7d0a30af67c696c83c3` | 150 514 788 octets, CMD `uvicorn atlas_backend.main:app --host 127.0.0.1 --port 8100`, `FileReplayNonceCache` + verrou avant `SQLiteStore` présents |
| APK de référence testé AVD | `97ac580d…c321d5` | construit à `8dac699`, arbre Android identique à `65759dd`/`c031cb5` |

Le diff Android `65759dd..c031cb5` est vide : l'APK exact `bb703ec7…` reste l'artefact Android de référence. L'APK de référence AVD (`97ac580d…`) et l'APK exact partagent le même arbre Android, le même certificat et le même `versionCode=1` ; seul le SHA de provenance embarqué diffère.

## 5. Administration Relay/connecteur — reconnaissance

Preuve complète : [V7-11_RELAY_ADMIN_RECON.md](evidence/V7-11_RELAY_ADMIN_RECON.md).

| Constat | Conséquence |
|---|---|
| Relay = conteneur Docker `atlas-v7-relay`, `restart=unless-stopped`, aucun label Compose, aucun mount, aucun healthcheck, aucune unité systemd. | Origine/commande de création inconnues ; recréation live non sûre. |
| État Relay probablement volatil ; aucune identité/DB trouvée dans la recherche bornée. | Ne pas supprimer/recréer sans preuve de persistance et sauvegarde. |
| `scripts/r3a/start-relay.sh` peut faire `docker rm -f` et publie `4400:4000`. | **Ne pas l'utiliser sur le live.** |
| `run-isolated-relay-tests.sh` vise 4402 par défaut. | Toujours fixer un port distinct (ex. 14402) pour les tests isolés. |
| `backup-state.mjs`/`state-backup.mjs` omettent `operations.json`. | Sauvegarde connecteur incomplète ; compléter avant toute opération d'identité. |
| `show-identity` peut créer une identité si elle manque. | Ne pas l'exécuter sur le live sans autorisation. |
| Fichiers d'état connecteur présents en `0600` (identity/devices/operations). | Présence vérifiée, appairage effectif non établi. |
| Recette isolée documentée : Core 18100, proxy 14400, connecteur 14401, Relay 14402. | Non exécutée ; à qualifier avant tout E2E Relay. |

**Verdict Relay :** toute mutation live reste **BLOCKED** jusqu'à identification du propriétaire, de la persistance, de la sauvegarde complète et d'une procédure de recréation testée.

## 6. Gates mis à jour (code `c031cb5`)

| Gate | Statut | Portée |
|---|---|---|
| `GO_V7_11_MAIN_AND_CI` | **GO** sur `9f0c783` ; **à revalider** sur `c031cb5` après push | PR #3 verte ; nouveau code à pousser. |
| `GO_V7_11_CODE_PROVENANCE` | **GO local** | APK exact `bb703ec7…` inchangé ; image Core finale `1e4c8e6c…` construite à `7efbefc` (B7 inclus) et active en live ; P2 : inspection Docker indépendante `UNKNOWN`, image locale non poussée en registre. |
| `GO_V7_11_FINAL_IMAGE_PROVENANCE` | **GO local avec P2** | B12 : image `sha256:1e4c8e6c…`, 150 520 138 octets, build propre à `7efbefc`, tag unique, marqueurs B7 + `FileReplayNonceCache` ; image/tag live `8604535e…` inchangés. P2 : inspection Docker indépendante `UNKNOWN` (B13), image non poussée en registre. |
| `GO_V7_11_ANTI_REPLAY_TOPOLOGY` | **GO local avec limitations P2** | A4-F3 GO ; verrou + garde + cache durable P6 + fiabilité P7 ; conditions live : cache dir persistant 0700, un seul Core/volume, secrets HMAC distincts par key_id, horloge stable. |
| `GO_V7_11_AUTH_STAGING` | **GO local partiel** | Inchangé ; stack complète isolée non exécutée. |
| `GO_V7_11_DEVICE_ISOLATION` | **GO local** | Inchangé. |
| `GO_V7_11_LEGACY_COMPATIBILITY` | **GO local avec limitation SQLite** | Inchangé. |
| `GO_V7_11_SQLITE_MIGRATION_DRY_RUN` | **GO local** | Inchangé. |
| `GO_V7_11_HISTORICAL_BACKFILL_DRY_RUN` | **GO local** | Inchangé. |
| `GO_V7_11_EXPIRATION_DRY_RUN` | **GO local** | Inchangé. |
| `GO_V7_11_ANDROID_APK_CANDIDATE` | **GO local** | APK exact `bb703ec7…`, certificat correct. |
| `GO_V7_11_ANDROID_PRESERVATION_AVD` | **GO_WITH_LIMITATIONS** | Inchangé ; APPLY A0 bloqué sur `DEMO`. |
| `GO_V7_11_ANDROID_PRESERVATION_PHYSICAL` | **GO_WITH_LIMITATIONS** | Installation in-place sur Pixel 9 Pro XL API 37 : APK exact `bb703ec7…`, données byte-identiques, HOME/permissions conservés, aucun crash. QA fonctionnelle et APPLY NOT_RUN. |
| `GO_V7_11_RELAY_SYNC_AVD` | **NOT_RUN** | Aucun E2E Relay avec Android ; le stack isolé B1 ne vaut pas test AVD. |
| `GO_V7_11_ISOLATED_STACK` | **GO local final** | B1 (stack neuf) + B12 (image finale sur **copie réelle** migrée) : health 200, v5, backup 0600, verrou, cache 0700, legacy 200/401, Relay/proxy/connecteur, fail-closed ; live inchangé. Déviation bridge/`0.0.0.0` interne documentée. |
| `GO_V7_11_IDENTITY_BINDING` | **NOT_RUN** | Base live désormais v5, mais l'authentification reste `legacy` : aucun principal, credential ou binding V4 n'a été créé/vérifié. Le binding réel reste à faire avec autorisation dédiée. |
| `GO_V7_11_REAL_COPY_MIGRATION` | **GO local final** | B5 puis B9/B10 : migration v3→v5 sur copie réelle PASS avec le code corrigé ; backup 0600, fail-closed, 3 propositions sans propriétaire, 1 candidate expiration, live toujours v3. |
| `GO_V7_11_BACKUP_PERMISSIONS` | **GO local avec limites P2/P3** | B7/B8/B9/B10 : backup `0600` avec `O_EXCL`/`O_NOFOLLOW`, fail-closed, P1 fermé sur copie réelle ; fsync répertoire, mémoire, `os.close` et portabilité restent P2/P3. |
| `GO_V7_11_INDEPENDENT_FINAL_COPY_REVIEW` | **GO local avec limites** | B10 : P1 fermé sur données live, 0 P0/P1 ; migration live NO-GO. |
| `GO_V7_11_CONNECTOR_BACKUP_COVERAGE` | **GO local avec gap confirmé** | B3 : `operations.json` omis du backup/restore CLI ; procédure complète 3/3 testée sur état synthétique. Backup live non exécuté. |
| `GO_V7_11_INDEPENDENT_ISOLATED_REVIEW` | **GO local avec P2 / NO-GO live** | B4 : 0 P0/P1, 2 P2 ; vérification Docker directe UNKNOWN ; aucun test/live exécuté par le relecteur. |
| `GO_V7_11_BACKUP_ROLLBACK_PLAN` | **GO local avec limites** | Backup live Core/Pixel/connecteur exécuté et restauration testée sur copies ; pas de restauration live, pas de copie hors site/chiffrée. |
| `GO_V7_11_LIVE_BACKUP` | **GO local avec limites** | Fenêtre du 2026-11-08 : Core arrêté ~14 s, DB/sidecar copiés, Pixel V7-07 vérifié, connecteur 3/3 restauré sur copie, Relay jamais arrêté ; B11 GO local, 0 P0/P1, 2 P2 fermés. |
| `GO_V7_11_LIVE_MIGRATION` | **GO local** | Migration live v3→v5 exécutée le 2026-11-08 : backup frais `9f5c8fd2…`, image `1e4c8e6c…`, v5/ledger/intégrité/FK, backup B7 `0600`, legacy 200/401, Relay intact ; B14 GO local, 0 P0/P1. |
| `GO_V7_11_SCHEMA_V5_LIVE` | **GO local** | Base live v5, ledger `[1..5]`, `device_ref`, contrôle logique 17/19 tables MATCH, seules différences attendues (état d'expiration + ledger). |
| `GO_V7_11_ANDROID_POST_MIGRATION_SYNC` | **PASS avec limitation** | Pixel intact ; proxy 4400 + connecteur 4401 actifs ; chat utilisateur « test » → `POST /v2/agent/chat` **200** à 12:25:52Z ; session connecteur 1 requête/0 erreur ; WS 101 ; réponse visible. `CoreV4SyncWorker` d'état non observé séparément. |
| `GO_V7_11_INDEPENDENT_SECURITY_REVIEW` | **GO avec limitations P2** | A4-F3 sur `a3d72db` : 0 P0/P1 ; 3 nouveaux P2 + réserves héritées ; conditions de déploiement listées ci-dessus. |
| `GO_V7_11_LIVE_CHANGE_READINESS` | **NO-GO** | Migration v3→v5 **exécutée**, image finale active et **chat app→Core v5 PASS avec limitation**, mais cutover complet non qualifié : binding/principal non configurés, Relay non administrable, backup local sans hors-site/chiffrement, DB live 0644, aucun APPLY. |
| `GO_PRODUCT_INSTALL` | **GO partiel** | Installation in-place et préservation **PASS** sur Pixel physique API 37 (preuve `evidence/V7-11B_PIXEL_INSTALL_EVIDENCE.md`) ; QA fonctionnelle complète NOT_RUN ; APPLY NOT_RUN. |
| `GO_LIVE_SECURITY` | **NO-GO / NOT_RUN** | Aucun test live. |
| `GO_DAILY_PILOT` | **NO-GO / NOT_RUN** | Pilote E interdit. |

## 7. Autorisations et prochaines étapes

1. **A4-F2/F3** : GO avec limitations P2 (fait).
2. **P6/P7** : cache nonce durable partagé et fiabilité du verrou/initialisation, revus A4-F3 (fait).
3. **Push** des commits de code et des preuves associées sur la PR #3 ; CI verte à revalider sur le head final.
4. **Conditions live** : cache dir persistant 0700, un seul Core/volume, secrets HMAC distincts par key_id, horloge stable/NTP, runbook de récupération d'un marqueur corrompu.
3. **A6** : mise à jour éventuelle du dossier de gates avec le code `c031cb5` et l'addendum.
4. Ensuite seulement, demander des autorisations séparées :
   - **Relay admin** : identifier propriétaire/persistance, compléter la sauvegarde `operations.json`, tester une recréation sur environnement isolé.
   - **Backup live** : snapshot Core/Relay/connecteur/Pixel avec fenêtre d'arrêt.
   - **Promotion dual** : sur copie vérifiée.
   - **Installation Pixel** : APK exact `bb703ec7…` ou rebuild au SHA final, avec sauvegarde app-only.
   - **Test APPLY** : sur plan jetable/synthétique, ou autorisation Pixel/données séparée avec backup/restauration.
   - **Cutover irréversible** : révocation, rotation, `principal`, avec autorisation nominative distincte.

Aucun de ces blocs n'est autorisé par le présent addendum.

## 8. Addendum — installation Pixel (2026-11-08)

L'utilisateur a choisi le paquet « Installation Pixel (physique) ». L'installation in-place de l'APK exact `bb703ec7…` a été exécutée sur le Pixel 9 Pro XL physique (Android 17 / API 37, utilisateur 0) après sauvegarde app-only.

- Sauvegarde préalable hors dépôt : APK antérieur `c7b0f71f…55a0` ; données `115acfbd…f048` (26 fichiers, archive extraite et vérifiée) ; permissions et rôle HOME capturés.
- `adb install -r` : **Success**, 2026-11-08T16:08:42Z → 16:08:51Z.
- Après installation : APK installé = candidat exact `bb703ec7…f1dff`, certificat `da301466…eeee`, `versionCode=1`, `versionName=0.1.0`, `firstInstallTime` inchangé, 23/23 permissions identiques, rôle HOME conservé, archive données **byte-identique**.
- Après démarrage : 15/15 `shared_prefs` identiques (consentements et configuration de synchronisation préservés) ; seuls le marqueur ART `profileInstalled` et les journaux WorkManager changent ; cold start 399 ms ; intent HOME 101 ms ; aucun FATAL/ANR/Keystore.
- Core/Relay live inchangés : `restarts=0`, démarrés 2026-11-07T10:05:46Z, health 200, ports 8100/4402, 4400/4401 absents.

**Preuve :** [`evidence/V7-11B_PIXEL_INSTALL_EVIDENCE.md`](evidence/V7-11B_PIXEL_INSTALL_EVIDENCE.md).

**Limites :** QA fonctionnelle complète NOT_RUN ; APPLY A0 NOT_RUN/BLOCKED ; Keystore non introspectable sans root ; rollback disponible mais non exercé sur le Pixel ; autres utilisateurs Android non examinés. Aucune autorisation supplémentaire n'est consommée par cet addendum : la révocation, le cutover, la promotion `dual`/`principal`, le backup live et la modification Relay restent interdits.

## 9. Addendum — qualification isolée, identité et backup connecteur (2026-11-08)

Paquet autorisé par l'utilisateur : « Qualification isolée + identité lecture seule ». Trois tickets `codex/gpt-6-luna` / `xhigh` vérifiés, intégrés sur la branche.

### 9.1 B1 — stack isolé : GO local

- Endpoints `127.0.0.1:18100/14400/14401/14402` uniquement ; Core `mock`, token synthétique, SQLite neuve `user_version=5`, `integrity_check=ok`.
- Core/Relay/proxy `/health` = 200 ; connecteur `/metrics` = 200 avec `relay_connected=true`, `errors_total=0` ; proxy `ws_upgrade status=101` ; registration Relay observée.
- État connecteur : `identity.json` 0600 ; `devices.json`/`operations.json` absents sans appairage.
- Live `atlas-core-atlas-core-1` et `atlas-v7-relay` : `restarts=0`, `StartedAt` identiques avant/après ; ports live inchangés.
- Déviation : bridge dédié **non-`internal`** avec publication `127.0.0.1` seulement, car `--internal` + publication loopback ne crée aucun listener hôte (test parent `000`). Pas de capture paquets exhaustive.
- Preuve : [`evidence/V7-11B_ISOLATED_STACK_EVIDENCE.md`](evidence/V7-11B_ISOLATED_STACK_EVIDENCE.md).

### 9.2 B2 — identité/binding live : BLOCKED

- Base live toujours `user_version=3`, `integrity_check=ok`.
- Tables `principals_v4`, `credentials_v4`, `principal_capabilities_v4`, `relay_principal_bindings_v4` **absentes** ; `agent_proposals_v4` contient 3 lignes mais **aucune colonne `device_ref`**.
- `ATLAS_V4_AUTH_MODE` et `ATLAS_V4_ENABLED` sont unset ; le code retombe sur `legacy`.
- Connecteur : `identity.json` (clé publique 32 octets, fingerprint cohérent), `devices.json` 20 entrées dont le libellé `pixel-v7`, `operations.json` 9 entrées ; modes 0600, mtimes inchangés.
- Conséquence : l'association `pixel-v7` → principal RELAY → propositions **ne peut pas être établie** sur le live actuel. Le binding n'est pas « absent » : il est **non observable** faute de schéma V4. La migration v3→v5 reste à exécuter sur une copie vérifiée, puis sur le live avec autorisation distincte.
- Preuve : [`evidence/V7-11B_IDENTITY_BINDING_EVIDENCE.md`](evidence/V7-11B_IDENTITY_BINDING_EVIDENCE.md).

### 9.3 B3 — backup connecteur : GO local avec gap

- `backup-state.mjs` et `restore-state.mjs` ne traitent que `identity.json` et `devices.json` ; `operations.json` est absent du backup, du manifeste et de la restauration.
- Gap reproduit par le parent : le backup valide contient `identity.json`, `devices.json`, `manifest.json` et **pas** `operations.json`.
- Procédure complète manuelle testée sur état synthétique : 3/3 fichiers, hashes source = manifeste = cible, modes 0700/0600.
- Le backup live n'a pas été exécuté ; le connecteur live n'a pas été arrêté.
- Preuve : [`evidence/V7-11B_CONNECTOR_BACKUP_EVIDENCE.md`](evidence/V7-11B_CONNECTOR_BACKUP_EVIDENCE.md).

### 9.4 Gates et conditions

| Gate | Statut |
|---|---|
| `GO_V7_11_ISOLATED_STACK` | **GO local** (PASS B1) |
| `GO_V7_11_IDENTITY_BINDING` | **BLOCKED / UNKNOWN** (B2) |
| `GO_V7_11_CONNECTOR_BACKUP_COVERAGE` | **GO local avec gap** (B3) |
| `GO_V7_11_RELAY_SYNC_AVD` | **NOT_RUN** |
| `GO_V7_11_LIVE_CHANGE_READINESS` | **NO-GO** |

Conditions avant toute action live :

1. Sauvegarde live Core/Relay/connecteur/Pixel dans une fenêtre autorisée, avec restauration testée.
2. Migration SQLite v3→v5 sur copie vérifiée, puis décision distincte pour le live.
3. Création/vérification du principal et du binding `pixel-v7` après migration ; aucune revendication avant preuve.
4. Procédure de backup connecteur complète (3 fichiers) déployée ou exécutée manuellement, service arrêté.
5. Relay identifié/administrable et recréation testée hors live.
6. Aucun APPLY, aucune révocation, aucun changement de bearer sans autorisation nominative distincte.

### 9.5 Revue indépendante B4

La revue indépendante B4 sur le SHA intégré `4b87c93` conclut **GO_WITH_LIMITATIONS local / NO-GO live**, avec **0 P0/P1** et **2 P2** :

1. **UUID de sessions d'agents dans le registre** : ils sont conservés pour la traçabilité d'orchestration ; aucun ID d'appareil, secret ou donnée personnelle n'est exposé. Ce point est accepté comme P2 documentaire, sans retrait des identifiants de sessions nécessaire à la reproductibilité.
2. **Durabilité/atomicité du restore manuel** : le remplacement des trois fichiers n'est pas atomique, sans `fsync` ni rollback automatique, et la création de la destination a une fenêtre TOCTOU. La procédure complète reste **non déployée** et ne doit pas être utilisée sur le live avant durcissement (écriture temporaire, `fsync` fichiers+répertoire, rollback, contrôle d'absence de course) et test de restauration.

La revue n'a pas interrogé Docker directement ; les faits live B2 sont recoupés avec l'inventaire et le registre mais marqués `UNKNOWN` pour la vérification indépendante. Les résultats B1/B3 sont examinés comme preuves fournies, non rejoués. Preuve : [`evidence/V7-11B_ISOLATED_REVIEW.md`](evidence/V7-11B_ISOLATED_REVIEW.md).

## 10. Addendum — migration v3→v5 sur copie réelle (2026-11-08)

Paquet autorisé par l'utilisateur : « Migration v3→v5 sur copie réelle ». Ticket B5 `codex/gpt-6-luna` / `xhigh` vérifié.

- **Trois exports live** en lecture seule : SHA-256 identique `752919f2…`, `user_version=3`, `integrity_check=ok`, `foreign_key_check=[]`, 3 propositions V4 / 2 événements / 2 consentements / 1 ligne historique. Le live n'a pas été modifié.
- **Migration de la copie** : `user_version=5`, ledger `[1,2,3,4,5]`, tables V4/V5 et colonne `device_ref` présentes, historique et compteurs inchangés. Sauvegarde pré-migration `df644a94…`, v3, intégrité ok, mode 0600.
- **Backfill dry-run** : 3 propositions sans propriétaire ; cible LEGACY **synthétique** uniquement ; 0 credential ; hash inchangé. Aucun `--apply`.
- **Expiration dry-run** : 3 propositions (APPLIED 1 / PENDING_ANDROID 1 / REJECTED 1) ; 1 candidate PENDING_ANDROID ; 0 candidat avec ACK ; 0 anomalie. Aucun `--apply`.
- **Idempotence** : réouverture conserve v5, ledger, intégrité, FK, historique et compteurs ; l'empreinte du dump SQL reste stable. Le hash binaire du fichier change à chaque initialisation parce que l'initialiseur réassigne `PRAGMA user_version` pour les migrations déjà appliquées ; ce n'est pas une divergence sémantique.
- **Nettoyage** : copie, sauvegardes et scripts supprimés ; live toujours actif, `restarts=0`, `user_version=3`.
- Preuve : [`evidence/V7-11B_REAL_COPY_MIGRATION_EVIDENCE.md`](evidence/V7-11B_REAL_COPY_MIGRATION_EVIDENCE.md).

### 10.1 Correctif P1/P2 du backup pré-migration

La revue B6 a trouvé un **P1** dans `_create_coherent_pre_migration_backup` : le backup était créé par `sqlite3.connect(backup_path)` sans mode `0600` explicite ni `O_EXCL`/`O_NOFOLLOW`, et un backup préexistant était réutilisé après un simple `integrity_check`. Le live possède justement un ancien `.pre-v4.bak` en **0644**, taille **65 536 octets**, qui ne doit pas servir de sauvegarde pré-migration.

Le correctif B7 crée désormais le backup avec `O_CREAT|O_EXCL|O_RDWR|O_NOFOLLOW`, mode demandé `0600`, `fchmod`, vérifications inode/propriétaire/mode/taille, snapshot mémoire sérialisé, `fsync`, relecture et contrôles `integrity_check`/`user_version`. Tout fichier, symlink ou lien cassé préexistant provoque `MigrationIntegrityError` avec instruction de vérifier puis déplacer l'ancien backup. Le P1 et le P2 de provenance sont fermés (B8), sans nouveau P0/P1.

### 10.2 Rejeu final sur copie réelle

B9 a rejoué la migration avec le code corrigé sur une nouvelle copie privée de la base live :

- exports live identiques SHA `752919f2…`, v3, intégrité ok, FK vide, compteurs 3/2/2/1 ;
- migration v5, ledger `[1,2,3,4,5]`, tables V4/V5 et `device_ref`, compteurs conservés ;
- nouveau backup régulier, propriétaire euid, **0600**, v3, intègre, 249 856 octets ;
- fail-closed vérifié sur backup préexistant et symlink, sans modification de la copie ni de la cible ;
- backfill dry-run 3 propositions sans propriétaire, 0 credential ; expiration dry-run 1 candidate `PENDING_ANDROID`, 0 anomalie ;
- réouverture v5 idempotente ; live final identique au hash initial, toujours v3 ;
- ancien sidecar live `0644`/65 536 octets documenté, ni copié ni ouvert ;
- copie, backups et scripts supprimés.

La revue B10 confirme **P1 fermé sur données live**, GO local final, 0 P0/P1. Preuves : [`evidence/V7-11B_BACKUP_PERMISSIONS_FIX_EVIDENCE.md`](evidence/V7-11B_BACKUP_PERMISSIONS_FIX_EVIDENCE.md), [`evidence/V7-11B_BACKUP_FIX_REVIEW.md`](evidence/V7-11B_BACKUP_FIX_REVIEW.md), [`evidence/V7-11B_REAL_COPY_MIGRATION_FINAL_EVIDENCE.md`](evidence/V7-11B_REAL_COPY_MIGRATION_FINAL_EVIDENCE.md), [`evidence/V7-11B_FINAL_COPY_REVIEW.md`](evidence/V7-11B_FINAL_COPY_REVIEW.md).

### Conditions avant migration live

1. Fenêtre de backup autorisée : arrêt Core/connecteur, snapshots Core/Relay/connecteur (3 fichiers) et Pixel, restauration testée.
2. **Ancien sidecar** : vérifier puis déplacer `/data/atlas.sqlite3.pre-v4.bak` (0644, 65 536 octets), confirmer l'absence du chemin avant redémarrage ; le nouveau backup doit être régulier, propriétaire euid, mode `0600`, v3 et intègre. Sans ce déplacement, le nouveau Core échoue volontairement en fail-closed.
3. Autorisation nominative distincte pour appliquer la migration au volume live, avec sauvegarde pré-migration vérifiée et rollback.
4. Décision explicite sur les **3 propositions sans propriétaire** : import du bearer LEGACY live (opération sensible) ou attribution explicite ; aucun backfill automatique.
5. Décision sur la **1 candidate d'expiration PENDING_ANDROID** avant toute réconciliation ; ne pas l'appliquer silencieusement.
6. Après migration : vérifier le principal/binding `pixel-v7`, sans créer de credential ni changer le bearer sans autorisation.
7. Relay administrable et recréation testée hors live ; backup connecteur complet (3 fichiers) exécuté et restauration vérifiée.
8. `GO_V7_11_RELAY_SYNC_AVD` reste **NOT_RUN** ; `GO_V7_11_LIVE_CHANGE_READINESS` reste **NO-GO**.

P2/P3 acceptés pour le chemin local, à surveiller avant live : `fsync` du répertoire parent non réalisé, snapshot entièrement en mémoire, fenêtre entre snapshot et `BEGIN IMMEDIATE` (écrivains à arrêter), `os.close` non normalisé, `docker cp` non coordonné pour la copie, sidecar live non validé, identifiants d'orchestration dans le registre, portabilité `O_NOFOLLOW`/`serialize`. Aucun n'est un P0/P1 confirmé.

## 11. Addendum — fenêtre de backup live et archivage du sidecar (2026-11-08)

Paquet autorisé par l'utilisateur : « Fenêtre de backup live + sidecar ». Opérateur unique parent ; Relay jamais arrêté ; aucune migration live.

### 11.1 Fenêtre et disponibilité

- Ouverture : 2026-11-08T20:28:23Z. Core arrêté à 20:29:04Z, redémarré à 20:29:18Z : **~14 s d'indisponibilité**.
- Relay : jamais arrêté, `StartedAt=2026-11-07T10:05:46.958Z`, health 200 avant/après.
- Connecteur : inactif avant/pendant/après (aucun processus, aucun listener 4400/4401).

### 11.2 Artefacts vérifiés

| Composant | Résultat |
|---|---|
| Pixel V7-07 | `preflight`/`backup`/`verify` PASS ; archive `7e5908cd…`, 25 fichiers, manifeste `2ac75fa7…`, 15 `shared_prefs` ; app relancée, HOME conservé |
| Core DB | `752919f2…` identique avant/arrêt, v3, intégrité ok, FK vide, compteurs 3/2/2/1 |
| Sidecar | `b8210d25…`, `user_version=0` stale, archivé `0600` sous `atlas.sqlite3.pre-v4.bak.v7-11b-archive` ; chemin exact **absent** |
| Core après retour | health 200, DB v3 intègre, `restarts=0` (arrêt/démarrage manuel) |
| Connecteur | 30 entrées ; 3 fichiers d'état live=backup ; restauration 3/3 identique sur copie ; aucun démarrage/arrêt |
| Relay | métadonnées filtrées uniquement ; état volatile non sauvegardé ; aucune recréation |
| Backup global | racine `0700`, fichiers `0600`, 427 K, manifeste `73a173ce…`, 14 fichiers couverts (10 principaux + 4 auxiliaires) |

Restaurations testées **sur copies uniquement** : Core v3/intègre, connecteur 3/3, archive Pixel vérifiée ; copies de test supprimées. Aucune restauration live, aucune promotion.

### 11.3 Revue indépendante et P2

B11 : **GO_WITH_LIMITATIONS local / NO-GO live**, **0 P0/P1**. Deux P2 initialement ouverts ont été corrigés et revérifiés :

1. nom de fichier de configuration credential exposé dans la preuve → remplacé par une description générique ; sanitisation PASS ;
2. quatre fichiers auxiliaires hors manifeste → intégrés au manifeste global (14 fichiers) ; hashes/tailles/modes vérifiés.

Preuves : [`evidence/V7-11B_LIVE_BACKUP_EVIDENCE.md`](evidence/V7-11B_LIVE_BACKUP_EVIDENCE.md), [`evidence/V7-11B_LIVE_BACKUP_REVIEW.md`](evidence/V7-11B_LIVE_BACKUP_REVIEW.md).

### 11.4 Limites et condition critique avant live

- Backup **local au même hôte**, sans chiffrement au repos ni copie hors site ; 56 G libres (97 % utilisé).
- DB live toujours en mode `0644` ; le backup connecteur contient des tokens/secrets ; le sidecar archivé v0 n'est **pas** un rollback pré-migration valide.
- **L'image Core `528af664…` est stale** : construite à `a3d72db`, avant le correctif B7 (backup `0600` + fail-closed). Elle ne doit pas être déployée en live.
- Avant toute migration live : reconstruire l'image au SHA code final, rejouer le stack isolé sur une copie réelle migrée (health, migration, verrou, anti-rejeu, chemin legacy), puis obtenir une autorisation nominative distincte avec rollback et décision binding/3 propositions/1 expiration.
- `GO_V7_11_LIVE_CHANGE_READINESS` reste **NO-GO**.

## 12. Addendum — image finale et stack isolé sur copie réelle (2026-11-08)

Paquet autorisé : « Reconstruire l'image finale + stack isolé sur copie migrée ». Aucune mutation live.

### 12.1 Image finale

- Source : `git archive 7efbefcc5b747823cfd6d8da34b0c402953a0b0f` (B7 inclus).
- Build : `docker build --build-context contracts=… -t atlas-core-v7-11b-final-7efbefc …/backend`.
- Image : `sha256:1e4c8e6c599a3846e6870143b78cf3d39ae9d2da050db04e26d7cb3af8ecd35a`, **150 520 138 octets**.
- CMD : `uvicorn atlas_backend.main:app --host 127.0.0.1 --port 8100` ; ENTRYPOINT : `python /usr/local/bin/atlas-core-entrypoint.py`.
- Marqueurs présents : `O_NOFOLLOW`, `MigrationIntegrityError`, `_create_coherent_pre_migration_backup`, `FileReplayNonceCache`.
- Image/tag live `atlas-core-atlas-core` = `sha256:8604535e…`, inchangés ; tag final conservé localement uniquement.

### 12.2 Stack isolé sur copie réelle

- Export live courant : `9f5c8fd2…`, v3, integrity ok, FK 0, propositions V4 3 (APPLIED/PENDING_ANDROID/REJECTED), événements 2, consentements 2, événements de consentement 4, historique 1.
- Incident d'oracle initial : B12 avait comparé au hash antérieur `752919f2…` et compté des tables V1/V2 (`0/0/4/0`). Correction : hash courant accepté, compteurs V4 vérifiés ; aucune perte.
- Migration sur copie : v5, ledger `[1..5]`, `device_ref`, compteurs conservés.
- Backup B7 : `atlas.sqlite3.pre-v4.bak`, régulier, **0600**, v3, intègre, `193fcae4…`.
- Verrou `atlas.sqlite3.lock` 0600 ; cache `/data/replay` 0700.
- Legacy : token synthétique **200** sur `/v1/directives/current`, faux token **401**.
- Relay/proxy/connecteur : health 200, `relay_connected=true`, WS 101, enregistrement synthétique.
- Fail-closed : backup préexistant → exit 1, `MigrationIntegrityError`, message « vérifier puis déplacer », DB/backup inchangés, aucune santé.
- Live, image/tag, conteneurs, ports et `StartedAt` inchangés ; ressources isolées nettoyées ; image finale conservée.

### 12.3 Décision opératoire critique

Le **premier démarrage** du Core migré exécute le sweeper d'expiration : la proposition `PENDING_ANDROID` (expirée depuis le 2026-11-06) passe à `EXPIRED` et un événement d'expiration est écrit. Le backup v3 pré-migration conserve l'état antérieur. Cette décision doit être prise **avant** la migration live ; elle n'est pas réversible par le seul redémarrage.

### 12.4 Revue B13 et P2

B13 : **GO_WITH_LIMITATIONS local / NO-GO live**, 0 P0/P1, 4 P2 :

1. inspection Docker indépendante `UNKNOWN` (sandbox) ; le parent a vérifié directement image/tag/conteneurs ;
2. égalité logique complète non démontrée par les seuls compteurs (hash fichier différent après redémarrage) ;
3. expiration automatique à prendre avant le premier démarrage ;
4. matrice de gates à réconcilier (fait dans cet addendum).

L'image est locale, non poussée en registre. La qualification utilise l'adaptateur `mock`, sans Android/appairage réel.

### 12.5 Conditions avant migration live

1. **Autorisation nominative distincte** pour arrêter Core, remplacer l'image et migrer le volume live.
2. **Décision explicite sur la proposition PENDING_ANDROID** : accepter son expiration au premier démarrage, ou suspendre/arrêter pour une autre décision (aucune écriture live sans autorisation).
3. **Backup frais immédiatement avant migration** : la base live actuelle a évolué (`9f5c8fd2…`) depuis le backup de fenêtre (`752919f2…`) ; produire une copie v3 à l'arrêt, vérifier intégrité/FK/compteurs, et conserver le nouveau backup B7 `0600`.
4. **Déploiement** : recréer le conteneur Core avec l'image `1e4c8e6c…` en préservant `network_mode: host`, `read_only`, tmpfs, secrets, volume, `cap_drop`, `no-new-privileges`, restart policy et healthcheck ; ne jamais retagger `atlas-core-atlas-core`.
5. **Rollback** : image live `8604535e…` + backup v3 frais + procédure de restauration testée ; toute écriture post-backup perdue en cas de rollback.
6. Après migration : vérifier v5, compteurs, health, sync legacy de l'app, absence d'erreurs, puis seulement envisager principal/binding, Relay admin et la suite V4.
7. `GO_V7_11_LIVE_CHANGE_READINESS` reste **NO-GO** jusqu'à cette autorisation.

## 13. Addendum — migration live v3→v5 et image finale (2026-11-08/09)

Paquet autorisé : « Migrer le live v3→v5 maintenant », avec acceptation de l'expiration de la proposition `PENDING_ANDROID`.

### 13.1 Exécution

- Core arrêté à `2026-11-08T22:45:25Z` (exit 0), backup frais v3 `9f5c8fd2…` (integrity ok, FK 0, compteurs 3/2/2/4/1, états APPLIED/PENDING_ANDROID/REJECTED), fichiers `0600`.
- Image finale `1e4c8e6c…` déployée via `compose.yaml` + `compose.openclaw.yaml` + override privé persistant (`0600`) ; config `network_mode: host`, `read_only`, tmpfs, volume, trois secrets, `cap_drop: ALL`, `no-new-privileges`, restart/healthcheck préservés.
- Core redémarré à `22:45:41Z` — **~16 s d'indisponibilité** — health **200**.
- Ancienne image `8604535e…` (tag `atlas-core-atlas-core`) conservée pour rollback.
- Relay jamais arrêté (`StartedAt` inchangé, health 200).

### 13.2 État après migration

- Base **v5**, ledger `[1,2,3,4,5]`, intégrité ok, FK 0.
- Propositions 3 (APPLIED 1 / **EXPIRED 1** / REJECTED 1), événements 2, consentements 2, événements de consentement 4, historique 1, événement d'expiration 1.
- Backup B7 `atlas.sqlite3.pre-v4.bak` : régulier, **0600**, v3, intégrité ok, `193fcae4…`.
- Verrou `atlas.sqlite3.lock` `0600` ; cache `/data/replay` `0700`.
- Legacy : token live **200** / faux token **401** ; adapter OpenClaw et capabilities préservés ; 0 erreur Core.
- La proposition `PENDING_ANDROID` est passée à `EXPIRED` au premier démarrage, comme autorisé ; le backup v3 conserve l'état antérieur.

### 13.3 Contrôle logique et rollback

- Comparatif v3↔v5 : **17/19 tables MATCH** ; seules différences attendues : une ligne `agent_proposals_v4` (colonne `state` uniquement) et le ledger 3→5 ; 6 nouvelles tables V4/V5 ; aucune table supprimée.
- Runbook de rollback exact : image `8604535e…`, volume `atlas-core-data`, backup `9f5c8fd2…`, restauration en `0600/10001`, renommage du sidecar B7 et du lock, contrôles v3, redémarrage old image. **Non exécuté.**
- Preuves : [`evidence/V7-11B_LIVE_MIGRATION_EVIDENCE.md`](evidence/V7-11B_LIVE_MIGRATION_EVIDENCE.md), [`evidence/V7-11B_LIVE_MIGRATION_LOGICAL_CHECK.md`](evidence/V7-11B_LIVE_MIGRATION_LOGICAL_CHECK.md), [`evidence/V7-11B_LIVE_ROLLBACK_RUNBOOK.md`](evidence/V7-11B_LIVE_ROLLBACK_RUNBOOK.md).

### 13.4 Revue B14 et limites

B14 : **GO_WITH_LIMITATIONS local / NO-GO cutover**, **0 P0/P1**. P2 rollback et conservation logique **fermés** ; l'inspection Docker indépendante reste `UNKNOWN` ; le backup local sans hors-site/chiffrement et la DB live `0644` restent des risques acceptés.

Limites actuelles :

1. **Pixel déconnecté d'ADB** : sync Android post-migration, HOME, Keystore et consentements `NOT_RUN`.
2. **Binding/principal non configurés** : auth toujours `legacy`, aucune credential/binding V4.
3. **Relay non administré** : recréation non testée, backup connecteur non déployé.
4. **Backup local uniquement**, DB live `0644`, aucun APPLY/réconciliation.

### 13.5 Gates

| Gate | Statut |
|---|---|
| `GO_V7_11_LIVE_MIGRATION` | **GO local** |
| `GO_V7_11_SCHEMA_V5_LIVE` | **GO local** |
| `GO_V7_11_ANDROID_POST_MIGRATION_SYNC` | **PASS avec limitation** |
| `GO_V7_11_IDENTITY_BINDING` | **NOT_RUN** |
| `GO_V7_11_LIVE_CHANGE_READINESS` | **NO-GO** (cutover complet) |

Prochaine étape recommandée : reconnecter le Pixel et vérifier la synchronisation post-migration en lecture seule, avant toute configuration de binding ou de Relay.

## 14. Addendum — vérification Pixel après migration (2026-11-09)

### 14.1 Pixel et application

- Pixel reconnecté, Android 17 / API 37, APK exact `bb703ec7…`, version 1/0.1.0, certificat `da301466…eeee`.
- HOME `dev.atlas.coach`, `LauncherActivity` au premier plan, 15/15 noms `shared_prefs`, permissions 23/23 identiques au backup d'installation, aucun FATAL/ANR/Keystore.
- L'utilisateur a désactivé le mode avion ; Wi-Fi validé, aucun VPN actif ; Tailscale mobile reste hors ligne (dernière vue 34 j).

### 14.2 Chemin réseau et preuve de connexion Coach

- Core v5 sur `127.0.0.1:8100` ; Relay sur `127.0.0.1:4402`.
- Proxy 4400 et connecteur 4401 démarrés à la demande : proxy health 200, connecteur `relay_connected=true`, `errors_total=0`, WS 101 ; Funnel `8443→4400` ciblé.
- `MainActivity` ouverte ; l'utilisateur a envoyé « test » au Coach ; **`POST /v2/agent/chat` 200** à `12:25:52Z`.
- Connecteur : `connections_opened_total=1`, `sessions_opened_total=1`, `active_sessions=1`, `requests_total=1`, `errors_total=0`, latence ~13 s ; proxy WS **101** à `12:25:19Z`.
- App : `atlas_local_state.xml` et `atlas_relay_configuration.xml` réécrits à `12:25Z` ; réponse Coach visible.
- `CoreV4SyncWorker` forcé (job 253) : exécuté puis replanifié, sans requête HTTP séparée ; la connectivité est prouvée par le chat.
- Aucune permission, aucun consentement, aucun bearer, aucun réglage Wi-Fi/Tailscale/Funnel n'a été modifié. Le proxy et le connecteur ne sont pas supervisés et s'arrêteront à la fin de session/au redémarrage.

### 14.3 Revue B15 et limites

B15 final : **GO local avec limitation / NO-GO cutover**, 0 P0/P1. Chat app→Core v5 **PASS avec limitation** ; `CoreV4SyncWorker` `NOT_OBSERVED` ; P2 sanitisation **clos** ; P2 runtime Docker/ADB `UNKNOWN` (sandbox). Binding `NOT_RUN`, cutover `NO-GO`.

Preuves : [`evidence/V7-11B_ANDROID_POST_MIGRATION_EVIDENCE.md`](evidence/V7-11B_ANDROID_POST_MIGRATION_EVIDENCE.md), [`evidence/V7-11B_ANDROID_POST_MIGRATION_REVIEW.md`](evidence/V7-11B_ANDROID_POST_MIGRATION_REVIEW.md).

### 14.4 Conditions restantes

1. **Binding/principal V4** : auth legacy, 0 principal/credential ; décision et configuration dédiées.
2. **Relay/connecteur** : proxy et connecteur actifs mais non supervisés ; recréation Relay non testée, sauvegarde connecteur 3 fichiers non déployée.
3. **Backup** : local au même hôte, sans chiffrement/hors-site ; DB live `0644`.
4. **Données** : 3 propositions (APPLIED/EXPIRED/REJECTED) ; aucun APPLY/backfill/réconciliation supplémentaire.
5. **Worker d'état** : cycle dédié non observé ; le chat suffit à la connectivité, pas à qualifier chaque cycle de sync.

`GO_V7_11_LIVE_CHANGE_READINESS` reste **NO-GO** pour le cutover complet.
