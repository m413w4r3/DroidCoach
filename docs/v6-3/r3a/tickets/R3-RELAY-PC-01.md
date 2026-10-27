# R3-RELAY-PC-01 — Connecteur PC relayé

| Clé | Valeur |
|---|---|
| TASK_ID / statut | `R3-RELAY-PC-01 / READY` |
| Objectif | Service Node autonome qui se connecte au relais Paseo, termine le canal Noise `protected`, authentifie un appareil ATLAS et exécute une liste fermée d'opérations vers FastAPI loopback |
| Dépendances | `R3-PREFLIGHT-01` (contrat `atlas.remote.v1`, Paseo construit) |
| BASE_SHA | à remplir par le parent au lancement (SHA du commit de contrats) |
| Profil exigé | `codex:gpt-6-luna` / `xhigh` |
| Worktree / accès | worktree dédié, écriture limitée au scope |
| Lire | `docs/v6-3/r3a/TRANSPORT_PASEO.md`, `docs/v6-3/r3a/DECISIONS_ARCHITECTURE.md`, `references/repos/paseo/packages/relay/src/e2ee.ts`, `references/repos/paseo/packages/relay/src/secure-channel.ts`, `references/repos/paseo-relay/README.md`, `references/repos/paseo-relay/lib/paseo_relay/{connection,socket,ownership}.ex`, `backend/atlas_backend/main.py` |
| Écrire | `integrations/relay/pc/**` exclusivement |
| Oracle | `npm test` PASS ; test d'interopérabilité Noise avec un client de test Node utilisant `@getpaseo/relay/e2ee` ; test de connexion à un **vrai** relais Paseo local ; tests d'auth, idempotence, expiration, révocation, liste blanche |
| Stop conditions | `@getpaseo/relay/e2ee` inutilisable ; impossibilité de lier le relais local ; secret dans un fichier suivi ; modèle/effort non conforme |
| Livrables | commit SHA, diff, `npm test` output, preuves expurgées, README de démarrage, rapport `PASS/FAIL/BLOCKED` |
| Interdictions | modifier le daemon Paseo, le relais upstream, OpenClaw, le Bridge, FastAPI ; exposer un port public ; accepter `legacy` ; créer un tunnel HTTP générique ; logger un token/payload |

## Détail

1. Initialiser `integrations/relay/pc` (TypeScript, Node 24, ESM, vitest).
2. Dépendance `@getpaseo/relay` : `file:../../../references/repos/paseo/packages/relay`,
   version épinglée, `dist/` construit depuis `902b0c7…`.
3. Identité serveur X25519 persistée 0600, commande `show-identity`.
4. Registre d'appareils JSON 0600 : `device_id`, label, `token_sha256`,
   capabilities, dates, `revoked_at`. Commandes `pair-device`, `revoke-device`.
5. `relayLink` : socket contrôle v2, `ping` 10 s, `sync`/`connected`/`disconnected`,
   ouverture des sockets data, `openDaemonSecureChannel` avec
   `minimumMode: "protected"`.
6. `session` : validation `hello`, nonce anti-rejeu, token en temps constant,
   quotas, session unique, expiration.
7. `operations` : liste fermée et mapping exact vers FastAPI ; cache
   d'idempotence 10 min ; `plan.revision.result`; refus de tout champ inconnu.
8. `backend` : HTTP loopback, Bearer de service, timeouts, taille bornée, erreurs
   typées ; aucun payload dans les logs.
9. Métriques JSON sur `127.0.0.1` uniquement : connexions, sessions, requêtes,
   erreurs, latences, mémoire. Aucune donnée personnelle.
10. Tests : protocole, auth, anti-rejeu, idempotence, expiration, révocation,
    liste blanche, backend absent/restauré, relais absent/reconnecté, client Noise
    réel, mauvais mode, handshake altéré.
11. Documenter le démarrage local et les variables d'environnement sans secret.

## Reprise

Session ID, SHA produit, tests exécutés, blocages. Aucune sous-délégation.
