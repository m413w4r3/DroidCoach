# V7-08 — Budget total du tour chat V4 et mesure du 504

## Constat mesuré (2026-11-04)

Le tour V4 réel du 2026-11-04 a été exécuté depuis le Pixel en LTE via le
Funnel public, le Relay/Noise et le Gateway OpenClaw réel (`openai/gpt-6-luna`,
six outils client). Le quota quotidien local était désactivé
(`daily_token_limit_enforced=false`) et la consommation était mesurée.

Séquence observée dans les journaux Gateway (heures locales Europe/Paris) :

| Phase | Horodatage | Durée |
|---|---|---|
| Requête V4 reçue, catalogue des 6 outils | 19:16:41.149 | — |
| Première complétion provider terminée sur `tool_calls` | 19:16:57.507 | **16,36 s** |
| Deuxième complétion provider démarrée | 19:16:57.720 | — |
| Deuxième complétion interrompue (`caller-abort`) | 19:17:00.966 | **3,25 s** |

La route Core a renvoyé **HTTP 504** à l'échéance totale de 20 s. Le premier
outil (`atlas_get_day_plan`) a été annoncé par le modèle ; la seconde
complétion, qui devait appeler `atlas_propose_plan_revision`, a été coupée
avant de produire sa réponse. Aucune proposition n'a été créée
(`agent_proposals_v4=0`), aucun ACK.

Le besoin mesuré est donc supérieur à 20 s : environ 16,4 s pour la première
complétion, puis environ 6 à 11 s pour la seconde selon les tours précédents,
soit un total de l'ordre de 22 à 27 s. Le plafond de 20 s est la contrainte
limitante, pas le modèle ni le transport.

## Correctif minimal

`ATLAS_CHAT_V4_DEADLINE_S` borne le budget total du tour V4 (toutes les
complétions provider, validations et exécutions d'outils). Il est lu au
démarrage du Core :

- absent ou vide : **20,0 s**, comportement historique conservé ;
- valeur numérique finie entre **1,0 et 50,0 s** ;
- valeur invalide, non finie ou hors bornes : erreur explicite au démarrage
  (fail closed), jamais un repli silencieux.

Le timeout par requête provider (`OPENCLAW_TIMEOUT_S`, 30 s), la limite RPM, le
circuit breaker, les retries bornés, les limites d'outils/propositions, le
cache idempotent et les consentements restent inchangés. Le budget total doit
rester inférieur au timeout Android du chat relayé (55 s) et au TTL du relais
(60 s) ; la borne de 50 s garantit cette marge.

Dans ce déploiement personnel, `compose.openclaw.yaml` fixe
`ATLAS_CHAT_V4_DEADLINE_S: "40"`. Le défaut du code reste 20 s pour tout autre
déploiement.

## Rollback

Remettre la variable à `20`, ou la supprimer, puis redémarrer le Core. Le
comportement historique de 20 s revient. Aucune migration de données n'est
nécessaire. Le rollback du correctif quota reste indépendant
(`ATLAS_AGENT_DAILY_TOKENS_ENFORCED`).

## Tests

`backend/tests/test_v7_08_chat_deadline.py` couvre : défaut absent/vide,
override numérique et espaces, valeurs invalides (`not-a-number`, `0`, `-1`,
`51`, `nan`, `inf`), lecture du module dans un processus neuf (défaut 20,
override 40, échec explicite sur valeur invalide), et propagation de la
constante jusqu'à `v4_router`. Les tests de chat V4 existants continuent de
couvrir le timeout et les limites d'outils.

Résultat sur le SHA de développement : `407 passed, 1 skipped`.

## Limites

Le correctif ne prouve pas à lui seul le parcours A0 : il faut un snapshot de
plan frais (fenêtre de 5 minutes), un consentement `DAY_PLAN` courant, une
conversation non contaminée et une action APPLY/REJECT explicite sur le Pixel.
Ces preuves relèvent de la recette physique V7-08.
