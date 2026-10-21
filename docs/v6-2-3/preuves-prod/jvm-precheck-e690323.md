# Prévalidation JVM avant commit du correctif de test

- Statut : FAIL (erreur de compilation du nouveau test, corrigée ensuite)
- HEAD : `e690323c9ab70ea4d4eeca8bf732c9b53f53935d`
- État du worktree : modifications locales du test de transaction en cours ; pas encore commitées
- Répertoire : `android/`
- Commande exacte :

  ```sh
  GRADLE_USER_HOME=/tmp/atlas-v623-review-gradle-home ./gradlew --no-daemon --rerun-tasks :app:testDebugUnitTest --tests 'dev.atlas.coach.app.GoalFocusStartTransactionTest.processStopAfterProductRootWriteRestoresLinkedFocusAndCreditsOnce' --console=plain
  ```

- Sortie pertinente : `GoalFocusStartTransactionTest.kt:180:13 Unresolved reference 'GoalProgressCalculator'.`; `BUILD FAILED` pendant `:app:compileDebugUnitTestKotlin`.
- Correction : ajout de l’import `dev.atlas.coach.goals.GoalProgressCalculator`.
- Le même test compile et passe ensuite sur le commit `44361333fd7ed6fcd1cdec6123c48439fe1e5459`; voir [jvm-full-4436133.md](jvm-full-4436133.md) et son TSV par cas.
