# R2-BRIDGE-QA-01 — Qualification isolée du plugin de recherche OpenClaw

| Clé | Valeur |
|---|---|
| TASK_ID / statut | `R2-BRIDGE-QA-01 / READY (vague C)` |
| BASE_SHA | HEAD de la campagne au lancement (SHA exact fourni) |
| Profil imposé | `provider=codex`, `model=gpt-6-luna`, `effort=xhigh` |
| Worktree | `feat/r2-bridge-qa` |
| Accès | écriture bornée au plugin ; aucun ADB, aucun secret réel, aucun Bridge réel |
| Dépendances | aucune (le parent ne modifie pas OpenClaw) |

## Objectif observable

Revalider le plugin `atlas_chatgpt_research` contre un **faux Bridge** et le SDK
OpenClaw installé, prouver qu'il n'expose qu'un outil de recherche sans
mutation, documenter la provenance et la procédure d'activation/retour arrière.
L'activation réelle reste `BLOCKED_POLICY`.

## Scope d'écriture exclusif

```text
integrations/openclaw/atlas_chatgpt_research/**
```

Interdictions : `~/.openclaw/openclaw.json`, toute configuration OpenClaw
active, tout redémarrage du Gateway, tout appel au Bridge réel, tout secret,
`backend/**`, `android/**`, `docs/**` hors ce dossier.

## Exigences

### 1. Compatibilité et build

- Vérifier la version d'OpenClaw installée et le SDK
  `openclaw/plugin-sdk/tool-plugin` utilisé.
- `npm ci` (ou `npm install` si le lock est cohérent), `npm test`,
  `npm run plugin:build`.
- `openclaw plugins build --entry ./dist/index.js --check`.
- `openclaw plugins validate --entry ./dist/index.js`.
- Vérifier que `openclaw.plugin.json` déclare **un seul** outil,
  `atlas_chatgpt_research`, `optional: true`, sans outil shell/fichier/session.

### 2. Faux Bridge

Ajouter un harnais de test local (serveur HTTP éphémère ou mock `fetch`) et des
tests pour :

1. `/health` indisponible → erreur typée, aucun crash.
2. 401/403 → aucune fuite de clé, erreur stable.
3. 429 avec `Retry-After` → conservé, pas de retry infini.
4. Timeout → erreur bornée.
5. JSON malformé / schéma invalide → résultat refusé.
6. `409 bridge_payload_conflict` → aucun retry avec un corps différent.
7. Même clé + même payload → même run, aucun doublon.
8. `background: true` → POST puis poll `GET /v1/bridge/runs/{id}`.
9. États `queued|running|completed|failed|needs_review` gérés explicitement.
10. `/ready` extension absente → état dégradé explicite.
11. `visible_citations` → conservées comme données non fiables.
12. Mode `prompt_instructed` → `PROMPT_INSTRUCTED_UNVERIFIED`, jamais
    « recherche vérifiée ».
13. Réponse contenant une instruction hostile → traitée comme donnée, aucune
    action.
14. Aucun outil de mutation dans le manifeste effectif.
15. `toolCallId` utilisé comme `request_id` et clé d'idempotence.
16. Aucune clé API dans les logs, erreurs ou fixtures.

### 3. Documentation

Créer ou mettre à jour `VALIDATION_R2.md` :

- commandes et résultats exacts ;
- version OpenClaw/SDK ;
- matrice des 16 recettes ;
- provenance (`ui_tool`, `prompt_instructed`, citations) et limites ;
- politique : planificateur `atlas` sans Bridge, agent de recherche restreint
  au seul outil ;
- procédure d'activation et de retour arrière, avec accord ciblé requis ;
- statut final : `BLOCKED_POLICY` pour l'activation réelle.

## Oracle

```bash
cd integrations/openclaw/atlas_chatgpt_research
npm ci
npm test
npm run plugin:build
openclaw plugins build --entry ./dist/index.js --check
openclaw plugins validate --entry ./dist/index.js
```

Tous les tests existants (41+) et les nouveaux doivent passer. Le manifeste
généré doit être cohérent avec `package.json`.

## Livrables

Commit local, SHA de sortie, fichiers, commandes, résultats
`PASS/FAIL/BLOCKED/NOT_RUN`, matrice des recettes, limites.
Aucune installation, aucune activation, aucun redémarrage.

## Stop conditions

Besoin d'un secret réel, du Bridge réel, d'une modification
`~/.openclaw/openclaw.json`, d'un redémarrage, d'ADB ou d'une installation
globale. Modèle/effort non conformes ou conflit de fichier.
