# V7-06A-TRACE-ANDROID — Instrumentation bornée worker/Relay

Date : 2026-10-31
Base : `c1c0c56bd5547bc7503118cd6b5ccaf2c2a306cf`
Commit : `34763f957174afbb4d9a26e6ba8309375fe79136`
Intégration : merge `0fe8155`
Session : `f1e2f879-a304-40ac-8221-b9bc7cecd69b` — `codex/gpt-6-luna`, `xhigh`
Worktree : `/tmp/atlas-v7-06a-trace-android`

## Livré

- `CoreV4Diagnostics` : trace mémoire bornée (256 événements), désactivée par défaut,
  activée explicitement pour diagnostic/tests. Logcat `ATLAS_V4_DIAG` seulement si
  activée.
- Champs bornés : séquence, temps écoulé, ordinal worker, génération d’exécution,
  type immédiat/périodique, `runAttempt`, catégorie, version de consentement, état,
  étape, résultat, résultat d’ACK, durée, génération de session, tag de corrélation
  SHA-256 tronqué (8 hex). Aucun UUID réel, bearer, payload ou donnée personnelle.
- Worker : `PASS_START/END`, consentement `PREPARE/DISPATCH/RESPONSE/ACK_ATTEMPT/
  ACK_RESULT/ERROR`, plan/statut `PREPARE/DISPATCH/RESPONSE/ACK_*`, type de travail
  transmis par `CoreV4SyncScheduler` via `inputData`.
- `acknowledgeConsent` retourne `APPLIED`, `SUPERSEDED` ou `MISSING` ; mêmes écritures
  et mêmes no-op qu’avant.
- Relay : `QUEUED`, `DISPATCHED`, `SOCKET_SEND_ACCEPTED/REJECTED`, `RESPONSE_RECEIVED`,
  `CORRELATED`, `TIMEOUT`, `EXPIRED`, `CANCELLED`, `QUEUE_FULL`, `OFFLINE`,
  `SESSION_CLOSED` (code), `SESSION_REPLACED` ; génération de session monotone.
- Aucun changement de politique de synchronisation, de retry ou d’acquittement.

## Vérifications sur le SHA intégré `0fe8155`

- `ANDROID_HOME=… ./gradlew :app:testDebugUnitTest --offline` : **BUILD SUCCESSFUL**,
  **887 tests, 0 échec, 0 erreur, 0 ignoré** (rapports XML).
- `./gradlew :app:lintDebug --offline` : **BUILD SUCCESSFUL**.
- `git diff --check` : PASS.
- Worktree propre après commit ; aucun ADB/émulateur/Pixel ; aucun réseau.

## Extrait synthétique expurgé

```text
seq=42 elapsedMs=224 workerOrdinal=12 executionGeneration=12 workKind=IMMEDIATE runAttempt=1 category=DAY_PLAN consentVersion=2 state=RESPONSE stage=RESPONSE_RECEIVED result=SUCCESS ackResult=NONE durationMs=6 sessionGeneration=0 correlationTag=11e594f4
seq=43 elapsedMs=225 workerOrdinal=12 executionGeneration=12 workKind=IMMEDIATE runAttempt=1 category=DAY_PLAN consentVersion=2 state=CORRELATED stage=CORRELATED result=CORRELATED ackResult=NONE durationMs=0 sessionGeneration=0 correlationTag=11e594f4
seq=44 elapsedMs=225 workerOrdinal=12 executionGeneration=12 workKind=IMMEDIATE runAttempt=1 category=DAY_PLAN consentVersion=2 state=CORRELATED stage=CONSENT_RESPONSE result=SUCCESS ackResult=NONE durationMs=7 sessionGeneration=0 correlationTag=11e594f4
seq=46 elapsedMs=226 workerOrdinal=12 executionGeneration=12 workKind=IMMEDIATE runAttempt=1 category=DAY_PLAN consentVersion=2 state=ACKNOWLEDGED stage=ACK_RESULT result=APPLIED ackResult=APPLIED durationMs=0 sessionGeneration=0 correlationTag=11e594f4
```

## Limites

- La trace est en mémoire et best-effort ; elle ne prouve pas la livraison réseau.
- `RESPONSE_RECEIVED` peut être enregistré sans contexte worker (thread de réception) ;
  la corrélation inter-couches utilise le tag tronqué et la génération de session.
- Le volume de code d’instrumentation est important (1 187 insertions) mais borné aux
  sept fichiers du scope et sans changement de comportement.
