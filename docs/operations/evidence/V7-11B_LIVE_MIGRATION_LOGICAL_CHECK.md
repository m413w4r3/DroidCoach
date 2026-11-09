# V7-11B — Contrôle logique v3→v5 après migration live

**Date :** 2026-11-08
**Objet :** vérifier qu'aucune valeur des tables existantes n'a été altérée par la migration live, au-delà de la transition d'expiration autorisée et du ledger.

## Méthode

- Source : backup frais v3 `9f5c8fd24e816ad105ce852db75fedc0b1423a9a4f30e96b1bc9c055e3d5ca55`.
- Cible : copie read-only de la base live v5 (`9f2cd235…`), supprimée après analyse.
- Pour chaque table existante en v3 : colonnes communes, lignes triées, hash SHA-256 déterministe des tuples (valeurs non publiées).
- Aucune ligne n'est affichée ; seuls noms de tables, compteurs, statuts et noms de colonnes modifiés sont rapportés.

## Résultat

- Tables v3 : **19** ; tables v5 : **25**.
- Nouvelles tables attendues : `agent_proposal_expiration_events_v4`, `credentials_v4`, `principal_capabilities_v4`, `principal_events_v4`, `principals_v4`, `relay_principal_bindings_v4`.
- Tables supprimées : **aucune**.

| Table | Lignes v3 / v5 | Hash |
|---|---:|---|
| `activity_status_events_v4` | 0 / 0 | MATCH |
| `agent_chat_cache` | 4 / 4 | MATCH |
| `agent_proposal_acks` | 0 / 0 | MATCH |
| `agent_proposal_events_v4` | 2 / 2 | MATCH |
| `agent_proposals` (historique) | 1 / 1 | MATCH |
| `agent_proposals_v4` | 3 / 3 | **DIFF attendu** |
| `consent_state_events_v4` | 4 / 4 | MATCH |
| `consent_states_v4` | 2 / 2 | MATCH |
| `day_plan_snapshots_v4` | 16 / 16 | MATCH |
| `directive_responses` | 0 / 0 | MATCH |
| `events` | 0 / 0 | MATCH |
| `goal_progress_snapshots_v4` | 0 / 0 | MATCH |
| `plan_revision_requests` | 0 / 0 | MATCH |
| `project_memory` | 0 / 0 | MATCH |
| `schema_migrations` | 3 / 5 | **DIFF attendu** |
| `tool_audit_v4` | 0 / 0 | MATCH |
| `training_snapshots_v4` | 0 / 0 | MATCH |
| `usage_summaries_v4` | 0 / 0 | MATCH |
| `v4_quarantine` | 0 / 0 | MATCH |

### Différences

1. `agent_proposals_v4` : **1 ligne modifiée**, colonne `state` uniquement — transition `PENDING_ANDROID` → `EXPIRED`, autorisée avant migration. Les autres colonnes de cette ligne et les deux autres lignes sont inchangées.
2. `schema_migrations` : passage de 3 à 5 lignes — ajout des migrations 4 et 5, attendu.

Aucune autre différence de valeur n'est détectée sur les tables existantes.

## Limites

- Le contrôle porte sur les tables v3 existantes et les colonnes communes ; les nouvelles colonnes (`device_ref`) et les six nouvelles tables sont hors comparaison de valeurs.
- Les hashes de lignes ne constituent pas une preuve contre une altération malveillante concertée ; ils détectent les modifications accidentelles ou non coordonnées.
- La copie v5 a été supprimée après analyse ; aucun contenu de ligne n'a été publié.
