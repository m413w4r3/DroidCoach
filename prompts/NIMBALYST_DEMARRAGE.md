# Message court à envoyer à l'orchestrateur Nimbalyst

Tu es l'orchestrateur principal d'implémentation d'ATLAS. Lis `README.md`, `AGENTS.md`, `docs/08-phase1-preanalyse.md`, `docs/09-plan-lots-et-recette-vm.md`, puis `prompts/AGENT_PRINCIPAL.md` (V3), et exécute ce dernier.

**Ne recommence pas l'audit général open source : la présélection et les URLs des repos ont été documentées dans `docs/08-phase1-preanalyse.md`.** Dépense l'essentiel de l'effort sur du code qui compile et sur les tests.

Je suis sous Linux et Android Studio est installé dans `/opt/android-studio/bin` ; détecte le véritable chemin du SDK Android et les AVD existants. La priorité absolue est VM-0 puis VM-1 : APK ATLAS compilé, installé, lancé et testé sur un émulateur quand l'environnement le permet. Interface d'inspiration Olauncher, application autonome sans clé IA ni serveur.

**Ton rôle est d'orchestrer, pas de réaliser seul toute l'implémentation.** Délègue les lots S0 à S5 prévus au plan en utilisant le MCP Nimbalyst `spawn_session` ; configure chaque session avec `model: "openai-codex:gpt-6-luna"`, `effortLevel: "xhigh"`, `notifyOnComplete: true` si le schéma du tool accepte ces valeurs. Vérifie la signature exacte des outils avant appel ; ne simule jamais leur utilisation.

Pour chaque session, fixe les chemins autorisés/interdits, les dépendances, les entrées/sorties et les tests de réception. Limite la concurrence et évite les fichiers partagés. Utilise les outils Nimbalyst de suivi pour récupérer les résultats, inspecter les diffs et demander les corrections. Rends les preuves de compilation, émulateur et tests ; indique explicitement tout jalon `NON TESTÉ` s'il manque un prérequis.
