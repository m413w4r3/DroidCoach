# V7-10 A1 — Preuve d’implémentation

- **Task ID:** `V7-10-A1-DEVICE-AUTH`
- **Base SHA:** `710a49a0094e49ae1e794d009e55536fe1aba7fe`
- **Branche:** `v7-10/a1-device-auth`
- **Profil effectif:** `codex / gpt-6-luna / xhigh`, confirmé par l’orchestrateur Paseo.
- **Portée:** Core backend V4, connecteur Relay PC, CLI offline, tests et documentation. Aucun changement Android.

## Commits locaux

1. `c12340b5b9d0a99fb8c60ec5aa34023e6fa1b4c2` — `feat(v7-10): add core device principal registry and auth modes`
2. `c480d7487a79533440a39b1671be5966aa16884b` — `feat(v7-10): scope proposals and cursors to authenticated device`
3. `d2cebefad0e2326e5d7a35003e9ddec2a6eb780f` — `feat(v7-10): bind relay sessions to core principals with signed assertions`
4. `c5b097e419188d10393b84a30e71b20138e6678a` — `test(v7-10): prove per-device authentication isolation`
5. Documentation and this evidence are committed together as the final documentation commit; its SHA is recorded in the final handoff and Git history (a commit cannot contain its own SHA).

## Fichiers livrés

- Core: `backend/atlas_backend/store.py`, `v4_auth.py`, `v4_assertion.py`, `v4_router.py`, `v4_projection_store.py`, `v4_tool_service.py`.
- Provisionnement offline: `backend/scripts/atlas_v4_principals.py`.
- Tests: `backend/tests/conftest.py`, `test_v4_principal_auth.py`, `test_v4_principal_cli.py`, `test_v4_migrations.py`, `test_v4_projection_store.py`, `test_chat_orchestration_v4.py`.
- Relay: `integrations/relay/pc/src/{backend,connector}.ts`, `scripts/{check-config,check-secret}.mjs`, `scripts/lib/config.mjs`, tests `config-env.test.ts`, `connector-resilience.test.ts`, `operations.test.ts`.
- Docs: `docs/operations/V7-10_SECURITY_MIGRATION.md`, ce rapport, `docs/operations/evidence/V7-10_A1_TASK_REGISTRY.md` et l’ajout V7-10 à `docs/DECISIONS_AUTOMATIQUES.md`.

## Vérifications

| Statut | Commande / preuve | Résultat |
|---|---|---|
| PASS | `cd backend && ATLAS_AGENT_ADAPTER=mock python -m pytest -q` | 422 passed, 1 skipped, 3 warnings, 46.39 s. Toutes les bases de test sont isolées sous `/tmp` par la fixture de garde. |
| PASS | `cd integrations/relay/pc && npm_config_offline=true npm run test:unit -- --maxWorkers=2` | Build TypeScript réussi; 18 fichiers, 119 tests passés. Réseau npm externe désactivé. |
| PASS | `node --test integrations/relay/proxy/test/proxy.test.mjs` | 19 passés, 0 échec. L’entrée de fichier est utilisée car Node 24 ne traite pas le chemin de dossier fourni comme cible de test. |
| PASS | `cd backend && ATLAS_AGENT_ADAPTER=mock python -m pytest -q tests/test_v4_principal_auth.py tests/test_v4_principal_cli.py tests/test_chat_orchestration_v4.py` | 52 passed, 1 warning; inclut le chat legacy, l’insertion portant `device_ref`, consentement, pull et ACK. |
| PASS | `git diff --check` | Aucun whitespace error au contrôle final avant commit documentaire. |
| PASS | Revue des fixtures et diagnostics modifiés | Valeurs synthétiques seulement; aucun bearer réel, `.env` ni fichier de token réel lu ou ajouté au dépôt. |
| NOT RUN | `node --test integrations/relay/proxy/test` (forme dossier demandée) | Node 24 requiert un fichier de test explicite; le fichier équivalent ci-dessus a passé ses 19 tests. |
| NOT RUN | Migration, backfill, rollback ou révocation sur une base/service live; Android, ADB, Pixel, réseau externe | Exclu par le ticket. |

Le premier essai des tests Node en sandbox a rencontré des refus de loopback/processus (`EPERM`); la même suite a ensuite passé avec exécution locale autorisée, sans accès réseau externe. La suite backend complète a aussi été exécutée avec le runner local autorisé.

## Statut des gates

- `GO_CORE_DEVICE_PRINCIPALS`: **NOT_RUN** — approbation opérationnelle indépendante requise.
- `GO_RELAY_DEVICE_BINDING`: **NOT_RUN** — approbation opérationnelle indépendante requise.
- `GO_BEARER_MIGRATION_DESIGN`: **NOT_RUN** — plan documenté, migration live non autorisée.
- `GO_BEARER_REVOKE_FAIL_CLOSED`: **NOT_RUN** — tests synthétiques passés; aucune révocation live exécutée.

## Incident à auditer

Avant l’ajout de la garde d’import des tests, un import de `atlas_backend.main` a laissé son initialisation lire `ATLAS_DB_PATH` depuis l’environnement du processus et appeler le migrateur existant sur `backend/data/atlas.sqlite3`. Le contenu de cette base n’a pas été lu et aucune interaction ultérieure avec ce chemin n’a été faite. Son statut (base live ou locale) et l’effet exact de l’initialisation restent **non vérifiés**; ne pas considérer l’interdiction de toucher une base live comme certifiée sans audit séparé. La garde actuelle force les tests à utiliser une base temporaire neuve.

## Limites

Le cache anti-rejeu est borné et en mémoire par processus; plusieurs workers Core requièrent un routage qui empêche les rejeux inter-workers ou une protection partagée ultérieure. V1–V3 conservent leur authentification locale historique par `ATLAS_TOKEN`; seul le chemin V4 du connecteur cesse d’utiliser ce bearer partagé. Le mode `legacy` demeure volontairement compatible et conserve le modèle historique. Aucune migration, révocation ou configuration de production n’a été effectuée.
