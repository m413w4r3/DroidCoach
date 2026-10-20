# Revue indépendante V6.2.1 — S7

**Date :** 20 octobre 2026  
**HEAD relu :** 7719ab9  
**Base comparée :** 819cdfd  
**Périmètre :** lecture du diff V6.2.1, des décisions automatiques et des sources touchées; reproductions JVM seulement. Aucun code de production n'a été modifié.

## Verdict

La Definition of Done côté code **n'est pas satisfaite**. Un faux succès après échec du commit est reproduit sur une action Agent et une valeur de l'ancien payload reste dans la quarantaine d'un document mal formé. Un risque de dépassement du timeout du worker reste plausible. Le test JVM final est vert uniquement parce que les deux reproductions confirmées portent l'annotation @Ignore requise.

## Constats

### R1 — P0 confirmé : une action Agent peut être marquée réussie sans état racine durable

**Sources :** AtlasController.kt:1913-1922, AtlasController.kt:2455-2459, et le contrat tolérant de AtlasController.kt:3484-3521.

**Scénario :** une proposition HOME est acceptée alors que LocalStateStore.write échoue. Le port met à jour agentHomeStateSnapshot, appelle persist() qui retourne PersistResult.Failed, puis rend tout de même AgentActionOutcome(true). acceptAgentProposal réessaie avec le même persist() tolérant et planifie quand même l'ACK. Le journal d'effet peut donc conserver l'état terminé pendant que l'état métier racine n'a jamais été écrit.

**Reproduction :** V621ReviewFindingsTest.localAgentActionMustNotReportAppliedWhenRootCommitFails, annoté @Ignore("V621-REVIEW: R1"). Le test injecte un backend qui lève à l'écriture et vérifie que l'action ne doit pas être annoncée appliquée. Exécuté sans @Ignore, il échoue sur l'assertion attendue : “dispatch reported success although root persistence failed”. La première exécution non ignorée a terminé avec “286 tests completed, 2 failed”.

**Correctif proposé :** préparer l'état métier suivant, le committer par persistOrThrow, puis le publier et renvoyer applied=true. Si le commit échoue, restaurer l'état en mémoire, garder l'effet en réconciliation et ne pas envoyer d'ACK terminal.

### R2 — P1 confirmé : un ancien contexte sensible peut rester dans la quarantaine

**Sources :** AtlasController.kt:448-460, puis le transfert de StateReadResult.Corrupt.rawJson vers la quarantaine à AtlasController.kt:249-255.

**Scénario :** le document contient lastAgentSharedContext avec des valeurs d'objectif, ainsi qu'une erreur de syntaxe JSON. La migration ne peut pas parser l'objet; getOrDefault(raw) conserve donc la chaîne originale. Cette chaîne est ensuite copiée en quarantaine avant le remplacement du document.

**Reproduction :** V621ReviewFindingsTest.malformedLegacyContextMustBeRemovedBeforeQuarantine, annoté @Ignore("V621-REVIEW: R2"). Avec le test actif, l'assertion “the quarantine still contains the old shared goal” échoue. La même exécution non ignorée compte 286 tests, avec R1 et R2 en échec.

**Correctif proposé :** quand le JSON ne peut pas être analysé, ne pas conserver le document brut s'il peut contenir l'ancien champ. Quarantainer une enveloppe sans valeurs (raison, horodatage et empreinte), puis garder le stockage actif en lecture seule si nécessaire.

### R3 — P2 plausible : un plan peut continuer après le timeout annoncé du worker

**Sources :** ProactiveCoachWorker.kt:31-58, AtlasController.kt:860-880 et AtlasController.kt:1421-1459; AgentApiClient.kt:60-67 fixe le timeout de lecture HTTP à 65 secondes alors que le worker attend 60 secondes.

**Scénario suspecté :** à 60 secondes, withTimeoutOrNull annule le CompletableFuture attendu par WorkManager. L'événement et le drain du contrôleur sont exécutés séparément sur syncWorker; annuler le futur waiter ne prouve pas que le transport HTTP ou la suite du drain est annulé. Une réponse reçue après 60 secondes pourrait encore être traitée et produire une notification après que WorkManager a rendu success.

**Reproduction :** plausible, non confirmée par JVM. Le chemin implique le cycle de vie CoroutineWorker et un transport HTTP bloqué; l'environnement autorisait uniquement testDebugUnitTest, sans test WorkManager instrumenté ni émulateur.

**Correctif proposé :** propager l'annulation jusqu'au travail actif et au transport, puis revalider une génération d'annulation avant toute fusion locale et notification. Aligner le timeout HTTP sur le budget total du worker.

## Contrôles demandés

- **Persistance critique :** Goals, choix d'inclusion des objectifs existants, consentements, mandat, migrations et état proactif utilisent les chemins stricts ajoutés. Le faux succès R1 subsiste dans le port d'action et l'ACK.
- **Révocation :** le client HTTP commun contrôle requestAllowed pour chat/plan, inbox et ACK; les réponses asynchrones de chat/plan/inbox sont rejetées par génération après révocation. Aucun contournement réseau confirmé dans les chemins audités. Une requête déjà écrite avant révocation reste évidemment déjà envoyée; le callback tardif est rejeté.
- **Payload et export :** stateMap() et exportAllData() ne réémettent plus les anciennes clés détaillées; l'audit durable ne contient que date, catégories et statut. R2 révèle le résidu de quarantaine pour les blobs illisibles.
- **Diagnostics :** les appels Log examinés n'écrivent que le type d'erreur et le statut HTTP. Les utilisations de error.message trouvées alimentent des messages UI, pas les logs; aucun stack, token ou payload n'est journalisé dans les appels trouvés.
- **Worker et notification :** travail unique WorkManager de 30 minutes, contrôle d'éligibilité avant collecte, HTTP sur l'exécuteur dédié. L'intent est explicite et immutable; l'identifiant est un UUID local, vérifié dans l'inbox, et le tap ne déclenche pas d'acceptation. R3 reste le point non établi.
- **Tiroir HOME :** LazyColumn utilise appPackage comme clé et garde le footer hors de la liste. Le scroll sémantique n'a pas été validé visuellement ou par instrumentation dans cette revue JVM.

## Exécutions

- cd android && ./gradlew testDebugUnitTest, reproductions actives : échec attendu, 286 tests, 2 échecs (R1 et R2).
- Après ajout des annotations @Ignore exigées : ./gradlew testDebugUnitTest termine BUILD SUCCESSFUL; R1/R2 sont ignorés et les autres tests passent.
- Aucun émulateur, instrumentation, lint, assemblage, test backend, push ou merge n'a été lancé.
- Une vérification initiale de version depuis la racine n'a pas trouvé ./gradlew; le wrapper Android est sous android/.

## Contre-vérification — HEAD 46529a5

**R1 — corrigé dans les chemins audités.** Neuf actions qui écrivent un effet local passent par `persistAgentAction` : HOME directive/ordre, Focus proposer/planifier/démarrer/pause, habitude, proposition de politique et recommandation de contenu. `app_policy.adjust` est la dixième branche du port mais reste explicitement refusée, sans mutation. L'état métier et `appliedLocalAgentEffectKeys` partagent le même `persistOrThrow`; la publication en mémoire, le démarrage Focus, les alarmes et l'événement de sync arrivent après le commit. Une écriture racine échouée laisse le journal en `PREPARED`, sans ACK terminal; `reconcileInterrupted()` au démarrage lit le marqueur durable pour terminer le journal sans rejouer l'effet. Le test R1 est maintenant actif et passe. Aucune régression reproductible observée sur ce chemin.

**R2 — correction partielle; nouveau R4 P1 confirmé.** Pour un nom de clé littéral, le blob mal formé devient une enveloppe SHA-256 sans valeur. Dans ce document corrompu, `persistenceAllowed` reste vrai si la mise en quarantaine de l'enveloppe réussit (`recoveryReason == null || quarantineSucceeded`); en cas d'échec, l'écriture reste bloquée. Toutefois, la détection de la clé sensible cherche la sous-chaîne littérale `"lastAgentSharedContext"`. Un nom JSON équivalent écrit avec un caractère échappé (`"lastAgentSharedCon\u0074ext"`) échappe à ce filtre; si une autre partie du document est mal formée, le JSON brut contenant l'ancien objectif est copié en quarantaine. **R4 — P1 confirmé**, source [AtlasController.kt:449-461] : reproduction par `V621ReviewFindingsTest.malformedLegacyContextWithEscapedKeyMustBeRemovedBeforeQuarantine`, annoté `@Ignore("V621-REVIEW: R4")`. Activé, le test échoue sur « the quarantine still contains the old shared goal » (sortie : **290 tests, 1 échec**, `testDebugUnitTest`). Correctif proposé : pour tout JSON impossible à parser, mettre en quarantaine uniquement l'enveloppe sans valeurs; ne pas décider de la sensibilité par recherche de texte brut.

**R3 et intégration notification — corrigés pour le budget et le retrait de consentement observés.** Le budget est bien `4 s connexion + 65 s lecture + 30 s marge = 99 s`, inférieur aux dix minutes WorkManager; l'instance de production utilise les timeouts par défaut. Le test JVM vérifie cette relation et que l'expiration annule le waiter, sans relancer le travail. Le callback final de publication revérifie le mandat/capabilité proactive et les catégories des déclencheurs avant `show`. La décision HTTP reste hors du thread principal. Cela traite le décalage initial 60 s / 65 s et empêche une notification tardive après retrait déjà visible au contrôle final; le test JVM n'exerce pas le cycle complet WorkManager/HTTP/Android.

**Verdict Definition of Done : non satisfaite côté code.** R1 et le budget R3 sont étayés par le code et les tests JVM présents; R2 laisse le résidu R4. La contre-vérification n'a exécuté que `cd android && ./gradlew testDebugUnitTest`; aucun émulateur ni test instrumenté n'a été lancé, donc les critères E2E et instrumentés restent non vérifiés. Après annotation obligatoire de R4, la suite JVM finale passe; le rapport garde aussi la sortie de son exécution active en échec ci-dessus.
