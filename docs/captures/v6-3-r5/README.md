# Captures ATLAS V6.3 — R5

- **Appareil** : AVD API 37 (`sdk_gphone16k_x86_64`), 1080 × 1920, données synthétiques.
- **Code** : branche `feat/atlas-v6-3-ux-daily-use`, SHA final `072424f`.
- **Date** : 2026-10-29.
- **Expurgation** : aucune donnée personnelle, aucun compte, aucun jeton, aucun `serverId`, aucune capture du tiroir d'applications. La tâche Focus visible (`Lis/avance 10 min`) est une donnée synthétique de démonstration.

## Fichiers

| Fichier | Écran |
|---|---|
| `01-focus-gauge.png` | Focus en cours : jauge circulaire, compte à rebours, tâche, phase, cycle, pause, urgence |
| `02-coach-quick-actions.png` | Coach : onglets Échanges/Synthèses, état local, actions rapides, conversation |
| `03-coach-insights.png` | Synthèses Jour : sélecteur Jour/Semaine/Mois, métriques locales, détails repliés |
| `04-coach-day-form.png` | Formulaire « Organiser ma journée » pré-rempli 09:00–18:00 |
| `05-coach-confirmation.png` | Confirmation explicite avant envoi au coach |

## Limites

- Captures AVD uniquement : le Pixel physique n'était pas connecté.
- Les captures Pixel « après » de R4 restent à refaire après reconnexion.
- L'écran Synthèses affiche ici des zéros dérivés de l'état local synthétique ; l'état `Données insuffisantes` est couvert par les tests.
