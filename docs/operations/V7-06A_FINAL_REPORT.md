# ATLAS V7-06A — Rapport final de campagne

Date : 2026-11-01
Branche locale : `feat/atlas-v7-06a-v4-reliability`
Base observée : `55625299c364e1c71eba5924fcfe933af1852a2f`
Code intégré : `be31d097fe0cf6a5bc50688b421bc9eecbbc847e` + correctif présentation
`fbfa403ac0c28ad32a9c5995b31ebd36edc7924f`
Docs/registre : `REGISTRY_V7_06A.md`
Aucun push, PR, merge distant ; aucun test sur l’application produit installée du
propriétaire.

## 1. Objectif et résultat en une phrase

Identifier le mécanisme ayant empêché Android d’acquitter le second consentement
V4, le corriger de façon bornée et démontrer la résistance aux timeouts,
annulations, duplications et redémarrages. Résultat : **mécanisme de perte/non-reprise
reproduit et corrigé, suites déterministes vertes, qualification physique QA PASS
sur Pixel API 37.1 ; cause racine exacte de V7-05 toujours UNKNOWN ; VM-1 bloquée
par l’environnement ; aucune preuve VM ni de l’application produit installée.**

## 2. Diagnostic

Trois audits lecture seule indépendants (A1 Android, A2 Relay, A3 connecteur/Core)
ont établi, sans modifier l’état live :

- Android classait une réponse envoyée mais non reçue en `UNAVAILABLE` non rejouable,
  arrêtait la passe avant le plan et ne programmait pas de suivi immédiat.
- Le connecteur conservait après redémarrage des opérations V4
  `completed_redacted` sans reçu rejouable ; une reprise Android pouvait donc être
  bloquée.
- Une deuxième tentative Android réutilise le corps, la clé et le `request_id`, mais
  Relay régénère `created_at`/`expires_at` et peut changer de `session_id` ; le
  connecteur comparait le hash de toute l’enveloppe et répondait
  `IDEMPOTENCY_CONFLICT` au lieu de rejouer le reçu.
- Les deux réponses HTTP 200 Core et les deux opérations `completed_redacted` du
  run V7-05 ne prouvent pas la réception côté Android ; `UNAVAILABLE` ne prouve pas
  un échec serveur.

**La cause racine exacte de V7-05 reste UNKNOWN.** Le mécanisme ci-dessus est
compatible avec les observations, mais n’est pas présenté comme la cause certaine.
Le timeout de 8 s n’est pas présumé cause racine.

Preuves : `evidence/V7-06A_DIAGNOSTIC_REPORT.md`, `V7-06A_DIAG_A1_ANDROID.md`,
`V7-06A_DIAG_A2_RELAY.md`, `V7-06A_DIAG_A3_CORE.md`.

## 3. Instrumentation bornée (Phase B)

- Android : `CoreV4Diagnostics`, 256 événements en mémoire, désactivé par défaut,
  activation explicite, tag logcat `ATLAS_V4_DIAG`, corrélations hachées, aucun
  payload/UUID/token. ACK typé `APPLIED`/`SUPERSEDED`/`MISSING`.
- Connecteur : diagnostics bornés 512 événements, activés par `ATLAS_DIAG_TRACE=1`,
  une ligne JSON stderr ≤ 1 024 octets par événement, étapes POST/journal/WS.
- Aucun changement de comportement dans ces tranches ; aucune donnée sensible.

## 4. Reproductions déterministes (Phase C)

| Suite | Rouges reproduits | Devenir |
|---|---|---|
| Android JVM | perte réponse consentement, perte réponse plan, collision `request_id` en vol, réponse d’ancienne session | 4 → verts |
| Connecteur Node | reçu consentement après redémarrage, consentement incertain ré-exécutable | 2 → verts |
| Connecteur Node R2 | rejeu après renouvellement d’enveloppe, en mémoire et après redémarrage (`2 failed | 1 passed | 24 skipped`) | 2 → verts |
| Backend | révocation v3, idempotence même clé/corps, conflit même clé/corps différent, rejeu après version plus récente | verts |

Les tests rouges échouaient pour la raison visée (`UNAVAILABLE/TIMEOUT`,
`IDEMPOTENCY_CONFLICT`, session obsolète), pas par compilation ou configuration.
Preuves : `V7-06A_TEST_RED.md`, `V7-06A_FIX.md`, `V7-06A_FIX_PC_R2.md`,
`V7-06A_TEST_MATRIX.md`, `V7-06A_SYNC_IDEMPOTENCY_MATRIX.md`.

## 5. Correctif minimal (Phase D)

Trois tranches disjointes, aucun test existant affaibli, aucun timeout augmenté,
aucune rédaction globale désactivée, aucun verrou réseau bloquant la révocation
locale immédiate.

1. **Android `2004500`** : reprise unique `UNAVAILABLE` (hors
   `CAPABILITY_UNAVAILABLE`/`CANCELLED`) avec même corps/clé durable et relecture
   du consentement ; refus d’un `request_id` déjà en file/en vol ; frames d’une
   ancienne session ignorées sans abort ; génération de socket filtrée sous verrou ;
   relecture `syncedVersion` ; single-flight de passe + `APPEND_OR_REPLACE`/`UPDATE`.
2. **Connecteur `51e3e00`** : reçu consentement non personnel borné conservé dans
   `completed_redacted`, rejoué après redémarrage sans second POST ; ré-exécution
   limitée au consentement incertain ; autres V4 toujours bloqués/rédigés ; journal
   validé strictement pour ce champ.
3. **Connecteur `e0d9062`** (P1 de revue) : rejeu V4 par identité métier stable
   (opération + clé d’enveloppe + payload) quand Relay renouvelle
   `session_id`/horodatages ; conflit conservé si payload/clé change ; non-V4
   inchangé ; `requestHash` conservé pour le journal.
4. **Android `fbfa403`** (issu de la qualification physique QA) : une passe de
   suivi sans travail ne réenregistre plus `RUNNING` si l’état précédent est
   `SYNCED`, afin de ne plus dégrader un succès en `NO_PENDING_SOURCE` ; test JVM
   `CoreV4SyncWorkerPresentationTest`. Les erreurs et les premières passes
   continuent d’enregistrer leur état normal.

## 6. Vérifications sur le code intégré `be31d09`

| Vérification | Résultat |
|---|---|
| Android JVM (`:app:testDebugUnitTest --offline --rerun-tasks`) | **896 tests, 0 échec, 0 erreur, 0 ignoré** (117 fichiers de résultats) |
| Android lint (`:app:lintDebug --offline`) | **BUILD SUCCESSFUL** |
| Connecteur Node (`npm run test:unit`) | **17 fichiers, 109 tests PASS** |
| Backend (`pytest -q`, base temporaire) | **348 passed, 1 skipped** |
| `git diff --check` | PASS |
| Build APK debug + AndroidTest (`--rerun-tasks`) | BUILD SUCCESSFUL, 67 tâches |

Les 896 tests incluent le nouveau test de présentation ; 895 tests passaient sur
`be31d09` avant ce correctif.

Régressions vérifiées : annulation/rejeu exact, révocation v3 prioritaire,
idempotence même clé/corps, conflit explicite même clé/corps différent, chat V4
toujours bloqué après redémarrage, remplacement de session, rejeu sans second effet.

## 7. Qualification VM-1 — BLOCKED_ENVIRONMENT

- Code `be31d09` dans `/tmp/atlas-v7-06a-vm` ; APK app
  `59c29bba0f54188a9eea058ecb9809bf2515622d50534d6df6105a7d7a4fb949`, APK AndroidTest
  `df112be47385cae6a6745a169f8635ebb7acc62d758b9a375fc662051494c888`, package
  `dev.atlas.coach` 0.1.0, compileSdk 37, targetSdk 36, certificat debug
  `da3014662eb65e50a87ef364a2e583c331398795b222e3b37b1a16553a00eeee`.
- AVD `atlas_v7_06a_api37`, série `emulator-5580`, API 37.2 / Android 17.
- Stack isolée : Core `127.0.0.1:8110` (DB temporaire), relais épinglé
  `atlas-paseo-relay:r3a` `127.0.0.1:4412`, connecteur neuf `127.0.0.1:4411`,
  jetons synthétiques `0600`, aucun service live.
- Trois boots : cold boot, redémarrage `-no-boot-anim`, puis `-wipe-data` autorisé
  sur l’AVD seul. `sys.boot_completed` n’a jamais valu `1` sous 300 s ; services
  `activity`/`package` intermittents ; logs système filtrés vides. L’AVD avait
  booté le 2026-10-31 (`evidence/V7-06A_AVD_BOOT.md`) : blocage environnemental
  transitoire, pas une preuve d’échec du correctif.
- **Aucun APK installé, aucun pairing poussé, aucune instrumentation, aucun oracle
  Core, aucun VM-1/VM-2. Ni PASS ni FAIL VM.**
- Nettoyage : AVD arrêté, stack arrêtée, conteneur supprimé, jetons/pairing/DB/état
  isolés supprimés ; services live intacts.
- Preuve : `evidence/V7-06A_VM_BLOCKED.md`. Le harnais non exécuté est commité
  (`6dfae8b`) pour une reprise ultérieure ; il ne constitue pas une preuve.

## 7bis. Qualification physique de substitution (QA)

Le propriétaire a branché son téléphone physique et autorisé un débogage. Pour ne
pas toucher son application, ses données, son Keystore ni son rôle HOME, une
variante QA séparée `dev.atlas.coach.qa4` (label `ATLAS QA`) a été installée. Le
test instrumenté strict a été exécuté via `adb reverse` contre la même stack
isolée (Core 8110, relais épinglé 4412, connecteur 4411), avec un appairage
synthétique neuf.

- Appareil : Pixel 9 Pro XL, Android 17 / API 37.1 ; série non publiée.
- Résultat : **PASS strict**, `OK (1 test)`, 3,08 s, 14 étapes ; présentation
  `SYNCED pending=0`, plan v2, DAY_PLAN v2→v3 (REVOKED), ACTIVITY_STATUS v2
  (GRANTED), 75 événements de trace ; oracle Core **PASS** (3 événements, capture
  plan v2/consent v2, aucun doublon, aucune quarantaine).
- Défaut trouvé : une passe de suivi sans travail écrasait `SYNCED` en
  `RUNNING`/`NO_PENDING_SOURCE` (échecs stricts `.qa` et `.qa3`). Correctif
  `fbfa403` + test JVM ; le run strict `.qa4` est PASS.
- État propriétaire : l’app `dev.atlas.coach` n’a pas été installée, remplacée,
  effacée ni lue (`lastUpdateTime` inchangé au 2026-10-31 00:18:13) ; HOME reste
  `dev.atlas.coach/.app.LauncherActivity`. Les paquets QA sont séparés.
- Nettoyage : stack arrêtée, conteneur supprimé, jetons/pairing/DB/état isolés
  supprimés, `adb reverse` retiré. Les paquets QA restent installés en attente
  d’une autorisation de désinstallation distincte.
- Limites : preuve sur paquet QA, **pas** sur l’application produit installée ;
  `GO_PIXEL` reste NO GO ; aucun VM-2, chat réel ou révision de plan.
- Preuves : `evidence/V7-06A_PHYSICAL_QA.md`,
  `evidence/V7-06A_PHYSICAL_QA_TRACE.md`.

## 8. Revue indépendante

- Pré-VM (`d1a7590b…`, lecture seule sur `b04a5ff`) : **`CORRECTION_REQUIRED`**,
  aucun P0, un P1 (rejeu après renouvellement d’enveloppe Relay) et trois P2.
- P1 corrigé et couvert par deux tests rouges→verts (mémoire et redémarrage).
- P2 documentés, non bloquants : validation stricte du reçu consentement et
  corrélation `request_id` ; accumulation possible de travaux immédiats
  `APPEND_OR_REPLACE` ; risque pré-existant d’inversion `consentGate` ↔ verrou
  WebSocket (non reproduit) et verrou de stockage pendant l’ouverture réseau en
  mode DIRECT.
- Revue finale (lecture seule, `a3ebc10`, code `be31d09`) : **`ACCEPTED`**, aucun
  P0/P1 ouvert. P1 clos ; preuve VM bloquée et verdicts jugés cohérents. Le
  relecteur n'a pas relancé les suites et n'a exécuté aucun test VM/Pixel.
- Revue ciblée du delta physique (`b918755`) : **`ACCEPTED`**, aucun P0/P1. P2
  non bloquants : après `SYNCED`, un nouveau consentement peut laisser ce code
  visible pendant une passe réelle ; les résumés doivent dire « aucun test
  physique de l'application produit installée », pas « aucun test physique ».

## 9. Verdicts

| Verdict | Résultat | Portée |
|---|---|---|
| `GO_V4_CONSENT_SYNC` | **GO** | physique QA (Pixel API 37.1) + JVM 896/896 + Node 109/109 + backend 348/1 |
| `GO_V4_PLAN_SYNC` | **GO** | reçu plan physique v2, rejeu même clé, suites |
| `GO_V4_RECONCILIATION` | **GO** | révocation physique v3 hors-ligne sans ACK fictif, oracle Core |
| `GO_PHYSICAL_QA_V4_E2E` | **GO** | paquet QA `.qa4` sur téléphone physique ; **pas** l’app produit installée |
| `GO_VM_V4_E2E` | **NO GO — BLOCKED_ENVIRONMENT** | AVD non booté ; l’équivalent physique QA a passé, le gate VM reste non exécuté |
| `GO_REMOTE_CHAT_VM` | **NO GO — NOT_RUN** | VM-2 non lancée, aucun modèle réel |
| `GO_PLAN_REVISION_VM` | **NO GO — NOT_RUN** | VM-2 non lancée |
| `GO_PIXEL` | **NO GO** | aucun test physique de l’**application produit installée** ; le paquet QA séparé a passé |
| `GO_PRODUCT_REMOTE` | **NO GO** | preuve physique produit plan/ACK absente |

Les GO V4 sont adossés à la preuve physique QA et aux suites déterministes ; ils
ne valent ni pour la VM ni pour l’application produit installée. Un succès VM ou QA
ne serait jamais converti en preuve Pixel produit.

## 10. Sécurité et état résiduel live

L’audit lecture seule du 2026-10-31 (`evidence/V7-06A_RESIDUAL_ACCESS_AUDIT.md`)
constate que le Core live conserve `DAY_PLAN` et `ACTIVITY_STATUS` `GRANTED` v2 pour
le `device_ref` du bearer Android, et que les devices connecteur `pixel-v7` et
`r3c-pixel-final` ne sont pas révoqués. Aucune révocation/rotation live n’a été
appliquée : action sensible nécessitant une autorisation ciblée et une sauvegarde.
Les tests VM et la qualification physique QA utilisaient un environnement
entièrement isolé. Le paquet QA séparé n’a pas réutilisé l’appairage, la base, le
token ni les données du Pixel antérieur ; l’application `dev.atlas.coach`, ses
données et son rôle HOME sont restés intacts. Les paquets QA `.qa`, `.qa2`,
`.qa3`, `.qa4` et leurs tests restent installés, en attente d’une autorisation de
désinstallation distincte.

## 11. Limites honnêtes

- Cause racine V7-05 **UNKNOWN** ; le correctif garantit la reprise sûre, pas la
  reconstitution exacte de la fenêtre de perte.
- **Aucune preuve VM** dans cette campagne ; la preuve physique QA est sur un
  paquet séparé, pas sur l’application produit installée. Les GO V4 ne valent ni
  pour la VM ni pour le produit installé.
- VM1-7/VM1-8 (perte de réponse injectée, redémarrage connecteur au milieu d’un
  consentement) ne sont pas revendiqués sur VM ; ils sont couverts par les tests
  Node/JVM.
- P2 : après un état `SYNCED`, un nouveau consentement peut laisser `SYNCED`
  visible pendant la passe réelle (l’indicateur de progression est retardé) ;
  les autres P2 de revue restent ouverts.
- Aucun push/PR/merge distant ; tout est local sur la branche de campagne.

## 12. Prochaines actions

1. Reprendre VM-1 sur un AVD API 37 sain après un boot vérifié
   (`sys.boot_completed=1`, `activity`/`package` stables), avec le harnais et les
   scripts déjà commités, puis VM-2 seulement après VM-1 PASS. Le gate VM reste
   distinct de la preuve physique QA déjà obtenue.
2. Revue finale : faite et `ACCEPTED` sur `a3ebc10` ; delta physique `ACCEPTED`
   sur `b918755`. Conserver la lecture seule pour toute reprise.
3. Si le propriétaire le souhaite, tester l’**application produit installée** sur
   le téléphone physique après sauvegarde app-only et autorisation ciblée de mise
   à jour ; ce test seul pourrait viser `GO_PIXEL`.
4. Demander l’autorisation de désinstaller les paquets QA `.qa`, `.qa2`, `.qa3`,
   `.qa4` et leurs APK de test ; ils ne contiennent que des fixtures synthétiques.
5. Faire autoriser et appliquer la révocation/rotation résiduelle live avec
   sauvegarde, si le propriétaire le décide.
6. Traiter les P2 séparément, avec tests dédiés, sans élargir ce lot minimal.
