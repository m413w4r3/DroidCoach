# ATLAS V6.2.2 — contre-revue indépendante S7

**Date :** 21 octobre 2026
**Branche / HEAD :** `feat/atlas-v6-2-2` / `bf647cc18a142dd0f5cd5e68b21c36d511014388`
**Paire antérieure acceptée :** app `ce10c968988fb3383e06ee94b3c1eb3723f37efcf6035597601643916a9f298a`, AndroidTest `893da1cf4b9750f868cc943e4e5e01ac86409ed75455c12a5c20d781d7195526`.
**Handoff S5 ACK contre-revu :** app `7c6c41a4b90eb8fa88b3ffcc6003468092c3681a8ef6cd95024e92b73ec7c1ea`, AndroidTest `542280c3c6d020cfe0327056889affeec2dabb9cbaba65a0a03e526ae512deeb`.

## Verdict

**Gate 5 : FAIL — qualification complète inachevée.** L’acceptation antérieure `ce10/893da1` reste inchangée. Sur le handoff `7c6c/542280`, les deux méthodes ACK passent sur les APK installées en API 34. Le Pixel 9 Pro XL API37 passe la méthode de course de révocation, mais le chemin positif `accepted → executed` échoue de façon reproduite avec le runner `-2`; aucune cause n’est attribuée. Le constat P1-01 est résolu pour le blocage de course testé; le constat P1-04 sur le chemin positif API37 reste ouvert. En outre, la suite Android principale, le harnais complet avec dix compléments, le Gateway et le scénario hors ligne n’ont pas été rejoués sur cette paire. Les résultats antérieurs ne sont pas transférés : Gate 5 reste FAIL.

Sur le manifeste S5 exact (`87d05…`, 234 entrées), le rejeu JVM passe **317/317** et `lintDebug` passe avec **53 avertissements et zéro erreur**. Les 315/315 et lint du snapshot S7 précédent (`dbce…`) restent historiques.

**Aucun P0 identifié dans le périmètre revu.** D3, TalkBack réel et Android 17 restent hors PASS. Le seul essai physique de cette paire est le contrôle ACK limité API37, avec un résultat mixte; aucune qualification physique générale n’est revendiquée. Le détail des chemins, preuves et limites est dans [la preuve S7](preuves-v622/s7-revue-independante.txt).

## Constats classés

### S7-P1-01 — Constat initial sur `ce10/893da1` — HISTORIQUE, correctif S5 ciblé validé

**Scénario dans le snapshot précédent `dbce…`.** Après qu’une proposition a été exécutée par un run proactif, `sendPendingAgentAcks` contrôlait l’époque à la ligne 2054, puis appelait `client.acknowledge` à la ligne 2056. Si le consentement était retiré puis réactivé dans cet intervalle, le jeton capturé était invalide, mais l’ACK client ne le recevait pas; le seul `requestAllowed = ::isAgentRequestAllowed` pouvait redevenir vrai après réopt-in. Ce constat décrit l’ancienne source, pas le correctif S5.

**Code et preuve historiques.** La contre-revue du snapshot `dbce…` a relevé l’absence de callback d’époque dans les lignes 2055–2062 d’AtlasController et dans l’ancienne signature de `AgentApiClient.acknowledge`; le contrôle commun ne vérifiait que le consentement générique. Ces anciennes lignes ne sont pas des liens vers la source S5 actuelle. Les autres frontières proactive revues alors incluaient la capture avant contrôles, la réservation durable, le retour transport, le merge/dispatch et le gate final du publisher.

Les tests présents dans ce snapshot vérifiaient retrait/réopt-in au commit de réservation, réponse tardive et retour transport avant fusion, avec zéro transport ou effets locaux pour les réponses périmées. Ils ne plaçaient pas le retrait à la frontière d’ACK. Le hook `proactiveAckAttemptObserverForTesting` exposait cet emplacement.

**Correction demandée à l’époque — propriétaires S2 + S5.** Faire passer l’autorisation proactive à chaque ACK et la contrôler avant la sérialisation du body, y compris entre les deux ACK `accepted` / `executed`; tester le retrait/réopt-in à la frontière transport, préserver l’idempotence et le comportement hors proactivité. Le handoff S5 ajoute statiquement cette modification; ses deux tests ciblés passent désormais sur les APK exactes, comme détaillé ci-dessous.

### Handoff S5 ACK final — correctif revu; méthodes ciblées PASS, harnais complet NOT_RUN

S5 a ajouté le callback d’autorisation capturée à [AtlasController.kt](../../android/app/src/main/java/dev/atlas/coach/app/AtlasController.kt#L2040) et le passe pour les branches `accepted`, `executed` et autres ACK. [AgentApiClient.kt](../../android/app/src/main/java/dev/atlas/coach/sync/agent/coach/AgentApiClient.kt#L209) réévalue ce callback dans le `bodyFactory` avant de sérialiser l’ACK; `acknowledgeExecution` le transmet aux deux transitions. Le contrôle générique `requestAllowed` reste actif en amont.

Les assertions source correspondent au scénario demandé : [le test contrôleur `sameClockReoptInAfterProactiveDispatchPreventsRealAckAtSerializationGate`](../../android/app/src/androidTest/java/dev/atlas/coach/app/ProactiveControllerPublicationAuthorizationInstrumentationTest.kt#L232) fait le prévol inbox en loopback, révoque puis réopte au même instant, constate que le gate générique repasse mais que l’époque capturée refuse le body, vérifie qu’aucun POST ACK n’atteint le serveur, que `ackPending` reste vrai et que l’effet local n’est pas rejoué. [Le test positif `proactiveDispatchSendsAcceptedThenExecutedThroughRealLoopbackClient`](../../android/app/src/androidTest/java/dev/atlas/coach/app/ProactiveControllerPublicationAuthorizationInstrumentationTest.kt#L384) vérifie deux requêtes `accepted` puis `executed`, l’effet, un dispatch et `ackPending=false`. Les tests JVM [refus avant sérialisation](../../android/app/src/test/java/dev/atlas/coach/sync/agent/coach/AgentApiClientTest.kt#L128) et [recontrôle entre accepted/executed](../../android/app/src/test/java/dev/atlas/coach/sync/agent/coach/AgentApiClientTest.kt#L156) sont également présents.

La preuve S6 finale [ACK API34](preuves-v622/s6-ack-api34-runtime-20261021.txt) identifie les APK installées par leurs hashes exacts et rapporte `OK (1 test)` / exit 0 pour chacune des deux méthodes ciblées. Pour le test de course, le mode résumé n’a pas émis les champs STATUS optionnels : le collecteur local a d’abord signalé à tort un échec pour ces champs absents, mais le runner a rendu `OK (1 test)` et exit 0. Aucun log brut de ce run n’a été conservé. Le test positif a en plus les statuts bruts attendus (`class+method matched`, `numtests=1`, code instrumentation `-1`). Les deux résultats API34 sont **PASS**; ils exercent un loopback local, pas le Gateway réel.

Les préflights initiaux S6 sur 5037 puis 5038 étaient incomplets et sont maintenant explicitement supersédés par la preuve runtime finale. Le registre assigné `/tmp/atlas-v622-s1/avd-home` a fourni `atlas_v622_s1_api34`; l’AVD `emulator-5580`, SDK 34, a été démarré sans wipe, les APK ont été installées puis extraites avec hashes conformes, et l’AVD a été arrêté normalement. La cible non-émulateur n’a pas été ciblée. S7 n’a pas utilisé ADB/AVD. Le test Gateway, la suite principale et le harnais complet restent `NOT_RUN` sur `7c6c/542280`; les résultats de `ce10/893da1` restent séparés.

Le handoff physique séparé [Pixel API37](preuves-v622/s6-ack-physical-runtime-20261021.txt) vérifie les mêmes hashes candidat installés sur un Pixel 9 Pro XL API37. La course `sameClockReoptInAfterProactiveDispatchPreventsRealAckAtSerializationGate` passe 1/1. Le chemin positif `proactiveDispatchSendsAcceptedThenExecutedThroughRealLoopbackClient` échoue de façon reproduite : un cas sélectionné, le rejeu diagnostic rend `FAILURES!!!` et le statut runner `-2`. Aucun message d’assertion ni log brut permettant une attribution causale n’est conservé; ce résultat est **FAIL non attribué**, et ne remplace pas le PASS API34. Après le premier run, les packages initiaux absents ont été retirés; les trois caches et trois préférences de fixture ont été supprimés par chemin exact et leur absence vérifiée, avec zéro notification active avant/après ce nettoyage. Une reprise ultérieure a été interrompue avant instrumentation : le script contenait `install -r`, mais aucun `am instrument`, sortie ni hash post-installation n’a été capturé. L’état des packages après cette interruption est **NOT_RECHECKED**; aucun autre ADB n’a ensuite été lancé et aucun troisième test n’a eu lieu. Aucun réglage/permission n’a changé, aucun `pm clear` ou wipe n’a été utilisé. L’AVD API34 est resté arrêté.

### S7-P1-04 — Chemin positif ACK en API37 — FAIL reproduit, cause non attribuée

Sur le Pixel 9 Pro XL API37, la méthode `proactiveDispatchSendsAcceptedThenExecutedThroughRealLoopbackClient` a échoué lors de deux exécutions; le rejeu diagnostic sélectionne un cas et rend `FAILURES!!!` / code runner `-2`. La preuve ne conserve aucun message d’assertion ni sortie brute suffisante pour attribuer une cause. Cela ne démontre pas à lui seul un défaut de production, mais laisse le chemin positif ACK non qualifié sur cette cible et empêche de déclarer l’ensemble du contrat ACK validé. Le résultat distinct API34 est PASS 1/1 pour cette même méthode. L’appel interrompu ultérieur ne contenait aucun `am instrument`, mais son précontrôle contenait `install -r`; aucun hash/sortie post-installation n’a été capturé et le statut package est `NOT_RECHECKED`. Il n’y a donc pas eu de troisième test; S7 ne demande ni ne lance d’autre essai.

### S7-P1-02 — Ancienne preuve Gateway Goal–Focus — RÉSOLU SUR `ce10/893da1`; NOT_RUN SUR `7c6c/542280`

Le test courant [V62RealGatewayContextBridgeTest.kt](../../android/app/src/androidTest/java/dev/atlas/coach/app/V62RealGatewayContextBridgeTest.kt#L103) démarre désormais `startFocus(plan, goalId = goal.id)`. Avant l’horloge, il vérifie Focus `RUNNING`, le lien Goal et la progression calculée `0/120`; il relit aussi le root durable et vérifie Focus et lien présents. Après 25 minutes d’horloge injectée, il vérifie `25/120` en mémoire et sur le root. Aucun appel postérieur à `linkFocusToGoal` ne fabrique le résultat.

La paire `ce10/893da1` a deux exécutions Gateway consécutives PASS (1/1 chacune), deux ACK exécutés avec effet, puis le test local hors ligne PASS (1/1). Cette preuve reste un Focus accéléré, pas 25 minutes réelles.

### S7-P1-03 — Échecs E2E historiques confondus avec le candidat — RÉSOLU POUR L’ATTRIBUTION CE10/893DA1

Le rapport exact S6 sépare les résultats : `ce10/893da1` a les deux passages Gateway et le scénario hors ligne PASS; le préambule `V6RealAgentE2e` est `NOT_RUN` en mode `context-only`. Les FAIL précédemment observés sur l’AndroidTest `8edb` demeurent historiques et non attribués faute de journaux complets. Ils ne sont ni transférés à la paire courante, ni effacés de l’historique.

Le test Gateway courant emploie l’`AgentApiClient` et l’adaptateur OpenClaw réel configuré par la recette S6. Les propositions synthétiques des tests de contrôleur et les scénarios offline restent des fixtures locales; ils ne sont pas présentés comme réponses OpenClaw.

### S7-P2-01 — Commentaire Worker périmé — RÉSOLU

Le commentaire de [ProactiveCoachWorker.kt](../../android/app/src/main/java/dev/atlas/coach/coach/proactive/android/ProactiveCoachWorker.kt#L28) décrit désormais la capture d’époque avant préparation, le lien à la réservation, les recontrôles et la limite OS `notify/cancel`. La course ACK historique est couverte par le callback de sérialisation et les deux méthodes ciblées PASS sur `7c6c/542280`.

### S7-P2-02 — Message trompeur après échec de commit Focus — RÉSOLU

Après échec d’écriture racine, [AtlasController.kt](../../android/app/src/main/java/dev/atlas/coach/app/AtlasController.kt#L3170) affiche « Focus non démarré. Aucun lien à l’objectif n’a été enregistré. » Le test instrumenté et le test JVM vérifient le texte, le root inchangé, l’absence de session et de lien, et l’absence d’alarme/événement.

### S7-P2-03 — Synthèse XML et compteurs trop permissifs — CORRIGÉ DANS LE CODE, EXÉCUTION NON REJOUÉE PAR S7

Le nouveau [résumeur](../../scripts/v62-instrumentation-summary.py#L124) échoue sur XML manquant, illisible ou mal formé, compte exact différent de 75, doublons, échecs, erreurs et skips; il exige les dix journaux complémentaires, leurs résumés à un test, les statuts de préconditions et les 21 captures. Les cas filtrés sont rapportés séparément et comptés comme zéro exécution PASS. [Les tests négatifs](../../scripts/test-v62-instrumentation-summary.py#L174) couvrent notamment le faux vert historique à zéro test, XML invalide, compte principal inattendu, journal complémentaire absent/non parsable et filtres incohérents.

Revue statique S7 : PASS sur les assertions présentes. L’auto-test Python et le harnais complet ne sont pas déclarés exécutés sur la paire exacte; ils restent `NOT_RUN` pour cette contre-revue.

### S7-P2-04 — D3 n’a pas deux repos comparables — NOT_RUN

La preuve de la paire exacte garde D3 `NOT_RUN`. Une ancienne fenêtre locale de 60 s et une tentative coaching bloquée à la précondition de consentement ne constituent pas deux modes de repos comparables. Aucune mesure d’autonomie ou de consommation comparative n’est revendiquée.

## Contrôles statiques complémentaires

- **Goal–Focus et effets externes : PASS au périmètre source/test revu.** Le départ local prépare Focus et lien Goal, construit une candidate root unique, vérifie l’état Goal attendu puis commite avant publication en mémoire, alarme, événement ou succès UI. Les wrappers convergent vers cette transaction; le chemin agent reste sous vérification `MandateVerifier`, journal et clé d’idempotence.
- **Progression : PASS au périmètre exécuté/documenté.** Le crédit vient du travail terminé lié; les pauses, annulations et lectures répétées ne génèrent pas de crédit ou de double progression. La paire antérieure `ce10/893da1` vérifie explicitement `0/120` puis `25/120`; le Gateway n’a pas été rejoué sur `7c6c/542280`.
- **Révocation proactive : PASS pour la course de révocation ciblée; chemin positif multi-cible non résolu.** Epoch avant contrôles, réservation, transport, inbox, dispatch, bodyFactory ACK et gate `notify` ont des contrôles liés au jeton. Le test de course passe en API34 et API37; le chemin positif passe en API34 mais échoue sans cause attribuée en API37. L’annulation Android ne peut pas être atomique avec un appel Binder déjà entré; le code annule seulement les notifications du canal proactif.
- **Privacy/permissions : PASS au contrôle statique ciblé.** Le jeton d’époque est process-local et absent du payload. Le message de plan contient sources et dates agrégées, sans IDs d’événement ni contenu brut du déclencheur. Aucun `ACCESS_BACKGROUND_LOCATION`, `AccessibilityService`, root, Device Owner ou kiosque n’a été trouvé dans le périmètre de production revu. Aucune URL, valeur de jeton ni payload utilisateur n’est inscrite dans la preuve S7.
- **Fixture `AtlasLaunchTest` : PASS pour la préférence dédiée.** La fixture copie toutes les entrées et clones les `Set<String>` avant mutation; `@After` efface uniquement `atlas_agent_revocation_barrier`, restaure chaque valeur par type, commit synchroniquement et compare `preferences.all` à l’instantané. Le test ciblé passe 1/1. Aucun échec artificiel n’a été injecté après neutralisation. La suite `@Before clearDemoState()` peut modifier `atlas_local_state`; S6 indique que l’état applicatif sur l’AVD n’a pas été restauré ni inspecté. La preuve porte donc seulement sur la préférence de révocation, pas sur l’état complet de l’application.

## Statuts et limites

Les PASS fixture, suite, captures et Gateway ci-dessous appartiennent à l’acceptation antérieure `ce10/893da1` et ne sont pas transférés à `7c6c/542280`. Les validations du nouveau candidat sont listées séparément.

| Domaine | Statut retenu pour cette revue |
| --- | --- |
| `ce10/893da1` : restauration fixture ciblée | **PASS 1/1**, portée à la préférence dédiée |
| `ce10/893da1` : suite API 34 principale | **PASS 75/75**, 0 échec / erreur / ignoré, viewport corrigé à 360 dp |
| `ce10/893da1` : captures largeur isolées | **PASS 2/2**, après correction viewport |
| `ce10/893da1` : Gateway réel et scénario local | **PASS 2 × 1/1 Gateway**, deux ACK avec effet; offline **PASS 1/1** |
| `ce10/893da1` : préambule `V6RealAgentE2e` / harnais + dix compléments | **NOT_RUN** (`context-only`) / **NOT_RUN** |
| `7c6c/542280` : APK installées / hashes extraits | **PASS** app et AndroidTest conformes aux hashes candidats; SDK 34 |
| `7c6c/542280` : retrait/réopt-in au bodyFactory ACK | **PASS 1/1**, exit 0, résumé runner `OK (1 test)`; champs STATUS optionnels absents et aucun log brut conservé |
| `7c6c/542280` : ACK positif `accepted → executed` API34 | **PASS 1/1**, exit 0, statuts bruts classe/méthode, `numtests=1`, code `-1` |
| `7c6c/542280` : course API37 sur Pixel 9 Pro XL | **PASS 1/1**, distinct du run API34 |
| `7c6c/542280` : ACK positif API37 sur Pixel 9 Pro XL | **FAIL reproduit**, un cas sélectionné, runner `-2`; cause non attribuée |
| Pixel API37 : nettoyage après premier run | **PASS** packages absents, caches/préférences nettoyés, zéro notification active avant/après ce nettoyage |
| Pixel API37 : état package après interruption | **NOT_RECHECKED**; précontrôle interrompu avec `install -r`, sans `am instrument`, sortie ou hash post-installation |
| `7c6c/542280` : JVM | **PASS 317/317**, 57 fichiers XML, zéro failure/error/skipped |
| `7c6c/542280` : lint | **PASS**, 53 warnings, zéro erreur |
| `7c6c/542280` : suite Android principale / Gateway / offline / harnais complet + dix compléments | **NOT_RUN** |
| Tests négatifs Python du résumeur | Assertions statiquement revues; exécution **NOT_RUN** par S7 |
| Manifeste sources/tests/scripts de `7c6c/542280` | **PASS 234/234** avant et après validation Gradle |
| Snapshot S7 antérieur (`dbce…`) | **PASS 315/315 + lint**; historique, remplacé pour le candidat actuel par 317/317 et lint 53 warnings |
| Résultat JVM antérieur 313/313 | Historique, antérieur au rejeu 315/315 de `dbce…` |
| Baseline backend pytest | **PASS de référence 66 + 1 skip**, non rejouée par S7 |
| Android 17 | **BLOCKED** environnement avant installation; 17 scénarios et 11 tests ciblés **NOT_RUN** |
| D3, TalkBack réel, batterie physique / recette physique complète | **NOT_RUN**; seul le contrôle ACK limité API37 a été exécuté, avec résultat mixte |

Les anciens comptes `74+10` et les `19` tests ciblés appartiennent aux preuves antérieures; ils ne remplacent pas le `75/75` de `ce10/893da1` et ne sont pas transférés à `7c6c/542280`. Le premier run `75` cas au viewport 411 dp a échoué deux assertions de largeur; il reste classé comme échec de précondition. Après réglage réversible à 360 dp, les deux cas isolés et le run principal ont passé sur `ce10/893da1`.

S7 a vérifié le manifeste exact `87d05…` (**234/234** avant et après Gradle), puis exécuté `./gradlew --rerun-tasks :app:testDebugUnitTest :app:lintDebug --console=plain` : **BUILD SUCCESSFUL**, 33 tâches, JVM **317/317** sans échec/erreur/skip, lint **53 warnings / 0 erreur**. Sept avertissements Kotlin de casts non vérifiés ont été émis pendant la compilation des tests. Le daemon Gradle a été arrêté après validation. S7 n’a lancé ni ADB/AVD, ni test instrumenté, Gateway ou harnais complet sur cette nouvelle paire. Aucun fichier source/test/script partagé n’a été modifié par S7; ses seules écritures sont ces deux rapports. Aucun commit, push, merge ou tag.
