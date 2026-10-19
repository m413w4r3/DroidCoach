# Recette Android 17 — lot H3

Date : 19 octobre 2026 · AVD `Pixel_9_Pro_XL_2` (`emulator-5556`) · Android 17 API 37 / `sdk_full=37.2` · APK `targetSdk=36`.

Les statuts portent sur l'étendue indiquée : **PASS** signifie que la preuve citée a été recueillie; **FAIL** une attente vérifiée puis non satisfaite; **BLOCKED** qu'un environnement, un handler externe ou une garantie Android empêche la validation complète. Un test accéléré ou par adaptateur n'est pas présenté comme un essai de durée réelle.

## Résultat exécuté

La recette finale est lancée par `scripts/v4-recette.sh`. Les sorties brutes, 27 XML Android, le XML pytest et l'empreinte APK sont sous [le journal du run final](logs/recette-20261019T024528Z/). Le script relève versions, AVD et SDK, puis exécute `testDebugUnitTest` (108 JVM, 0 échec), `assembleDebug`, `lintDebug` (0 erreur, 37 avertissements), `connectedDebugAndroidTest` (30, 0 échec) et pytest (17, 0 échec, 1 avertissement Starlette/AnyIO). `testDebugUnitTest` a été exécuté; toutes les assertions sont réussies. Le run contient aussi le test d'affichage de la licence GPLv3 ajouté en H3. Le champ `Commit` du journal indique le HEAD de base `f981632`; les changements H3 non commités étaient présents dans le worktree et sont inclus dans ce livrable. L'empreinte SHA-256 de l'APK debug finale est `63d04aa1e6a14ca5ebbf753c1c62dede0b5723a7ead0742c0be28116c35f2884`. L'E2E Android ↔ FastAPI/SQLite est distinct et documenté sous [H2-20261019T023745Z](logs/H2-20261019T023745Z/).

| §6 | Statut | Preuve recueillie / portée restante |
|---|---|---|
| 1. Inventaire et suite | **PASS** | `adb devices -l`, versions JDK/Gradle/AGP/SDK/AVD dans `run.log`; le même script enchaîne JVM, assemblage, lint, tests connectés sur API 37 et pytest. XML copiés dans `xml/`. Le CI GitHub est défini mais aucun run CI n'a été déclenché. |
| 2. Installation, cycle de vie, persistance | **PASS** | La suite installe/démarre l'app, recrée `Activity`/contrôleur et teste Retour; le lot G a vérifié l'accueil en paysage. Le test H3 écrit une habitude cochée, une épingle Settings et un Focus actif puis recrée `AtlasController` et ouvre Settings. Le test direct `am instrument` a laissé l'APK installée; `manual-system-checks.txt` vérifie les trois états dans les préférences après force-stop + relance, puis après `adb reboot` sans option de wipe. `sys.boot_completed=1`, même AVD et page 16 Ko; logcat après reboot : 0 ligne ATLAS `FATAL EXCEPTION`/ANR. Pas de promesse qu'un receiver relance une app arrêtée de force. |
| 3. Pomodoro | **PASS (deux cycles accélérés)** | Test instrumenté H1 fait travail → pause → travail → fin, état `FINISHED`, `completed=2`, notification de phase présente; frontières accélérées par `FakeClock`. `POST_NOTIFICATIONS` autorisée par le test. Cycle long, réglage des cycles à la main, refus réel de permission et annulation manuelle ne sont pas couverts par cette preuve. Les alarmes de production sont inexactes. |
| 4. HOME | **PASS** | Lot G a activé `ROLE_HOME` après action explicite dans Android, épinglé/lancé Settings depuis le launcher ATLAS, puis restauré Pixel Launcher. La résolution HOME finale est `com.google.android.apps.nexuslauncher/.NexusLauncherActivity`; les captures de liste et d'accueil réels sont inspectées dans [le rapport G](lots/G.md). |
| 5. Routines et rappels | **PASS (règles locales), BLOCKED (heure réelle)** | Tests et écran couvrent création/activation, proposition non exécutée sans décision, désactivation et calcul de prochaine échéance. La livraison exacte d'une alarme après reboot/fuseau ne peut pas être promise avec `AlarmManager.set`; le parcours de livraison au mur reste à exécuter manuellement. |
| 6. Observation | **PASS (scénario instrumenté)** | H1 vérifie refus → zéro requête/enregistrement; accord Usage Access + allowlist et quatre ouvertures fixture → événements et friction; hors allowlist/dialer exclus; révocation → arrêt, purge et deux polls post-révocation. Latence observée dans ce scénario : **1,839 s**, pas une borne garantie. Détails et extraits dans [H1](lots/H1.md). Le geste `appops set dev.atlas.coach GET_USAGE_STATS allow` est un geste AVD de test documenté, pas la procédure utilisateur. |
| 7. Habitudes | **PASS (fonctionnel et persistance contrôleur)** | Tests couvrent création, planning quotidien/hebdomadaire, check-in/retrait, score et export CSV; test H3 recrée le contrôleur avec une habitude cochée conservée. L'alarme réelle de rappel, le reboot et la déduplication par livraison murale restent hors preuve. |
| 8. Contenus | **PASS (contrat/fakes), BLOCKED (handlers installés)** | Tests vérifient lien `ACTION_VIEW`, fallback navigateur, absence de handler, ajout/suppression et état local via adaptateurs contrôlés. Aucune application vidéo précise ni ReVanced n'est supposée installée; la matrice réelle d'applications externes reste manuelle. |
| 9. Backend | **PASS** | `scripts/v4-backend-e2e.sh` rejoué sur l'AVD target 36 : endpoint local authentifié, ack, rejeu idempotent, 401, aucune requête sans consentement, arrêt hors ligne puis reprise et SQLite `events=3`, `unique=3`. Les captures du run sont dans le journal H2 indiqué plus haut. |
| 10. UX / captures | **PASS (captures listées et inspectées)** | Accueil clair/sombre, fontScale 2.0, paysage et principaux écrans G ont été inspectés; H3 a aussi inspecté le nouvel écran sombre Sync. Le défaut « Plus sélectionné sur Accueil » provenait du banc de capture et est corrigé : sélection sémantique désormais reliée à la destination capturée. Voir [rapport H3](lots/H3.md). L'AVD mesure 448 dp de large; le cas tablette `sw >= 600dp` est non testé. |
| 11. Permissions / sécurité | **PASS (manifeste et mesures exécutées)** | Permission APK extraite avec `aapt2`; manifeste vérifié : `INTERNET`, `PACKAGE_USAGE_STATS`, `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED` et permission interne générée par AndroidX. Justifications et permissions absentes listées ci-dessous et dans [l'audit Android 17](04-defauts-et-limites.md). Pas de service Accessibilité, root, overlay, blocage d'appel ou écran d'urgence intercepté. |

## Permission APK

L'audit porte sur l'APK assemblée, pas seulement le manifeste source. La permission dynamique `dev.atlas.coach.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` est injectée par AndroidX et protège ses receivers non exportés.

| Permission | Raison et contrôle |
|---|---|
| `android.permission.INTERNET` | Sync backend facultative; LocalMock reste le défaut. Cleartext permis seulement en debug vers `10.0.2.2`; release exige HTTPS. |
| `android.permission.PACKAGE_USAGE_STATS` | Observation optionnelle, l'utilisateur doit l'accorder depuis les Réglages Android; allowlist, arrêt immédiat et purge à la révocation. Ne donne ni interception instantanée ni contenu d'écran. |
| `android.permission.POST_NOTIFICATIONS` | Notifications de phase/rappel/friction optionnelles; le refus ne doit pas empêcher l'usage local. |
| `android.permission.RECEIVE_BOOT_COMPLETED` | Reprogrammer les alarmes inexactes autorisées après reboot; ne contourne pas le force-stop. |
| `dev.atlas.coach.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | Permission interne générée pour isoler des receivers de bibliothèque. |

Absentes : `ACCESS_LOCAL_NETWORK` à target 36, `SCHEDULE_EXACT_ALARM`, `USE_EXACT_ALARM`, `QUERY_ALL_PACKAGES`, SMS, Contacts, Accessibility, overlay, localisation, caméra, micro et Bluetooth. L'accès LAN Android 17 reste implicite jusqu'à target 36 selon la documentation officielle; target 37 a été essayé séparément et le backend local a échoué sans la nouvelle permission.

## Feuille de parcours manuel / appareils réels

Cette feuille évite d'assimiler les fakes et le temps accéléré aux événements Android en temps réel. Cocher chaque étape seulement après nouvelle exécution et conserver la preuve dans `docs/v4/logs/`.

- [x] Force-stop puis relance explicite; le processus est absent après `am force-stop`, puis l'activité est démarrée par `am start`; les données locales reviennent.
- [x] `adb reboot` sur l'AVD existant, **sans wipe**; après boot, habitude, check-in, épingle et Focus sauvegardés; `logcat` sans crash/ANR ATLAS. Voir `logs/H3-20261019-final/manual-system-checks.txt`.
- [ ] Focus de longue durée, pause/reprise, annulation et modification du nombre de cycles dans l'UI; regarder `dumpsys notification` pendant une notification active.
- [ ] Retirer réellement POST_NOTIFICATIONS avec `pm revoke`/appops sur AVD; confirmer fonction locale sans notification; rétablir seulement via Settings pour les autres essais.
- [ ] Dans Settings, retirer puis réaccorder Usage Access; vérifier la feuille système, puis comparer au geste réservé aux tests `appops set dev.atlas.coach GET_USAGE_STATS allow`.
- [ ] Désactiver Observation et routine/rappel immédiatement; attendre une échéance réelle, puis vérifier qu'aucune nouvelle notification ou collecte ne survient. Les timings sont inexactes.
- [ ] Tester lien vidéo avec un handler installé, navigateur de secours, puis sans handler; ne pas installer/présumer ReVanced.
- [ ] Répéter sur téléphone/tablette `sw >= 600dp`, réseau Wi-Fi réel, économies batterie et Android 17 stable; l'AVD actuel n'établit pas ces résultats.

Avant de partager un rapport, vérifier les PNG une à une et retirer les captures avec contenu personnel, texte illisible ou thème cassé. Ne jamais inclure de token dans commandes, XML ou journaux.
