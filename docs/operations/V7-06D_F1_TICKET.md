# ATLAS — Ticket F1 « zéro proposition » (V7-06D-PHYS-F1)

| Clé | Valeur |
|---|---|
| TASK_ID / statut | `V7-06D-PHYS-F1 / F1-FIX IMPLEMENTED — REVIEW GO_WITH_LIMITATIONS — PHYS PASS 5/5 + A4-4 PASS — EVIDENCE REVIEW PENDING` |
| Objectif | Supprimer l'échec F1 observé sur la série physique, rendre l'oracle de test exact, puis prouver la correction par tests déterministes et une nouvelle série de 5+ démarrages froids. |
| Dépendances | V7-06D intégré localement sur `main` ; A4-4 **PASS** ; provenance QA **GO** ; preuve `evidence/V7-06D_PHYSICAL_QA.md`. |
| BASE_SHA | `06424fdff48fb63eebe22ec7a7dd43282dbf428b` (main locale après merge fast-forward ; `origin/main` reste `9ad90c7…`). |
| Branche / worktree | `fix/v7-06d-f1-proposal-missing`, worktree isolé obligatoire ; aucune modification directe de `main`. |
| Profil | Tous les enfants : `codex:gpt-6-luna`, `effort=xhigh`, vérifié dans les métadonnées Paseo ; aucune sous-délégation ; 6 enfants maximum ; un seul opérateur ADB. |
| Lire | `V7-06D_PHYSICAL_QA.md`, `V7-06D_FINAL_REPORT.md` §13, `V7-06D_DIAGNOSTIC.md` §8, `V7_06B_CoachLoopInstrumentedTest.kt`, `run_qa.py`, `run_scenario.py`, `test_harness.py`, schéma/prompt de l'outil `atlas_propose_plan_revision`. |
| Écrire | Fichiers attribués exclusivement à l'enfant : test instrumenté, harnais/tests, code du contrat d'outil identifié par le diagnostic. Aucun fichier partagé entre deux enfants. |
| Oracle | Voir §3. Aucun PASS n'est accordé sur une simple relance. |
| Stop conditions | Autorisation physique absente, secret/donnée personnelle, modèle/effort non conforme, conflit de fichiers, besoin d'une action live non autorisée. |
| Livrables | Output SHA, diff borné, commandes, logs expurgés, statuts `PASS/FAIL/BLOCKED/NOT_RUN`, proof physique, revue indépendante. |
| Revue parent | `ACCEPTED / CORRECTION_REQUIRED / BLOCKED`, puis intégration locale uniquement sur autorisation. |

## 1. Constat reproductible

La phase physique `V7-06D-PHYS` a exécuté 5 démarrages froids : **3 PASS / 2 FAIL**.
Les tentatives 2 et 3 échouent avec :

```text
java.lang.IllegalStateException: V4 tool metadata is not the generated suffix of the assistant response
  at V7_06B_CoachLoopInstrumentedTest.parseToolMetadata(V7_06B_CoachLoopInstrumentedTest.kt:641)
```

L'app-state de la tentative 3 montre une réponse assistant valide avec
`tool_state=completed`, `tool_call_count=2` et **zéro proposition**. Le modèle
indique que l'appel `atlas_propose_plan_revision` a été refusé localement puis
rejeté par le service. Le test ne sait pas lire la forme légitime
« Aucune proposition (0). » et masque le vrai échec.

Les stages `BEFORE_DISPATCH`, `SOCKET_SEND_ACCEPTED`, `RESPONSE_RECEIVED`,
`CORRELATED` et `UI_RESULT=SUCCESS` sont présents : **F1 n'est pas la deadlock
G↔C**. La cause produit exacte du rejet d'outil reste **INCONCLUSIVE** ; F1 est
un blocage de fiabilité du parcours coach.

## 2. Sous-problèmes

### F1a — oracle de test ambigu (P2)

`parseToolMetadata` exige la forme avec identifiants de proposition et échoue
avec `IllegalStateException` lorsqu'une réponse valide n'a aucune proposition.
Il doit produire un échec explicite `PROPOSAL_MISSING` et ne jamais accepter une
réponse sans proposition comme un PASS.

### F1b — rejet de `atlas_propose_plan_revision` (nouveau P1 fiabilité)

Le modèle a émis l'outil, mais le service l'a rejeté ; aucune proposition n'a été
créée. Le ticket doit établir, avec des preuves expurgées, si le rejet vient :

- du schéma d'outil ou du prompt synthétique (arguments `changes`/`justification`,
  `idempotency_key`, `op`/`activity_id`) ;
- de la validation Core/OpenClaw ou de l'idempotence ;
- d'une variabilité du modèle.

Aucune correction ne doit inventer une proposition, rejouer un outil de manière
à créer un doublon, ni transformer un échec en PASS.

## 3. Oracle et critères d'acceptation

1. **F1a rouge→vert** : un test ciblé appelle `parseToolMetadata` sur une
   réponse valide `Aucune proposition (0).` et attend `PROPOSAL_MISSING` ; il
   échoue sur BASE et passe après correction.
2. **F1b reproductible** : un test déterministe au niveau du contrat d'outil /
   de la validation reproduit le rejet observé, échoue sur BASE, puis passe après
   correction. Le test ne dépend pas d'un appel modèle non déterministe pour son
   oracle ; il peut utiliser un enregistrement synthétique expurgé.
3. **Aucune régression** : Android JVM, lint/build, Node, proxy, harnais,
   backend, `py_compile` et `git diff --check` PASS sur le SHA intégré.
4. **Preuve physique** : après correction, nouvelle série d'au moins 5 démarrages
   froids sur Pixel 9 Pro XL API 37.1, **0 échec F1**, gate APK installé vérifié
   (`installed_apk_verified=true`, hashes installés = construits). Autorisation
   physique ciblée requise avant ADB ; aucun test produit, HOME, Keystore,
   donnée personnelle ou service live.
5. **Revue indépendante** en lecture seule sur le SHA final ; aucun P0/P1 ouvert.
6. **Verdicts** : `GO_CHAT_RELIABILITY_QA` ne peut passer à GO qu'après la série
   physique et la revue ; `GO_CHAT_CONCURRENCY` et `GO_A4_4_CLIENT` ne sont pas
   remis en cause par F1. `GO_PIXEL_PRODUCT`/`GO_PRODUCT_REMOTE` restent NO GO.

## 4. Preuves de départ

- `docs/operations/evidence/V7-06D_PHYSICAL_QA.md` (§3, F1).
- `docs/operations/V7-06D_DIAGNOSTIC.md` §8.
- Preuves privées de la tentative 3 : app-state expurgé, runner log, logcat,
  `openclaw-proxy.jsonl` (hors dépôt, `0600`).
- Aucun token, bearer, UUID brut, contenu utilisateur ou donnée personnelle dans
  les preuves publiques.

## 5. Interdictions

- Aucun push, PR, merge distant ou clôture d'issue sans autorisation spécifique.
- Aucune modification live, Funnel, Core/Gateway, identités ou secrets.
- Aucun ADB/Pixel sans nouvelle autorisation ciblée et pré-vol `android17-device-qa`.
- Aucun `pm clear`, `uninstall` produit, root, Device Owner, kiosque ou
  AccessibilityService.
- Aucune réécriture des verdicts historiques V7-06D.

## 6. Sous-sessions exécutées et suite

1. `F1-DIAG` (lecture seule) : **ACCEPTED (PARTIAL)** — session
   `cbbda8c4-f724-4c43-b234-3daad189f0d1`, diagnostic
   `V7-06D_F1_DIAGNOSTIC.md`.
2. `F1-FIX` (écriture bornée) : **IMPLEMENTED** — session
   `6302771e-82b7-45e2-acbe-4c2ed55b585f` ; rouge
   `725f9e3089f85a609427e7e256158e0b46ff7e73`, vert
   `07490370e7afa964c1eb6223945544af7b21b887` ; rapport
   `V7-06D_F1_FIX_REPORT.md`.
3. `F1-REVIEW` (lecture seule) : **GO_WITH_LIMITATIONS** — session
   `b143ca97-7418-4e21-b83f-3271c3d63304`, aucun P0/P1 code ; P1
   `EVIDENCE_GAP` physique, P2 de couverture du parseur.
4. `F1-PHYS` : **PASS** — autorisation ciblée « F1 + A4-4 » ; 5/5 démarrages
   froids PASS, zéro échec F1, sonde A4-4 PASS, gate de provenance installé
   vérifié ; preuve `evidence/V7-06D_F1_PHYSICAL_QA.md`.
5. Revue indépendante de la preuve physique : **PENDING** ; elle seule peut
   confirmer `GO_CHAT_RELIABILITY_QA`.
