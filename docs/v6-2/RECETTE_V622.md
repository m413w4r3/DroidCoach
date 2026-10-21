# ATLAS V6.2.2 — recette S6

**Date :** 21 octobre 2026

**Branche / HEAD :** `feat/atlas-v6-2-2` / `bf647cc18a142dd0f5cd5e68b21c36d511014388`

**Statut courant :** la qualification principale S6 acceptée reste app `ce10c968…` / AndroidTest `893da1cf…` : le test ciblé passe 1/1, la suite principale passe 75/75 à 360 dp, les deux captures HOME/Coach passent 1/1 chacune, puis deux passages Gateway et le scénario hors ligne passent sur cette paire. Sur la paire ACK S5 app `7c6c41a4…` / AndroidTest `542280c3…`, les deux méthodes passent sur l'AVD API34. Le contrôle physique supplémentaire API37 passe la course de révocation ACK mais échoue de façon reproduite sur le chemin positif; le nettoyage après ce run a restauré l'état initial absent. Une tentative de reprise ensuite a été interrompue avant instrumentation; l'état des packages après cette interruption est `NOT_RECHECKED`. Ces résultats ACK ne transfèrent pas les 75 tests ni les autres scénarios à cette paire. Le test de révocation restaure le fichier SharedPreferences à son état exact. Les compléments sûrs 8/8 et la localisation restent historiques sur `0b5c…`; harnais complet, UsageStats, D3, essai physique complet et TalkBack réel restent `NOT_RUN`; l'environnement AVD Android 17 S4 reste `BLOCKED`. La qualification globale reste partielle. Voir [la preuve de restauration exacte](preuves-v622/s6-revocation-fixture-exact-restore-20261021.txt), [le run ACK API34](preuves-v622/s6-ack-api34-runtime-20261021.txt) et [le contrôle physique ACK](preuves-v622/s6-ack-physical-runtime-20261021.txt).

**Suivi ACK de la paire S5 finale :** hashes locaux app `7c6c41a4…` / AndroidTest `542280c3…` et manifeste 234 entrées vérifiés. Le registre dédié a confirmé `atlas_v622_s1_api34`; l'inventaire ADB partagé a montré un seul émulateur `emulator-5580` et une cible non-émulateur non ciblée. Les propriétés live ont confirmé AVD/API34. Les deux APK ont été installées avec `adb -s emulator-5580 install -r`, et leurs hashes extraits correspondent. La course ACK au gate de sérialisation et le chemin positif accepted→executed passent chacun `OK (1 test)`. L'AVD a ensuite été arrêté explicitement; aucun wipe/clear/uninstall ni commande sur l'appareil non-émulateur. Voir [le run ACK final](preuves-v622/s6-ack-api34-runtime-20261021.txt), le [préflight initial corrigé](preuves-v622/s6-ack-api34-shared-adb-preflight-20261021.txt), [le préflight initial 5037](preuves-v622/s6-ack-api34-final-20261021.txt) et [la tentative 5038](preuves-v622/s6-ack-api34-5038-isolation-20261021.txt). L'acceptation principale antérieure de la paire `ce10…` / `893da1…` reste distincte.

## Contrôle physique ciblé des deux méthodes ACK — 21 octobre

Après l'arrêt confirmé de l'AVD, l'inventaire ADB a identifié une seule cible physique : Pixel 9 Pro XL, API 37. Les packages `dev.atlas.coach` et `dev.atlas.coach.test` étaient absents; l'app candidate et son AndroidTest ont été installés temporairement par des commandes `adb -s` explicites, puis les hashes installés ont été vérifiés avant les tests. La course `sameClockReoptInAfterProactiveDispatchPreventsRealAckAtSerializationGate` passe 1/1. `proactiveDispatchSendsAcceptedThenExecutedThroughRealLoopbackClient` échoue avec un cas sélectionné; un rejeu de diagnostic reproduit `FAILURES!!!` / code runner `-2`. Aucun message d'assertion ou contenu privé n'a été conservé. Voir [la preuve physique expurgée](preuves-v622/s6-ack-physical-runtime-20261021.txt).

Les trois répertoires cache et trois fichiers SharedPreferences créés avec les préfixes de fixture ont été recensés par leur nom, supprimés par chemin exact, puis leur absence a été vérifiée. Aucun enregistrement actif de notification du package n'a été trouvé avant ou après retrait des packages. Les deux packages sont absents à nouveau; aucun réglage ou permission n'a changé, et aucun `pm clear`, wipe ou reset n'a été utilisé. Ce contrôle ne constitue pas une recette physique complète, ne remplace pas le résultat API34 et ne relance pas l'environnement AVD Android 17 S4.

Après ce résultat, une tentative de reprise conditionnelle a été interrompue avant l'instrumentation. Son script comprenait `install -r`, mais aucune sortie ni hash post-installation n'a été capturé. L'absence des packages reste confirmée pour le nettoyage du premier run; l'état post-interruption n'a pas été revérifié et aucune autre commande appareil n'a été lancée.

## Périmètre et environnement

S6 a repris la livraison S5 C+B2 et le correctif S7-P1-01, avec revue orchestrateur acceptée. Les sources de production reçues sont restées gelées pendant cette recette. Les modifications S6 se limitent aux fixtures/tests AndroidTest, scripts de recette, documents et preuves. Les régressions ciblées ont été lancées explicitement avant le harnais, avec comptes réels AndroidJUnitRunner.

L'AVD attribué était `atlas_v622_s1_api34`, API 34, modèle `sdk_gphone64_x86_64`, émulateur Android 37.2.12, serveur ADB 5037 et série AVD `emulator-5580`. Au premier inventaire de la recette principale, un seul émulateur était listé et aucun appareil physique. Au run ACK API34, une cible non-émulateur était présente mais n'a pas été ciblée. Après l'arrêt de l'AVD, un handoff ultérieur a autorisé les deux méthodes ACK sur un Pixel 9 Pro XL physique; ce contrôle distinct est décrit ci-dessus. Aucun AVD S4 sur serveur 5038 n'a été touché. Après la recette S1, le rapport S4 final a été reçu et relu : l'environnement AVD API 37 était `BLOCKED` avant l'installation du candidat final, sans test Android ATLAS exécuté. Voir [s6-inventaire.txt](preuves-v622/s6-inventaire.txt) et [s6-android17.txt](preuves-v622/s6-android17.txt).

Le candidat reçu initial `S5/S7-P1-01` était app SHA-256 `ce10c968988fb3383e06ee94b3c1eb3723f37efcf6035597601643916a9f298a` et AndroidTest SHA-256 `ea53e4294457c6ccdc1c5ebec52025f3f8259f76486b402a8175e0242904933c`, digest code/tests/harnais `6fd5cbf1d44656d101bfa790f1d0d7e7da4b2f595b702584241a5c3f5b2757a5`. Après le diagnostic DEX, S5 a reconstruit proprement le candidat CLEAN : app `ce10c968988fb3383e06ee94b3c1eb3723f37efcf6035597601643916a9f298a`, AndroidTest `8edb71a818ac01a4c3195cab48def493be117587159f939962f4f15ed053014d`, digest code/tests/harnais `93ec83319a2d1f8c9d1a95205a82432d6f7c253700081c2b1387a3fc4d1916d9`. Les résultats du rejeu frais sont dans [la preuve CLEAN](preuves-v622/s6-rejeu-clean-20261021.txt); la preuve [ea53 reçue](preuves-v622/s6-candidat-s5-s7-p1-01-final.txt) demeure historique.

## Reprise après revue du nettoyage exact — AndroidTest 893da1 — 21 octobre

La fixture capture maintenant tout le fichier de préférences de révocation avant la neutralisation, puis restaure les clés, types et valeurs d'origine en `@After`. Un instantané vide rétablit l'absence des clés matérialisées par `writeState`; le test compare les préférences restaurées à l'instantané. La capture précède la mutation et reste disponible si une assertion ultérieure échoue. Aucun échec artificiel n'a été injecté.

Seul `:app:assembleDebugAndroidTest` a été exécuté. L'APK app reste `ce10c968…`; le nouvel AndroidTest est `893da1cf4b9750f868cc943e4e5e01ac86409ed75455c12a5c20d781d7195526`. Candidat hors dépôt : `/tmp/atlas-v622-s6/quarantine-exact-restore-20261021/`. Le test ciblé passe 1/1; la suite principale passe 75/75 après réglage vérifié à 360 dp. Les deux captures HOME/Coach repassées séparément font 1/1 chacune. Le premier passage principal sur l'écran par défaut 411 dp a échoué dans ces deux assertions de largeur et reste un résultat de précondition distinct, pas le verdict final.

Deux E2E Gateway réels consécutifs passent, suivis du parcours hors ligne, sur les APK installées `ce10…` / `893da1…`, hachées avant et après. Le préambule `V6RealAgentE2e` est `NOT_RUN` sur cette paire; son ancien échec non classé reste historique. Aucun `pm clear`, wipe ou désinstallation n'a été lancé. `AtlasLaunchTest.@Before` efface toutefois `atlas_local_state` pendant ses cas puis écrit des fixtures; l'état applicatif peut donc différer de son état initial. Les E2E utilisent leur `ContextWrapper` isolé. Aucune donnée d'app n'a été inspectée ni restaurée; tout reste conservé sur l'AVD. L'AVD a été arrêté proprement et son écran rétabli à sa géométrie d'origine. Les commandes et statuts figurent dans [la preuve dédiée](preuves-v622/s6-revocation-fixture-exact-restore-20261021.txt).

### Rejeu supplémentaire sur la paire exacte — 21 octobre, 11:37–11:42 UTC

Après un inventaire ADB vide, seul `atlas_v622_s1_api34` a été démarré. L'app installée a été vérifiée à `ce10…`; seul AndroidTest a été remplacé avec `install -r -t`, puis les deux APK extraites ont correspondu à `ce10…` / `893da1…`. Le test de migration/quarantaine ciblé a passé 1/1; le principal, avec l'attendu fixé à 75 avant le lancement et le viewport configuré à 360 dp, a passé `OK (75 tests)`, 0 échec, erreur ou ignoré. Aucun Gradle ni build n'a été lancé.

Le Gateway OpenClaw réel a ensuite passé deux fois consécutivement (1/1 par passage), puis le parcours hors ligne a passé 1/1; les sorties expurgées confirment deux ACK exécutés avec effet. `V6RealAgentE2e` reste séparément `NOT_RUN` en mode `context-only`. Les hashes runner, résultats expurgés et commandes figurent dans la preuve liée ci-dessus. L'écran a été restauré; l'AVD est arrêté et ses données conservées. `pm clear`, uninstall et wipe n'ont pas été utilisés. Le test `AtlasLaunchTest` a toutefois effacé `atlas_local_state` au travers de son `@Before` autorisé et écrit des données de fixture; l'état final du package n'a pas été inspecté ni restauré.

## Correction ciblée et rejeu principal — AndroidTest 0b5c — historique — 21 octobre

Le cas ciblé reproduit d'abord le rouge sur la paire `ce10…` / `8edb…`: le runner échoue à l'assertion `authorizedCategories` de `AtlasLaunchTest.kt:338`, après que l'assertion brute de quarantaine a passé. L'état durable contenait les catégories et consentements attendus; la barrière séparée `agent_blocked=true` masquait l'aperçu. Le test sauvegarde maintenant la barrière, l'initialise à neutre pour son fixture et la restaure dans `@After`. La valeur quarantinée est comparée sémantiquement et une fixture contenant un sentinel synthétique prouve l'absence des deux clés privées. Voir [preuve ciblée avec les détails et hashes](preuves-v622/s6-correction-quarantaine-20261021.txt).

Seul `AtlasLaunchTest.kt` a changé. `:app:assembleDebugAndroidTest` passe; aucun `assembleDebug` ni `clean` n'a été exécuté. L'app reste au hash `ce10c968…`; le nouvel AndroidTest est `0b5c3d087cbf856207cd85d7a4a5573d532a65d89394a54a6c7d16abcc0b3872`. Les hashes installés ont été vérifiés sur `atlas_v622_s1_api34` / API34 / ADB5037 après remplacement du seul package de test, sans effacer les données. Le test ciblé fait `OK (1 test)`; le runner principal à 360 dp fait `OK (75 tests)`, et les huit compléments sûrs font 8/8 PASS.

La localisation du même couple APK donne logique, activité visible et préparation mobilité PASS; l'acquisition mobilité reste `TEST_SYSTEM_BLOCKED` (`no_fresh_coarse_fix_after_host_injection`). À la clôture de cette preuve ciblée, aucun E2E n'avait encore été relancé sur `0b5c…`; la reprise distincte ci-dessous a depuis requalifié les scénarios Gateway et hors ligne. Le script complet reste `NOT_RUN` en raison de ses deux `pm clear` et des tests UsageStats intrusifs.

## E2E Gateway réel et hors ligne — AndroidTest 0b5c — historique — 21 octobre

Avant instrumentation, les APK installées ont été relues et les deux SHA attendus correspondaient exactement; les mêmes empreintes ont été confirmées après. Le runner S6 d'origine aurait réinstallé les APK. Le rejeu a donc utilisé une copie temporaire sans ses deux commandes d'installation et a conservé une cible explicitement sélectionnée `emulator-5580`. Un autre appareil non-émulateur figurait dans l'inventaire ADB; son identifiant n'a pas été affiché et aucune commande ne l'a ciblé. Aucun `pm clear`, wipe, désinstallation ou build n'a été exécuté.

Le test `V62RealGatewayContextBridgeTest#realGatewayUsesAccelerated25MinuteFocusContextAndAcceptedProposal` passe deux fois de suite, 1/1 à chaque passage. L'adaptateur est `openclaw`, la connexion réelle passe de non connectée avant la première requête à connectée après, et la base temporaire confirme deux ACK exécutés avec effet. Le PID est présent puis absent après le force-stop entre passages. Après arrêt et contrôle d'inaccessibilité du backend, `localGoalFocusFavoritesAndEmergencyPauseRemainAvailableAfterBackendStop` passe 1/1; le processus est force-stoppé puis recréé par Activity Manager hors ligne.

Le préambule historique `V6RealAgentE2e` est `NOT_RUN` sur `0b5c…`. Son `FAIL` non classé sur `8edb…` reste distinct, sans cause attribuée faute de log brut. Les résumés expurgés, l'identité des APK et l'état final sont dans [la preuve E2E finale](preuves-v622/s6-e2e-final-0b5c-20261021.txt); les sorties S6 sont sous `/tmp/atlas-v622-s6/openclaw-real-final-0b5c-20261021/`.

## Premier rejeu du candidat S5 CLEAN — 21 octobre — historique avant correction

Les hashes des APK candidate et installées ont été vérifiés avant/après le test; la recette E2E a utilisé `install -r` et conservé les données. L'AVD était exclusivement `atlas_v622_s1_api34`, API 34, ADB 5037; seul cet émulateur apparaissait à l'inventaire. Aucun APK n'a été rebâti pendant le rejeu.

Les cinq groupes ciblés passent avec leurs comptes runner : controller 3/3, publisher 2/2, Goal–Focus 7/7, persistance 6/6, Worker 2/2, total 20/20. Le runner principal correctement configuré à 360 dp a exécuté 75/75 cas avec 1 échec, 0 erreur et 0 ignoré. L'échec est `AtlasLaunchTest#recoveryDefersLegacyConsentAndGoalMigrationUntilTheStateGraphIsReady`, assertion de quarantaine à `AtlasLaunchTest.kt:338`, reproduite dans les deux lancements principaux. La cause reste non attribuée; aucune modification de production ou d'assertion n'a été faite.

Huit compléments non destructifs passent : trois LocationCapture à provider simulé, le cas paysage, et HOME/Coach à 320 dp et 360 dp. Deux compléments UsageStats restent `NOT_RUN`, car l'un révoque l'AppOp et efface le magasin d'observation, et l'autre purge consentement/historique UsageStats. Le script `v62-complete-instrumentation.sh` n'a donc pas été lancé : il appelle aussi `pm clear` deux fois sans condition. Le résultat complet ne peut pas être déclaré PASS; les huit PASS, les deux `NOT_RUN` et l'échec principal restent distincts. Le test `AtlasLaunchTest` exécute son propre `@Before clearDemoState()` sur `atlas_local_state`; cette mutation propre au test est consignée dans la preuve.

La recette visible coarse passe pour la logique et l'activité principale. Le test de préparation du consentement mobilité passe, mais la vraie acquisition est `TEST_SYSTEM_BLOCKED` : aucun nouveau fix coarse n'a été retourné après injection synchronisée. Ce statut décrit le fournisseur Android, pas un PASS fonctionnel de mobilité. Les deux modes D3 ne sont pas exécutés dans ce rejeu : UsageStats est `ignore` pour UID et package. L'ancienne fenêtre locale de 60 s est conservée comme historique mais ne forme pas une paire avec cette exécution.

Le scénario S6 Gateway → Goal/Focus lié → progression 25/120 → proposition/mandat/effet/ACK → révocation puis requête sans Goal passe deux fois de suite sur l'OpenClaw réel. Un force-stop et l'absence de PID sont vérifiés entre les passages; deux ACK avec effet existent dans la base temporaire. Après arrêt du backend, le parcours local passe 1/1 et le processus app est arrêté puis recréé par Activity Manager hors ligne. Le préambule d'un ancien test `V6RealAgentE2eInstrumentationTest` a échoué séparément; faute de log brut conservé, aucune cause n'est affirmée et il n'est pas compté comme succès.

Toutes les sorties brutes restent sous `/tmp/atlas-v622-s6/clean-replay-20261021/` et `.../openclaw-real-clean-context-20261021/`; seul le résumé expurgé est versionné dans [la preuve CLEAN](preuves-v622/s6-rejeu-clean-20261021.txt).

**Addendum :** l'attribution « égalité brute de quarantaine » ci-dessus est corrigée par le rejeu rouge/vert isolé. L'échec réel se trouvait dans l'assertion de catégories au même voisinage de lignes; la barrière de révocation persistante du fixture en était la cause. Les résultats actualisés sont dans [la preuve de correction ciblée](preuves-v622/s6-correction-quarantaine-20261021.txt); cette première exécution reste conservée comme historique.

## Régressions explicites du candidat ea53 — historique

| Classe | Cas | Échecs | Erreurs | Ignorés | Résultat |
| --- | ---: | ---: | ---: | ---: | --- |
| `GoalFocusStartInstrumentationTest` | 7 | 1 | 0 | 0 | FAIL |
| `ProactiveControllerPublicationAuthorizationInstrumentationTest` | 3 | 0 | 0 | 0 | PASS |
| `ProactiveCoachNotificationPublisherInstrumentationTest` | 2 | 0 | 0 | 0 | PASS |
| `AgentPersistenceFailureInstrumentationTest` (V6.2.1) | 6 | 0 | 0 | 0 | PASS |
| `ProactiveCoachWorkerInstrumentationTest` (V6.2.1) | 2 | 0 | 0 | 0 | PASS |
| **Total ciblé candidat reçu** | **20** | **1** | **0** | **0** | **19 PASS / 1 FAIL** |

La sélection contient 20 méthodes selon l'inventaire préalable. Les trois courses contrôleur passent, dont la réservation durable sans transport ni rejeu; les deux cas publisher, les six autres cas Goal–Focus, les six cas de persistance et les deux cas worker passent. `failedInitialCommitHasNoSessionGoalAlarmStartedEventOrRecreatedState` échoue de façon reproductible. Le runner et le résumé HTML ne donnent pas le message réel; la trace du test APK ne correspond pas à l'assertion exacte du source actuel, et la chaîne attendue actuelle est absente des DEX de `ea53…`. La recette conserve `FAIL`; aucune production, assertion ou fixture n'a été corrigée pour obtenir du vert. Voir [preuve du candidat reçu](preuves-v622/s6-candidat-s5-s7-p1-01-final.txt).

## Harnais instrumentation du candidat ea53 et D1 — historique

Pour le candidat reçu, l'inventaire statique a fixé **75 cas principaux** avant le lancement (89 méthodes annotées, moins les 4 méthodes E2E exclues, moins les 10 filtres explicites). La commande `ADB_SERVER_PORT=5037 ANDROID_SERIAL=emulator-5580 bash scripts/v62-complete-instrumentation.sh` a exécuté la suite mais a fini en échec de test :

- 75/75 cas exécutés dans la suite principale, 1 échec, 0 erreur et 0 skip;
- 10/10 cas complémentaires, 0 échec, erreur ou skip;
- 21/21 captures PNG;
- 24 classes avec cas exécutés dans la suite principale.

Le verdict du harnais candidat est `FAIL` à cause du cas Goal–Focus ci-dessus. Les dix filtres restent distincts et ne deviennent pas des succès; les 10 compléments sont passés. Les deux tests dépendant d'une injection GPS visible gardent `TEST_SYSTEM_BLOCKED` dans la recette dédiée. La recette historique 74/74 + 10/10 et le passage intermédiaire 8edb restent distincts. Le résultat candidat est sous `/tmp/atlas-v622-s6/full-harness-candidate-ce10c968-final/`.

La première recette complète avait produit 73 PASS et 2 FAIL sur 75 cas. Le premier échec venait de l'écriture privée de la fixture Lot E sous le package test; la fixture a été remplacée par un backend mémoire. Le second était le test de mobilité visible lancé sans injection hôte; le prérequis est maintenant déclaré au filtre, puis la recette dédiée distingue le blocage système. Aucun test n'a été supprimé ou affaibli. Les sorties complètes hors dépôt et leur manifeste sont sous `/tmp/atlas-v622-s6/full-harness-final/20261020T230129Z/`; le manifeste SHA-256 vaut `d3fe0c5c8809627f209f043e61d1d33319f4a4d1842dd3083b256d0e2e3225d6`.

La matrice D1 couverte par les cas Compose/AndroidTest inclut 320/360 dp, portrait et paysage, police 1×/1,5×/2×, recherche, favoris, pied fixe, pause d'urgence, retour HOME et jeux synthétiques de 100/250 applications. Résultat `PASS` sur cet AVD; cela ne qualifie pas la lisibilité sur un téléphone physique.

## Localisation et mobilité visible du candidat ea53 — historique

La recette `scripts/v62-location-visible-recipe.sh` a exécuté ses tests de logique puis vérifié la paire installée avant toute mutation :

- logique contrôlée (provider, fraîcheur, divulgation, TTL et cycle mobilité) : `TEST_LOGIQUE_PASS`;
- comparaison en lecture seule : app installée `ce10…`, AndroidTest installé `8edb71a8…`, attendu `ea53e429…`;
- recette candidate : `BLOCKED` avant instrumentation GPS, car le test APK ne correspond pas au candidat reçu; script arrêté sans désinstaller ni réinstaller.

Aucune acquisition GPS ou service mobilité n’a été lancée sur le candidat `ea53…`. Le résultat antérieur `TEST_SYSTEM_BLOCKED` faute de fix coarse concerne la paire intermédiaire `8edb…` et reste historique; aucun statut système n’est transféré à cette exécution. Preuve de la comparaison sous `/tmp/atlas-v622-s6/location-candidate-ce10-ea53-final/`; anciens résultats dans [s6-localisation-candidat-corrige.txt](preuves-v622/s6-localisation-candidat-corrige.txt) et [s6-localisation.txt](preuves-v622/s6-localisation.txt).

## Parcours Focus, persistance et interruption OS du candidat ea53 — historique

Les parcours Compose couvrent le choix du Goal de 120 minutes, le Focus de 25 minutes, la progression, les 25 minutes persistées, la recréation, la pause et l'annulation sans crédit. Six des sept cas Goal–Focus passent dans le runner du candidat; le cas d'échec atomique reste `FAIL` et ne permet pas de déclarer toute cette classe verte. Le vrai Gateway fournit aussi un parcours complet 0/120 vers 25/120 avec lien Goal–Focus durable.

Le script OpenClaw a également observé `am force-stop` réel du package applicatif : PID présent avant, absent après, processus relancé lors de la suite. Après arrêt et vérification d'inaccessibilité du backend FastAPI temporaire, le test local Focus/Goal/favoris/pause d'urgence a passé. La preuve et la limite du scénario sont dans [s6-hors-ligne-processus.txt](preuves-v622/s6-hors-ligne-processus.txt).

## E2E OpenClaw réel sur le candidat ea53 — historique

Le Gateway OpenClaw configuré localement était joignable. Le script S6 a utilisé l'adaptateur `openclaw`, un bearer aléatoire éphémère et une base temporaire isolée. Aucun jeton ni payload n'est dans le dépôt ou les preuves.

Deux passages consécutifs du candidat figé sont maintenant `PASS` sur le Gateway OpenClaw réel, chacun avec 1 test; le processus est force-stoppé entre les passages. Le scénario corrigé démarre Focus avec `goalId`, vérifie le lien durable et 0/120 avant l'avance de l'horloge, termine 25 minutes à 25/120, puis vérifie la proposition, MandateVerifier, l'effet et l'ACK. La révocation `goals` est suivie d'une requête fraîche sans cette catégorie. Deux ACK exécutés sont confirmés.

Après arrêt et contrôle d'inaccessibilité du backend, le test local hors ligne passe. Le script force-stoppe encore l'application puis relance son activité avec Activity Manager; le processus est observé backend arrêté. Les anciens refus ne sont pas réattribués sans journaux complets. L'ancien résultat de `OK (0 tests)` du scénario hors ligne était dû au nom de méthode inexistant et n'est pas compté comme preuve. Les résultats E2E du candidat reçu sont dans [s6-candidat-s5-s7-p1-01-final.txt](preuves-v622/s6-candidat-s5-s7-p1-01-final.txt); [s6-e2e-candidat-s5-final.txt](preuves-v622/s6-e2e-candidat-s5-final.txt) conserve les passages antérieurs. Les diagnostics [s6-openclaw.txt](preuves-v622/s6-openclaw.txt) et [s6-e2e-diagnostic.txt](preuves-v622/s6-e2e-diagnostic.txt) restent des journaux historiques distincts.

## TalkBack et mesures antérieurs au candidat CLEAN

Sur S1, TalkBack a été lié temporairement; l'arbre UI de HOME contenait 40 nœuds, 19 libellés accessibles, 6 nœuds activables, dont le libellé de pause d'urgence et le libellé Focus. Le rôle HOME et les paramètres d'accessibilité ont été restaurés. Aucun parcours vocal/gestuel fiable n'a été effectué; D2 reste partiel et TalkBack réel physique `NOT_RUN`.

Une fenêtre locale valide de 60 s, écran éteint, a été mesurée; CPU processus/threads et réseau UID sont indisponibles. JobScheduler indique 14 lignes stables et Batterystats 10 enregistrements stables, sans attribuer de réveils. La fenêtre coaching n'a pas démarré : l'AppOp Android était accordé et la fixture synthétique choisie, mais la page Observation ATLAS gardait une allowlist vide après l'action de consentement. D3 est `BLOCKED` comme paire. L'AppOp a été remis à `ignore`; le Coach et la proactivité sont restés désactivés. Aucun reset ni bouton « Fin » n'a été utilisé. Le harnais D3 impose deux fenêtres >=60 s et deux états écran connus identiques. Le diagnostic statique confirme que le toucher principal est la confirmation ATLAS attendue, mais les preuves runtime ne permettent pas d'identifier le maillon qui l'a empêché. Détails : [preuve du candidat](preuves-v622/s6-candidat-s5-s7-p1-01-final.txt), [diagnostic du consentement](preuves-v622/s6-d3-consentement-ui-diagnostic.txt), [rapport D3](preuves-v622/s6-d3-blocked-pause-urgence.txt) et [protocole](preuves-v622/s6-protocole-repos-coaching.txt).

## Baseline complète précédente et limites

Après la recette, le baseline demandé a été lancé avec `clean testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`; 315 tests JVM uniques passent, lint et les deux assemblages passent, et les deux hashes APK restent ceux du candidat. Le pytest final passe avec 66 tests, 1 skip et 0 échec après exposition du Pygments système via `PYTHONPATH`; la première commande sans ce chemin échoue avant collection. Les détails du candidat reçu et de la baseline sont dans [s6-candidat-s5-s7-p1-01-final.txt](preuves-v622/s6-candidat-s5-s7-p1-01-final.txt) et [s6-baseline.txt](preuves-v622/s6-baseline.txt). Le worktree Git reste modifié et non commité.

Le diagnostic E2E ultérieur a réutilisé les APK figées ci-dessus sans relancer Gradle; il n'a changé aucune empreinte d'APK.

La baseline complète ci-dessus précède la correction de fixture AndroidTest `0b5c…`; celle-ci a lancé `:app:assembleDebugAndroidTest` et les tests unitaires localisation ciblés. La reprise de restauration exacte sur `893da1…` a lancé uniquement `:app:assembleDebugAndroidTest`, le test ciblé, la suite principale 75 cas à 360 dp et les E2E dédiés. Les compléments et la localisation ne sont pas transférés à `893da1…`. Les preuves E2E `8edb…` et `0b5c…` restent historiques et distinctes; le dernier rejeu est [documenté ici](preuves-v622/s6-revocation-fixture-exact-restore-20261021.txt).

La recette physique complète reste `NOT_RUN`; seul le contrôle ciblé ACK ci-dessus a eu lieu. TalkBack réel et les mesures de batterie physique restent `NOT_RUN`. L'environnement Android 17 S4 est `BLOCKED` et les 17 scénarios A2 ainsi que 11 nouveaux tests du candidat final sont `NOT_RUN`. Les copies APK S4 correspondaient aux empreintes attendues (`dfb856…` / `353a8d…`), mais aucune installation du candidat final n'a eu lieu : l'installation historique d'une APK plus ancienne n'est pas réutilisée comme PASS. L'Issue 1 reste ouverte. La cause framework externe étant inchangée, aucun nouvel essai de cet environnement AVD API 37 n'a été lancé en S6. Aucun `pm clear`, wipe ni effacement de données utilisateur n'a été fait. Aucun commit, push, merge ou tag n'a été créé. Le verdict global reste une qualification partielle; voir [QUALIFICATION_PRODUIT_V622.md](QUALIFICATION_PRODUIT_V622.md).
