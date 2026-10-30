# ATLAS Core avec Docker Compose

Cette configuration lance uniquement le Core FastAPI sur Linux. Le projet Compose a le nom stable `atlas-core` et son volume a le nom global stable `atlas-core-data`, indépendants du chemin du checkout. Le service utilise le réseau hôte et écoute sur `127.0.0.1:8100`; aucun port n'est publié. Les relais, proxies et OpenClaw ne font pas partie de ce Compose.

## Secret Bearer

Créer un jeton aléatoire avec un générateur cryptographique et le conserver hors du dépôt dans un fichier maître accessible uniquement à son propriétaire (répertoire `0700`, fichier `0600`). Le fichier doit contenir le jeton sans newline final. Sur Docker Compose local, le secret fichier est un bind mount qui conserve les permissions du fichier source. Pour que l'UID `10001` puisse le lire sans privilèges supplémentaires, créer une copie de livraison en mode `0444` dans un répertoire hôte `0700`; définir `ATLAS_TOKEN_FILE` vers cette copie :

```sh
install -d -m 0700 "$(dirname "$ATLAS_TOKEN_DELIVERY")"
install -m 0444 "$ATLAS_TOKEN_MASTER" "${ATLAS_TOKEN_DELIVERY}.new"
mv -f "${ATLAS_TOKEN_DELIVERY}.new" "$ATLAS_TOKEN_DELIVERY"
export ATLAS_TOKEN_FILE="$ATLAS_TOKEN_DELIVERY"
docker compose config --quiet
docker compose up -d
```

Compose monte cette copie comme secret en lecture seule à `/run/secrets/atlas_token`. L'entrypoint le lit, retire tout éventuel `CR`/`LF` final, puis démarre Uvicorn avec `ATLAS_TOKEN` dans l'environnement du processus. La valeur du jeton n'est ni dans Git, ni dans l'image, ni dans la configuration rendue par `docker compose config`; celle-ci ne contient que la référence au fichier secret. Le `.env` local est ignoré par Git si l'on choisit d'y enregistrer `ATLAS_TOKEN_FILE`.

Le conteneur et l'entrypoint tournent directement sous UID/GID `10001`, avec `cap_drop: ALL` et `no-new-privileges`. Le fichier maître reste `0600`; la copie de livraison est protégée sur l'hôte par son répertoire parent `0700`, puis montée en lecture seule dans le conteneur. Le système de fichiers racine reste en lecture seule, `/tmp` est un tmpfs, et seul `/data` est persistant et inscriptible.

Pour faire tourner le jeton, créer un nouveau fichier maître privé, actualiser atomiquement la copie de livraison avec les commandes ci-dessus, exporter son chemin dans `ATLAS_TOKEN_FILE`, puis recréer le service :

```sh
docker compose up -d --force-recreate atlas-core
```

Vérifier les routes protégées avec le nouveau jeton avant de retirer l'ancien fichier secret.

## Données et vérifications

SQLite est stockée dans le volume nommé `atlas-core-data` monté sur `/data`. `docker compose restart atlas-core`, puis `docker compose down` suivi de `docker compose up -d`, conservent ce volume. Ne pas utiliser `docker compose down -v` si les données doivent rester.

```sh
docker compose build
docker compose ps
curl -i http://127.0.0.1:8100/health
```

Le healthcheck utilise la bibliothèque standard Python, sans `curl` dans l'image. Seul le Core écoute sur `127.0.0.1:8100`.
