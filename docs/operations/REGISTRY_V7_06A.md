# ATLAS V7-06A — Fiabilisation V4 : registre de campagne

**Mission :** identifier et corriger le mécanisme ayant empêché Android d’acquitter le
second consentement V4, puis démontrer sur VM/AVD que consentements, DayPlan et reçus
résistent aux timeouts, annulations, duplications et redémarrages.

**BASE_SHA :** `55625299c364e1c71eba5924fcfe933af1852a2f` (local = `origin/main` observé).
**Branche d’intégration locale :** `feat/atlas-v7-06a-v4-reliability`.
**Aucun push, PR, merge distant, aucun ADB sur le Pixel physique.**
**Environnement de qualification :** émulateur API 37 exclusivement (`emulator-*`),
identité, appairage, consentements, plan et serveur de test synthétiques neufs.

## 1. Contraintes d’exécution

- 6 enfants simultanés maximum, 3 tickets d’implémentation maximum, aucune sous-délégation.
- Tous les enfants : `provider=codex`, `model=gpt-6-luna`, effort effectif `xhigh`,
  vérifié dans les métadonnées Paseo de chaque session.
- Worktree séparé par enfant ; un seul propriétaire d’écriture par fichier.
- Audits et revues : worktree détaché + `chmod -R a-w` + contrôle `git status`/HEAD
  après exécution (Paseo/Codex n’expose pas de mode lecture seule natif).
- Un seul opérateur ADB/AVD à la fois ; série explicite `emulator-*`, jamais `adb -d`.
- Aucun token, bearer, UUID réel, payload complet, contenu de conversation, titre
  d’activité personnel ni identité brute dans les preuves.
- Une notification de fin != validation : le parent relit diff, tests et limites.

## 2. Tickets

| TASK_ID | Objectif | Dépendance | Statut | Session Paseo | Worktree | Oracle |
|---|---|---|---|---|---|---|
| V7-06A-DIAG-A1-ANDROID | Audit lecture seule worker/persistance/ACK | BASE_SHA | FINISHED — rapport reçu, worktree intact | `148404ff-897a-407e-b686-2cf92976cf1f` | `/tmp/atlas-v7-06a-a1-android-ro` | Matrice H1–H6 + cycle local ; preuve `evidence/V7-06A_DIAG_A1_ANDROID.md` |
| V7-06A-DIAG-A2-RELAY | Audit lecture seule transport/corrélation/session | BASE_SHA | FINISHED — rapport reçu, worktree intact | `1b325742-6f12-49f3-a77b-3068b2a35461` | `/tmp/atlas-v7-06a-a2-relay-ro` | Matrice H1–H6 + cycle Relay ; preuve `evidence/V7-06A_DIAG_A2_RELAY.md` |
| V7-06A-DIAG-A3-CORE | Audit lecture seule connecteur PC + Core V4 | BASE_SHA | FINISHED — rapport reçu, worktree intact | `0f3c41b5-ebb5-4dfb-9903-465ebe781fc3` | `/tmp/atlas-v7-06a-a3-core-ro` | Matrice H1–H6 + garanties Core vs journal connecteur ; preuve `evidence/V7-06A_DIAG_A3_CORE.md` |
| V7-06A-DIAG | Consolidation des trois audits, matrice et rapport | A1/A2/A3 | FINISHED — cause racine UNKNOWN, mécanisme de blocage établi | — | — | `evidence/V7-06A_DIAGNOSTIC_REPORT.md` |
| V7-06A-TRACE-ANDROID | Instrumentation bornée worker/Relay, ACK typé, diagnostics | DIAG | FINISHED — commit `34763f9`, intégré `0fe8155`, 887 tests + lint PASS | `f1e2f879-a304-40ac-8221-b9bc7cecd69b` | `/tmp/atlas-v7-06a-trace-android` | Trace synthétique jusqu’à l’ACK ; `evidence/V7-06A_TRACE_ANDROID.md` |
| V7-06A-TRACE-PC | Instrumentation bornée connecteur (POST/journal/WS/session) | DIAG | FINISHED — commit `c24b1bf`, intégré `1bffa9e`, 100/100 tests | `2b14b7bc-cebc-4ead-94dd-55bd74f7ea8c` | `/tmp/atlas-v7-06a-trace-pc` | Dernière étape et durées séparées ; `evidence/V7-06A_TRACE_PC.md` |
| V7-06A-TEST-ANDROID | Tests JVM C1/C3/C4/C6/C7/C8, rouges sur le SHA instrumenté | TRACE `0fe8155` | FINISHED — commit `6d749c7` ; 895 tests, 4 rouges confirmés | `d1ff1caa-eb64-4e25-8a6f-b0cadc12c281` | `/tmp/atlas-v7-06a-test-android` | `evidence/V7-06A_TEST_RED.md` |
| V7-06A-TEST-PC | Tests connecteur/Core C5/C6/C7/C8, rouges sur le SHA instrumenté | TRACE `0fe8155` | FINISHED — commit `6973b5e` ; 2 rouges confirmés, backend ciblé 13 PASS | `7a1b8c69-a2cd-4159-9a57-e4d2dc7d64e1` | `/tmp/atlas-v7-06a-test-pc` | `evidence/V7-06A_TEST_RED.md` |
| V7-06A-FIX-ANDROID | Reprise incertaine, collision `request_id`, session obsolète, single-flight | TEST-ANDROID | FINISHED — commit `2004500`, intégré `39746bd` ; 895 tests + lint PASS | `9f329781-ae13-49bf-a785-a53db1b965cb` | `/tmp/atlas-v7-06a-fix-android` | `evidence/V7-06A_FIX.md` |
| V7-06A-FIX-PC | Reçu consentement après restart, rejeu uncertain ciblé | TEST-PC | FINISHED — commit `51e3e00`, intégré `39746bd` ; Node 106, backend 348 | `df555254-e385-4cde-a714-a011fc8c6286` | `/tmp/atlas-v7-06a-fix-pc` | `evidence/V7-06A_FIX.md` |
| V7-06A-FIX-PC-R2 | Rejeu V4 quand Relay renouvelle l'enveloppe (P1 revue) | REVIEW `b04a5ff` | RUNNING | `1dcb69b6-82cc-4ef4-8a13-25d54eed0115` | `/tmp/atlas-v7-06a-fix-pc-r2` | Test rouge puis vert : même corps/clé/request_id, nouvelle session/horodatages → reçu rejoué, un seul POST |
| V7-06A-VM | Qualification VM/AVD API 37, recettes VM-1 puis VM-2 | FIX corrigé | PAUSED — build de contrôle PASS sur `b04a5ff`, aucun test instrumenté lancé, AVD arrêté ; attente du SHA corrigé | `a9280272-0534-4cc6-91db-2c78c9abb2b0` | `/tmp/atlas-v7-06a-vm` | VM-1 PASS avant toute VM-2 ; preuves SHA/APK/hash |
| V7-06A-REVIEW | Revue indépendante (sécurité, idempotence, révocation) | FIX `b04a5ff` | FINISHED pré-VM — `CORRECTION_REQUIRED`, aucun P0, 1 P1, 3 P2 | `d1a7590b-a0c0-49b6-bc47-cf57dd4d262e` | `/tmp/atlas-v7-06a-review` | Relecture finale obligatoire après correctif et VM |

## 3. Preuves historiques conservées

- V7-05 Pixel : **FAIL** à la première synchronisation V4, une seule exécution sur
  `fdaef7b` ; deux `POST /v4/sync/consent-state` 200, deux opérations connecteur
  `completed_redacted`, Android DAY_PLAN 2/2 mais ACTIVITY_STATUS 0/2,
  `v4_code=UNAVAILABLE`, aucun reçu plan, plan resté v2. Cause racine **UNKNOWN**.
- Preuve : `docs/operations/evidence/V7-05_PIXEL_V4_E2E_FAIL.md`.
- Cette campagne ne relance pas ce test sur le Pixel et ne modifie pas l’état résiduel.

## 3bis. Audit d’accès résiduel (2026-10-31)

Audit lecture seule réalisé sans ADB ni modification live ; preuve
`docs/operations/evidence/V7-06A_RESIDUAL_ACCESS_AUDIT.md`.

- Core live : `DAY_PLAN` et `ACTIVITY_STATUS` toujours `GRANTED` v2 pour le
  `device_ref` du bearer Android ; **aucune projection personnelle V4** stockée.
- Connecteur : `pixel-v7` et `r3c-pixel-final` ne sont pas révoqués ; le jeton
  `pixel-v7` a encore servi à un chat V2 le 2026-10-31 à 15:40Z.
- Aucune révocation/rotation live effectuée : action sensible soumise à une
  autorisation ciblée et à une sauvegarde. Les tests VM utilisent un environnement
  entièrement isolé.

## 3quater. Phase B intégrée — instrumentation bornée

- Branche d’intégration au SHA `0fe8155` : deux tranches disjointes fusionnées.
- Android : commit `34763f9`, merge `0fe8155` ; **887 tests JVM PASS**, **lint PASS**,
  trace `CoreV4Diagnostics` désactivée par défaut, ACK typé
  `APPLIED/SUPERSEDED/MISSING`, événements Relay bornés.
- Connecteur : commit `c24b1bf`, merge `1bffa9e` ; **100 tests Node PASS**, trace
  `ATLAS_DIAG_TRACE=1`, durées POST/journal/réponse et résultat d’envoi WS.
- Preuves : `evidence/V7-06A_TRACE_ANDROID.md`, `evidence/V7-06A_TRACE_PC.md`.
- Aucun correctif de comportement dans ces tranches ; aucun test discriminant rouge
  n’est encore intégré.

## 3quinquies. Phase D intégrée — correctif minimal

- **SHA intégré `39746bd258c125736ff50e54e571c723d10a1ecf`** : tests rouges + correctifs
  Android (`2004500`) et connecteur (`51e3e00`) fusionnés.
- Android : reprise bornée `UNAVAILABLE` (une tentative, même corps/clé), refus de
  collision `request_id`, frames d’ancienne session ignorées, génération sous verrou,
  relecture `syncedVersion`, single-flight + `APPEND_OR_REPLACE`/`UPDATE`.
- Connecteur : reçu consentement borné conservé dans `completed_redacted`, rejoué
  après redémarrage sans second POST ; ré-exécution limitée au consentement incertain ;
  autres V4 toujours bloqués/rédigés ; trace stderr bornée si activée.
- Résultats sur le SHA intégré : Android **895/895 + lint PASS**, Node **106/106**,
  backend **348 passed / 1 skipped**, `git diff --check` PASS.
- Preuve : `evidence/V7-06A_FIX.md`.
- Qualification VM/AVD et revue indépendante finale restent à exécuter.

## 3sexies. Revue indépendante pré-VM et correctif P1 (2026-11-01)

- Revue lecture seule sur `b04a5ff` : verdict **`CORRECTION_REQUIRED`**, aucun P0.
- **P1 confirmé par le parent** : `requestHash = hash(request)` couvre toute l'enveloppe
  (`session_id`, `created_at`, `expires_at`). Or Android réutilise le même corps,
  la même clé et le même `request_id` lors de sa reprise `UNAVAILABLE`, tandis que
  `RelayBackendTransport` régénère les horodatages et peut changer de session. Le
  connecteur répondait `IDEMPOTENCY_CONFLICT` avant de consulter le cache : le reçu
  déjà obtenu n'était pas rejoué et Android laissait le consentement en attente.
  Le test de rejeu après redémarrage existant réutilisait le même objet d'enveloppe,
  donc il ne couvrait pas ce cas.
- Correctif borné `V7-06A-FIX-PC-R2` : comparer l'identité métier stable
  (opération + clé d'idempotence + payload) pour le rejeu V4 par `request_id`, ignorer
  seulement les métadonnées de transport régénérées ; conserver le conflit si le
  payload ou la clé change ; conserver le comportement plein-enveloppe pour les
  opérations non-V4. Aucun changement de timeout, de rédaction ou de schéma.
- P2 documentés sans blocage : validation stricte du reçu connecteur et corrélation
  `request_id` ; accumulation possible de travaux immédiats `APPEND_OR_REPLACE` ;
  risque pré-existant d'inversion `consentGate` ↔ verrou WebSocket (non reproduit).
- La qualification VM reprendra sur le SHA corrigé ; aucune preuve VM n'est produite
  sur `b04a5ff`.

## 4. Verdicts visés (distincts)

`GO_V4_CONSENT_SYNC`, `GO_V4_PLAN_SYNC`, `GO_V4_RECONCILIATION`, `GO_VM_V4_E2E`,
`GO_REMOTE_CHAT_VM`, `GO_PLAN_REVISION_VM`.

`GO_PIXEL` et `GO_PRODUCT_REMOTE` restent **sans GO** tant qu’une preuve physique
requise n’existe pas. Un succès VM n’est jamais converti en preuve Pixel.

## 5. Journal des sessions

| Date | Session | Rôle | Provider/modèle/effort constatés | Résultat |
|---|---|---|---|---|
| 2026-10-31 | `148404ff-897a-407e-b686-2cf92976cf1f` | A1 Android RO | codex / gpt-6-luna / xhigh | RUNNING |
| 2026-10-31 | `1b325742-6f12-49f3-a77b-3068b2a35461` | A2 Relay RO | codex / gpt-6-luna / xhigh | RUNNING |
| 2026-10-31 | `0f3c41b5-ebb5-4dfb-9903-465ebe781fc3` | A3 Core/Connector RO | codex / gpt-6-luna / xhigh | FINISHED — cause UNKNOWN |
| 2026-10-31 | `f1e2f879-a304-40ac-8221-b9bc7cecd69b` | TRACE Android | codex / gpt-6-luna / xhigh | FINISHED — `34763f9`, 887 tests, lint PASS |
| 2026-10-31 | `2b14b7bc-cebc-4ead-94dd-55bd74f7ea8c` | TRACE PC | codex / gpt-6-luna / xhigh | FINISHED — `c24b1bf`, 100 tests PASS |
| 2026-10-31 | `d1ff1caa-eb64-4e25-8a6f-b0cadc12c281` | TEST Android | codex / gpt-6-luna / xhigh | FINISHED — `6d749c7`, 4 rouges |
| 2026-10-31 | `7a1b8c69-a2cd-4159-9a57-e4d2dc7d64e1` | TEST PC | codex / gpt-6-luna / xhigh | FINISHED — `6973b5e`, 2 rouges |
| 2026-10-31 | `9f329781-ae13-49bf-a785-a53db1b965cb` | FIX Android | codex / gpt-6-luna / xhigh | FINISHED — `2004500`, 895 tests + lint |
| 2026-10-31 | `df555254-e385-4cde-a714-a011fc8c6286` | FIX PC | codex / gpt-6-luna / xhigh | FINISHED — `51e3e00`, Node 106 |
| 2026-11-01 | `d1a7590b-a0c0-49b6-bc47-cf57dd4d262e` | REVIEW pré-VM | codex / gpt-6-luna / xhigh | FINISHED — `CORRECTION_REQUIRED`, 1 P1 |
| 2026-11-01 | `1dcb69b6-82cc-4ef4-8a13-25d54eed0115` | FIX PC R2 | codex / gpt-6-luna / xhigh | RUNNING |
| 2026-11-01 | `a9280272-0534-4cc6-91db-2c78c9abb2b0` | VM | codex / gpt-6-luna / xhigh | PAUSED — build contrôle PASS, AVD arrêté |

## 6. Décisions d’orchestration

- Le diagnostic précède tout correctif ; aucune option de la Phase D n’est appliquée
  par défaut.
- Le timeout de 8 s n’est pas présumé cause racine.
- Les audits ne modifient aucun état live ; ils citent le BASE_SHA.
- La qualification VM repart d’un environnement serveur isolé et de données
  synthétiques ; aucun fichier privé du Pixel n’est copié.
