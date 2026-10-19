# V6 — API Android du lot D

Cette note définit les surfaces que l'écran Coach et le futur HOME utilisent. Le contrôleur reste l'unique propriétaire de l'état; l'interface ne modifie pas directement les dépôts locaux.

## API publique du HOME

Le lot E lit `AtlasController.agentHomeState: AgentHomeState`.

```kotlin
data class AgentHomeState(
    val directive: AgentHomeDirective?,
    val focusProposal: AgentFocusProposalCard?,
    val contentRecommendation: AgentContentCard?,
    val cardOrder: List<String>,
    val cancelledFocusProposalIds: Set<String>,
)
```

- `directive` expose `title`, `body`, `primaryAction` (`focus.start`, `open_coach`, `none`) et `focusProposalId?`.
- `focusProposal` expose `proposalId`, `label`, `focusMinutes`, `breakMinutes`, `cycles`, `state` (`PROPOSED`, `PLANNED`, `RUNNING`, `PAUSED`, `CANCELLED`, `FINISHED`), `startAtUtcMillis?` et `routineId?`.
- `contentRecommendation` expose `contentId?`, `url?` et `title`. Le HOME peut présenter la carte; son ouverture demeure un geste explicite.
- `cardOrder` contient les cartes `directive`, `focus`, `content`. `completeCardOrder()` append les cartes omises dans l'ordre initial : un réordonnancement ne masque aucune carte, aucun favori ni secours.
- `cancelledFocusProposalIds` est un tombstone durable. Une action `focus.start` qui référence une session annulée reste refusée même après l'arrivée d'une nouvelle carte Focus.

Les mutations passent par `AtlasController.acceptAgentProposal`, `startAgentProposedFocus` et `cancelAgentFocusProposal`. Un démarrage agent ne navigue pas vers `FocusScreen` et ne lance aucune activité. La planification appelle le `AndroidRoutineProposalScheduler` existant, qui utilise une alarme inexacte et affiche une proposition à confirmer.

La structure complète est persistée dans `LocalStateStore` sous la clé JSON versionnée `agentHomeState`; le mandat est sous `agentMandate`. La boîte de réception et le journal d'effets sont des fichiers privés atomiques distincts sous `filesDir`.

## Coach et boîte de réception

- `AgentCoachScreen` appelle `pullAgentInbox()` à l'ouverture; le bouton « Actualiser la boîte de réception » rappelle cette méthode.
- Aucun WorkManager n'est présent dans le projet. Il n'y a donc pas de polling hors interface ni de changement manifeste.
- `sendAgentMessage()` poste `/v2/agent/chat` quand une URL HTTPS et le jeton Bearer V4 existants sont configurés. Le texte du message n'est envoyé qu'après le bouton « Envoyer »; le contexte distant contient les catégories consenties dans le mandat, vides par défaut. Aucun token n'est journalisé.
- Sans transport distant, l'adaptateur local déterministe crée une conversation et une proposition Focus locale. Il n'exige ni serveur, ni compte, ni secret.
- Les réponses/propositions sont ajoutées localement après parse strict. Le curseur, les propositions, leur état, le report local et les acknowledgements en attente sont persistés. Les duplicats sont détectés par `proposal_id` et `idempotency_key`; une proposition expirée devient terminale `expired`.
- L'API de pull attend `{"proposals": [...], "cursor": "..." | null}`. Cette forme explicite le choix Android pour la réponse GET, le contrat source ne nommant pas encore le champ de collection. Les propositions utilisent exactement l'objet v2 de `CONTRATS.md`.
- Les acknowledgements POST utilisent `{state, effect, at}` et restent en attente locale jusqu'au succès HTTP. Le client appelle `/v2/agent/proposals/{id}/ack` uniquement pour les états terminaux.
- `context_version` et les catégories autorisées sont affichées comme sources de contexte. Le serveur ne définit pas de tableau de citations détaillées dans Proposal v2.

## Révalidation et effets

`ActionDispatcher` appelle toujours le seul `MandateVerifier` avant effet. Celui-ci relit le mandat courant, sa version, révocation/suspension, expiration, TTL, capacités locales, fenêtre et quotas Focus, consentement UsageStats effectif, denylist vitale, cooldown et références de sessions annulées. `required_level` backend reste indicatif; le mode et les bornes du mandat local déterminent la confirmation.

Les dix valeurs d'action ont chacune une branche fermée. Aucun Intent, commande, nom de paquet ni destination d'ouverture n'est construit depuis une proposition. `content.recommend.url` passe la validation HTTPS YouTube déjà utilisée par la bibliothèque; le HOME n'ouvre pas la page automatiquement.

`app_policy.propose` conserve une suggestion locale. `app_policy.adjust` est refusée par la politique : le moteur Android actuel mesure des événements d'ouverture UsageStats, pas une durée quotidienne fiable. Un plafond quotidien fictif n'est donc pas appliqué; il faudra une capacité locale appropriée avant de l'exécuter. Les paquets de politique restent liés à l'allowlist réellement consentie et à la denylist vitale.

Le journal `AgentEffectJournal` garde les effets, états terminaux, clés d'idempotence et horodatages, sans message complet ni jeton. Il est consultable dans le panneau « Journal des effets ».

## Validation locale

- `cd android && ./gradlew testDebugUnitTest lintDebug assembleDebug :app:compileDebugAndroidTestKotlin` : succès; 125 tests JVM exécutés, 0 échec, lint et APK debug compilés, sources instrumentées compilées.
- `cd android && ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.atlas.coach.sync.agent.AgentContractsInstrumentationTest` : succès; 1 test instrumenté exécuté sur `emulator-5554` (AVD Android 17), 0 échec. Aucun appareil Pixel physique n'a été utilisé.
- Le client réseau a un test JVM avec un serveur HTTP stub local pour vérifier le Bearer, le chat, le pull et l'ack. L'intégration au backend du lot C n'a pas été exécutée ici.

Limite connue : `app_policy.adjust` reste refusée jusqu'à ce qu'Android dispose d'une mesure fiable de durée quotidienne. Aucun WorkManager n'est installé; le pull a lieu à l'ouverture Coach et à la demande.

