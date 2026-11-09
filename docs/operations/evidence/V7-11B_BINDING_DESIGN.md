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
| Montage Core | Le conteneur live est actif ; son unique montage /data est un volume nommé qui correspond au volume Compose attendu. La base est régulière, mode 0644, propriétaire/groupe du processus Core ; user_version=5, integrity_check et foreign_key_check passent. |
| Core auth V4 | Mode legacy explicitement configuré par l’override Compose actif ; keyring d’assertion absent, ATLAS_V4_CURSOR_SECRET absent, principaux outil et legacy configurés absents. Le flag ATLAS_V4_ENABLED à 0 vu dans cet override n’est pas le mode d’authentification Core. |
| Configuration Compose Core | Les labels live indiquent trois fichiers Compose effectifs. Le troisième est un override privé hors du dépôt ; il fixe notamment le mode legacy, le flag V4 Core à 0 et le cache anti-rejeu. Son chemin et son contenu brut ne sont pas reproduits. Les exemples Compose qui omettent cet override ne sont pas sûrs pour recréer le Core live. |
| Cache anti-rejeu Core | ATLAS_V4_REPLAY_CACHE_DIR configuré ; répertoire absolu, présent, non symlink, mode 0700, propriétaire Core, dans le volume persistant /data. |
| Capacités globales Core | compose.openclaw.yaml active le sous-ensemble lecture/chat. Aucun grant principal n’existe dans principal_capabilities_v4. |
| Connecteur | V4 activé, mode legacy, ATLAS_REQUIRE_PRINCIPAL_BINDING=0, fichier bearer Android configuré. Map principal et paramètres d’assertion (key ID, fichier ou variable) absents. |
| Transport | Proxy 4400 et métriques connecteur 4401 joignables ; relevé courant : relay_connected=true, active_sessions=0. La preuve chat rapporte une session ouverte (connections_opened_total=1, sessions_opened_total=1, active_sessions=1), une requête et aucune erreur (requests_total=1, errors_total=0). |
| Identité Relay | Fichier régulier, mode 0600. Le connecteur actif a chargé une identité valide au démarrage ; son initialiseur vérifie la paire publique/privée et l’empreinte. Le fichier privé n’a pas été ouvert et les valeurs publiques ne sont pas reproduites. |
| Registre Relay | 20 devices au total ; 1 device actif, portant le libellé pixel-v7. Ce décompte permet une map complète à une entrée si l’état ne change pas. Aucun identifiant Relay n’a été relevé dans la preuve. |
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

Le cursor protège l’intégrité des curseurs paginés. En dual sans ATLAS_V4_CURSOR_SECRET, sa clé est dérivée par SHA-256 du bearer Android : elle reste stable tant que ce bearer ne change pas, mais le client qui détient le bearer peut la calculer. En principal, un secret serveur stable d’au moins 32 caractères est requis ; son absence empêche le démarrage. Décision de design : la garantie attendue est l’intégrité serveur, pas seulement la stabilité. Maintenir donc le secret serveur stable dès dual et le conserver en principal. Le code ne propose pas ATLAS_V4_CURSOR_SECRET_FILE : l’injection depuis un fichier exige un wrapper de démarrage ou un support de code.

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
test -n "$PRIVATE_WORK"
sudo test ! -e "$PRIVATE_WORK"
sudo install -d -o root -g root -m 0700 "$PRIVATE_WORK"
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

Préconditions : Phase 0 acceptée, répétition synthétique PASS, un seul appareil actif (le pixel-v7) confirmé en lecture seule, zéro binding, grants minimaux choisis. La mesure live actuelle est 1 appareil actif et 1 appareil actif pixel-v7 ; si ces ensembles divergent ou changent avant la phase, NO-GO. La map doit couvrir tous les appareils actifs concernés ; les appareils non couverts doivent être désactivés explicitement avant B. Les contrôles Paseo d’arrêt/reprise du connecteur doivent être identifiés et vérifiés avant B ; ils ne le sont pas encore, donc B est actuellement NO-GO. Garder le connecteur arrêté pendant le provisioning. Ne pas modifier le bearer Android existant. **Tous les contrôles ci-dessous précèdent l’arrêt Core.** `PRIVATE_WORK` et les répertoires secrets sont préparés en Phase 0.

Le bloc est un seul shell fail-closed. Avant toute coupure, il lit dans `docker inspect` l’environnement effectif du conteneur et le montage `/data`, exige `ATLAS_DB_PATH=/data/atlas.sqlite3` et le volume attendu, puis valide la cible hôte. Après `stop`, il réinspecte le même conteneur et refait ces comparaisons. Les contrôles de chemin utilisent `lstat` sur chaque composant et `realpath` strictement comparé au chemin lexical : tout symlink, composant non-répertoire, cible non régulière ou inode différent échoue fermé. Les commandes n’affichent aucun chemin privé, ID de conteneur, ID de device ou reçu. Si une précondition échoue avant `stop`, Core reste en marche et le connecteur reste arrêté. Après `stop`, le trap recrée le Core avec les fichiers Compose legacy précédents sur toute sortie/erreur avant validation complète du chemin dual. Le trap ne peut arrêter le connecteur sans son contrôle Paseo : le garder arrêté pendant tout le rollback et rendre B NO-GO tant que le contrôle d’arrêt vérifié manque.

**Oracle read-only et mutations :** `run_cli_readonly` conserve l’égalité stricte du fingerprint complet avant/après `preview-migration` : identité conteneur/montage/chemin effectif, contrôles `lstat`/`realpath`, `(st_dev, st_ino)`, UID/GID/mode, `user_version=5`, `integrity_check=ok`, `foreign_key_check` vide, hash SHA-256 du fichier et snapshot logique de toutes les tables. Le snapshot logique canonise chaque valeur avec son type (NULL, entier, réel, texte, blob), trie les colonnes par ordre de schéma et les lignes par encodage canonique ; noms et définitions de tables restent identiques.

`run_cli_mutating` prend un snapshot logique complet avant et après chaque CLI. Il exige avant/après la même identité de conteneur, montage `/data`, type/nom/source de volume, `ATLAS_DB_PATH` effectif et chemin hôte, aucun symlink, le même `(st_dev, st_ino)`, UID/GID/mode inchangés, `user_version=5`, intégrité `ok` et aucune violation FK. Il compare les multisets de lignes canoniques de **toutes** les tables : suppressions, changements de schéma et toute variation hors delta attendu font échouer la séquence. Les tables hors registre, dont propositions, consentements et événements, doivent rester strictement inchangées. Le snapshot est en mémoire ou dans un fichier temporaire privé `0600`, supprimé après comparaison ; aucun contenu, reçu ni bearer n’est affiché.

| CLI | Delta logique exact accepté |
|---|---|
| `preview-migration` | Aucune ligne ni définition logique modifiée ; fingerprint complet, hash compris, identique. |
| `principal add` | `principals_v4` : une insertion ANDROID/ACTIVE, `device_ref` non vide et unique ; `created_at` ISO cohérent et égal à l’horodatage de l’événement. `principal_events_v4` : une insertion `PROVISIONED` liée au principal. Le reçu JSON doit identifier exactement ces `principal_id` et `device_ref`. |
| `credential add` | `credentials_v4` : une insertion ACTIVE liée au principal actif, audience RELAY, algorithme SHA-256 et `token_hash` égal au SHA-256 du bearer généré lu en mémoire depuis le fichier privé. `principal_events_v4` : une insertion `PROVISIONED` liée au principal/credential, metadata RELAY et horodatage cohérent. Le bearer n’est jamais imprimé. |
| `binding add` | `relay_principal_bindings_v4` : une insertion ACTIVE liée au principal et au relay device. `principal_events_v4` : une insertion `RELAY_BOUND` cohérente avec le binding et son horodatage. |
| `capability set` | `principal_capabilities_v4` : une ligne insérée ou mise à jour à `granted=1` pour le couple principal/capability ; `principal_events_v4` : un événement `CAPABILITY_CHANGED` cohérent. Si la capability était déjà accordée, seul un no-op exact (aucun delta logique) est accepté. |

Tout autre ajout, retrait ou modification, y compris une ligne préexistante modifiée/supprimée, déclenche le trap legacy. Le SHA-256 global sert aux backups et aux étapes read-only ; il n’est jamais comparé autour d’une mutation SQLite attendue. Cet oracle est défini ici et doit passer une répétition synthétique avant toute phase live. B reste NO-GO pour ses autres prérequis : contrôle Paseo du connecteur, backup/restore vérifiés, répétition et autorisation propriétaire.

~~~sh
set -euo pipefail
set +x
: "${CODE_ROOT:?checkout principal requis}"
: "${ATLAS_COMPOSE_ENV_FILE:?env-file Compose requis}"
: "${LIVE_COMPOSE_OVERRIDE:?override live requis}"
: "${V4_COMPOSE_OVERRIDE:?override V4 préparé en privé requis}"
: "${EXPECTED_CORE_VOLUME:?volume déclaré par le Compose vérifié requis}"
: "${CORE_UID:?UID Core requis}"
: "${CORE_GID:?GID Core requis}"
: "${CORE_KEYS_FILE:?keyring Core requis}"
: "${CURSOR_FILE:?cursor secret serveur requis}"
: "${CONNECTOR_ASSERTION_FILE:?clé connecteur requise}"
: "${CORE_REPLAY_CACHE_DIR:?cache anti-rejeu requis}"
: "${PRIVATE_WORK:?dossier de reçus privé requis}"
: "${CONNECTOR_UID:?UID connecteur requis}"
: "${CONNECTOR_GID:?GID connecteur requis}"
: "${CONNECTOR_STATE_DIR:?registre connecteur requis}"
: "${CONNECTOR_SECRET_DIR:?dossier credential connecteur requis}"
: "${CONNECTOR_DUAL_ENV_FILE:?env-file dual connecteur vérifié requis}"
: "${CONNECTOR_MAP_FILE:?chemin map requis}"
: "${APPROVED_RELAY_CAPABILITY:?capability approuvée requise}"
CORE_CONTAINER=atlas-core-atlas-core-1
EXPECTED_DB_MODE=644

test -f "$CODE_ROOT/compose.yaml"
test ! -L "$CODE_ROOT/compose.yaml"
test -f "$CODE_ROOT/compose.openclaw.yaml"
test ! -L "$CODE_ROOT/compose.openclaw.yaml"
test -f "$ATLAS_COMPOSE_ENV_FILE"
test ! -L "$ATLAS_COMPOSE_ENV_FILE"
test -f "$LIVE_COMPOSE_OVERRIDE"
test ! -L "$LIVE_COMPOSE_OVERRIDE"
test -f "$V4_COMPOSE_OVERRIDE"
test ! -L "$V4_COMPOSE_OVERRIDE"
test -f "$CONNECTOR_DUAL_ENV_FILE"
test ! -L "$CONNECTOR_DUAL_ENV_FILE"
sudo test -d "$PRIVATE_WORK"
sudo test ! -L "$PRIVATE_WORK"
sudo test -d "$CONNECTOR_STATE_DIR"
sudo test ! -L "$CONNECTOR_STATE_DIR"
sudo test -f "$CONNECTOR_STATE_DIR/devices.json"
sudo test ! -L "$CONNECTOR_STATE_DIR/devices.json"
sudo test -d "$CONNECTOR_SECRET_DIR"
sudo test ! -L "$CONNECTOR_SECRET_DIR"
sudo test -d "$(dirname -- "$CONNECTOR_MAP_FILE")"
sudo test ! -L "$(dirname -- "$CONNECTOR_MAP_FILE")"
sudo test ! -e "$CONNECTOR_MAP_FILE"
sudo test ! -L "$CONNECTOR_MAP_FILE"
sudo test ! -e "$CONNECTOR_SECRET_DIR/relay-credential"
sudo test ! -L "$CONNECTOR_SECRET_DIR/relay-credential"
sudo test -f "$CORE_KEYS_FILE"
sudo test ! -L "$CORE_KEYS_FILE"
sudo test -f "$CURSOR_FILE"
sudo test ! -L "$CURSOR_FILE"
sudo test -f "$CONNECTOR_ASSERTION_FILE"
sudo test ! -L "$CONNECTOR_ASSERTION_FILE"
sudo test -d "$CORE_REPLAY_CACHE_DIR"
sudo test ! -L "$CORE_REPLAY_CACHE_DIR"
[[ "$CORE_UID" =~ ^[0-9]+$ && "$CORE_GID" =~ ^[0-9]+$ ]]
[[ "$CONNECTOR_UID" =~ ^[0-9]+$ && "$CONNECTOR_GID" =~ ^[0-9]+$ ]]

check_owner_mode() {
  local target="$1" expected="$2" actual
  actual="$(sudo stat -Lc '%u:%g:%a' -- "$target" 2>/dev/null)"
  test "$actual" = "$expected"
}
check_owner_mode "$PRIVATE_WORK" "0:0:700"
check_owner_mode "$CONNECTOR_STATE_DIR" "$CONNECTOR_UID:$CONNECTOR_GID:700"
check_owner_mode "$CONNECTOR_STATE_DIR/devices.json" "$CONNECTOR_UID:$CONNECTOR_GID:600"
check_owner_mode "$CONNECTOR_SECRET_DIR" "$CONNECTOR_UID:$CONNECTOR_GID:700"
check_owner_mode "$CONNECTOR_ASSERTION_FILE" "$CONNECTOR_UID:$CONNECTOR_GID:600"
check_owner_mode "$CONNECTOR_DUAL_ENV_FILE" "$CONNECTOR_UID:$CONNECTOR_GID:600"
check_owner_mode "$CORE_KEYS_FILE" "$CORE_UID:$CORE_GID:600"
check_owner_mode "$CURSOR_FILE" "$CORE_UID:$CORE_GID:600"
check_owner_mode "$CORE_REPLAY_CACHE_DIR" "$CORE_UID:$CORE_GID:700"
check_owner_mode "$(dirname -- "$CONNECTOR_MAP_FILE")" "$CONNECTOR_UID:$CONNECTOR_GID:700"

CORE_CONFIG_USER="$(docker inspect --format '{{.Config.User}}' "$CORE_CONTAINER" 2>/dev/null)"
test "${CORE_CONFIG_USER%%:*}" = "$CORE_UID"
if [[ "$CORE_CONFIG_USER" == *:* ]]; then
  test "${CORE_CONFIG_USER#*:}" = "$CORE_GID"
fi
test "$(docker inspect --format '{{.State.Running}}' "$CORE_CONTAINER" 2>/dev/null)" = true
CORE_CONTAINER_ID="$(docker inspect --format '{{.Id}}' "$CORE_CONTAINER" 2>/dev/null)"
test -n "$CORE_CONTAINER_ID"

sudo python3 - "$CONNECTOR_STATE_DIR/devices.json" <<'PY'
import json, sys
try:
    devices=json.load(open(sys.argv[1], encoding="utf-8"))["devices"]
    active=[d for d in devices if d.get("revoked_at") is None]
    target=[d for d in active if d.get("label")=="pixel-v7"]
    if len(active)!=1 or len(target)!=1:
        raise ValueError
except Exception:
    raise SystemExit("active-device coverage preflight failed")
PY
python3 - <<'PY'
import socket, sys
s=socket.socket()
s.settimeout(1)
if s.connect_ex(("127.0.0.1",4401)) == 0:
    raise SystemExit("connector must be stopped before Core transition")
PY

resolve_core_db() {
  python3 - "$CORE_CONTAINER" "$EXPECTED_CORE_VOLUME" "$1" <<'PY'
import json, subprocess, sys
container, expected_volume, expected_state=sys.argv[1:]
def run(args):
    return subprocess.check_output(args, text=True, stderr=subprocess.DEVNULL).strip()
try:
    env=json.loads(run(["docker","inspect","--format","{{json .Config.Env}}",container]))
    mounts=json.loads(run(["docker","inspect","--format","{{json .Mounts}}",container]))
    running=run(["docker","inspect","--format","{{.State.Running}}",container]) == "true"
    container_id=run(["docker","inspect","--format","{{.Id}}",container])
    db_paths=[item.split("=",1)[1] for item in env if item.startswith("ATLAS_DB_PATH=")]
    expected_source=run(["docker","volume","inspect","--format","{{.Mountpoint}}",expected_volume])
    data=[m for m in mounts if m.get("Destination")=="/data"]
    if len(db_paths)!=1 or db_paths[0]!="/data/atlas.sqlite3": raise ValueError
    if len(data)!=1 or data[0].get("Type")!="volume": raise ValueError
    if data[0].get("Name")!=expected_volume or data[0].get("Source")!=expected_source: raise ValueError
    if running != (expected_state=="running"): raise ValueError
    print(json.dumps({"id":container_id,"source":data[0]["Source"],
                      "db":data[0]["Source"].rstrip("/")+"/atlas.sqlite3"},separators=(",",":")))
except Exception:
    raise SystemExit("effective Core DB path or /data volume comparison failed")
PY
}
CORE_TARGET_BEFORE="$(resolve_core_db running)"
CORE_CONTAINER_ID="$(python3 -c 'import json,sys; print(json.loads(sys.argv[1])["id"])' "$CORE_TARGET_BEFORE")"
CORE_MOUNT_SOURCE="$(python3 -c 'import json,sys; print(json.loads(sys.argv[1])["source"])' "$CORE_TARGET_BEFORE")"
CORE_DB="$(python3 -c 'import json,sys; print(json.loads(sys.argv[1])["db"])' "$CORE_TARGET_BEFORE")"
test -n "$CORE_CONTAINER_ID"
safe_db_fingerprint() {
  sudo python3 - "$CORE_DB" "$CORE_MOUNT_SOURCE/atlas.sqlite3" "$CORE_UID" "$CORE_GID" "$EXPECTED_DB_MODE" <<'PY'
import hashlib, os, stat, sys
target, mounted, uid, gid, mode=sys.argv[1:]
def inspect(path):
    if not os.path.isabs(path) or os.path.normpath(path)!=path: raise ValueError
    parts=[p for p in path.split("/") if p]
    if not parts: raise ValueError
    nofollow=getattr(os,"O_NOFOLLOW",0)
    directory=getattr(os,"O_DIRECTORY",0)
    dirfd=os.open("/",os.O_RDONLY|directory|nofollow)
    try:
        current="/"
        for part in parts[:-1]:
            current=os.path.join(current,part)
            item=os.lstat(current)
            if stat.S_ISLNK(item.st_mode) or not stat.S_ISDIR(item.st_mode): raise ValueError
            nextfd=os.open(part,os.O_RDONLY|directory|nofollow,dir_fd=dirfd)
            opened_dir=os.fstat(nextfd)
            if (item.st_dev,item.st_ino)!=(opened_dir.st_dev,opened_dir.st_ino):
                os.close(nextfd)
                raise ValueError
            os.close(dirfd)
            dirfd=nextfd
        leaf=parts[-1]
        before=os.lstat(path)
        if stat.S_ISLNK(before.st_mode) or not stat.S_ISREG(before.st_mode): raise ValueError
        fd=os.open(leaf,os.O_RDONLY|nofollow,dir_fd=dirfd)
        try:
            opened=os.fstat(fd)
            if not stat.S_ISREG(opened.st_mode): raise ValueError
            if (before.st_dev,before.st_ino)!=(opened.st_dev,opened.st_ino): raise ValueError
            digest=hashlib.sha256()
            while True:
                block=os.read(fd,1024*1024)
                if not block: break
                digest.update(block)
            after=os.lstat(path)
            if (after.st_dev,after.st_ino)!=(opened.st_dev,opened.st_ino): raise ValueError
            if os.path.realpath(path,strict=True)!=path: raise ValueError
            return opened,digest.hexdigest()
        finally:
            os.close(fd)
    finally:
        os.close(dirfd)
try:
    a,ha=inspect(target)
    b,hb=inspect(mounted)
    assert (a.st_dev,a.st_ino)==(b.st_dev,b.st_ino)
    assert ha==hb
    assert a.st_uid==int(uid) and a.st_gid==int(gid)
    assert format(stat.S_IMODE(a.st_mode),"o")==mode
    print(f"{a.st_dev}:{a.st_ino}:{a.st_uid}:{a.st_gid}:{format(stat.S_IMODE(a.st_mode),'o')}:{ha}")
except Exception:
    raise SystemExit("SQLite target path, inode, owner, mode, or hash check failed")
PY
}
PRE_STOP_META="$(safe_db_fingerprint)"
sudo python3 - "$CORE_DB" <<'PY'
import os, sqlite3, stat, sys
from urllib.parse import quote
try:
    s=os.lstat(sys.argv[1])
    assert not stat.S_ISLNK(s.st_mode) and stat.S_ISREG(s.st_mode)
    db=sqlite3.connect("file:"+quote(sys.argv[1],safe="/")+"?mode=ro",uri=True)
    db.execute("PRAGMA query_only=ON")
    assert db.execute("PRAGMA user_version").fetchone()[0]==5
    assert db.execute("PRAGMA integrity_check").fetchone()[0]=="ok"
    assert not db.execute("PRAGMA foreign_key_check").fetchall()
    assert db.execute("SELECT count(*) FROM principals_v4").fetchone()[0]==0
    assert db.execute("SELECT count(*) FROM credentials_v4").fetchone()[0]==0
    assert db.execute("SELECT count(*) FROM relay_principal_bindings_v4").fetchone()[0]==0
    db.close()
except Exception:
    raise SystemExit("Core registry preflight failed")
PY
docker compose --env-file "$ATLAS_COMPOSE_ENV_FILE" \
  -f "$CODE_ROOT/compose.yaml" -f "$CODE_ROOT/compose.openclaw.yaml" \
  -f "$LIVE_COMPOSE_OVERRIDE" -f "$V4_COMPOSE_OVERRIDE" config --quiet >/dev/null 2>&1
test -z "$(sudo find "$PRIVATE_WORK" -mindepth 1 -maxdepth 1 -print -quit 2>/dev/null)"

CORE_ROLLBACK_ARMED=0
DUAL_ACTIVE=0
restore_legacy_core() {
  local exit_status=$?
  trap - EXIT
  if [[ "$CORE_ROLLBACK_ARMED" == 1 && "$DUAL_ACTIVE" == 0 ]]; then
    if ! docker compose --env-file "$ATLAS_COMPOSE_ENV_FILE" \
      -f "$CODE_ROOT/compose.yaml" -f "$CODE_ROOT/compose.openclaw.yaml" \
      -f "$LIVE_COMPOSE_OVERRIDE" up -d --force-recreate atlas-core >/dev/null 2>&1; then
      printf '%s\n' 'Legacy Core recovery failed; keep the connector stopped and require operator recovery.' >&2
      return 1
    fi
    if [[ "$(docker inspect --format '{{.State.Running}}' "$CORE_CONTAINER" 2>/dev/null || true)" != true ]]; then
      printf '%s\n' 'Legacy Core did not return to running; keep the connector stopped and require operator recovery.' >&2
      return 1
    fi
  fi
  return "$exit_status"
}
trap restore_legacy_core EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
CORE_ROLLBACK_ARMED=1
docker compose --env-file "$ATLAS_COMPOSE_ENV_FILE" \
  -f "$CODE_ROOT/compose.yaml" -f "$CODE_ROOT/compose.openclaw.yaml" \
  -f "$LIVE_COMPOSE_OVERRIDE" stop atlas-core >/dev/null 2>&1

test "$(docker inspect --format '{{.Id}}' "$CORE_CONTAINER" 2>/dev/null)" = "$CORE_CONTAINER_ID"
test "$(docker inspect --format '{{.State.Running}}' "$CORE_CONTAINER" 2>/dev/null)" = false
CORE_TARGET_AFTER="$(resolve_core_db stopped)"
test "$(python3 -c 'import json,sys; print(json.loads(sys.argv[1])["id"])' "$CORE_TARGET_AFTER")" = "$CORE_CONTAINER_ID"
test "$(python3 -c 'import json,sys; print(json.loads(sys.argv[1])["source"])' "$CORE_TARGET_AFTER")" = "$CORE_MOUNT_SOURCE"
CORE_DB_AFTER_STOP="$(python3 -c 'import json,sys; print(json.loads(sys.argv[1])["db"])' "$CORE_TARGET_AFTER")"
test "$CORE_DB_AFTER_STOP" = "$CORE_DB"
POST_STOP_META="$(safe_db_fingerprint)"
test "${POST_STOP_META%:*}" = "${PRE_STOP_META%:*}"
sudo python3 - "$CORE_DB_AFTER_STOP" <<'PY'
import os, sqlite3, stat, sys
from urllib.parse import quote
path=sys.argv[1]
try:
    s=os.lstat(path)
    assert not stat.S_ISLNK(s.st_mode) and stat.S_ISREG(s.st_mode)
    db=sqlite3.connect("file:"+quote(path,safe="/")+"?mode=ro",uri=True)
    db.execute("PRAGMA query_only=ON")
    assert db.execute("PRAGMA user_version").fetchone()[0]==5
    assert db.execute("PRAGMA integrity_check").fetchone()[0]=="ok"
    assert not db.execute("PRAGMA foreign_key_check").fetchall()
    assert db.execute("SELECT count(*) FROM principals_v4").fetchone()[0]==0
    assert db.execute("SELECT count(*) FROM credentials_v4").fetchone()[0]==0
    assert db.execute("SELECT count(*) FROM relay_principal_bindings_v4").fetchone()[0]==0
    db.close()
except Exception:
    raise SystemExit("stopped Core database verification failed")
PY

sudo env CODE_ROOT="$CODE_ROOT" CORE_DB="$CORE_DB_AFTER_STOP" CORE_MOUNT_SOURCE="$CORE_MOUNT_SOURCE" \
  CORE_UID="$CORE_UID" CORE_GID="$CORE_GID" EXPECTED_DB_MODE="$EXPECTED_DB_MODE" \
  CORE_CONTAINER="$CORE_CONTAINER" CORE_CONTAINER_ID="$CORE_CONTAINER_ID" \
  EXPECTED_CORE_VOLUME="$EXPECTED_CORE_VOLUME" \
  PRIVATE_WORK="$PRIVATE_WORK" \
  CONNECTOR_UID="$CONNECTOR_UID" CONNECTOR_GID="$CONNECTOR_GID" \
  CONNECTOR_SECRET_DIR="$CONNECTOR_SECRET_DIR" CONNECTOR_MAP_FILE="$CONNECTOR_MAP_FILE" \
  CONNECTOR_STATE_DIR="$CONNECTOR_STATE_DIR" \
  APPROVED_RELAY_CAPABILITY="$APPROVED_RELAY_CAPABILITY" bash -euo pipefail <<'ROOT'
umask 077
db_fingerprint() {
  python3 - "$1" "$CORE_DB" "$CORE_MOUNT_SOURCE/atlas.sqlite3" "$CORE_UID" "$CORE_GID" "$EXPECTED_DB_MODE" <<'PY'
import hashlib, os, stat, sys
kind, target, mounted, uid, gid, mode=sys.argv[1:]
def inspect(path, include_hash):
    if not os.path.isabs(path) or os.path.normpath(path)!=path: raise ValueError
    parts=[p for p in path.split("/") if p]
    if not parts: raise ValueError
    nofollow=getattr(os,"O_NOFOLLOW",0)
    directory=getattr(os,"O_DIRECTORY",0)
    dirfd=os.open("/",os.O_RDONLY|directory|nofollow)
    try:
        current="/"
        for part in parts[:-1]:
            current=os.path.join(current,part)
            item=os.lstat(current)
            if stat.S_ISLNK(item.st_mode) or not stat.S_ISDIR(item.st_mode): raise ValueError
            nextfd=os.open(part,os.O_RDONLY|directory|nofollow,dir_fd=dirfd)
            opened_dir=os.fstat(nextfd)
            if (item.st_dev,item.st_ino)!=(opened_dir.st_dev,opened_dir.st_ino):
                os.close(nextfd)
                raise ValueError
            os.close(dirfd)
            dirfd=nextfd
        leaf=parts[-1]
        before=os.lstat(path)
        if stat.S_ISLNK(before.st_mode) or not stat.S_ISREG(before.st_mode): raise ValueError
        fd=os.open(leaf,os.O_RDONLY|nofollow,dir_fd=dirfd)
        try:
            opened=os.fstat(fd)
            if not stat.S_ISREG(opened.st_mode) or (before.st_dev,before.st_ino)!=(opened.st_dev,opened.st_ino): raise ValueError
            digest=hashlib.sha256() if include_hash else None
            if include_hash:
                while True:
                    block=os.read(fd,1024*1024)
                    if not block: break
                    digest.update(block)
            after=os.lstat(path)
            if (after.st_dev,after.st_ino)!=(opened.st_dev,opened.st_ino): raise ValueError
            if os.path.realpath(path,strict=True)!=path: raise ValueError
            return opened,digest.hexdigest() if digest is not None else None
        finally:
            os.close(fd)
    finally:
        os.close(dirfd)
try:
    a,ha=inspect(target,kind=="full"); b,hb=inspect(mounted,kind=="full")
    assert (a.st_dev,a.st_ino)==(b.st_dev,b.st_ino)
    if kind=="full": assert ha==hb
    assert a.st_uid==int(uid) and a.st_gid==int(gid)
    assert format(stat.S_IMODE(a.st_mode),"o")==mode
    result=f"{a.st_dev}:{a.st_ino}:{a.st_uid}:{a.st_gid}:{format(stat.S_IMODE(a.st_mode),'o')}"
    print(result+f":{ha}" if kind=="full" else result)
except Exception:
    raise SystemExit("SQLite CLI target hash/inode check failed")
PY
}
target_identity() {
  python3 - "$CORE_CONTAINER" "$EXPECTED_CORE_VOLUME" "$CORE_CONTAINER_ID" \
    "$CORE_MOUNT_SOURCE" "$CORE_DB" <<'PY'
import json, subprocess, sys
container, volume, pinned_id, pinned_source, pinned_db=sys.argv[1:]
def run(args):
    return subprocess.check_output(args,text=True,stderr=subprocess.DEVNULL).strip()
try:
    env=json.loads(run(["docker","inspect","--format","{{json .Config.Env}}",container]))
    mounts=json.loads(run(["docker","inspect","--format","{{json .Mounts}}",container]))
    running=run(["docker","inspect","--format","{{.State.Running}}",container])
    cid=run(["docker","inspect","--format","{{.Id}}",container])
    paths=[x.split("=",1)[1] for x in env if x.startswith("ATLAS_DB_PATH=")]
    source=run(["docker","volume","inspect","--format","{{.Mountpoint}}",volume])
    data=[m for m in mounts if m.get("Destination")=="/data"]
    if running!="false" or cid!=pinned_id or len(paths)!=1 or paths[0]!="/data/atlas.sqlite3": raise ValueError
    if len(data)!=1 or data[0].get("Type")!="volume" or data[0].get("Name")!=volume: raise ValueError
    if data[0].get("Source")!=source or source!=pinned_source: raise ValueError
    if source.rstrip("/")+"/atlas.sqlite3"!=pinned_db: raise ValueError
    print("OK")
except Exception:
    raise SystemExit("stopped container/mount/path identity changed")
PY
}
db_health() {
  python3 - "$CORE_DB" <<'PY'
import sqlite3, sys
from urllib.parse import quote
try:
    db=sqlite3.connect("file:"+quote(sys.argv[1],safe="/")+"?mode=ro",uri=True)
    db.execute("PRAGMA query_only=ON")
    assert db.execute("PRAGMA user_version").fetchone()[0]==5
    assert db.execute("PRAGMA integrity_check").fetchone()[0]=="ok"
    assert not db.execute("PRAGMA foreign_key_check").fetchall()
    db.close()
    print("OK")
except Exception:
    raise SystemExit("SQLite version/integrity/foreign-key check failed")
PY
}
ORACLE_BEFORE=""
ORACLE_AFTER=""
cleanup_oracle_snapshots() {
  [[ -z "$ORACLE_BEFORE" ]] || rm -f -- "$ORACLE_BEFORE"
  [[ -z "$ORACLE_AFTER" ]] || rm -f -- "$ORACLE_AFTER"
}
trap cleanup_oracle_snapshots EXIT
new_oracle_snapshots() {
  umask 077
  ORACLE_BEFORE="$(mktemp "$PRIVATE_WORK/.logical-before.XXXXXX")"
  ORACLE_AFTER="$(mktemp "$PRIVATE_WORK/.logical-after.XXXXXX")"
  chmod 0600 "$ORACLE_BEFORE" "$ORACLE_AFTER"
}
logical_snapshot() {
  python3 - "$CORE_DB" <<'PY'
import base64, json, sqlite3, sys
from urllib.parse import quote
def encode(value):
    if value is None: return ["null", None]
    if isinstance(value, int): return ["integer", str(value)]
    if isinstance(value, float): return ["real", value.hex()]
    if isinstance(value, bytes): return ["blob", base64.b64encode(value).decode("ascii")]
    if isinstance(value, str): return ["text", value]
    raise TypeError
try:
    db=sqlite3.connect("file:"+quote(sys.argv[1],safe="/")+"?mode=ro",uri=True)
    db.execute("PRAGMA query_only=ON")
    schema=[[encode(v) for v in row] for row in db.execute(
        "SELECT type,name,tbl_name,sql FROM sqlite_master ORDER BY type,name,tbl_name")]
    tables={}
    for (name,) in db.execute("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name"):
        quoted='"'+name.replace('"','""')+'"'
        cursor=db.execute("SELECT * FROM "+quoted)
        columns=[item[0] for item in cursor.description]
        rows=[[encode(v) for v in row] for row in cursor.fetchall()]
        rows.sort(key=lambda row: json.dumps(row,sort_keys=True,separators=(",",":"),ensure_ascii=True))
        tables[name]={"columns":columns,"rows":rows}
    db.close()
    print(json.dumps({"schema":schema,"tables":tables},sort_keys=True,separators=(",",":"),ensure_ascii=True))
except Exception:
    raise SystemExit("canonical logical SQLite snapshot failed")
PY
}
assert_exact_logical_delta() {
  local operation="$1" before_file="$2" after_file="$3" principal_id="$4"
  local relay_device_id="$5" capability="$6" token_file="$7"
  python3 - "$operation" "$before_file" "$after_file" "$PRIVATE_WORK" \
    "$principal_id" "$relay_device_id" "$capability" "$token_file" <<'PY'
import base64, collections, datetime, hashlib, json, pathlib, sys
op,before_file,after_file,work,principal_id,relay_id,capability,token_file=sys.argv[1:]
def decode(value):
    kind,data=value
    if kind=="null": return None
    if kind=="integer": return int(data)
    if kind=="real": return float.fromhex(data)
    if kind=="blob": return base64.b64decode(data)
    if kind=="text": return data
    raise ValueError
def canon(row): return json.dumps(row,sort_keys=True,separators=(",",":"),ensure_ascii=True)
def rows(snapshot,table):
    entry=snapshot["tables"][table]
    return [dict(zip(entry["columns"],[decode(v) for v in row])) for row in entry["rows"]]
def time_ok(value):
    parsed=datetime.datetime.fromisoformat(value.replace("Z","+00:00"))
    return parsed.tzinfo is not None
def require(condition):
    if not condition: raise ValueError
try:
    before=json.load(open(before_file,encoding="utf-8"))
    after=json.load(open(after_file,encoding="utf-8"))
    require(before["schema"]==after["schema"])
    require(set(before["tables"])==set(after["tables"]))
    changes={}
    for table in before["tables"]:
        left=before["tables"][table]; right=after["tables"][table]
        require(left["columns"]==right["columns"])
        old=collections.Counter(canon(row) for row in left["rows"])
        new=collections.Counter(canon(row) for row in right["rows"])
        removed_keys=list((old-new).elements()); added_keys=list((new-old).elements())
        if removed_keys or added_keys:
            old_rows={canon(row):row for row in left["rows"]}
            new_rows={canon(row):row for row in right["rows"]}
            changes[table]={"removed":[dict(zip(left["columns"],[decode(v) for v in old_rows[k]])) for k in removed_keys],
                            "added":[dict(zip(right["columns"],[decode(v) for v in new_rows[k]])) for k in added_keys]}
    receipt_names={"principal_add":"principal.json","credential_add":"credential-receipt.json",
                   "binding_add":"binding-receipt.json","capability_set":"capability-receipt.json"}
    if op=="principal_add":
        receipt=json.load(open(pathlib.Path(work)/receipt_names[op],encoding="utf-8"))
        changed={"principals_v4","principal_events_v4"}
        require(set(changes)==changed)
        p=changes["principals_v4"]; require(len(p["added"])==1 and not p["removed"])
        row=p["added"][0]; pid=receipt["principal_id"]; ref=receipt["device_ref"]
        require(receipt["kind"]=="ANDROID" and row["principal_id"]==pid and row["device_ref"]==ref)
        require(bool(ref) and row["kind"]=="ANDROID" and row["state"]=="ACTIVE" and row["revoked_at"] is None)
        require(sum(r["device_ref"]==ref for r in rows(after,"principals_v4"))==1)
        ev=changes["principal_events_v4"]; require(len(ev["added"])==1 and not ev["removed"])
        event=ev["added"][0]
        require(event["principal_id"]==pid and event["credential_id"] is None and event["event_type"]=="PROVISIONED")
        require(json.loads(event["metadata_json"])=={"kind":"ANDROID"})
        require(row["created_at"]==event["occurred_at"] and time_ok(row["created_at"]))
    elif op=="credential_add":
        receipt=json.load(open(pathlib.Path(work)/receipt_names[op],encoding="utf-8"))
        token=pathlib.Path(token_file).read_bytes().rstrip(b"\n")
        require(token and b"\n" not in token)
        digest=hashlib.sha256(token).hexdigest()
        require(receipt["principal_id"]==principal_id and receipt["audience"]=="RELAY" and receipt["status"]=="ACTIVE")
        require(set(changes)=={"credentials_v4","principal_events_v4"})
        delta=changes["credentials_v4"]; require(len(delta["added"])==1 and not delta["removed"])
        row=delta["added"][0]
        require(row["credential_id"]==receipt["credential_id"] and row["principal_id"]==principal_id)
        require(row["token_hash"]==digest and row["hash_algorithm"]=="SHA-256" and row["audience"]=="RELAY")
        require(row["state"]=="ACTIVE" and row["revoked_at"] is None and row["replaced_by"] is None)
        require(any(p["principal_id"]==principal_id and p["state"]=="ACTIVE" for p in rows(after,"principals_v4")))
        ev=changes["principal_events_v4"]; require(len(ev["added"])==1 and not ev["removed"])
        event=ev["added"][0]
        require(event["principal_id"]==principal_id and event["credential_id"]==row["credential_id"] and event["event_type"]=="PROVISIONED")
        require(json.loads(event["metadata_json"])=={"audience":"RELAY"})
        require(row["created_at"]==event["occurred_at"] and time_ok(row["created_at"]))
    elif op=="binding_add":
        receipt=json.load(open(pathlib.Path(work)/receipt_names[op],encoding="utf-8"))
        require(receipt["relay_device_id"]==relay_id and receipt["principal_id"]==principal_id and receipt["state"]=="ACTIVE")
        require(set(changes)=={"relay_principal_bindings_v4","principal_events_v4"})
        delta=changes["relay_principal_bindings_v4"]; require(len(delta["added"])==1 and not delta["removed"])
        row=delta["added"][0]
        require(row["relay_device_id"]==relay_id and row["principal_id"]==principal_id and row["state"]=="ACTIVE" and row["revoked_at"] is None)
        ev=changes["principal_events_v4"]; require(len(ev["added"])==1 and not ev["removed"])
        event=ev["added"][0]; metadata=json.loads(event["metadata_json"])
        require(event["principal_id"]==principal_id and event["credential_id"] is None and event["event_type"]=="RELAY_BOUND")
        require(metadata=={"relay_device_id":relay_id,"binding_id":row["event_id"]})
        require(row["created_at"]==event["occurred_at"] and time_ok(row["created_at"]))
    elif op=="capability_set":
        receipt=json.load(open(pathlib.Path(work)/receipt_names[op],encoding="utf-8"))
        require(receipt["principal_id"]==principal_id and receipt["capability"]==capability and receipt["granted"] is True)
        old=[r for r in rows(before,"principal_capabilities_v4") if r["principal_id"]==principal_id and r["capability"]==capability]
        new=[r for r in rows(after,"principal_capabilities_v4") if r["principal_id"]==principal_id and r["capability"]==capability]
        require(len(old)<=1 and len(new)==1 and new[0]["granted"]==1)
        if old and old[0]["granted"]==1:
            require(not changes)
        else:
            require(set(changes)=={"principal_capabilities_v4","principal_events_v4"})
            delta=changes["principal_capabilities_v4"]
            require(delta["added"]==new and delta["removed"]==old)
            ev=changes["principal_events_v4"]; require(len(ev["added"])==1 and not ev["removed"])
            event=ev["added"][0]
            require(event["principal_id"]==principal_id and event["credential_id"] is None and event["event_type"]=="CAPABILITY_CHANGED")
            require(json.loads(event["metadata_json"])=={"capability":capability,"granted":True})
            require(new[0]["updated_at"]==event["occurred_at"] and time_ok(new[0]["updated_at"]))
    else:
        raise ValueError
except Exception:
    raise SystemExit("exact logical SQLite delta oracle failed")
PY
}
run_cli_readonly() {
  local before_meta before_health after_meta after_health
  target_identity >/dev/null
  before_meta="$(db_fingerprint full)"
  before_health="$(db_health)"
  new_oracle_snapshots
  logical_snapshot > "$ORACLE_BEFORE"
  "$@"
  target_identity >/dev/null
  after_meta="$(db_fingerprint full)"
  after_health="$(db_health)"
  logical_snapshot > "$ORACLE_AFTER"
  if [[ "$after_meta" != "$before_meta" || "$after_health" != "$before_health" ]] || \
     ! cmp -s -- "$ORACLE_BEFORE" "$ORACLE_AFTER"; then
    printf '%s\n' 'Read-only CLI changed target fingerprint or logical rows; abort.' >&2
    return 74
  fi
  cleanup_oracle_snapshots
  ORACLE_BEFORE="" ORACLE_AFTER=""
}
run_cli_mutating() {
  local operation="$1" principal_id="$2" relay_device_id="$3" capability="$4" token_file="$5"
  shift 5
  target_identity >/dev/null
  local before_meta before_health after_meta after_health
  before_meta="$(db_fingerprint identity)"
  before_health="$(db_health)"
  new_oracle_snapshots
  logical_snapshot > "$ORACLE_BEFORE"
  "$@"
  target_identity >/dev/null
  after_meta="$(db_fingerprint identity)"
  after_health="$(db_health)"
  logical_snapshot > "$ORACLE_AFTER"
  test "$after_meta" = "$before_meta"
  test "$after_health" = "$before_health"
  assert_exact_logical_delta "$operation" "$ORACLE_BEFORE" "$ORACLE_AFTER" \
    "$PRIVATE_WORK" "$principal_id" "$relay_device_id" "$capability" "$token_file"
  cleanup_oracle_snapshots
  ORACLE_BEFORE="" ORACLE_AFTER=""
}
run_cli_readonly python3 "$CODE_ROOT/backend/scripts/atlas_v4_principals.py" \
  --db "$CORE_DB" preview-migration > "$PRIVATE_WORK/preflight.json" 2> "$PRIVATE_WORK/preflight.err"
run_cli_mutating principal_add "" "" "" "" python3 "$CODE_ROOT/backend/scripts/atlas_v4_principals.py" --db "$CORE_DB" \
  principal add --kind ANDROID > "$PRIVATE_WORK/principal.json" 2> "$PRIVATE_WORK/principal.err"
PRINCIPAL_ID="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["principal_id"])' \
  "$PRIVATE_WORK/principal.json")"
run_cli_mutating credential_add "$PRINCIPAL_ID" "" "" "$PRIVATE_WORK/relay-credential" \
  python3 "$CODE_ROOT/backend/scripts/atlas_v4_principals.py" --db "$CORE_DB" \
  credential add --principal-id "$PRINCIPAL_ID" --audience RELAY --generate \
  --output "$PRIVATE_WORK/relay-credential" > "$PRIVATE_WORK/credential-receipt.json" 2> "$PRIVATE_WORK/credential.err"
install -o "$CONNECTOR_UID" -g "$CONNECTOR_GID" -m 0600 \
  "$PRIVATE_WORK/relay-credential" "$CONNECTOR_SECRET_DIR/relay-credential" \
  2> "$PRIVATE_WORK/credential-install.err"
RELAY_DEVICE_ID="$(python3 - "$CONNECTOR_STATE_DIR/devices.json" <<'PY'
import json, sys
try:
    devices=json.load(open(sys.argv[1], encoding="utf-8"))["devices"]
    active=[d for d in devices if d.get("revoked_at") is None]
    matches=[d for d in active if d.get("label")=="pixel-v7"]
    if len(active)!=1 or len(matches)!=1: raise ValueError
    print(matches[0]["device_id"])
except Exception:
    raise SystemExit("active-device coverage changed")
PY
)"
run_cli_mutating binding_add "$PRINCIPAL_ID" "$RELAY_DEVICE_ID" "" "" \
  python3 "$CODE_ROOT/backend/scripts/atlas_v4_principals.py" --db "$CORE_DB" \
  binding add --relay-device-id "$RELAY_DEVICE_ID" --principal-id "$PRINCIPAL_ID" \
  > "$PRIVATE_WORK/binding-receipt.json" 2> "$PRIVATE_WORK/binding.err"
python3 - "$RELAY_DEVICE_ID" "$PRINCIPAL_ID" \
  "$CONNECTOR_SECRET_DIR/relay-credential" "$CONNECTOR_MAP_FILE" <<'PY'
import json, os, sys
relay_id, principal_id, credential_file, target = sys.argv[1:]
try:
    payload = {relay_id: {"principal_id": principal_id, "credential_file": credential_file}}
    fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL |
                 getattr(os, "O_NOFOLLOW", 0), 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as output:
        json.dump(payload, output, separators=(",", ":"))
        output.write("\n")
        output.flush()
        os.fsync(output.fileno())
except Exception:
    raise SystemExit("private map creation failed")
PY
chown "$CONNECTOR_UID:$CONNECTOR_GID" "$CONNECTOR_MAP_FILE" 2> "$PRIVATE_WORK/map-owner.err"
chmod 0600 "$CONNECTOR_MAP_FILE" 2> "$PRIVATE_WORK/map-mode.err"
python3 - "$CONNECTOR_STATE_DIR/devices.json" "$CONNECTOR_MAP_FILE" <<'PY'
import json, sys
try:
    devices=json.load(open(sys.argv[1], encoding="utf-8"))["devices"]
    active={d["device_id"] for d in devices if d.get("revoked_at") is None}
    mapping=json.load(open(sys.argv[2], encoding="utf-8"))
    if not active or set(mapping)!=active: raise ValueError
except Exception:
    raise SystemExit("principal map does not cover active devices")
PY
run_cli_mutating capability_set "$PRINCIPAL_ID" "" "$APPROVED_RELAY_CAPABILITY" "" \
  python3 "$CODE_ROOT/backend/scripts/atlas_v4_principals.py" --db "$CORE_DB" \
  capability set --principal-id "$PRINCIPAL_ID" \
  --capability "$APPROVED_RELAY_CAPABILITY" --grant \
  > "$PRIVATE_WORK/capability-receipt.json" 2> "$PRIVATE_WORK/capability.err"
ROOT

CORE_TARGET_FINAL="$(resolve_core_db stopped)"
test "$(python3 -c 'import json,sys; print(json.loads(sys.argv[1])["id"])' "$CORE_TARGET_FINAL")" = "$CORE_CONTAINER_ID"
test "$(python3 -c 'import json,sys; print(json.loads(sys.argv[1])["source"])' "$CORE_TARGET_FINAL")" = "$CORE_MOUNT_SOURCE"
test "$(python3 -c 'import json,sys; print(json.loads(sys.argv[1])["db"])' "$CORE_TARGET_FINAL")" = "$CORE_DB"
sudo python3 - "$CORE_DB_AFTER_STOP" <<'PY'
import os, sqlite3, stat, sys
from urllib.parse import quote
try:
    s=os.lstat(sys.argv[1])
    assert not stat.S_ISLNK(s.st_mode) and stat.S_ISREG(s.st_mode)
    db=sqlite3.connect("file:"+quote(sys.argv[1],safe="/")+"?mode=ro",uri=True)
    db.execute("PRAGMA query_only=ON")
    assert db.execute("PRAGMA user_version").fetchone()[0]==5
    assert db.execute("PRAGMA integrity_check").fetchone()[0]=="ok"
    assert not db.execute("PRAGMA foreign_key_check").fetchall()
    expected={"principals_v4":1,"credentials_v4":1,
              "relay_principal_bindings_v4":1,"principal_capabilities_v4":1,
              "principal_events_v4":4}
    actual={table:db.execute("SELECT count(*) FROM \""+table+"\"").fetchone()[0]
            for table in expected}
    assert actual==expected
    db.close()
except Exception:
    raise SystemExit("post-CLI target, health or registry-count verification failed")
PY

docker compose --env-file "$ATLAS_COMPOSE_ENV_FILE" \
  -f "$CODE_ROOT/compose.yaml" -f "$CODE_ROOT/compose.openclaw.yaml" \
  -f "$LIVE_COMPOSE_OVERRIDE" -f "$V4_COMPOSE_OVERRIDE" \
  up -d --force-recreate atlas-core >/dev/null 2>&1
# Garder le trap armé. Vérifier Core dual et les oracles Core, puis appliquer le contrôle Paseo
# vérifié du connecteur et vérifier ses oracles. En cas d’échec après son démarrage, l’arrêter
# avec le contrôle Paseo vérifié avant de quitter ; le trap recrée alors le Core legacy.
read -r -p 'Après PASS de tous les oracles Core et Relay, saisir DUAL_ORACLES_PASS : ' ORACLE_ACK
test "$ORACLE_ACK" = DUAL_ORACLES_PASS
DUAL_ACTIVE=1
CORE_ROLLBACK_ARMED=0
trap - EXIT INT TERM
~~~

Avant arrêt, `docker inspect` fournit l’`ATLAS_DB_PATH` effectif et le montage `/data` ; seul `/data/atlas.sqlite3` est accepté et le type, nom et source doivent correspondre au volume attendu. Après arrêt, la même inspection doit retrouver le même conteneur, volume, source et chemin effectif. Le garde hôte applique `lstat` à chaque composant, refuse tout symlink, compare `realpath` au chemin lexical et exige que les deux chemins dérivés du montage désignent le même `(st_dev, st_ino)`. Il vérifie UID/GID/mode avant l’arrêt, après l’arrêt et autour de chaque CLI ; `user_version=5`, `integrity_check` et `foreign_key_check` sont relus en SQLite `mode=ro` avant l’arrêt, après l’arrêt et après la CLI. Le hash SHA-256 et l’inode doivent rester identiques pour le backup et les étapes read-only ; chaque CLI mutante est contrôlée par identité de cible, intégrité et deltas logiques exacts, sans comparaison du hash global. Après la dernière CLI, le contrôle final vérifie aussi les compteurs attendus : 1 principal, 1 credential, 1 binding, 1 capability et 4 événements. Tout échec après l’arrêt déclenche le trap legacy ; si la récupération Compose échoue, le connecteur doit rester arrêté et l’intervention opérateur est requise.

Le principal, credential et binding sont trois mutations CLI distinctes. Une erreur CLI peut laisser un état partiel ; le trap remet Core en legacy, garde le connecteur arrêté, puis exige vérification des seuls agrégats. Aucune révocation compensatoire ni réemploi d’un binding révoqué. La CLI offline ne prend pas le verrou du service : Core doit rester arrêté pendant toute la séquence.

Map connecteur privée, générée depuis les variables sans imprimer son contenu :

~~~json
{
  "<relay_device_id lu en mémoire>": {
    "principal_id": "<principal_id depuis reçu privé>",
    "credential_file": "<chemin absolu du credential 0600>"
  }
}
~~~

Fichier d’environnement connecteur, hors dépôt, mode 0600 ; le chemin fourni par `CONNECTOR_DUAL_ENV_FILE` doit être celui réellement consommé par le superviseur Paseo :

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

La validation `config --quiet` et le `up --force-recreate` dual sont intégrés au bloc fail-closed ci-dessus ; ne pas exécuter une seconde séquence hors trap. `up --force-recreate` charge le nouvel environnement ; `restart` seul ne prendrait pas les paramètres Compose modifiés. Le port 4401 appartient à un processus sous scope Paseo, pas à un service Compose ou unité systemd de connecteur identifiée. **Point d’arrêt :** le contrôle de redémarrage du connecteur reste à confirmer par son propriétaire/opérateur Paseo ; ne pas supposer kill, systemctl restart ou docker restart.

Oracles avant requête produit : health Core 200 ; mode dual chargé ; v5/intégrité/FK ; exactement un nouveau principal, credential RELAY actif et binding ; aucune credential/binding existante changée ; keyring/cache montés et permissions/UID valides ; ensemble des clés de map égal à l’ensemble des appareils actifs, avec 1/1 aujourd’hui ; connecteur `relay_connected=true`, V4 actif, map utilisée et binding requis ; chat V2 encore disponible. Tester le chemin fonctionnel sur copie synthétique. Toute requête live au-delà de health/métriques nécessite accord séparé : aucune route auth-only ne permet de prouver le binding sans toucher une route produit.

### Phase 2 — Application DIRECT/V4

Après B accepté et si le propriétaire veut partager le même device_ref, ajouter credential DIRECT au même principal Android. Créer un principal séparé uniquement si une isolation de données différente est voulue. La CLI est offline : refaire le snapshot préalable et arrêter Core pendant l’ajout, puis le recréer avec la même configuration dual.

**Extrait partiel non autonome (option C).** Il dépend d’un backup frais vérifié, d’un arrêt Core, de la même vérification volume/inode/DB et du trap legacy de Phase 1. Après la CLI, recréer Core avec les deux overrides dual, vérifier son health puis la config app et la sync avant de considérer C terminé. Ne pas lancer cet extrait seul.

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

**Extrait partiel non autonome (option D).** Il dépend d’un backup/restauration répétés, d’un arrêt Core, des contrôles volume/inode/DB, d’un trap de redémarrage legacy avant réussite de la transition, puis d’un redémarrage en mode principal et d’oracles complets. Le trap ne peut pas annuler une révocation CLI réussie ; toute erreur après révocation impose l’arrêt du connecteur et une nouvelle décision propriétaire. Ne pas lancer cet extrait seul.

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
| P2 | Sans secret serveur en dual, le cursor est dérivé par SHA-256 du bearer Android : stable si le bearer l’est, mais calculable par son détenteur. Le design exige l’intégrité serveur ; garder donc un secret stable ≥32 caractères en dual et principal. Pas d’option fichier native ; wrapper/env à revoir pour journalisation et accès Docker. |
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
