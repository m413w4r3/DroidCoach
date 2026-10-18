# ATLAS Core — backend mock

Service optionnel de démonstration pour ATLAS. L’application Android n’en dépend pas et reste utilisable hors ligne. Le service est local, stocke les événements et réponses dans SQLite, et tourne sans clé IA ni appel réseau sortant.

## Installation

Python 3.12 est requis. Depuis la racine du dépôt :

```sh
cd backend
python3.12 -m venv .venv
.venv/bin/python -m pip install -r requirements.txt
```

Les versions directes sont épinglées dans `requirements.txt`. Le venv local et `data/` sont ignorés. La base est créée automatiquement dans `backend/data/atlas.sqlite3`, ou à l’emplacement défini par `ATLAS_DB_PATH`.

## Lancement local

Depuis `backend/`, créer un jeton de développement dans l’environnement puis lancer Uvicorn :

```sh
export ATLAS_TOKEN="$(python -c 'import secrets; print(secrets.token_urlsafe(32))')"
.venv/bin/uvicorn atlas_backend.main:app --host 127.0.0.1 --port 8000
```

L’hôte par défaut du lancement Python (`python -m atlas_backend.main`) est également `127.0.0.1`. Toutes les routes sauf `/health`, dont `/openapi.json`, demandent `Authorization: Bearer $ATLAS_TOKEN`. Si le jeton n’est pas configuré, les routes protégées répondent `401`. Le jeton n’est ni fourni par défaut ni enregistré dans SQLite.

Pour désactiver des capacités individuellement, fournir un objet JSON limité aux flags connus. Les autres capacités gardent leur valeur par défaut :

```sh
export ATLAS_CAPABILITIES_JSON='{"focus_sessions": false, "content_recommendations": false}'
```

Une capacité désactivée fait refuser l’opération concernée. Les erreurs de syntaxe, noms inconnus ou valeurs non booléennes empêchent le démarrage.

## Exemples curl

Dans un second terminal, depuis `backend/` après avoir défini le même jeton :

```sh
curl -i http://127.0.0.1:8000/health
curl -H "Authorization: Bearer $ATLAS_TOKEN" http://127.0.0.1:8000/v1/directives/current
curl -X POST http://127.0.0.1:8000/v1/directives/demo-focus-001/respond \
  -H "Authorization: Bearer $ATLAS_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"choice":"start"}'
curl -H "Authorization: Bearer $ATLAS_TOKEN" http://127.0.0.1:8000/v1/content/recommendations
curl -X POST http://127.0.0.1:8000/v1/coach/message \
  -H "Authorization: Bearer $ATLAS_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"message":"Je veux avancer sur une tâche"}'
curl -H "Authorization: Bearer $ATLAS_TOKEN" http://127.0.0.1:8000/v1/export
```

OpenAPI généré : `curl -H "Authorization: Bearer $ATLAS_TOKEN" http://127.0.0.1:8000/openapi.json`.

## Routes livrées

- `GET /health`
- `GET /v1/directives/current`
- `POST /v1/directives/{id}/respond` avec `start`, `discuss`, `alternative`, `postpone` ou `pause`
- `POST /v1/events` (idempotence par UUID `event_id`)
- `GET /v1/plan/today`
- `GET /v1/policy/effective`
- `GET /v1/content/recommendations` (catalogue statique de trois URLs HTTPS au plus)
- `POST /v1/coach/message` (mock déterministe)
- `POST /v1/sync`
- `GET /v1/export`

## Sécurité et limites

`AgentAdapter` ne peut fournir qu’une proposition. `MockAgentAdapter` est déterministe, ignore le texte fourni et ne fait aucun appel réseau. `PolicyEngine` compare chaque action au mandat et à la capability correspondante ; ce backend n’exécute aucune action Android ou système. Les textes entrants sont des données non fiables, limités en taille et jamais évalués comme code. Les objets JSON refusent les champs supplémentaires. Les contenus du catalogue sont du texte brut et chaque URL est validée en HTTPS ; aucune page ou métadonnée distante n’est récupérée.

OpenClaw est `unsupported` : **adaptateur non implémenté**. Il n’y a pas de client OpenClaw, LLM, RSS ou intégration externe. SQLite et ce jeton local conviennent uniquement à une démonstration sur la machine. Le serveur est conçu pour écouter sur loopback ; ce n’est pas un déploiement HTTPS pour réseau public. Les changements de policy et l’exécution d’actions Android restent la responsabilité déterministe du client, avec son propre consentement.

## Tests

```sh
.venv/bin/python -m pytest -q
```

