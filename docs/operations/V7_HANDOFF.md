# ATLAS V7 — handoff d’orchestration

Date : 2026-10-31  
Branche locale : `feat/atlas-v7-remote-operational`  
Base mission : `b759bdafbf0c67eda1a14ae2cf2f3fac75239e4c`  
Aucun push, PR ou merge distant effectué.

## V7-06A — fiabilisation V4 (2026-11-01)

**Statut global : code corrigé et suites déterministes PASS ; qualification VM
`BLOCKED_ENVIRONMENT` ; aucun test Pixel dans cette campagne.**

- Branche locale : `feat/atlas-v7-06a-v4-reliability`. Base observée :
  `55625299c364e1c71eba5924fcfe933af1852a2f`. Code intégré :
  `be31d097fe0cf6a5bc50688b421bc9eecbbc847e`. Docs/registre : voir
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
- Résultats sur le code intégré : Android **895/895** JVM, lint PASS ; Node
  **109/109** ; backend **348 passed / 1 skipped** ; `git diff --check` PASS.
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
  → revue finale **`ACCEPTED`** sur `a3ebc10`, aucun P0/P1 ; P2 documentés.
- Verdicts : `GO_V4_CONSENT_SYNC`, `GO_V4_PLAN_SYNC`, `GO_V4_RECONCILIATION` =
  **GO déterministe** (pas une preuve VM/Pixel) ; `GO_VM_V4_E2E` = **NO GO —
  BLOCKED_ENVIRONMENT** ; `GO_REMOTE_CHAT_VM` et `GO_PLAN_REVISION_VM` =
  **NO GO — NOT_RUN** ; `GO_PIXEL` et `GO_PRODUCT_REMOTE` = **NO GO**.
- Prochaines actions : réexécuter VM-1 sur un AVD API 37 sain (nouveau boot
  vérifié), puis VM-2 seulement après VM-1 PASS ; une preuve Pixel reste requise
  pour `GO_PIXEL`/`GO_PRODUCT_REMOTE`. Les P2 (validation stricte du reçu,
  accumulation des travaux immédiats, inversion potentielle de verrous) restent
  ouverts. L'audit d'accès résiduel live (`pixel-v7`, `r3c-pixel-final`, bearer
  Android encore GRANTED v2 côté Core) n'a **pas** été remédié : action sensible
  soumise à autorisation ciblée et sauvegarde.
- Interdictions maintenues : aucun ADB/tests instrumentés sur le Pixel physique,
  aucun push/PR/merge distant, aucun service live modifié, un seul opérateur
  ADB/AVD à la fois.

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
