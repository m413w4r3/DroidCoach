# V7-06D — Traçabilité cryptographique source → APK → preuve

**Date :** 2026-11-01
**Base :** `9ad90c7a079736ffc5f7bb6a7f0d4ee3eed70580`
**Audit :** `codex/gpt-6-luna`, effort effectif `xhigh`, worktree
`/tmp/atlas-v7-06d-a3-proof-ro`, lecture seule (`collaborationMode=Plan`).
**Statut :** conception et implémentation en cours ; résultats détaillés
ci-dessous. Aucun APK contenant un secret ou une fixture personnelle n'est publié.

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

## 5. Résultats

| Oracle | Résultat |
|---|---|
| Manifeste généré et embarqué dans l'APK app QA | **PASS** — build de validation `49ef354`, `assets/atlas-build-provenance.json` présent |
| Manifeste généré et embarqué dans l'APK AndroidTest QA | **PASS** — mêmes octets que l'APK app |
| SHA source/tree/clean/variant/package/version/SDK corrects | **PASS** — `source_sha=49ef354…`, `worktree_clean=true`, `variant=qa`, `compile_sdk=37.2`, IDs et versions conformes |
| Extraction et hash du manifeste | **PASS** — hash canonique `dd4b7d39…` (raw `abbbde46…`) identique dans les deux APK |
| Gate installé↔construit testé (négatifs inclus) | **PASS** — tests unitaires dirty/mismatch/absent/cert/version/hash |
| Proof post-hoc sans provenance → `INCONCLUSIVE` | **PASS** — `post_hoc_provenance` et `require_pass_provenance` |
| Tests harnais complets | **PASS** — **25/25** (parent a rejoué) |
| Build `assembleQa` / `assembleQaAndroidTest` | **PASS** — agent et parent |
| Exécution physique avec ces APK | **NOT_RUN** — pas d'autorisation |

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

**`GO_APK_PROVENANCE` = GO_WITH_LIMITATIONS** : la chaîne
source→manifeste→APK est vérifiée sur le build intégré `cf82cd0` et le gate
installé↔construit est couvert par des tests négatifs ; aucun APK installé n'a
été vérifié sur appareil dans V7-06D. Les preuves V7-06C historiques restent
`INCONCLUSIVE` pour ce lien et ne sont pas reclassées.
