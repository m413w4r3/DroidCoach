# Lot D — observation consentie et friction R3

Date : 2026-10-19 · branche `feat/atlas-v4-android17` · toolchain AGP 9.4.1 / Gradle 9.6.0 / compileSdk 37.2 / targetSdk 35.

## Résultat

Le noyau d’observation est implémenté dans `usage/**` et le filtrage des règles dans `rules/**`. La collecte reste fermée tant qu’un but, une allowlist explicite et l’accès système vérifié ne sont pas réunis. Le provider Reef/accessibilité est absent et explicitement `UnsupportedProvider`.

La recette réelle à quatre ouvertures est **BLOCKED** par le manifeste hôte, qui est hors du périmètre de ce lot : il ne déclare pas `PACKAGE_USAGE_STATS` ni `POST_NOTIFICATIONS`. L’essai `appops set` autorisé sur `emulator-5556` a laissé `GET_USAGE_STATS` en mode `default`; le scénario fixture instrumenté n’a donc pas lu d’événements UsageStats et aucune notification réelle n’est revendiquée. Les diffs nécessaires sont ci-dessous, non appliqués.

## API livrée

- `UsageEventSource.query(cursor, throughUtc, maxRawEvents)` avec `SimulatedUsageEventSource` fondé sur `AppEventSimulator` et `AndroidUsageStatsEventSource` fondé sur `UsageStatsManager.queryEvents`.
- `UsageObserver.acceptConsent`, `poll`, `revokeConsent`, `deleteHistory` et `exportHistoryCsv`. Stores injectables; l’adaptateur local SharedPreferences est disponible pour le raccordement par le parent.
- `ObservationConsent` exige une finalité et des packages choisis. Le consentement est enregistré après la purge des anciens packages et l’établissement du curseur de départ. Une révocation écrit d’abord l’état désactivé, puis efface le curseur, les compteurs et les événements.
- `LocalPolicy` garde des valeurs par défaut pour compatibilité source. L’observateur remplace toujours son allowlist par celle du consentement; le filtre du moteur laisse `null` pour les anciens appels de simulateur. À la migration persistée, une allowlist absente doit devenir vide.
- `FrictionPresenter` est l’interface. `AndroidFrictionPresenter` publie une notification privée avec PendingIntents immuables pour ouvrir volontairement `IntentionActivity` ou appliquer la pause de 30 min. Aucune Activity et aucun overlay ne sont lancés depuis le polling.
- `UsageStatsProvider.enabled` vaut `false` par défaut. `UnsupportedProvider` porte le flag `observation.accessibility`, toujours faux, avec la raison. Aucun `UsageTracker`, `BlockerService`, `MindfulLaunchActivity` ou `AccessibilityService` Reef n’a été repris.

## Polling, sécurité et données

- Cadence de référence : un appel toutes les **10 s au maximum lorsque l’Activity ATLAS est visible**. Il n’y a pas de Worker dans ce lot. Une invocation WorkManager ultérieure resterait best-effort et sa période minimale de 15 min ne serait pas du temps réel. UsageStats ne bloque jamais au lancement; la réaction peut attendre le prochain retour visible vers ATLAS.
- Chaque requête limite la fenêtre de rattrapage à **6 h**, le traitement à **256 événements bruts** et conserve un curseur horodaté avec les fingerprints des événements à la borne. `ACTIVITY_RESUMED` et `MOVE_TO_FOREGROUND` sont les seuls types transformés en ouvertures.
- Les répétitions du même package au premier plan sont ignorées. SystemUI sert de transition transitoire : le retour au même package dans les 20 s est traité comme un retour depuis l’ombre de notifications. Android n’expose pas toujours la cause d’une reprise; cette règle est une heuristique, pas une déduplication parfaite. Les ouvertures après HOME restent distinctes.
- Le denylist statique inclut ATLAS, Settings, SystemUI, dialer/téléphonie, urgence, contrôleurs d’autorisations et installateurs. L’adaptateur ajoute HOME courant, applications téléphone/SMS par défaut, administrateurs actifs, services d’accessibilité activés et applications système. Un package non résolu par visibilité est refusé. Une exception de règle ou une allowlist ne peut jamais retirer un package protégé.
- Les événements conservés ne contiennent que l’identifiant opaque, le package et l’heure. La limite est 7 jours au prochain polling et 500 entrées; les IDs de déduplication sont plafonnés à 2 000. Révocation et suppression effacent l’historique. L’export CSV n’inclut ni texte d’écran, notifications, URL ni comptes.
- Les règles existantes restent déterministes : seuil, fenêtre, cooldown, heures calmes, exceptions, pause globale. L’observateur ne collecte ni n’enregistre un package hors allowlist ou protégé avant d’appeler le moteur.

## Fixture

`android/fixtures/distraction-app/` est un build Gradle indépendant, package `dev.atlas.fixture.distraction`, activité minimale libellée « Distraction test », sans données personnelles ni dépendance ATLAS. Construction : `bash android/fixtures/build-distraction-app.sh`. Aucun changement à `android/settings.gradle.kts` n’est nécessaire.

## Environnement et validation exécutée

- Branche `feat/atlas-v4-android17`, HEAD de départ `c2cba5a636aebb24d9abed97ee4c7e01df9ae749`; seul `emulator-5556` est utilisé. `java -version` : OpenJDK 21.0.12.1; wrapper : Gradle 9.6.0; Python : 3.12.3; AVD : API 37 / `sdk_full=37.2`.
- `bash android/fixtures/build-distraction-app.sh` — **PASS**, APK fixture produite.
- `./gradlew :app:testDebugUnitTest --tests 'dev.atlas.coach.rules.*' --tests 'dev.atlas.coach.usage.*'` — **PASS, 25 tests, 0 échec** : 14 RuleEvaluator, 2 denylist, 9 observer, dont la révocation concurrente AppOps.
- `./gradlew :app:testDebugUnitTest` — **PASS, 77 tests, 0 échec** au dernier passage.
- `./gradlew :app:compileDebugAndroidTestKotlin` et `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest` — **PASS**.
- `ANDROID_SERIAL=emulator-5556 ./gradlew :app:connectedDebugAndroidTest` — **PASS, 13 tests, 0 échec/ignoré** (10 AtlasLaunch + 3 usage). Le test de refus AppOps et l’absence de permission notification sont exécutés. Le scénario réel conditionnel journalise `BLOCKED: GET_USAGE_STATS is not allowed` et retourne avant de lancer les quatre activités; le succès Gradle ne vaut donc pas preuve de la recette réelle.
- `./gradlew :app:lintDebug` — **FAIL, 4 erreurs / 25 avertissements** au dernier rapport. Erreurs : `MissingPermission` sur `queryEvents` et `NotificationPermission` sur le presenter D plus les deux notifications Focus du lot C. Les déclarations de manifeste demandées ci-dessous lèvent ces erreurs; elles ne sont pas masquées par suppression Lint. L’avertissement `ACTIVITY_RESUMED` API 29 a été gardé derrière un contrôle de version.
- Aucune mesure réelle de latence UsageStats n’est disponible : le manifeste interdit empêche l’accès. Les 10 s sont l’intervalle configuré, pas une mesure sur appareil. L’API ne promet pas l’interception instantanée.

### Tentative AppOps réservée au test AVD

Après installation temporaire de l’APK debug sur `emulator-5556`, la commande autorisée a été lancée :

```sh
adb -s emulator-5556 shell appops set dev.atlas.coach GET_USAGE_STATS allow
adb -s emulator-5556 shell appops get dev.atlas.coach GET_USAGE_STATS
```

La seconde commande a retourné `GET_USAGE_STATS: default`, car le manifeste installé ne déclare pas `PACKAGE_USAGE_STATS`. Une tentative avant installation a retourné `No UID for dev.atlas.coach`. Aucun réglage n’a été activé silencieusement dans l’application; le parcours utilisateur reste le lien vers `Settings.ACTION_USAGE_ACCESS_SETTINGS` après choix explicite.

## Verdicts par critère

| Critère | Statut | Preuve / limite |
|---|---|---|
| Sources simulée et UsageStats sans accessibilité | PASS | API compilée; le provider réel reste fermé par accès AppOps |
| Curseur, filtre de types, bornes et déduplication | PASS | Tests JVM; aucun contenu d’écran/notification/URL |
| Consentement, allowlist, denylist, perte de permission et révocation/purge | PASS | Tests JVM; refus AppOps réel instrumenté |
| Seuil, fenêtre, cooldown, heures calmes, exceptions et pause | PASS | Tests JVM RuleEvaluator et UsageObserver |
| Friction notification et écran d’intention volontaire | BLOCKED | Implémentation compilée; notification bloquée par permission absente et Activity/Receiver non déclarés |
| Provider Accessibility/Mindful Reef | PASS comme non-implémentation | `UnsupportedProvider`, capability false; aucun code Reef interdit repris |
| Fixture Android indépendante | PASS | Build APK séparé validé; aucun module ajouté au settings hôte |
| Tests JVM du lot D | PASS | 24/24 ciblés; 76/76 suite complète au dernier passage |
| UsageStats réel : 4 ouvertures, seuil, notification, hors allowlist, révocation et latence | BLOCKED | Le test instrumentation a journalisé le refus AppOps réel puis n’a pas lancé la fixture; `appops set` reste `default` sans permission manifeste |
| Lint application | FAIL | 4 erreurs de permission, dont 2 déjà présentes dans les notifications Focus du lot C |

## Intégration requise — propositions non appliquées

### Manifeste

Le manifeste actuel a déjà une requête `<queries>` `MAIN` + `LAUNCHER`, nécessaire à la sélection d’apps visibles. Elle suffit; ne pas ajouter `QUERY_ALL_PACKAGES`. Ajouter seulement les permissions justifiées et les composants privés ci-dessous :

```diff
 <manifest xmlns:android="http://schemas.android.com/apk/res/android"
+    xmlns:tools="http://schemas.android.com/tools">
+    <uses-permission android:name="android.permission.PACKAGE_USAGE_STATS"
+        tools:ignore="ProtectedPermissions" />
+    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
     <application ...>
+        <activity android:name="dev.atlas.coach.usage.android.IntentionActivity"
+            android:exported="false" />
+        <receiver android:name="dev.atlas.coach.usage.android.EmergencyPauseReceiver"
+            android:exported="false" />
```

`PACKAGE_USAGE_STATS` est une permission spéciale d’accès accordée par la page Settings, pas par une boîte runtime. `POST_NOTIFICATIONS` sert aux propositions et aux notifications Focus déjà présentes; demander la runtime permission en expliquant la friction, puis tolérer le refus sans notification. Aucun service, overlay ou démarrage d’Activity en arrière-plan n’est requis.

### AtlasController et paramètres

- Conserver `CAP_APP_TRACKING` à `false` par défaut; ne plus laisser son switch générique activer une collecte. Créer le `UsageObserver` avec `AndroidUsageAccessStatus`, `AndroidUsageStatsEventSource`, `SharedPreferencesUsageStores` ou un adaptateur sur l’état ATLAS, `AndroidProtectedPackages.protectedPackages(context, consent.allowlistedPackageNames)`, `RuleEvaluator` et `AndroidFrictionPresenter`.
- Ajouter des actions dédiées : sélection multi-app + finalité affichée; ouvrir `usageAccessSettingsIntent()` seulement après confirmation; à `onResume`, relire AppOps; commencer au curseur courant si autorisé; toute désactivation appelle `revokeConsent()` avant de mettre le flag à faux. Poller toutes les 10 s uniquement pendant la visibilité ATLAS. La pause NotificationReceiver doit être fusionnée dans la politique par `AndroidEmergencyPauseStore.apply(...)`; l’action d’arrêt d’urgence existante doit aussi appeler `AndroidEmergencyPauseStore.clear(...)`.
- Remplacer dans `AtlasApp` la ligne `CapabilityRow("Consentement observation d'apps", ...)` par l’état de permission réel, le sélecteur, l’action « Accès à l’utilisation », l’interrupteur de révocation/purge et les commandes d’export/suppression. Garder le bouton de pause d’urgence visible et le choix du HOME d’origine accessible.
- Pour préserver le simulateur local après migration, évaluer les événements `dev.atlas.demo.distraction` avec `policy.copy(allowlistedPackageNames = setOf("dev.atlas.demo.distraction"))`; cela ne doit pas ouvrir la collecte réelle.

### Persistance (`data/**`, diff à intégrer par le parent)

Ajouter les deux clés à `LocalPolicy.toStorageMap()` et au parsing de `localPolicyFromStorage()` sans toucher aux événements/historiques déjà enregistrés :

```diff
 fun LocalPolicy.toStorageMap() = mapOf(
     ...
+    "allowlistedPackageNames" to allowlistedPackageNames.orEmpty().sorted(),
+    "excludedPackageNames" to excludedPackageNames.sorted(),
 )
 fun localPolicyFromStorage(values: Map<String, Any?>): LocalPolicy {
     ...
+    val allowlisted = values.optionalList("allowlistedPackageNames")?.map { it as? String
+        ?: throw IllegalArgumentException("Invalid allowlisted package") }?.toSet().orEmpty()
+    val excluded = values.optionalList("excludedPackageNames")?.map { it as? String
+        ?: throw IllegalArgumentException("Invalid excluded package") }?.toSet().orEmpty()
     return LocalPolicy(
         ... // conserver seuil, fenêtre, cooldown, pause, vitalPackageNames et heures calmes
+        allowlistedPackageNames = allowlisted,
+        excludedPackageNames = excluded,
     )
 }
```

Pour une ancienne map sans allowlist, `emptySet()` maintient l’observation désactivée jusqu’au consentement et conserve tous les autres champs. La récupération `AtlasStateRecovery` n’a pas besoin d’un nouveau sous-état : elle appelle déjà `localPolicyFromStorage()` dans la récupération isolée de `policy`; les listes mal formées feront tomber cette seule section sur son fallback.

### `settings.gradle.kts` (inclusion facultative seulement)

Le script autonome fonctionne déjà sans inclusion. Si l’orchestrateur souhaite lancer le fixture depuis le build hôte, voici le diff non appliqué :

```diff
 include(":app")
+include(":distraction-fixture")
+project(":distraction-fixture").projectDir = file("fixtures/distraction-app/app")
```

## Limites et suite

Le branchement UI/Activity, les clés persistées `LocalPolicy`, les deux permissions, la déclaration des composants, le test réel de notification et la mesure de latence attendent l’intégration parentale. Une fois le manifeste raccordé, exécuter sur `emulator-5556` : permission refusée → zéro appel source; activer explicitement via Settings; démarrer/retourner à `dev.atlas.fixture.distraction` quatre fois depuis `adb shell am start`; vérifier une intervention et notification, Settings/hors allowlist ignoré, puis révoquer et vérifier purge/arrêt. Mesurer le temps entre la quatrième activité et le prochain polling visible; ce résultat ne doit pas être décrit comme un blocage instantané.
