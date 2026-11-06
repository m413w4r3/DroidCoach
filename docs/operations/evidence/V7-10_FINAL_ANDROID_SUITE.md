# V7-10 — Preuve de la suite Android finale

**Date :** 2026-11-06
**Exécution :** orchestrateur, sur le HEAD d’intégration `a8de7542573190f471b5d0af067425bc186aeaf5`
**SHA du code Android :** `3123dba4f89579d414406d8c790609d1e4a5adf6` (sources Android identiques à celles de `a8de754`)

## Commande et résultat

Commande lancée depuis le répertoire `android/` :

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --offline --max-workers=1 --console=plain
```

Résultat : **BUILD SUCCESSFUL in 4m 30s**; 84 tâches actionnables, dont 19 exécutées et 65 déjà à jour.

- Agrégation de 124 fichiers XML : **934 tests, 0 failure, 0 error, 0 skipped**.
- Lint : **0 erreur, 89 avertissements**.
- APK debug et APK androidTest assemblés.

## Artefacts et intégrité

Le log brut temporaire, non versionné à l’origine, faisait **5 542 octets**. Son SHA-256 avant expurgation est :

```text
361c6bb39980f502f80f08cc66b42f88a23be92e5948581919bbe3a631879204
```

La copie complète est versionnée dans [V7-10_FINAL_ANDROID_SUITE.log](V7-10_FINAL_ANDROID_SUITE.log). Les chemins absolus personnels du dépôt ont été remplacés par `<REPO>`; aucun autre artefact temporaire n’est ajouté. Le SHA-256 de la copie expurgée est `273b9283d4a3dcb5b2427d4a7834ddbf31b82c82f8261c6b753cedfe34feae5f`.

Les 124 XML utilisés pour l’agrégation étaient des artefacts locaux et ne sont pas versionnés individuellement. Le log conservé documente la commande, les tâches Gradle, la compilation, le lint, l’assemblage et le résultat final.

## Provenance et limites

Cette suite a été exécutée par l’orchestrateur sur `a8de754`; le code Android correspond à `3123dba`. A5 n’a pas réussi à démarrer son rerun Gradle final, car le wrapper était bloqué par un verrou dans un cache en lecture seule. Cette exécution ultérieure de l’orchestrateur fournit la vérification Android JVM/lint finale, sans modifier le rapport A5.

A6 a lu le log et les XML, mais n’a lancé ni Gradle ni ADB. Cette preuve ne qualifie pas un Pixel physique, un service live ou une installation produit. Les tests instrumentés sur AVD API 37 (**9/9** et **1/1**) sont consignés séparément dans les preuves A5.
