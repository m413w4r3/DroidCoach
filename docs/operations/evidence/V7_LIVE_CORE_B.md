# V7 — bascule Core live vers l’adaptateur OpenClaw (scope B)

Date : 2026-10-30  
Autorisation : scopes A–D du propriétaire ; ce document couvre **B uniquement**.  
Aucune donnée personnelle n’est reproduite ; le Core live n’avait aucune projection V4 avant le test.

## Sauvegarde et image de rollback

- Répertoire privé : `~/.local/state/atlas-core/backups/v7-core-20261030T215248Z` (`0700`).
- SQLite live sauvegardé par l’API `sqlite3.backup` dans le conteneur, puis extrait par `docker exec cat` :
  `PRAGMA quick_check=ok`, 19 tables, SHA-256
  `a50967e9f1c75b9567ae9f17c2412a730aa46b14576aa2c981850956815cd122`, 205 KiB.
- Ancienne image conservée et taguée `atlas-core-atlas-core:pre-v7` :
  `sha256:5107e0c144361faf18711dce62359eff3bb92b2583ff6b1f690c79deea3d7b39`.
- Nouvelle image construite depuis le SHA d’intégration `5a56a6d` (V7-01C + V7-01E) :
  `sha256:00b88ef904669169f381073d969a9c7cbd94451bfccc616c653a46d1746554c3`.
- `compose config --quiet` : OK. Recréation avec
  `docker compose -p atlas-core -f compose.yaml -f compose.openclaw.yaml up -d --force-recreate --no-build --wait`.
- Premier démarrage en échec : le secret Gateway était `0600` et illisible par l’UID 10001.
  Correction : copie de livraison en `0444` dans le parent `0700`, comme les autres secrets Core ;
  redémarrage, puis `healthy`. Le fichier de sauvegarde reste `0600`.

## État live vérifié

- `/health` : HTTP 200 `{"status":"ok","mode":"openclaw"}`.
- `/internal/v4/capabilities` (token outil, en-têtes V4) : HTTP 200, les sept flags vrais :
  `day_plan.read`, `activity_status.read`, `plan_revision.propose`, `usage_summary.read`,
  `goal_progress.read`, `training_plan.read`, `coach.chat`.
- `/v2/agent/status` (token Android) : HTTP 200, `adapter=openclaw`, `connected=false`
  (le statut de connectivité de l’adaptateur n’est pas l’oracle du chat ; voir ci-dessous).
- Chat V4 réel synthétique, sans catégorie partagée :
  `POST /v4/agent/chat` HTTP **200**, `agent_status=ok`, `tool_state=none`,
  `tool_call_count=0`, `proposal_ids=[]`, réponse non vide (46 caractères). Aucun fallback mock.
- Deuxième chat V4 synthétique demandant `atlas_get_day_plan` : HTTP 200, `agent_status=ok`,
  `tool_state=none` (le modèle n’a pas appelé le client tool sans données/consentement).
- Outil natif plugin après B : `GET /internal/v4/day-plan` reçu par le Core et répondu
  **403 `CONSENT_REQUIRED`** (aucun consentement Android live). Le chemin réseau/token/capability
  du plugin est donc établi ; le refus est le gate de consentement attendu.
- Conteneur : image `00b88ef…`, `read_only=true`, UID 10001, `healthy`, volume
  `atlas-core-data` conservé. Les projections V4 live étaient toutes à zéro avant le test.

## Limites et rollback

- `adapter.connected=false` sur `/v2/agent/status` reste à expliquer ; le chat V4 réel prouve que
  l’adaptateur et le Gateway fonctionnent. Ne pas utiliser ce champ comme verdict produit.
- Le Pixel n’a pas encore de consentement ni de projection V4 ; les outils natifs et le coach
  resteront sans données tant que D n’a pas synchronisé.
- Rollback : `docker tag atlas-core-atlas-core:pre-v7 atlas-core-atlas-core:latest`, puis
  `docker compose -p atlas-core -f compose.yaml up -d --force-recreate --no-build atlas-core`.
  Le volume et la base ne doivent pas être supprimés. Aucun `down -v`.
