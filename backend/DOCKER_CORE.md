# ATLAS Core avec Docker Compose

Cette configuration lance uniquement le Core FastAPI sur Linux. Le projet Compose a le nom stable `atlas-core` et son volume a le nom global stable `atlas-core-data`, indépendants du chemin du checkout. Le service utilise le réseau hôte et écoute sur `127.0.0.1:8100`; aucun port n'est publié. Le contexte de build est `backend/`; les contrats JSON requis depuis `contracts/` sont fournis comme contexte BuildKit nommé. Les relais, proxies et OpenClaw ne font pas partie de ce Compose.

Depuis la racine du dépôt :

```sh
docker compose config
docker compose build
docker compose up -d
docker compose ps
curl -i http://127.0.0.1:8100/health
```

Compose utilise `backend/compose.env.example` pour les valeurs non secrètes. Pour activer les routes protégées, fournir `ATLAS_TOKEN` dans l'environnement du processus Compose avant `docker compose up -d`; la valeur n'est ni copiée dans l'image ni enregistrée dans le dépôt. Sans jeton configuré, `/health` reste accessible et les routes protégées par Bearer répondent `401`.

Exemple avec un jeton conservé hors du dépôt :

```sh
export ATLAS_TOKEN="<jeton local>"
docker compose up -d
```

SQLite est stockée dans le volume nommé `atlas-core-data` monté sur `/data`. `docker compose restart atlas-core`, puis `docker compose down` suivi de `docker compose up -d`, conservent ce volume. Ne pas utiliser `docker compose down -v` si les données doivent rester.

Le système de fichiers racine du conteneur est en lecture seule; `/tmp` est un tmpfs et seul `/data` est persistant et inscriptible. Le processus tourne sous l'UID/GID `10001`, sans capacités Linux, avec `no-new-privileges`. Le healthcheck utilise la bibliothèque standard Python, sans `curl` dans l'image.
