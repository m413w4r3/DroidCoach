# ATLAS V6.2 — État de clôture

**Date :** 2026-10-25. **Branche :** `chore/cloture-v62-android17` (locale, aucun push).
**Base :** `8890046012965f3a3112ff0c419014c74e189d11`.
**Statut :** `V6.2_CLOSED` — reviewers finaux `GO_WITH_LIMITATIONS`, aucun P0/P1; limites acceptées et documentées ci-dessous.

## Registre

Le registre détaillé et les propriétaires de tâches sont dans [REGISTRE_TACHES.md](REGISTRE_TACHES.md). Les preuves sont dans les sous-dossiers `preuves-*` et les captures émulateur dans `captures/`.

## Gates et faits

| Élément | État | Preuve |
|---|---|---|
| JVM sur `1a6f761` | PASS | 339 tests, 0 échec, 0 erreur, 0 ignoré (57 classes) |
| Lint sur `1a6f761` | PASS | 0 erreur, 47 avertissements |
| Build debug + androidTest sur `1a6f761` | PASS | BUILD SUCCESSFUL; APK `dc1c3e00…` |
| Backend sur `1a6f761` | PASS | 66 passed, 1 skipped, 1 warning, 2.72 s |
| AVD API 37 sur `1a6f761` | PASS effectif | 77 PASS + retest font scale = 78/78 après cold boot; classes agent/focus 15/15; premier run UI flaky conservé comme fait historique |
| OpenClaw Android sur `1a6f761` | PASS | `V62RealGatewayContextBridgeTest#realGatewayUsesAccelerated25MinuteFocusContextAndAcceptedProposal` : OK (1 test), 13,817 s, gateway réel et backend éphémère |
| Pixel — installation, HOME, hors ligne, Pause, notifications, accessibilité | PASS historique | Campagne sur `8890046…` / APK `48c7766e…`; aucun retest sur le SHA final par décision du propriétaire |
| Pixel — Focus/objectif | PASS historique | 0/120 avant fin, 25/120 après fin, 25/120 après relance à froid, annulation sans crédit |
| Localisation/mobilité | NOT_RUN, désactivées par défaut | `CAP_LOCATION=false`, `CAP_MOBILITY=false`; issue #1 ouverte |
| Restauration Pixel | Non exécutée, décision propriétaire | État QA synthétique conservé; sauvegarde d'origine intacte |
| Revue fonctionnelle finale | GO_WITH_LIMITATIONS sur `1a6f761` | Aucun P0/P1 code; limites Pixel/AVD/P2 acceptées |
| Revue sécurité finale | GO_WITH_LIMITATIONS sur `1a6f761` | Aucun P0/P1 code; P2 URL OpenClaw et pull tardif acceptés comme limites |
| Revue du SHA final après correction | GO_WITH_LIMITATIONS | Re-revue du delta `18484e2..1a6f761` rendue sans P0/P1 |

## Correctifs P1 livrés

1. **Pauses longues :** le vérificateur compte la pause de 15 min insérée toutes les 4 cycles (`longBreakMinutes=15`, `longBreakEveryCycles=min(4, cycles)`), en plus des pauses courtes (`1a6f761`).
2. **DST / heure ambiguë :** la fin de fenêtre est comparée en date et heure locales, sans décaler la borne au lendemain lors du retour à l'heure d'hiver (`1a6f761`).
3. **Bornes de test :** durée totale exactement égale à 55 min acceptée; fin à 09:30 refusée; DST à Paris refusé; pause courte de 0 portée à 1 min; revalidation de carte persistée (`ActionDispatcherTest`, 35/35).
4. **Mode continu :** une carte agent `continuous` est refusée au démarrage avant toute mutation; durée marquée non bornée dans le helper (`1a6f761`).

## Décisions du propriétaire

- Aucun nouveau test sur le Pixel physique; `emulator-5554` est la seule cible Android restante.
- Aucune session Focus de 25 minutes en temps réel.
- L'état QA du Pixel est conservé; la restauration n'est pas exécutée.
- Aucun push, merge distant ou fermeture d'issue.

## Conditions de clôture

1. Correctifs P1-A/P1-B intégrés, JVM et tests ciblés verts.
2. CI finale, AVD final et OpenClaw Android rejoués sur le SHA final.
3. Revue fonctionnelle et sécurité du SHA final : aucun P0/P1.
4. Acceptation explicite des limites restantes (Pixel non retesté sur le SHA final, localisation/mobilité non validées mais désactivées, restauration non exécutée, P2 sécurité ouverts).
5. Verdict `V6.2_CLOSED` ou `V6.2_NOT_CLOSED` consigné dans [RAPPORT_CLOTURE_V62.md](RAPPORT_CLOTURE_V62.md).
