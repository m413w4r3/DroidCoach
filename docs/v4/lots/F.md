# Lot F — backend, contrats et sync

Date : 2026-10-19. Branche : `feat/atlas-v4-android17`. Aucun secret ou donnée personnelle n'a été ajouté. Aucun fichier `habits/**` ou `content/**` n'a été modifié par ce lot.

## Objectif livré

- Contrat FastAPI de synchronisation versionné (`contract_version=1`), événements UUID idempotents, dates UTC et erreurs avec codes stables.
- Adaptateurs Kotlin `CoachGateway` et `SyncGateway`, mock déterministe par défaut, `RemoteBackendGateway` facultatif sans nouvelle dépendance.
- Outbox privée durable, reprise avec UUID conservé, ack serveur, backoff exponentiel borné avec jitter et révocation qui écarte les éléments non encore acquittés.
- Consentements explicitement activés par catégorie; toutes les catégories et les détails d'apps sont désactivés par défaut. Les réponses du coach envoyées à distance exigent le consentement `coach_interaction`.
- Contrats futurs de proposition et mock déterministe, sans LLM/OpenClaw, sans exécuteur Android/shell/ADB.

## Fichiers modifiés ou créés

- Backend : `backend/atlas_backend/main.py`, `backend/atlas_backend/store.py`, `backend/tests/test_api.py`, `backend/README.md`.
- Contrats : `contracts/directive.schema.json`, nouveaux `contracts/sync-v1.schema.json` et `contracts/error-v1.schema.json`.
- Android : nouveau package `android/app/src/main/java/dev/atlas/coach/sync/**`, tests JVM `android/app/src/test/java/dev/atlas/coach/sync/**`, test instrumenté `android/app/src/androidTest/java/dev/atlas/coach/sync/RemoteBackendE2eInstrumentationTest.kt`.
- Script : `scripts/v4-backend-e2e.sh`.
- Docs : ce rapport, `docs/v4/05-suite-openclaw-future.md`, `docs/DECISIONS_AUTOMATIQUES.md`.

## Critères

| Critère | Statut | Preuve / limite |
|---|---|---|
| Contrats backend, UTC, UUID et idempotence | **PASS** | `17 passed` Pytest; replay identique, collision UUID 409, offset normalisé UTC, auth Bearer refusée, version inconnue 422, ack v1 testé. |
| Adaptateurs Android et outbox durable | **PASS** | 8 tests `sync` couvrent directive/réponse mock distante, ack, 401, 5xx puis reprise, hors ligne puis reprise après recréation, consentement/révocation, UUID et idempotence de la file. |
| Mocks et contrats futurs d'agent | **PASS** | 3 tests JVM; action inconnue et mandat révoqué refusés, journal conservé, mode simulation. Aucun exécuteur n'existe. |
| Consentement par catégorie et minimisation | **PASS** | Sans consentement, aucune requête; événements `LOCAL_ONLY` exclus; détails d'apps exigent un flag séparé en plus de la catégorie. |
| Token Android configuré/chiffré | **PASS** | `AndroidTokenStoreInstrumentationTest` exécuté sur l'AVD `emulator-5556` : 1 test; round-trip Keystore AES-GCM, vérification stockage non clair, révocation. |
| Compilation instrumentée | **PASS** | `:app:compileDebugAndroidTestKotlin --offline --rerun-tasks` a réussi; le scénario E2E compilé couvre online, offline, resume. |
| Preuve réelle AVD → FastAPI → réponse → AVD, puis reprise | **BLOCKED** | `scripts/v4-backend-e2e.sh` a assemblé APK et test instrumenté puis s'est arrêté avec code 77 : l'APK n'a pas `android.permission.INTERNET`. Le manifeste est hors périmètre. Aucune requête Android réelle n'a donc été prétendue. Le fichier de configuration cleartext debug manque aussi. |
| Branchement LocalMock par défaut, écran de réglages et lancement différé | **BLOCKED / intégration requise** | Les gateways sont prêtes et le mock est testé; `AtlasController`, `AtlasApp`, manifeste et Gradle sont interdits à F. Le mode n'est donc pas encore raccordé au parcours UI ni à un ordonnanceur réseau Android. |
| Permission réseau local Android 17 | **PASS (analyse)** | [La doc officielle](https://developer.android.com/privacy-and-security/local-network-permission) dit que `ACCESS_LOCAL_NETWORK` est exigée à partir de `targetSdk=37`; le projet reste à 35. Pour le target actuel, déclarer `INTERNET` suffit. La permission runtime n'a pas été testée sur l'AVD car l'E2E est bloqué avant réseau. |
| Suite Android complète | **PASS** | Relance forcée `:app:testDebugUnitTest --offline --rerun-tasks` : 101 tests, 0 échec. Une exécution antérieure pendant une modification simultanée de `habits/**` avait échoué sur trois assertions E; la relance après stabilisation est entièrement verte, sans modification de ces fichiers par F. |

## Commandes et sorties

- `/tmp/v4/venv/bin/python -m pytest -q` depuis `backend/` : **17 passed**, un avertissement Starlette/AnyIO préexistant.
- `./gradlew :app:testDebugUnitTest --offline --rerun-tasks` : **101 tests, 0 échec**, dont 8 tests sync et 3 tests agent.
- `./gradlew :app:compileDebugAndroidTestKotlin --offline --rerun-tasks` : **BUILD SUCCESSFUL**.
- Installation debug + androidTest et `adb -s emulator-5556 shell am instrument -w -e class dev.atlas.coach.sync.AndroidTokenStoreInstrumentationTest dev.atlas.coach.test/androidx.test.runner.AndroidJUnitRunner` : **OK (1 test)** sur l'AVD Android 17 / API 37.2.
- `scripts/v4-backend-e2e.sh` : APK debug et APK androidTest construites; sortie `BLOCKED: debug APK does not declare INTERNET`; code 77. Aucun serveur n'a été lancé et l'AVD n'a pas été modifié.
- JSON parse des 3 fichiers `contracts/*.json` : réussi.
- Toolchain constatée : AGP 9.4.1, Gradle 9.6.0, JDK 21.0.12, compileSdk 37.2, targetSdk 35, AVD existant `emulator-5556` / Android 17 API 37.2.

## Sécurité, dates et limites

Le serveur écoute sur `127.0.0.1` par défaut et refuse les appels sans Bearer configuré. Le script E2E réserve `0.0.0.0` au test local de l'AVD, contacte l'hôte par `10.0.2.2`, génère un token et une base éphémères, coupe uniquement son propre serveur, puis valide deux événements UUID distincts. Le token n'est pas imprimé ni conservé dans le dépôt.

Le token côté Android est lu par `BearerTokenProvider`; l'adaptateur Android fournit un stockage SharedPreferences app-private configurable, chiffré AES-GCM avec une clé Android Keystore. Le test instrumenté vérifie que le token relu fonctionne et que la valeur enregistrée n'est pas le token brut. Le futur écran doit le masquer, le supprimer sur révocation et ne jamais l'afficher dans les messages ou logs. L'outbox emploie un fichier privé avec écriture atomique et sa propre version de schéma. La synchronisation reste appelée par `syncNow()`; aucune tâche Android persistante de type WorkManager/ConnectivityManager n'est câblée dans ce lot.

La preuve complète dépend des diffs d'intégration ci-dessous. L'implémentation RemoteBackend utilise `HttpURLConnection`, donc aucune dépendance OkHttp n'est requise. Le mode LocalMock demeure la voie prévue pour l'expérience hors ligne.

## Intégration requise (diffs proposés uniquement)

Ces modifications ne sont pas appliquées parce que les fichiers concernés sont interdits au lot F.

### Manifest principal : autoriser les connexions réseau

Dans `android/app/src/main/AndroidManifest.xml`, sous la racine `<manifest>` :

```diff
 <manifest ...>
+    <uses-permission android:name="android.permission.INTERNET" />
     <application ...>
```

`INTERNET` est une permission normale. Ne pas ajouter `ACCESS_LOCAL_NETWORK` tant que `targetSdk=35`. Si le target est ultérieurement porté à 37, ajouter `ACCESS_LOCAL_NETWORK` et un parcours de demande runtime/refus/révocation avant la connexion LAN.

### HTTP cleartext : debug uniquement, limité à l'émulateur

Créer `android/app/src/debug/AndroidManifest.xml` :

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:networkSecurityConfig="@xml/network_security_config" />
</manifest>
```

Créer `android/app/src/debug/res/xml/network_security_config.xml` :

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="false" />
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">10.0.2.2</domain>
    </domain-config>
</network-security-config>
```

La variante release garde le cleartext interdit. Le serveur de démonstration n'est pas destiné à un déploiement public.

### Gradle

Aucun diff `build.gradle.kts` : `HttpURLConnection`, `java.time` (minSdk 26) et `org.json` disponible sur Android suffisent. Pas d'OkHttp/WorkManager ajouté.

### `AtlasController`

Dans `android/app/src/main/java/dev/atlas/coach/app/AtlasController.kt`, ajouter une configuration distincte au state local, chargée avec `LocalMock` et `SyncConsent.DISABLED`, puis construire l'adaptateur distant uniquement si l'utilisateur choisit explicitement Backend :

```kotlin
private val syncOutbox = AndroidSyncStorage.outbox(context)
private val tokenStore = SharedPreferencesBearerTokenStore(context)
private var syncConsent by mutableStateOf(loadSyncConsent() ?: SyncConsent.DISABLED)
private var coachGateway: CoachGateway = LocalMockCoachGateway()
private var syncGateway: SyncGateway = LocalMockSyncGateway()

fun configureRemoteBackend(baseUrl: String, enabled: Boolean) {
    if (!enabled) {
        coachGateway = LocalMockCoachGateway()
        syncGateway = LocalMockSyncGateway()
        return
    }
    val remote = RemoteBackendGateway(baseUrl, tokenStore, syncOutbox, { syncConsent })
    coachGateway = remote
    syncGateway = remote
}
```

Les appels réseau doivent partir d'un exécuteur de fond; convertir les événements locaux vers `SyncEvent` seulement après vérification `SyncConsent.allows`. L'UI doit conserver le coach local comme fallback, et les événements doivent garder le même UUID jusqu'à l'ack. Un ordonnanceur connecté au réseau devra appeler `syncNow()` avec backoff; ce lot ne l'intègre pas.

### Réglages UI

Dans `SettingsScreen` de `AtlasApp.kt`, ajouter une carte « Partage backend (facultatif) » : interrupteur principal désactivé par défaut, URL et token masqué, puis interrupteurs indépendants pour `focus`, `habit`, `content`, `coach_interaction` et `app_usage_summary`. Ajouter un contrôle `app_usage_detail` séparé, explicite et désactivé par défaut. Toute révocation doit appeler `discardDisallowed` avant la prochaine requête et effacer le token si l'utilisateur le demande. Afficher l'état `LocalMock`, attente d'auth, reprise différée ou synchro réussie sans afficher le token.

## Décisions et travaux restants

- Choix réversibles consignés dans `docs/DECISIONS_AUTOMATIQUES.md` : LocalMock par défaut, consentements off, fichier privé, backoff 1 s–6 h, version de contrat 1, stratégie `ACCESS_LOCAL_NETWORK` au target 35.
- Appliquer les diffs manifest/debug uniquement pendant l'intégration centrale, puis exécuter `scripts/v4-backend-e2e.sh` sur l'AVD existant.
- Raccorder le mode et les consentements à `AtlasController` / settings, persister les consentements séparément, et ajouter une reprise réseau Android si le produit la demande.
- Rejouer la suite JVM complète après stabilisation du lot E.
- SHA du commit principal du lot F : `7f5a5b9` (`V4 F: implement consented backend sync`).
