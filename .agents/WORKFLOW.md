# ATLAS — Contrat d'orchestration Paseo

Ce document fixe **la méthode d'exécution agentique**, pas le périmètre produit. Le prompt maître actif définit les fonctionnalités.

## Rôles et profils

| Rôle | Modèle effectif | Autorité |
|---|---|---|
| Orchestrateur parent Paseo | Modèle choisi par l'utilisateur, par exemple GPT-6.1 Sol Medium | Découpe, supervise, contrôle, intègre localement et demande les autorisations |
| **TOUS** les sous-agents (exploration, diagnostic, implémentation, QA, correction, revue) | Provider **Codex** ; modèle **`gpt-6-luna`** ; effort **`xhigh`** | Un ticket borné, sans sous-délégation |
| Relecteur indépendant | Nouvelle sous-session **`gpt-6-luna` / `xhigh`**, différente de l'auteur | Lecture seule effective, avis motivé |

**Aucun enfant Luna High/Medium ou d'un autre fournisseur.** Si Paseo n'offre pas ce couple modèle/effort, marquer `BLOCKED` et demander une résolution au propriétaire, sans remplacement silencieux. Le repo ne configure **pas** les profils hôte Paseo : vérifier le provider/modèle/effort réels **dans les métadonnées des sessions**.

**Concurrence :** 6 enfants actifs **maximum au total** , 1 propriétaire par fichier, 1 intégrateur parent, 1 seul propriétaire ADB/Pixel/AVD à la fois. Les agents enfants peuvent créent d'autres agents.

## Pré-vol de chaque chantier

1. Lire `AGENTS.md`, la mission et les skills utiles ; identifier les contrats et documents actuels.
2. Vérifier `pwd`, `git status --short`, `git rev-parse HEAD`, worktrees, accès réseau/shell/ADB, outils et CI. Préserver tout état utilisateur existant.
3. Fixer une base SHA complète, une **nouvelle branche**, les critères de sortie et la matrice des tests. Ne pas travailler directement sur `main` pour les modifications.
4. Créer **un registre unique de tâches** dans le dossier de preuves de la mission. États : `READY -> RUNNING -> REVIEW_REQUIRED -> ACCEPTED -> INTEGRATED` ou `CORRECTION_REQUIRED / BLOCKED`.
5. Produire des tâches **verticales et vérifiables**, de préférence une question/correctif par nouvelle session. Maximum 3 tâches indépendantes actives. Un ticket sans oracle ou avec fichiers partagés n'est pas lançable.

## Contrat de délégation obligatoire

Utiliser `.agents/TASK_TEMPLATE.md`. Toute session reçoit :
- `TASK_ID`, objectif observable, dépendances et catégorie (scout/test/patch/revue).
- `BASE_SHA` exact, scope de lecture, **scope d'écriture exclusif**, worktree et droits réels.
- `MODEL=codex:gpt-6-luna`, `EFFORT=xhigh` ; vérifier la session réellement lancée.
- **Oracle** : test discriminant, condition de réussite/échec, ou grille d'examen explicite si tâche de revue.
- Interdictions, stop conditions, références à lire et livrables (diff, commit SHA, tests, preuves).
- `notifyOnComplete` ou mécanisme d'événement **réellement disponible**.

## Réception événementielle, corrections et intégration

1. À chaque notification de fin, lire diff/commit, scope, tests et limites. **Notification != ACCEPTED**.
2. Corriger un défaut avec une demande ciblée à la même sous-session si approprié, sinon créer **une nouvelle Luna XHigh** avec handoff SHA ; aucune double intégration.
3. Maximum **3 tentatives** par défaut/oracle ; au bout de 2 sans amélioration, revue indépendante.
4. Le parent approuve le patch puis **l'intègre localement** ; il relance le test sur le **SHA intégré** et fait revoir le résultat après toute correction P0/P1.
5. Aucun push, PR, merge distant, clôture d'issue ou action sensible sur Pixel sans autorisation **explicite et spécifique** de l'utilisateur.
6. En cas de coupure : enregistrer session IDs, statuts, base/output SHA, prochains oracles ; à la reprise, réconcilier avec Git et les sessions **une seule fois par événement**, sans rejouer des tâches terminées.

**INTERDIT :** `while sleep`, polling récurrent de statuts, délais répétés pour attendre les agents, relance systématique des enfants, auto-approbation ou délégation récursive. Si Paseo ne peut pas réveiller automatiquement le parent, sauvegarder un handoff et rendre la main jusqu'à reprise explicite.

## Gates de validation

- `git diff --check`, diff borné, aucun secret/donnée utilisateur dans commit ni trace.
- Test capable de détecter le bug **avant** correction si raisonnablement reproductible ; régression ciblée puis suites JVM/lint/build/backend concernées.
- Résultats `PASS / FAIL / BLOCKED / NOT_RUN` **liés à un SHA exact**, environnement et commande ; FakeClock != temps réel ; mock != OpenClaw réel.
- **Android 17/API 37 Pixel physique** est la cible de clôture V6.2 ; **ne pas lancer la suite instrumentée Android 14**. Un émulateur Android 17 bloqué par `surfaceflinger` n'invalide pas la preuve obtenue sur Pixel, mais n'est pas marqué PASS.
- Le skill `android17-device-qa` encadre toutes actions sur téléphone : ADB unique, pré-vol lecture seule, sauvegarde durable, autorisations ciblées pour rôle HOME, permissions, installation et services ; jamais `pm clear` ou `uninstall` par défaut.
- Vérifier intégrité du stockage, mandat/consentement/révocation, Pause d'urgence et usage hors connexion ; vraie proposition OpenClaw si la clôture l'exige.
- Toute adaptation de `references/repos/` comporte SHA amont, licence et mention dans `THIRD_PARTY_NOTICES.md`; ne rien importer pour un objectif cosmétique.
- **Revue indépendante** sur le SHA final, sans P0 ouvert. Le parent présente `GO/NO-GO`, preuves et limites. Une dérogation P1 est soumise au propriétaire.

## Smoke-test Paseo avant campagne physique

Tester d'abord sur code non sensible : (1) une Luna XHigh en lecture seule avec vérification du modèle/effort et absence d'écriture ; (2) deux enfants dans des worktrees différents ; (3) réception d'une notification ou handoff explicite ; (4) revue du parent avant intégration. Si l'hôte ne supporte pas un élément, consigner `BLOCKED`, ne pas prétendre qu'il fonctionne.

Les compétences sont sous `.agents/skills/`. Le dépôt peut fournir un `.codex/config.toml` de secours pour les sous-agents Codex **natifs**, mais **cela n'installe pas les profils Paseo**, et la délégation native ne doit pas être imbriquée dans les enfants Paseo.
