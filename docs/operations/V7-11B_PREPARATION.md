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
| `GO_V7_11_CODE_PROVENANCE` | **GO local** | APK exact `65759dd` `bb703ec7…` ; image Core finale `a3d72db` `528af664…` (cache durable + verrou). |
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
| `GO_V7_11_ISOLATED_STACK` | **GO local** | B1 PASS : Core/Relay/proxy health 200, DB neuve `user_version=5`, `/metrics` `relay_connected=true`, WS 101, loopback only, live inchangé. Déviation bridge non-`internal` documentée. |
| `GO_V7_11_IDENTITY_BINDING` | **BLOCKED / UNKNOWN** | B2 : base live `user_version=3`, tables V4 absentes, `device_ref` absent ; le libellé `pixel-v7` n'existe que côté connecteur. Vérification impossible avant migration live. |
| `GO_V7_11_CONNECTOR_BACKUP_COVERAGE` | **GO local avec gap confirmé** | B3 : `operations.json` omis du backup/restore CLI ; procédure complète 3/3 testée sur état synthétique. Backup live non exécuté. |
| `GO_V7_11_BACKUP_ROLLBACK_PLAN` | **GO local avec limitations** | Backup connecteur incomplet confirmé ; procédure complète uniquement synthétique ; backup live/Relay/Pixel non exécuté. |
| `GO_V7_11_INDEPENDENT_SECURITY_REVIEW` | **GO avec limitations P2** | A4-F3 sur `a3d72db` : 0 P0/P1 ; 3 nouveaux P2 + réserves héritées ; conditions de déploiement listées ci-dessus. |
| `GO_V7_11_LIVE_CHANGE_READINESS` | **NO-GO** | Relay non administrable ; base live encore `user_version=3` sans tables V4 ; identité/binding non vérifiables ; backup live absent ; procédure complète connecteur non déployée. |
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
