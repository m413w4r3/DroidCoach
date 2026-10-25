# ATLAS / DroidCoach

**Un launcher Android minimaliste et un coach IA personnel, contextuel et volontairement directif.**

ATLAS cherche à transformer les objectifs choisis par son utilisateur en **actions réalisables**, à organiser ses journées, à limiter les automatismes numériques et à apprendre de ses décisions et de ses retours. Il doit pouvoir dire « voici la prochaine action utile », expliquer pourquoi, accepter une objection, réorganiser le programme et accompagner son exécution.

Le coach est **ferme mais contestable** : chaque pouvoir est défini dans un mandat explicite, borné, vérifiable et révocable. L'IA propose ; ATLAS et les services autorisés exécutent uniquement des actions validées. Le contrôle des fonctions essentielles du téléphone et une pause d'urgence restent disponibles sans Internet.

**État du projet (25 octobre 2026) :** **V6.2 clôturée** (`GO_WITH_LIMITATIONS`) ; **V6.3 en conception**, et non livrée. Plateforme de qualification : **Android 17 / API 37, Pixel physique et AVD**. Dépôt : [DroidCoach](https://github.com/m413w4r3/DroidCoach).

## L'expérience recherchée

ATLAS ne doit pas devenir un tableau de bord de productivité ni un simple chatbot. Sur le téléphone, il relie **intention → journée planifiée → action → réalisation → retour utilisateur → adaptation du plan**.

- **Planifier** : partir d'objectifs et de priorités, des engagements du calendrier, des habitudes, de l'énergie et des plages de repos consenties ; proposer un programme réaliste plutôt que remplir mécaniquement chaque créneau.
- **Faire agir** : afficher une seule prochaine action pertinente sur HOME, lancer un Focus associé à un objectif, suivre son résultat et proposer la suite.
- **Négocier** : répondre à « Pourquoi ? », « Je ne peux pas », « Changeons d'activité », « Reporter » ou « Pause ». Une explication (retard de train, imprévu, fatigue déclarée, conflit d'horaire) peut conduire à un **nouveau plan**, sans culpabilisation.
- **Observer avec mesure** : prendre en compte, après autorisations séparées, les applications sélectionnées, la progression des objectifs, les rendez-vous et éventuellement des signaux de localisation liés à des activités prévues. Un signal de présence n'est pas une preuve d'activité accomplie.
- **Apprendre** : conserver une mémoire **progressive, lisible, corrigeable et effaçable** des objectifs, engagements, actions, réactions et préférences. Ne pas inférer silencieusement une règle permanente d'un seul comportement.
- **Soutenir l'engagement** : éviter les ouvertures réflexes d'applications par une friction locale préalablement acceptée, avec exceptions et possibilité de dérogation. Aucune escalade coercitive décidée seule par le modèle.

Exemple de journée cible : ATLAS prépare une proposition de planning, place une séance de travail et un entraînement autour des rendez-vous, rappelle la prochaine action, tient compte du Focus terminé puis, si l'utilisateur explique qu'il est retenu dans le train, propose de reporter une activité et de déplacer **seulement** les événements concernés après validation.

## Deux composants, un seul coach

**Sur Android :** launcher HOME textuel, interfaces Coach/Focus/objectifs/habitudes, état local, mandats et permissions, règles déterministes, journal des effets, accès aux apps essentielles et fonctionnement dégradé hors ligne. L'application est propriétaire de son état et de ses autorisations Android.

**Sur le PC personnel :** backend FastAPI privé et agent OpenClaw, destinés à rester disponibles au quotidien pour raisonner sur le contexte consenti, organiser les journées, dialoguer et proposer des ajustements. L'absence temporaire du serveur est signalée ; **le téléphone continue d'exécuter les décisions déjà enregistrées**, ses timers, son launcher, ses rappels locaux et les protections consenties. Une panne réseau ne vaut jamais autorisation de contourner les règles.

**Principe d'autorité :** OpenClaw n'a ni shell Android, ni droit général d'écriture dans les applications. Il émet des propositions structurées ; chaque adaptateur et vérificateur contrôle le mandat, la fraîcheur, les permissions, la cible et les effets. Les écritures sensibles exigent une présentation compréhensible et le degré d'approbation prévu par l'utilisateur. Tout changement reste traçable et, lorsque possible, annulable.

## Intégrations ciblées pour V6.3 — non livrées

### Calendrier local : [Etar Calendar](https://github.com/Etar-Group/Etar-Calendar)

Etar sera l'interface de consultation du calendrier. Etar exploite le **stockage des calendriers Android** ; le chemin d'interopérabilité à étudier est donc `CalendarContract` (ou les intents Android), **pas** la manipulation de la base privée ni l'automatisation de l'interface d'Etar.

ATLAS doit pouvoir, après permission et mandat dédiés :
- lire les créneaux pertinents des calendriers sélectionnés ;
- **proposer** de créer, déplacer ou modifier un événement après une conversation ;
- présenter le calendrier concerné, l'ancien et le nouvel horaire, les conflits et les conséquences avant toute écriture ;
- respecter récurrences, fuseaux horaires, changements d'heure, rappels, permissions de chaque calendrier, doublons et possibilité de revenir en arrière.

L'objectif initial est **un calendrier local contrôlé par l'utilisateur**, sans synchronisation cloud obligatoire. La compatibilité réelle avec le calendrier local choisi et les cas d'écriture seront qualifiés sur Android 17.

### Entraînement : [wger](https://github.com/wger-project/wger)

wger est la **source de vérité** envisagée pour les programmes d'entraînement, exercices, séries et journaux de séances. wger expose une API REST ; l'éventuelle utilisation de son [serveur MCP](https://github.com/wger-project/mcp-server) reste à évaluer, ce dernier étant annoncé en cours de développement.

Après discussion et autorisations appropriées, ATLAS doit pouvoir :
- consulter le programme et proposer/créer/ajuster des séances dans wger ;
- organiser des créneaux d'entraînement cohérents avec le calendrier ;
- exploiter les séries réellement enregistrées, répétitions, charges, RIR et impressions déclarées ;
- proposer une **progression graduelle conditionnée par les performances enregistrées**, sans prétendre garantir les résultats ou remplacer l'avis d'un professionnel ;
- demander si la séance a eu lieu et comment elle s'est passée, puis proposer des ajustements soumis à validation.

Être localisé près d'une salle, ou y avoir un rendez-vous, **ne prouve pas** qu'une séance a été réalisée. Une validation fiable dépend du journal wger et/ou d'une confirmation explicite.

### Localisation contextuelle et déplacements

Avec un **consentement distinct**, ATLAS pourra tenir compte d'un départ en train, d'une arrivée au travail ou d'un déplacement vers la salle pour contextualiser les propositions et ajuster les horaires.

**Cible : activation bornée par une activité prévue, lorsque c'est techniquement et réglementairement faisable sur Android 17**, avec arrêt explicite à l'échéance, possibilité de refus et mécanisme de secours si la permission manque. Ne pas installer une collecte GPS continue par défaut. Les lieux/zones ne seront configurés et partagés que sur choix de l'utilisateur ; aucune trajectoire brute transmise automatiquement à OpenClaw. La localisation/mobilité V6.2 reste désactivée par défaut tant que sa recette n'est pas close ([issue #1](https://github.com/m413w4r3/DroidCoach/issues/1)).

### Friction volontaire contre les distractions

**Premier niveau prévu en V6.3 :** sur les applications choisies, intercaler une intention, un délai ou une confirmation dans les ouvertures effectuées **depuis le launcher ATLAS** ; les règles, exceptions et pauses sont locales.

**Étude séparée :** prototype isolé de détection/interruption au premier plan sur Android (accessibilité éventuelle), seulement après examen des permissions, de la sécurité, des contraintes de distribution et des tests sur appareil de test. Aucun blocage global ni contrôle arbitraire confié à OpenClaw.

## Ce qui est réellement livré en V6.2

- Launcher HOME Compose textuel (favoris, tiroir/recherche, directive), avec choix explicite du rôle HOME et retour au launcher précédent.
- UI noir/ivoire à identité éditoriale et discrète gravure, inspirée de la sobriété d'[Olauncher](https://github.com/tanujnotes/Olauncher), sans copier son identité.
- Objectifs locaux, habitudes avec check-ins et export, Focus/Pomodoro avec pauses, reprise persistée, lien objectif/session et progression non dupliquée.
- Notifications et routines facultatives ; observation d'applications via `UsageStatsManager` sur autorisation, **best effort**, sans interception ni blocage.
- Backend FastAPI facultatif, adaptateur OpenClaw opérationnel, catégories de contexte consenties, propositions structurées, mandat local, vérification au moment des effets, journal idempotent et révocation.
- Pause d'urgence et fonctions essentielles locales sans connexion.

**V6.2 ne possède pas encore** la boucle de planification quotidienne négociée, l'édition calendrier Etar, l'édition de wger, la mémoire comportementale longitudinale décrite plus haut, les signaux de présence contextuels opérationnels ni la friction ferme inter-applications. Ce sont des objectifs de conception, pas des capacités annoncées comme existantes.

## Qualification et limites connues

**V6.2 : `V6.2_CLOSED / GO_WITH_LIMITATIONS` au 25 octobre 2026.** Dossier source : [rapport de clôture](docs/v6-2-3/cloture-v62/RAPPORT_CLOTURE_V62.md), [matrice Android 17](docs/v6-2-3/cloture-v62/MATRICE_ANDROID17_PIXEL.md) et [revue indépendante](docs/v6-2-3/cloture-v62/REVUE_INDEPENDANTE.md).

| Preuve | Portée |
| --- | --- |
| JVM | **339/339** sur le code final `1a6f761` |
| Android lint | **0 erreur, 47 avertissements** |
| Backend | **66 réussis, 1 ignoré** |
| Android 17 AVD | **77 PASS au run final + 1 retest ciblé PASS** après cold boot : 78 méthodes vérifiées, pas 78 PASS en un seul run |
| OpenClaw réel | Test Android instrumenté avec vrai gateway + backend sur AVD API 37, **13,817 s** ; Focus de 25 minutes **simulé par horloge accélérée** |
| Pixel physique Android 17 | HOME, mode hors ligne, Pause, notifications et progression objectif/Focus **validés historiquement** sur l'APK antérieur `8890046`, **non rejoués sur le code final** |
| Localisation/mobilité | **NOT_RUN**, désactivées par défaut ; issue #1 ouverte |

Autres limites acceptées : révocation **en cours d'une requête OpenClaw réelle** non rejouée (tests locaux de barrière réussis) ; restauration du Pixel non exécutée par décision utilisateur ; P2 de sécurité concernant la destination OpenClaw configurable et un callback tardif. Aucun service `AccessibilityService`, Device Owner, root ni blocage d'application tierce n'est livré.

La CI GitHub exécute les vérifications JVM/lint/build/backend ; les validations Android 17 instrumentées et sur Pixel sont distinctes. Les suites Android 14 ne sont plus le critère de sortie du projet. **Ne jamais exécuter des scripts Android destructifs sur un téléphone personnel sans pré-vol et autorisation.**

## Architecture, développement et documentation

- Android : Kotlin/Jetpack Compose, AGP 9.4.1, Gradle 9.6.0, JDK 21 ; `compileSdk 37.2`, `targetSdk 36`, `minSdk 26`.
- Backend : Python 3.12, FastAPI, SQLite local, adaptateur OpenClaw optionnel ; voir [backend/README.md](backend/README.md).
- Build sans téléphone depuis la racine :
  ```sh
  cd android
  ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
  ```
- Workflow d'ingénierie Paseo : [AGENTS.md](AGENTS.md), [contrat d'orchestration](.agents/WORKFLOW.md), [skills](.agents/skills/README.md). Tous les sous-agents délégués sont GPT-6 Luna XHigh.
- Documents de référence : [produit](docs/01-produit.md), [spécification initiale](docs/00-specification-initiale.md), [direction visuelle](docs/06-direction-visuelle.md), [sources tierces](THIRD_PARTY_NOTICES.md).
- Les intégrations Etar, wger et localisation doivent faire l'objet de **contrats et tests adaptés aux versions réellement installées**, plutôt que de forks globaux non justifiés.

## Prochaine étape : cadrage V6.3

Concevoir des **tranches verticales utilisables** avant le prompt d'implémentation. Arbitrages encore ouverts :
1. **Calendrier** : quel calendrier local sélectionner, permissions de lecture/écriture, mode d'approbation et gestion des conflits/récurrences ?
2. **wger** : instance et mode d'accès, API REST vs MCP expérimental, opérations autorisées, source de vérité des performances et règles d'adaptation.
3. **Localisation** : signaux minimaux nécessaires, activation liée au planning, exactitude acceptable, confidentialité et comportement sans permission.
4. **Mémoire et planification** : quels événements conserver, comment expliquer/revoir l'apprentissage, quelles replanifications nécessitent confirmation et comment tenir sans serveur.
5. **Engagement** : applications sélectionnées, intensité de la friction launcher, exceptions, protocole de dérogation et expérimentation d'accessibilité isolée.
6. **Ordre de livraison** : prioriser une journée planifiée et révisable de bout en bout, puis élargir l'intégration à wger, au contexte géographique et aux contrôles plus forts sans perdre la fiabilité Android 17.

**Aucune des intégrations V6.3 ci-dessus n'est validée par les preuves V6.2.** Les décisions techniques et les pouvoirs accordés au Coach restent à spécifier avant développement.

## Licence et crédits

Le projet est distribué sous [GNU GPL v3](LICENSE). Les adaptations et leurs provenances (notamment Reef, Olauncher et Loop) sont décrites dans [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Les dépôts tiers servent de références techniques ou d'interfaces envisagées et ne sont pas présumés intégrés à ATLAS.
