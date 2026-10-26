# ATLAS V6.3 — Handoff R2 (en cours)

## 1. Reprise immédiate

- Branche : `feat/atlas-v6-3-r2-emulator-ui`
- Base : `438616923a88eff814e96347d031506c6ea12f60`
- Registre : `docs/v6-3/r2/REGISTRE_R2.md`
- Décisions : `docs/v6-3/r2/DECISIONS_R2.md`
- Matrice : `docs/v6-3/r2/MATRICE_RECETTE_R2.md`
- Audit avant : `docs/v6-3/r2/AUDIT_UI_AVANT.md`
- Tickets : `docs/v6-3/r2/tickets/**`

## 2. Émulateur

- `emulator-5554` = `Pixel_9_Pro_XL_2`, API 37.2, headless.
- Sauvegardes hors dépôt :
  - AVD : `~/.local/share/atlas-v63-r2/avd-backup/`
  - Données ATLAS : `~/.local/share/atlas-v63-r2/app-data/`
- ATLAS est le HOME préexistant ; ne pas changer ce rôle sans restauration.
- Projection AVANT : `~/.local/share/atlas-v63-r2/screenshots/pre/`.
- Fixtures : plan v4 3 activités, 5 habitudes synthétiques, 1 habitude
  archivée, historique 7/30 jours.

## 3. Tickets et état

| Ticket | Branche/worktree | Session | Statut | Preuve |
|---|---|---|---|---|
| R2-CAL-01 | `feat/r2-calendar-write` | `ed02e2cd…` | ACCEPTED_WITH_P2 / INTEGRATED | 18 ciblés, 514 JVM ; `preuves/gates-wave-a.txt` |
| R2-UI-01 | `feat/r2-ui-accent` | `2bb09cbf…` | ACCEPTED / INTEGRATED | 11 tests UI ; correctifs parent `54b4183` |
| R2-SPORT-01 | `feat/r2-sport-projection` | `ca7f5e7a…` | ACCEPTED_WITH_P3 / INTEGRATED | 8 backend + 7 JVM ; 178 backend |
| R2-PLAN-UI-01 | `feat/r2-plan-ui` | à lancer | READY (vague B) | — |
| R2-HABITS-UI-01 | `feat/r2-habits-ui` | à lancer | READY (vague B) | — |
| R2-MEMORY-01 | `feat/r2-memory-core` | à lancer | READY (vague B) | — |

## 4. Prochaines actions

1. Vague B : lancer `R2-PLAN-UI-01`, `R2-HABITS-UI-01` et `R2-MEMORY-01` sur
   worktrees séparés, avec base `d59437650342c58d66b8c845aac313245f369372`.
2. Intégrer chaque ticket après revue du diff et oracle rejoué.
3. Vague C : `R2-USAGE-01`, `R2-BRIDGE-QA-01`, raccordement sport/calendrier.
4. Vague D : recette émulateur, captures après, accessibilité, handoff Pixel.

## 5. Limites connues

- Aucun calendrier local dans l'émulateur au pré-vol : écriture réelle
  `BLOCKED_ENVIRONMENT` tant qu'un calendrier local inscriptible n'est pas
  qualifié.
- Etar non installé.
- Aucun accès au Pixel physique.
- Aucune activation OpenClaw, aucun redémarrage du Gateway, aucune écriture
  wger réelle.
