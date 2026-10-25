# ATLAS V6.2 — État de clôture

**Date :** 2026-10-25. **Branche :** `chore/cloture-v62-android17` (locale, aucun push).
**Base :** `8890046012965f3a3112ff0c419014c74e189d11`.
**Statut :** `NOT_CLOSED` — correctifs P1 du mandat en cours après la revue sécurité finale; les autres gates sont documentés ci-dessous.

## Registre

Le registre détaillé et les propriétaires de tâches sont dans [REGISTRE_TACHES.md](REGISTRE_TACHES.md). Les preuves sont dans les sous-dossiers `preuves-*` et les captures émulateur dans `captures/`.

## Gates et faits

| Élément | État | Preuve |
|---|---|---|
| JVM sur `b13b3fb` | PASS | 332 tests, 0 échec, 0 erreur, 0 ignoré (57 classes) |
| Lint sur `b13b3fb` | PASS | 0 erreur, 47 avertissements |
| Build debug + androidTest sur `b13b3fb` | PASS | BUILD SUCCESSFUL; APK `2743688c…` |
| Backend sur `b13b3fb` | PASS | 66 passed, 1 skipped, 1 warning, 2.28 s |
| AVD API 37 sur `b13b3fb` | PASS | 78/78 méthodes effectives : 77 PASS + retest font scale PASS |
| OpenClaw Android sur `b13b3fb` | PASS | `V62RealGatewayContextBridgeTest#realGatewayUsesAccelerated25MinuteFocusContextAndAcceptedProposal` : OK (1 test), 17,743 s, gateway réel et backend éphémère |
| Pixel — installation, HOME, hors ligne, Pause, notifications, accessibilité | PASS historique | Campagne sur `8890046…` / APK `48c7766e…`; aucun retest sur le SHA final par décision du propriétaire |
| Pixel — Focus/objectif | PASS historique | 0/120 avant fin, 25/120 après fin, 25/120 après relance à froid, annulation sans crédit |
| Localisation/mobilité | NOT_RUN, désactivées par défaut | `CAP_LOCATION=false`, `CAP_MOBILITY=false`; issue #1 ouverte |
| Restauration Pixel | Non exécutée, décision propriétaire | État QA synthétique conservé; sauvegarde d'origine intacte |
| Revue fonctionnelle finale | NO_GO sur `1251009` | Aucun P0/P1 code; P2 de preuve finale et de test de borne exacte, désormais traités sur `b13b3fb` |
| Revue sécurité finale | NO_GO sur `1251009` | Deux P1 confirmés (pauses longues, DST), corrigés par `b13b3fb`; P2 transport/pull/chemins restent ouverts |
| Revue du SHA final après correction | PENDING | Re-revue du delta `1251009..b13b3fb` et du dossier final |

## Correctifs P1 livrés

1. **Pauses longues :** le vérificateur compte la pause de 15 min insérée toutes les 4 cycles (`longBreakMinutes=15`, `longBreakEveryCycles=min(4, cycles)`), en plus des pauses courtes (`b13b3fb`).
2. **DST / heure ambiguë :** la fin de fenêtre est comparée en date et heure locales, sans décaler la borne au lendemain lors du retour à l'heure d'hiver (`b13b3fb`).
3. **Bornes de test :** durée totale exactement égale à 55 min acceptée; fin à 09:30 refusée; DST à Paris refusé (`ActionDispatcherTest`, 28/28).

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
