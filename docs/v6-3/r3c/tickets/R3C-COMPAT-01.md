# R3C-COMPAT-01 — Ticket

| Clé | Valeur |
|---|---|
| Statut | **INTEGRATED** `a162f70` |
| Objectif | Maintenir la session pour un ancien appairage sans `coach.chat`, refuser uniquement le chat, conserver les opérations autorisées, aucune élévation |
| Dépendances | Aucune |
| BASE_SHA | `eedf91502227297be5d2ff35524e79983ea36f49` |
| Profil constaté | `codex/gpt-6-luna`, `xhigh` effectif, mode `auto-review`, agent `6093964c-7ce5-4f6e-9424-ea59b08f1761` |
| Worktree | `<worktree Paseo>/r3c-compat-01` |
| Écriture | `RelayBackendTransport.kt`, `RelayCapabilityNegotiationTest.kt`, `r3c-compat.test.ts` |
| Oracle | JVM : session `RELAY_CONNECTED`, plan utilisable, chat `OPERATION_UNAVAILABLE`, aucun close ; Node : intersection stricte, opérations anciennes OK |
| Stop conditions | Élévation de capacité, fermeture de session, régression des opérations |
| Livrables | Diff, tests, preuve `compat-01-tests.txt` |
| Résultat | Parent a rejoué JVM et Node : PASS ; E2E public ancien appairage 4/4 PASS |
| Limite | Parcours Android sur Pixel `BLOCKED_DEVICE` |
