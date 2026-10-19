# V5 — Lot 5 : recette finale

Date : 19 octobre 2026. Branche `feat/atlas-v5-launcher`. Émulateur Android 17 (API 37.2) `Pixel_9_Pro_XL_2`; aucun téléphone physique.

| Contrôle | Commande | Résultat |
|---|---|---|
| CI GitHub (Lot 0) | run `39211209919` | Succès : SDK installé, JVM, lint, assemble, pytest sur le runner. |
| JVM + lint + build | `cd android && ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` | `BUILD SUCCESSFUL` (112 tests JVM, lint sans erreur). |
| Instrumentés (AVD) | `./gradlew :app:connectedDebugAndroidTest` | 31 tests. Première exécution : 3 échecs (voir ci-dessous). Après correction : `BUILD SUCCESSFUL`. |
| Backend | `pytest -q` (venv avec `backend/requirements.txt`) | `17 passed`. |
| Parcours HOME réels | voir [recette émulateur](03-recette-home-emulateur.md) | PASS sur 8 scénarios, hors exceptions listées dans ce document. |

## Régressions trouvées et corrigées

Le contrôleur est maintenant unique par processus (`AtlasApplication`). Deux tests de quarantaine d'état (`AtlasLaunchTest`) réécrivent le stockage puis recréent l'activité; ils ne relisaient plus le stockage. Ils réinitialisent désormais le contrôleur avec `resetControllerForTesting()`. Le test `LauncherPresentationTest` fermait le tiroir par un balayage plus court que le seuil de 72 dp; le geste de test est maintenant de 400 px. Aucun changement de comportement produit.

## Types de preuve

- **Parcours réellement exécutés** : lots 3 et 4 sur l'AVD (adb, uiautomator, captures).
- **Tests instrumentés** : exécutés sur l'AVD; certains effacent les préférences privées d'ATLAS (pas les données de l'émulateur). Le rôle HOME est perdu à la réinstallation de l'APK; il a été rétabli pour la fin de recette avec `cmd role add-role-holder` (outil de test AVD, pas un parcours utilisateur).
- **Scénarios simulés** : friction, coach déterministe, fixtures synthétiques.
- **Non testé** : téléphone physique, tablette `sw >= 600dp`, TalkBack interactif, profil professionnel, force-stop sans relance immédiate du HOME, apps tierces absentes de l'AVD (ex. Calculatrice).

## Limites

Observation : voir [02-observation-hors-interface.md](02-observation-hors-interface.md). Aucune détection continue n'est revendiquée; le HOME ne lance pas le polling UsageStats. Pas de LLM, OpenClaw ni service d'accessibilité.
