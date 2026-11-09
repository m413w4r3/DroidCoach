# V7-11B-B16 — Design du binding V4 et du chemin Relay

**Statut :** design uniquement, aucune opération live exécutée.
**Base obligatoire :** 256d517e1f97856dcd9e3431c75ae564f352919c.
**Périmètre :** principal, credential, capability, binding V4 et transition d’authentification Relay.

Aucune valeur de bearer, secret, identifiant de device, principal, credential, device_ref, empreinte publique ou chemin privé live n’est reproduite. Les commandes sont des modèles non exécutés. Aucun fichier runtime, volume, bearer, identité ou app n’a été modifié. Aucun test ni ADB n’a été lancé.

## 1. État actuel vérifié

Vérifications en lecture seule le 9 novembre 2026. Le SHA et l’arbre propre ont été contrôlés dans le worktree et le dépôt de référence. Les requêtes SQLite ont utilisé mode=ro et PRAGMA query_only=ON. L’environnement live a été inspecté par présence de variables, compteurs et métadonnées uniquement.

| Surface | Résultat |
|---|---|
| Core SQLite | user_version=5, migration V5 présente, tables du registre présentes ; 0 principal, 0 credential, 0 binding. Core et port loopback 8100 joignables. |
| Core auth V4 | Mode legacy explicitement configuré par l’override Compose actif ; keyring d’assertion absent, ATLAS_V4_CURSOR_SECRET absent, principaux outil et legacy configurés absents. Le flag ATLAS_V4_ENABLED à 0 vu dans cet override n’est pas le mode d’authentification Core. |
| Configuration Compose Core | Les labels live indiquent trois fichiers Compose effectifs. Le troisième est un override privé hors du dépôt ; il fixe notamment le mode legacy, le flag V4 Core à 0 et le cache anti-rejeu. Son chemin et son contenu brut ne sont pas reproduits. Les exemples Compose qui omettent cet override ne sont pas sûrs pour recréer le Core live. |
| Cache anti-rejeu Core | ATLAS_V4_REPLAY_CACHE_DIR configuré ; répertoire absolu, présent, non symlink, mode 0700, propriétaire Core, dans le volume persistant /data. |
| Capacités globales Core | compose.openclaw.yaml active le sous-ensemble lecture/chat. Aucun grant principal n’existe dans principal_capabilities_v4. |
| Connecteur | V4 activé, mode legacy, ATLAS_REQUIRE_PRINCIPAL_BINDING=0, fichier bearer Android configuré. Map principal et paramètres d’assertion (key ID, fichier ou variable) absents. |
| Transport | Proxy 4400 et métriques connecteur 4401 joignables ; relevé courant : relay_connected=true, active_sessions=0. La preuve chat rapporte une session ouverte (connections_opened_total=1, sessions_opened_total=1, active_sessions=1), une requête et aucune erreur (requests_total=1, errors_total=0). |
| Identité Relay | Fichier régulier, mode 0600. Le connecteur actif a chargé une identité valide au démarrage ; son initialiseur vérifie la paire publique/privée et l’empreinte. Le fichier privé n’a pas été ouvert et les valeurs publiques ne sont pas reproduites. |
| Registre Relay | 20 devices au total ; exactement un device actif porte le libellé pixel-v7. Aucun identifiant Relay n’a été relevé dans la preuve. |
| Application | La preuve post-migration rapporte POST /v2/agent/chat → 200 avec réponse visible. CoreV4SyncWorker a été exécuté dans cette preuve, mais aucune requête HTTP séparée n’a été observée. La configuration V4 effective de l’app reste opaque. Pas d’ADB dans cette tâche. |
| Backup et administration | Le backup B7 est documenté en mode 0600. La revue B11 le limite au snapshot local. Les outils de backup connecteur standard omettent operations.json ; l’administration/recréation du Relay n’est pas qualifiée. Aucun backup live n’a été créé ou ouvert ici. |

La preuve B2 V7-11B décrit un état antérieur à la migration live : elle reste utile pour le connecteur, mais ses observations de schéma Core v3/absent sont dépassées. Le relevé SQLite ci-dessus confirme directement l’état Core v5 actuel. Sources : [preuve identité/binding](V7-11B_IDENTITY_BINDING_EVIDENCE.md), [préparation V7-11B](../V7-11B_PREPARATION.md), [preuve Android post-migration](V7-11B_ANDROID_POST_MIGRATION_EVIDENCE.md), [revue backup live](V7-11B_LIVE_BACKUP_REVIEW.md), [preuve backup connecteur](V7-11B_CONNECTOR_BACKUP_EVIDENCE.md).

## 2. Modèle d’authentification

### Principal, credential et audience

principals_v4.kind accepte ANDROID, TOOL et LEGACY. Un principal actif possède un device_ref unique et immuable. credentials_v4 stocke le hash SHA-256 du bearer, pas le bearer lui-même ; chaque credential a une audience et un état actif/révoqué.

Audiences :

- DIRECT : credential V4 présenté directement par l’application Android ; sans assertion Relay.
- RELAY : credential réservé aux requêtes Core émises par le connecteur ; exige assertion valide et binding actif.
- TOOL : credential distinct pour les routes internes d’outils ; le Core attend aussi le principal outil configuré.
- LEGACY : compatibilité enregistrée explicitement ; acceptée en dual si enregistrée, refusée en principal pour les routes V4 Android.

Parcours Relay :

1. La session Relay authentifiée sélectionne un relay_device_id déjà présent dans le registre du connecteur.
2. La map privée du connecteur associe ce relay_device_id à un principal et à un fichier de credential Relay. Le credential ne vient jamais du payload Android.
3. Le connecteur envoie le bearer RELAY au Core et signe la requête HTTP.
4. Le Core résout le bearer par hash, récupère principal, device_ref, audience et grants, puis valide assertion et binding.
5. Le Core exige l’égalité du principal et du device_ref résolus avec la ligne de binding active correspondant au relay_device_id signé.

Le principal Relay est de kind ANDROID avec un credential d’audience RELAY. relay_principal_bindings_v4 associe relay_device_id à principal_id ; la jointure avec principals_v4 donne le device_ref. L’ajout exige principal actif et credential RELAY actif. Un relay_device_id déjà provisionné ne peut pas être réaffecté ; la révocation est monotone.

### Capacités et consentements

Allowlist V4 :

- Lecture/chat/proposition : day_plan.read, activity_status.read, plan_revision.propose, usage_summary.read, goal_progress.read, training_plan.read, coach.chat.
- Écriture/synchronisation : day_plan.snapshot.write, activity_status.write, usage_summary.write, goal_progress.write, training_plan.write, consent.state.write, proposal.ack.

Les grants par principal résident dans principal_capabilities_v4 et doivent être accordés explicitement. Le Core applique aussi les capacités globales actives et, pour les projections concernées, le consentement Android, sa version et la fraîcheur des données. Une capability grantée seule ne vaut pas consentement.

### Tables principales

principals_v4, credentials_v4, principal_capabilities_v4, principal_events_v4 et relay_principal_bindings_v4 forment le registre. Les évènements sont immuables ; les triggers interdisent la réactivation d’un principal, credential ou binding révoqué. La propriété des propositions et snapshots dépend du device_ref. Un nouveau principal crée un nouveau device_ref : aucun historique n’est transféré automatiquement.

### Assertion HMAC Relay

Les en-têtes portent le device Relay, le key ID, l’horodatage, le nonce et la signature. Le message canonique est :

~~~text
v1\nMETHOD\nPATH\nTIMESTAMP\nNONCE\nRELAY_DEVICE_ID\nSHA256(body)
~~~

La signature est HMAC-SHA256 base64url avec préfixe v1=. Le connecteur signe méthode, route et corps effectivement envoyés ; le Core valide les mêmes octets/chemin, une fenêtre temporelle de ±120 secondes, le key ID, la signature en comparaison temps constant et la réservation unique (key_id, nonce). Le Core refuse aussi une assertion trop ancienne par rapport à son démarrage.

Le Core accepte au plus deux clés simultanées pour rotation. Le key_id ne fait pas partie du message canonique : chaque key ID doit avoir un secret HMAC différent. En production, les deux côtés lisent des fichiers privés 0600, absolus, réguliers et détenus par le compte du processus. Le Core lit un JSON {key_id: base64url_secret} ; le connecteur lit une valeur base64url seule de 32 octets minimum. Pas de secret en variable hors tests.

Le cache mémoire par défaut n’est pas partagé entre workers et disparaît au redémarrage. Le cache fichier configuré ici est persistant, atomique et fail-closed ; son répertoire doit rester privé 0700, sur un volume stable partagé par tout worker autorisé. Fenêtre ±120 secondes, TTL cache 300 secondes, recul d’horloge après expiration du cache et erreurs de cache restent à surveiller.

### Modes Core

| Mode | Comportement V4 |
|---|---|
| legacy | Utilise le bearer Android statique historique. Le connecteur peut utiliser ATLAS_V4_ANDROID_TOKEN_FILE si aucune map n’est fournie. Une map présente en legacy désactive V4 côté connecteur ; ne pas installer la map lors d’une simple préparation registre. |
| dual | Accepte les credentials du registre, y compris LEGACY s’ils ont été importés explicitement. Un credential Relay exige assertion et binding. Le connecteur choisit la map si elle est valide ; map ou clé invalide désactive V4 au lieu de retomber sur le bearer Android. |
| principal | Refuse LEGACY pour les routes V4, exige un ATLAS_V4_CURSOR_SECRET stable d’au moins 32 caractères et les credentials/capacités nécessaires. Les routes d’outils exigent un principal TOOL et ATLAS_V4_TOOL_PRINCIPAL_ID. |

Le cursor secret protège les curseurs paginés. Sans valeur configurée, le service crée une clé aléatoire par processus, ce qui invalide les curseurs au redémarrage ; en principal, son absence empêche le démarrage. Le code ne propose pas ATLAS_V4_CURSOR_SECRET_FILE : la lecture depuis un fichier exige wrapper de démarrage ou support de code.

Le mode V4 ne remplace pas l’authentification séparée de POST /v2/agent/chat, qui reste basée sur le bearer legacy. En revanche, le worker Android V4 ne peut pas utiliser un bearer legacy absent du registre après le passage en dual. Il n’est pas observé en émission actuellement ; vérifier sur copie avant promotion.

## 3. Gaps et interdictions

### Chemin Relay V4

Manquent : principal Android pour le device Relay, credential RELAY, binding vers l’identifiant déjà appairé, grants minimaux, map privée, secret HMAC commun et key ID côté Core, ATLAS_V4_AUTH_MODE=dual, ATLAS_REQUIRE_PRINCIPAL_BINDING=1 et redémarrage contrôlé du connecteur. Le schéma Core v5 et le cache anti-rejeu durable sont déjà présents ; aucun keyring n’est chargé.

Le connecteur courant reste en fallback bearer. Pour le chemin principal-bound, map complète avant activation dual ; sinon le connecteur ferme son V4. Vérifier aussi sur copie si OpenClaw V4 appelle des routes TOOL : registre et principal outil sont actuellement vides.

### Application DIRECT/V4

Manquent : credential DIRECT, configuration effective de l’app et vérification de stockage local, grants de sync validés et cycle de sync observé. Le nouveau device_ref n’hérite pas des données historiques. Toute migration de données, consentements ou propositions est distincte ; ne rien attribuer par déduction.

### À ne pas faire dans ce chantier

- Ne pas importer le bearer live sans besoin démontré ; ne pas le changer, tourner ou révoquer.
- Ne pas créer, tourner ou révoquer credential, binding ou grant live sans approbation ciblée.
- Ne pas installer la map alors que le connecteur reste legacy : le code désactive alors V4 au lieu d’utiliser le fallback.
- Ne pas lancer legacy-import, backfill-proposals, atlas_v4_expiration_reconcile.py avec --apply ni modifier SQLite directement.
- Ne pas appairer/révoquer un device Relay, toucher état/volume/identité, ni exécuter show-identity sur le live (peut créer une identité manquante et lit le fichier privé).
- Ne pas envoyer de chat V4 réel comme test : la route peut appeler OpenClaw, lire des projections ou créer une proposition.
- Ne pas lancer ADB ni inspecter les préférences Android.

## 4. Options de transition

| Option | Gain de sécurité | Risque principal | Réversibilité | Prérequis et autorisation |
|---|---|---|---|---|
| A — Préparer registre en legacy | Prépare principal, credential Relay, binding et secrets sans imposer l’auth principale au trafic. | Credential/binding actifs mais dormants ; CLI offline et arrêt Core. Révocation monotone, pas de suppression simple. Ne pas installer la map connecteur. | Retour trafic legacy ; les lignes créées restent auditées et dormantes. | Approbation provisionnement/backup et fenêtre d’arrêt Core ; confirmer le device Relay actif sans publier son identifiant. |
| B — Relay en dual, bearer app inchangé | Toute requête V4 Relay utilise audience RELAY, HMAC, anti-rejeu et binding. Le chat V2 garde son auth. | Map/clé invalide bloque V4 ; worker app V4 non observé peut échouer ; routes OpenClaw TOOL peuvent nécessiter un principal dédié. | Revenir à legacy côté config, en conservant les lignes ; ne pas révoquer. | Accord live spécifique pour dual, credential, binding, secrets et redémarrages ; backup/restore ; répétition synthétique PASS. |
| C — App DIRECT/V4 avec nouvelle credential | Credential DIRECT séparée de Relay et du bearer historique. | Config app opaque, stockage secret et sync non démontrés ; nouveaux grants/consentements ; nouveau device_ref sans héritage. | Restaurer config app et laisser credential dormant. | Accord distinct pour credential DIRECT, capacités de sync, configuration/install Android et QA API 37 avec sauvegarde consentie. |
| D — Cutover principal et révocation legacy | Retire le chemin V4 legacy et impose registre, audiences et capacités. | Rupture clients legacy ; révocations irréversibles ; cursor secret et TOOL indispensables ; /v2/agent/chat reste une surface séparée. | Faible après révocation. Credential/binding révoqué ne peut être réactivé ; restauration DB peut perdre des opérations post-backup. | Accord nominatif séparé, preuves DIRECT/RELAY/TOOL, migration V2 séparée, backup complet/restauration répétée. |

## 5. Plan recommandé par phases

**Recommandation :** répétition isolée, puis option B comme changement borné. A seulement si attendre entre phases a une valeur : A crée déjà des lignes et secrets actifs dormants. Garder le bearer app inchangé, le chat V2 legacy et les requêtes produit Relay hors service tant que les oracles ne sont pas passés. Ne pas grouper C ou D avec B.

### Phase 0 — Préparation et backup

1. Accord explicite pour fenêtre Core/connecteur et copie des secrets d’état en backup. B7 0600 ne remplace pas un snapshot frais. Vérifier le restore sur copie.
2. Sauvegarder Core à l’arrêt avec volume complet et contrôles integrity_check, foreign_key_check et version 5. Backup hors dépôt, répertoire 0700, fichiers 0600, nom neuf.
3. Arrêter le connecteur via son contrôle Paseo confirmé avant de copier identity.json, devices.json et operations.json sous archive privée. Le processus actif n’a pas de commande d’arrêt vérifiée dans ce dossier ; sans elle, arrêter la phase et ne pas prétendre que le snapshot connecteur est cohérent. Les outils standards omettent operations.json ; ne pas utiliser leur restore incomplet.
4. Ne pas recréer/modifier Relay sans propriétaire et persistance identifiés. Le plan ne change pas le pairing existant.
5. Après approbation, créer key ID non réutilisé, secret HMAC aléatoire d’au moins 32 octets distinct du cursor secret, cursor secret stable, keyring Core, fichier assertion connecteur, credential temporaire et map. Tous hors dépôt, mode 0600, propriétaires correspondant aux processus consommateurs.
6. Répéter provisioning et HMAC sur copie v5 synthétique : mauvais audience, binding absent, key ID inconnu, mauvaise signature, rejeu, map invalide et restart.

Modèles non exécutés. Variables privées à fournir ; ne pas activer set -x ni afficher les JSON de reçus.

~~~sh
export CODE_ROOT="${CODE_ROOT:?checkout principal requis}"
test -n "$ATLAS_COMPOSE_ENV_FILE"
test -n "$LIVE_COMPOSE_OVERRIDE"
test -f "$LIVE_COMPOSE_OVERRIDE"
test -n "$BACKUP_DIR"
test -n "$CONNECTOR_STATE_DIR"
test ! -e "$BACKUP_DIR"
# LIVE_COMPOSE_OVERRIDE doit être le troisième fichier effectif constaté sur le Core live.
# Vérifier cette correspondance avec l’opérateur sans afficher le chemin ni le contenu.
set -eu

umask 077
install -d -m 0700 "$BACKUP_DIR"
install -d -m 0700 "$BACKUP_DIR/core-volume" "$BACKUP_DIR/connector"
start_core() {
  docker compose --env-file "$ATLAS_COMPOSE_ENV_FILE" \
    -f "$CODE_ROOT/compose.yaml" -f "$CODE_ROOT/compose.openclaw.yaml" \
    -f "$LIVE_COMPOSE_OVERRIDE" start atlas-core
}
trap start_core EXIT
docker compose --env-file "$ATLAS_COMPOSE_ENV_FILE" \
  -f "$CODE_ROOT/compose.yaml" -f "$CODE_ROOT/compose.openclaw.yaml" \
  -f "$LIVE_COMPOSE_OVERRIDE" stop atlas-core
CORE_CID="$(docker compose --env-file "$ATLAS_COMPOSE_ENV_FILE" \
  -f "$CODE_ROOT/compose.yaml" -f "$CODE_ROOT/compose.openclaw.yaml" \
  -f "$LIVE_COMPOSE_OVERRIDE" ps -aq atlas-core)"
docker cp "$CORE_CID:/data/." "$BACKUP_DIR/core-volume"
find "$BACKUP_DIR/core-volume" -type d -exec chmod 0700 {} +
find "$BACKUP_DIR/core-volume" -type f -exec chmod 0600 {} +
sha256sum "$BACKUP_DIR/core-volume/atlas.sqlite3" > "$BACKUP_DIR/core-volume/manifest.sha256"
chmod 0600 "$BACKUP_DIR/core-volume/manifest.sha256"
python3 - "$BACKUP_DIR/core-volume/atlas.sqlite3" <<'PY'
import sqlite3, sys
c = sqlite3.connect("file:" + sys.argv[1] + "?mode=ro", uri=True)
c.execute("PRAGMA query_only=ON")
assert c.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
assert not c.execute("PRAGMA foreign_key_check").fetchall()
assert c.execute("PRAGMA user_version").fetchone()[0] == 5
c.close()
PY
for name in identity.json devices.json operations.json; do
  cp -- "$CONNECTOR_STATE_DIR/$name" "$BACKUP_DIR/connector/$name"
  chmod 0600 "$BACKUP_DIR/connector/$name"
done
sha256sum "$BACKUP_DIR/connector/identity.json" \
  "$BACKUP_DIR/connector/devices.json" "$BACKUP_DIR/connector/operations.json" \
  > "$BACKUP_DIR/connector/manifest.sha256"
chmod 0600 "$BACKUP_DIR/connector/manifest.sha256"
docker compose --env-file "$ATLAS_COMPOSE_ENV_FILE" \
  -f "$CODE_ROOT/compose.yaml" -f "$CODE_ROOT/compose.openclaw.yaml" \
  -f "$LIVE_COMPOSE_OVERRIDE" start atlas-core
trap - EXIT
~~~

Le snapshot Core doit être contrôlé sur copie avec les éventuels sidecars SQLite. Si contrôle échoue, arrêter la phase. Après le contrôle, redémarrer le connecteur via son contrôle Paseo confirmé avec sa configuration antérieure. Le restore manuel multi-fichier connecteur n’est pas atomique et reste P2 ; ne pas l’utiliser comme rollback live avant durcissement et nouvel essai.

Génération de fichiers secrets sans impression de contenu :

~~~sh
test -n "$CORE_SECRET_DIR"
test -n "$CONNECTOR_SECRET_DIR"
test -n "$CORE_KEYS_FILE"
test -n "$CONNECTOR_ASSERTION_FILE"
test -n "$CURSOR_FILE"
test -n "$ATLAS_V4_RELAY_ASSERTION_KEY_ID"
test -n "$CORE_UID"
test -n "$CORE_GID"
test -n "$CONNECTOR_UID"
test -n "$CONNECTOR_GID"
sudo install -d -o "$CORE_UID" -g "$CORE_GID" -m 0700 "$CORE_SECRET_DIR"
sudo install -d -o "$CONNECTOR_UID" -g "$CONNECTOR_GID" -m 0700 "$CONNECTOR_SECRET_DIR"
sudo env CORE_KEYS_FILE="$CORE_KEYS_FILE" CONNECTOR_ASSERTION_FILE="$CONNECTOR_ASSERTION_FILE" \
  CURSOR_FILE="$CURSOR_FILE" ATLAS_V4_RELAY_ASSERTION_KEY_ID="$ATLAS_V4_RELAY_ASSERTION_KEY_ID" \
  CORE_UID="$CORE_UID" CORE_GID="$CORE_GID" CONNECTOR_UID="$CONNECTOR_UID" \
  CONNECTOR_GID="$CONNECTOR_GID" bash -eu <<'ROOT'
umask 077
python3 - "$CORE_KEYS_FILE" "$CONNECTOR_ASSERTION_FILE" "$CURSOR_FILE" \
  "$ATLAS_V4_RELAY_ASSERTION_KEY_ID" <<'PY'
import base64, json, os, secrets, sys
core_keys, connector_key, cursor_file, key_id = sys.argv[1:]
relay = base64.urlsafe_b64encode(secrets.token_bytes(32)).rstrip(b"=").decode("ascii")
cursor = base64.urlsafe_b64encode(secrets.token_bytes(32)).rstrip(b"=").decode("ascii")
def write_new(path, value):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL |
                 getattr(os, "O_NOFOLLOW", 0), 0o600)
    with os.fdopen(fd, "w", encoding="ascii") as f:
        f.write(value)
        f.flush()
        os.fsync(f.fileno())
write_new(core_keys, json.dumps({key_id: relay}, separators=(",", ":")) + "\n")
write_new(connector_key, relay + "\n")
write_new(cursor_file, cursor)
PY
chmod 0600 "$CORE_KEYS_FILE" "$CONNECTOR_ASSERTION_FILE" "$CURSOR_FILE"
chown "$CORE_UID:$CORE_GID" "$CORE_KEYS_FILE" "$CURSOR_FILE"
chown "$CONNECTOR_UID:$CONNECTOR_GID" "$CONNECTOR_ASSERTION_FILE"
ROOT
~~~

Les répertoires/fichiers doivent ensuite appartenir aux UID effectifs Core et connecteur. Le cursor n’a pas d’option fichier native : l’override local doit monter le fichier privé et l’exporter sans journaliser la valeur, ou un changement de code distinct doit ajouter un support fichier.

### Phase 1 — Principal + binding Relay en dual

Préconditions : Phase 0 acceptée, répétition synthétique PASS, unique entrée active pixel-v7 confirmée, zéro binding, grants minimaux choisis. Arrêter le connecteur par le contrôle Paseo vérifié, puis arrêter Core pendant les commandes CLI offline. Garder le connecteur arrêté jusqu’à ce que le Core dual soit sain et la nouvelle map installée. Si le contrôle d’arrêt/reprise connecteur n’est toujours pas déterminé, B est NO-GO. Ne pas modifier le bearer Android existant.

~~~sh
export CODE_ROOT="${CODE_ROOT:?checkout principal requis}"
test -n "$ATLAS_COMPOSE_ENV_FILE"
test -n "$LIVE_COMPOSE_OVERRIDE"
test -f "$LIVE_COMPOSE_OVERRIDE"
test -n "$V4_COMPOSE_OVERRIDE"
test -f "$V4_COMPOSE_OVERRIDE"
docker compose --env-file "$ATLAS_COMPOSE_ENV_FILE" \
  -f "$CODE_ROOT/compose.yaml" -f "$CODE_ROOT/compose.openclaw.yaml" \
  -f "$LIVE_COMPOSE_OVERRIDE" stop atlas-core
test -n "$PRIVATE_WORK"
test ! -e "$PRIVATE_WORK"
test -n "$CONNECTOR_UID"
test -n "$CONNECTOR_GID"
test -n "$CONNECTOR_SECRET_DIR"
test -n "$CONNECTOR_MAP_FILE"
test ! -e "$CONNECTOR_MAP_FILE"
test -n "$CONNECTOR_STATE_DIR"
test -f "$CONNECTOR_STATE_DIR/devices.json"
test -n "$APPROVED_RELAY_CAPABILITY"
sudo install -d -o root -g root -m 0700 "$PRIVATE_WORK"
CORE_DB="$(docker volume inspect --format '{{.Mountpoint}}' atlas-core-data)/atlas.sqlite3"
sudo install -d -o "$CONNECTOR_UID" -g "$CONNECTOR_GID" -m 0700 "$CONNECTOR_SECRET_DIR"
test ! -e "$CONNECTOR_SECRET_DIR/relay-credential"
sudo env CODE_ROOT="$CODE_ROOT" CORE_DB="$CORE_DB" PRIVATE_WORK="$PRIVATE_WORK" \
  CONNECTOR_UID="$CONNECTOR_UID" CONNECTOR_GID="$CONNECTOR_GID" \
  CONNECTOR_SECRET_DIR="$CONNECTOR_SECRET_DIR" CONNECTOR_MAP_FILE="$CONNECTOR_MAP_FILE" \
  CONNECTOR_STATE_DIR="$CONNECTOR_STATE_DIR" \
  APPROVED_RELAY_CAPABILITY="$APPROVED_RELAY_CAPABILITY" bash -eu <<'ROOT'
python3 "$CODE_ROOT/backend/scripts/atlas_v4_principals.py" \
  --db "$CORE_DB" preview-migration > "$PRIVATE_WORK/preflight.json"
python3 "$CODE_ROOT/backend/scripts/atlas_v4_principals.py" --db "$CORE_DB" \
  principal add --kind ANDROID > "$PRIVATE_WORK/principal.json"
PRINCIPAL_ID="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["principal_id"])' \
  "$PRIVATE_WORK/principal.json")"
python3 "$CODE_ROOT/backend/scripts/atlas_v4_principals.py" --db "$CORE_DB" \
  credential add --principal-id "$PRINCIPAL_ID" --audience RELAY --generate \
  --output "$PRIVATE_WORK/relay-credential" > "$PRIVATE_WORK/credential-receipt.json"
install -o "$CONNECTOR_UID" -g "$CONNECTOR_GID" -m 0600 \
  "$PRIVATE_WORK/relay-credential" "$CONNECTOR_SECRET_DIR/relay-credential"
RELAY_DEVICE_ID="$(python3 - "$CONNECTOR_STATE_DIR/devices.json" <<'PY'
import json, sys
devices=json.load(open(sys.argv[1], encoding="utf-8"))["devices"]
matches=[d for d in devices if d.get("label")=="pixel-v7" and d.get("revoked_at") is None]
if len(matches) != 1: raise SystemExit("expected exactly one active pixel-v7 device")
print(matches[0]["device_id"])
PY
)"
python3 "$CODE_ROOT/backend/scripts/atlas_v4_principals.py" --db "$CORE_DB" \
  binding add --relay-device-id "$RELAY_DEVICE_ID" --principal-id "$PRINCIPAL_ID" \
  > "$PRIVATE_WORK/binding-receipt.json"
python3 - "$RELAY_DEVICE_ID" "$PRINCIPAL_ID" \
  "$CONNECTOR_SECRET_DIR/relay-credential" "$CONNECTOR_MAP_FILE" <<'PY'
import json, os, sys
relay_id, principal_id, credential_file, target = sys.argv[1:]
payload = {relay_id: {"principal_id": principal_id, "credential_file": credential_file}}
fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL |
             getattr(os, "O_NOFOLLOW", 0), 0o600)
with os.fdopen(fd, "w", encoding="utf-8") as output:
    json.dump(payload, output, separators=(",", ":"))
    output.write("\n")
    output.flush()
    os.fsync(output.fileno())
PY
chown "$CONNECTOR_UID:$CONNECTOR_GID" "$CONNECTOR_MAP_FILE"
chmod 0600 "$CONNECTOR_MAP_FILE"
python3 "$CODE_ROOT/backend/scripts/atlas_v4_principals.py" --db "$CORE_DB" \
  capability set --principal-id "$PRINCIPAL_ID" \
  --capability "$APPROVED_RELAY_CAPABILITY" --grant \
  > "$PRIVATE_WORK/capability-receipt.json"
ROOT
~~~

Le principal, credential et binding sont trois mutations CLI distinctes. Après erreur, arrêter et vérifier agrégats ; pas de révocation compensatoire ni réemploi d’un binding révoqué. Cette CLI offline ne prend pas le verrou du service : garder Core arrêté pendant toute la séquence.

Map connecteur privée, générée depuis les variables sans imprimer son contenu :

~~~json
{
  "<relay_device_id lu en mémoire>": {
    "principal_id": "<principal_id depuis reçu privé>",
    "credential_file": "<chemin absolu du credential 0600>"
  }
}
~~~

Fichier d’environnement connecteur, hors dépôt, mode 0600 :

~~~dotenv
ATLAS_V4_ENABLED=1
ATLAS_V4_AUTH_MODE=dual
ATLAS_REQUIRE_PRINCIPAL_BINDING=1
ATLAS_CORE_PRINCIPAL_MAP_FILE=<chemin absolu map privée>
ATLAS_V4_RELAY_ASSERTION_KEY_ID=<key_id non secret>
ATLAS_V4_RELAY_ASSERTION_SECRET_FILE=<chemin absolu secret connecteur>
ATLAS_V4_ANDROID_TOKEN_FILE=<valeur existante inchangée pour rollback legacy>
~~~

Core doit recevoir ATLAS_V4_AUTH_MODE=dual, ATLAS_V4_RELAY_ASSERTION_KEYS_FILE vers le keyring monté en lecture seule, le chemin persistant courant ATLAS_V4_REPLAY_CACHE_DIR et le cursor secret stable. L’override local doit monter les fichiers keyring/cursor en lecture seule, conserver l’entrypoint de chargement des secrets existant, puis lui passer ATLAS_V4_CURSOR_SECRET depuis le fichier privé sans afficher sa valeur. Exemple de forme à compléter avec les chemins protégés :

~~~yaml
services:
  atlas-core:
    environment:
      ATLAS_V4_AUTH_MODE: dual
      ATLAS_V4_RELAY_ASSERTION_KEYS_FILE: /run/secrets/atlas_v4_relay_assertion_keys
      ATLAS_V4_REPLAY_CACHE_DIR: <chemin persistant actuel, inchangé>
    volumes:
      - type: bind
        source: <CORE_KEYS_FILE, propriétaire Core, mode 0600>
        target: /run/secrets/atlas_v4_relay_assertion_keys
        read_only: true
      - type: bind
        source: <CURSOR_FILE, propriétaire Core, mode 0600>
        target: /run/secrets/atlas_v4_cursor
        read_only: true
    entrypoint: ["/bin/sh", "-ec"]
    command:
      - 'export ATLAS_V4_CURSOR_SECRET="$$(cat /run/secrets/atlas_v4_cursor)"; exec python /usr/local/bin/atlas-core-entrypoint.py uvicorn atlas_backend.main:app --host 127.0.0.1 --port 8100'
~~~

Valider sans afficher la config fusionnée :

~~~sh
docker compose --env-file "$ATLAS_COMPOSE_ENV_FILE" \
  -f "$CODE_ROOT/compose.yaml" -f "$CODE_ROOT/compose.openclaw.yaml" \
  -f "$LIVE_COMPOSE_OVERRIDE" \
  -f "$V4_COMPOSE_OVERRIDE" config --quiet
docker compose --env-file "$ATLAS_COMPOSE_ENV_FILE" \
  -f "$CODE_ROOT/compose.yaml" -f "$CODE_ROOT/compose.openclaw.yaml" \
  -f "$LIVE_COMPOSE_OVERRIDE" \
  -f "$V4_COMPOSE_OVERRIDE" up -d --force-recreate atlas-core
~~~

up --force-recreate charge le nouvel environnement ; restart seul ne prendrait pas les paramètres Compose modifiés. Le port 4401 appartient à un processus sous scope Paseo, pas à un service Compose ou unité systemd de connecteur identifiée. **Point d’arrêt :** la commande de redémarrage du connecteur reste à confirmer par son propriétaire/opérateur Paseo ; ne pas supposer kill, systemctl restart ou docker restart.

Oracles avant requête produit : health Core 200 ; mode dual chargé ; v5/intégrité/FK ; exactement un nouveau principal, credential RELAY actif et binding ; aucune credential/binding existante changée ; keyring/cache montés et permissions/UID valides ; map parsable et credential lisible par connecteur ; connecteur relay_connected=true, V4 actif, map utilisée et binding requis ; chat V2 encore disponible. Tester le chemin fonctionnel sur copie synthétique. Toute requête live au-delà de health/métriques nécessite accord séparé : aucune route auth-only ne permet de prouver le binding sans toucher une route produit.

### Phase 2 — Application DIRECT/V4

Après B accepté et si le propriétaire veut partager le même device_ref, ajouter credential DIRECT au même principal Android. Créer un principal séparé uniquement si une isolation de données différente est voulue. La CLI est offline : refaire le snapshot préalable et arrêter Core pendant l’ajout, puis le recréer avec la même configuration dual.

~~~sh
sudo env CODE_ROOT="$CODE_ROOT" CORE_DB="$CORE_DB" PRINCIPAL_ID="$PRINCIPAL_ID" \
  PRIVATE_WORK="$PRIVATE_WORK" bash -eu <<'ROOT'
python3 "$CODE_ROOT/backend/scripts/atlas_v4_principals.py" --db "$CORE_DB" \
  credential add --principal-id "$PRINCIPAL_ID" --audience DIRECT --generate \
  --output "$PRIVATE_WORK/direct-credential" > "$PRIVATE_WORK/direct-receipt.json"
ROOT
~~~

Configurer le credential dans le canal app approuvé, sans afficher le bearer ni modifier le bearer V2. Choisir explicitement capacités d’écriture et consentements ; le Compose actuel n’active pas toutes les capacités du worker. Vérifier stockage local et sync Android V4 distincte via QA Android 17 après autorisation ciblée. CoreV4SyncWorker n’est pas qualifié tant qu’une requête séparée n’est pas observée.

### Phase 3 — Mode principal et révocation séparée

Après acceptation Relay, DIRECT et outils nécessaires : charger cursor secret stable, grants minimaux, principal/credential TOOL si OpenClaw les exige, arrêter Core pour la révocation CLI s’il y a une ligne V4 LEGACY explicitement approuvée, puis configurer principal et recréer Core. Tester refus LEGACY sur copie avant tout oracle live autorisé. Le bearer statique de /v2/agent/chat est distinct ; son remplacement/révocation n’est pas inclus implicitement.

Exemple CLI de révocation, non exécuté et seulement après accord nominatif :

~~~sh
sudo python3 "$CODE_ROOT/backend/scripts/atlas_v4_principals.py" --db "$CORE_DB" \
  credential revoke --credential-id "$APPROVED_CREDENTIAL_ID"
~~~

Le registre actuel étant vide, aucun credential LEGACY V4 n’existe à révoquer ainsi. Ne pas importer le bearer live pour créer une cible. Révocation binding/principal/credential monotone ; rollback avant révocation signifie restaurer la config, pas réactiver la ligne. Restaurer une DB ancienne exige arrêt Core, backup du nouvel état et approbation, car propositions/sync postérieures pourraient être perdues.

### Rollback et arrêts transverses

- Avant B : ne pas déployer map/env ; si Core est arrêté, relancer la configuration précédente avec :

~~~sh
docker compose --env-file "$ATLAS_COMPOSE_ENV_FILE" \
  -f "$CODE_ROOT/compose.yaml" -f "$CODE_ROOT/compose.openclaw.yaml" \
  -f "$LIVE_COMPOSE_OVERRIDE" start atlas-core
~~~

- Après B, avant révocation : arrêter le connecteur avec son contrôle Paseo vérifié, retirer l’override V4, restaurer l’env connecteur antérieur, puis recréer Core legacy :

~~~sh
docker compose --env-file "$ATLAS_COMPOSE_ENV_FILE" \
  -f "$CODE_ROOT/compose.yaml" -f "$CODE_ROOT/compose.openclaw.yaml" \
  -f "$LIVE_COMPOSE_OVERRIDE" \
  up -d --force-recreate atlas-core
~~~

Redémarrer ensuite le connecteur par le contrôle Paseo vérifié. Garder lignes et secrets dormants.
- Échec d’oracle, health, relay_connected, mode, permissions ou compteurs inattendus : arrêter phase, ne pas lancer chat/apply/reconcile, capturer uniquement état agrégé, revenir au mode précédent. Toute restauration DB est séparément approuvée.
- Phase C : restaurer config app sauvegardée, credential DIRECT dormant, bearer V2 inchangé.
- Phase D après révocation : pas de rollback direct ; nouveau principal/credential et binding demandent approbation nouvelle.

atlas_v4_expiration_reconcile.py est indépendant du binding. Sans --apply, dry-run read-only ; --apply --backup avec un chemin absolu inexistant modifie les propositions expirées. Ne pas le combiner à provisioning, cutover ou backfill.

## 6. Risques P2/P3

| Niveau | Risque / contrôle |
|---|---|
| P2 | Secret HMAC différent par key ID : celui-ci sélectionne la clé mais n’est pas signé. Overlap maximum de deux clés ; répéter rotation sur copie puis retirer l’ancienne après oracle. |
| P2 | Cache anti-rejeu correctement configuré ; préserver 0700, atomicité O_EXCL/verrous/fsync, horloge stable et un Core par volume. Erreur fail-closed peut apparaître comme 500 ; cache mémoire multi-worker à éviter. |
| P2 | Cursor secret absent : curseurs instables en dual, démarrage refusé en principal. Pas d’option fichier native ; wrapper/env à revoir pour journalisation et accès Docker. |
| P2 | Map/credential connecteur invalides désactivent V4. Fallback bearer seulement en legacy sans map ; aucune bascule silencieuse en dual. |
| P2 | Backup connecteur standard omet operations.json. Backup manuel des trois fichiers essayé sur synthétique ; restore multi-fichier non atomique et sans rollback automatique. |
| P2 | Backup B7 0600 documenté, mais revue limitée au snapshot local, sans hors-site/chiffrement attesté. Prendre snapshot frais et démontrer restore sur copie. |
| P2 | Capacités globales, grants par principal et consentements sont barrières distinctes ; sync DIRECT exige capacités d’écriture absentes du relevé. Accorder au cas par cas. |
| P2 | dual garde chat V2, mais worker V4 non observé passe par le registre et pourrait échouer. Tester sur copie, pas avec chat réel qui pourrait créer proposition. |
| P2 | Config app opaque et stockage secret non contrôlé ; aucune sync HTTP distincte prouvée. Android exige backup et QA 17 autorisée. |
| P2 | Admin, persistance et recréation Relay non qualifiées. Aucune mutation Relay dans le plan. |
| P3 | Aucun ADR propriétaire sur partage DIRECT/RELAY du device_ref, héritage des données, grants et autorité de révocation. |
| P3 | Processus connecteur sous scope Paseo sans unité de redémarrage démontrée ; documenter superviseur, env-file effectif et rollback avant promotion. |

## 7. Décisions du propriétaire

Avant toute phase live, confirmer explicitement :

1. Option autorisée A/B/C/D et fenêtre d’indisponibilité Core acceptable.
2. Partage ou séparation du device_ref entre Relay et app DIRECT ; données historiques isolées jusqu’à approbation d’une migration ownership.
3. Capabilities minimales Relay, écritures/consentements app et besoin éventuel d’un principal TOOL en B.
4. Propriétaires/distribution des secrets, rotation, UID des fichiers 0600 et accord sur l’injection du cursor via wrapper.
5. Contrôle Paseo réel de redémarrage/restauration connecteur, env-file effectif et preuve d’administration/restauration Relay exigée.
6. Backup/restore Core/connecteur répété sur copie, y compris operations.json ; lieu durable du backup privé.
7. Accord séparé pour config/install Android V4, requête Relay live, backfill, changement bearer et chaque révocation. D exige un accord nominatif distinct.

## Références de code et preuves

- Auth, kinds, audiences, grants, binding : backend/atlas_backend/v4_auth.py, v4_router.py, store.py, v4_tool_service.py.
- HMAC et cache : backend/atlas_backend/v4_assertion.py ; signature connecteur : integrations/relay/pc/src/connector.ts et backend.ts.
- Identité/registre/config : integrations/relay/pc/src/identity.ts, device-registry.ts et scripts/lib/config.mjs.
- CLI : backend/scripts/atlas_v4_principals.py ; expiration indépendante : backend/scripts/atlas_v4_expiration_reconcile.py.
- Déploiement : compose.yaml, compose.openclaw.yaml, backend/compose.env.example.
- Preuves : V7-11B_IDENTITY_BINDING_EVIDENCE.md, V7-11B_PREPARATION.md, V7-11_A1_CORE_MIGRATION_EVIDENCE.md, V7-11_A2_RELAY_REPLAY_EVIDENCE.md, V7-11B_ANDROID_POST_MIGRATION_EVIDENCE.md, V7-11B_LIVE_BACKUP_REVIEW.md et V7-11B_CONNECTOR_BACKUP_EVIDENCE.md.

**Limites :** métriques/environnement filtré utilisés, sans révéler ligne de commande ou secrets. Identité privée, contenu Core, préférences app et admin Relay non ouverts. Chat V2 et non-observation du worker viennent de la preuve post-migration, sans replay ici. Aucune suite de tests exécutée.
