# V5 — Lot 4 : observation hors interface

Date : 19 octobre 2026. Audit du code et essai sur l'AVD Android 17 API 37 (`Pixel_9_Pro_XL_2`, `emulator-5554`). Aucun téléphone physique n'a été utilisé.

## Audit du code existant

| Élément | Comportement constaté |
|---|---|
| `AtlasApp.kt`, `LaunchedEffect(activityStarted, CAP_APP_TRACKING)` | Le polling démarre seulement lorsque l'activité Coach est en état Lifecycle `STARTED`, appelle `controller.onHostResumed()`, interroge immédiatement puis attend `UsageRetention.VISIBLE_POLL_INTERVAL_MILLIS` (10 s). L'effet est annulé quand l'activité quitte `STARTED`. |
| `AtlasController.pollUsageObservation()` | Refuse de lire sans consentement explicite et AppOp autorisé; à la révocation système, purge consentement, curseur, snapshot, compteur de règles et historique. |
| `AndroidUsageStatsEventSource.query()` | Appelle `UsageStatsManager.queryEvents(begin, through)` et ne transforme que `ACTIVITY_RESUMED` / `MOVE_TO_FOREGROUND` en événements ATLAS. Il conserve le nom de paquet, l'horodatage et une empreinte opaque; pas le texte d'écran, le nom d'activité, le contenu de notification, URL ou compte. |
| `UsageObserver` et `UsageRetention` | L'allowlist est requise; le curseur évite de relire la même tranche; max. 256 événements sources par poll, lookback applicatif limité à 6 h, rétention locale max. 7 jours et 500 ouvertures. L'événement antérieur au consentement n'est pas repris. |
| `LauncherActivity` / HOME | Cette activité ne démarre pas le `LaunchedEffect` de `AtlasApp` et n'appelle pas `pollUsageObservation()`. Le fait qu'ATLAS soit le HOME et souvent au premier plan ne signifie donc pas que le polling UsageStats du Coach tourne. |

Dans une navigation plein écran normale, quand une autre activité recouvre le Coach, son état passe hors de `STARTED` et sa boucle est annulée. La nuance du code est `STARTED`, non un test explicite de l'activité topmost; aucun mode multi-fenêtre n'a été mesuré.

## Mesure sur l'AVD

Les apps préinstallées du système apparaissaient dans le tiroir HOME mais étaient toutes exclues du sélecteur UsageStats, conformément à la denylist et au filtrage des apps système. Le sélecteur affichait « Aucune application lançable admissible n'a été trouvée ». Pour conserver ce garde-fou, le test a utilisé uniquement l'activité synthétique existante `android/fixtures/distraction-app`, libellée « Distraction test », puis l'a désinstallée. Aucun paquet de données personnelles n'a été utilisé.

Commandes et consentement de test :

```sh
cd android
./fixtures/build-distraction-app.sh
adb install -r fixtures/distraction-app/app/build/outputs/apk/debug/app-debug.apk
adb shell appops set dev.atlas.coach GET_USAGE_STATS allow
adb shell appops get dev.atlas.coach GET_USAGE_STATS
```

Le build du fixture a terminé par `BUILD SUCCESSFUL in 6s` (`33 actionable tasks: 33 up-to-date`). L'installation a répondu `Success`; l'AppOp a répondu `GET_USAGE_STATS: allow`. Dans l'UI Observation, « Distraction test » a été cochée et l'action « Donner mon consentement et continuer » a affiché « Observation activée pour 1 application(s) sélectionnée(s). Les ouvertures sont lues uniquement quand ATLAS est visible. » Le document privé montrait alors la finalité `GENTLE_FRICTION`, l'allowlist `dev.atlas.fixture.distraction`, un curseur de départ et `records=[]`.

| Étape | Résultat mesuré |
|---|---|
| Lancement depuis le tiroir ATLAS | `adb shell input tap 500 790`; activité au premier plan `dev.atlas.fixture.distraction/.DistractionActivity`. Heure appareil après lancement : `1792394030039` ms UTC; événement conservé par la suite : `1792394030131` ms UTC. |
| Fixture au premier plan, Coach derrière | À `2026-10-19T07:13:59.421Z`, le fichier local avait encore `records=[]`; son curseur restait à `1792393938726`. Le Coach n'était plus `STARTED`. |
| Geste Accueil vers ATLAS HOME | `adb shell input keyevent KEYCODE_HOME`; activité reprise `dev.atlas.coach/.app.LauncherActivity`. À `2026-10-19T07:14:04.380Z`, l'historique était encore vide et le curseur inchangé. Aucun poll n'est exécuté par `LauncherActivity`. |
| Retour explicite au Coach | Après l'action « Coach », `MainActivity` a repris à `2026-10-19T07:15:00.823Z`. Le curseur persisté après `queryEvents` était `1792394101001` ms (`07:15:01.001Z`), soit 178 ms après le tap Coach; le stockage contenait alors un événement `dev.atlas.fixture.distraction` horodaté `07:13:50.131Z`. Le délai événement → requête dans ce parcours était 70,870 ms, essentiellement le temps passé avant de rouvrir le Coach; ce n'est pas une borne Android. |
| Poll suivant alors que le Coach reste au premier plan | Le curseur a ensuite avancé à `1792394151055` ms; le nombre de records est resté à 1. La cadence de code est 10 s et chaque lecture est une requête par plage, non une interception temps réel. |

Cette mesure confirme la récupération différée par `UsageStatsManager.queryEvents` lors de la reprise du Coach : l'événement existait dans la plage système, mais la base locale ATLAS n'a été mise à jour qu'au poll de la prochaine activité Coach. Quand ATLAS HOME est l'écran d'accueil, le launcher lui-même reste le premier plan, mais la boucle de `MainActivity` n'est pas active. Le code n'obtient pas une notification immédiate au lancement d'une autre app.

Après mesure, la révocation a été faite depuis l'écran Observation. L'UI a affiché `Allowlist active : aucune` et `historique actuel : 0 événement(s)`; `atlas_usage_observation_v1.xml` ne gardait que le consentement révoqué. L'AppOp a été remise à `default` (`GET_USAGE_STATS: default`) et `adb uninstall dev.atlas.fixture.distraction` a répondu `Success`. Aucun polling de test ni fixture n'est resté actif.

## Capacités et limites Android

- `PACKAGE_USAGE_STATS` est un accès spécial accordé dans les réglages système; il ne donne pas un flux de rappel continu. `UsageStatsManager.queryEvents()` interroge une plage d'historique. Android ne conserve les événements que « quelques jours » et ne garantit pas une latence instantanée. ATLAS borne en plus ses propres lectures à 6 h; une reprise plus tardive peut laisser des événements hors de sa plage applicative.
- `WorkManager` et `JobScheduler` sont adaptés au travail persistant différé. Le travail périodique a un minimum de 15 minutes et s'exécute dans une fenêtre flexible; Doze, contraintes et économie batterie peuvent le retarder. Aucun résultat ne doit être présenté comme une interception en temps réel.
- Un foreground service doit déclarer un type adapté au travail, les permissions correspondantes et un usage conforme aux restrictions de démarrage en arrière-plan. Il est visible par une notification persistante, a un coût batterie continu et ne transforme pas `queryEvents()` en callback exact. Il n'y a pas de service d'observation à ajouter pour promettre une détection immédiate.
- Doze diffère les travaux réseau et tâches planifiées jusqu'aux fenêtres de maintenance. App Standby classe les apps en buckets et limite le travail différé selon l'état d'utilisation; la politique précise dépend d'Android et du fabricant. Le bucket de cet AVD n'a pas été forcé ni mesuré.

Sources Android officielles : [UsageStatsManager](https://developer.android.com/reference/android/app/usage/UsageStatsManager), [travail périodique WorkManager](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work), [JobScheduler](https://developer.android.com/reference/android/app/job/JobScheduler), [déclaration des foreground services](https://developer.android.com/develop/background-work/services/fgs/declare), [types de foreground service](https://developer.android.com/develop/background-work/services/fgs/service-types), [restrictions de démarrage en arrière-plan](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start), [Doze et App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby), [App Standby Buckets](https://developer.android.com/topic/performance/appstandby).

## Architecture recommandée, sans évolution de code dans ce lot

1. Garder comme défaut la lecture différée au retour d'une activité Coach visible/`STARTED`, après AppOp Android, finalité affichée et consentement ATLAS distinct avec allowlist choisie. Purger immédiatement à la révocation et conserver les plafonds locaux.
2. Si un besoin hors interface est validé plus tard, étudier un `PeriodicWorkRequest` optionnel d'au moins 15 minutes derrière un capability flag indépendant, désactivé par défaut, plus un consentement séparé. Le worker doit revérifier AppOp/consentement à chaque passage, lire uniquement les paquets autorisés, dédupliquer avec le curseur, borner les plages et signaler une lacune si Doze ou une interruption dépasse la rétention; il ne bloque jamais l'app observée.
3. Ne pas introduire de foreground service pour simuler une précision que l'API ne fournit pas. Si un cas d'usage distinct le justifie, il faut d'abord établir son type Android autorisé, son indication visible, le consentement spécifique, le comportement de révocation et le budget batterie.
4. Tester séparément Doze, buckets, fabricant et cycles de reboot avant d'annoncer une couverture en arrière-plan. Cette recette n'a testé ni Doze, ni les quotas App Standby, ni le comportement d'un téléphone réel.

Aucun code de production n'a été modifié dans ce lot : le comportement observé correspond à l'indication actuelle de l'UI (polling visible uniquement). Aucun AccessibilityService, root, LLM ou OpenClaw n'a été introduit.
