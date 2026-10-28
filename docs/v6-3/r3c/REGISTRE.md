# ATLAS V6.3 — R3-C — Registre de campagne

Campagne ouverte le **2026-10-28**. Orchestrateur parent unique : session Paseo
`9d2473d1-5b31-422f-be84-037de8fbda14` (`deepseek-claude` /
`deepseek-flash[1m]`, effort `max`, hôte `srv_ye_FTq15PUEs`).

## 1. Base Git et réconciliation distante

| Élément | Valeur vérifiée |
|---|---|
| Base de campagne | `eedf91502227297be5d2ff35524e79983ea36f49` |
| `origin/main` au pré-vol | `eedf91502227297be5d2ff35524e79983ea36f49` |
| Commits R3-B ancêtres de `origin/main` | `5795d37`, `738ef93`, `4be991d` : **OUI** |
| Branche de campagne | `feat/atlas-v6-3-r3c-qualification` |
| Branche de ticket | une branche/worktree Paseo par ticket |
| Intégration | parent uniquement, sur la branche de campagne |
| Push / PR / merge distant | **aucun sans autorisation explicite** |
| CI sur `eedf915` | run `40718987949`, **success**, terminé `2026-10-28T15:49:40Z` |

La mention « aucun push » du rapport R3-B est une **incohérence documentaire** :
les commits R3-B sont bien présents sur `origin/main`. Aucun historique n'a été
réécrit ; la campagne R3-C repart de `eedf915`.

## 2. Children Paseo — modèle vérifié

Tous les enfants sont `provider=codex`, `model=gpt-6-luna`,
`thinkingOptionId=xhigh`, `effectiveThinkingOptionId=xhigh`, mode
`auto-review`, sans sous-délégation. Métadonnées relues via
`mcp__paseo__get_agent_status`.

| Ticket | Agent ID | Worktree | Branche | Statut |
|---|---|---|---|---|
| `R3C-ANDROID-NET-01` | `6a1b8d43-8c2a-4eed-807e-03142cf7f1e6` | `/home/nill/.paseo/worktrees/073527gm/r3c-net-01` | `feat/r3c-net-01` | BLOCKED_SANDBOX → patch intégré |
| `R3C-COMPAT-01` | `6093964c-7ce5-4f6e-9424-ea59b08f1761` | `/home/nill/.paseo/worktrees/073527gm/r3c-compat-01` | `feat/r3c-compat-01` | BLOCKED_SANDBOX → patch intégré |
| `R3C-RACE-01` | `be46e357-2cc0-4fd4-b367-3d036d5e7df3` | `/home/nill/.paseo/worktrees/073527gm/r3c-race-01` | `feat/r3c-race-01` | BLOCKED_SANDBOX → patch intégré |

Le bac à sable Codex refuse l'écriture dans `.git/worktrees`, `~/.gradle` et
l'exécution de certains binaires `node_modules` (`Read-only file system`,
`EPERM`). Les enfants ont donc produit les patches et preuves dans leur
worktree ; le parent a exécuté les oracles et intégré les diffs. Ce n'est pas
un échec logiciel : statut `BLOCKED_SANDBOX` documenté.

## 3. Tickets

| Ticket | Vague | Statut | Scope exclusif | Oracle principal |
|---|---|---|---|---|
| `R3C-BASE-01` | A | ACCEPTED (preuves) | parent, lecture seule | Git/CI/APK/backups/services/Tailscale réconciliés |
| `R3C-ANDROID-NET-01` | A | **INTEGRATED** `d2419b3` | `PaseoRelayClient.kt`, nouveau test de classification | OFFLINE vs ERROR, TLS terminal, reprise réseau |
| `R3C-COMPAT-01` | A | **INTEGRATED** `a162f70` | `RelayBackendTransport.kt`, tests Android/Node | session maintenue, `OPERATION_UNAVAILABLE` ciblé, aucune élévation |
| `R3C-RACE-01` | A | **INTEGRATED** `22ba24f` | tests et hooks de test uniquement | 3 scénarios Android + 3 scénarios Node, ordre DEVICE_REVOKED/4001 |
| `R3C-PIXEL-SAFETY-01` | A/B | READY (runbook) | parent, docs | runbook non destructif, sauvegarde vérifiée |
| `R3C-PIXEL-QUALIFY-01` | C | BLOCKED_DEVICE | parent, opérateur ADB unique | Pixel connecté + mandat d'installation |
| `R3C-FUNNEL-01` | B | PARTIAL → PASS public PC | parent, diagnostic non destructif | TLS/health/ready/metrics/WSS/Noise/auth/chat via IP publique |
| `R3C-REGRESSION-01` | C | PARTIAL | parent + Pixel | NET-07/13, CHAT-05/06, UI-04/06/07/08, R-22 |
| `R3C-PC-DEPLOY-01` | B | PARTIAL | parent, services de test isolés | ordre, SIGKILL, réseau, logs, ports, rollback |
| `R3C-CI-01` | B | INTEGRATED `cd6e751` | `.github/workflows/ci.yml`, harnais relais isolé | Android + backend + Node + proxy |

## 4. Intégration

| Ticket | Commit d'intégration | Gates rejouées par le parent |
|---|---|---|
| `R3C-COMPAT-01` | `a162f70` | JVM relay `BUILD SUCCESSFUL` ; Vitest `r3c-compat` 1/1 |
| `R3C-RACE-01` | `22ba24f` | JVM relay ciblé `BUILD SUCCESSFUL` ; Vitest `r3c-race` 3/3 |
| `R3C-ANDROID-NET-01` | `d2419b3` | JVM relay complet `BUILD SUCCESSFUL` ; 14 cas de classification ; gates 712 tests / 0 échec |
| `R3C-CI-01` | `cd6e751` | YAML valide ; suite Node complète 17 fichiers/81 tests PASS ; proxy 18/18 ; harnais relais réel 81/81 |

Une notification de fin n'est **pas** une acceptation. Chaque intégration
ci-dessus a été rejouée sur le SHA intégré.

## 5. Autorisations

Autorisés : lecture, code, tests, builds, commits locaux, services de test
isolés sur loopback, diagnostic Funnel non destructif, sauvegardes hors dépôt.

Non autorisés à ce stade : push/PR/merge distant, modification de la
configuration Tailscale, installation persistante systemd, activation d'un
nouveau `linger`, modification d'OpenClaw/FastAPI personnels, ADB/Pixel sans
mandat ciblé, lecture wger, `pm clear`, `uninstall`, factory reset, `rm -rf`
des répertoires applicatifs, écrasement global des préférences/bases.

`linger` était **déjà** `yes` avant la campagne ; son état a seulement été lu,
pas modifié.

## 6. Environnement de preuve

| Élément | Valeur |
|---|---|
| Dépôt | `/home/nill/perso/AndroCoach/atlas-preparation` |
| JDK | OpenJDK 21.0.12.1 |
| Node / npm | v24.18.0 / 11.16.0 |
| Python | 3.12.3 |
| Android SDK | `/home/nill/Android/Sdk`, API 37.x |
| ADB | appareil : **aucun connecté au pré-vol** |
| Pixel | `45301FDAS001QT`, hors USB ; Tailscale `offline, last seen 23d` |
| Tailscale | 1.104.4, `Self.Online=true`, `Health=[]` |
| Funnel | `tcp://all.taildc7886.ts.net:8443` → `127.0.0.1:4400` |
| Relais local | image `atlas-paseo-relay:r3a`, `127.0.0.1:4402` |
| Proxy | `127.0.0.1:4400 -> 4402` |
| FastAPI isolé | `127.0.0.1:8100`, adaptateur `mock` |
| Connecteur | métriques `127.0.0.1:4401`, `relay_connected=true` |

Aucun secret, token, clé privée ou donnée personnelle ne doit apparaître dans
les preuves versionnées.
