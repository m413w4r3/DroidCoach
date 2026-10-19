# Défauts, limites et audit Android 17

État audité le 19 octobre 2026 avec la documentation Android officielle, l'APK V4 et l'AVD existant. L'application finale vise `targetSdk 36`; elle a été compilée avec `compileSdk 37.2` et exercée sur Android 17 API 37. Aucun téléphone physique ni tablette n'a été utilisé.

Sources officielles consultées : [changements pour les apps ciblant Android 17](https://developer.android.com/about/versions/17/behavior-changes-17), [changements pour toutes les apps sur Android 17](https://developer.android.com/about/versions/17/behavior-changes-all), [permission réseau local](https://developer.android.com/privacy-and-security/local-network-permission), [changements pour les apps ciblant Android 16](https://developer.android.com/about/versions/16/behavior-changes-16) et [changements Android 16 pour toutes les apps](https://developer.android.com/about/versions/16/behavior-changes-all). Les pages Android 17 étaient mises à jour le 16 octobre 2026; la page de permission LAN le 2 octobre 2026.

## Android 17 — changements pour `targetSdk 37+`

| Changement officiel | Évaluation ATLAS | État / suite |
|---|---|---|
| Limite mémoire des `RemoteViews` (bitmap + icônes) | ATLAS ne déclare ni widget ni `RemoteViews`. | Sans impact fonctionnel; aucun widget revendiqué. |
| Nouvelle `MessageQueue` sans verrou; introspection privée potentiellement cassée | CodeGraph ne trouve aucun accès réflexif à `MessageQueue` ni `System.load`; ATLAS n'inspecte pas les champs internes Android. | Pas d'adaptation identifiée. |
| Champs `static final` non modifiables par réflexion/JNI | Aucun accès réflexif de mutation n'a été trouvé dans les sources ATLAS. | Pas d'adaptation identifiée; dépendances transitives non auditées individuellement. |
| Accessibilité clavier physique / IME CJK | Compose utilise les champs de texte standard; aucun `InputConnection` personnalisé ou `AccessibilityService`. | Pas de code spécifique; le comportement repose sur les composants AndroidX standard. |
| ECH TLS | Les connexions utilisent `HttpURLConnection`; négociation ECH dépend aussi de la pile Android et du serveur. Aucun certificat personnalisé ni exigence ECH n'est configuré. | Aucun résultat ECH revendiqué pour l'endpoint E2E local. |
| Permission runtime `ACCESS_LOCAL_NETWORK` obligatoire pour accéder au LAN à target 37 | Le backend local de recette utilise `10.0.2.2`. À target 37, sans permission, son E2E échoue par timeout TCP; la documentation indique que target 36 ou inférieur garde l'accès implicite via `INTERNET`. | **Bloquant pour le flux LAN actuel à target 37.** Pas de demande large ajoutée pour une Sync facultative; cible finale 36. Migration future : déclaration, explication avant demande, demande runtime uniquement au besoin, tests refus/révocation. |
| Masquage des mots de passe sur clavier physique | Le token backend est déjà présenté avec `PasswordVisualTransformation`; aucun affichage en clair n'est nécessaire. | Comportement souhaité; pas de changement. |
| Protection différée des OTP SMS | ATLAS ne demande pas de permission SMS et ne lit ni SMS ni OTP. | Sans impact identifié. |
| Renforcement des lancements d'activités en arrière-plan / `IntentSender` | Les alarmes déclenchent des receivers et notifications; elles ne démarrent pas d'écran. L'écran d'intention ne s'ouvre qu'après un tap de notification. Navigation/HOME et lancement d'apps sont des actions UI explicites. | Aucun mode historique `MODE_BACKGROUND_ACTIVITY_START_ALLOWED` trouvé. Pas de lancement autonome visé. |
| Certificate Transparency activée par défaut à target 37 | Pas de configuration de certificats propres; backend debug local en HTTP et backend de production doit être HTTPS. | Pas d'endpoint public soumis à cette recette. |
| Dynamic Code Loading natif : fichier chargé avec `System.load()` doit être en lecture seule | L'APK contient `libandroidx.graphics.path.so` pour quatre ABI; pas de chargement de code depuis un fichier externe dans ATLAS. La bibliothèque est embarquée par AndroidX. | Install, lancement et suite instrumentée passent sur l'AVD 16 Ko; segments ELF alignés sur 16 Ko. |
| Changements Contacts Provider 2 | Pas de permission Contacts ni d'usage de `ContactsContract`. | Sans impact identifié. |
| `setContentCaptureEnabled(false)` ne désactive plus Content Capture | ATLAS n'appelle pas cette API et ne transmet aucun contenu d'écran à un agent ou serveur. Les fonctions IA restent contractuelles/mock. | Capture système locale dépend des services et réglages Android; aucune garantie de désactivation globale n'est annoncée. |
| Audio en arrière-plan plus strict | L'application ne lit aucun audio, ne demande pas de focus audio et ne possède pas de service audio. | Sans impact identifié. |
| Orientation / ratio non verrouillables sur `sw >= 600dp`; l'opt-out Android 16 disparaît à 37 | Aucun verrou d'orientation n'est configuré. Les écrans Compose sont scrollables et la navigation s'adapte à la grande police. | AVD utilisé : 448 dp de largeur logique; test tablette `sw >= 600dp` **non exécuté**. À tester avant target 37. |
| `BluetoothSocket` retourne `-1` après fermeture | ATLAS n'utilise ni Bluetooth ni RFCOMM. | Sans impact identifié. |
| Limite de 50 000 clés par magasin Android Keystore pour les apps ciblant 37 | ATLAS utilise un alias de clé AES-GCM pour protéger le token backend; pas de création de clé par événement ou utilisateur. | Le plafond est sans effet attendu; quota non mesuré sur l'AVD. Le cycle de vie rotation/suppression du token reste couvert par les tests du stockage. |

## Android 17 — changements qui s'appliquent à toutes les apps

| Changement officiel | Évaluation ATLAS | État / suite |
|---|---|---|
| Limites mémoire liées à la RAM de l'appareil | Aucune fuite ou pression mémoire n'a été signalée par les tests ordinaires. Aucun profilage sous limite forcée n'a été fait. | **Non mesuré**; contrôler `ApplicationExitInfo`/memory limiter avant déploiement large. |
| Protection OTP WebOTP pour les apps qui lisent les SMS | Aucun accès SMS. | Sans impact identifié. |
| Projet de dépréciation de `usesCleartextTraffic` | Base de sécurité réseau refuse le cleartext; exception de debug limitée à `10.0.2.2`; builds release exigent HTTPS. | Configuration inspectée dans l'APK et la ressource réseau. |
| Restriction de trafic loopback entre profils | Pas de profil professionnel / second utilisateur configuré; le backend de recette est l'alias hôte d'émulateur. | **Profil multi-utilisateur non testé.** Pas de promesse de sync cross-profile. |
| Visibilité IME non restaurée automatiquement après certaines rotations | L'application ne promet pas de garder un clavier ouvert après recréation. Champs d'édition reçoivent leur état Compose normal. | La rotation visuelle est vérifiée; conservation de visibilité IME non exigée. |
| Touchpad : événements relatifs pendant capture pointeur | ATLAS ne demande pas de pointer capture. | Sans impact identifié. |
| Audio en arrière-plan plus strict | Aucun usage audio. | Sans impact identifié. |
| Re-pairing Bluetooth autonome | Aucun usage Bluetooth. | Sans impact identifié. |
| URI implicites `ACTION_SEND` / capture d'image : retrait annoncé pour Android 18 | ATLAS ouvre des liens via `ACTION_VIEW`; aucun envoi de média ni capture d'image. | Pas un changement Android 17; garder les URI grants explicites si ces intents sont ajoutés. |

## Points Android évalués explicitement

- **Permission d'usage :** `PACKAGE_USAGE_STATS` est un accès spécial accordé par l'utilisateur dans les réglages Android, pas une permission runtime ordinaire. ATLAS lit seulement les événements d'usage best effort; il ne lit pas le texte ou les images de l'écran. Révocation testée dans le parcours d'observation. `appops set ... GET_USAGE_STATS allow` est réservé aux tests AVD et ne remplace pas le parcours Settings.
- **Notifications :** `POST_NOTIFICATIONS` est demandée uniquement pour les fonctions facultatives de notifications. Un refus laisse Focus/les habitudes utilisables, mais Android peut ne rien afficher. Les canaux peuvent aussi être coupés séparément dans les réglages système.
- **Alarmes exactes / inexactes :** Focus, routines et rappels utilisent `AlarmManager.set` (RTC inexact). Aucun `setExact*`, `SCHEDULE_EXACT_ALARM` ou `USE_EXACT_ALARM`; Android peut retarder l'événement. Le minuteur UI et l'état sauvegardé servent à recalculer la phase.
- **Activités en arrière-plan :** les receivers d'alarme postent une notification ou resynchronisent l'état; ils ne lancent pas d'Activity. Un lancement via notification, choix de HOME ou application externe suit une action explicite.
- **HOME / `RoleManager` :** `ROLE_HOME` est demandé uniquement après action dans l'application. Aucun remplacement silencieux. Après recette, le HOME résolu est le launcher système `com.google.android.apps.nexuslauncher/.NexusLauncherActivity`.
- **Orientation et tailles adaptatives :** target 36 active les règles Android 16 sur grands écrans; l'application n'impose pas le portrait. Rotation/recréation testées sur téléphone AVD. L'écran sw600dp reste hors preuve.
- **Cycle de vie, reboot, force-stop :** l'état applicatif est local et récupérable; les receivers de boot ne contournent pas le force-stop Android. Après force-stop, aucune exécution de tâche ne peut être garantie avant relancement. Une validation de reboot AVD sans wipe et la persistance de données synthétiques sont consignées dans la recette.
- **Batterie :** pas de polling UsageStats permanent en arrière-plan; la lecture réelle est déclenchée lorsque l'hôte est visible et le consentement actif. Sync/retries restent un travail applicatif best effort; pas de service permanent. Alarmes inexactes pour éviter d'exiger l'accès exact.
- **Confidentialité :** `allowBackup=false`; données locales privées; aucun identifiant, texte d'écran ou contenu de notification tiers envoyé. Les catégories backend sont vides par défaut; révocation et purge sont explicites. Aucun accès micro, contacts, SMS, caméra, localisation ou accessibilité.

## Changement de `targetSdk` et résultats

1. **35 → 36** (`f470e32`) : changement isolé; recette AVD complète PASS le 19-10, rapports sous `docs/v4/logs/recette-20261019T022417Z/` et run final après H3 sous `docs/v4/logs/recette-20261019T024528Z/`. Cela active notamment edge-to-edge sans opt-out et le predictive back; le contenu utilise les insets Compose et les tests de navigation/back passent.
2. **36 → 37** (`21ebc9a`) : JVM, lint, assemble, 30 tests instrumentés et 17 pytest PASS sous `docs/v4/logs/recette-20261019T022729Z/`. Mais l'E2E Android → backend LAN échoue par `SocketTimeoutException` vers `10.0.2.2`; aucune déclaration/demande `ACCESS_LOCAL_NETWORK` n'existe. Cette régression est un blocage démontré pour ce flux facultatif à target 37, pas une incompatibilité générale Android 17.
3. **Retour à 36** (`f981632`) : cible finale choisie. L'accès LAN à target 36 ne doit pas déclarer ni demander `ACCESS_LOCAL_NETWORK` selon la documentation officielle. La recette E2E finale est rejouée sur la cible retenue; aucun droit supplémentaire n'est introduit.

## Compatibilité mémoire page 16 Ko

L'AVD rapporte `getconf PAGE_SIZE = 16384`, Android API 37, ABI x86_64. L'APK embarque `libandroidx.graphics.path.so` en arm64-v8a, armeabi-v7a, x86 et x86_64. `readelf -lW` indique `p_align=0x4000` pour chaque segment `LOAD` inspecté et `zipalign -c -P 16 4` réussit sur l'APK. La suite instrumentée et l'app démarrent sur cet AVD sans crash natif observé. Ce contrôle ne remplace pas un essai sur chaque ABI physique.

## Statut des fonctions et des limites

| Classe | État concret |
|---|---|
| **Réalisé** | UI Compose, Focus, habitudes, bibliothèque manuelle, HOME optionnel, UsageStats consenti, notifications et alarmes inexactes, FastAPI/SQLite local, Sync facultative, export CSV. Voir architecture réalisée. |
| **Expérimental / optionnel** | UsageStats réel et HOME choisi par utilisateur; Sync backend locale et rôles Android sur l'AVD. Ils ne sont pas activés par défaut. |
| **Simulé** | Coach déterministe hors ligne, adaptateurs mock, tests de scénarios/friction synthétiques. Aucun LLM n'est derrière le mock. |
| **Bloqué par l'API / politique Android** | Target 37 exige `ACCESS_LOCAL_NETWORK` avant le backend LAN. UsageStats ne garantit pas une interception immédiate. Alarmes inexactes ne garantissent pas une heure précise. |
| **Non testé** | Téléphone réel, tablette sw600dp, profil professionnel, contraintes mémoire forcées, réseau Wi-Fi d'un appareil réel, matrice exhaustive d'apps vidéo installées ou absentes, disponibilité d'un lecteur tiers particulier. |
| **Reporté OpenClaw** | Seulement contrats d'extension; pas d'agent, LLM, outil, scheduler, shell ou exécution distante. |

Aucun défaut bloquant ouvert n'est connu pour la démonstration Android hors ligne target 36. La migration target 37 et les validations multi-appareils restent ouvertes.
