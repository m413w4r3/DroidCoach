# ATLAS V6.2 — validation finale ciblée

Date : 20 octobre 2026. Cette passe couvre le correctif JVM/lint et les validations globales demandées. Les autres changements déjà présents dans le dépôt n’ont pas été modifiés.

## Corrections

- `LocationCaptureManager` vérifie explicitement qu’une permission coarse ou fine est accordée juste avant les trois appels Android concernés. Une `SecurityException` pendant une révocation remonte jusqu’au manager et devient `PERMISSION_REQUIRED`. Le fallback listener API 26–29, la fraîcheur et le timeout restent en place.
- `AgentContextAssemblerTest` vérifie l’ensemble des catégories autorisées (7) tout en conservant les assertions de confidentialité sur le texte brut.
- `LauncherAppRulesTest` attend l’ordre alphabétique des résultats partiels (`Alarm Clock` avant `Desk Clock`). Le classement de production reste inchangé.

## Résultats exécutés

| Commande | Résultat |
| --- | --- |
| `cd android && ./gradlew testDebugUnitTest lintDebug assembleDebug compileDebugAndroidTestKotlin assembleDebugAndroidTest --console=plain` | `BUILD SUCCESSFUL` en 35 s ; 81 tâches, 16 exécutées et 65 à jour. Les 256 tests JVM passent : 0 échec, 0 erreur, 0 ignoré, sur 47 suites. |
| `backend/.venv/bin/python -m pytest backend/tests -q` | 66 réussis, 1 ignoré, 1 avertissement de dépréciation Starlette/AnyIO. |
| `cd android && ./gradlew --stop --console=plain` | 1 daemon Gradle arrêté ; `jps -l` ne montre plus de processus Gradle. |

Détail des suites ciblées : `LocationCaptureManagerTest` 10/10, `AgentContextAssemblerTest` 11/11 et `LauncherAppRulesTest` 5/5.

Lint termine sans erreur et sans `MissingPermission`. Le rapport contient 49 avertissements non bloquants, notamment sur les ressources de traduction, la cible SDK et les versions de dépendances. Aucun n’a nécessité une modification hors périmètre.

`compileDebugAndroidTestKotlin` et `assembleDebugAndroidTest` ont été inclus dans Gradle. Aucun test instrumenté n’a été lancé et aucun AVD n’a été démarré pendant cette passe.

## Preuves natives et limites

- La preuve antérieure Gateway réel sur AVD 34 et le parcours hors ligne restent disponibles dans [le journal Gateway](logs/recette-native/gateway-final/v62-gateway-instrumentation.log) et [le journal hors ligne](logs/recette-native/gateway-final/v62-offline-instrumentation.log). Chaque journal rapporte `OK (1 test)` ; ces scénarios n’ont pas été rejoués.
- L’AVD API 37 reste bloqué par le crash système SurfaceFlinger observé lors de la recette. S9 doit reprendre l’acquisition coarse sur un environnement utilisable.
- TalkBack et un Pixel physique restent non vérifiés. Aucun résultat de cette passe ne prétend à un test Pixel.

## Journaux complets

Les sorties de commandes sont conservées avec les chemins locaux remplacés et les champs de credential filtrés :

- [Gradle Android](logs/validation-finale/android-gradle.log)
- [pytest backend](logs/validation-finale/backend-pytest.log)
- [Arrêt du daemon Gradle](logs/validation-finale/gradle-stop.log)
