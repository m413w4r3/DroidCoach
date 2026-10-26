# ATLAS V6.3 R3-A — Matrice de recette

Statuts : `PASS`, `FAIL`, `BLOCKED`, `NOT_RUN`, `PARTIAL`.
Environnement : `emulator-5554`, Android 17 / API 37. Toute preuve est liée à un
SHA et à une commande. Une preuve émulateur ne vaut pas preuve Pixel.

## 1. Gates techniques

| ID | Gate | Commande | Statut |
|---|---|---|---|
| T1 | Backend complet | `backend/.venv/bin/python -m pytest -q` | NOT_RUN |
| T2 | JVM Android complète | `android/gradlew :app:testDebugUnitTest --rerun-tasks --max-workers=1` | NOT_RUN |
| T3 | Lint | `android/gradlew :app:lintDebug` | NOT_RUN |
| T4 | Build debug | `android/gradlew :app:assembleDebug` | NOT_RUN |
| T5 | Compilation androidTest | `android/gradlew :app:assembleDebugAndroidTest` | NOT_RUN |
| T6 | Tests instrumentés API 37 | `am instrument` ciblé | NOT_RUN |
| T7 | Tests Node transport | `npm test` dans `integrations/relay/pc` | **PASS** — 23 tests, 6 fichiers, sur SHA intégré |
| T8 | Build relais local | `docker build -t atlas-paseo-relay:r3a references/repos/paseo-relay` | **PASS** le 2026-10-26 |
| T9 | Vecteurs Noise Kotlin = upstream | tests JVM ciblés | NOT_RUN |
| T10 | Interop Kotlin ↔ Node | relais local + client de test | NOT_RUN |
| T11 | APK installé = SHA annoncé | `sha256sum` + `dumpsys package` + pull | NOT_RUN |
| T12 | PC live Node → relais → connecteur → FastAPI | `node scripts/pc-live-check.mjs` | **PASS** — handshake protégé, auth, proposition V3, rejeu, cache |
| T13 | Calendrier local réel + Etar | tests instrumentés + captures | **PASS** — création, relecture, move/edit/delete, visibilité |

## 2. Transport — cryptographie

| ID | Cas | Statut |
|---|---|---|
| X1 | Bonne identité serveur | NOT_RUN |
| X2 | Mauvaise identité serveur | NOT_RUN |
| X3 | Mode legacy refusé des deux côtés | NOT_RUN |
| X4 | Handshake altéré | NOT_RUN |
| X5 | Enregistrement altéré | NOT_RUN |
| X6 | Rejeu d'un enregistrement | NOT_RUN |
| X7 | Duplication | NOT_RUN |
| X8 | Réordonnancement | NOT_RUN |
| X9 | Troncature | NOT_RUN |
| X10 | Message trop grand | NOT_RUN |
| X11 | Reconnexion avec handshake frais | NOT_RUN |
| X12 | Révocation client | NOT_RUN |
| X13 | Mauvais token applicatif | NOT_RUN |
| X14 | Nonce de hello rejoué | NOT_RUN |

## 3. Transport — fonctionnement

| ID | Cas | Statut |
|---|---|---|
| F1 | PC seul, relais absent | NOT_RUN |
| F2 | Android seul, relais absent | NOT_RUN |
| F3 | Relais présent, backend absent | NOT_RUN |
| F4 | Backend restauré | NOT_RUN |
| F5 | Perte réseau simulée | NOT_RUN |
| F6 | Coupure relais | NOT_RUN |
| F7 | Redémarrage connecteur PC | NOT_RUN |
| F8 | Changement d'identité serveur | NOT_RUN |
| F9 | Demande expirée | NOT_RUN |
| F10 | Demande répétée (idempotence) | NOT_RUN |
| F11 | Résultat reçu deux fois | NOT_RUN |
| F12 | Résultat après changement de version du plan | NOT_RUN |
| F13 | File de requêtes pleine | NOT_RUN |
| F14 | Redémarrage application Android | NOT_RUN |
| F15 | Aucun `adb reverse` | NOT_RUN |

## 4. Transport — E2E majeur

| ID | Cas | Statut |
|---|---|---|
| E1 | Android → relais → Noise → connecteur → FastAPI → Android | NOT_RUN |
| E2 | Réponse réellement issue de FastAPI | NOT_RUN |
| E3 | Révision `DayPlan` via transport | NOT_RUN |
| E4 | Validation Android existante appliquée | NOT_RUN |
| E5 | Fermeture relais → déconnexion constatée | NOT_RUN |
| E6 | Relais rétabli → reconnexion valide | NOT_RUN |
| E7 | Aucun doublon ni requête périmée rejouée | NOT_RUN |

## 5. Calendrier

Les 22 scénarios C1–C22 sont détaillés dans `CALENDRIER_ETAR.md`; tous sont
`NOT_RUN` au lancement.

| ID | Gate | Statut |
|---|---|---|
| K1 | `READ_CALENDAR` refusé | PARTIAL — refus fail-closed couvert côté moteur JVM R2 ; parcours UI à rejouer |
| K2 | `READ_CALENDAR` accordé | **PASS** — accordé par UiAutomation pour la qualification locale |
| K3 | `WRITE_CALENDAR` refusé | PARTIAL — couvert par le moteur ; parcours UI à rejouer |
| K4 | `WRITE_CALENDAR` accordé | **PASS** — accordé par UiAutomation ; écriture réelle effectuée |
| K5 | Calendrier local sélectionné | PARTIAL — calendrier ATLAS créé et utilisé ; sélection UI explicite à câbler |
| K6 | Calendrier étranger rejeté | NOT_RUN (émulateur) — couvert JVM |
| K7 | Insertion réelle | **PASS** — `event_id=2` via provider réel |
| K8 | Relecture réelle | **PASS** — marqueurs et fenêtre relus |
| K9 | Visibilité Etar | **PASS** — capture `preuves/captures/etar-month-view.png` |
| K10 | Modification | **PASS** — titre relu et affiché dans Etar |
| K11 | Déplacement | **PASS** — 22:30→23:00, capture `etar-moved.png` |
| K12 | Suppression | **PASS** — provider vide, capture `etar-deleted.png` |
| K13 | Doublon | **PASS** — une seule ligne par `CUSTOM_APP_URI` |
| K14 | Crash | NOT_RUN |
| K15 | Reprise | NOT_RUN |
| K16 | Modification extérieure | NOT_RUN |
| K17 | Divergence | NOT_RUN |
| K18 | Révocation | NOT_RUN |
| K19 | Restauration fixtures | NOT_RUN |

## 6. Intégrations C

| ID | Cas | Statut |
|---|---|---|
| S1 | wger lecture réelle → contrat sport-plan-v1 → Android | NOT_RUN |
| S2 | États `FRESH`/`STALE`/`UNAVAILABLE`/`MISMATCH` | NOT_RUN |
| S3 | `trainingRef` persisté dans `DayPlan` | NOT_RUN |
| M1 | Parcours mémoire complet | NOT_RUN |
| U1 | Usage Access réel sur émulateur | NOT_RUN |
| U2 | Quotas et révocation | NOT_RUN |
| B1 | Plugin Bridge : tests existants non régressés | NOT_RUN |

## 7. Verdict

Verdict final à établir dans `HANDOFF_R3A.md`. Aucun `GO` V6.3 possible sans
recette Pixel physique et connexion extérieure réelle.
