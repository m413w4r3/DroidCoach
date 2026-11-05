# ATLAS V7-08 — Addendum V7-09

**Date :** 2026-11-05
**Objet :** corriger les affirmations devenues périmées du rapport et du
handoff V7-08, sans réécrire les preuves historiques.
**Références historiques :** `V7-08_FINAL_REPORT.md`,
`V7-08_HANDOFF.md`, `REGISTRY_V7_08.md`.

Cet addendum ne remplace pas les documents V7-08. Il précise ce qui a changé
après la clôture V7-08 et ce qui reste valide.

## 1. REJECT V7-08 : `NOT_RUN` confirmé, puis complété en V7-09

Le rapport V7-08 §9 indiquait **« REJECT : NON TERMINÉ »** et le handoff V7-08
demandait de tirer puis refuser la proposition
`2da9c5e2-57ed-4bac-8f5f-01eb0e4b780e`. Cette affirmation était exacte au
2026-11-04.

En V7-09 :

- `2da9c5e2…` a expiré le **2026-11-05T19:28:58Z** (20:28:58 à Paris) sans
  avoir jamais été livrée à l'inbox Android ;
- le pull V4 l'omet ; elle reste `PENDING_ANDROID` en Core, sans ACK ni
  événement ;
- aucun refus tardif, aucun ACK `EXPIRED` fabriqué, aucun SQL manuel n'a été
  exécuté ;
- la procédure de reprise V7-08 (handoff §4, étapes 4 à 7) est **périmée pour
  cette proposition**.

Le parcours REJECT a été complété avec une **nouvelle proposition valide** :

- `proposal_id = 2a29a35f-23b4-4aae-9d28-2904320d6029` ;
- `base_version = 2`, plan `3d2dba67…` du 2026-11-05 ;
- `A0_USER_REVIEW`, `NONE_AT_CORE`, 23:00 → 23:10, 45 min ;
- refus explicite Android le `2026-11-05T21:02:53Z` ;
- ACK `9114cf22-b010-4ecb-93a3-1ad198f69ca7`, `REJECTED`,
  `USER_REJECTED`, sans `applied_plan_version` ;
- plan local et projection Core inchangés (v2 / 1380) ;
- rejeu contrôlé du corps exact → HTTP 200, `X-Atlas-Replayed: true`, un seul
  événement.

Les preuves détaillées sont dans `V7-09_REJECT_QA.md`.

## 2. Plan synthétique : deux dates différentes

Le rapport V7-08 §14 décrit un plan local **v16 du 2026-11-04** avec une
activité `PLANNED` à 22:55 (1375) et cinq entrées `POSTPONED`. Cette description
reste exacte pour l'historique du 2026-11-04.

En V7-09, le worker V4 ne synchronise que le plan de la date système
(2026-11-05). Un nouveau plan du jour a donc été créé :

- plan `3d2dba67-7f9e-4600-99fd-9afae9f6d4e3` ;
- activité synthétique `9245884e-50df-49b5-b6bf-91c9660a04fa` à 23:00–23:45
  (1380), 45 min ;
- version v2 pour la recette, puis **v3 vide** après nettoyage ;
- projection Core finale : `plan_version=3`, 0 activité,
  `observed_at=2026-11-05T21:07:04.186653Z`.

Le plan du 2026-11-04 (6 activités synthétiques) reste présent dans les
fichiers locaux et l'historique Core. Il n'était plus accessible par l'écran
Plan du jour et n'a pas été manipulé par fichier ni restauré par archive. Ce
résidu est signalé, pas dissimulé.

## 3. Ce qui reste valide de V7-08

- Transport public LTE → Funnel → proxy → Relay/Noise → connecteur → Core →
  Gateway → `openai/gpt-6-luna` : valide.
- Proposition APPLIED `e867c13c-2327-4f04-905e-51de965e2876` :
  `base_version=15`, 22:45 → 22:55, ACK `APPLIED`
  (`ack_id=49f0f982…`, `applied_plan_version=16`, séquence 1) : **valide et
  inchangée**.
- Rejeu APPLIED idempotent : un seul événement : valide.
- Quota local non bloquant (`ATLAS_AGENT_DAILY_TOKENS_ENFORCED=false`) et
  deadline V4 `40 s` : valides.
- Suite backend **408 passed, 1 skipped** sur le code qualifié : toujours
  observée sur la branche V7-09.
- Aucun P0/P1 nouveau introduit par le code V7-08.

## 4. P2 V7-08 toujours ouverts

| P2 V7-08 | État V7-09 |
|---|---|
| Toggle « Conversation V4 » non persistant au cold start | **toujours ouvert** ; réactivé manuellement avant le tour |
| Bouton « Pousser les projections V4 » grisé en mode relais | **toujours ouvert** ; workaround « Enregistrer la connexion » utilisé |
| Pull des propositions sans bouton explicite en relais | **toujours ouvert** ; worker 15 min ou workaround |
| `STALE_DATA` → HTTP 400 dans le chemin chat | **toujours ouvert** ; non corrigé |
| `Future.cancel()` n'interrompt pas un outil synchrone déjà lancé | **toujours ouvert** ; dette P2 |
| Enveloppe de pull non publiée dans le contrat | **toujours ouvert** |
| Compteur de quota en mémoire | **toujours ouvert** |

## 5. P1 V7-08 toujours ouvert

Le **bearer Android partagé** reste ouvert. V7-09 ne l'a ni tourné, ni révoqué,
ni migré. Le REJECT fonctionnel ne réduit pas ce risque. Voir
`V7-09_SECURITY_READINESS.md` pour le plan de migration et les critères
GO/NO-GO.

Le pilote quotidien de sept jours reste **NO-GO et non autorisé**.
