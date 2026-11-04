# ATLAS V7-08 — diagnostic Core/OpenClaw des réponses 504, 400 et 200 sans outil

**Ticket :** A2, diagnostic indépendant, lecture seule du code et des preuves disponibles
**Base SHA :** `cbdf0e2fb26190015a0d529a3ef713ed4b4379ef`
**Branche / worktree :** `v7-08/a2-diag` / `073527gm/v7-08-a2-diag`
**Profil Paseo constaté :** `codex / gpt-6-luna`, option effective `xhigh`
**Périmètre :** 504, 400 et 200 sans outil. Aucun code produit modifié ; aucun service, relay, appareil ou conteneur lancé ou redémarré.

## Méthode et preuve disponible

Les statuts qualifient la force de chaque affirmation : **PROVEN** = vérifiable dans le code actuel ou dans une preuve V7-07 présente dans le dépôt ; **INFERRED** = scénario cohérent avec ces éléments, mais sans trace primaire suffisante ; **INCONCLUSIVE** = les éléments présents ne permettent pas de trancher.

**PROVEN —** le dépôt contient le compte rendu `docs/operations/V7-07_D_RESUME.md`, qui rapporte les valeurs Core et Gateway utiles, mais pas les dumps expurgés du cache Core ni les traces brutes des runs Gateway. Le seul fichier d’évidence dont le nom correspond à V7-07 est `docs/operations/evidence/V7-07_AVD_BACKUP_RESTORE.md`, qui ne constitue pas une trace du chat. `V7-07_PHYSICAL_EXECUTION.md` documente notamment la qualification isolée antérieure du modèle, pas les logs bruts de l’incident D.

**INCONCLUSIVE —** aucune preuve primaire du corps HTTP 400, de son `request_id`, des `tool_call_id`, ni du couple Gateway `session_id/run_id` n’est archivée ici. En particulier, le corps de la branche `tool_call_count>0` n’expose pas `error.code`; il faudrait un log corrélé de l’exception interne pour attribuer le code. Les observations physiques ci-dessous sont donc citées comme rapportées par V7-07, et non comme relecture indépendante des journaux originaux.

## A. 504 après 20 secondes

### Origine et séquence du délai

**PROVEN —** le délai total est fixé à `CHAT_V4_DEADLINE_SECONDS = 20.0` dans `backend/atlas_backend/openclaw_adapter.py:38`. `agent_chat` crée une seule échéance monotone `deadline = time.monotonic() + CHAT_V4_DEADLINE_SECONDS` avant de soumettre `chat_v4` au pool de threads (`backend/atlas_backend/v4_router.py:1411-1432`). Cette échéance couvre toutes les complétions provider, les validations et exécutions locales d’outils, ainsi que la complétion finale. Ce n’est pas un budget de 20 secondes par phase ni par appel modèle.

**PROVEN —** le chemin outillé est séquentiel dans le Core : une complétion provider retourne un ou plusieurs `tool_calls`; le Core les compte, valide puis exécute chaque outil localement; le résultat est ajouté aux messages; la boucle repart vers le provider (`openclaw_adapter.py:739-767`, `820-828`, `891-970`). L’outil `atlas_get_day_plan` est exécuté par le dispatch local de `V4ToolService`, pas par un outil natif du Gateway (`v4_router.py:551-563`, `1329-1349`).

**INFERRED —** la séquence décrite dans V7-07 est : complétion provider #1 avec `stopReason=tool_calls`; exécution locale réussie de `atlas_get_day_plan`; envoi d’une complétion provider suivante avec le résultat de l’outil; expiration du budget Core avant la fin de cette complétion. Le rapport dit explicitement que le premier outil a été appelé puis que « la seconde complétion modèle » a été coupée (`V7-07_D_RESUME.md:66-80`). Le code permet cette séquence. Les traces brutes manquantes empêchent toutefois de confirmer le nombre et les heures exacts des POST provider.

**PROVEN —** chaque complétion utilise `httpx.Client.stream` vers `/v1/chat/completions`, avec `timeout = min(self.timeout_s, remaining)` (`openclaw_adapter.py:587-620`). Le corps est lu en entier par `iter_bytes()`, accumulé, puis décodé en JSON avant que `chat_v4` puisse continuer (`openclaw_adapter.py:649-703`). Il s’agit d’un flux HTTP consommé par le Core, pas d’une réponse token par token renvoyée au téléphone. Des contrôles monotones ont lieu après réception de chunks, après la réponse et après le décodage; la lecture bloquante n’est pas interrompue par ces contrôles tant qu’elle n’a pas rendu la main.

### Future, thread, stream et caller-abort

**PROVEN —** à l’échéance, la coroutine appelle `chat_future.cancel()` puis lève `asyncio.TimeoutError` (`v4_router.py:1432-1438`). Le résultat de `cancel()` n’est pas contrôlé et aucun signal d’annulation coopératif ni fermeture explicite du stream actif n’est transmis au worker. Selon le contrat de `concurrent.futures.Future.cancel()`, un Future déjà en cours d’exécution n’est pas interrompu par cet appel. Le thread peut donc continuer après que la route a décidé de répondre. L’adaptateur dispose de ses propres vérifications de deadline et le contexte `with self._client.stream(...)` ferme la réponse quand le worker sort, mais cela ne constitue pas une annulation immédiate garantie du thread ni de l’appel réseau.

**PROVEN —** le pool est limité à quatre workers (`v4_router.py:56`); un thread encore bloqué après la réponse peut donc retenir un slot jusqu’à son retour. Une opération locale qui avait déjà démarré peut également finir après que la coroutine a pris son snapshot; le code expose alors `uncertain` lorsque l’effet est actif (`v4_router.py:1446-1471`).

**PROVEN —** le timeout httpx est calculé au début de chaque POST avec le temps restant à cet instant. C’est une limite appliquée par phase d’I/O, alors que le Core vérifie ensuite l’échéance entre les chunks. Le budget externe garantit le retour HTTP de la route autour de l’échéance; il ne garantit pas que le worker et son socket soient arrêtés au même instant. Si le worker dépasse la date limite pendant une lecture, il peut ne constater l’échéance qu’après réception d’un chunk ou à l’expiration du timeout de lecture.

**PROVEN —** le client relayé Android laisse 55 s à la requête de chat (`AgentChatClient.kt:466-470`), ce qui est supérieur au budget Core de 20 s. Cela rend le budget Core la première limite configurée dans le parcours normal; cela n’exclut pas une annulation volontaire ou une rupture réseau, que les traces disponibles ne documentent pas.

**PROVEN —** si au moins un outil a démarré, le timeout de la route photographie `attempted`, `completed`, `active`, `uncertain` et les IDs de propositions. Avec une tentative terminée et sans autre outil actif, elle peut renvoyer HTTP 504 avec `tool_call_count=1`, `tool_state=completed`; si un outil reste actif ou incertain, elle choisit `uncertain` (`v4_router.py:1312-1327`, `1446-1471`). Le résultat final est mis en cache par `request_id`, avec son status HTTP et son corps (`v4_router.py:1518-1533`). La table `_chat_error_response` associe également `TIMEOUT` à 504 (`v4_router.py:333-352`).

**PROVEN —** un status 5xx reçu directement du Gateway est classé `UPSTREAM_UNAVAILABLE`, et la route chat le convertit en 503; un 504 renvoyé par le Core à l’échéance vient donc du timeout total du Core, non d’une simple transmission du status HTTP 504 upstream (`openclaw_adapter.py:636-643`, `v4_router.py:341-346`, `1446-1471`).

**INFERRED —** V7-07 rapporte précisément `504` après 20 s, `tool_call_count=1`, `tool_state=completed`, puis un run Gateway en `caller-abort` avec `ClientDisconnectError` (`V7-07_D_RESUME.md:66-81`). Le run #1 a donc, selon ce rapport, terminé normalement sur `tool_calls`; le run suivant n’a pas fourni de complétion finale avant l’abandon. Cela concorde avec l’échéance Core et avec l’état d’outil terminé.

**INCONCLUSIVE —** le rapport n’inclut pas le journal Gateway brut avec les `run_id`, ni les traces Core/httpx. On ne peut donc pas confirmer que le `caller-abort` cite exactement la complétion #2, ni distinguer si la fermeture upstream a été déclenchée par un timeout de lecture httpx, une fermeture ultérieure du stream, ou une autre rupture. `ClientDisconnectError` dans le Gateway décrit une déconnexion de son client HTTP; cela ne prouve pas que l’utilisateur Android, le relay ou le connecteur ait abandonné la requête. Le handler Core n’installe pas ici de traitement explicite `Request.is_disconnected()`/`ClientDisconnect`.

### Attribution et options bornées

| Attribution | Conclusion |
|---|---|
| Core | **PROVEN :** le Core porte une échéance totale de 20 s et fabrique le 504 de timeout. **INFERRED :** c’est la cause la plus probable du 504 observé exactement à 20 s. |
| Gateway | **INFERRED :** il aurait terminé le premier run avec `tool_calls`, puis enregistré un abandon de la complétion suivante. L’état du processus Gateway n’est pas indiqué par ces lignes et n’est pas à confondre avec l’état d’un run. |
| Modèle/provider | **INCONCLUSIVE :** aucun temps de génération, premier token, temps de queue Gateway, ni temps de réponse par POST n’est archivé. On ne peut pas attribuer les secondes écoulées au modèle seul. |
| Outil Core | **PROVEN par le corps rapporté :** l’outil #1 est déclaré `completed`. **INCONCLUSIVE :** sa durée exacte n’est pas fournie. |

**PROVEN —** le code ne suffit pas à chiffrer la part de latence du Core, du Gateway et du provider : il n’enregistre pas de durée par phase sur ce chemin. Le seul chiffre disponible est le délai total observé/rapporté de 20 s.

| Option bornée à évaluer | Effet et risque |
|---|---|
| Instrumenter d’abord les phases, puis attribuer des sous-budgets à l’intérieur du même délai global | Permet de séparer attente provider, exécution outil et traitement Core sans augmenter le plafond. Un sous-budget mal réparti peut faire échouer une phase pourtant viable; choisir les parts après mesures, pas au hasard. |
| Ajouter une annulation coopérative et fermer explicitement la requête provider active | Évite de laisser des workers/sockets vivre après le 504. La fermeture d’un appel provider ne peut pas interrompre sûrement un effet SQLite déjà démarré; les issues d’écriture doivent rester idempotentes et marquées incertaines si le résultat n’est pas connu. |
| Introduire du streaming de bout en bout | Peut afficher une progression plus tôt, mais l’adaptateur actuel accumule la réponse JSON. Le relay/Noise/Android devrait porter un protocole de flux et d’annulation; un texte partiel n’est pas la preuve qu’une proposition a été persistée. Cela ne raccourcit pas nécessairement la séquence modèle → outil → modèle. |
| S’appuyer sur le cache de chat | Le cache actuel empêche de réexécuter le même `request_id` et rejoue sa réponse; il ne réduit pas le délai du premier appel et ne corrige pas une nouvelle requête sur la même session Gateway. Une réponse 200 sans proposition peut elle aussi être rejouée. |

**Recommandation A — PROVEN / PRIORITÉ 1 :** garder le plafond global borné; instrumenter les phases et l’issue du worker; ensuite définir les sous-budgets avec une marge d’annulation. Ne pas augmenter arbitrairement les 20 secondes.

## B. Tous les chemins du 400 dans un tour chat

### Origines possibles dans le Core

| Chemin | Résultat produit par le code | Correspond à `tool_call_count=2, tool_state=partial` ? |
|---|---|---|
| JSON entrant illisible, corps invalide ou échec de `_CHAT_REQUEST_SCHEMA` | `_chat_error_response(..., "BAD_REQUEST")` avant le démarrage de l’agent (`v4_router.py:1223-1228`). Le corps est une erreur V4 et ne contient pas les compteurs d’outils. | **PROVEN :** si le corps Core en cache avait réellement les compteurs rapportés, ce chemin de schéma entrant ne l’explique pas. |
| Échec de sérialisation UTF-8 du corps canonique | `BAD_REQUEST` avant la boucle et sans compteurs (`v4_router.py:1238-1243`). | **PROVEN :** incompatible avec une réponse d’orchestration contenant les deux compteurs, si la description V7-07 du cache est exacte. |
| `conversation_id` impossible à encoder strictement | L’adaptateur lève `ChatOrchestrationFailure("BAD_REQUEST")` (`openclaw_adapter.py:729-732`), avant toute complétion ou tool call. Sur la route HTTP, la canonicalisation UTF-8 du corps peut rejeter le même texte en amont (`v4_router.py:1238-1243`). | **PROVEN :** n’explique pas un compte de deux appels observés. |
| Schéma/enveloppe des arguments générés par le provider | JSON invalide, outil inconnu ou arguments non conformes sont transformés en résultat d’outil V4 `BAD_REQUEST`, renvoyé au modèle dans le message `tool`; ce n’est pas directement un HTTP 400 (`openclaw_adapter.py:858-884`, `924-930`, `973-990`). Le modèle peut corriger, poursuivre ou répondre ensuite sans outil. | **PROVEN :** le compteur peut inclure ces appels, car il est incrémenté avant validation (`827-828`). Seul, ce chemin ne déclenche pas le 400 HTTP montré par le Core. |
| Callback `validate_tool` | Vérifie capability, consentement et version (`v4_router.py:1288-1299`). Il émet `UPSTREAM_UNAVAILABLE`, `CAPABILITY_UNAVAILABLE`, `CONSENT_REQUIRED` ou `STALE_VERSION`, pas `BAD_REQUEST`. | **PROVEN :** une erreur de validation après un outil déjà réussi peut produire `partial`, mais pas `BAD_REQUEST` directement par cette fonction. |
| Schéma interne du contrat `execute_tool` | Un échec de `_TOOL_REQUEST_SCHEMA` est converti en `UPSTREAM_UNAVAILABLE` (`v4_router.py:1332-1338`). | **PROVEN :** pas `BAD_REQUEST` dans le code actuel. |
| `V4ToolError` levée par `V4ToolService` | `execute_tool` propage son code sous forme de `ChatToolFailure` (`v4_router.py:1344-1349`); l’adaptateur la transforme en `ChatOrchestrationFailure` avec compteurs et état (`openclaw_adapter.py:941-952`). Le statut HTTP passe alors par `_chat_error_response` (`v4_router.py:1472-1485`). | **PROVEN :** peut donner 400 avec compteurs; si un outil antérieur a terminé, le statut peut être `partial`. |
| Erreur fournie par relay/connecteur ou traduction côté Android | Le client reçoit un `RemoteError` avec code/status issus de l’enveloppe relay (`RelayBackendTransport.kt:437-447`), puis choisit un message selon `remoteCode`/`httpStatus` (`AgentChatClient.kt:410-422`, `447-464`). | **INCONCLUSIVE :** sans enveloppe du relay, le texte UI ne localise pas l’origine. |

### Pourquoi `partial` est compatible avec `STALE_DATA`

**PROVEN —** `tool_call_count` compte les appels annoncés par le provider avant la validation et l’exécution (`openclaw_adapter.py:822-828`). `tool_state` est construit à partir des appels complétés; l’échec après un succès donne `partial` (`openclaw_adapter.py:562-568`, `891-901`, `941-952`). Ce compteur n’est donc pas un nombre de réussites.

**PROVEN —** `atlas_propose_plan_revision` refuse le snapshot si son horodatage est inconnu ou si le snapshot de plan dépasse les 5 minutes : ces deux branches lèvent `V4ToolError("STALE_DATA")` (`v4_tool_service.py:41-48`, `381-410`, `755-769`). Le plan synthétique décrit par V7-07 pouvait devenir périmé au fil de la reprise, mais le corps de la requête et son horodatage précis au moment du 400 ne sont pas présents dans le dossier.

**PROVEN —** le chemin `STALE_DATA` est converti en exception chat, puis le mapping chat retourne actuellement le status par défaut 400 : `_chat_error_response` n’a ni entrée `STALE_DATA` dans `status_by_code`, ni message dédié et utilise `.get(code, 400)` (`v4_router.py:311-369`). Le schéma d’erreur autorise ce code (`contracts/v4/error.schema.json:23-60`), mais quand `tool_call_count>0`, `agent_chat` n’utilise que le status calculé et renvoie ensuite un corps d’orchestration sans champ `error` (`v4_router.py:1472-1485`). Ce corps ne révèle donc pas si l’exception qui a conduit au 400 était `STALE_DATA` ou une autre erreur.

**INFERRED —** un scénario précis compatible avec le résumé V7-07 est : le premier appel `atlas_get_day_plan` se termine; l’appel suivant `atlas_propose_plan_revision` rencontre un snapshot >5 min; `V4ToolError(STALE_DATA)` remonte; l’adaptateur garde le compte total à 2 et passe l’état à `partial`; le routeur chat renvoie HTTP 400 par défaut, avec un corps d’orchestration sans code d’erreur. Il correspond aux valeurs rapportées `tool_call_count=2`, `tool_state=partial` et aucune proposition (`V7-07_D_RESUME.md:68-74`, `85-88`), mais il n’est **pas prouvé** comme cause de cet incident sans journal corrélé du `tool_call_id` et de l’exception interne.

**PROVEN —** plusieurs branches du service lèvent `V4ToolError("BAD_REQUEST")` pour des arguments invalides (`v4_tool_service.py:146-155`, `210-225`, `265-270`, `325-335`, `534-549`, `571-669`, `830-858`). Une partie des erreurs structurelles (champs, types, formats, plages numériques, opérations) est déjà rejetée par le même JSON Schema côté adaptateur, qui répond au modèle par un résultat d’outil `BAD_REQUEST` plutôt que par HTTP 400 (`openclaw_adapter.py:858-884`; `contracts/v4/tool-requests.schema.json:6-53`, `54-253`, `294-340`). En revanche, le schéma documente la contrainte métier `window_end_after_window_start` comme non vérifiable par JSON Schema standard, et le service l’applique en levant directement `BAD_REQUEST` (`tool-requests.schema.json:255-292`, `v4_tool_service.py:210-220`); c’est un chemin concret d’erreur d’arguments pouvant remonter au status HTTP 400. Pour les autres erreurs redondantes, une différence de schéma ou un appel direct du service serait nécessaire; aucune trace ne permet de dire qu’elle a eu lieu dans V7-07.

**PROVEN —** les curseurs malformés lèvent `CURSOR_INVALID`, pas `BAD_REQUEST` (`v4_tool_service.py:865-879`), mais ce code absent de la table chat tombe lui aussi sur HTTP 400. De même, des erreurs métier `VALIDATION_FAILED` et `PERIOD_TOO_LARGE` peuvent atteindre le défaut 400 du chat alors que leurs mappings généraux sont 422 (`v4_router.py:269-303`, `333-369`; `v4_tool_service.py:221-222`, `579-586`).

**PROVEN —** dans le chemin chat, tout code de `ChatToolFailure` absent de la table `_chat_error_response` tombe aussi sur HTTP 400. Cela inclut des codes différents de `BAD_REQUEST`, tels `STALE_DATA`, `VALIDATION_FAILED`, `CURSOR_INVALID` ou `PERIOD_TOO_LARGE`; le mapping général leur attribue d’autres statuts (`v4_router.py:269-303`, `333-369`; `v4_tool_service.py:222`, `579-586`, `879`). Pour une erreur après au moins un tool call, le corps d’orchestration ne contient pas le code qui a servi à choisir le status.

**PROVEN —** `plan_agent_v3.py` n’est pas le chemin d’exécution des six outils de `agent_chat`: le dispatch V4 appelle directement `V4ToolService` (`v4_router.py:551-563`, `1329-1349`). `plan_agent_v3.propose_plan_revision` et `OpenClawAdapter.propose_plan_revision` constituent un autre chemin de proposition (`plan_agent_v3.py:203-291`, `openclaw_adapter.py:1111-1145`). Ses statuts ne permettent donc pas d’identifier le `BAD_REQUEST` du chat V4.

### Instrumentation minimale (proposition conceptuelle, non appliquée)

**Recommandation B — PROVEN / PRIORITÉ 1 :** journaliser une ligne par phase et par outil avec `request_id`, `tool_call_id` (lorsqu’il existe), nom d’outil sur liste autorisée, phase (`provider_request`, `validate_tool`, `execute_tool`, `proposal_write`), code stable, résultat (`started/completed/rejected/uncertain`), durée monotone, compteurs et nombre/IDs de propositions. Ne jamais journaliser message utilisateur, arguments, résultat complet, bearer, clé, corps de prompt ni données personnelles. Le code actuel ne transmet pas le `request_id` à `chat_v4`, et `validate_tool` ne reçoit pas le `tool_call_id`; un patch conceptuel doit faire transiter ces deux identifiants jusqu’aux logs sans élargir le contenu journalisé.

**INCONCLUSIVE —** sans log corrélé `request_id + tool_call_id + code`, on ne peut pas départager validation d’arguments, `STALE_DATA`, autre `V4ToolError` ou status relay/connecteur pour le 400 V7-07.

## C. 200 sans outil : session Gateway et faux positif A0

**PROVEN —** à chaque appel `chat_v4`, le Core crée une liste locale de messages system + user pour le tour, calcule `provider_user = "atlas-" + sha256(conversation_id)` et envoie ce `user` avec `tool_choice="auto"` (`openclaw_adapter.py:717-760`). Le même `conversation_id` redonne donc le même identifiant utilisateur Gateway. Le compte rendu V7-07 décrit ce passage et la session nommée `agent:atlas:openai-user:…`; la rotation de conversation a ensuite donné zéro session Gateway nouvelle avant l’appel, contrairement à l’ancienne session (`V7-07_D_RESUME.md:146-160`).

**INFERRED —** si le Gateway conserve un historique par ce `user`, une nouvelle requête Core peut être interprétée dans l’historique du run précédent même si le corps local ne contient que le nouveau message. Avec `tool_choice="auto"`, le modèle peut répondre depuis ce contexte sans demander d’outil. V7-07 rapporte les réponses à zéro outil qui réutilisaient l’ancien résultat et affirmaient des données anciennes ou une proposition « en attente » (`V7-07_D_RESUME.md:69-71`, `82-91`). Le lien causal entre chaque texte et un message précis du stockage d’historique Gateway n’est pas démontrable sans trace de session/run.

**PROVEN —** si le provider retourne un message final non vide avec `finish_reason="stop"` et sans propriété `tool_calls`, le Core renvoie `agent_status="ok"`, `tool_state="none"` lorsque rien n’a été exécuté, `tool_call_count=0` et la liste `proposal_ids` courante (vide sans proposition) (`openclaw_adapter.py:792-811`). Cette réponse satisfait la forme de réponse chat et peut devenir HTTP 200 (`v4_router.py:1491-1517`). HTTP 200 prouve donc seulement que le tour conversationnel a produit une réponse finale valide.

**PROVEN —** un 200 avec `tool_call_count=0` et `proposal_ids=[]` ne démontre ni appel d’outil ni A0. Pour refuser ce faux positif, le critère Core minimal est : `agent_status=ok`, `tool_state=completed`, `tool_call_count >= 1`, `proposal_ids` non vide, puis vérification que chaque ID existe dans le stockage Core comme proposition `PENDING_ANDROID`, `confirmation_mode=A0_USER_REVIEW`, `auto_application=NONE_AT_CORE`. Le seul compteur d’appels ne suffit pas puisqu’il est incrémenté avant succès.

**Recommandation C — PROVEN / PRIORITÉ 0 :** le verdict « A0 obtenu » doit dépendre d’un `proposal_id` renvoyé puis vérifié en persistance, jamais du seul HTTP 200 ni du texte final du modèle.

**Recommandation C — PROVEN / PRIORITÉ 1 :** côté Gateway, archiver par requête un `session_id`, `run_id`, un identifiant de corrélation commun, `stopReason`, les `tool_call_id`/noms, leur résultat stable et l’issue de fin (`completed`, `caller-abort`, `error`). Côté Core, lier la chaîne de runs au `request_id` et au hash de conversation, sans contenu de message. Pour qualifier A0, accepter les runs intermédiaires `stopReason=tool_calls` seulement s’ils sont suivis des résultats d’outils correspondants et d’une fin normale; refuser la qualification si la chaîne se termine par `caller-abort`/erreur ou si les IDs d’outil/proposition ne correspondent pas à la réponse et à la persistance Core.

## D. Cohérence des mappings et localisation du 400

### Codes HTTP dans le Core

| Chemin | HTTP 400 | HTTP 409 | HTTP 504 |
|---|---|---|---|
| `_error_response` général | `BAD_REQUEST`, `UNKNOWN_FIELD`, `UNSUPPORTED_VERSION`, `CURSOR_INVALID` | `PLAN_MISMATCH`, `STALE_DATA`, `STALE_VERSION`, `CONFLICT`, `IDEMPOTENCY_CONFLICT`, `EVENT_ID_CONFLICT`, `ACK_ID_CONFLICT`, `ACK_CONFLICT`, `PROPOSAL_EXPIRED`, `REPLAY_PENDING`, `RESULT_UNCERTAIN` | `TIMEOUT` |
| `_chat_error_response` | `BAD_REQUEST`; tout code absent de la table tombe aussi sur 400 par défaut | `STALE_VERSION`, `IDEMPOTENCY_CONFLICT`, `REPLAY_PENDING` | `TIMEOUT` |

Les tables sont respectivement dans `backend/atlas_backend/v4_router.py:269-303` et `333-353`. **PROVEN :** l’affirmation « `STALE_DATA` est mappé 409 dans V4 » est vraie pour `_error_response`, mais fausse pour la route chat actuelle, qui le laisse tomber sur 400. Dans la branche avec outils comptés, le corps de réponse ne contient pas `error.code`; un HTTP 400 n’implique donc pas nécessairement `BAD_REQUEST` comme code applicatif.

**PROVEN —** les réponses entrantes mal formées et les codes `BAD_REQUEST` créés par le Core sont des origines possibles; un `STALE_DATA` provenant du second outil en est une autre à cause du mapping chat. Pour un corps Core en cache qui inclut réellement `tool_call_count=2` et `tool_state=partial`, les branches de validation HTTP initiale sont moins compatibles, car elles retournent avant de lancer `chat_v4`.

**PROVEN —** la couche Android traduit un `RemoteError` de deux façons : un `remoteCode=BAD_REQUEST` devient le message connu « La requête V4 a été refusée… »; un status/codage non reconnu, dont le fallback `HTTP_400`, devient le texte générique « Le serveur a refusé la requête relayée… » (`AgentChatClient.kt:410-422`, `447-464`). Le relay transmet séparément `remote_code` et `http_status` dans `RelayCallResult.RemoteError` (`RelayBackendTransport.kt:437-447`).

**INFERRED —** le texte d’interface cité dans V7-07 (`V7-07_D_RESUME.md:70`) est compatible avec un `HTTP_400` générique propagé par le relay, y compris un 400 initialement produit par le Core. Il est aussi compatible avec un code relay/connecteur inconnu. Il ne localise pas l’étage qui a créé le status.

**INCONCLUSIVE —** sans l’enveloppe `RemoteError` reçue par Android (`remote_code`, `http_status`, `code`) et la réponse HTTP Core stockée, il est impossible d’affirmer que le 400 observé venait du Core, du Gateway, du relay ou du connecteur. Le Gateway ne devrait pas être supposé produire ce 400 : un échec V4 outil suit le mapping du Core, tandis qu’un statut upstream 4xx générique est traité par l’adaptateur comme `UPSTREAM_UNAVAILABLE`; aucune trace de statut HTTP brut Gateway n’est fournie ici (`openclaw_adapter.py:632-643`).

### Preuves manquantes

1. **INCONCLUSIVE —** le corps brut (expurgé) de la réponse Core du 400 : `request_id`, status, `tool_call_count`, `tool_state`, `proposal_ids`. La branche observée n’inclut pas `error.code`.
2. **INCONCLUSIVE —** événement d’exception Core corrélé par `request_id` et `tool_call_id` : outil demandé, résultat du schéma d’arguments, gate `validate_tool`, code de `V4ToolError`, durée et état de persistance.
3. **INCONCLUSIVE —** traces Gateway brutes correspondantes : session/run, nombre d’appels provider, horodatages de début/fin, `stopReason`, `caller-abort`/`ClientDisconnectError`, avec l’identifiant de requête Core corrélé.
4. **INCONCLUSIVE —** métadonnées relay/connecteur reçues par le téléphone pour l’incident 400 : `remote_code`, `http_status`, code relay, request/session ID. La phrase de l’UI ne suffit pas.
5. **INCONCLUSIVE —** mesure monotone par phase pour le tour 504 et durée réelle du worker après le retour HTTP. Le total de 20 s ne sépare pas Core, Gateway et génération modèle.
6. **INCONCLUSIVE —** preuve de persistance d’une proposition pour chaque réponse déclarée 200; V7-07 rapporte zéro proposition après les essais concernés (`V7-07_D_RESUME.md:73-74`), ce qui confirme qu’ils ne satisfont pas le critère A0.

## Recommandation minimale priorisée

1. **Priorité 0 — fail closed A0 :** ne valider aucune réussite depuis le texte du modèle ou HTTP 200; exiger `tool_state=completed`, compteur cohérent, `proposal_ids` et lecture de persistance Core correspondant à `A0_USER_REVIEW` / `NONE_AT_CORE`.
2. **Priorité 1 — corriger conceptuellement le mapping chat :** ajouter `STALE_DATA: 409` et un message utilisateur explicite à `_chat_error_response`; rendre les autres codes V4 autorisés explicites ou partager une table unique avec `_error_response` pour qu’un code non listé ne devienne pas silencieusement 400. Ce patch n’est pas appliqué dans ce ticket.
3. **Priorité 1 — instrumentation expurgée :** corréler `request_id`, `tool_call_id`, `run_id`, `session_id` (hashé si nécessaire), code stable, issue et durées; ne jamais journaliser les arguments, prompts ou données personnelles.
4. **Priorité 2 — annulation et budget :** après mesure, remplacer le seul `Future.cancel()` par une annulation coopérative qui ferme l’appel provider; préserver l’idempotence et l’état incertain pour tout outil dont l’écriture a pu commencer. Ne pas relever le timeout global sans mesures.

## Commandes et contrôles read-only exécutés

**PROVEN —** inventaire initial :

```text
pwd
git status --short --branch
git rev-parse HEAD
test -d .codegraph && echo CODEGRAPH_PRESENT || echo CODEGRAPH_ABSENT
```

Résultats expurgés : worktree `v7-08/a2-diag` propre à la base demandée; HEAD = `cbdf0e2fb26190015a0d529a3ef713ed4b4379ef`; `.codegraph/` absent, donc recherches shell utilisées.

**PROVEN —** lecture de directives et documents :

```text
cat AGENTS.md
cat .agents/WORKFLOW.md
cat .agents/TASK_TEMPLATE.md
cat .agents/skills/README.md
cat .agents/skills/diagnosing-bugs/SKILL.md
cat README.md
cat backend/README.md
cat docs/operations/V7-07_D_RESUME.md
cat docs/operations/V7-07_PHYSICAL_EXECUTION.md
```

**PROVEN —** recherches ciblées et lectures numérotées :

```text
rg -n 'CHAT_V4_DEADLINE_SECONDS|_chat_v4_completion|chat_v4|BAD_REQUEST|STALE_DATA|tool_call_count|tool_state|provider_user|agent_chat' backend/atlas_backend/ docs/operations/
rg --files docs/operations/evidence | rg 'V7-07'
nl -ba backend/atlas_backend/openclaw_adapter.py | sed -n '610,705p'
nl -ba backend/atlas_backend/openclaw_adapter.py | sed -n '820,990p'
nl -ba backend/atlas_backend/v4_router.py | sed -n '260,375p'
nl -ba backend/atlas_backend/v4_router.py | sed -n '1217,1244p'
nl -ba backend/atlas_backend/v4_router.py | sed -n '1280,1385p'
nl -ba backend/atlas_backend/v4_router.py | sed -n '1424,1533p'
nl -ba backend/atlas_backend/v4_tool_service.py | sed -n '125,175p'
nl -ba backend/atlas_backend/v4_tool_service.py | sed -n '205,285p'
nl -ba backend/atlas_backend/v4_tool_service.py | sed -n '315,445p'
nl -ba backend/atlas_backend/v4_tool_service.py | sed -n '525,675p'
nl -ba backend/atlas_backend/v4_tool_service.py | sed -n '750,772p'
nl -ba backend/atlas_backend/v4_tool_service.py | sed -n '820,865p'
nl -ba backend/atlas_backend/v4_tool_service.py | sed -n '860,915p'
nl -ba backend/atlas_backend/plan_agent_v3.py | sed -n '203,300p'
nl -ba contracts/v4/chat-request.schema.json
nl -ba contracts/v4/tool-requests.schema.json | sed -n '1,240p'
nl -ba contracts/v4/tool-requests.schema.json | sed -n '230,360p'
nl -ba contracts/v4/error.schema.json | sed -n '20,65p'
nl -ba contracts/v4/error.schema.json | sed -n '120,165p'
nl -ba android/app/src/main/java/dev/atlas/coach/sync/agent/coach/AgentChatClient.kt | sed -n '400,485p'
nl -ba android/app/src/main/java/dev/atlas/coach/sync/relay/RelayBackendTransport.kt | sed -n '1,35p'
nl -ba android/app/src/main/java/dev/atlas/coach/sync/relay/RelayBackendTransport.kt | sed -n '420,452p'
```

Résultats expurgés : mappings et séquences décrits dans A–D; seul `V7-07_AVD_BACKUP_RESTORE.md` correspond au filtre de nom d’évidence V7-07, aucun journal brut de chat V7-07 trouvé sous `docs/operations/evidence/`.

**PROVEN —** vérification read-only du profil courant via Paseo `list_agents` filtré sur le worktree : `provider=codex`, `model=gpt-6-luna`, `effectiveThinkingOptionId=xhigh`.

**NOT_RUN —** tests, appels réseau, accès ADB/Pixel, inspection de bases runtime ou logs privés : hors du périmètre read-only demandé. Aucun test n’a été lancé.
