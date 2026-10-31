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
| V7-06A-TEST-ANDROID | Tests JVM C1/C3/C4/C6/C7/C8, rouges sur le SHA instrumenté | TRACE `0fe8155` | READY | — | — | C1/C3/C8 rouges pour la raison attendue ; C4/C6/C7 verts ; suite complète exécutée |
| V7-06A-TEST-PC | Tests connecteur/Core C5/C6/C7/C8, rouges sur le SHA instrumenté | TRACE `0fe8155` | READY | — | — | C5 rouges ; C7 verts ; suites Node/pytest exécutées |
| V7-06A-FIX | Correctif minimal justifié | TEST | BLOCKED | — | — | Aucune régression JVM/backend/Node ; défaut reproduit corrigé |
| V7-06A-VM | Qualification VM/AVD API 37, recettes VM-1 puis VM-2 | FIX | BLOCKED | — | — | VM-1 PASS avant toute VM-2 ; preuves SHA/APK/hash |
| V7-06A-REVIEW | Revue indépendante finale (sécurité, idempotence, révocation) | VM | BLOCKED | — | — | Aucun P0/P1 ouvert dans le périmètre accepté |

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

## 6. Décisions d’orchestration

- Le diagnostic précède tout correctif ; aucune option de la Phase D n’est appliquée
  par défaut.
- Le timeout de 8 s n’est pas présumé cause racine.
- Les audits ne modifient aucun état live ; ils citent le BASE_SHA.
- La qualification VM repart d’un environnement serveur isolé et de données
  synthétiques ; aucun fichier privé du Pixel n’est copié.
