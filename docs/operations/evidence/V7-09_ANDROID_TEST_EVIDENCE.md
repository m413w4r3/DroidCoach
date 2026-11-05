# ATLAS V7-09 — Preuves des tests Android JVM et du lint

**Date :** 2026-11-05
**Branche :** `feat/atlas-v7-09-reject-and-readiness`
**SHA revu :** `ae17409be3aecf7309c3142c31ed10ce488f5e22`
**Portée :** tests JVM et lint exécutés sur le code Android courant, sans
installation d'APK et sans test instrumenté.

## 1. Tests JVM

Commande exécutée depuis `android/` :

```sh
./gradlew :app:testDebugUnitTest --console=plain
```

Résultat Gradle :

```text
BUILD SUCCESSFUL in 1m 17s
25 actionable tasks: 6 executed, 19 up-to-date
```

Décompte indépendant des résultats JUnit générés dans
`android/app/build/test-results/testDebugUnitTest/` (121 fichiers XML) :

```text
jvm_tests 922 failures 0 skipped 0 files 121
```

Méthode : parsing XML des éléments `<testsuite tests= failures= errors=
skipped=>`. Aucun test instrumenté n'a été exécuté ; aucun APK n'a été
installé ou modifié.

## 2. Lint

Commande exécutée depuis `android/` :

```sh
./gradlew :app:lintDebug --console=plain
```

Résultat Gradle :

```text
BUILD SUCCESSFUL in 3m 17s
27 actionable tasks: 8 executed, 19 up-to-date
```

Rapport `android/app/build/reports/lint-results-debug.txt` :

```text
0 errors, 89 warnings
```

Les 89 avertissements sont ceux du code existant ; aucun fichier Android n'a
été modifié par V7-09. Ils ne sont pas des erreurs de build.

## 3. Conservation des artefacts

Les sorties de build sont ignorées par Git et ne sont pas commitées. Des
copies ont été placées dans le répertoire privé d'évidence V7-09, hors dépôt :

- `android-test-results/*.xml` — 121 fichiers JUnit ;
- `android-lint-results-debug.txt` — rapport lint ;
- `gradle-jvm-tests.log`, `gradle-lint.log` — journaux Gradle ;
- `pytest-targeted.xml`, `pytest-full.xml` — suites backend ciblée et
  complète.

Le répertoire est en `0700`, les fichiers en `0600`, avec `SHA256SUMS`.

## 4. Limites

- Aucun test Android instrumenté sur Pixel n'a été exécuté pour V7-09.
- Aucun APK n'a été reconstruit ni installé.
- Les tests JVM ne remplacent pas la recette physique REJECT, qui est
  documentée dans `V7-09_REJECT_QA.md`.
- La CI distante n'a pas été lancée : la branche n'a pas été poussée.
