# ATLAS V7 — handoff d’orchestration

Date : 2026-10-31  
Branche locale : `feat/atlas-v7-remote-operational`  
Base mission : `b759bdafbf0c67eda1a14ae2cf2f3fac75239e4c`  
Aucun push, PR ou merge distant effectué.

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
  l’intégration (`e52a07d`). **Le test n’a pas encore été exécuté sur Pixel.**

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

## Travail immédiat à reprendre

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
- Le test V7-05 a été compilé et relu, mais **pas exécuté** avant ce handoff.
- Les noms d’outils exacts ne sont pas exposés par le contrat V4 ; ils doivent être
  corrélés côté Core/OpenClaw.
- A4-4 (4001→1012) reste non qualifié ; pas de revendication.
- GO_PRODUCT_REMOTE ne peut être déclaré qu’après un V7-05 PASS avec preuve plan/ACK.
