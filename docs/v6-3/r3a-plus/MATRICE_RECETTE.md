# ATLAS V6.3 — R3-A+ — Matrice de recette

Statuts : `PASS`, `FAIL`, `PARTIAL`, `BLOCKED`, `NOT_RUN`.
Environnement principal : **Pixel 9 Pro XL physique, Android 17 / API 37**.
Toute preuve est liée à un SHA et à une commande. Une preuve JVM ne vaut pas
preuve instrumentée ; une preuve Pixel ne vaut pas preuve Internet réelle.

## 1. Gates techniques

| ID | Gate | Commande | Statut |
|---|---|---|---|
| T1 | Backend complet | `backend/.venv/bin/python -m pytest -q` | **PASS** — 187 passed, 1 skipped sur `f6c11d0` |
| T2 | JVM Android complète | `android/gradlew :app:testDebugUnitTest --rerun-tasks --max-workers=1` | **PASS** — 94 suites, 669 tests, 0 échec sur `f6c11d0` |
| T3 | Lint | `android/gradlew :app:lintDebug` | **PASS** — 0 erreur, 62 avertissements sur `f6c11d0` |
| T4 | Build debug | `android/gradlew :app:assembleDebug` | **PASS** — source `f6c11d0` |
| T5 | Compilation androidTest | `android/gradlew :app:assembleDebugAndroidTest` | **PASS** — source `f6c11d0` |
| T6 | Tests instrumentés API 37 | `am instrument` ciblé sur Pixel | **PASS** — WSS W1–W10 + calendrier C1–C5 + nettoyage, sur APK `66645143…` ; suite UI R3-A historique NOT_RUN (risque données personnelles) |
| T7 | Tests Node transport | `npm test` dans `integrations/relay/pc` | **PASS** — 14 fichiers / 62 tests sur `f6c11d0`, sans variable d'env, relais réel |
| T8 | Build relais local | `docker run ... atlas-paseo-relay:r3a` | **PASS** — image épinglée, `/health` 200 sur `127.0.0.1:4400` |
| T9 | Installation propre connecteur | répertoire vierge, sans clone Paseo | **PASS** — copie hors dépôt sans `references/` sur `f6c11d0`, `npm ci --offline`, build, config 7/7, artefact `cc1102a…`, 14 fichiers / 62 tests |
| T10 | Unité systemd utilisateur | `systemd-analyze verify` + test isolé | **PARTIAL** — `systemd-analyze --user verify` PASS ; test transitoire isolé NOT_RUN (pas de gestionnaire utilisateur jetable) |
| T11 | WSS bout en bout Pixel | recette C | **PASS** — W1–W10 sur APK `66645143…` |
| T12 | Calendrier C1–C5 Pixel | recette C | **PASS** — C1–C5 + nettoyage sur APK `66645143…` |
| T13 | Backend routes connecteur | pytest + test réel lecture seule | **PARTIAL** — 187/1 ; route `/v1/sport/plan` présente et mappée ; lecture wger réelle NOT_RUN (token absent) |
| T14 | Revue indépendante | lecture seule | **NO_GO INITIAL / CORRECTIONS APPLIQUÉES / DELTA EN COURS** — cible delta `f6c11d0` ; 1 P1 + 3 P2 + 1 P3 corrigés ou expliqués |
| T15 | Module relay Android | `:app:testDebugUnitTest --tests 'dev.atlas.coach.sync.relay.*'` | **PASS** — inclus dans 94/669 sur `f6c11d0` |
| T16 | Politique WSS du config checker | `node --test scripts/lib/config-policy.check.mjs` + smoke connecteur | **PASS** — 7/7 tests ; WSS remote accepté, ws remote refusé |

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
| C1 | Mort réelle du processus puis réconciliation | **PASS** — Pixel, phase 1 SIGKILL (`Process crashed`), phase 2 RECONCILED, un seul événement |
| C2 | Événement étranger → `FOREIGN_EVENT`, provider inchangé | **PASS** — Pixel, ligne CalendarContract inchangée |
| C3 | Calendrier synthétique indisponible → fail-closed | **PASS** — Pixel, calendrier jetable vide supprimé, `CALENDAR_NOT_LOCAL` |
| C4 | Permissions READ/WRITE refusées, retirées, restaurées | **PASS** — READ refusé, WRITE refusé, restauration, révocation pendant l'opération → processus tué, aucune écriture |
| C5 | Fuseau/DST, redémarrage, édition/suppression externe, doublons, parallélisme | **PASS** — fuseaux explicites, édition/suppression externe, replay/parallèle, force-stop phase 1/2 |

## 6. Persistance Android

| ID | Cas | Statut |
|---|---|---|
| A1 | Données ATLAS préexistantes préservées après mise à jour APK | **PASS** — 4 hashes identiques après installs intermédiaire et finale |
| A2 | Plan/habitudes/mémoire/consentements après force-stop | **PASS** — 4 hashes identiques après tous les tests et force-stop |
| A3 | Identité relayée après redémarrage | **NOT_RUN** — les tests WSS utilisent le transport direct, pas le keystore de l'app |
| A4 | Credentials supprimés après révocation explicite | **PARTIAL** — révocation PC et état REVOKED testés ; suppression locale via l'UI à rejouer |
| A5 | Aucun secret dans les sauvegardes ordinaires | **PASS (doc)** — backup app-only hors repo, valeur du token non affichée |

## 7. Verdict

Verdict R3-A+ : **EN COURS** — WSS et calendrier PASS sur Pixel ; la revue
indépendante initiale a rendu NO_GO sur un P1 de runbook, corrigé avec les P2
associés ; la re-revue delta doit confirmer l'absence de P0/P1. Le statut R3-A
reste `GO_WITH_LIMITATIONS` et V6.3 global reste `NO_GO`.
