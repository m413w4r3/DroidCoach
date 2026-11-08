# V7-11 — Candidat Android et préservation AVD API 37

**Statut : GO local sur AVD API 37 pour l'APK reconstruit, le rôle HOME, la préservation et le rollback ; A0 pull/REJECT/expiration PASS ; A0 APPLY en retry ciblé ; Pixel physique et transport Relay réel NOT_RUN.**
**Base :** `8dac699cdb37e2f858a76e4cf7f9fc3bcd613e9c`.
**Preuve détaillée :** [V7-11_A3_ANDROID_CANDIDATE_EVIDENCE.md](evidence/V7-11_A3_ANDROID_CANDIDATE_EVIDENCE.md).

## Artefact candidat

| Champ | Valeur |
|---|---|
| APK debug SHA-256 (build A3, testé AVD, SHA source 8dac699) | `97ac580d2cdcc5384a1f1ab8e15f2f3b4d57ca468177dede55166be154c321d5` |
| APK debug SHA-256 (build A5 indépendant, SHA intégré 04fdc77) | `27baca26312fa6981b8e11fc846eb32b08f41c7a631df36a69df1c986239d13e` |
| AndroidTest APK SHA-256 (A3 / A5) | `8d9287db…676cc07` / `9db0a3ff…32e1bff` |
| applicationId | `dev.atlas.coach` |
| versionCode / versionName | `1` / `0.1.0` |
| compileSdk | `37.2` |
| minSdk / targetSdk | `26` / `36` |
| debuggable | `true` |
| allowBackup | `false` |
| Certificat SHA-256 | `da3014662eb65e50a87ef364a2e583c331398795b222e3b37b1a16553a00eeee` |
| Signature | V2 valide, DN `C=US, O=Android, CN=Android Debug` |

Le certificat correspond à l'empreinte de référence complète du dépôt. Le `versionCode=1` permet une mise à jour in-place sur le produit installé connu (`versionCode 1`, `versionName 0.1.0`). L'APK est debug, comme le produit actuellement installé ; une release avec une autre signature casserait la mise à jour in-place.

Les deux hashes diffèrent entre eux et de l'artefact V7-10 documenté (`2517f241…`) car les builds embarquent une provenance/source SHA différente (`8dac699` pour A3, `04fdc77` pour A5) et un horodatage de build. L'ancien APK `/tmp/v7-10-a5-final.apk` n'existe plus ; la comparaison binaire directe est **NOT_RUN**. Le certificat, le package, le versionCode, le manifeste et les permissions sont identiques ; l'arbre Android est inchangé entre ces SHA. L'artefact à installer en V7-11B devra être reconstruit au SHA final, puis re-testé sur AVD.

## Qualification AVD API 37

AVD `atlas_v62_disposable_api37` (image x86_64 API 37.2.12.0), serial `<AVD_SERIAL>` uniquement. Aucun Pixel physique.

| Test | Résultat |
|---|---|
| Installation APK de base (`739ad878`) | PASS — `firstInstallTime=2026-11-08 11:05:12` |
| Mise à jour candidat `adb install -r` | PASS — `firstInstallTime` inchangé |
| Préservation de 7 fixtures synthétiques | PASS — 6 hashes identiques ; l'état racine est normalisé au premier démarrage en conservant `capabilities.sync=true` |
| Cold start | PASS — `LaunchState: COLD`, aucune exception fatale |
| Agent Coach instrumentation | **9/9** |
| Scheduler Core V4 instrumentation | **1/1** (mise en file locale, pas d'envoi Relay) |
| JVM/lint/build indépendants A5 (04fdc77) | **PASS** — 934 tests, 0 échec/erreur/ignoré ; lint 0 erreur, 80 avertissements ; APK debug et androidTest assemblés |
| Image Core indépendante A5 (04fdc77) | **PASS** — `sha256:2c5ae216…f1cd57`, 150 498 024 octets, `v4_auth.py`/`v4_assertion.py` présents, aucun service lancé |
| Rollback in-place vers l'APK de base | PASS — `install -r` sans `-d`, données et `firstInstallTime` conservés |
| Rôle HOME (A3-F1) | **PASS** — manifeste HOME/DEFAULT, `cmd role add-role-holder`, intent HOME → `LauncherActivity` à froid, rôle et données conservés après réinstallation candidat et rollback base |
| A0 pull/inbox (A3-F1) | **PASS** — Core mock local 18100, app en Direct, proposition synthétique visible dans l'inbox ; pull rejoué identique |
| A0 REJECT + rejeu ACK (A3-F1) | **PASS** — `REJECTED / USER_REJECTED`, ACK livré, rejeu `x-atlas-replayed: true` |
| A0 expiration (A3-F1) | **PASS côté API Core/SQLite isolée** — pull à zéro, état `EXPIRED`, source `PULL` ; expiration UI Android **NOT_RUN** |
| A0 APPLY (A3-F1/F2) | **BLOCKED — produit** : avec plan/version/activité alignés, le Core refuse une proposition sur un plan d'origine `DEMO` (`503 DATA_UNAVAILABLE — un plan de démonstration n'est pas une projection Android`) avant calcul du diff ; aucun `proposal_id`, aucun ACK `APPLIED`, plan inchangé. Aucun APPLY réel n'est revendiqué sur AVD. |
| Transport Relay réel | **NOT_RUN** — aucun service live/pairing |

L'émulateur a signalé 4096 MB de RAM effective malgré `-memory 2048` ; c'est une limite d'environnement, pas un échec du test. L'émulateur a été arrêté après la passe.

**Anomalie HOME** : lors du premier remplacement de l'APK de base avec HOME actif, un `FATAL EXCEPTION` du framework Android (`ResourcesManager`/`ConfigurationController`, NPE de configuration) a été observé une fois. Les données sont restées intactes ; le rollback final et le lancement HOME suivant n'ont produit aucun nouveau FATAL. Le risque de remplacement d'APK avec HOME actif doit être testé sur le Pixel en V7-11B, avec sauvegarde préalable.

**Continuité de données** : le package n'était pas installé au pré-vol A3-F1 ; les fixtures ont été créées après la première installation. La conservation est donc démontrée entre la réinstallation candidat et le rollback, mais pas la continuité depuis une passe antérieure.

## Limites

- AVD ≠ Pixel physique. La préservation réelle du Keystore, du rôle HOME, des permissions et des données du Pixel reste à qualifier en V7-11B.
- L'instrumentation ne prouve pas un envoi Relay de bout en bout.
- Le parcours A0 APPLY est **BLOCKED par la sémantique produit** : le Core refuse une proposition sur un plan `DEMO`. Il reste à qualifier sur le Pixel avec un plan/projection Android réel, sous autorisation V7-11B. La preuve APPLY V7-08 est historique et ne qualifie pas automatiquement le code V7-10/V7-11.
- L'expiration UI Android et le transport Relay réel ne sont pas qualifiés.
- Le remplacement d'APK avec HOME actif a produit une anomalie framework transitoire sur l'AVD.
- Le backup app-only réel du Pixel et sa restauration ne sont pas couverts par l'AVD.

## Verdict

`GO_V7_11_ANDROID_APK_CANDIDATE` : **GO local** pour le code Android et deux builds indépendants (package, version, signature, permissions, manifeste identiques) ; l'artefact exact de V7-11B doit être reconstruit au SHA final et re-testé.
`GO_V7_11_ANDROID_PRESERVATION_AVD` : **GO local** pour la mise à jour in-place, le rôle HOME et le rollback, avec les limites de continuité et l'anomalie framework documentées.
`GO_V7_11_RELAY_SYNC_AVD` : **NOT_RUN**.
`GO_PRODUCT_INSTALL` : **NO-GO / NOT_RUN** — aucune installation Pixel en V7-11A.
