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
`7ea8c58`/`69d0db6`), P2 documentaire corrigé ; revue delta du SHA corrigé
`[À COMPLÉTER]`.

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
- La revue delta du SHA corrigé doit confirmer le P1 de provenance et le P2
  documentaire.
