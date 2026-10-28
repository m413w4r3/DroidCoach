# ATLAS V6.3 — R3-C — Preuves

Chaque preuve indique : SHA source, SHA APK si applicable, environnement,
commande, horodatage, résultat brut ou résumé vérifiable, résultat attendu,
résultat observé, statut, limites. Les horodatages sont locaux
(`Europe/Paris`, +01:00) sauf mention UTC.

## P-BASE-01 — Git local et distant

| Champ | Valeur |
|---|---|
| SHA source | `eedf91502227297be5d2ff35524e79983ea36f49` |
| Environnement | PC hôte, dépôt principal |
| Commande | `git rev-parse HEAD` ; `git ls-remote --heads origin main` ; `git merge-base --is-ancestor <sha> origin/main` |
| Horodatage | 2026-10-28T16:52+01:00 |
| Attendu | `main` local = distant = base annoncée ; R3-B ancêtre |
| Observé | local et distant `eedf915…` ; `5795d37`, `738ef93`, `4be991d` ancêtres : OK |
| Statut | **PASS** |
| Limites | La mention « aucun push » du rapport R3-B est contredite par `origin/main` ; réconciliée sans réécriture |

## P-BASE-02 — CI sur `eedf915`

| Champ | Valeur |
|---|---|
| SHA source | `eedf91502227297be5d2ff35524e79983ea36f49` |
| Environnement | GitHub Actions, workflow `CI` |
| Commande | `gh run list --limit 10 --json …` |
| Horodatage | créé `2026-10-28T15:44:22Z`, terminé `2026-10-28T15:49:40Z` |
| Attendu | résultat réel connu avant toute modification |
| Observé | run `40718987949`, `status=completed`, `conclusion=success` |
| Statut | **PASS** |
| Limites | L'information « in_progress » du brief était périmée ; aucun succès n'est déclaré sans workflow terminé |

## P-BASE-03 — Artefacts APK et sauvegardes

| Champ | Valeur |
|---|---|
| Environnement | PC hôte, artefacts R3-B |
| Commande | `sha256sum android/app/build/outputs/apk/…` ; `apksigner verify --print-certs` ; `aapt2 dump badging` ; `sha256sum ~/atlas-backups/r3b/…` |
| Horodatage | 2026-10-28T17:05+01:00 |
| Attendu | Identifier sans ambiguïté le binaire et les sauvegardes |
| Observé | APK principal `057560940cf078bb90b19bb32a8d288fe3f294c8d2cbf5f256ada5e907033e8c` ; androidTest `5aef13210dc2760fb03fbf757f58b040a2cb2e20838e444427cec211dab70f04` ; signature debug `da3014662eb65e50a87ef364a2e583c331398795b222e3b37b1a16553a00eeee` ; `versionCode=1`, `versionName=0.1.0`, API 37 ; sauvegarde app-only `fbca987ad521f99bdc4c626b35367a1a9de674da12fdfc4fcf42c5417696aabd` (0600) ; APK rollback `66645143b0e6d861b90bbad4acfcc8fe85cae5c939e1d394e3e81c9ecad518b5` (0600) |
| Statut | **PASS (inventaire)** |
| Limites | Ces APK sont ceux de R3-B. **Aucun n'est une preuve du code R3-C.** Un nouveau couple APK sera construit depuis le SHA R3-C intégré |

## P-BASE-04 — Environnement

| Champ | Valeur |
|---|---|
| Environnement | PC hôte |
| Commande | `node --version`, `java -version`, `python3 --version`, `adb devices -l`, `tailscale version` |
| Horodatage | 2026-10-28T16:52+01:00 |
| Observé | Node v24.18.0 ; OpenJDK 21.0.12.1 ; Python 3.12.3 ; **aucun appareil ADB** ; Tailscale 1.104.4 |
| Statut | **PASS (inventaire)** |
| Limites | Le Pixel `45301FDAS001QT` est déconnecté ; toute recette Pixel est `BLOCKED_DEVICE` jusqu'à reconnexion et mandat |

## P-FUNNEL-01 — Diagnostic non destructif

| Champ | Valeur |
|---|---|
| SHA source | `eedf915…` + services de test R3-C |
| Environnement | PC hôte, `tailscaled` actif |
| Commande | `tailscale status` ; `tailscale funnel status --json` ; `tailscale netcheck` ; `systemctl is-active tailscaled` |
| Horodatage | 2026-10-28T16:52+01:00 |
| Attendu | Distinguer panne réseau, backend arrêté, proxy/relais/connecteur, TLS |
| Observé | `Self.Online=true`, `Health=[]` ; Funnel `8443 → 127.0.0.1:4400`, `AllowFunnel=true` ; netcheck UDP OK, IPv4 publique `88.160.125.113:20588`, DERP Francfort ; `tailscaled` actif |
| Statut | **PASS (diagnostic)** |
| Limites | Le plan de contrôle était injoignable en R3-B ; il est de nouveau joignable. Aucune configuration Tailscale modifiée |

## P-FUNNEL-02 — Chemin local rétabli

| Champ | Valeur |
|---|---|
| Environnement | services de test isolés |
| Commande | relais `atlas-paseo-relay:r3a` sur `127.0.0.1:4402` ; proxy `4400→4402` ; FastAPI `8100` ; connecteur |
| Horodatage | 2026-10-28T16:56+01:00 |
| Observé | `4402/health` 200 ; `4400/health` 200 ; `4400/ready` 200 ; `4400/metrics` 404 ; `8100/health` 200 ; `4401/metrics` `relay_connected=true` |
| Statut | **PASS** |
| Limites | Services isolés, adaptateur FastAPI `mock` ; aucune installation persistante |

## P-FUNNEL-03 — TLS et HTTP publics avec DNS indépendant

| Champ | Valeur |
|---|---|
| Environnement | PC, résolution DoH `1.1.1.1`, IP publique `176.58.90.46` |
| Commande | DoH A/AAAA ; `openssl s_client -connect 176.58.90.46:8443 -servername all.taildc7886.ts.net` ; `curl --resolve all.taildc7886.ts.net:8443:176.58.90.46 …` |
| Horodatage | 2026-10-28T16:57+01:00 |
| Attendu | TLS public valide, `/health` 200, `/ready` 200, `/metrics` 404 |
| Observé | A `176.58.90.46/.63/.145`, AAAA `2a00:dd80:3e::47e/…` ; TLSv1.3, `CN=all.taildc7886.ts.net`, `Verification: OK` ; `health_http=200 tls_verify=0`, `ready_http=200`, `metrics_http=404` |
| Statut | **PASS** |
| Limites | Connexion initiée par le PC via l'IP publique, sans MagicDNS ni `100.119.62.63` ; ce n'est pas encore une preuve depuis le réseau mobile Pixel |

## P-FUNNEL-04 — WSS public, paramètres invalides, absence d'exposition

| Champ | Valeur |
|---|---|
| Environnement | PC, IP publique du Funnel |
| Commande | `curl --http1.1 -H 'Connection: Upgrade' … https://all.taildc7886.ts.net:8443/ws?…` ; `curl … /metrics?…` ; `ss -ltnp` |
| Horodatage | 2026-10-28T17:05+01:00 |
| Attendu | `/ws` valide 101 ; `v=1`, query inconnue, `serverId` absent refusés 400 ; `/metrics` 404 ; ports internes non publics |
| Observé | `/ws?serverId=…&role=client&v=2` → `HTTP/1.1 101 Switching Protocols` ; `v=1` → 400 ; `serverId` absent → 400 ; query inconnue → 400 ; upgrade `/metrics` → 404 ; listeners `4400/4401/4402/8100` sur `127.0.0.1` uniquement ; `8443` sur `tailscale0` |
| Statut | **PASS** |
| Limites | Le 101 prouve l'upgrade et le filtrage ; la couche Noise/auth est prouvée par P-FUNNEL-05 |

## P-FUNNEL-05 — E2E public complet (Noise, auth, chat)

| Champ | Valeur |
|---|---|
| SHA source | `eedf915…` (code R3-B) + services de test |
| Environnement | `wss://all.taildc7886.ts.net:8443` via IP publique `176.58.90.46`, appareil synthétique `49f67626-…` |
| Commande | `ATLAS_E2E_PUBLIC_IP=176.58.90.46 node scripts/r3b-public-e2e.mjs` |
| Horodatage | 2026-10-28T16:59+01:00 |
| Attendu | Noise `protected`, auth appareil, `backend.health`, `sync.status`, chat relayé, rejeu, idempotence, limite payload |
| Observé | **7/7 PASS** : `noise_protected_and_device_auth`, `backend_health_via_funnel` (`ok`), `sync_status_via_funnel` (`relay_connected=true`), `coach_chat_relayed` (`agent_status=mock`, 2 propositions), `coach_chat_replay_no_duplicate` (`replayed=true`), `coach_chat_idempotency_conflict` (`IDEMPOTENCY_CONFLICT`), `coach_chat_payload_limit` (`INVALID_REQUEST`) |
| Statut | **PASS (PC, ingress public)** |
| Limites | Adaptateur `mock`, pas OpenClaw réel ; connexion initiée par le PC ; pas encore de Pixel sur réseau mobile. Les preuves R3-B de chat OpenClaw réel restent historiques, pas des preuves R3-C |

## P-COMPAT-01 — Ancien appairage sans `coach.chat`

| Champ | Valeur |
|---|---|
| SHA source | `a162f70` (intégré sur `feat/atlas-v6-3-r3c-qualification`) |
| Environnement | JVM Android + Node 24 |
| Commande | `./gradlew :app:testDebugUnitTest --tests 'dev.atlas.coach.sync.relay.*' --max-workers=1` ; `npx vitest run test/r3c-compat.test.ts` |
| Horodatage | 2026-10-28T17:02+01:00 |
| Attendu | Session `RELAY_CONNECTED`, plan.revision utilisable, chat `OPERATION_UNAVAILABLE`, aucun close, aucune élévation |
| Observé | Gradle `BUILD SUCCESSFUL` ; Vitest `1 test passed` ; tests Android `RelayCapabilityNegotiationTest` 4 cas PASS |
| Statut | **PASS** |
| Limites | Node test utilise le canal chiffré simulé et un backend factice ; l'E2E public réel est P-FUNNEL-05 |

## P-RACE-01 — Courses consentement/révocation

| Champ | Valeur |
|---|---|
| SHA source | `22ba24f` |
| Environnement | JVM Android + Node 24 |
| Commande | `./gradlew :app:testDebugUnitTest --tests '…RelayConcurrencyGateTest' …` ; `npx vitest run test/r3c-race.test.ts` |
| Horodatage | 2026-10-28T17:04+01:00 |
| Attendu | Aucun envoi après révocation, ordre `DEVICE_REVOKED` avant `4001`, aucune réponse tardive acceptée, deux chemins de révocation |
| Observé | Gradle `BUILD SUCCESSFUL` ; Vitest **3/3 PASS** (polling, réception directe, écriture DEVICE_REVOKED bloquée) |
| Statut | **PASS** |
| Limites | Tests déterministes JVM/Node ; la course réelle sur Pixel reste à rejouer en vague C si l'appareil est disponible |

## P-NODE-FULL — Suite connecteur complète avec relais réel

| Champ | Valeur |
|---|---|
| Environnement | relais épinglé `127.0.0.1:4402` |
| Commande | `ATLAS_TEST_RELAY_URL=ws://127.0.0.1:4402 npm test` |
| Horodatage | 2026-10-28T17:03+01:00 |
| Observé | **16 fichiers, 78 tests PASS**, dont `relay.integration.test.ts` 1/1 |
| Statut | **PASS** |
| Limites | Le relais est un conteneur local épinglé ; ce n'est pas le Funnel public |

## P-NET-01 — Classification réseau OFFLINE / ERROR

| Champ | Valeur |
|---|---|
| SHA source | `d2419b3` |
| Environnement | JVM Android, tests déterministes |
| Commande | `./gradlew :app:testDebugUnitTest --tests 'dev.atlas.coach.sync.relay.*' --max-workers=1` |
| Horodatage | 2026-10-28T17:09+01:00 |
| Attendu | réseau → OFFLINE ; TLS/Noise/permission/inconnu → ERROR terminal ; auth → AUTH_REQUIRED ; révocation → REVOKED ; reprise réseau sans ERROR |
| Observé | **BUILD SUCCESSFUL** ; 14 cas `RelayNetworkClassificationTest` PASS, dont mode avion, Wi-Fi, données mobiles, perte Internet, relais injoignable, FastAPI arrêté, certificat TLS invalide, jeton invalide, révocation, close protégé, reprise réseau et échec I/O pendant le handshake |
| Statut | **PASS (JVM)** |
| Limites | La matrice réelle sur Pixel (mode avion, Wi-Fi/LTE, TLS) reste à rejouer en vague C |

## P-GATES-01 — Gates complètes sur le SHA intégré

| Champ | Valeur |
|---|---|
| SHA source | `d2419b3` |
| Environnement | PC hôte, JDK 21, Node 24, Python 3.12 |
| Commande | `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --max-workers=1` ; `backend/.venv/bin/python -m pytest -q` ; `ATLAS_TEST_RELAY_URL=ws://127.0.0.1:4402 npm test` |
| Horodatage | 2026-10-28T17:10+01:00 |
| Observé | Android : **100 suites, 712 tests, 0 échec/erreur/ignoré** ; lint **0 erreur, 71 avertissements** ; build APK principal et androidTest **PASS** ; backend **196 passed, 1 skipped** ; Node **17 fichiers, 81 tests PASS** |
| Statut | **PASS** |
| Limites | Ce SHA n'est pas encore celui du gel final ; les gates seront rejouées après revue et avant le gel APK |

## P-APK-R3C-01 — APK construits depuis le SHA intégré

| Champ | Valeur |
|---|---|
| SHA source | `d2419b3` |
| Environnement | build debug, API 37 |
| Commande | `sha256sum` ; `apksigner verify --print-certs` ; `aapt2 dump badging` |
| Horodatage | 2026-10-28T17:10+01:00 |
| Observé | APK principal `8332afd921f879a50261f8c6a60693ad2637bee7ae3165ee1cb8861e37bf1621` ; APK androidTest `5aef13210dc2760fb03fbf757f58b040a2cb2e20838e444427cec211dab70f04` (sources instrumentation inchangées, artefact byte-identique) ; signature debug `da301466…` ; `versionCode=1`, `versionName=0.1.0` |
| Statut | **PASS (build)** |
| Limites | APK **non installé** sur Pixel ; l'APK androidTest sera reconstruit après `clean` au gel final pour lever toute ambiguïté d'incrémentalité |

## P-HARNESS-01 — Relais isolé reproductible

| Champ | Valeur |
|---|---|
| SHA source | `d2419b3` |
| Environnement | Docker, image `atlas-paseo-relay:r3a`, port loopback `4403` |
| Commande | `ATLAS_TEST_RELAY_PORT=4403 integrations/relay/pc/scripts/run-isolated-relay-tests.sh` |
| Horodatage | 2026-10-28T17:11+01:00 |
| Observé | relais démarré, **17 fichiers / 81 tests PASS**, conteneur `atlas-r3c-test-relay` supprimé |
| Statut | **PASS** |
| Limites | Dépend de Docker et de l'image locale épinglée ; sur un runner sans image, le harnais retourne `BLOCKED` |

## P-PROXY-01 — Suite proxy filtrant

| Champ | Valeur |
|---|---|
| Environnement | relais de test actif sur `4402`, port `4400` libéré |
| Commande | arrêt contrôlé du proxy de qualification ; `node --test integrations/relay/proxy/test` ; redémarrage |
| Horodatage | 2026-10-28T17:11+01:00 |
| Observé | **18 tests, 18 PASS, 0 FAIL** ; proxy redémarré, `local_health=200`, `public_health=200`, connecteur reconnecté |
| Statut | **PASS** |
| Limites | Le premier essai avait échoué en `EADDRINUSE` parce que le proxy de qualification occupait `4400` ; le rejeu avec port libre confirme le comportement |

## P-CI-01 — Durcissement CI (à rejouer sur la branche)

| Champ | Valeur |
|---|---|
| SHA source | `cd6e751` |
| Environnement | GitHub Actions |
| Commande | workflow `CI`, jobs `verify` et `relay-node` |
| Horodatage | préparation 2026-10-28T17:04+01:00 |
| Attendu | Android + backend + Node + proxy |
| Observé | YAML valide ; suite Node locale 81/81 ; proxy local 18/18 ; harnais relais réel 81/81. **Aucun run CI distant déclaré avant exécution terminée** |
| Statut | **NOT_RUN (distant)** |
| Limites | Un push est nécessaire pour déclencher la CI distante ; il exige une autorisation explicite. En attendant, le statut reste `NOT_RUN`, pas `PASS` |
