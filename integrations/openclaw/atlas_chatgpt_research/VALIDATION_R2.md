# Validation R2 — plugin `atlas_chatgpt_research`

| Élément | Valeur |
|---|---|
| Ticket | `R2-BRIDGE-QA-01` |
| Base | `993951fc13ac9f7ba23548d846fab6cac01418db` |
| Environnement | Node `v24.18.0`, npm `11.16.0`, OpenClaw `2026.10.5` |
| SDK local | `openclaw@2026.10.5`, import `openclaw/plugin-sdk/tool-plugin` |
| Dépendances ciblées | `typebox@1.3.34`, `vitest@3.2.6` |
| Activation | **`BLOCKED_POLICY`** |

## Commandes et résultats

Les commandes ont été lancées depuis `integrations/openclaw/atlas_chatgpt_research/`.

| Commande | Résultat |
|---|---|
| `npm ci` dans le sandbox | `BLOCKED` au premier essai : le contrôle local de version d’esbuild a reçu `EPERM` lors du lancement de son binaire. |
| `npm ci` dans le dossier du plugin après autorisation d’exécution locale | `PASS` : 393 paquets installés; aucun changement de lockfile. npm a signalé 10 vulnérabilités dans l’arbre installé (3 modérées, 5 élevées, 2 critiques). |
| `npm test` | `PASS` : 3 fichiers, 50 tests réussis. Vitest utilise un `fetch` simulé; aucune requête HTTP réelle. |
| `npm run plugin:build` | `PASS` : TypeScript compilé et métadonnées OpenClaw générées. |
| `openclaw plugins build --entry ./dist/index.js --check` | `PASS` : `Plugin metadata is up to date.` |
| `openclaw plugins validate --entry ./dist/index.js` | `PASS` : `Plugin atlas_chatgpt_research is valid.` |
| `npm ls openclaw typebox vitest --depth=0` | `PASS` : versions conformes au tableau ci-dessus. |

Le manifeste généré déclare le seul outil `atlas_chatgpt_research` avec `optional: true`. Le SDK confirme le même outil et aucune capacité shell, fichier, session ou mutation. Aucun secret n’est présent dans le manifeste; les tests génèrent leur valeur de clé éphémère en mémoire.

## Matrice des 16 recettes

| # | Exigence | Preuve locale | Résultat |
|---:|---|---|---|
| 1 | `/health` indisponible : erreur typée, sans crash | `BridgeClient.checkReadiness`; sortie stable de `executeResearchTool` | `PASS` |
| 2 | 401/403 : erreur stable, aucune fuite de clé | erreurs auth simulées; sortie et logs contrôlés | `PASS` |
| 3 | 429 avec `Retry-After` : délai conservé et nombre de tentatives borné | délai injecté vérifié; seconde réponse 429 conservée dans `retryAfterMs`; deux POST maximum | `PASS` |
| 4 | Timeout borné | timeout injecté, reprise par la même clé et délai global borné | `PASS` |
| 5 | JSON malformé ou schéma invalide refusé | réponse de run, réponse de recherche et citations invalides refusées sans résultat partiel | `PASS` |
| 6 | 409 `bridge_payload_conflict` sans retry avec corps différent | une seule tentative conflictuelle; aucun nouveau run | `PASS` |
| 7 | Même clé et même payload : même run sans doublon | corps et clés identiques; fixture ne crée qu’un run | `PASS` |
| 8 | `background: true` : POST puis poll du run | fixture POST `queued`, puis GET par identifiant jusqu’au terminal | `PASS` |
| 9 | États `queued`, `running`, `completed`, `failed`, `needs_review` | progression de poll et cas terminaux; aucune réponse partielle pour les échecs/revue | `PASS` |
| 10 | `/ready` signale l’extension absente | `/ready` simulé en 503; état `unavailable` explicite, aucun POST | `PASS` |
| 11 | `visible_citations` conservées comme données non fiables | citations lues uniquement depuis les métadonnées Bridge et rendues structurées | `PASS` |
| 12 | `prompt_instructed` reste non vérifié | marqueur `PROMPT_INSTRUCTED_UNVERIFIED`; `verified_search: false` | `PASS` |
| 13 | Contenu hostile traité comme donnée, aucune action | texte hostile refusé comme réponse, sans appel supplémentaire ni exécution | `PASS` |
| 14 | Aucun outil de mutation | SDK et manifeste généré : un outil de recherche facultatif seulement | `PASS` |
| 15 | `toolCallId` utilisé comme `request_id` et clé d’idempotence | assertions sur le corps et `X-Idempotency-Key` | `PASS` |
| 16 | Aucune clé dans les logs, erreurs ou fixtures | clés de test éphémères, erreurs génériques, sorties et logs vérifiés | `PASS` |

## Provenance et limites

- `search_mode` conserve la valeur Bridge. `verified_search` n’est vrai que pour `ui_tool`; cela atteste l’activation du contrôle UI pour ce run, sans garantir l’actualité ni la fiabilité des sources.
- `prompt_instructed` produit le marqueur `PROMPT_INSTRUCTED_UNVERIFIED` et reste `verified_search: false`. Le plugin ne présente jamais ce mode comme une recherche vérifiée.
- Les citations proviennent uniquement de `metadata.visible_citations`. Le plugin valide leur forme et leur URL HTTPS, mais ne les ouvre pas et ne leur accorde aucune confiance particulière.
- Les textes de recherche, résultats et citations restent des données non fiables. Les sorties qui ne respectent pas le contrat ou contiennent les motifs hostiles détectés sont refusées sans champs partiels; cela ne constitue pas une preuve de résistance du modèle aux injections.
- `needs_review`, erreur ambiguë, absence du Bridge et absence d’extension ne déclenchent aucune action Android et ne fournissent aucun résultat partiel.
- Aucun Bridge réel, navigateur, extension ChatGPT, recherche UI, Gateway actif ou configuration personnelle n’a été appelé ou modifié. Ces vérifications restent `NOT_RUN`.

## Politique d’accès et activation / retour arrière

Politique prévue : le planificateur `atlas` ne reçoit pas le Bridge et ne peut que déléguer à l’agent de recherche autorisé. L’agent de recherche ne reçoit que `atlas_chatgpt_research`. Le plugin demeure optionnel et en lecture seule.

Activation réelle : **`BLOCKED_POLICY`**. Ce ticket n’autorise ni installation ni changement de configuration OpenClaw active, ajout d’allowlist, activation, appel du Bridge réel ou redémarrage du Gateway. Avant toute activation, demander un accord ciblé couvrant explicitement le diff de `~/.openclaw/openclaw.json`, l’installation/activation du plugin, l’accès de l’agent de recherche au seul outil ci-dessus, la disponibilité de `BRIDGE_API_KEY` par environnement et le reload/redémarrage du Gateway. Présenter le diff et confirmer que le planificateur n’a pas accès au Bridge.

Après cet accord seulement, la procédure proposée est de sauvegarder la configuration active, installer/activer le plugin, appliquer les allowlists minimales ci-dessus, puis valider l’inventaire effectif des outils avant tout essai. Ne jamais placer la valeur de `BRIDGE_API_KEY` dans le manifeste, les logs ou le dépôt.

Retour arrière proposé : désactiver le plugin, retirer son entrée active et restaurer les allowlists sauvegardées; révoquer ou retirer la variable d’environnement si elle a été dédiée à cet essai. Si la configuration Gateway doit être rechargée, demander un accord ciblé couvrant ce reload. Cette qualification n’a effectué aucune de ces opérations.
