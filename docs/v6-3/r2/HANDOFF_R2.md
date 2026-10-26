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
| R2-CAL-01 | à créer | — | READY | — |
| R2-UI-01 | à créer | — | READY | — |
| R2-SPORT-01 | à créer | — | READY | — |

## 4. Prochaines actions

1. Vague A : lancer trois enfants `codex:gpt-6-luna` / `xhigh` sur worktrees
   séparés ; vérifier modèle/effort dans les métadonnées.
2. Pendant l'exécution : audit de confidentialité des preuves R1, captures
   complémentaires, préparation des fixtures de recette.
3. Intégrer chaque ticket après revue du diff et oracle rejoué.
4. Vague B après `R2-UI-01`.

## 5. Limites connues

- Aucun calendrier local dans l'émulateur au pré-vol : écriture réelle
  `BLOCKED_ENVIRONMENT` tant qu'un calendrier local inscriptible n'est pas
  qualifié.
- Etar non installé.
- Aucun accès au Pixel physique.
- Aucune activation OpenClaw, aucun redémarrage du Gateway, aucune écriture
  wger réelle.
