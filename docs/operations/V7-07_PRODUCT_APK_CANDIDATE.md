# ATLAS V7-07 — Candidat APK produit

**Date :** 2026-11-01
**BASE_SHA :** `32e7c70c78fbe295f66f67d6be50dc797a3f6e6d`
**Statut :** **EN COURS DE CONSTRUCTION / NON INSTALLÉ**. Ce document prépare un
candidat ; il ne vaut pas autorisation d'installation.

## 1. Identité attendue

| Élément | Valeur attendue | Statut |
|---|---|---|
| `applicationId` | `dev.atlas.coach` | à vérifier sur l'APK construit |
| `versionCode` | `1` | à vérifier |
| `versionName` | `0.1.0` | à vérifier |
| Variante | `debug` (pas `qa`) | à vérifier |
| Signature | certificat debug SHA-256 `da301466…eeee` | à vérifier |
| `minSdk` / `targetSdk` / `compileSdk` | `26` / `36` / `37.2` | à vérifier |
| Manifeste de provenance | `source_sha=32e7c70…`, `worktree_clean=true` | à vérifier |
| Activité HOME | `dev.atlas.coach.app.LauncherActivity` | à vérifier |
| `allowBackup` | `false` | à vérifier |
| `debuggable` | `true` (identique au produit installé historique) | à vérifier |

Le candidat doit conserver `versionCode=1` pour permettre une mise à jour
`adb install -r` sur le paquet existant et un rollback vers l'APK précédent de
même versionCode. Une livraison ultérieure devra incrémenter le versionCode.

## 2. Build et tests

Le build est exécuté hors ligne dans un worktree détaché au SHA exact, sans
installation, sans ADB et sans réseau. Résultats constatés le 2026-11-01
(session `e15d44a1-219c-456b-bc5f-7f0857b78b25`, `codex/gpt-6-luna/xhigh`) et
revérifiés par le parent :

| Oracle | Résultat | Preuve |
|---|---|---|
| `:app:testDebugUnitTest --offline --rerun-tasks` | **PASS 912/912**, 120 fichiers, 0 échec/erreur/ignoré | rapports XML du build |
| `:app:lintDebug --offline` | **PASS**, 0 erreur, 78 avertissements | `lint-results-debug.*` |
| `:app:assembleDebug` | **PASS** | `BUILD SUCCESSFUL`, 84 tâches |
| `:app:assembleDebugAndroidTest` | **PASS** (compilation/empaquetage, aucune exécution) | APK test produit |
| `git diff --check` / worktree propre hors build | **PASS** | seul `artifacts/` non suivi |
| Provenance `worktree_clean=true` | **PASS** | manifeste embarqué |

### Artefacts

| Artefact | SHA-256 | Taille |
|---|---|---:|
| `app-debug.apk` | `d32efc7684c131131101f476f9f7ae186933efe863cb561b2ce0039ddc45e992` | 15 806 203 |
| `app-debug-androidTest.apk` | `8f6376be9b9bfc2bf6c8c004d0bf7cbb770a55936af0e8aeab2378014a194795` | 1 568 240 |
| `atlas-build-provenance.json` | `e367fe3616f4e955ccd2468a63342bffbc8192b6120e1ad3855173d4617ceabb` | 406 |
| Manifeste canonique trié | `9647b6b8507d9a1d195f3e1cfdb1b76ea284698cc4d3c12e635c676600e89e6e` | — |

`zipalign -c -v 4` et `apksigner verify` passent sur les deux APK. Les
artefacts sont conservés dans un répertoire de build privé, hors dépôt.

## 3. Vérifications de l'APK

Résultats constatés sur l'APK `d32efc76…` :

- `applicationId=dev.atlas.coach`, `versionCode=1`, `versionName=0.1.0`,
  `minSdk=26`, `targetSdk=36`, `compileSdk=37.2` ;
- signature V2 `da3014662eb65e50a87ef364a2e583c331398795b222e3b37b1a16553a00eeee`,
  identique à l'APK historique ;
- 16 permissions, **identiques** à l'APK historique, aucune permission ajoutée ;
- `debuggable=true`, `allowBackup=false`, `usesCleartextTraffic=false` ;
- `LauncherActivity` avec `MAIN` + `HOME` + `DEFAULT` ; `MainActivity` pour
  `LAUNCHER` ;
- aucun `AccessibilityService`, Device Admin/Device Owner, kiosque ou root ;
- manifeste de provenance : `source_sha=32e7c70…`, `worktree_clean=true`,
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
| Conservation réelle des données | état actuel inconnu | **NOT_RUN / BLOCKED_DEVICE_ABSENT** |
| Rôle HOME actuel | inconnu | **NOT_RUN** |
| Permissions actuelles | inconnues | **NOT_RUN** |
| Migration état local/plans | code compatible | **PROVEN (statique)** |
| Rollback vers l'APK précédent | même signature/versionCode | **PROVEN (statique)** ; test AVD requis |

## 5. Préconditions d'installation (non exécutées)

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

- Il ne prouve pas que l'application produit actuellement installée se met à
  jour sans perte.
- Il ne prouve pas le rôle HOME, les permissions, le calendrier ou les alarmes.
- Il ne prouve pas le chat distant, la proposition ou l'ACK sur le produit.
- Il ne prouve pas la fiabilité quotidienne.
- Il ne vaut ni `GO_PRODUCT_INSTALL`, ni `GO_PRODUCT_REMOTE`, ni
  `GO_DAILY_PILOT`.

## 7. Verdict

`GO_PRODUCT_APK_BUILD` : **GO** — build reproductible au SHA exact, 912/912
JVM, lint 0 erreur, APK signé compatible, permissions inchangées, provenance
vérifiée.

`GO_PRODUCT_APK_CANDIDATE` : **PENDING** — la revue indépendante
sécurité/perte de données et l'essai AVD backup/restore/update ne sont pas
encore terminés. Le build seul ne suffit pas.

`GO_PRODUCT_INSTALL` : **NO-GO** tant que les préconditions §5 ne sont pas
réunies et que l'autorisation ciblée n'est pas accordée.
