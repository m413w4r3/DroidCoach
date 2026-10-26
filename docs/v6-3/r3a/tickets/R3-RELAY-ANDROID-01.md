# R3-RELAY-ANDROID-01 — Client Android relayé

| Clé | Valeur |
|---|---|
| TASK_ID / statut | `R3-RELAY-ANDROID-01 / READY` |
| Objectif | Transport Kotlin autonome : WebSocket, handshake Noise NK protégé, records authentifiés, auth appareil, reconnexion, files bornées, états observables, adaptation `PlanRevisionProposalClient` |
| Dépendances | `R3-PREFLIGHT-01` (contrat et vecteurs) |
| BASE_SHA | à remplir par le parent au lancement |
| Profil exigé | `codex:gpt-6-luna` / `xhigh` |
| Worktree / accès | worktree dédié, écriture limitée au scope |
| Lire | `docs/v6-3/r3a/TRANSPORT_PASEO.md`, `references/repos/paseo/packages/relay/src/noise/**`, `references/repos/paseo/packages/relay/src/protected-records.ts`, `references/repos/paseo/packages/relay/src/secure-channel.ts`, `android/.../plan/PlanRevisionApiClient.kt`, `android/.../sync/**` |
| Écrire | `android/app/src/main/java/dev/atlas/coach/sync/relay/**` et `android/app/src/test/java/dev/atlas/coach/sync/relay/**` exclusivement |
| Oracle | tests JVM : vecteurs Noise upstream exacts, handshake croisé avec un vecteur Node, records valides/malformés/rejoués/réordonnés/tronqués, tailles, protocole, idempotence, expiration, reconnexion ; compilation `:app:compileDebugKotlin` ; test instrumenté de socket réel contre un relais local si disponible |
| Stop conditions | aucune bibliothèque cryptographique fiable pour minSdk 26 ; divergence de vecteur non résolue ; nécessité de modifier un fichier hors scope ; modèle/effort non conforme |
| Livrables | commit SHA, diff, tests, vecteurs, rapport `PASS/FAIL/BLOCKED` |
| Interdictions | modifier `PlanScreen.kt`, manifeste, écrans métier, dépendances sans justification ; WebView ; runtime Node ; fallback legacy ; clé privée en dur ; token dans URL/log |

## Détail

1. Ajouter les dépendances minimales nécessaires (OkHttp pour WebSocket, Tink pour
   X25519/ChaCha20-Poly1305) avec version épinglée et notice tierce.
2. Implémenter `NoiseCipherState`, `SymmetricState`, `NoiseNkInitiator` selon le
   spec Paseo : prologue, HKDF, mixHash/mixKey, nonces.
3. Implémenter `ProtectedRecordCodec` (chunking, FIRST/FINAL, longueurs, kind).
4. Implémenter `AtlasRemoteProtocol` : enveloppes `hello`, `hello_ok`, `request`,
   `response`, `error`, `cancel`, `ping`, `pong`, bornes, codes d'erreur.
5. Implémenter `PaseoRelayClient` : URL v2, `e2ee_hello` retry 1 s, refus `ready`
   sans `protected`, records base64 texte, close codes, reconnexion exponentielle
   bornée avec jitter, annulation.
6. Implémenter `RelayBackendTransport` : une requête à la fois par `request_id`,
   file bornée, expiration, cache court, états `RELAY_*`.
7. `RelayPlanRevisionClient` : construit le payload `PlanRevisionRequestV3` comme
   `PlanRevisionApiClient`, parse la réponse avec `PlanRevisionProposalV3.parse`,
   retourne les mêmes `PlanRevisionApiResult`.
8. `RelayCredentialStore` : interface ; aucune implémentation Keystore dans ce
   ticket (le parent la fournit) ; un fake en mémoire pour les tests.
9. Tests JVM exhaustifs ; test instrumenté optionnel contre un relais local.
10. Documenter les paramètres de protocole et les limites dans le code.

## Reprise

Session ID, SHA produit, tests exécutés, blocages. Aucune sous-délégation.
