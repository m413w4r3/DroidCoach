# R3B-CHAT-01 — Contrat et raccordement backend du chat relayé

| Clé | Valeur |
|---|---|
| TASK_ID / statut | `R3B-CHAT-01 / READY` |
| Vague | A |
| BASE_SHA | `5795d378d38b3e86ce99f1551c41d5044cd4a81c` |
| Profil obligatoire | `codex:gpt-6-luna`, effort `xhigh`, session vérifiée par le parent |
| Worktree | worktree Paseo dédié |
| Dépendances | aucune |

## Objectif observable

Étendre `atlas.remote.v1` de façon additive avec la capacité `coach.chat` et
l'opération `coach.chat.request`, router cette opération uniquement vers
`POST /v2/agent/chat` de FastAPI, avec validation stricte, limites, mandats,
consentements, erreurs explicites et traitement non automatique des résultats
incertains.

## Scope d'écriture exclusif

- `backend/**`
- `contracts/**`
- `integrations/relay/pc/src/protocol.ts`
- `integrations/relay/pc/src/operations.ts`
- `integrations/relay/pc/src/backend.ts`
- `integrations/relay/pc/src/session.ts`
- `integrations/relay/pc/test/chat-r3b*.test.ts`
- `docs/v6-3/r3b/preuves/chat-01*.txt`

Interdit : modifier `integrations/relay/pc/scripts/**`, `systemd/**`,
`integrations/relay/proxy/**`, `android/**` ; exposer un proxy HTTP générique ;
changer le protocole Noise ou le transport ; pousser sur GitHub.

## Contrat technique

### Capacité et opération

- Ajouter la capacité `coach.chat` à la liste fermée des capacités.
- Ajouter l'opération `coach.chat.request` à la liste fermée des opérations.
- Mapper `coach.chat.request` → capacité `coach.chat` dans la session.
- Ne pas modifier les noms, tailles ou enveloppes existantes ; les anciens
  clients restent valides.

### Payload entrant (validation stricte, clés exactes)

```json
{
  "conversation_id": "<1..120 chars>",
  "message": "<1..2000 chars>",
  "shared_context": {
    "consented_categories": ["..."],
    "categories": {"...": ["..."]}
  },
  "mandate_summary": {
    "id": "<1..80>",
    "version": 1,
    "allowed_actions": ["..."],
    "mode": "proposals_only|preauthorized",
    "focus_window": null | {...},
    "app_policy_bounds": {...}
  }
}
```

- Refuser `null` pour `conversation_id` (le backend l'exige non nul).
- `shared_context` : catégories et valeurs bornées ; enveloppe minimisée
  ≤ 8 192 octets, conformément au backend ; refuser les clés inconnues.
- `mandate_summary` : mêmes contraintes que `backend/atlas_backend/models_v2.py`
  (`id`, `version`, `allowed_actions` ≤ 10 et uniques, `mode`, `focus_window`
  avec jours/heures/timezone/minute/sessions, `app_policy_bounds` ≤ 50).
- La taille totale de la requête reste ≤ 32 768 octets ; une requête trop
  grande reçoit `PAYLOAD_TOO_LARGE`.
- L'identité d'appareil est celle de la session authentifiée ; elle n'est pas
  un champ libre du payload et n'est jamais transmise à FastAPI.

### Routage

- `coach.chat.request` → `POST /v2/agent/chat`, corps exactement le payload,
  `Authorization: Bearer <ATLAS_SERVICE_TOKEN>`, `Content-Type: application/json`.
- Aucune autre route, aucun chemin choisi par le client, aucun proxy générique.
- La réponse FastAPI `{reply, proposals, agent_status}` est renvoyée dans
  l'enveloppe `response.result`, bornée à 262 144 octets.
- Valider la forme de la réponse : `reply` chaîne ≤ 4 000, `proposals` tableau
  ≤ 5 d'objets, `agent_status` ∈ `mock|connected|unavailable` ; toute réponse
  malformée devient `BACKEND_REJECTED` (sans exposer le corps brut).

### Idempotence et incertitude

- Ajouter `coach.chat.request` aux opérations à effet (journal persistant).
- Rejeu strictement identique (même `request_id`/`idempotency_key`, même
  payload) : réponse mémorisée avec `replayed: true`, aucun second POST.
- Même clé avec un payload différent : `IDEMPOTENCY_CONFLICT`.
- POST dont le résultat est incertain (timeout, coupure après envoi, 5xx) :
  marquer `uncertain`, renvoyer `BACKEND_UNAVAILABLE` non retentable avec un
  message explicite, **ne jamais rejouer automatiquement**.
- Le journal doit accepter les anciennes entrées `plan.revision.request` et les
  nouvelles entrées `coach.chat.request`, et rester fail-closed si corrompu.
- `cancel` interrompt une requête en vol au mieux ; une requête déjà acceptée
  par FastAPI peut aboutir. La réponse d'annulation doit être `CANCELLED`.

### Backend

- Réutiliser `AgentChatRequest`, `AgentOutputV2`, les mandats, consentements,
  la politique de capacité et la déduplication existants.
- Ne pas exécuter d'action depuis une réponse : les propositions restent des
  propositions ; aucune écriture calendrier, wger, habitude, fichier, commande
  PC ou paramètre Android.
- Ajouter les tests backend nécessaires pour prouver le contrat, les limites,
  la validation du contexte, les mandats et les propositions.

### Contrats

- Mettre à jour `contracts/atlas-remote-v1.schema.json` avec la capacité,
  l'opération et le payload/response.
- Ne pas casser les schémas existants.

## Oracle

```sh
cd backend && .venv/bin/python -m pytest -q
cd integrations/relay/pc && npm run build && npm test
```

Tests supplémentaires obligatoires (Node) :

1. capacité absente → `OPERATION_UNAVAILABLE` ;
2. payload valide → appel backend exact `POST /v2/agent/chat` et réponse
   transmise ;
3. message > 2 000, contexte > 8 KiB, clé inconnue, mandat invalide,
   `conversation_id` null → `INVALID_REQUEST`/`PAYLOAD_TOO_LARGE` ;
4. appareil révoqué → `DEVICE_REVOKED`, aucun appel backend ;
5. POST incertain → un seul appel observé, `BACKEND_UNAVAILABLE`, rejeu
   bloqué ;
6. rejeu identique terminé → zéro second appel, `replayed: true` ;
7. conflit d'idempotence → `IDEMPOTENCY_CONFLICT` ;
8. annulation → `CANCELLED` ;
9. réponse backend malformée/trop grande → erreur bornée ;
10. aucun message utilisateur, token ou corps de réponse dans les logs.

## Livrables

- Code, contrats, tests et preuves `chat-01*.txt`.
- Commit local avec SHA ; aucun push.
- Inventaire des fichiers modifiés et des tests exécutés.

## Stop conditions

- Modèle/effort incorrect : `BLOCKED`.
- Besoin de modifier Android ou le proxy : arrêter et rendre la main.
- Toute route générique, tout chemin choisi par le client, toute fuite de
  secret ou de message : `STOP`.
- Rejeu automatique d'un POST incertain ou second effet : `STOP`.
- Toute proposition exécutée sans confirmation : `STOP`.
