# ATLAS V7-06D — Fiabilité remote et révocation A4-4 : registre de campagne

**Mission :** fermer les deux P1 laissés ouverts par V7-06C — blocage
intermittent du chat Android avant émission vers le connecteur, et restitution
côté Android du refus d'un appareil révoqué (A4-4) — puis fermer le P2 de
traçabilité APK↔SHA. Aucune nouvelle campagne produit.

**BASE_SHA :** `9ad90c7a079736ffc5f7bb6a7f0d4ee3eed70580`
(local = `origin/main`, vérifié le 2026-11-01).
**Branche de travail :** `feat/atlas-v7-06d-remote-hardening`, créée depuis ce
SHA. Aucun push, PR, merge distant ni service live modifié à ce stade.

**Statut global :** `CORRECTIONS INTÉGRÉES — P1 et P2 de revue provenance
corrigés (rouge→vert) ; confirmation finale du SHA corrigé en cours ; aucune
qualification physique`.

## 1. Contraintes d'exécution

- Parent Paseo orchestrateur : découpe, délègue, contrôle, intègre localement.
- **Tous** les sous-agents : `provider=codex`, `model=gpt-6-luna`, effort effectif
  `xhigh`, vérifié dans les métadonnées Paseo de chaque session.
- 6 enfants simultanés maximum, 3 implémentations simultanées maximum, aucune
  sous-délégation, un worktree et une propriété d'écriture par enfant.
- Audits et revues techniquement en lecture seule (Codex `collaborationMode=Plan`,
  worktree détaché, aucun test/ADB/Docker/réseau lancé).
- Un seul opérateur ADB/AVD à la fois. **Aucun ADB sur le Pixel physique** sans
  nouvelle autorisation spécifique.
- Une notification de fin n'est pas une validation : le parent relit le rapport
  et la preuve avant `ACCEPTED`/`INTEGRATED`.
- Aucun token, bearer, secret, UUID brut, contenu utilisateur ou donnée
  personnelle dans les preuves.

## 2. Pré-vol V7-06D-00 (lecture seule, 2026-11-01)

| Élément | Constat |
|---|---|
| Git local | `HEAD=9ad90c7a079736ffc5f7bb6a7f0d4ee3eed70580`, arbre propre |
| Git distant (réf. locale) | `origin/main=9ad90c7a079736ffc5f7bb6a7f0d4ee3eed70580` (identique) |
| CI GitHub | run `41316272473` sur `9ad90c7`, workflow `CI`, conclusion `success` |
| Branche | `feat/atlas-v7-06d-remote-hardening` |
| JDK | OpenJDK `21.0.12.1` |
| Node / npm | `v24.18.0` |
| Python | `3.12.3` |
| Docker | `27.1.1` |
| SDK Android | platforms `android-37.0`, `37.1`, `37.2` ; build-tools `37.0.0` |
| Paseo | provider `codex` disponible ; `gpt-6-luna` avec effort `xhigh` disponible |
| Appareil physique | **non contacté** — aucun ADB lancé ; aucun test physique autorisé à ce stade |
| Services live | non inspectés en écriture ; aucune action live prévue pendant les audits |

**Décision de pré-vol :** les trois audits A1–A3 sont lancés en lecture seule sur
des worktrees détachés au `BASE_SHA`. Aucun code n'est modifié avant
l'établissement de la nécessité des correctifs et de leurs invariants.

## 2bis. Recontrôle live en lecture seule (2026-11-01)

Aucune écriture, aucun redémarrage, aucun ADB. Vérifications :

| Ressource | Constat lecture seule |
|---|---|
| Connecteur `~/.local/state/atlas-relay/devices.json` | 19 entrées ; 17 révoquées ; **2 non révoquées** : `563a01fa…` (14 capabilities, dont V4) et `f4315995…` (6 capabilities V1/V2) |
| Core live `atlas-core-atlas-core-1` | conteneur `Up 42 hours (healthy)` |
| `consent_states_v4` | 2 lignes pour `device_ref` `android-9a3f0f75…` : `DAY_PLAN` et `ACTIVITY_STATUS`, `GRANTED`, version 2, `updated_at=2026-10-31T00:23:51Z` |
| Projections / propositions / ACK live | `day_plan_snapshots_v4=0`, `activity_status_events_v4=0`, `agent_proposals_v4=0`, `agent_proposal_events_v4=0` |

**Décision :** aucune révocation, rotation ou modification live n'est exécutée.
Une demande d'autorisation ciblée est préparée dans le rapport final pour les deux
identités connecteur obsolètes ; la rotation du bearer Core reste une décision
séparée (réappairage/reconfiguration possibles).

## 3. Tickets

| TASK_ID | Objectif | Dépendance | Statut | Session Paseo | Worktree | Oracle |
|---|---|---|---|---|---|---|
| V7-06D-00-PREFLIGHT | SHA, CI, outils, worktrees, registre | — | **ACCEPTED (lecture seule)** | parent | racine | SHA local=distant, arbre propre, aucun ADB/live |
| V7-06D-A1-ANDROID-CONCURRENCY | Graphe verrous/threads, cycle complet ou absence, tests à barrières du P1 chat | PREFLIGHT | **ACCEPTED (lecture seule)** — cycle G↔C complet et atteignable prouvé (`pump` tient G et attend C dans `sendTextFrame` ; callback OkHttp tient C et `pump()` attend G) ; timeout 55 s inopérant car `pump()` précède `await` ; cause historique reste UNKNOWN ; autres hypothèses classées | `db85f131-1494-48b8-b227-23dd60ee4f40` | `/tmp/atlas-v7-06d-a1-android-ro` | cycle inter-threads nommé, file:line, tests à barrières, invariants |
| V7-06D-A2-REVOCATION-A4-4 | Matrice des refus et perte du code/état côté client | PREFLIGHT | **ACCEPTED (lecture seule)** — cause exacte : trame pré-auth `DEVICE_REVOKED` émise en V1 pour un client V2 → parse strict Android → `ERROR` + `abort(4003)` + `close_code=null` ; le relais transforme le close serveur en 1012 ; `revoked_at` vérifié avant le token (divulgation) | `3b0bbe77-4d1b-44db-8da1-16f06f61f854` | `/tmp/atlas-v7-06d-a2-revocation-ro` | matrice, point de perte, ordre token, tests discriminants |
| V7-06D-A3-RELIABILITY-PROVENANCE | Durabilité/corrélation/harnais + traçabilité APK↔SHA | PREFLIGHT | **ACCEPTED (lecture seule)** — aucun P0 ; P1 identité de requête non persistée, P1 attente de gate non bornée avant le timeout, P2 provenance documentaire, P2 403 direct, P2 gate d'instrumentation permissif, P2 timeouts ADB ; design manifeste fourni | `5f2eb93c-cc6c-4e9d-9f36-5db1fc31e3dc` | `/tmp/atlas-v7-06d-a3-proof-ro` | rapport structuré, file:line, champs absents, tests négatifs proposés |

| V7-06D-X-CHAT-CONCURRENCY | Test rouge G↔C, correction verrous/attente bornée, distinction timeout avant/après envoi, traces bornées | A1 | **ACCEPTED (code)** — rouge `f6e8659` vérifié sur BASE (échec 2,247 s, reader WAITING sur G dans `pump`), fix `df8f8ae` intégré `f0a2099`/`813432b` ; JVM intégrée **909/909**, lint PASS, build debug PASS ; aucun run appareil | `ac0cad8c-1364-498e-b6c9-a0eceb302a71` | `/tmp/atlas-v7-06d-fix-chat` (`fix/v7-06d-chat-concurrency`) | test rouge échoue sur BASE puis vert ; JVM complet ; aucun envoi après refus/timeout pré-envoi |
| V7-06D-Y-A4-4-REVOCATION | Protocole DEVICE_REVOKED pré-auth, ordre token/révocation, état REVOKED collant, sonde stricte renforcée | A2 | **ACCEPTED (code)** — commits `686d437` (rouge), `b822192` (connecteur), `207bea2` (Android) ; test rouge revérifié par le parent (V1 reçu au lieu de V2), Node **114/114 PASS** revérifié, JVM **902/902** rapporté, probe compilée avec `-PqaTestBuildType=qa` | `e3d364f5-0545-4692-a02d-46a538d8b0ca` | `/tmp/atlas-v7-06d-fix-a4-4` (`fix/v7-06d-a4-4-revocation`) | test rouge V1→V2, Node complet, JVM policy, probe compilée |
| V7-06D-Z-APK-PROVENANCE | Manifeste de build embarqué + vérification APK installés + champs proof + tests négatifs | A3 | **ACCEPTED (code)** — commits `04960c6` (tests), `4c8b691` (implémentation), `49ef354` (suffixe Android) ; intégrés `173dd01`, `8d0aa1c`, `7b0ae5a` ; harnais initial 25/25, builds QA PASS, manifests identiques dans les deux APK (`worktree_clean=true`, `variant=qa`, `compile_sdk=37.2`, `source_sha=49ef354`), gate installé↔construit testé ; aucun run appareil | `29a8a6fc-ab40-410a-9419-b90839ad6e57` | `/tmp/atlas-v7-06d-feat-provenance` (`feat/v7-06d-apk-provenance`) | manifeste extrait des deux APK, gate installé→build, tests dirty/mismatch/absent, build QA PASS |
| V7-06D-Z2-PROVENANCE-P1 | Correction du rejet du champ `installed_apk_sha256` par le validateur strict + régression du chemin complet | Z + revue | **ACCEPTED (code)** — rouge `c599661`, fix `1031a1a`, intégrés `7ea8c58`/`69d0db6` ; harnais **26/26 PASS**, `py_compile` et `git diff --check` PASS ; code Android inchangé | `29a8a6fc-ab40-410a-9419-b90839ad6e57` | `/tmp/atlas-v7-06d-feat-provenance` | test rouge `PROOF_PROVENANCE_FIELD_MISSING` puis vert ; hashes installés validés/égaux aux construits |
| V7-06D-Z3-PROVENANCE-P2 | Schéma exact des proofs, test exécutant la vraie vérification installée, post-hoc aligné | REVIEW2 | **ACCEPTED (code)** — rouge `052ca80`, fix `4ebdf5f`, intégrés `fded03d`/`bfb1965` ; harnais **27/27 PASS**, backend **399 passed/1 skipped**, `py_compile` et `git diff --check` PASS ; code Android inchangé | `29a8a6fc-ab40-410a-9419-b90839ad6e57` | `/tmp/atlas-v7-06d-feat-provenance` | clés inconnues refusées par le wrapper ; test du chemin réel `verify_installed_artifacts` ; post-hoc `INCONCLUSIVE` |
| V7-06D-REVIEW | Revue indépendante lecture seule du SHA intégré | X/Y/Z/Z2 | **CORRECTION_REQUIRED puis P1 corrigé** — session `5f9adb37…` ; P1 provenance confirmé (gate bloquant), P2 libellés ; P1 corrigé Z2, P2 corrigé | `5f9adb37-57bc-4068-9aa8-53899f44f00b` | `/tmp/atlas-v7-06d-review-ro` | rapport P0/P1/P2, huit verdicts, aucun fichier modifié |
| V7-06D-REVIEW2 | Revue delta indépendante du SHA corrigé | Z2 + REVIEW | **CORRECTION_REQUIRED puis corrigé** — P1 confirmé corrigé ; deux P2 (wrapper permissif, test mockant la frontière) et libellés obsolètes ; corrigés par Z3, revue de confirmation relancée | `c2c2abd5-0340-4bfe-8fe6-35e5cc6bfc15` | `/tmp/atlas-v7-06d-review2-ro` | P1 rouge→vert, schéma proof exact, absence de régression, labels, huit verdicts |
| V7-06D-REVIEW3 | Confirmation finale courte du SHA corrigé | Z3 + REVIEW2 | **RUNNING** | à renseigner | worktree dédié en lecture seule | confirmer P2 et l'absence de régression ; huit verdicts |

## 4. Journal des sessions

| Date | Session | Rôle | Provider/modèle/effort constatés | Résultat |
|---|---|---|---|---|
| 2026-11-01 | parent | Pré-vol V7-06D-00 | session parente | `ACCEPTED` lecture seule ; branche créée ; aucun ADB/live |
| 2026-11-01 | `db85f131-1494-48b8-b227-23dd60ee4f40` | V7-06D-A1 audit concurrence | codex / `gpt-6-luna` / `xhigh` (`effectiveThinkingOptionId=xhigh`, `collaborationMode=Plan`) | **ACCEPTED** — cycle G↔C complet et atteignable, timeout inopérant, autres hypothèses classées ; aucun fichier modifié |
| 2026-11-01 | `3b0bbe77-4d1b-44db-8da1-16f06f61f854` | V7-06D-A2 audit révocation | codex / `gpt-6-luna` / `xhigh` (`effectiveThinkingOptionId=xhigh`, `collaborationMode=Plan`) | **ACCEPTED** — `DEVICE_REVOKED` pré-auth émis en V1 pour client V2, relais 1012, ordre token ; matrice et tests |
| 2026-11-01 | `5f2eb93c-cc6c-4e9d-9f36-5db1fc31e3dc` | V7-06D-A3 audit fiabilité/provenance | codex / `gpt-6-luna` / `xhigh` (`effectiveThinkingOptionId=xhigh`, `collaborationMode=Plan`) | **ACCEPTED** — identité de requête non persistée, attente de gate non bornée, provenance documentaire ; design et tests négatifs |
| 2026-11-01 | `e3d364f5-0545-4692-a02d-46a538d8b0ca` | V7-06D-Y correction A4-4 | codex / `gpt-6-luna` / `xhigh` | **ACCEPTED (code)** — rouge connecteur vérifié, Node 114/114 revérifié, JVM 902/902 rapporté, sonde compilée ; intégré `c6de88f`/`d3619d4`/`c1bb1d1` |
| 2026-11-01 | `29a8a6fc-ab40-410a-9419-b90839ad6e57` | V7-06D-Z provenance APK | codex / `gpt-6-luna` / `xhigh` | **ACCEPTED (code)** — harnais 25/25 revérifié, builds QA PASS, manifests APK app/test identiques, `worktree_clean=true` ; intégré `173dd01`/`8d0aa1c`/`7b0ae5a` |
| 2026-11-01 | `ac0cad8c-1364-498e-b6c9-a0eceb302a71` | V7-06D-X concurrence chat | codex / `gpt-6-luna` / `xhigh` | **ACCEPTED (code)** — rouge vérifié sur BASE, fix `df8f8ae` intégré `813432b`, JVM intégrée **909/909**, lint PASS, aucun run appareil |
| 2026-11-01 | `29a8a6fc-ab40-410a-9419-b90839ad6e57` | V7-06D-Z2 correction P1 provenance | codex / `gpt-6-luna` / `xhigh` | **ACCEPTED (code)** — rouge `c599661`, fix `1031a1a`, intégrés `7ea8c58`/`69d0db6` ; 26/26 harnais |
| 2026-11-01 | `5f9adb37-57bc-4068-9aa8-53899f44f00b` | V7-06D-REVIEW relecture indépendante | codex / `gpt-6-luna` / `xhigh` (`collaborationMode=Plan`) | **CORRECTION_REQUIRED** sur `b163202` — P1 gate provenance confirmé, P2 libellés ; P1 corrigé Z2, P2 corrigé |
| 2026-11-01 | `c2c2abd5-0340-4bfe-8fe6-35e5cc6bfc15` | V7-06D-REVIEW2 revue delta | codex / `gpt-6-luna` / `xhigh` (`collaborationMode=Plan`) | **CORRECTION_REQUIRED** sur `0c19a3f` — P1 confirmé corrigé ; P2 wrapper permissif, P2 test mockant la frontière, libellés obsolètes ; corrigés Z3 `fded03d`/`bfb1965` ; aucun fichier modifié |
| 2026-11-01 | `29a8a6fc-ab40-410a-9419-b90839ad6e57` | V7-06D-Z3 correction P2 provenance | codex / `gpt-6-luna` / `xhigh` | **ACCEPTED (code)** — rouge `052ca80`, fix `4ebdf5f`, intégrés `fded03d`/`bfb1965` ; harnais 27/27, backend 399/1 |

## 4bis. Résultats finaux d'intégration

SHA de build de provenance (APK) : `cf82cd02a8e00f75d1cd7d476a84691a276f3011`
(worktree propre). SHA de code final après corrections harnais P1/P2 : `bfb1965` ;
le code Android est identique entre les deux SHA (`git diff cf82cd0..bfb1965 --
android/` vide). Le HEAD documentaire final ne change que `docs/`.

| Suite | Résultat |
|---|---|
| Android JVM `:app:testDebugUnitTest --offline --rerun-tasks` | **909/909 PASS**, 119 fichiers, 0 échec/erreur/ignoré |
| Android lint `:app:lintDebug --offline` | **PASS** |
| Builds `assembleDebug`, `assembleQa`, `assembleQaAndroidTest` | **PASS** |
| Node connecteur | **114/114 PASS**, 18 fichiers |
| Proxy relay | **19/19 PASS** |
| Harnais V7-06C/V7-06D | **27/27 PASS** (après corrections P1/P2 de revue) |
| Backend `pytest -q` | **399 passed / 1 skipped**, 43 subtests |
| `py_compile`, `git diff --check` | **PASS** |
| APK final app / test | `1e31a643…` / `05dd465b…` ; manifeste `8d678af5…`, source `cf82cd0…`, propre, `qa`, `compile_sdk=37.2` |
| Sonde A4-4 sur appareil | **NOT_RUN** |
| Série de démarrages froids | **NOT_RUN** |

**Statut global :** deux P1 V7-06C corrigés avec preuves déterministes ; P2
provenance fermé par un mécanisme vérifié ; **P1 et P2 de revue indépendante**
sur le gate de provenance corrigés (`bfb1965`, rouge→vert) ; **confirmation
finale du SHA corrigé en cours** ; **aucune qualification physique**. Verdicts :
`GO_CHAT_CONCURRENCY`, `GO_REVOKED_DEVICE_SERVER` ; `GO_APK_PROVENANCE`
(GO_WITH_LIMITATIONS, chaîne logicielle) ; `GO_CHAT_RELIABILITY_QA` et
`GO_A4_4_CLIENT` **NOT_RUN** ; `GO_PIXEL_PRODUCT`/`GO_PRODUCT_REMOTE`
**NO GO**.

## 5. Règles de preuve

- Chaque résultat porte `PASS`, `FAIL`, `BLOCKED`, `NOT_RUN` ou `INCONCLUSIVE`,
  lié au SHA exact, à l'environnement et à la commande.
- Un test compilé n'est pas un test exécuté ; un test JVM n'est pas une preuve
  sur téléphone ; une correction préventive n'est pas la cause historique.
- Une seule relance qui PASS après un blocage ne prouve pas la fiabilité.
- Les preuves V7-06C restent intactes ; aucun verdict historique n'est réécrit.
- Toute action live (révocation, rotation) exige une autorisation ciblée et une
  sauvegarde vérifiée ; elle n'est pas exécutée dans cette phase.
