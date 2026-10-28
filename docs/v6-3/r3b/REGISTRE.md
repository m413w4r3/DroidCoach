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
| `R3B-NET-01` | A | RUNNING | `integrations/relay/proxy/**` (nouveau), `docs/v6-3/r3b/preuves/net-01*.txt` | Proxy filtrant `4400 -> 4402` : `/health` et `/ready` proxifiés, `/metrics` refusé, upgrade `/ws` v2 réel, tests négatifs |
| `R3B-PC-01` | A | RUNNING | `integrations/relay/pc/scripts/r3b-*`, `integrations/relay/pc/test/r3b-*`, `integrations/relay/pc/systemd/**`, `docs/v6-3/r3b/preuves/pc-01*.txt` | Résilience réelle : démarrage, arrêt, SIGKILL, reconnexion, FastAPI arrêté/relancé, état et identité conservés, aucun doublon |
| `R3B-CHAT-01` | A | RUNNING | `backend/**`, `contracts/**`, `integrations/relay/pc/src/{protocol,operations,backend,session}.ts`, `test/chat-r3b*`, `docs/v6-3/r3b/preuves/chat-01*.txt` | `coach.chat` + `coach.chat.request` validés, route fixe `POST /v2/agent/chat`, limites, mandats, erreurs, rejeu non automatique |
| `R3B-CHAT-WIRE-01` | B | BLOCKED (dépend de CHAT-01 intégré) | `android/app/src/main/java/dev/atlas/coach/sync/relay/**`, `sync/agent/coach/**`, `app/RelaySettingsController.kt`, `app/AtlasController.kt`, `app/RelaySettingsScreen.kt`, `app/AgentCoachScreen.kt`, tests JVM | Conversation RELAY réelle, mode local distingué, états UI, annulation, accessibilité, tests JVM |
| `R3B-ANDROID-01` | B | BLOCKED (dépend de CHAT-WIRE-01 intégré) | `android/app/src/androidTest/**`, hooks de test explicitement validés par le parent | A3 Keystore réel après force-stop/redémarrage ; A4 révocation → `REVOKED` → effacement explicite → relance sans réappairage |
| `R3B-QA-C` | C | BLOCKED (dépend de B) | parent (opérateur ADB unique) | Tests transport/chat, API 37, Funnel public, réseau réel, Pixel, régressions |
| `R3B-REVIEW-01` | D | BLOCKED (dépend de C) | relecteur indépendant lecture seule | Aucun P0/P1 sur le SHA final |

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

## Sessions enfants — vague A

Modèle/effort vérifiés dans les métadonnées Paseo : `provider=codex`,
`model=gpt-6-luna`, `thinkingOptionId=xhigh`, `effectiveThinkingOptionId=xhigh`,
mode `auto-review`. Aucune sous-délégation.

| Ticket | Agent ID | Worktree | Branche |
|---|---|---|---|
| `R3B-NET-01` | `180017b9-b399-4a47-aa9a-43bb83cf8b65` | `/home/nill/.paseo/worktrees/073527gm/r3b-net-01` | `feat/r3b-net-01` |
| `R3B-PC-01` | `c8f7868c-7bc8-4280-83bb-bed8d00ed0bb` | `/home/nill/.paseo/worktrees/073527gm/r3b-pc-01` | `feat/r3b-pc-01` |
| `R3B-CHAT-01` | `92f6e6fe-dda5-4fd7-98b3-ddeeffb0e565` | `/home/nill/.paseo/worktrees/073527gm/r3b-chat-01` | `feat/r3b-chat-01` |

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
