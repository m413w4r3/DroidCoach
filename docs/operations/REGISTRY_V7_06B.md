# ATLAS V7-06B — Coach Loop : registre de campagne

**Mission :** prouver le fil rouge Android QA → Relay → Core → OpenClaw réel →
proposition A0 → Android → DayPlan v2→v3 → ACK Core, ainsi qu’un scénario de rejet.

**BASE_SHA :** `b55062e1890ff10139ba5ce4a1384cb729c5c0f1` (local = `origin/main`
observé au lancement). **Aucun push, PR, merge distant, aucun service live
modifié, aucune action téléphone sans autorisation ciblée.**

**Environnement :** profils OpenClaw, Core, SQLite, connecteur, relais et
appairage synthétiques neufs. L’installation OpenClaw personnelle, le Core live,
le connecteur live et le Pixel du propriétaire ne sont pas réutilisés.

## 1. Contraintes d’exécution

- 6 enfants Paseo simultanés maximum, dont 3 tickets d’implémentation au plus ;
  aucune sous-délégation.
- Tous les enfants : `provider=codex`, `model=gpt-6-luna`, effort effectif
  `xhigh`, vérifié dans les métadonnées Paseo de chaque session.
- Un worktree et une branche locale par enfant ; un seul propriétaire d’écriture
  par fichier ; le parent intègre localement.
- Audits/relectures : worktree détaché + `chmod -R a-w`, contrôle du HEAD et de
  `git status` après exécution.
- Un seul opérateur ADB/AVD à la fois ; jamais `adb -d` ni le Pixel produit.
- Aucun token, bearer, secret, UUID personnel, contenu de conversation ou
  donnée du propriétaire dans les preuves ; traces expurgées et bornées.
- Une notification de fin ≠ validation : le parent relit diff, tests, preuves et
  limites avant `ACCEPTED`/`INTEGRATED`.

## 2. Pré-vol observé (2026-11-01, lecture seule)

| Élément | Constat |
|---|---|
| Git local / distant | `main` = `origin/main` = `b55062e1890ff10139ba5ce4a1384cb729c5c0f1`, arbre propre au lancement |
| CI du HEAD | run `41292054517` : **success** (`verify`, `relay-node`) |
| OpenClaw installé | `2026.10.5` (`1b58cb9`), Node `v24.18.0`, Gateway personnel actif sur `127.0.0.1:18789` |
| Core live | conteneur `atlas-core-atlas-core-1` healthy, aucune proposition V4, **2 consentements résiduels** `DAY_PLAN`/`ACTIVITY_STATUS` `GRANTED` v2 pour `android-9a3f…` |
| Connecteur live | `pixel-v7` (14 capabilities V4) et `r3c-pixel-final` (6 capabilities V1/V2) non révoqués ; aucun autre appareil actif |
| OpenClaw politique live | `tools.profile=full`, agent `atlas` : `allow=[atlas_core_tools]`, `deny=[gateway,session_status,presence,exec,process,code_execution,wait]`, `codeMode.enabled=false` ; **non modifiée** |
| Android | JDK 21, SDK 37, KVM présent, AVD `atlas_v7_06a_api37` existant ; boot headless tenté le 2026-11-01 : `emulator-5580`, Android 17 / API 37, `sys.boot_completed=1`, services `activity`/`package` trouvés, x86_64. Mémoire disponible ~4–5 Gi pendant la stack. |
| Appareil physique | non utilisé ; toute nouvelle opération exige une autorisation ciblée distincte |

**Décision de pré-vol :** aucune révocation/rotation live n’est appliquée. La
procédure ciblée (sauvegarde puis révocation de `pixel-v7` et
`r3c-pixel-final`, rotation éventuelle du bearer Core) reste soumise à une
autorisation explicite et sera documentée dans le rapport final. V7-06B
n’utilise ni ces identités, ni ces consentements, ni le bearer Android live.

### 2bis. Sonde runtime parent du Gateway isolé (2026-11-01)

Sonde bornée exécutée par le parent dans `/tmp`, avec un état/configuration/
workspace OpenClaw neufs, sans toucher au Gateway personnel (dont le fichier de
configuration est resté inchangé, mtime `2026-10-30 22:46:57`) :

- OpenClaw `2026.10.5` démarre avec `OPENCLAW_STATE_DIR`/`OPENCLAW_CONFIG_PATH`
  privés, `gateway.auth.mode=token`, `chatCompletions.enabled=true`.
- Le provider `claude-cli` a été écarté : il n’expose pas les outils client
  fournis dans `/v1/chat/completions` et laisse apparaître des outils natifs du
  harness CLI (fichiers/recherche/agent), non gouvernés par la politique ATLAS.
- Un provider custom `deepseek` (`api=openai-completions`,
  `baseUrl=https://api.deepseek.com`, `apiKey` SecretRef
  `env:deepseek:DEEPSEEK_API_KEY`, modèle `deepseek-chat`) reçoit réellement la
  requête. La clé reste dans l’environnement, absente du dépôt et des preuves.
- Agent neuf, `tools.profile=minimal` + `deny` explicite
  `[presence,session_status,gateway,exec,process,code_execution,wait,ls,read,write,edit,apply_patch]`,
  `codeMode.enabled=false` : `tools.catalog` renvoie **0 outil natif** pour
  l’agent.
- Avec une fonction client `probe_echo` synthétique, la réponse HTTP est
  `finish_reason=tool_calls` et contient `probe_echo` ; le Gateway n’exécute pas
  le code métier client. Le modèle réel est `deepseek-chat` via l’alias
  `openclaw/<agent>`.
- Conclusion : le chemin canonique **boucle Core + outils client** est
  réalisable avec un Gateway isolé, un modèle réel et **zéro outil natif**. La
  sonde ne remplace pas la recette E2E complète (consentements, projection,
  proposition, ACK) ; elle établit seulement le maillon Gateway/modèle.

**Précision importante (tools.catalog vs tools.effective) :** `tools.catalog`
liste le catalogue global des outils cœur candidats (73 outils en groupes) et
n’est **pas** la surface effective de l’agent. Une session synthétique réelle
(warm-up `/v1/chat/completions`, `user=v706b-probe`, modèle DeepSeek réel) suivie
de `tools.effective` avec
`sessionKey=agent:<agent>:openai-user:v706b-probe` renvoie `groups=[]`,
`profile=minimal`, et `toolAccess.tools` avec `ls`, `read`, etc. au statut
`excluded` (raisons `profile` + `deny`). La surface native effective est donc
vide ; le modèle ne reçoit que les outils client. L’oracle du harnais doit
utiliser `tools.effective`/la politique, pas un catalogue global à zéro.

**Décision propriétaire (2026-11-01) :** après explication de l’impossibilité de
réutiliser l’auth OAuth `openai/gpt-6-luna` du Gateway personnel sans partager/
copier son état, le propriétaire a choisi de **continuer avec le modèle réel
DeepSeek dans l’instance isolée**. Cette décision est explicitement tracée :
les verdicts V7-06B portent sur un modèle réel `deepseek/deepseek-chat` via le
Gateway OpenClaw isolé, **pas** sur le modèle `gpt-6-luna` du Gateway live.
`GO_PRODUCT_REMOTE` et toute revendication sur le modèle produit restent
fermés.

## 3. Tickets

| TASK_ID | Objectif | Dépendance | Statut | Session Paseo | Worktree | Oracle |
|---|---|---|---|---|---|---|
| V7-06B-AUDIT-A1-CORE-PATH | Audit lecture seule du chemin d’outils orchestré par Core | BASE_SHA | **ACCEPTED** — chemin canonique = boucle Core, double dépôt P1 possible/non prouvé ; worktree intact | `757fc89c-0a5d-43e4-90ef-f2bb6be3a20f` | `/tmp/atlas-v7-06b-a1-core` | A1_STATUS=STATIC_COMPLETE_RUNTIME_NOT_RUN, CANONICAL_PATH=CORE_CHAT_V4_EXECUTE_TOOL, DOUBLE_EFFECT_RISK=POSSIBLE_UNPROVEN_P1 |
| V7-06B-AUDIT-A2-NATIVE-GATEWAY | Audit lecture seule plugin natif + sémantique Gateway/instance isolée | BASE_SHA | **ACCEPTED** — outils client délégués, outils natifs exécutés, double effet inter-chemins possible ; recette isolée statique | `b5da0ea1-c55d-479c-9a96-9b951f9f608d` | `/tmp/atlas-v7-06b-a2-native` | A2_STATUS=STATIC_AUDIT_COMPLETE_RUNTIME_NOT_RUN; GATEWAY_TOOL_LOOP=CLIENT_CALLS_RETURNED_NATIVE_TOOLS_EXECUTED; NATIVE_PLUGIN_DOUBLE_EFFECT=POSSIBLE_CROSS_PATH_UNGUARDED; ISOLATED_GATEWAY_FEASIBLE=YES_CANDIDATE_NOT_RUN |
| V7-06B-AUDIT-A3-ANDROID-RELAY | Audit lecture seule harnais Android/Relay et gaps V7-06B | BASE_SHA | **ACCEPTED** — 9 gaps identifiés (isolation, UI, second pull, oracle Core, preuve des tools) ; worktree intact. KVM vu par le parent (`/dev/kvm`), absent dans le sandbox Codex : à re-vérifier par l’opérateur AVD. | `19b692af-5843-4e62-bea9-9566deab8d1b` | `/tmp/atlas-v7-06b-a3-android` | A3_STATUS=STATIC_AUDIT_COMPLETE; HARNESS_GAPS=FAIL (9); AVD_STRATEGY=BLOCKED dans le sandbox, tentative unique après pré-vol parent |
| V7-06B-E2E-BACKEND | Stack isolée Core/Gateway/Relay/connecteur + recette backend réelle | AUDIT A1/A2 + FIX TERMINAL | **ACCEPTED (runtime)** — positif **PASS** (DeepSeek réel, 2 tool calls, 1 proposition A0, ACK APPLIED, plan v3, replay sans appel modèle) et négatif **PASS** (STALE/UNKNOWN, stale version, terminal, injection, consentement révoqué, 0 proposition/ACK) | `762fb3e9-6440-4be4-96c1-81676d7a64cb` | `/tmp/atlas-v7-06b-backend` | Invocation réelle, deux tool calls, une proposition A0, `base_version=2`, `NONE_AT_CORE`, aucun dépôt double ; négatif terminal après correctif |
| V7-06B-FIX-TERMINAL-ACTIVITY | Garde backend : statut terminal effectif (dernier événement) pour reschedule/edit_activity | E2E-BACKEND BLOCKED | **ACCEPTED** — commits `dd7b2de` + `4a8adae` (test contractuel réaligné), 8 rouges→verts, **357 passed / 1 skipped** backend complet, `git diff --check` PASS ; intégrés `fba6462`/`191ccd0` | `45fd538e-fdd3-4746-8004-ce32b604c93d` | `/tmp/atlas-v7-06b-terminal-guard` | Test rouge→vert, `VALIDATION_FAILED`, aucune proposition ; suite backend complète PASS |
| V7-06B-FIX-STATUS-GUARD | Garde statuts fail-closed : consentement, version, plan, séquence, fraîcheur | REVUE P1 | **ACCEPTED (code + tests)** — commit `9aecda8` intégré `25bfed8` ; 12 cas rouge→vert `STALE_DATA`, 10 contrôles existants, **22/22 ciblés**, backend **370 passed / 1 skipped** | `072814b2-af8b-4578-9dd4-6289b4845c4d` | `/tmp/atlas-v7-06b-status-guard` | Incertitude → `STALE_DATA` sans proposition ; terminal valide → `VALIDATION_FAILED` ; fallback snapshot conservé |
| V7-06B-ANDROID-QA | Harnais QA séparé, fil rouge 18 étapes + reject | AUDIT A3 + E2E-BACKEND | **BLOCKED (environnement AVD)** — builds QA/JVM/DEX PASS, harnais durci `b67de16`/`29f9502` ; aucun runtime instrumenté : AVD API 37.2 16 Ko instable (UWB HAL puis surfaceflinger, redémarrages zygote). Aucun Pixel physique utilisé. | `dd5c99d1-4f14-4a6a-bdcd-b362fa3d7633` | `/tmp/atlas-v7-06b-android` | Test instrumenté PASS, oracle Core APPLIED/plan v3, rejet sans changement — **NON EXÉCUTÉ** |
| V7-06B-REVIEW | Revue indépendante lecture seule du SHA intégré | E2E + ANDROID-QA | **REJECTED (initial)** — P1 garde statuts (corrigé `25bfed8`), P2 preuve ACK (fermé par replay réel), P2 SHA (clarifié) ; **re-review à exécuter** sur le HEAD corrigé | `3999e794-40d5-4379-8742-4134b34688b7` | `/tmp/atlas-v7-06b-review-rw` | P0/P1 ouverts : P1 corrigé, re-review requise |

## 3bis. Défaut bloquant découvert et correctif séparé

Le ticket E2E backend a reproduit, sur SQLite temporaire, le comportement
suivant : un événement `PLANNED → DONE` accepté en HTTP 200 persiste bien dans
`activity_status_events_v4`, mais une proposition `reschedule` de la même
activité retourne `QUEUED` avec un `proposal_id` au lieu de `VALIDATION_FAILED`.
`V4ToolService._build_diff` lit le statut du snapshot et ignore le dernier
événement de statut, alors que `atlas_get_activity_status` utilise cet
événement. C’est un écart de sécurité pour le cas négatif « activité
protégée » et une incohérence entre lecture et proposition.

- Décision : correctif borné `V7-06B-FIX-TERMINAL-ACTIVITY`, propriétaire
  exclusif de `backend/atlas_backend/v4_tool_service.py` et d’un nouveau test
  `backend/tests/test_v7_06b_terminal_activity.py`.
- Exigence : test rouge d’abord (DONE/MISSED/CANCELLED/POSTPONED → refus,
  aucune proposition ; IN_PROGRESS → toujours autorisé), puis correction du
  statut effectif ; aucune modification de contrat, de timeout ou d’idempotence.
- Le ticket E2E backend reprend après fusion du correctif ; il ne doit pas
  contourner ce cas ni le marquer PASS sans preuve.
- L’agent E2E a tenté d’utiliser `send_agent_prompt` pour joindre le parent ;
  la permission a été refusée (aucune sous-délégation / message inter-agents).
  Son rapport final a suffi.

## 3ter. Régressions de suite complète et corrections

- **Backend** : la suite complète sur le SHA intégré a révélé un test obsolète
  (`test_v4_projection_store.py::test_all_six_tools_match_contracts...`) qui
  proposait un déplacement sur une activité déjà `DONE`. La nouvelle garde
  terminale le refuse correctement ; le test est corrigé pour conserver son
  objectif contractuel (proposition sur une activité PLANNED, statut DONE lu
  séparément), sans affaiblir la garde.
- **Android** : la revue parent a trouvé deux bugs de harnais avant runtime :
  (1) l’assertion d’idempotence supposait à tort que Core conserve la clé du
  prompt, alors que Core la remplace par une UUIDv5 ; (2) le runner traitait
  `INSTRUMENTATION_CODE: -1` comme un échec alors que c’est le code de succès.
  Corrigés dans `886d92d` ; builds QA/AndroidTest, JVM et DEX PASS.
- Ces corrections sont de test/harnais, pas de comportement produit, sauf la
  garde terminale `dd7b2de` qui est un correctif de sécurité borné.

## 3quater. Suites préliminaires sur la branche d’intégration (avant merge T1)

| Suite | Résultat | Note |
|---|---|---|
| Android JVM `:app:testDebugUnitTest --offline --rerun-tasks` | **896/896 PASS**, 0 échec, 0 erreur, 0 ignoré | 117 fichiers de résultats |
| Android lint `:app:lintDebug --offline` | **PASS** | rapport HTML/SARIF généré |
| APK QA + AndroidTest `.qa.v706b.compile` | **PASS** | app `dev.atlas.coach.qa.v706b.compile`, test `.test` ; hashes préliminaires app `8f4065be…`, test `3df719cf…` |
| Connecteur Node `npm run test:unit` | **109/109 PASS** | 17 fichiers |
| Plugin OpenClaw `npm test` | **59/59 PASS** | `atlas_core_tools` |
| Backend `pytest -q` | **356 passed / 1 failed / 1 skipped** | échec = test obsolète `test_v4_projection_store.py` après la garde terminale ; correction test-only en cours (T3), aucun défaut produit supplémentaire |

Ces résultats seront rejoués sur le SHA final après merge T1/T3 ; les hashes APK
définitifs seront recalculés. Les 71/71 backend ciblés du correctif et les
109/109 Node du connecteur sont déjà PASS sur les commits respectifs.

## 3quinquies. E2E backend réel — résultats runtime (2026-11-01)

Stack isolée complète (Core FastAPI + SQLite temporaire, Gateway OpenClaw isolé,
provider réel DeepSeek, proxy loopback, relay épinglé `atlas-paseo-relay:r3a`,
connecteur PC, identités/appairage/jetons synthétiques neufs). Aucun service
live, aucune session OpenClaw personnelle, aucun Pixel produit n’est utilisé.

| Scénario | Résultat | Oracles constatés |
|---|---|---|
| Positif | **PASS** | 3 requêtes proxy, modèle `openclaw/<agent>` → `deepseek/deepseek-chat` ; 6 outils client ; `atlas_get_day_plan` puis `atlas_propose_plan_revision` ; `tool_call_id` corrélés ; `finish_reason=stop` ; `tool_call_count=2` ; 1 proposition `base_version=2`, `A0_USER_REVIEW`, `NONE_AT_CORE`, diff +10 min ; plan v2 (600) inchangé avant ACK ; 1 ACK `APPLIED`, `applied_plan_version=3` ; replay du même ACK : réponse identique, `replayed=true`, **1 seul** événement ACK ; plan v3 (610) `FRESH` ; pull 1→0 ; replay Core et Relay identiques sans nouvel appel modèle ; 1 ligne de cache chat ; consentements révoqués v2 ; plugin natif absent, surface native vide |
| Négatif | **PASS** | version de plan stale → `STALE_VERSION` ; projection STALE → proposition `STALE_DATA` ; projection UNKNOWN → lecture `UNKNOWN`/`NO_PLAN` et proposition `STALE_DATA` ; activité terminale → `VALIDATION_FAILED` sans proposition ; injection atteint le modèle réel mais ne crée aucun fichier sentinelle et n’exécute aucun outil interdit ; consentement révoqué → `CONSENT_REQUIRED` sans appel modèle ; **0 proposition, 0 ACK** persistés |

**Corrections de harnais (aucun changement produit supplémentaire après T3) :**

- enveloppe relay : `expires_at` ramené de +120 s à +30 s (le protocole exige
  ≤ 60 s après `created_at` ; les requêtes étaient rejetées `INVALID_REQUEST`) ;
- identité d’arrêt Gateway : le processus réécrit sa ligne de commande en
  `openclaw-gateway`, la signature d’arrêt accepte cette forme ;
- stderr des processus redirigé vers des fichiers privés 0600 au lieu d’un pipe,
  pour que les stacks lancées par un processus séparé survivent et restent
  arrêtables ;
- oracle UNKNOWN aligné sur le contrat : `data.state=NO_PLAN` (branche vide
  schéma-valide) et non `data=null` ;
- refus d’activité terminale : le harnais accepte 400 ou 422 avec
  `VALIDATION_FAILED` ; l’observation réelle est **400** (voir P2) ;
- diagnostics client privés et push/install Android bornés avec retries.

**P2 non bloquant :** la route interne `/internal/v4/plan-revision-proposals`
retourne `VALIDATION_FAILED` en HTTP **400** via le mapping local de
`invoke_tool`, alors que la table `_ERROR_STATUS` du même routeur déclare
**422**. Le refus, l’absence de proposition et l’oracle de sécurité sont
corrects ; recommandation : aligner le mapping de `invoke_tool` sur 422 dans un
ticket borné séparé, sans élargir V7-06B.

**Traçabilité :** `docs/operations/evidence/V7-06B_OPENCLAW_TRACE.md`.
Le verdict porte sur le modèle réel **DeepSeek** de l’instance isolée, pas sur
`gpt-6-luna` ni sur le produit.

## 3sexies. Runtime Android QA — blocage environnemental AVD (2026-11-01)

Le harnais QA a été construit, installé et préparé, mais **aucune instrumentation
n’a pu s’exécuter** sur l’AVD API 37 disponible. Aucun résultat Android n’est
revendiqué.

**Symptômes observés :**

- AVD `atlas_v7_06a_api37` : Android 17 / API 37, x86_64,
  `google_apis_ps16k` (image 16 Ko), 6 Go de RAM alloués après diagnostic.
- `android.hardware.uwb-service` abandonne en boucle à l’ouverture de
  `/dev/uwb0` (`ENXIO`, `No such device or address`) ; `init` marque
  `sys.init.updatable_crashing_process_name=vendor.uwb_hal` puis tue `zygote`
  (crash recovery), ce qui redémarre `system_server` toutes les ~10–15 s.
- UWB désactivé (`-feature -Uwb`, `vendor.qemu.vport.uwb` absent, service jamais
  démarré), un autre crash apparaît : `surfaceflinger` reçoit `SIGABRT` dans
  `RegionSampling` ; `init` tue de nouveau `zygote`. Le cycle persiste avec
  `-gpu swiftshader_indirect`, `-gpu off` et `-gpu guest`.
- Testés : 3 Go puis 6 Go, `-wipe-data` + cold boot, `-no-snapshot`,
  désactivation UWB, trois modes GPU. Le service `package` apparaît/disparaît
  avec les redémarrages ; aucune fenêtre stable de 20 s n’a été obtenue.
- Le Pixel physique connecté n’a **pas** été utilisé : aucune autorisation
  ciblée nouvelle, donc aucune opération ADB sur cet appareil.

**Conséquences :**

- `GO_ANDROID_QA_CHAT`, `GO_ANDROID_QA_PLAN_APPLY`, `GO_ANDROID_QA_ACK` et
  `GO_QA_COACH_LOOP` restent **NO GO / BLOCKED** : le fil rouge Android n’est
  pas prouvé, même si le backend et les contrats le sont.
- Le harnais a été durci sans revendication runtime : attente bornée de
  `run-as`, création de `files/`, retries bornés sur push pairing et install
  APK (`b67de16`, intégré `29f9502`). Aucun changement produit.
- L’AVD est jetable ; après diagnostic, l’émulateur, la stack isolée et les
  conteneurs V7-06B ont été arrêtés et supprimés. Aucun conteneur ni root
  V7-06B ne subsiste.

**Nature du blocage :** défaut de l’image/émulateur API 37.2 16 Ko sur cet hôte
(UWB virtio puis `surfaceflinger`), pas une régression ATLAS. Une clôture Android
17 exige un AVD sain ou un Pixel physique avec consentement ciblé et sauvegarde,
selon `android17-device-qa`.

## 3septies. Suites finales sur l’arbre intégré

| Suite | Résultat |
|---|---|
| Backend `pytest -q` | **370 passed / 1 skipped** (36,66 s), après correctif `25bfed8` |
| Garde statuts ciblée (T3b) | **22/22 PASS** (12 cas d’incertitude `STALE_DATA` + 10 contrôles) |
| Replay ACK réel (même enveloppe) | **PASS** — réponse identique, `replayed=true`, 1 seul événement ACK, aucun nouvel effet |
| Connecteur Node `npm run test:unit` | **109/109 PASS** (17 fichiers) |
| Plugin OpenClaw `npm test` | **59/59 PASS** |
| Android JVM `:app:testDebugUnitTest --offline --rerun-tasks` | **896/896 PASS**, 0 échec/erreur/ignoré (117 fichiers) ; BUILD SUCCESSFUL 2 min 24 s |
| Android lint `:app:lintDebug --offline` | **PASS** ; rapports HTML/SARIF générés |
| APK QA + AndroidTest `.qa.v706b.compile2` | **PASS** ; app SHA-256 `4e5f9455…d919d3`, test SHA-256 `24441a33…e891` ; packages `dev.atlas.coach.qa.v706b.compile2` et `.test` ; compileSdk 37 |
| `git diff --check` | **PASS** (exit 0) |

Ces suites sont statiques/build ; aucun test instrumenté Android n’a été
exécuté (voir 3sexies).

## 4. Journal des sessions

| Date | Session | Rôle | Provider/modèle/effort constatés | Résultat |
|---|---|---|---|---|
| 2026-11-01 | `757fc89c-0a5d-43e4-90ef-f2bb6be3a20f` | A1 Core path (RO) | codex / `gpt-6-luna` / `xhigh` | FINISHED — `ACCEPTED`, chemin canonique Core, double effet P1 non prouvé |
| 2026-11-01 | `b5da0ea1-c55d-479c-9a96-9b951f9f608d` | A2 native/Gateway (RO) | codex / `gpt-6-luna` / `xhigh` | FINISHED — `ACCEPTED`, outils client délégués, outils natifs exécutables |
| 2026-11-01 | `19b692af-5843-4e62-bea9-9566deab8d1b` | A3 Android/Relay (RO) | codex / `gpt-6-luna` / `xhigh` | FINISHED — `ACCEPTED`, 9 gaps harnais |
| 2026-11-01 | `762fb3e9-6440-4be4-96c1-81676d7a64cb` | E2E Backend | codex / `gpt-6-luna` / `xhigh` | FINISHED — BLOCKED (défaut produit) puis **RUNNING — reprise** après `dd7b2de` |
| 2026-11-01 | `45fd538e-fdd3-4746-8004-ce32b604c93d` | Fix terminal guard | codex / `gpt-6-luna` / `xhigh` | FINISHED — `ACCEPTED`, commits `dd7b2de` + `4a8adae`, backend 357/1 |
| 2026-11-01 | `dd5c99d1-4f14-4a6a-bdcd-b362fa3d7633` | Android QA | codex / `gpt-6-luna` / `xhigh` | FINISHED — `ACCEPTED (code)`, commits `91d1ce2` + `886d92d`, builds QA/JVM/DEX PASS ; runtime NOT_RUN |
| 2026-11-01 | parent (post-T1) | E2E backend réel | DeepSeek réel isolé ; pas de nouveau modèle parent revendiqué | `ACCEPTED` — positif PASS, négatif PASS, corrections harnais, `V7-06B_OPENCLAW_TRACE.md` |
| 2026-11-01 | parent/opérateur AVD | Android QA runtime | AVD API 37.2 16 Ko, aucun Pixel physique | `BLOCKED (environnement)` — UWB HAL puis `surfaceflinger`, `zygote` redémarré ; aucune instrumentation lancée |
| 2026-11-01 | parent (intégration) | Durcissement harnais QA | — | `b67de16` intégré `29f9502` : `run-as` borné, `files/`, retries push/install ; runtime non revendiqué |

## 5. Décisions d’orchestration

- Les audits précèdent toute implémentation ; aucun correctif produit n’est
  lancé avant d’avoir établi le chemin canonique et le risque de double effet.
- Le chemin canonique devra être démontré, pas supposé : invocation réelle du
  modèle, outils autorisés uniquement, résultats d’outils réellement reçus par
  le modèle, aucun succès mock, aucun double dépôt.
- Les P2 V7-06A restent suivis ; seuls ceux qui affectent concrètement le fil
  rouge V7-06B seront corrigés avec un test ciblé.
- La qualification AVD est bornée : pré-vol, un boot, diagnostic, puis
  isolement dans un ticket environnement si `sys.boot_completed` et les
  services ne sont pas sains. Aucun enchaînement de boots défaillants.
- `GO_QA_COACH_LOOP` exige le modèle réel, une proposition A0, une application
  Android et un ACK constaté côté Core. Les autres verdicts restent distincts.
