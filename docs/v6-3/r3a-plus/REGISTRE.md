# ATLAS V6.3 — Campagne R3-A+ — Registre

Statut : **OPEN**. R3-A reste **CLOSED_LOCAL / GO_WITH_LIMITATIONS** ; V6.3 global
reste **NO_GO**. Cette campagne ne modifie aucune conclusion historique R3-A.

## 1. Base et environnement

| Élément | Valeur |
|---|---|
| Date de lancement | 2026-10-27 |
| Branche de campagne | `feat/atlas-v6-3-r3a-plus` |
| Base `main` | `9a05dcab71e2327c58c720d82623882fc4d1cfc1` (CI GitHub PASS) |
| Arbre de travail | propre avant création de branche |
| JDK | OpenJDK 21.0.12.1 |
| Node / npm | v24.18.0 / 11.16.0 |
| Python | 3.12.3, venv backend |
| Docker | 27.1.1 |
| Android cible | **Pixel 9 Pro XL physique**, Android 17 / API 37, build `CP3A.260905.009` |
| Émulateur | **non utilisé** pour cette campagne (décision du propriétaire) |
| Etar | `ws.xsoh.etar` 1.0.57 |
| Provider calendrier | présent ; calendriers personnels préexistants, **aucun calendrier ATLAS** |
| ATLAS installé au pré-vol | `dev.atlas.coach` 0.1.0 debug, `versionCode=1`, APK `6ab95fac…` |
| Sauvegarde app ATLAS | `~/.local/share/atlas-v63-r3a-plus/backups/atlas-app-data-pre-r3a-plus.tar`, SHA-256 `d1ffca6a15c5a371aa0e913858e262b6a16a141bb7234aa81bf829ab390172ed` |
| APK installé sauvegardé | `atlas-installed-pre-r3a-plus.apk`, SHA-256 `6ab95fac9d635ea9f5a4947b6fabc901526b6d953f7f930e04e39fcfbf51c886` |
| Baseline événements | 91 événements personnels, répartis sur les calendriers 2, 4, 5, 6, 10 ; aucun événement ATLAS |
| Paseo | workspace `wks_f0bd4eda1e19e231`, hôte local |
| Paseo Relay | image/commit R3-A `3fc41c96…` conservés |
| Consentement propriétaire | usage du Pixel physique à la place de l'émulateur, explicitement demandé le 2026-10-27 ; actions limitées aux fixtures synthétiques, aucune donnée personnelle modifiée |

Le numéro de série matériel n'est pas reporté dans les preuves versionnées.

## 2. Règles appliquées

- Un seul parent orchestrateur, seul intégrateur et seul opérateur ADB.
- Six enfants actifs maximum, trois implémentations simultanées maximum.
- Tous les enfants : `provider=codex`, `model=gpt-6-luna`, effort `xhigh`,
  vérifié dans les métadonnées de session. Aucune sous-délégation.
- Worktrees et branches distincts, un propriétaire d'écriture par fichier.
- Une notification n'est pas une acceptation : le parent inspecte le diff, rejoue
  l'oracle sur le SHA intégré et met à jour ce registre.
- Aucun push, PR, fusion distante, déploiement public, modification OpenClaw
  personnelle, écriture wger réelle ou action destructive sur le Pixel.
- Toute preuve est liée à un SHA, un environnement, une commande et un statut
  `PASS` / `FAIL` / `PARTIAL` / `BLOCKED` / `NOT_RUN`.
- Aucun secret, token, identifiant matériel, compte personnel ou donnée intime
  dans Git, les logs ou les rapports.

## 3. Vagues et tickets

| TASK_ID | Objectif | Scope d'écriture exclusif | Oracle | Statut |
|---|---|---|---|---|
| `R3AP-PREFLIGHT-01` | pré-vol, branche, docs, sauvegarde Pixel | `docs/v6-3/r3a-plus/**` | pré-vol + backup + baseline | **ACCEPTED (parent)** |
| `R3AP-WSS-01` | politique réseau WSS, validation PC/Android, infra TLS de test | `integrations/relay/pc/src/relay-link.ts`, `integrations/relay/pc/test/relay-url.test.ts`, `android/app/src/main/java/dev/atlas/coach/sync/relay/{RelayConfig,RelayConfigurationValidator,PaseoRelayClient}.kt`, tests JVM de ces fichiers, `android/app/src/debug/**`, `tools/relay-tls-test/**`, `docs/v6-3/r3a-plus/preuves/wss-*` | WSS de test accepté, WS distant refusé, mauvais hostname/CA/identité refusés, FastAPI privé | **ACCEPTED / INTEGRATED** — `b2cbebc`, merge `733cd78` ; Node 40/40, JVM relais 36/36, WSS Android W1–W10 PASS sur Pixel avec l'APK final `66645143…` |
| `R3AP-PACKAGING-01` | packaging reproductible + supervision utilisateur | `integrations/relay/pc/{package.json,package-lock.json}`, `integrations/relay/pc/scripts/**`, `integrations/relay/pc/vendor/**`, `integrations/relay/pc/packaging/**`, `integrations/relay/pc/systemd/**`, `THIRD_PARTY_NOTICES.md`, `docs/v6-3/r3a-plus/{INSTALLATION_PC,SUPERVISION_PC}.md`, `docs/v6-3/r3a-plus/preuves/packaging-*` | installation propre sans clone Paseo, hash vérifié, unité systemd valide, secrets non affichés | **ACCEPTED / INTEGRATED_WITH_PARENT_FIX** — `d8a425d`, merge `722cdde` ; artefact `cc1102a…`, clean install 9/35 PASS, audit runtime 0, systemd statique PASS / transitoire NOT_RUN ; correction parent de la politique WSS du config checker |
| `R3AP-CALENDAR-01` | scénarios C1–C5 réels et tests instrumentés | `android/app/src/main/java/dev/atlas/coach/calendar/**` (correctifs ciblés), `android/app/src/test/java/dev/atlas/coach/calendar/**`, `android/app/src/androidTest/java/dev/atlas/coach/calendar/**`, `docs/v6-3/r3a-plus/preuves/calendar-*` | tests JVM + androidTest compilés ; scénarios exécutables sur Pixel | **ACCEPTED / INTEGRATED** — `e66d2ef`, merge `0f3426a` ; 64 JVM ciblés ; Pixel C1–C5 et nettoyage PASS sur APK `66645143…` |
| `R3AP-RESILIENCE-01` | résilience et idempotence du connecteur | `integrations/relay/pc/src/{connector,session,operations,backend,metrics,protocol}.ts`, `integrations/relay/pc/test/**` hors fichiers B2 | 13 scénarios de résilience, échec fermé, pas de rejeu aveugle | **ACCEPTED / INTEGRATED** — `d19a5e3`, merge `9686c87` ; 26 tests ciblés, 52 tests Node sans variable d'env, SIGKILL réel, rejeu POST bloqué |
| `R3AP-PAIRING-01` | appairage/révocation atomiques | `integrations/relay/pc/src/{main,device-registry,secure-files,file-lock}.ts`, tests correspondants | aucun appareil orphelin autorisé, token jamais sur stdout, concurrence sûre | **ACCEPTED / INTEGRATED** — `afa4615`, merge `51e3405` ; 20 tests ciblés, 48 tests Node, invariant token-livré-avant-autorisation |
| `R3AP-ANDROID-P3-01` | corrections P3 et messages | fichiers UI/usage/relais ciblés, tests correspondants | quota désactivé affiché « Désactivé », erreurs TLS/identité/permission distinctes | **ACCEPTED / INTEGRATED** — `90739a6`, merge `3c6aef1` ; 25 tests ciblés, 666 JVM, lint 0 erreur / 70 avertissements |
| `R3AP-E2E-01` | recette WSS bout en bout sur Pixel | scripts de recette parent, preuves | Android physique → TLS/WSS test → relais réel → Noise → connecteur → FastAPI | **IN_PROGRESS** — W1–W10 PASS ; calendrier C1–C5 et nettoyage PASS ; persistance PASS ; revue indépendante en cours |
| `R3AP-GATES-01` | gates finales non-device déléguées | aucun (lecture/build uniquement) | Node, backend, JVM, lint, builds, installation propre | **ACCEPTED** — session `e38897ac…`, rapport `preuves/gates-agent-run.txt`, tout PASS sur `38fd4a0` |
| `R3AP-REVIEW-01` | revue indépendante lecture seule | aucun | grille P0/P1/P2/P3 | **PENDING** |

## 3.1 Sessions Paseo vérifiées

| TASK_ID | Agent ID | Session Codex | Modèle effectif | Effort effectif | Worktree |
|---|---|---|---|---|---|
| `R3AP-WSS-01` | `7c16a073-23a2-4961-9f80-69e1a9a646ec` | `01a17ee3-6b10-78e2-ab34-7e99988af6e6` | `gpt-6-luna` | `xhigh` | `feat-r3a-plus-wss` |
| `R3AP-PACKAGING-01` | `1c83bff5-cc1a-4528-be27-2d6b478d2486` | `01a17ee3-e1d6-7a71-9e85-a64d1292c1a0` | `gpt-6-luna` | `xhigh` | `feat-r3a-plus-packaging` |
| `R3AP-CALENDAR-01` | `e8b64fe2-9fdd-4128-96cf-62e7a0689cec` | `01a17ee3-eb93-7bf1-bbf1-727b16580b97` | `gpt-6-luna` | `xhigh` | `feat-r3a-plus-calendar` |
| `R3AP-RESILIENCE-01` | `3d0fecbe-a363-424f-a790-35168d9da54b` | `01a17ef8-86af-74d3-881a-a1285f9e9aeb` | `gpt-6-luna` | `xhigh` | `feat-r3a-plus-resilience` |
| `R3AP-PAIRING-01` | `08271ed4-9794-4412-a24b-7b749f97069e` | `01a17ef8-9197-7ef3-940c-d9daba268759` | `gpt-6-luna` | `xhigh` | `feat-r3a-plus-pairing` |
| `R3AP-ANDROID-P3-01` | `11be7a22-36f0-458b-879b-7fcf2dc91c53` | `01a17ef8-9b81-7d61-ab3b-9f46b02822ad` | `gpt-6-luna` | `xhigh` | `feat-r3a-plus-android-p3` |

Les trois sessions ont été créées en mode `full-access`, sans sous-délégation,
avec `BASE_SHA=a514ea172578889f2fdbcaf8bec3d06159063a5c`.

## 4. Journal

| Date | Événement | Résultat |
|---|---|---|
| 2026-10-27 | Pré-vol Git : base `9a05dca`, arbre propre | **PASS** |
| 2026-10-27 | Pré-vol outils : JDK 21, Node 24, Python 3.12, Docker 27 | **PASS** |
| 2026-10-27 | Pixel physique Android 17/API 37 connecté, état `device` | **PASS** |
| 2026-10-27 | Baseline calendrier : 12 calendriers, 91 événements personnels, aucun événement ATLAS | **PASS** |
| 2026-10-27 | Sauvegarde app-only ATLAS + APK installé + hashes ; extraction de contrôle | **PASS** — archive 16 fichiers, temp supprimé |
| 2026-10-27 | Signature APK installé = certificat debug ; APK test même certificat | **PASS** |
| 2026-10-27 | `adb reverse` testé puis retiré ; aucun proxy HTTP global | **PASS** |
| 2026-10-27 | Branche `feat/atlas-v6-3-r3a-plus` créée sur `9a05dca` | **PASS** |
| 2026-10-27 | Contrats R3-A+ figés | **PASS (doc)** |
| 2026-10-27 | `R3AP-WSS-01` livré | `b2cbebc` ; 18 fichiers, aucune clé privée, aucun symlink, aucun binaire ; Node 40/40 avec opt-in loopback, JVM relais 16/16 annoncés, harnais WSS 6/6 |
| 2026-10-27 | Revue parent `R3AP-WSS-01` | scope conforme ; Node 40/40 rejoué, JVM relais 36/36 rejoué, `assembleDebugAndroidTest` PASS, harnais WSS réel 6/6 rejoué sur le merge |
| 2026-10-27 | Intégration WSS | merge `733cd78` ; Android runtime WSS, révocation et redémarrages restent à exécuter en vague C |
| 2026-10-27 | Suivi WSS noté | `relay-reconnect.test.ts` doit passer `allowLoopbackWs: true` pour rester vert sans variable globale (ticket B1) ; `npm ci` signale 1 advisory modérée et 2 critiques préexistantes à revoir |
| 2026-10-27 | `R3AP-PACKAGING-01` livré | `d8a425d` ; tarball vendoré `@getpaseo/relay@0.13.1` SHA-256 `cc1102a…` identique à l'intégrité npm, clean install sans `references/` 9/35 PASS, audit runtime 0, systemd-analyze PASS, README sport corrigé |
| 2026-10-27 | Revue parent packaging | scope conforme, aucun secret/symlink, artefact binaire intentionnel et hashé ; défaut détecté : config checker et INSTALLATION_PC encore loopback-only |
| 2026-10-27 | Correction parent packaging | `config.mjs`, test `config-policy.check.mjs`, `verify-install.sh`, README et INSTALLATION_PC alignés sur la politique WSS ; 7/7 tests PASS ; smoke WSS remote accepté et ws remote refusé |
| 2026-10-27 | Intégration packaging | merge `722cdde` ; suite Node complète avec dépendance vendorée à rejouer, systemd transitoire NON_EXÉCUTÉ (PARTIAL) |
| 2026-10-27 | Rejeu Node packaging | `npm ci` + `verify:artifact` + config policy 7/7 + `npm test` 10 fichiers / 40 tests PASS sur le merge ; tarball `cc1102a…` |
| 2026-10-27 | Installation propre finale | copie hors dépôt sans `references/` sur `89f881e` : `npm ci --offline` PASS, build, config 7/7, artefact, 40/40 tests PASS |
| 2026-10-27 | Test WSS Android préparé | `RelayWssE2eInstrumentedTest` (DNS debug injecté, CA test, mauvais hostname/CA/Noise, interruption TLS, FastAPI privé) compile ; exécution Pixel PENDING vague C |
| 2026-10-27 | `R3AP-RESILIENCE-01` livré | `d19a5e3` ; 14 fichiers, 26 ciblés / 52 Node sans env, SIGKILL réel, journal POST, registre fail-closed |
| 2026-10-27 | `R3AP-PAIRING-01` livré | `afa4615` ; 9 fichiers, 20 ciblés / 48 Node, token livré avant autorisation, compensation, arrivée tardive déterministe |
| 2026-10-27 | `R3AP-ANDROID-P3-01` livré | `90739a6` ; 18 fichiers, 25 ciblés / 666 JVM, lint 0/70, quota désactivé et erreurs relais typées |
| 2026-10-27 | Intégration vague B | merges `9686c87`, `51e3405`, `3c6aef1` ; Node 13 fichiers / 60 tests sans variable d'env, JVM 94 suites / 666 tests, lint 0/70, builds PASS |
| 2026-10-27 | Premier E2E WSS Pixel | APK intermédiaire `9ff0ae52` : health + plan revision, mauvais hostname, CA inconnue, mauvaise identité Noise, ws distant, isolation FastAPI, coupure/reconnexion TLS PASS |
| 2026-10-27 | Limite de reprise détectée | le relais ne rattache pas un client existant au connecteur redémarré ; timeout de handshake connecteur + heartbeat Android ajoutés |
| 2026-10-27 | Correctif reprise | timeout répondant 8 s (test 500 ms) et ping/pong Android 15 s/8 s (test 3 s/2 s) ; tests JVM et Pixel PASS |
| 2026-10-27 | Révocation distinguée | le connecteur envoie `DEVICE_REVOKED` avant la fermeture 4001 ; test Pixel REVOKED PASS |
| 2026-10-27 | APK final heartbeat | `66645143…` installé, données ATLAS préservées (4 hashes identiques) ; APK test `5d8370e9…` |
| 2026-10-27 | Recette WSS Pixel finale | W1–W10 PASS : round-trip, refus TLS/identité, coupure TLS, redémarrage connecteur (6,6 s), redémarrage relais (6,4 s), révocation (0,6 s) |
| 2026-10-27 | Preuves WSS Pixel | `preuves/pixel-wss-*.txt`, `preuves/pixel-wss-summary.txt`, `preuves/pixel-apk-migration.txt` |
| 2026-10-27 | Calendrier C1 Pixel | phase 1 `Process crashed` après journal UNKNOWN durable ; phase 2 réconciliation PASS, un seul événement, aucun doublon |
| 2026-10-27 | Calendrier C2/C3 Pixel | `FOREIGN_EVENT` avec provider inchangé ; calendrier jetable supprimé → `CALENDAR_NOT_LOCAL` fail-closed |
| 2026-10-27 | Calendrier C4 Pixel | READ refusé PASS ; WRITE refusé PASS ; restauration PASS ; révocation pendant l'opération → processus tué par Android, fail-closed accepté, aucune écriture |
| 2026-10-27 | Calendrier C5 Pixel | fuseau/DST explicites PASS ; édition/suppression externe PASS ; nouveau plan/parallèle PASS ; force-stop phase 1/2 PASS |
| 2026-10-27 | Nettoyage calendrier Pixel | PASS ; 12 calendriers personnels et 91 événements inchangés, 0 compte/calendrier ATLAS, permissions READ/WRITE revenues à refusé |
| 2026-10-27 | Persistance données ATLAS | PASS ; 4 hashes identiques avant/après tests (plan, local state, sync config, inbox) |
| 2026-10-27 | Suite UI R3-A de référence sur Pixel | NOT_RUN — risque de modification des données personnelles ; les changements B3 sont couverts par 666 tests JVM et les tests Pixel ciblés sûrs |
| 2026-10-27 | Gates finales déléguées | session `e38897ac…` : Node 14/62, backend 187/1 skip, JVM 94/668, lint 0/62, builds PASS, clean-copy offline PASS sur `38fd4a0` |
| 2026-10-27 | Rapport gates | `preuves/gates-agent-run.txt` ; APK de build agent `1cc96587…` (non-déterministe par rapport à l'APK Pixel `66645143…`, même source `38fd4a0`) |
| 2026-10-27 | `R3AP-CALENDAR-01` livré | `e66d2ef` + doc `a827d69` ; 64 JVM ciblés et 590 JVM complets annoncés par l'agent ; androidTest compilé |
| 2026-10-27 | Revue parent `R3AP-CALENDAR-01` | scope conforme, pas de symlink commité, pas de sortie de build ; 64 tests ciblés rejoués PASS sur le merge |
| 2026-10-27 | Intégration calendrier | merge `0f3426a` ; suite JVM complète rejouée : 91 suites / 654 tests / 0 échec sur `fe54e20` ; l'annonce 590/590 de l'agent est corrigée par la mesure parent |

## 5. Preuves

Les preuves versionnées vivent dans `docs/v6-3/r3a-plus/preuves/`. Elles ne
contiennent aucun secret, token, identifiant matériel, compte, chemin privé ni
donnée personnelle. Les captures utilisent uniquement des fixtures synthétiques.
