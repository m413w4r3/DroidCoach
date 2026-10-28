# R3C-ANDROID-NET-01 — Ticket

| Clé | Valeur |
|---|---|
| Statut | **INTEGRATED** `d2419b3` |
| Objectif | Classer une panne réseau en `OFFLINE`, une erreur TLS/Noise/protocole en `ERROR` terminal, sans masquer une erreur de sécurité |
| Dépendances | Aucune |
| BASE_SHA | `eedf91502227297be5d2ff35524e79983ea36f49` |
| Profil constaté | `codex/gpt-6-luna`, `xhigh` effectif, mode `auto-review`, agent `6a1b8d43-8c2a-4eed-807e-03142cf7f1e6` |
| Worktree | `<worktree Paseo>/r3c-net-01` |
| Écriture | `PaseoRelayClient.kt`, `RelayNetworkClassificationTest.kt` |
| Oracle | 14 cas JVM : OFFLINE réseau, ERROR TLS/Noise/permission/inconnu, AUTH_REQUIRED, REVOKED, BACKEND_UNAVAILABLE, reprise réseau |
| Stop conditions | Modèle incorrect, permission absente, conflit de fichiers, test non déterministe |
| Livrables | Diff, tests, preuve `net-01-jvm.txt`, rapport de blocage sandbox |
| Résultat | Patch produit par l'enfant ; oracles exécutés par le parent ; gates 712 tests / 0 échec |
| Limite | Observation Pixel réelle `BLOCKED_DEVICE` |
