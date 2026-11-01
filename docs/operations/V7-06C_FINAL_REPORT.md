# ATLAS V7-06C — Coach Android puis transport public : rapport final

**Statut du document : CLÔTURE DE CAMPAGNE — Coach Loop QA GO_WITH_LIMITATIONS en
local et via WSS public ; deux P1 ouverts (blocage intermittent avant chat,
observabilité de révocation A4-4) ; revue indépendante ACCEPTED_WITH_LIMITATIONS,
aucun P0. `GO_PIXEL_PRODUCT` et `GO_PRODUCT_REMOTE` restent NO GO.**
Les sections sont mises à jour au fur et à mesure des exécutions réelles sur le SHA
intégré. Une ligne `NOT_RUN`, `BLOCKED`, `FAIL` ou `CORRECTION_REQUIRED` ne doit pas
être lue comme un succès.

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

Le **transport public WSS** a été qualifié depuis le Pixel en **LTE**, sans
`adb reverse`, vers `wss://all.taildc7886.ts.net:8443` : TLS valide, relais isolé
sur `127.0.0.1:4400`, Core/Gateway/proxy en loopback. APPLY et REJECT ont tous deux
produit leurs preuves `network=public_wss` (DayPlan v3/ACK APPLIED ; plan v2/ACK
REJECTED). Le Funnel n'a pas été modifié et a retrouvé son état initial après arrêt
de la stack.

Deux **P1** restent ouverts et ne sont pas transformés en succès :

1. **Fiabilité** : le premier essai APPLY public s'est bloqué après la synchronisation,
   avant l'envoi du chat ; une relance sur stack neuve a PASS. Cause racine non
   confirmée (inversion de verrous `consentGate`/WebSocket suspectée).
2. **Observabilité A4-4** : après révocation du device QA, le connecteur refuse bien
   la reconnexion (aucune nouvelle session, `errors_total=1`), mais l'app observe
   `state=ERROR`, `close_code=null` au lieu de `REVOKED`/4001. La sonde instrumentée
   est **FAIL**.

Le produit `dev.atlas.coach` n'a jamais été installé, remplacé ni instrumenté ; les
18 paquets QA ont été désinstallés après autorisation.

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
Commits `64020d7` (harnais), `f9017f4` (attestation ACK liée à l'`ack_id`),
`d0f7d48` (port Relay épinglé), intégrés jusqu'à `3b9b3d1`. Le harnais V7-06B non
commité sous `/tmp/atlas-v7-06b-backend` a été audité ; ses éléments utiles sont portés
sous `scripts/v7-06c/` avec les correctifs connus : `expires_at` 30 s, arrêt Gateway
par signature, stderr des enfants en fichiers privés 0600, readiness réelle,
identités synthétiques et oracles indépendants.

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

Tests statiques du harnais : `17/17` unittest PASS (dont attestation ACK et port
épinglé), `py_compile`, `node --check`, `git diff --check`. Nettoyage : chaque stack
arrêtée par identité propriétaire
(processus, conteneur, racine) ; aucune ressource `atlas-v7-06c` résiduelle.

## 4. Android APPLY

Statut : **PASS local et public WSS** — `GO_ANDROID_QA_CHAT`,
`GO_ANDROID_QA_PLAN_APPLY`, `GO_ANDROID_QA_ACK` pour le scénario APPLY. Preuves :
`v7-06c-apply-a49e3a3aa819.json` (local) et
`v7-06c-apply-e4557615f525.json` (public, `network=public_wss`),
`docs/operations/evidence/V7-06C_ANDROID_APPLY.md` et
`docs/operations/evidence/V7-06C_PUBLIC_WSS.md`.

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

Statut : **PASS local et public WSS** — `GO_ANDROID_QA_REJECT`. Preuves :
`v7-06c-reject-2fb57424d534.json` (local, post-hoc) et
`v7-06c-reject-be5168ed2070.json` (public, `network=public_wss`),
`docs/operations/evidence/V7-06C_ANDROID_REJECT.md` et
`docs/operations/evidence/V7-06C_PUBLIC_WSS.md`.

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

Statut : **APPLY et REJECT PASS via WSS public depuis LTE** — `GO_REMOTE_V4_PUBLIC`
avec deux P1 documentés (fiabilité, observabilité A4-4).

Modalité autorisée par le propriétaire : relais isolé publié sur `127.0.0.1:4400`,
port déjà ciblé par le Funnel existant ; **aucun changement Funnel/Tailscale**, aucun
`sudo`, aucun service live modifié.

| Élément | Constat |
|---|---|
| Réseau Android | Pixel 9 Pro XL sur LTE (`MOBILE[LTE]`, réseau 151), Wi‑Fi désactivé pendant les tests, restauré ensuite |
| DNS/TLS | `all.taildc7886.ts.net` résolu depuis le téléphone ; `https://…:8443/health` → HTTP 200, TLS valide (`ssl_verify_result=0`) |
| Relay | conteneur isolé épinglé `atlas-paseo-relay:r3a`, publié `127.0.0.1:4400` ; Funnel inchangé `8443 → 4400` |
| Noise/auth | Noise NK protégé, `server_public_key` épinglée, session device authentifiée, capabilities V4 |
| Exposition | Core `8111`, Gateway `19076` et proxy `19075` en loopback uniquement ; aucun port Core/Gateway public |
| `adb reverse` | **absent** sur APPLY et REJECT |
| APPLY | `network=public_wss`, proposition `26240af5-…` APPLIED, plan v2→v3, ACK `80962380-…` APPLIED v3, rejeu identique |
| REJECT | `network=public_wss`, proposition `9adb680d-…` REJECTED, plan v2, ACK `6122332c-…` REJECTED, aucun v3 |
| Funnel final | cible `127.0.0.1:4400` de nouveau sans listener, état initial ; relais live `atlas-v7-relay` sur 4402 intact |

Deux limites P1 sont conservées :

1. **Blocage intermittent avant chat** : au premier essai APPLY public, le message
   USER a été persisté mais aucune requête chat n'a atteint le connecteur ; le test
   est resté bloqué et a été arrêté. Une relance sur stack neuve a PASS. Cause racine
   non confirmée (inversion de verrous `consentGate`/WebSocket suspectée).
2. **Révocation A4-4** : le device QA `968aff85…` a été révoqué durablement. La
   reconnexion est refusée par le connecteur (`connections_opened_total=2`,
   `sessions_opened_total=1`, `errors_total=1`, aucune nouvelle session), mais l'app
   observe `state=ERROR`, `close_code=null` au lieu de `REVOKED`/4001. La sonde
   instrumentée est **FAIL** ; le refus serveur est confirmé par les métriques.

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

### Demande de révocation ciblée (non exécutée)

- **Ressources** : devices connecteur `pixel-v7` (14 capabilities, dont V4) et
  `r3c-pixel-final` (6 capabilities V1/V2) ; `device_ref` Core `android-9a3f0f…`
  avec `DAY_PLAN`/`ACTIVITY_STATUS` `GRANTED` v2 ; bearer Android live associé.
- **Effets** : fermeture des sessions relay de ces devices, refus des nouvelles
  opérations V4, conservation des données existantes ; aucune suppression de
  projection ou de consentement n’est nécessaire. Les autres appareils et le
  produit ne sont pas touchés.
- **Sauvegarde préalable** : copie privée 0600 de
  `~/.local/state/atlas-relay/devices.json`, `operations.json` et d’un export
  lecture seule des consentements Core, avec hashes.
- **Commandes prévues** : `node dist/main.js revoke-device <device_id>` pour chaque
  device ciblé, puis vérification `devices.json` et test de reconnexion refusée ;
  la rotation du bearer Core reste une opération distincte, avec redémarrage
  contrôlé du service live et mise à jour du client.
- **Vérification** : aucune nouvelle session/opération pour les devices révoqués ;
  refus observable ; services live healthy ; aucun impact sur les autres devices.
- **Rollback** : restauration du registre sauvegardé uniquement en cas de
  révocation erronée, suivie d’une nouvelle vérification ; une révocation
  volontaire n’est pas annulée automatiquement.
- **Autorisation** : requise explicitement avant toute commande live.

Aucune identité personnelle n’est utilisée par la stack V7-06C. Le jeton relay de
l’app QA est stocké sous forme `IV:ciphertext` chiffrée AES-GCM avec une clé
Android Keystore (`KeystoreRelayCredentialStore`) ; aucun jeton en clair n’a été
publié dans les preuves. L’appareil a été temporairement basculé en LTE pour la
recette publique, puis le Wi‑Fi a été restauré (`wifi_on=1`, réseau par défaut 152) ;
la configuration `mobile_data=1` d’origine est inchangée.

**Nettoyage :** les 10 paquets QA V7-06C et les 8 anciens paquets `qa/qa2/qa3/qa4`
(app + test) ont été désinstallés après autorisation explicite. Seuls
`dev.atlas.coach` (produit) et `dev.atlas.coach.test` restent installés ; le produit,
HOME, Keystore et données personnelles sont intacts (`lastUpdateTime` inchangé).

## 8. Suites et preuves

Exécutées sur le code intégré (SHA de code `fa960d5` pour la sonde, backend P2
`b33d43b`, harnais jusqu'à `3b9b3d1`) :

| Suite | Résultat |
|---|---|
| Backend `pytest -q` (après P2 422) | **372 passed / 1 skipped** (29,38 s, 3 warnings) |
| Connecteur Node `npm run test:unit` | **109/109 PASS** (17 fichiers) |
| Plugin OpenClaw `npm test` | **59/59 PASS** |
| Proxy relay `node --test proxy.test.mjs` | **19/19 PASS** |
| Android JVM `:app:testDebugUnitTest --offline --rerun-tasks` | **896/896 PASS**, 0 échec/erreur/ignoré (117 fichiers) |
| Android lint `:app:lintDebug --offline` | **PASS** (HTML/SARIF générés) |
| Builds `assembleDebug`, `assembleQa`, `assembleQaAndroidTest` | **PASS** ; QA REJECT public app `7759c26b…c36b`, test `c6eebb6a…aed` (ACK replay + sonde) ; debug `b0095cf4…c694` |
| Tests du harnais | **17/17 PASS** (attestation ACK, port épinglé) |
| `git diff --check` | À rejouer sur le SHA final documentaire |

Résultats runtime : voir §3 (harnais backend), §4 (APPLY), §5 (REJECT) et §6 (WSS public).

## 9. Verdicts

| Verdict | Décision | Portée |
|---|---|---|
| `GO_E2E_HARNESS_REPRODUCIBLE` | **GO_WITH_LIMITATIONS** | backend positif et négatif rejoués depuis le dépôt, DeepSeek réel isolé ; P2 traçabilité APK↔SHA suivi, champ pull corrigé `20445bf` |
| `GO_ANDROID_QA_CHAT` | **GO_WITH_LIMITATIONS** | Pixel physique API 37, paquet QA, DeepSeek réel, 2 outils corrélés (local et public) ; P1 blocage public intermittent |
| `GO_ANDROID_QA_PLAN_APPLY` | **GO** | DayPlan v2→v3 appliqué par le code Android, Core v3 à 610 (local et public) |
| `GO_ANDROID_QA_ACK` | **GO** | ACK APPLIED et REJECTED émis par Android, confirmés Core, rejeux identiques |
| `GO_ANDROID_QA_REJECT` | **GO** | rejet explicite, plan v2 inchangé, aucun v3 (local et public) |
| `GO_QA_COACH_LOOP` | **GO_WITH_LIMITATIONS** | APPLY v2→v3 + ACK APPLIED et REJECT sans changement ; P1 fiabilité public |
| `GO_REMOTE_V4_PUBLIC` | **GO_WITH_LIMITATIONS (QA APPLY/REJECT uniquement)** | WSS public LTE, TLS/Noise/auth, aucun `adb reverse`, Core/Gateway loopback ; **pas** une qualification générale de fiabilité, A4-4 ou produit |
| `GO_PIXEL_PRODUCT` | **NO GO** | paquet produit non testé ; seuls des paquets QA séparés l’ont été |
| `GO_PRODUCT_REMOTE` | **NO GO** | produit non testé ; modèle/stack isolés, pas le produit |

## 10. Revue indépendante

**Verdict : `GO_WITH_LIMITATIONS` pour les parcours QA APPLY/REJECT ; aucun P0.**
Revue `d4a996c5-d07a-45d2-ad7e-9c837159c60b` (`codex:gpt-6-luna` / `xhigh`),
worktree détaché `/tmp/atlas-v7-06c-review-ro` en lecture seule (`a-w`), SHA revu
`8cab6fc`. La session a confirmé `HEAD`/`git status` propres et n’a modifié aucun
fichier ; elle n’a lancé aucun test, ADB, Docker ni réseau.

- **P0** : aucun.
- **P1 fiabilité** : blocage intermittent avant chat, une occurrence, relance PASS,
  cause racine non confirmée.
- **P1 A4-4** : refus serveur de la reconnexion révoquée confirmé, mais app
  `ERROR`/`close_code=null` ; sonde **FAIL**, A4-4 non qualifié.
- **P2 pull** : champ `pull_count_after_runner` trompeur ; corrigé après revue
  (`20445bf`), 17/17 tests harnais.
- **P2 traçabilité APK↔SHA** : les proofs JSON ne sérialisent pas le SHA source ;
  les SHAs d’exécution et hashes APK restent documentés dans les preuves. Limite
  suivie pour une amélioration future.
- La revue recommande `GO_WITH_LIMITATIONS` pour le harnais, le chat, la boucle
  Coach et le public QA APPLY/REJECT ; `GO` pour APPLY/REJECT/ACK ; `NO GO` pour le
  produit. Le parent adopte ces verdicts.
- Détail complet : `docs/operations/evidence/V7-06C_SECURITY_REVIEW.md`.

## 11. Limites honnêtes

- Un test compilé n’est pas un test exécuté.
- Une proposition Core n’est pas une modification Android appliquée.
- Un ACK construit par le harnais backend n’est pas un ACK émis par Android.
- Un succès local ne vaut pas `GO_REMOTE_V4_PUBLIC` ; les preuves publiques ont été
  obtenues séparément sur LTE, sans `adb reverse`.
- Un succès sur paquet QA ne vaut pas `GO_PIXEL_PRODUCT` ; le produit n’a pas été testé.
- **P1 fiabilité** : le premier APPLY public s’est bloqué avant le chat ; la relance a
  PASS. Le défaut n’est pas corrigé et sa cause racine n’est pas confirmée.
- **P1 A4-4** : la révocation est refusée côté connecteur mais l’app renvoie
  `ERROR`/`close_code=null` ; la sonde instrumentée est FAIL. Aucun 4001/`REVOKED`
  propre n’a été observé.
- Les identités live résiduelles (`pixel-v7`, `r3c-pixel-final`, consentements
  `GRANTED v2`) restent non révoquées ; aucune action live n’a été effectuée.
