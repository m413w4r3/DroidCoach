# V7-06A-FIX-PC-R2 — Rejeu V4 après renouvellement d’enveloppe Relay

Date : 2026-11-01
Origine : P1 de la revue indépendante pré-VM
Base : `edd4490ee878e778d5ab6d663cbde3f2424cabb5`
Commit du correctif : `e0d9062de733a25630ff2394c471e440d6a587f1`
Merge d’intégration : `be31d097fe0cf6a5bc50688b421bc9eecbbc847e`
Session : `1dcb69b6-82cc-4ef4-8a13-25d54eed0115` — `codex/gpt-6-luna`, `xhigh`
Worktree : `/tmp/atlas-v7-06a-fix-pc-r2`

## Défaut reproduit

`executeValidated` comparait `requestHash = hash(request)` sur toute l’enveloppe
avant de consulter les caches de rejeu. Android réutilise le même corps, la même
clé et le même `request_id` lors de sa reprise `UNAVAILABLE`, mais
`RelayBackendTransport` régénère `created_at`/`expires_at` et peut changer de
`session_id`. Le connecteur répondait donc `IDEMPOTENCY_CONFLICT` au lieu de
rejouer un reçu déjà obtenu.

Preuve rouge avant correctif, sur les tests ajoutés :

```text
2 failed | 1 passed | 24 skipped (27)
RemoteError: idempotency conflict
```

Les deux échecs sont le rejeu en mémoire et le rejeu après redémarrage ; le test
de conflit métier passait déjà.

## Correctif

Deux fichiers, +127/−3 :

- `integrations/relay/pc/src/operations.ts` : pour les opérations V4 uniquement,
  le rejeu par `request_id` compare l’opération, la clé d’idempotence de
  l’enveloppe et `businessHash` (opération + payload). Seules les métadonnées de
  transport régénérées (`session_id`, `created_at`, `expires_at`) sont ignorées.
  Les opérations non-V4 conservent la comparaison du hash complet.
  `PendingRequest` porte l’identité stable ; `requestHash` reste persisté pour la
  compatibilité du journal. Aucun changement de timeout, de rédaction, de schéma
  ou de politique de rejeu.
- `integrations/relay/pc/test/operations.test.ts` : trois tests ajoutés —
  rejeu en mémoire avec enveloppe renouvelée, rejeu après redémarrage,
  refus si le payload change (conflit) ou si la clé d’enveloppe diverge
  (`INVALID_REQUEST`, refus de validation avant cache).

## Vérifications après correctif

| Vérification | Résultat |
|---|---|
| Tests ciblés des trois nouveaux cas | PASS |
| `npm run test:unit` | **17 fichiers, 109 tests passés, 0 échec** |
| `npx tsc -p tsconfig.json --noEmit` | PASS |
| `git diff --check` | PASS |
| Suite complète rejouée par le parent sur le merge `be31d09` | **109/109 PASS** |

## Limites

- Les P2 de la revue restent hors périmètre : validation stricte du reçu
  consentement et corrélation `request_id`, accumulation possible des travaux
  immédiats, inversion potentielle `consentGate` ↔ verrou WebSocket.
- Ce correctif ne prouve pas la fenêtre exacte de perte V7-05 ; il garantit que
  le rejeu V4 n’est plus refusé à cause du renouvellement d’enveloppe.
- La qualification VM est requise pour la preuve d’intégration ; aucune preuve
  Pixel n’est produite.
