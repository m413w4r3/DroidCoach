# V7-01B — audit lecture seule des ambiguïtés

Date : 2026-10-30
Branche : `v7-01b-isolated-core-proof`
`BASE_SHA` de la preuve initiale : `b759bdafbf0c67eda1a14ae2cf2f3fac75239e4c`
Commit audité avant ce rapport : `4f8d844ccf825c1d0e5b50a601ba08953ed55b2a`
OpenClaw installé : `2026.10.5` (`1b58cb9`)
Agent d’audit effectif : `codex / gpt-6-luna`, effort `xhigh` (métadonnées Paseo : `effectiveThinkingOptionId=xhigh`).

Audit sans modification de configuration, session, service ou fichier préexistant. Seul ce nouveau rapport est ajouté. Aucun token, contenu de prompt, code généré ou donnée personnelle n’est reproduit.

## Verdicts

| Champ | Verdict | Motif |
|---|---|---|
| `EXEC_ANOMALY` | `CONFIRMED — CODE_MODE` | Le modèle a réellement demandé la façade Code Mode `exec`; son code s’est achevé. Ce n’est pas le shell `exec` exclu par la projection. |
| `NATIVE_TOOL_PROOF` | `BLOCKED` | Les appels ATLAS de la trajectoire ne portent pas leur source; les outils client homonymes ont priorité sur les outils plugin. Le GET interne n’a aucun identifiant corrélable et son ordre contredit la causalité alléguée. |
| `TOOLS_EFFECTIVE` | `FAIL` | L’aperçu lu ne contient aucun outil ATLAS et marque 66 entrées Core `excluded`; la documentation précise que cet aperçu ne représente pas l’inventaire exact d’un run actif. |
| `PROPOSED_MINIMAL_FIX` | `TEST ONLY — NOT APPLIED` | Expérience isolée avec un grant au niveau du plugin, puis nouvelle mesure de l’aperçu; éviter le profil `full`, qui élargirait l’exposition Core. |

## 1. Événements `exec` et arguments du statut

J’ai lu en mode SQLite `mode=ro` la base Gateway `/home/nill/.openclaw/agents/atlas/agent/openclaw-agent.sqlite`, tables `session_windows`, `transcript_events` et `trajectory_runtime_events`. Les coordonnées ci-dessous sont les numéros de séquence des événements, avec leurs horodatages UTC; les clés de session sont synthétiques et leurs suffixes suffisent à les distinguer.

Pour la session `…d788`, la ligne `transcript_events.seq=5` à `20:18:44.420Z` est un message assistant contenant explicitement un `toolCall` nommé `exec`, avec les champs d’arguments `code:string` et `title:string`. La trajectoire runtime enregistre `tool.call exec` à `20:18:44.439Z`, puis `tool.result exec` à `20:18:44.593Z`, `success=true`; le transcript indique `status=completed` et `isError=false`. Une seconde exécution dans cette même session commence à `20:25:49.123Z` (`seq=11`) avec `code`, `title` et `awaitResults:boolean`; son résultat est aussi terminé avec succès.

La session `…9b0` contient un `toolCall exec` à `20:26:18.434Z` (`transcript_events.seq=5`) avec les mêmes champs Code Mode et `awaitResults`. La session réussie `…d022` contient un autre appel à `20:41:46.795Z`; le tool ATLAS qui suit reçoit `{}` et le résultat de la cellule est `completed`/succès à `20:41:46.926Z`.

Ce ne sont pas des événements d’une autre session : les messages assistant, les résultats de tools et les événements runtime sont dans les sessions synthétiques correspondantes et portent le même run Gateway. L’argument exact du champ `code` n’est pas recopié. Sa forme suffit à identifier le tool : la documentation Code Mode définit `exec` par `title` et `code` (`tools/code-mode/tool-surface.md:21-39`); le tool shell normal utilise `command` (`:49-53`). La même documentation indique que la façade modèle `exec` est Code Mode et que le shell `exec`, s’il est autorisé, est caché séparément (`:314-318`). Le résultat `ok` correspond donc à l’exécution terminée d’une cellule JavaScript Code Mode, pas à une preuve que le shell `exec` Core était autorisé. La configuration n’a pas de réglage global `tools.codeMode` explicite; OpenClaw 2026.10.5 applique alors `auto` (`tools/code-mode/configuration.md:11-17`).

L’hypothèse d’un `activity_id` vide ou non UUID n’est pas confirmée. Dans `…d788`, l’argument réel de `atlas_get_activity_status` contient `activity_id:string` valide comme UUID (`transcript_events.seq=7`, `20:18:44.576Z`); le runtime retourne `success=true` (`trajectory_runtime_events.seq=9`, `20:18:44.559Z`). La tentative suivante contient aussi des `activity_id` et `plan_id` valides comme UUID (`…9b0`, `seq=7`). La cause des deux `UPSTREAM_UNAVAILABLE` initiaux reste donc à diagnostiquer séparément; cette hypothèse précise est réfutée par les arguments conservés.

## 2. Corrélation de l’appel natif

La trajectoire Gateway enregistre pour `…d788` les tool calls `atlas_get_day_plan` et `atlas_get_activity_status`, puis `success=true` pour chacun (`trajectory_runtime_events.seq=6-9`, entre `20:18:44.549Z` et `20:18:44.559Z`). Dans le troisième tour `…d022`, elle enregistre `atlas_get_day_plan` avec `{}` et `success=true` (`seq=6-7`, `20:41:46.908Z-20:41:46.910Z`).

Ces noms ne prouvent pas l’origine plugin. Le résultat Code Mode indique un catalogue avec six outils `openclaw` et six outils `client`; les métadonnées conservées associent les tool calls à leurs noms, mais pas à `source` ni `pluginId`. Le Core V4 envoie lui aussi six fonctions du même nom à `/v1/chat/completions` et exécute localement chaque réponse de tool call via `execute_tool` (`backend/atlas_backend/openclaw_adapter.py:743-753, 893-935`; `backend/atlas_backend/v4_tool_service.py:81-82`).

La documentation Code Mode établit l’ordre du catalogue — outils Core, plugins, puis outils client — et précise que, pour un nom exact dupliqué, le tool client ultérieur gagne (`tools/code-mode/tool-surface.md:262-278`). Les six outils ATLAS sont effectivement homonymes côté plugin et côté client. La meilleure explication des événements de chat est donc le dispatch client/local du Core V4; les traces conservées ne donnent toutefois pas l’identité de source par appel individuel. Elles ne prouvent pas que le plugin natif a été exécuté.

Le seul journal Core rapporté pour le GET interne affiche `/internal/v4/day-plan` HTTP 200 à `20:18:44.741952821Z`, soit `7.074 ms` après la ligne d’accès du POST `/v4/agent/chat` à `20:18:44.734878568Z`. Il survient également après `session.ended` Gateway à `20:18:44.704Z`. Le GET n’a pas de request ID dans la ligne d’accès conservée. Or le plugin natif crée un UUID indépendant et l’envoie sous `X-Atlas-Request-Id` (`extensions/atlas_core_tools/src/core-client.ts:395-408`, en-tête `:301-309`); cet identifiant n’est ni dans le journal d’accès rapporté, ni disponible dans les logs du conteneur, supprimé à son arrêt `--rm`. Il n’existe donc pas de lien de requête permettant d’attribuer ce GET au tool call Gateway. Il peut s’agir d’une requête directe ou concurrente, ou d’un autre processus; les traces restantes ne départagent pas ces cas.

Le test demandé avec `tools.invoke` n’a pas été exécuté. OpenClaw documente cette méthode comme `operator.write`, tandis que `tools.catalog` et `tools.effective` sont `operator.read` (`gateway/protocol/operator-methods.md:28-54`). Selon la condition posée, son exécution aurait nécessité une autorisation de portée distincte et aurait effectivement invoqué un tool. Aucun code d’erreur Gateway/Core n’a donc été observé pour cet appel. La vérification de listeners en lecture seule montre actuellement 8100 et 18789 à l’écoute et aucun listener sur 18100; cette observation ne remplace pas un appel plugin.

**Requalification :** `NATIVE_TOOL_PROOF: BLOCKED`. La ligne GET prouve qu’un GET interne a abouti, mais ni l’appelant ni son rattachement au tour chat ne sont établis. Le statut `PASS` du rapport initial (`V7-01B_ISOLATED_CORE_PROOF.md:107-109`) n’est pas supporté par les identifiants et l’ordre disponibles.

## 3. `tools.effective`, politique et plugin optionnel

Lecture de configuration, limitée aux champs non secrets : `agents.entries.atlas.tools.profile=minimal`, `deny=[gateway, session_status, presence]`, `alsoAllow` contient les six noms ATLAS. `plugins.entries.atlas_core_tools.enabled=true`; les six capacités sont activées. Aucun secret ni valeur de token n’a été lu dans la sortie de cette inspection.

Les appels Gateway en lecture seule donnent deux résultats distincts :

- `tools.catalog` pour `agentId=atlas` présente les six noms avec `source=plugin`, `pluginId=atlas_core_tools`, `optional=true` et `defaultProfiles=[]`. Le manifeste local les déclare optionnels (`~/.openclaw/extensions/atlas_core_tools/openclaw.plugin.json:60-85`); l’implémentation les déclare également `optional: true` (`src/index.ts:55-64`). Le plugin chargé et ses six outils figurent donc bien au catalogue.
- `tools.effective` sur la session synthétique existante `…d207d022` retourne `profile=minimal`, `checked=live-session`, `groups=[]` et 66 entrées Core au statut `excluded`; aucun outil ATLAS ne figure dans `toolAccess`. `exec` y est `excluded`. La notice est `browser-filtered-by-profile`. La liste exacte des 66 noms est dans le rapport initial (`V7-01B_ISOLATED_CORE_PROOF.md:71-90`).

La politique OpenClaw explique le résultat Core : `minimal` est un profil restreint; le profil par agent prime sur le `full` global, et les trois entrées `deny` retirent aussi les outils `gateway`, `session_status` et `presence` du profil. Les outils plugin sont un autre ensemble : le profil `full` les sélectionne par wildcard, tandis que les outils optionnels exigent une sélection explicite. Le résolveur installé accepte une sélection par plugin ID **ou par nom d’outil** (`dist/tools-g_oFPp5o.mjs:270-271, 515-525`; `dist/tool-grant-allowlist-m1tXxJWt.mjs:13-27`). `alsoAllow` est fusionné à l’allowlist de profil (`dist/agent-tools.policy-B291qxvx.mjs:95-103`). Les six noms présents dans la configuration devraient donc constituer un grant explicite; le constat ne s’explique pas par une absence de ces noms dans `alsoAllow`.

La documentation dit que `tools.effective` est une projection prospective serveur incluant Core, plugin, canal et MCP, mais pas l’inventaire exact d’un run; une entrée absente ne démontre pas qu’elle est désactivée (`gateway/protocol/operator-methods.md:33-52`). Code Mode rend cette limite concrète ici : le modèle voit `exec`/`wait`, alors que les autres outils passent dans un catalogue caché (`tools/code-mode/tool-surface.md:11-18`), et les outils client homonymes peuvent masquer les plugin tools. La réponse observée ne couvre donc pas cet inventaire actif; les 66 statuts décrivent les candidats Core, pas les 6 outils ATLAS cachés.

**Conclusion `TOOLS_EFFECTIVE` :** l’oracle strict de présence des six noms échoue (`FAIL`), mais cet aperçu n’est pas une preuve fiable que le run actif ne pouvait pas appeler les tools. Il ne doit pas être utilisé seul comme inventaire d’exécution pour cette trajectoire Code Mode.

## 4. Modification minimale proposée pour un ticket séparé

Ne pas basculer l’agent vers `full` : ce profil sélectionne tous les outils Core et les plugins optionnels, ce qui élargit nettement la surface. Dans une copie jetable de configuration seulement, tester une seule variante : remplacer les six entrées nominatives de `agents.entries.atlas.tools.alsoAllow` par le seul plugin ID `atlas_core_tools`, garder `profile=minimal` et les trois `deny`, puis relire `tools.effective`. Le plugin contient actuellement ces six tools uniquement. Ce test distingue la sélection d’un plugin optionnel par identifiant de la projection Code Mode; le code installé accepte déjà les noms exacts, donc il s’agit d’un diagnostic, pas d’un correctif confirmé.

Pour une preuve native, le ticket de test doit éviter les six noms client en collision — par exemple en testant l’inventaire plugin sans transmettre les fonctions V4 client, ou en donnant au chemin client des noms distincts dans une fixture isolée. Il faudra alors corréler le `toolCallId` Gateway avec le `X-Atlas-Request-Id`/`request_id` reçu et renvoyé par le Core. Aucune de ces modifications ni aucun appel d’écriture n’a été effectué ici.

## 5. Limites et état final

- Aucun restart, changement de configuration, suppression de session/fichier, ADB ou appel `tools.invoke`.
- Le conteneur Core isolé a déjà été arrêté lors de la preuve initiale. Au contrôle des sockets, 8100 et 18789 sont à l’écoute; 18100 ne l’est pas. Les services live n’ont pas été modifiés.
- L’absence de request ID dans l’access log et la disparition des logs du conteneur empêchent la corrélation rétrospective. L’erreur exacte qu’aurait renvoyée `tools.invoke` n’est pas connue.
- L’échec `UPSTREAM_UNAVAILABLE` des premiers chats n’est pas attribué par cet audit; les `activity_id` disponibles sont valides. Le résultat réussi du tour à un seul tool est cohérent avec le dispatch local Core, pas une preuve native plugin.
- Aucun test n’a été lancé; l’audit est constitué de consultations read-only des traces, de la configuration filtrée, du catalogue/aperçu Gateway et des sources installées.
