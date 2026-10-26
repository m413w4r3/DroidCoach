# Lot A — baseline et SDK Android 17

## Objectif et statut

| Critère | Statut | Preuve |
|---|---|---|
| Détecter SDK installés, SDK 37.2 téléchargeable et accès réseau | PASS | `sdkmanager --list` puis installation réussie de `platforms;android-37.2` |
| Migrer vers une chaîne stable qui comprend le SDK 37.2 | PASS | AGP 9.4.1, Gradle 9.6.0, Kotlin intégré 2.2.10; syntaxe `minorApiLevel=2` compilée |
| Garder `targetSdk=35` avant audit de comportement | PASS | `android/app/build.gradle.kts`; aucun changement de comportement cible demandé ici |
| Fournir le contrôle d’environnement et la recette reproductible | PASS | `scripts/android17-env.sh`, `scripts/v4-recette.sh` |
| Exécuter les vérifications du lot sur l’AVD autorisé | PASS | 51 tests JVM, APK, lint, 10 tests instrumentés, 14 pytest |

## Modifications

- [android/build.gradle.kts]($HOME/perso/AndroCoach/atlas-preparation/android/build.gradle.kts) : AGP 9.4.1 et plugin Compose 2.2.10. AGP fournit Kotlin 2.2.10 intégré.
- [android/app/build.gradle.kts]($HOME/perso/AndroCoach/atlas-preparation/android/app/build.gradle.kts) : `compileSdk` réglé sur release 37, mineure 2; suppression du plugin Kotlin Android redondant avec AGP 9; migration vers `compilerOptions` pour garder le bytecode Java/Kotlin en 17.
- [android/gradle/wrapper/gradle-wrapper.properties]($HOME/perso/AndroCoach/atlas-preparation/android/gradle/wrapper/gradle-wrapper.properties) : Gradle 9.6.0, minimum indiqué par AGP 9.4.
- [scripts/android17-env.sh]($HOME/perso/AndroCoach/atlas-preparation/scripts/android17-env.sh) : exporte `ANDROID_HOME`, `ANDROID_SDK_ROOT`, `ANDROID_SERIAL` (par défaut `emulator-5556`), puis vérifie `adb`, la plateforme locale 37.2, le nom AVD `Pixel_9_Pro_XL_2`, `SDK_INT=37` et `sdk_full=37.2`. Le script ne démarre, n’arrête, ne recrée et ne wipe aucun AVD.
- [scripts/v4-recette.sh]($HOME/perso/AndroCoach/atlas-preparation/scripts/v4-recette.sh) : collecte `adb devices`, versions, unit tests, assemble, lint, tests connectés, pytest via `/tmp/v4/venv`, SHA-256 APK et XML Gradle/pytest dans un dossier horodaté de `docs/v4/logs/`.
- `docs/v4/00-baseline-android17.md` : inventaire SDK et résultats après migration.
- [Logs et XML de la recette](../logs/recette-20261018T225123Z/).

## Choix et compatibilité

`sdkmanager --list` a annoncé la plateforme stable 37.2; le téléchargement du paquet `platform-37.2_r01.zip` a réussi. Les plateformes 37.0 et 37.1 et Build Tools 37.0.0 étaient déjà installées. AGP 9.4.1 a ajouté Build Tools 36.0.0. Le réseau vers Google Maven et les distributions Gradle a fonctionné.

La sélection a été mesurée par des builds : AGP 8.9.3 ne connaît pas le DSL `minorApiLevel`; cette API Gradle est disponible à partir d’AGP 8.13.0. AGP 8.13.2 compilait le projet, mais avertissait qu’il n’avait été testé que jusqu’à API 36.1. AGP 9.3.1 avertissait encore sur 37.2 et demandait des opt-outs DSL/Kotlin. AGP 9.4.1 avec son Kotlin intégré et Gradle 9.6.0 compile SDK 37.2 sans ces avertissements de compatibilité; le build, lint et les tests instrumentés ont réussi.

Références officielles : [setup SDK Android 17](https://developer.android.com/about/versions/17/setup-sdk), [table de compatibilité AGP/API](https://developer.android.com/build/releases/about-agp), [notes AGP 9.4](https://developer.android.com/build/releases/agp-9-4-0-release-notes), [DSL de niveaux mineurs](https://developer.android.com/reference/tools/gradle-api/8.13/com/android/build/api/dsl/CompileSdkSpec) et [matrice Kotlin/Gradle/AGP](https://kotlinlang.org/docs/gradle-configure-project.html). La POM AGP 9.4.1 référence KGP 2.2.10, utilisé ici comme Kotlin intégré.

Le Compose BOM reste à 2024.12.01. Il a fonctionné avec Kotlin/Compose compiler 2.2.10; les recommandations Lint de versions plus récentes ne sont pas une exigence de la migration Android 17.

Le maintien de `targetSdk=35` est explicite et réversible jusqu’à l’audit des changements de comportement Android 17 demandé par le prompt. Ce choix est consigné dans ce rapport et dans le baseline, seuls documents de décision dans le périmètre de fichiers autorisé du lot A.

## Environnement observé

- SDK : `$HOME/Android/Sdk`; `sdkmanager` 16.0. `sdkmanager --list` réussit avec un avertissement non bloquant indiquant que l’outil connaît les SDK XML jusqu’à version 3 mais en a rencontré un de version 4.
- Plateformes présentes après installation : Android 23, 24, 26, 31, 33, 34, 35, 37.0, 37.1, 37.2.
- Build Tools présents après build : 30.0.3, 34.0.0, 35.0.0, 36.0.0-rc1, 36.0.0, 37.0.0.
- Hôte : OpenJDK 21.0.12.1; ABI AVD x86_64; série ADB `emulator-5556`.
- L’AVD observé est un émulateur Android 17 / API 37.2. Aucun téléphone Pixel physique n’a été utilisé.

## Commandes et preuves

```sh
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --list
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --install 'platforms;android-37.2'
bash -n scripts/android17-env.sh scripts/v4-recette.sh
scripts/android17-env.sh
scripts/v4-recette.sh
```

Résultat de `scripts/v4-recette.sh` : PASS, lancé le 2026-10-18 à 22:51:23 UTC. `:app:testDebugUnitTest` : 51/51; `:app:assembleDebug` : PASS; `:app:lintDebug` : PASS; `:app:connectedDebugAndroidTest` : 10/10 sur `Pixel_9_Pro_XL_2(AVD) - 17`; pytest : 14/14. Le run a capturé les rapports XML Gradle et pytest ainsi que les sorties complètes dans [le dossier de logs](../logs/recette-20261018T225123Z/).

APK : `android/app/build/outputs/apk/debug/app-debug.apk` — SHA-256 `76975a1cd56e88318c466538a50d03c844b8acccb00c663db740eaa5e0637ef3`.

## Lint, limites et suite

Lint a terminé avec succès et produit 13 avertissements : `OldTargetApi` (target 35 volontaire), une recommandation `AndroidGradlePluginVersion` de Gradle 9.8.1 alors que Gradle 9.6.0 est le minimum AGP requis et validé ici, des suggestions de mise à jour pour Compose/AndroidX test/JSON, puis `DataExtractionRules` et `UseKtx` sur des fichiers hors périmètre. Le rapport XML Lint est `android/app/build/reports/lint-results-debug.xml`. Aucun avertissement de compilation dû à l’usage du DSL `minorApiLevel` ou au Kotlin intégré n’est apparu.

Le pytest a produit un avertissement externe de dépréciation Starlette/AnyIO; les 14 tests ont réussi. La CLI `sdkmanager` signale le format XML 4, mais les opérations demandées ont réussi.

Le lot A valide la porte automatisable §6.1. Il ne certifie pas les scénarios manuels §6.2–§6.11, l’usage sur téléphone physique, ni l’audit Android 17 de `targetSdk`. Ces travaux restent à la recette d’intégration.

## Fichiers interdits et état Git

Aucun fichier `src/main/**`, backend, contrat, manifeste ou navigation n’a été modifié. Les avertissements Lint sur ces sources sont consignés pour l’intégrateur; aucun diff n’est proposé dans ce lot.

Les tests ont tourné sur la branche `feat/atlas-v4-android17`, à partir du HEAD `b44b14c` (commit du lot B) avec les changements Lot A présents dans le worktree. Le commit Lot A n’a pas été créé : `developer_git_commit_proposal` a rejeté les chemins relatifs et absolus en indiquant qu’ils étaient « outside the session’s git repositories ». Aucun commit CLI n’a été tenté. Le worktree conserve les changements A non commités. Le fichier préexistant non suivi `README_APPLIQUER.md` a été laissé intact.

### Résultats antérieurs exploratoires

Les essais intermédiaires AGP 8.13.2 et 9.3.1 ont compilé, mais ont émis des avertissements de support 37.2; la tentative 8.9.3 ne reconnaissait pas `minorApiLevel`. Ils sont remplacés par la combinaison finale ci-dessus et ne constituent pas le résultat de recette livré.
