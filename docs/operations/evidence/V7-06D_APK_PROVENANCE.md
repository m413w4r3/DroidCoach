# V7-06D — Traçabilité cryptographique source → APK → preuve

**Date :** 2026-11-01
**Base :** `9ad90c7a079736ffc5f7bb6a7f0d4ee3eed70580`
**Audit :** `codex/gpt-6-luna`, effort effectif `xhigh`, worktree
`/tmp/atlas-v7-06d-a3-proof-ro`, lecture seule (`collaborationMode=Plan`).
**Statut :** implémentation et tests terminés ; P1 de revue indépendante corrigé
et revérifié ; aucun run physique. Aucun APK contenant un secret ou une fixture
personnelle n'est publié.

## 1. Lacune V7-06C

Les preuves JSON V7-06C contenaient des hashes d'APK calculés après le run, mais
pas le SHA source, l'état propre/sale, le variant Gradle, l'`applicationId` de
test, les versions, le SDK de compilation, l'empreinte du certificat de signature
ni un manifeste de build. Aucun contrôle ne comparait les APK **installés** aux
APK construits avant l'instrumentation. Le lien était donc documentaire, pas
cryptographique.

## 2. Manifeste généré au build

Le build doit produire un fichier déterministe, embarqué dans l'APK application
**et** dans l'APK AndroidTest, sous `assets/atlas-build-provenance.json` :

```json
{
  "source_sha": "<git rev-parse HEAD>",
  "source_tree_sha": "<git rev-parse HEAD^{tree}>",
  "worktree_clean": true,
  "variant": "qa",
  "application_id": "dev.atlas.coach.qa.v706b...",
  "test_application_id": "dev.atlas.coach.qa.v706b....test",
  "version_code": 1,
  "version_name": "0.1.0-qa",
  "compile_sdk": "37.2",
  "min_sdk": 26,
  "target_sdk": 36,
  "scenario_id": "v7-06d-<scenario>"
}
```

Contraintes :

- aucune date, aucun secret, aucun token, aucune donnée personnelle ;
- JSON trié et sérialisé de façon canonique ;
- généré depuis la copie de travail réellement compilée, pas depuis une valeur
  documentaire copiée ;
- `worktree_clean=false` autorisé pour un build de développement mais **refusé
  pour une preuve de qualification** ;
- le manifeste est couvert par le hash SHA-256 de l'APK puisqu'il est embarqué
  avant signature.

## 3. Gate avant instrumentation

Avant tout `am instrument`, le runner doit :

1. extraire le manifeste des APK app et test construits ;
2. vérifier `source_sha`, `source_tree_sha`, `worktree_clean`, `variant`,
   `application_id`, `test_application_id`, `version_code`, `version_name`,
   `compile_sdk`, `scenario_id` contre la demande et le checkout ;
3. calculer les SHA-256 des deux APK construits ;
4. après installation, lire `pm path` et hacher les APK **installés** ; ils
   doivent être byte-identiques aux APK construits ;
5. vérifier le certificat de signature de l'APK construit
   (`apksigner verify --print-certs`) et la cohérence package/version installés ;
6. en cas de champ absent, mismatch, worktree sale ou APK installé différent :
   **ne pas instrumenter**, produire un code d'échec non sensible ou marquer
   `INCONCLUSIVE` pour un oracle post-hoc.

Le proof JSON sérialise :

```
provenance.source_sha
provenance.source_tree_sha
provenance.worktree_clean
provenance.variant
provenance.application_id
provenance.test_application_id
provenance.version_code
provenance.version_name
provenance.compile_sdk
provenance.runtime_sdk
provenance.app_apk_sha256
provenance.android_test_apk_sha256
provenance.signing_cert_sha256
provenance.build_provenance_sha256
provenance.android_manifest_sha256
provenance.android_test_manifest_sha256
provenance.installed_apk_sha256
provenance.scenario_id
provenance.network
provenance.endpoint
provenance.installed_apk_verified
provenance.verification_result
```

Un proof de run normal ne peut pas être `PASS` si la provenance est absente ou
incohérente. Un oracle `verify-existing` sur une exécution historique, dont les
APK installés ne peuvent plus être vérifiés, porte
`verification_result=INCONCLUSIVE` ; il ne vaut pas `GO_APK_PROVENANCE`.

## 4. Tests négatifs obligatoires

- worktree sale → refus/`INCONCLUSIVE` ;
- APK app ou test absent → refus ;
- manifeste absent d'un APK → refus ;
- `source_sha`/`source_tree_sha` différents du checkout → refus ;
- variant/package/version incohérents → refus ;
- certificat de signature incohérent → refus ;
- hash de l'APK installé différent du construit → refus, zéro instrumentation ;
- proof post-hoc sans provenance → `INCONCLUSIVE`, pas `PASS` ;
- aucun token, bearer, série brute ou donnée personnelle dans le manifeste/proof.

## 4bis. P1 de revue indépendante et correction

La revue indépendante `5f9adb37-57bc-4068-9aa8-53899f44f00b` a trouvé que
`verify_installed_artifacts` retourne `installed_apk_sha256`, que
`start_instrumentation` l'ajoute au proof, puis que `verify_complete_proof_provenance`
rejette tout champ hors `QA_PROOF_FIELDS` : le run réel était bloqué avant
`am instrument` (`PROOF_PROVENANCE_FIELD_MISSING`). Le test existant masquait le
problème car son mock ne retournait que `installed_apk_verified`.

Correction (commit rouge `c599661`, correctif `1031a1a`, intégrés
`7ea8c58`/`69d0db6`) :

- `installed_apk_sha256` est intégré au schéma (`QA_PROOF_FIELDS` et
  `PASS_PROVENANCE_FIELDS`) et validé comme dictionnaire exact `{app,
  android_test}` de SHA-256 minuscules ;
- les hashes installés doivent être égaux aux hashes des APK construits ;
- le rejet des champs inconnus reste strict ;
- une régression couvre le chemin `verify_installed_artifacts` →
  `instrument_after_verification` → `start_instrumentation` →
  `verify_complete_proof_provenance`, y compris les hashes absents, malformés ou
  différents ;
- le wrapper V7-06C applique la même validation.

Le code Android est inchangé entre le build APK `cf82cd0` et les correctifs
harnais `69d0db6`/`bfb1965` (`git diff cf82cd0..bfb1965 -- android/` vide) ; le
build APK final reste donc valide et les correctifs portent sur le harnais de
vérification.

## 4ter. P2 de revue delta et correction

La revue delta `c2c2abd5-0340-4bfe-8fe6-35e5cc6bfc15` a confirmé le P1 corrigé
et relevé deux P2 : `require_pass_provenance` du wrapper V7-06C n'exigeait pas
le schéma exact et acceptait des clés inconnues, alors que le validateur du
runner les refuse ; le test de succès mockait la frontière du vérificateur et
n'exécutait pas la vraie fonction `verify_installed_artifacts`.

Correction Z3 (commit rouge `052ca80`, correctif `4ebdf5f`, intégrés
`fded03d`/`bfb1965`) :

- le wrapper exige l'ensemble exact de clés de provenance et refuse toute clé
  inconnue, y compris dans le proof post-hoc ;
- un test de succès exécute la vraie fonction `verify_installed_artifacts` avec
  des primitives bas niveau simulées (dumps de paquets, hashes installés,
  certificats), puis valide le proof fusionné par le validateur strict ;
- le schéma post-hoc reflète les mêmes clés avec
  `verification_result=INCONCLUSIVE` ;
- les libellés de statut obsolètes sont corrigés dans les documents finaux.

Harnais **27/27 PASS**, `py_compile` et `git diff --check` PASS ; code Android
inchangé.

## 5. Résultats

| Oracle | Résultat |
|---|---|
| Manifeste généré et embarqué dans l'APK app QA | **PASS** — build de validation `49ef354`, `assets/atlas-build-provenance.json` présent |
| Manifeste généré et embarqué dans l'APK AndroidTest QA | **PASS** — mêmes octets que l'APK app |
| SHA source/tree/clean/variant/package/version/SDK corrects | **PASS** — `source_sha=49ef354…`, `worktree_clean=true`, `variant=qa`, `compile_sdk=37.2`, IDs et versions conformes |
| Extraction et hash du manifeste | **PASS** — hash canonique `dd4b7d39…` (raw `abbbde46…`) identique dans les deux APK |
| Gate installé↔construit testé (négatifs inclus) | **PASS** — tests unitaires dirty/mismatch/absent/cert/version/hash |
| Proof post-hoc sans provenance → `INCONCLUSIVE` | **PASS** — `post_hoc_provenance` et `require_pass_provenance` |
| P1 revue : `installed_apk_sha256` rejeté par le validateur strict | **PASS (corrigé)** — test rouge `c599661` → correctif `1031a1a`, intégrés `7ea8c58`/`69d0db6` |
| P2 revue delta : wrapper permissif, test mockant la frontière | **PASS (corrigé)** — rouge `052ca80` → correctif `4ebdf5f`, intégrés `fded03d`/`bfb1965` |
| Tests harnais complets | **PASS** — **27/27** (parent a rejoué) |
| Build `assembleQa` / `assembleQaAndroidTest` | **PASS** — agent et parent |
| Exécution physique avec ces APK exacts | **NOT_RUN** — la phase physique a utilisé un build distinct `2fa90bd` (Android identique) ; gate installé exercé et documenté en §8 |

### Build de validation de la branche Z (`49ef354`)

| Élément | Valeur |
|---|---|
| APK app QA | `0b026d98a94659a8288ae486a344a9b9cf57815b2fb12c756081b44fe65241cd` |
| APK AndroidTest QA | `64baf58cd6e63ec81d98ca381cad08632251d9eeab0eebde6dd511add8daab80` |
| Manifeste canonique | `dd4b7d3995b63a111abb3b9ca0b6ecc2724e221eaff0168d42b4ed4e69dcd679` |
| Source | `49ef35462445f6ed7558e7885e24eb366c33cfe7`, arbre propre |
| Variante / SDK | `qa` / `compile_sdk=37.2`, `min_sdk=26`, `target_sdk=36` |
| Scénario | `v7-06c-android-apply` (build de validation) |

Le build final de la campagne a été reconstruit sur le SHA intégré et documenté
dans `V7-06D_FINAL_REPORT.md` ; les hashes ci-dessus ne sont pas réutilisés comme
preuve d'un autre SHA.

### Build final intégré (`cf82cd0`, worktree propre)

| Élément | Valeur |
|---|---|
| APK app QA | `1e31a6437065a0b6788e1c2d66238ab00f34f850e54e5195b5b5b34cf5ace7b8` |
| APK AndroidTest QA | `05dd465be19f969d46573ac6f8c8604860cdf6aa17b536ad094e73acef8d25fd` |
| Manifeste canonique | `8d678af5b2dca8197b580a962a52078901e884bc24e5dcae7ad2f443cc815bb7` |
| Source / arbre | `cf82cd02a8e00f75d1cd7d476a84691a276f3011` / `1d7c1a6069abc2878aa11805f29b347863cbd047` |
| Propreté / variante | `worktree_clean=true` / `qa` |
| SDK | `compile_sdk=37.2`, `min_sdk=26`, `target_sdk=36` |
| Version | code `1`, nom `0.1.0-qa` |
| Signature (app et test) | certificat debug SHA-256 `da3014662eb65e50a87ef364a2e583c331398795b222e3b37b1a16553a00eeee` |
| Scénario | `v7-06d-final` |
| Manifestes app/test | identiques octet pour octet |

Le HEAD documentaire final peut différer de `cf82cd0` uniquement par des
changements `docs/` ; le SHA compilé et le manifeste embarqué restent
`cf82cd0…`. Aucun APK installé n'a été vérifié sur appareil dans V7-06D.

## 6. Limites

- Aucun run Android physique V7-06D n'a été exécuté ; les hashes d'APK installés
  ne sont donc pas prouvés sur appareil dans cette campagne. Le gate est testé
  de façon déterministe et le build final est vérifiable localement.
- Les preuves V7-06C historiques ne sont pas réécrites : elles restent
  `INCONCLUSIVE` pour le lien APK↔source et ne sont pas reclassées.
- Aucun APK n'est publié ; seuls des hashes et des champs non sensibles sont
  conservés.

## 7. Verdict

**`GO_APK_PROVENANCE` = GO** : la chaîne source→manifeste→APK est vérifiée sur
le build intégré `cf82cd0`, le P1 de revue sur le champ installé et les P2 de la
revue delta sont corrigés et testés (rouge → vert), la revue finale
`19c375fd…` sur `88ac019` ne relève aucun P0/P1. La phase physique
`V7-06D-PHYS` a exercé le gate installé↔construit sur le Pixel : pour les trois
preuves PASS, les hashes installés sont égaux aux hashes construits
(`installed_apk_verified=true`, `verification_result=VERIFIED`) ; les cinq
tentatives ont franchi le gate avant instrumentation. Limite : APK QA
uniquement, pas d'APK produit ; build `2fa90bd` (Android identique à
`bfb1965`). Les preuves V7-06C historiques restent `INCONCLUSIVE` pour ce lien
et ne sont pas reclassées. Détails : `V7-06D_PHYSICAL_QA.md`.

## 8. Phase physique V7-06D-PHYS (2026-11-01)

Sur Pixel 9 Pro XL physique (Android 17 / SDK 37.1), cinq tentatives QA
isolées ont franchi le gate installé↔construit avant `am instrument`. Les trois
preuves PASS (`2fa90bd`, arbre `40c9e5bc…`, `worktree_clean=true`, `qa`,
compile `37.2`) portent `installed_apk_verified=true`,
`verification_result=VERIFIED` et des hashes installés égaux aux hashes
construits :

| Tentative | APK app | APK test |
|---|---|---|
| 1 | `9cf3d847…` | `b9915e94…` |
| 4 | `45683196…` | `08aeb076…` |
| 5 | `472035b0…` | `10fc3c21…` |

Les tentatives 2 et 3 ont également exécuté l'instrumentation, donc franchi le
gate, mais n'ont pas produit de proof PASS (échec F1 après la réponse chat).
Limites : APK QA uniquement ; pas d'APK produit ; aucune preuve publique WSS.
