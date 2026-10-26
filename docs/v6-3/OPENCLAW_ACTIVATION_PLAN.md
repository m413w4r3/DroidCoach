# OpenClaw — plan d'activation du plugin de recherche (non appliqué)

Ce document prépare une **demande d'autorisation ciblée**. Aucune
configuration active n'a été modifiée, aucun plugin installé, aucun service
redémarré.

## État actuel vérifié

- OpenClaw `2026.10.5`, gateway loopback `127.0.0.1:18789`, auth token.
- Agent `atlas` : `tools.deny: ["*"]`.
- `references/repos/openclaw` absent des worktrees (ignoré), mais l'installation
  locale fait foi.
- Bridge `127.0.0.1:8001` actif, extension connectée, `web_search =
  prompt_instructed` (toggle UI indisponible).
- `.env` ATLAS contient `ATLAS_BRIDGE_API_KEY` ; le Bridge attend
  `Authorization: Bearer <BRIDGE_API_KEY>`.

## Politique cible (proposée, à valider)

```json5
agents: {
  entries: {
    atlas: {
      tools: { profile: "full", allow: ["sessions_spawn"] },
      subagents: { allowAgents: ["atlas-research"], requireAgentId: true },
    },
    "atlas-research": {
      // workspace et modèle à réutiliser depuis la configuration existante,
      // sans accès shell/fichiers
      tools: { profile: "full", allow: ["atlas_chatgpt_research"] },
    },
  },
},
plugins: {
  entries: {
    atlas_chatgpt_research: {
      enabled: true,
      config: {
        bridgeUrl: "http://127.0.0.1:8001",
        apiKey: "${BRIDGE_API_KEY}",
      },
    },
  },
},
```

Points à vérifier avant application :
- `profile: "full"` + `allow` restreint réellement aux outils listés ; sinon,
  utiliser la forme d'allowlist effectivement supportée par 2026.10.5.
- `atlas-research` reçoit uniquement `atlas_chatgpt_research` et aucun outil
  shell/fichier/session.
- `sessions_spawn` est bien disponible pour `atlas` malgré `subagents`.
- Le planificateur ne reçoit jamais directement le Bridge.
- La variable `BRIDGE_API_KEY` doit être présente dans l'environnement du
  gateway, ou le plugin doit lire un fichier de secret local en 0600. Aucune
  clé dans le code, le manifeste ou un log.

## Secrets

- Ne pas copier `ATLAS_BRIDGE_API_KEY` dans un prompt, un commit ou un fichier
  versionné.
- Option A : variable d'environnement du gateway (nécessite un redémarrage
  contrôlé).
- Option B : fichier local 0600 référencé par une option `apiKeyFile` du plugin
  (à ajouter au plugin si retenue).
- Le `.env` ATLAS reste la source locale ; ne pas le copier dans le dépôt
  OpenClaw.

## Procédure d'activation (après accord)

1. Sauvegarder `~/.openclaw/openclaw.json` en
   `~/.openclaw/openclaw.json.pre-atlas-research-<date>` (0600), vérifier la
   copie.
2. Construire et valider le plugin :
   `npm test`, `npm run plugin:build`, `openclaw plugins validate`.
3. Installer le plugin localement (`openclaw plugins install <path>` ou lien
   contrôlé), sans l'activer si possible.
4. Appliquer la politique d'agents et l'entrée plugin avec une écriture
   atomique ; conserver la sauvegarde.
5. Recharger/redémarrer le gateway de façon contrôlée ; vérifier
   `openclaw plugins inspect atlas_chatgpt_research --runtime --json` et la
   liste d'outils effective de `atlas`/`atlas-research`.
6. Exécuter **une** recherche réelle bornée via l'agent `atlas` :
   - vérifier que la sortie contient `search_mode`, `verified_search`, des
     citations visibles et `run_id` ;
   - si `prompt_instructed`, qualifier `PROMPT_INSTRUCTED_UNVERIFIED` ;
   - ne déclencher aucune action ATLAS.
7. Consigner les preuves : SHA du plugin, configuration appliquée (sans
   secret), `run_id`, mode de recherche, limites.

## Risque

- Le planificateur obtient `sessions_spawn` : il peut déléguer à
  `atlas-research`, pas exécuter d'outil arbitraire.
- L'agent de recherche utilise la session ChatGPT web déjà authentifiée via le
  Bridge ; aucune donnée ATLAS sensible ne doit être transmise.
- Une page hostile peut tenter d'injecter des instructions ; la sortie reste
  une donnée non fiable et ne devient jamais une autorisation.
- Le redémarrage du gateway interrompt temporairement les usages existants.

## Retour arrière

- Restaurer `~/.openclaw/openclaw.json` depuis la sauvegarde.
- Désactiver/désinstaller le plugin.
- Recharger/redémarrer le gateway.
- Vérifier que `atlas` retrouve `tools.deny: ["*"]` et que
  `atlas-research` n'est plus accessible.
- Aucune donnée ATLAS n'est modifiée par cette activation.

## Test attendu

- `atlas` ne peut pas appeler directement `atlas_chatgpt_research`.
- `atlas-research` ne peut appeler que `atlas_chatgpt_research`.
- Une recherche réelle via `atlas` retourne un résultat structuré avec
  provenance et limites.
- Aucun outil de mutation (shell, fichiers, calendrier, wger) n'est accessible.
- En cas d'échec de config : `BLOCKED_POLICY`, aucune revendication de
  fonctionnement.

## État final à restaurer si le test échoue

Configuration OpenClaw d'origine, plugin désactivé, gateway redémarré, agent
`atlas` avec `tools.deny: ["*"]`.
