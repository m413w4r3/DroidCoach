# Rapport du lot S4 — Backend léger ATLAS Core (mock)

## Résultat

Backend optionnel et indépendant livré sous `backend/`. L’application Android ne l’importe pas et n’en dépend pas. Aucun fichier Android, contrat, prompt, référence, ni document `docs/00–09` n’a été modifié. Le schéma `contracts/directive.schema.json` reste inchangé ; la directive servie respecte ses champs et les cinq choix existants.

## Fichiers du lot

- `backend/README.md` — installation, lancement local, exemples `curl`, limites.
- `backend/requirements.txt` — dépendances directes épinglées.
- `backend/.gitignore` — venv, données SQLite, caches Python.
- `backend/atlas_backend/main.py` — routes FastAPI, validation Pydantic, auth Bearer et limite de corps à 65 536 octets.
- `backend/atlas_backend/agent.py` — interface `AgentAdapter`, proposition mock déterministe.
- `backend/atlas_backend/policy.py` — mandat, capabilities configurables et validation déterministe des actions/paramètres.
- `backend/atlas_backend/content.py` — catalogue statique limité et validation HTTPS/plain text.
- `backend/atlas_backend/store.py` — persistance SQLite et idempotence.
- `backend/tests/conftest.py`, `backend/tests/test_api.py`, `backend/tests/test_content_and_config.py` — tests pytest.

Les données locales sont créées dans `backend/data/atlas.sqlite3`. Ce chemin est ignoré par la règle `backend/.gitignore`. Le dépôt n’étant pas initialisé avec Git, aucun `git status` ni vérification de l’index Git n’était disponible.

## API

Routes livrées :

- `GET /health` (seule route sans authentification)
- `GET /v1/directives/current`
- `POST /v1/directives/{id}/respond` — `start`, `discuss`, `alternative`, `postpone`, `pause`
- `POST /v1/events` — idempotent par `event_id` UUID
- `GET /v1/plan/today`
- `GET /v1/policy/effective`
- `GET /v1/content/recommendations` — trois éléments HTTPS statiques au maximum
- `POST /v1/coach/message`
- `POST /v1/sync`
- `GET /v1/export`
- `GET /openapi.json` — OpenAPI FastAPI généré et protégé par Bearer

## Sécurité et décisions du lot

- Les routes protégées répondent `401` si `ATLAS_TOKEN` manque ou ne correspond pas. La comparaison est constante en temps. Aucun secret par défaut n’est fourni ni enregistré.
- Uvicorn écoute explicitement sur `127.0.0.1` dans les exemples. Le service n’ouvre pas de port réseau public par défaut.
- Le mandat énumère les seules actions de coaching permises et chaque action dépend d’une capability explicite. Les paramètres sont validés par action ; une commande système, un paramètre inconnu ou une capability coupée est refusé.
- L’agent retourne une proposition seulement. La policy accepte ou rejette ; ce backend n’exécute aucune action Android ou système (`action_executed: false`). Les choix `postpone` et `pause` renvoient donc une proposition au client, sans modifier l’état Android.
- Le mock ignore le message reçu, ne le stocke pas et produit toujours la même réponse/proposition. Le texte entrant est limité à 1 000 caractères, les objets refusent les champs inconnus et le corps HTTP est limité à 65 536 octets. Il n’est jamais interprété comme du code.
- Les recommandations sont des données statiques ; les titres sont du texte brut et les URL sont validées HTTPS. Aucun contenu distant n’est récupéré. OpenClaw est annoncé `unsupported` : **adaptateur non implémenté**.
- Les capabilities sont indépendantes et peuvent être surchargées par `ATLAS_CAPABILITIES_JSON`; les noms inconnus et valeurs non booléennes sont rejetés.
- Le stockage est SQLite minimal sans migration ni synchronisation réseau. La sync du MVP est une ingestion locale idempotente.

## Environnement observé

- Python `3.12.3`, venv créé dans `backend/.venv`.
- FastAPI `0.115.12`, Pydantic `2.11.5`, Uvicorn `0.34.3`, pytest `8.4.0`, httpx `0.28.1`.
- Linux x86_64, OpenJDK `21.0.12.1`, Android SDK présent (`ANDROID_HOME=$HOME/Android/Sdk`), ADB `34.0.4`.
- `adb devices -l` listait `emulator-5554`; les AVD disponibles étaient `Pixel_9_Pro_XL` et `Pixel_9_Pro_XL_API_30_2`. Aucun test Android ou sur Pixel physique n’a été lancé pour ce lot backend.
- Répertoire `.git` absent.

## Commandes et sorties réelles

Installation exécutée :

```text
python3.12 -m venv backend/.venv
backend/.venv/bin/python -m pip install --disable-pip-version-check -r backend/requirements.txt
Successfully installed ... fastapi-0.115.12 ... httpx-0.28.1 ... pydantic-2.11.5 ... pytest-8.4.0 ... uvicorn-0.34.3
```

Commande de tests demandée, depuis la racine :

```text
$ backend/.venv/bin/python -m pytest -q
..............                                                           [100%]
=============================== warnings summary ===============================
.../site-packages/starlette/testclient.py:37: DeprecationWarning: The anyio.abc.BlockingPortal alias is deprecated, use anyio.from_thread.BlockingPortal instead.
  _PortalFactoryType = typing.Callable[[], typing.ContextManager[anyio.abc.BlockingPortal]]
-- Docs: https://docs.pytest.org/en/stable/how-to/capture-warnings.html
14 passed, 1 warning in 0.76s
```

Le premier lancement pytest a échoué à la collecte (`ModuleNotFoundError: atlas_backend`), puis les itérations suivantes ont révélé une annotation d’authentification mal résolue et le mode strict Pydantic pour UUID/date. Ces défauts ont été corrigés ; la sortie ci-dessus est celle du lancement final.

Résultats des itérations de correction : collecte interrompue avant tests ; puis `8 failed, 6 passed` sur l’authentification ; puis `3 failed, 11 passed` sur le parsing UUID/date ; après ces corrections, tous les lancements finaux ont donné `14 passed`.

Démarrage réel du serveur avec un jeton aléatoire temporaire généré à l’exécution, puis arrêt du processus :

```text
$ backend/.venv/bin/python -m uvicorn --app-dir backend atlas_backend.main:app --host 127.0.0.1 --port 18765 --log-level warning
GET /health
{"status":"ok","mode":"mock"}
HTTP 200
GET /v1/directives/current (Bearer token)
{"schema_version":1,"directive_id":"demo-focus-001","type":"start_focus_session","title":"Avancer 10 minutes sur une tâche","reason":"Une courte session est une prochaine étape simple.","project_id":null,"estimated_minutes":10,"choices":["start","discuss","alternative","postpone","pause"],"created_at":"2026-10-18T20:38:06Z","expires_at":"2026-10-19T20:38:06Z","requires_explicit_confirmation":false}
HTTP 200
Uvicorn arrêté (PID 3202173).
Journal Uvicorn :
```

Le processus a ensuite été vérifié terminé. Les tests OpenAPI confirment la génération du document et sa protection par jeton.

## Verdicts

- **PASS** — venv et dépendances épinglées installés.
- **PASS** — 14 tests pytest : health, auth absent/incorrect, idempotence et stockage unique, cinq choix, refus hors mandat et paramètres non autorisés, contenu/URL non HTTPS, limites de taille, export, sync, mock coach déterministe et OpenAPI.
- **PASS** — vrai démarrage Uvicorn sur `127.0.0.1`, `curl` de `/health` et d’une route authentifiée, arrêt du serveur confirmé.
- **PASS** — OpenClaw explicitement `unsupported`, sans prétendre à une intégration.
- **NON TESTÉ** — consommation par Android, déploiement HTTPS/réseau, intégration OpenClaw, récupération de recommandations distantes et exécution d’actions par le client.

## Limites et suite possible

Ce lot fournit le stockage et les réponses de démo, pas un service de production : pas de rate limiting, TLS, comptes, migrations SQLite, suppression des données, source RSS, IA distante ou accès Android. En cas de raccordement futur, Android doit rester autonome ; le client doit revérifier le mandat, le consentement et les capabilities avant toute action locale.
