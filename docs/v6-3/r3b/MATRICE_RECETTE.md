# ATLAS V6.3 — R3-B — Matrice de recette

Mise à jour finale: **2026-10-28**. Statuts : `PASS`, `FAIL`, `PARTIAL`,
`BLOCKED`, `NOT_RUN`.

Code intégré de référence : `7754005` (branche de campagne). L'APK final
`1a877422…` contient le correctif de consentement au dispatch et les preuves
A4 renforcées. L'APK `b55776a9…` a servi aux tests Pixel A3/A4/chat/réseau
avant ce correctif; les tests du nouvel APK sont bloqués par la panne
d'ingress public Funnel (R-18/R-20).

## 1. Gates techniques

| ID | Gate | Commande / environnement | Statut final |
|---|---|---|---|
| T1 | Backend complet | `backend/.venv/bin/python -m pytest -q` | **PASS** — 196 passed, 1 skipped, 1 warning (arbre final `dc8f55e`) |
| T2 | JVM Android complète | `android/gradlew :app:testDebugUnitTest --rerun-tasks --max-workers=1` | **PASS** — 97 suites, 690 tests, 0 échec/erreur/ignoré (arbre final `7754005`) |
| T3 | Lint Android | `android/gradlew :app:lintDebug` | **PASS** — 0 erreur, 71 avertissements (arbre final) |
| T4 | Build debug | `android/gradlew :app:assembleDebug` | **PASS** — APK `1a877422…` (final) ; APK Pixel antérieur `b55776a9…`, signature debug |
| T5 | Build androidTest | `android/gradlew :app:assembleDebugAndroidTest` | **PASS** — APK `d9213e63…` (arbre final, assertions A4 4001/alias incluses) |
| T6 | Instrumentation API 37 | Pixel physique `45301FDAS001QT`, Android 17 / API 37 | **PARTIAL** — tests ciblés PASS sur l'APK `b55776a9`, avant le correctif de consentement ; re-test de l'APK final `1a877422` bloqué par l'indisponibilité de l'ingress public Funnel. Suite complète non lancée d'un bloc |
| T7 | Tests Node connecteur | `integrations/relay/pc: npm test` avec relais réel | **PASS** — 15 fichiers, 76 tests (arbre final `7754005`, proxy 4400 actif) |
| T8 | Image relais épinglée | `atlas-paseo-relay:r3a`, `/health` loopback | **PASS** — image locale vérifiée, `/health` 200 sur 127.0.0.1:4402 |
| T9 | Tests proxy filtrant | `node --test integrations/relay/proxy/test` | **PASS** — 18/18 (arbre final, relais réel 4402) |
| T10 | Unités systemd | `systemd-analyze --user verify` (sans installation) | **PARTIAL** — vérification statique PASS ; aucun service installé ni lancé |
| T11 | WSS public Funnel | `https://all.taildc7886.ts.net:8443/health`, `/ready` | **PASS (PC, qualification publique)** — TLS 1.3, `/health` 200, `/ready` 200, WSS 101, Noise protégé. Recontrôle public final indisponible (contrôle Tailscale injoignable, voir R-18) ; E2E final via MagicDNS PASS |
| T12 | Chat mock isolé | Backend mock + transport relayé | **PASS** — chat mock via Funnel public, replay sans doublon (preuve antérieure) ; tests Node 74/74 sur l'arbre final |
| T13 | Chat OpenClaw | Isolé et/ou personnel autorisé | **PASS** — OpenClaw personnel réel via Funnel public (`agent_status=connected`, 1 proposition, max 4 295 ms) ; E2E final via MagicDNS `connected` |
| T14 | A3 Keystore | Pairing + force-stop + redémarrage Android | **PASS (réserve R-19)** — `R3bA3PairingInstrumentedTest` et `R3bA3PersistenceInstrumentedTest` sur Pixel/APK antérieur ; journaux horodatés partiels ; re-test final bloqué par l'ingress |
| T15 | A4 révocation | Session active + révocation PC + effacement local | **PARTIAL** — parcours A4 PASS sur l'APK antérieur ; assertions renforcées close 4001 et alias Keystore non rejouées sur l'APK final à cause de l'ingress public. Voir A4-4/A4-8 |
| T16 | Données Pixel | Hashes ATLAS et compteurs calendrier | **PASS** — 5/5 hashes, calendriers 12, événements 91 après restauration finale |
| T17 | Revue indépendante | Lecture seule sur SHA final | **RUNNING** — revue 1 `BLOQUÉ` (P1 corrigé en `7754005`) ; re-revue du SHA final à exécuter ; voir `REVUE_INDEPENDANTE.md` |

## 2. Réseau public et Funnel

| ID | Critère | Statut final |
|---|---|---|
| NET-01 | DNS public et TLS valide | **PASS (PC)** — DNS public via DoH, certificat Let's Encrypt, TLS 1.3, SNI/SAN conformes (qualification 13:00). Recontrôle public 15:15 empêché par la connectivité Tailscale du PC (R-18), sans changement de configuration |
| NET-02 | WSS public sur 8443, sans VPN Android | **PASS (Pixel)** — l'app Android utilise l'URL Funnel publique; tests Wi-Fi/LTE/5G et handover sans VPN ni `adb reverse` |
| NET-03 | Absence d'`adb reverse` et de proxy USB | **PASS** — `adb reverse --list` vide, aucun proxy HTTP pendant les tests réseau |
| NET-04 | Noise NK `protected` et identité épinglée | **PASS** — handshake protégé et auth appareil via Funnel |
| NET-05 | `backend.health` via Funnel jusqu'au PC | **PASS** — `{"status":"ok"}` via Funnel et FastAPI 8100 |
| NET-06 | Chat réel via le relais et l'adaptateur autorisé | **PASS** — OpenClaw personnel réel via Funnel, `PERSONAL_OPENCLAW_PASS`; E2E final `agent_status=connected` |
| NET-07 | Coupure LTE/5G et état OFFLINE | **PARTIAL** — coupure mode avion et reprise PASS; l'état observé est `ERROR`, pas `OFFLINE`. Le libellé exact n'est pas prouvé |
| NET-08 | Reconnexion après restauration réseau | **PASS** — reprise RELAY_CONNECTED en 28,396 s, conversation locale conservée |
| NET-09 | Wi-Fi → LTE/5G → Wi-Fi | **PASS** — réseau CELLULAR observé, `mDataConnectionState=2`, reprise Wi-Fi; test 24,761 s |
| NET-10 | Redémarrage connecteur/FastAPI | **PASS** — connecteur arrêté/relancé (Pixel : ERROR 19 s puis RELAY_CONNECTED 1 s; 39,852 s) et arrêt/relance FastAPI réel (`BACKEND_UNAVAILABLE` puis `ok`) |
| NET-11 | Redémarrage proxy/relais | **PASS (PC)** — proxy arrêté/relancé, relais `atlas-relay-r3b-probe` redémarré; `/health` et `/ready` 200, `/metrics` 404 en local et via Funnel/MagicDNS. Aucune session Android active pendant ce test ciblé |
| NET-12 | Révocation avec session active | **PASS** — A4 sur Pixel, session active, DEVICE_REVOKED/4001/REVOKED |
| NET-13 | Expiration de requête, session et token | **PARTIAL** — expiration de requête et idempotence testées; expiration de session (1 h) et de token non rejouée sur Pixel |
| NET-14 | Version et payload invalides | **PASS** — `v=1`, query inconnue, serverId manquant, message >2000 refusés |
| NET-15 | `/metrics` inaccessible publiquement | **PASS** — `/metrics` public 404, upgrade `/metrics` 404 |
| NET-16 | Données locales inchangées | **PASS** — restauration finale 5/5 hashes, calendrier 12/91, aucun artefact de test |

## 3. Conversation relayée

| ID | Cas | Statut final |
|---|---|---|
| CHAT-01 | Conversation sur adaptateur mock isolé | **PASS** — chat mock via Funnel public et backend mock |
| CHAT-02 | Conversation sur instance OpenClaw de test isolée si disponible | **PASS** — `PERSONAL_OPENCLAW_PASS` via Funnel; E2E final `connected` |
| CHAT-03 | Contexte réellement consenti vérifié | **PASS** — payload minimisé et audit de contexte vérifiés Node/JVM; Pixel : catégories partagées du mandat vides, aucune donnée interdite requise, provenance `agent_status:connected` |
| CHAT-04 | Refus des opérations sans capacité | **PASS** — appareil sans `coach.chat` reçoit `OPERATION_UNAVAILABLE` |
| CHAT-05 | Mandat révoqué ou expiré | **PARTIAL** — couverture JVM/Node; pas de nouveau parcours Pixel dédié au mandat expiré |
| CHAT-06 | Déconnexion en cours de requête | **PARTIAL** — tests Node de déconnexion; pas de coupure réseau Pixel pendant un chat en vol |
| CHAT-07 | Reconnexion et absence de doublon | **PASS** — rejeu `replayed=true`, aucun second POST; reconnexion Noise testée |
| CHAT-08 | Payload malformé, trop grand ou mauvaise version | **PASS** — refus `INVALID_REQUEST`/`PAYLOAD_TOO_LARGE` public et tests Node |
| CHAT-09 | Proposition non exécutée sans confirmation | **PASS** — test Pixel : aucune proposition `EXECUTED` sans confirmation; mode PROPOSALS_ONLY |
| CHAT-10 | Distinction visible de provenance mock/personnelle | **PASS** — UI Pixel « OpenClaw via PC » et provenance par message; mock distingué |

## 4. Identité persistante A3

| ID | Cas | Statut final |
|---|---|---|
| A3-1 | Appairage synthétique via le vrai Keystore | **PASS** — Pixel, device synthétique |
| A3-2 | Connexion protégée | **PASS** — RELAY_CONNECTED via Funnel |
| A3-3 | Force-stop et relance | **PASS** — `R3bA3PersistenceInstrumentedTest` |
| A3-4 | Conservation du serverId et de la clé publique | **PASS** — vérifié après redémarrage |
| A3-5 | Conservation du token chiffré | **PASS** — aucun token en clair; Keystore réel |
| A3-6 | Redémarrage Android | **PASS** — Pixel redémarré, identité conservée |
| A3-7 | Nouvelle authentification sans ressaisie | **PASS** — reconnexion après relance/redémarrage |
| A3-8 | Données ATLAS préexistantes inchangées | **PASS** — hashes A3 vérifiés; restauration finale 5/5 |

## 5. Révocation A4

| ID | Cas | Statut final |
|---|---|---|
| A4-1 | Session authentifiée active | **PASS** — session Pixel active avant révocation |
| A4-2 | Révocation côté PC | **PASS** — `revoke-device` sur le connecteur |
| A4-3 | Réception `DEVICE_REVOKED` | **PASS** — trame reçue avant fermeture (grâce 200 ms) |
| A4-4 | Fermeture WebSocket `4001` | **PARTIAL** — code observé antérieurement et assertion `lastCloseCode == 4001` ajoutée dans `7754005` ; test renforcé non rejoué sur Pixel à cause de l'ingress public |
| A4-5 | État Android `REVOKED` | **PASS (APK antérieur)** — état et message vérifiés ; re-test final bloqué par l'ingress |
| A4-6 | Absence de reconnexion authentifiée | **PASS** — aucune reconnexion authentifiée |
| A4-7 | Suppression explicite des identifiants locaux | **PASS** — action UI explicite |
| A4-8 | Vérification de l'effacement des credentials | **PARTIAL** — credential/ciphertext effacé et testé sur l'APK antérieur ; suppression de l'alias Keystore et assertion ajoutées dans `7754005`, non rejouées sur Pixel à cause de l'ingress |
| A4-9 | Relance sans réappairage implicite | **PASS** — PAIRING_REQUIRED |
| A4-10 | Nouvel appairage explicite possible | **PASS** — device6 réappairé explicitement |

## 6. Résilience PC

| ID | Cas | Statut final |
|---|---|---|
| PC-01 | Démarrage supervisé | **PASS** — harnais `r3b-resilience.mjs`, métriques 200 |
| PC-02 | Arrêt propre SIGTERM | **PASS** — code 0, port libéré |
| PC-03 | SIGKILL et redémarrage | **PASS** — même identité, même registre |
| PC-04 | Reconnexion au relais | **PASS** — relais 4402 et Funnel |
| PC-05 | Identité et registre conservés | **PASS** — empreinte et hash stables |
| PC-06 | Absence de fuite de token | **PASS** — scan stdout/stderr du harnais |
| PC-07 | Redémarrage réel de FastAPI | **PASS** — uvicorn arrêté/redémarré, preuve publique |
| PC-08 | Erreur explicite pendant l'indisponibilité FastAPI | **PASS** — `BACKEND_UNAVAILABLE` |
| PC-09 | Réussite après restauration | **PASS** — `health_status=ok` après redémarrage |
| PC-10 | Absence de duplication des requêtes incertaines | **PASS** — un seul POST, rejeu bloqué après restart |
| PC-11 | Rollback et intégrité de l'état | **PASS** — manifeste et empreinte vérifiés |

## 7. Vérifications complémentaires

| ID | Cas | Statut final |
|---|---|---|
| WGER-01 | Lecture seule réelle `/v1/sport/plan` si autorisée | **BLOCKED** — autorisation explicite absente; aucune lecture wger exécutée |
| UI-01 | Connexion et révocation | **PASS** — A4 UI Pixel |
| UI-02 | Conversation | **PASS** — chat Pixel, brouillon, rotation, provenance |
| UI-03 | Erreur réseau | **PASS** — état ERROR observé et reprise; pas de crash |
| UI-04 | Saisie du token | **PARTIAL** — l'appairage Pixel utilise un fichier de token synthétique; pas de parcours de saisie manuelle rejoué |
| UI-05 | Persistance de l'appairage | **PASS** — A3 force-stop/redémarrage |
| UI-06 | Retour HOME | **NOT_RUN** — non exécuté pendant cette campagne |
| UI-07 | Mode hors ligne | **PARTIAL** — mock local couvert JVM; pas de parcours Pixel OFFLINE dédié |
| UI-08 | Accessibilité (police, rotation, TalkBack) | **PARTIAL** — rotation et brouillon PASS sur Pixel; TalkBack et police agrandie non testés |

## 8. Règles de preuve

- Une preuve JVM ne vaut pas preuve instrumentée.
- Une preuve émulateur ne vaut pas preuve Pixel.
- Une preuve Wi-Fi personnelle ne vaut pas preuve LTE/5G.
- Une preuve loopback ne vaut pas preuve Funnel public.
- Un `tailscale funnel status` seul ne vaut pas preuve de connectivité Internet.
- Le PC éteint reste indisponible : le Funnel n'apporte aucune haute
  disponibilité indépendante de la machine.
- Le recontrôle public final du 2026-10-28 15:15 a échoué au TLS parce que
  `tailscaled` ne joignait plus le plan de contrôle Tailscale (réseau
  injoignable); aucune configuration Tailscale n'a été modifiée. Voir R-18.
- La revue indépendante 1 a rendu `BLOQUÉ` sur un P1 de consentement périmé.
  Le correctif `7754005` est couvert par 690 tests JVM et 76 tests Node ; le
  re-test Pixel de l'APK final est bloqué par l'ingress public (R-20).
