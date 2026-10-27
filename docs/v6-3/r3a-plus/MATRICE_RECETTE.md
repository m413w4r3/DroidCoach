# ATLAS V6.3 — R3-A+ — Matrice de recette

Statuts : `PASS`, `FAIL`, `PARTIAL`, `BLOCKED`, `NOT_RUN`.
Environnement principal : **Pixel 9 Pro XL physique, Android 17 / API 37**.
Toute preuve est liée à un SHA et à une commande. Une preuve JVM ne vaut pas
preuve instrumentée ; une preuve Pixel ne vaut pas preuve Internet réelle.

## 1. Gates techniques

| ID | Gate | Commande | Statut |
|---|---|---|---|
| T1 | Backend complet | `backend/.venv/bin/python -m pytest -q` | **PASS** — 187 passed, 1 skipped sur `012b101` |
| T2 | JVM Android complète | `android/gradlew :app:testDebugUnitTest --rerun-tasks --max-workers=1` | **PASS** — 91 suites, 654 tests, 0 échec sur `fe54e20` (après merge calendrier) |
| T3 | Lint | `android/gradlew :app:lintDebug` | PENDING |
| T4 | Build debug | `android/gradlew :app:assembleDebug` | PENDING |
| T5 | Compilation androidTest | `android/gradlew :app:assembleDebugAndroidTest` | PENDING |
| T6 | Tests instrumentés API 37 | `am instrument` ciblé sur Pixel | PENDING |
| T5 | Compilation androidTest | `android/gradlew :app:assembleDebugAndroidTest` | **PASS** — `733cd78` (merge WSS) |
| T7 | Tests Node transport | `npm test` dans `integrations/relay/pc` | **PASS** — 10 fichiers / 40 tests sur le merge packaging, dépendance vendorée, relais réel ; `ATLAS_ALLOW_LOOPBACK_WS=1` encore requis par le test de reconnexion (correction B1 en cours) |
| T8 | Build relais local | `docker run ... atlas-paseo-relay:r3a` | **PASS** — image épinglée, `/health` 200 sur `127.0.0.1:4400` |
| T15 | Module relay Android | `:app:testDebugUnitTest --tests 'dev.atlas.coach.sync.relay.*'` | **PASS** — 36 tests, 6 suites sur `733cd78` |
| T9 | Installation propre connecteur | répertoire vierge, sans clone Paseo | **PASS** — copie hors dépôt sans `references/` sur `89f881e`, `npm ci --offline`, build, config 7/7, artefact `cc1102a…`, 40/40 tests |
| T10 | Unité systemd utilisateur | `systemd-analyze verify` + test isolé | **PARTIAL** — `systemd-analyze --user verify` PASS ; test transitoire isolé NOT_RUN (pas de gestionnaire utilisateur jetable) |
| T16 | Politique WSS du config checker | `node --test scripts/lib/config-policy.check.mjs` + smoke connecteur | **PASS** — 7/7 tests ; WSS remote accepté au démarrage, ws remote refusé |
| T11 | WSS bout en bout Pixel | recette C | PENDING |
| T12 | Calendrier C1–C5 Pixel | recette C | PENDING |
| T13 | Backend routes connecteur | pytest + test réel lecture seule | PENDING |
| T14 | Revue indépendante | lecture seule | PENDING |

## 2. Transport WSS

| ID | Cas | Statut |
|---|---|---|
| W1 | WSS CA de test approuvée | **PASS** — Pixel APK `66645143…`, round-trip health + plan revision ; Node/TLS aussi |
| W2 | Mauvais hostname | **PASS** — Pixel, certificat wrong-host refusé |
| W3 | Certificat non approuvé | **PASS** — Pixel, certificat self-signed refusé |
| W4 | TLS interrompu | **PASS** — Pixel, déconnexion puis reconnexion après redémarrage du proxy |
| W5 | `ws://` distant refusé | **PASS** — Pixel/JVM/Node |
| W6 | Mauvaise identité Noise | **PASS** — Pixel, TLS valide mais identité épinglée fausse refusée |
| W7 | Reconnexion après coupure TLS | **PASS** — Pixel, même test que W4 |
| W8 | FastAPI inaccessible publiquement | **PASS** — Pixel, plain HTTP via le proxy n'atteint jamais la sentinelle 8100 |
| W9 | Révocation | **PASS** — Pixel, `DEVICE_REVOKED` avant fermeture 4001, état REVOKED |
| W10 | Redémarrage connecteur/relais | **PASS** — Pixel, connecteur 6,6 s et relais 6,4 s, reprise health |

## 3. Fiabilité connecteur

| ID | Cas | Statut |
|---|---|---|
| R1 | Redémarrage connecteur | **PASS** — JVM + Pixel, identité/registre conservés, client reconnecté après heartbeat |
| R2 | Redémarrage relais | **PASS** — JVM fake + Pixel relais réel |
| R3 | Redémarrage FastAPI | **PARTIAL** — disponibilité synthétique et health réel ; arrêt/redémarrage réel de FastAPI non rejoué |
| R4 | Arrêt brutal connecteur | **PASS** — SIGKILL réel d'un processus enfant (JVM) |
| R5 | Identité conservée | **PASS** — JVM |
| R6 | Registre conservé | **PASS** — JVM |
| R7 | Appareil révoqué après redémarrage | **PASS** — JVM |
| R8 | Clients synthétiques concurrents | **PASS** — JVM, 8 clients |
| R9 | Registre illisible → échec fermé | **PASS** — JVM |
| R10 | Réponse perdue après effet backend | **PASS** — JVM, backend appelé une fois, rejeu bloqué |
| R11 | Requête expirée | **PASS** — JVM |
| R12 | Même idempotency key, même contenu | **PASS** — JVM |
| R13 | Même clé, contenus différents | **PASS** — JVM |

## 4. Appairage

| ID | Cas | Statut |
|---|---|---|
| P1 | Fichier token déjà présent | **PASS** — `pairing.test.ts` |
| P2 | Répertoire inaccessible | **PASS** — `pairing.test.ts` |
| P3 | Fichier refusé | **PASS** — `pairing.test.ts` |
| P4 | Appairage concurrent | **PASS** — `pairing.test.ts` + stress verrou |
| P5 | Révocation concurrente | **PASS** — `pairing.test.ts` |
| P6 | Redémarrage pendant appairage | **PASS** — `pairing.test.ts` |
| P7 | Token absent de stdout | **PASS** — CLI capturé, fichier 0600 |

## 5. Calendrier sur Pixel

| ID | Cas | Statut |
|---|---|---|
| C1 | Mort réelle du processus puis réconciliation | PENDING |
| C2 | Événement étranger → `FOREIGN_EVENT`, provider inchangé | PENDING |
| C3 | Calendrier synthétique indisponible → fail-closed | PENDING |
| C4 | Permissions READ/WRITE refusées, retirées, restaurées | PENDING |
| C5 | Fuseau/DST, redémarrage, édition/suppression externe, doublons, parallélisme | PENDING |

## 6. Persistance Android

| ID | Cas | Statut |
|---|---|---|
| A1 | Données ATLAS préexistantes préservées après mise à jour APK | **PASS** — 4 hashes identiques après installs intermédiaire et finale |
| A2 | Plan/habitudes/mémoire/consentements après force-stop | PENDING (recette calendrier/persistance) |
| A3 | Identité relayée après redémarrage | PENDING (identité key-store, vague C) |
| A4 | Credentials supprimés après révocation explicite | PARTIAL — révocation PC testée ; suppression locale UI à rejouer |
| A5 | Aucun secret dans les sauvegardes ordinaires | PASS (doc) — backup app-only hors repo, valeur du token non affichée |

## 7. Verdict

Verdict R3-A+ : PENDING. Le statut R3-A reste `GO_WITH_LIMITATIONS` et V6.3
global reste `NO_GO`.
