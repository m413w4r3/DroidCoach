# V6 — Contrats d'intégration (source de vérité)

Version de schéma : `schema_version: 2` (le contrat V1 `directive.schema.json` / `sync-v1` reste valide et inchangé).
Fichier machine-lisible de référence : `contracts/proposal-v2.schema.json` (à créer par le lot C, identique à ce document).

## Architecture

```
Android (autorité finale, mandat local) ── HTTPS Bearer ATLAS ──▶ ATLAS Core (FastAPI : validation, quotas, journal)
                                                                   │  Bearer Gateway (secret serveur seulement)
                                                                   ▼
                                                  OpenClaw Gateway loopback  POST /v1/chat/completions
                                                  model=openclaw/atlas, tools deny *
```

- OpenClaw ne produit que du **JSON de proposition** (jamais de commande). Le backend parse strictement ; échec = aucune action.
- Le backend valide (Pydantic + `PolicyEngine`) et met la proposition dans une **boîte de réception** ; Android la **récupère** (pull), la **revalide localement** (mandat, état, horloge) puis exécute via `ActionDispatcher`.
- Le champ `state` n'est jamais pris du modèle : le backend fixe `proposed`; Android fixe le reste.
- Trois niveaux distincts : (a) permission **OS** (écran système), (b) consentement **ATLAS** d'observer un package, (c) **mandat** donné à l'agent. Jamais confondus.

## Objet Proposal v2

| Champ | Type | Règles |
|---|---|---|
| `schema_version` | int | `2` |
| `proposal_id` | str | UUID généré par le **backend** |
| `idempotency_key` | str | ≤ 80 car., stable pour un même contenu ; rejouer = no-op |
| `conversation_id` | str\|null | id stable non sensible |
| `mandate_id`, `mandate_version` | str, int | mandat visé lors de la création ; Android compare à son mandat courant |
| `context_version` | str | hash/version du contexte partagé utilisé |
| `created_at`, `expires_at` | RFC3339 UTC | TTL max 24 h (config), refus si expiré à la consommation |
| `action_type` | enum | liste fermée ci-dessous |
| `parameters` | objet typé | schéma strict par action, `additionalProperties:false` |
| `reason` | str ≤ 240 | motif affiché à l'utilisateur |
| `required_level` | enum | `proposal_only` \| `confirm` \| `preauthorized_ok` (calcul **indicatif** backend ; Android recalcule) |
| `state` | enum | `proposed`, `pending_confirmation`, `accepted`, `executed`, `rejected`, `expired`, `cancelled` |

Transitions autorisées côté Android : `proposed→pending_confirmation→accepted→executed`, `proposed→accepted→executed` (si pré-autorisé), toute→`rejected|expired|cancelled`. `executed`, `rejected`, `expired`, `cancelled` sont terminaux. Une session annulée ne peut pas être ressuscitée par une nouvelle proposition ayant la même `idempotency_key`.

## Actions V6 (liste fermée) et paramètres

| `action_type` | Paramètres | Niveau par défaut |
|---|---|---|
| `home.set_directive` | `title` ≤ 60, `body` ≤ 200, `primary_action` ∈ {`focus.start`,`open_coach`,`none`}, `focus_proposal_id`? | automatique si coaching perso activé + mandat |
| `home.reorder_cards` | `order`: sous-ensemble ordonné de {`directive`,`focus`,`content`} | automatique ; ne masque jamais favoris/secours |
| `focus.propose` | `focus_minutes` 1-180, `break_minutes` 0-60, `cycles` 1-8, `label`? ≤ 60 | proposition ; carte Session proposée |
| `focus.plan` | idem + `start_at` (UTC), `timezone` IANA | confirm ; auto seulement dans fenêtre/quotas du mandat |
| `focus.start` / `focus.pause` | `session_ref` (proposal_id d'un focus accepté) | confirm ; auto si mandat |
| `habit.suggest` | `name` ≤ 60, `schedule`∈{daily,weekdays} | proposition seulement |
| `app_policy.propose` / `app_policy.adjust` | `package_ref` (identifiant logique d'une app déjà consentie), `daily_limit_minutes` 5-480, `window`?, `mode` ∈ {`soft_reminder`,`friction`}, `valid_until` (UTC ≤ 14 j) | adjust auto seulement dans les bornes du mandat par package ; sinon confirm |
| `content.recommend` | `content_id` (bibliothèque locale) ou `url` https d'un domaine autorisé, `title` ≤ 120 | auto-affichage, ouverture par tap |

Interdits (rejet automatique) : tout autre `action_type`, package système/vital, intent arbitraire, URL hors allowlist, permission OS, retrait denylist, désactivation sortie d'urgence, durcissement hors bornes sans confirmation.

## Mandat local (Android, source de vérité)

`AgentMandate v2` : `id`, `version`, `revoked`, `suspended`, `mode` ∈ {`proposals_only`,`preauthorized`}, `allowed_actions: Set`, `focus_window` (jours, heures, fuseau, `max_minutes_per_session`, `max_sessions_per_day`), `app_policy_bounds: Map<package_ref, {min_limit_minutes, max_mode, max_valid_days}>`, `max_changes_per_day`, `shared_data_categories: Set` (vide par défaut), `expires_at`. Toute révocation/suspension annule les planifications en attente. `MandateVerifier` existant évolue (compatibilité : mandat V1 chargé comme `proposals_only`).

## Endpoints backend (auth Bearer ATLAS existante)

| Méthode | Chemin | Rôle |
|---|---|---|
| POST | `/v2/agent/chat` | `{conversation_id, message ≤ 2000, shared_context{categories consenties}, mandate_summary}` → `{reply, proposals[], agent_status}` |
| POST | `/v2/agent/plan` | idem mais demande explicite de directive du jour (« Que dois-je faire maintenant ? ») |
| GET | `/v2/agent/inbox?after=<cursor>` | propositions non expirées, non acquittées |
| POST | `/v2/agent/proposals/{id}/ack` | `{state, effect?, at}` journalisation du résultat côté Android |
| GET | `/v2/agent/status` | `{adapter: mock|openclaw, connected, breaker, quota}` ; jamais de secret |

`agent_status`: `connected` | `unavailable` | `mock`. Les erreurs gardent le format `error-v1`.

## Configuration backend (variables d'environnement, aucun secret dans le dépôt)

`ATLAS_AGENT_ADAPTER=mock|openclaw` (défaut `mock`), `OPENCLAW_BASE_URL=http://127.0.0.1:18789`, `OPENCLAW_GATEWAY_TOKEN_FILE=<fichier mode 600>` (ou `OPENCLAW_GATEWAY_TOKEN`), `OPENCLAW_AGENT_ID=atlas`, `OPENCLAW_TIMEOUT_S=60`, `ATLAS_AGENT_RPM`, `ATLAS_AGENT_DAILY_TOKENS`.

## Invariants de sécurité

- Pas de `/tools/invoke`, pas d'outil fourni au modèle ; contenu externe = donnée non fiable, jamais instruction.
- Entrée utilisateur/contexte transmis après minimisation : catégories non consenties = vides ; pas de noms de package, URLs, titres d'habitudes sans consentement.
- Sortie modèle : JSON strict `{"reply": str, "proposals":[{action_type, parameters, reason}]}` (≤ 5 propositions) ; tout champ inconnu ⇒ rejet.
- Journal sans secret ni message complet (hash/longueur) côté serveur ; journal des décisions complet et consultable côté Android.
