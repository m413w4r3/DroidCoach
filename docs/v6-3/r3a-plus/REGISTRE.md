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
| `R3AP-WSS-01` | politique réseau WSS, validation PC/Android, infra TLS de test | `integrations/relay/pc/src/relay-link.ts`, `integrations/relay/pc/test/relay-url.test.ts`, `android/app/src/main/java/dev/atlas/coach/sync/relay/{RelayConfig,RelayConfigurationValidator,PaseoRelayClient}.kt`, tests JVM de ces fichiers, `android/app/src/debug/**`, `tools/relay-tls-test/**`, `docs/v6-3/r3a-plus/preuves/wss-*` | WSS de test accepté, WS distant refusé, mauvais hostname/CA/identité refusés, FastAPI privé | **READY** |
| `R3AP-PACKAGING-01` | packaging reproductible + supervision utilisateur | `integrations/relay/pc/{package.json,package-lock.json}`, `integrations/relay/pc/scripts/**`, `integrations/relay/pc/vendor/**`, `integrations/relay/pc/packaging/**`, `integrations/relay/pc/systemd/**`, `THIRD_PARTY_NOTICES.md`, `docs/v6-3/r3a-plus/{INSTALLATION_PC,SUPERVISION_PC}.md`, `docs/v6-3/r3a-plus/preuves/packaging-*` | installation propre sans clone Paseo, hash vérifié, unité systemd valide, secrets non affichés | **READY** |
| `R3AP-CALENDAR-01` | scénarios C1–C5 réels et tests instrumentés | `android/app/src/main/java/dev/atlas/coach/calendar/**` (correctifs ciblés), `android/app/src/test/java/dev/atlas/coach/calendar/**`, `android/app/src/androidTest/java/dev/atlas/coach/calendar/**`, `docs/v6-3/r3a-plus/preuves/calendar-*` | tests JVM + androidTest compilés ; scénarios exécutables sur Pixel | **READY** |
| `R3AP-RESILIENCE-01` | résilience et idempotence du connecteur | `integrations/relay/pc/src/{connector,session,operations,backend,metrics,protocol}.ts`, `integrations/relay/pc/test/**` hors fichiers A1 | 13 scénarios de résilience, échec fermé, pas de rejeu aveugle | **PENDING** |
| `R3AP-PAIRING-01` | appairage/révocation atomiques | `integrations/relay/pc/src/{main,device-registry,secure-files,file-lock}.ts`, tests correspondants | aucun appareil orphelin autorisé, token jamais sur stdout, concurrence sûre | **PENDING** |
| `R3AP-ANDROID-P3-01` | corrections P3 et messages | fichiers UI/usage/relais ciblés, tests correspondants | quota désactivé affiché « Désactivé », erreurs TLS/identité/permission distinctes | **PENDING** |
| `R3AP-E2E-01` | recette WSS bout en bout sur Pixel | scripts de recette parent, preuves | Android physique → TLS/WSS test → relais réel → Noise → connecteur → FastAPI | **PENDING** |
| `R3AP-REVIEW-01` | revue indépendante lecture seule | aucun | grille P0/P1/P2/P3 | **PENDING** |

## 4. Journal

| Date | Événement | Résultat |
|---|---|---|
| 2026-10-27 | Pré-vol Git : base `9a05dca`, arbre propre | **PASS** |
| 2026-10-27 | Pré-vol outils : JDK 21, Node 24, Python 3.12, Docker 27 | **PASS** |
| 2026-10-27 | Pixel physique Android 17/API 37 connecté, état `device` | **PASS** |
| 2026-10-27 | Baseline calendrier : 91 événements personnels, aucun événement ATLAS | **PASS** |
| 2026-10-27 | Sauvegarde app-only ATLAS + APK installé + hashes | **PASS** |
| 2026-10-27 | Branche `feat/atlas-v6-3-r3a-plus` créée sur `9a05dca` | **PASS** |
| 2026-10-27 | Contrats R3-A+ figés | **PASS (doc)** |

## 5. Preuves

Les preuves versionnées vivent dans `docs/v6-3/r3a-plus/preuves/`. Elles ne
contiennent aucun secret, token, identifiant matériel, compte, chemin privé ni
donnée personnelle. Les captures utilisent uniquement des fixtures synthétiques.
