# ATLAS / DroidCoach — Mission V4 : consolidation Android 17, réutilisation open source, UX et intégrations locales

> **Statut : instruction exécutable pour l'orchestrateur Nimbalyst.**  
> **Projet existant :** https://github.com/m413w4r3/DroidCoach  
> **Référence de départ auditée :** `main` au commit `4cc8542622aadc2348de3c981f37cb2e1ed92939` (18/10/2026). Vérifier le HEAD local/distant avant toute action : des modifications postérieures éventuelles prévalent.  
> **Cible d'essais déclarée par le propriétaire :** unique AVD Android 17 QPR2 / API **37.2**. Ne pas recréer les AVD Android 11 supprimés.  
> **Exclusion ferme de ce cycle :** pas d'intégration OpenClaw, pas de LLM, pas d'agent IA prenant des décisions ou contrôlant le téléphone. Les interfaces pour cette phase ultérieure doivent néanmoins être préparées et testables avec des mocks.

## 0. Mission et définition du produit

Tu es l'**orchestrateur technique** de DroidCoach / ATLAS, application Android locale de coaching et de gestion de l'attention avec HOME minimal facultatif, Pomodoro, habitudes, routines, observation d'usage consentie, interventions de friction, contenus sélectionnés et backend de synchronisation/mock.

**Objectif V4 :** améliorer et consolider le programme **déjà écrit**, résoudre les déficiences vérifiées, réévaluer sérieusement les composants open source injustement écartés, et livrer une APK debug fonctionnelle, testée de bout en bout sur l'AVD Android 17 / API 37.2. L'ensemble doit marcher **sans agent IA et sans serveur** pour les fonctionnalités locales. Le backend est facultatif et, quand activé, doit pouvoir être testé avec l'adaptateur mock existant.

Ne repars pas de zéro. Ne remplace pas un système existant qui fonctionne sans démontrer un gain fonctionnel, de fiabilité ou de maintenance. Ne crée pas un « nouveau MVP » qui supprime des fonctionnalités déjà présentes. Ne marque jamais « PASS » sans exécution et preuve.

Le succès est un **produit Android cohérent**, pas une liste de fonctions simulées ou seulement couvertes par des tests unitaires. L'utilisateur doit pouvoir quotidiennement utiliser l'accueil, démarrer/planifier un focus, voir une notification de fin de phase, suivre ses habitudes, configurer les applications observées, recevoir une friction douce pour une distraction détectée, ouvrir un contenu YouTube dans une application compatible, consulter ses informations locales et garder le contrôle sur chaque permission.

## 1. Préparation obligatoire (R0) : état Git et environnement

1. Lire : `README.md`, `AGENTS.md`, `docs/AUDIT_CIBLE.md`, `docs/VALIDATION_VM.md`, `docs/lots/S5-revue.md`, `docs/lots/S6-rapport.md`, `docs/01-produit.md`, `docs/02-architecture.md`, `docs/06-direction-visuelle.md`, `docs/08-phase1-preanalyse.md`, `docs/09-plan-lots-et-recette-vm.md` et ce prompt. La spécification historique `docs/00-specification-initiale.md` est une **référence**, pas une instruction de réimplémenter tout d'un coup. Appliquer l'intention récente explicite de réutiliser du code OSS quand c'est avantageux.
2. Faire `git status`, noter branche, SHA, modifications non commitées, remote. **Ne rien écraser.** Créer une branche de travail `feat/atlas-v4-android17` (ou équivalent si déjà occupée), un baseline commit seulement si nécessaire et autorisé ; ne pas réécrire `main`, ne pas `push --force`, ne pas effacer des données Android ni le seul AVD.
3. Android Studio : `/opt/android-studio/bin`. Découvrir effectivement SDK, JDK, `adb`, `sdkmanager`, `avdmanager`, émulateur, version d'Android, niveau SDK (`SDK_INT`) **et API complète/mineure (`SDK_INT_FULL` si accessible)**, ABI, nom AVD, espace disque, KVM. Ne pas déduire qu'un AVD 37.2 implique `compileSdk=37.2` : ce sont deux paramètres différents. Relever `compileSdk`, `targetSdk` et versions Gradle/AGP/Kotlin du projet. Vérifier les exigences du SDK Android 17 avant toute migration.
4. Le projet audit initial avait `compileSdk=35`, `targetSdk=35`, AGP 8.7.3, Kotlin 2.0.21, Gradle 8.9. Ce sont les **valeurs précédentes**, pas une prescription V4. Le SDK 37 nécessite un AGP compatible (documentation Android 17 : AGP >= 8.9.0-rc01), et une compilation spécifique 37.2 peut nécessiter la syntaxe `compileSdk { version = release(37) { minorApiLevel = 2 } }` avec AGP adapté. N'augmenter `targetSdk` à 37 qu'après audit des changements de comportement et tests ; documenter les raisons en cas de maintien temporaire de target 35/36. Ne pas forcer un changement de version si l'AVD peut exécuter l'APK existante et qu'il compromet la stabilité.
5. Exécuter le baseline : `./scripts/build-android.sh`, `cd android && ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`, `cd backend && python -m pytest -q` (adapter au venv et versions réellement disponibles) et tests connectés si l'AVD fonctionne. Les précédents rapports déclarent 51 tests JVM Android, 10 instrumentés et 14 backend réussis **sur l'ancien environnement** : ils ne remplacent pas les tests sur Android 17.
6. Capturer **avant** toute modification les principaux écrans Android existants, logs et permissions, comparer visuellement à `docs/captures/vm1c-*`. Établir `docs/v4/00-baseline-android17.md` avec résultats et limites.
7. Inspecter les dossiers de l'arbre réel avant de fixer les chemins autorisés aux sous-agents. Réévaluer les décisions si le code a évolué depuis le SHA initial. Ne pas perdre de temps à auditer globalement tous les fichiers historiques.

**Références officielles Android :**
- https://developer.android.com/about/versions/17/setup-sdk
- https://developer.android.com/about/versions/17/behavior-changes-17
- https://developer.android.com/about/versions/17/behavior-changes-all
- https://developer.android.com/build (SDK mineurs)
- https://developer.android.com/reference/android/app/usage/UsageStatsManager

## 2. Constats déjà établis : à corriger, pas à redécouvrir intégralement

L'audit précédent, fondé sur le dépôt `4cc8542`, établit les éléments suivants (revalider uniquement en présence de modifications récentes) :

- `android/app/src/main/java/dev/atlas/coach/focus/FocusEngine.kt` n'a **pas de limite de cycles totale** ; `longBreakEveryCycles` contrôle la pause longue, pas la fin. L'UI peut afficher « Cycle 4 / 4 » indéfiniment ; exiger une sémantique explicite `totalWorkCycles`, `longBreakEveryCycles`, achèvement automatique ou mode continu choisi. Migrer l'état persisté sans le perdre.
- Le focus ticke essentiellement pendant la visibilité de l'écran ; **absence de notification fiable** en fin de phase hors application et de planification locale active. Le calcul de durée restaurée fonctionne déjà selon les rapports, à préserver et mieux tester sur reboot/horloge.
- `android/app/src/main/java/dev/atlas/coach/rules/RuleModels.kt` définit `LocalPolicy` avec des exclusions (`vitalPackageNames`) mais **sans allowlist explicite des apps réellement surveillées**. Avant toute observation réelle, implémenter une sélection consentie par package, un denylist de sécurité non modifiable, une révocation immédiate, la minimisation/rétention des données et des tests.
- Le simulateur `AppEventSimulator` ne doit pas être confondu avec de l'observation Android. Rien ne surveille les applications tierces ni ne fournit actuellement de friction réelle au moment de leur lancement.
- Le launcher facultatif a un rôle `HOME`, un catalogue d'activités `MAIN/LAUNCHER`, une UI textuelle inspirée d'Olauncher, épingles et sortie vers les réglages ; il ne forme pas encore une expérience launcher raffinée et testée comme HOME par défaut sur Android 17.
- `AtlasController.kt` (~677 lignes) et `AtlasApp.kt` (~528 lignes) mélangent navigation, persistance, logique métier et UI ; les découpler par étapes sans refonte totale.
- `backend/` comprend FastAPI + SQLite + auth bearer, contrat et policy + AgentAdapter mock. **L'application Android ne lui parle pas**. Les endpoints mock ne doivent pas être assimilés à un agent réel.
- La bibliothèque contient des **liens YouTube de recherche statiques**, sans vraie curation ni pilotage ReVanced.
- `docs/02-architecture.md` décrit des modules `core-domain`, Room, DataStore, WorkManager et un backend connecté **cibles**, pas nécessairement le code livré. Aligner les docs sur le réalisé et l'évolutif.
- Déjà positifs à conserver : tests, récupération partielle des corruptions de stockage, historique, force d'habitudes Loop, modèles Pomodoro Reef, HOME facultatif, interface à paramètres révocables, absence de permissions intrusives non nécessaires, backend mock borné.

## 3. Références open source : réévaluation utile et vérifiable (R1)

**Ne pas exclure un projet entier parce qu'il est gros ou moins simple à intégrer.** Inversement ne pas importer un dépôt entier par principe. Pour chaque référence, choisir **copier/adopter un sous-module**, **forker une application**, **intégrer par IPC/intent/API**, **prendre le design seulement**, ou **écarter avec raison démontrée**, après un *spike* technique ciblé.

Dépôts et révisions déjà documentées, à vérifier au checkout sans substituer aveuglément HEAD :

1. **Reef** — https://github.com/aload0/Reef — référence précédente `044358fb26bf11aa85dddadf919106ca4053d926`. Examiner réellement `timer/`, `data/FocusStats.kt`, `routine/`, `services/routines/`, `accessibility/UsageTracker.kt`, `accessibility/BlockerService.kt`, `MindfulLaunchActivity.kt`. Priorité à l'algorithme Pomodoro/notifications, déclenchement routines, suivi d'usage et **friction de lancement**. Vérifier dépendances, permissions, effets secondaires, compatibilité API 37, couplage. Des bugs historiques liés aux notifications/bloqueurs existent : ne pas copier un service sans tests. Choisir adaptation limitée ou composant isolé, derrière interfaces, sans collecte d'écran ni comportement intrusif implicite.
2. **Olauncher** — https://github.com/tanujnotes/Olauncher — `f8e8d6497c457734c7a3171d36374157678e1f33`. Ne plus le réduire à une simple palette de couleurs : analyser ergonomie HOME, liste/tri/recherche, gestes, favoris, paramétrage, alternatives d'accueil et cycle de vie du launcher. Réutiliser du code ergonomique sélectionné si cela évite de réinventer, avec licences et attribution ; ne pas obligatoirement porter tous les fragments View dans Compose.
3. **Lawnchair / Launcher3** — https://github.com/LawnchairLauncher/lawnchair — `fc100ae9095a83b8b51c447a3b0a4ce2b399ad59`. Le refus précédent reposait surtout sur la complexité. Réaliser un **spike borné** sur les problèmes qu'ATLAS risque de rencontrer comme vrai HOME : liste d'apps, raccourcis, widgets, recents/gestes, profils multiples, stabilité et paramètres système. Comparer le coût réel d'une adoption modulaire/fork à un launcher maison. **Ne pas porter Quickstep/privileged APIs sans nécessité**. En l'absence de valeur nette pour la V4, conserver le HOME minimal ATLAS et tracer la décision, mais ne pas écarter sans vérification.
4. **Loop / uhabits** — https://github.com/iSoron/uhabits — `c06b682f6b7b9c09eb278a06ffb57a42f0cddd38`. Le projet reprend déjà score/fréquence/CSV. Examiner aussi **plannings flexibles, séries, rappels et widgets** ; intégrer les éléments réellement utiles à la création et au suivi des habitudes coach, ou choisir une interop séparée vérifiable. Ne pas introduire un second stockage des mêmes habitudes.
5. **ReVanced** — https://github.com/orgs/ReVanced/repositories et https://github.com/ReVanced/revanced-manager ; vérifier les emplacements actuels officiels (certains dépôts de patches sont susceptibles d'être indisponibles). **Ne pas forker ni modifier ReVanced pour atteindre les objectifs V4**. Retenir intents Android de liens vidéo, choix explicite du lecteur, fallback navigateur et tests d'absence du lecteur. Toute action interne prétendue doit reposer sur une API stable vérifiée, sinon noter « non pris en charge ».
6. **Tasker et outils de launcher/automatisation** : examiner une intégration externe si un protocole public apporte une vraie valeur et un test réalisable ; ne pas créer de dépendance obligatoire à un APK tiers. **Miniflux/RSS** est une piste backend de curation ultérieure : aucune promesse de contrôle YouTube ou de contenu IA sans source.

**Sortie R1 :** `docs/v4/01-reemploi-open-source.md`, tableau avec URL, SHA, fichiers précis, dépendances, fonctions réellement récupérables, preuve de compilation/spike, choix, coût du maintien, pourquoi garder/écarter. Échéance : ne pas bloquer les autres lots pour un audit interminable. Les clones se font dans `_research/` ou `references/repos/` ignoré par Git, épinglés au SHA. Adapter du code existant **lorsqu'il est exploitable** plutôt que réécrire. Pour les fichiers incorporés, conserver provenance, auteur, licence et modifications dans `docs/AUDIT_CIBLE.md` et `THIRD_PARTY_NOTICES.md`. Le dépôt GitHub est public : ne pas supposer que l'usage privé permet d'ignorer les obligations liées à la publication/distribution éventuelle. Mettre en ordre `LICENSE` et les notices si nécessaire, sans détourner la mission.

## 4. Travaux fonctionnels à réaliser

### R2 — Fiabilité de la concentration et Pomodoro utilisable

- Faire évoluer `FocusPlan`, `FocusSession`, `FocusEngine`, état persisté et UI pour distinguer **cycles totaux**, rythme des pauses et mode continu. Achever une session finie automatiquement ; conserver la possibilité de terminer/annuler et vérifier le lien habitude uniquement après la condition choisie. Tests aux frontières du dernier cycle, pause longue, interruption, annulation, redémarrage.
- Réutiliser, si utile, le code de minuterie/calendrier de Reef. Préserver une **source de vérité unique**, éviter deux timers concurrents. Restaurer après Activity recreation, force-stop, verrouillage, processus tué, changement d'heure et redémarrage (suivant garanties Android réelles). Pas de boucle active CPU/réécriture synchrone d'état toutes les secondes en arrière-plan.
- Notifier **début/fin de phase et prochain travail** avec notification channel, permission `POST_NOTIFICATIONS` sur versions concernées, action utilisateur et PendingIntents sûrs. Pour les alarmes planifiées, étudier AlarmManager / WorkManager selon exigences de ponctualité et permissions disponibles. WorkManager n'est pas une horloge à la seconde. Si permission refusée, fonctionnement dégradé documenté sans mentir.
- **Routines** : créer/modifier/supprimer/activer/désactiver horaires et jours, calendrier local, prochain déclenchement, confirmation utilisateur ou démarrage automatique seulement si mandat explicite préautorisé. Une notification de proposition est une bonne alternative à un lancement d'Activity en arrière-plan interdit. Toutes les sessions programmées sont révocables et consultables.
- Lier de manière cohérente session, directive mock, projet facultatif, habitude et journal. Éviter comptage en double.

### R3 — Observation consentie et friction réelle, sans agents IA

- Créer un `AppUsageObserver`/`UsageEventSource` interchangeable : `Simulated` (test), `UsageStats` réel (opt-in), éventuellement provider expérimental isolé basé sur APIs d'accessibilité si **justifié** et si conforme aux contraintes Android 17 / politique d'utilisation. Priorité au chemin natif `UsageStatsManager` avec événements disponibles. Ne pas faire croire à un blocage instantané avec une API polling best-effort.
- Construire un parcours de permission clair pour *Accès à l'utilisation* (réglages système, contrôle du statut réel, refus, perte de permission, révocation). À l'activation, l'utilisateur choisit explicitement des packages **allowlist** et la finalité. Les apps essentielles (téléphone, urgence, paramètres, System UI, HOME, services sécurité, gestion d'accès) ne peuvent pas être bloquées ; surveiller ces apps est interdit par défaut. Désactiver l'observation doit stopper la collecte, pas seulement masquer l'UI.
- Convertir les événements d'ouverture réels en événements locaux dédupliqués. Filtrer initialisation, répétitions, changements de configuration, retours depuis notifications et double comptage. Ne collecter ni texte d'écran, ni contenu des notifications, ni URL consultées, ni comptes personnels. Retention bornée et suppression/export.
- Brancher les ouvertures sur `RuleEvaluator` avec règles configurables (seuil, fenêtre, cooldown, heures calmes, permissions, exceptions et pause). Ajouter une **friction douce réellement visible** : notification contextualisée, écran d'intention seulement lorsque permis et explicitement déclenché par l'utilisateur/une API autorisée ; pas d'overlay intrusif automatique ni de contournement des restrictions de background activity launch.
- **Étudier Reef `MindfulLaunchActivity` et son blocage**. Si son adaptation est sûre, stable et véritablement compatible API 37, isoler derrière un provider **expérimental désactivé par défaut**, sans root/Device Owner/Device Admin/fermeture forcée, avec interrupteur d'arrêt d'urgence et tests. Si ce n'est pas fiable, livrer `UsageStats + notifications + friction volontaire` et une décision technique honnête. Ne pas prétendre que le mode strict est réalisé.
- Sur l'AVD, installer une **petite app de test de distraction** (fixture distincte, code local, non distribuée) et déclencher plusieurs ouvertures réelles pour vérifier l'observateur, les seuils, la déduplication, les notifications et la révocation. Ne pas se contenter de `AppEventSimulator`.

### R4 — Launcher / interface réellement raffinés

- Conserver une **seule expérience principale** : l'accueil HOME facultatif inspiré d'Olauncher et le coach. L'écran d'accueil ne doit plus être une liste de 10 gros boutons équivalents. Prévoir une structure claire : (a) directive prioritaire, (b) 1 action principale, (c) état focus / prochaine session, (d) accès léger aux habitudes, (e) raccourcis d'apps, (f) bibliothèque/journal/paramètres dans navigation secondaire. La **pause d'urgence** demeure toujours accessible mais ne doit pas occuper la place visuelle de l'action principale sur tous les écrans.
- Source de design : Olauncher, simplicité typographique et faible charge cognitive. Conserver une identité ATLAS distincte (nom/icône uniques) : fond sobre, typographie cohérente, échelles et espacements, états clairs. Une carte n'est nécessaire que si elle sert une information. Diminuer les titres géants et le nombre de `OutlinedButton` simultanés. Mode sombre et mode clair ou palette adaptable. Éviter la reproduction de l'UI complète de Reef.
- Fonctionnalités HOME : liste d'apps visible et utilisable, favoris persistants, recherche/filtrage si nécessaire, geste non conflictuel, paramètres d'accueil, affichage d'heure robuste, compatibilité insets/status/nav bars, retour/Back et sortie sûre vers un autre HOME. Explorer widgets/raccourcis seulement si le spike Lawnchair montre un gain net sans fragiliser VM-1.
- Paramètres : séparer « usage quotidien » et « diagnostic avancé ». Cacher simulateur/debug derrière section développeur. Présenter clairement ce qui est **actif**, **simulé**, **autorisé**, **impossible**. Aucun réglage factice. Ajouter accueil/init onboarding pour les permissions sans incitation trompeuse.
- Français soigné, états vide/chargement/erreur, taille des zones tactiles >= 48 dp, contrastes, lecteurs d'écran, mise à l'échelle des polices, clavier, paysage si possible. Tests Compose et **captures réelles sur l'AVD API 37.2** pour accueil, launcher, focus actif/pause/terminé, habitudes, routines, permissions, intervention et paramètres. Inspecter les images, pas seulement `screencap`.
- Réduire `AtlasApp.kt` et `AtlasController.kt` par refactorings mesurés, avec state holders/ViewModels/modules fonctionnels et injections testables. Ne pas créer 15 modules Gradle prématurément.

### R5 — Habitudes et contenus

- Garder les adaptations Loop vérifiées (fréquence, force, CSV). Ajouter planification flexible exploitable en UI (quotidien, certains jours, n fois/semaine, éventuellement jours alternés si modèle compatible), rappels révocables, check-in et annulation, historique et statistiques compréhensibles, conversion propre en événements du coach. Vérifier les calculs sur fuseaux horaires, dates futures, fuseau changé, série et non-échéances.
- Rappels d'habitudes avec permissions/notifs et sans dépendance IA. Pas de double check-in automatique : l'utilisateur valide sauf règle explicite préautorisée.
- Bibliothèque : permettre l'ajout manuel de liens vidéo YouTube spécifiques (et suppression/épingle), ouverture par `ACTION_VIEW` avec choix de lecteur installé, fallback navigateur, gestion d'échec. Conserver ReVanced comme lecteur indépendant ; **ne pas patcher** l'APK ni piloter ses réglages internes sans interface stable. Séparer liens sélectionnés et simples recherches génériques. La curation intelligente viendra plus tard.
- Traçabilité des ouvertures utiles sans collecter l'historique personnel de YouTube/ReVanced.

### R6 — Connexion Android ↔ ATLAS Core mock (sans OpenClaw)

- Mettre en place un `CoachGateway` et `SyncGateway`/`EventOutbox` interchangeables côté Android : `LocalMock` (par défaut, hors ligne) et `RemoteBackend` **facultatif** vers FastAPI existant, sans casser les données locales. Formaliser les contrats JSON existants, cohérence DTO / dates UTC / UUID / idempotence, versionnement et erreurs.
- Synchroniser **uniquement** les données expressément activées pour le backend, avec file locale durable, ack serveur, backoff, reprise réseau et absence de duplication. Ne jamais envoyer les détails privés d'applications par défaut ; distinguer données locales et données partageables. Android garde la source de vérité des permissions, de l'état Focus et des décisions d'actions.
- Pour l'émulateur, backend de développement sur hôte (souvent `10.0.2.2`) : vérifier l'adresse réellement accessible, les règles de cleartext *uniquement pour debug si nécessaire* et, avec `targetSdk=37`, la nouvelle permission réseau local `ACCESS_LOCAL_NETWORK` si applicable. Aucune configuration réseau dangereusement permissive en release. Auth Bearer stockée sans secret hardcodé dans Git, configuration explicite ; pas d'ouverture publique du backend.
- Tests API/mock indépendants, tests contractuels Android/backend et **preuve réelle** de `Android → backend → réponse mock → Android` avec serveur démarré, puis déconnexion et reprise hors ligne. Le mock du backend ne doit pas être présenté comme de l'IA.
- Préparer l'interface future `AgentAdapter` / `CoachDecisionProvider` / vérification de mandat : schémas d'actions autorisées, validation locale, journal des propositions, simulation, permissions révocables. **Ne connecter aucun LLM/OpenClaw pour cette V4** et ne fournir aucun shell/ADB/API système à un agent.

### R7 — Qualité, sécurité, livraison et validation Android 17

- Refactorer uniquement là où les tests existent et les changements diminuent le couplage. Corriger les vrais avertissements/crahs/lags observés. Ajouter versionnement/migration des données, défense contre états corrompus, export/suppression et scénarios permission révoquée. Mesurer les problèmes plutôt que remplacer tout `SharedPreferences` d'office ; migrer vers Room/DataStore **si les besoins de requêtes/volumétrie/outbox l'exigent** avec migration réversible et tests.
- Mettre en place CI GitHub Actions pour compilation, tests JVM, lint et pytest ; ne pas prétendre que la CI possède un AVD 37.2 si ce n'est pas le cas. Faire les tests instrumentés **localement sur l'AVD disponible**.
- Pour Android 17, auditer permissions d'usage, notifications, alarmes, activités en arrière-plan, `ACCESS_LOCAL_NETWORK` si besoin, HOME/RoleManager, tailles adaptatives, cycle de vie, comportement reboot/force-stop, batterie, privacy et crashs. L'AVD peut ne pas reproduire certaines restrictions d'un Pixel physique : noter explicitement les limites.
- Rapports de tests : horodatage, SHA commit + SHA APK, versions complètes AGP/SDK, ligne de commande, tests pass/fail, `adb shell dumpsys`, `logcat` filtré et contexte des erreurs, état des permissions, captures non tronquées, feuille de parcours manuel. Ne pas marquer test PASS si simplement compilé.
- Vérification de sécurité : jamais de bloqueur de dialer/réglages/urgence ni de suppression de l'accès à HOME, pas d'activation silencieuse d'accessibilité/usage stats, pas de secrets dans Git/logs, aucun envoi externe d'historique d'apps par défaut. Retrait du consentement testé réellement.
- Réconcilier README/docs et fonctionnalités livrées ; distinguer `réalisé`, `expérimental`, `simulé`, `bloqué par API Android`, `non testé`, `reporté OpenClaw`. Publier notes de modification, instructions reproduction et backlog clair. Réévaluer licences GPL/notice du **dépôt public** sans y consacrer l'essentiel du cycle.

## 5. Ordonnancement / délégation Nimbalyst

**Obligation d'orchestration :** vérifier la disponibilité et la signature réelle des outils MCP Nimbalyst ; utiliser `spawn_session` pour déléguer les lots avec, si acceptés par la signature et disponibles :

- `model: "openai-codex:gpt-6-luna"`
- `effortLevel: "xhigh"`
- `notifyOnComplete: true`

Recueillir par `get_session_status`, `get_session_result`, `list_spawned_sessions`, `send_prompt` et les moyens Nimbalyst pertinents les résultats de chaque session. Ne pas feindre une délégation si ces outils ne sont pas disponibles. Une seule personne (l'orchestrateur ou une session d'intégration dédiée) contrôle les fichiers transversaux : `android/app/build.gradle.kts`, manifests, `MainActivity.kt`, `AtlasApp.kt`, `AtlasController.kt`, `contracts/**` et navigation.

**Décomposition recommandée, limitée à 2 sous-sessions d'implémentation simultanées**, après R0 :

| Lot | Dépend de | Périmètre de code (autorité exclusive sauf accord orchestrateur) | Validation minimale |
|---|---|---|---|
| A — baseline / SDK Android 17 | aucun | scripts, Gradle, tests/config, `docs/v4/00*`; coordonne mutations Gradle | build + test sur unique AVD ; versions notées |
| B — audit ciblé OSS | A pour environnement, indépendant en lecture | `_research/` + `docs/v4/01*`, recommandations uniquement | SHAs/fichiers et spikes démontrés |
| C — moteur Focus/routines | A+B | `focus/**`, `routines/**`, tests associés ; proposer sans toucher UI centrale | fin auto cycle, pauses, reprise et tests de reprise |
| D — observation/règles | A+B | `rules/**` et **nouveau** `usage/**`, tests/fixture debug dédiés | ouverture réelle → règle ; consentement/arrêt |
| E — habitudes/contenus | A+B | `habits/**`, `content/**`, tests associés | planification, stats, vidéo réelle/ouverture fallback |
| F — backend/contrats | A | `backend/**`, propositions de `contracts/**`, nouveaux adapters Android sous `sync/**` | test local HTTP et hors ligne |
| G — interface/launcher | C/D/E stabilisés ou API établies | `launcher/**`, nouveaux composables dédiés ; fichiers UI centraux **réservés à l'intégrateur** | captures Android 17 et tests UI |
| H — intégration/recette | C à G | `app/**`, Gradle/manifeste, liens inter-modules, CI/docs globaux | APK + tests + scénarios manuels complets |

Avant chaque `spawn_session`, rédiger un brief propre : objectif observable, dépendances, fichiers autorisés/interdits, API à exposer, critères de recette, exigences Git/commit et chemin du rapport `docs/v4/lots/<id>.md`. Pour les travaux parallèles, préférer des worktrees si Git + Nimbalyst le permettent ; sinon changements de dossiers non superposés et intégration séquentielle, sans modification simultanée de mêmes fichiers. Les dépendances doivent être explicitement gelées avant de commencer le lot G ; éviter une course où les sous-agents réécrivent des interfaces.

L'orchestrateur **n'est pas uniquement un lanceur de sessions** : il compare les rapports au diff réel, demande corrections, intègre, résout conflits, relance tests. Le lot A et la validation d'intégration sont des portes bloquantes. Si un module expérimental non essentiel est impossible sur API 37.2, documenter la limite et poursuivre les autres lots ; ne pas déguiser un mock en fonction finale.

## 6. Contrat de recette finale : tests obligatoires

Sur **le seul émulateur Android 17/API 37.2 existant**, pas sur un AVD recréé :

1. `adb devices`, version Android/API complète, identité de l'AVD et permission status. `:app:testDebugUnitTest`, `:app:assembleDebug`, `:app:lintDebug`, `:app:connectedDebugAndroidTest` réussissent après les derniers commits ; pytest backend réussit. Capturer XML des rapports et sorties réelles.
2. Installation APK debug, démarrage direct, rotation/recréation d'Activity, arrière-plan/retour, force-stop/reprise, redémarrage d'AVD **sans wipe destructif**, aucun crash ni ANR relevant dans logcat ; conservation des habitudes, épingles et focus selon règles explicites.
3. Pomodoro 2 cycles rapide : vérifier travail→pause→travail→**terminé automatiquement**, notification fin, compteur sans ambiguïté ; tester aussi cycle long, pause, annulation, refus d'autorisation notifications, réglage du nombre de cycles et persistance.
4. HOME : activation par action explicite, vrai écran HOME sur API 37.2, liste/épingle/ouverture d'app, bouton retour aux paramètres et rétablissement launcher système. Conserver la possibilité de revenir à l'AVD sans HOME ATLAS après tests.
5. Routines horaires + rappels : séquence planifiée contrôlée (horloge/instrumentation), rien d'exécuté sans consentement ; test de désactivation immédiate et de persistance.
6. Observation : permission refusée→aucune collecte ; consentement + allowlist→ouverture réelle d'app fixture 4 fois→règle/frottement/notification ; app hors allowlist→rien ; révocation→arrêt collecte ; apps vitales/exemptées→aucune interception. Documenter la latence réelle de détection et les limites. Si path expérimental d'accessibilité, tests séparés et flag désactivé par défaut.
7. Habitudes : création, rappel, contrôle de calendrier (quotidien + hebdomadaire), check-in/retrait, score Loop et export ; confirmation de lien après fin de focus ; pas de doublon après relance.
8. Contenus : lien vidéo précis vers app installée, fallback vers navigateur, aucun handler, validation de lien et tests absence ReVanced sans le supposer installé ; ajout/suppression manuel persisté.
9. Backend : mode mock local toujours disponible ; serveur lancé sur machine hôte, Android connecté depuis AVD si configuré, événements idempotents / auth / synchronisation et retour offline ; permission réseau local Android 17 testée si concernée. Si problème local réseau non résolu, noter FAIL/BLOCKED et ne pas prétendre intégration réussie.
10. UX : captures propres avant/après pour tous les écrans, test d'agrandissement de police, contrastes, petits/grands écrans en preview si possible, vraie inspection visuelle. UI opérationnelle sans simulateur visible au premier plan.
11. Audit permission via manifeste construit / `aapt` ou `apkanalyzer`, justifier chaque nouvelle permission et respecter l'arrêt d'urgence.

**Livrables à mettre dans le dépôt :**
- `docs/v4/00-baseline-android17.md`, `01-reemploi-open-source.md`, `02-architecture-realisee.md`, `03-recette-android17.md`, `04-defauts-et-limites.md`, `05-suite-openclaw-future.md` (**contrats seulement, zéro intégration agent**), `lots/*.md`.
- `docs/v4/captures/` : captures ADB nommées, avant/après sans données privées ; éventuellement vidéo courte de parcours si outils existants.
- `README.md` actualisé (installation, VM Android 17 et usage quotidien), `THIRD_PARTY_NOTICES.md`/licence si requis, tests automatisés et workflow de CI.
- APK debug construite **localement** sous `android/app/build/outputs/apk/debug/` (ne pas ajouter les binaires au Git sans décision), commandes pour installation ; SHA-256 APK dans rapport.
- `git status` propre si possible, commits logiques sur branche dédiée, récapitulatif fichiers modifiés. Ne pas fusionner `main` ni faire `push --force`. Pousser la branche si accès Git autorisé et sans secret ; signaler l'URL du PR possible, sans fusion automatique.

## 7. Définition de « terminé » / interdictions de faux positif

V4 est achevée **uniquement si** le launcher, le focus à cycles finis, les habitudes, routines et contenus sont utilisables sur Android 17, l'observation réelle et la friction douce fonctionnent de manière consentie **ou sont clairement marquées bloquées avec preuve et solution de repli**, le backend mock peut être testé sans IA, les tests documentés passent et l'interface est visiblement améliorée. Les capacités écartées doivent être étiquetées ; ne pas cocher des fonctionnalités non livrées dans le README.

**Ne pas :** intégrer OpenClaw, faire de nouvelles promesses de contrôle YouTube/ReVanced, rooter l'AVD, installer un Device Owner, activer silencieusement des permissions d'accessibilité ou d'usage, lancer des activités intempestives, bloquer les applications vitales, effacer l'unique AVD, publier une APK, envoyer du code ou des données à un tiers, réécrire tout le projet, inventer des logs, annoncer une CI réussie sans run, ni attendre la validation d'un agent IA pour terminer les composants purement Android.

**Exécute maintenant R0**, puis délègue R1 et les travaux par lots. À chaque porte R0 / R2-R5 / R7, fournis un bilan factuel (PASS/FAIL/BLOCKED, preuves, fichiers, blocages) et poursuis les étapes possibles sans attendre de nouvelles consignes, sauf choix irréversible, secret manquant, permission utilisateur nécessaire sur VM ou obstacle réellement bloquant.
