# V63-R3-SCOUT-01 — Audit lecture seule Bridge + SDK plugin OpenClaw

| Clé | Valeur |
|---|---|
| TASK_ID / statut | `V63-R3-SCOUT-01 / READY` |
| Base | branche `feat/atlas-v6-3-reprise` ; SHA exact annoncé dans le prompt |
| Profil | `provider=codex`, `model=gpt-6-luna`, `effort=xhigh` |
| Worktree | dédié, `chmod -R a-w`, aucune écriture |
| Dépendances | aucune |

## Objectif observable

Produire un rapport d'audit **sans modification** qui permet de lancer le ticket
d'implémentation du plugin OpenClaw `atlas_chatgpt_research` sans hypothèse non
vérifiée.

## Sources à lire

- Bridge local : `/home/nill/perso/work/Bridges/chatgpt-bridge` (SHA
  `0a181f844c09d68b62d0e17e3c3d6e83e34a58aa`), en particulier `README.md`,
  `AGENTS.md`, `docs/chatgpt_bridge_operations.md`, `bridge/contracts.py`,
  `bridge/routes_bridge.py`, `bridge/routes_openai.py`, `bridge/run_service.py`,
  tests du registre durable, de recherche et des contrôles UI.
- OpenClaw installé : `/home/nill/.nvm/versions/node/v24.18.0/lib/node_modules/openclaw`
  version **2026.10.5**, docs `docs/plugins/tool-plugins.md`,
  `docs/plugins/manifest.md`, `docs/plugins/sdk-setup.md`,
  `docs/plugins/sdk-overview.md`, `docs/plugins/sdk-subpaths.md`,
  `docs/gateway/config-tools.md`.
- Code de référence OpenClaw : `references/repos/openclaw` (lecture seule).

**Ne pas lire** le `.env` du Bridge ni les secrets de `~/.openclaw/openclaw.json`.
Les noms de variables suffisent (`BRIDGE_API_KEY`, `BRIDGE_WS_TOKEN`).

## Questions à trancher

1. Structure exacte d'un plugin `defineToolPlugin` pour OpenClaw 2026.10.5
   (`package.json`, `openclaw.plugin.json`, `tsconfig`, build, validation).
2. Comment le plugin reçoit sa configuration (URL Bridge, clé HTTP) via les
   mécanismes de secrets/environnement OpenClaw, sans clé dans le code ni dans
   le manifeste.
3. Comment restreindre l'outil à un agent/contexte de recherche, en gardant
   l'agent planificateur `atlas` avec `tools.deny: ["*"]`.
4. Contrat exact de `POST /v1/bridge/runs` et `GET /v1/bridge/runs/{id}` :
   champs requis, `X-Idempotency-Key`, `web_search`, états, timeout, reprise.
5. Champs de provenance disponibles (`web_search_mode`, sources, dates,
   `run_id`, `request_id`) et limites réelles du Bridge.
6. Mapping des 16 cas de recette de `PROMPT_MAITRE.md` §6.11 vers des tests
   automatisables du plugin/adaptateur.
7. Risques : injection de prompt via page, absence de source fiable, JSON
   malformé, extension déconnectée, quota.
8. Fichiers exacts et oracle du futur ticket `V63-L8-02`.

## Interdictions

Aucune écriture, aucun `chmod` inverse, aucun appel réseau (le Bridge est déjà
vérifié par le parent), aucune installation de plugin, aucun redémarrage
OpenClaw, aucun secret lu ou affiché, aucune sous-délégation.

## Livrable

Rapport concis dans la réponse finale : constats avec chemins/lignes, contrat
API, structure de plugin recommandée, prérequis de configuration, matrice de
tests, risques, oracle. Marquer explicitement `VÉRIFIÉ` / `NON VÉRIFIÉ` et
`BLOCKED` si un élément dépend d'une action propriétaire.
