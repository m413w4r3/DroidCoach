# V7-06B — trace OpenClaw réel (Gateway isolé)

**Date :** 2026-11-01
**BASE_SHA :** `b55062e1890ff10139ba5ce4a1384cb729c5c0f1`
**Scénario :** backend positif, stack isolée (`/tmp/atlas-v7-06b-private/positive-820936`)
**Résultat :** `PASS` — 3 enregistrements de proxy, 2 appels d’outils client corrélés,
une proposition A0, ACK durable `APPLIED`, plan v2→v3.

## Chaîne de modèle observée

| Élément | Valeur constatée |
|---|---|
| Gateway | OpenClaw `2026.10.5` (`1b58cb9`) isolé, état/configuration/workspace privés |
| Agent | `v706b_positive_820936_0c7271` (synthétique, éphémère) |
| Alias demandé par Core | `openclaw/v706b_positive_820936_0c7271` |
| Provider/modèle effectif | `deepseek/deepseek-chat` (provider custom isolé) |
| Politique d’outils | `minimal` + deny explicite ; `codeMode.enabled=false` |
| Plugin natif `atlas_core_tools` | **absent** (`gateway_native_plugin=false`) |
| Catalogue global candidat | 15 groupes (non représentatif de la surface effective) |
| Surface native effective | vide (vérifiée par `tools.effective` sur session synthétique) |
| Outils client exposés | 6, exactement les outils ATLAS autorisés |

**Décision propriétaire tracée :** le modèle réel utilisé est DeepSeek, choisi après
constat que l’état OAuth personnel `openai/gpt-6-luna` ne peut pas être réutilisé dans
une instance isolée sans copier l’état/session personnels. Cette trace ne revendique
donc ni `gpt-6-luna`, ni le modèle du Gateway live, ni le produit.

## Enregistrements du proxy (métadonnées seulement)

Source : `openclaw-proxy.jsonl` du stack privé, proxy loopback devant le Gateway.
Les prompts, réponses, corps, jetons, identifiants de requête et identifiants d’appel
sont absents ; les identifiants d’appel sont représentés par des hashes tronqués.

| # | Durée | Rôles du message | Outils demandés | Outils retournés | Corrélation `tool_call_id` | `finish_reason` | HTTP |
|---|---|---|---|---|---|---|---|
| 1 | 9 723,68 ms | `system, user` | 6 | `atlas_get_day_plan` | aucune (premier appel) | `tool_calls` | 200 |
| 2 | 2 298,56 ms | `system, user, assistant, tool` | 6 | `atlas_propose_plan_revision` | `541eb387a8237af0` | `tool_calls` | 200 |
| 3 | 2 488,97 ms | `system, user, assistant, tool, assistant, tool` | 6 | aucun | `541eb387a8237af0`, `f42b922b5018417a` | `stop` | 200 |

Les six outils demandés à chaque tour sont :
`atlas_get_day_plan`, `atlas_get_activity_status`, `atlas_propose_plan_revision`,
`atlas_get_usage_summary`, `atlas_get_goal_progress`, `atlas_get_training_plan`.
Aucun outil natif (`exec`, `shell`, `read`, `write`, `edit`, `apply_patch`, `gateway`,
`session_status`, `presence`, `wait`, `ls`, `process`, `code_execution`) n’apparaît
dans les noms retournés.

## Lecture de la boucle

1. Core envoie la conversation et les six définitions d’outils client au Gateway.
2. Le modèle réel demande `atlas_get_day_plan` ; Core exécute l’outil localement
   (`V4ToolService`) et renvoie le résultat dans un message `role=tool`.
3. Le modèle demande `atlas_propose_plan_revision` ; Core exécute l’outil localement,
   persiste une proposition A0 (`A0_USER_REVIEW`, `NONE_AT_CORE`, `base_version=2`)
   et renvoie le résultat.
4. Le modèle termine par `finish_reason=stop`, sans nouvel appel d’outil.
5. Aucun outil natif du Gateway n’est exécuté ; aucun double dépôt plugin natif/Core
   n’est possible dans cette configuration (plugin absent, surface native vide).

## Oracles backend associés

| Oracle | Valeur |
|---|---|
| `tool_call_count` Core | 2 |
| Proposition(s) persistée(s) | 1, `base_version=2`, `A0_USER_REVIEW`, `NONE_AT_CORE` |
| Diff | `target_start_minute=610` (+10 min) |
| Plan avant ACK | v2, `start_minute=600`, `FRESH` |
| ACK durable | 1 événement `APPLIED`, `applied_plan_version=3` |
| Replay ACK | même enveloppe rejouée : réponse identique, `replayed=true`, **1 seul** événement ACK, aucun nouvel effet |
| Plan après ACK | v3, `start_minute=610`, `FRESH` |
| Pull après ACK | 0 proposition |
| Replay Core | même réponse, aucun nouvel appel modèle (`traceCountBefore=traceCountAfter=3`) |
| Replay Relay | même réponse, `replayed=true`, aucun nouvel appel modèle |
| Cache chat | 1 ligne |
| Consentements finaux | révoqués, version 2 |

## Limites

- Cette trace prouve le chemin **Core → client tools → Gateway → modèle réel** dans une
  instance isolée avec DeepSeek. Elle ne prouve pas un modèle `gpt-6-luna`, ni le
  Gateway live, ni l’application produit, ni un déploiement public.
- Les durées et octets sont ceux d’un environnement local synthétique et ne constituent
  pas une mesure de performance produit.
- Aucune donnée personnelle, aucun jeton, aucun contenu de conversation n’est reproduit.
