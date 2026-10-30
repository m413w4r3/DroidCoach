# V7-01B — preuve Core isolé / OpenClaw

Date : 2026-10-30

Branche : `v7-01b-isolated-core-proof`

`BASE_SHA` : `b759bdafbf0c67eda1a14ae2cf2f3fac75239e4c`

## Verdicts

- `ISOLATED_CORE_REAL_ADAPTER: PASS`
- `SIX_TOOLS_EFFECTIVE: FAIL`
- `REAL_AGENT_TURN: PASS`
- `NATIVE_TOOL_PROOF: PASS`
- `NO_MOCK_FALLBACK: PASS`

Le chemin Core isolé → Gateway → modèle a terminé un tour réel avec un appel local à `atlas_get_day_plan`. Un appel natif `atlas_get_day_plan` a aussi atteint la route interne du Core isolé. L’aperçu `tools.effective` ne contient toutefois aucun outil ATLAS et signale tous les outils listés comme exclus. Une entrée `exec` apparaît en plus dans la trajectoire Gateway alors qu’elle est elle aussi déclarée exclue; ses arguments sont masqués. Cette incohérence de politique doit être résolue avant d’utiliser cet aperçu comme garantie de l’inventaire réellement actif.

## Environnement isolé

- Image Docker : `atlas-core-atlas-core:latest`, ID `sha256:5107e0c144361faf18711dce62359eff3bb92b2583ff6b1f690c79deea3d7b39`.
- OpenClaw : `2026.10.5` (`1b58cb9`), Gateway `127.0.0.1:18789`, chat completions HTTP activé.
- Plugin `atlas_core_tools` activé, `coreBaseUrl=http://127.0.0.1:18100`, six capacités activées. Son fichier de token existant a été lu sans afficher sa valeur; propriétaire `nill`, mode `0600`. Il n’a pas été modifié.
- Le Core jetable a écouté uniquement sur `127.0.0.1:18100`, avec SQLite sous `/tmp` du conteneur, aucun volume Docker ni port publié. Le token Android était synthétique; `ATLAS_TOOL_TOKEN` provenait du fichier du plugin; le token Gateway était monté depuis un fichier temporaire en lecture seule et chargé par un bootstrap. Aucune valeur de token n’est reproduite ici.
- Durcissement observé par `docker inspect` : système de fichiers racine en lecture seule, tmpfs `/tmp` (`noexec,nosuid,nodev`, 64 MiB), `cap_drop=ALL`, `no-new-privileges`, UID/GID `10001:10001`, limite de 128 processus et mémoire de 512 MiB.
- Variables non secrètes : `ATLAS_AGENT_ADAPTER=openclaw`, `ATLAS_DB_PATH=/tmp/atlas-v7-01b.sqlite3`, `OPENCLAW_BASE_URL=http://127.0.0.1:18789`, `OPENCLAW_AGENT_ID=atlas`, `OPENCLAW_TIMEOUT_S=18`; les sept capacités V4 étaient à `true`.

Commande de lancement effective, avec les chemins des fichiers temporaires (contenus omis) :

```sh
docker run --rm -d --name atlas-v7-01b-isolated --network host --read-only \
  --tmpfs /tmp:rw,noexec,nosuid,nodev,size=64m --cap-drop ALL \
  --security-opt no-new-privileges:true --user 10001:10001 \
  --pids-limit 128 --memory 512m \
  --mount type=bind,src=/tmp/atlas-v7-01b-proof-jfx2jpt8/android_token,dst=/run/secrets/atlas_token,readonly \
  --mount type=bind,src=/tmp/atlas-v7-01b-proof-jfx2jpt8/tool_token,dst=/run/secrets/atlas_tool_token,readonly \
  --mount type=bind,src=/tmp/atlas-v7-01b-proof-jfx2jpt8/gateway_token,dst=/run/secrets/openclaw_gateway_token,readonly \
  --mount type=bind,src=/tmp/atlas-v7-01b-proof-jfx2jpt8/gateway-bootstrap.py,dst=/run/gateway-bootstrap.py,readonly \
  --env ATLAS_AGENT_ADAPTER=openclaw \
  --env ATLAS_DB_PATH=/tmp/atlas-v7-01b.sqlite3 \
  --env OPENCLAW_BASE_URL=http://127.0.0.1:18789 \
  --env OPENCLAW_AGENT_ID=atlas --env OPENCLAW_TIMEOUT_S=18 \
  --env 'ATLAS_V4_CAPABILITIES_JSON={"day_plan.read":true,"activity_status.read":true,"plan_revision.propose":true,"usage_summary.read":true,"goal_progress.read":true,"training_plan.read":true,"coach.chat":true}' \
  --entrypoint python atlas-core-atlas-core:latest \
  /run/gateway-bootstrap.py uvicorn atlas_backend.main:app --host 127.0.0.1 --port 18100 --access-log
```

Le bootstrap a seulement lu le fichier de token Gateway et l’a fourni au processus isolé. Il n’a pas modifié la configuration OpenClaw. L’image a ensuite chargé les fichiers de token Android et outil selon son entrypoint existant.

## Capacités, données synthétiques et routes internes

`GET /health` répondait HTTP 200 avec `mode=mock`. Le code actuel hardcode cette valeur; elle n’a donc pas servi d’oracle. `GET /internal/v4/capabilities`, authentifié par le token outil et les en-têtes de contrat V4, a répondu HTTP 200 avec ces sept flags vrais : `day_plan.read`, `activity_status.read`, `plan_revision.propose`, `usage_summary.read`, `goal_progress.read`, `training_plan.read`, `coach.chat`.

Les synchronisations Android synthétiques ont répondu HTTP 200 et accepté : consentements `DAY_PLAN`, `ACTIVITY_STATUS`, `USAGE_SUMMARY`, `GOAL_PROGRESS`, `TRAINING_PLAN`; plan approuvé du `2026-10-30` avec deux activités inventées, version 1; événement `PLANNED → IN_PROGRESS`, provenance `CONFIRMED / USER_UI`; résumé d’usage de 120 000 ms; un objectif synthétique. Aucun nom, calendrier ou autre donnée personnelle réelle n’a été utilisé.

Les six routes internes ont été exercées avec le token outil et les en-têtes `x-atlas-contract-version: 4`, `x-atlas-schema-version: 4`, `x-atlas-capability` et `x-atlas-request-id` :

| Route | Résultat |
|---|---|
| `GET /internal/v4/day-plan` | HTTP 200, `FRESH`, version 1, deux activités |
| `GET /internal/v4/activity-status/{id}` | HTTP 200, `FRESH`, `IN_PROGRESS`, `CONFIRMED / USER_UI` |
| `POST /internal/v4/plan-revision-proposals` | HTTP 200, `QUEUED`, `auto_application=NONE_AT_CORE` |
| `GET /internal/v4/usage-summary` | HTTP 200, `FRESH`, 120 000 ms |
| `GET /internal/v4/goal-progress` | HTTP 200, `FRESH`, un objectif |
| `GET /internal/v4/training-plan` | HTTP 500 avec une session d’entraînement non vide conforme au schéma de sync; HTTP 200, `FRESH`, avec les tableaux sessions/routines vides |

La proposition directe a créé une seule ligne SQLite : `aa5b7934-27c0-41ca-a0c4-ca714eebfae0`, état `PENDING_ANDROID`, `base_version=1`. Le plan est resté en version 1 avec la même révision `922d39f4-0568-41d4-abbf-53a3cddf4cd8`; l’activité déplacée proposée n’a pas été appliquée. La troisième conversation de chat a renvoyé `proposal_ids=[]`; le compteur SQLite est resté à une proposition.

## `tools.effective`

Appel read-only effectué sur une session synthétique réelle :

```sh
openclaw gateway call --json tools.effective --params '{"sessionKey":"agent:atlas:openai-user:atlas-fdeb3da93151c63d2b2f893d8d3acf19cfb3e3a9149143a714e0f227d207d022"}'
```

Réponse : agent `atlas`, profil `minimal`, groupes vides, `access_active_ids=[]`; 66 outils listés, tous au statut `excluded`. Aucun des six outils du plugin n’apparaît. Liste exacte des entrées :

```text
ls, read, write, edit, apply_patch, exec, process, code_execution, secrets,
web_search, web_fetch, x_search, memory_search, memory_get, presence,
sessions, sessions_list, sessions_history, sessions_search, conversations_list,
conversations_send, conversations_turn, sessions_send, sessions_spawn,
github_identity_status, github_publish, sessions_yield, subagents, session_status,
suggest_task, dismiss_task, browser, screen, theme, dashboard, terminal, portal,
canvas, show_widget, message, heartbeat_respond, automations, gateway, plugins,
openclaw, nodes, computer, mobile_ui, decision_evaluate, agents_list, get_goal,
create_goal, update_goal, progress_card, ask_user, skill_workshop, skills_search,
skills_read, view_image, image_generate, music_generate, video_generate,
transcripts, tts, pdf, agents_wait
```

Avis Gateway associé : `browser-filtered-by-profile`. Les six entrées absentes sont `atlas_get_day_plan`, `atlas_get_activity_status`, `atlas_propose_plan_revision`, `atlas_get_usage_summary`, `atlas_get_goal_progress`, `atlas_get_training_plan`. L’aperçu échoue donc à l’oracle `SIX_TOOLS_EFFECTIVE`; aucune configuration live n’a été changée pour tenter de le corriger.

La trajectoire synthétique Gateway contient aussi des événements `exec tool.call` et `exec tool.result ok`, alors que `exec` est `excluded` dans cet aperçu. Les arguments sont masqués par OpenClaw. Ce décalage est rapporté comme une anomalie de politique; le test ne l’interprète pas comme une autorisation d’exécution.

## Tour réel Core → Gateway

Les deux premières requêtes V4, avec demandes de lecture plan/statut puis une variante nommant les outils, ont répondu HTTP 503 `UPSTREAM_UNAVAILABLE`. Elles n’ont pas renvoyé de `agent_status`, de réponse, ni de compteurs d’outils dans le corps d’erreur. Aucun fallback de chat `mock` n’a été renvoyé.

La troisième et dernière requête ne demandait que `atlas_get_day_plan`, avec les arguments `{}` valides, sans statut ni proposition. Résultat : HTTP 200, `agent_status=ok`, `tool_call_count=1`, `tool_state=completed`, `proposal_ids=[]`, réponse non vide de 243 caractères. La session Gateway a enregistré `atlas_get_day_plan tool.call`, `tool.result ok` puis `model.completed`; le Core a journalisé le POST chat en 200 à `20:41:51.766Z`. Il n’y a pas de GET `/internal/v4/day-plan` à cette heure, cohérent avec le dispatch local de `V4ToolService` pour ce tour.

Les arguments des deux premiers tours ne peuvent pas être vérifiés exactement : `openclaw sessions tail` remplace les blocs d’arguments par `{...redacted...}`. La trajectoire y nomme `atlas_get_day_plan` et `atlas_get_activity_status` et indique des résultats `ok`, mais cela ne prouve pas que les arguments reçus par le parseur Core satisfaisaient son schéma. L’hypothèse `activity_id` absent/invalide reste donc **non confirmée**. Aucun patch temporaire ni redémarrage/rechargement du processus isolé n’a été fait; le troisième essai a isolé et prouvé le cas à un outil.

Le code actuel traite un nom inconnu, un JSON d’arguments invalide ou un échec de validation JSON Schema comme `invalid_provider()`, donc `UPSTREAM_UNAVAILABLE` (`backend/atlas_backend/openclaw_adapter.py:831-857`). Le contrat de retour borné d’erreur par message `role=tool` reste un correctif séparé à évaluer; les présentes preuves ne permettent pas d’attribuer les deux 503 à cette branche précise.

## Preuve du plugin natif Gateway

Dans la session synthétique de la première requête, la trajectoire Gateway à `20:18:44Z` enregistre `atlas_get_day_plan tool.call` puis `tool.result ok`. Le journal d’accès du Core isolé contient le POST `/v4/agent/chat` à `20:18:44.734878568Z`, suivi de `GET /internal/v4/day-plan?local_date=2026-10-30` en HTTP 200 à `20:18:44.741952821Z`. Le plugin configuré pour joindre `127.0.0.1:18100` a donc atteint l’instance isolée via sa route interne. Le POST de chat englobant a fini en 503; cette preuve concerne l’appel natif du plugin, pas un tour V4 complet réussi.

## Écarts restants et nettoyage

- Les six outils ATLAS ne figurent pas dans `tools.effective`, tandis qu’un événement `exec` apparaît dans la trajectoire. Auditer la construction de l’inventaire effectif et le décalage entre aperçu et exécution avant tout changement live.
- Les deux erreurs `UPSTREAM_UNAVAILABLE` restent sans diagnostic de corps Gateway. Les arguments sont masqués et l’instrumentation temporaire demandée n’a pas été chargée, car cela aurait exigé le redémarrage/rechargement du Core isolé, que le cadrage complémentaire interdisait. La troisième tentative valide passe.
- `GET /internal/v4/training-plan` échoue en 500 avec un snapshot non vide accepté par le schéma de sync; le schéma de résultat du tool attend d’autres champs (`slot_id`, `routine_id`, `source`). Corriger et tester ce contrat dans un ticket séparé.
- Le token outil du Core live reste différent de celui du fichier du plugin; cette preuve n’a pas modifié le live et utilise uniquement le token du plugin dans l’instance jetable.
- L’instance `--rm` a été arrêtée. Vérification finale : aucun listener 18100; le Core live `atlas-core-atlas-core-1` est toujours `healthy` sur 8100; le Gateway reste sur 18789 avec le PID observé `559596`. Aucun service live n’a été redémarré.
- **Nettoyage incomplet après refus d’approbation :** l’outil a refusé la suppression demandée du dossier temporaire. Les fichiers token restent sous `/tmp/atlas-v7-01b-proof-jfx2jpt8/` (dossier `0700`, fichiers en lecture seule); leurs contenus ne figurent ni dans le rapport ni dans le dépôt. Le refus de suppression des quatre sessions Gateway synthétiques a également été appliqué; leurs sessions vivent encore, même si la prévisualisation ciblée avait confirmé qu’elles étaient synthétiques. Je n’ai pas contourné ces refus. Le répertoire d’export `/tmp/atlas-v7-01b-trajectory` n’existe pas.

Sessions synthétiques visées par la prévisualisation de suppression :

```text
agent:atlas:openai-user:atlas-babebebff7b0faef413f830f0f03c5d85da7e1d97fc5343948d0d347c869d788
agent:atlas:openai-user:atlas-22aeba781a75777b6abff0c091cbcc0eceb025addd15c56264a040379e3fb9b0
agent:atlas:openai-user:atlas-fdeb3da93151c63d2b2f893d8d3acf19cfb3e3a9149143a714e0f227d207d022
agent:atlas:openai-user:v7-01b-gateway-diagnostic
```

## Commandes de vérification et d’arrêt

Commandes de lecture utilisées pour la version et le chemin natif :

```sh
openclaw --version
openclaw gateway call --json tools.effective --params '{"sessionKey":"<sessionKey synthétique>"}'
openclaw sessions tail --agent atlas --session-key '<sessionKey synthétique>' --tail 20
docker inspect atlas-v7-01b-isolated
docker logs --since 2h --timestamps atlas-v7-01b-isolated
```

Les requêtes HTTP ont été émises contre `127.0.0.1:18100` avec des tokens lus depuis les fichiers montés, sans valeur de token en argument de commande. Les routes et résultats expurgés sont consignés ci-dessus. L’arrêt et la vérification des listeners :

```sh
docker stop atlas-v7-01b-isolated
docker ps --filter name=atlas-v7-01b-isolated --format '{{.Names}} {{.Status}}'
ss -ltnp | rg ':(8100|18100|18789)\b'
```

Pas de test de token Gateway invalide : non nécessaire après la réussite du chemin réel et non requis par les critères bloquants.
