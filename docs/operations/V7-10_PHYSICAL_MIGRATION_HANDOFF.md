> **AUCUNE ACTION PHYSIQUE EXÉCUTÉE EN V7-10.** Ce document est un protocole opérateur à exécuter seulement après autorisations ciblées. Il ne certifie aucune migration, installation, révocation, vérification live ou qualification Pixel.

# V7-10 — Handoff opérateur de migration physique

Base documentaire : `d2f98265b1c4ca42fafc481d7b121f5d4f2bed01` (branche d’intégration `feat/atlas-v7-10-security-reliability`). Le plan suit le registre Core V4, la migration additive SQLite v4/v5, le cycle d’expiration et les changements Android décrits dans [l’architecture V7-10](V7-10_ARCHITECTURE.md). Les gates `GO_PRODUCT_INSTALL`, `GO_LIVE_SECURITY` et `GO_DAILY_PILOT` sont **NO_GO / NOT_RUN**.

Ce protocole n’exécute aucune commande. Les chaînes entre chevrons sont des valeurs fictives à remplacer dans une session opérateur privée; ne jamais y mettre un bearer dans la ligne de commande. Les variables de chemin/id ci-dessous ne sont pas des secrets.

```sh
export PIXEL_SERIAL='<SERIAL_EXPLICITEMENT_AUTORISE>'
export CANDIDATE_APK='<CHEMIN_APK_FINAL>'
export PREVIOUS_APK='<CHEMIN_APK_PRECEDENT_SAUVEGARDE>'
export BACKUP_DIR='<CHEMIN_ABSOLU_PRIVE_HORS_DEPOT_ET_HORS_TMP>'
export CORE_COMPOSE_FILE='<CHEMIN_COMPOSE_VERIFIE>'
export CORE_SERVICE='<NOM_SERVICE_CORE_COMPOSE_VERIFIE>'
export CORE_VOLUME_NAME='<NOM_VOLUME_CORE_VERIFIE>'
export CORE_CONTAINER='<NOM_CONTENEUR_CORE_VERIFIE>'
export CORE_UID='<UID_CORE_DANS_LE_CONTENEUR>'
export CORE_GID='<GID_CORE_DANS_LE_CONTENEUR>'
export RELAY_UNIT='<SUPERVISEUR_CONNECTEUR_VERIFIE>'
export CORE_DB_PATH='/data/atlas.sqlite3'
export CORE_ENV_FILE='<CHEMIN_CONFIG_CORE_PRIVEE>'
export CORE_ENV_STAGED='<CHEMIN_CONFIG_CORE_STAGED_PRIVEE>'
export CORE_ENV_PRINCIPAL_STAGED='<CHEMIN_CONFIG_CORE_PRINCIPAL_STAGED_PRIVEE>'
export CORE_ENV_ROTATED_STAGED='<CHEMIN_CONFIG_CORE_ROTATION_STAGED_PRIVEE>'
export RELAY_INSTALL='<CHEMIN_INSTALLATION_CONNECTEUR_VERIFIE>'
export RELAY_ENV_FILE='<CHEMIN_CONFIG_RELAY_PRIVEE>'
export RELAY_ENV_STAGED='<CHEMIN_CONFIG_RELAY_STAGED_PRIVEE>'
export RELAY_ENV_PRINCIPAL_STAGED='<CHEMIN_CONFIG_RELAY_PRINCIPAL_STAGED_PRIVEE>'
export RELAY_ENV_ROTATED_STAGED='<CHEMIN_CONFIG_RELAY_ROTATION_STAGED_PRIVEE>'
export RELAY_MAP_FILE='<CHEMIN_MAP_RELAY_PRIVEE_HORS_DEPOT>'
export RELAY_MAP_DUAL_STAGED='<CHEMIN_MAP_RELAY_DUAL_STAGED_PRIVEE>'
export RELAY_MAP_PRINCIPAL_STAGED='<CHEMIN_MAP_RELAY_PRINCIPAL_STAGED_PRIVEE>'
export RELAY_MAP_ROTATED_STAGED='<CHEMIN_MAP_RELAY_ROTATION_STAGED_PRIVEE>'
export CORE_KEYRING_FILE='<CHEMIN_TROUSSEAU_CORE_PRIVE>'
export CORE_KEYRING_STAGED='<CHEMIN_TROUSSEAU_CORE_INITIAL_STAGED>'
export CORE_KEYRING_OVERLAP_STAGED='<CHEMIN_TROUSSEAU_CORE_AVEC_2_CLES_STAGED>'
export CORE_KEYRING_FINAL_STAGED='<CHEMIN_TROUSSEAU_CORE_AVEC_NOUVELLE_CLE_SEULE>'
export CORE_KEYRING_CONTAINER_FILE='<CHEMIN_TROUSSEAU_MONTE_EN_LECTURE_SEULE_DANS_CORE>'
export RELAY_ASSERTION_SECRET_FILE='<CHEMIN_SECRET_ASSERTION_PRIVE_HORS_DEPOT>'
export RELAY_ASSERTION_SECRET_STAGED='<CHEMIN_SECRET_ASSERTION_INITIAL_STAGED_PRIVE>'
export RELAY_ASSERTION_SECRET_ROTATED_STAGED='<CHEMIN_NOUVEAU_SECRET_ASSERTION_STAGED_PRIVE>'
export OLD_RELAY_ASSERTION_KEY_ID='<ANCIEN_KEY_ID_NON_SECRET>'
export NEW_RELAY_ASSERTION_KEY_ID='<NOUVEAU_KEY_ID_NON_SECRET>'
export PRINCIPAL_DB_COPY='<CHEMIN_ABSOLU_COPIE_SQLITE_DE_TRAVAIL>'
export BACKFILL_DB_COPY='<CHEMIN_ABSOLU_NEUF_COPIE_BACKFILL>'
export BINDING_DB_COPY='<CHEMIN_ABSOLU_NEUF_COPIE_BINDING>'
export TEST_REVOKE_DB_COPY='<CHEMIN_ABSOLU_NEUF_COPIE_REVOCATION_TEST>'
export LEGACY_REVOKE_DB_COPY='<CHEMIN_ABSOLU_NEUF_COPIE_REVOCATION_LEGACY>'
export ANDROID_A_ID='<ID_PRINCIPAL_ANDROID_A>'
export ANDROID_B_ID='<ID_PRINCIPAL_ANDROID_B>'
export RELAY_A_PRINCIPAL_ID='<ID_PRINCIPAL_RELAY_A>'
export RELAY_B_PRINCIPAL_ID='<ID_PRINCIPAL_RELAY_B>'
export TOOL_PRINCIPAL_ID='<ID_PRINCIPAL_TOOL>'
export RELAY_A_DEVICE_ID='<ID_RELAY_A>'
export RELAY_B_DEVICE_ID='<ID_RELAY_B_TEST>'
export LEGACY_PRINCIPAL_ID='<ID_LEGACY_RETOURNE_PAR_L_IMPORT>'
export LEGACY_CREDENTIAL_ID='<ID_CREDENTIAL_LEGACY_RETOURNE_PAR_L_IMPORT>'
export TEST_B_CREDENTIAL_ID='<ID_CREDENTIAL_RELAY_B_RETOURNEE_PAR_LA_CLI>'
export ACTIVE_RELAY_CREDENTIAL_ID='<ID_CREDENTIAL_RELAY_A_ACTIVE>'
export LEGACY_CREDENTIAL_FILE='<CHEMIN_FICHIER_BEARER_LEGACY_PRIVE>'
export PRIVATE_CREDENTIAL_DIR='<REPERTOIRE_PRIVE_CREDENTIALS_MODE_0700>'
```

Créer `BACKUP_DIR` hors dépôt et hors `/tmp`, mode `0700`; les fichiers qu’il contient restent `0600`. Ne pas enregistrer les sorties contenant des IDs de session, des noms privés ou des chemins locaux dans un rapport public.

## Prérequis de consentement, sauvegarde et vérification

- Obtenir une autorisation ciblée nommant l’appareil, le créneau, le propriétaire ADB, le snapshot applicatif, l’arrêt/redémarrage Core et Relay, la migration de base, les credentials à créer, les tests de refus, la mise à jour APK et, dans une autorisation distincte, toute révocation. Une autorisation de backup ne vaut pas autorisation de migration, d’installation ou de révocation.
- Geler pendant la fenêtre la configuration, les writes Core et les sessions Relay/Android qui pourraient modifier la base. Inventorier les processus et commandes de redémarrage réellement installés. Si le conteneur, le volume, le chemin DB ou le mécanisme de livraison des variables/secret files n’est pas identifié sans ambiguïté, arrêter le protocole.
- Sauvegarder et vérifier séparément l’état applicatif `dev.atlas.coach`, la base Core, la configuration privée Core/Relay, l’état du connecteur Relay (identité/registre/operations) et les APK précédent/candidat. Conserver les clés Keystore hors archive : elles ne sont pas exportables. Une désinstallation ne constitue pas un rollback.
- Tester la vérification et la restauration sur une copie jetable, avec fixtures synthétiques. La preuve V7-07 d’AVD API 35 et le backup/update Pixel historiques ne qualifient pas le présent APK ou le présent schéma V7-10. L’outil app-only exige `run-as`; si le préflight échoue, arrêter, ne pas remplacer par `adb backup`.
- Vérifier consentements produits actuels séparément des anciens grants de base. Une restauration de données ou l’opt-in V4 ne constitue pas un nouveau consentement.
- Ne pas afficher de credential, secret HMAC, bearer, token Relay/Gateway, contenu de conversation, projection personnelle ou proposition. Les commandes utilisent des fichiers privés ou des entrées stdin pour les bearers. Ne jamais passer un secret dans un argument, l’historique shell ou un journal.
- La migration de registre A1 est exécutée par l’ouverture `SQLiteStore` des commandes d’administration; `preview-migration` est lecture seule. Toutes les premières exécutions et validations se font sur une copie. Les modes `dual` et `principal` exigent le schéma v5.

## Protocole opérateur en 13 étapes

### 1. Geler la configuration et confirmer l’autorisation ciblée

**Précondition.** Autorisation écrite ciblée valide pour les opérations précises de la fenêtre; propriétaire ADB/Core/Relay désigné; fenêtre de maintenance communiquée.

**Action / commande.** Confirmer et tracer le change ID, puis geler les déploiements, les écritures applicatives et les changements d’environnement. Aucun changement de gateway ou de réseau public n’est implicite.

```sh
: "${CHANGE_ID:?renseigner le change ID autorise}"
: "${PIXEL_SERIAL:?renseigner le serial explicitement autorise}"
: "${CORE_CONTAINER:?renseigner le conteneur inventorie}"
```

**Résultat attendu.** Cible, portée, durée, voies d’arrêt et personnes autorisées concordent; aucune session non prévue n’écrit dans Core.

**Preuve à conserver.** Autorisation, horodatage UTC, change ID, identité logique de l’appareil sous forme expurgée, opérateurs et état initial des flux.

**Rollback / arrêt.** Sans autorisation ciblée ou si le gel ne tient pas, arrêter ici et ne pas modifier de credential/configuration.

### 2. Inventorier versions, hashes, services et base

**Précondition.** Étape 1 acceptée; chemins de configuration et de DB obtenus du déploiement observé, sans lire leur contenu secret.

**Action / commandes.** Relever un seul appareil ADB, sa version Android/API, paquet et capacité `run-as`, APK installé/candidat, commits Core/Relay, états de service et métadonnées SQLite. Les hashes sont conservés dans le dossier privé; ne pas imprimer le contenu de configuration.

```sh
adb devices -l
test "$(adb -s "$PIXEL_SERIAL" get-state)" = device
adb -s "$PIXEL_SERIAL" shell getprop ro.build.version.sdk
adb -s "$PIXEL_SERIAL" shell run-as dev.atlas.coach id
adb -s "$PIXEL_SERIAL" shell pm path dev.atlas.coach
adb -s "$PIXEL_SERIAL" shell cmd role get-role-holders android.app.role.HOME
sha256sum "$CANDIDATE_APK" "$PREVIOUS_APK"
apksigner verify --print-certs "$CANDIDATE_APK"
apksigner verify --print-certs "$PREVIOUS_APK"
apkanalyzer manifest application-id "$CANDIDATE_APK"
apkanalyzer manifest version-code "$CANDIDATE_APK"
sha256sum "$CORE_ENV_FILE" "$RELAY_ENV_FILE"
for config_file in "$RELAY_MAP_FILE" "$CORE_KEYRING_FILE" "$RELAY_ASSERTION_SECRET_FILE"; do
  if test -f "$config_file"; then sha256sum "$config_file"; fi
done
git -C '<CHEMIN_SOURCE_CORE>' rev-parse HEAD
node --version
sha256sum "$RELAY_INSTALL/dist/main.js"
docker inspect --format '{{.Image}}' "$CORE_CONTAINER"
docker compose -f "$CORE_COMPOSE_FILE" ps "$CORE_SERVICE"
docker inspect --format '{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{end}}' "$CORE_CONTAINER"
docker exec "$CORE_CONTAINER" id
docker volume inspect --format '{{.Name}} {{.Mountpoint}}' "$CORE_VOLUME_NAME"
# Si l'inventaire confirme une unité systemd user :
systemctl --user is-active "$RELAY_UNIT"
curl --fail --silent --show-error --output /dev/null --write-out '%{http_code}\n' http://127.0.0.1:8100/health
curl --fail --silent --show-error --output /dev/null --write-out '%{http_code}\n' http://127.0.0.1:4402/health
curl --silent --show-error --output /dev/null --write-out '%{http_code}\n' http://127.0.0.1:4401/metrics
```

Pour consigner l’état SQLite sans lire les lignes métier :

```sh
docker exec "$CORE_CONTAINER" python -c 'import sqlite3,sys; c=sqlite3.connect("file:"+sys.argv[1]+"?mode=ro",uri=True); print("user_version",c.execute("PRAGMA user_version").fetchone()[0]); print("integrity",c.execute("PRAGMA integrity_check").fetchone()[0]); print("foreign_key_violations",len(c.execute("PRAGMA foreign_key_check").fetchall())); print("proposal_states",c.execute("SELECT state,count(*) FROM agent_proposals_v4 GROUP BY state ORDER BY state").fetchall()); print("ack_events",c.execute("SELECT count(*) FROM agent_proposal_events_v4").fetchone()[0])' "$CORE_DB_PATH"
```

**Résultat attendu.** Un seul appareil autorisé est ciblable; l’APK candidat a le paquet attendu, une signature compatible et un `versionCode` installable en place; services et DB sont identifiés et sains. Les versions et hashes sont rapprochés des artefacts approuvés. L’état Relay n’est pas supposé à partir de la seule santé HTTP.

**Preuve à conserver.** `adb devices` expurgé, API, paquet/version, SHA-256 APK, empreinte du certificat, SHA des fichiers de configuration, versions/commits des services, santé Core/Relay et métadonnées SQLite (version, intégrité, comptes d’états/ACK).

**Rollback / arrêt.** Plusieurs appareils, `run-as` refusé, signature/paquet/version inattendus, DB non intègre, migration déjà partielle ou service non identifié : aucun changement; corriger l’inventaire avant de reprendre.

### 3. Créer une sauvegarde complète et prouver son intégrité

**Précondition.** Étape 2 conforme; espace privé suffisant; l’outil backup et l’APK installé passent le préflight.

**Action / commandes.** Capturer les données app-only avec l’outil validé, un backup cohérent Core via SQLite Backup API, ainsi que les fichiers de configuration et d’état Relay. `backup` arrête ATLAS; le consentement couvre cette interruption.

Dans cette procédure, arrêter Relay avec `systemctl --user stop "$RELAY_UNIT"` uniquement si l’étape 2 a confirmé cette unité. Sinon utiliser la commande exacte du superviseur inventorié; ne pas substituer `kill`, `nohup` ou un mécanisme de session.

```sh
umask 077
install -d -m 0700 "$BACKUP_DIR"
scripts/v7-07/atlas-product-backup.sh preflight --serial "$PIXEL_SERIAL" --apk "$CANDIDATE_APK"
scripts/v7-07/atlas-product-backup.sh backup --serial "$PIXEL_SERIAL" --backup-dir "$BACKUP_DIR/android"
scripts/v7-07/atlas-product-backup.sh verify \
  --archive "$BACKUP_DIR/android/dev.atlas.coach-appdata.tar" \
  --manifest "$BACKUP_DIR/android/manifest.json"
docker exec "$CORE_CONTAINER" python -c 'import os,sqlite3,sys; s=sqlite3.connect("file:"+sys.argv[1]+"?mode=ro",uri=True); d=sqlite3.connect(sys.argv[2]); s.backup(d); d.execute("PRAGMA foreign_keys=ON"); print("integrity",d.execute("PRAGMA integrity_check").fetchone()[0]); print("foreign_key_violations",len(d.execute("PRAGMA foreign_key_check").fetchall())); os.chmod(sys.argv[2],0o600)' "$CORE_DB_PATH" /tmp/atlas-v7-10-core-backup.sqlite3
docker cp "$CORE_CONTAINER:/tmp/atlas-v7-10-core-backup.sqlite3" "$BACKUP_DIR/core.sqlite3"
chmod 0600 "$BACKUP_DIR/core.sqlite3"
node "$RELAY_INSTALL/scripts/backup-state.mjs" "$RELAY_ENV_FILE" "$BACKUP_DIR/relay-state"
install -m 0600 "$CORE_ENV_FILE" "$BACKUP_DIR/core.env"
# Copier sans afficher les valeurs les fichiers privés présents, et conserver les APK exacts.
install -d -m 0700 "$BACKUP_DIR/private"
for private_file in "$RELAY_MAP_FILE" "$CORE_KEYRING_FILE" "$RELAY_ASSERTION_SECRET_FILE" "$PRIVATE_CREDENTIAL_DIR"/*; do
  if test -f "$private_file"; then install -m 0600 "$private_file" "$BACKUP_DIR/private/$(basename "$private_file")"; fi
done
install -m 0600 "$PREVIOUS_APK" "$BACKUP_DIR/previous.apk"
install -m 0600 "$CANDIDATE_APK" "$BACKUP_DIR/candidate.apk"
docker compose -f "$CORE_COMPOSE_FILE" stop "$CORE_SERVICE"
systemctl --user stop "$RELAY_UNIT"
CORE_VOLUME_MOUNT="$(docker volume inspect --format '{{.Mountpoint}}' "$CORE_VOLUME_NAME")"
test ! -e "$CORE_VOLUME_MOUNT/$(basename "$CORE_DB_PATH")-wal"
test ! -e "$CORE_VOLUME_MOUNT/$(basename "$CORE_DB_PATH")-shm"
docker cp "$CORE_CONTAINER:$CORE_DB_PATH" "$BACKUP_DIR/core-stopped.sqlite3"
chmod 0600 "$BACKUP_DIR/core-stopped.sqlite3"
test "$(sqlite3 -readonly "$BACKUP_DIR/core-stopped.sqlite3" 'PRAGMA integrity_check;')" = ok
test -z "$(sqlite3 -readonly "$BACKUP_DIR/core-stopped.sqlite3" 'PRAGMA foreign_key_check;')"
sqlite3 -readonly "$BACKUP_DIR/core-stopped.sqlite3" 'PRAGMA user_version;'
find "$BACKUP_DIR" -type f ! -name SHA256SUMS -print0 | LC_ALL=C sort -z | xargs -0 sha256sum > "$BACKUP_DIR/SHA256SUMS"
chmod 0600 "$BACKUP_DIR/SHA256SUMS"
sha256sum -c "$BACKUP_DIR/SHA256SUMS"
```

Copier également vers `BACKUP_DIR` la map privée, le trousseau HMAC, les fichiers de credentials, l’APK précédent et les fichiers d’état Relay présents, avec le même contrôle d’accès. Leur contenu reste secret; ne pas l’imprimer. La sauvegarde SQLite est créée à travers l’API SQLite afin d’inclure une vue cohérente même si WAL est actif.

**Résultat attendu.** `BACKUP_OK`, `VERIFY_OK`, `integrity=ok`, zéro violation de clé étrangère, archive et manifeste privés intègres, fichiers conservés dans un répertoire `0700`; Core et connecteur sont arrêtés après la capture pour geler les écritures pendant la préparation offline.

**Preuve à conserver.** SHA des archives/base/configurations, résultat de vérification, `user_version`, manifeste sans contenu app, compte des fichiers et contrôle des modes. Garder la sauvegarde complète dans le coffre privé; ne publier que les résultats expurgés.

**Rollback / arrêt.** Toute différence de hash, archive incomplète, erreur SQLite, absence de `run-as`, fichier secret manquant ou mode trop permissif : ne pas migrer et ne pas installer. Conserver les artefacts de diagnostic en accès restreint.

### 4. Vérifier et restaurer les sauvegardes sur des copies jetables

**Précondition.** Étape 3 validée; AVD API 37 jetable ou environnement jetable explicitement accepté, app candidate installée avec le certificat attendu, fixtures synthétiques uniquement; aucun compte ou service personnel connecté. L’archive source est sérialisée au Pixel d’origine et l’outil refuse de la restaurer sur un autre serial.

**Action / commandes.** Vérifier le backup exact du Pixel sans le restaurer; pour tester la mécanique de restauration Android, créer sur l’AVD un backup séparé après y avoir généré des données synthétiques, vérifier ce backup, puis le restaurer sur le même AVD/serial. Produire aussi une copie SQLite neuve et vérifier sa restauration/intégrité. Ne jamais réécrire le serial dans le manifeste ni restaurer l’archive Pixel sur un autre appareil.

```sh
sha256sum -c "$BACKUP_DIR/SHA256SUMS"
export DISPOSABLE_AVD_SERIAL='<SERIAL_AVD_JETABLE_API_37>'
export ANDROID_ADB_SERVER_PORT=5038
scripts/v7-07/atlas-product-backup.sh preflight --serial "$DISPOSABLE_AVD_SERIAL" --apk "$CANDIDATE_APK"
scripts/v7-07/atlas-product-backup.sh backup --serial "$DISPOSABLE_AVD_SERIAL" \
  --backup-dir "$BACKUP_DIR/android-avd-self-test"
scripts/v7-07/atlas-product-backup.sh verify \
  --archive "$BACKUP_DIR/android-avd-self-test/dev.atlas.coach-appdata.tar" \
  --manifest "$BACKUP_DIR/android-avd-self-test/manifest.json"
ATLAS_BACKUP_ROOT="$BACKUP_DIR/android-avd-rollback" \
ATLAS_CONFIRM_RESTORE='RESTORE dev.atlas.coach' \
  scripts/v7-07/atlas-product-backup.sh restore \
  --serial "$DISPOSABLE_AVD_SERIAL" \
  --archive "$BACKUP_DIR/android-avd-self-test/dev.atlas.coach-appdata.tar" \
  --manifest "$BACKUP_DIR/android-avd-self-test/manifest.json" \
  --confirm-restore
unset ANDROID_ADB_SERVER_PORT
```

Copie/restauration de contrôle SQLite sur un chemin neuf :

```sh
export CORE_RESTORE_TEST='<CHEMIN_ABSOLU_NEUF_COPIE_RESTAURATION_SQLITE>'
python3 - "$BACKUP_DIR/core.sqlite3" "$CORE_RESTORE_TEST" <<'PY'
import os, sqlite3, sys
source = sqlite3.connect(f"file:{sys.argv[1]}?mode=ro", uri=True)
restored = sqlite3.connect(sys.argv[2])
source.backup(restored)
print("integrity", restored.execute("PRAGMA integrity_check").fetchone()[0])
print("foreign_key_violations", len(restored.execute("PRAGMA foreign_key_check").fetchall()))
print("user_version", restored.execute("PRAGMA user_version").fetchone()[0])
os.chmod(sys.argv[2], 0o600)
PY
```

**Résultat attendu.** Le backup Pixel exact passe `verify`; restauration de l’archive synthétique créée par et liée au serial AVD réussit sur ce même AVD; copie DB restaurée, même `user_version`, `integrity=ok`, aucune violation FK. Cela ne démontre pas la restauration de l’archive Pixel sur un appareil de remplacement. Aucune clé Keystore n’est exportée par l’archive; un réappairage peut rester nécessaire.

**Preuve à conserver.** Versions API/AVD, résultat backup/verify/restore, checksums et vérifications SQLite expurgées; aucun log app ou donnée de personne.

**Rollback / arrêt.** Si l’essai échoue, conserver l’AVD et les fichiers de test pour diagnostic puis le supprimer selon la procédure approuvée. Ne pas poursuivre vers une restauration réelle pour « réparer » l’essai. Si l’exigence est de prouver la restaurabilité du backup Pixel original sur un clone, arrêter : le contrôle de serial de l’outil interdit ce scénario et toute solution nécessiterait un outil/test séparé revu.

### 5. Provisionner offline les principaux, credentials et grants sur copie

**Précondition.** Étape 4 acceptée; `PRINCIPAL_DB_COPY` est une copie vérifiée, jamais la DB active. Le propriétaire a approuvé chaque audience, principal et capability nécessaire. Les credentials sont stockés dans un répertoire privé `0700`.

**Action / commandes.** Prévisualiser la migration; créer/importer le principal LEGACY explicitement; créer les principaux Android/Relay/Tool, credentials distincts et grants choisis dans une matrice approuvée. Les mutations de la CLI appliquent les migrations au fichier passé en `--db`; elles restent limitées à la copie.

```sh
python3 - "$BACKUP_DIR/core-stopped.sqlite3" "$PRINCIPAL_DB_COPY" <<'PY'
import os, sqlite3, sys
source = sqlite3.connect(f"file:{sys.argv[1]}?mode=ro", uri=True)
copy = sqlite3.connect(sys.argv[2])
source.backup(copy)
print("integrity", copy.execute("PRAGMA integrity_check").fetchone()[0])
print("foreign_key_violations", len(copy.execute("PRAGMA foreign_key_check").fetchall()))
os.chmod(sys.argv[2], 0o600)
PY
python3 backend/scripts/atlas_v4_principals.py --db "$PRINCIPAL_DB_COPY" preview-migration
install -d -m 0700 "$PRIVATE_CREDENTIAL_DIR"
python3 backend/scripts/atlas_v4_principals.py --db "$PRINCIPAL_DB_COPY" legacy-import --grant-defaults < "$LEGACY_CREDENTIAL_FILE"
python3 backend/scripts/atlas_v4_principals.py --db "$PRINCIPAL_DB_COPY" principal add --principal-id "$ANDROID_A_ID" --kind ANDROID
python3 backend/scripts/atlas_v4_principals.py --db "$PRINCIPAL_DB_COPY" credential add --principal-id "$ANDROID_A_ID" --audience DIRECT --generate --output "$PRIVATE_CREDENTIAL_DIR/android-a.direct"
python3 backend/scripts/atlas_v4_principals.py --db "$PRINCIPAL_DB_COPY" principal add --principal-id "$RELAY_A_PRINCIPAL_ID" --kind ANDROID
python3 backend/scripts/atlas_v4_principals.py --db "$PRINCIPAL_DB_COPY" credential add --principal-id "$RELAY_A_PRINCIPAL_ID" --audience RELAY --generate --output "$PRIVATE_CREDENTIAL_DIR/relay-a.core"
python3 backend/scripts/atlas_v4_principals.py --db "$PRINCIPAL_DB_COPY" principal add --principal-id "$RELAY_B_PRINCIPAL_ID" --kind ANDROID
python3 backend/scripts/atlas_v4_principals.py --db "$PRINCIPAL_DB_COPY" credential add --principal-id "$RELAY_B_PRINCIPAL_ID" --audience RELAY --generate --output "$PRIVATE_CREDENTIAL_DIR/relay-b.core"
python3 backend/scripts/atlas_v4_principals.py --db "$PRINCIPAL_DB_COPY" principal add --principal-id "$TOOL_PRINCIPAL_ID" --kind TOOL
python3 backend/scripts/atlas_v4_principals.py --db "$PRINCIPAL_DB_COPY" credential add --principal-id "$TOOL_PRINCIPAL_ID" --audience TOOL --generate --output "$PRIVATE_CREDENTIAL_DIR/tool.core"
```

Pour chaque grant approuvé, exécuter la commande ci-dessous avec le principal et la capability correspondants. Le manifeste de grants ne contient aucun secret; ne pas accorder l’allowlist entière aux principaux Android par commodité.

```sh
python3 backend/scripts/atlas_v4_principals.py --db "$PRINCIPAL_DB_COPY" capability set \
  --principal-id "$ANDROID_A_ID" --capability '<CAPABILITY_ALLOWLISTEE_APPROUVEE>' --grant
```

Vérifier chaque credential depuis son fichier privé, sans argument secret :

```sh
python3 backend/scripts/atlas_v4_principals.py --db "$PRINCIPAL_DB_COPY" verify-token < "$PRIVATE_CREDENTIAL_DIR/android-a.direct"
python3 backend/scripts/atlas_v4_principals.py --db "$PRINCIPAL_DB_COPY" verify-token < "$PRIVATE_CREDENTIAL_DIR/relay-a.core"
python3 backend/scripts/atlas_v4_principals.py --db "$PRINCIPAL_DB_COPY" verify-token < "$PRIVATE_CREDENTIAL_DIR/relay-b.core"
python3 backend/scripts/atlas_v4_principals.py --db "$PRINCIPAL_DB_COPY" verify-token < "$PRIVATE_CREDENTIAL_DIR/tool.core"
python3 backend/scripts/atlas_v4_principals.py --db "$PRINCIPAL_DB_COPY" list
```

**Résultat attendu.** Migration v4/v5 présente sur la copie, hash uniquement dans SQLite, audiences et grants de la matrice exacts, états `ACTIVE`, `device_ref` distincts/stables. L’import LEGACY et ses grants de compatibilité sont une décision explicite, pas un défaut automatique.

**Preuve à conserver.** Sorties redacted de `verify-token`/`list`, `user_version`, checksums/intégrité, manifest des IDs non personnels et matrice d’audience/grants. Ne jamais conserver les fichiers bearer dans les preuves.

**Rollback / arrêt.** Une mauvaise audience, grant plus large, import inattendu, principal en doublon ou migration non conforme invalide la copie; la jeter de manière contrôlée et repartir d’une copie vérifiée. Ne pas tenter de remettre un tombstone en `ACTIVE`.

### 6. Configurer `dual`, le principal LEGACY et le secret curseur; redémarrer Core

**Précondition.** Étape 5 approuvée; la copie v5 et les fichiers secrets ont été restaurés/testés; V4 Relay et les clients distants restent en pause. Le service Core a un mécanisme approuvé qui livre les nouvelles variables et fichiers privés au conteneur. Le Compose de référence n’injecte pas automatiquement ces nouvelles valeurs : vérifier le mécanisme avant toute relance.

**Action / commandes.** Dans la configuration privée gérée par le déploiement, régler `ATLAS_V4_AUTH_MODE=dual`, `ATLAS_V4_LEGACY_PRINCIPAL_ID=<ID_LEGACY_ACTIF>`, `ATLAS_V4_CURSOR_SECRET=<SECRET_INJECTE_PAR_LE_GESTIONNAIRE>` et `ATLAS_V4_TOOL_PRINCIPAL_ID=<ID_TOOL>`. Injecter le curseur via le gestionnaire secret, longueur minimale 32; ne pas écrire sa valeur dans cette documentation ou dans un argument shell. Contrôler les permissions sans afficher les valeurs. Core et Relay étant arrêtés depuis l’étape 3, la copie préparée provient du dernier état gelé; ne pas la promouvoir si des écritures ont eu lieu depuis cette sauvegarde.

```sh
install -m 0600 "$CORE_ENV_STAGED" "$CORE_ENV_FILE"
stat -c '%a %U %n' "$CORE_ENV_FILE"
docker compose -f "$CORE_COMPOSE_FILE" stop "$CORE_SERVICE"
# Seulement si l’inventaire de l’étape 2 a confirmé cette unité systemd user.
systemctl --user stop "$RELAY_UNIT"
CORE_VOLUME_MOUNT="$(docker volume inspect --format '{{.Mountpoint}}' "$CORE_VOLUME_NAME")"
test -d "$CORE_VOLUME_MOUNT"
test ! -e "$CORE_VOLUME_MOUNT/$(basename "$CORE_DB_PATH")-wal"
test ! -e "$CORE_VOLUME_MOUNT/$(basename "$CORE_DB_PATH")-shm"
sudo install -o "$CORE_UID" -g "$CORE_GID" -m 0600 "$PRINCIPAL_DB_COPY" "$CORE_VOLUME_MOUNT/$(basename "$CORE_DB_PATH")"
docker compose -f "$CORE_COMPOSE_FILE" up -d --force-recreate "$CORE_SERVICE"
CORE_CONTAINER="$(docker compose -f "$CORE_COMPOSE_FILE" ps -q "$CORE_SERVICE")"
docker compose -f "$CORE_COMPOSE_FILE" ps "$CORE_SERVICE"
curl --fail --silent --show-error http://127.0.0.1:8100/health
```

**Résultat attendu.** Core redémarre avec `dual`, schéma v5, principal LEGACY explicite et curseur secret valide; health OK; aucune route Relay n’est ouverte avant l’étape 8. Les lignes NULL sont encore présentes jusqu’au backfill.

**Preuve à conserver.** Hash de la config privée (pas son contenu), `stat` des fichiers, hash de la copie déployée, `user_version`, `integrity_check`, health et sortie expurgée de `docker compose ps`.

**Rollback / arrêt.** Si variables non injectées, fichier hors droits, WAL/SHM résiduel, migration incomplète ou Core non sain : arrêter Core; ne pas forcer `down -v`; conserver l’état affecté, restaurer la copie vérifiée pré-migration et la configuration antérieure seulement si aucun credential n’a été révoqué.

### 7. Backfill des propositions historiques NULL sur copie vérifiée

**Précondition.** Core en dual, écritures clients suspendues, principal LEGACY et son consentement/attribution historique vérifiés. La cible est une copie SQLite fraîche issue de l’état Core gelé. Le sweeper et les pulls peuvent changer des états : toute écriture depuis le snapshot invalide la promotion de cette copie.

**Action / commandes.** Core démarre en dual mais les clients restent suspendus. Pour le backfill, arrêter Core, vérifier la fermeture SQLite, capturer une copie fraîche de la DB active, lancer le dry-run puis appliquer uniquement sur cette copie avec backup exclusif. Après contrôles, promouvoir la copie alors que Core reste arrêté et redémarrer. Comparer les nombres de propositions et d’ACK avant/après, par état; aucune ligne d’ACK ne doit être modifiée.

```sh
docker compose -f "$CORE_COMPOSE_FILE" stop "$CORE_SERVICE"
# Seulement si l’inventaire de l’étape 2 a confirmé cette unité systemd user.
systemctl --user stop "$RELAY_UNIT"
CORE_VOLUME_MOUNT="$(docker volume inspect --format '{{.Mountpoint}}' "$CORE_VOLUME_NAME")"
test ! -e "$CORE_VOLUME_MOUNT/$(basename "$CORE_DB_PATH")-wal"
test ! -e "$CORE_VOLUME_MOUNT/$(basename "$CORE_DB_PATH")-shm"
docker cp "$CORE_CONTAINER:$CORE_DB_PATH" "$BACKFILL_DB_COPY"
chmod 0600 "$BACKFILL_DB_COPY"
python3 backend/scripts/atlas_v4_principals.py --db "$BACKFILL_DB_COPY" backfill-proposals --legacy-principal-id "$LEGACY_PRINCIPAL_ID"
python3 backend/scripts/atlas_v4_principals.py --db "$BACKFILL_DB_COPY" backfill-proposals \
  --legacy-principal-id "$LEGACY_PRINCIPAL_ID" --apply --backup "$BACKUP_DIR/pre-backfill.sqlite3"
sqlite3 -readonly "$BACKFILL_DB_COPY" \
  "SELECT 'proposals',count(*) FROM agent_proposals_v4 UNION ALL SELECT 'unowned',count(*) FROM agent_proposals_v4 WHERE device_ref IS NULL UNION ALL SELECT 'ack_events',count(*) FROM agent_proposal_events_v4;"
sqlite3 -readonly "$BACKFILL_DB_COPY" \
  "SELECT state,count(*) FROM agent_proposals_v4 GROUP BY state ORDER BY state;"
sudo install -o "$CORE_UID" -g "$CORE_GID" -m 0600 "$BACKFILL_DB_COPY" "$CORE_VOLUME_MOUNT/$(basename "$CORE_DB_PATH")"
docker compose -f "$CORE_COMPOSE_FILE" up -d --force-recreate "$CORE_SERVICE"
CORE_CONTAINER="$(docker compose -f "$CORE_COMPOSE_FILE" ps -q "$CORE_SERVICE")"
```

Si un dry-run séparé du cycle d’expiration est demandé, il est read-only par défaut; `--apply` est une mutation distincte, avec son propre backup vérifié et son propre accord. Ne pas inclure une expiration en masse dans l’autorisation de backfill sans la nommer.

**Résultat attendu.** `unowned=0` sur la copie après apply, total des propositions et nombre/états d’ACK inchangés, intégrité/FK valides, backup d’avant apply vérifié et comptes documentés. Les propositions anciennes sont attribuées au seul LEGACY confirmé; aucune répartition estimée.

**Preuve à conserver.** Sorties dry-run/apply redacted, hash du backup, comptes avant/après, `integrity_check`, FK, `user_version` et compte ACK exact sans IDs de proposition.

**Rollback / arrêt.** Si le nombre d’ACK diffère, une ligne est ambiguë, la sauvegarde échoue ou le Core a écrit depuis le snapshot, ne pas promouvoir la copie. Arrêter Core, refaire une sauvegarde du nouvel état et recommencer sur une nouvelle copie; ne pas modifier à la main les lignes SQL.

### 8. Configurer Relay dual, map/binding/clé HMAC et redémarrer de façon contrôlée

**Précondition.** Étape 7 validée; chaque ID Relay de test/produit a un principal `ANDROID` actif, un credential `RELAY` actif et un binding planifié. Une clé HMAC est provisionnée de façon identique dans le trousseau Core et le fichier privé du connecteur, avec `key_id` convenu. Map et credential appartiennent au compte du connecteur; le keyring appartient à l’UID vu par le processus Core dans le conteneur.

**Action / commandes.** Arrêter Core, prendre une copie fraîche de la DB, enregistrer le binding côté Core sur cette copie, contrôler son intégrité puis la promouvoir avant redémarrage. Placer la map privée et le fichier secret hors racine `.git`, réguliers, non symlink, owner courant et mode `0600`. La map doit respecter exactement la forme `{ "<RELAY_DEVICE_ID>": { "principal_id": "<PRINCIPAL_ID>", "credential_file": "<CHEMIN_ABSOLU_CREDENTIAL>" } }`. Ne jamais mettre la map, les credentials, le keyring ou le secret dans le dépôt.

```sh
docker compose -f "$CORE_COMPOSE_FILE" stop "$CORE_SERVICE"
# Seulement si l’inventaire de l’étape 2 a confirmé cette unité systemd user.
systemctl --user stop "$RELAY_UNIT"
CORE_VOLUME_MOUNT="$(docker volume inspect --format '{{.Mountpoint}}' "$CORE_VOLUME_NAME")"
test ! -e "$CORE_VOLUME_MOUNT/$(basename "$CORE_DB_PATH")-wal"
test ! -e "$CORE_VOLUME_MOUNT/$(basename "$CORE_DB_PATH")-shm"
docker cp "$CORE_CONTAINER:$CORE_DB_PATH" "$BINDING_DB_COPY"
chmod 0600 "$BINDING_DB_COPY"
cp --reflink=never "$BINDING_DB_COPY" "$BACKUP_DIR/core-before-binding.sqlite3"
chmod 0600 "$BACKUP_DIR/core-before-binding.sqlite3"
sha256sum "$BACKUP_DIR/core-before-binding.sqlite3"
python3 backend/scripts/atlas_v4_principals.py --db "$BINDING_DB_COPY" binding add \
  --relay-device-id "$RELAY_A_DEVICE_ID" --principal-id "$RELAY_A_PRINCIPAL_ID"
python3 backend/scripts/atlas_v4_principals.py --db "$BINDING_DB_COPY" binding add \
  --relay-device-id "$RELAY_B_DEVICE_ID" --principal-id "$RELAY_B_PRINCIPAL_ID"
python3 - "$BINDING_DB_COPY" <<'PY'
import sqlite3, sys
c = sqlite3.connect(f"file:{sys.argv[1]}?mode=ro", uri=True)
print("integrity", c.execute("PRAGMA integrity_check").fetchone()[0])
print("foreign_key_violations", len(c.execute("PRAGMA foreign_key_check").fetchall()))
PY
install -m 0600 "$CORE_ENV_STAGED" "$CORE_ENV_FILE"
install -m 0600 "$RELAY_ENV_STAGED" "$RELAY_ENV_FILE"
install -m 0600 "$RELAY_MAP_DUAL_STAGED" "$RELAY_MAP_FILE"
install -m 0600 "$RELAY_ASSERTION_SECRET_STAGED" "$RELAY_ASSERTION_SECRET_FILE"
sudo install -o "$CORE_UID" -g "$CORE_GID" -m 0600 "$CORE_KEYRING_STAGED" "$CORE_KEYRING_FILE"
chmod 0600 "$RELAY_MAP_FILE" "$CORE_KEYRING_FILE" "$RELAY_ASSERTION_SECRET_FILE"
stat -c '%a %U %n' "$CORE_ENV_FILE" "$RELAY_ENV_FILE" "$RELAY_MAP_FILE" "$CORE_KEYRING_FILE" "$RELAY_ASSERTION_SECRET_FILE" "$PRIVATE_CREDENTIAL_DIR/relay-a.core" "$PRIVATE_CREDENTIAL_DIR/relay-b.core"
node "$RELAY_INSTALL/scripts/check-config.mjs" --env-file "$RELAY_ENV_FILE"
node "$RELAY_INSTALL/scripts/check-secret.mjs" --env-file "$RELAY_ENV_FILE"
sudo install -o "$CORE_UID" -g "$CORE_GID" -m 0600 "$BINDING_DB_COPY" "$CORE_VOLUME_MOUNT/$(basename "$CORE_DB_PATH")"
docker compose -f "$CORE_COMPOSE_FILE" up -d --force-recreate "$CORE_SERVICE"
CORE_CONTAINER="$(docker compose -f "$CORE_COMPOSE_FILE" ps -q "$CORE_SERVICE")"
```

Dans la configuration Core privée montée au conteneur, renseigner `ATLAS_V4_RELAY_ASSERTION_KEYS_FILE=<CORE_KEYRING_CONTAINER_FILE>`. Dans la configuration Relay privée : `ATLAS_V4_ENABLED=1`, `ATLAS_V4_AUTH_MODE=dual`, `ATLAS_REQUIRE_PRINCIPAL_BINDING=1`, `ATLAS_CORE_PRINCIPAL_MAP_FILE=<MAP_PRIVEE>`, `ATLAS_V4_RELAY_ASSERTION_KEY_ID=<KEY_ID>` et `ATLAS_V4_RELAY_ASSERTION_SECRET_FILE=<SECRET_PRIVÉ>`. La variable de compatibilité `ATLAS_V4_ANDROID_TOKEN_FILE` doit être absente. Les deux commandes de validation doivent passer sans afficher les valeurs.

```sh
systemctl --user restart "$RELAY_UNIT"
systemctl --user is-active "$RELAY_UNIT"
curl --fail --silent --show-error --output /dev/null --write-out '%{http_code}\n' http://127.0.0.1:4401/metrics
curl --fail --silent --show-error http://127.0.0.1:4402/health
```

N’utiliser `systemctl --user` que si l’inventaire a confirmé cette unité installée. Sinon, le change plan doit nommer le superviseur existant et sa commande d’arrêt/redémarrage; ne pas improviser `nohup`, `killall` ou `loginctl enable-linger`.

**Résultat attendu.** Core et connecteur en `dual`; map et secret validés; binding actif concordant avec le principal et credential `RELAY`; health des services OK. Map absente/invalide, assertion absente ou désaccord credential/binding coupe V4 sans fallback.

**Preuve à conserver.** Statut redacted des checks, hash de map/config/keyring, `key_id` non secret, binding/principal/credential IDs expurgés, health et résultat du redémarrage.

**Rollback / arrêt.** Si un check échoue, ne pas reconnecter Android. Arrêter le connecteur, revenir à la map/config précédente vérifiée, restaurer la DB/config Core précédente en dual sans downgrade de schéma, puis redémarrer le service dont l’état est connu.

### 9. Installer en place l’APK final et vérifier données/consentements/opt-in

**Précondition.** Étapes 1–8 acceptées; `CANDIDATE_APK` est final et approuvé, package/version/signature comparés, backup app-only vérifié; autorisation inclut l’installation sur cet appareil précis.

**Action / commandes.** Répéter le préflight juste avant l’installation. Installer avec `-r`, même paquet et même signature, sans désinstaller, sans `pm clear`, sans changer HOME. Comparer les hashes des données non personnelles avec le manifeste et vérifier via l’UI l’opt-in V4 et les consentements séparément.

```sh
scripts/v7-07/atlas-product-backup.sh preflight --serial "$PIXEL_SERIAL" --apk "$CANDIDATE_APK"
adb -s "$PIXEL_SERIAL" install -r "$CANDIDATE_APK"
adb -s "$PIXEL_SERIAL" shell dumpsys package dev.atlas.coach
adb -s "$PIXEL_SERIAL" shell cmd role get-role-holders android.app.role.HOME
```

Ne pas afficher `dumpsys` entier dans un rapport public. Contrôler les champs utiles localement. Le credential Core ne doit pas être confondu avec le token d’appairage Relay; le V4 opt-in reste `false` jusqu’à l’action explicite de l’utilisateur et ne remplace aucun consentement serveur.

**Résultat attendu.** Mise à jour conservant les données, paquet, certificat et rôle HOME; opt-in/consentements présents et inchangés; aucun réappairage supposé à partir d’un ciphertext Keystore seul.

**Preuve à conserver.** SHA APK candidat, certificat, versionCode, résultat `install -r`, hashes des données clés et rôle HOME, sans contenu de fichiers.

**Rollback / arrêt.** Signature/paquet incompatible, versionCode inférieur, donnée manquante/quarantaine, prompt de réappairage inattendu ou changement de consentement : arrêter le scénario réseau; conserver l’état défectueux et, si autorisé, réinstaller l’APK précédent avec `adb install -r`. La restauration de données in-situ exige un accord distinct.

### 10. Tester A/A, B/B, A avec bearer B, révocation et reconnexion

**Précondition.** Core/Relay dual sains; consentements actuels de test accordés séparément; fixtures synthétiques; principal B et appareil Relay B de test distincts; harness approuvé qui parle à la session Relay réellement authentifiée. Si aucun second principal/appareil/harness indépendant n’est disponible, cette étape est bloquée et aucun résultat ne peut être inféré des tests locaux.

**Action / séquence.** Exécuter dans une fenêtre isolée : (a) session A avec map/credential/binding A; (b) session B avec B; (c) recopier temporairement dans la map de test de A le `credential_file` B tout en gardant l’ID de session A et son binding A; (d) rétablir map A; (e) révoquer un credential jetable B après backup, redémarrage contrôlé et vérifier son refus; (f) créer une nouvelle credential B, mettre à jour la map et reconnecter la session Relay B. Ne pas révoquer le principal ou bearer produit pour tester.

Pour la révocation de test, le Core doit être arrêté avant l’outil offline :

```sh
docker compose -f "$CORE_COMPOSE_FILE" stop "$CORE_SERVICE"
# Seulement si l’inventaire de l’étape 2 a confirmé cette unité systemd user.
systemctl --user stop "$RELAY_UNIT"
CORE_VOLUME_MOUNT="$(docker volume inspect --format '{{.Mountpoint}}' "$CORE_VOLUME_NAME")"
test ! -e "$CORE_VOLUME_MOUNT/$(basename "$CORE_DB_PATH")-wal"
test ! -e "$CORE_VOLUME_MOUNT/$(basename "$CORE_DB_PATH")-shm"
docker cp "$CORE_CONTAINER:$CORE_DB_PATH" "$TEST_REVOKE_DB_COPY"
chmod 0600 "$TEST_REVOKE_DB_COPY"
cp --reflink=never "$TEST_REVOKE_DB_COPY" "$BACKUP_DIR/core-before-test-revoke.sqlite3"
chmod 0600 "$BACKUP_DIR/core-before-test-revoke.sqlite3"
sha256sum "$BACKUP_DIR/core-before-test-revoke.sqlite3"
python3 backend/scripts/atlas_v4_principals.py --db "$TEST_REVOKE_DB_COPY" credential revoke --credential-id "$TEST_B_CREDENTIAL_ID"
python3 backend/scripts/atlas_v4_principals.py --db "$TEST_REVOKE_DB_COPY" credential add \
  --principal-id "$RELAY_B_PRINCIPAL_ID" --audience RELAY --generate \
  --output "$PRIVATE_CREDENTIAL_DIR/relay-b-reconnected.core"
sudo install -o "$CORE_UID" -g "$CORE_GID" -m 0600 "$TEST_REVOKE_DB_COPY" "$CORE_VOLUME_MOUNT/$(basename "$CORE_DB_PATH")"
docker compose -f "$CORE_COMPOSE_FILE" up -d --force-recreate "$CORE_SERVICE"
CORE_CONTAINER="$(docker compose -f "$CORE_COMPOSE_FILE" ps -q "$CORE_SERVICE")"
```

Mettre ensuite à jour l’entrée B de la map pour pointer sur `relay-b-reconnected.core`, lancer `check-config` et `check-secret`, puis redémarrer le connecteur par le superviseur inventorié et reconnecter la session B. L’unité ci-dessous ne s’applique que si elle a été confirmée à l’étape 2.

```sh
systemctl --user restart "$RELAY_UNIT"
```

**Résultat attendu.** A/A et B/B réussissent uniquement pour leurs principals; A avec credential B est rejeté (`401` générique/fail-closed), sans lecture ou mutation croisée; après révocation le credential B reste refusé; la nouvelle credential B reconnecte après mise à jour de map. Consentements de A ne sont jamais réutilisés pour B.

**Preuve à conserver.** Résultats HTTP/code et hash des requêtes expurgés; état/compte des lignes avant/après; aucun payload, bearer, proposition personnelle ou secret. Relier l’assertion à la version exacte du harness et aux IDs synthétiques.

**Rollback / arrêt.** Toute réponse autre que le refus attendu en A/B, mutation croisée, consentement hérité, erreur de rejeu ambiguë ou état incertain : couper V4 Relay, garder Core dual, isoler les fichiers/hashes et ne pas passer à principal. La credential de test révoquée n’est pas réactivée; une credential neuve est requise.

### 11. Passer en mode `principal`, refuser LEGACY et créer la nouvelle paire

**Précondition.** Étape 10 validée pour tous les appareils autorisés; aucun client nécessaire ne dépend encore du bearer LEGACY; principal Relay et credentials `DIRECT`/`RELAY` neufs créés et vérifiés, binding actif et configuration/map préparés; consentements frais documentés.

**Action / commandes.** Geler encore les clients, sauvegarder DB/config, installer la configuration privée `principal` préparée, basculer Core et connecteur en `principal`, vérifier `ATLAS_V4_CURSOR_SECRET` et `ATLAS_V4_TOOL_PRINCIPAL_ID`, redémarrer, puis dans l’UI établir la nouvelle paire avec l’identité Relay approuvée. Tenter l’ancien credential LEGACY via le harness sans l’imprimer.

```sh
docker compose -f "$CORE_COMPOSE_FILE" stop "$CORE_SERVICE"
# Seulement si l’inventaire de l’étape 2 a confirmé cette unité systemd user.
systemctl --user stop "$RELAY_UNIT"
CORE_VOLUME_MOUNT="$(docker volume inspect --format '{{.Mountpoint}}' "$CORE_VOLUME_NAME")"
test ! -e "$CORE_VOLUME_MOUNT/$(basename "$CORE_DB_PATH")-wal"
test ! -e "$CORE_VOLUME_MOUNT/$(basename "$CORE_DB_PATH")-shm"
docker cp "$CORE_CONTAINER:$CORE_DB_PATH" "$BACKUP_DIR/core-before-principal.sqlite3"
chmod 0600 "$BACKUP_DIR/core-before-principal.sqlite3"
test "$(sqlite3 -readonly "$BACKUP_DIR/core-before-principal.sqlite3" 'PRAGMA integrity_check;')" = ok
test -z "$(sqlite3 -readonly "$BACKUP_DIR/core-before-principal.sqlite3" 'PRAGMA foreign_key_check;')"
sha256sum "$BACKUP_DIR/core-before-principal.sqlite3"
install -d -m 0700 "$BACKUP_DIR/principal-cutover"
install -m 0600 "$CORE_ENV_FILE" "$BACKUP_DIR/principal-cutover/core.env"
install -m 0600 "$RELAY_ENV_FILE" "$BACKUP_DIR/principal-cutover/relay.env"
install -m 0600 "$RELAY_MAP_FILE" "$BACKUP_DIR/principal-cutover/relay-map.json"
install -m 0600 "$CORE_KEYRING_FILE" "$BACKUP_DIR/principal-cutover/core-keyring.json"
install -m 0600 "$RELAY_ASSERTION_SECRET_FILE" "$BACKUP_DIR/principal-cutover/relay-assertion.secret"
sha256sum "$BACKUP_DIR/core-before-principal.sqlite3" "$BACKUP_DIR/principal-cutover"/*
install -m 0600 "$CORE_ENV_PRINCIPAL_STAGED" "$CORE_ENV_FILE"
install -m 0600 "$RELAY_ENV_PRINCIPAL_STAGED" "$RELAY_ENV_FILE"
install -m 0600 "$RELAY_MAP_PRINCIPAL_STAGED" "$RELAY_MAP_FILE"
stat -c '%a %U %n' "$CORE_ENV_FILE" "$RELAY_ENV_FILE" "$RELAY_MAP_FILE"
docker compose -f "$CORE_COMPOSE_FILE" up -d --force-recreate "$CORE_SERVICE"
CORE_CONTAINER="$(docker compose -f "$CORE_COMPOSE_FILE" ps -q "$CORE_SERVICE")"
node "$RELAY_INSTALL/scripts/check-config.mjs" --env-file "$RELAY_ENV_FILE"
node "$RELAY_INSTALL/scripts/check-secret.mjs" --env-file "$RELAY_ENV_FILE"
systemctl --user restart "$RELAY_UNIT"
curl --fail --silent --show-error http://127.0.0.1:8100/health
curl --fail --silent --show-error http://127.0.0.1:4402/health
# Dans l’UI, établir la nouvelle paire Relay approuvée; via le harness, confirmer le refus LEGACY sans afficher le bearer.
```

La commande systemd ne s’applique que si cette unité existe et a été vérifiée à l’étape 2; sinon utiliser le superviseur déjà inventorié.

**Résultat attendu.** Les credentials `DIRECT`/`RELAY` actifs passent avec le bon principal; `LEGACY` est refusé; le Core n’ouvre pas de fallback; la nouvelle paire Relay s’authentifie avec son binding propre.

**Preuve à conserver.** Hash config, check-config/check-secret, réponse santé, refus legacy, identité/credential nouveaux expurgés et consentements actuels.

**Rollback / arrêt.** Avant toute révocation, un retour contrôlé en `dual` peut être envisagé si l’ancien credential est encore actif et le risque partagé explicitement accepté. Sinon rester en `principal` fail-closed et corriger par credential neuf.

### 12. Révoquer l’ancien bearer, faire la rotation et prouver fail-closed

**Précondition.** Étape 11 acceptée; autorisation ciblée distincte pour la révocation du credential LEGACY, backup vérifié immédiat, nouvelle paire testée, configuration de rollback comprise. La révocation est irréversible dans le registre.

**Action / commandes.** Arrêter Core, capturer une copie SQLite fraîche et sa sauvegarde, révoquer le credential LEGACY et faire tourner le credential `RELAY` A par `credential rotate` sur cette copie. Cette commande crée un nouvel identifiant actif et révoque l’ancien dans la même opération; elle ne réactive jamais l’ancien bearer. Après contrôle et promotion, pointer la map A vers le nouveau fichier. Pour HMAC, provisionner dans le gestionnaire de secrets un nouveau secret sous `NEW_RELAY_ASSERTION_KEY_ID`, faire accepter temporairement les clés `$OLD_RELAY_ASSERTION_KEY_ID` et `$NEW_RELAY_ASSERTION_KEY_ID` côté Core, basculer le connecteur vers la nouvelle, vérifier un appel Relay, puis retirer l’ancienne clé. Au plus deux clés Core sont acceptées. Ne jamais réutiliser un bearer révoqué ni mettre une clé en ligne de commande.

```sh
docker compose -f "$CORE_COMPOSE_FILE" stop "$CORE_SERVICE"
# Seulement si l’inventaire de l’étape 2 a confirmé cette unité systemd user.
systemctl --user stop "$RELAY_UNIT"
CORE_VOLUME_MOUNT="$(docker volume inspect --format '{{.Mountpoint}}' "$CORE_VOLUME_NAME")"
test ! -e "$CORE_VOLUME_MOUNT/$(basename "$CORE_DB_PATH")-wal"
test ! -e "$CORE_VOLUME_MOUNT/$(basename "$CORE_DB_PATH")-shm"
docker cp "$CORE_CONTAINER:$CORE_DB_PATH" "$LEGACY_REVOKE_DB_COPY"
chmod 0600 "$LEGACY_REVOKE_DB_COPY"
cp --reflink=never "$LEGACY_REVOKE_DB_COPY" "$BACKUP_DIR/core-before-legacy-revoke.sqlite3"
chmod 0600 "$BACKUP_DIR/core-before-legacy-revoke.sqlite3"
sha256sum "$BACKUP_DIR/core-before-legacy-revoke.sqlite3"
test "$(sqlite3 -readonly "$LEGACY_REVOKE_DB_COPY" 'PRAGMA integrity_check;')" = ok
test -z "$(sqlite3 -readonly "$LEGACY_REVOKE_DB_COPY" 'PRAGMA foreign_key_check;')"
sqlite3 -readonly "$LEGACY_REVOKE_DB_COPY" 'PRAGMA user_version;'
python3 backend/scripts/atlas_v4_principals.py --db "$LEGACY_REVOKE_DB_COPY" credential revoke --credential-id "$LEGACY_CREDENTIAL_ID"
set +e
LEGACY_VERIFY="$(python3 backend/scripts/atlas_v4_principals.py --db "$LEGACY_REVOKE_DB_COPY" verify-token < "$LEGACY_CREDENTIAL_FILE")"
LEGACY_VERIFY_STATUS=$?
set -e
test "$LEGACY_VERIFY_STATUS" -eq 1
test "$LEGACY_VERIFY" = '{"valid": false}'
python3 backend/scripts/atlas_v4_principals.py --db "$LEGACY_REVOKE_DB_COPY" credential rotate \
  --credential-id "$ACTIVE_RELAY_CREDENTIAL_ID" --generate \
  --output "$PRIVATE_CREDENTIAL_DIR/relay-a.rotated.core"
set +e
RELAY_A_OLD_VERIFY="$(python3 backend/scripts/atlas_v4_principals.py --db "$LEGACY_REVOKE_DB_COPY" verify-token < "$PRIVATE_CREDENTIAL_DIR/relay-a.core")"
RELAY_A_OLD_VERIFY_STATUS=$?
set -e
test "$RELAY_A_OLD_VERIFY_STATUS" -eq 1
test "$RELAY_A_OLD_VERIFY" = '{"valid": false}'
python3 backend/scripts/atlas_v4_principals.py --db "$LEGACY_REVOKE_DB_COPY" verify-token \
  < "$PRIVATE_CREDENTIAL_DIR/relay-a.rotated.core"
python3 - "$LEGACY_REVOKE_DB_COPY" <<'PY'
import sqlite3, sys
c = sqlite3.connect(f"file:{sys.argv[1]}?mode=ro", uri=True)
print("integrity", c.execute("PRAGMA integrity_check").fetchone()[0])
print("foreign_key_violations", len(c.execute("PRAGMA foreign_key_check").fetchall()))
print("user_version", c.execute("PRAGMA user_version").fetchone()[0])
PY
sudo install -o "$CORE_UID" -g "$CORE_GID" -m 0600 "$LEGACY_REVOKE_DB_COPY" "$CORE_VOLUME_MOUNT/$(basename "$CORE_DB_PATH")"
install -m 0600 "$RELAY_MAP_ROTATED_STAGED" "$RELAY_MAP_FILE"
install -m 0600 "$RELAY_ASSERTION_SECRET_ROTATED_STAGED" "$RELAY_ASSERTION_SECRET_FILE"
sudo install -o "$CORE_UID" -g "$CORE_GID" -m 0600 "$CORE_KEYRING_OVERLAP_STAGED" "$CORE_KEYRING_FILE"
install -m 0600 "$RELAY_ENV_ROTATED_STAGED" "$RELAY_ENV_FILE"
install -m 0600 "$CORE_ENV_ROTATED_STAGED" "$CORE_ENV_FILE"
stat -c '%a %U %n' "$CORE_KEYRING_FILE" "$RELAY_ASSERTION_SECRET_FILE" \
  "$RELAY_MAP_FILE" "$PRIVATE_CREDENTIAL_DIR/relay-a.rotated.core"
node "$RELAY_INSTALL/scripts/check-config.mjs" --env-file "$RELAY_ENV_FILE"
node "$RELAY_INSTALL/scripts/check-secret.mjs" --env-file "$RELAY_ENV_FILE"
docker compose -f "$CORE_COMPOSE_FILE" up -d --force-recreate "$CORE_SERVICE"
CORE_CONTAINER="$(docker compose -f "$CORE_COMPOSE_FILE" ps -q "$CORE_SERVICE")"
systemctl --user restart "$RELAY_UNIT"
curl --fail --silent --show-error http://127.0.0.1:8100/health
curl --fail --silent --show-error http://127.0.0.1:4402/health
# Via le harness approuvé, refuser LEGACY et l'ancien credential RELAY A; réussir A avec le bearer tourné et NEW_RELAY_ASSERTION_KEY_ID.
sudo install -o "$CORE_UID" -g "$CORE_GID" -m 0600 "$CORE_KEYRING_FINAL_STAGED" "$CORE_KEYRING_FILE"
docker compose -f "$CORE_COMPOSE_FILE" up -d --force-recreate "$CORE_SERVICE"
CORE_CONTAINER="$(docker compose -f "$CORE_COMPOSE_FILE" ps -q "$CORE_SERVICE")"
curl --fail --silent --show-error http://127.0.0.1:8100/health
curl --fail --silent --show-error http://127.0.0.1:4402/health
# Rejouer le succès A avec la nouvelle clé; refuser LEGACY, l’ancien bearer et une assertion signée par l’ancien key_id.
```

`CORE_ENV_ROTATED_STAGED` garde `ATLAS_V4_AUTH_MODE=principal` et désigne le même `CORE_KEYRING_CONTAINER_FILE`. `CORE_KEYRING_OVERLAP_STAGED` contient temporairement l’ancienne et la nouvelle clé; `CORE_KEYRING_FINAL_STAGED` ne contient que la nouvelle. `RELAY_ENV_ROTATED_STAGED` conserve le mode `principal`, la map et le chemin de secret validés, avec `ATLAS_V4_RELAY_ASSERTION_KEY_ID` égal à la valeur de `$NEW_RELAY_ASSERTION_KEY_ID`. Le secret HMAC nouvellement provisionné fait au moins 32 octets; son ID reste non secret et respecte les contraintes du code. Le fichier de map staged doit associer `RELAY_A_DEVICE_ID` à `RELAY_A_PRINCIPAL_ID` et au chemin `relay-a.rotated.core`. Les fichiers staged sont préparés hors dépôt et vérifiés avant l’arrêt; cette page n’en contient aucune valeur.

N’utiliser la commande `systemctl --user` que si l’unité a été confirmée à l’étape 2; sinon exécuter l’équivalent exact du superviseur inventorié. Le nouveau `key_id` doit être observé par le harness avant de retirer l’ancienne clé du trousseau Core. Les contrôles `check-config` et `check-secret` ne remplacent pas l’essai signé de bout en bout.

**Résultat attendu.** Le bearer LEGACY et l’ancien credential RELAY A échouent; leurs identifiants restent révoqués. Les credentials fraîchement provisionnés restent utilisables. Map, binding, assertion et nouveau `key_id` sont vérifiés après retrait de l’ancienne clé. Config manquante, map invalide ou signature HMAC invalide désactive V4 sans fallback.

**Preuve à conserver.** Événement de révocation, IDs/key IDs non secrets, hashes des fichiers privés et résultats de refus/connexion; conserver la sauvegarde chiffrée et le rapport expurgé.

**Rollback / arrêt.** Ne pas revenir à une sauvegarde pré-révocation comme état actif et ne pas rebasculer vers un mode qui rendrait l’ancien bearer utilisable. Corriger avec une credential neuve, un nouveau binding/map et, si l’identité Relay a été révoquée, un nouvel appairage.

### 13. Clôturer les gates et décider si le pilote E est autorisable

**Précondition.** Étapes précédentes terminées ou formellement bloquées; aucune erreur non comprise; toutes les preuves collectées sont expurgées; propriétaire décideur disponible.

**Action / commandes.** Réconcilier les statuts de service, DB, app, credentials, consentements, expiration, backup/restore, Android et sécurité; faire une revue indépendante sur le SHA exact. Aucun script de clôture ne met les gates à GO automatiquement.

```sh
git -C '<CHEMIN_SOURCE_CORE>' rev-parse HEAD
git -C '<CHEMIN_SOURCE_CORE>' status --short
sha256sum -c "$BACKUP_DIR/SHA256SUMS"
```

**Résultat attendu.** Le rapport final renvoie aux preuves A2/A3/A5/A4-delta/A6, cite le SHA relu et distingue `PASS`, `NO_GO`, `NOT_RUN` et les limitations. Dans ce checkout, A5, A4-delta indépendant et A6 sont absents : ces conclusions restent **à consolider dans le rapport final**. Sans preuve Android 17/API 37 Pixel spécifique à l’APK et à cette migration, `GO_PRODUCT_INSTALL` ne peut pas être levé. Les trois gates restent `NO_GO / NOT_RUN` jusqu’à décision explicite du propriétaire.

**Preuve à conserver.** Rapport final signé par l’orchestrateur/relecteur, inventaire expurgé, hashes des preuves privées, chaîne des SHAs, écarts et personne qui autorise (ou refuse) E.

**Rollback / arrêt.** Si une gate nécessaire est `NOT_RUN`, si une preuve manque, si une anomalie d’identité/consentement/expiration reste ouverte ou si une révocation n’est pas démontrée, ne pas lancer E; garder V4 coupé ou le mode le plus restrictif accepté.

## Rollback avant révocation

Avant toute révocation, si le credential partagé historique reste `ACTIVE` et si un rollback est autorisé, arrêter Core et Relay, sauvegarder l’état courant, revenir explicitement à `ATLAS_V4_AUTH_MODE=legacy`, puis redémarrer avec la configuration correspondante. Conserver le schéma v4/v5 : **ne supprimer ni colonne ni table et ne restaurer aucune ancienne base par défaut**. En mode legacy le bearer partagé redevient accepté et le risque P1 est rouvert; consigner propriétaire, motif et échéance du retour. Si le seul problème est une map/assertion invalide, garder les clients Relay arrêtés et corriger les fichiers privés au lieu de choisir un fallback implicite.

## Rollback après révocation

Une révocation de credential est monotone; le bearer révoqué ne peut pas être ressuscité. Ne pas restaurer une sauvegarde pré-révocation comme base active si elle ferait apparaître le credential `ACTIVE`. Pour revenir sur une base de données endommagée, arrêter Core, préserver la base affectée et ses WAL/SHM, restaurer un snapshot contrôlé sur une nouvelle copie, réappliquer le tombstone de révocation avant ouverture du service, vérifier l’intégrité, puis **réémettre une credential neuve avec un nouvel identifiant**. Mettre à jour la map et le binding depuis cette nouvelle credential; faire un nouvel appairage si le token/identité Relay a lui-même été révoqué. Une restauration DB peut perdre les écritures post-snapshot : inventorier et documenter cette fenêtre avant promotion.

## Risques bloquants pour le pilote E

- **Identité :** principal, credential, audience, `device_ref`, session Relay et binding doivent converger; un partage de bearer ou un mapping incohérent bloque le pilote.
- **Consentements :** consentements historiques, données restaurées et opt-in V4 ne prouvent pas le consentement actuel de chaque principal.
- **Expiration :** propositions échues, non livrées, `UNCERTAIN`, ACK et audit serveur doivent être réconciliés sans inventer d’ACK ni transformer un état incertain en terminal.
- **Mapping :** map privée et binding `relay_principal_bindings_v4` doivent correspondre; absence ou erreur doit rester fail-closed.
- **Secret :** keyring Core, secret de signature Relay, `key_id`, permissions `0600`, frontière du checkout ATLAS, horloge et anti-rejeu sont des prérequis d’exploitation.
- **Données :** backup complet vérifié, restauration sur copie, WAL/SHM et fenêtre d’écritures post-backup doivent être maîtrisés; Keystore ne se restaure pas depuis le tar.
- **Restauration app-only :** le manifeste lie l’archive au serial d’origine; le test AVD valide la mécanique avec une archive synthétique de ce même AVD, pas la restauration du backup Pixel sur un appareil différent.
- **Signature APK :** paquet, versionCode et certificat doivent permettre la mise à jour in-place; désinstaller ferait perdre les clés Keystore et peut exiger un nouvel appairage.
- **Absence de preuve Pixel :** aucune qualification Pixel V7-10 n’est présente. Une preuve historique V7-07 de backup/update ne valide ni l’APK final V7-10, ni les parcours auth/expiration actuels.
- **Preuves de clôture :** les rapports A5, A4-delta indépendant et A6 ne sont pas présents dans cette base; le dossier final et la décision de pilote doivent être consolidés après leur livraison.

## Références et traçabilité

- Code Core/Relay/Android/contrats : [V7-10_ARCHITECTURE.md](V7-10_ARCHITECTURE.md), [migration sécurité](V7-10_SECURITY_MIGRATION.md), [cycle d’expiration](V7-10_EXPIRATION_LIFECYCLE.md), [fiabilité Android A3](V7-10_ANDROID_RELIABILITY.md).
- Preuves de code : [A1](evidence/V7-10_A1_IMPLEMENTATION_EVIDENCE.md), [correctifs A1-FIX](evidence/V7-10_A1_FIX_EVIDENCE.md), [A2](evidence/V7-10_A2_IMPLEMENTATION_EVIDENCE.md), [S3 Android](evidence/V7-10_S3_ANDROID_MAP.md).
- Préparation et limites historiques : [V7-07 exécution physique](V7-07_PHYSICAL_EXECUTION.md), [V7-07 backup/rollback](V7-07_BACKUP_AND_ROLLBACK.md), [V7-08 handoff](V7-08_HANDOFF.md), [V7-09 rapport final](V7-09_FINAL_REPORT.md).
- Commits d’intégration vérifiés, dans l’ordre de l’historique : `ae76bda`, `499b4ef`, `710a49a`, `4c50a13`, `8dd31a7`, `acccd0c`, `6d8688d`, `cef4e13`, `00a4a8d`, `2ff9fa1`, `a43f115`, `298fafe`, `5cdbc80`, `fa82af3`, `614b639`, `a566503`, `70beee9`, `7b1004b`, `c0eb437`, `549f658`, `e672ee2`, `440b0a5`, `0890fd2`, `d96810b`, `77dc105`, `ffcd43e`, `739ad87`, `d2f9826`. La chaîne a été vérifiée dans l’historique d’intégration; aucun commit de branche source non intégrée n’est cité.

Ce document est un handoff, pas une preuve d’exécution. Aucune des commandes ci-dessus n’a été exécutée pendant V7-10-D1-DOCS.
