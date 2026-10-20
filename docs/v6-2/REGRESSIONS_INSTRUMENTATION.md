# ATLAS V6.2 — régressions instrumentées

Date : 20 octobre 2026. AVD jetable `atlas_v62_disposable_api34`, API 34, `emulator-5554` (`sdk_gphone64_x86_64`). Un seul émulateur était connecté ; aucun téléphone physique n’a été utilisé.

## Contrats UI rétablis

Les 18 cas UI hérités listés dans la recette du 19 octobre ont été remis au contrat courant. Quinze passent maintenant ; les trois qui échouent encore exposent des défauts produit vérifiés ci-dessous. Les modifications restent dans les tests d’instrumentation :

- `AtlasLaunchTest` et `AtlasVisualCaptureTest` utilisent les libellés et descriptions sémantiques actuellement exposés ; les gestes de défilement ciblent un conteneur réellement scrollable. Les scénarios Focus, conversation, réglages, sortie et récupération locale restent exercés.
- `HomeLandscapeFooterInstrumentationTest` attend la rangée unique `Applications / Coach / Pause / Plus` du contrat V6.2, et conserve les vérifications de cibles tactiles d’au moins 48 dp.
- `LotEUiInstrumentationTest` ouvre les réglages et sorties depuis le menu Plus et fait défiler les sections longues du Coach ; les contrôles de pause d’urgence, de sortie et de taille tactile restent assertés.
- `V61LandscapeCaptureTest` déclare son prérequis d’affichage paysage au runner générique et s’exécute ensuite avec une géométrie paysage réellement configurée par ADB.
- Les deux scénarios AppOps/fixture et les deux méthodes coarse synchronisées déclarent leurs arguments hôte avec `@RequiresBackendArguments`. L’absence de harnais filtre ces cas de la suite générique ; elle ne convertit pas leur échec en succès.

Le parseur de `scripts/v62-complete-instrumentation.sh` accepte aussi la ligne JUnit abrégée `Tests run: 1, Failures: 1`. Sans cette forme, le premier résultat fixture avait été compté à tort comme une erreur plutôt qu’un échec. Le résumé final distingue maintenant les deux.

## Recette complète

Commande finale :

```sh
bash scripts/v62-complete-instrumentation.sh
```

Résultats dans [`logs/regressions-instrumentation/20261020T003437Z`](logs/regressions-instrumentation/20261020T003437Z/) :

- Assemblage app + AndroidTest réussi ; suite Gradle : **47 cas, 44 réussis, 3 échecs, 0 erreur, 0 ignoré**, répartis sur 17 classes. La recette est donc en échec honnête sur trois assertions produit, décrites plus bas.
- Quinze des 18 anciens cas UI passent. Trois échecs subsistent dans `AtlasLaunchTest` : la session Focus mise en pause n’est pas rendue lorsque Focus est désactivé, et les deux avis de récupération locale sont remplacés par un message d’échec de persistance. Les cinq classes `AtlasLaunchTest`, `AtlasVisualCaptureTest`, `HomeLandscapeFooterInstrumentationTest`, `LotEUiInstrumentationTest` et `V61LandscapeCaptureTest` ont toutes exécuté leurs cas prévus dans leur configuration adaptée.
- Le runner a bien poursuivi après la classe location : son XML contient aussi les cas MobilityService, AndroidTokenStore, SyncSettings, AgentContracts et UsageFixture. `unseen-instrumentation-classes.txt` est vide. Le run historique du 19 octobre ne contenait que 42 cas et se terminait après le timeout coarse ; les journaux Gradle ne montrent pas de crash du runner qui permettrait d’attribuer une cause plus précise. Dans le run final, les deux méthodes nécessitant l’injection coarse extérieure sont filtrées par leur prérequis explicite, puis toutes les classes restantes sont exécutées.
- Neuf cas restent filtrés dans le run portrait générique pour des préconditions explicites : 1 capture V61 paysage, 2 méthodes d’injection coarse synchronisée, 2 scénarios AppOps/fixture nécessitant le harnais, et 4 tests backend réel nécessitant adaptateur ou jeton. Les scénarios Gateway restent exclus comme demandé ; la preuve Gateway antérieure n’a pas été rejouée.
- Dix reprises ciblées ont réussi : 3 tests location sans fix réel, 1 V61 en paysage, 1 refus AppOps réel, 1 fixture réelle à quatre ouvertures, et 4 captures natives. Résumé dans [`counts.txt`](logs/regressions-instrumentation/20261020T003437Z/counts.txt).
- Les 21 PNG sont valides : 10 captures natives à 360 dp, 10 à 320 dp et la capture paysage V61. Voir [`capture-validation.txt`](logs/regressions-instrumentation/20261020T003437Z/capture-validation.txt).

### AppOps et fixture réelle

Le script vérifie l’identité de l’AVD avant d’agir. Il efface uniquement les données de `dev.atlas.coach` et `dev.atlas.coach.test`. Pour le test de refus, l’hôte configure `GET_USAGE_STATS=ignore` avec les commandes ADB standard `appops`, lance l’instrumentation, puis restaure les modes UID et package. Pour le test positif, il installe l’APK synthétique existant et permet temporairement l’AppOp ; le test instrumenté n’écrit pas AppOps et ne demande aucune permission privilégiée. Le lancement de l’activité fixture utilise la commande shell de l’UiAutomation de test.

Résultats finaux : `actualAppOpsDenialStopsBeforeQueryingUsageStats` réussit avec 0 lecture, 0 enregistrement ; `fourRealFixtureOpensReachTheRuleAfterReturningToAtlas` réussit. Le journal [`usage-fixture-events.log`](logs/regressions-instrumentation/20261020T003437Z/usage-fixture-events.log) montre 4 enregistrements, une notification active, l’ouverture de l’écran d’intention et une révocation suivie de deux sondages sans collecte.

Le même scénario fixture avait échoué une fois dans le run précédent [`20261020T002451Z`](logs/regressions-instrumentation/20261020T002451Z/) : 4 enregistrements étaient présents, mais aucune notification ne l’était. La reprise finale a réussi, donc ce comportement reste intermittent et n’a pas été masqué par un skip. Sur le run final, le journal affiche `interventions=0` alors que la notification est active ; c’est un défaut supplémentaire vérifié de l’instantané UI du contrôleur : le chemin de polling appelle `touch()` après l’intervention mais ne rafraîchit pas `interventionsSnapshot` (`AtlasController.kt`, lignes 2575–2581 et 2844–2846), tandis que le rafraîchissement de cette propriété se fait dans `refreshDomainSnapshots()` (ligne 2934). Ce défaut de code production est signalé, pas modifié dans ce travail limité aux tests.

### Localisation et état AVD

Les méthodes coarse synchronisées sont filtrées du run générique avec leur clé `location_fix_injection`. Aucun autre essai d’injection réelle n’a été fait : le rapport S11 conserve le premier fix réussi (âge 5 ms, précision rapportée 2 000 m) et le second timeout de 10 s ; le timeout n’est pas présenté comme un succès.

Le fichier [`state-restored.txt`](logs/regressions-instrumentation/20261020T003437Z/state-restored.txt) confirme le retour aux permissions coarse accordée, fine refusée, notifications accordées ; AppOps UID/package `default` ; localisation activée ; dimensions 1080×2400 et densité physique ; APK fixture désinstallée. Aucun root n’a été utilisé.

### Audit clavier et TalkBack

TalkBack était initialement désactivé (`enabled_accessibility_services=null`, `accessibility_enabled=0`). Il a été activé temporairement depuis Réglages Android, puis arrêté dans ce même écran. La tabulation clavier sur HOME a atteint les cibles de navigation Compose Focus puis Plus ; l’arbre UI montrait des noms accessibles tels que « Ouvrir Focus » et « Ouvrir Plus ». Le service est de nouveau désactivé (`accessibility_enabled=0`). Le prompt Android de notifications de TalkBack a été refusé : la permission reste non accordée et son AppOp reste `ignore`. Le refus a marqué le prompt comme définitivement refusé ; cette limite d’état est consignée dans [`accessibility-audit.txt`](../../logs/regressions-instrumentation/20261020T003437Z/accessibility-audit.txt). L’audio n’a pas été capturé : aucune conclusion n’est tirée sur la qualité des annonces vocales. L’audit clavier/TalkBack est partiel.

## Défauts produit à corriger séparément

1. **Focus désactivé : état de pause non rendu.** Le test vérifie directement que le contrôleur a mis la session en pause, puis cherche « Concentration · en pause » et le message « Focus désactivé. La session est en pause. ». L’échec survient parce que `FocusScreen` ne rend que « Focus désactivé dans les paramètres. » lorsque la capability est off ([`AtlasScreens.kt:205`](../../android/app/src/main/java/dev/atlas/coach/app/AtlasScreens.kt:205)). Le contrôleur produit un message de mise en pause ([`AtlasController.kt:2806`](../../android/app/src/main/java/dev/atlas/coach/app/AtlasController.kt:2806)), mais l’écran ne l’affiche pas.
2. **Récupération locale : l’avis de quarantaine est remplacé.** La quarantaine réussit, puis le test de sous-états invalides et celui de schéma inconnu lisent tous deux `Impossible d'enregistrer l'état local…` au lieu de l’avis de récupération. `latestMessage` commence avec `recoveryNotice` ([`AtlasController.kt:222`](../../android/app/src/main/java/dev/atlas/coach/app/AtlasController.kt:222), ligne 335), puis `persist()` remplace cet avis dans son bloc `catch` ([`AtlasController.kt:2874`](../../android/app/src/main/java/dev/atlas/coach/app/AtlasController.kt:2874)). L’exception de stockage est avalée ; son type précis n’est pas disponible dans ces journaux.
3. **Instantané des interventions : état UI périmé après le polling.** Le run final montre quatre enregistrements et une notification active, mais `controller.interventions.size=0` ([`usage-fixture-events.log`](../../logs/regressions-instrumentation/20261020T003437Z/usage-fixture-events.log)). `pollUsageObservationWithCurrentConsent()` reçoit le résultat et appelle `touch()` ([`AtlasController.kt:2575`](../../android/app/src/main/java/dev/atlas/coach/app/AtlasController.kt:2575)); `touch()` ne fait qu’incrémenter la révision (ligne 2844), alors que `interventionsSnapshot` n’est rempli que par `refreshDomainSnapshots()` (ligne 2934). L’interface peut donc garder un nombre d’interventions nul après notification.
4. **Fixture : résultat intermittent.** Un run a enregistré quatre événements sans notification ; la relance a satisfait le scénario complet. Garder ce test rouge si le défaut se reproduit, et journaliser la politique effective et les horodatages d’événements pour diagnostiquer ce cas.

Les assertions des trois cas Gradle restent présentes. Ce travail n’a modifié aucun code de production, manifeste, fichier Gradle, client/backend ou test Gateway. JVM et lint restent à la vérification finale du root.
