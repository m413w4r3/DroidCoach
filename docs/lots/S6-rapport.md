# S6 — Reprise maximale de code open source

Date : 2026-10-18. Le dépôt ATLAS n'est pas initialisé avec Git. Les références ont été lues depuis les clones de `references/repos/` sans les modifier. La décision d'usage exclusivement privé et d'acceptation GPLv3 est celle du porteur du projet.

## Reprises livrées

- **Reef, MIT avec attribution requise** (`044358fb26bf11aa85dddadf919106ca4053d926`) : modèle/état Pomodoro et calcul des cycles de `timer/TimerState.kt` et `timer/TimerScreen.kt` adaptés dans `ReefTimerState.kt` et `ReefPomodoroSetup.kt`; statistiques de `data/FocusStats.kt` et `screens/FocusStatsScreen.kt` adaptées dans `FocusStatsScreen.kt`; modèle et calcul des fenêtres horaires de `data/Routine.kt`, `services/routines/RoutineEvaluator.kt` et `RoutineTimeCalculator.kt` adaptés dans `FocusRoutine.kt`/`FocusRoutinesScreen.kt`. Le contrat Focus existant reste la source de vérité. Les routines suggèrent un démarrage et ne démarrent qu'après une action explicite.
- **Loop Habit Tracker / uhabits-core, GPLv3-or-later** (`c06b682f6b7b9c09eb278a06ffb57a42f0cddd38`) : `Frequency.kt`, la mise à jour de score de `Score.kt`, la fenêtre roulante de `ScoreList.kt` et l'échappement/export de `Strings.kt` et `HabitsCSVExporter.kt` adaptés dans le dossier `android/app/src/main/java/dev/atlas/coach/habits/thirdparty/loop/`. `HabitRepository` expose la force et l'export CSV; la persistance JSON/SharedPreferences ATLAS reste en place.
- **Olauncher, GPLv3** (`f8e8d6497c457734c7a3171d36374157678e1f33`) : modèle de tri d'`app/src/main/java/app/olauncher/data/AppModel.kt` et structure d'accueil textuel de `app/src/main/java/app/olauncher/ui/HomeFragment.kt` adaptés dans `LauncherAppModel.kt` et `LauncherHomeScreen.kt`. L'accueil est une capability indépendante, inactive par défaut, affiche toujours la directive, permet les épingles et une sortie par la navigation Android. L'énumération d'applications a été écrite côté ATLAS avec `MAIN` + `LAUNCHER` dans `<queries>`.
- **Lawnchair, Apache-2.0** (`fc100ae9095a83b8b51c447a3b0a4ce2b399ad59`) : aucun code repris; son launcher AOSP étendu est trop large pour un composant isolé qui apporterait une valeur claire ici.

La liste exhaustive fichier Atlas ← source, SHA, licence et nature est dans [AUDIT_CIBLE.md](../AUDIT_CIBLE.md). Les décisions prises sont également consignées dans [DECISIONS_AUTOMATIQUES.md](../DECISIONS_AUTOMATIQUES.md). Les textes de licence et l'attribution Reef figurent dans [`THIRD_PARTY_NOTICES.md`](../../THIRD_PARTY_NOTICES.md); l'écran À propos / Licences de l'app présente les licences et le lien Reef.

## Éléments écartés et limites fonctionnelles

- Reef `Routines.kt`, quotas d'apps, suivi d'usage, blocage, mode strict, DND, alarmes, services et AccessibilityService : écartés. Le parcours ne requiert aucune observation et n'intercepte pas les apps tierces.
- uhabits `uhabits-android`, stockage Loop, SQLite/Room, injection, tâches et couches multiplateformes : écartés; aucune donnée Loop n'est lue ni écrite.
- Olauncher `QUERY_ALL_PACKAGES`, services d'accessibilité, permissions d'administration, profils utilisateurs, raccourcis et services système : écartés.
- Lawnchair : entièrement écarté, sans dépendance Apache ajoutée.
- Aucun nouveau module Gradle ni dépendance Gradle n'a été ajouté. Les contrats locaux Focus, Habits et règles, la directive ATLAS et la persistance JSON/SharedPreferences sont conservés. Les flags `focusRoutines` et `launcherHome` sont indépendants et désactivés par défaut.

La GPLv3 est acceptée pour cette utilisation privée. L'APK n'est pas destinée à la distribution; tout partage de binaire exige la publication des sources complètes sous les licences compatibles, la conservation des notices et la conformité à la GPLv3.

## Preuves exécutées

- `cd android && ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --rerun-tasks` : **PASS**; `BUILD SUCCESSFUL in 24s`; 51 tests JVM, 0 échec/erreur/ignoré. Deux avertissements Kotlin de cast non vérifié dans `PersistedRepositoriesTest.kt` restent visibles.
- `cd android && ./gradlew :app:connectedDebugAndroidTest` sur `emulator-5554`, AVD Android 11/API 30 : **PASS**, 10 tests, 0 échec/ignoré, `BUILD SUCCESSFUL in 50s`.
- Installation et exécution finale sur AVD, parcours Accueil → Focus → Habitudes → Accueil launcher; activité focus restaurée après `am force-stop` et relancement; `KEYCODE_HOME` a ramené vers `com.android.launcher3/.uioverrides.QuickstepLauncher`, launcher par défaut observé avant installation puis rétabli après le sélecteur Android.
- Les captures [Accueil](../captures/vm1c-accueil.png), [Focus](../captures/vm1c-focus.png), [Focus restauré](../captures/vm1c-focus-restaure.png), [Habitudes](../captures/vm1c-habitudes.png) et [Accueil launcher](../captures/vm1c-launcher.png) ont été inspectées visuellement.
- `aapt dump permissions` ne montre que la permission générée `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`; aucune permission sensible ou `QUERY_ALL_PACKAGES`. Le filtre logcat final n'a trouvé ni `FATAL EXCEPTION` ni `ANR in`.

Le détail des commandes, du SHA de l'APK et du parcours manuel est consigné dans [VALIDATION_VM.md](../VALIDATION_VM.md), section « Après reprise S6 ». Aucun essai sur Pixel réel n'a été effectué.
