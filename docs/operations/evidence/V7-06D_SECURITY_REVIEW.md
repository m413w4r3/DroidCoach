# V7-06D — Revue indépendante (lecture seule)

**Date :** 2026-11-01
**Revue :** session Paseo `5f9adb37-57bc-4068-9aa8-53899f44f00b`,
`provider=codex`, `model=gpt-6-luna`, effort effectif `xhigh`,
`collaborationMode=Plan`, worktree détaché `/tmp/atlas-v7-06d-review-ro`
(`chmod -R a-w` sur les sources), lecture seule.
**SHA revu :** `b163202f8f0aee6d1ef5d101b180432967a2646b`
(code/build `cf82cd02a8e00f75d1cd7d476a84691a276f3011`, delta docs-only).
**Verdict initial :** `CORRECTION_REQUIRED` — un P1 confirmé et un P2.
**Après correction :** P1 corrigé `1031a1a` (rouge `c599661`, intégrés
`7ea8c58`/`69d0db6`), P2 documentaire corrigé. **Revue delta**
`c2c2abd5-0340-4bfe-8fe6-35e5cc6bfc15` sur `0c19a3f` : P1 confirmé corrigé,
deux P2 supplémentaires (wrapper acceptant des clés inconnues, test de succès
mockant la frontière du vérificateur), corrigés par Z3 `4ebdf5f` (rouge
`052ca80`, intégrés `fded03d`/`bfb1965`). **Revue finale** `19c375fd…` sur
`88ac019` : aucun P0/P1, un P2 documentaire (libellés de statut de revue devenus
obsolètes), corrigé ; les huit recommandations de verdict sont inchangées.

La session a confirmé `HEAD=b163202`, `git status --short` vide, aucun fichier
modifié, aucun test/build/ADB/Docker/réseau lancé.

## 1. Constats

### P0

**Aucun P0 identifié.**

### P1 — le runner QA rejette son propre résultat de vérification installée

- Les clés autorisées du proof sont définies dans `run_qa.py:40` ; elles
  contiennent `installed_apk_verified` et `verification_result`, mais pas
  `installed_apk_sha256`.
- `verify_installed_artifacts` (`run_qa.py:595`) retourne pourtant
  `installed_apk_sha256` avec le flag et le résultat.
- `instrument_after_verification` (`run_qa.py:231`) transmet ce dictionnaire au
  callback ; le callback de production l'ajoute au proof puis appelle le
  validateur strict (`run_qa.py:852`, `:855`).
- Le validateur exige un ensemble exact de clés (`run_qa.py:187`) et rejette
  `installed_apk_sha256` comme champ inconnu :
  `PROOF_PROVENANCE_FIELD_MISSING`, avant `push_private_pairing` et
  `am instrument`.
- Le test existant (`test_harness.py:547`) masquait le problème : son mock ne
  retournait que `installed_apk_verified`.

**Attendu :** une vérification installée réussie doit passer le gate et permettre
l'instrumentation ; des hashes installés différents des hashes construits
doivent toujours être refusés avant instrumentation.

**Correction retenue :** ajouter `installed_apk_sha256` au schéma
(`QA_PROOF_FIELDS`, `PASS_PROVENANCE_FIELDS`), valider exactement les clés
`app`/`android_test` en SHA-256 minuscules et leur égalité avec
`app_apk_sha256`/`android_test_apk_sha256` ; conserver le rejet des champs
inconnus ; ajouter une régression couvrant le chemin complet et les hashes
absents/malformés/différents. Commit rouge `c599661`, correctif `1031a1a`
(intégrés `7ea8c58`/`69d0db6`).

### P2 — libellés de progression obsolètes

`V7-06D_APK_PROVENANCE.md` décrivait encore la conception comme « en cours »
alors qu'il contenait les résultats finaux ; le registre portait encore
`RUNNING — audits indépendants en cours`. Corrigé dans les documents finaux.

## 2. Confirmations de la revue

- **Chat concurrence :** les callbacks planifient le pump hors du moniteur client
  **C** ; aucun appel client n'est fait sous le moniteur transport **T** ; **G**
  couvre `beforeDispatch` → payload → écriture socket. Le `DEVICE_REVOKED` de
  session échoue la file et l'in-flight avec l'erreur typée. Le timeout avant
  envoi est définitif et n'écrit rien ; après envoi, il reste incertain et n'est
  pas rejoué automatiquement ; l'identité d'une requête non envoyée survit à la
  reconnexion. L'exécuteur de callback est créé/arrêté avec le transport. Le test
  rouge conserve son assertion de seconde écriture. Cause historique V7-06C
  `UNKNOWN`.
- **A4-4 :** le connecteur utilise le protocole du hello pour le refus pré-auth
  et vérifie le token avant de révéler la révocation. Android ne peut entrer en
  `REVOKED` que depuis une trame authentifiée et parsée ; les échecs génériques
  ne créent pas cet état. La limite close 1012 est documentée. La sonde stricte
  est renforcée mais `NOT_RUN` ; aucun PASS de sonde n'est revendiqué.

## 3. Recommandations de verdict (SHA `b163202`)

| Verdict | Recommandation |
|---|---|
| `GO_CHAT_CONCURRENCY` | **GO** |
| `GO_CHAT_RELIABILITY_QA` | **NOT_RUN** |
| `GO_REVOKED_DEVICE_SERVER` | **GO** |
| `GO_A4_4_CLIENT` | **NO GO — NOT_RUN** |
| `GO_APK_PROVENANCE` | **NO GO en attente de revue du SHA corrigé** |
| `GO_REMOTE_V4_PUBLIC` | **GO_WITH_LIMITATIONS, hérité** |
| `GO_PIXEL_PRODUCT` | **NO GO** |
| `GO_PRODUCT_REMOTE` | **NO GO** |

**Statut global initial :** `CORRECTION_REQUIRED` pour `b163202`.

## 4. Limites de la revue

- Revue statique et documentaire ; aucun test, build, ADB, Docker ou réseau
  exécuté.
- Les totaux de tests sont des résumés documentés, non des logs bruts
  indépendamment rejoués par la revue.
- Les qualifications physiques A4-4 et fiabilité restent `NOT_RUN`.
- La revue delta du SHA corrigé a confirmé le P1 de provenance et le P2
  documentaire, puis relevé deux P2 supplémentaires corrigés par Z3. La revue
  finale `19c375fd…` sur `88ac019` n'a trouvé aucun P0/P1 et un seul P2
  documentaire (libellés obsolètes), corrigé dans les documents finaux.

## 5. Revue delta du SHA corrigé (`0c19a3f`)

**Session :** `c2c2abd5-0340-4bfe-8fe6-35e5cc6bfc15`, `codex/gpt-6-luna`,
effort effectif `xhigh`, `collaborationMode=Plan`, worktree
`/tmp/atlas-v7-06d-review2-ro` en lecture seule. Aucun fichier modifié, aucun
test/build/ADB/Docker/réseau lancé.

**Verdict delta initial : `CORRECTION_REQUIRED` sur `0c19a3f`.** Le P1
antérieur est confirmé corrigé. Deux P2 restent :

1. `run_scenario.require_pass_provenance` n'exigeait pas le schéma exact et
   acceptait des clés inconnues, alors que le validateur du runner les refuse.
2. Le test de succès utilisait un mock à la frontière du vérificateur et
   n'exécutait pas la vraie fonction `verify_installed_artifacts`.
3. Les libellés de statut de revue étaient encore marqués « en cours » /
   `[À COMPLÉTER]` dans les documents.

**Correction Z3 :** commit rouge `052ca80`, correctif `4ebdf5f`, intégrés
`fded03d`/`bfb1965`. Le wrapper exige le schéma exact ; le test exécute
`verify_installed_artifacts` avec des primitives bas niveau simulées et valide
le proof fusionné ; le schéma post-hoc reflète les mêmes clés avec
`INCONCLUSIVE` ; les libellés sont corrigés dans les documents finaux.
Harnais **27/27 PASS**, backend **399 passed / 1 skipped**, `py_compile` et
`git diff --check` PASS.

**Verdicts de la revue delta :**

| Verdict | Recommandation |
|---|---|
| `GO_CHAT_CONCURRENCY` | **GO** |
| `GO_CHAT_RELIABILITY_QA` | **NOT_RUN** |
| `GO_REVOKED_DEVICE_SERVER` | **GO** |
| `GO_A4_4_CLIENT` | **NO GO — NOT_RUN** |
| `GO_APK_PROVENANCE` | **GO_WITH_LIMITATIONS** (chaîne logicielle ; pas de preuve installée sur appareil) |
| `GO_REMOTE_V4_PUBLIC` | **GO_WITH_LIMITATIONS, hérité** |
| `GO_PIXEL_PRODUCT` | **NO GO** |
| `GO_PRODUCT_REMOTE` | **NO GO** |

## 6. Revue finale du SHA corrigé (`88ac019`)

**Session :** `19c375fd-9e02-48ac-9fc8-c8c928d5562a`, `provider=codex`,
`model=gpt-6-luna`, `effectiveThinkingOptionId=xhigh`, `collaborationMode=Plan`
pendant la préparation puis `Default` après approbation du plan, worktree
`/tmp/atlas-v7-06d-review3-ro` détaché à `88ac019` et `chmod -R a-w`. Le parent a
vérifié ces métadonnées Paseo. Aucun fichier modifié ; aucun test, build, ADB,
AVD, Docker ou réseau exécuté. Une tentative d'édition documentaire de la revue
a été refusée par le contrôle d'approbation ; le worktree est resté propre et la
revue ne l'a pas réessayée.

**Verdict global sur `88ac019` : `CORRECTION_REQUIRED` uniquement pour un P2
documentaire.** Aucun P0, aucun P1, aucune correction de code requise. Les
libellés de statut de revue des sept documents étaient devenus obsolètes après
la revue finale ; ils sont corrigés dans le commit documentaire final, sans
réécrire les verdicts initiaux ou delta explicitement datés.

Constats confirmés :

- le wrapper exige l'ensemble exact des clés de provenance et rejette les clés
  inconnues ; le proof post-hoc porte le même ensemble avec `INCONCLUSIVE` et
  ne peut pas passer le validateur PASS ;
- le runner exige `installed_apk_sha256` exactement `{app, android_test}`, en
  SHA-256 minuscules, égaux aux hashes construits ;
- le test exécute la vraie fonction `verify_installed_artifacts` avec ses
  primitives bas niveau simulées, puis valide le proof fusionné avec les deux
  validateurs stricts ; les négatifs couvrent hash divergent et absence ;
- `fded03d` ne change que le harnais de tests ; `bfb1965` porte les corrections
  attendues ; `bfb1965..88ac019` ne contient que les sept documents, aucun
  changement Android, Node ou backend.

**Huit recommandations de la revue finale :**

| Verdict | Recommandation |
|---|---|
| `GO_CHAT_CONCURRENCY` | **GO** |
| `GO_CHAT_RELIABILITY_QA` | **NOT_RUN** |
| `GO_REVOKED_DEVICE_SERVER` | **GO** |
| `GO_A4_4_CLIENT` | **NO GO / NOT_RUN** |
| `GO_APK_PROVENANCE` | **GO_WITH_LIMITATIONS** (chaîne logicielle ; aucune preuve installée sur appareil) |
| `GO_REMOTE_V4_PUBLIC` | **GO_WITH_LIMITATIONS, hérité** |
| `GO_PIXEL_PRODUCT` | **NO GO** |
| `GO_PRODUCT_REMOTE` | **NO GO** |

**Limites finales :** revue statique, sans réexécution des suites ; le P2
documentaire est corrigé après le SHA examiné ; les qualifications physiques
A4-4 et fiabilité restent `NOT_RUN`.
