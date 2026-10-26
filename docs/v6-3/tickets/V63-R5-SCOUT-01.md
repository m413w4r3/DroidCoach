# V63-R5-SCOUT-01 — Audit lecture seule du transport Paseo/Relay

| Clé | Valeur |
|---|---|
| TASK_ID / statut | `V63-R5-SCOUT-01 / READY` |
| Base | branche `feat/atlas-v6-3-reprise` ; SHA exact annoncé dans le prompt |
| Profil | `provider=codex`, `model=gpt-6-luna`, `effort=xhigh` |
| Worktree | dédié, `chmod -R a-w`, aucune écriture |
| Dépendances | aucune |

## Objectif observable

Cartographier le code de transport sécurisé de Paseo et Paseo Relay en vue d'un
réemploi **ciblé** par ATLAS, sans import de code ni modification des dépôts.

## Sources

- `references/repos/paseo` — SHA
  `902b0c7a1637cc7a6c2ee268dca8161965a35def`.
- `references/repos/paseo-relay` — SHA
  `3fc41c96c8c63f3a7109e832899cc57d473c4531`.
- Fichiers Paseo indiqués par `PROMPT_MAITRE.md` §8.2 :
  `packages/relay/src/secure-channel.ts`, `packages/relay/src/noise/`,
  `protected-records.ts`, `encrypted-channel.ts`,
  `packages/client/src/daemon-client-relay-e2ee-transport.ts`,
  `packages/server/src/server/relay-transport.ts`,
  `docs/relay-endpoint-security.md`, `SECURITY.md`.
- Paseo Relay : routage, WebSocket, cycle de vie, reconnexions, déploiement,
  tests d'interopérabilité.

## Questions à trancher

1. Le mode `Noise_NK_25519_ChaChaPoly_SHA256` est-il réellement celui utilisé ?
   Où, avec quelles bibliothèques et quels paramètres ?
2. Existe-t-il un fallback legacy ? Où est-il contrôlé ?
3. Quels tests/vecteurs upstream peuvent servir d'oracle d'interopérabilité ?
4. Quels composants sont réutilisables côté PC (Node/TypeScript) sans fork ?
5. Quels composants doivent être réimplémentés côté Android Kotlin, et avec
   quelles bibliothèques maintenues (Keystore, X25519, ChaCha20-Poly1305) ?
6. Licences respectives et obligations (`THIRD_PARTY_NOTICES.md`).
7. Ce qui est **interdit** : API de contrôle des agents Paseo, terminal distant,
   identités/clés du daemon Paseo, relais public Paseo.
8. Plan d'adaptateurs, topologie (Android → relais → connecteur PC → FastAPI),
   appairage, révocation, tests, et conditions `BLOCKED_INFRA`.

## Interdictions

Aucune écriture, aucun clone, aucun réseau, aucune installation, aucun secret,
aucune sous-délégation, aucune revendication de test non exécuté.

## Livrable

Rapport concis : SHA, fichiers/lignes, licences, primitives, vecteurs, gaps,
plan de réemploi, risques, oracle du futur ticket. Marquer `VÉRIFIÉ` /
`NON VÉRIFIÉ` et `BLOCKED_INFRA` si nécessaire.
