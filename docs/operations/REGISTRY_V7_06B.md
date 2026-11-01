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
| Android | JDK 21, SDK 37, KVM présent, AVD `atlas_v7_06a_api37` existant ; mémoire disponible ~5,8 Gi au lancement ; boot AVD non tenté à ce stade |
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
| V7-06B-E2E-BACKEND | Stack isolée Core/Gateway/Relay/connecteur + recette backend réelle | AUDIT A1/A2 | **BLOCKED — défaut produit reproduit** : un événement `PLANNED→DONE` n’empêche pas une proposition `reschedule` (`QUEUED` au lieu de `VALIDATION_FAILED`). Worktree propre, aucun commit. Reprise après `V7-06B-FIX-TERMINAL-ACTIVITY`. | `762fb3e9-6440-4be4-96c1-81676d7a64cb` | `/tmp/atlas-v7-06b-backend` | Invocation réelle, deux tool calls, une proposition A0, `base_version=2`, `NONE_AT_CORE`, aucun dépôt double ; négatif terminal après correctif |
| V7-06B-FIX-TERMINAL-ACTIVITY | Garde backend : statut terminal effectif (dernier événement) pour reschedule/edit_activity | E2E-BACKEND BLOCKED | **RUNNING** | `45fd538e-fdd3-4746-8004-ce32b604c93d` | `/tmp/atlas-v7-06b-terminal-guard` | Test rouge→vert, `VALIDATION_FAILED`, aucune proposition ; suites backend ciblées PASS |
| V7-06B-ANDROID-QA | Harnais QA séparé, fil rouge 18 étapes + reject | AUDIT A3 + E2E-BACKEND | **RUNNING** | `dd5c99d1-4f14-4a6a-bdcd-b362fa3d7633` | `/tmp/atlas-v7-06b-android` | Test instrumenté PASS, oracle Core APPLIED/plan v3, rejet sans changement |
| V7-06B-REVIEW | Revue indépendante lecture seule du SHA intégré | E2E + ANDROID-QA | READY | — | à créer | Aucun P0/P1 ouvert, preuves cohérentes |

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

## 4. Journal des sessions

| Date | Session | Rôle | Provider/modèle/effort constatés | Résultat |
|---|---|---|---|---|
| 2026-11-01 | `757fc89c-0a5d-43e4-90ef-f2bb6be3a20f` | A1 Core path (RO) | codex / `gpt-6-luna` / `xhigh` | FINISHED — `ACCEPTED`, chemin canonique Core, double effet P1 non prouvé |
| 2026-11-01 | `b5da0ea1-c55d-479c-9a96-9b951f9f608d` | A2 native/Gateway (RO) | codex / `gpt-6-luna` / `xhigh` | FINISHED — `ACCEPTED`, outils client délégués, outils natifs exécutables |
| 2026-11-01 | `19b692af-5843-4e62-bea9-9566deab8d1b` | A3 Android/Relay (RO) | codex / `gpt-6-luna` / `xhigh` | FINISHED — `ACCEPTED`, 9 gaps harnais |
| 2026-11-01 | `762fb3e9-6440-4be4-96c1-81676d7a64cb` | E2E Backend | codex / `gpt-6-luna` / `xhigh` | FINISHED — **BLOCKED** défaut produit reproduit, worktree propre, aucun commit |
| 2026-11-01 | `45fd538e-fdd3-4746-8004-ce32b604c93d` | Fix terminal guard | codex / `gpt-6-luna` / `xhigh` | RUNNING |
| 2026-11-01 | `dd5c99d1-4f14-4a6a-bdcd-b362fa3d7633` | Android QA | codex / `gpt-6-luna` / `xhigh` | RUNNING |

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
