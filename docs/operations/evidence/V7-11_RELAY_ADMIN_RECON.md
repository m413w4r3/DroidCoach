# V7-11B-P1 — Dossier d’administration Relay/connecteur

**Type :** reconnaissance indépendante, lecture seule

**Date :** 2026-11-08

**BASE_SHA :** `af94aa713e73b2ce7a1e8b603ebc5296397ccd38`

**Branche :** `audit/v7-11-a6-cutover-gates`

**Modèle/effort déclarés par le parent :** `codex/gpt-6-luna/xhigh`

## Verdict

Le Relay live est supervisé par le démon Docker avec `restart=unless-stopped`, et non par une unité systemd ou un projet Compose identifié. Le conteneur n’a aucun label Compose ni montage, et sa configuration exacte de création n’est pas déterminable avec les sources accessibles. Le proxy et le connecteur ne sont pas actuellement en écoute sur leurs ports attendus. Les modèles systemd versionnés et les scripts R3-A ne décrivent donc pas, à eux seuls, l’installation live.

Le dépôt contient assez d’éléments pour construire un **stack de qualification isolé** sur `18100/14400/14401/14402`, avec Core mock, données et secrets synthétiques, Relay local et endpoints loopback. Cette recette n’a pas été exécutée. La recréation ou le remplacement du Relay live reste **NO-GO** tant que son propriétaire, la persistance interne de l’image et la procédure de bascule ne sont pas établis par une autorisation distincte.

Aucun service n’a été démarré, arrêté ou modifié. Aucun fichier secret ou base personnelle n’a été ouvert; les valeurs d’environnement n’ont jamais été affichées et seuls les noms des variables Docker ont été émis. Aucun identifiant d’appareil ni historique shell n’a été lu ou écrit. Aucun test Android/ADB n’a été lancé.

## État réel relevé

| Élément | Observation vérifiée | Portée et limite |
|---|---|---|
| Conteneur Relay | `atlas-v7-relay`, image `atlas-paseo-relay:r3a`, état `running`, démarré le `2026-11-07T10:05:46Z`, compteur de redémarrage `0` | L’identifiant Docker brut n’est pas conservé dans cette preuve. |
| Supervision | Politique Docker `unless-stopped`; aucun healthcheck déclaré | Le démon Docker peut relancer le processus après une sortie. Aucun contrôle de santé Docker ne déclenche de réparation applicative. |
| Réseau | mode `bridge`; publication `127.0.0.1:4402` vers le port conteneur `4000` | Pas de bind public du port Relay observé. |
| Image/processus | Entrypoint `/app/bin/paseo_relay`, commande `start`; processus `beam.smp` avec auxiliaires Erlang | L’historique d’image a été lu sans afficher les commandes de construction. L’image locale correspond au pin du dépôt (comparaison booléenne uniquement). |
| Labels/montages | aucun label; aucun montage | Aucun fichier de configuration/state hôte ne peut être rattaché au conteneur par les montages Docker. |
| Variables | seuls les noms ont été émis : `HOME`, `PASEO_RELAY_DRAIN`, `PASEO_RELAY_HOST`, `PASEO_RELAY_PORT`, `PATH` | Les valeurs n’ont pas été affichées. |
| Journaux | 14 lignes dans la fenêtre demandée; recherche filtrée : 2 correspondances « warning », 0 « error/fatal », 0 « denial » | Comptes par motifs uniquement; le texte brut n’a pas été restitué ni analysé sémantiquement. |
| Couche conteneur | aucune différence de couche correspondant à `/app`, `paseo`, `relay`, `state`, `db`, `config` ou `identity` | Le contrôle est basé sur chemins; il ne prouve pas l’absence de persistance interne hors de ces motifs. |
| Configuration image | `/app/releases/0.1.0/sys.config`, mode `0644`; binaire sous `/app/bin` | Métadonnées seulement, contenu non lu. |
| Ports hôte | instantané `ss -ltnp` : Core sur `127.0.0.1:8100`, Relay sur `127.0.0.1:4402`; aucune écoute observée sur `4400`, `4401`, `18100`, `14400`, `14401` ou `14402` | Disponibilité valable au moment de l’instantané seulement. L’outil a refusé un second contrôle filtré dans le sandbox. |
| Conteneurs ATLAS | Core et Relay présents; aucun conteneur proxy/connecteur identifié | Le Core live n’est pas utilisé dans la recette isolée. |
| systemd | seule unité ATLAS/OpenClaw pertinente listée : `openclaw-gateway.service`, active; aucune unité Relay/proxy dans les listes user/system ni fichiers d’unités user correspondants | Les unités du dépôt sont des modèles, pas des unités installées. |
| Cron | aucune entrée utilisateur contenant `atlas`, `relay` ou `paseo`; aucun nom de fichier cron système correspondant | Les commandes des entrées cron n’ont pas été affichées. Un script générique sans nom apparent reste possible. |
| Compose | `compose.yaml` décrit Core; `compose.openclaw.yaml` est un overlay Core; aucun service Relay/proxy/connecteur ni label Compose sur le conteneur live | Aucun `docker compose config` n’a été lancé, afin d’éviter de matérialiser des valeurs interpolées. |

### Commandes de preuve et filtres

Les commandes Docker et systemd ont nécessité un accès de lecture hors sandbox. La sortie a été limitée aux champs ci-dessous; aucune commande d’écriture n’a été exécutée.

```sh
# Inspecter le Relay sans émettre d’ID brut, de valeur d’environnement ou de source de montage.
docker inspect atlas-v7-relay | python3 -c 'import json,sys; d=json.load(sys.stdin)[0]; c=d["Config"]; s=d["State"]; print({"name":d["Name"],"image_ref":c.get("Image"),"state":s.get("Status"),"started":s.get("StartedAt"),"restart_count":d.get("RestartCount"),"health":s.get("Health",{}).get("Status","none"),"healthcheck":bool(c.get("Healthcheck")),"restart_policy":d.get("HostConfig",{}).get("RestartPolicy",{}).get("Name"),"network_mode":d.get("HostConfig",{}).get("NetworkMode"),"entrypoint":c.get("Entrypoint"),"cmd":c.get("Cmd"),"env_names":sorted(x.partition("=")[0] for x in c.get("Env",[])),"label_keys":sorted(c.get("Labels") or {}),"mounts":[(m.get("Type"),m.get("Destination"),m.get("RW")) for m in d.get("Mounts",[])],"ports":d.get("NetworkSettings",{}).get("Ports")})'
docker top atlas-v7-relay -eo pid,ppid,user,comm
docker logs --tail 120 atlas-v7-relay 2>&1 | python3 -c 'import sys,re; lines=list(sys.stdin); pats={"error_or_fatal":r"error|fatal","warning":r"warn","startup_or_listening":r"ready|listening|started","denial":r"denied|unauthor|forbidden"}; print("tail_lines",len(lines)); print({k:sum(bool(re.search(p,x,re.I)) for x in lines) for k,p in pats.items()}); print("raw_log_lines_emitted",False)'
docker image history --format '{{.CreatedAt}}|{{.Size}}' atlas-paseo-relay:r3a
ACTUAL_IMAGE_ID="$(docker image inspect --format '{{.Id}}' atlas-paseo-relay:r3a)"
EXPECTED_IMAGE_ID="$(sed -n 's/^EXPECTED_IMAGE_ID="\([^"]*\)"$/\1/p' integrations/relay/pc/scripts/run-isolated-relay-tests.sh)"
test "$ACTUAL_IMAGE_ID" = "$EXPECTED_IMAGE_ID" && echo 'matches_repository_pin=true; digest_emitted=false'
docker diff atlas-v7-relay | python3 -c 'import sys,re; rows=[x.rstrip() for x in sys.stdin]; f=[re.sub(r"[A-Fa-f0-9]{32,}","<redacted-id>",x) for x in rows if any(k in x.lower() for k in ("/app","paseo","relay","state","db","config","identity"))]; print("matching_writable_layer_paths",len(f)); [print(x) for x in f[:80]]'
docker exec atlas-v7-relay sh -c 'find /app /root /var/lib -xdev -type f \( -iname "*identity*" -o -iname "*state*" -o -iname "*.db" -o -iname "*.sqlite*" -o -iname "*relay*.json" \) -printf "%m %p\n" 2>/dev/null; stat -c "%a %F" /app/releases/0.1.0/sys.config'
docker ps -a --format '{{.Names}}|{{.Image}}|{{.Status}}|{{.Ports}}'
ss -ltnp
systemctl --user list-units --all --no-legend --no-pager | rg -i 'relay|openclaw|atlas' || true
systemctl --user list-unit-files --no-legend --no-pager | rg -i 'relay|openclaw|atlas' || true
systemctl list-units --all --no-legend --no-pager | rg -i 'relay|openclaw|atlas' || true
systemctl list-unit-files --no-legend --no-pager | rg -i 'relay|openclaw|atlas' || true
crontab -l 2>/dev/null | python3 -c 'import sys; rows=[(i,x.strip()) for i,x in enumerate(sys.stdin,1) if any(k in x.lower() for k in ("atlas","relay","paseo"))]; print("matching_user_cron_entries",len(rows)); [print("entry",i,"schedule"," ".join(x.split()[:5])) for i,x in rows]'
find /etc/cron.d /etc/cron.hourly /etc/cron.daily /etc/cron.weekly /etc/cron.monthly /var/spool/cron -maxdepth 2 -type f -printf '%m %f\n' 2>/dev/null | rg -i 'atlas|relay|paseo' || true
rg -n -i 'atlas-v7-relay|atlas-paseo-relay|relay' compose.yaml compose.openclaw.yaml || true
python3 -c 'import os,stat; from pathlib import Path; h=Path.home(); items={"relay_env":h/".config/atlas/relay.env","connector_state":h/".local/state/atlas-relay","connector_identity":h/".local/state/atlas-relay/identity.json","connector_registry":h/".local/state/atlas-relay/devices.json","connector_operations":h/".local/state/atlas-relay/operations.json","relay_unit":h/".config/systemd/user/atlas-relay.service","proxy_unit":h/".config/systemd/user/atlas-relay-proxy.service"}; [(lambda k,p: (lambda s: print(k,"present",stat.S_IMODE(s.st_mode)))(p.lstat()))(k,p) if p.exists() else print(k,"absent") for k,p in items.items()]'
```

Le premier essai `docker exec ... rg ...` a échoué car `rg` n’existe pas dans l’image; il a été remplacé par un filtre `awk`. Cela n’a modifié aucun fichier ni service.

## Origine, supervision et recréation

### Ce qui peut être affirmé

1. Le conteneur live est relancé par le démon Docker selon `unless-stopped`. Il n’a pas de label Compose, de healthcheck ni de montage visible.
2. Les unités `integrations/relay/pc/systemd/atlas-relay.service` et `integrations/relay/proxy/systemd/atlas-relay-proxy.service` sont des modèles versionnés. Elles ne sont pas installées. La première supervise le **connecteur Node**, pas le Relay Erlang; la seconde supervise le proxy Node.
3. `scripts/r3a/start-relay.sh` démarre par défaut un autre nom de conteneur et publie `4400:4000`. Si le nom configuré existe arrêté, le script exécute `docker rm -f` avant de lancer. Il est donc non idempotent et inadapté à l’administration du conteneur live.
4. `integrations/relay/pc/scripts/run-isolated-relay-tests.sh` choisit un nom unique mais utilise `4402` par défaut, port déjà occupé par le Relay live. Le lancer sans `ATLAS_TEST_RELAY_PORT` distinct échouerait au bind ou créerait un conflit; son nettoyage cible le conteneur qu’il a créé, mais ce script n’est pas une procédure de remplacement live.
5. Les fichiers Compose racine ne déclarent pas le Relay. Aucune correspondance du conteneur live avec un script de lancement actuellement versionné n’a été établie.
6. L’historique shell n’a pas été inspecté, conformément à l’interdiction. Il est donc impossible d’identifier l’auteur ou la commande de création originelle à partir de cette inspection.

### Procédure de recréation live — non autorisée par ce ticket

Il n’existe pas de commande de remplacement sûre et complète démontrée. Toute opération future devrait, dans cet ordre :

1. obtenir une autorisation spécifique du propriétaire du Relay et identifier le consommateur de `4402`/le chemin de retour;
2. capturer les métadonnées filtrées ci-dessus, confirmer de nouveau le digest épinglé, le port, la politique de redémarrage et l’absence de montage;
3. établir par documentation ou source amont si le Relay conserve autre chose que des sessions en mémoire; la présente inspection ne suffit pas à le prouver;
4. préserver séparément l’état du **connecteur** si une opération le touche; ne pas appeler `show-identity`, car cette commande peut créer une identité si elle manque;
5. préparer un conteneur de remplacement et vérifier son `/health` sur un port temporaire distinct avant toute bascule;
6. planifier une fenêtre de coupure et un rollback avec le propriétaire. La libération de `4402`, le remplacement du nom live ou l’effacement de l’ancien conteneur exigent une autorisation de changement distincte.

Ne pas copier l’unité systemd du connecteur ou du proxy pour piloter le conteneur Erlang. Ne pas exécuter les scripts R3-A sur le live : leurs noms/ports par défaut et leur comportement de suppression ne correspondent pas au conteneur observé.

## Connecteur, configuration et identité persistante

Le connecteur PC est un processus Node distinct du serveur Relay. Sa configuration versionnée indique par défaut `ATLAS_RELAY_URL=ws://127.0.0.1:4400`, Core `http://127.0.0.1:8100`, métriques `127.0.0.1:4401`, et état sous `~/.local/state/atlas-relay`. La configuration attend notamment `ATLAS_SERVICE_TOKEN`, `ATLAS_RELAY_STATE_DIR`, `ATLAS_ALLOW_LOOPBACK_WS` et `ATLAS_METRICS_PORT`. Aucun de ces defaults ne prouve que le processus live les utilisait.

Constats de métadonnées hôte (contenu non lu) :

| Chemin logique | Présence/mode observés | Conclusion limitée |
|---|---|---|
| `~/.config/atlas/relay.env` | présent, `0600` | Valeurs et éventuels overrides non lus. |
| `~/.local/state/atlas-relay` | présent, répertoire `0700` | C’est le chemin d’état par défaut documenté; l’override dans l’environnement n’est pas vérifié. |
| `identity.json`, `devices.json`, `operations.json` sous ce répertoire | présents, fichiers réguliers `0600` | Métadonnées seulement; aucune identité, entrée d’appareil ni donnée opérationnelle n’a été lue. |
| unités user `atlas-relay.service`, `atlas-relay-proxy.service` | absentes | Pas de supervision systemd utilisateur installée sous ces noms. |

Le connecteur n’écoute pas sur `4401` dans l’instantané réseau et aucune unité Relay/connecteur/proxy n’est enregistrée. Les fichiers d’état existent, mais sans lire l’environnement et sans processus actif, leur appartenance à une exécution précise, l’endpoint réellement configuré et l’appairage historique ne sont pas prouvés. Aucune conclusion sur l’identité d’un appareil ou sa validité n’est tirée.

`backup-state.mjs`/`state-backup.mjs` sauvegardent `identity.json` et `devices.json` dans une nouvelle destination privée, avec manifeste et empreintes. Ils **n’incluent pas `operations.json`**; ce n’est donc pas une sauvegarde complète de l’état du connecteur. L’outil de restauration exige le service arrêté, une confirmation d’empreinte et une sauvegarde pré-restauration, et peut supprimer des fichiers absents de la sauvegarde. Ne pas lancer sauvegarde/restauration ou `pair-device`/`revoke-device` dans ce ticket.

### Persistance du Relay

L’image contient `/app/releases/0.1.0/sys.config` en `0644`; aucune identité, base SQLite ou base Relay nommée n’a été trouvée dans la recherche bornée de `/app`, `/root` et `/var/lib`. Aucun volume/montage ni chemin correspondant n’apparaît dans la couche inscriptible filtrée. La recherche n’a pas lu le contenu des fichiers.

Ces indices suggèrent que le serveur Relay utilise un état runtime volatil, mais ils **ne démontrent pas** que l’application n’écrit jamais ailleurs ou qu’elle n’a aucune base interne. La source de l’image en cours n’est pas présente comme code exploitable dans ce dépôt. Traiter l’état des sessions Relay comme volatile et non récupérable jusqu’à preuve contraire; conserver la prudence d’un état interne non documenté avant toute suppression/recréation.

## Recette de stack isolé V7-11B — non exécutée

### Topologie et limites

| Composant | Endpoint de qualification | Isolation vérifiée par conception |
|---|---:|---|
| Core local | `127.0.0.1:18100` | Adapter `mock`; SQLite neuf dans un répertoire temporaire dédié; token synthétique neuf. |
| Proxy | `127.0.0.1:14400` | Processus Node manuel; écoute loopback et amont loopback uniquement. |
| Connecteur métriques | `127.0.0.1:14401` | Listener loopback configuré par `ATLAS_METRICS_PORT`; état neuf distinct. |
| Relay | `127.0.0.1:14402` vers `4000` conteneur | Image épinglée; conteneur dédié sur un réseau Docker `--internal`; aucun volume; sans restart policy. |

La recette n’emploie ni Core `8100`, ni Relay `4402`, ni Gateway `18789`, ni Funnel, ni relay.env existant. Elle ne configure pas de compte réel, d’appareil physique, de credential V4 ou de paire Pixel. Elle prouve uniquement le câblage logiciel local; elle ne vaut ni qualification sécurité V4, ni preuve de compatibilité Android.

### Préconditions et arrêts

- Lancer uniquement après autorisation d’exécuter un stack de test local. Le présent ticket n’autorise pas son lancement.
- Arrêter si l’une des quatre ports `18100/14400/14401/14402` est occupée, si le répertoire ou les noms Docker choisis existent déjà, si Docker n’accepte pas un réseau `--internal` avec publication loopback, ou si le digest image ne correspond plus au pin versionné.
- Arrêter si la configuration Core pourrait viser une base hors du répertoire temporaire, si l’adaptateur n’est pas `mock`, si les valeurs de secret viennent du live, ou si une commande requiert Gateway/Funnel/public bind.
- Aucun fallback vers les ports live ou une interface `0.0.0.0` côté hôte. Ne pas installer/activer les unités systemd de modèle.

### Commandes de démarrage proposées

À exécuter manuellement dans des terminaux séparés **après** les préconditions. L’identifiant d’exécution rend le conteneur/réseau/répertoire uniques; si une collision existe, choisir un nouvel identifiant, sans supprimer l’objet déjà présent. Conserver la valeur de `ISOLATED_ROOT` affichée au premier terminal et la réutiliser pour charger `stack.env` dans les terminaux suivants. Dans les commandes suivantes, remplacer `<RUN_ID>` par la valeur exacte affichée; ne pas saisir les chevrons.

```sh
# Préparer/build le connecteur avant de lancer les services.
(cd integrations/relay/pc && npm ci && npm run build)

set -euo pipefail
RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)-$$"
ISOLATED_ROOT="/tmp/atlas-v7-11b-${RUN_ID}"
NETWORK="atlas-v7-11b-${RUN_ID}"
RELAY_NAME="atlas-v7-11b-relay-${RUN_ID}"
IMAGE="atlas-paseo-relay:r3a"

PORT_SNAPSHOT="$(ss -ltnp)" || { echo 'STOP: cannot verify local ports' >&2; exit 2; }
if printf '%s\n' "$PORT_SNAPSHOT" | awk '$4 ~ /:(18100|14400|14401|14402)$/ {busy=1} END {exit !busy}'; then
  echo 'STOP: an isolated stack port is already occupied' >&2; exit 2
fi

EXPECTED_IMAGE_ID="$(sed -n 's/^EXPECTED_IMAGE_ID="\([^"]*\)"$/\1/p' integrations/relay/pc/scripts/run-isolated-relay-tests.sh)"
test -n "$EXPECTED_IMAGE_ID" || { echo 'STOP: repository pin missing' >&2; exit 2; }
test "$(docker image inspect --format '{{.Id}}' "$IMAGE")" = "$EXPECTED_IMAGE_ID" || {
  echo 'STOP: Relay image differs from repository pin' >&2; exit 2;
}
! docker network inspect "$NETWORK" >/dev/null 2>&1 || { echo 'STOP: network name collision' >&2; exit 2; }
! docker inspect "$RELAY_NAME" >/dev/null 2>&1 || { echo 'STOP: container name collision' >&2; exit 2; }
test ! -e "$ISOLATED_ROOT" || { echo 'STOP: isolated root already exists' >&2; exit 2; }
mkdir -m 0700 "$ISOLATED_ROOT"
mkdir -m 0700 "$ISOLATED_ROOT/secrets" "$ISOLATED_ROOT/state"
docker network create --internal "$NETWORK"

cat > "$ISOLATED_ROOT/stack.env" <<EOF
RUN_ID=$RUN_ID
ISOLATED_ROOT=$ISOLATED_ROOT
NETWORK=$NETWORK
RELAY_NAME=$RELAY_NAME
IMAGE=$IMAGE
EOF
chmod 0600 "$ISOLATED_ROOT/stack.env"
printf 'ISOLATED_ROOT=%s\n' "$ISOLATED_ROOT"
```

Dans un terminal dédié, charger le chemin exact affiché ci-dessus puis démarrer le Core (il reste au premier plan) :

```sh
ISOLATED_ROOT='/tmp/atlas-v7-11b-<RUN_ID>'
. "$ISOLATED_ROOT/stack.env"

# Core : ce script crée un bearer synthétique dans ISOLATED_ROOT/secrets et
# l’API ouvre une base SQLite neuve. Le terminal reste attaché au serveur local.
ATLAS_SECRET_DIR="$ISOLATED_ROOT/secrets" \
ATLAS_DB_PATH="$ISOLATED_ROOT/atlas.sqlite3" \
ATLAS_BACKEND_PORT=18100 \
ATLAS_AGENT_ADAPTER=mock \
scripts/r3a/start-backend.sh
```

Après que le Core répond, créer `connector.env` **sans afficher le bearer** en copiant le token synthétique généré par `start-backend.sh`; ne jamais lire le token live. À lancer depuis un autre terminal avec le `ISOLATED_ROOT` exact :

```sh
ISOLATED_ROOT='/tmp/atlas-v7-11b-<RUN_ID>'
. "$ISOLATED_ROOT/stack.env"

python3 - "$ISOLATED_ROOT" "$RUN_ID" <<'PY'
import os, pathlib, sys
root = pathlib.Path(sys.argv[1])
run_id = sys.argv[2]
token = (root / "secrets" / "backend-service-token").read_text(encoding="ascii").strip()
if not token or any(ch.isspace() for ch in token):
    raise SystemExit("STOP: synthetic Core token is absent or malformed")
env = root / "connector.env"
values = [
    "ATLAS_RELAY_URL=ws://127.0.0.1:14400",
    f"ATLAS_RELAY_SERVER_ID=atlas-v7-11b-{run_id}",
    f"ATLAS_RELAY_STATE_DIR={root / 'state'}",
    "ATLAS_BACKEND_URL=http://127.0.0.1:18100",
    f"ATLAS_SERVICE_TOKEN={token}",
    "ATLAS_V4_ENABLED=0",
    "ATLAS_V4_AUTH_MODE=legacy",
    "ATLAS_ALLOW_LOOPBACK_WS=1",
    "ATLAS_METRICS_PORT=14401",
]
with env.open("x", encoding="utf-8") as output:
    output.write("\n".join(values) + "\n")
os.chmod(env, 0o600)
print("connector.env created with mode 0600; token value suppressed")
PY
```

Dans un terminal dédié, lancer le Relay isolé :

```sh
ISOLATED_ROOT='/tmp/atlas-v7-11b-<RUN_ID>'
. "$ISOLATED_ROOT/stack.env"
docker run --detach --rm --name "$RELAY_NAME" --network "$NETWORK" \
  -p 127.0.0.1:14402:4000 \
  -e PASEO_RELAY_HOST=0.0.0.0 -e PASEO_RELAY_PORT=4000 \
  "$IMAGE" >/dev/null
curl -fsS http://127.0.0.1:14402/health
```

Dans un autre terminal, depuis la racine du dépôt, lancer le proxy au premier plan :

```sh
ISOLATED_ROOT='/tmp/atlas-v7-11b-<RUN_ID>'
. "$ISOLATED_ROOT/stack.env"
LISTEN_HOST=127.0.0.1 LISTEN_PORT=14400 \
UPSTREAM_HOST=127.0.0.1 UPSTREAM_PORT=14402 \
node integrations/relay/proxy/src/server.mjs
```

Dans un troisième terminal, valider la configuration puis démarrer le connecteur au premier plan :

```sh
ISOLATED_ROOT='/tmp/atlas-v7-11b-<RUN_ID>'
. "$ISOLATED_ROOT/stack.env"
node integrations/relay/pc/scripts/check-config.mjs --env-file "$ISOLATED_ROOT/connector.env"
node integrations/relay/pc/scripts/run-cli.mjs "$ISOLATED_ROOT/connector.env" start
```

Les commandes Node ci-dessus sont au premier plan. Le Core écrit dans son SQLite isolé; le connecteur crée uniquement `identity.json`, `devices.json` et `operations.json` sous le nouveau state dir. V4 reste désactivé. Ne pas appeler `show-identity`, `pair-device` ou `revoke-device` pendant la simple qualification du câblage.

Vérifications non sensibles proposées : `curl -fsS http://127.0.0.1:18100/health`, `curl -fsS http://127.0.0.1:14402/health`, `curl -fsS http://127.0.0.1:14400/health`, et `curl -fsS http://127.0.0.1:14401/metrics`. Elles ne valident ni une identité de téléphone, ni le binding V4, ni l’autorisation produit.

### Arrêt isolé

Arrêter les processus Core, proxy et connecteur par `Ctrl-C` dans leurs terminaux respectifs. Arrêter **uniquement** le Relay à nom unique de cette exécution avec `docker stop "$RELAY_NAME"`; `--rm` retire ce seul conteneur. Puis `docker network rm "$NETWORK"`. Ne pas exécuter de suppression récursive du répertoire si son chemin n’a pas été relu et comparé au préfixe `/tmp/atlas-v7-11b-`; l’état synthétique peut être conservé pour la qualification suivante.

## Risques et décisions

| Niveau | Risque | Décision / réduction requise |
|---|---|---|
| P1 | Le propriétaire et la commande d’origine du conteneur live ne sont pas identifiés; absence de labels Compose, de volume et d’unité Relay. | Blocage de tout remplacement live; obtenir l’administrateur responsable et une recette approuvée. |
| P1 | Persistance interne du Relay inconnue; aucune preuve qu’une recréation préserverait les sessions ou un état Relay caché. | Considérer sessions/état non récupérables; documenter l’amont ou démontrer le stockage avant bascule. |
| P1 | Connecteur inactif côté listeners/unités, alors que des fichiers d’état privés existent. L’endpoint effectif et l’identité associée ne sont pas vérifiés. | Ne pas régénérer, restaurer, appairer ou révoquer; établir la configuration effective avec une inspection autorisée qui masque les valeurs. |
| P1 | La sauvegarde fournie omet `operations.json`; une sauvegarde dite complète à partir de cet utilitaire seul ne l’est pas. | Définir une sauvegarde cohérente des trois fichiers, avec connecteur arrêté et mode privé, avant toute opération sur état. |
| P1 | Remplacer le Relay sur `4402` coupe ses clients et ne peut pas se faire en parallèle sur le même bind. | Plan de coupure, validation hors port live, rollback et autorité distincte. |
| P2 | `scripts/r3a/start-relay.sh` utilise un autre nom/port par défaut et peut supprimer un conteneur arrêté si le nom est ciblé. | Ne pas employer comme gestionnaire du Relay live; choisir un nom unique et auditer les branches destructives avant usage. |
| P2 | `run-isolated-relay-tests.sh` cible `4402` par défaut, occupé par le live. | Ne pas exécuter sans port isolé libre et vérification de pin; préférer la recette à réseau interne ci-dessus. |
| P2 | Les deux fichiers systemd ne reflètent pas le superviseur réel du Relay et leurs valeurs de ports reflètent l’architecture proxy/connector attendue. | Étiqueter les unités comme modèles; ne pas présenter `$RELAY_UNIT` comme vérifié pour le conteneur Erlang. |

**Gate proposé V7-11B-P1 : BLOCKED pour toute mutation live; PREPARED pour l’élaboration d’un stack local isolé.** Débloquer seulement après une autorisation de lancement du stack de test et, séparément, une autorisation de changement live avec propriétaire, plan de coupure et preuve de persistance/rollback. La prochaine action raisonnable est de valider le stack isolé en local avec données synthétiques; cette preuve ne doit pas être utilisée comme acceptation du live.
