# V4 — R0 : baseline Android 17 (2026-10-19)

## Dépôt
- Branche de travail : `feat/atlas-v4-android17` (créée depuis `master`, HEAD `4cc8542`, identique à `origin/main`).
- Non suivis avant intervention : `README_APPLIQUER.md`, `prompts/REPRISE_V4_ANDROID17.md`. Aucun fichier modifié.

## Environnement relevé
| Élément | Valeur |
|---|---|
| SDK | `/home/nill/Android/Sdk` ; plateformes 23,24,26,31,33,34,35,**37.0,37.1** (pas de `platforms/android-37.2` : seule l'image système 37.2 est installée) |
| JDK | OpenJDK 21.0.12 |
| Gradle / AGP / Kotlin | 8.9 / 8.7.3 / 2.0.21 (inchangés) |
| `compileSdk` / `targetSdk` / `minSdk` | 35 / 35 / 26 (inchangés à ce stade) |
| Python | 3.12 ; pytest via venv `/tmp/v4/venv` (le pytest utilisateur manquait `pygments`) |
| KVM | `/dev/kvm` présent |
| AVD | `Pixel_9_Pro_XL_2`, Android 17, `SDK_INT=37`, `sdk_full=37.2`, x86_64, page 16 Ko, Google APIs Play Store, fingerprint `google/sdk_gphone16k_x86_64/emu64xa16k:17/CP41.260831.007/16416850:userdebug/dev-keys` |
| Gestion de l'AVD | lancé par Android Studio (série **`emulator-5556`**) ; `emulator-5554` apparaît « offline » (résidu). Toujours utiliser `ANDROID_SERIAL=emulator-5556`. |

Incident : un second émulateur lancé par l'orchestrateur sur `Pixel_9_Pro_XL` est resté « offline » ; il a été arrêté (processus lancé par nous uniquement). Aucun AVD créé, supprimé ni effacé.

## Résultats exécutés
| Commande | Résultat |
|---|---|
| `./gradlew --offline :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` | **PASS** — 51 tests JVM, 0 échec (la plupart des tâches étaient `UP-TO-DATE`) |
| `pytest -q` (backend, venv) | **PASS** — 14 tests |
| `:app:connectedDebugAndroidTest` sur Android 17 / 37.2, état initial | **FAIL 10/10** — `NoSuchMethodException: android.hardware.input.InputManager.getInstance` (Espresso 3.6.1 transitif incompatible API 37) |
| idem après ajout `androidTest: espresso-core:3.7.0` | **PASS 10/10** |
| Install + lancement APK debug | OK, `targetSdk=35`, aucune entrée crash du process `dev.atlas.coach` (les entrées du buffer crash concernent `android.hardware.uwb-service`, système) |

APK : `android/app/build/outputs/apk/debug/app-debug.apk`, SHA-256 `687e15efe460d69cd12c0531944622bc3eb937d9cb4578f6869568998764a88d` (build de la baseline, avant modifs V4). Permissions : aucune permission runtime (seule la permission interne AndroidX `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`).

Logs : `/tmp/v4/baseline-gradle.log`, `/tmp/v4/baseline-connected*.log` (copiés dans `docs/v4/logs/` par le lot H).

## Constats visuels (capture `captures/baseline-01-accueil.png`)
- Edge-to-edge mal géré sur API 37 : l'horloge système chevauche le libellé « ACCUEIL » ; le bouton « Pause d'urgence » est collé/sous la barre de navigation.
- L'accueil reste une pile de 10 boutons de poids équivalent (conforme au constat de l'audit).

## Limites
- Pas de Pixel physique ; un seul AVD ; pas de `platforms/android-37.2` donc `compileSdk 37.2` non encore testé (le lot A doit installer/valider via `sdkmanager`, hors ligne possible ou non à vérifier).
- Les 51/10/14 tests des anciens rapports sont désormais reproduits ici (10 instrumentés seulement après correctif Espresso).

## Après migration — lot A (2026-10-19)

### SDK et réseau
- `sdkmanager --list` a terminé avec le code 0 et a listé les plateformes stables `platforms;android-37.0`, `37.1` et `37.2`. Le réseau Google Maven / services Gradle est accessible.
- `sdkmanager --install 'platforms;android-37.2'` a téléchargé `platform-37.2_r01.zip`; `platforms/android-37.2/android.jar` est présent. `source.properties` indique `AndroidVersion.ApiLevel=37.2`, extension level 24.
- Build-tools disponibles après la compilation : `30.0.3`, `34.0.0`, `35.0.0`, `36.0.0-rc1`, `36.0.0` et `37.0.0`. Le build d’essai AGP 9.3.1 a téléchargé `36.0.0`, réutilisé ensuite par la chaîne finale; `37.0.0` était déjà installé.
- `sdkmanager` est en version 16.0. Il signale que ses outils ne lisent que les formats SDK XML jusqu'à 3 alors qu'un fichier local est en version 4; `--list` et l'installation de la plateforme ont néanmoins réussi.
- Appareil ciblé par les tests : AVD `Pixel_9_Pro_XL_2`, `emulator-5556`, Android 17, `SDK_INT=37`, `sdk_full=37.2`, ABI `x86_64`. Aucun AVD créé, wipé ou arrêté.

### Chaîne de build finale
| Élément | Valeur finale |
|---|---|
| AGP | 9.4.1 |
| Gradle wrapper | 9.6.0 |
| Kotlin | 2.2.10 intégré à AGP 9.4.1; Compose compiler plugin 2.2.10 |
| Compose BOM | 2024.12.01, inchangé |
| `compileSdk` | API 37, `minorApiLevel = 2` |
| `targetSdk` / `minSdk` | 35 / 26 |
| Java hôte / bytecode | JDK 21.0.12.1 / 17 |

Le DSL `compileSdk { version = release(37) { minorApiLevel = 2 } }` compile avec AGP 9.4.1 sur la plateforme 37.2. AGP 9.4.1 fournit Kotlin intégré; le plugin Kotlin Android séparé a été retiré, et `kotlinOptions` a été remplacé par `compilerOptions`.

Références officielles vérifiées : [configuration SDK Android 17](https://developer.android.com/about/versions/17/setup-sdk), [compatibilité AGP/API](https://developer.android.com/build/releases/about-agp), [notes AGP 9.4](https://developer.android.com/build/releases/agp-9-4-0-release-notes), [DSL compileSdk mineur](https://developer.android.com/reference/tools/gradle-api/8.13/com/android/build/api/dsl/CompileSdkSpec) et [compatibilité Kotlin Gradle](https://kotlinlang.org/docs/gradle-configure-project.html).

Le `targetSdk` reste à 35 : l’audit des changements Android 17 (`behavior-changes-17` et `behavior-changes-all`) n’a pas été réalisé dans ce lot. Son augmentation reste une décision ultérieure, réversible et soumise à audit.

### Recette exécutée
`scripts/v4-recette.sh` a terminé avec le code 0 le 2026-10-18 à 22:51:23 UTC. Le détail réel des commandes et sorties, les XML Gradle et pytest ainsi que le hash de l’APK sont dans [les logs de recette](logs/recette-20261018T225123Z/).

| Étape | Résultat |
|---|---|
| `adb devices -l` et contrôle API / `sdk_full` / identité AVD | PASS — `emulator-5556`, API 37 / 37.2 |
| `:app:testDebugUnitTest` | PASS — 51 tests, 0 échec |
| `:app:assembleDebug` | PASS |
| `:app:lintDebug` | PASS — tâche réussie, 13 avertissements Lint consignés ci-dessous |
| `:app:connectedDebugAndroidTest` | PASS — 10 tests, 0 échec sur l’AVD |
| `/tmp/v4/venv/bin/python -m pytest -q` | PASS — 14 tests, 1 avertissement de dépréciation Starlette/AnyIO externe |
| Rapports XML | PASS — 15 fichiers XML JVM, 1 instrumenté, 1 pytest copiés/présents sous `docs/v4/logs/` |

SHA-256 de `android/app/build/outputs/apk/debug/app-debug.apk` : `76975a1cd56e88318c466538a50d03c844b8acccb00c663db740eaa5e0637ef3`.

### Avertissements Lint et limites
- `OldTargetApi` est attendu tant que `targetSdk=35`; le changer sans l’audit de comportement demandé serait prématuré.
- `AndroidGradlePluginVersion` recommande Gradle 9.8.1 alors que le wrapper 9.6.0 requis par AGP 9.4.1 a passé la recette complète. Aucun saut supplémentaire du wrapper n’a été effectué pour éviter une migration sans besoin fonctionnel.
- Les autres avertissements sont des recommandations de mise à jour de dépendances (`compose-bom`, `activity-compose`, AndroidX test, `org.json`) et des constats `DataExtractionRules` / `UseKtx` sur des sources et le manifeste hors du périmètre autorisé. Ils sont visibles dans `android/app/build/reports/lint-results-debug.xml`; aucun fichier source principal ou manifeste n’a été modifié par le lot A.
- Aucun avertissement de compatibilité `compileSdk 37.2` ni d’API DSL Kotlin n’est apparu avec la chaîne finale. Les scénarios manuels §6.2–§6.11 restent hors de la recette technique du lot A; l’AVD est le seul environnement d’exécution, pas un Pixel physique.
