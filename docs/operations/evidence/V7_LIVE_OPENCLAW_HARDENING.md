# V7 — durcissement OpenClaw live (scope A)

Date : 2026-10-30  
Autorisation : scopes A–D donnés explicitement par le propriétaire.  
Périmètre appliqué : **A uniquement** (Gateway/OpenClaw). Core, relais et Pixel non modifiés à ce stade.

## Sauvegarde

- Répertoire privé : `~/.openclaw/backups/v7-code-mode-20261030T213932Z` (`0700`).
- `openclaw.json` sauvegardé avant modification, mode `0600`, SHA-256 :
  `1d78935cc5b83d7a6a4c9795831bed7f43bc3b6b6c2c693ae1f0838bf2a7bb38`.
- Ancien token plugin sauvegardé, mode `0600`, SHA-256 :
  `c050bb3afe6b5c2b76bc3f0d953d16ca98c99e10f2cd9c8733e319b4a711534e`.
- État agent et workspace copiés dans `atlas-agent/` et `atlas-workspace/` ; manifeste
  `AGENT_SHA256SUMS` ; copie SQLite contrôlée `PRAGMA quick_check=ok`, 117 fenêtres de session.
- Aucune valeur de token n’a été affichée.

## Changement appliqué

- `agents.entries.atlas.tools` : suppression de `alsoAllow` (qui produisait une allowlist
  implicite avec wildcard), `profile=full` **filtré par** `allow=["atlas_core_tools"]`,
  refus explicites `gateway`, `session_status`, `presence`, `exec`, `process`,
  `code_execution`, `wait`.
- `agents.entries.atlas.tools.codeMode.enabled=false` et `tools.codeMode.enabled=false`.
- `plugins.entries.atlas_core_tools.config.coreBaseUrl` : `18100` → `http://127.0.0.1:8100`.
- `tokenFile` du plugin aligné sur le token outil du Core live : comparaison après `trim()`,
  valeurs égales, longueur 64 ; aucune valeur affichée.
- `openclaw config validate` : `Config valid`.
- Gateway arrêté (`--force`, autorisé), redémarré : service `openclaw-gateway.service`,
  `Runtime: running`, connectivité OK, `127.0.0.1:18789`.

## Preuves runtime

1. Chat synthétique simple : HTTP 200, `finish_reason=stop`, aucun tool call, réponse non vide.
2. Chat synthétique « Code Mode » : HTTP 200, aucun tool call, réponse directe.
3. Trajectoire de la session synthétique `v7-code-mode-check-3f9cf38acf824abab7162cd0ee49531b` :
   `context.compiled (9 tools)`, `tool_search ok`, `model.completed` ; **aucun `exec`, aucun `wait`,
   aucune cellule Code Mode**.
4. Chat synthétique « lis mon plan » : `context.compiled (9 tools)`, `tool_search`,
   `tool_call`, `atlas_get_day_plan`, puis `tool.result error` ; le Core live a reçu
   `GET /internal/v4/day-plan` et répondu **403 Forbidden** (capabilities V4 encore désactivées).
   Cela corrèle le plugin natif, le `coreBaseUrl` et l’authentification outil.
5. `tools.catalog` liste toujours les six outils `atlas_*` (`pluginId=atlas_core_tools`,
   `optional=true`). `tools.effective` reste un aperçu incomplet (66 Core `excluded`, aucun
   ATLAS), conformément à la limite déjà auditée ; il n’est pas utilisé comme preuve d’exécution.

## Verdicts

- `CODE_MODE_EXEC: MITIGATED — runtime` : plus de surface `exec`/`wait` observée ; allowlist
  explicite sans wildcard.
- `NATIVE_PLUGIN_PATH: PASS_ONE_TOOL` : `atlas_get_day_plan` exécuté par le plugin et reçu par
  le Core live (403 attendu, capabilities désactivées).
- `SIX_TOOLS_EFFECTIVE: PARTIAL` : six entrées cataloguées et allowlistées ; une exécution
  native corrélée ; preuve des six et de la proposition à poursuivre après activation Core.
- `CORE_LIVE: NOT_CHANGED` : le Core reste `mode=mock`, capabilities V4 fausses, volume intact.
- `PIXEL: NOT_RUN`. `RELAY: NOT_STARTED`.

## Rollback

Restaurer `openclaw.json` et l’ancien token plugin depuis le répertoire de sauvegarde, puis
redémarrer le service Gateway. Les fichiers d’identité/workspace n’ont pas été modifiés.
