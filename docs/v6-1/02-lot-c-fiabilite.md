# Lot C — Fiabilité du domaine, de l’inbox et du backend

**Date :** 2026-10-19  
**Branche :** `feat/atlas-v6-1-stabilisation`  
**Périmètre respecté :** domaine/données/backend, contrats HOME et Focus, tests JVM et pytest. Aucun changement de branche, commit, push ou merge. Pas d’émulateur.

## Audit et défauts corrigés

| Zone | Défaut observé | Correction |
|---|---|---|
| HOME | Une directive stockée n’avait pas d’origine. L’état agent absent était rendu « local hors ligne », même si la directive persistée venait de l’agent distant. La disponibilité n’avait ni horodatage ni durée de fraîcheur. | Ajout de `DirectiveProvenance` (`REMOTE_AI`, `LOCAL_DEMO`, `MANUAL_ENTRY`) à la directive persistée, migration des directives locales de schéma 1 vers `REMOTE_AI`, et séparation de `AgentBackendState` (`REACHABLE`, `UNAVAILABLE`, `UNVERIFIED`) avec dernière vérification et dernier succès. Une observation expire après 5 minutes; un recul d’horloge produit `UNVERIFIED`. Une directive distante sans vérification fraîche n’est plus classée locale. |
| Inbox | Un JSON invalide effaçait silencieusement la map et le curseur. Les acknowledgements terminaux pouvaient être supprimés par le plafonnement; une page dépassant la capacité pouvait avancer le curseur et perdre des propositions. | Copie en quarantaine avec motif et chemin avant remise à zéro. Si la copie échoue, l’inbox reste en lecture seule et le fichier source n’est pas écrasé. Les entrées non acquittées ne sont pas élaguées. À capacité pleine, le curseur ne progresse pas au-delà des propositions non stockées. Une proposition acceptée reste disponible au journal pour réconciliation après TTL; le dispatcher revalide le TTL avant une reprise. Le dernier contact backend réussi est persisté dans le fichier inbox. |
| Journal / dispatch | L’effet était exécuté avant la première écriture durable du journal. Une interruption après l’effet mais avant `EXECUTED` permettait un doublon. Les recherches ne vérifiaient pas globalement la clé d’idempotence; l’historique ancien était supprimé au plafond de 500. | Le journal écrit `PREPARED` avant la branche locale et `COMPLETE` avant de rendre l’ack éligible. Il refuse une clé déjà associée à une autre proposition et conserve les tombstones. À 500 entrées, il bloque les nouveaux dispatchs au lieu d’oublier des clés. Un résultat incertain reste bloqué jusqu’à `APPLIED` ou `NOT_APPLIED`; il n’est jamais rejoué par défaut. |
| Mandat et références | Le mandat, les capacités, le TTL, les quotas et les références étaient validés avant l’acceptation, mais pas une seconde fois juste avant l’effet. | Revalidation juste avant la branche locale. Les tests couvrent révocation, consentement retiré, TTL arrivé à échéance, quota atteint et référence Focus annulée. Une édition/refus d’une action déjà préparée est bloquée. |
| État HOME corrompu | `AgentHomeState.fromJson` retombait silencieusement sur un état vide. | La charge invalide et un motif sont conservés dans une enveloppe de quarantaine sérialisable, au lieu d’être abandonnés. |
| Durées Focus | Le parseur ne recopiait qu’une durée de travail numérique simple. Il ignorait pause/cycles et traitait plusieurs occurrences d’une même durée comme une valeur unique. | Normalisation déterministe du travail, des pauses et des cycles, en chiffres ou cardinaux français usuels. Les valeurs corrigées sont bornées par le contrat. Plusieurs contraintes concurrentes, valeur hors limites, durée non classée ou forme non prise en charge retirent la proposition Focus et demandent une précision. Une trace contrôlée enregistre statut, champs et motif sans enregistrer le message. |

## Fiabilité et réconciliation

Tests d’interruption ajoutés entre l’acceptation, la branche locale, l’écriture finale du journal, la mise à jour de l’inbox et l’ack serveur. Un journal `COMPLETE` répare l’état de l’inbox après redémarrage; un ack non confirmé reste en attente et réutilise la même proposition. Le port d’action doit implémenter `reconcile(proposal, idempotencyKey)` en inspectant l’état durable local : `APPLIED` termine sans rejouer, `NOT_APPLIED` autorise une reprise après revalidation, et `UNKNOWN` conserve l’état préparé sans doublon.

## Intégration requise et limites à la fin du lot C initial

`AtlasController` était explicitement hors périmètre et n’a pas été modifié. Il reste à raccorder dans l’hôte :

- appeler `AgentInboxRepository.markBackendUnavailable()` à l’échec d’un contrôle distant;
- recopier `AgentInboxRepository.backendObservation()` dans `AgentHomeState.backendObservation` avant de construire l’état HOME;
- appeler `ActionDispatcher.reconcileInterrupted()` au redémarrage;
- implémenter la lecture durable de chaque effet dans le `AgentActionPort` concret. Le comportement par défaut est `UNKNOWN`, sûr contre les doublons mais qui peut laisser une opération en attente de réconciliation.

Ces points de raccordement ne sont pas présentés comme exécutés. Les champs à fournir au HOME sont `directiveProvenance`, `backendState` et `lastSuccessfulContactAtUtcMillis`; l’ancien `agentStatus` reste une projection de compatibilité. L’écran Compose et `LauncherHomeScreen` n’ont pas été modifiés dans ce lot. Pour l’affichage final, l’UI doit présenter provenance et disponibilité séparément, et distinguer `UNVERIFIED` d’une panne observée.

Le normaliseur accepte les chiffres et les cardinaux français usuels; les secondes, « quart d’heure » et « heure et demie » demandent une reformulation au lieu d’une conversion implicite. Les propositions non-Focus ne sont pas modifiées par ce traitement.

## Validation

Commandes exécutées :

```sh
cd android
./gradlew --rerun-tasks :app:testDebugUnitTest

cd ../backend
.venv/bin/python -m pytest -q
```

- Android JVM : `BUILD SUCCESSFUL`, tâche `:app:testDebugUnitTest`.
- Backend : `60 passed, 1 skipped`; le test ignoré dépend du Gateway réel (`ATLAS_REAL_GATEWAY=1`). Un avertissement `DeprecationWarning` provient de `starlette.testclient` / AnyIO.
- `git diff --check` : aucune erreur.

## NON TESTÉ à la fin du lot C initial

- Aucun émulateur, test instrumenté Android, téléphone physique ou reboot réel.
- Aucun appel à OpenClaw/Gateway réel; le test réel pytest est ignoré sans configuration dédiée.
- Raccordement runtime des quatre points hôte listés ci-dessus.
- Validation visuelle du badge HOME et affichage explicite des trois états backend.

## Addendum — stabilisation du scénario OpenClaw réel (2026-10-19)

La comparaison de `ActionDispatcher` avec `21b6018` a isolé une régression du raccordement runtime : Lot C écrit maintenant `PREPARED` avant l’effet, puis revalide le mandat et les quotas. `AtlasController.agentVerificationContext()` fournissait au cooldown le dernier horodatage de toute entrée du journal. La revalidation relisait donc le `PREPARED` qu’elle venait d’écrire et refusait sa propre action comme trop récente; ce refus était ensuite journalisé en `COMPLETE/REJECTED`. Le cooldown ne retient désormais que `COMPLETE/EXECUTED`. Les entrées `PREPARED` restent protégées contre le rejeu et exigent une réconciliation; les effets refusés ou incertains ne déclenchent pas le cooldown.

`ActionDispatcherTest.pulledRemoteDirectiveSurvivesPreparedRevalidationAndIsPersisted` reproduit pull, acceptation, dispatch et restauration d’une directive `REMOTE_AI` avec son `sourceProposalId`. `onlySuccessfullyExecutedEffectsStartCooldown` vérifie qu’un effet réellement exécuté garde le cooldown, tandis que `PREPARED`, `NONE` et `REJECTED` n’en démarrent pas. Les tests d’idempotence, quarantaine, révocation et TTL existants restent exécutés dans la suite JVM.

Le scénario réel a aussi révélé que le client envoyait un ACK `executed` sans l’ACK `accepted` exigé par les transitions backend. Le client envoie maintenant `accepted → executed` et peut reprendre si le serveur a déjà avancé; deux tests HTTP couvrent la séquence et la reprise après `409`. Dernier résultat réel : [harnais OpenClaw](logs/recette-20261019/openclaw-real-fix/harness.log) et [logs backend filtrés](logs/recette-20261019/openclaw-real-fix/backend.log).
