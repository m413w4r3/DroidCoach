# ATLAS V6.2 — rapport d’investigation Android 17

Investigation ciblée terminée le 20 octobre 2026. **Android 17 n’est pas validé pour ATLAS V6.2.** Trois configurations d’AVD Google Play 16 Ko ont été examinées. Deux atteignent le boot Android mais échouent à produire une capture PNG ; l’essai `lavapipe` reste hors ligne sous ADB. Aucun APK ATLAS n’a été installé sur ces AVD et aucun test instrumenté Android 17 n’a été exécuté.

## Résultat des configurations

Les trois AVD étaient vierges, basés sur `system-images;android-37.2;google_apis_playstore_ps16k;x86_64` révision 6 et le profil Pixel 8 Pro. Chaque configuration utilisait 4 CPU, 2 048 Mio, un écran de 1 344 × 2 992 à 480 dpi. Les essais GPU `auto`, `lavapipe` et `swangle` sont les trois variantes prévues ; le redémarrage `swangle` sur un port ADB valide ne constitue pas une quatrième variante.

| GPU | Observation | Conclusion |
| --- | --- | --- |
| `auto` | Boot terminé ; API 37, `sdk_full=37.2`, pages de 16 384 octets. La capture `screencap -p` renvoie 72 octets de texte d’assertion au lieu d’un PNG. | AVD non stable pour la recette ; assertion `!rcEnc->featureInfo()->hasReadColorBufferDma`. |
| `lavapipe` | QEMU annonce un boot terminé en 61 401 ms, mais ADB reste `offline` après deux attentes bornées de 45 s et 40 s. | Aucune commande HOME, capture ou installation n’est possible. |
| `swangle` | Le premier lancement utilisait le port console 5586 et son port ADB 5587, hors de la plage recommandée. Même AVD relancé sur 5578/5579 : boot terminé, ADB connecté, API 37, `sdk_full=37.2`, pages de 16 384 octets. `screencap -p` renvoie la même assertion de 72 octets. | `dumpsys window` a ensuite montré `NexusLauncherActivity`, mais la capture reste invalide. Le seuil boot + HOME + capture n’est pas atteint. |

L’assertion de capture apparaît alors qu’ATLAS n’est pas installé ; elle ne peut donc pas être provoquée par son code. Cela établit une limite de la chaîne de capture graphique de cet environnement d’émulateur, pas une validation générale du système Android 17. Aucun appareil Pixel physique n’a été utilisé.

## Vérification des versions SDK officielles

La liste distante de `sdkmanager` du 20 octobre 2026 propose l’émulateur **37.2.12** et l’image Google Play API 37.2 16 Ko **révision 6**, identiques aux versions installées. Aucun paquet image ou émulateur plus récent n’était disponible pour cette combinaison ; aucune mise à jour n’a été installée. La liste annonce aussi Platform Tools 37.0.1 et Command-line Tools 23.0, tandis que l’hôte dispose de Platform Tools 35.0.2 et Command-line Tools 16.0. La vérification distante a réussi avec l’avertissement que ces Command-line Tools ne comprennent que le format SDK XML jusqu’à 3 alors qu’un fichier XML version 4 a été rencontré.

Environnement relevé : JDK 21.0.12.1, Python 3.12.3, Gradle 9.6.0, émulateur 37.2.12.0 et ADB hôte `/usr/bin/adb` 34.0.4. Les preuves préexistantes — 256 tests JVM, 48 tests instrumentés API 34 et 10 reprises — n’ont pas été relancées et restent celles consignées dans [LIVRAISON.md](LIVRAISON.md).

## Tests ATLAS

Le seuil demandé — boot, HOME et capture valide — n’a été atteint par aucun AVD. Conformément à ce prérequis, l’APK final n’a pas été installé et les tests ciblés n’ont pas été lancés : `AtlasLaunchTest` (12 tests), les captures HOME 320/360 dp, `AgentContractsInstrumentationTest`, le test de démarrage/arrêt visible du service mobilité et le fallback localisation API 26–29. Aucun accès coarse réel n’a été tenté. Aucun Gateway, modèle ou identifiant n’a été utilisé. La liste est conservée dans [tests-not-run.txt](../../logs/android17-final/tests-not-run.txt).

## Périmètre et état laissé

Aucun fichier de production, test existant, Gradle ou manifeste n’a été modifié. Les écritures du dépôt de cette investigation sont limitées à ce rapport et `logs/android17-final/`. Le profil personnel `Pixel_9_Pro_XL_2` n’a été consulté que pour ses réglages matériels autorisés ; son userdata n’a pas été ouvert ou modifié. L’AVD Android 34 n’a reçu aucune commande de test ou d’arrêt de ma part. L’AVD API 37 Nexus 5 déjà présent n’a pas été démarré ni modifié ; les essais ont utilisé des AVD neufs isolés dans `/tmp`.

Après l’interruption du tour, aucun processus QEMU n’était actif au moment de la reprise. J’ai redémarré uniquement l’AVD dédié `atlas_v62_android17_gp_swangle` sur le port 5578 et l’ai laissé actif. Il ne contient pas le package `dev.atlas.coach` : aucun token ni permission runtime ATLAS n’y est présent. Les deux AVD temporaires intermédiaires ont été supprimés ; seul le profil dédié final reste sous `/tmp/atlas-v62-android17/avd/`. Voir [état final](../../logs/android17-final/final-device-state.txt) et [nettoyage AVD](../../logs/android17-final/avd-cleanup.txt).

## Traces

- [Versions et environnement](../../logs/android17-final/environment.txt), [liste sdkmanager](../../logs/android17-final/sdkmanager-list.txt)
- [Configuration auto](../../logs/android17-final/config1-avd-settings.txt), [boot et capture](../../logs/android17-final/config1-boot.txt), [sortie de capture](../../logs/android17-final/config1-screencap-output.txt)
- [Configuration lavapipe](../../logs/android17-final/config2-avd-settings.txt), [boot et ADB](../../logs/android17-final/config2-boot.txt)
- [Configuration swangle](../../logs/android17-final/config3-avd-settings.txt), [boot final](../../logs/android17-final/config3-boot.txt), [état Android](../../logs/android17-final/config3-framework-state.txt), [sortie de capture](../../logs/android17-final/config3-screencap-output.txt)

La compatibilité Android 17 demeure **non démontrée**. Il faudra une image/émulateur corrigé ou un environnement de capture fonctionnel avant d’exécuter la recette instrumentée ATLAS.
