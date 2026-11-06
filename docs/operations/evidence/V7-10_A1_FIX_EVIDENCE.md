# V7-10-A1-FIX — preuves de remédiation A4

## Registre de tâche

| Champ | Valeur |
|---|---|
| Tâche | `V7-10-A1-FIX` |
| Branche | `v7-10/a1-auth-fix` |
| Base | `549f658834c67f57028ba49879bfb6577affd47b` |
| Scope | Authentification V4 Core, secret d’assertion Relay côté connecteur, contrats d’erreur, tests de sécurité et documentation de migration. |
| Autorisé | Modifications et commits locaux dans ce worktree; fixtures synthétiques isolées dans `/tmp`; suites backend, connecteur et proxy conformément aux commandes ci-dessous. |
| Hors scope | Android, sémantique d’expiration A2, ADB, Pixel/AVD, Relay/Core/Gateway live, secrets réels, push, PR et changements de configuration de service. |
| Oracle | Constats A4 P1.1/P1.2/P2.1/P2.2/P2.3; suite backend épinglée, build et tests unitaires du connecteur, proxy avec exclusion obligatoire de l’intégration 4402, `git diff --check`/`git show --check`. |

Le correctif, les tests et la preuve sont livrés en trois commits locaux distincts. Aucun appareil ni service live n’a été utilisé. Le profil Luna/XHigh a été demandé dans le message de dispatch; le runtime de cette session ne fournit pas de commande permettant d’inspecter le sous-modèle ou l’effort effectif.

## Constats traités

| Constat | Remédiation | Preuve |
|---|---|---|
| P1.1 — catalogue TOOL refusé | `/internal/v4/capabilities` traite le catalogue comme métadonnée authentifiée du principal TOOL. Loopback, bearer, principal TOOL configuré, credential/principal actif, audience et enveloppe V4 restent exigés. Aucun grant `tool_catalog.read` ni élargissement aux autres routes. | `test_tool_catalog_is_authenticated_principal_metadata_without_a_grant` en `dual` et `principal`: succès, version/en-tête manquants, principal mal configuré, bearer inconnu, audience incorrecte et credential révoqué refusés. |
| P1.2 — lignes historiques accessibles aux LEGACY | `ATLAS_V4_LEGACY_PRINCIPAL_ID` désigne le seul principal LEGACY actif qui peut voir, chercher et ACKer des propositions sans propriétaire en `dual`. Variable absente: 403; principal absent, mauvais kind ou inactif: 503. En `legacy`, le bearer installé conserve sa compatibilité. | `test_historical_null_proposals_are_visible_only_to_legacy_in_dual_mode` couvre A/B, pull vide et ACK/lookup 404 pour B, refus sans variable et pour configuration invalide, puis visibilité du seul destinataire après attribution. `test_proposal_backfill_is_dry_run_by_default_and_apply_requires_verified_backup` valide aussi la commande offline avec backup et affectation explicite. |
| P2.1 — flags de mutations incomplets | Les flags globaux couvrent l’allowlist et s’intersectent avec le grant du principal. Lectures/chat absents restent `false`; mutations absentes restent `true`; `false` retourne 403 avant écriture, consentement ou ACK, en modes principal et legacy. Les noms hors allowlist restent rejetés. | Tests ciblés consentement, projection, ACK et configuration inconnue; la suite backend complète passe. Le schéma d’erreur V4 reconnaît `consent.state.write` afin qu’un refus d’authentification garde son code 401 plutôt que d’être remplacé par `INTERNAL`. |
| P2.2 — secret assertion en environnement production | Le secret d’environnement est accepté uniquement sous `NODE_ENV=test`; production échoue explicitement pour cette source. Les sources simultanées échouent. Le fichier de production exige chemin absolu, racine Git exclue quand elle est détectée, propriétaire du processus, mode `0600`, fichier régulier sans symlink, lecture `O_NOFOLLOW`, taille/contenu valides. `check-config` et `check-secret` partagent le validateur. | Tests `configFromEnvironment`, `check-config` et `check-secret`: environnement test accepté, production refusée, fichier privé production accepté, mode trop large et sources simultanées refusés; la valeur du secret n’apparaît pas dans la sortie. Le correctif complémentaire ci-dessous couvre le layout standalone et la racine `.git` découverte. |
| P2.3 — matrice partielle | La matrice couvre bearer croisé, credential inconnu/révoqué, identité Relay inconnue/révoquée, désaccord credential/binding, identités forgées, absence de consentement, persistance de révocation, rollback legacy↔dual, intégrité HMAC et rotation à deux key IDs. Les tests connecteur couvrent le mapping et la signature synthétiques. | `test_relay_assertion_binds_credential_to_active_session_and_rejects_replay` vérifie A/A et B/B, A avec le bearer B, les identités inconnue/révoquée, méthode, chemin, corps, timestamp, nonce, replay, fenêtre et rotation. Les cas de données/propositions et de catalogue sont listés ci-dessus. L’E2E sur un Relay réel reste `NOT_RUN`; ni test Pixel/AVD ni service live n’est revendiqué. |

## Vérifications exécutées

| Commande | Résultat |
|---|---|
| `ATLAS_AGENT_ADAPTER=mock /tmp/atlas-v7-10-a1-fix-venv/bin/python -m pytest -q` depuis `backend/` | **451 passed, 1 skipped, 3 warnings**, 38.10 s, avec versions épinglées de `backend/requirements.txt`. Le TestClient FastAPI bloque dans le bac à sable restreint; cette suite a été lancée avec l’autorisation locale requise pour ses threads de lifespan et fixtures temporaires. |
| `/tmp/atlas-v7-10-a1-fix-venv/bin/python -m pytest -q tests/test_v4_principal_auth.py::test_relay_assertion_binds_credential_to_active_session_and_rejects_replay` | **1 passed** après ajout du scénario B/B. |
| `npm run test:unit` depuis `integrations/relay/pc/` | **121 passed, 18 fichiers**; le script exécute aussi `npm run build` (`tsc -p tsconfig.json`). |
| `node --test --test-skip-pattern='pinned relay integration on 4402' integrations/relay/proxy/test/proxy.test.mjs` | **18 passed, 0 failed**. Le test qui sonde le service local déjà actif sur `127.0.0.1:4402` a été exclu par le motif requis. Aucun service n’a été démarré ou arrêté. |
| `git diff --check` | PASS, aucune erreur d’espacement. |
| `git show --check --oneline 36647c3` et `git show --check --oneline d7101e4` | PASS pour les deux commits code et tests. Le commit documentation/preuve est vérifié à la clôture. |

## Enquête du test de chemin d’archive

Test inchangé: `backend/tests/test_agent_v2.py::test_gateway_token_file_requires_mode_600_and_location_outside_repository`.

- Checkout du worktree: **1 passed**.
- `git archive HEAD` extrait dans un sous-répertoire dédié de `/tmp`, avec le `tmp_path` pytest par défaut hors de l’archive: **1 passed**.
- Même archive, avec `--basetemp` placé délibérément sous sa racine: **1 failed**, comme attendu puisque le test refuse un credential dans le dépôt.

La cause est l’emplacement, pas une permission incorrecte: `OpenClawAgentAdapter._read_token` calcule la racine à partir du fichier source. Si l’archive est extraite directement dans `/tmp`, cette racine devient `/tmp` et le `tmp_path` pytest par défaut (`/tmp/pytest-of-…`) est alors considéré à l’intérieur du dépôt. Le credential temporaire est donc rejeté. L’extraction dans un sous-répertoire ou un `--basetemp` hors racine fait passer le test. Aucun assouplissement du test n’a été fait.

## Commits

| Partie | Commit |
|---|---|
| (1) Correctifs auth et secret | `36647c3105306b75afbae9ce39d820bd80f00581` |
| (2) Matrice de tests | `d7101e4b0dc30728f25740b361dab02c18fc0c5c` |
| (3) Documentation et preuves | SHA retourné par Git à la clôture; non auto-référencé dans ce fichier. |

Les commits sont locaux uniquement. Aucun push ni PR.

## Limites

- La partie bout-en-bout qui dépend d’un Relay/Core live est `NOT_RUN`; les vérifications du vérificateur backend et du connecteur sont synthétiques et séparées.
- Aucun Pixel API 37, AVD, ADB ou configuration réelle n’a été touché.
- La suite complète backend a trois avertissements non bloquants liés au deprecation de `BlockingPortal` et à des Operation IDs OpenAPI du routeur générique.
- Le runtime n’expose pas de vérification du sous-modèle/effort demandé; aucune valeur effective non observable n’est revendiquée.

## Correctif complémentaire — racine Git du connecteur (2026-11-06)

| Champ | Valeur |
|---|---|
| Base | `b204c442eb9b3492c7210b0e91fe42a8108d141a` |
| Fichiers | `integrations/relay/pc/src/connector.ts`, `integrations/relay/pc/scripts/lib/config.mjs`, tests `connector-resilience.test.ts` et `config-env.test.ts`, README, migration, décisions automatiques et cette preuve. |
| Remédiation | Suppression des calculs de racine par profondeur fixe. Les deux modules remontent à la recherche d’un marqueur `.git` (fichier worktree ou dossier). Un résultat `undefined` signifie une installation standalone et désactive uniquement la frontière de dépôt; les contrôles de chemin absolu, propriétaire, mode `0600`, fichier régulier, absence de symlink, lecture `O_NOFOLLOW`, taille et contenu restent actifs. |
| Tests ciblés | Le prédicat exporté reçoit explicitement `undefined` pour un layout temporaire `standalone/src/lib`, puis une racine contenant un `.git` synthétique pour refuser un secret dans le checkout. Les deux lecteurs valident un fichier privé standalone. Les rejets couvrent mode incorrect, propriétaire simulé, symlink, chemin relatif et contenu invalide. |
| Profil | Le profil Luna/XHigh a été demandé; le runtime ne permet pas d’inspecter le modèle et l’effort effectifs. |

| Commande | Résultat |
|---|---|
| `cd integrations/relay/pc && npm run test:unit` | **123 passed, 18 fichiers**; `npm run build` / `tsc -p tsconfig.json` s’exécute avant Vitest. Le premier essai dans le bac à sable restreint échouait sur `EPERM` pour sockets loopback et sous-processus existants; le même test relancé avec les permissions locales demandées a passé. |
| `cd integrations/relay/pc && npm run build` | PASS; compilation TypeScript. |
| `git diff --check` | PASS. |
| `git show --check --oneline <commit>` | PASS; commit local séparé `fix(auth): detect repository root for relay secrets` (SHA repris dans le rapport de clôture, non auto-référencé ici). |
| `git status --short` après commit | Propre. |

La fixture standalone injecte explicitement `undefined` dans le prédicat pur pour ne pas dépendre des marqueurs Git présents dans les ancêtres du chemin temporaire du runner. Aucun Relay/Core live, ADB, Pixel, AVD ni test 4402 n’a été utilisé.
