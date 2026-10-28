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
| T1 | Backend complet | `backend/.venv/bin/python -m pytest -q` | NOT_RUN |
| T2 | JVM Android complète | `android/gradlew :app:testDebugUnitTest --rerun-tasks --max-workers=1` | NOT_RUN |
| T3 | Lint Android | `android/gradlew :app:lintDebug` | NOT_RUN |
| T4 | Build debug | `android/gradlew :app:assembleDebug` | NOT_RUN |
| T5 | Build androidTest | `android/gradlew :app:assembleDebugAndroidTest` | NOT_RUN |
| T6 | Instrumentation API 37 | Pixel physique / émulateur API 37 | NOT_RUN |
| T7 | Tests Node connecteur | `integrations/relay/pc: npm test` avec relais réel | NOT_RUN |
| T8 | Image relais épinglée | `atlas-paseo-relay:r3a`, `/health` loopback | NOT_RUN |
| T9 | Tests proxy filtrant | `node --test integrations/relay/proxy/test` | NOT_RUN |
| T10 | Unités systemd | `systemd-analyze --user verify` (sans installation) | NOT_RUN |
| T11 | WSS public Funnel | `https://all.taildc7886.ts.net:8443/health`, `/ready` | NOT_RUN |
| T12 | Chat mock isolé | Backend mock + transport relayé | NOT_RUN |
| T13 | Chat OpenClaw | Isolé et/ou personnel autorisé | NOT_RUN |
| T14 | A3 Keystore | Pairing + force-stop + redémarrage Android | NOT_RUN |
| T15 | A4 révocation | Session active + révocation PC + effacement local | NOT_RUN |
| T16 | Données Pixel | Hashes ATLAS et compteurs calendrier | NOT_RUN |
| T17 | Revue indépendante | Lecture seule sur SHA final | NOT_RUN |

## 2. Réseau public et Funnel

| ID | Critère | Statut initial |
|---|---|---|
| NET-01 | DNS public et TLS valide | NOT_RUN |
| NET-02 | WSS public sur 8443, sans VPN Android | NOT_RUN |
| NET-03 | Absence d'`adb reverse` et de proxy USB | NOT_RUN |
| NET-04 | Noise NK `protected` et identité épinglée | NOT_RUN |
| NET-05 | `backend.health` via Funnel jusqu'au PC | NOT_RUN |
| NET-06 | Chat réel via le relais et l'adaptateur autorisé | NOT_RUN |
| NET-07 | Coupure LTE/5G et état OFFLINE | NOT_RUN |
| NET-08 | Reconnexion après restauration réseau | NOT_RUN |
| NET-09 | Wi-Fi → LTE/5G → Wi-Fi | NOT_RUN |
| NET-10 | Redémarrage connecteur/FastAPI | NOT_RUN |
| NET-11 | Redémarrage proxy/relais | NOT_RUN |
| NET-12 | Révocation avec session active | NOT_RUN |
| NET-13 | Expiration de requête, session et token | NOT_RUN |
| NET-14 | Version et payload invalides | NOT_RUN |
| NET-15 | `/metrics` inaccessible publiquement | NOT_RUN |
| NET-16 | Données locales inchangées | NOT_RUN |

## 3. Conversation relayée

| ID | Cas | Statut initial |
|---|---|---|
| CHAT-01 | Conversation sur adaptateur mock isolé | NOT_RUN |
| CHAT-02 | Conversation sur instance OpenClaw de test isolée si disponible | NOT_RUN |
| CHAT-03 | Contexte réellement consenti vérifié | NOT_RUN |
| CHAT-04 | Refus des opérations sans capacité | NOT_RUN |
| CHAT-05 | Mandat révoqué ou expiré | NOT_RUN |
| CHAT-06 | Déconnexion en cours de requête | NOT_RUN |
| CHAT-07 | Reconnexion et absence de doublon | NOT_RUN |
| CHAT-08 | Payload malformé, trop grand ou mauvaise version | NOT_RUN |
| CHAT-09 | Proposition non exécutée sans confirmation | NOT_RUN |
| CHAT-10 | Distinction visible de provenance mock/personnelle | NOT_RUN |

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
| PC-01 | Démarrage supervisé | NOT_RUN |
| PC-02 | Arrêt propre SIGTERM | NOT_RUN |
| PC-03 | SIGKILL et redémarrage | NOT_RUN |
| PC-04 | Reconnexion au relais | NOT_RUN |
| PC-05 | Identité et registre conservés | NOT_RUN |
| PC-06 | Absence de fuite de token | NOT_RUN |
| PC-07 | Redémarrage réel de FastAPI | NOT_RUN |
| PC-08 | Erreur explicite pendant l'indisponibilité FastAPI | NOT_RUN |
| PC-09 | Réussite après restauration | NOT_RUN |
| PC-10 | Absence de duplication des requêtes incertaines | NOT_RUN |
| PC-11 | Rollback et intégrité de l'état | NOT_RUN |

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
