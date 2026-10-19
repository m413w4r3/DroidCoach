# ATLAS V6 — Backend OpenClaw (Lot C)

Le backend V2 reçoit des demandes de coaching, demande éventuellement une sortie
structurée à OpenClaw, applique la policy serveur et stocke uniquement des
propositions. Il n'exécute aucune action Android. Android reste responsable de
la comparaison au mandat courant et de l'exécution après consentement.

Le contrat de référence n'a pas été modifié. Le schéma Proposal v2 livré est
[proposal-v2.schema.json](../../contracts/proposal-v2.schema.json), en accord
avec [CONTRATS.md](CONTRATS.md). Les routes V1 et leur stockage existant restent
compatibles; `/v1/coach/message` reste déterministe en mode mock, y compris quand
OpenClaw est activé pour V2.

## Configuration

Le défaut est `ATLAS_AGENT_ADAPTER=mock`. Aucun serveur, compte, jeton ou appel
réseau n'est nécessaire pour la démonstration mock. Pour OpenClaw :

| Variable | Défaut | Utilité |
|---|---|---|
| `ATLAS_AGENT_ADAPTER` | `mock` | `mock` ou `openclaw` |
| `OPENCLAW_BASE_URL` | `http://127.0.0.1:18789` | URL locale de la Gateway |
| `OPENCLAW_AGENT_ID` | `atlas` | identifiant sûr transformé en `openclaw/<id>` |
| `OPENCLAW_TIMEOUT_S` | `60` | délai maximal par tentative |
| `OPENCLAW_GATEWAY_TOKEN_FILE` | — | fichier externe au dépôt, permissions exactes `0600` |
| `OPENCLAW_GATEWAY_TOKEN` | — | alternative au fichier, lue côté serveur seulement |
| `ATLAS_AGENT_RPM` | `30` | appels Gateway maximum par minute, tentatives de retry incluses |
| `ATLAS_AGENT_DAILY_TOKENS` | `100000` | budget journalier estimé; la taille UTF-8 et la sortie réservée sont imputées par tentative |
| `ATLAS_AGENT_MAX_PAYLOAD_BYTES` | `16384` | taille maximale de la requête Gateway encodée |
| `ATLAS_AGENT_MAX_OUTPUT_TOKENS` | `1200` | réserve maximale de génération |
| `ATLAS_PROPOSAL_TTL_HOURS` | `24` | durée de vie, bornée à 1–24 heures |
| `ATLAS_CONTENT_ALLOWED_DOMAINS` | hôtes du catalogue démo | allowlist de domaines HTTPS exacte, séparée par des virgules |

Exemple de configuration pour les tests réels :

```sh
export ATLAS_AGENT_ADAPTER=openclaw
export OPENCLAW_GATEWAY_TOKEN_FILE="$HOME/.config/atlas/openclaw-gateway-token"
./scripts/v6-backend-e2e.sh
```

Le jeton est chargé depuis le fichier ou l'environnement et n'est jamais mis
dans les requêtes ATLAS, les réponses, les exceptions exposées, les journaux ou
SQLite. Le chemin du fichier doit être hors du dépôt; le backend refuse tout
autre mode que `0600`. Le client appelle uniquement
`POST /v1/chat/completions`, avec `model=openclaw/atlas` par défaut, un champ
`user` dérivé par SHA-256 de l'identifiant de conversation et aucun champ
`tools`. Il n'appelle pas `/tools/invoke`.

En cas de timeout ou de réponse HTTP 5xx, l'adaptateur tente au plus deux
reprises, car le Gateway ne reçoit qu'une génération sans outil ni effet de
bord. Trois appels échoués ouvrent le circuit pendant 30 secondes. Les erreurs
401, les payloads trop grands, les quotas atteints, les sorties JSON non
conformes et les erreurs de transport renvoient explicitement
`agent_status=unavailable` avec zéro proposition et un texte de repli générique.

## API V2

Toutes les routes ci-dessous demandent le Bearer ATLAS déjà utilisé par V1. Les
requêtes mal formées utilisent `error-v1` (`contract_version: 1` et code
`contract_validation_failed`).

| Méthode | Chemin | Résultat |
|---|---|---|
| `POST` | `/v2/agent/chat` | réponse, propositions valides et `agent_status` |
| `POST` | `/v2/agent/plan` | même contrat pour la demande de directive du jour |
| `GET` | `/v2/agent/inbox?after=<proposal_id>` | propositions proposées, non acquittées et non expirées |
| `POST` | `/v2/agent/proposals/{id}/ack` | enregistre un état et un effet optionnel envoyé par Android |
| `GET` | `/v2/agent/status` | adaptateur, connexion, breaker et compteurs de quota, sans secret |
| `GET` | `/v2/agent/memory` | objectifs, projets, disponibilités et préférences locales |
| `PUT` | `/v2/agent/memory` | remplace explicitement la mémoire structurée |
| `DELETE` | `/v2/agent/memory/{category}/{id}` | supprime une entrée explicite |
| `DELETE` | `/v2/agent/memory` | supprime toute la mémoire de projet |

Les requêtes chat/plan contiennent `conversation_id`, `message` (2 000
caractères max), `shared_context` et `mandate_summary`. Chaque catégorie de
contexte doit apparaître dans `consented_categories` pour être transmise; les
autres deviennent des listes vides. Les catégories sensibles aux références
d'applications, liens ou titres sont également vidées sans leur consentement
correspondant. L'identifiant de conversation est haché avant stockage et avant
son dérivé `user` envoyé à OpenClaw. Le journal de cache ne conserve ni message
ni identifiant brut.

Les réponses OpenClaw doivent être un JSON strict de forme
`{"reply": ..., "proposals": [...]}`. Le parseur accepte soit cet objet seul,
soit un unique bloc Markdown `json`; aucun texte libre n'est interprété comme
commande. Les clés inconnues, doublons JSON, actions inconnues, types invalides
ou plus de cinq propositions invalident la réponse entière. Le prompt répète que
les messages, projets, titres, URL et autres contenus externes sont des données
non fiables. La validation réelle est la policy unique de
`atlas_backend.policy.PolicyEngine`, étendue aux actions V2.

Les propositions enregistrées reçoivent leur UUID, leurs timestamps UTC, leur
`idempotency_key`, leur `context_version`, leur niveau indicatif et leur état
`proposed` côté serveur. Une requête identique chat/plan est mise en cache pour
éviter de rappeler le modèle; une action de même contenu conserve sa clé
d'idempotence même après annulation. La boîte SQLite conserve l'état courant,
refuse les transitions interdites, marque les éléments expirés et n'expose à
l'inbox que les propositions actives et non acquittées. Le serveur ne prétend
pas que l'état d'un mandat transmis par Android est l'autorité locale.

La policy vérifie la liste fermée des dix actions du contrat, leurs capacités
indépendantes et leurs bornes typées. Les horaires focus suivent la fenêtre de
mandat lorsqu'elle est fournie, y compris durée cumulée des cycles, session
quotidienne et fuseau; `focus.start` et `focus.pause` exigent une référence de
proposition focus déjà acceptée. Les limites d'applications doivent correspondre
à une référence dans `app_policy_bounds`, refuser les packages système et
respecter durée, mode et plafond de 14 jours. Les recommandations URL exigent
HTTPS et une correspondance exacte à l'allowlist; les identifiants de contenu
doivent exister dans le catalogue local.

La mémoire `/v2/agent/memory` est une structure locale SQLite sans données
automatiquement ajoutées au prompt. Pour la partager à l'agent, Android doit la
placer dans `shared_context` et consentir explicitement à la catégorie.

## Résultats mock

Commande exécutée après l'implémentation :

```sh
./scripts/v6-backend-e2e.sh
```

Cette commande exécute les tests backend V1 et V2 sans réseau externe; le test
marqué `real_gateway` est exclu. Résultat final à reporter après la dernière
exécution : **42 passed, 1 deselected**. La suite pytest complète donne
**42 passed, 1 skipped**; les tests V1/V4 de `backend/tests/test_api.py` et
`backend/tests/test_content_and_config.py` sont inclus. `ruff check backend`
donne **All checks passed**. Mypy n'est pas installé sur cet environnement.

## Résultat OpenClaw réel

Le test `@pytest.mark.real_gateway` n'est exécuté qu'avec
`ATLAS_REAL_GATEWAY=1`. Il envoie « Que dois-je faire maintenant ? » au Gateway
sur `127.0.0.1:18789` avec un contexte vide et un mandat de test limité. La
sortie acceptée sans secret est conservée dans
[lot-c-real-gateway.txt](logs/lot-c-real-gateway.txt). Résultat de la dernière
exécution : **1 passed, 42 deselected**. Le test a confirmé au moins une
proposition v2 valide et vérifié en mémoire que le jeton ne figure pas dans la
réponse ni dans le journal produit.

Le premier essai réel a établi la connexion, mais l'agent a choisi une réponse
sans proposition. Le prompt a ensuite été précisé pour les demandes `plan` :
si le mandat autorise `home.set_directive` ou `focus.propose`, au moins une
proposition de ce type doit être formulée. L'essai suivant a produit une
proposition `focus.propose` valide. La configuration OpenClaw n'a pas été
modifiée pendant ce lot. Le jeton a été lu par un petit script Python depuis
`~/.openclaw/openclaw.json` et copié dans
`~/.config/atlas/openclaw-gateway-token` avec mode `0600`; sa valeur n'a pas été
affichée.

## Limites restantes

- Les quotas, compteurs et circuit breaker sont en mémoire du processus; ils se
  réinitialisent au redémarrage et ne coordonnent pas plusieurs workers.
- La base SQLite et la mémoire ne sont pas chiffrées par ce backend local.
- Les bornes envoyées dans `mandate_summary` et les consentements transmis sont
  des résumés clients : Android doit les comparer à son mandat et à son
  consentement réels avant toute exécution.
- Le backend stocke les propositions pour l'inbox, mais n'exécute pas d'action
  Android et ne remplace pas l'`ActionDispatcher`/validateur local.
- Les tests Android et l'AVD ne font pas partie du LOT C.
