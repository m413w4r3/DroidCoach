# ATLAS V6.3 — R3-B — Registre de campagne

Campagne ouverte le **2026-10-28**. Orchestrateur parent unique : session Paseo
`wks_df1c3be12eaa95f6` / hôte `srv_ye_FTq15PUEs`. Tous les enfants sont
`codex:gpt-6-luna`, effort `xhigh`, sans sous-délégation. Maximum six enfants
actifs, dont trois implémentations.

## Base et branches

| Élément | Valeur |
|---|---|
| Base Git vérifiée | `5795d378d38b3e86ce99f1551c41d5044cd4a81c` |
| `main` au pré-vol | identique à la base |
| Branche de campagne | `feat/atlas-v6-3-r3b-remote-openclaw` |
| Branche de ticket | une branche par ticket, worktree Paseo séparé |
| Intégration | parent uniquement, sur la branche de campagne |
| Push / PR | **interdit** sans autorisation explicite |

## Tickets

| Ticket | Vague | Statut | Propriétaire / scope exclusif | Oracle principal |
|---|---|---|---|---|
| `R3B-NET-01` | A | INTEGRATED | `integrations/relay/proxy/**` (nouveau), `docs/v6-3/r3b/preuves/net-01*.txt` | Proxy filtrant `4400 -> 4402` : `/health` et `/ready` proxifiés, `/metrics` refusé, upgrade `/ws` v2 réel, tests négatifs |
| `R3B-PC-01` | A | INTEGRATED | `integrations/relay/pc/scripts/r3b-*`, `integrations/relay/pc/test/r3b-*`, `integrations/relay/pc/systemd/**`, `docs/v6-3/r3b/preuves/pc-01*.txt` | Résilience réelle : démarrage, arrêt, SIGKILL, reconnexion, FastAPI arrêté/relancé, état et identité conservés, aucun doublon |
| `R3B-CHAT-01` | A | INTEGRATED | `backend/**`, `contracts/**`, `integrations/relay/pc/src/{protocol,operations,backend,session}.ts`, `test/chat-r3b*`, `docs/v6-3/r3b/preuves/chat-01*.txt` | `coach.chat` + `coach.chat.request` validés, route fixe `POST /v2/agent/chat`, limites, mandats, erreurs, rejeu non automatique |
| `R3B-CHAT-WIRE-01` | B | INTEGRATED | `android/app/src/main/java/dev/atlas/coach/sync/relay/**`, `sync/agent/coach/**`, `app/RelaySettingsController.kt`, `app/AtlasController.kt`, `app/RelaySettingsScreen.kt`, `app/AgentCoachScreen.kt`, tests JVM | Conversation RELAY réelle, mode local distingué, états UI, annulation, accessibilité, tests JVM |
| `R3B-ANDROID-01` | B | INTEGRATED | `android/app/src/androidTest/**`, hooks de test explicitement validés par le parent | A3 Keystore réel après force-stop/redémarrage ; A4 révocation → `REVOKED` → effacement explicite → relance sans réappairage |
| `R3B-QA-C` | C | ACCEPTED (limites documentées) | parent (opérateur ADB unique) | Tests transport/chat, API 37, Funnel public, réseau réel, Pixel, régressions |
| `R3B-REVIEW-01` | D | RUNNING | relecteur indépendant lecture seule | Aucun P0/P1 sur le SHA final |

États : `READY -> RUNNING -> REVIEW_REQUIRED -> ACCEPTED -> INTEGRATED`, ou
`CORRECTION_REQUIRED / BLOCKED`. Une notification de fin n'est pas une
acceptation.

## Répartition des vagues

- **Vague A** (3 implémentations simultanées maximum) : `R3B-NET-01`,
  `R3B-PC-01`, `R3B-CHAT-01`.
- **Vague B** : `R3B-CHAT-WIRE-01` puis `R3B-ANDROID-01` (séquentiel pour
  garantir un seul propriétaire des fichiers Android principaux).
- **Vague C** : tests complets, émulateur API 37, qualification Funnel publique,
  tests Pixel sous autorisation de l'utilisateur, régressions et accessibilité.
- **Vague D** : corrections, rejeu sur SHA intégré, revue indépendante,
  nettoyage, handoff et verdict.

## Intégration vague A

| Ticket | Commit de code | Commit final ticket | Merge sur campagne | Gates rejouées sur le SHA intégré |
|---|---|---|---|---|
| `R3B-PC-01` | `0a96281f29b78031b2ecb792bebcc7239d35f3f7` | `0a96281` | `dcf3309` | harnais 14 scénarios PASS ; `npm test` 72/72 avec proxy sur 4400 |
| `R3B-NET-01` | `4363d8f0102002350a8b846a00575d6a91a16774` | `4363d8f` | `bb5bb99` | 18/18 tests proxy PASS ; systemd-analyze PASS ; Funnel public TLS/HTTP/WSS PASS |
| `R3B-CHAT-01` | `6ded055cdd68327466a320fcc54e8db8ea4dfe61` | `6ded055` | `2548673` | backend 196/1 ; chat 12/12 ; Node 73/73 ; timeout chat 55 s ; OpenClaw réel public PASS |

Aucune notification de fin n'a été acceptée sans rejeu parent. Les trois tickets
restent liés à leurs preuves dans `docs/v6-3/r3b/preuves/`.

## Sessions enfants — vague A

Modèle/effort vérifiés dans les métadonnées Paseo : `provider=codex`,
`model=gpt-6-luna`, `thinkingOptionId=xhigh`, `effectiveThinkingOptionId=xhigh`,
mode `auto-review`. Aucune sous-délégation.

| Ticket | Agent ID | Worktree | Branche |
|---|---|---|---|
| `R3B-NET-01` | `180017b9-b399-4a47-aa9a-43bb83cf8b65` | `/home/nill/.paseo/worktrees/073527gm/r3b-net-01` | `feat/r3b-net-01` |
| `R3B-PC-01` | `c8f7868c-7bc8-4280-83bb-bed8d00ed0bb` | `/home/nill/.paseo/worktrees/073527gm/r3b-pc-01` | `feat/r3b-pc-01` |
| `R3B-CHAT-01` | `92f6e6fe-dda5-4fd7-98b3-ddeeffb0e565` | `/home/nill/.paseo/worktrees/073527gm/r3b-chat-01` | `feat/r3b-chat-01` |

### Vague B — session active

| Ticket | Agent ID | Worktree | Branche | Modèle vérifié |
|---|---|---|---|---|
| `R3B-CHAT-WIRE-01` | `25c4a326-e19a-47f4-8303-5d8abeb20803` | `/home/nill/.paseo/worktrees/073527gm/r3b-chat-wire-01` | `feat/r3b-chat-wire-01` | `codex/gpt-6-luna`, `xhigh` effectif — intégré `3ecccad`, code `7f15ad4`, 685 tests JVM |

Note de reprise : l'agent s'est d'abord bloqué sur son auto-identification
interne « GPT-6 » ; le parent a fourni la métadonnée Paseo vérifiée
(`model=gpt-6-luna`, `effectiveThinkingOptionId=xhigh`) et l'a débloqué sans
changer de modèle.

### Vague B — Android A3/A4 (intégration et tests Pixel)

| Élément | Valeur |
|---|---|
| Implémentation / hooks de test | commits `c916ab8`, `7be0599` ; correctif produit A4 `c5e1bea` (`RelaySettingsErrorPolicy`) |
| Correctif connecteur de révocation | `dc8f55e` — délai borné 200 ms avant fermeture 4001 |
| Corrections test-only | `f266416`, `937b95a`, `790b476` (sélecteurs Compose, historique long, état `RELAY_CONNECTING`) |
| APK principal | `b55776a9…`, code Android `8eb4845` |
| Opérateur ADB | parent unique ; aucun autre opérateur Pixel/ADB simultané |
| Modèle des sous-agents | `codex/gpt-6-luna`, `xhigh` effectif pour les sessions enfants de la vague A/B ; les corrections d'intégration et les tests Pixel ont été opérés par le parent |

Les tests Pixel A3, A4, chat et réseau ont tous été exécutés sur l'appareil
physique `45301FDAS001QT`, avec sauvegarde app-only préalable et restauration
finale. Voir `preuves/pixel-instrumentation-final.txt` et
`preuves/pixel-network.txt`.

## Qualification publique PC (SHA `2548673`)

| Preuve | Statut |
|---|---|
| DNS public, certificat Let's Encrypt, TLS 1.3, SNI/SAN | PASS |
| `/health` et `/ready` publics | PASS |
| `/metrics` public et upgrade `/metrics` | PASS — 404 |
| WSS public `role=server`/`role=client` v2 | PASS — 101 |
| Refus `v=1`, query inconnue, `serverId` absent | PASS — 400 |
| Noise NK `protected` + auth appareil via IP publique | PASS |
| `backend.health` via Funnel | PASS |
| `sync.status` via Funnel | PASS |
| Chat mock relayé, rejeu sans doublon | PASS |
| Chat OpenClaw personnel réel relayé | PASS — `agent_status=connected`, 1 proposition, max 4 295 ms |
| Refus de chat sans capacité `coach.chat` | PASS — `OPERATION_UNAVAILABLE` |
| Arrêt/redémarrage réel FastAPI via Funnel | PASS — `BACKEND_UNAVAILABLE` puis `ok` |
| Redémarrage connecteur / reconnexion relais | PASS |

## Qualification Pixel (appareil physique)

| Preuve | Statut |
|---|---|
| A3 appairage Keystore réel | PASS — `R3bA3PairingInstrumentedTest` OK |
| A3 force-stop, redémarrage, identité et token chiffré conservés | PASS — `R3bA3PersistenceInstrumentedTest` |
| A4 session active, révocation PC, `DEVICE_REVOKED`, `4001`, `REVOKED` | PASS — `R3bA4RevocationInstrumentedTest` (17,821 s) après grâce 200 ms |
| A4 effacement explicite et relance sans appairage implicite | PASS — `R3bA4AfterClearInstrumentedTest` |
| A4 réappairage explicite | PASS — `R3bA4RepairInstrumentedTest` (device6) |
| Chat relayé OpenClaw, provenance, aucun fallback direct, brouillon/rotation | PASS — `R3bRelayChatInstrumentedTest` |
| Coupure mode avion et reprise | PASS — 28,396 s ; état observé ERROR |
| Wi-Fi → LTE/5G → Wi-Fi | PASS — 24,761 s ; réseau CELLULAR et `mDataConnectionState=2` |
| Redémarrage connecteur avec session Pixel | PASS — 39,852 s ; ERROR 19 s puis RELAY_CONNECTED 1 s |
| Restauration finale des données | PASS — 5/5 hashes, calendriers 12/91, aucun artefact |

## Vérifications finales du code intégré `dc8f55e`

| Gate | Résultat |
|---|---|
| Backend pytest | 196 passed, 1 skipped |
| Node connecteur | 74/74, 15 fichiers |
| Proxy filtrant | 18/18 |
| JVM Android | 688 tests, 97 suites, 0 échec |
| Lint | 0 erreur, 71 avertissements |
| Builds | APK principal `b55776a9…`, androidTest `025fd62e…` |
| E2E final via Funnel/MagicDNS | 7/7 PASS, `agent_status=connected` |
| Proxy + relais redémarrés | `/health`/`/ready` 200, `/metrics` 404 |

## Limite de recontrôle public final

Le 2026-10-28 à 15:15, le recontrôle par l'IP publique du Funnel a échoué au
TLS : `tailscaled` ne joignait plus le plan de contrôle Tailscale
(`network is unreachable`). Aucune configuration Tailscale n'a été modifiée.
La qualification publique de 13:00 reste la preuve de l'ingress public ; le
rejeu final a été effectué via le nom Funnel résolu par MagicDNS et a réussi
sur les sept contrôles. Cette limite est reportée en R-18 et dans le rapport
final.

Reprise : si une session est interrompue, relire son commit, son diff et ses
preuves ; ne pas rejouer une tâche terminée. Une notification de fin n'est pas
une acceptation.

## Autorisations et limites

Autorisés dans ce workspace : lecture, code, tests, builds, commits locaux,
services de test isolés, adaptateur mock, émulateur après pré-vol, préparation
des runbooks, revue indépendante, diagnostic non destructif.

Autorisation utilisateur explicite : **utiliser le Pixel physique
`45301FDAS001QT`** pour les tests de la campagne. Toute installation d'APK
exige une sauvegarde app-only vérifiée et un mandat ciblé ; `pm clear`,
désinstallation, factory reset, modification HOME, permissions non consenties
et écritures wger restent interdits.

Opérations non autorisées à ce stade : modification de la configuration
Tailscale personnelle, remplacement/redémarrage d'un service PC personnel,
installation persistante systemd, activation `linger`, modification de FastAPI
ou OpenClaw personnels, lecture wger non couverte par le mandat existant, push
ou modification GitHub distante. Si une autorisation manque : `BLOCKED`, sans
contournement.

## Environnement de preuve

| Élément | Valeur au pré-vol |
|---|---|
| Dépôt | `/home/nill/perso/AndroCoach/atlas-preparation` |
| JDK | OpenJDK 21.0.12.1 |
| Node / npm | v24.18.0 / 11.16.0 |
| Python | 3.12.3 |
| Android SDK | `/home/nill/Android/Sdk` |
| ADB | 1.0.41 |
| Pixel | `45301FDAS001QT`, Pixel 9 Pro XL, Android 17 / API 37 |
| Tailscale | 1.104.4 |
| Funnel | `tcp://all.taildc7886.ts.net:8443` → `tcp://127.0.0.1:4400` |
| Relais local | image `atlas-paseo-relay:r3a` vérifiée, à lancer sur `127.0.0.1:4402` |
| FastAPI local | `127.0.0.1:8100` (instance de test isolée) |
| OpenClaw | gateway locale `127.0.0.1:18789` joignable, jeton privé existant |

Aucun secret, token, clé privée ou donnée personnelle ne doit apparaître dans
les preuves versionnées ou les commits.
