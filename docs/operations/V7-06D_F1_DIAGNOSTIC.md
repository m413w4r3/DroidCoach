# V7-06D — Diagnostic F1 (sous-session F1-DIAG)

**Date :** 2026-11-01
**Session :** `cbbda8c4-f724-4c43-b234-3daad189f0d1`, `provider=codex`,
`model=gpt-6-luna`, effort effectif `xhigh`, worktree
`/tmp/atlas-v7-06d-f1-diag-ro` détaché à
`18609702f42effee3857c6366e2b0232ca32bb46`, `chmod -R a-w`. Métadonnées Paseo
vérifiées par le parent. Lecture seule : aucun fichier modifié, aucun
test/build/ADB/Docker/réseau exécuté, aucune sous-délégation.

**Verdict : `PARTIAL`.** F1a est diagnostiqué ; l'incohérence prompt/schéma de
F1b est **PROVEN**, mais l'attribution exacte du rejet (validateur local vs
résultat métier du service) reste **INCONCLUSIVE** sans les arguments bruts.
Aucun P0. P1 fiabilité du parcours de proposition ; P2 oracle de test ambigu.

## 1. F1a — oracle de test ambigu

- Le formateur émet légitimement `Aucune proposition (0).` lorsque la liste
  d'identifiants est vide : `AgentChatClient.kt:134`.
- Le parseur instrumenté n'accepte que la forme avec identifiants :
  `V7_06B_CoachLoopInstrumentedTest.kt:636`, `:641`, regex `:810`.
  `matchEntire` échoue alors avec `IllegalStateException` au lieu d'un échec
  explicite `PROPOSAL_MISSING`.
- **Test rouge minimal :** passer le suffixe complet
  `État des outils : completed (2 appel(s)). Aucune proposition (0).` et
  attendre `PROPOSAL_MISSING`, pas l'exception de format actuelle. Le test doit
  continuer à exiger exactement une proposition pour un PASS.
- **Correctif :** reconnaître la forme zéro-proposition, puis échouer
  explicitement avant le chemin PASS ; ne jamais accepter zéro proposition comme
  succès.

## 2. F1b — prompt synthétique vs contrat d'outil

Chaîne établie :

1. Le prompt synthétique demande `activity_id`, `op`, horaires et
   `idempotency_key` **à plat**, sans `changes` ni `justification`
   (`V7_06B_CoachLoopInstrumentedTest.kt:627`, `:634`).
2. Le schéma `contracts/v4/tool-requests.schema.json` exige `plan_id`,
   `base_version`, un tableau `changes` contenant l'opération, et
   `justification` (`:54`, `:250`).
3. L'adaptateur retire `idempotency_key` du schéma présenté au modèle, car
   cette clé est **backend-owned** (`openclaw_adapter.py:104`). Il valide les
   arguments localement ; une incompatibilité renvoie `BAD_REQUEST` sans appeler
   le service (`openclaw_adapter.py:878`, `:924`).
4. Si la validation passe, le routeur injecte une clé UUIDv5 déterministe
   dérivée de la requête avant l'appel au service (`v4_router.py:1333`) ; le
   service valide ensuite les champs requis (`v4_tool_service.py:381`, `:534`).

L'app-state privé de la tentative 3 confirme le récit du modèle : la première
tentative sans `changes`/`justification` a été refusée localement, la seconde
avec `idempotency_key` a été rejetée (`BAD_REQUEST`), et aucune proposition n'a
été créée. Les arguments bruts ne sont pas conservés ; l'attribution exacte au
validateur local ou à un résultat métier du service reste `INCONCLUSIVE`.

**Conclusion :** le prompt doit demander
`changes: [{op, activity_id, target_start_minute, duration_minutes?}]` et une
`justification`, **sans** demander `idempotency_key`. La clé fournie par le
prompt est hors du contrat exposé au modèle et ne doit pas y figurer.

## 3. Tests déterministes proposés

1. **F1a** : test du parseur sur la forme zéro-proposition → `PROPOSAL_MISSING`.
2. **F1b parité prompt/contrat** : un test qui échoue sur la forme actuelle du
   prompt et vérifie la présence de `changes`/`justification`, l'absence de
   `idempotency_key` côté modèle, et le schéma du tableau `changes`.
3. **F1b adaptateur** : fournisseur simulé, payload structuré valide, injection
   backend de la clé, absence d'appel Core/proposition pour l'ancienne forme.
4. **F1b négatif** : l'ancienne forme à plat doit produire `BAD_REQUEST` local
   et aucun effet, sans appel modèle réel.

## 4. Plan F1-FIX

- **F1a d'abord** : propriété exclusive de
  `V7_06B_CoachLoopInstrumentedTest.kt` pour le parseur et son oracle explicite.
- **F1b ensuite** : même fichier pour corriger le prompt ;
  `backend/tests/test_chat_orchestration_v4.py` (propriété exclusive) pour les
  cas simulés schéma/route/service. Ne modifier le schéma ou le service que si
  ces tests révèlent un défaut distinct.
- Si l'attribution reste incertaine, ajouter une télémétrie bornée : étape du
  rejet, code d'erreur autorisé, noms de clés présentes/manquantes et résultat
  d'effet. Aucun argument, texte utilisateur, UUID ou identifiant d'appareil.
- **Oracle final** : tests ciblés, suites Android JVM, lint/build, Node, proxy,
  harnais, backend, `py_compile`, `git diff --check`, puis **5+ démarrages
  froids physiques** avec gate de provenance vérifié et zéro échec F1.
  `GO_CHAT_RELIABILITY_QA` reste **NO GO** jusque-là.
- Risques : convertir zéro proposition en PASS, dérive entre schémas, ou
  retenter une opération dont l'effet est incertain.

## 5. Limites

- Diagnostic statique fondé sur le HEAD `1860970`.
- Les arguments d'outil exacts et le résultat métier précis du service ne sont
  pas établis par les preuves expurgées.
- Le modèle/effort effectif n'est pas vérifiable depuis la session ; le parent a
  confirmé `codex/gpt-6-luna`/`xhigh` dans les métadonnées Paseo.
- Aucun test, build, ADB, Docker ou réseau exécuté ; aucun fichier modifié.
