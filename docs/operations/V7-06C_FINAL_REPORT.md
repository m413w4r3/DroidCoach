# ATLAS V7-06C — Coach Android puis transport public : rapport final

**Statut du document : EN COURS — boucle Coach Android QA GO ; P2, suites finales,
revue indépendante et transport public restent à traiter.**
Les sections sont mises à jour au fur et à mesure des exécutions réelles sur le SHA
intégré. Une ligne `NOT_RUN`, `BLOCKED` ou `CORRECTION_REQUIRED` ne doit pas être lue
comme un succès.

**Campagne :** V7-06C « Coach Android puis transport public »
**BASE_SHA :** `3e0d1f4c9ca591f903a894be87183395d30fee0b`
**Branche :** `feat/atlas-v7-06c-android-coach-loop`
**Registre :** `docs/operations/REGISTRY_V7_06C.md`
**Date de lancement :** 2026-11-01

## 1. Résultat exécutif

Le harnais reproductible est **PASS** : les scénarios backend positif et négatif ont été
rejoués depuis le dépôt sur `b4db8da`, avec le modèle réel `deepseek/deepseek-chat`, une
proposition A0, un ACK `APPLIED` v3 et zéro proposition/ACK dans le négatif.

Sur le **Pixel 9 Pro XL physique, Android 17 / API 37**, le scénario **APPLY** a réellement
exécuté les 12 étapes du test QA en 19,387 s : DayPlan v2→v3 par le code Android, ACK
`APPLIED` émis par Android et confirmé en Core (`applied_plan_version=3`), rejeu du même
ACK sans second effet, consentements révoqués v3. Le produit, HOME, Keystore et données
personnelles sont restés intacts.

Le scénario **REJECT** a également exécuté les 12 étapes en 21,214 s : proposition
`REJECTED`, plan resté v2 sans aucune v3, ACK `REJECTED` durable et rejeu identique.
Un défaut de sélection d'attestation logcat du harnais (deux runs dans le buffer) a
été corrigé et l'oracle post-hoc en lecture seule a produit un proof PASS lié à
l'`ack_id` Core, sans ré-exécution. `GO_ANDROID_QA_REJECT` et `GO_QA_COACH_LOOP` sont
donc **GO** sur le paquet QA physique.

Le transport public reste **BLOCKED_NETWORK** : le Funnel pointe vers
`127.0.0.1:4400`, sans listener, et la sonde TLS échoue. Aucun changement Funnel/Tailscale
n'a été effectué.

## 2. Périmètre et provenance

- Le code et les preuves V7-06A/V7-06B déjà acceptés ne sont ni rejoués ni réécrits sans
  raison technique.
- Le test Android réutilisé est
  `V7_06B_CoachLoopInstrumentedTest` avec son runner
  `scripts/v7-06b/android/run_qa.py`, sous un nouveau package QA
  `dev.atlas.coach.qa.v706b.v706c…`.
- Le paquet produit `dev.atlas.coach`, HOME, Keystore et données personnelles ne sont
  pas touchés.
- Le modèle réel de campagne reste `deepseek/deepseek-chat` via un Gateway OpenClaw
  isolé ; aucun profil OAuth personnel n’est copié.
- La stack isole Core, SQLite, Relay épinglé, connecteur, Gateway, proxy et identités
  synthétiques ; aucun service live n’est modifié.

## 3. Harnais reproductible (V7-06C-01)

Statut : **PASS** — `GO_E2E_HARNESS_REPRODUCIBLE`.
Commit `64020d7543febf4322d8ba8002d8695322e43606`, intégré localement dans
`b4db8da5f3ae3aa1ea85d52a4bcaf996f4d3744a`. Le harnais V7-06B non commité sous
`/tmp/atlas-v7-06b-backend` a été audité ; ses éléments utiles sont portés sous
`scripts/v7-06c/` avec les correctifs connus : `expires_at` 30 s, arrêt Gateway par
signature, stderr des enfants en fichiers privés 0600, readiness réelle, identités
synthétiques et oracles indépendants.

Commandes de reproduction :

```sh
python3 scripts/v7-06c/backend/stack.py start --scenario backend-positive
python3 scripts/v7-06c/backend/stack.py stop --private-root "$PRIVATE_ROOT"
python3 scripts/v7-06c/backend/stack.py start --scenario backend-negative
python3 scripts/v7-06c/backend/stack.py stop --private-root "$PRIVATE_ROOT"
```

Résultats constatés :

| Scénario | Résultat | Oracles |
|---|---|---|
| Backend positif | **PASS** | 3 tours proxy ; DeepSeek réel ; 6 outils client ; `atlas_get_day_plan` + `atlas_propose_plan_revision` corrélés ; 1 proposition A0 `base_version=2` ; 1 ACK `APPLIED` v3 ; snapshots v2/v3 ; rejeu identique ; 1 cache chat |
| Backend négatif | **PASS** | stale version/projection/unknown refusés ; terminal `VALIDATION_FAILED` ; injection sans sentinelle ; consentement révoqué refusé ; **0 proposition / 0 ACK** |

Tests statiques du harnais : `12/12` unittest PASS, `py_compile`, `node --check`,
`git diff --check`. Nettoyage : chaque stack arrêtée par identité propriétaire
(processus, conteneur, racine) ; aucune ressource `atlas-v7-06c` résiduelle.

## 4. Android APPLY

Statut : **PASS** — `GO_ANDROID_QA_CHAT`, `GO_ANDROID_QA_PLAN_APPLY`, `GO_ANDROID_QA_ACK`
pour le scénario APPLY. Preuve : `v7-06c-apply-a49e3a3aa819.json` et
`docs/operations/evidence/V7-06C_ANDROID_APPLY.md`.

| Élément | Valeur |
|---|---|
| Appareil | Pixel 9 Pro XL, Android 17 / API 37, arm64-v8a ; série hashée `f2e9258b…` |
| SHA source | `b4db8da5f3ae3aa1ea85d52a4bcaf996f4d3744a` |
| Paquet QA | `dev.atlas.coach.qa.v706b.v706capply.a7f3c21e` |
| APK app / test | `8441a5dab058d3a2852a77d30ccef7a23cec8c2563ac1083212d46439856224d` / `c97427fb9f2a029a065e0924221f351458151860a962604c021f5189576337d8` |
| Signature | debug `da301466…eeee` |
| Runner | `OK (1 test)`, 19,387 s, 12 étapes PASS |
| Réseau | local `adb reverse` `ws://127.0.0.1:4413`; reverse ajouté puis restauré |
| Modèle | `deepseek/deepseek-chat` via Gateway isolé ; 3 tours proxy, 2 outils corrélés |
| Proposition | `71b80fea-…`, `APPLIED`, `base_version=2`, `A0_USER_REVIEW`, `NONE_AT_CORE`, reschedule 610, durée 45 |
| ACK | `3f67edb5-…`, `APPLIED`, `applied_plan_version=3`, 1 seul événement |
| Plan Core | v2 start=600, v3 start=610 ; `current_revision_id` v3 = `revision_id` ACK `3ea92f60-…` |
| Rejeu ACK | `ack_replay=identical`, 1 ACK local, aucun second effet |
| Consentements | `DAY_PLAN` et `ACTIVITY_STATUS` `REVOKED` v3, 4 événements |
| Produit/HOME | inchangés (`lastUpdateTime=2026-10-31 00:18:13`, rôle HOME identique) |

Oracle indépendant : SQLite Core en lecture seule, journal `operations.json` du
connecteur (1 ACK `completed_redacted`, 2 snapshots complétés, 1 chat complété,
0 incertain) et trace proxy (6 outils exacts, corrélation, `finish_reason=stop`,
0 outil interdit).

## 5. Android REJECT

Statut : **PASS** — `GO_ANDROID_QA_REJECT`. Preuve :
`v7-06c-reject-2fb57424d534.json` et
`docs/operations/evidence/V7-06C_ANDROID_REJECT.md`.

| Élément | Valeur |
|---|---|
| Appareil / exécution | Pixel 9 Pro XL API 37 ; exécution sur `b4db8da…` |
| Paquet QA | `dev.atlas.coach.qa.v706b.v706creject.b4d9e602` |
| APK app / test | `b599532c…9a6b` / `be283081…0e83` |
| Runner | `OK (1 test)`, 21,214 s, 12 étapes PASS |
| Proposition | `7512ea09-…`, `REJECTED`, `base_version=2`, `A0_USER_REVIEW`, `NONE_AT_CORE` |
| Plan | v2 conservé, start=600, durée 45 ; **aucune ligne v3** |
| ACK | `c22d1829-…`, `REJECTED`, `applied_plan_version=NULL`, 1 seul événement |
| Rejeu ACK | `ack_replay=identical`, 1 ACK local, aucun second effet |
| Consentements | `REVOKED` v3 pour les deux catégories |
| Journal / trace | 1 ACK `completed_redacted`, 1 snapshot, 1 chat, 0 incertain ; 3 tours proxy corrélés |

**Incident de harnais et correction.** Le premier wrapper a exécuté le test avec
succès mais a refusé de produire la preuve (`ANDROID_ACK_REPLAY_ATTESTATION_MISSING`) :
le buffer logcat contenait aussi l'attestation APPLY et le wrapper exigeait exactement
une ligne. Le correctif `f9017f4` (intégré `5ca0a92`) lie l'attestation à l'`ack_id` et
à l'état ACK du Core, et ajoute `verify-existing`. L'oracle post-hoc a été exécuté en
lecture seule sur la stack existante (`execution_mode=post_hoc_existing_stack`,
aucune nouvelle exécution) et a produit le proof PASS. Le test et le produit ne sont
pas en cause ; les 14 tests du harnais passent.

## 6. Transport public WSS

Statut : **BLOCKED_NETWORK — NOT_RUN**.

Constat de pré-vol (lecture seule) :

- DNS public `all.taildc7886.ts.net` résolu.
- Funnel actif `tcp://all.taildc7886.ts.net:8443` → `127.0.0.1:4400`.
- Aucun listener sur `127.0.0.1:4400`.
- Le relais live publie `4000/tcp -> 127.0.0.1:4402`.
- Sonde TLS `https://all.taildc7886.ts.net:8443/health` : erreur `SSL_ERROR_SYSCALL`.

Aucun changement Funnel/Tailscale, service live ou secret n’a été effectué. Une
autorisation ciblée et un endpoint vivant sont requis avant toute recette publique.

## 7. Sécurité et identités résiduelles

Le pré-vol V7-06C a relu l’audit V7-06A et recontrôlé l’état live en lecture seule
le 2026-11-01 :

- Core live `atlas-core-atlas-core-1` : un `device_ref` `android-9a3f0f…` conserve
  `DAY_PLAN` et `ACTIVITY_STATUS` `GRANTED` version 2 (`updated_at`
  `2026-10-31T00:23:51Z`), 2 événements de consentement, 0 snapshot DayPlan,
  0 proposition et 0 ACK.
- Registre connecteur : `pixel-v7` (14 capabilities, dont V4) et
  `r3c-pixel-final` (6 capabilities V1/V2) ne sont pas révoqués ; les autres
  entrées sont révoquées.
- Journal d’opérations : dernière écriture `2026-10-31 16:40:43`, aucun signe
  d’activité V7-06C.

**Aucune révocation, rotation de bearer ou modification live n’a été effectuée.**
Une révocation ciblée de ces deux appareils et/ou une rotation du bearer Core
restent soumises à une autorisation spécifique, avec sauvegarde, commandes
exactes, vérification des refus après révocation et rollback compatible.

Aucune identité personnelle n’est utilisée par la stack V7-06C. L’appareil
physique QA est sur Wi-Fi ; les interfaces `rmnet` sont `DOWN`. La recette
publique exigera un réseau extérieur réel (données mobiles ou autorisation
ciblée de changement réseau), sans `adb reverse`.

## 8. Suites et preuves

Exécutées sur le SHA intégré `b4db8da` (et complétées après les correctifs de harnais) :

| Suite | Résultat |
|---|---|
| Backend `pytest -q` | **371 passed / 1 skipped** (30,87 s, 3 warnings) |
| Connecteur Node `npm run test:unit` | **109/109 PASS** (17 fichiers) |
| Plugin OpenClaw `npm test` | **59/59 PASS** |
| Proxy relay `node --test proxy.test.mjs` | **19/19 PASS** |
| Android JVM `:app:testDebugUnitTest --offline --rerun-tasks` | **896/896 PASS**, 0 échec/erreur/ignoré (117 fichiers) |
| Android lint `:app:lintDebug --offline` | **PASS** (HTML/SARIF générés) |
| Builds `assembleDebug`, `assembleQa`, `assembleQaAndroidTest` | **PASS** ; APK QA REJECT app `b599532c…9a6b`, test `be283081…0e83` ; debug `b0095cf4…c694` |
| Tests du harnais | **14/14 PASS** après correctif attestation (intégré `5ca0a92`) |
| `git diff --check` | À rejouer sur le SHA final après le P2 |

Résultats runtime : voir §3 (harnais backend), §4 (APPLY) et §5 (REJECT).

## 9. Verdicts

| Verdict | Décision | Portée |
|---|---|---|
| `GO_E2E_HARNESS_REPRODUCIBLE` | **GO** | backend positif et négatif rejoués depuis le dépôt sur `b4db8da`, DeepSeek réel isolé |
| `GO_ANDROID_QA_CHAT` | **GO** | Pixel physique API 37, paquet QA, DeepSeek réel, 2 outils corrélés (APPLY) |
| `GO_ANDROID_QA_PLAN_APPLY` | **GO** | DayPlan v2→v3 appliqué par le code Android, Core v3 à 610 |
| `GO_ANDROID_QA_ACK` | **GO** | ACK APPLIED et REJECTED émis par Android, confirmés Core, rejeux identiques |
| `GO_ANDROID_QA_REJECT` | **GO** | rejet explicite, plan v2 inchangé, aucun v3, oracle post-hoc lié à l’ACK Android |
| `GO_QA_COACH_LOOP` | **GO** | APPLY v2→v3 + ACK APPLIED et REJECT sans changement, sur Android QA physique |
| `GO_REMOTE_V4_PUBLIC` | **NO GO — BLOCKED_NETWORK** | Funnel cible morte, aucune autorisation de changement |
| `GO_PIXEL_PRODUCT` | **NO GO** | paquet produit non testé |
| `GO_PRODUCT_REMOTE` | **NO GO** | produit et transport public non testés |

## 10. Revue indépendante

Statut : **PENDING**. Aucune revue ne peut accepter un runtime non exécuté.

## 11. Limites honnêtes

- Un test compilé n’est pas un test exécuté.
- Une proposition Core n’est pas une modification Android appliquée.
- Un ACK construit par le harnais backend n’est pas un ACK émis par Android.
- Un succès local ne vaut pas `GO_REMOTE_V4_PUBLIC`.
- Un succès sur paquet QA ne vaut pas `GO_PIXEL_PRODUCT`.
