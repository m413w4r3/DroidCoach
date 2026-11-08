# V7-11 — Répétition de migration SQLite sur copie

**Statut : GO local sur base synthétique. Aucune migration live, aucun volume Docker personnel, aucune donnée réelle.**
**Base auditée :** `8dac699cdb37e2f858a76e4cf7f9fc3bcd613e9c`.
**Preuve détaillée :** [V7-11_A1_CORE_MIGRATION_EVIDENCE.md](evidence/V7-11_A1_CORE_MIGRATION_EVIDENCE.md).

## Résultat

La migration V7-10 a été rejouée sur une base SQLite **synthétique** construite en schéma v3, avec une ligne historique et une proposition V4 sans propriétaire. Aucun fichier live, secret ou contenu personnel n'a été utilisé.

| Étape | Résultat |
|---|---|
| État initial | `user_version=3`, `integrity_check=ok`, `foreign_key_check=[]`, une ligne historique V2 |
| `preview-migration` | PASS — aucune mutation |
| Migration v3→v5 | PASS — 5 migrations contiguës, checksums vérifiés, `user_version=5`, intégrité et FK valides |
| Import LEGACY explicite | PASS — bearer synthétique de 58 caractères, hash SHA-256 uniquement, `--grant-defaults` explicite |
| Principal `pixel-v7` de test | PASS — `device_ref` synthétique stable, credentials `DIRECT` et `RELAY` distincts |
| Principal `TOOL` | PASS — principal et credential séparés |
| Grants | PASS — `consent.state.write`, `plan_revision.propose`, `proposal.ack` uniquement |
| Binding Relay | PASS — `relay-pixel-v7-test` → `pixel-v7` actif |
| Backfill dry-run | PASS — 1 proposition sans propriétaire détectée |
| Backfill `--apply --backup` | PASS — 1 mise à jour, backup vérifié (SHA-256 `29d21f70…b1365`), 0 ligne sans propriétaire après application |
| Isolation propriétaire | PASS — LEGACY retrouve sa proposition ; `pixel-v7` ne peut pas la retrouver ; trigger d'INSERT sans `device_ref` et changement de propriétaire refusés |
| Expiration dry-run | PASS — 2 propositions actives échues détectées, `UNCERTAIN` exclu |
| Expiration apply par lots | PASS — 2 expirations, 2 audits, `UNCERTAIN` conservé, 0 anomalie |
| Redémarrage / idempotence | PASS — `user_version=5`, 5 migrations, 3 propositions, 2 audits, grants et hash logique conservés ; backfill relancé à 0 |
| Migration live / volume Docker | **NOT_RUN** — interdit en V7-11A |

## Points d'attention pour V7-11B

1. **Attribution historique** : le backfill n'est autorisé que vers un principal LEGACY explicitement désigné. L'attribution réelle des 3 propositions live (dont 1 `PENDING_ANDROID`) reste une décision métier à consigner avant tout `--apply` live.
2. **WAL/SHM** : la base live observée est en `journal_mode=delete` et sans WAL/SHM au moment de l'inventaire. La procédure V7-11B doit arrêter Core, vérifier l'absence de WAL/SHM, capturer une copie fraîche et contrôler `integrity_check`, `foreign_key_check`, `user_version` et les comptes avant promotion.
3. **Sauvegarde** : le dry-run synthétique a produit un backup vérifié ; le backup live doit suivre la même procédure et rester hors dépôt, en `0700/0600`.
4. **Réconciliation d'expiration** : `UNCERTAIN` ne doit jamais être converti automatiquement. L'`--apply` est transactionnel par lots, pas globalement atomique.
5. **Schéma** : aucune rétrogradation destructive. Un rollback applicatif repasse en mode `legacy` sans supprimer tables/colonnes ; il ne restaure pas une base pré-migration par défaut.

## Verdict

`GO_V7_11_SQLITE_MIGRATION_DRY_RUN` : **GO local** sur base synthétique.
`GO_V7_11_HISTORICAL_BACKFILL_DRY_RUN` : **GO local** (dry-run + apply sur copie) ; attribution live encore ouverte.
`GO_V7_11_EXPIRATION_DRY_RUN` : **GO local**.
La migration live et la promotion du volume restent **NOT_RUN / NO-GO** jusqu'à l'autorisation V7-11B.
