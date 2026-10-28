# ATLAS V6.3 — R3-C — Matrice de recette

Mise à jour : **2026-10-28 17:15 +01:00**. Statuts : `PASS`, `FAIL`,
`PARTIAL`, `BLOCKED`, `NOT_RUN`, `BLOCKED_DEVICE`, `BLOCKED_AUTH`,
`NON_REGRESSION_PASS`.

Règle : un PASS R3-B ne devient **jamais** automatiquement un
`NON_REGRESSION_PASS` R3-C. Il faut une réexécution sur le SHA/APK R3-C.
Une preuve JVM ne vaut pas preuve instrumentée ; une preuve MagicDNS ne vaut
pas preuve publique ; une preuve PC ne vaut pas preuve Pixel.

## 1. Gates techniques

| ID | Gate | R3-B | R3-C | Preuve / limite |
|---|---|---|---|---|
| T1 | Backend pytest | PASS | **PASS** — 196 passed, 1 skipped | `d2419b3` |
| T2 | JVM Android | PASS 691 | **PASS** — 100 suites, 712 tests, 0 échec | `d2419b3` |
| T3 | Lint Android | PASS 0/71 | **PASS** — 0 erreur, 71 avertissements | `d2419b3` |
| T4 | Build APK principal | PASS | **PASS** — `8332afd9…` | `d2419b3`, non installé |
| T5 | Build APK androidTest | PASS | **PASS** — `5aef1321…` (identique, sources instrumentation inchangées) | Rebuild `clean` prévu au gel |
| T6 | Instrumentation API 37 | PARTIAL | **BLOCKED_DEVICE** | Aucun Pixel ADB au pré-vol |
| T7 | Node connecteur | PASS 77 | **PASS** — 17 fichiers, 81 tests | `d2419b3` |
| T8 | Relais épinglé | PASS | **PASS** — image `atlas-paseo-relay:r3a`, 4402 | |
| T9 | Proxy filtrant | PASS 18 | **PASS** — 18/18 | rejeu avec port 4400 libre |
| T10 | Unités systemd | PARTIAL | **PARTIAL / BLOCKED_AUTHORIZATION** | Modèles non installés ; vérification statique seule |
| T11 | WSS public Funnel | PASS historique / PARTIAL final | **PASS (PC, IP publique)** | TLS 1.3, health/ready 200, metrics 404, WSS 101, E2E 7/7 |
| T12 | Chat mock relayé | PASS | **PASS (PC public)** | adaptateur mock |
| T13 | Chat OpenClaw réel | PASS historique | **NOT_REPLAYED_R3C** | Aucune instance OpenClaw réelle utilisée dans R3-C |
| T14 | A3 Keystore | PASS ancien APK | **BLOCKED_DEVICE** | Pas de Pixel ; pas de nouveau binaire installé |
| T15 | A4 révocation | PARTIAL ancien APK | **BLOCKED_DEVICE** | Assertions 4001/Keystore non rejouées sur binaire R3-C |
| T16 | Données Pixel | PASS 5/5 | **BLOCKED_DEVICE** | Pas de comparaison possible sans appareil |
| T17 | Revue indépendante | PASS avec réserves | **PENDING** | À exécuter sur le SHA final |

## 2. Réseau et Funnel

| ID | Cas | R3-B | R3-C | Limite |
|---|---|---|---|---|
| NET-01 | DNS public + TLS | PASS historique | **PASS** — DoH 1.1.1.1, IP `176.58.90.46`, TLS 1.3, certificat valide | PC, pas Pixel mobile |
| NET-02 | WSS public 8443 sans VPN Android | PASS historique | **NOT_RUN_PIXEL** | Pixel absent |
| NET-03 | Absence `adb reverse`/proxy USB | PASS historique | **NOT_RUN_PIXEL** | Pixel absent |
| NET-04 | Noise NK `protected`, identité épinglée | PASS historique | **PASS (PC public)** | E2E 7/7 |
| NET-05 | `backend.health` via Funnel | PASS | **PASS (PC public)** | mock |
| NET-06 | Chat réel via relais/adaptateur autorisé | PASS historique | **PASS mock / NOT_REPLAYED OpenClaw** | pas de modification OpenClaw |
| NET-07 | Coupure réseau → OFFLINE | PARTIAL (ERROR observé) | **PASS JVM / BLOCKED_DEVICE Pixel** | 14 cas JVM ; observation finale sur binaire R3-C requise |
| NET-08 | Reconnexion après réseau | PASS | **PASS JVM (reprise OFFLINE→CONNECTING→CONNECTED)** | Pixel à rejouer |
| NET-09 | Wi-Fi → LTE/5G → Wi-Fi | PASS historique | **NOT_RUN_PIXEL** | Pixel absent |
| NET-10 | Redémarrage connecteur/FastAPI | PASS | **PASS (services isolés)** | SIGKILL/relance déjà couverts par tests Node ; redémarrage PC réel non exécuté |
| NET-11 | Redémarrage proxy/relais | PASS | **PASS (proxy redémarré, connecteur reconnecté)** | |
| NET-12 | Révocation avec session active | PASS historique | **PASS JVM/Node, BLOCKED_DEVICE Pixel** | |
| NET-13 | Expiration requête/session/token | PARTIAL | **PARTIAL** | Horloges contrôlées JVM/Node ; parcours Pixel non rejoué |
| NET-14 | Version/payload invalides | PASS | **PASS (public + tests)** | `v=1`, query inconnue, `serverId` absent → 400 |
| NET-15 | `/metrics` inaccessible publiquement | PASS | **PASS** | 404 public, upgrade 404 |
| NET-16 | Données locales inchangées | PASS historique | **BLOCKED_DEVICE** | Pas de Pixel |

## 3. Conversation et compatibilité

| ID | Cas | R3-B | R3-C | Limite |
|---|---|---|---|---|
| CHAT-01 | Chat mock relayé | PASS | **PASS (PC public)** | |
| CHAT-02 | Chat OpenClaw réel | PASS historique | **NOT_REPLAYED_R3C** | |
| CHAT-03 | Contexte consenti/minimisé | PASS | **PASS (tests JVM/Node)** | Pixel non rejoué |
| CHAT-04 | Refus sans capacité | PASS connecteur, Android fermait la session | **PASS** — session maintenue, `OPERATION_UNAVAILABLE`, anciennes opérations utilisables | `a162f70` |
| CHAT-05 | Mandat révoqué/expiré | PARTIAL | **PARTIAL** | Tests JVM/Node ; parcours Pixel non rejoué |
| CHAT-06 | Déconnexion pendant chat en vol | PARTIAL | **PASS JVM/Node** | Pixel non rejoué |
| CHAT-07 | Reconnexion sans doublon | PASS | **PASS (public + Node)** | |
| CHAT-08 | Payload malformé/trop grand | PASS | **PASS** | |
| CHAT-09 | Aucune action sans confirmation | PASS historique | **NOT_REPLAYED_R3C** | Pixel absent |
| CHAT-10 | Provenance mock/OpenClaw | PASS historique | **PARTIAL** — mock public ; OpenClaw non rejoué | |

## 4. Identité et révocation

| ID | Cas | R3-B | R3-C | Limite |
|---|---|---|---|---|
| A3-1..8 | Appairage, force-stop, reboot, identité, token | PASS ancien APK | **BLOCKED_DEVICE** | Aucun APK R3-C installé |
| A4-1..10 | Révocation, DEVICE_REVOKED, 4001, REVOKED, effacement, réappairage | PARTIAL ancien APK | **PASS JVM/Node ; BLOCKED_DEVICE Pixel** | Assertions 4001/Keystore à rejouer sur binaire final |

## 5. Courses R-22

| ID | Cas | R3-B | R3-C | Limite |
|---|---|---|---|---|
| R-22a | Chat en attente + révocation → aucun envoi | PARTIAL | **PASS JVM** | |
| R-22b | Écriture WebSocket contrôlée + révocation, ordre du verrou | PARTIAL | **PASS JVM/Node** | |
| R-22c | Requête en vol + DEVICE_REVOKED, pas de réponse tardive | PARTIAL | **PASS JVM/Node** | |
| R-22d | Deux chemins de révocation (polling, réception directe) | PARTIAL | **PASS Node** | |

## 6. UI et accessibilité

| ID | Cas | R3-B | R3-C | Limite |
|---|---|---|---|---|
| UI-01 | Connexion/révocation | PASS historique | **BLOCKED_DEVICE** | |
| UI-02 | Conversation, brouillon, rotation | PASS historique | **BLOCKED_DEVICE** | |
| UI-03 | Erreur réseau | PASS historique | **PARTIAL (JVM)** | Pixel à rejouer |
| UI-04 | Saisie et stockage du token | PARTIAL | **BLOCKED_DEVICE** | |
| UI-06 | Retour HOME | NOT_RUN | **NOT_RUN** | |
| UI-07 | Mode hors ligne | PARTIAL | **PARTIAL** — mock local couvert JVM ; Pixel non rejoué | |
| UI-08 | Police agrandie, rotation, TalkBack | PARTIAL | **BLOCKED_DEVICE** | |

## 7. PC, supervision et limites produit

| ID | Cas | R3-B | R3-C | Limite |
|---|---|---|---|---|
| PC-01..11 | Démarrage, SIGKILL, identité, reconnexion, FastAPI, rollback | PASS | **PASS (tests Node + services isolés)** | Redémarrage complet du PC non exécuté |
| PC-12 | Supervision persistante systemd | NOT_INSTALLED | **BLOCKED_AUTHORIZATION** | Aucune unité installée, `linger` déjà actif non modifié |
| WGER-01 | Lecture wger réelle | BLOCKED | **BLOCKED_AUTH** | Aucune lecture autorisée |
| H24/Doze | Disponibilité permanente / notifications | NOT_RUN | **NOT_RUN** | Ne pas promettre |
| R-15/R-17 | Déni de service relais public | ouvert | **ouvert, documenté** | Pas de réécriture générale |

## 8. Synthèse des changements de statut

```text
R3-B PARTIAL -> R3-C PASS :
  NET-07 (JVM, en attente d'observation Pixel)
  CHAT-06 (JVM/Node)
  R-22 (a,b,c,d)
  Proxy 18/18
  Node 81/81

R3-B PARTIAL -> R3-C PASS/BLOCKED :
  NET-13 -> PARTIAL (horloges contrôlées, Pixel bloqué)
  A4 -> PASS JVM/Node / BLOCKED_DEVICE Pixel
  UI-07 -> PARTIAL

R3-B PASS -> NON_REGRESSION_PASS :
  Backend (196/1), JVM (712), Node (81), Proxy (18), lint/build
  Ces PASS sont rejoués sur `d2419b3`, pas simplement recopiés.

R3-B PASS -> NOT_REPLAYED_R3C :
  Chat OpenClaw réel, A3/A4 Pixel, UI Pixel, données Pixel.

R3-B BLOCKED -> R3-C BLOCKED :
  WGER-01 (BLOCKED_AUTH), systemd persistant (BLOCKED_AUTHORIZATION),
  Pixel (BLOCKED_DEVICE).
```

## 9. Verdicts

| Verdict | Conditions | État R3-C |
|---|---|---|
| `GO_PREPARED_WITH_LIMITATIONS` | code compile, tests passent, qualification externe manquante | **ATTEIGNABLE** après revue ; le Pixel et la CI distante manquent |
| `GO_REMOTE_WITH_LIMITATIONS` | APK final installé sur Pixel, A3/A4, chat réel, Funnel public, Noise, coupure/reprise, aucune perte, aucun P0/P1, revue favorable | **NON ATTEINT** — aucune installation Pixel, OpenClaw réel non rejoué, revue en attente |
| `GO_V63` | toutes les gates produit V6.3, fonctionnement durable | **NON ATTEINT** — H24/Doze/systemd/Pixel non qualifiés |
