# CI finale V6.2 — exécution reproductible

**Statut global : BLOCKED** — la build Android est acceptée ; le runner pytest de cette session reste bloqué et ne fournit pas de résumé final.

## SHA et environnement

- SHA testé : `8890046012965f3a3112ff0c419014c74e189d11`.
- `git status --short` était vide au départ. Le travail CI a été lancé depuis une archive propre de ce SHA dans `<tmp-ci-dir>`, afin de garder les sorties de build hors du worktree.
- Linux x86_64, OpenJDK `21.0.12.1`, Gradle wrapper `9.6.0`, SDK Android `platforms;android-37.2`, build-tools `37.0.0`, Python backend `3.12.3`.
- Le build Gradle a utilisé le `GRADLE_USER_HOME` par défaut, `<gradle-home>`. Aucun ADB, émulateur ou téléphone n’a été utilisé.

Le détail de l’environnement est dans [environment.txt](preuves-ci/environment.txt). Les journaux complets et les sorties XML restent sous `<tmp-ci-dir>/`.

## Résultats

| Vérification | Statut | Résultat |
| --- | --- | --- |
| `:app:testDebugUnitTest` | PASS | 321 tests, 0 échec, 0 erreur, 0 ignoré ; XML de 57 classes. La tâche a été exécutée, pas marquée `UP-TO-DATE`. |
| `:app:lintDebug` | PASS | 0 erreur lint, 52 avertissements lint ; la tâche a été exécutée. |
| `:app:assembleDebug` | PASS | APK produit et signé en debug. |
| `:app:assembleDebugAndroidTest` | PASS | APK de test produit et signé en debug. La suite instrumentée n’a pas été lancée. |
| Backend `pytest -q` | BLOCKED | Trois exécutions n’ont pas rendu de résumé pytest ; elles se sont arrêtées au même point après 37 résultats visibles. Aucun échec n’a été affiché avant interruption. |
| `git diff --check` | PASS | Aucune erreur d’espacement. Aucun fichier source, manifeste, Gradle ou test n’a été modifié. |

Gradle a terminé par `BUILD SUCCESSFUL` en 3 minutes : **81 tâches actionnables, 81 exécutées**. Les tâches demandées de test, lint et assemblage ne sont pas `UP-TO-DATE`. Sept avertissements Kotlin de casts non vérifiés figurent aussi dans la compilation des sources JVM de test ; ils ne sont pas des avertissements lint.

## Backend et limites d’exécution

Les exécutions pytest sont détaillées dans [backend-summary.txt](preuves-ci/backend-summary.txt). La première, depuis l’archive propre, est restée sans progression après 37 points pendant plus de cinq minutes et a été interrompue (code 130). La relance dans le worktree, avec le même venv, a reproduit les 37 points sans résumé puis a été interrompue. Une tentative depuis le checkout principal, dont le HEAD est aussi `8890046…`, s’est arrêtée au même point.

Le parent Paseo a communiqué un résultat indépendant sur ce même SHA : **66 passed, 1 skipped en 2,53 s** avec `cd backend && .venv/bin/python -m pytest -q`. Cette corroboration est consignée séparément ; elle ne remplace pas le résultat incomplet de ces exécutions. La collecte pytest a recensé 67 cas. Les 37 points partiels ne permettent pas de compter proprement les statuts finaux `passed / failed / skipped`, donc le gate backend reste `BLOCKED`, sans conclure à un échec de code.

Comparaison factuelle avec la baseline parentale : JVM identique à 321 tests ; lint à 52 avertissements contre 53 dans la baseline et 0 erreur ; backend non mesurable sur ce runner malgré le résultat indépendant 66/1. Ces écarts sont à examiner et ne constituent pas à eux seuls un verdict de régression.

Les deux APK sont listés avec leur SHA-256 et le certificat de signature dans [apk-signatures.txt](preuves-ci/apk-signatures.txt). Les APK eux-mêmes et les rapports complets restent dans `/tmp` et ne sont pas ajoutés au dépôt.

## Commandes exécutées

Build Android, depuis `android/` de l’archive temporaire :

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --rerun-tasks --max-workers=1 --console=plain
```

L’environnement du build fixait aussi `ANDROID_HOME=<android-sdk>` et `ANDROID_SDK_ROOT=<android-sdk>`. Le journal complet est `<tmp-ci-dir>/gradle-ci.log`.

Backend, depuis `backend/` du worktree, avec le venv externe présent :

```sh
PYTHONDONTWRITEBYTECODE=1 PYTEST_ADDOPTS='-p no:cacheprovider' PYTHONPATH=<worktree>/v62-ci-final/backend \
  <repo>/backend/.venv/bin/python -m pytest -q
```

Les deux variables pytest désactivent seulement l’écriture de bytecode et du cache pytest ; la commande testée reste `python -m pytest -q`. Le journal correspondant est `<tmp-ci-dir>/backend-pytest-corroboration.log`.

Note de pré-vol : avant la correction d’environnement du parent, une sonde `--version` a téléchargé le wrapper Gradle dans `<tmp-gradle-dir>`. Ce cache temporaire n’a pas servi au build ci-dessus, qui a utilisé `<gradle-home>`.

## Addendum parent — backend sur le même SHA

Le parent a exécuté indépendamment, dans le checkout principal au SHA `8890046012965f3a3112ff0c419014c74e189d11` :

```sh
cd backend && .venv/bin/python -m pytest -q
```

Résultat réel : **66 passed, 1 skipped, 1 warning in 2.45 s**. Le journal complet est conservé hors dépôt. Ce run frais, sur le même SHA et dans l'environnement de référence, lève le `BLOCKED` du runner CI pour le gate backend ; les exécutions bloquées de l'agent restent consignées comme un artefact d'environnement de son archive temporaire, sans échec de code observé.

## Run final `b13b3fb` — 2026-10-25

Après les correctifs P1 de mandat (`4aff91a`, `1251009`, `d718540`/`b13b3fb`), une exécution finale complète a été lancée depuis le checkout de clôture :

```sh
cd android
./gradlew --offline --no-daemon --console=plain --rerun-tasks \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

| Vérification | Résultat |
|---|---|
| `:app:testDebugUnitTest` | **332 tests, 0 échec, 0 erreur, 0 ignoré** (57 classes) |
| `:app:lintDebug` | **0 erreur, 47 avertissements** |
| `:app:assembleDebug` + `assembleDebugAndroidTest` | **BUILD SUCCESSFUL**, 81 tâches exécutées, 1 min 45 s |
| APK debug | SHA-256 `2743688c830a3e3df42b9db3c4d251cbf796837160d252e5976098346b2448ac` |
| APK androidTest | SHA-256 `4a916f12f324b4cf10574038d42750bc6fe006f8dbc856ad9903225c824afa00` |
| Certificat debug | SHA-256 `da3014662eb65e50a87ef364a2e583c331398795b222e3b37b1a16553a00eeee` |

Backend, même checkout : `66 passed, 1 skipped, 1 warning in 2.28 s`.

Les artefacts détaillés et le journal Gradle expurgé sont dans [preuves-finales-b13b3fb/PREUVES_FINALES.md](preuves-finales-b13b3fb/PREUVES_FINALES.md). Les résultats ci-dessus sont rattachés au SHA final, pas au SHA de base.

## Run final `18484e2` — 2026-10-25

Après le correctif R3 (`f21fa8e`/`18484e2` : pause nulle et revalidation `focus.start`), la CI complète a été rejouée depuis le checkout de clôture.

| Vérification | Résultat |
|---|---|
| `:app:testDebugUnitTest` | **337 tests, 0 échec, 0 erreur, 0 ignoré** (57 classes) |
| `:app:lintDebug` | **0 erreur, 47 avertissements** |
| `:app:assembleDebug` + `assembleDebugAndroidTest` | **BUILD SUCCESSFUL**, 81 tâches exécutées, 1 min 26 s |
| APK debug | SHA-256 `8064dc0afac476e8a081981be4abc65620e96f169b135315d17a7f5fb5621b55` |
| APK androidTest | SHA-256 `4a916f12f324b4cf10574038d42750bc6fe006f8dbc856ad9903225c824afa00` |
| Certificat debug | SHA-256 `da3014662eb65e50a87ef364a2e583c331398795b222e3b37b1a16553a00eeee` |

Backend, même checkout : `66 passed, 1 skipped, 1 warning in 2.59 s`.

Artefacts : [preuves-finales-18484e2/PREUVES_FINALES.md](preuves-finales-18484e2/PREUVES_FINALES.md).

## Run final `1a6f761` — 2026-10-25

Après le correctif R4 (`edbb3f5`/`1a6f761` : refus du mode continu), la CI complète a été rejouée.

| Vérification | Résultat |
|---|---|
| `:app:testDebugUnitTest` | **339 tests, 0 échec, 0 erreur, 0 ignoré** (57 classes) |
| `:app:lintDebug` | **0 erreur, 47 avertissements** |
| `:app:assembleDebug` + `assembleDebugAndroidTest` | **BUILD SUCCESSFUL**, 81 tâches exécutées, 1 min 16 s |
| APK debug | SHA-256 `dc1c3e004fb7a3394c47dfc4b28407e211a06f48db51e86bbc7b665f05d62d87` |
| APK androidTest | SHA-256 `4a916f12f324b4cf10574038d42750bc6fe006f8dbc856ad9903225c824afa00` |
| Certificat debug | SHA-256 `da3014662eb65e50a87ef364a2e583c331398795b222e3b37b1a16553a00eeee` |

Backend, même checkout : `66 passed, 1 skipped, 1 warning in 2.72 s`.

Artefacts : [preuves-finales-1a6f761/PREUVES_FINALES.md](preuves-finales-1a6f761/PREUVES_FINALES.md).
