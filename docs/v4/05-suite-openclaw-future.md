# Contrats d'extension futurs (contrats uniquement)

Le lot F ne connecte aucun LLM, OpenClaw, service d'agent ni outil externe. Le mock déterministe sert uniquement à rendre les contrats testables et l'interface démontrable hors ligne.

## Frontière de proposition

`CoachDecisionProvider.propose(context, mandate)` et `AgentAdapter` retournent seulement une `ProposedAction`. Le modèle contient un type textuel, des paramètres simples et une justification. Le client ne confie au fournisseur aucun accès Android, shell, ADB, base locale ou réseau.

Les seuls types compris par `MandateVerifier` sont `focus_session`, `suggest_break` et `open_content`. Chaque type a des paramètres en liste blanche et des bornes déterministes. Un type inconnu, un paramètre supplémentaire, un mandat révoqué ou une action absente du mandat est refusé côté Android.

## Validation et simulation

Le workflow relit le mandat local au moment de chaque proposition, exécute `MandateVerifier`, puis enregistre proposition et résultat dans le journal local. `simulationMode` est vrai par défaut. Ce lot n'a aucun exécuteur d'action ; même une proposition acceptée reste une proposition et demande un flux de confirmation et d'exécution séparé.

`DeterministicMockDecisionProvider` ignore le contexte textuel fourni et propose la même courte session de focus. Il n'appelle aucun modèle ou serveur. Il ne constitue pas une intelligence artificielle.

## Extensions reportées

Une implémentation ultérieure devra conserver ces contrats, expliciter provenance et rétention du contexte, révocation immédiate du mandat, confirmation pour tout effet externe et journal des décisions. La présence d'une interface ne signifie pas qu'un adaptateur OpenClaw ou LLM existe, ni qu'une action puisse être exécutée.

## Vérification finale H3

L'audit de fin de V4 confirme que ce document décrit des contrats uniquement. Aucun endpoint OpenClaw, processus agent, jeton, outil, shell, scheduler, LLM ou capacité d'action distante n'a été ajouté dans H3. Le mock déterministe reste local et ne consomme pas les données privées d'applications tierces.
