# R3C-RACE-01 — Ticket

| Clé | Valeur |
|---|---|
| Statut | **INTEGRATED** `22ba24f` |
| Objectif | Fermer R-22 par des tests déterministes de consentement et de révocation, sans modifier le code de production |
| Dépendances | Aucune |
| BASE_SHA | `eedf91502227297be5d2ff35524e79983ea36f49` |
| Profil constaté | `codex/gpt-6-luna`, `xhigh` effectif, mode `auto-review`, agent `be46e357-2cc0-4fd4-b367-3d036d5e7df3` |
| Worktree | `<worktree Paseo>/r3c-race-01` |
| Écriture | `RelayTestFixtures.kt` (hooks), `RelayConcurrencyGateTest.kt`, `r3c-race.test.ts` |
| Oracle | 3 scénarios Android + 3 scénarios Node, ordre `DEVICE_REVOKED` avant `4001`, aucune réponse tardive, deux chemins de révocation |
| Stop conditions | Défaut de production révélé, test non déterministe, sommeil arbitraire |
| Livrables | Diff test-only, preuve `race-01-tests.txt` |
| Résultat | Parent a rejoué : Android PASS, Node 3/3 PASS |
| Limite | Reproduction Pixel pendant coupure `PARTIAL` |
