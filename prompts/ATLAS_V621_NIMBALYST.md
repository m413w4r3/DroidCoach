# Nimbalyst — ATLAS V6.2.1, reprise de fiabilité post-push

## Mission

Tu pilotes une mission de **consolidation uniquement** du dépôt `m413w4r3/DroidCoach`.

HEAD de référence audité : `819cdfd1223f87676403cf7fd4c043a97bd2354c` sur `main` (2026-10-20). Lire en premier `docs/v6-2/AUDIT_POST_PUSH_2026-10-20.md` fourni avec ce prompt, puis `AGENTS.md`, `docs/v6-2/LIVRAISON.md`, `CORRECTIONS_PRODUIT_FINALES.md`, `VALIDATION_FINALE.md`, `CONTRATS_INTERFACES.md` et les vrais fichiers source cités dans l'audit. Vérifier le HEAD réel avant toute modification ; si `main` a avancé, auditer les deltas et éviter d'écraser des corrections.

Ton objectif n'est ni de redessiner ATLAS ni d'ajouter V6.3 : **corriger les garanties de durabilité des données, de révocation et de proactivité vérifiées insuffisantes après V6.2**. Tu dois inspecter, implémenter, intégrer, tester et documenter, pas seulement proposer un plan.

Outils exécutants Nimbalyst : `openai-codex:gpt-6-luna`, `effortLevel: xhigh`, `notifyOnComplete: true`. **Maximum 3 sous-sessions simultanées**, propriété exclusive des fichiers, worktrees indépendants si possible. Vérifier les signatures réelles des outils Nimbalyst. Orchestrateur : revue et correction des diffs. **Ne pas push, merge, effacer de données ni modifier l'AVD personnel sans autorisation explicite.** AVD instrumenté jetable et dédié uniquement. Ne jamais écrire de secrets de Gateway dans la repo, les logs ou les prompts.

## Décisions d'architecture non négociables

1. **Conserver** Compose actuel, backend FastAPI, `/v2/agent/chat`, `/v2/agent/plan`, `/v2/agent/inbox`, modèles V2, mandat, dispatcher et mécanisme local Focus. Aucune nouvelle Gateway ou dépendance majeure.
2. **Conserver** `GoalRepository`, `AgentContextAssembler`, `ProactiveCoachScheduler`, `UsageObserver`, `LocationDisclosure` et `LauncherAppRules`; corriger à leurs frontières. Aucun fork tiers, aucun nouveau clone OSS.
3. **Ne pas ajouter** de permission d'arrière-plan de localisation, service IA permanent, AccessibilityService, service de notifications générales, kiosk, contournement de pause ou exécution shell par OpenClaw.
4. Toute écriture critique est confirmée, ou retourne un échec explicite. Un effet externe (notification, plan réseau dépendant d'une réservation, annonce « enregistré ») ne doit pas être déclenché sur un état simplement modifié en mémoire.
5. La révocation doit être **fail-closed**, sur le réseau maintenant et après redémarrage. Une erreur de stockage ne doit jamais restaurer silencieusement un mandat précédemment annulé. Si un échec physique rend impossible une garantie de durabilité, mettre le système en état agent désactivé, exposer une erreur explicite et ne jamais déclarer la révocation enregistrée.
6. Le work proactif ne fournit pas du temps réel : WorkManager peut différer les exécutions. Aucun texte utilisateur ne doit le promettre.

## Lot A — P0, persistance et révocations (bloquant)

**Agent A unique sur ce lot. Aucun autre agent n'édite `AtlasController.kt`, `GoalRepository.kt`, `GoalPanelCoordinator.kt`, `AgentContextCoordinator.kt`, le stockage local ou les classes de mandat.**

### A1 — contrat de persistance

Le code `AtlasController.persist()` actuel absorbe les exceptions de `store.write`, tandis que `persistProactiveCoachState()` renvoie toujours `true`. `GoalRepository` modifie son cache avant commit. Supprimer cette possibilité de faux succès.

Ajouter une frontière interne de durabilité, à interface explicite :

```kotlin
sealed interface PersistResult {
    data object Stored : PersistResult
    data object NotWritable : PersistResult
    data class Failed(val reason: PersistFailureKind) : PersistResult
}
```

Adapter aux conventions Kotlin du repo sans nouvelle bibliothèque. Ajouter `persistOrThrow()` ou un équivalent strict et documenté pour tout write métier critique. `persist()` UI non critique peut encore afficher une erreur sans propager, mais les appels critiques ne doivent plus l'utiliser.

Corriger `persistProactiveCoachState` : préparer le futur état, écrire durablement, publier seulement après succès; revert valeur locale en cas d'erreur. S'assurer que `ProactiveCoachScheduler.persist(state)` reçoit bien une exception de l'adaptateur en cas d'échec et ne réserve aucune requête/notif si rien n'est stocké.

Pour Goals : calculer `nextRecords` sans muter le cache, appeler le writer avec un sous-document nouveau, puis remplacer les records seulement après succès. Dans le callback du contrôleur, restaurer l'ancien `goalsSubdocument` si l'écriture du snapshot racine échoue. Les créations, modifications, changements de statut, confirmations et liens doivent être inclus. Les messages de réussite sont générés seulement après commit validé.

En initialisation, conserver le mécanisme différé corrigé en V6.2 : ne pas réintroduire l'accès prématuré à `habitLinkPolicy`.

### A2 — révocation fail-closed

Auditer `disableAgent`, `revokeAgentMandate`, `suspendAgentMandate`, `setAgentSharedCategory`, `setLocationSummaryConsent` et toutes les migrations de consentement. Scénario prioritaire : faute d'écriture persistante, révocation puis recréation du contrôleur. Choisir la correction minimale cohérente avec le stockage existant : `fail-closed` latch ou marqueur de révocation indépendant écrit avec `SharedPreferences.commit()` (si cette voie existe), et chargement prioritaire de cette barrière **avant** toute possibilité de réseau. La barrière ne doit pas être supprimée avant qu'un nouvel opt-in explicite et durable soit enregistré. Ne pas remplacer tout le stockage ATLAS.

Ajouter un gate centralisé devant les appels agent et devant l'exécution automatique des propositions. Revalider les dates/catégories au moment de sérialiser le payload. Conserver l'idempotence existante.

Tests JVM + instrumentés à faute injectée : échec d'écriture de goals, mode proactif, mandat, permission catégorie, force-stop/recreation, absence de commande réseau et de notification, absence de double dispatch et état d'erreur non trompeur. Si une garantie s'avère impossible en présence de disque en panne, préciser explicitement la limite et privilégier l'arrêt de toute capacité distante.

### A3 — diagnostic local

Remplacer le diagnostic exclusivement `Exception.simpleName` par un type de panne structuré (`SERIALIZATION`, `STORAGE_IO`, `INVALID_STATE`, `UNKNOWN`) et un point d'appel; journal borné aux derniers 20 événements, conservé localement sans données métier, stack contenant texte personnel, credentials ou payload. Tout test d'erreur doit vérifier l'absence de secrets et textes d'objectifs dans la trace.

**Stop gate Lot A :** refus de considérer le lot terminé tant que les fautes injectées et un test recreation ne prouvent pas l'absence de faux succès.

## Lot B — P1, confidentialité et données courantes

**Agent B, après stabilisation A, propriété :** `sync/agent/context/**`, interfaces de partage Coach, tests associés. En cas d'édition de `AtlasController.kt`, remettre une demande à l'intégrateur unique S6.

### B1 — dernier contexte partagé

`lastSharedAgentContextJsonSnapshot` est actuellement conservé intégralement dans `stateMap`. Définir un `LastContextDisclosureAudit` minimal `sentAtUtcMillis`, `categoryNames`, `successOrAttemptStatus`, **sans valeurs** pour la conservation durable. Autoriser l'aperçu complet du prochain payload uniquement pendant un consentement actif. Dès qu'une catégorie est retirée, effacer ses valeurs du cache/état sauvegardé ; en cas de révocation générale supprimer tout texte de l'ancien payload. Préserver une information d'audit utile sans afficher de secrets.

Ajouter migration du champ V6.2 `lastAgentSharedContext` : ne plus charger les valeurs d'une ancienne sauvegarde lorsque le consentement n'est plus actif. Tests de redémarrage et réinitialisation catégorie par catégorie. Ne pas prétendre supprimer une copie déjà reçue par le backend ou une notification passée : distinguer arrêt du partage et suppression de l'historique local.

### B2 — objectifs antérieurs au consentement

Comportement imposé : le consentement `goals` offre le choix « Inclure mes objectifs actifs existants ». Choix explicite, off par défaut pour un nouvel opt-in ; lors de son activation, l'état **courant** des objectifs actifs existants peut être envoyé, mais aucun événement Focus ou check-in créé avant la date de consentement. Les objectifs inactifs restent exclus et la limite 8 Kio/10 items demeure. Si le choix est refusé, garder le filtrage `updatedAt >= consentAt` existant.

Rendre la distinction visible dans l'écran de partage, avec texte court. Tests des deux réponses, d'un objectif très ancien encore actif, de l'édition ultérieure et de la révocation. Une case sans écriture persistante réussie ne doit pas ouvrir de partage.

## Lot C — P1, proactivité hors de l'Activity

**Agent C, après lot A, propriété :** `coach/proactive/**`, nouveau `usage/android/ProactiveCoachWorker.kt` ou `coach/proactive/android/**`, `ProactiveCoachNotificationPublisher.kt`, tests. `AndroidManifest.xml`, `AtlasController.kt` et `MainActivity.kt` réservés à S6 pour l'intégration.

### C1 — work indépendant

Créer `ProactiveCoachWorker` `CoroutineWorker` avec nom unique `atlas_proactive_coach`, cadence périodique de **30 minutes** et `ExistingPeriodicWorkPolicy.KEEP`. Ne pas mélanger au worker `atlas_usage_observation`. Scheduler l'enregistre uniquement si `CAP_AGENT_PROACTIVE`, mandat valide, opt-in et notification disponibles ; l'annule dès désactivation/révocation. Respecter Doze. Le worker lit les domaines et dates actuels depuis le contrôleur unique, enregistre `goal_review_due`, `habit_reminder_due`, `daily_plan_due`, puis lance le scheduler déjà présent. Les sources existantes `focus_completed`, `distraction_threshold_reached`, `mobility_session_completed` continuent à partir d'événements effectifs. Aucun scan permanent d'apps ou GPS.

Le `doWork()` doit attendre une décision de travail bornée, sans exécuter les appels HTTP sur le thread principal. Conserver le `ScheduledExecutorService` pour le transport réseau actuel, avec livraison du résultat au worker et timeout explicite. Une fermeture d'Activity, un redémarrage processus ou un changement de zone horaire ne peut pas contourner les consentements, quotas, 60 min cooldown et silence 22–08 h. Reconnexion ne doit pas rejouer un événement qui a déjà sa réservation durable.

### C2 — notification actionnable

Ajouter un `PendingIntent` explicite et immutable vers `MainActivity`, ouvrant l'écran Coach et la proposition créée. `MainActivity` ne doit prendre qu'un identifiant local validé et ne doit jamais l'interpréter comme commande d'exécution. Aucune acceptation automatique. Test de tap avec app fermée, état HOME par défaut, proposition expirée et mandat révoqué. Si proposition expirée, afficher l'état expiré, pas un bouton d'acceptation valide.

### C3 — tests

Horloge injectable pour frontière de jour/fuseau, silence, quotas, dédup après restart; `TestWorkerBuilder` ou environnement AndroidTest approprié, WorkManager fake ; succès et échec de réservation, échec de notification, réplication d'événements, statut de réseau et consentement retiré pendant HTTP.

## Lot D — P2, correction limitée UI/documentation

À lancer après A et indépendamment de C dans des fichiers réservés :

- `LauncherHomeScreen.kt` : remplacer `filteredApps.forEach` dans la `Column` scrollable du tiroir par une liste lazy à clés stables. Ne pas toucher à la partie HOME text-first, à la disposition permanente Apps/Coach/Pause/Plus ni aux contrats d'accessibilité. Tester 100 et 250 apps sans freeze et orientation paysage / fontScale 2×.
- `docs/v6-2/` : ajouter `AUDIT_POST_PUSH_2026-10-20.md` ; créer un `STATUT_V621.md` qui sépare les limites connues et les correctifs réellement validés. Mettre à jour les liens internes absolus historiques avec un addendum, sans effacer les rapports de recette de 2026-10-19/20. Éviter d'ajouter de nouvelles centaines de logs ou captures dupliquées : indexer les preuves représentatives.
- Vérifier issue GitHub #1 (localisation optionnelle) : n'envisager fermeture que si code et preuve couvrent ses critères ; sinon documenter précisément le reliquat. Ne pas fermer automatiquement.
- Toute correction esthétique supplémentaire doit être fondée sur capture et test, pas sur un jugement gratuit de l'agent.

## Orchestration et propriété des fichiers

Plan impératif :

| Phase | Propriétaire | Fichiers | Dépendance |
| --- | --- | --- | --- |
| S0 | Orchestrateur seul | Lecture, inventaire, tests initiaux | aucune |
| S1 | Agent A | stockage local, Goals, coordinator et contrats | S0 |
| S2 | Orchestrateur | intégration et test fault injection P0 | S1 |
| S3 | Agent B | Context Bridge, consentement UI, tests | S2 |
| S4 | Agent C | worker proactif, notif, tests | S2 |
| S5 | Agent D | LauncherHomeScreen et docs | S2, parallélisable B/C sous plafond 3 |
| S6 | Orchestrateur/intégrateur unique | `AtlasController.kt`, `AtlasApp.kt`, `MainActivity.kt`, manifeste, Gradle, toute interface partagée | S3/S4/S5 |
| S7 | Relecteur indépendant | Lecture, reproduction, tests, plan de correction | S6 |

Aucun agent B/C/D n'édite simultanément `AtlasController.kt`. Utiliser de petits commits locaux séparés par lot, sans push. L'orchestrateur relit et corrige chaque intégration. Si un problème réclame une modification au-delà des fichiers réservés, rédiger un patch/demande d'intégration plutôt que modifier le fichier commun. Les sous-agents ne redécident pas des responsabilités de couches ni des garanties de sécurité.

## Recette finale et preuves

**Baseline V6.2** : job GitHub Actions https://github.com/m413w4r3/DroidCoach/actions/runs/39384423568 réussi. Rejouer :

```bash
cd android
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
cd ..
backend/.venv/bin/python -m pytest backend/tests -q
```

Puis exécuter l'instrumentation uniquement sur un AVD jetable et identifié. Ne pas effacer d'appareil personnel. Partir du harnais `scripts/v62-complete-instrumentation.sh` après lecture de ses préconditions. Distinguer tests génériques, tests filtrés à préconditions et reprises ciblées. Conserver les tests Android V6.1 du launcher, Focus, consentements et usage.

Nouveaux scénarios E2E exigés :

1. Échec physique simulé de `LocalStateStore.write` pendant modification de Goal : l'UI n'affiche pas de réussite et l'état restauré reste cohérent.
2. Échec de persist proactif au moment de réserver : aucun appel agent ni notification n'est déclenché.
3. Révocation partage/mandat pendant échec de stockage et nouvelle Activity/process : aucune requête autorisée ni restauration silencieuse de l'ancien consentement.
4. Ancien payload local contenant des objectifs purgé/masqué après révocation et redémarrage.
5. Objectif préexistant : activation de partage avec/sans consentement explicite ; aucune histoire pré-consentement fuitée.
6. Worker proactif après disparition de l'Activity : trigger dû reconnu, quotas et silence respectés, aucune duplication après redémarrage.
7. Toucher notification → proposition Coach correcte, sans exécution automatique.
8. HOME/tiroir 320/360 dp, fontScale 2×, 250 apps simulées, footer accessible.
9. Si Gateway réel est disponible de façon sûre : refaire Focus 25/120 simulé → OpenClaw → proposition → ACK → révocation ; sinon marquer explicitement « non rejoué » et ne jamais simuler une preuve réseau réelle.
10. Si émulateur Android 17 ne peut toujours pas faire `screencap` avant installation, ne pas attribuer le blocage au code ATLAS. Pixel physique non accessible = non validé.

**Definition of done** : aucun faux succès durable, test de révocation fail-closed, CI verte, instrumentation API 34 sans régression, planification proactif démontrée sans Activity, notification navigable, purge de l'historique sensible documentée, correctifs dans `docs/v6-2/STATUT_V621.md`, et un rapport factuel `docs/v6-2/RECETTE_V621.md` avec les commandes et les tests réellement exécutés. Ne pas créer le tag V6.2.1 ou pousser sans instruction explicite.

**Exécute maintenant. Il s'agit d'une mission d'implémentation et de contrôle, pas d'un exercice de propositions architecturales.**
