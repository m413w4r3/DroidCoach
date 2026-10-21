# S2/B2 — correction S7, époque de publication proactive

Date : 21 octobre 2026.

## État et périmètre

La correction S7 est appliquée aux fichiers détenus par S2 : scheduler, coordinator, jeton, tests JVM, commentaire Worker et test instrumenté du publisher. S2 n’a pas modifié `AtlasController`, les globals ou l’UI. Le build JVM et la compilation de source instrumentée ont été exécutés par S2 après Gate2; aucun émulateur n’a été lancé.

Le patch temporaire `/tmp/atlas-v622-s2/atlas-v622-s2.patch` décrit l’ancien état préparé avant Gate2. **Il est obsolète et ne doit pas être appliqué** : la source partagée actuelle fait foi.

Référence : [CONTRAT_NOTIFICATION_V622.md](CONTRAT_NOTIFICATION_V622.md).

## Contrat et signatures

L’époque est capturée au démarrage de `ProactiveCoachCoordinator.runDue`, avant le contrôle d’éligibilité, la purge, la lecture de consentement, l’horloge, le fuseau et la préparation du scheduler. Le scheduler ne recapture aucune génération.

```kotlin
data class ProactivePublicationEpoch(
    val capabilityGeneration: Long,
    val agentRequestGeneration: Long,
) {
    fun authorize(triggers: List<CoachTrigger>): ProactivePublicationAuthorization
}

data class ProactivePublicationAuthorization(
    val capabilityGeneration: Long,
    val agentRequestGeneration: Long,
    val triggers: List<CoachTrigger>,
)

class ProactiveCoachCoordinator(
    // autres callbacks d’état, consentement, éligibilité, horloge et fuseau
    private val capturePublicationEpoch: () -> ProactivePublicationEpoch,
    private val isPublicationAuthorizationCurrent:
        (ProactivePublicationAuthorization) -> Boolean,
    private val requestPlan:
        (ProactivePlanRequest, ProactivePublicationAuthorization) -> ProactivePlanResponse?,
    private val showNotification:
        (ProactiveNotification, ProactivePublicationAuthorization) -> Unit,
)

fun runDue(
    nowUtcMillis: Long,
    mandateZoneId: ZoneId,
    publicationEpoch: ProactivePublicationEpoch,
    isPublicationAuthorizationCurrent:
        (ProactivePublicationAuthorization) -> Boolean,
    requestPlan:
        (ProactivePlanRequest, ProactivePublicationAuthorization) -> ProactivePlanResponse?,
    showNotification:
        (ProactiveNotification, ProactivePublicationAuthorization) -> Unit,
): ProactiveRunResult
```

Aucun gate de production n’a de défaut permissif. Un constructeur secondaire de compatibilité conserve temporairement le raccord S5 déjà présent : il appelle le callback historique avec une requête vide pour prendre l’époque au début du run, puis le scheduler lie cette même paire de générations aux déclencheurs sélectionnés. S5 doit migrer vers le constructeur primaire `capturePublicationEpoch`; le callback historique ne doit pas servir à recapturer une génération après réservation.

## Correction S7-P1-01

Le scheduler construit l’autorisation liée aux déclencheurs sélectionnés et vérifie consentement courant et gate contrôleur avant la consommation des événements, puis immédiatement avant la persistance de la réservation. Si l’époque est déjà périmée avant le commit, les événements sélectionnés sont consommés en tombstones sans réservation de requête ni appel réseau. Cela évite leur replay sous une époque réoptée.

Si le retrait/re-opt-in arrive pendant l’écriture durable exacte, le scheduler conserve la réservation, quota, cooldown, séquence et tombstones. Il valide de nouveau l’autorisation dès le retour de l’écriture; l’époque restée liée au lot échoue et la réservation devient `REVOKED_BEFORE_REQUEST`. Aucun callback transport ni notification ne part. Le jeton est process-local : il n’est ni persisté ni placé dans une requête HTTP.

Les vérifications existantes après transport, avant/après traitement de réponse, avant réservation de notification et au gate final du publisher gardent la même autorisation liée à l’époque initiale.

## Cas de course et couverture

| Fenêtre | Test | Résultat vérifié |
| --- | --- | --- |
| Révocation avant le run/capture | `current grant timestamp and revocation purge related pending triggers` — scheduler | Purge du trigger encore pending, tombstone et aucun replay après réopt-in. |
| Révocation puis réopt-in après capture pendant les contrôles/préparation | `publicationEpochIsCapturedBeforeEligibilityConsentAndSchedulePreparation` — coordinator | L’époque précède éligibilité, consentement et fuseau; le lot lié à l’ancienne génération est tombstoné, zéro transport/notification. |
| Retrait et réopt-in exactement au commit de réservation, même horloge | `withdrawal and re opt in at reservation commit cannot authorize the reserved trigger` — scheduler | Échec fermé après commit, zéro transport/notification, réservation/quota/tombstone conservés; recréation du scheduler au même timestamp, zéro replay. Ce test a d’abord échoué contre le code fautif puis passe après correction. |
| Grant retiré avant transport, génération inchangée | `consent revoked before the network callback prevents the request` — scheduler | Le contrôle `currentConsent` seul bloque le callback; la génération reste constante. |
| Retrait pendant la requête en vol, génération inchangée | `consent revoked while the plan request is in flight suppresses its notification` — scheduler | La réponse est rejetée au retour, aucune réservation/publication de notification; la génération reste constante. |
| Retrait pendant transport via génération, puis réponse tardive | `authorization revoked while network response is in flight drops the late response` — scheduler | Barrière déterministe, tombstone et réservation de requête terminale, zéro slot/notification. |
| Retrait après réponse, pendant traitement ou après réservation notification | `authorization revoked during response handling prevents notification reservation`; `revocation after notification reservation fails reserved slot without replay` — scheduler | Pas de réservation si l’autorisation est périmée avant le slot; sinon le slot durable est marqué échoué sans replay/publication. |
| Notification active et retrait concurrent au gate | `revocationRemovesOnlyProactiveChannelIncludingLegacyUntaggedNotice`; `finalAuthorizationGateRejectsAndConcurrentRevocationSerializesTargetedRemoval` — publisher instrumenté | Attentes bornées de présence/disparition via `activeNotifications`, témoin hors canal conservé, gate/retrait ordonnés par latch; nettoyage `finally` des IDs et canal témoin. Le canal de production n’est jamais supprimé. |

Les deux tests `currentConsent` pré-réseau et en vol sont conservés en plus des tests génération : les grants sont retirés sans incrémenter la génération.

## Raccord exigé de S5

Le constructeur primaire du coordinator donne à S5 la frontière explicite. S5 doit :

1. Fournir `capturePublicationEpoch()` qui lit les deux générations pertinentes depuis un snapshot cohérent (sous le verrou d’autorité approprié), avant `isEnabled`, purge, lecture des grants, horloge/fuseau et préparation de la requête. Conserver cette même époque pour tout le run.
2. Conserver `isPublicationAuthorizationCurrent(authorization)` strict : générations identiques, agent et capability actifs, consentement courant de chaque trigger. Lier l’autorisation aux triggers exacts avant la réservation; ne jamais remplacer ses générations après celle-ci.
3. Dans le callback réseau, revalider sous la frontière de sérialisation contrôleur avant l’envoi. Après retour HTTP, revalider avant **chaque** fusion locale, insertion inbox, dispatch ou ACK. Le contrôle du scheduler après le retour du callback ne peut pas annuler un effet local que le callback a déjà commis.
4. Pour la course au commit, vérifier que le callback réseau n’est jamais entré et que les compteurs inbox/dispatch/ACK/notification restent à zéro. Pour un retrait pendant HTTP, laisser la requête déjà partie terminer, mais vérifier qu’aucune réponse périmée n’est fusionnée, dispatchée ou ACKée, et qu’aucune notification n’est réservée/publiée.
5. Incrémenter synchroniquement l’époque sur chaque révocation/désactivation pertinente avant les purges différées et l’annulation WorkManager. Garder le retrait ciblé du publisher après invalidation.

Aucun de ces raccords contrôleur n’a été modifié ou validé par S2.

## Limites de transaction

La réservation durable précède toujours le réseau et garde le comportement au plus une fois. Un retrait exact pendant son commit peut donc consommer le quota sans envoyer de requête. Les verrous du scheduler et du publisher ne rendent pas atomiques le stockage, la génération, le Binder `notify` et le Binder `cancel`. Si `notify` est déjà accepté par Android, un retrait ultérieur tente l’annulation ciblée; une bannière peut être visible brièvement. Aucun test ne revendique une transaction OS absolue.

## Vérifications S2

- Régression avant correction : commande ciblée `ProactiveCoachSchedulerTest.withdrawal and re opt in at reservation commit cannot authorize the reserved trigger`; **RED attendu**, une assertion échoue car l’ancien code appelle le transport.
- Suites ciblées scheduler/coordinator/Worker : **PASS**, 33 tests, 0 échec/erreur/skip.
- Suite JVM complète `./gradlew :app:testDebugUnitTest --console=plain` : **PASS**, 315 tests, 0 échec/erreur/skip.
- Compilation Android test `./gradlew :app:compileDebugAndroidTestKotlin --console=plain` : **PASS**, sans avertissement après correction.
- Exécution sur AVD/appareil des deux tests publisher : **NOT_RUN**. Ils utilisent le vrai `NotificationManager.activeNotifications` et restent à rejouer après intégration S5 avec l’environnement Android prévu.
- Aucun assemblage APK, AVD, appareil physique, commit, push ou merge n’a été utilisé par cette correction.

