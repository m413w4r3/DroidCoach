# ATLAS V6.2 — audit externe après push

**Date :** 20 octobre 2026  
**Dépôt :** https://github.com/m413w4r3/DroidCoach  
**HEAD audité :** `819cdfd1223f87676403cf7fd4c043a97bd2354c` (`main`, « ATLAS V6.2 »)  
**Base comparative :** `ee1e7b58c06531f6fdc631a42cdd7522d8ee90e1` (V6.1)  
**Méthode :** inspection à distance des fichiers de production, des modèles backend, des tests, des documents et des jobs GitHub Actions via le connecteur GitHub. Aucun clone, build Android local, essai sur émulateur ou téléphone, ni revue visuelle directe des PNG dans cette passe. Les résultats de recette ci-dessous sont ceux consignés dans le dépôt.

## Synthèse de décision

**V6.2 : évolution fonctionnelle substantielle, compilation/CI saine, mais pas encore une base de fiabilité suffisante pour étendre l'autonomie.** Faire une **V6.2.1, lot de consolidation**, avant V6.3. Ne pas ajouter pour le moment `ACCESS_BACKGROUND_LOCATION`, service IA permanent, accessibility surveillance, géofences, réécriture de `AtlasController` ou runtime Gateway sur Android.

## Éléments acquis et preuves

- Code présent : HOME text-first (`LauncherHomeScreen.kt`), objectifs persistés (`goals/`), `AgentContextAssembler` et revalidation au moment de l'écriture (`AgentContextCoordinator.kt`), backend `SharedContext` avec 13 catégories et minimisation, scheduler proactif, worker UsageStats et acquisition/coarsening Android dérivés d'OpenClaw.
- CI du commit final : **succès**, job `verify` incluant tests JVM, lint, build Android et tests backend : https://github.com/m413w4r3/DroidCoach/actions/runs/39384423568 .
- Tests consignés dans `LIVRAISON.md` : **256 JVM**, **66 Python réussis/1 ignoré**, **48 instrumentés API 34**, **10 reprises ciblées** (une partie rejoue des méthodes déjà comptées). E2E réel OpenClaw → Android avec Focus **simulé** de 25/120 min, puis révocation ; ce n'est pas un benchmark de 25 minutes réelles.
- Corrections antérieures effectivement visibles : reconstitution fraîche du contexte via `AgentContextCoordinator.requestContext()`, suppression des catégories vides dans `SharedContext.minimized()`, filtrage des objectifs inactifs, coarsening non répété dans `LocationRepository`, mise en pause/rendu Focus quand capability désactivée, rafraîchissement des interventions observées.
- Limites documentées : Android 17 non testé à cause d'un émulateur avec captures cassées **avant installation ATLAS** ; mobilité mesurée non reproductible ; TalkBack partiel ; aucune validation Pixel physique ou consommation batterie quantitative.

## Constats prioritaires

### P0-A — Un échec de persistance peut être traité comme un succès

**Sources :** `android/app/src/main/java/dev/atlas/coach/app/AtlasController.kt`, méthodes `persist()` et `persistProactiveCoachState()` ; `coach/proactive/ProactiveCoachScheduler.kt`, `persist()` ; `goals/GoalRepository.kt`, `persistCurrent()` et méthodes de mutation ; `app/GoalPanelCoordinator.kt` et `goalAction()`.

`AtlasController.persist()` capture les exceptions d'`store.write` et revient normalement. `persistProactiveCoachState()` met à jour `proactiveCoachStateValue`, appelle `persist()` puis renvoie toujours `true`; le scheduler traduit ce chemin comme une écriture confirmée. `GoalRepository` modifie sa carte en mémoire avant de demander le commit ; `goalAction()` peut afficher un succès alors que l'état n'est pas sur disque. Une erreur d'écriture distincte a été observée dans `CORRECTIONS_PRODUIT_FINALES.md` (`Unable to write local state (IllegalStateException)`) sans diagnostic du chemin d'échec.

**Risque démontré statiquement :** fausse durabilité ; quotas/idempotence et objectifs possiblement incohérents après redémarrage. **Risque à reproduire :** réapparition d'un ancien mandat ou consentement si une révocation n'est pas durable.

**Correction imposée :** `PersistResult` explicite (succès, état non-écrivable, échec). Un chemin de commande critique doit utiliser `persistOrThrow` et ne pas annoncer le succès avant validation du commit. Transactionner les modifications de Goals et du state proactif : construire l'état suivant, vérifier l'écriture, puis publier l'état en mémoire ; rollback si échec. Les révocations/arrêts doivent rendre le réseau fail-closed immédiatement et au redémarrage, avec un marqueur de refus durable indépendant si nécessaire. Aucun token ni contenu utilisateur dans les diagnostics.

**Tests nécessaires :** `LocalStateStore.write` injecté en échec, objectif créé/modifié, quota proactif, notification et demande agent interdits avant commit, révocation de catégorie/mandat, fermeture/recréation de contrôleur, comparaison état mémoire/disque, restauration sans double effet. Enregistrer un résultat d'écriture avant toute externalité (réseau, notification) dépendante.

### P0-B — Révocation : durabilité et données résiduelles

**Sources :** `AtlasController.kt`, `disableAgent`, `revokeAgentMandate`, `setAgentSharedCategory`, `onContextWritten`, `stateMap`; `AgentContextCoordinator.kt`.

L'agent dispose bien de contrôles de révocation **au moment de l'envoi** et d'un assemblage actualisé. En revanche, le masque de révocation peut ne pas survivre à une erreur d'écriture (voir P0-A). Par ailleurs `lastSharedAgentContextJsonSnapshot` est sauvegardé et restauré comme texte complet, mais aucune purge de ses valeurs n'est visible lors des révocations.

**Choix produit :** dès qu'une catégorie est révoquée, effacer ses valeurs du dernier payload conservé ; lors d'une révocation générale supprimer le payload complet. Garder uniquement le timestamp, la liste de catégories envoyées et un statut d'audit. Tests de persistance/recréation et non-réexposition dans l'UI ; vérifier que les prochaines requêtes restent vides après révocation. Classer la rétention locale des valeurs en P1, mais la durabilité de la révocation en P0.

### P1-A — Proactivité non durable hors affichage d'ATLAS

**Sources :** `AtlasController.kt`, `onHostResumed`, `recordDueGoalReviews`, `recordDueHabitReminders`, `recordDailyPlanDue`, `queueProactiveRun`; `usage/android/UsageObservationWorker.kt` ; `app/AtlasApp.kt`.

Le worker périodique de 30 minutes appelle **uniquement** `pollUsageObservation()`. La génération de plusieurs événements proactifs repose sur `onHostResumed` et des actions locales. Une absence prolongée d'ouverture d'ATLAS peut donc différer la revue d'objectifs et le plan quotidien jusqu'au retour de l'utilisateur.

**Correction imposée :** worker indépendant `ProactiveCoachWorker` avec travail périodique unique, intervalle initial 30 min, enregistré uniquement quand capability/mandat/notifications l'autorisent ; évaluation des triggers locaux puis `runDue`. La transaction et les gates de P0 doivent précéder cette activation. Recontrôler les droits au moment d'envoyer. Ne promettre aucune heure exacte (Doze, WorkManager). Aucune nouvelle collecte Android, aucune permission d'arrière-plan et aucun service IA constant. Tests de réveil processus froid, reboot, délai, silence 22–08 h, changement de fuseau et révocation.

### P1-B — Notification proactive non navigable

**Source :** `app/ProactiveCoachNotificationPublisher.kt` : notification sans `setContentIntent`.

**Correction :** `PendingIntent` explicite et immutable ouvrant `MainActivity` dans la destination Coach, avec proposition identifiée ; aucune acceptation ni dispatch automatique par tap. Tester depuis état app fermé, HOME role actif et Agent indisponible.

### P1-C — Objectif existant non partagé immédiatement après nouveau consentement

**Source :** `sync/agent/context/AgentContextAssembler.kt`, `addGoalCandidates` filtre `goal.updatedAt in consentAt..now`.

Un objectif actif créé avant l'activation du partage `goals` n'entre pas dans le contexte tant qu'il n'est pas modifié. C'est cohérent avec une lecture absolue « pas de données historiques avant consentement », mais peu lisible pour le produit : après avoir activé « Partager mes objectifs », l'utilisateur peut recevoir un Coach qui ne connaît aucun objectif.

**Décision de conception :** séparer l'**état courant** d'un objectif de son **historique d'événements**. Au moment où l'utilisateur active `goals`, afficher une confirmation « Inclure mes objectifs actifs existants » ; en cas de oui, autoriser leurs attributs courants sans transférer les historiques antérieurs au consentement. Garder le filtrage des sessions/check-ins historiques inchangé. Ajouter tests « objectif ancien sans nouvelle édition », « refus d'inclure existants », « révocation » et « multi-catégories ».

### P1-D — Observabilité des erreurs de stockage insuffisante

**Source :** `AtlasController.persist()` ; rapport `CORRECTIONS_PRODUIT_FINALES.md`.

Le journal actuel ne fournit que la classe d'exception. Le défaut observé n'est pas reproductible à partir de cette information.

**Correction :** codes d'erreur structurés, étape/fonction appelante et diagnostic minimal non sensible, avec stockage local borné (par exemple les 20 derniers états d'échec : type, emplacement de code, horodatage). Ne jamais journaliser payloads, objets d'objectif, catégories de contexte détaillées, token, URL privée ou stack contenant des données utilisateur. Éviter de masquer la cause et présenter un message utilisateur non mensonger.

### P2-A — Tiroir HOME : rendu non paresseux

**Source :** `app/LauncherHomeScreen.kt` : `filteredApps.forEach` dans une `Column.verticalScroll`.

**Correction :** remplacer uniquement le parcours tiroir par un composant lazy (`LazyColumn`/`LazyVerticalGrid` selon contraintes) avec clés stables ; conserver le footer permanent, le HOME textuel et les gestures. Bench de scroll sur fixture 100/250 apps et contrôle 320 dp/2×. Pas de refonte du design.

### P2-B — Concentration excessive dans `AtlasController`

`AtlasController.kt` contient environ 3 055 lignes, avec logique de persistance, UI state, sync, proactivité, Focus, goals et lifecycle. Ce n'est pas un défaut immédiat justifiant un rewrite.

**Correction ultérieure :** isoler un petit `DurableStateCoordinator` et des contrats de transaction après P0 ; ne pas remplacer la structure de l'app, les repositories, ni déplacer les grandes portions de code en même temps que les réparations fonctionnelles.

### P2-C — Documentation et poids du dépôt

- `docs/v6-2/LIVRAISON.md` parle encore d'un checkout non commité à la base V6.1 : conserver le rapport historique mais ajouter un statut post-push daté et le HEAD V6.2.
- `docs/v6-2/REVUE_INDEPENDANTE.md` possède des liens absolus `/home/nill/...` et des constats historiques corrigés ensuite. Ajouter un tableau d'issues `corrigé/à confirmer/ouvert` et des liens relatifs.
- `docs/v6-2/` comprend 797 fichiers suivis au HEAD, dont 605 sous `logs/` et 168 captures PNG. Conserver les preuves nécessaires, mais éviter les nouveaux duplicatas volumineux ; publier un index de captures représentatives et archiver les preuves annexes sans effacer unilatéralement l'historique.
- Les clones `references/repos/**` ne sont pas suivis (normal). Ne pas importer un nouveau projet tiers en V6.2.1.
- L'issue GitHub #1 (localisation à la demande) reste ouverte malgré l'implémentation ; vérifier son périmètre et la fermer seulement avec lien vers tests et réserves.

## Anomalies NON revendiquées comme actives

Les défauts de V6.2 intermédiaire concernant context snapshot périmé, clés vides backend, coarsening répété, statut des objectifs inactifs et fuseau proactif ont été corrigés dans les fichiers actuellement lus. Les historiques de `REVUE_INDEPENDANTE.md` ne représentent pas l'état final.

## Ordre V6.2.1

1. **Lot A (bloquant)** — stockage réellement durable + transactions Goals/Proactive + révocation fail-closed + tests de fautes/restarts.
2. **Lot B** — effacement de valeurs du dernier contexte partagé et consentement explicite sur les objectifs préexistants.
3. **Lot C** — worker proactif différé, intents des notifications, garanties de consentement en processus froid.
4. **Lot D** — performances du tiroir, observabilité bornée, docs/CI et recettage Android 14/17 selon environnement.

Ne pas lancer B/C sur une couche de persistance encore non fiable. Conserver le rythme max. trois exécutants Nimbalyst aux frontières de fichiers exclusives.

## Critères de sortie

- CI JVM/lint/build/Python verte, aucune régression aux comptes V6.2 (256 JVM, 66 Python réussis, instrumentation API34 baseline 48 cas), nouvelles suites de test PASS ; compter les reprises instrumentées distinctement.
- Injection d'échec d'écriture : zéro fausse confirmation, zéro effet externe avant commit, état cohérent après recréation.
- Révocation persistante/fail-closed ; aucun envoi post-révocation et aucun payload résiduel détaillé localement après purge.
- Travail périodique du Coach réellement en file en arrière-plan et testable après fermeture de l'Activity ; latence annoncée honnêtement.
- Notification mène au Coach et à la proposition correcte, sans auto-exécution.
- Scénario Focus simulé + objectif 25/120 + OpenClaw réel + révocation réexécuté si Gateway et secrets de test isolés disponibles ; autrement marquer « non rejoué ».
- Android 17 et Pixel physique restent « non vérifiés » tant qu'un essai réel n'a pas abouti ; ne pas prétendre résoudre le crash SurfaceFlinger côté ATLAS.
- Documents `docs/v6-2` mis à jour sans supprimer les rapports historiques ; synthèse post-push et matrice de preuves actualisées.

## Renvois

- CI : https://github.com/m413w4r3/DroidCoach/actions/runs/39384423568
- Livraison : https://github.com/m413w4r3/DroidCoach/blob/819cdfd1223f87676403cf7fd4c043a97bd2354c/docs/v6-2/LIVRAISON.md
- Corrections : https://github.com/m413w4r3/DroidCoach/blob/819cdfd1223f87676403cf7fd4c043a97bd2354c/docs/v6-2/CORRECTIONS_PRODUIT_FINALES.md
- Revue intermédiaire : https://github.com/m413w4r3/DroidCoach/blob/819cdfd1223f87676403cf7fd4c043a97bd2354c/docs/v6-2/REVUE_INDEPENDANTE.md
- Validation : https://github.com/m413w4r3/DroidCoach/blob/819cdfd1223f87676403cf7fd4c043a97bd2354c/docs/v6-2/VALIDATION_FINALE.md
- Android 17 : https://github.com/m413w4r3/DroidCoach/blob/819cdfd1223f87676403cf7fd4c043a97bd2354c/docs/v6-2/RAPPORT_ANDROID17.md
