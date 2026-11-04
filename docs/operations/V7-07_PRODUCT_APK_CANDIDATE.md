# ATLAS V7-07 — Candidat APK produit

**Date :** 2026-11-02
**BASE_SHA mission :** `32e7c70c78fbe295f66f67d6be50dc797a3f6e6d`
**Build final :** `b9f17f4fb1da930eeabfcb85cffd58c3929acd35` (correctif V4
`dd343c2` inclus)
**Statut :** **INSTALLÉ IN-PLACE SUR LE PIXEL LE 2026-11-02 / SMOKE LOCAL
CIBLÉ PASS / PARCOURS DISTANT BLOQUÉ**. L'installation a été exécutée après
sauvegarde réelle vérifiée ; la recette distante D est bloquée par le transport
public. Détail : `V7-07_PHYSICAL_EXECUTION.md`.

## 1. Identité vérifiée

| Élément | Valeur | Statut |
|---|---|---|
| `applicationId` | `dev.atlas.coach` | **vérifié** |
| `versionCode` | `1` | **vérifié** |
| `versionName` | `0.1.0` | **vérifié** |
| Variante | `debug` (pas `qa`) | **vérifié** |
| Signature | certificat debug SHA-256 `da301466…eeee` | **vérifié** |
| `minSdk` / `targetSdk` / `compileSdk` | `26` / `36` / `37.2` | **vérifié** |
| Manifeste de provenance | `source_sha=b9f17f4…`, `worktree_clean=true` | **vérifié** |
| Activité HOME | `dev.atlas.coach.app.LauncherActivity` | **vérifié** |
| `allowBackup` | `false` | **vérifié** |
| `debuggable` | `true` (identique au produit installé historique) | **vérifié** |

Le candidat doit conserver `versionCode=1` pour permettre une mise à jour
`adb install -r` sur le paquet existant et un rollback vers l'APK précédent de
même versionCode. Une livraison ultérieure devra incrémenter le versionCode.

## 2. Build et tests

Le build est exécuté hors ligne dans un worktree détaché au SHA exact, sans
installation, sans ADB et sans réseau. Le **build final de campagne** est
exécuté depuis `b9f17f4` (correctif V4 `dd343c2` inclus) ; les résultats de
l'ancien build `32e7c70` (`d32efc76…`) sont conservés comme historique et sont
**superseded** par les artefacts finaux consignés ci-dessous. Le tableau
ci-dessous décrit exclusivement le build final `b9f17f4` ; l'ancien build
`32e7c70` n'est rappelé qu'à titre historique dans la note ci-après.

| Oracle | Résultat attendu | Résultat constaté (build final `b9f17f4`) |
|---|---|---|
| `:app:testDebugUnitTest --offline --rerun-tasks` | PASS, compteur exact | **PASS 922/922**, 121 fichiers, 0 échec/erreur/ignoré ; quarantaine V4 5/5, parseur 8/8 |
| `:app:lintDebug --offline` | 0 erreur | **PASS**, 0 erreur, 80 avertissements |
| `:app:assembleDebug` | PASS | **PASS**, 84/84 tâches, 1 min 43 s |
| `:app:assembleDebugAndroidTest` | PASS (compilation) | **PASS** (empaquetage seulement, aucune exécution) |
| `git diff --check` / worktree propre hors build | PASS | **PASS**, `worktree_clean=true` dans la provenance |
| Provenance `worktree_clean=true` | PASS | **PASS** |

### Artefacts

| Artefact | SHA-256 | Taille |
|---|---|---:|
| `app-debug.apk` | `c7b0f71f43680f4bb45d8c9e6b808713ea09ff5f826fd79db31acce8ff4355a0` | 15 806 203 |
| `app-debug-androidTest.apk` | `36adef05792dc69a5bedcd7d6c25c330b1861e2ab0060d48d724fde775eb5533` | 1 568 272 |
| `atlas-build-provenance.json` | `82bd38c7d0554d68dc7fa26951b11b332b81b6042e946ffe69d6c79e030499b6` | 406 |
| Manifeste canonique trié | `310b180a89d56920da20160d674b9de033ae343f0f461a4fe6abe400b861828c` | — |

Provenance : `source_sha=b9f17f4…`, `source_tree_sha=6b1a6c43…`,
`worktree_clean=true`, `variant=debug`, `application_id=dev.atlas.coach`,
`version_code=1`, `version_name=0.1.0`, `compile_sdk=37.2`,
`test_application_id=dev.atlas.coach.test`. `zipalign -c -v 4` et
`apksigner verify` passent sur les deux APK. Les artefacts finaux sont
conservés en privé sous `~/.local/state/atlas-v7-07/candidate-final/`.

**Note :** l'ancien build `d32efc76…` (source `32e7c70`) ne contient pas le
correctif V4 et ne doit pas être installé. Une première tentative de rebuild
`dba3509…` a été rejetée car `worktree_clean=false` ; elle n'a livré aucun
artefact. Seul `c7b0f71f…` est le candidat final.

## 3. Vérifications de l'APK

Résultats constatés sur l'APK final `c7b0f71f…` (source `b9f17f4`, correctif
V4 inclus) :

- `applicationId=dev.atlas.coach`, `versionCode=1`, `versionName=0.1.0`,
  `minSdk=26`, `targetSdk=36`, `compileSdk=37.2` ;
- signature V2 `da3014662eb65e50a87ef364a2e583c331398795b222e3b37b1a16553a00eeee`,
  identique à l'APK historique ;
- 16 permissions, **identiques** à l'APK historique, aucune permission ajoutée ;
- `debuggable=true`, `allowBackup=false`, `usesCleartextTraffic=false` ;
- `LauncherActivity` avec `MAIN` + `HOME` + `DEFAULT` ; `MainActivity` pour
  `LAUNCHER` ;
- aucun `AccessibilityService`, Device Admin/Device Owner, kiosque ou root ;
- manifeste de provenance : `source_sha=b9f17f4…`, `worktree_clean=true`,
  `variant=debug`, `test_application_id=dev.atlas.coach.test` ;
- `zipalign` et `apksigner verify` PASS sur les deux APK.

À contrôler à nouveau avant toute installation sur le produit réel :

1. **Hash et taille** : SHA-256, taille exacte, `zipalign -c -v 4`.
2. **Signature** : `apksigner verify --print-certs` ; certificat identique à
   l'APK historique `post-install-base.apk` et au keystore debug de l'hôte.
3. **Badging** : `aapt2 dump badging` ; paquet, versions, SDK, permissions.
4. **Permissions** : comparer la liste avec l'APK réellement installé le jour
   de l'installation ; signaler toute permission dangereuse ajoutée.
5. **Manifeste embarqué** : extraire `assets/atlas-build-provenance.json` et
   vérifier tous les champs, dont `source_sha`, `source_tree_sha`,
   `worktree_clean`, `variant`, `application_id`, `version_code`,
   `version_name`, `compile_sdk`, `test_application_id`.
6. **Sécurité manifeste** : absence d'`AccessibilityService`, de Device Owner,
   de kiosque, de root, de permission d'installation silencieuse.
7. **HOME** : `LauncherActivity` avec intent-filter `HOME`/`DEFAULT` ;
   `MainActivity` reste le lanceur d'application.
8. **Réseau** : `usesCleartextTraffic=false` ; le transport produit passe par
   WSS ; les exceptions debug loopback ne doivent pas devenir un chemin public.

## 4. Compatibilité avec le produit installé

| Contrôle | Base | Statut |
|---|---|---|
| Même `applicationId` | APK historique `dev.atlas.coach` | **PROVEN (historique)** |
| Même certificat | `da301466…eeee` | **PROVEN (historique)** |
| Même versionCode | 1 | **PROVEN (historique)** |
| `adb install -r` déjà réussi sur ce paquet | preuve V7_INTEGRATION | **PROVEN (historique)** |
| Update in-place final `c7b0f71f…` sur APK historique | AVD API 35 synthétique | **PASS** — données conservées, app COLD, UI rendue |
| Restauration app-only après update | AVD API 35 synthétique | **PASS** — outil final, archive vérifiée |
| Conservation réelle des données sur le Pixel | hashes avant/après | **PASS (2026-11-02)** — état local, V4 sync, plans et WorkManager identiques |
| Rôle HOME actuel | inconnu | **NOT_RUN** |
| Permissions actuelles | inconnues | **NOT_RUN** |
| Migration état local/plans | code compatible | **PROVEN (statique)** |
| Rollback vers l'APK précédent | même signature/versionCode | **PASS (AVD API 35 synthétique)** ; Pixel API 37 NOT_RUN |

## 5. Préconditions d'installation

Toutes les conditions ci-dessous ont été vérifiées avant l'installation du
2026-11-02 ; la restauration in-situ du produit réel n'a pas été exécutée.

1. Autorisation ciblée du propriétaire pour le scope « APK produit ».
2. Appareil Pixel connecté et identifié sans ambiguïté (un seul opérateur ADB).
3. `GO_DATA_BACKUP` et `GO_DATA_RESTORE` démontrés sur environnement jetable,
   avec une procédure répétable et vérifiée.
4. Archive app-only du produit réel capturée, hashée et vérifiée avant toute
   installation ; aucune donnée personnelle dans les preuves.
5. APK précédent conservé avec hash, certificat et métadonnées.
6. Rôle HOME initial et launcher précédent relevés.
7. Batterie/stockage suffisants ; aucune opération destructive (`pm clear`,
   `uninstall`) prévue.
8. Plan de rollback APK et HOME écrit, testé sur AVD, et accepté.
9. Revue indépendante sécurité/perte de données sans P0/P1 ouvert.

## 6. Ce que ce candidat ne prouve pas

- Il ne prouve pas la restauration in-situ de l'archive réelle, ni la
  conservation des tokens Keystore après désinstallation/réinstallation.
- Il ne prouve pas le rôle HOME après un changement de launcher, ni les
  permissions après une révocation.
- Il ne prouve pas le chat distant, la proposition ou l'ACK sur le produit :
  le transport public est bloqué.
- Il ne prouve pas la fiabilité quotidienne.
- Il ne vaut ni `GO_PRODUCT_REMOTE`, ni `GO_DAILY_PILOT`.

## 7. Verdict

`GO_PRODUCT_APK_BUILD` : **GO** — build reproductible au SHA `b9f17f4`,
**922/922** JVM, lint 0 erreur, APK `c7b0f71f…` signé compatible, permissions
inchangées, provenance `worktree_clean=true` vérifiée.

`GO_PRODUCT_APK_CANDIDATE` : **GO_WITH_LIMITATIONS** — l'artefact final est
intègre et l'essai AVD API 35 synthétique est **PASS** (backup/restore/update/
HOME), avec le correctif V4 `dd343c2`. Limites : smoke ciblé Pixel API 37
**PASS**, Keystore/tokens `NOT_RUN`, restauration in-situ `NOT_RUN`. La
confirmation delta de la revue indépendante est **ACCEPTED** sur `cd45015`
(P1 provenance fermé, P2 documentaire corrigé, aucun P0/P1/P2 ouvert). Ces
limites sont des conditions d'installation, pas des défauts cachés.

`GO_PRODUCT_INSTALL` : **GO_WITH_LIMITATIONS** — installation in-place réelle
PASS le 2026-11-02, données conservées, signature/permissions/HOME inchangés ;
restauration in-situ et Keystore restent des limites documentées.
`GO_PRODUCT_REMOTE` : **NO-GO** — le transport public est PASS depuis le
2026-11-04, mais aucun parcours outillé fiable ni proposition A0 produit n'a
été obtenu. Voir `V7-07_D_RESUME.md`.
