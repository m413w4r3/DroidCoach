# V7-06D — Correctif F1 « zéro proposition »

**Date :** 2026-11-01
**Ticket :** `V7-06D-PHYS-F1` (`docs/operations/V7-06D_F1_TICKET.md`)
**Statut :** **F1-FIX IMPLEMENTED (code) — revue code `GO_WITH_LIMITATIONS` — physique F1 `PASS` (5/5) + A4-4 `PASS` — revue de preuve `GO_WITH_LIMITATIONS` — `GO_CHAT_RELIABILITY_QA` GO (limites P2)**
**Branche :** `fix/v7-06d-f1-fix`, aucun push/PR/merge distant.
**BASE :** `25fbab5` (docs) / code `06424fd` (`main` locale).
**Rouge :** `725f9e3089f85a609427e7e256158e0b46ff7e73`.
**Vert / SHA final :** `07490370e7afa964c1eb6223945544af7b21b887`.
**Session F1-FIX :** `6302771e-82b7-45e2-acbe-4c2ed55b585f`, provider `codex`,
modèle `gpt-6-luna`, effort effectif `xhigh`, worktree isolé
`/tmp/atlas-v7-06d-f1-fix`.
**Revue indépendante :** `b143ca97-7418-4e21-b83f-3271c3d63304`, provider
`codex`, modèle `gpt-6-luna`, effort effectif `xhigh`, worktree
`/tmp/atlas-v7-06d-f1-review-ro` détaché à `0749037` et `chmod -R a-w`.

## 1. Cause établie

F1 n'est pas la deadlock G↔C de V7-06C : les échecs physiques #2/#3
surviennent après `RESPONSE_RECEIVED` et `CORRELATED`, avec
`UI_RESULT=SUCCESS`, sur une réponse assistant valide à zéro proposition.

- **F1a — oracle de test ambigu.** Le formateur produit légitimement
  `Aucune proposition (0).` lorsque la liste d'identifiants est vide
  (`android/app/src/main/java/dev/atlas/coach/sync/agent/coach/AgentChatClient.kt:134`).
  L'ancien parseur instrumenté n'acceptait que la forme avec identifiants et
  levait une exception générique, masquant le vrai échec.
- **F1b — prompt synthétique hors contrat.** Le prompt demandait les arguments
  à plat (`activity_id`, `op`, horaires) et une `idempotency_key`. Le schéma
  `contracts/v4/tool-requests.schema.json` exige `plan_id`, `base_version`,
  un tableau `changes` et une `justification` ; la clé d'idempotence est
  backend-owned, retirée du schéma présenté au modèle par
  `backend/atlas_backend/openclaw_adapter.py:104`, puis injectée par
  `backend/atlas_backend/v4_router.py:1333` avant validation complète.
  L'ancien prompt reproduisait donc une forme que l'adaptateur rejette
  localement en `BAD_REQUEST`, sans appel Core ni proposition.
- **Attribution exacte de l'incident physique :** `INCONCLUSIVE` — les
  arguments bruts n'ont pas été conservés. La correction porte sur le harnais
  de test, pas sur le contrat produit, qui était déjà correct.

## 2. Modifications (5 fichiers, aucun code produit)

| Fichier | Changement |
|---|---|
| `android/app/build.gradle.kts` | ajoute `src/testShared/java` aux source sets `test` et `androidTest` ; garde les ressources `contracts/v4` pour le test JVM ; aucune racine ajoutée à la variante produit/QA runtime |
| `android/app/src/androidTest/java/dev/atlas/coach/e2e/V7_06B_CoachLoopInstrumentedTest.kt` | appelle le helper partagé ; retire la clé d'idempotence du prompt et l'assertion devenue sans objet ; conserve l'exigence `completed` et exactement une proposition |
| `android/app/src/testShared/java/dev/atlas/coach/e2e/V7_06BChatTestSupport.java` | helper Java pur partagé : prompt conforme au contrat, parseur qui signale `PROPOSAL_MISSING` sur la forme zéro proposition |
| `android/app/src/test/java/dev/atlas/coach/e2e/V7_06DProposalOracleTest.kt` | oracle JVM rouge→vert : zéro proposition, une proposition, parité prompt/schéma sans `idempotency_key` |
| `backend/tests/test_chat_orchestration_v4.py` | deux tests simulés : payload structuré accepté avec clé backend injectée ; ancienne forme plate rejetée `BAD_REQUEST` avant dispatch Core, sans proposition |

Le parseur conserve les contrôles existants : cohérence du compteur, UUID
canoniques, état `completed` et exactement une proposition côté instrumentation.
Zéro proposition ne peut jamais produire un PASS.

## 3. Preuve rouge → vert

**Rouge `725f9e3` :** le test ciblé JVM exécuté indépendamment par le parent sur
le commit rouge donne **3 tests, 2 échecs, 1 PASS** :

- `zeroProposalMetadataIsReportedAsProposalMissing` :
  `expected:<PROPOSAL_MISSING> but was:<V4 tool metadata is not the generated suffix of the assistant response>` ;
- `syntheticPromptMatchesProposalContractWithoutBackendOwnedKey` :
  `AssertionError` sur la parité du prompt ;
- `oneProposalMetadataParsesAndRemainsEligibleForPassAssertions` : PASS.

**Vert `0749037` :** le test ciblé passe **3/3**, puis la suite JVM complète
passe **912/912** (120 fichiers XML, 0 échec, 0 erreur, 0 ignoré), relancée
par le parent sur le SHA final avec `--offline --rerun-tasks`.

## 4. Vérifications déterministes sur le SHA final

| Suite / contrôle | Résultat |
|---|---|
| Android JVM `:app:testDebugUnitTest --offline --rerun-tasks` | **912/912 PASS**, 120 fichiers, 0 échec/erreur/ignoré |
| Android lint `:app:lintDebug --offline` | **PASS**, 0 erreur (avertissements préexistants) |
| Builds `assembleDebug`, `assembleQa` | **PASS** |
| AndroidTest `-PqaTestBuildType=qa :app:assembleQaAndroidTest --offline` | **PASS** — compilation/empaquetage uniquement, aucune exécution appareil |
| Backend `pytest -q` | **374 passed, 1 skipped**, 3 warnings, 0 échec (relancé par le parent) |
| Node `npm run test:unit` | **114/114 PASS**, 18 fichiers (relancé par le parent avec symlink temporaire, supprimé) |
| Proxy `node --test integrations/relay/proxy/test` | **19/19 PASS** (relancé par le parent) |
| Harnais `python3 -m unittest discover -s scripts/v7-06c/tests` | **27/27 PASS** (relancé par le parent) |
| `python3 -m py_compile backend/tests/test_chat_orchestration_v4.py` | **PASS** |
| `git diff --check` | **PASS** ; worktree propre, aucun symlink suivi |

Aucun ADB, Pixel/AVD ou service live n'a été utilisé pour cette phase
déterministe. Les tests physiques ont ensuite été exécutés dans la campagne
séparée `V7-06D-F1-PHYS` (voir §5bis et
`evidence/V7-06D_F1_PHYSICAL_QA.md`).

## 5. Revue indépendante

La revue lecture seule du SHA `0749037` rend **`GO_WITH_LIMITATIONS`** :

- **P0 : aucun.**
- **P1 : `EVIDENCE_GAP` de qualification physique — fermé par la campagne
  `V7-06D-F1-PHYS`.** Cinq démarrages froids PASS, zéro échec F1, sonde A4-4
  PASS et gate de provenance installé↔construit vérifié sur les cinq tentatives.
  La revue indépendante de la preuve physique rend `GO_WITH_LIMITATIONS`, sans
  P0/P1 (voir §5bis).
- **P2 : couverture du parseur.** Les tests couvrent zéro et une proposition en
  état `completed`, mais pas un statut non terminé ni des compteurs incohérents ;
  la branche zéro signale `PROPOSAL_MISSING` avant que l'appelant puisse
  rapporter le statut. Le test de schéma lit `oneOf[0]` et dépend donc de
  l'ordre des branches. Aucun faux PASS observé.
- Sont **PROVEN** : diff borné à cinq fichiers de test/configuration, absence de
  code produit modifié, absence de secret/donnée personnelle/endpoint live,
  F1a, parité F1b au niveau du contrat, partage Java et source sets.
- L'attribution exacte du rejet physique initial reste **INCONCLUSIVE**.

La revue n'a modifié aucun fichier suivi. Les compteurs backend/Node/proxy/
harnais, qu'elle n'a pas relancés, ont été revérifiés indépendamment par le
parent sur le SHA final (§4).

## 5bis. Revalidation physique `V7-06D-F1-PHYS`

Autorisation ciblée « F1 + sonde A4-4 » : paquet QA uniquement, stacks isolées,
identités synthétiques, aucun test produit, aucun service live. Cinq démarrages
froids ont été exécutés sur Pixel 9 Pro XL API 37.1, chacun avec une stack
neuve, un paquet QA neuf et un build propre.

- **5/5 PASS**, zéro échec F1 ; chaque proof est `PASS`, proposition et ACK
  `APPLIED`, `tool_state=completed`, `tool_call_count=2`, exactement une
  proposition, `forbidden_tool_count=0`, tool calls corrélés.
- **A4-4 PASS** : `REVOKED`/`REVOKED`, `close_code=null`, aucune nouvelle
  session authentifiée, erreurs `0→1`.
- **Provenance** : `source_sha=9149c47` (delta docs-only depuis `0749037`),
  `worktree_clean=true`, `runtime_sdk=37`, `compile_sdk=37.2`, hashes installés
  = hashes construits, `installed_apk_verified=true`,
  `verification_result=VERIFIED`, certificat `da301466…`.
- **Nettoyage** : le nettoyage automatique par tentative a laissé des paquets QA
  à cause d'une consommation de `stdin` dans la boucle ; le parent les a
  désinstallés manuellement. État final vérifié : aucun paquet QA, `adb reverse`
  vide, aucune stack, HOME et produit inchangés, aucun service live modifié.
- Preuve publique : `evidence/V7-06D_F1_PHYSICAL_QA.md` ; preuves privées
  `0600` hors dépôt, chemins locaux non publiés.
- **Revue indépendante de la preuve** : session
  `ee44d72e-4a7f-4ece-87ea-52a00525c015` (`codex/gpt-6-luna`/`xhigh`, lecture
  seule) — `GO_WITH_LIMITATIONS`, aucun P0/P1. P2 : un chemin absolu du home
  opérateur dans le document public, retiré après la revue ; P2 de couverture
  du parseur (`oneOf[0]`, statut non terminé) toujours ouvert. La revue autorise
  `GO_CHAT_RELIABILITY_QA` GO pour cette campagne.

## 6. Verdicts

| Verdict | État |
|---|---|
| `F1-FIX` (code) | **ACCEPTED AVEC LIMITATIONS** — `GO_WITH_LIMITATIONS` de la revue ; aucun P0/P1 code |
| Série physique F1 | **PASS** — 5/5, zéro échec F1 |
| Sonde A4-4 | **PASS** — `REVOKED`/`REVOKED`, aucune nouvelle session |
| `GO_CHAT_RELIABILITY_QA` | **GO** pour la campagne QA F1 (5/5 + A4-4, revue de preuve `GO_WITH_LIMITATIONS`, P2 documentés) |
| `GO_CHAT_CONCURRENCY` | **GO** — inchangé, non remis en cause par F1 |
| `GO_REVOKED_DEVICE_SERVER` | **GO** — inchangé |
| `GO_A4_4_CLIENT` | **GO** — confirmé sur appareil |
| `GO_APK_PROVENANCE` | **GO** — confirmé par le gate installé des cinq APK QA |
| `GO_REMOTE_V4_PUBLIC` | **GO_WITH_LIMITATIONS hérité** |
| `GO_PIXEL_PRODUCT` / `GO_PRODUCT_REMOTE` | **NO GO** — aucun test produit |

## 7. Restant à faire

1. Revue indépendante de la preuve physique effectuée :
   `GO_WITH_LIMITATIONS`, aucun P0/P1 ; le P2 de chemin public a été corrigé.
2. Le P2 de couverture du parseur (`oneOf[0]`, statut non terminé, compteurs
   incohérents) reste documenté ; il ne doit pas être présenté comme fermé.
3. Aucune nouvelle série physique n'est nécessaire ; aucun GO produit/public
   n'est déduit de cette campagne.

Aucun push, PR, merge distant ou modification live. `origin/main` reste
`9ad90c7` ; `main` locale reste `06424fd` ; le correctif F1 n'est pas intégré.
