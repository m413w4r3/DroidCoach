# ATLAS Core avec Docker Compose

Cette configuration lance uniquement le Core FastAPI sur Linux. Le projet Compose a le nom stable `atlas-core` et son volume a le nom global stable `atlas-core-data`, indépendants du chemin du checkout. Le service utilise le réseau hôte et écoute sur `127.0.0.1:8100`; aucun port n'est publié. Le Compose principal conserve l'adaptateur mock pour la démonstration hors ligne. L'override optionnel `compose.openclaw.yaml` active OpenClaw réel sur la même machine, sans modifier ce défaut.

## Secrets Bearer

Générer deux jetons cryptographiquement aléatoires et distincts, puis conserver les fichiers maîtres hors du dépôt dans un répertoire privé `0700`, fichiers `0600`, sans newline final : `atlas-core.token` pour Android (`ATLAS_TOKEN`) et `atlas-tool.token` pour les outils (`ATLAS_TOOL_TOKEN`). Android utilise le master Android; le plugin OpenClaw doit utiliser le master outil. Le plugin et Android ne sont pas configurés par ce Compose.

Sur Docker Compose local, les secrets fichier sont des bind mounts qui conservent les permissions des sources. Pour que l'UID `10001` puisse les lire sans privilèges supplémentaires, créer deux copies de livraison en mode `0444` dans un sous-répertoire hôte `0700`. Exemple avec des chemins génériques :

```sh
ATLAS_SECRETS_DIR="/chemin/privé/atlas-core/secrets"
ATLAS_COMPOSE_DIR="$ATLAS_SECRETS_DIR/compose"
ATLAS_TOKEN_MASTER="$ATLAS_SECRETS_DIR/atlas-core.token"
ATLAS_TOOL_TOKEN_MASTER="$ATLAS_SECRETS_DIR/atlas-tool.token"
ATLAS_TOKEN_DELIVERY="$ATLAS_COMPOSE_DIR/atlas-core.token"
ATLAS_TOOL_TOKEN_DELIVERY="$ATLAS_COMPOSE_DIR/atlas-tool.token"
install -d -m 0700 "$ATLAS_COMPOSE_DIR"
install -m 0444 "$ATLAS_TOKEN_MASTER" "${ATLAS_TOKEN_DELIVERY}.new"
mv -f "${ATLAS_TOKEN_DELIVERY}.new" "$ATLAS_TOKEN_DELIVERY"
install -m 0444 "$ATLAS_TOOL_TOKEN_MASTER" "${ATLAS_TOOL_TOKEN_DELIVERY}.new"
mv -f "${ATLAS_TOOL_TOKEN_DELIVERY}.new" "$ATLAS_TOOL_TOKEN_DELIVERY"
export ATLAS_TOKEN_FILE="$ATLAS_TOKEN_DELIVERY"
export ATLAS_TOOL_TOKEN_FILE="$ATLAS_TOOL_TOKEN_DELIVERY"
docker compose config --quiet
docker compose up -d
```

Compose monte les copies en lecture seule à `/run/secrets/atlas_token` et `/run/secrets/atlas_tool_token`. L'entrypoint retire les éventuels `CR`/`LF` finaux, refuse un secret vide ou identique à l'autre, puis démarre Uvicorn avec `ATLAS_TOKEN` et `ATLAS_TOOL_TOKEN` dans l'environnement du processus. Les valeurs ne sont ni dans Git, ni dans l'image, ni dans la configuration rendue par `docker compose config`; celle-ci ne contient que les références aux fichiers. Le `.env` local est ignoré par Git si l'on choisit d'y enregistrer les deux variables de chemin.

Le conteneur et l'entrypoint tournent directement sous UID/GID `10001`, avec `cap_drop: ALL` et `no-new-privileges`. Les masters restent en `0600`; les copies de livraison sont protégées sur l'hôte par leur répertoire parent `0700`. Le système de fichiers racine reste en lecture seule, `/tmp` est un tmpfs, et seul `/data` est persistant et inscriptible.

## Déploiement avec OpenClaw réel

Le Gateway doit écouter sur `127.0.0.1:18789` sur l'hôte Docker. `OPENCLAW_BASE_URL` est fixé à loopback dans l'override; si le Gateway utilise un autre port local, modifier uniquement le numéro de port dans cette URL en conservant `127.0.0.1`. Les capabilities V4 sont activées dans cet override, avec `coach.chat`; le Compose principal reste en mock et sans capabilities V4.

Générer le jeton Gateway dans un répertoire privé **hors du dépôt**. Garder le master en mode `0600`, puis créer une copie de livraison en mode `0444` dans un répertoire `0700` afin que l'UID `10001` puisse lire le bind mount local Docker :

```sh
OPENCLAW_SECRETS_DIR="/chemin/privé/atlas-core"
OPENCLAW_COMPOSE_DIR="$OPENCLAW_SECRETS_DIR/compose"
OPENCLAW_GATEWAY_TOKEN_MASTER="$OPENCLAW_SECRETS_DIR/openclaw-gateway.token"
OPENCLAW_GATEWAY_TOKEN_DELIVERY="$OPENCLAW_COMPOSE_DIR/openclaw-gateway.token"
install -d -m 0700 "$OPENCLAW_SECRETS_DIR" "$OPENCLAW_COMPOSE_DIR"
umask 077
python3 -c 'import secrets; print(secrets.token_urlsafe(48), end="")' > "$OPENCLAW_GATEWAY_TOKEN_MASTER"
chmod 0600 "$OPENCLAW_GATEWAY_TOKEN_MASTER"
install -m 0444 "$OPENCLAW_GATEWAY_TOKEN_MASTER" "${OPENCLAW_GATEWAY_TOKEN_DELIVERY}.new"
mv -f "${OPENCLAW_GATEWAY_TOKEN_DELIVERY}.new" "$OPENCLAW_GATEWAY_TOKEN_DELIVERY"
export OPENCLAW_GATEWAY_TOKEN_FILE="$OPENCLAW_GATEWAY_TOKEN_DELIVERY"
```

Rendre la configuration et vérifier qu'elle montre `ATLAS_AGENT_ADAPTER: openclaw`, le Gateway sur loopback, les sept flags V4 à `true`, le secret monté et aucun bloc `ports` :

```sh
docker compose -f compose.yaml -f compose.openclaw.yaml config
```

La sortie de `config` contient le chemin du fichier de livraison, jamais son contenu. Compose ne place pas le token Gateway dans l'environnement initial du conteneur : l'entrypoint le lit depuis `/run/secrets/openclaw_gateway_token` puis l'exporte au processus Core. La valeur ne figure donc ni dans Git, ni dans l'image, ni dans la configuration de `docker inspect`.

Après inspection de la configuration, recréer uniquement le Core :

```sh
docker compose -f compose.yaml -f compose.openclaw.yaml up -d --force-recreate atlas-core
```

Vérifier ensuite le healthcheck puis l'authentification et les capabilities du catalogue avec le token outil. Sans Bearer outil, la route doit répondre `401`; avec le token outil et les en-têtes V4 ci-dessous, elle doit répondre `200` et exposer chaque capability activée. La copie outil est déjà définie par `ATLAS_TOOL_TOKEN_FILE` dans la section précédente :

```sh
curl -i http://127.0.0.1:8100/health
curl -i http://127.0.0.1:8100/internal/v4/capabilities
python3 - <<'PY'
import json
import os
from pathlib import Path
from urllib.request import Request, urlopen

token = Path(os.environ["ATLAS_TOOL_TOKEN_FILE"]).read_text(encoding="ascii").rstrip("\r\n")
request = Request(
    "http://127.0.0.1:8100/internal/v4/capabilities",
    headers={
        "Authorization": f"Bearer {token}",
        "X-Atlas-Contract-Version": "4",
        "X-Atlas-Schema-Version": "4",
        "X-Atlas-Capability": "tool_catalog.read",
        "X-Atlas-Request-Id": "11111111-1111-4111-8111-111111111111",
    },
)
with urlopen(request) as response:
    print(response.status)
    print(json.dumps(json.load(response)["capabilities"], indent=2))
PY
```

Pour revenir au mode mock, recréer le service depuis le Compose principal seulement. Cette commande conserve le volume ; ne pas faire `down -v` et ne pas supprimer `atlas-core-data` :

```sh
docker compose -f compose.yaml up -d --force-recreate atlas-core
```

Ne pas exposer publiquement `8100` ou `18789`. Le Core et le Gateway restent liés à loopback et aucun port n'est publié par ces fichiers Compose.

Pour la rotation, générer les nouveaux masters distincts, actualiser les deux copies de livraison, mettre à jour les consommateurs correspondants (Android avec le master Android, plugin avec le master outil), puis recréer le Core :

```sh
docker compose up -d --force-recreate atlas-core
```

Valider les deux flux avec les nouveaux jetons avant de retirer les anciens fichiers.

## Données et vérifications

SQLite est stockée dans le volume nommé `atlas-core-data` monté sur `/data`. `docker compose restart atlas-core`, puis `docker compose down` suivi de `docker compose up -d`, conservent ce volume. Ne pas utiliser `docker compose down -v` si les données doivent rester.

```sh
docker compose build
docker compose ps
curl -i http://127.0.0.1:8100/health
```

Le healthcheck utilise la bibliothèque standard Python, sans `curl` dans l'image. Seul le Core écoute sur `127.0.0.1:8100`.
