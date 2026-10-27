# ATLAS V6.3 R3-A — Matrice de recette

Statuts : `PASS`, `FAIL`, `BLOCKED`, `NOT_RUN`, `PARTIAL`.
Environnement : `emulator-5554`, Android 17 / API 37. Toute preuve est liée à un
SHA et à une commande. Une preuve émulateur ne vaut pas preuve Pixel.

## 1. Gates techniques

| ID | Gate | Commande | Statut |
|---|---|---|---|
| T1 | Backend complet | `backend/.venv/bin/python -m pytest -q` | **PASS** — 187 passed, 1 skipped |
| T2 | JVM Android complète | `android/gradlew :app:testDebugUnitTest --rerun-tasks --max-workers=1` | **PASS** — 91 suites, 649 tests, 0 échec (rejoué sur `ad42b1c`) |
| T3 | Lint | `android/gradlew :app:lintDebug` | **PASS** — 0 erreur, 70 avertissements |
| T4 | Build debug | `android/gradlew :app:assembleDebug` | **PASS** — source `ad42b1c` |
| T5 | Compilation androidTest | `android/gradlew :app:assembleDebugAndroidTest` | **PASS** — source `ad42b1c` |
| T6 | Tests instrumentés API 37 | `am instrument` ciblé | **PASS** — 17/17 R2 + 4/4 calendrier + 1/1 E2E relais + 1/1 app controller ; lifecycle 4/4 rejoué ; source `ad42b1c`, APK `e8937c62…` |
| T7 | Tests Node transport | `npm test` dans `integrations/relay/pc` | **PASS** — 24 tests, 7 fichiers, sur `ad42b1c` |
| T8 | Build relais local | `docker build -t atlas-paseo-relay:r3a references/repos/paseo-relay` | **PASS** le 2026-10-26 |
| T9 | Vecteurs Noise Kotlin = upstream | tests JVM ciblés | **PASS** — 25 tests, vecteurs Paseo + cross-vector Node à clés fixes |
| T10 | Interop Kotlin ↔ Node | relais local + client de test | **PASS** — Android Kotlin ↔ relais ↔ connecteur Node ↔ FastAPI, 4 phases |
| T11 | APK installé = SHA annoncé | `sha256sum` + `dumpsys package` + pull | **PASS** — APK final `e8937c62…`, installé le 2026-10-27 01:30:41 ; suites et E2E exécutés dessus |
| T12 | PC live Node → relais → connecteur → FastAPI | `node scripts/pc-live-check.mjs` | **PASS** — rerun 2026-10-27T00:39Z sur `ad42b1c` : handshake protégé, auth, proposition V3, rejeu, cache |
| T13 | Calendrier local réel + Etar | tests instrumentés + captures | **PASS** — création, relecture, move/edit/delete, visibilité ; lifecycle final rejoué sur APK `e8937c62` |
| T14 | Moteur durable + permissions réelles | tests instrumentés provider réel | **PASS** — reprise, idempotence, crash injecté, revoke/grant, divergence Etar ; correctif de re-création testé en JVM |
| T15 | Module relay Android | `:app:testDebugUnitTest --tests 'dev.atlas.coach.sync.relay.*'` | **PASS** — 25 tests sur SHA intégré |

## 2. Transport — cryptographie

| ID | Cas | Statut |
|---|---|---|
| X1 | Bonne identité serveur | **PASS** — E2E + vecteurs upstream |
| X2 | Mauvaise identité serveur | **PARTIAL** — handshake altéré JVM ; clé serveur explicitement fausse non testée |
| X3 | Mode legacy refusé des deux côtés | **PASS (JVM)** — `refusesLegacyReady...` |
| X4 | Handshake altéré | **PASS (JVM)** — tampering et ordre |
| X5 | Enregistrement altéré | **PASS (JVM)** — tampering |
| X6 | Rejeu d'un enregistrement | **PASS (JVM)** — replay rejeté |
| X7 | Duplication | **PASS (JVM)** — duplication rejetée |
| X8 | Réordonnancement | **PASS (JVM)** — reordered rejeté |
| X9 | Troncature | **PASS (JVM)** — troncature rejetée |
| X10 | Message trop grand | **PASS (JVM)** — taille vérifiée avant allocation |
| X11 | Reconnexion avec handshake frais | **PASS** — phases 3/4, handshake refait |
| X12 | Révocation client | **PARTIAL** — révocation PC non rejouée en E2E |
| X13 | Mauvais token applicatif | **PASS (JVM)** — auth refusée |
| X14 | Nonce de hello rejoué | **PARTIAL** — anti-rejeu PC testé, nonce Android non rejoué en E2E |

## 3. Transport — fonctionnement

| ID | Cas | Statut |
|---|---|---|
| F1 | PC seul, relais absent | **PASS (Node)** — reconnexion contrôlée sans relais |
| F2 | Android seul, relais absent | **PASS** — `OFFLINE`, requête refusée |
| F3 | Relais présent, backend absent | **PARTIAL** — testé avec stubs côté PC ; arrêt réel de FastAPI non rejoué |
| F4 | Backend restauré | **PARTIAL** — live check PC ; cycle d'arrêt réel non rejoué |
| F5 | Perte réseau simulée | **PASS** — coupure du relais |
| F6 | Coupure relais | **PASS** — phase 2 |
| F7 | Redémarrage connecteur PC | **PASS** — phase 4, reconnexion Android et révision valide |
| F8 | Changement d'identité serveur | NOT_RUN (test JVM de handshake altéré disponible) |
| F9 | Demande expirée | **PASS (JVM)** — expiration refusée |
| F10 | Demande répétée (idempotence) | **PASS** — E2E, même payload + clé |
| F11 | Résultat reçu deux fois | **PASS** — `replayed=true`, résultat identique |
| F12 | Résultat après changement de version du plan | NOT_RUN |
| F13 | File de requêtes pleine | **PASS (JVM)** — file bornée |
| F14 | Redémarrage application Android | **PASS** — chaque phase est un processus instrumenté neuf |
| F15 | Aucun `adb reverse` | **PASS** — liste vide avant/pendant l'E2E |

## 4. Transport — E2E majeur

| ID | Cas | Statut |
|---|---|---|
| E1 | Android → relais → Noise → connecteur → FastAPI → Android | **PASS** — test instrumenté sur `emulator-5554`, `10.0.2.2:4400` |
| E2 | Réponse réellement issue de FastAPI | **PASS** — `agent_status=ok`, proposition V3 déterministe |
| E3 | Révision `DayPlan` via transport | **PASS** — `plan.revision.request`, base_version 4 |
| E4 | Validation Android existante appliquée | **PASS** — `PlanRevisionProposalV3.parse` |
| E5 | Fermeture relais → déconnexion constatée | **PASS** — phase 2 `OFFLINE` |
| E6 | Relais rétabli → reconnexion valide | **PASS** — phase 3 |
| E7 | Aucun doublon ni requête périmée rejouée | **PASS** — rejeu idempotent, métriques 2 erreurs attendues sans doublon |

## 5. Calendrier

Les 22 scénarios C1–C22 sont détaillés dans `CALENDRIER_ETAR.md`. C1–C18 sont
PASS ou PARTIAL avec preuves provider/Etar ; C19 est PASS (JVM), C20 PARTIAL,
C21 PASS (révocation réelle) et C22 PASS (fixture restaurée sur l'APK final).
Les captures UI antérieures à `bfbb9db` sont identifiées comme historiques dans
`preuves/PROVENANCE.md`.

| ID | Gate | Statut |
|---|---|---|
| K1 | `READ_CALENDAR` refusé | **PARTIAL (JVM)** — refus fail-closed couvert ; parcours UI externe non rejoué |
| K2 | `READ_CALENDAR` accordé | **PASS** — accordé par UiAutomation pour la qualification locale |
| K3 | `WRITE_CALENDAR` refusé | **PASS** — `pm revoke` externe, test à processus neuf, `PERMISSION_DENIED` |
| K4 | `WRITE_CALENDAR` accordé | **PASS** — `pm grant` externe ; création/suppression réelles |
| K5 | Calendrier local sélectionné | **PASS** — diagnostic : consentements, provisionnement et sélection `ATLAS R3A TEST` |
| K6 | Calendrier étranger rejeté | **PASS (JVM)** — type `com.google` et type trompeur refusés |
| K7 | Insertion réelle | **PASS** — `event_id=2` via provider réel |
| K8 | Relecture réelle | **PASS** — marqueurs et fenêtre relus |
| K9 | Visibilité Etar | **PASS** — capture `preuves/captures/etar-month-view.png` |
| K10 | Modification | **PASS** — titre relu et affiché ; UI v3 confirmée |
| K11 | Déplacement | **PASS** — UI v3 10:00→11:00, même `_id=11`, capture `etar-ui-moved.png` |
| K12 | Suppression | **PASS** — UI v4 « Suppression », provider vide, capture `etar-ui-deleted.png` |
| K13 | Doublon | **PASS** — une seule ligne par `CUSTOM_APP_URI` |
| K14 | Crash | **PARTIAL** — crash injecté avant effet + reprise ; SIGKILL réel à rejouer |
| K15 | Reprise | **PASS** — `recoverPending()` sur provider réel, sans doublon |
| K16 | Modification extérieure | **PASS** — titre modifié dans l'UI Etar |
| K17 | Divergence | **PASS** — `DIVERGED` / `READ_BACK_MISMATCH` |
| K18 | Révocation | **PASS** — révocation externe + refus d'effet |
| K19 | Restauration fixtures | **PASS** — lifecycle restore/move/delete/restore sur APK final, `_id=18`, capture `etar-final-restored.png` |

## 6. Intégrations C

| ID | Cas | Statut |
|---|---|---|
| S1 | wger lecture réelle → contrat sport-plan-v1 → Android | **PARTIAL** — route testée avec clients factices ; aucun accès wger réel |
| S2 | États `FRESH`/`STALE`/`UNAVAILABLE`/`MISMATCH` | **PASS (tests)** — mapping backend + client Android |
| S3 | `trainingRef` persisté dans `DayPlan` | **PASS (JVM)** — round-trip, plan legacy, révision |
| M1 | Parcours mémoire complet | **PASS** — ajout, consentement, affichage, persistance après force-stop et relance ; `preuves/memory-ui.txt` |
| U1 | Usage Access réel sur émulateur | **PASS** — appops `GET_USAGE_STATS: allow`, consentement, résumé de 6 événements, capture ; `preuves/usage-global-ui.txt` |
| U2 | Quotas et révocation | **PARTIAL** — révocation et purge PASS en UI et après redémarrage ; aucun quota configuré, évaluation couverte en JVM seulement |
| B1 | Plugin Bridge : tests existants non régressés | **PASS** — 50 tests / 3 fichiers, plugin ni installé ni activé |

## 7. Verdict

Verdict final à établir dans `HANDOFF_R3A.md`. Aucun `GO` V6.3 possible sans
recette Pixel physique et connexion extérieure réelle.
