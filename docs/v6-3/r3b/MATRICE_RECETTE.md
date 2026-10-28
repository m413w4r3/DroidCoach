# ATLAS V6.3 — R3-B — Matrice de recette

Statuts : `PASS`, `FAIL`, `PARTIAL`, `BLOCKED`, `NOT_RUN`.
Chaque ligne doit être liée à un SHA source, un SHA APK si applicable, un
environnement, une commande et une preuve expurgée. Une simulation ne vaut pas
preuve de service réel. Les statuts de ce document sont mis à jour au fil de la
campagne ; un statut initial `NOT_RUN` n'est jamais converti en `PASS` sans
preuve.

## 1. Gates techniques

| ID | Gate | Commande / environnement | Statut initial |
|---|---|---|---|
| T1 | Backend complet | `backend/.venv/bin/python -m pytest -q` | **PASS** — 196 passed, 1 skipped sur `dfe6da3` |
| T2 | JVM Android complète | `android/gradlew :app:testDebugUnitTest --rerun-tasks --max-workers=1` | **PASS** — 96 fichiers, 685 tests, 0 échec/erreur/ignoré sur `3ecccad` |
| T3 | Lint Android | `android/gradlew :app:lintDebug` | **PASS** — 0 erreur, 71 avertissements sur `3ecccad` |
| T4 | Build debug | `android/gradlew :app:assembleDebug` | **PASS** — APK `bf72c735…` sur `3ecccad`, signature debug `da301466…` |
| T5 | Build androidTest | `android/gradlew :app:assembleDebugAndroidTest` | **PASS** — APK androidTest `cd83bac8…` sur `3ecccad` |
| T6 | Instrumentation API 37 | Pixel physique / émulateur API 37 | NOT_RUN |
| T7 | Tests Node connecteur | `integrations/relay/pc: npm test` avec relais réel | **PASS** — 15 fichiers, 72 tests sur `dfe6da3`, proxy 4400 actif |
| T8 | Image relais épinglée | `atlas-paseo-relay:r3a`, `/health` loopback | **PASS** — image locale vérifiée, `/health` 200 sur 127.0.0.1:4402 |
| T9 | Tests proxy filtrant | `node --test integrations/relay/proxy/test` | **PASS** — 18/18 sur `dfe6da3`, intégration relais réel incluse |
| T10 | Unités systemd | `systemd-analyze --user verify` (sans installation) | **PARTIAL** — vérification statique PASS pour proxy et connecteur ; aucun service installé ni lancé |
| T11 | WSS public Funnel | `https://all.taildc7886.ts.net:8443/health`, `/ready` | **PASS (PC)** — TLS 1.3 valide, `/health` 200, `/ready` 200, WSS 101 ; Android traité en NET-02 |
| T12 | Chat mock isolé | Backend mock + transport relayé | **PASS** — chat mock via Funnel public, Noise protégé, replay sans doublon |
| T13 | Chat OpenClaw | Isolé et/ou personnel autorisé | **PASS** — OpenClaw personnel réel via Funnel public (`agent_status=connected`, 1 proposition, 4 295 ms max) |
| T14 | A3 Keystore | Pairing + force-stop + redémarrage Android | NOT_RUN |
| T15 | A4 révocation | Session active + révocation PC + effacement local | NOT_RUN |
| T16 | Données Pixel | Hashes ATLAS et compteurs calendrier | NOT_RUN |
| T17 | Revue indépendante | Lecture seule sur SHA final | NOT_RUN |

## 2. Réseau public et Funnel

| ID | Critère | Statut initial |
|---|---|---|
| NET-01 | DNS public et TLS valide | **PASS (PC)** — DNS public via DoH, certificat Let's Encrypt, TLS 1.3, SNI/SAN conformes |
| NET-02 | WSS public sur 8443, sans VPN Android | **PARTIAL** — WSS public 101 et Noise protégé depuis le PC ; Android LTE/5G à exécuter |
| NET-03 | Absence d'`adb reverse` et de proxy USB | NOT_RUN |
| NET-04 | Noise NK `protected` et identité épinglée | **PASS** — handshake protégé et auth appareil via l'IP publique |
| NET-05 | `backend.health` via Funnel jusqu'au PC | **PASS** — réponse `{"status":"ok"}` via Funnel public et FastAPI 8100 |
| NET-06 | Chat réel via le relais et l'adaptateur autorisé | **PASS** — OpenClaw personnel réel via Funnel public, `PERSONAL_OPENCLAW_PASS` |
| NET-07 | Coupure LTE/5G et état OFFLINE | NOT_RUN |
| NET-08 | Reconnexion après restauration réseau | NOT_RUN |
| NET-09 | Wi-Fi → LTE/5G → Wi-Fi | NOT_RUN |
| NET-10 | Redémarrage connecteur/FastAPI | **PARTIAL** — redémarrage connecteur et reprise relais PASS ; redémarrage FastAPI réel à rejouer |
| NET-11 | Redémarrage proxy/relais | NOT_RUN |
| NET-12 | Révocation avec session active | NOT_RUN |
| NET-13 | Expiration de requête, session et token | **PARTIAL** — expiration de requête et idempotence testées ; session/token à rejouer |
| NET-14 | Version et payload invalides | **PASS** — `v=1`, query inconnue, serverId manquant, message >2000 refusés |
| NET-15 | `/metrics` inaccessible publiquement | **PASS** — `/metrics` public 404, upgrade `/metrics` 404 |
| NET-16 | Données locales inchangées | NOT_RUN |

## 3. Conversation relayée

| ID | Cas | Statut initial |
|---|---|---|
| CHAT-01 | Conversation sur adaptateur mock isolé | **PASS** — chat mock via Funnel public et backend mock |
| CHAT-02 | Conversation sur instance OpenClaw de test isolée si disponible | **PASS** — `PERSONAL_OPENCLAW_PASS` via le Funnel public et l'adaptateur autorisé |
| CHAT-03 | Contexte réellement consenti vérifié | **PARTIAL** — payload exact et minimisation testés côté Node ; UI Android à vérifier |
| CHAT-04 | Refus des opérations sans capacité | **PASS** — appareil sans `coach.chat` reçoit `OPERATION_UNAVAILABLE` via le Funnel |
| CHAT-05 | Mandat révoqué ou expiré | **PARTIAL** — tests JVM/Node ; parcours Android à rejouer |
| CHAT-06 | Déconnexion en cours de requête | **PARTIAL** — tests Node de déconnexion ; Android à rejouer |
| CHAT-07 | Reconnexion et absence de doublon | **PASS** — rejeu public `replayed=true`, aucun second POST ; reconnexion Noise testée |
| CHAT-08 | Payload malformé, trop grand ou mauvaise version | **PASS** — refus public INVALID_REQUEST/PAYLOAD_TOO_LARGE et tests Node |
| CHAT-09 | Proposition non exécutée sans confirmation | **PARTIAL** — politique et tests existants ; Android à rejouer |
| CHAT-10 | Distinction visible de provenance mock/personnelle | **PARTIAL** — `agent_status` mock/connected disponible ; UI Android à vérifier |

## 4. Identité persistante A3

| ID | Cas | Statut initial |
|---|---|---|
| A3-1 | Appairage synthétique via le vrai Keystore | NOT_RUN |
| A3-2 | Connexion protégée | NOT_RUN |
| A3-3 | Force-stop et relance | NOT_RUN |
| A3-4 | Conservation du serverId et de la clé publique | NOT_RUN |
| A3-5 | Conservation du token chiffré | NOT_RUN |
| A3-6 | Redémarrage Android | NOT_RUN |
| A3-7 | Nouvelle authentification sans ressaisie | NOT_RUN |
| A3-8 | Données ATLAS préexistantes inchangées | NOT_RUN |

## 5. Révocation A4

| ID | Cas | Statut initial |
|---|---|---|
| A4-1 | Session authentifiée active | NOT_RUN |
| A4-2 | Révocation côté PC | NOT_RUN |
| A4-3 | Réception `DEVICE_REVOKED` | NOT_RUN |
| A4-4 | Fermeture WebSocket `4001` | NOT_RUN |
| A4-5 | État Android `REVOKED` | NOT_RUN |
| A4-6 | Absence de reconnexion authentifiée | NOT_RUN |
| A4-7 | Suppression explicite des identifiants locaux | NOT_RUN |
| A4-8 | Vérification de l'effacement des credentials | NOT_RUN |
| A4-9 | Relance sans réappairage implicite | NOT_RUN |
| A4-10 | Nouvel appairage explicite possible | NOT_RUN |

## 6. Résilience PC

| ID | Cas | Statut initial |
|---|---|---|
| PC-01 | Démarrage supervisé | **PASS** — harnais `r3b-resilience.mjs`, métriques 200 |
| PC-02 | Arrêt propre SIGTERM | **PASS** — code 0, port libéré |
| PC-03 | SIGKILL et redémarrage | **PASS** — même identité, même registre |
| PC-04 | Reconnexion au relais | **PASS** — relais 4402 et Funnel public |
| PC-05 | Identité et registre conservés | **PASS** — empreinte et hash stables |
| PC-06 | Absence de fuite de token | **PASS** — scan stdout/stderr du harnais |
| PC-07 | Redémarrage réel de FastAPI | **PASS** — uvicorn arrêté/redémarré, preuve publique |
| PC-08 | Erreur explicite pendant l'indisponibilité FastAPI | **PASS** — `BACKEND_UNAVAILABLE` via Funnel |
| PC-09 | Réussite après restauration | **PASS** — `health_status=ok` après redémarrage |
| PC-10 | Absence de duplication des requêtes incertaines | **PASS** — un seul POST, rejeu bloqué après restart |
| PC-11 | Rollback et intégrité de l'état | **PASS** — manifeste et empreinte vérifiés |

## 7. Vérifications complémentaires

| ID | Cas | Statut initial |
|---|---|---|
| WGER-01 | Lecture seule réelle `/v1/sport/plan` si autorisée | NOT_RUN |
| UI-01 | Connexion et révocation | NOT_RUN |
| UI-02 | Conversation | NOT_RUN |
| UI-03 | Erreur réseau | NOT_RUN |
| UI-04 | Saisie du token | NOT_RUN |
| UI-05 | Persistance de l'appairage | NOT_RUN |
| UI-06 | Retour HOME | NOT_RUN |
| UI-07 | Mode hors ligne | NOT_RUN |
| UI-08 | Accessibilité (police, rotation, TalkBack) | NOT_RUN |

## 8. Règles de preuve

- Une preuve JVM ne vaut pas preuve instrumentée.
- Une preuve émulateur ne vaut pas preuve Pixel.
- Une preuve Wi-Fi personnel ne vaut pas preuve LTE/5G.
- Une preuve loopback ne vaut pas preuve Funnel public.
- Un `tailscale funnel status` seul ne vaut pas preuve de connectivité Internet.
- Le PC éteint reste indisponible : le Funnel n'apporte aucune haute
  disponibilité indépendante de la machine.
