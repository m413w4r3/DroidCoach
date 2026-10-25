# Matrice Android 14 / Android 17 — V6.2.3, QA-R4

Révision testée : `7c12ad17f8340d775924a0b986dd44ac35b79722` plus le correctif de harnais instrumenté décrit dans [API37_DIAGNOSTIC.md](API37_DIAGNOSTIC.md). Statuts autorisés : `PASS`, `FAIL`, `BLOCKED`, `NOT_RUN`. « API 37 réel » désigne le Pixel physique `<serial-physique-omis>`; les résultats AVD restent séparés.

| Scénario | Android 14 — API 34 AVD | Android 17 — API 37 AVD | Android 17 — appareil réel |
|---|---|---|---|
| Installation et lancement | NOT_RUN | NOT_RUN | PASS |
| Rôle HOME | NOT_RUN | NOT_RUN | NOT_RUN |
| Retour au launcher précédent | NOT_RUN | NOT_RUN | NOT_RUN |
| Focus | NOT_RUN | NOT_RUN | PASS |
| Objectifs | NOT_RUN | NOT_RUN | PASS |
| Notifications | NOT_RUN | NOT_RUN | NOT_RUN |
| Localisation ponctuelle | NOT_RUN | NOT_RUN | NOT_RUN |
| Mobilité visible | NOT_RUN | NOT_RUN | NOT_RUN |
| Permissions runtime | NOT_RUN | NOT_RUN | NOT_RUN |
| Révocation | NOT_RUN | NOT_RUN | NOT_RUN |
| Rotation | NOT_RUN | NOT_RUN | NOT_RUN |
| Police 2× | NOT_RUN | NOT_RUN | PASS |
| Hors ligne | NOT_RUN | NOT_RUN | PASS |

La matrice détaillée avec preuves par ligne est dans [scenario-matrix-api34-r4.tsv](preuves-api37/scenario-matrix-api34-r4.tsv) et [scenario-matrix-api37-real-r4.tsv](preuves-api37/scenario-matrix-api37-real-r4.tsv). Le résultat par classe et par tentative est dans [api37-r4-class-results.tsv](preuves-api37/api37-r4-class-results.tsv).

## Android 14 — API 34 AVD

Les APK ont été compilés sur la révision intégrée. La première tentative s'est arrêtée avant le boot : ADB isolé 5038 a rendu `ADB server didn't ACK` et `LIBUSB_ERROR_BUSY`. La reprise avec `ADB_LIBUSB=0` a utilisé `/tmp/v623-emulator.lock`, un seul AVD jetable sur 5580/5581 et `-memory 2048`. Le garde a arrêté le boot lorsque `MemAvailable` est tombé à `4 890 744 KiB`, sous le seuil de `5 242 880 KiB`. La décision est **`NOT_RUN / HOST_ENVIRONMENT`** : zéro installation, zéro `am instrument`, zéro XML et zéro test. Android 14 n'est pas qualifié vert. Les 29 classes listées dans [api34-r4-class-results.tsv](preuves-api37/api34-r4-class-results.tsv) sont un inventaire statique, pas des exécutions.

## Android 17 — API 37 AVD

Aucun AVD API 37 n'a été démarré en QA-R4. Les résultats AVD historiques R3 restent distincts; les 13 scénarios de cette colonne sont `NOT_RUN`.

## Android 17 — Pixel physique

Après la déconnexion ADB observée au premier pré-vol, l'appareil est revenu dans `adb devices -l`. Le pré-vol repris a relevé batterie 82 %, écran allumé/déverrouillé, veille USB déjà active (`stay_on_while_plugged_in=7`), Android 17/API 37, HOME `com.google.android.apps.nexuslauncher`, ATLAS `0.1.0` et débogable. Le SHA-256 du certificat de l'APK installé, de l'APK intégré et de l'APK androidTest était `da3014662eb65e50a87ef364a2e583c331398795b222e3b37b1a16553a00eeee`. Le test package n'était pas présent avant l'installation.

La sauvegarde app a été créée avant toute écriture sur le téléphone, hors dépôt : 179 712 octets, 20 entrées tar, SHA-256 `06c1e2d64e8d394d1adc1178bb184cbef82b51e89a8618b17ba7e49bec62f7d5`. Après restauration, les 8 fichiers réguliers de l'archive avaient des contenus identiques (empreinte canonique `17a531c43d948929f39eaf54745e465903262c8ae5a64494918b76dcc715b5e5`). L'app a ensuite été lancée avec succès (`Status: ok`, `MainActivity` au premier plan); son démarrage a modifié `files/profileInstalled` et les deux sidecars `androidx.work.workdb-shm` / `androidx.work.workdb-wal`. La tentative de réappliquer l'archive après cet effet a échoué car le téléphone venait d'être débranché. La restauration finale bit à bit est donc **`NOT_RUN / DEVICE_ENVIRONMENT`**; les données applicatives originales avaient été restaurées et vérifiées avant le lancement, et les autres fichiers de l'archive n'avaient pas changé.

Exécutions AndroidJUnitRunner réelles :

- `AtlasLaunchTest` initial : 12 tests, 11 réussis, 1 échec à `focusRoutineIsCapabilityGatedAndRequiresExplicitStart` (`No compose hierarchies found` dans `openMore()`); classé `TEST_HARNESS_BUG` car la permission de notification n'était pas isolée dans un test qui active les routines.
- La même méthode a réussi sur l'APK baseline `aa9b2da` (1/1), puis sur l'APK intégré lors d'une reprise isolée (1/1). Après ajout d'un octroi explicite de `POST_NOTIFICATIONS` au seul test de capacité, la classe intégrée complète a réussi (12/12, zéro échec/skip).
- `LauncherPresentationTest` : 2/2 réussis; la variante à `LocalDensity.fontScale=2.0` confirme l'accessibilité des actions de sortie du launcher à police 2×.

Le total est de 29 cas instrumentés sur les différentes tentatives de diagnostic (27 réussis, 2 échecs observés, 0 ignoré); la qualification après correction du harnais est de 14/14 réussis. Seules ces deux classes ont été exécutées. Les classes Goals-Focus, notifications réelles, permissions runtime, localisation, mobilité, rotation et les autres classes sont `NOT_RUN` lorsque non exécutées. Les injections GPS hôte ne sont pas réalisées sur un téléphone réel. Aucun changement de rôle HOME, rotation système, police système ou `stay_on_while_plugged_in` n'a été effectué. Le package de test a disparu de l'inventaire après la commande de retrait; ATLAS est resté installé. L'appareil a été déconnecté avant les vérifications finales de données et de session.

Les sorties runner/XML et la capture de l'échec sont restées sous `/tmp/v623-qa/qa-r4-20261021T160125Z/phone/instrumentation/`; seuls les comptes et empreintes figurent dans le dépôt. Le rapport détaillé et les commandes sont dans [API37_DIAGNOSTIC.md](API37_DIAGNOSTIC.md).
