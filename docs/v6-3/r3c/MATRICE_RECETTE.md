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
| T4 | Build APK principal | PASS | **PASS** — `365bd84a…` (build propre) | code `d2419b3`, non installé |
| T5 | Build APK androidTest | PASS | **PASS** — `09ecfd7a…` (inclut le test R3-C d'effacement) | reconstruit et installé |
| T6 | Instrumentation API 37 | PARTIAL | **PARTIAL** — A3, chat réel, NET-07/09, UI-06/07/08 PASS sur Pixel ; A4-4 FAIL (relais 1012 au lieu de 4001) | binaire R3-C installé `365bd84a…` |
| T7 | Node connecteur | PASS 77 | **PASS** — 15 fichiers, 79 tests sans relais ; 17 fichiers, 81 tests avec relais | `d2419b3` |
| T8 | Relais épinglé | PASS | **PASS** — image `atlas-paseo-relay:r3a`, ID `sha256:75d68fd8…` | |
| T9 | Proxy filtrant | PASS 18 | **PASS** — 18/18 | rejeu avec port 4400 libre |
| T10 | Unités systemd | PARTIAL | **PARTIAL / BLOCKED_AUTHORIZATION** | Modèles non installés ; vérification statique seule |
| T11 | WSS public Funnel | PASS historique / PARTIAL final | **PASS (PC, IP publique)** | TLS 1.3, health/ready 200, metrics 404, WSS 101, E2E 7/7 |
| T12 | Chat mock relayé | PASS | **PASS (PC public + Pixel)** | adaptateur mock |
| T13 | Chat OpenClaw réel | PASS historique | **PASS (PC public + Android réel)** | `agent_status=connected`, test Pixel 15,658 s |
| T14 | A3 Keystore | PASS ancien APK | **PASS (APK R3-C)** | appairage 1,149 s ; force-stop 0,89 s ; reboot 1,439 s |
| T15 | A4 révocation | PARTIAL ancien APK | **PARTIAL** — `DEVICE_REVOKED`/`REVOKED`/effacement/réappairage PASS ; **A4-4 FAIL** close 1012 | relais ne propage pas 4001 |
| T16 | Données Pixel | PASS 5/5 | **PASS (périmètre R3-C)** | hash local stable, conversation conservée, aucun effacement implicite |
| T17 | Revue indépendante | PASS avec réserves | **PASS** — `APPROUVE`, aucun P0/P1 ; P2 corrigés | `REVUE_INDEPENDANTE.md` |

## 2. Réseau et Funnel

| ID | Cas | R3-B | R3-C | Limite |
|---|---|---|---|---|
| NET-01 | DNS public + TLS | PASS historique | **PASS** — DoH 1.1.1.1, IP `$FUNNEL_PUBLIC_IP`, TLS 1.3, certificat valide, source physique hors dépôt | PC, pas Pixel mobile |
| NET-02 | WSS public 8443 sans VPN Android | PASS historique | **PASS (Pixel R3-C)** | appairage/chat via Funnel, aucun VPN |
| NET-03 | Absence `adb reverse`/proxy USB | PASS historique | **PASS (Pixel R3-C)** | `reverse --list` vide, proxy null |
| NET-04 | Noise NK `protected`, identité épinglée | PASS historique | **PASS (PC public + Pixel)** | E2E 7/7, A3/chat |
| NET-05 | `backend.health` via Funnel | PASS | **PASS (PC public)** | mock et OpenClaw |
| NET-06 | Chat réel via relais/adaptateur autorisé | PASS historique | **PASS (PC public + Android réel)** | message synthétique |
| NET-07 | Coupure réseau → OFFLINE | PARTIAL (ERROR observé) | **PASS (Pixel R3-C)** | `{"state":"OFFLINE"}` sur coupure Wi-Fi+données, reconnexion 10,346 s |
| NET-08 | Reconnexion après réseau | PASS | **PASS (Pixel R3-C)** | conversation conservée |
| NET-09 | Wi-Fi → LTE/5G → Wi-Fi | PASS historique | **PASS (Pixel R3-C)** | CELLULAR puis WIFI, 9,931 s |
| NET-10 | Redémarrage connecteur/FastAPI | PASS | **PASS (PC public + SIGKILL)** | identité/registre conservés |
| NET-11 | Redémarrage proxy/relais | PASS | **PASS (PC)** | proxy redémarré, connecteur reconnecté |
| NET-12 | Révocation avec session active | PASS historique | **PARTIAL** — DEVICE_REVOKED/REVOKED/effacement PASS, close 1012 au lieu de 4001 | A4-4 FAIL |
| NET-13 | Expiration requête/session/token | PARTIAL | **PARTIAL** | horloges JVM/Node ; Pixel non rejoué |
| NET-14 | Version/payload invalides | PASS | **PASS (public + tests)** | `v=1`, query inconnue, `serverId` absent → 400 |
| NET-15 | `/metrics` inaccessible publiquement | PASS | **PASS** | 404 public, upgrade 404 |
| NET-16 | Données locales inchangées | PASS historique | **PASS (Pixel R3-C)** | hash local stable, conversation conservée |

## 3. Conversation et compatibilité

| ID | Cas | R3-B | R3-C | Limite |
|---|---|---|---|---|
| CHAT-01 | Chat mock relayé | PASS | **PASS (PC public + Pixel)** | |
| CHAT-02 | Chat OpenClaw réel | PASS historique | **PASS (PC public + Android réel)** | test Pixel 15,658 s |
| CHAT-03 | Contexte consenti/minimisé | PASS | **PASS (tests + Pixel)** | catégories partagées vides, mandat limité |
| CHAT-04 | Refus sans capacité | PASS connecteur, Android fermait la session | **PASS** — session maintenue, `OPERATION_UNAVAILABLE`, anciennes opérations utilisables ; vérifié aussi via Funnel public avec un ancien appareil | `a162f70`, P-FUNNEL-07 |
| CHAT-05 | Mandat révoqué/expiré | PARTIAL | **PARTIAL** | Tests JVM/Node ; pas de parcours Pixel dédié |
| CHAT-06 | Déconnexion pendant chat en vol | PARTIAL | **PARTIAL** | Tests JVM/Node ; pas de coupure Pixel pendant un chat en vol |
| CHAT-07 | Reconnexion sans doublon | PASS | **PASS (public + Pixel)** | conversation conservée, aucun rejeu |
| CHAT-08 | Payload malformé/trop grand | PASS | **PASS** | |
| CHAT-09 | Aucune action sans confirmation | PASS historique | **PASS (Pixel R3-C)** | test chat : aucune proposition `EXECUTED` |
| CHAT-10 | Provenance mock/OpenClaw | PASS historique | **PASS (Pixel R3-C)** | « OpenClaw via PC » asserté |

## 4. Identité et révocation

| ID | Cas | R3-B | R3-C | Limite |
|---|---|---|---|---|
| A3-1 | Appairage synthétique via le vrai Keystore | PASS ancien APK | **PASS (APK R3-C)** — 1,149 s | |
| A3-2 | Connexion protégée | PASS | **PASS (Pixel R3-C)** | Funnel public |
| A3-3 | Force-stop et relance | PASS | **PASS (0,89 s)** | |
| A3-4 | Conservation du serverId et de la clé publique | PASS | **PASS** | marqueur A3 |
| A3-5 | Conservation du token chiffré | PASS | **PASS** | préfs sans clair, Keystore |
| A3-6 | Redémarrage Android | PASS | **PASS (1,439 s)** | |
| A3-7 | Nouvelle authentification sans ressaisie | PASS | **PASS** | |
| A3-8 | Données ATLAS préexistantes inchangées | PASS | **PASS (périmètre R3-C)** | hash local stable ; aucune donnée R3-B pour user 0 |
| A4-1 | Session authentifiée active | PASS | **PASS** | |
| A4-2 | Révocation côté PC | PASS | **PASS** | marqueur + `revoke-device` |
| A4-3 | Réception `DEVICE_REVOKED` | PASS | **PASS** | |
| A4-4 | Fermeture WebSocket `4001` | PARTIAL | **FAIL** — le relais ferme avec `1012` | probes public/local/direct |
| A4-5 | État Android `REVOKED` | PASS | **PASS** | |
| A4-6 | Absence de reconnexion authentifiée | PASS | **PASS** | |
| A4-7 | Suppression explicite des identifiants | PASS | **PASS** — chemin production `clearCredentials()` | clic UI non rejoué après l'échec A4-4 |
| A4-8 | Effacement credential + alias Keystore | PARTIAL | **PASS** | test R3-C dédié |
| A4-9 | Relance sans réappairage implicite | PASS | **PASS (1,122 s)** | |
| A4-10 | Nouvel appairage explicite | PASS | **PASS (2,386 s)** | |

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
| UI-01 | Connexion/révocation | PASS historique | **PARTIAL** — écran de révocation non rejoué jusqu'au clic après l'échec A4-4 ; état REVOKED observé | |
| UI-02 | Conversation, brouillon, rotation | PASS historique | **PASS (Pixel R3-C)** | test chat : provenance, brouillon/historique |
| UI-03 | Erreur réseau | PASS historique | **PARTIAL** — OFFLINE observé, message UI non asserté | |
| UI-04 | Saisie et stockage du token | PARTIAL | **PARTIAL** — champ masqué, token jamais réaffiché, stockage Keystore vérifié ; frappe UI non rejouée | |
| UI-06 | Retour HOME | NOT_RUN | **PASS (Pixel R3-C)** | launcher puis relance, hash local inchangé |
| UI-07 | Mode hors ligne | PARTIAL | **PASS (Pixel R3-C)** | écran local hors ligne, aucun crash |
| UI-08 | Police agrandie, rotation, TalkBack | PARTIAL | **PARTIAL** — police 1,3 et paysage PASS ; TalkBack NON EXÉCUTÉ | |

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
  NET-07 (Pixel : OFFLINE observé, reconnexion)
  R-22 (JVM/Node, deux chemins)
  Proxy 18/18
  Node 81/81

R3-B PARTIAL -> R3-C PASS/BLOCKED :
  NET-13 -> PARTIAL (horloges contrôlées)
  A4 -> PASS sauf A4-4 FAIL (relais 1012 au lieu de 4001)
  UI-07 -> PASS (Pixel hors ligne)

R3-B PASS -> NON_REGRESSION_PASS :
  Backend 196/1, JVM 712, Node 81, Proxy 18, lint/build
  A3 Pixel (pairing/force-stop/reboot)
  Chat OpenClaw réel Android
  UI-02/UI-06, préservation des données R3-C

R3-B PASS -> NOT_REPLAYED_R3C :
  aucun sur le périmètre exécuté ; UI-04 (frappe manuelle) et TalkBack
  restent PARTIAL/NOT_RUN

R3-B BLOCKED -> R3-C :
  WGER-01 -> BLOCKED_AUTH
  systemd persistant -> BLOCKED_AUTHORIZATION
  Pixel -> exécuté ; A4-4 FAIL relais
```

## 9. Verdicts

| Verdict | Conditions | État R3-C |
|---|---|---|
| `GO_PREPARED_WITH_LIMITATIONS` | code compile, tests passent, qualification externe manquante | **ATTEINT** — code/tests/preuves publiques solides ; A4-4, UI-04, TalkBack, NET-13 restent limités |
| `GO_REMOTE_WITH_LIMITATIONS` | APK final installé sur Pixel, A3/A4, chat réel, Funnel public, Noise, coupure/reprise, aucune perte, aucun P0/P1, revue favorable | **NON ATTEINT** — toutes les conditions sont réunies sauf **A4-4** (close 1012 au lieu de 4001) ; décision requise : corriger le relais ou accepter explicitement 1012 |
| `GO_V63` | toutes les gates produit V6.3, fonctionnement durable | **NON ATTEINT** — A4-4, H24/Doze, systemd persistant, wger non qualifiés |
