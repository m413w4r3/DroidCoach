# ATLAS V7-09 — Revue indépendante A4

**Date :** 2026-11-05
**Rôle :** A4, relecteur indépendant n'ayant écrit aucun des fichiers examinés.
**Modèle / effort :** `codex/gpt-6-luna`, `xhigh` (statut Paseo effectif).
**Worktree :** `v7-09/a4-review`, `chmod -R a-w` (lecture seule).
**SHA examiné :** `ae17409be3aecf7309c3142c31ed10ce488f5e22`.
**Delta post-revue :** `ae17409..eb38ae7` plus corrections P2 de clôture,
revus par le parent ; aucun changement de code.

## Verdict

**GO_WITH_LIMITATIONS.**

Le rejet physique est corroboré par l'état SQLite actuel : proposition
`REJECTED`, un seul événement `USER_REJECTED`, total de deux événements. Les
deux P1 restent ouverts et correctement déclarés. A4 a demandé de corriger
quelques incohérences P2 de clarté/preuve avant publication ; elles ont été
corrigées ou documentées ci-dessous.

## Gates selon A4

| Gate | Avis A4 | Vérification |
|---|---|---|
| Proposition, rejet Android et ACK | **Accord** | Core : `REJECTED`, un événement, `applied_plan_version=NULL`, horodatages conformes. L'action Android n'a pas été rejouée par A4. |
| Idempotence | **Accord avec limites** | `replay-headers.txt` : HTTP 200, `X-Atlas-Replayed: true`, un seul événement. Le rapport précise que le worker n'a pas rejoué spontanément l'ACK livré. |
| Plan, chat distant et sécurité live | **Accord avec limites** | P1 bearer, proposition expirée non clôturée, marge de fraîcheur ~4 s et contournement de pull restent ouverts. |
| Smoke test local | **Non vérifié indépendamment** | A4 n'a effectué aucune action Pixel. |
| Backend | **Accord** | XML ciblé : 121 réussis, 0 échec ; XML complet : 408 réussis, 1 ignoré. Relance A4 de `test_v4_proposals.py` + `test_v7_08_a0_protocol.py` : **16 tests réussis**, 1 avertissement. |
| Android JVM et lint | **Non vérifié sur les totaux depuis le worktree** | Les sorties de build sont hors worktree ; les journaux privés Gradle attestent `BUILD SUCCESSFUL`. Les totaux 922/0 et 0/89 sont documentés dans `V7-09_ANDROID_TEST_EVIDENCE.md` avec les XML et le rapport lint copiés dans l'évidence privée. |
| Intégration / publication | **Accord avec limites** | Diff propre, docs-only, aucune référence distante V7-09, aucun push. |
| Pilote quotidien | **Accord : NO-GO** | P1 bearer et pull relay restent ouverts. |

## Findings

### P0

Aucun.

### P1 — préexistants, maintenus ouverts

1. **Bearer Core Android partagé**, non tourné ni révoqué. Le plan A3 décrit
   les étapes, oracles, rollback avant révocation et retour local/offline après
   révocation ; rien n'a été exécuté. Voir `V7-09_SECURITY_READINESS.md` et
   `evidence/V7-09_A3_SECURITY_READINESS.md`.
2. **Proposition `2da9c5e2…` expirée mais `PENDING_ANDROID`**, sans ACK. La
   base le confirme ; le défaut et l'absence de clôture native sont documentés,
   sans SQL manuel ni ACK fabriqué.

### P2 — clarté et preuves, et résolution

| Finding A4 | Résolution |
|---|---|
| Le rapport annonçait 1496 insertions alors que le diff réel à `ae17409` en compte 1500. | Corrigé : le rapport cite désormais **1500 insertions, 0 suppression, 8 fichiers au SHA `ae17409`**. |
| Les contrôles Git étaient présentés comme résultats puis comme « prévus ». | Corrigé : la section décrit les contrôles **exécutés** (`status`, `diff --check`, `show --check`, `log`, worktrees) et leur résultat. |
| A2 restait un document `NOT_RUN` avec le scénario v16/1375, sans marqueur de dépassement. | Un **addendum** en tête de `evidence/V7-09_A2_QA_ASSERTIONS.md` indique que l'exécution réelle est v2/1380 et renvoie à `V7-09_REJECT_QA.md`. |
| Le résumé « prouvé de bout en bout » pouvait laisser croire que le worker Android avait émis le rejeu idempotent. | Corrigé : le rapport précise qu'il s'agit d'un **rejeu contrôlé du corps ACK exact côté Core**, pas d'un renvoi spontané du worker. |
| Les totaux Android 922/0 et lint 0/89 n'étaient pas vérifiables depuis le worktree. | Ajout de `V7-09_ANDROID_TEST_EVIDENCE.md` avec commandes, décompte XML, rapport lint et note sur les artefacts privés hors dépôt. |

## Vérifications indépendantes effectuées par A4

- Lecture de tous les documents V7-09 et des trois rapports A1/A2/A3.
- Requêtes SQLite en `mode=ro` + `PRAGMA query_only=ON` sur
  `agent_proposals_v4`, `agent_proposal_events_v4`,
  `day_plan_snapshots_v4`, `consent_states_v4`.
- Vérification du diff docs-only, de `git diff --check` et de `git show
  --check` ; absence de média et de référence distante V7-09.
- Lecture des artefacts privés `replay-headers.txt` et
  `core-final-state.json`.
- Lecture des XML pytest ciblé/complet et des journaux Gradle.
- Relance ciblée de deux fichiers backend sous `ATLAS_DB_PATH=/tmp/...` :
  16 tests réussis.

## Limites de la revue

- Aucune commande ADB ni action Pixel : le smoke test local et le geste REJECT
  restent des preuves du parent, non rejouées par A4.
- Aucun build Android relancé ; les totaux JVM/lint sont vérifiés par les
  artefacts copiés, pas par une nouvelle exécution dans le worktree.
- Aucun journal Relay/Gateway complet n'a été fourni à A4 ; l'état Relay est
  celui documenté par le parent.
- A4 n'a modifié aucun fichier et n'a effectué aucun push.

## Avis de publication

A4 recommandait de **différer la publication** du SHA `ae17409` jusqu'à
correction des P2 ci-dessus. Ces corrections sont intégrées au commit de
clôture. Le parent propose donc la branche à l'examen du propriétaire, sans
push, PR ni merge. Le pilote quotidien reste **NO-GO**.
