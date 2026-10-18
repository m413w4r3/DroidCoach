# S5 — Revue indépendante Android

## Périmètre et vérifications

Lecture seule de app/, data/, focus/, rules/, habits/, coach/, content/, du manifeste et des tests Android. Rapports S1–S3, docs/VALIDATION_VM.md, README et AGENTS.md lus; backend/ ignoré. Aucun index .codegraph/; dépôt sans Git.

Environnement : JDK 21.0.12.1, wrapper Gradle 8.9, SDK /home/nill/Android/Sdk, Python 3.12.3, ADB 35.0.2. Commande exécutée depuis android/ : ./gradlew :app:testDebugUnitTest :app:lintDebug --rerun-tasks — BUILD SUCCESSFUL, 30 tests, 0 échec/erreur/ignoré. Lint réussit avec 6 avertissements (5 versions Gradle suggérées et 1 avertissement dataExtractionRules pour minSdk 26). emulator-5554 absent; pas de test connecté ni de changement du rôle HOME.

## Constats priorisés

### P0 — Restauration invalide peut faire crasher l’Activity — plausible

Fichiers : [AtlasController.kt:63](/home/nill/perso/AndroCoach/atlas-preparation/android/app/src/main/java/dev/atlas/coach/app/AtlasController.kt:63), [AtlasStateMaps.kt:31](/home/nill/perso/AndroCoach/atlas-preparation/android/app/src/main/java/dev/atlas/coach/data/AtlasStateMaps.kt:31), [AtlasStateMaps.kt:45](/home/nill/perso/AndroCoach/atlas-preparation/android/app/src/main/java/dev/atlas/coach/data/AtlasStateMaps.kt:45), [MainActivity.kt:26](/home/nill/perso/AndroCoach/atlas-preparation/android/app/src/main/java/dev/atlas/coach/app/MainActivity.kt:26).

Scénario : conserver un JSON syntaxiquement valide avec policy.quietStart mal formé (échec de LocalTime.parse) ou une ouverture récente de règle sans eventId (appel à error). Ces parseurs sont appelés sans protection pendant la construction du contrôleur; la création UI échoue à chaque lancement. schemaVersion est écrit mais jamais vérifié, donc aucun aiguillage vers une migration.

Correctif : valider la version puis migrer explicitement; décoder chaque sous-état de façon transactionnelle, avec valeur de repli et sauvegarde/quarantaine du blob invalide. Ajouter des tests JVM de restauration mal formée et de version inconnue.

### P1 — JSON invalide traité comme première installation et écrasé — vérifié par le flux de code

Fichiers : [LocalStateStore.kt:14](/home/nill/perso/AndroCoach/atlas-preparation/android/app/src/main/java/dev/atlas/coach/data/LocalStateStore.kt:14), [AtlasController.kt:112](/home/nill/perso/AndroCoach/atlas-preparation/android/app/src/main/java/dev/atlas/coach/app/AtlasController.kt:112), [AtlasController.kt:372](/home/nill/perso/AndroCoach/atlas-preparation/android/app/src/main/java/dev/atlas/coach/app/AtlasController.kt:372).

Scénario : un JSON tronqué fait retourner emptyMap(); le contrôleur prend les valeurs démo puis appelle persist() parce que la map restaurée est vide, remplaçant le blob original sans signaler la perte.

Correctif : distinguer absence et corruption, préserver une copie du texte brut et demander/rendre visible une remise à zéro avant d’écrire les valeurs par défaut.

### P1 — Désactiver les capacités n’arrête pas tous leurs effets — vérifié par le flux de code

Fichiers : [AtlasController.kt:212](/home/nill/perso/AndroCoach/atlas-preparation/android/app/src/main/java/dev/atlas/coach/app/AtlasController.kt:212), [AtlasController.kt:345](/home/nill/perso/AndroCoach/atlas-preparation/android/app/src/main/java/dev/atlas/coach/app/AtlasController.kt:345), [AtlasApp.kt:208](/home/nill/perso/AndroCoach/atlas-preparation/android/app/src/main/java/dev/atlas/coach/app/AtlasApp.kt:208), [AtlasApp.kt:211](/home/nill/perso/AndroCoach/atlas-preparation/android/app/src/main/java/dev/atlas/coach/app/AtlasApp.kt:211).

Scénario : démarrer puis terminer un focus, désactiver Habitudes, puis utiliser « Relier à une habitude… » : le bouton reste affiché et confirmHabitLink coche l’habitude sans vérifier le flag. De même, désactiver Focus laisse une session en cours affichée et le tick actif.

Correctif : faire respecter chaque flag dans les méthodes du contrôleur et dans les écrans; à la désactivation de Focus, arrêter ou mettre en pause la session selon une règle explicite. Tester la désactivation pendant une session et avant confirmation de lien.

### P1 — Le timer écrit synchroniquement chaque seconde même quand l’écran est masqué — plausible

Fichiers : [AtlasApp.kt:61](/home/nill/perso/AndroCoach/atlas-preparation/android/app/src/main/java/dev/atlas/coach/app/AtlasApp.kt:61), [AtlasController.kt:185](/home/nill/perso/AndroCoach/atlas-preparation/android/app/src/main/java/dev/atlas/coach/app/AtlasController.kt:185), [AndroidStateBackend.kt:11](/home/nill/perso/AndroCoach/atlas-preparation/android/app/src/main/java/dev/atlas/coach/data/AndroidStateBackend.kt:11).

Scénario : ouvrir Focus puis mettre l’Activity en arrière-plan sans changer d’écran. Le LaunchedEffect ne suit que controller.screen; tant que la composition vit, il tick et persist() sérialise tout l’état puis appelle SharedPreferences.commit() sur le thread UI chaque seconde.

Correctif : lier le tick au cycle de vie visible (STARTED/RESUMED) et limiter les écritures aux transitions/checkpoints nécessaires, sans supprimer le checkpoint utile à la restauration après force-stop.

### P1 — La sortie HOME peut rouvrir ATLAS après son choix comme accueil — plausible

Fichiers : [MainActivity.kt:38](/home/nill/perso/AndroCoach/atlas-preparation/android/app/src/main/java/dev/atlas/coach/app/MainActivity.kt:38), [MainActivity.kt:52](/home/nill/perso/AndroCoach/atlas-preparation/android/app/src/main/java/dev/atlas/coach/app/MainActivity.kt:52), [AndroidManifest.xml:19](/home/nill/perso/AndroCoach/atlas-preparation/android/app/src/main/AndroidManifest.xml:19).

Scénario : accepter « Proposer ATLAS comme accueil… », puis choisir « Quitter vers le launcher système ». ACTION_MAIN + CATEGORY_HOME résout alors vers le HOME par défaut, potentiellement l’alias ATLAS lui-même; le bouton ne garantit plus le retour au launcher précédent.

Correctif : fournir une sortie qui ouvre les réglages système de l’application HOME par défaut ou laisse choisir un autre launcher, et vérifier le parcours après attribution du rôle sans laisser l’AVD modifié.

### P1 — Sans gestionnaire ACTION_VIEW, le chooser peut être annoncé comme ouvert — plausible, non testé

Fichiers : [AndroidContentOpener.kt:16](/home/nill/perso/AndroCoach/atlas-preparation/android/app/src/main/java/dev/atlas/coach/content/AndroidContentOpener.kt:16), [ContentCatalog.kt:89](/home/nill/perso/AndroCoach/atlas-preparation/android/app/src/main/java/dev/atlas/coach/content/ContentCatalog.kt:89).

Scénario : aucun client ne peut ouvrir l’URL. openChooser() renvoie vrai dès que startActivity(Intent.createChooser(...)) ne lève pas d’exception; le résultat devient OPENED_CHOOSER avant d’essayer le fallback ou NO_COMPATIBLE_APP. Le rapport VM-1 indique que ce cas n’a pas été testé.

Correctif : vérifier les activités compatibles avant de lancer le chooser et retourner explicitement NO_COMPATIBLE_APP si la liste est vide; couvrir le cas sans handler sur un contexte de test isolé.

### P2 — L’état de déduplication et les propositions grandissent sans borne — vérifié par le flux de code

Fichiers : [RuleEvaluator.kt:11](/home/nill/perso/AndroCoach/atlas-preparation/android/app/src/main/java/dev/atlas/coach/rules/RuleEvaluator.kt:11), [RuleEvaluator.kt:21](/home/nill/perso/AndroCoach/atlas-preparation/android/app/src/main/java/dev/atlas/coach/rules/RuleEvaluator.kt:21), [AtlasStateMaps.kt:38](/home/nill/perso/AndroCoach/atlas-preparation/android/app/src/main/java/dev/atlas/coach/data/AtlasStateMaps.kt:38), [AtlasApp.kt:370](/home/nill/perso/AndroCoach/atlas-preparation/android/app/src/main/java/dev/atlas/coach/app/AtlasApp.kt:370).

Scénario : répéter « Injecter 4 autres » ajoute quatre nouveaux UUID à processedEventIds même pendant le cooldown; les interventions restent aussi toutes conservées et rendues. Chaque sauvegarde réécrit l’ensemble.

Correctif : associer les IDs à une date d’expiration cohérente avec la fenêtre d’idempotence, borner l’historique visible et persister une archive/rétention distincte.

### P2 — Libellé anglais et trous de tests négatifs — vérifié dans le code

Fichiers : [AtlasApp.kt:216](/home/nill/perso/AndroCoach/atlas-preparation/android/app/src/main/java/dev/atlas/coach/app/AtlasApp.kt:216), [SmokeUnitTest.kt:8](/home/nill/perso/AndroCoach/atlas-preparation/android/app/src/test/java/dev/atlas/coach/SmokeUnitTest.kt:8), [AtlasLaunchTest.kt:31](/home/nill/perso/AndroCoach/atlas-preparation/android/app/src/androidTest/java/dev/atlas/coach/AtlasLaunchTest.kt:31).

Scénario : une session active affiche littéralement running ou paused; l’écran construit le libellé à partir du nom d’enum. La suite passe, mais ne couvre pas la restauration invalide, la désactivation de capacités ni le retour HOME après attribution du rôle; le smoke test unitaire ne vérifie que la constante ATLAS.

Correctif : mapper les statuts et effets à des libellés français explicites; ajouter les assertions ciblées ci-dessus et tester les cas négatifs plutôt que seulement la présence de l’écran.

## Résumé

- Risque principal : crash de démarrage sur un sous-état JSON mal formé; la version de schéma n’est pas migrée.
- JSON syntaxiquement invalide : retour silencieux aux valeurs démo puis écrasement de l’état local.
- Les flags Habitudes/Focus ne neutralisent pas les usages déjà ouverts; le timer persiste synchroniquement en arrière-plan.
- Sortie HOME après sélection d’ATLAS et absence de handler ACTION_VIEW restent plausibles, non testées.
- Tests JVM et lint passent; 30 tests, 0 échec. Aucun émulateur connecté ni Pixel physique vérifié pour S5.
- Manifeste sans permission sensible, sauvegarde Android désactivée; aucun problème manifeste de cibles ou descriptions d’accessibilité relevé.
