# V7-11B — qualification du stack isolé Core / Relay / proxy / connecteur

## Résultat

**Qualification locale PASS**, avec les limites de stockage d’état et de portée décrites ci-dessous. Exécution sur une racine temporaire dédiée, avec Core `mock`, token synthétique, SQLite neuve, V4 désactivé, et endpoints applicatifs loopback. Le stack live n’a pas été démarré, arrêté ni redémarré.

Le code exécutable et ses artefacts non versionnés ont été lus depuis le checkout principal, qui est resté strictement en lecture seule. Seul ce fichier a été modifié dans le worktree de preuve.

## Base et environnement

- `BASE_SHA` : `9385d1249ffdda533e16e44684d42a5000a6748b`.
- `CODE_ROOT=<checkout-principal>` : HEAD égal à `BASE_SHA`, arbre propre avant démarrage, pendant l’exécution et après nettoyage.
- Worktree de preuve : la reprise a commencé sur le commit local de preuve `994dac3e359032cc41dd5bf8e12ffec04b6325c5`, arbre propre. Il contient le commit antérieur BLOCKED; ce commit est remplacé par le commit amendé de cette preuve.
- Déviation documentée : `backend/.venv/bin/python`, `integrations/relay/pc/dist` et `integrations/relay/pc/node_modules` étaient absents du worktree isolé. Ils existaient dans `CODE_ROOT` au même SHA et ont été utilisés sans création, modification ou suppression sous ce chemin.
- Node `v24.18.0`; Python du venv backend présent; Docker client / serveur `27.1.1 / 27.1.1`.
- Image `atlas-paseo-relay:r3a` : ID `sha256:75d68fd8bdb9d60a26203cd7c49c07c035f1f400c47740770056814e76846889`, identique au pin attendu.
- Réseau, racine de travail et chemins d’état/secrets : exécution unique sous `/tmp/atlas-v7-11b-b1-<RUN_ID>`, mode `0700` pour la racine, `secrets/` et `state/`.

## Prévol, isolation et commandes

Contrôles de base exécutés avant la création de la racine temporaire :

```sh
git -C "$CODE_ROOT" rev-parse HEAD
git -C "$CODE_ROOT" status --short
test -x "$CODE_ROOT/backend/.venv/bin/python"
test -d "$CODE_ROOT/integrations/relay/pc/dist"
test -d "$CODE_ROOT/integrations/relay/pc/node_modules"
docker image inspect --format '{{.Id}}' atlas-paseo-relay:r3a
docker inspect --format '{{.Name}}|restarts={{.RestartCount}}|started={{.State.StartedAt}}|status={{.State.Status}}' atlas-core-atlas-core-1 atlas-v7-relay
ss -ltnp
```

Au prévol, `18100`, `14400`, `14401`, `14402` étaient libres. `8100` et `4402` écoutaient sur `127.0.0.1` uniquement. Aucun repli vers un port live n’a été utilisé.

Le stack a utilisé un réseau Docker bridge dédié **non-`internal`**, car la publication loopback depuis un bridge `--internal` ne créait pas de listener hôte lors du test antérieur signalé dans la mission. Le réseau dédié a été vérifié `Internal=false`. Le Relay a été lancé sans volume, sans politique de redémarrage, sans variable d’environnement live et avec publication `127.0.0.1:14402:4000` seulement. L’image écoute `0.0.0.0:4000` dans son propre espace réseau pour accepter le trafic du bridge; l’hôte n’a exposé que `127.0.0.1:14402`.

Commandes de lancement, avec les valeurs synthétiques conservées sous la racine temporaire :

```sh
RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)-$$"
ISOLATED_ROOT="/tmp/atlas-v7-11b-b1-$RUN_ID"
NETWORK="atlas-v7-11b-b1-net-$RUN_ID"
RELAY_NAME="atlas-v7-11b-b1-relay-$RUN_ID"
umask 077
mkdir -m 0700 "$ISOLATED_ROOT"
mkdir -m 0700 "$ISOLATED_ROOT/secrets" "$ISOLATED_ROOT/state"

# Relay — nom et réseau uniques; sortie Docker supprimée
# IMAGE vérifiée contre le pin avant lancement
docker network create --driver bridge "$NETWORK" >/dev/null
docker run --detach --rm --name "$RELAY_NAME" --network "$NETWORK" \
  -p 127.0.0.1:14402:4000 \
  -e PASEO_RELAY_HOST=0.0.0.0 -e PASEO_RELAY_PORT=4000 \
  atlas-paseo-relay:r3a >/dev/null

# Core — lancé depuis CODE_ROOT avec un environnement vierge
cd "$CODE_ROOT"
env -i PATH="$PATH" PYTHONDONTWRITEBYTECODE=1 \
  ATLAS_SECRET_DIR="$ISOLATED_ROOT/secrets" \
  ATLAS_DB_PATH="$ISOLATED_ROOT/atlas.sqlite3" \
  ATLAS_BACKEND_PORT=18100 ATLAS_AGENT_ADAPTER=mock \
  scripts/r3a/start-backend.sh >"$ISOLATED_ROOT/core.log" 2>&1 </dev/null &

# Proxy — lancé depuis CODE_ROOT avec endpoints loopback
cd "$CODE_ROOT"
env -i PATH="$PATH" LISTEN_HOST=127.0.0.1 LISTEN_PORT=14400 \
  UPSTREAM_HOST=127.0.0.1 UPSTREAM_PORT=14402 \
  node integrations/relay/proxy/src/server.mjs \
  >"$ISOLATED_ROOT/proxy.log" 2>&1 </dev/null &

# connector.env privé créé sous ISOLATED_ROOT. Le token a été lu depuis
# ISOLATED_ROOT/secrets/backend-service-token et jamais affiché.
python3 - "$ISOLATED_ROOT" "$RUN_ID" <<'PY'
import os, pathlib, sys
root = pathlib.Path(sys.argv[1])
run_id = sys.argv[2]
token = (root / "secrets" / "backend-service-token").read_text(encoding="ascii").strip()
values = [
    "ATLAS_RELAY_URL=ws://127.0.0.1:14400",
    f"ATLAS_RELAY_SERVER_ID=atlas-v7-11b-b1-{run_id}",
    f"ATLAS_RELAY_STATE_DIR={root / 'state'}",
    "ATLAS_BACKEND_URL=http://127.0.0.1:18100",
    f"ATLAS_SERVICE_TOKEN={token}",
    "ATLAS_V4_ENABLED=0",
    "ATLAS_V4_AUTH_MODE=legacy",
    "ATLAS_ALLOW_LOOPBACK_WS=1",
    "ATLAS_METRICS_PORT=14401",
]
with (root / "connector.env").open("x", encoding="utf-8") as output:
    output.write("\n".join(values) + "\n")
os.chmod(root / "connector.env", 0o600)
PY

# Config validation and connector startup; outputs stay in the private root.
cd "$CODE_ROOT"
env -i PATH="$PATH" node integrations/relay/pc/scripts/check-config.mjs \
  --env-file "$ISOLATED_ROOT/connector.env" \
  >"$ISOLATED_ROOT/check-config.log" 2>&1
env -i PATH="$PATH" node integrations/relay/pc/scripts/run-cli.mjs \
  "$ISOLATED_ROOT/connector.env" start \
  >"$ISOLATED_ROOT/connector.log" 2>&1 </dev/null &
```

Chaque attente HTTP a fait au plus 35 tentatives, espacées d’une seconde, avec un timeout `curl` de deux secondes et corps envoyé vers `/dev/null`. Seul le code a été conservé :

```sh
curl --silent --show-error --connect-timeout 1 --max-time 2 \
  --output /dev/null --write-out '%{http_code}' "$URL"
```

La vérification DB a ouvert le chemin calculé depuis `ISOLATED_ROOT`, vérifié qu’il y reste, puis exécuté :

```sh
"$CODE_ROOT/backend/.venv/bin/python" - "$ISOLATED_ROOT/atlas.sqlite3" "$ISOLATED_ROOT" <<'PY'
import pathlib, sqlite3, sys
db = pathlib.Path(sys.argv[1]).resolve()
root = pathlib.Path(sys.argv[2]).resolve()
assert root in db.parents and db.name == "atlas.sqlite3"
with sqlite3.connect(db) as conn:
    print(conn.execute("PRAGMA user_version").fetchone()[0])
    print(conn.execute("PRAGMA integrity_check").fetchone()[0])
PY
```

Le fichier token Core et `connector.env` étaient en `0600`; aucune valeur de secret n’a été affichée. Le processus Core a été contrôlé via son environnement en ne lisant que `ATLAS_DB_PATH`, `ATLAS_AGENT_ADAPTER` et `ATLAS_BACKEND_PORT`. Les logs du Relay et du proxy ont été filtrés : seuls les noms d’événements, la route `ws` et le statut ont été conservés, sans token, serveur ID, appareil ID ou identifiant brut de conteneur.

## Résultats observés

- Core `/health` : **200**. Environnement du processus : DB sous `ISOLATED_ROOT/atlas.sqlite3`, adaptateur `mock`, port `18100`.
- SQLite : chemin résolu sous la racine isolée; `PRAGMA user_version=5`; `PRAGMA integrity_check=ok`.
- Relay `/health` : **200**.
- Proxy `/health` : **200**.
- Connecteur `/metrics` : **200**; `relay_connected=true`, `connections_opened_total=0`, `errors_total=0`. `check-config.mjs` a réussi et confirmé V4 désactivé ainsi que la présence masquée du token de service.
- Journaux filtrés : `proxy_log=ws_upgrade route=ws status=101`; le log Relay contenait un marqueur de registration, détecté sans conserver son contenu; log connecteur de démarrage présent. Le statut connecté des métriques confirme la connexion de contrôle Relay.
- État du connecteur : répertoire `state/` en `0700`; `identity.json` présent en `0600`. `devices.json` et `operations.json` étaient absents après le démarrage sans appairage ni opération; aucun contenu ni identifiant n’a été lu. Aucune commande `show-identity`, `pair-device` ou `revoke-device` n’a été appelée.
- Réseau Relay : bridge dédié non-interne, aucun montage, aucune politique de redémarrage, aucune variable live ressemblant à un token/secret/URL Core/Relay. Publication hôte `127.0.0.1:14402` seulement.
- `ss -ltnp` pendant l’exécution, filtré sans PIDs : `127.0.0.1:18100`, `127.0.0.1:14400`, `127.0.0.1:14401`, `127.0.0.1:14402`; aucun bind wildcard pour ces ports. Les ports live restaient liés à `127.0.0.1:8100` et `127.0.0.1:4402`.
- Avant et après l’exécution, `atlas-core-atlas-core-1` et `atlas-v7-relay` étaient `running`, `restarts=0`; leurs heures `StartedAt` étaient identiques : respectivement `2026-11-07T10:05:46.547498879Z` et `2026-11-07T10:05:46.958256848Z`.
- `CODE_ROOT` est resté au SHA de base et propre pendant l’exécution et après le nettoyage.

Aucune requête n’a visé les ports live. Core et connecteur avaient un environnement vierge; seuls les endpoints loopback isolés figuraient dans leurs configurations. Le Relay n’avait ni montage ni configuration live. Le live a été seulement observé en lecture seule par `ss` et `docker inspect` ciblé.

## Oracles

| # | Oracle | Résultat | Preuve / limite |
|---|---|---|---|
| 1 | HEAD base et arbre propre | **PASS** | `CODE_ROOT` était exactement au `BASE_SHA` et propre avant/après; worktree de preuve propre au début de la reprise, avec le commit de preuve BLOCKED antérieur. |
| 2 | Ports isolés libres et image épinglée | **PASS** | Les quatre ports étaient libres au prévol; image ID identique au pin; ports live `8100/4402` inchangés. |
| 3 | Core, DB isolée et intégrité | **PASS** | `/health` 200; `mock`; chemin DB sous la racine; version SQLite 5; intégrité `ok`; token synthétique privé. |
| 4 | Relay `/health` | **PASS** | `127.0.0.1:14402/health` 200; bridge dédié non-interne, binding loopback. |
| 5 | Proxy `/health` | **PASS** | `127.0.0.1:14400/health` 200; écoute/upstream loopback. |
| 6 | Connecteur métriques et contrôle Relay | **PASS** | `/metrics` 200, `relay_connected=true`, upgrade WebSocket 101, marqueur de registration Relay. `devices.json` et `operations.json` absents sans opération; aucun appairage effectué. |
| 7 | Bindings isolés et absence de wildcard; live préservé | **PASS** | Les quatre listeners hôte observés étaient en `127.0.0.1`; `8100/4402` conservés sur loopback. |
| 8 | Redémarrages live inchangés | **PASS** | `restarts=0` et `StartedAt` identiques avant/après pour les deux services live. |
| 9 | Nettoyage de l’exécution | **PASS** | PID Core/proxy/connecteur arrêtés; conteneur, réseau et racine temporaire supprimés; ports isolés libres; aucun résidu de l’exécution. |
| 10 | Preuve et commit local | **PASS** | Ce document est l’unique changement du commit amendé. Aucun push ni fusion. |

## Tentative préalable et réconciliation

Une première passe s’est arrêtée après le démarrage Core au garde local `relay_container_isolation_mismatch`; aucun healthcheck Relay, proxy ou connecteur n’a été revendiqué pour cette passe. Son trap a imprimé `cleanup=FAIL`. Le relevé de réconciliation qui a suivi a trouvé zéro conteneur, zéro réseau et zéro racine temporaire avec les préfixes de l’exécution, les ports isolés libres et les services live inchangés. La seconde exécution a passé les contrôles d’isolation détaillés et son nettoyage final est PASS. Aucun objet de la première tentative ne subsiste.

## Limites

Cette recette qualifie le câblage logiciel local avec l’image Relay épinglée; elle ne valide ni l’authentification V4, ni un appareil Android, ni un parcours produit. Aucun appareil n’a été appairé et aucune opération connecteur n’a été envoyée, ce qui explique l’absence de `devices.json` et `operations.json`.

Le bridge est non-interne pour permettre la publication loopback; la preuve n’est pas une capture exhaustive de paquets. L’absence de contact live est établie par les endpoints loopback isolés, l’environnement minimal, l’adaptateur `mock`, l’absence de montages/variables live, les journaux de proxy filtrés et l’absence de changement des services live. Aucun contrôle de trafic externe générique n’a été effectué.

Nettoyage exécuté sur les seuls noms uniques de cette exécution : arrêt des trois processus possédés, `docker stop "$RELAY_NAME"` (conteneur `--rm`), `docker network rm "$NETWORK"`, puis suppression de `$ISOLATED_ROOT` après validation de son marqueur `.owner`. État final : stack isolé supprimé; worktree de preuve prêt pour commit local amendé; `CODE_ROOT` toujours propre au `BASE_SHA`.
