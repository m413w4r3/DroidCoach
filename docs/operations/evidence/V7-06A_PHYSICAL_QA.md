# V7-06A — Qualification physique de substitution (QA)

Date : 2026-11-01
Déclencheur : le propriétaire a branché son téléphone physique et autorisé son
utilisation pour déboguer ce qui peut l’être, puis mettre à jour les résultats.
Code de base : `be31d097fe0cf6a5bc50688b421bc9eecbbc847e`.
Commit QA (correctif de présentation + harnais) : `21cd84467e8b4a19fa393ba8e08ba102665a8ca4`.
Intégration campagne (correctif + test + preuves, sans la configuration de build
QA) : `fbfa403ac0c28ad32a9c5995b31ebd36edc7924f`. Le harnais physique reste sur la
branche `v7-06a/physical-qa` (`b918755`) pour une reprise.

## Résultat

**PASS physique strict du parcours V4 consentements / DayPlan / reçu / révocation.**
Test instrumenté : `OK (1 test)`, 3,08 s, 14 étapes PASS. Ce résultat est une
preuve **physique**, mais sur un paquet QA séparé, pas sur l’application installée
du propriétaire.

| Élément | Valeur |
|---|---|
| Appareil | Pixel 9 Pro XL, Android 17, API 37.1 (`sdk_full=37.1`) |
| Série | non publiée (conformément au skill `android17-device-qa`) |
| Paquet QA | `dev.atlas.coach.qa4`, version `0.1.0-qa`, label `ATLAS QA` |
| Paquet test | `dev.atlas.coach.qa4.test` |
| Compile/target SDK | 37 / 36 |
| Certificat debug | `da3014662eb65e50a87ef364a2e583c331398795b222e3b37b1a16553a00eeee` |
| APK app SHA-256 | `749be061fe43c77945c31bd2b05d13b366e7efcb0d7d827d3a7c5f1a9d7a5d0f` |
| APK test SHA-256 | `81248a757dcab0ba05cf1e8c6b3d2886eb89fd5647ecf7982cd364483e46f2bb` |

## Environnement isolé

- Core `127.0.0.1:8110` avec base temporaire et jeton synthétique ; relais épinglé
  `atlas-paseo-relay:r3a` sur `127.0.0.1:4412` ; connecteur neuf sur
  `127.0.0.1:4411`, état et identité neufs.
- Téléphone relié au relais par `adb reverse tcp:4412 tcp:4412`, donc
  `ws://127.0.0.1:4412` côté app QA ; aucun port LAN ni service live.
- Appairage synthétique neuf, fichier `0600`, poussé uniquement dans le paquet QA,
  lu puis supprimé par le test. Aucun appairage, token, base ou donnée du Pixel
  antérieur n’a été réutilisé.
- Aucun service live, aucun ADB sur une autre app que les paquets QA, aucun test
  sur l’application `dev.atlas.coach` du propriétaire.

## Preuve du test

Commande (arguments non secrets) :

```text
adb -s <série> shell am instrument -w -r \
  -e class dev.atlas.coach.e2e.V7_06A_VmConsentSyncInstrumentedTest \
  -e relay_url ws://127.0.0.1:4412 \
  -e server_id atlas-pc-v7-06a-vm \
  -e server_public_key <clé publique isolée> \
  dev.atlas.coach.qa4.test/androidx.test.runner.AndroidJUnitRunner
```

Sortie : `OK (1 test)`, 3,08 s. Étapes observées :

```text
step=01 PASS pairing_read/configuration
step=02 PASS relay_connected
step=03 PASS consent_day_plan_granted
step=04 PASS consent_activity_status_granted
step=05 OBSERVED presentation=SYNCED pending=0 ; PASS consent_acks=2 applied=true
step=06 PASS approved_day_plan_receipt
step=07 PASS plan_receipt_version=2 freshness=FRESH
step=08 PASS extra_pass duplicate_dispatches=0
step=09 PASS disconnected / revoke_pending
step=10 PASS offline_ack=false
step=11 PASS relay_reconnected
step=12 PASS revocation_reconciled
step=13 PASS final_day_plan=REVOKED:3/3
step=14 PASS extra_pass duplicate_dispatches=0
vm1_result=PASS steps=14 plan_version=2 day_grant=2 day_revoke=3 status_grant=2 trace_events=75 token_logged=false
```

Oracle Core en lecture seule (`verify_vm1_core.py`) : **PASS**. `DAY_PLAN`
`GRANTED v2` puis `REVOKED v3`, `ACTIVITY_STATUS` `GRANTED v2`, provenance
`ANDROID_SYNC`, exactement 3 événements, une capture DayPlan v2 avec
`consent_version=2`, aucun groupe d’idempotence dupliqué, aucune quarantaine.

Trace expurgée : `V7-06A_PHYSICAL_QA_TRACE.md` (76 événements Android, 40
événements connecteur ; payloads, tokens, UUID et série absents).

## Défaut découvert et corrigé

Les premiers runs physiques (`dev.atlas.coach.qa`, puis `.qa3`) ont échoué sur
l’état de présentation attendu `SYNCED` : observé `RUNNING` puis
`NO_PENDING_SOURCE`. Les ACK consentements et le reçu DayPlan étaient pourtant
`APPLIED`. Cause : une passe de suivi sans travail (`finishPass` → travail
immédiat) enregistrait `RUNNING` au démarrage, puis `NO_PENDING_SOURCE` à la fin,
écrasant le `SYNCED` déjà obtenu.

Correctif `21cd8446` : `CoreV4SyncWorker` n’enregistre `RUNNING` que si l’état
précédent n’est pas `SYNCED` ; une passe de suivi sans travail ne dégrade donc plus
un succès. Test JVM ajouté : `CoreV4SyncWorkerPresentationTest`
(`testQaUnitTest` PASS). Le run strict `.qa4` avec ce correctif est **PASS**,
`presentation=SYNCED pending=0`.

Le run `.qa2`, sans correctif et avec l’assertion de présentation transformée en
observation, avait déjà validé les 14 étapes et observé `SYNCED` ; il n’est pas
utilisé comme preuve stricte.

## État du téléphone et du propriétaire

- L’application `dev.atlas.coach` du propriétaire n’a **pas** été installée,
  remplacée, effacée ni lue : `lastUpdateTime` reste `2026-10-31 00:18:13`.
- Le rôle HOME reste `dev.atlas.coach/.app.LauncherActivity` avant et après.
- Les paquets QA `dev.atlas.coach.qa4` / `.qa4.test` sont séparés (applicationId,
  données, Keystore) ; les paquets de diagnostic `.qa`, `.qa2`, `.qa3` et leurs
  tests restent installés et attendent une autorisation distincte pour être
  désinstallés.
- Aucune donnée personnelle, aucun token, aucune base et aucun réglage du
  propriétaire n’a été lu ou modifié. Aucun test VM-2, chat ou révision de plan.

## Limites

- Preuve sur paquet QA, pas sur l’application installée du propriétaire : elle
  établit le comportement du code V4 sur matériel physique, mais **`GO_PIXEL`
  n’est pas accordé** pour le produit/parcours installé.
- Le correctif de présentation est validé par le run physique strict et un test
  JVM unitaire ; il doit encore être intégré et relu indépendamment.
- Aucun VM-2, chat réel, révision de plan ou preuve de service distant.
