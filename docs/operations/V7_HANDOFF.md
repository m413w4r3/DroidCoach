# ATLAS V7 — handoff d’orchestration

Date : 2026-10-31  
Branche locale : `feat/atlas-v7-remote-operational`  
Base mission : `b759bdafbf0c67eda1a14ae2cf2f3fac75239e4c`  
Aucun push, PR ou merge distant effectué.

## V7-07 — Préproduction et pilote quotidien (2026-11-01/02)

**Statut global : PRÉPARATION AVANCÉE — aucun `GO_PRODUCT_INSTALL`, aucun
`GO_PRODUCT_REMOTE`, aucun `GO_DAILY_PILOT`. Le produit physique n'a pas été
touché.**

- Branche locale `feat/atlas-v7-07-preproduction`, base
  `32e7c70c78fbe295f66f67d6be50dc797a3f6e6d` (= `origin/main`, CI `41350216879`
  `success`). Aucun push/PR/merge distant.
- Pré-vol : Pixel non connecté (`adb devices` vide) ; Core live sain,
  `mode=openclaw`, 7 capabilities ; Gateway live actif ; Relay sain sur 4402 ;
  **connecteur PC arrêté**, Funnel `8443→4400` sans listener ; 2 devices actifs
  (`pixel-v7`, `r3c-pixel-final`) et 2 consentements Core `GRANTED v2`.
- Trois audits lecture seule `codex/gpt-6-luna`/`xhigh` :
  - **A1** : 24 SharedPreferences, fichiers plans/mémoire/inbox, WorkManager,
    deux alias Keystore non exportables ; `adb backup` inutilisable ;
    P1 de perte potentielle `atlas_core_v4_sync` si racine invalide.
  - **A2** : plugin natif `atlas_core_tools` activé en parallèle du chemin Core,
    `profile=full`, modèle `openai/gpt-6-luna` non qualifié, `deepseek-chat`
    documenté retiré ; transport distant indisponible ; bearer Android partagé.
  - **A3** : procédure `run-as`/tar, matrice de restaurabilité, rollback,
    export/import intégré nécessaire pour une sauvegarde durable.
- Outillage : `scripts/v7-07/atlas-product-backup.sh` (branche
  `feat/v7-07-backup-tool`, commits `2517097`, `30f1d63`, `2d49705`) ; 17 tests
  hors ligne, deux corrections issues d'essais réels (composant explicite,
  `df` via `run-as`).
- **Essai AVD API 35 jetable, données synthétiques** :
  préflight/backup/verify/restore **PASS** (archive `ec88d1a2…`, 11 fichiers,
  zéro cache) ; HOME ATLAS et retour Nexus **PASS** ; hors ligne **PASS** ;
  update in-place APK historique → candidat **PASS** ; rollback candidat →
  historique **PASS**. Keystore/tokens **NOT_RUN**. Pixel API 37 **NOT_RUN**.
  Preuve : `evidence/V7-07_AVD_BACKUP_RESTORE.md`.
- **Candidat APK** : build propre sur `32e7c70`, JVM **912/912**, lint 0 erreur,
  APK `d32efc7684c131131101f476f9f7ae186933efe863cb561b2ce0039ddc45e992`,
  signature `da301466…eeee`, permissions identiques à l'historique,
  provenance `worktree_clean=true`. Conservé en privé sous
  `~/.local/state/atlas-v7-07/candidate/`. Aucune installation produit.
- **P2 parseur F1** : commit test-only `fae8caa` sur
  `test/v7-07-parser-p2`, 6/6 ciblés, **915/915 JVM**, aucun code produit ni
  contrat modifié.
- Revue indépendante finale et demande d'autorisation ciblée A–E restent à
  faire. Les documents produits sont `V7-07_READINESS_AUDIT.md`,
  `V7-07_BACKUP_AND_ROLLBACK.md`, `V7-07_LIVE_CONFIG_PLAN.md`,
  `V7-07_PRODUCT_APK_CANDIDATE.md`, `V7-07_PILOT_RECIPE.md`,
  `REGISTRY_V7_07.md`.
- **Prochaines actions :** revue indépendante lecture seule du SHA final ;
  soumettre au propriétaire les scopes A (sécurité live), B (Gateway/Core),
  C (APK produit), D (appairage/recette distante), E (pilote quotidien).
  Ne pas installer, révoquer, tourner le bearer ou modifier le Gateway sans
  autorisation ciblée.

## V7-06D — Fiabilité remote et révocation A4-4 (2026-11-01)

**Statut global : deux P1 diagnostiqués et corrigés avec tests discriminants ;
P2 de provenance APK↔SHA fermé par un mécanisme vérifié ; phase physique
complémentaire exécutée sur Pixel : A4-4 **PASS**, provenance installée **GO**,
fiabilité chat **3/5 → NO GO**, F1 corrigé côté harnais et **revalidé
physiquement 5/5 + A4-4 PASS**, revue de preuve `GO_WITH_LIMITATIONS` ;
`GO_CHAT_RELIABILITY_QA` **GO** pour cette campagne ; `GO_CHAT_CONCURRENCY`,
`GO_REVOKED_DEVICE_SERVER`, `GO_A4_4_CLIENT`, `GO_APK_PROVENANCE` ;
`GO_CHAT_RELIABILITY_QA` non accordé ; `GO_PIXEL_PRODUCT`/`GO_PRODUCT_REMOTE` =
NO GO.**

- Branche locale `feat/atlas-v7-06d-remote-hardening`, base
  `9ad90c7a079736ffc5f7bb6a7f0d4ee3eed70580`, aucun push/PR/merge distant.
- Trois audits lecture seule `codex/gpt-6-luna`/`xhigh` : deadlock G↔C du chat
  (cycle complet, timeout inopérant) ; trame pré-auth `DEVICE_REVOKED` émise en
  V1 pour un client V2 + ordre token/révocation ; provenance APK documentaire.
- Correctif chat `df8f8ae` (intégré `813432b`) : pumps de callback hors du
  moniteur client, attente de consent gate bornée, `abort` hors du verrou
  transport, `TIMEOUT_NOT_SENT` distinct, traces bornées ; rouge `f6e8659`
  reproduit sur BASE, JVM intégrée **909/909**.
- Correctif A4-4 `b822192`/`207bea2` (intégrés `d3619d4`/`c1bb1d1`) : protocole
  d'erreur respecté, token vérifié avant révocation, état `REVOKED` collant après
  le close relay 1012, sonde instrumentée renforcée (état + type d'échec).
- Provenance `04960c6`/`4c8b691`/`49ef354` (intégrés `173dd01`/`8d0aa1c`/
  `7b0ae5a`) : manifeste de build embarqué dans les deux APK, gate
  installé↔construit, proof enrichi ; build final `cf82cd0`, APK
  `1e31a643…`/`05dd465b…`, manifeste `8d678af5…`, `worktree_clean=true`.
- Suites : Node **114/114**, proxy **19/19**, harnais **27/27** (après corrections
  P1/P2 de revue), backend **399 passed/1 skipped**, lint/build PASS,
  `git diff --check` PASS.
- Revue indépendante `5f9adb37…` (`codex/gpt-6-luna`/`xhigh`, lecture seule) :
  **CORRECTION_REQUIRED** sur `b163202` — P1 confirmé sur le gate de provenance
  (`installed_apk_sha256` rejeté comme champ inconnu), corrigé `1031a1a` (rouge
  `c599661`, intégrés `7ea8c58`/`69d0db6`), P2 de libellés corrigé. Revue delta
  `c2c2abd5…` sur `0c19a3f` : P1 confirmé corrigé, deux P2 (wrapper permissif,
  test mockant la frontière du vérificateur) et libellés obsolètes, corrigés par
  Z3 `4ebdf5f` (rouge `052ca80`, intégrés `fded03d`/`bfb1965`) ; harnais 27/27.
  Revue finale `19c375fd…` sur `88ac019` (code `bfb1965`) : aucun P0/P1, un P2
  documentaire de libellés corrigé ; confirmation delta `06d30ed` `ACCEPTED`.
- Phase physique `V7-06D-PHYS` (2026-11-01) : Pixel 9 Pro XL API 37.1, paquet QA
  uniquement, stack isolée, autorisation ciblée. Sonde A4-4 **PASS**
  (`REVOKED`/`REVOKED`, aucune nouvelle session, `close_code=null`) ; série de 5
  démarrages froids **3 PASS / 2 FAIL**. Les échecs #2/#3 sont des réponses à
  zéro proposition après rejet de l'outil (F1), pas la deadlock G↔C. Provenance
  des APK QA installés **vérifiée** (`VERIFIED`, hashes installés = construits).
  QA désinstallés, reverse retiré, HOME/produit inchangés, aucun service live.
- Correctif F1 (2026-11-01, `fix/v7-06d-f1-fix`) : diagnostic `F1-DIAG`
  `ACCEPTED (PARTIAL)` ; rouge `725f9e3` (oracle zéro proposition + parité
  prompt), vert `0749037` ; prompt aligné sur `changes`/`justification` sans
  `idempotency_key` backend-owned, parseur émettant `PROPOSAL_MISSING`. JVM
  **912/912**, backend **374 passed/1 skipped**, Node **114/114**, proxy
  **19/19**, harnais **27/27**, lint/build/AndroidTest PASS. Revue indépendante
  `b143ca97…` : **GO_WITH_LIMITATIONS**, aucun P0/P1 code ; P1 `EVIDENCE_GAP`
  physique, P2 couverture du parseur. Aucun code produit modifié.
- Revalidation physique F1 (autorisation ciblée « F1 + A4-4 », 2026-11-01) :
  **5/5 démarrages froids PASS**, zéro échec F1, chaque proposition/ACK
  `APPLIED`, `tool_state=completed`, exactement une proposition ; **sonde A4-4
  PASS** (`REVOKED`/`REVOKED`, `close_code=null`, aucune nouvelle session) ;
  provenance installée vérifiée (`source_sha=9149c47`, delta docs-only depuis
  `0749037`, hashes installés = construits, `runtime_sdk=37`). Nettoyage final
  propre après correction manuelle d'une boucle de nettoyage automate
  incomplète ; aucun paquet QA, reverse ni stack restant. Preuve :
  `evidence/V7-06D_F1_PHYSICAL_QA.md`. Revue indépendante de la preuve
  `ee44d72e…` : **GO_WITH_LIMITATIONS**, aucun P0/P1 ; P2 de chemin public
  corrigé, P2 de couverture du parseur ouvert. `GO_CHAT_RELIABILITY_QA` **GO**
  pour la campagne QA F1.
- Intégration locale : lignée F1 (ticket, diagnostic, correctif, preuves
  physiques, revues) fast-forwardée sur `main` à `0588be0` le 2026-11-01 ;
  aucun push/PR/merge distant.
- Contrôles live strictement en lecture seule. Identités résiduelles inchangées :
  `pixel-v7` et `r3c-pixel-final` non révoqués, consentements Core `GRANTED v2` ;
  aucune révocation/rotation exécutée.
- Prochaines actions : le P2 de couverture du parseur (`oneOf[0]`, statut non
  terminé) reste ouvert ; aucune nouvelle série physique n'est nécessaire.
  Décisions live séparées (révocation des deux devices, rotation éventuelle du
  bearer Core). Aucun push/merge sans autorisation.
- Preuves : `V7-06D_FINAL_REPORT.md`, `V7-06D_DIAGNOSTIC.md`,
  `REGISTRY_V7_06D.md`, `evidence/V7-06D_CHAT_CONCURRENCY.md`,
  `evidence/V7-06D_REVOCATION_A4_4.md`, `evidence/V7-06D_APK_PROVENANCE.md`,
  `evidence/V7-06D_PHYSICAL_QA.md`.

## V7-06C — Coach Android QA puis transport public (2026-11-01)

**Statut global : APPLY/REJECT/ACK Android QA GO ; harnais, chat, boucle QA et WSS
public GO_WITH_LIMITATIONS ; revue indépendante ACCEPTED_WITH_LIMITATIONS sans P0 ;
deux P1 ouverts (blocage intermittent avant chat, observabilité de révocation A4-4) ;
application produit non testée (`GO_PIXEL_PRODUCT`/`GO_PRODUCT_REMOTE` = NO GO).**

- Branche locale : `feat/atlas-v7-06c-android-coach-loop`, base
  `3e0d1f4c9ca591f903a894be87183395d30fee0b`, aucun push/PR/merge distant.
- Harnais reproductible : `64020d7`, correctif attestation `f9017f4`, port Relay
  épinglé `d0f7d48`, intégrés jusqu’à `3b9b3d1` ; 17/17 tests, backend positif et
  négatif PASS avec DeepSeek réel isolé (`GO_E2E_HARNESS_REPRODUCIBLE`).
- Test Android QA : rejeu ACK exact `69feb5c` (intégré `f7e6441`) et sonde de
  reconnexion révoquée `f1b6b30` (intégré `fa960d5`).
- P2 HTTP 422 : `02be9c1`, intégré `b33d43b`, test rouge→vert, backend
  **372 passed / 1 skipped**.
- Exécutions physiques (Pixel 9 Pro XL, Android 17 / API 37, paquets QA séparés) :
  APPLY local 12 étapes/19,387 s (plan v2→v3, ACK APPLIED v3) ; REJECT local
  12 étapes/21,214 s (plan v2, ACK REJECTED, proof post-hoc corrigé).
- WSS public depuis LTE, sans `adb reverse` : APPLY sur
  `wss://all.taildc7886.ts.net:8443` (proposition `26240af5-…`, ACK `80962380-…`
  APPLIED v3) et REJECT (proposition `9adb680d-…`, ACK `6122332c-…` REJECTED),
  relais isolé sur `127.0.0.1:4400`, TLS/Noise/auth, Core/Gateway loopback.
- P1 fiabilité : premier APPLY public bloqué après les syncs, avant le chat ;
  relance PASS. Cause racine non confirmée (inversion `consentGate`/WebSocket
  suspectée). P1 A4-4 : device QA révoqué, le connecteur refuse (aucune nouvelle
  session, `errors_total=1`) mais l’app observe `ERROR`/`close_code=null` au lieu de
  `REVOKED`/4001 ; la sonde instrumentée est **FAIL**.
- Nettoyage : 10 paquets QA V7-06C + 8 anciens `qa/qa2/qa3/qa4` désinstallés après
  autorisation ; produit, HOME, Keystore et données personnelles intacts. Wi‑Fi
  restauré ; Funnel revenu à son état initial (cible 4400 sans listener) ; relais
  live 4402 et Core/Gateway live non modifiés.
- Identités live résiduelles : `pixel-v7` et `r3c-pixel-final` non révoqués,
  consentements `DAY_PLAN`/`ACTIVITY_STATUS` `GRANTED v2` pour `android-9a3f0f…` ;
  aucune révocation/rotation exécutée sans autorisation ciblée.
- Revue indépendante lecture seule `d4a996c5-d07a-45d2-ad7e-9c837159c60b`
  (`codex/gpt-6-luna/xhigh`, SHA `8cab6fc`) : **aucun P0**,
  `ACCEPTED_WITH_LIMITATIONS`, P2 pull corrigé `20445bf`, P2 traçabilité APK↔SHA
  suivi ; verdicts `GO_WITH_LIMITATIONS` pour le harnais, le chat, la boucle QA et
  le WSS public QA, `GO` pour APPLY/REJECT/ACK, `NO GO` pour le produit.
- Preuves : `docs/operations/V7-06C_FINAL_REPORT.md`,
  `REGISTRY_V7_06C.md`, `evidence/V7-06C_ANDROID_APPLY.md`,
  `evidence/V7-06C_ANDROID_REJECT.md`, `evidence/V7-06C_PUBLIC_WSS.md`,
  `evidence/V7-06C_SECURITY_REVIEW.md`.
- Prochaines actions : diagnostic/correctif du blocage intermittent, correction de
  l’observabilité A4-4, ajout de la provenance APK↔SHA aux proofs, puis
  qualification de l’application produit et du fil rouge produit distant si
  autorisés ; révocation ciblée des identités live seulement sur accord explicite.

## V7-06A — fiabilisation V4 (2026-11-01)

**Statut global : code corrigé, suites déterministes PASS, qualification physique
QA PASS ; qualification VM `BLOCKED_ENVIRONMENT` ; aucun test de l'application
produit installée du propriétaire.**

- Branche locale : `feat/atlas-v7-06a-v4-reliability`. Base observée :
  `55625299c364e1c71eba5924fcfe933af1852a2f`. Code intégré :
  `be31d097fe0cf6a5bc50688b421bc9eecbbc847e` + correctif présentation
  `fbfa403ac0c28ad32a9c5995b31ebd36edc7924f`. Docs/registre : voir
  `REGISTRY_V7_06A.md` et `evidence/V7-06A_FINAL_REPORT.md`.
- Cause racine exacte de V7-05 : **UNKNOWN** ; les audits A1/A2/A3 établissent un
  mécanisme de perte/non-reprise (réponse envoyée mais non reçue classée
  `UNAVAILABLE`, arrêt de la passe, reçu non rejoué ; connecteur
  `completed_redacted` bloquant après redémarrage). Ne pas présenter ce mécanisme
  comme la cause certaine.
- Correctifs bornés intégrés :
  - Android `2004500` : reprise unique `UNAVAILABLE` même corps/clé, relecture
    `syncedVersion`, refus de collision `request_id`, frames d'ancienne session
    ignorées, single-flight + `APPEND_OR_REPLACE`/`UPDATE`.
  - Connecteur `51e3e00` : reçu consentement borné conservé après redémarrage,
    ré-exécution limitée au consentement incertain, autres V4 toujours rédigés.
  - Connecteur `e0d9062` (P1 revue) : rejeu V4 par identité métier stable quand
    Relay renouvelle `session_id`/horodatages ; conflit conservé si payload/clé
    change ; non-V4 inchangé.
  - Android `fbfa403` (issu du débogage physique) : une passe de suivi sans
    travail ne dégrade plus `SYNCED` en `RUNNING`/`NO_PENDING_SOURCE` ; test JVM
    ajouté.
- Résultats sur le code intégré : Android **896/896** JVM (895 avant le correctif
  de présentation), lint PASS ; Node **109/109** ; backend
  **348 passed / 1 skipped** ; `git diff --check` PASS.
- **Qualification physique QA : PASS** sur Pixel 9 Pro XL / Android 17 / API 37.1,
  paquet séparé `dev.atlas.coach.qa4` (app propriétaire, données et HOME intacts) :
  test strict `OK (1 test)`, 14 étapes, `SYNCED pending=0`, plan v2, DAY_PLAN
  v2→v3, ACTIVITY_STATUS v2, oracle Core PASS. Preuve :
  `evidence/V7-06A_PHYSICAL_QA.md` et `V7-06A_PHYSICAL_QA_TRACE.md`.
- Tests rouges reproduits puis verts : perte consentement/plan, collision
  `request_id`, session obsolète, reçu après redémarrage, consentement incertain,
  enveloppe Relay renouvelée (mémoire et redémarrage). P2 documentés.
- **VM-1 : non exécuté.** AVD `atlas_v7_06a_api37` / `emulator-5580` API 37.2 :
  trois boots (cold, `-no-boot-anim`, `-wipe-data`) sans `sys.boot_completed=1`
  sous 300 s ; services intermittents, logs filtrés vides. Aucun APK installé,
  aucun pairing poussé, aucune instrumentation, aucun oracle Core, aucun VM-2.
  Nettoyage : AVD/stack arrêtés, conteneur supprimé, jetons synthétiques supprimés,
  services live intacts. Preuve : `evidence/V7-06A_VM_BLOCKED.md`.
- Revue indépendante : pré-VM `CORRECTION_REQUIRED` (1 P1) → P1 corrigé et testé
  → revue finale **`ACCEPTED`** sur `a3ebc10`, aucun P0/P1 ; delta physique
  **`ACCEPTED`** sur `b918755` ; P2 documentés.
- Verdicts : `GO_V4_CONSENT_SYNC`, `GO_V4_PLAN_SYNC`, `GO_V4_RECONCILIATION` =
  **GO** (preuve physique QA + suites déterministes) ; `GO_PHYSICAL_QA_V4_E2E` =
  **GO** (paquet QA séparé) ; `GO_VM_V4_E2E` = **NO GO — BLOCKED_ENVIRONMENT** ;
  `GO_REMOTE_CHAT_VM` et `GO_PLAN_REVISION_VM` = **NO GO — NOT_RUN** ;
  `GO_PIXEL` = **NO GO** (aucun test de l'application produit installée) ;
  `GO_PRODUCT_REMOTE` = **NO GO**.
- Prochaines actions : réexécuter VM-1 sur un AVD API 37 sain (nouveau boot
  vérifié), puis VM-2 seulement après VM-1 PASS ; si le propriétaire le souhaite,
  tester l'**application produit installée** après sauvegarde app-only et
  autorisation ciblée, seul chemin vers `GO_PIXEL`/`GO_PRODUCT_REMOTE` ; demander
  l'autorisation de désinstaller les paquets QA `.qa`, `.qa2`, `.qa3`, `.qa4`.
  Les P2 (validation stricte du reçu, accumulation des travaux immédiats,
  inversion potentielle de verrous, indicateur `SYNCED` pendant un nouveau
  consentement) restent ouverts. L'audit d'accès résiduel live (`pixel-v7`,
  `r3c-pixel-final`, bearer Android encore GRANTED v2 côté Core) n'a **pas** été
  remédié : action sensible soumise à autorisation ciblée et sauvegarde.
- Interdictions maintenues : aucun ADB/tests instrumentés sur le Pixel physique,
  aucun push/PR/merge distant, aucun service live modifié, un seul opérateur
  ADB/AVD à la fois. *(Section historique V7-06B ; la phase V7-06D-PHYS du
  2026-11-01 a depuis utilisé le Pixel avec une autorisation ciblée, paquet QA
  uniquement, documentée dans `evidence/V7-06D_PHYSICAL_QA.md`.)*

## Où en est la campagne

### Acquis et intégrés localement

- **V7-01C** (erreurs d’outil bornées) : mergé et revu.
- **V7-01E** (health mode) : corrigé, revu, mergé ; backend 346 passed / 1 skipped.
- **V7-02A** (connecteur V4) : mergé.
- **V7-02B** (clés `relay.env` V4) : commit `e86cb70`, mergé `efe652f` ; build PASS,
  96 tests Node PASS ; redémarrage par `scripts/start.sh` vérifié.
- **V7-03B/C/D** (Android relayé V4, request IDs, erreurs HTTP) : revus, mergés
  `c6d93f5` ; suite JVM complète **883 PASS**, lint PASS, backend 346 PASS.
- **V7-04** (seed plan synthétique) : revu ACCEPT, mergé ; exécution Pixel **PASS**
  `OK (1 test)`, plan `b0902f1b-8404-4ed7-a614-96aeb93a6bb1` version 2, 1 activité.
- **V7-05** (scénario V4 réel Pixel) : revu ACCEPT au commit `6de83c8`, mergé dans
  l’intégration (`e52a07d`). **Exécuté une seule fois le 2026-10-31 sur `fdaef7b` :
  FAIL** à la première synchronisation V4 (voir « Mise à jour post-handoff » ci-dessous).

### État live (à ne pas perturber sans autorisation)

- APK app final installé sur Pixel 9 Pro XL, API 37, arm64 :
  SHA-256 `0d0ce524f1166690f5399ea7197ab4eb3b39bfa93604d3feb98613d75bc57ca4`,
  signataire `da301466…eeee`, package `dev.atlas.coach` 0.1.0.
- Backup app-only vérifié : `~/.local/state/atlas-pixel/backups/20261030-pre-v7`,
  archive `388f2250…`, 35 entrées, répertoire `0700`.
- R3B UI chat réel sur Pixel : **PASS** `OK (1 test)`, relay public
  `wss://all.taildc7886.ts.net:8443`, token temporaire supprimé ; configuration
  relay/agent d’essai laissée active.
- Seed Pixel : **PASS**, plan `b0902f1b…` v2.
- Core live : image `00b88ef…`, `mode=openclaw`, sept capabilities, healthy.
- Relais public : `/health` et `/ready` 200 ; `r3b-public-e2e.mjs` PASS.
- Connecteur redémarré par chemin documenté : wrapper PID `1731948`, enfant
  `1731971`, `relay_connected=true` ; état sauvegardé dans
  `~/.local/state/atlas-relay/backups/v7-02b-pre-restart-20261031T000156Z`.
- APK test final pour V7-05 : SHA-256
  `94f24d4917e6fe01a91e60f43cbc4b1b10019e193df1cb6191ec345051b53250`,
  installé sur le Pixel ; il contient `V7PlanSeedInstrumentedTest` et
  `V7RemoteV4InstrumentedTest`.

## Mise à jour post-handoff — V7-05 exécuté (2026-10-31)

Le test V7-05 a été exécuté **une seule fois** sur le Pixel avec `-e test_action apply`,
sur `fdaef7b`. Résultat : **FAIL**. Processus `0`, mais runner `FAILURES!!!`,
`numtests=1`, échec à 61,6 s pendant l’attente du reçu V4 initial :

```text
v4_code=UNAVAILABLE; day_freshness=Aucun reçu; day_consent=GRANTED:2/2;
status_consent=GRANTED:0/2; plan_receipt=null; plan_version=2
```

Aucun chat, proposition, application `v2→v3` ou ACK ; plan resté v2 ; tag
`ATLAS_V7_V4_E2E` vide. Core a reçu exactement deux `POST /v4/sync/consent-state` 200
et le connecteur a journalisé les deux consentements `completed_redacted` : une
capability manquante et un rejet Core sont écartés par les faits ; le défaut se situe
sur le retour/ACK Android (hypothèse dominante, **cause racine non établie**).
**Ne pas relancer** : budget de corrections épuisé ; le diagnostic indépendant
read-only a été effectué et conclut `cause racine UNKNOWN`. Toute correction reste
à autoriser. Preuve :
`docs/operations/evidence/V7-05_PIXEL_V4_E2E_FAIL.md`.

État résiduel relevé read-only (API 37) : DAY_PLAN `REVOKED` v3 avec
`synced_version=2` ; ACTIVITY_STATUS `REVOKED` v3 avec `synced_version=0` ; plan
toujours `APPROVED` v2 (1 activité) ; inbox V4 absente ; code de présentation
`UNAVAILABLE` ; aucun processus d’instrumentation. Le cleanup a donc révoqué
localement mais **les révocations ne sont pas synchronisées** : le Core peut encore
détenir DAY_PLAN `GRANTED` v2. Toute prochaine exécution autorisée doit d’abord
synchroniser ces révocations ; aucune synchronisation n’a été tentée après le run.
Si le Pixel physique est un jour reconnecté, synchroniser ces révocations avant toute
autre action ; les tests VM doivent repartir d’un état/appairage propres.

**Décision propriétaire (2026-10-31) :** le Pixel physique est rendu ; **tous les
prochains tests devront se faire via VM/AVD**. Aucune nouvelle exécution
ADB/instrumentée sur le Pixel physique n’est autorisée. Un client ADB suspendu hérité
de la copie du token R3B a été terminé côté hôte ; l’état du fichier token sur
l’appareil n’a pas été revérifié.

## Travail immédiat à reprendre

> **Superseded** : cette section décrit la préparation d’avant exécution. Le test a
> déjà été lancé une fois le 2026-10-31 et a échoué ; **ne pas le relancer**. La
> commande est conservée pour historique.

Le test V7-05 doit être lancé **une seule fois** avec l’action explicite :

```sh
timeout 600s adb -d shell am instrument -w -r \
  -e class dev.atlas.coach.e2e.V7RemoteV4InstrumentedTest \
  -e test_action apply \
  dev.atlas.coach.test/androidx.test.runner.AndroidJUnitRunner
```

Il attend le pairing `pixel-v7` persistant, accorde DAY_PLAN/ACTIVITY_STATUS, synchronise,
envoie un chat V4 réel (tools `atlas_get_day_plan` + `atlas_propose_plan_revision`),
attend une nouvelle proposition A0, applique (`v2→v3`, +10 min), ACK durable, puis
révoque/synchronise les consentements et annule ses workers.

Après exécution :
1. Récupérer le compteur runner (`OK/FAIL`, nombre de tests) et les lignes non sensibles
   `ATLAS_V7_V4_E2E` via `adb logcat -d -s ATLAS_V7_V4_E2E`.
2. Vérifier que le plan local est version 3 et que le déplacement est observé ; vérifier
   les consentements `REVOKED` avec `syncedVersion == version` et l’inbox sans ACK
   pending. Le test doit le faire lui-même et échouer sinon.
3. Si PASS : compléter `docs/operations/evidence/` avec les IDs/hash/versions, puis
   mettre à jour `REGISTRY_V7.md` et les verdicts. Corréler les noms d’outils exacts
   dans les logs Core/OpenClaw (le test ne les déduit pas du texte).
4. Si FAIL : ne pas relancer en boucle ; lire l’assertion et les logs filtrés, ajuster
   uniquement si nécessaire (budget de correction du ticket respecté), ou rapporter
   `BLOCKED`/`NOT_RUN`.

## Autorisations et garde-fous

- Le propriétaire a autorisé les scopes A–D (OpenClaw config/restart, Core live,
  relais/connecteur, Pixel Android 17). **Aucun push/PR/merge distant** n’est autorisé.
- Une notification de fin n’est pas une validation : relire les diffs, tests et preuves.
- Un seul opérateur ADB/Pixel/AVD à la fois : l’agent Pixel
  `01eace63-b56f-4ce1-beb3-e532a46adb05` est l’opérateur courant.
- Relecteur indépendant : `b5ea9317-8704-4e64-9b41-972a2cfb8428` (read-only).
- Auteur tests : `6d75f9ec-9f3f-4713-adce-95bbb22e2abf`.
- Ne pas désinstaller, `pm clear`, redémarrer Core/Gateway/relais, ni toucher aux
  secrets sans accord ciblé. Le token Pixel a été supprimé ; le backup reste privé.
- La configuration relay/agent d’essai est restée active après R3B ; toute restauration
  nécessite un accord distinct.

## Limites honnêtes

- R3B est un chat **V2** relayé ; V7-05 est le premier test V4 réel.
- Le test V7-05 a été compilé et relu ; il n’était **pas exécuté** avant ce handoff.
  Il l’a été ensuite, une seule fois, et a échoué (voir « Mise à jour post-handoff »).
- Les noms d’outils exacts ne sont pas exposés par le contrat V4 ; ils doivent être
  corrélés côté Core/OpenClaw.
- A4-4 (4001→1012) reste non qualifié ; pas de revendication.
- GO_PRODUCT_REMOTE ne peut être déclaré qu’après un V7-05 PASS avec preuve plan/ACK.
