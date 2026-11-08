# V7-11-A1 — preuve Core, registre V4 et migration SQLite

- **TASK_ID :** `V7-11-A1`
- **Date :** 2026-11-08
- **BASE_SHA :** `8dac699cdb37e2f858a76e4cf7f9fc3bcd613e9c`
- **Branche :** `audit/v7-11-a1-core-migration`
- **Profil vérifié par les métadonnées Paseo du parent :** `codex:gpt-6-luna`, `xhigh`
- **État Git initial :** propre
- **Sortie :** commit local indiqué dans le compte-rendu final; aucun push.

## Périmètre

Audit en lecture seule de `backend/atlas_backend/{store.py,v4_auth.py,v4_assertion.py,v4_router.py,v4_projection_store.py,main.py}`, `backend/scripts/atlas_v4_principals.py` et `backend/scripts/atlas_v4_expiration_reconcile.py`, à la base SHA ci-dessus. Correctif borné à `scripts/v7-06c/tests/test_harness.py`. La seule autre écriture est ce rapport.

Le dépôt ne contient pas `.codegraph/`; l’inventaire s’est donc appuyé sur les fichiers source et tests. Aucun ADB, appareil, Pixel, service live, volume Docker, secret réel, fichier `.env` ou base personnelle n’a été consulté.

## Résultats de l’audit V7-10

- Les migrations 4 et 5 sont additives : la v4 crée le registre principal/credentials/grants/bindings, ajoute `device_ref` et ses index/triggers; la v5 ajoute l’audit d’expiration. Le ledger vérifie les checksums, les versions contiguës, applique sous `BEGIN IMMEDIATE` et prend un backup pré-migration. La table historique V2 est restée intacte dans la répétition synthétique.
- Les événements de consentement, ACK, principal et expiration sont append-only. Les triggers interdisent un propriétaire de proposition nul, le changement ultérieur de propriétaire, la réactivation d’un principal/credential/binding révoqué et le retour de `device_ref` d’un principal à une autre valeur.
- `legacy-import` exige le choix explicite `--grant-defaults`; il calcule et conserve uniquement SHA-256 du bearer ASCII. La résolution vérifie digest, audience et états actifs. Le registre admet `DIRECT`, `RELAY`, `TOOL` et `LEGACY`; le mode `principal` refuse `LEGACY`.
- Les capacités sont allowlistées. Une route exige à la fois son flag global actif et son grant principal. `tool_catalog.read` est une métadonnée TOOL spéciale non accordable. Le principal de test `pixel-v7` n’a reçu que `consent.state.write`, `plan_revision.propose` et `proposal.ack`.
- Le chemin Relay lie l’assertion HMAC (méthode, chemin, timestamp, nonce, ID Relay et hash du corps) au credential d’audience `RELAY`, puis vérifie le binding actif vers le même principal et `device_ref`. L’identité ne vient pas du payload client.
- Les propositions sont créées avec le `device_ref` du contexte authentifié. Le pull, la recherche d’idempotence et l’ACK filtrent le propriétaire; l’accès inter-principal est masqué en `404`. Les lignes sans propriétaire ne sont exposées en mode `dual` qu’au principal LEGACY explicitement configuré.
- L’ACK contrôle propriétaire, consentement courant, hash de replay et séquence sous transaction immédiate; les mises à jour d’état sont conditionnelles. La transition `UNCERTAIN → RECONCILED` est explicite. L’expiration traite uniquement `PENDING_ANDROID`, `RECEIVED` et `AWAITING_USER`, par lot déterministe, avec mise à jour et audit atomiques. `UNCERTAIN` reste inchangé.
- Le backfill est dry-run par défaut; l’application exige une cible LEGACY non ambiguë/explicite et une sauvegarde exclusive vérifiée. La réconciliation d’expiration vérifie v5/checksum, intégrité et FK, sauvegarde avant application et traite des lots transactionnels.
- La concurrence est sérialisée par `BEGIN IMMEDIATE`, contraintes uniques et mises à jour conditionnelles. Les suites couvrent ACK concurrents, expiration, incertitude, révocation et isolation; elles passent dans l’oracle racine.

## Oracles et commandes

| Oracle | Statut | Résultat |
|---|---|---|
| Reproduction CI avant patch | **FAIL attendu avant correction** | Deux méthodes de `CoreOracleTests` échouaient (9 sous-cas) : `agent_proposals_v4` avait 11 colonnes et l’INSERT de fixture en fournissait 10. Le trigger propriétaire n’était pas affaibli; l’échec arrivait au nombre de valeurs. |
| Harness après patch | **PASS** | `27 passed, 43 subtests passed`. |
| Suite racine complète | **PASS** | `496 passed, 1 skipped, 4 warnings, 48 subtests passed` en 50,12 s. |
| Migration synthétique v3→v5 | **PASS** | `user_version=5`, 5 migrations, `integrity_check=ok`, `foreign_key_check=[]`; la ligne V2 historique subsiste et la ligne V4 préexistante reste sans propriétaire avant backfill. |
| Import LEGACY et provisioning | **PASS** | Bearer synthétique généré à l’exécution (58 caractères), jamais imprimé ni enregistré dans ce rapport. `pixel-v7` a des credentials distincts `DIRECT` et `RELAY`; principal et credential TOOL séparés; grants minimaux et binding Relay actif vérifiés. Les trois audiences ont été validées par `verify-token`; les hashes DIRECT/RELAY diffèrent. |
| Backfill dry-run/apply + backup | **PASS** | Dry-run : 1 proposition sans propriétaire. Apply : 1 mise à jour, backup vérifié, SHA-256 `29d21f704447bc1081edb2915bb763c16a236621930dac6d86d707b191cb1365`. Après application : 0 ligne sans propriétaire; la ligne historique appartient au principal LEGACY importé. |
| Isolation propriétaire | **PASS** | Le propriétaire LEGACY retrouve sa proposition; le principal `pixel-v7` ne peut pas la retrouver. Le trigger rejette aussi un INSERT sans `device_ref` et une modification de propriétaire. |
| Expiration / UNCERTAIN | **PASS** | Le dry-run a trouvé 2 propositions actives échues et exclu la proposition `UNCERTAIN`. Apply par lots de 1 : 2 expirations, 2 audits, 1 `UNCERTAIN` conservé, aucune anomalie d’audit. Le dry-run suivant trouve 0 candidat. |
| Redémarrage / idempotence | **PASS** | Une nouvelle initialisation `SQLiteStore` conserve v5, les 5 migrations, 3 propositions, 2 audits, les grants et le hash logique `9ef8546916ef0cd9028b71a7bf969b96d7c80237d567e74ece7847a4d8bf26bd`. Le dry-run du backfill relancé retourne 0 propriétaire manquant. |
| Migration / services live, Android ou Pixel physique | **NOT_RUN** | Exclus par les interdictions du ticket; la preuve porte uniquement sur `/tmp` synthétique et les tests locaux. |
| Vérification whitespace | **PASS** | `git diff --check`. |

### Harness rouge puis vert

Commande exécutée avant le patch :

```sh
python -m pytest -q scripts/v7-06c/tests/test_harness.py
```

Sortie résumée avant : `9 failed, 27 passed, 34 subtests passed`; les erreurs proviennent des deux méthodes `test_apply_and_reject_core_oracles_pass` et `test_core_oracle_rejects_required_falsifications`.

Même commande après le patch : `27 passed, 43 subtests passed`.

Correctif : passer 11 valeurs à l’INSERT de fixture, avec `device_ref="synthetic-device"`. Le trigger de production reste intact.

### Oracle racine

Commande exacte depuis la racine du worktree :

```sh
python -m pytest -q
```

Résultat : **496 passed, 1 skipped, 4 warnings, 48 subtests passed**. Avertissements : dépréciation Starlette/httpx, marqueur `real_gateway` non enregistré et deux Operation IDs FastAPI dupliqués; aucun échec.

### Répétition SQLite synthétique

La base et les fichiers de backup/credentials ont été créés sous `/tmp/v7-11-a1-core-migration.NdhvgW`. La fixture v3, construite avec `sqlite3` et les migrations v1–v3/checksums du code, contenait une ligne de proposition V4 sans `device_ref` et une ligne dans la table historique. Contrôle initial : `user_version=3`, `integrity_check=ok`, `foreign_key_check=[]`, une ligne historique.

Commandes de CLI exécutées, avec les variables de chemin et d’identifiant temporaire dépliées ici :

```sh
TMP=/tmp/v7-11-a1-core-migration.NdhvgW
LEGACY_PRINCIPAL_ID=legacy_W0p8BqHAZB6nD31VGyt0wXRo
python -c "import json,sqlite3; c=sqlite3.connect('$TMP/core.sqlite3.pre-v4.bak'); print(json.dumps({'user_version':c.execute('PRAGMA user_version').fetchone()[0],'integrity_check':c.execute('PRAGMA integrity_check').fetchone()[0],'foreign_key_check':[list(x) for x in c.execute('PRAGMA foreign_key_check')],'legacy_rows':c.execute('SELECT COUNT(*) FROM agent_proposals').fetchone()[0],'v4_rows_without_owner_column':c.execute('SELECT COUNT(*) FROM agent_proposals_v4').fetchone()[0]},sort_keys=True))"
python backend/scripts/atlas_v4_principals.py --db "$TMP/core.sqlite3" preview-migration
PYTHONPATH=backend python -c "from atlas_backend.store import SQLiteStore; SQLiteStore('$TMP/core.sqlite3')"
python backend/scripts/atlas_v4_principals.py --db "$TMP/core.sqlite3" preview-migration
cp "$TMP/core.sqlite3.pre-v4.bak" "$TMP/core-migration-cli.sqlite3"
python backend/scripts/atlas_v4_principals.py --db "$TMP/core-migration-cli.sqlite3" preview-migration
PYTHONPATH=backend python -c "from atlas_backend.store import SQLiteStore; SQLiteStore('$TMP/core-migration-cli.sqlite3')"
python -c 'import secrets,sys; sys.stdout.write("synthetic-"+secrets.token_urlsafe(36)+"\n")' | python backend/scripts/atlas_v4_principals.py --db "$TMP/core.sqlite3" legacy-import --grant-defaults
python backend/scripts/atlas_v4_principals.py --db "$TMP/core.sqlite3" principal add --principal-id pixel-v7 --kind ANDROID --device-ref synthetic-device
python backend/scripts/atlas_v4_principals.py --db "$TMP/core.sqlite3" credential add --principal-id pixel-v7 --audience DIRECT --generate --output "$TMP/pixel-direct.credential"
python backend/scripts/atlas_v4_principals.py --db "$TMP/core.sqlite3" credential add --principal-id pixel-v7 --audience RELAY --generate --output "$TMP/pixel-relay.credential"
python backend/scripts/atlas_v4_principals.py --db "$TMP/core.sqlite3" principal add --principal-id atlas-tool-test --kind TOOL --device-ref synthetic-tool-device
python backend/scripts/atlas_v4_principals.py --db "$TMP/core.sqlite3" credential add --principal-id atlas-tool-test --audience TOOL --generate --output "$TMP/tool.credential"
python backend/scripts/atlas_v4_principals.py --db "$TMP/core.sqlite3" capability set --principal-id pixel-v7 --capability consent.state.write --grant
python backend/scripts/atlas_v4_principals.py --db "$TMP/core.sqlite3" capability set --principal-id pixel-v7 --capability plan_revision.propose --grant
python backend/scripts/atlas_v4_principals.py --db "$TMP/core.sqlite3" capability set --principal-id pixel-v7 --capability proposal.ack --grant
python backend/scripts/atlas_v4_principals.py --db "$TMP/core.sqlite3" binding add --relay-device-id relay-pixel-v7-test --principal-id pixel-v7
cat "$TMP/pixel-direct.credential" | python backend/scripts/atlas_v4_principals.py --db "$TMP/core.sqlite3" verify-token
cat "$TMP/pixel-relay.credential" | python backend/scripts/atlas_v4_principals.py --db "$TMP/core.sqlite3" verify-token
cat "$TMP/tool.credential" | python backend/scripts/atlas_v4_principals.py --db "$TMP/core.sqlite3" verify-token
python backend/scripts/atlas_v4_principals.py --db "$TMP/core.sqlite3" backfill-proposals --legacy-principal-id "$LEGACY_PRINCIPAL_ID"
python backend/scripts/atlas_v4_principals.py --db "$TMP/core.sqlite3" backfill-proposals --legacy-principal-id "$LEGACY_PRINCIPAL_ID" --apply --backup "$TMP/pre-backfill.sqlite3"
python backend/scripts/atlas_v4_principals.py --db "$TMP/core.sqlite3" backfill-proposals --legacy-principal-id "$LEGACY_PRINCIPAL_ID"
python backend/scripts/atlas_v4_expiration_reconcile.py --db "$TMP/core.sqlite3" --now 2026-11-08T00:00:00Z
python backend/scripts/atlas_v4_expiration_reconcile.py --db "$TMP/core.sqlite3" --now 2026-11-08T00:00:00Z --apply --backup "$TMP/pre-expiration-reconcile.sqlite3" --batch-size 1
python backend/scripts/atlas_v4_expiration_reconcile.py --db "$TMP/core.sqlite3" --now 2026-11-08T00:00:00Z
```

`LEGACY_PRINCIPAL_ID` est l’identifiant opaque temporaire retourné par l’import. Aucun bearer ni contenu de fichier credential ne figure dans les sorties ou ce commit. Les contrôles finaux ont vérifié intégrité, FK, `user_version`, compte des lignes, hashes distincts des credentials sans afficher ces hashes, hash de backup et triggers append-only.

## Fichiers modifiés

- `scripts/v7-06c/tests/test_harness.py` — ajoute le 11e champ de fixture et le propriétaire synthétique attendu.
- `docs/operations/evidence/V7-11_A1_CORE_MIGRATION_EVIDENCE.md` — cette preuve.

## Risques et limites

- Le cache anti-rejeu Relay est en mémoire par processus; un déploiement multi-worker doit assurer l’affinité/routage ou un stockage de nonce partagé.
- La réconciliation `--apply` est transactionnelle par batch, pas sur l’ensemble du run. Une interruption après des lots exige la procédure de restauration depuis la sauvegarde vérifiée.
- Le mode d’authentification par défaut reste `legacy`; le cutover vers `principal` et la configuration des flags globaux restent des décisions d’exploitation distinctes.
- Cette preuve n’est pas une autorisation de migration ni une validation live. Aucun service, volume Docker, ADB ou appareil n’a été utilisé.
- Pendant la préparation de la fixture synthétique, un premier ledger v3 généré manuellement avait des checksums mal encodés; l’initialiseur l’a correctement refusé (`MigrationChecksumConflict`). Les checksums ont été recalculés avec la formule du code, puis la migration v3→v5 a réussi. Ce faux départ concerne uniquement la fixture jetable.

## Prochaine action

Le parent doit relire le diff et intégrer localement le commit après cette preuve. Aucun changement hors du périmètre autorisé n’a été nécessaire.

## Correction V7-11-A1-F2 — tombstone LEGACY en mode legacy

- **Base de travail :** `b9392a581f2353509dc711a6438053a71e02e826`
- **Branche :** `audit/v7-11-a1-core-migration`
- **Profil de session transmis par le parent Paseo :** `codex:gpt-6-luna`, effort `xhigh`.
- **Périmètre d’écriture :** `backend/atlas_backend/v4_auth.py`, `backend/atlas_backend/v4_router.py`, `backend/tests/test_v4_principal_auth.py` et ce rapport. Aucun schéma SQLite n’a été modifié.

Le mode legacy consultait uniquement `ATLAS_TOKEN`. Après `legacy-import` puis révocation, l’ancien bearer restait donc accepté. `V4PrincipalRegistry.credential_state()` calcule le hash du bearer et effectue un `SELECT` en lecture seule filtré sur `audience='LEGACY'`; le bearer n’est ni stocké ni journalisé. La branche legacy renvoie le même échec générique `AUTH_REQUIRED` (401) si l’état vaut `REVOKED`. Un bearer inconnu ou une table de registre absente garde le comportement de compatibilité existant. Les modes dual et principal ne changent pas.

### Résultats et commandes

| Oracle | Statut | Résultat |
|---|---|---|
| Reproduction indépendante avant correctif | **FAIL attendu avant correction** | La requête Android legacy après révocation a obtenu `200`; l’assertion attendait `401`. Cela confirme le contournement sur base synthétique v5. |
| Bearer LEGACY révoqué après correctif | **PASS** | Réponse `401`, code générique `AUTH_REQUIRED`. |
| Bearer historique non importé et credential LEGACY ACTIVE | **PASS** | Les deux requêtes restent acceptées en mode legacy. |
| Table `credentials_v4` absente | **PASS** | La requête reste acceptée, sans exception/500. La table a été retirée après initialisation de l’app pour simuler l’absence du registre pré-v4. |
| Credential DIRECT révoquée, audiences séparées | **PASS** | Elle n’est pas traitée comme tombstone LEGACY; le chemin legacy historique reste accepté. |
| Modes dual et principal | **PASS** | Tests d’authentification et suite complète sans régression ni fallback ajouté. |
| Suite backend | **PASS** | `454 passed, 1 skipped, 3 warnings` en 32,76 s. |
| Suite racine | **PASS** | `499 passed, 1 skipped, 4 warnings, 48 subtests passed` en 58,18 s. |
| `git diff --check` | **PASS** | Aucune erreur whitespace. |
| Services live, ADB, appareils | **NOT_RUN** | Exclu par le ticket; uniquement des bases et bearer synthétiques dans les tests locaux. |

La commande rouge, exécutée avant tout changement de production depuis `backend/`, était :

```sh
ATLAS_AGENT_ADAPTER=mock python3 -m pytest -q tests/test_v4_principal_auth.py::test_legacy_mode_rejects_explicitly_revoked_imported_bearer
```

Résultat rouge : `1 failed`; `response.status_code` valait `200` au lieu de `401`.

Après correction, les tests ciblés du fichier ont été exécutés depuis `backend/` :

```sh
ATLAS_AGENT_ADAPTER=mock python3 -m pytest -q tests/test_v4_principal_auth.py
```

Résultat : `21 passed, 1 warning`. Suites finales exécutées :

```sh
# Depuis backend/
ATLAS_AGENT_ADAPTER=mock python3 -m pytest -q
# Depuis la racine du worktree
python3 -m pytest -q
git diff --check
```

Le test de table absente retire `credentials_v4` après le démarrage de l’application. L’initialiseur normal met automatiquement à niveau les bases avant le service; ce test vérifie directement le chemin de repli de lecture sans émuler tout un processus de démarrage pré-v4. Une erreur SQLite autre que l’absence de table continue à remonter au gestionnaire existant.

**Prochaine action :** relecture parent et intégration du commit local; aucun push n’a été effectué.

## V7-11B-P2 — verrou single-writer Core

- **Base de travail :** `2c3e1788601d1640f1207cfbc2e1418f10cbd83f`.
- **Branche :** `audit/v7-11-a1-core-migration`.
- **Scope d’écriture :** `backend/atlas_backend/main.py`, `backend/tests/test_core_db_lock.py` et ce rapport.
- **Périmètre runtime :** aucun service live, ADB ou appareil utilisé; toutes les bases sont sous les répertoires temporaires des tests.

Le lifespan acquiert maintenant un `flock(LOCK_EX | LOCK_NB)` sur le fichier adjacent à la base résolue (`<base>.lock`). Le fichier est ouvert avec `O_CREAT | O_RDWR | O_NOFOLLOW`, son parent est vérifié, les liens symboliques et fichiers non réguliers sont refusés, et son mode est forcé à `0600`. Le contenu est vidé après obtention du verrou; aucun PID, bearer ou secret n’y est écrit. Le descripteur reste détenu jusqu’à la fin du nettoyage des tâches du lifespan, puis le verrou est explicitement libéré et le descripteur fermé. Les chemins de base qui sont des symlinks convergent vers le même verrou. `:memory:` n’obtient pas de fichier de verrou.

Les CLI et outils offline ne prennent pas ce verrou, conformément au périmètre; ils restent utilisables lorsque Core est arrêté. Comme `flock` est advisory, ces outils doivent être exécutés Core arrêté pour éviter une écriture concurrente.

### Oracles et commandes

| Oracle | Statut | Résultat |
|---|---|---|
| Rouge avant patch sur la base `2c3e178` | **FAIL attendu avant correction** | Le second lifespan sur la même base est entré sans erreur; pytest a échoué avec `DID NOT RAISE RuntimeError`. |
| Verrou exclusif, libération et mode du fichier | **PASS** | Deuxième application refusée pendant que la première détient le verrou; fichier régulier, non symlink, mode `0600`, vide; après arrêt, seconde application et ouverture offline réussissent. |
| Bases distinctes | **PASS** | Les deux applications démarrent en même temps. |
| Alias symlink de la base | **PASS** | Le second chemin converge vers le verrou de la base cible et le démarrage concurrent est refusé. |
| Symlink au chemin `.lock` | **PASS** | Démarrage refusé; la cible du symlink n’est pas touchée. |
| `:memory:` | **PASS** | Aucun fichier `:memory:.lock` n’est créé. |
| Tests ciblés du verrou | **PASS** | `5 passed, 1 warning`. |
| Tests existants du lifespan d’expiration | **PASS** | Avec les tests du verrou : `6 passed, 1 warning`. |
| Suite backend | **PASS** | `459 passed, 1 skipped, 3 warnings` en 28,01 s. |
| Suite racine | **PASS** | `504 passed, 1 skipped, 4 warnings, 48 subtests passed` en 35,83 s. |
| `git diff --check` | **PASS** | Aucune erreur whitespace. |

Commande rouge exécutée depuis `backend/` avec le contenu de `main.py` restauré temporairement à la base `2c3e178` :

```sh
ATLAS_AGENT_ADAPTER=mock python3 -m pytest -q tests/test_core_db_lock.py::test_same_database_lifespan_is_exclusive_and_released
```

Sortie : `1 failed`; le second lifespan démarrait au lieu de lever l’erreur attendue.

Commandes vertes exécutées :

```sh
# Depuis backend/
ATLAS_AGENT_ADAPTER=mock python3 -m pytest -q tests/test_core_db_lock.py
ATLAS_AGENT_ADAPTER=mock python3 -m pytest -q tests/test_core_db_lock.py tests/test_v4_proposal_expiration.py::test_lifespan_sweeper_logs_tick_error_and_stops_cleanly tests/test_v4_proposal_expiration.py::test_lifespan_sweeper_can_be_disabled
ATLAS_AGENT_ADAPTER=mock python3 -m pytest -q
# Depuis la racine du worktree
python3 -m pytest -q
git diff --check
```

Les tests d’exclusivité ouvrent deux applications/lifespans dans le même processus sur des descripteurs séparés; aucune campagne multi-processus distincte n’a été faite. Le mécanisme s’appuie sur `flock` advisory fourni par le système de fichiers. Les CLI offline ne consultent pas le verrou et doivent rester arrêtées pendant l’exécution de Core.

**Prochaine action :** revue parent du commit local; aucun push effectué.

## V7-11B-P7 — fiabilité du test et verrouillage de l’initialisation

- **Base de travail :** `5fc3322fe59f2bbd6246812e21c6dd56163a708e`.
- **Branche :** `audit/v7-11-a1-core-migration`.
- **Scope d’écriture :** `backend/atlas_backend/main.py`, `backend/tests/test_core_db_lock.py` et ce rapport.
- **Environnement :** bases temporaires pytest uniquement; aucun service live, ADB ou appareil.

### Diagnostic du blocage

Le test `test_different_databases_can_start_together` a été exécuté dix fois, avec une limite de huit secondes par processus : les dix exécutions ont réussi en 0,94–1,02 s. Le blocage signalé précédemment n’a donc pas été reproduit. Le chemin asynchrone qui peut attendre lors de l’arrêt est la tâche de nettoyage du cache, exécutée via `asyncio.to_thread`; `asyncio.run` attend aussi l’arrêt de son executor par défaut. C’est une piste plausible pour une attente lors du nettoyage de tâches/executor, mais les éléments disponibles ne permettent pas d’établir cette cause comme certaine.

Le test est maintenant borné par `asyncio.wait_for(..., timeout=5)`, exécute le petit nettoyage SQLite inline pour éviter que la fermeture de l’executor masque un échec du test, et vérifie après les lifespans qu’aucune tâche asyncio ne reste active. Les contextes `async with` assurent l’annulation et le nettoyage du lifespan en cas d’exception ou de timeout.

### Course d’initialisation SQLite

La course signalée était réelle : `SQLiteStore.__init__` appelle `initialize()` et peut créer ou migrer le schéma avant l’acquisition du verrou dans le lifespan. `create_app` acquiert maintenant le même verrou avant de construire `SQLiteStore`, puis le libère dans un `finally`, y compris si l’initialisation lève une exception. Le lifespan acquiert ensuite le verrou pour toute la durée où l’application sert des requêtes et le libère après le nettoyage de ses tâches. Cela ferme la fenêtre de migration concurrente sans déplacer la construction des routes ou refactoriser `create_app`.

### Résultats

| Oracle | Statut | Résultat |
|---|---|---|
| Reproduction en boucle du test deux bases | **PASS** | 10/10 réussites, chaque processus en 1,09–1,26 s; hang non reproduit. |
| Test borné et absence de tâches asyncio résiduelles | **PASS** | Timeout explicite de 5 s, nettoyage des tâches et assertion qu’aucune tâche ne fuit. |
| Initialisation/migration protégée par le verrou | **PASS** | Pendant `SQLiteStore.initialize`, une deuxième acquisition échoue avec « already in use ». |
| Arrêt normal | **PASS** | Après sortie des lifespans, le verrou peut être repris; l’ouverture offline et `integrity_check` réussissent. |
| Erreur d’initialisation du store | **PASS** | Exception synthétique; verrou repris immédiatement après. |
| Erreur pendant le démarrage du lifespan | **PASS** | Échec synthétique de création de tâche; aucune tâche résiduelle et verrou repris. |
| Exception dans le corps du lifespan | **PASS** | Exception propagée; tâches arrêtées et verrou repris. |
| Tests ciblés verrou + sweeper | **PASS** | `11 passed, 1 warning` en 1,69 s. |
| Suite backend | **PASS** | `463 passed, 1 skipped, 3 warnings` en 51,52 s. |
| Suite racine | **PASS** | `508 passed, 1 skipped, 4 warnings, 48 subtests passed` en 49,45 s. |
| `git diff --check` | **PASS** | Sans erreur. |
| Processus Core multiples distincts | **NOT_RUN** | Tests d’exclusivité dans un même processus et descripteurs séparés; campagne multi-processus non demandée par cet oracle. |

### Commandes exécutées

Depuis `backend/` :

```sh
ATLAS_AGENT_ADAPTER=mock python3 -m pytest -q tests/test_core_db_lock.py
ATLAS_AGENT_ADAPTER=mock python3 -m pytest -q tests/test_core_db_lock.py tests/test_v4_proposal_expiration.py::test_lifespan_sweeper_logs_tick_error_and_stops_cleanly tests/test_v4_proposal_expiration.py::test_lifespan_sweeper_can_be_disabled
ATLAS_AGENT_ADAPTER=mock python3 -m pytest -q
```

La boucle de dix exécutions a été lancée depuis `backend/` avec une limite de huit secondes par processus :

```sh
python3 - <<'PY'
import subprocess
import time

command = [
    "python3",
    "-m",
    "pytest",
    "-q",
    "tests/test_core_db_lock.py::test_different_databases_can_start_together",
]
for iteration in range(1, 11):
    started = time.monotonic()
    result = subprocess.run(command, capture_output=True, text=True, timeout=8)
    elapsed = time.monotonic() - started
    if result.returncode:
        print(f"{iteration}/10 failed after {elapsed:.2f}s")
        print(result.stdout)
        print(result.stderr)
        raise SystemExit(result.returncode)
    summary = next(line for line in result.stdout.splitlines() if "passed" in line)
    print(f"{iteration}/10 {summary}; process={elapsed:.2f}s")
PY
```

Sortie : 10 lignes `1 passed, 1 warning`, temps par processus de 1,09–1,26 s. Depuis la racine du worktree :

```sh
python3 -m pytest -q
git diff --check
```

Le correctif de course d’initialisation est inclus dans le même verrou d’écriture single-writer. La cause exacte de l’ancien blocage reste indéterminée tant qu’il ne peut pas être reproduit; le test est maintenant déterministe et chaque exécution est bornée.

**Prochaine action :** revue parent du commit local; aucun push effectué.
