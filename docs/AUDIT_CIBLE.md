# Audit ciblé des références S6

Audit effectué le 2026-10-18 sur les clones en lecture seule de `references/repos/`. Les quatre révisions ci-dessous ont été vérifiées par `git rev-parse HEAD`. Le dépôt ATLAS lui-même n'est pas initialisé avec Git.

## Reef — reprise MIT avec attribution requise

- SHA audité : `044358fb26bf11aa85dddadf919106ca4053d926`.
- Licence exacte annoncée : MIT dans `README.md` §License; le dépôt ajoute une condition contractuelle d'attribution avec lien visible vers Reef.
- Le README interdit aussi de reprendre le nom Reef comme marque du produit et l'icône officielle; ATLAS ne reprend ni ce nom produit, ni cette icône.
- Le texte MIT complet du README est inclus dans `THIRD_PARTY_NOTICES.md`.
- Fichiers retenus : `timer/TimerState.kt`, `timer/TimerScreen.kt`, `data/FocusStats.kt`, `screens/FocusStatsScreen.kt`, `data/Routine.kt`, `services/routines/RoutineEvaluator.kt`, `services/routines/RoutineTimeCalculator.kt`.
- `TimerState` fournit les phases, cycles et durées Pomodoro, adaptées à nos modèles persistés.
- `TimerScreen` fournit le schéma de configuration des durées/cycles; l'écran ATLAS a été gardé Compose et n'importe pas de navigation Reef.
- Les statistiques adaptées calculent uniquement le temps de concentration et les cycles terminés de sessions Focus ATLAS.
- Les routines retenues sont limitées aux fenêtres horaires locales et à une suggestion de focus à confirmer.
- `Routines.kt` n'est pas repris : il écrit dans les préférences Reef et pilote des sessions, alarmes et limites d'apps.
- Quotas par application, statistiques d'utilisation, blocage d'apps, DND, notification permanente et services/accessibilité Reef sont écartés.
- Aucune permission de surveillance ou dépendance Gradle Reef n'a été ajoutée.
- L'écran « À propos / Licences » affiche l'attribution exigée et le lien `https://github.com/aload0/Reef`.
- Verdict : **adapter les composants Pomodoro, statistiques de focus et calcul des fenêtres; écarter le blocage et les quotas**.

## Olauncher — adaptation GPLv3 pour un accueil facultatif

- SHA audité : `f8e8d6497c457734c7a3171d36374157678e1f33`.
- Licence exacte annoncée dans `README.md` : GNU GPL version 3; le fichier racine `LICENSE` fournit le texte GPLv3 intégral.
- Fichiers retenus : `app/src/main/java/app/olauncher/data/AppModel.kt` et `app/src/main/java/app/olauncher/ui/HomeFragment.kt`.
- Le modèle de tri alphabétique et l'organisation d'un accueil textuel sont adaptés aux données Compose d'ATLAS.
- La liste ATLAS interroge uniquement les activités `MAIN` + `LAUNCHER` avec une déclaration `<queries>` dédiée.
- Le manifeste Olauncher déclare `QUERY_ALL_PACKAGES`; cette permission n'est pas reprise.
- Les services d'accessibilité, Device Admin, contrôle de services, permissions de wallpaper/status bar, usages et raccourcis multi-profils sont écartés.
- La capability `launcherHome` est indépendante et désactivée par défaut; épingles et capability restent dans l'état JSON local ATLAS.
- L'écran affiche l'heure et la directive du jour, propose des gestes Compose simples, et permet le lancement explicite des applications listées.
- La sortie vers les réglages d'accueil Android est accessible depuis l'écran; le rôle HOME n'est jamais modifié automatiquement.
- Les gestes Olauncher basés sur des `View` n'ont pas été copiés; les gestes du cadran Compose sont propres à ATLAS.
- Aucun code, ressource, configuration Gradle ou dépendance Android Olauncher complète n'est importé.
- Le texte GPLv3 et le dépôt source sont référencés dans `THIRD_PARTY_NOTICES.md` et dans l'écran de licences.
- Verdict : **adapter le modèle d'app et la structure textuelle; réécrire la liaison Android en requête ciblée; écarter permissions et services**.

## Loop Habit Tracker / uhabits-core — calculs GPLv3-or-later

- SHA audité : `c06b682f6b7b9c09eb278a06ffb57a42f0cddd38`.
- Licence exacte : GNU GPL v3 ou ultérieure, annoncée dans `README.md` et dans les en-têtes des sources Kotlin core retenues.
- Périmètre retenu : uniquement `uhabits-core/src/commonMain`; aucun fichier de `uhabits-android` n'est incorporé.
- Fichiers sources retenus : `models/Frequency.kt`, `models/Score.kt`, `models/ScoreList.kt`, `io/HabitsCSVExporter.kt` et `platform/io/Strings.kt`.
- La fréquence numérique, la mise à jour exponentielle de force et la fenêtre roulante sont adaptées au modèle de check-ins ATLAS.
- Les jours hors échéance deviennent `SKIP`, les jours dus sans check-in `NO`, et les check-ins explicites `YES_MANUAL`.
- `HabitRepository` reste le contrat d'accès; l'API évolue avec `strength()` et garde maps/JSON/SharedPreferences pour la persistance.
- L'export local reprend l'échappement CSV, ajoute une ligne de force par jour, et conserve les lignes ATLAS `habit` / `checkin` existantes.
- Les historiques et séries actuels restent ceux d'ATLAS; `StreakList` Loop n'est pas importé car ses `EntryList`/`SKIP` et règles de fréquence ne correspondent pas aux échéances existantes.
- Les classes SQL/SQLite, Room, injection, tâches, coroutines du module, UI multiplateforme et stockage Loop sont écartés.
- Aucune dépendance Gradle n'est ajoutée; les fichiers repris sont Kotlin/JVM autonomes.
- Les en-têtes copyright GPL d'origine sont préservés; chaque fichier comporte son chemin et SHA d'adaptation.
- L'about/licences indique Loop Habit Tracker, la licence GPLv3-or-later et le dépôt source.
- Verdict : **adapter fréquence, force et export CSV; garder stockage et séries ATLAS; écarter SQLite et le reste des modules**.

## Lawnchair — Apache-2.0, non utilisé

- SHA audité : `fc100ae9095a83b8b51c447a3b0a4ce2b399ad59`.
- Licence déclarée au `LICENSE.txt` racine : Apache License 2.0.
- Le README décrit une base Lawnchair 16 issue de Launcher3 Android 16 et un launcher AOSP étendu.
- Le candidat ciblé était le HOME, déjà couvert par un petit écran Compose ATLAS et un catalogue de raccourcis launcher.
- Aucun composant précis de Lawnchair ne justifie l'import de Launcher3, Quickstep, ses ressources et ses nombreuses liaisons Android.
- Aucun fichier Lawnchair n'a été copié ou adapté; il n'y a donc pas de source enfant à incorporer ni de dépendance Apache à ajouter.
- Le fichier `LICENSE.txt` a été consulté; aucun fichier source n'a été sélectionné pour examen de ses en-têtes individuels.
- Si un besoin futur vise un composant précis, son en-tête, sa notice et sa licence devront être vérifiés fichier par fichier avant reprise.
- La mention Apache dans le README ATLAS reste un avertissement général; cette révision S6 ne reprend pas de code Apache.
- Verdict : **écarter pour cette version, le besoin launcher étant couvert sans la base Android 16**.

## Fichiers ATLAS ← sources retenues

| Fichier ATLAS | Fichier source | SHA | Licence | Nature |
|---|---|---|---|---|
| `android/app/src/main/java/dev/atlas/coach/focus/ReefTimerState.kt` | `Reef/Reef/src/main/java/dev/pranav/reef/timer/TimerState.kt`; `Reef/Reef/src/main/java/dev/pranav/reef/timer/TimerScreen.kt`; `Reef/Reef/src/main/java/dev/pranav/reef/data/FocusStats.kt` | `044358fb26bf11aa85dddadf919106ca4053d926` | MIT + attribution Reef | Adaptation des modèles et calcul de cycle |
| `android/app/src/main/java/dev/atlas/coach/app/ReefPomodoroSetup.kt` | `Reef/Reef/src/main/java/dev/pranav/reef/timer/TimerScreen.kt` | `044358fb26bf11aa85dddadf919106ca4053d926` | MIT + attribution Reef | Adaptation du flux de configuration Compose |
| `android/app/src/main/java/dev/atlas/coach/app/FocusStatsScreen.kt` | `Reef/Reef/src/main/java/dev/pranav/reef/screens/FocusStatsScreen.kt`; `Reef/Reef/src/main/java/dev/pranav/reef/data/FocusStats.kt` | `044358fb26bf11aa85dddadf919106ca4053d926` | MIT + attribution Reef | Adaptation de la présentation de stats |
| `android/app/src/main/java/dev/atlas/coach/routines/FocusRoutine.kt` | `Reef/Reef/src/main/java/dev/pranav/reef/data/Routine.kt`; `Reef/Reef/src/main/java/dev/pranav/reef/services/routines/RoutineEvaluator.kt`; `Reef/Reef/src/main/java/dev/pranav/reef/services/routines/RoutineTimeCalculator.kt` | `044358fb26bf11aa85dddadf919106ca4053d926` | MIT + attribution Reef | Adaptation limitée aux plages de focus |
| `android/app/src/main/java/dev/atlas/coach/launcher/LauncherAppModel.kt` | `Olauncher/app/src/main/java/app/olauncher/data/AppModel.kt` | `f8e8d6497c457734c7a3171d36374157678e1f33` | GPLv3 | Adaptation du modèle de tri des apps |
| `android/app/src/main/java/dev/atlas/coach/app/LauncherHomeScreen.kt` | `Olauncher/app/src/main/java/app/olauncher/ui/HomeFragment.kt` | `f8e8d6497c457734c7a3171d36374157678e1f33` | GPLv3 | Adaptation de l'accueil textuel |
| `android/app/src/main/java/dev/atlas/coach/habits/thirdparty/loop/Frequency.kt` | `uhabits-core/src/commonMain/kotlin/org/isoron/uhabits/core/models/Frequency.kt` | `c06b682f6b7b9c09eb278a06ffb57a42f0cddd38` | GPLv3-or-later | Copie adaptée du modèle |
| `android/app/src/main/java/dev/atlas/coach/habits/thirdparty/loop/Score.kt` | `uhabits-core/src/commonMain/kotlin/org/isoron/uhabits/core/models/Score.kt` | `c06b682f6b7b9c09eb278a06ffb57a42f0cddd38` | GPLv3-or-later | Copie adaptée du calcul de force |
| `android/app/src/main/java/dev/atlas/coach/habits/thirdparty/loop/HabitStrengthCalculator.kt` | `uhabits-core/src/commonMain/kotlin/org/isoron/uhabits/core/models/ScoreList.kt` | `c06b682f6b7b9c09eb278a06ffb57a42f0cddd38` | GPLv3-or-later | Adaptation du calcul de fenêtre roulante |
| `android/app/src/main/java/dev/atlas/coach/habits/thirdparty/loop/LoopCsvLine.kt` | `uhabits-core/src/commonMain/kotlin/org/isoron/platform/io/Strings.kt` | `c06b682f6b7b9c09eb278a06ffb57a42f0cddd38` | GPLv3-or-later | Copie adaptée de l'échappement CSV |
| `android/app/src/main/java/dev/atlas/coach/habits/thirdparty/loop/LoopHabitCsvExporter.kt` | `uhabits-core/src/commonMain/kotlin/org/isoron/uhabits/core/io/HabitsCSVExporter.kt` | `c06b682f6b7b9c09eb278a06ffb57a42f0cddd38` | GPLv3-or-later | Adaptation de l'export aux contrats ATLAS |

## V4 — candidats évalués (non intégrés)

Le lot B a vérifié les révisions épinglées suivantes et n’a intégré aucun nouveau code tiers. Les adaptations déjà attribuées dans les sections S6 ci-dessus ne sont pas annulées par cette évaluation.

| Référence | Révision | Fichiers source examinés / frontière de l’audit | Décision B |
|---|---|---|---|
| Reef | 044358fb26bf11aa85dddadf919106ca4053d926 | timer/TimerState.kt, timer/TimerScreen.kt, data/FocusStats.kt, data/Routine.kt, routine/Routines.kt, services/routines/*, accessibility/UsageTracker.kt, accessibility/BlockerService.kt, FocusModeService.kt, MindfulLaunchActivity.kt | Adapter des modèles/calculs purs derrière les contrats ATLAS. Service timer, stockage/routines d’apps, notifications bloquantes et Accessibility écartés. |
| Olauncher | f8e8d6497c457734c7a3171d36374157678e1f33 | data/AppModel.kt, helper/Utils.kt#getAppsList, ui/HomeFragment.kt, ui/AppDrawerAdapter.kt, ui/AppDrawerFragment.kt, ui/SettingsFragment.kt, MainActivity.kt, helper/Extensions.kt#showLauncherSelector | Référence UX et algorithmes de liste/recherche; pas de nouvelle source ajoutée par B. |
| Lawnchair / Launcher3 | fc100ae9095a83b8b51c447a3b0a4ce2b399ad59 | settings.gradle, build.gradle, manifests; AlphabeticalAppsList.java, ShortcutRequest.java, LauncherAppWidgetHost.java, WorkProfileManager.java, PrivateProfileManager.java, quickstep/.../RecentsView.java | Spike structurel chiffré; garder HOME maison Compose, aucun module ou fork. |
| Loop / uhabits | c06b682f6b7b9c09eb278a06ffb57a42f0cddd38 | commonMain/.../Frequency.kt, WeekdayList.kt, Reminder.kt, EntryList.kt, StreakList.kt, Score.kt, ScoreList.kt; scheduler, receiver et widgets Android | Reprendre les calculs de fréquence/séries/rappels adaptés à l’unique HabitRepository; ne pas importer un second stockage ni les widgets Loop. |
| ReVanced | Manager 2a88257b71d35c9f141907ffea67c87e51bbb0db; locator 0d6dc3b4a8d6a0e5e5daf6e59a17fa9ed8fbed5f; miroir 015fe10a23ea5b919a3fda6e7da2c176517ae75d | Manager README, état officiel du dépôt de patches et licence GitLab; aucun clone | App indépendante. Utiliser ACTION_VIEW pour les liens vidéo; ne patcher ni piloter ReVanced. |
| Tasker | Docs officielles sans SHA versionné | Documentation publique « Intents » seulement | Pas d’intégration obligatoire; pas de protocole propre ATLAS vérifié. |
| Miniflux | Dépôt serveur HEAD observé c317f84bc3779bc5d936b75408b631589983c81a; API versionnée par serveur | API REST /v1 uniquement, aucun code Android | Backend facultatif de curation ultérieure; pas de contrôle YouTube/ReVanced. |

Le détail des dépendances, licences, preuves de spike, limites API 37 et recommandations par lot figure dans docs/v4/01-reemploi-open-source.md.
