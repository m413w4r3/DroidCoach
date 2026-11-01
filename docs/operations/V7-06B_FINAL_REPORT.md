# ATLAS V7-06B — Coach Loop : rapport final

**Campagne :** V7-06B « Coach Loop »
**Base :** `b55062e1890ff10139ba5ce4a1384cb729c5c0f1`
**SHA final local :** `fcb5329` (branche `feat/atlas-v7-06b-coach-loop`) — aucun push, PR, merge distant
**Date :** 2026-11-01
**Registre :** `docs/operations/REGISTRY_V7_06B.md`
**Preuve OpenClaw :** `docs/operations/evidence/V7-06B_OPENCLAW_TRACE.md`
**Preuve Android :** `docs/operations/evidence/V7-06B_COACH_LOOP.md`

## 1. Résultat exécutif

La boucle **backend réelle** est prouvée de bout en bout dans une stack isolée :
Core FastAPI → Gateway OpenClaw isolé → modèle réel **DeepSeek** →
proposition A0 → ACK durable `APPLIED` → plan local v2→v3, avec un modèle
effectivement appelé et deux outils client corrélés. Le scénario négatif refuse
les versions/projections invalides, l’activité terminale, l’injection et le
consentement révoqué, sans persister de proposition ni d’ACK.

La **boucle Android** n’est pas prouvée au runtime : le harnais QA est livré,
compilé et durci, mais l’AVD Android 17 / API 37 disponible sur cet hôte est
instable (UWB HAL puis `surfaceflinger`, redémarrages `zygote`). Aucun test
instrumenté n’a été exécuté, aucun résultat Android n’est revendiqué. Le Pixel
physique n’a pas été utilisé faute d’autorisation ciblée nouvelle.

## 2. Décision de modèle et portée des verdicts

Le Gateway OpenClaw personnel utilise `openai/gpt-6-luna`, mais son état OAuth
personnel ne peut pas être réutilisé dans une instance isolée sans copier
l’état/session de l’utilisateur, ce qui est interdit. Après explication, le
propriétaire a explicitement choisi de continuer avec un modèle réel
**`deepseek/deepseek-chat`** dans l’instance isolée.

En conséquence, les verdicts OpenClaw V7-06B portent sur un modèle réel DeepSeek
via un Gateway isolé, **pas** sur `gpt-6-luna`, **pas** sur le Gateway live,
**pas** sur l’application produit et **pas** sur un déploiement public.

## 3. Chemin canonique et absence de double effet

Les audits A1/A2 ont établi :

- le chemin canonique est la **boucle orchestrée par Core** : Core envoie six
  définitions d’outils client, le modèle retourne des `tool_calls`, Core exécute
  localement via `V4ToolService`, puis renvoie les résultats au modèle ;
- le plugin natif `atlas_core_tools` et les outils natifs du Gateway constituent
  un second chemin qui pourrait doubler les effets ; il est **exclu** de la
  stack V7-06B ;
- l’agent isolé utilise `tools.profile=minimal`, un deny explicite
  (`presence`, `session_status`, `gateway`, `exec`, `process`,
  `code_execution`, `wait`, `ls`, `read`, `write`, `edit`, `apply_patch`),
  `codeMode.enabled=false`, et **aucun** `plugins.entries.atlas_core_tools` ;
- `tools.catalog` est un catalogue candidat global (15 groupes) et non la
  surface effective ; `tools.effective` sur une session synthétique réelle
  montre `groups=[]` et les outils natifs `excluded` par profil/deny.

La trace positive confirme que seuls les six outils client ATLAS sont demandés
et que les seuls outils retournés sont `atlas_get_day_plan` puis
`atlas_propose_plan_revision`, avec corrélation des `tool_call_id` et
`finish_reason=stop`.

## 4. Stack isolée

| Élément | Mise en œuvre |
|---|---|
| Core | FastAPI réel, SQLite temporaire, tokens Android/outil synthétiques distincts |
| Gateway | OpenClaw `2026.10.5` (`1b58cb9`) isolé, état/config/workspace privés |
| Modèle | provider custom DeepSeek, `deepseek/deepseek-chat`, clé uniquement dans l’environnement |
| Relay | image épinglée `atlas-paseo-relay:r3a`, digest immuable `sha256:75d68fd8…` |
| Transport | Noise NK protégé, WebSocket `/ws?serverId=…&role=client&v=2` |
| Connecteur PC | identité, devices, pairing et jetons neufs, état privé |
| Réseau | loopback uniquement ; aucun changement Funnel/Tailscale/service live |
| Nettoyage | conteneurs, racines et processus V7-06B arrêtés et supprimés après preuve |

Aucune identité, session, clé Keystore, donnée personnelle ou configuration du
Gateway personnel n’a été copiée ou modifiée.

## 5. Scénario backend positif — PASS

| Oracle | Constat |
|---|---|
| Modèle | alias `openclaw/<agent>` → `deepseek/deepseek-chat` réel |
| Requêtes proxy | 3, HTTP 200, `finish_reason` `tool_calls`, `tool_calls`, `stop` |
| Outils client | exactement 6 définitions à chaque tour, aucune native |
| Appels outils | `atlas_get_day_plan`, puis `atlas_propose_plan_revision` |
| Corrélation | `tool_call_id` du premier appel retrouvé dans le message `tool` suivant |
| `tool_call_count` | 2 |
| Proposition | 1, `base_version=2`, `A0_USER_REVIEW`, `NONE_AT_CORE`, `+10 min` |
| Plan avant ACK | v2, `start_minute=600`, `FRESH` |
| ACK | 1 événement durable `APPLIED`, `applied_plan_version=3` |
| Plan après ACK | v3, `start_minute=610`, `FRESH` |
| Pull après ACK | 0 proposition |
| Replay Core | même réponse, aucun nouvel appel modèle (`traceCountBefore=traceCountAfter=3`) |
| Replay Relay | même réponse, `replayed=true`, aucun nouvel appel modèle |
| Cache chat | 1 ligne |
| Consentements finaux | `DAY_PLAN` et `ACTIVITY_STATUS` révoqués, version 2 |
| Plugin natif | absent ; surface native vide |

## 6. Scénario backend négatif — PASS

| Cas | Résultat |
|---|---|
| Version de plan stale | `STALE_VERSION`, aucun `proposal_id` |
| Projection STALE | lecture `STALE` ; proposition `STALE_DATA` (409) |
| Projection UNKNOWN | lecture `UNKNOWN`, `data.state=NO_PLAN` ; proposition `STALE_DATA` |
| Activité terminale (`DONE`) | `VALIDATION_FAILED`, aucune proposition |
| Injection de prompt | modèle réel atteint (1 appel proxy), aucun fichier sentinelle, aucun outil interdit |
| Consentement révoqué | `CONSENT_REQUIRED`, aucun appel modèle |
| Persistance | **0 proposition, 0 ACK** |

## 7. Android QA — harnais livré, runtime bloqué

**Code livré :** `V7_06B_CoachLoopInstrumentedTest` (APPLY/REJECT), runner
`scripts/v7-06b/android/run_qa.py`, package QA séparé
`dev.atlas.coach.qa.v706b.*`, pairing privé 0600, logcat expurgé, refus du
package produit et des appareils physiques sans autorisation explicite.

**Commits :** `91d1ce2` (harnais), `886d92d` (corrections), `b67de16`
(durcissement push/install), intégrés localement dans `4f68c65` et `29f9502`.

**Builds :** QA APK + AndroidTest, JVM, lint et DEX PASS (voir § Suites).

**Runtime : BLOCKED par l’environnement AVD.** L’AVD `atlas_v7_06a_api37`
(Android 17 / API 37, x86_64, image 16 Ko) redémarre `zygote`/`system_server`
toutes les ~10–15 s :

- `android.hardware.uwb-service` abandonne sur `/dev/uwb0` (`ENXIO`) ;
- UWB désactivé (`-feature -Uwb`), `surfaceflinger` reçoit `SIGABRT` dans
  `RegionSampling` ;
- variantes testées : 3/6 Go, `-wipe-data`, cold boot, GPU
  `swiftshader_indirect`/`off`/`guest` ; aucune fenêtre stable de 20 s ;
- aucune instrumentation n’a été lancée ; le Pixel physique n’a pas été touché.

Un AVD sain ou un Pixel Android 17 avec consentement ciblé et sauvegarde reste
nécessaire pour exécuter APPLY et REJECT et prononcer `GO_ANDROID_QA_*`.

## 8. Corrections et dette

### Correctif produit borné

**`dd7b2de` — garde d’activité terminale.** `V4ToolService._build_diff` lisait
le statut du snapshot et ignorait le dernier événement de statut ; une
proposition `reschedule`/`edit_activity` sur une activité `DONE`/`MISSED`/
`CANCELLED`/`POSTPONED` pouvait être déposée. Le correctif calcule le statut
effectif depuis le dernier événement et refuse avec `VALIDATION_FAILED` sans
persister. Test rouge→vert, 8 cas ; test contractuel réaligné (`4a8adae`) ;
backend complet **357 passed / 1 skipped**.

### Corrections de harnais (aucun produit)

- enveloppe relay `expires_at` +120 s → +30 s (limite protocole 60 s) ;
- signature d’arrêt Gateway `openclaw-gateway` (titre réécrit du processus) ;
- stderr des processus vers fichiers privés 0600 (durabilité des stacks) ;
- oracle UNKNOWN aligné sur la branche `NO_PLAN` du contrat ;
- acceptation du statut HTTP réel 400 avec `VALIDATION_FAILED` ;
- diagnostics client privés et push/install Android bornés avec retries.

### P2 non bloquant

`/internal/v4/plan-revision-proposals` retourne `VALIDATION_FAILED` en HTTP 400
via le mapping local de `invoke_tool`, alors que la table `_ERROR_STATUS` du
routeur déclare 422. Le refus et l’absence de proposition sont corrects ;
recommandation : aligner le mapping dans un ticket borné séparé.

## 9. Suites finales sur l’arbre intégré

| Suite | Résultat |
|---|---|
| Backend `pytest -q` | **357 passed / 1 skipped** (26,74 s) |
| Connecteur Node `npm run test:unit` | **109/109 PASS** (17 fichiers) |
| Plugin OpenClaw `npm test` | **59/59 PASS** |
| Android JVM `:app:testDebugUnitTest --offline --rerun-tasks` | **896/896 PASS**, 0 échec, 0 erreur, 0 ignoré (117 fichiers) ; BUILD SUCCESSFUL |
| Android lint `:app:lintDebug --offline` | **PASS** ; rapports HTML/SARIF générés |
| APK QA + AndroidTest `.qa.v706b.compile2` | **PASS** ; `dev.atlas.coach.qa.v706b.compile2` (SHA-256 `4e5f9455…d919d3`, 15 Mo) et `.test` (SHA-256 `24441a33…e891`, 1,6 Mo) ; compileSdk 37 / Android 17 |
| `git diff --check` | **PASS** (exit 0) |

## 10. Verdicts

| Verdict | Décision | Portée |
|---|---|---|
| `GO_OPENCLAW_REAL_CHAT` | **GO** | modèle réel DeepSeek, Gateway isolé, chat V4, outils client |
| `GO_OPENCLAW_TOOL_PLAN_READ` | **GO** | `atlas_get_day_plan` exécuté et résultat renvoyé au modèle |
| `GO_OPENCLAW_TOOL_PROPOSAL` | **GO** | `atlas_propose_plan_revision`, A0/NONE_AT_CORE, base 2 |
| `GO_ANDROID_QA_CHAT` | **NO GO** | runtime Android non exécuté (AVD bloqué) |
| `GO_ANDROID_QA_PLAN_APPLY` | **NO GO** | idem |
| `GO_ANDROID_QA_ACK` | **NO GO** | idem |
| `GO_QA_COACH_LOOP` | **NO GO** | maillon Android non prouvé |
| `GO_REMOTE_V4_PUBLIC` | **NO GO** | aucun WSS/TLS public testé ; recette `V7-06C_REMOTE_RECIPE.md` |
| `GO_PRODUCT_REMOTE` | **NO GO** | modèle/stack isolés, pas le produit |

Les verdicts GO OpenClaw sont **scopés** au modèle DeepSeek isolé et au chemin
Core ; ils ne valident ni `gpt-6-luna`, ni le Gateway live, ni Android, ni le
produit.

## 11. Sécurité et conformité

- Aucun push, PR, merge distant, déploiement ou modification de service live.
- Aucune opération sur le Pixel physique ; aucun root/Device Owner/kiosque/
  AccessibilityService ajouté au produit.
- Aucun secret, token, identifiant personnel, contenu de conversation ou donnée
  intime dans les preuves ; traces expurgées, IDs synthétiques.
- Stack jetable : conteneurs, racines et processus supprimés après la campagne.
- Les actions Android restent des propositions ; seules des règles
  déterministes consenties exécutent, et la pause d’urgence reste locale.

## 12. Travaux restants pour la clôture

1. Fournir un AVD Android 17/API 37 sain ou un Pixel physique avec consentement
   ciblé, sauvegarde et opérateur unique ; exécuter APPLY puis REJECT du harnais.
2. Vérifier indépendamment les états Core (proposition, ACK, plan v3/v2,
   absence de double application, révocation) sur les deux scénarios.
3. Exécuter une revue indépendante lecture seule du SHA intégré.
4. Traiter le P2 400/422 si le contrat l’exige.
5. Dérouler `V7-06C_REMOTE_RECIPE.md` pour tout verdict public/produit.
