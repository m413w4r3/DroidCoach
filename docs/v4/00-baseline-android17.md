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
