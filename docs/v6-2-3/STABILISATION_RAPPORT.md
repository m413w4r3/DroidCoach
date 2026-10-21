# Stabilisation produit ATLAS V6.2.3 — PROD-R1

Date : 2026-10-21
Worktree : `worktree/ancient-trail`
Révision testée : `44361333fd7ed6fcd1cdec6123c48439fe1e5459`
Parent intégré au départ : `e690323c9ab70ea4d4eeca8bf732c9b53f53935d`

Les statuts ci-dessous concernent la révision `4436133`. Chaque `PASS` JVM renvoie à l’exécution E1, qui enregistre le SHA, la commande exacte, la sortie Gradle et le résumé synthétique des XML. Aucun AVD ni téléphone n’a été utilisé dans cette reprise PROD-R1.

## Résultats

| Point | Statut | Cas vérifiés | Exécution et preuve |
|---|---|---|---|
| Suite JVM complète | PASS | 321 tests, 0 échec, 0 erreur, 0 ignoré | E1 : SHA `44361333fd7ed6fcd1cdec6123c48439fe1e5459`; commande et sortie dans [jvm-full-4436133.md](preuves-prod/jvm-full-4436133.md), détail dans les deux TSV associés |
| Arrêt après le commit Focus + Goal du produit, reprise et crédit unique | PASS | `GoalFocusStartTransactionTest.processStopAfterProductRootWriteRestoresLinkedFocusAndCreditsOnce` | E1, cas listé PASS dans [jvm-full-4436133-cases.tsv](preuves-prod/jvm-full-4436133-cases.tsv) |
| Commit racine unique, double démarrage, fin et annulation | PASS | `focusAndGoalLinkUseOneRootCommit`, `concurrentDoubleTapStartRequestsCommitOnlyOneFocusSession`, `controlledClockCreditsOnlyCompletedLinkedWorkAndRepeatedReadsDoNotDoubleCredit`, `unlinkedSessionAndCancelledLinkedSessionNeverChangeFocusGoalProgress` | E1, suite `GoalFocusStartTransactionTest` dans [jvm-full-4436133-suites.tsv](preuves-prod/jvm-full-4436133-suites.tsv) |
| Contexte Coach consenti et retrait des objectifs pendant une requête | PASS | `consentedCoachContextCarriesLinkedFocusAndGoalProgressAndDropsRevokedGoals`, `revokingGoalsDuringAnInFlightCoachRequestRemovesThemFromTheNextRequest` | E1, cas dans [jvm-full-4436133-cases.tsv](preuves-prod/jvm-full-4436133-cases.tsv) |
| Récupération locale et quarantaine sans charge brute | PASS | `AtlasControllerInitialStateRecoveryTest.unparseableDocumentIsQuarantinedAsValueFreeEnvelopeThenGoalPersistsAcrossRecreation`, `unknownSchemaQuarantinesSanitizedCopyAndDoesNotRestoreSharedPayload` | E1, cas dans [jvm-full-4436133-cases.tsv](preuves-prod/jvm-full-4436133-cases.tsv) |
| Idempotence et révocation du scheduler proactif | PASS | tests JVM de réservation, callback révoqué et `successfullyDeliveredNotificationIsNotRepeatedAfterSchedulerRecreation` | E1, suite `ProactiveCoachSchedulerTest` dans [jvm-full-4436133-suites.tsv](preuves-prod/jvm-full-4436133-suites.tsv) |
| Consentement et révocation de localisation dans le domaine JVM | PASS | `LocationPrivacyTest`, `LocationRepositoryTest` | E1, cas dans [jvm-full-4436133-cases.tsv](preuves-prod/jvm-full-4436133-cases.tsv) |
| Constructeur réel d’`AtlasController` et son initialisation Android | NOT_RUN | L’initialisation réelle du constructeur n’est pas exécutée dans la JVM hôte. | Limite documentée ci-dessous. |
| Publication Android des notifications, rôle HOME, pause d’urgence sur appareil | NOT_RUN | Aucun instrumenté et aucune vérification manuelle sur appareil dans PROD-R1. | Les résultats AVD de l’ancien rapport ne sont pas une qualification de la révision intégrée et sont retirés de cette matrice. |
| Permissions système de localisation sur Android | NOT_RUN | Aucun instrumenté sur cette révision. | Les résultats JVM ci-dessus ne qualifient pas les adaptateurs Android. |
| API 34 intégrée, API 37, Gateway réel, téléphone réel | NOT_RUN | Hors de cette exécution, conformément à la consigne sans appareil. | Aucun résultat n’est revendiqué pour ces cibles. |
| `lintDebug` et assemblage AndroidTest sur cette révision | NOT_RUN | Non lancés dans PROD-R1. | — |

## Correction du constat P2 — reprise après commit racine

Le test précédent de `GoalFocusPersistenceRecoveryTest` écrivait lui-même le document racine attendu. Il a été supprimé. Le test de remplacement est dans `GoalFocusStartTransactionTest` et emprunte la méthode publique de production `AtlasController.startFocus`, donc le chemin réel `startFocusTransaction` → `FocusEngine.prepareStart` + `GoalPanelCoordinator.prepareFocusLink` → candidat racine → `persistOrThrow` → `LocalStateStore.write`.

Le `MemoryStateBackend` de test enregistre les octets racine, puis lève une erreur simulant l’arrêt du processus avant que l’appel d’écriture retourne. Le test vérifie qu’une seule écriture a eu lieu et que l’ancienne instance n’a publié ni session Focus ni lien Goal. Il relit ensuite le même backend, restaure une nouvelle `FocusEngine` avec `FocusEngine.restore` et un nouveau `GoalPanelCoordinator` depuis le sous-document Goals. Le Focus actif et son lien sont présents, avec zéro minute créditée. La fin est exercée via `AtlasController.finishFocus`; une troisième reconstruction relit le même backend et confirme 25 minutes, même si le Focus est présent à la fois comme session courante et dans l’historique. Dix lectures supplémentaires restent à 25 minutes. Le test n’effectue aucune écriture de transaction manuelle et aucun accès réseau.

Le constructeur réel d’`AtlasController` n’est pas instanciable de façon représentative dans la JVM de ce projet : le constructeur exige un `Context` Android et son initialisation appelle `context.applicationContext`, `Handler(Looper.getMainLooper())`, les services Android, SharedPreferences et les adaptateurs de notifications/usage. Les dépendances JVM de `android/app/build.gradle.kts` ne fournissent que JUnit et `org.json`, sans runtime Android/Robolectric. Les tests JVM existants utilisent donc `Unsafe.allocateInstance` et injectent les dépendances; le nouveau test fait de même, mais appelle les méthodes publiques réelles `startFocus` et `finishFocus`, et les classes de production de restauration. Il ne prétend pas tester le constructeur ou son bloc `init` Android. Aucune couture ni modification d’`AtlasController.kt` n’a été nécessaire.

## Défauts et changements

Le défaut démontré était une preuve de test trop faible, pas un défaut produit : son implémentation pouvait rester verte si le contrôleur construisait un mauvais candidat racine. Le test manuel du store a été remplacé par le scénario de transaction réel décrit ci-dessus. Aucun fichier de production n’a été modifié.

Le commit `44361333fd7ed6fcd1cdec6123c48439fe1e5459` contient le test et retire l’ancien test trompeur. Les preuves E1 ont été générées ensuite à partir des résultats XML de ce commit. Le rapport et les résumés ne contiennent pas de numéro de série matériel ni de chemin absolu du compte local.

## Tentative de compilation corrigée

Avant le commit, une prévalidation sur HEAD `e690323c9ab70ea4d4eeca8bf732c9b53f53935d` a échoué car le nouveau test ne référençait pas encore `GoalProgressCalculator` (`Unresolved reference`). L’import a été ajouté; la suite complète passe ensuite sur `4436133`. La commande et l’erreur concise sont consignées dans [jvm-precheck-e690323.md](preuves-prod/jvm-precheck-e690323.md).

## Résultats historiques retirés de la qualification

Les comptes JVM et instrumentés de l’ancien rapport concernaient des exécutions antérieures à l’intégration examinée dans R3. Les statuts instrumentés de l’ancien rapport ne mentionnaient pas le SHA exact au moment des tests; ces affirmations sont retirées plutôt que réattribuées. Leur statut pour la révision intégrée est `NOT_RUN`; aucun appareil n’a été sollicité pour les réattribuer.

## Contrôle des espaces

Le contrôle `git diff --check` est `PASS` sur les modifications PROD-R1. La commande, le HEAD et la sortie sont consignés dans [git-diff-check-4436133.md](preuves-prod/git-diff-check-4436133.md).

## Suite V6.3

- Exécuter sur un AVD autorisé les parcours Android intégrés de rôle HOME, notifications et permissions quand la QA le demande.
- Valider le constructeur/récupération Android de `AtlasController` dans l’environnement instrumenté QA, sans ajouter de couture à ce worktree.
- Conserver les résultats de chaque révision avec son SHA, les sorties de compte synthétiques et des preuves expurgées.
