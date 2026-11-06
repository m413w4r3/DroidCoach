# V7-10 A2 — Preuve d’implémentation du cycle d’expiration

**TASK_ID :** `V7-10-A2-EXPIRATION-LIFECYCLE`
**Branche :** `v7-10/a2-expiration`
**Base :** `fa82af32f3bfed18d54476c9ef1b9b88092d6d36`
**Portée :** Core/backend, contrat d’erreur, compatibilité connecteur Relay, outil hors ligne, tests et documentation. Aucun fichier Android modifié.

## Commits d’implémentation

| Étape | SHA | Contenu principal |
|---|---|---|
| (a) expiration, audit, balayeur | `d84379413838b9d9d3d24541fccf37fd3199e486` | `backend/atlas_backend/{main.py,store.py,v4_projection_store.py,v4_router.py}`; migration v5, helper transactionnel, expiration pull/ACK et sweeper de lifespan |
| (b) ACK et concurrence | `394dc474837973c5f63b84e3a43bbc49b509503c` | `backend/atlas_backend/{v4_projection_store.py,v4_router.py}`, `contracts/v4/error.schema.json`, `integrations/relay/pc/src/backend.ts` |
| (c) erreurs chat STALE_DATA | `2d3627c5453239721013f1e716d6aca0805e81d2` | `backend/atlas_backend/v4_router.py`; échec d’outil `STALE_DATA` renvoyé comme 409 standard assaini |
| (d) réconciliation hors ligne | `8c6fb729e585fb564d17e9c3d77c6d3e0dae9eea` | `backend/scripts/atlas_v4_expiration_reconcile.py`, complément du stockage dans `backend/atlas_backend/v4_projection_store.py` |
| (e) tests lifecycle | `0c0447fab596f6f01aecd37fde549d973fd76b6e` | `backend/tests/conftest.py`, tests expiration/propositions/chat/migration/propriété |

Le commit séparé des deux documents est celui qui porte ce fichier; son SHA figure dans le rapport final de clôture.

## Résultats de tests

Les suites ont été exécutées sur le code au SHA `0c0447fab596f6f01aecd37fde549d973fd76b6e`.

| Suite | Commande exacte | Résultat |
|---|---|---|
| Backend | `cd backend && ATLAS_AGENT_ADAPTER=mock /tmp/v7-10-a2-backend-venv/bin/python -m pytest -q` | **PASS** — 442 passed, 1 skipped, 3 warnings, 42.34 s. Le skip requiert `ATLAS_REAL_GATEWAY`. Warnings : dépréciation AnyIO `BlockingPortal` et deux operation IDs OpenAPI dupliqués. |
| Connecteur PC | `cd integrations/relay/pc && npm ci --offline && npm run test:unit` | **PASS** — 119 tests, 18 fichiers; compilation TypeScript (`tsc`) réussie. `npm ci` a signalé le script `esbuild@0.28.2` comme en attente d’autorisation (`allow-scripts`). |
| Proxy Relay | `node --test --test-skip-pattern='pinned relay integration on 4402' integrations/relay/proxy/test/proxy.test.mjs` | **PASS** — 18 tests, 18 pass, 0 fail. Le cas exact `pinned relay integration on 4402` est **EXCLU / NON_EXÉCUTÉ** par le runner avant son callback. |

### Environnement et limites d’exécution

- La version exacte de Python du venv temporaire était 3.12.4; `backend/.venv` n’était pas présent. Le backend a donc été vérifié avec le venv isolé sous `/tmp`, installé depuis `backend/requirements.txt`.
- `npm ci --offline` et les tests connecteur ont nécessité l’exécution hors sandbox après le blocage `EPERM` du script d’installation esbuild.
- Le proxy utilise des listeners de test loopback éphémères. La première tentative sandboxée a échoué avec `listen EPERM`; la commande filtrée canonique ci-dessus a passé hors sandbox. La commande `--test-name-pattern` a été vérifiée avec un test factice et n’excluait pas sûrement le cas imbriqué sous Node 24; elle n’a pas été utilisée sur le fichier proxy source.
- Node utilisé pour les tests proxy : `v24.18.0`.
- Pas de tests Android, AVD ou Pixel exécutés. Aucun test connecté à une base ou un service live n’a été lancé pendant la vérification documentée ici.
- Aucun dry-run/apply du réconciliateur n’a été exécuté contre une base réelle. Les tests automatiques utilisent uniquement des bases temporaires.

## Incident de test antérieur — relais local 4402

Avant cette reprise, le cas préexistant `pinned relay integration on 4402` a été exécuté **deux fois** et a contacté le relais local. Ces deux exécutions sont hors de la vérification filtrée ci-dessus. Les opérations observées étaient en lecture seule : `GET /health`, `GET /ready`, upgrade WebSocket vers `/ws?serverId=synthetic&role=server&v=2`, puis fermeture. Aucune configuration ni donnée n’a été modifiée. Le cas n’a pas été relancé depuis que l’exclusion explicite a été posée; le runner canonique l’a omis avant le callback.

## Couverture incluse

Les tests ajoutés/exécutés couvrent les bornes avant/à/après échéance, batch/ordre/idempotence, rollback si l’audit échoue, `busy_timeout`, DB vide, reprise après redémarrage, sweeper répété et lifespan, migration depuis v4, pull/page/cursor, proposition jamais tirée, `UNCERTAIN`, consentement/révocation, matrice ACK tardif/prématuré/rejeu/conflit, course APPLY/REJECT vs sweeper et propriété A/B après expiration. Les tests chat vérifient `STALE_DATA` avant et après appel d’outil et les non-régressions d’erreurs associées.

## État final et risques ouverts

- **PASS** : commits (a)–(e) présents, suites backend et connecteur vertes, proxy filtré vert; la migration est additive v5 et l’audit d’expiration est séparé des ACK.
- **NON EXÉCUTÉ** : réconciliation sur une vraie base; déploiement/migration d’une instance réelle; QA Android/AVD/Pixel.
- **P2** : `UNCERTAIN` reste ouvert jusqu’à une réconciliation explicite; il n’est pas expiré automatiquement.
- **Risque opérationnel** : l’application de la réconciliation est transactionnelle par batch, pas globalement atomique; en cas d’arrêt, restaurer le backup vérifié selon [la procédure de retour arrière](../V7-10_EXPIRATION_LIFECYCLE.md#retour-arrière-opérateur).
- **Registre** : aucun registre A2 n’a été trouvé dans `.agents/` (seuls `WORKFLOW.md` et `TASK_TEMPLATE.md`); aucun chemin de registre n’a été inventé.
