# ATLAS Core avec Docker Compose

Cette configuration lance uniquement le Core FastAPI sur Linux. Le projet Compose a le nom stable `atlas-core` et son volume a le nom global stable `atlas-core-data`, indépendants du chemin du checkout. Le service utilise le réseau hôte et écoute sur `127.0.0.1:8100`; aucun port n'est publié. Les relais, proxies et OpenClaw ne font pas partie de ce Compose.

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
