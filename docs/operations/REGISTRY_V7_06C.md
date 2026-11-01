# ATLAS V7-06C — Coach Android puis transport public : registre de campagne

**Mission :** prouver depuis l’application Android QA réellement exécutée que le coach
OpenClaw lit le DayPlan v2, propose un déplacement de 10 minutes, qu’Android applique
ou rejette explicitement, et que le Core enregistre durablement le résultat et l’ACK ;
puis qualifier le même parcours via un WSS public sans `adb reverse`.

**BASE_SHA :** `3e0d1f4c9ca591f903a894be87183395d30fee0b` (local = `origin/main`
vérifié le 2026-11-01). **Branche de travail :**
`feat/atlas-v7-06c-android-coach-loop`, aucun push, PR, merge distant ni service live
modifié à ce stade.

**Environnement :** identités, appairage, jetons, base et stack synthétiques neufs.
Aucune identité, session, donnée ou configuration personnelle n’est réutilisée.

## 1. Contraintes d’exécution

- Parent Paseo orchestrateur ; **tous** les sous-agents `provider=codex`,
  `model=gpt-6-luna`, effort effectif `xhigh`, vérifié dans les métadonnées Paseo.
- 6 enfants simultanés maximum, dont 3 implémentations indépendantes au plus ;
  aucune sous-délégation.
- Worktree distinct et propriété d’écriture exclusive par enfant ; le parent intègre
  localement.
- Un seul opérateur ADB/AVD à la fois. Aucune opération ADB en écriture sans
  autorisation ciblée.
- Une notification de fin n’est pas une validation : le parent relit diff, tests,
  preuves et limites avant `ACCEPTED`/`INTEGRATED`.
- Aucun token, bearer, secret, UUID personnel, contenu de conversation ou donnée
  personnelle dans les preuves.

## 2. Pré-vol V7-06C-00 (2026-11-01, lecture seule)

| Élément | Constat |
|---|---|
| Git local | `HEAD=3e0d1f4c9ca591f903a894be87183395d30fee0b`, arbre propre au lancement |
| Git distant | `origin/main=3e0d1f4c9ca591f903a894be87183395d30fee0b` (identique) |
| Branche | `feat/atlas-v7-06c-android-coach-loop` créée depuis ce SHA |
| Paseo | provider Codex disponible ; modèle `gpt-6-luna` et effort `xhigh` disponibles |
| Enfant V7-06C-01 | session `3b012d9f-efaf-424f-9668-52c42be6cefc`, `codex/gpt-6-luna/xhigh`, mode `full-access`, worktree `feat-v7-06c-harness` |
| Modèle réel | `DEEPSEEK_API_KEY` présente dans l’environnement (valeur non lue, non écrite) |
| OpenClaw | `2026.10.5` (`1b58cb9`) |
| Node / npm | `v24.18.0` / `11.16.0` |
| Docker | `27.1.1` ; image `atlas-paseo-relay:r3a` = digest `sha256:75d68fd8bdb9d60a26203cd7c49c07c035f1f400c47740770056814e76846889` |
| Backend | venv Python 3.12 présent ; `dist/main.js` du connecteur présent |
| JDK / SDK | OpenJDK `21.0.12.1`, SDK Android 37, `sdk.dir=/home/nill/Android/Sdk` |
| KVM | `/dev/kvm` présent ; émulateur `37.2.12.0` |
| Images AVD | API 37.2 `google_apis_ps16k`, API 37.1 `google_apis_playstore_ps16k`, API 35 `google_apis`/`ps16k` (x86_64 et arm64) |
| AVD existants | `atlas_v7_06a_api37` (blocage V7-06B documenté), `atlas_v62_disposable_api37`, `Pixel_9_Pro_XL*` |
| Résidus V7-06B | Aucun conteneur/processus de stack isolée V7-06B ; `/tmp/atlas-v7-06b-backend` existe encore (worktree `dd7b2de`, harnais non commité) |
| Services live | Core personnel `127.0.0.1:8100`, Gateway personnel `127.0.0.1:18789`, conteneurs `atlas-core-atlas-core-1` et `atlas-v7-relay` actifs ; **non modifiés** |
| Funnel public | `tcp://all.taildc7886.ts.net:8443` → `127.0.0.1:4400` ; aucun listener sur 4400 ; le relais live publie `4000/tcp -> 127.0.0.1:4402` ; sonde TLS en échec |
| DNS public | `all.taildc7886.ts.net` résolu (176.58.90.46/63/145) depuis l’hôte |
| Appareil physique | Pixel 9 Pro XL, série hashée `f2e9258b85327628c8d25b1652b00601e698f9ccb2a8b23fb1e913ff355a5946`, Android 17 / API 37, build `CP3A.260905.009`, arm64-v8a, `sys.boot_completed=1`, batterie 100 % |
| Paquet produit | `dev.atlas.coach` 0.1.0, `lastUpdateTime=2026-10-31 00:18:13`, rôle HOME `dev.atlas.coach/.app.LauncherActivity` |
| Paquets QA antérieurs | `dev.atlas.coach.qa`, `.qa2`, `.qa3`, `.qa4` et leurs `.test` installés ; non réutilisés |
| Autorisation physique | demandée et accordée explicitement le 2026-11-01 pour **APPLY + REJECT** sur ce Pixel, paquets QA seulement, sans toucher au produit, HOME, Keystore ni données personnelles |

**Décision de pré-vol :** la cible Android est le Pixel physique autorisé ; l’AVD
API 37.2 16 Ko n’est pas relancé (blocage V7-06B documenté). Le transport public reste
`BLOCKED_NETWORK` tant que le Funnel ne pointe pas vers un relais vivant et qu’une
autorisation ciblée de changement Funnel n’est pas donnée.

### 2bis. Paquets QA et autorisation physique

Autorisation ciblée du propriétaire (2026-11-01) : APPLY + REJECT sur le Pixel
9 Pro XL désigné, paquets QA uniquement, sans toucher à `dev.atlas.coach`, HOME,
Keystore ni données personnelles. Le delta AndroidTest ajoutant le rejeu ACK a été
explicitement re-confirmé.

| Scénario | Package QA | APK app SHA-256 | APK AndroidTest SHA-256 |
|---|---|---|---|
| APPLY | `dev.atlas.coach.qa.v706b.v706capply.a7f3c21e` | `8441a5dab058d3a2852a77d30ccef7a23cec8c2563ac1083212d46439856224d` | `c97427fb9f2a029a065e0924221f351458151860a962604c021f5189576337d8` |
| REJECT | `dev.atlas.coach.qa.v706b.v706creject.b4d9e602` | `b599532c171f3d169b80823f2e5dc63794864d7ae4042138dc7bce3aa32f9a6b` | `be283081cb0ec14e3853524feecc668aaae10629c1a1d25342579b08dcaa0e83` |

Signature debug : `da3014662eb65e50a87ef364a2e583c331398795b222e3b37b1a16553a00eeee`.
Les APK app sont bit-identiques aux hashes présentés lors de la première autorisation ;
seuls les APK AndroidTest ont changé pour le rejeu ACK.

## 3. Tickets

| TASK_ID | Objectif | Dépendance | Statut | Session Paseo | Worktree | Oracle |
|---|---|---|---|---|---|---|
| V7-06C-00-PREFLIGHT | SHA, CI, services live, ressources, harnais, identités | — | **ACCEPTED (lecture seule)** | parent | racine | SHA local=distant, inventaire complet, aucun service modifié |
| V7-06C-01-HARNESS | Harnais reproductible Core/Relay/connecteur/Gateway + RUN Android + oracles | PREFLIGHT | **ACCEPTED (code + runtime backend)** — commits `64020d7`, `f9017f4`, `d0f7d48` intégrés jusqu’à `3b9b3d1` ; 17/17 tests harnais, backend positif et négatif PASS | `3b012d9f-efaf-424f-9668-52c42be6cefc` | `feat-v7-06c-harness` | py_compile, unittest, node --check, `git diff --check` ; backend réel PASS |
| V7-06C-02-ANDROID-RUNTIME | Pré-vol physique, installation QA, Keystore/WorkManager | PREFLIGHT + autorisation | **ACCEPTED (runtime)** — Pixel 9 Pro XL API 37, paquets QA installés, Keystore/WorkManager exercés, produit intact | parent opérateur unique | racine | device stable, runner PASS, produit/HOME inchangés |
| V7-06C-03B-ACK-REPLAY-TEST | Rejeu de l’enveloppe ACK exacte depuis Android avant révocation | 02 | **ACCEPTED (code + runtime)** — commit `69feb5c`, merge `f7e6441` ; APPLY et REJECT ont émis `ack_replay=identical` | `3b0bd988-c977-46ee-b1ff-c099874461d2` | `feat-v7-06c-ack-replay-test` | compilation QA, runtime APPLY + REJECT |
| V7-06C-03-APPLY | DayPlan v2→v3 par Android, ACK APPLIED durable, rejeu sans second effet | 01 + 02 + 03B | **ACCEPTED (runtime local + public)** — local 12 étapes / 19,387 s ; public `network=public_wss` APPLIED v3 | parent opérateur | racine | proofs `v7-06c-apply-a49e3a3aa819.json`, `v7-06c-apply-e4557615f525.json` |
| V7-06C-03-REJECT | Refus explicite sans modification du plan, ACK REJECTED durable | 01 + 02 + 03B | **ACCEPTED (runtime local + public)** — local runner PASS + proof post-hoc ; public `network=public_wss` REJECTED, plan v2 | parent opérateur | racine | proofs `v7-06c-reject-2fb57424d534.json`, `v7-06c-reject-be5168ed2070.json` |
| V7-06C-P2-VALIDATION-422 | Aligner `VALIDATION_FAILED` sur HTTP 422 dans `invoke_tool` + test ciblé | — | **ACCEPTED** — commit `02be9c1`, intégré `b33d43b` ; test rouge→vert, backend **372 passed / 1 skipped** | `a079b4bf-7252-4c61-b017-f9fab0370b6e` | `fix/v7-06c-validation-422` | test ciblé PASS, suite backend complète |
| V7-06C-04-WSS-PUBLIC | Même parcours via WSS public, réseau extérieur, sans `adb reverse` | 03 + endpoint public autorisé | **ACCEPTED (runtime, limitations P1)** — Pixel LTE → `wss://all.taildc7886.ts.net:8443` → relais isolé 4400 ; APPLY + REJECT PASS, TLS valide, Noise/auth, aucun `adb reverse`, Core/Gateway loopback | parent opérateur | racine | proofs `remote-apply2`, `remote-reject` |
| V7-06C-04-REVOKE-PROBE | Reconnexion avec device QA révoqué et code de fermeture | 04 | **FAIL (observabilité P1)** — device révoqué, connecteur refuse (aucune nouvelle session, `errors_total=1`) ; app `state=ERROR`, `close_code=null`, pas `REVOKED`/4001 | `3b0bd988-c977-46ee-b1ff-c099874461d2` | `feat-v7-06c-ack-replay-test` | probe instrumenté FAIL ; refus serveur confirmé par métriques |
| V7-06C-05-REVIEW | Revue indépendante lecture seule du SHA intégré | 01–04 | **PENDING** | à créer | worktree détaché | grille P0/P1/P2, aucun P0 ouvert |

## 4. Journal des sessions

| Date | Session | Rôle | Provider/modèle/effort constatés | Résultat |
|---|---|---|---|---|
| 2026-11-01 | parent | Pré-vol V7-06C-00 | session parente | `ACCEPTED` lecture seule ; aucune modification live |
| 2026-11-01 | `3b012d9f-efaf-424f-9668-52c42be6cefc` | V7-06C-01 harnais | codex / `gpt-6-luna` / `xhigh` (métadonnées vérifiées) | `ACCEPTED (code)` — `64020d7`, `f9017f4`, `d0f7d48` ; 17/17 tests |
| 2026-11-01 | `3b0bd988-c977-46ee-b1ff-c099874461d2` | V7-06C-03B rejeu ACK + sonde révocation | codex / `gpt-6-luna` / `xhigh` (métadonnées vérifiées) | `ACCEPTED (code)` — `69feb5c`, `f1b6b30` intégrés `f7e6441`/`fa960d5` |
| 2026-11-01 | parent + autorisation propriétaire | QA physique APPLY+REJECT | — | autorisation ciblée accordée ; produit/HOME/Keystore non touchés |
| 2026-11-01 | parent | Backend reproductible positif | DeepSeek réel isolé | **PASS** — 3 tours proxy, 2 outils corrélés, 1 proposition A0, ACK APPLIED v3 |
| 2026-11-01 | parent | Backend reproductible négatif | DeepSeek réel isolé | **PASS** — stale/unknown/terminal/injection/consent révoqué ; 0 proposition/ACK |
| 2026-11-01 | parent | Android APPLY physique local | Pixel 9 Pro XL, API 37, paquet `…v706capply.a7f3c21e` | **PASS** — 12 étapes / 19,387 s, v2→v3, ACK APPLIED v3, rejeu identique |
| 2026-11-01 | parent | Android REJECT physique local | Pixel 9 Pro XL, API 37, paquet `…v706creject.b4d9e602` | **PASS (post-hoc)** — 12 étapes / 21,214 s, plan v2, ACK REJECTED ; incident logcat multi-runs corrigé |
| 2026-11-01 | `3b012d9f-efaf-424f-9668-52c42be6cefc` | Correctif attestation + port 4400 | codex / `gpt-6-luna` / `xhigh` | `f9017f4`/`d0f7d48` intégrés `5ca0a92`/`3b9b3d1` ; 17/17 tests |
| 2026-11-01 | `a079b4bf-7252-4c61-b017-f9fab0370b6e` | P2 `VALIDATION_FAILED` 422 | codex / `gpt-6-luna` / `xhigh` (métadonnées vérifiées) | **ACCEPTED** — `02be9c1`, intégré `b33d43b` ; backend 372/1 |
| 2026-11-01 | parent | Remote APPLY public WSS — 1er essai | Pixel LTE, stack `android-apply-1853166` | **BLOCKED (hang intermittent)** — USER persisté, aucune requête chat, session relay fermée ; root non confirmée |
| 2026-11-01 | parent | Remote APPLY public WSS — 2e essai | Pixel LTE, stack `android-apply-1940890` | **PASS** — `network=public_wss`, v2→v3, ACK APPLIED v3, TLS/Noise, aucun `adb reverse` |
| 2026-11-01 | parent | Remote REJECT public WSS | Pixel LTE, stack `android-reject-1968579` | **PASS** — `network=public_wss`, plan v2, ACK REJECTED, sonde incluse |
| 2026-11-01 | parent | Sonde reconnexion device révoqué | Pixel LTE, device `968aff85…` révoqué | **FAIL observabilité** — connecteur refuse (aucune session, `errors_total=1`) ; app `ERROR`, `close_code=null` |
| 2026-11-01 | parent + autorisation propriétaire | Nettoyage QA | — | 18 paquets `qa*` désinstallés ; produit `dev.atlas.coach`/HOME intacts ; Wi‑Fi restauré ; Funnel état initial |

## 5. Résultats runtime (2026-11-01)

### 5.1 Harnais backend reproductible — PASS

Commit intégré `b4db8da`. Stacks neuves, DeepSeek réel isolé, aucune identité live.

| Scénario | Résultat | Oracles |
|---|---|---|
| Backend positif | **PASS** | 3 enregistrements proxy ; modèle `openclaw/<agent>` → `deepseek/deepseek-chat` ; 6 outils client ; `atlas_get_day_plan` + `atlas_propose_plan_revision` corrélés ; `finish_reason=stop` ; 1 proposition `base_version=2`, `A0_USER_REVIEW`, `NONE_AT_CORE`, +10 min ; 1 ACK `APPLIED`, `applied_plan_version=3` ; snapshots v2 (600) et v3 (610) ; rejeu Relay/Core identique sans nouvel appel modèle ; 1 ligne de cache chat |
| Backend négatif | **PASS** | `STALE_VERSION` ; projection `STALE`/`UNKNOWN` → `STALE_DATA` ; activité terminale `VALIDATION_FAILED` (HTTP 400 observé) ; injection : 1 appel modèle réel, aucun fichier sentinelle ; consentement révoqué `CONSENT_REQUIRED` ; **0 proposition, 0 ACK** persistés |

Nettoyage : chaque stack a été arrêtée par identité propriétaire (processus, conteneur,
racine) ; aucune ressource `atlas-v7-06c` résiduelle.

### 5.2 Android APPLY physique — PASS

| Élément | Valeur |
|---|---|
| Appareil | Pixel 9 Pro XL, API 37, arm64-v8a ; série hashée `f2e9258b…` |
| Paquet QA | `dev.atlas.coach.qa.v706b.v706capply.a7f3c21e` |
| APK app / test | `8441a5da…56224d` / `c97427fb…337d8` (conformes à l’autorisation) |
| Runner | `OK (1 test)`, 19,387 s, 12 étapes PASS |
| Modèle | `openclaw/v706c_android-apply_…` → `deepseek/deepseek-chat`, 3 tours proxy, 2 outils corrélés |
| Proposition | `71b80fea-…`, `APPLIED`, `base_version=2`, `A0_USER_REVIEW`, `NONE_AT_CORE`, reschedule 610, durée 45 |
| ACK | `3f67edb5-…`, `APPLIED`, `applied_plan_version=3`, 1 seul événement |
| Plan Core | v2 start=600, v3 start=610, `current_revision_id` v3 = `revision_id` ACK `3ea92f60-…` |
| Rejeu ACK | `ack_replay=identical`, 1 ACK local, aucun second effet |
| Consentements | `DAY_PLAN` et `ACTIVITY_STATUS` `REVOKED` v3, 4 événements |
| Reverse ADB | ajouté puis retiré, état initial restauré |
| Produit/HOME | inchangés (`lastUpdateTime=2026-10-31 00:18:13`, rôle HOME identique) |

### 5.3 Android REJECT physique — PASS

| Élément | Valeur |
|---|---|
| Appareil | Pixel 9 Pro XL, API 37, arm64-v8a ; série hashée `f2e9258b…` |
| Paquet QA | `dev.atlas.coach.qa.v706b.v706creject.b4d9e602` |
| APK app / test | `b599532c…9a6b` / `be283081…0e83` |
| Runner | `OK (1 test)`, 21,214 s, 12 étapes PASS |
| Proposition | `7512ea09-…`, `REJECTED`, `base_version=2`, `A0_USER_REVIEW`, `NONE_AT_CORE` |
| Plan | v2 conservé, start=600, durée 45 ; **aucune ligne v3** |
| ACK | `c22d1829-…`, `REJECTED`, `applied_plan_version=NULL`, 1 seul événement |
| Rejeu ACK | `ack_replay=identical`, 1 ACK local, aucun second effet |
| Consentements | `REVOKED` v3 pour les deux catégories |
| Journal | 1 ACK `completed_redacted`, 1 snapshot, 1 chat, 0 incertain |
| Trace outils | 3 enregistrements, 6 outils exacts, corrélés, `stop`, 0 interdit |

**Incident de harnais et correction :** le premier wrapper a exécuté le test avec
succès mais a refusé la preuve (`ANDROID_ACK_REPLAY_ATTESTATION_MISSING`) car le
buffer logcat contenait les attestations APPLY et REJECT. Le correctif `f9017f4`
(intégré `5ca0a92`) lie l'attestation à l'`ack_id`/état ACK du Core et ajoute
`verify-existing`. L'oracle post-hoc a été exécuté en lecture seule sur la stack
existante (`execution_mode=post_hoc_existing_stack`, aucune nouvelle exécution) et
a produit le proof PASS `v7-06c-reject-2fb57424d534.json`. Le test et le produit
n'étaient pas en cause.

### 5.4 WSS public depuis réseau extérieur — APPLY/REJECT PASS

| Élément | Valeur |
|---|---|
| Réseau | Pixel 9 Pro XL sur **LTE** (`MOBILE[LTE]`, réseau 151), Wi‑Fi désactivé ; DNS public résolu, ping Funnel OK |
| Endpoint | `wss://all.taildc7886.ts.net:8443` ; TLS valide (`curl` 200, `ssl_verify_result=0`) ; Funnel inchangé `8443 → 127.0.0.1:4400` |
| Relais | Conteneur isolé épinglé publié sur `127.0.0.1:4400` (port déjà ciblé par le Funnel), identité/jetons synthétiques neufs |
| Exposition | Core `8111`, Gateway `19076`, proxy `19075` en loopback uniquement ; aucun port Core/Gateway publié |
| `adb reverse` | **absent** (`requested=false`, `added=false`) sur les deux scénarios |
| APPLY | stack `android-apply-1940890` ; paquet `…v706capply.f1e2d3c4b5a6` ; APK `92fb9708…` / `57321867…` ; proposition `26240af5-…` APPLIED, plan v2→v3, ACK `80962380-…` APPLIED v3 |
| REJECT | stack `android-reject-1968579` ; paquet `…v706creject.bf5b0d3e2c4a` ; APK `7759c26b…` / `c6eebb6a…` ; proposition `9adb680d-…` REJECTED, plan v2, ACK `6122332c-…` REJECTED |
| Oracles | Snapshots v2/v3 et {2} ; consentements révoqués v3 ; journal connecteur ; trace outils 3 tours corrélés ; rejeu ACK identique |
| Funnel après | Cible 4400 de nouveau sans listener (état initial) ; relais live `atlas-v7-relay` sur 4402 intact ; aucun changement Funnel/Tailscale |

### 5.5 Sonde de reconnexion après révocation — FAIL d’observabilité (P1)

Le device QA `968aff85-ab1e-4b50-a398-10085e1d2c25` a été révoqué dans le registre
du connecteur isolé (`revoked_at=2026-11-01T14:05:07Z`). La sonde instrumentée
`V7_06C_RevokedDeviceReconnectInstrumentedTest` a reconnecté l’app QA existante :

- **Refus serveur confirmé** : métriques connecteur `connections_opened_total=2`,
  `sessions_opened_total=1` (aucune nouvelle session), `errors_total=1`,
  `active_sessions=0`.
- **Côté client** : `state=ERROR`, `close_code=null`, credentials présents.
  Le probe a échoué son assertion stricte (`explicit_refusal_not_observed`) ; il
  n’a pas observé `REVOKED`/`AUTH_REQUIRED` ni le code 4001 attendu.
- Conclusion : la révocation est **effective côté serveur** (aucune nouvelle
  session/opération), mais l’observabilité client A4-4 reste défectueuse
  (`ERROR`/`null` au lieu d’un refus lisible `REVOKED`/4001). Le test est rapporté
  **FAIL**, pas transformé en PASS.

### 5.6 Incident de fiabilité — blocage intermittent avant chat (P1)

Au **premier** essai APPLY public (stack `android-apply-1853166`), le test a
synchronisé consentements + plan v2 + pull, persisté le message USER dans la
conversation locale, puis **aucune requête `v4.coach.chat.request`** n’a atteint le
connecteur. Le runner hôte a été coupé après 540 s ; l’instrumentation est restée
active jusqu’à `am force-stop`. La relance sur une stack neuve a **PASS** (APPLY
public). Root cause non confirmée : hypothèse dominante d’inversion de verrous
`consentGate` ↔ WebSocket (`AgentContextCoordinator.consentStillAllows`,
`RelayBackendTransport.pump`), cohérente avec le P2 V7-06A « non reproduit ».
Aucun thread-dump n’a pu être obtenu (debuggerd root requis ; le second essai a
passé avant la fenêtre de dump). Défaut classé **P1 fiabilité**, non corrigé.

### 5.7 Nettoyage et identités

- 18 paquets QA (`dev.atlas.coach.qa*`, y compris les anciens qa/qa2/qa3/qa4) ont
  été désinstallés après autorisation explicite. Le produit `dev.atlas.coach`,
  `dev.atlas.coach.test`, HOME, Keystore et données personnelles sont intacts.
- Wi‑Fi restauré (`wifi_on=1`, réseau par défaut 152), aucun `adb reverse`.
- Le Funnel est revenu à son état initial (cible 4400 sans listener) ; le relais
  live `atlas-v7-relay` sur 4402 et le Core/Gateway live n’ont pas été modifiés.
- Les identités live résiduelles (`pixel-v7`, `r3c-pixel-final`, consentements
  `GRANTED v2` du `device_ref` `android-9a3f0f…`) restent **non révoquées** ; aucune
  action live n’a été effectuée sans autorisation ciblée.

## 6. Décisions et limites

- Le harnais V7-06B sous `/tmp` est audité comme source de reprise ; sa version
  minimale reproductible est portée sous `scripts/v7-06c/` par le ticket 01.
- Le test instrumenté `V7_06B_CoachLoopInstrumentedTest` et son runner
  `scripts/v7-06b/android/run_qa.py` sont réutilisés sans création d’un troisième
  harnais ; le namespace de paquet reste `dev.atlas.coach.qa.v706b.*`.
- Le paquet produit `dev.atlas.coach` n’est ni installé, ni remplacé, ni effacé,
  ni instrumenté.
- Le rejeu du même ACK est exécuté par le test Android avant révocation, avec
  l’enveloppe `InboxV4Ack` exacte ; le harnais vérifie l’attestation logcat liée à
  l’`ack_id` et l’unicité de l’événement Core. Le rejeu backend V7-06B reste une
  preuve distincte.
- Aucun verdict GO n’est prononcé avant exécution réelle et oracle indépendant.
- **P1 fiabilité** : blocage intermittent avant chat observé une fois en public ;
  relance PASS. Aucun correctif produit appliqué ; diagnostic indépendant requis.
- **P1 observabilité A4-4** : device révoqué refusé par le connecteur mais app
  `ERROR`/`close_code=null`, pas `REVOKED`/4001. Le probe est **FAIL**.
- Le P2 HTTP 422 est corrigé (`02be9c1`, `b33d43b`) avec test rouge→vert et
  backend 372/1 ; les autres codes HTTP n’ont pas été modifiés.
- Le `GO_REMOTE_V4_PUBLIC` porte sur le parcours APPLY/REJECT public réellement
  exécuté ; il ne vaut ni `GO_PIXEL_PRODUCT` ni `GO_PRODUCT_REMOTE`.
- Les identités live résiduelles et les paquets produit restent hors périmètre de
  la campagne ; aucune révocation live n’a été exécutée.
