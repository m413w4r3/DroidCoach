# V7-06A-FIX — Correctif minimal et résultats intégrés

Date : 2026-10-31 / 2026-11-01
Base des correctifs : tests rouges intégrés
- Android : `6d749c7` → fix `2004500`
- Connecteur : `6973b5e` → fix `51e3e00`
**SHA intégré : `39746bd258c125736ff50e54e571c723d10a1ecf`** (merge des deux tranches
test+fix sur la branche `feat/atlas-v7-06a-v4-reliability`).

## Correctif Android (`2004500`, 3 fichiers, +209/−72)

- Reprise bornée d’un envoi incertain : une seule tentative supplémentaire pour
  `UNAVAILABLE` (hors `CAPABILITY_UNAVAILABLE`/`CANCELLED`), même corps/clé durable,
  relecture du consentement avant chaque tentative ; `PROTOCOL`/`OFFLINE`/définitifs
  non rejoués. Aucune augmentation de timeout.
- `RelayBackendTransport` : refus d’un `request_id` déjà en file/en vol (le premier
  appelant conserve la réponse, le doublon reçoit un échec typé) ; les frames dont le
  `session_id` diffère de la session courante sont ignorées sans abort ; l’échec
  protocolaire reste pour une frame de la session courante avec identifiant inconnu.
- `PaseoRelayClient` : génération visible et callbacks d’ancienne socket filtrés sous
  verrou.
- Relecture de `syncedVersion` avant envoi ; single-flight de passe avec demande de
  suivi coalescée ; immédiat `APPEND_OR_REPLACE`, périodique `UPDATE`.
- Aucun test modifié.

## Correctif connecteur (`51e3e00`, 2 fichiers, +102/−18)

- Entrée durable `completed_redacted` pour `v4.consent.state.upsert` enrichie d’un
  champ borné `consentReceipt` (reçu Core non personnel, validé strictement).
- Au redémarrage, reconstruction en mémoire du reçu et rejeu sans second POST.
- Ré-exécution autorisée uniquement pour un consentement `uncertain` ou un ancien
  `completed_redacted` sans reçu, même clé/corps/hash métier ; les autres V4 restent
  bloqués et rédigés (chat compris).
- `diagnostics.ts` : une ligne stderr JSON bornée (≤1 024 octets) par événement quand
  `ATLAS_DIAG_TRACE=1`.
- Aucun test modifié.

## Vérifications sur le SHA intégré `39746bd`

| Suite | Résultat |
|---|---|
| Android JVM (`:app:testDebugUnitTest --offline`) | **895 tests, 0 échec, 0 erreur, 0 ignoré** |
| Android lint (`:app:lintDebug --offline`) | **BUILD SUCCESSFUL** |
| Connecteur (`npm run test:unit`) | **106 tests, 17 fichiers, PASS** |
| Backend (`pytest -q`, base temporaire) | **348 passed, 1 skipped** |
| `git diff --check` | PASS |

Tests rouges devenus verts : `lostConsentResponseIsRetriedWithSameBodyAndKey`,
`lostPlanResponseIsRetriedWithSameBodyAndKey`,
`duplicateInFlightRequestIdDoesNotReplaceFirstWaiter`,
`staleSessionResponseDoesNotAbortCurrentSession`,
`consentReceiptSurvivesRestartAndReplays`,
`uncertainConsentCanBeSafelyReexecutedAfterRestart`.
Non-régressions vertes : annulation/rejeu, révocation v3, idempotence, chat V4 toujours
bloqué après redémarrage, remplacement de session.

## Limites

- Aucune qualification Pixel/ADB dans cette tranche ; la VM reste à exécuter.
- Risque résiduel documenté : inversion potentielle `consentGate` ↔ verrou client
  WebSocket pendant `pump()` depuis le thread de réception ; pré-existante, non
  reproduite, non corrigée dans ce lot minimal.
- Le correctif ne prouve pas la fenêtre exacte de perte V7-05 ; il garantit la reprise
  sûre et la récupération après redémarrage.
