# V6.2.2 S2 — contrat d’invalidation et de publication proactive

**État : analyse seulement — 20 octobre 2026**  
**Base auditée :** `feat/atlas-v6-2-2`, `bf647cc18a142dd0f5cd5e68b21c36d511014388`  
**Propriétaire initial :** S2 pour ce contrat, puis `coach/proactive/**`, [ProactiveCoachNotificationPublisher.kt](../../android/app/src/main/java/dev/atlas/coach/app/ProactiveCoachNotificationPublisher.kt) et les tests associés après Gate 2. [AtlasController.kt](../../android/app/src/main/java/dev/atlas/coach/app/AtlasController.kt), l’UI et les fichiers globaux restent à l’intégrateur unique S5.

## Barrière et périmètre

S0 est accepté : 290 tests JVM passent, sans échec ni cas ignoré ; lint, assemblages debug et APK AndroidTest réussissent ; pytest donne 66 réussis et 1 ignoré. Gate 2 n’est pas encore déclaré validé dans [ORCHESTRATION_V622.md](ORCHESTRATION_V622.md) : les 30 tests JVM ciblés persistance/révocation passent, mais les huit cas instrumentés concernés n’ont pas été rejoués sur API 34. Ce document fixe le raccordement uniquement. Aucun source, test ou build n’a été modifié/exécuté ici et aucun émulateur n’a été consulté.

L’implémentation attend la levée explicite de Gate 2. S2 ne modifiera jamais `AtlasController.kt`, l’UI ou les globals ; S5 câblera les callbacks décrits ci-dessous. Le contrat ne demande ni nouveau réseau dans le publisher ni changement de stockage durable ou de schéma.

## Audit du chemin actuel

1. `ProactiveCoachWorker` appelle le contrôleur singleton. L’annulation de son `CompletableFuture` n’arrête pas nécessairement le drain partagé ni le transport HTTP ; le résultat tardif est donc contrôlé séparément.
2. `ProactiveCoachScheduler` écrit la réservation de requête, consomme les triggers avec tombstones et avance la séquence avant le réseau. Après réponse, il écrit la réservation de notification avant `showNotification`, puis marque celle-ci `DELIVERED` ou `FAILED`. Ces réservations assurent quota et comportement au plus une tentative après crash.
3. Le contrôleur a déjà deux compteurs atomiques : `agentRequestGeneration` et `proactiveEffectGeneration`. La requête proactive capture les deux et les vérifie autour de l’appel réseau et avant la fusion de réponse. Le coordinateur a également sa génération propre, mais `disableAndPurgePending()` ne l’incrémente que lorsque le travail asynchrone du contrôleur l’exécute.
4. Le callback final du contrôleur relit le consentement et `isProactiveCoachEnabled()`, puis appelle `ProactiveCoachNotificationPublisher.show()`. Celui-ci construit une notification privée sur le canal dédié `atlas_proactive_coach`, puis appelle `NotificationManager.notify()` sans recevoir la génération capturée. Le contrôle de consentement et l’appel OS ne forment donc pas un gate versionné.
5. Les révocations globales, la désactivation proactive, la suspension/révocation du mandat et la pause d’urgence incrémentent aujourd’hui `proactiveEffectGeneration`. Un retrait de catégorie incrémente `agentRequestGeneration` via `blockAgentForRevocation`, mais n’invalide pas explicitement la génération proactive. Les purges/annulations du coordinateur et plusieurs rafraîchissements WorkManager sont différés sur `syncWorker`.
6. Le publisher ne possède aujourd’hui aucune méthode de retrait des notifications visibles. Sa publication utilise `notify(id, notification)` sans tag. Le canal `atlas_proactive_coach` n’est utilisé par aucun autre publisher ATLAS dans les sources auditées ; les notifications Focus, mobilité, habitudes et friction ont d’autres canaux.

Sources vérifiées : [ProactiveCoachWorker.kt](../../android/app/src/main/java/dev/atlas/coach/coach/proactive/android/ProactiveCoachWorker.kt), [ProactiveCoachCoordinator.kt](../../android/app/src/main/java/dev/atlas/coach/app/ProactiveCoachCoordinator.kt), [ProactiveCoachScheduler.kt](../../android/app/src/main/java/dev/atlas/coach/coach/proactive/ProactiveCoachScheduler.kt), [ProactiveCoachNotificationPublisher.kt](../../android/app/src/main/java/dev/atlas/coach/app/ProactiveCoachNotificationPublisher.kt) et [AtlasController.kt](../../android/app/src/main/java/dev/atlas/coach/app/AtlasController.kt).

## Contrat d’autorisation à transporter

Le contrôleur est la source de vérité pour les générations. Réutiliser `proactiveEffectGeneration` comme génération de capacité proactive monotone du processus, et inclure aussi `agentRequestGeneration` afin qu’un retrait/réopt-in de catégorie rende invalide toute réponse issue de l’ancienne autorisation. S5 incrémente la génération proactive **synchrone et avant tout travail différé** lors de toute fermeture de capacité pertinente. Cela couvre au minimum la désactivation proactive, la désactivation de l’agent, la suspension/révocation du mandat, la pause d’urgence et les retraits de consentement de catégories utilisées par une requête en vol. Le compteur est propre au processus ; il ne doit jamais être persisté ni transmis au backend.

Ajouter dans `coach/proactive` un jeton éphémère immuable, produit par le contrôleur avant le transport et gardant les triggers exacts de la réservation :

```kotlin
data class ProactivePublicationAuthorization(
    val capabilityGeneration: Long,
    val agentRequestGeneration: Long,
    val triggers: List<CoachTrigger>,
)
```

Signatures de frontière proposées pour `ProactiveCoachCoordinator` :

```kotlin
private val capturePublicationAuthorization:
    (ProactivePlanRequest) -> ProactivePublicationAuthorization,
private val isPublicationAuthorizationCurrent:
    (ProactivePublicationAuthorization) -> Boolean,
private val requestPlan:
    (ProactivePlanRequest, ProactivePublicationAuthorization) -> ProactivePlanResponse?,
private val showNotification:
    (ProactiveNotification, ProactivePublicationAuthorization) -> Unit,
```

Le coordinateur/scheduler capture un seul jeton par réservation et le conserve uniquement en mémoire durant `runDue`. Il le vérifie avant d’appeler `requestPlan`, immédiatement après le retour (avant de déclarer la requête réussie ou de réserver la notification), avant la livraison et au gate final. Si le jeton est invalide avant l’envoi, la requête est refusée sans réseau. S’il devient invalide pendant/après le transport, la réponse est abandonnée avant toute fusion locale, dispatch ou ACK ; la réservation déjà faite garde son tombstone et son quota, et aucun nouvel essai automatique de ce trigger n’est créé.

Dans le contrôleur, S5 fournit des helpers de cette forme :

```kotlin
private fun captureProactivePublicationAuthorization(
    request: ProactivePlanRequest,
): ProactivePublicationAuthorization = ProactivePublicationAuthorization(
    capabilityGeneration = proactiveEffectGeneration.get(),
    agentRequestGeneration = agentRequestGeneration.get(),
    triggers = request.triggers.toList(),
)

private fun isProactivePublicationAuthorized(
    authorization: ProactivePublicationAuthorization,
): Boolean =
    authorization.capabilityGeneration == proactiveEffectGeneration.get() &&
        authorization.agentRequestGeneration == agentRequestGeneration.get() &&
        isAgentRequestAllowed() && isProactiveCoachEnabled() &&
        authorization.triggers.all(proactiveConsentSnapshot()::allows)
```

Les noms peuvent suivre le style S5, mais les deux générations doivent être comparées et les consentements de **chaque trigger d’origine**, avec leur date de survenue, doivent être réévalués. Vérifier également que `notification.triggerSources` correspond aux sources du jeton. Cette comparaison évite de valider une ancienne réponse après retrait puis réopt-in rapide, y compris lorsque le trigger `DAILY_PLAN_DUE` n’a pas de catégorie propre mais que le texte du plan a utilisé un contexte partagé.

Le callback réseau du contrôleur reçoit le jeton capturé et s’en sert pour `AgentApiClient.plan(... allowSend = { ... })`, puis revalide avant toute fusion de la réponse, dispatch local ou mise en file d’ACK. Il ne recapture pas une nouvelle génération après la réponse. `ProactivePlanResponse` et les structures persistées restent inchangées : le jeton n’est ni donnée réseau, ni donnée de notification, ni donnée durable.

## Gate final et retrait Android ciblé

Changer le publisher vers ces signatures :

```kotlin
fun show(
    notification: ProactiveNotification,
    isStillAuthorized: () -> Boolean,
)

fun cancelPublishedProactiveNotifications(): Int
```

`show` construit le `PendingIntent` et l’objet Android, revérifie la disponibilité des notifications, puis évalue `isStillAuthorized()` au dernier point possible, juste avant `NotificationManager.notify`. Un refus lance une exception contrôlée afin que le scheduler marque la réservation de notification `FAILED`, sans annoncer une livraison. Aucun HTTP ne doit être introduit dans cette méthode.

Pour le retrait, attribuer les futures publications à un tag stable réservé à ATLAS. `cancelPublishedProactiveNotifications()` énumère `NotificationManager.activeNotifications`, ne sélectionne que les notifications publiées par l’application sur `atlas_proactive_coach`, puis appelle `cancel(tag, id)` pour chacune. Le filtrage par canal permet aussi de retirer les notifications préexistantes sans tag. Ne jamais appeler `cancelAll()` ni annuler un ID sans vérifier son canal : les notifications Focus, mobilité, habitudes et friction doivent rester intactes.

À chaque révocation qui invalide le contenu, S5 doit incrémenter la génération immédiatement, demander l’annulation du travail proactif actif, retirer les notifications proactives visibles, puis recalculer l’éligibilité WorkManager. Pour un retrait de catégorie alors que d’autres consentements restent actifs, le travail en cours est invalidé et arrêté ; le travail périodique ne reste planifié que si la capability proactive et les consentements restants le permettent. Comme le texte de notification peut résumer plusieurs catégories, le défaut prudent au retrait d’une catégorie est de retirer toutes les notifications de ce canal. L’invalidation ne dépend pas de la fin de `queueProactiveDisableAndPurge()`.

Une synchronisation locale étroite du publisher entre `show` et le retrait peut ordonner les appels d’une instance, mais ne change pas la limite OS décrite ci-dessous. La génération est incrémentée avant le retrait afin qu’un producteur bloqué sur cette synchronisation échoue au gate lorsqu’il reprend.

## Six courses de test imposées

Ces tests ne sont **pas exécutés pendant cette phase**. Les noms ci-dessous sont des noms proposés ; ils peuvent être adaptés sans réduire les assertions.

1. **Révocation avant la réponse réseau** — `revocationBeforePlanResponseDropsLateResult`. Bloquer une réponse avec une barrière, invalider le jeton, puis libérer le transport. Vérifier que la réponse ne fusionne rien dans l’inbox, ne déclenche aucun effet/ACK, ne réserve ni ne publie de notification. Le transport déjà envoyé ne peut pas être rappelé ; seule sa réponse locale est rejetée.
2. **Révocation pendant le traitement de réponse** — `revocationDuringPlanResponseHandlingPreventsMergeAndAck`. Arrêter le parcours après réception HTTP mais avant `mergePulled`/dispatch/ACK, révoquer, puis reprendre. Vérifier que la vérification de génération précède chaque mutation et qu’aucun contenu ancien ne rejoint l’état local ou la notification.
3. **Révocation entre réservation et publication** — `revocationAfterNotificationReservationSkipsPublish`. Laisser la réservation durable être écrite, bloquer à l’entrée du publisher, révoquer et reprendre. Le gate doit refuser `notify`, le statut existant devient `FAILED`, le tombstone et le quota sont conservés, et aucun replay n’a lieu.
4. **Notification déjà visible au retrait** — `revocationCancelsOnlyProactiveNotifications`. Publier une notification proactive et une notification témoin sur un canal ATLAS distinct, révoquer, puis vérifier que le canal proactif est vide et que le témoin reste visible. Vérifier aussi la suppression d’une notification proactive de l’ancien format sans tag.
5. **Absence après invalidation au gate final contrôlé** — `publisherChecksAuthorizationImmediatelyBeforeNotify`. Avec une génération contrôlée et un callback de gate déterministe, invalider le jeton au dernier contrôle avant `notify`. Vérifier avec `activeNotifications` qu’aucune notification proactive n’apparaît. Ajouter le cas complémentaire où `notify` est déjà entré avant l’invalidation : la révocation attend la fin de publication puis tente le retrait ciblé.
6. **Aucun doublon après redémarrage** — `deliveredReservationIsNotRepublishedAfterControllerRecreation`. Publier une fois, recréer contrôleur/coordinateur depuis l’état durable, puis relancer le worker. Vérifier une seule réservation et un seul appel `notify`, sans répétition de l’événement ni changement de quota ; après révocation, la notification visible reste retirée.

Préserver en plus les assertions existantes des tests `ProactiveCoachCoordinatorTest`, `ProactiveCoachSchedulerTest`, `ProactiveCoachWorkerInstrumentationTest` et `ProactiveCoachNotificationInstrumentationTest` : quotas quotidiens, silence 22 h–8 h, cooldown motif, déduplication, réservation persistée avant effet, absence d’ACK/de dispatch automatique au tap et annulation du travail à la révocation. Les deux premières courses doivent tester l’absence de fusion et d’ACK, pas seulement l’absence de bannière Android.

## Limites transactionnelles et comportement au crash

`NotificationManager.notify()` et `cancel()` sont des appels système indépendants des commits ATLAS. Il n’existe pas de transaction atomique couvrant génération, stockage, transport HTTP et affichage Android. Une révocation après l’ultime comparaison mais pendant l’appel Binder peut rendre la notification visible brièvement avant que le retrait la supprime ; un crash entre ces appels peut également empêcher le retrait immédiat. La capacité peut être invalidée dans le processus, les courses contrôlées doivent échouer fermées et le retrait OS doit être demandé sans délai, mais ne jamais promettre « impossible à afficher après révocation » dans toutes les courses système.

La réservation préalable est volontairement au plus une fois : après un crash, ATLAS ne sait pas toujours si Android a reçu le `notify`. Ne pas libérer un quota ou rejouer le trigger pour simuler une livraison exactement une fois. L’état `RESERVED` ou `FAILED` peut représenter une publication incertaine, au prix d’une notification manquée plutôt que d’un doublon. Le retrait ne peut pas effacer un texte déjà aperçu par la personne ni une copie de contexte déjà envoyée au serveur.
