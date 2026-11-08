# V7-11A — Registre des tickets Paseo

**Mission :** qualifier V7-10 avant toute mutation live. **Frontière :** V7-11A uniquement ; V7-11B interdit sans autorisation ciblée.
**Base d'intégration :** `feat/atlas-v7-11-migration-readiness`, BASE_SHA `8dac699cdb37e2f858a76e4cf7f9fc3bcd613e9c`.
**Parent :** DeepSeek Flash / Claude harness (orchestrateur, seul intégrateur). **Enfants :** `provider=codex`, `model=gpt-6-luna`, `effort=xhigh`, aucun fallback, aucune sous-délégation.
**Concurrence :** 6 enfants maximum, 3 producteurs de code maximum, 1 propriétaire ADB/AVD, worktree exclusif, fichiers d'écriture disjoints.

## Règles communes

- Vérifier `git rev-parse HEAD` = BASE_SHA dans le worktree ; sinon `BLOCKED`.
- Aucune écriture sur `main`, aucun push/PR/merge distant, aucun force-push/reset destructif.
- Aucune mutation des services live : pas de `docker stop/restart/up/down/exec` d'écriture, pas de modification du volume `atlas-core-data`, pas de `systemctl`, pas de Tailscale/Funnel/Serve.
- Aucun ADB vers le Pixel physique `<PIXEL_SERIAL>`. Seul A3 peut utiliser un AVD, avec un serial `emulator-*` explicite.
- Aucun secret, bearer, token, contenu de base personnelle ou identifiant brut dans les rapports, commits, logs ou commandes affichées.
- Chaque agent écrit uniquement son fichier de preuve sous `docs/operations/evidence/` dans son worktree, plus son éventuel correctif borné explicitement autorisé.
- Fin de ticket : message final avec `PASS/FAIL/BLOCKED/NOT_RUN` par oracle, commandes exactes, SHA, fichiers modifiés et limites.

## Tickets

| ID | Titre | Type | Écriture autorisée | Oracle principal | État |
|---|---|---|---|---|---|
| V7-11-A1 | Core, registre, migration SQLite, consentements, expiration ; correction CI harness | producteur borné | `scripts/v7-06c/tests/test_harness.py`, `docs/operations/evidence/V7-11_A1_CORE_MIGRATION_EVIDENCE.md` | `python -m pytest -q` racine PASS ; migration/backfill/réconciliation sur base synthétique PASS ; échec avant correctif reproduit | ACCEPTED / INTEGRATED `850f6a2` (output `b9392a5`) — parent rerun : **496 passed, 1 skipped, 48 subtests** ; harness 27/43 ; migration v3→v5 synthétique PASS |
| V7-11-A2 | Relay/Noise, binding, assertion HMAC, anti-rejeu, topologie live lecture seule | scout + test | `docs/operations/evidence/V7-11_A2_RELAY_REPLAY_EVIDENCE.md` ; scripts jetables sous `/tmp` uniquement | tests connecteur 131/131, proxy 18/18 hors 4402 ; démonstration rejeu mono/multi-worker ; topologie réelle documentée | ACCEPTED / INTEGRATED `bdf3adf` (output `e4a4461`) — **NO-GO live** : rejeu inter-worker P2 confirmé ; 4400/4401 absents ; déviation 4402 consignée |
| V7-11-A3 | APK candidat, provenance, préservation et rollback AVD API 37 | producteur artefact | `docs/operations/evidence/V7-11_A3_ANDROID_CANDIDATE_EVIDENCE.md` ; artefacts sous `/tmp` | build reproductible + hashes ; install -r sur AVD API 37 avec données préservées ; instrumentation PASS ; rollback in-place testé | ACCEPTED / INTEGRATED `4ec02ce` (output `57b5c19`) — APK `97ac580d…`, cert `da301466…eeee`, install/préservation/rollback PASS, 9/9 + 1/1 ; **HOME non exercé, A0 isolé NOT_RUN → A3-F1** |
| V7-11-A4 | Revue sécurité indépendante, lecture seule | relecteur | `docs/operations/evidence/V7-11_A4_SECURITY_REVIEW.md` | findings P0/P1/P2 avec `file:line` et reproduction ; verdict GO/NO_GO sur SHA final | ACCEPTED (vague 1) / INTEGRATED `d1bf920` (output `81dc085`) — **NO_GO** : P1 legacy confirmé (legacy=200, dual/principal=401), 4 P2 ; re-revue après A1-F2 |
| V7-11-A5 | CI, provenance, suites complètes, scan secrets | scout + test | `docs/operations/evidence/V7-11_A5_CI_PROVENANCE_EVIDENCE.md` | CI reproduite sur SHA final ; suites backend/connecteur/proxy/Android PASS sur SHA exact ; scan secrets/logs PASS | ACCEPTED (vague 1) / INTEGRATED `04fdc77` (output `f37412a`) — provenance PASS, CI FAIL, backend 451/1, connecteur 131/131, proxy 18/18, Android DEFERRED, scan secrets P2 (5 fichiers) → A5-F1 |
| V7-11-A6 | Audit handoff 13 étapes, plan live corrigé, rollback, gates | relecteur | `docs/operations/evidence/V7-11_A6_CUTOVER_GATES.md` | chaque étape du handoff classée vérifiée/non vérifiée ; plan V7-11B et rollback par étape ; matrice des gates | ACCEPTED (audit) / INTEGRATED `2aa0211` — **NO-GO live** ; P1 legacy/tombstone confirmé ; P1 Relay non administrable ; revue finale sur SHA intégré en attente |

## Dépendances

- A1 → A4/A5 pour la revue du correctif CI et le rerun final.
- A2 → A4 pour la revue anti-rejeu et topologie.
- A3 → A5 pour la revue Android finale (A5 ne lance pas d'émulateur en concurrence avec A3).
- A6 consomme les preuves A1–A5 après `ACCEPTED` ; sa revue finale est une nouvelle passe indépendante.
- Toute correction P0/P1 repasse par un producteur borné puis une revue indépendante sur le SHA intégré.

## Journal

| Date | Événement | Détail |
|---|---|---|
| 2026-11-08 | Pré-vol | `main` local = `origin/main` = `8dac699` ; CI distante `failure` sur `Run backend tests` (2 tests `scripts/v7-06c/tests/test_harness.py`, colonne `device_ref` manquante). Core live : 1 uvicorn, mode legacy, volume `atlas-core-data`. Relay 4402 ; aucun listener 4400/4401 au scan initial. |
| 2026-11-08 | Branche | `feat/atlas-v7-11-migration-readiness` créée depuis `8dac699`. |
| 2026-11-08 | Enfants | A1 `d47863cc-3c9e-4285-b2c3-4d020a5eecd1` ; A2 `df882229-f68b-4301-beed-2aa3ba9d4461` ; A3 `854a4c2b-1b4b-4d9c-a1b7-14fce32e94dc` ; A4 `8e0d5e1f-817a-4245-80fa-8f936666a1a3` ; A5 `e540dcaa-4d29-41db-a319-75b5901c039c` ; A6 `87cdcf14-115a-4a95-838e-adc676de01f0`. Tous `codex/gpt-6-luna` / `xhigh`, worktrees isolés. |
| 2026-11-08 | Live | Core pré-V7-10 (image 2026-11-04, sans `v4_auth.py`), 1 uvicorn, DB `user_version=3`, integrity ok, 3 propositions / 2 ACK. Relay `r3a` 4402 ; **aucun listener 4400/4401** ; Funnel 8443→4400 inopérant. Détail : `V7-11_LIVE_INVENTORY.md`. |
| 2026-11-08 | A2 ACCEPTED | Preuve 99 lignes, diff borné. Rejeu inter-worker reproduit (P2) ; gate live NO-GO. Connecteur 131/131 ; proxy 18 passés + 1 skip. Déviation : premier run proxy a contacté 4402 en GET health/ready + handshake WS, sans mutation ; non compté comme preuve. Core/Relay non redémarrés (`restarts=0`, démarrés 2026-11-07T10:05:46Z). |
| 2026-11-08 | A2-F1 | Rejeu après redémarrage reproduit : un cache neuf accepte une assertion encore dans la fenêtre. Mitigation renforcée : cache partagé durable, ou worker unique + blocage V4 ≥240 s après redémarrage. Intégré `7d94de8` (output `291b5f7`). |
| 2026-11-08 | A1 ACCEPTED | Diff limité au harness (11e valeur `synthetic-device`) + preuve. Parent rerun racine : 496 passed, 1 skipped, 48 subtests. Migration v3→v5, LEGACY, credentials DIRECT/RELAY/TOOL, grants, binding, backfill, expiration, UNCERTAIN et idempotence PASS sur `/tmp`. Intégré `850f6a2` (output `b9392a5`). |
| 2026-11-08 | A6 ACCEPTED (audit) | Rapport 219 lignes intégré `2aa0211`. Verdict NO-GO live. P1 legacy : `require_android` en mode legacy compare à `ATLAS_TOKEN` sans consulter le registre (confirmé code `v4_router.py:767-789`). P1 Relay non administrable, identité/binding inconnus, bascule Android incomplète, anti-rejeu live non vérifié. Revue finale A6 sur SHA intégré à faire. |
| 2026-11-08 | A3 ACCEPTED | APK rebuild `97ac580d…`, cert `da301466…eeee`, version 1/0.1.0, minSdk 26, target 36, allowBackup=false, debuggable=true. AVD API 37 : install -r, firstInstallTime stable, 7 fixtures préservées, cold start OK, Coach 9/9, scheduler 1/1, rollback in-place PASS. HOME non exercé (Nexus Launcher) et A0 isolé NOT_RUN → F1. Intégré `4ec02ce`. |
| 2026-11-08 | A4 ACCEPTED (vague 1) | Revue indépendante : 0 P0, 1 P1 (legacy réaccepte un bearer révoqué ; reproduction ASGI réelle legacy=200, dual/principal=401), 4 P2 (capabilities vides, cache process-local, permissions SQLite, chemins locaux). Verdict NO_GO. Intégré `d1bf920` ; re-revue après A1-F2. |
| 2026-11-08 | A5 ACCEPTED (vague 1) | Provenance 40 commits/107 chemins, origin/main = 8dac699, CI failure confirmée, backend 451/1, connecteur 131/131, proxy 18/18, Android différé, scan secrets sans secret réel mais 5 findings P2. Intégré `04fdc77` ; F1 = Android complet + image Core + sanitisation. |
| 2026-11-08 | A1-F2 ACCEPTED / INTEGRATED `65759dd` (output `2c3e178`) | Garde legacy : `credential_state()` refuse un credential LEGACY REVOKED ; compatibilité token inconnu/table absente ; pas de faux positif autre audience. Test rouge 200 → vert 401. Backend 454/1, racine **499 passed / 1 skipped / 48 subtests** (rerun parent). Re-revue A4-F1 en cours. |
| 2026-11-08 | A5-F1 ACCEPTED / INTEGRATED `8a2ccfa` (output `b6c2cc0`) | Android 934/934, lint 0 erreur/80 avertissements ; APK `27baca26…` ; image Core `sha256:2c5ae216…` (150 MB, v4 présent) ; sanitisation des 5 P2 PASS ; scan courant sans secret/path/ID. Résidu historique Git documenté. Rerun final sur SHA définitif à faire (A1-F2 non inclus dans 04fdc77). |
| 2026-11-08 | A4-F1 ACCEPTED / INTEGRATED (output `2f750b7`) | Re-revue du correctif legacy sur `65759dd` : **GO avec réserves P2**. P1 fermé (a–h PASS) ; 0 P0/P1 ; 5 P2 dont intégrité du tombstone sous écriture SQLite directe. `GO_V7_11_INDEPENDENT_SECURITY_REVIEW = GO` avec réserves. |
| 2026-11-08 | A5-F2 ACCEPTED / INTEGRATED `4f48697` (output `55b94ff`) | Qualification finale sur `c3dcaa1` : racine 499/1/48, backend 454/1, connecteur 131/131, proxy 18/18, Android 934/934 + lint 0/80, image Core `sha256:91d456e7…`, APK final `3feabac7…`, scan propre. Diff code `65759dd..c3dcaa1` vide. |
