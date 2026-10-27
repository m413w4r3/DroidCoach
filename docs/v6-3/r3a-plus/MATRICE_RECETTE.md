# ATLAS V6.3 — R3-A+ — Matrice de recette

Statuts : `PASS`, `FAIL`, `PARTIAL`, `BLOCKED`, `NOT_RUN`.
Environnement principal : **Pixel 9 Pro XL physique, Android 17 / API 37**.
Toute preuve est liée à un SHA et à une commande. Une preuve JVM ne vaut pas
preuve instrumentée ; une preuve Pixel ne vaut pas preuve Internet réelle.

## 1. Gates techniques

| ID | Gate | Commande | Statut |
|---|---|---|---|
| T1 | Backend complet | `backend/.venv/bin/python -m pytest -q` | PENDING |
| T2 | JVM Android complète | `android/gradlew :app:testDebugUnitTest --rerun-tasks --max-workers=1` | PENDING |
| T3 | Lint | `android/gradlew :app:lintDebug` | PENDING |
| T4 | Build debug | `android/gradlew :app:assembleDebug` | PENDING |
| T5 | Compilation androidTest | `android/gradlew :app:assembleDebugAndroidTest` | PENDING |
| T6 | Tests instrumentés API 37 | `am instrument` ciblé sur Pixel | PENDING |
| T7 | Tests Node transport | `npm test` dans `integrations/relay/pc` | PENDING |
| T8 | Build relais local | `docker build` image épinglée | PENDING |
| T9 | Installation propre connecteur | répertoire vierge, sans clone Paseo | PENDING |
| T10 | Unité systemd utilisateur | `systemd-analyze verify` + test isolé | PENDING |
| T11 | WSS bout en bout Pixel | recette C | PENDING |
| T12 | Calendrier C1–C5 Pixel | recette C | PENDING |
| T13 | Backend routes connecteur | pytest + test réel lecture seule | PENDING |
| T14 | Revue indépendante | lecture seule | PENDING |

## 2. Transport WSS

| ID | Cas | Statut |
|---|---|---|
| W1 | WSS CA de test approuvée | PENDING |
| W2 | Mauvais hostname | PENDING |
| W3 | Certificat non approuvé | PENDING |
| W4 | TLS interrompu | PENDING |
| W5 | `ws://` distant refusé | PENDING |
| W6 | Mauvaise identité Noise | PENDING |
| W7 | Reconnexion après coupure TLS | PENDING |
| W8 | FastAPI inaccessible publiquement | PENDING |
| W9 | Révocation | PENDING |
| W10 | Redémarrage connecteur/relais | PENDING |

## 3. Fiabilité connecteur

| ID | Cas | Statut |
|---|---|---|
| R1 | Redémarrage connecteur | PENDING |
| R2 | Redémarrage relais | PENDING |
| R3 | Redémarrage FastAPI | PENDING |
| R4 | Arrêt brutal connecteur | PENDING |
| R5 | Identité conservée | PENDING |
| R6 | Registre conservé | PENDING |
| R7 | Appareil révoqué après redémarrage | PENDING |
| R8 | Clients synthétiques concurrents | PENDING |
| R9 | Registre illisible → échec fermé | PENDING |
| R10 | Réponse perdue après effet backend | PENDING |
| R11 | Requête expirée | PENDING |
| R12 | Même idempotency key, même contenu | PENDING |
| R13 | Même clé, contenus différents | PENDING |

## 4. Appairage

| ID | Cas | Statut |
|---|---|---|
| P1 | Fichier token déjà présent | PENDING |
| P2 | Répertoire inaccessible | PENDING |
| P3 | Fichier refusé | PENDING |
| P4 | Appairage concurrent | PENDING |
| P5 | Révocation concurrente | PENDING |
| P6 | Redémarrage pendant appairage | PENDING |
| P7 | Token absent de stdout | PENDING |

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
| A1 | Données ATLAS préexistantes préservées après mise à jour APK | PENDING |
| A2 | Plan/habitudes/mémoire/consentements après force-stop | PENDING |
| A3 | Identité relayée après redémarrage | PENDING |
| A4 | Credentials supprimés après révocation explicite | PENDING |
| A5 | Aucun secret dans les sauvegardes ordinaires | PENDING |

## 7. Verdict

Verdict R3-A+ : PENDING. Le statut R3-A reste `GO_WITH_LIMITATIONS` et V6.3
global reste `NO_GO`.
