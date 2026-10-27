# R3-USAGE-UI-01 — Usage global réel, résumés et quotas

| Clé | Valeur |
|---|---|
| TASK_ID / statut | `R3-USAGE-UI-01 / READY` |
| Objectif | Raccorder le noyau `GlobalUsageObserver` à un parcours utilisateur complet et qualifier Usage Access réel sur l'émulateur avec des applications/fenêtres synthétiques |
| Dépendances | noyau usage R2 intégré |
| BASE_SHA | à remplir au lancement |
| Profil exigé | `codex:gpt-6-luna` / `xhigh` |
| Worktree | dédié |
| Écrire | `android/app/src/main/java/dev/atlas/coach/usage/**`, nouveaux `android/app/src/main/java/dev/atlas/coach/app/GlobalUsage*.kt`, tests `usage/**` et `app/GlobalUsage*` |
| Ne pas écrire | `AtlasController.kt`, `AtlasApp.kt`, `ObservationScreen.kt` (parent), manifeste, backend, relay, calendar |
| Oracle | JVM : consentement global, permission refusée/accordée, agrégation, quotas, révocation, purge, limitation de fréquence, pseudonymisation ; test instrumenté avec source synthétique et, pour le parent, Usage Access réel sur `emulator-5554` |
| Stop conditions | besoin de lire du contenu d'écran/notifications ; besoin de modifier hors scope ; UsageStats promis comme exact ; modèle/effort non conforme |
| Livrables | commit SHA, diff, tests, rapport |

## Détail

1. Écran `GlobalUsageScreen` : consentement global distinct, état réel d'Usage
   Access, résumé de la fenêtre, total, apps (nom seulement si catégorie
   `package_names` consentie, sinon `app_ref` pseudonyme), fiabilité, couverture,
   dernière mise à jour, quotas (OK/WARNING/EXCEEDED/UNKNOWN), révocation et purge.
2. Limitation de fréquence : intervalle minimal entre collectes, pas de collecte
   en boucle, pas de chevauchement ; la collecte visible existante reste séparée.
3. Le consentement révoqué purge le curseur et le résumé ; une permission refusée
   ne produit aucune lecture.
4. Aucune conclusion sur la précision à la milliseconde ; documenter les limites.
5. Tests JVM avec source synthétique ; tests instrumentés avec événements
   contrôlés. Le parent exécute la recette Usage Access réelle sur l'émulateur.
6. Le parent câble l'écran dans la navigation et `AtlasController`.

## Reprise

Session ID, SHA, tests, blocages.
