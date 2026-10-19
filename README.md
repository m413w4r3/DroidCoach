# ATLAS / DroidCoach — préparation V5

**V5 :** ATLAS fournit une `LauncherActivity` HOME autonome (heure, date, directive, favoris, tiroir avec recherche) distincte de l'application de gestion (`MainActivity`). Voir [architecture HOME](docs/v5/01-architecture-home.md), [observation hors interface](docs/v5/02-observation-hors-interface.md), [recette émulateur](docs/v5/03-recette-home-emulateur.md) et [recette finale](docs/v5/04-recette-finale.md).

ATLAS est une application Android Compose de coaching personnel, encore au stade de démonstration locale. Elle fonctionne sans compte, abonnement, serveur ni secret. Les propositions locales sont déterministes. Aucun modèle LLM ou agent externe n'est connecté.

La configuration livrée compile avec AGP 9.4.1, Gradle 9.6.0, JDK 21, `compileSdk 37.2` et `targetSdk 36`. La recette finale a été exécutée sur l'AVD Android 17 `Pixel_9_Pro_XL_2` (`emulator-5556`, x86_64, pages mémoire 16 Ko). Aucun téléphone Pixel physique n'a été utilisé. Voir le [rapport de recette V4](docs/v4/03-recette-android17.md) et l'[audit Android 17](docs/v4/04-defauts-et-limites.md).

## Fonctions livrées

- Accueil ATLAS, navigation Compose, réglages indépendants par capability et pause d'urgence hors ligne.
- Focus Pomodoro configurable, état local, pauses et fin automatique; notifications de phase facultatives, selon la permission Android.
- Routines consultatives et rappels d'habitudes; Android peut retarder les alarmes inexactes.
- Habitudes locales avec calendrier, check-in/retrait et export CSV.
- Bibliothèque de liens vidéo ajoutés manuellement; ouverture via Intent Android ou navigateur. Aucun accès au flux privé YouTube ni contrôle interne de ReVanced.
- Observation facultative via `UsageStatsManager`, après consentement système, allowlist et activation explicite. La détection est approximative et ne bloque pas l'application observée.
- Sync facultative vers le backend FastAPI. Elle est désactivée par défaut; les catégories partagées sont vides au départ. Le jeton est chiffré par Android Keystore.
- L'option HOME est activée uniquement après choix du rôle Android par l'utilisateur. Le launcher système reste accessible.

Les contrats d'extension IA sont documentés, mais OpenClaw, un LLM, une action Android pilotée par agent et une curation RSS ne sont pas implémentés. Voir [architecture réalisée](docs/v4/02-architecture-realisee.md) et [suite OpenClaw future](docs/v4/05-suite-openclaw-future.md).

## Démonstration sur l'AVD existant

Prérequis : JDK 21, Android SDK avec `platforms;android-37.2` et `build-tools;37.0.0`, Python 3.12 et l'AVD existant `Pixel_9_Pro_XL_2`. Ne pas créer ni effacer l'AVD.

```sh
/home/nill/Android/Sdk/emulator/emulator \
  -avd Pixel_9_Pro_XL_2 -no-snapshot-save -no-boot-anim -no-window -port 5556
export ANDROID_SERIAL=emulator-5556
cd android
./gradlew :app:assembleDebug
cd ..
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb shell monkey -p dev.atlas.coach 1
```

La démonstration est utilisable sans démarrer `backend/`. Les données d'exemple sont locales et synthétiques. Pour activer l'observation, l'utilisateur suit le lien vers « Accès aux données d'utilisation » dans Android, puis choisit les applications autorisées dans ATLAS. Une révocation arrête l'observation. Le geste `appops` n'est réservé qu'aux tests AVD documentés.

Pour un usage quotidien, démarrer Focus ou gérer ses habitudes depuis ATLAS. Les rappels et notifications peuvent être désactivés séparément dans les réglages de l'application ou d'Android. Le launcher système peut être rétabli dans Android → Applications par défaut → Application d'accueil, ou depuis l'action de sortie d'ATLAS.

Le backend local de développement et ses tests sont décrits dans `backend/README.md` et `scripts/v4-backend-e2e.sh`. Une configuration distante requiert une URL et un jeton configurés par l'utilisateur; elle ne fait pas partie de la démonstration sans serveur.

## Validation

```sh
export ANDROID_SERIAL=emulator-5556
./scripts/v4-recette.sh
./scripts/v4-backend-e2e.sh
```

La CI GitHub Actions exécute tests JVM, lint, assemblage debug et pytest. Elle ne lance pas d'émulateur Android 17; `connectedDebugAndroidTest` reste une recette locale sur AVD, documentée avec ses résultats sous [`docs/v4/logs/`](docs/v4/logs/).

## Limites connues

- `UsageStatsManager` fournit des événements best effort, avec latence variable. ATLAS n'intercepte pas l'écran, ne bloque pas d'application et n'affiche pas d'overlay sur le dialer, les réglages ou les fonctions d'urgence.
- Les alarmes de focus, routines et habitudes sont inexactes et peuvent être retardées. Aucune permission d'alarme exacte n'est demandée.
- `targetSdk 37` est reporté : Android 17 exigerait `ACCESS_LOCAL_NETWORK` pour le backend LAN et une nouvelle demande runtime. La cible retenue est 36, testée sur Android 17; le plan est dans l'audit.
- Aucun service d'accessibilité, root, Device Owner, kiosque, installation silencieuse ou manipulation d'APK tierce n'est livré.
- L'APK debug n'est pas signée pour une distribution et n'est pas publiée. Aucun test de publication Play Store n'a été effectué.

## Licence et attribution

Le code source est distribué sous [GNU GPL v3](LICENSE). Les adaptations de Reef conservent son attribution MIT particulière; les provenances Olauncher et Loop sont détaillées dans [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Les références auditées ne signifient pas qu'un service ou dépôt tiers est intégré.
