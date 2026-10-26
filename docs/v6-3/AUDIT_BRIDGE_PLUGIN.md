# V63-R3 — Audit Bridge ChatGPT + plugin OpenClaw (lecture seule)

| Élément | Valeur |
|---|---|
| TASK_ID | `V63-R3-SCOUT-01` |
| Session Paseo | `c818a83c-46db-473b-b38c-823be3a108f3` |
| Profil | `provider=codex`, `model=gpt-6-luna`, `effectiveThinkingOptionId=xhigh` |
| Base | `f625f0de21725485c91247e96986fdccd4eee597` |
| Worktree | `audit/v63-r3-bridge-plugin`, `chmod -R a-w`, HEAD et statut inchangés |
| Bridge | `$HOME/perso/work/Bridges/chatgpt-bridge` @ `0a181f844c09d68b62d0e17e3c3d6e83e34a58aa` |
| OpenClaw | installé `2026.10.5` ; `references/repos/openclaw` absent du worktree (ignoré par Git), audit sur l'installation locale |
| Tests | **aucun exécuté** ; aucun appel réseau, aucune installation, aucun redémarrage |

## 1. Plugin `defineToolPlugin` — structure vérifiée

- SDK public `openclaw/plugin-sdk/tool-plugin` ; paquet ESM TypeScript ;
  Node ≥ 24.16 ; `typebox` en dépendance runtime.
- Scaffold : `src/index.ts`, `src/index.test.ts`, `tsconfig.json`
  (`ES2022`, `NodeNext`, `strict`, `outDir: dist`), `vitest.config.ts`,
  `package.json`, `openclaw.plugin.json` généré.
- `openclaw plugins build` génère les métadonnées ; `openclaw plugins validate`
  vérifie manifeste, outils déclarés et chemin `openclaw.extensions`.
- `execute(params, config, context)` reçoit la configuration typée et un
  `context.toolCallId` stable.

## 2. Configuration et secrets

Schéma recommandé :

```ts
configSchema: Type.Object({
  bridgeUrl: Type.String({ minLength: 1 }),
  apiKey: Type.String({ minLength: 1 }),
}, { additionalProperties: false })
```

Configuration OpenClaw recommandée, sans valeur secrète dans le code ni le
manifeste :

```json5
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
}
```

OpenClaw substitue `${VAR}` ; une variable manquante reste non résolue et le
plugin doit refuser sans afficher la valeur. `BRIDGE_WS_TOKEN` est réservé à
l'extension Chrome et ne doit jamais être transmis au plugin.
**SecretRef n'est pas vérifié pour cette clé de plugin tierce** : utiliser la
substitution d'environnement, pas un objet SecretRef supposé compatible.

## 3. Isolation planificateur / recherche — `BLOCKED_POLICY`

État actuel : `agents.entries.atlas.tools.deny: ["*"]`. Ce refus bloque aussi
`sessions_spawn`, donc l'agent `atlas` ne peut pas déléguer à un agent de
recherche. `subagents.allowAgents` ne rétablit pas un outil bloqué.

Correction minimale proposée (à valider et approuver avant toute modification
de la configuration active) :

```json5
agents: {
  entries: {
    atlas: {
      tools: { profile: "full", allow: ["sessions_spawn"] },
      subagents: { allowAgents: ["atlas-research"], requireAgentId: true },
    },
    "atlas-research": {
      tools: { profile: "full", allow: ["atlas_chatgpt_research"] },
    },
  },
}
```

Effet : le planificateur ne reçoit que `sessions_spawn` (pas le Bridge, pas
d'outil arbitraire) ; le chercheur ne reçoit que
`atlas_chatgpt_research`. Le refus global `["*"]` est remplacé par une
allowlist étroite, pas supprimé au profit de tous les outils.
**Cette modification de la configuration OpenClaw active et tout redémarrage
exigent un accord ciblé du propriétaire.** Sans accord, le parcours
« discussion dans `atlas` → recherche » reste `BLOCKED_POLICY` ; le plugin
peut néanmoins être construit et validé hors activation.

Gap restant : le planificateur doit savoir quand déléguer et comment nommer
l'agent de recherche. Ce point devra être traité dans le prompt/instructions
OpenClaw du ticket L8-02, sans exposer le Bridge directement au planificateur.

## 4. Contrat Bridge vérifié

- `POST /v1/bridge/runs` exige `input` ; `requested_model`, `web_search`,
  `background`, `allow_unverified_model`, `request_id`, `conversation`,
  `recovery`, `response_format`, `reasoning_effort`, `ui_model`, `profile`
  sont optionnels.
- Pour le plugin : `input` texte, `web_search: true`, `background: true`,
  `request_id` stable.
- `X-Idempotency-Key` et `request_id` doivent concorder. Le hash canonique
  couvre le JSON hors `request_id` ; même clé + même payload → même run ;
  même clé + contenu différent → `409 bridge_payload_conflict`.
- Utiliser `context.toolCallId` comme `request_id` et comme clé d'idempotence.
- `background: true` → POST retourne l'ID et `queued`/`running` ; le plugin
  interroge `GET /v1/bridge/runs/{id}`. Le GET accepte aussi la clé
  d'idempotence exacte.
- États : `queued`, `running`, `completed`, `failed`, **`needs_review`**.
- Timeouts effectifs du code : 300 s d'inactivité extension, 3600 s total,
  30 s pour les contrôles UI. Le README annonce encore 120/900 s : retenir le
  code et la doc opérations.
- `/health` ne prouve pas la disponibilité de l'extension ; `/ready` distingue
  l'extension absente.
- Runs terminaux conservés 7 jours par défaut.
- Après perte de réponse : rejouer le même POST avec la même clé et le même
  payload. Un redémarrage pendant un run laisse `submission_attempted` ; pas
  de resoumission implicite.
- Une clé différente crée un autre run qui attend le slot UI ; la
  sérialisation n'est pas une déduplication. Prévoir une admission
  single-flight côté plugin.

## 5. Provenance et limites

- `metadata.web_search_mode` : `ui_tool`, `prompt_instructed`, `off`,
  `off_unverified`, `untouched`.
- `ui_tool` prouve que le contrôle UI de recherche a réussi pour ce run, pas
  que le modèle a consulté une source récente.
- Citations : `metadata.visible_citations` (`label`, `url`, `canonical_url`,
  `position`). Pas de propriété `sources` ni de date de publication.
- `created_at` est l'horodatage de la réponse Bridge, pas la date d'une
  source.
- `request_id` sert à l'idempotence et n'est pas un champ de provenance
  renvoyé ; l'ID de run est `id`.
- Une citation visible prouve un lien présenté par l'UI, pas sa date ni sa
  fiabilité.
- **Environnement actuel** : le Bridge réel retourne
  `web_search = prompt_instructed` et `web_search_toggle = unavailable`.
  Toute recherche sera donc qualifiée `PROMPT_INSTRUCTED_UNVERIFIED`, jamais
  « recherche récente vérifiée ».

## 6. Matrice des 16 recettes (condensée)

| # | Scénario | Mock plugin | Réel requis |
|---|---|---|---|
| 1 | Service indisponible | rejet réseau typé | Bridge absent |
| 2 | Clé absente/incorrecte | 401, aucune fuite | auth Bridge |
| 3 | Extension déconnectée | `/ready` ou 503 | extension réellement absente |
| 4 | Extension connectée | fixture `/ready` 200 | `/ready` réel |
| 5 | Recherche `web_search:true` | payload seul | Bridge + UI ChatGPT |
| 6 | Métadonnée de recherche | cinq modes | UI réelle |
| 7 | Sources | fixture `visible_citations` | sources visibles |
| 8 | Aucune source fiable | fixture vide | règle de confiance ou revue humaine |
| 9 | JSON malformé | parse/validation | sortie réelle non déterministe |
| 10 | Timeout | fetch/poll lent + deadline | bornes backend |
| 11 | Déconnexion en cours de run | erreur après acceptation | déconnexion contrôlée |
| 12 | Reprise par ID | POST puis GET | Bridge réel |
| 13 | Même clé, même requête | clé/corps identiques | déduplication SQLite |
| 14 | Même clé, corps différent | 409, aucun retry | API réelle |
| 15 | Injection via page | réponse hostile simulée | page hostile + modèle |
| 16 | Aucun outil de mutation | un seul tool déclaré | liste d'outils effective |

## 7. Risques

- Injection de page : le Bridge ajoute une consigne « pages non fiables »
  (`generation.py:1335`), ce qui ne prouve pas la résistance du modèle.
  Toute réponse/citation est une donnée non fiable.
- Fiabilité des sources : aucune politique de domaine fiable ; le plugin peut
  signaler « aucune citation » mais ne doit pas inventer un verdict.
- JSON malformé : revalider côté client ; échec = résultat refusé.
- Extension déconnectée : état dégradé, pas de relance en boucle.
- Timeout/quota : `bridge_rate_limited` + `Retry-After` ; pas de contrat de
  quota ChatGPT distinct vérifié.
- Deuxième génération active : admission/single-flight, pas de second run
  aveugle.

## 8. Fichiers et oracle proposés pour V63-L8-02

Placement proposé (à tracer dans `DECISIONS_AUTOMATIQUES.md`) :

```text
integrations/openclaw/atlas_chatgpt_research/
  package.json
  package-lock.json
  openclaw.plugin.json        # généré
  tsconfig.json
  vitest.config.ts
  README.md
  examples/openclaw.config.example.json5
  src/index.ts
  src/bridge-client.ts
  src/contracts.ts
  src/index.test.ts
  src/bridge-client.test.ts
  src/recipes.test.ts
  dist/index.js               # généré
```

Un seul outil : `atlas_chatgpt_research`, `optional: true`, schéma limité aux
données abstraites permises par §6.10. Le client envoie `background: true`,
`web_search: true`, `request_id = toolCallId`, poll le run et valide la sortie
avant de la retourner.

Oracle local (après création du paquet et dépendances verrouillées) :

```bash
npm test
npm run plugin:build
openclaw plugins build --entry ./dist/index.js --check
openclaw plugins validate --entry ./dist/index.js
```

L'inspection effective des outils via le Gateway et l'activation exigent un
accord ciblé et un redémarrage contrôlé.

## 9. Verdict de l'audit

**GO_WITH_LIMITATIONS** pour construire et valider le plugin hors activation.
**BLOCKED_POLICY** pour le parcours discussion `atlas` → recherche tant que
`tools.deny: ["*"]` reste en place : la correction limitée proposée doit être
approuvée, appliquée et validée. Aucun test runtime n'est revendiqué ; la
recherche restera `PROMPT_INSTRUCTED_UNVERIFIED` dans l'environnement actuel.
