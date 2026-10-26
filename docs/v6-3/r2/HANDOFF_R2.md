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
- État final : émulateur **arrêté proprement**, données applicatives pré-R2
  restaurées (1 habitude, aucun plan), APK final `cc88e649…` installé et vérifié,
  READ_CALENDAR révoquée, consentement calendrier effacé, `adb reverse` retiré,
  police 1.0 et rotation automatique restaurées.
- Sauvegardes hors dépôt :
  - AVD : `~/.local/share/atlas-v63-r2/avd-backup/`
  - Données ATLAS : `~/.local/share/atlas-v63-r2/app-data/`
- ATLAS est le HOME préexistant ; ne pas changer ce rôle sans restauration.
- Projection AVANT : `~/.local/share/atlas-v63-r2/screenshots/pre/`.
- Projection APRÈS : `~/.local/share/atlas-v63-r2/screenshots/post/`.
- Fixtures R2 : plan v4 3 activités, 5 habitudes synthétiques, 1 habitude
  archivée, historique 7/30 jours (sauvegarde
  `atlas-app-data-wave-b-fixtures.tar`, SHA `19fdacc8…`).

## 3. Tickets et état

| Ticket | Branche/worktree | Session | Statut | Preuve |
|---|---|---|---|---|
| R2-CAL-01 | `feat/r2-calendar-write` | `ed02e2cd…` | ACCEPTED_WITH_P2 / INTEGRATED | 18 ciblés, 514 JVM ; `preuves/gates-wave-a.txt` |
| R2-UI-01 | `feat/r2-ui-accent` | `2bb09cbf…` | ACCEPTED / INTEGRATED | 11 tests UI ; correctifs parent `54b4183` |
| R2-SPORT-01 | `feat/r2-sport-projection` | `ca7f5e7a…` | ACCEPTED_WITH_P3 / INTEGRATED | 8 backend + 7 JVM ; 178 backend |
| R2-PLAN-UI-01 | `feat/r2-plan-ui` | `1d108d56…` | ACCEPTED / INTEGRATED | 546 JVM, 17/17 instrumentés |
| R2-HABITS-UI-01 | `feat/r2-habits-ui` | `505ddca3…` | ACCEPTED / INTEGRATED | P1 scroll corrigé, 17/17 instrumentés |
| R2-MEMORY-01 | `feat/r2-memory-core` | `408a1689…` | ACCEPTED_WITH_P2 / INTEGRATED | 15 tests mémoire |
| R2-SPORT-WIRE-01 | parent | — | PARTIAL / INTEGRATED | écran Sport + entrée Plus ; lien DayPlan à faire |
| R2-USAGE-01 | `feat/r2-usage-summary` | `ca2ff2d2…` | RUNNING (vague C) | — |
| R2-BRIDGE-QA-01 | `feat/r2-bridge-qa` | `d79be9a2…` | RUNNING (vague C) | — |

## 4. Prochaines actions

1. Recevoir et revoir `R2-USAGE-01` et `R2-BRIDGE-QA-01` (vague C) ; rejouer
   leurs oracles sur le SHA intégré.
2. Mettre à jour la matrice et le registre avec leurs résultats.
3. Traiter le lien `trainingRef` DayPlan (S6) si un nouveau ticket est ouvert.
4. Recette Pixel physique selon `PIXEL_RUNBOOK_R2.md` — **aucune action
   physique n'a été exécutée en R2**.
5. Aucun push, PR ou fusion distante sans accord explicite.

## 5. Limites connues

- Aucun calendrier local dans l'émulateur au pré-vol : écriture réelle
  `BLOCKED_ENVIRONMENT` tant qu'un calendrier local inscriptible n'est pas
  qualifié.
- Etar non installé.
- Aucun accès au Pixel physique.
- Aucune activation OpenClaw, aucun redémarrage du Gateway, aucune écriture
  wger réelle.
