# ATLAS — directives permanentes aux agents de code

## Orchestration Paseo obligatoire

- **Un seul orchestrateur parent Paseo**, modèle choisi par l'utilisateur (ex. GPT-6.1 Sol Medium). Le parent découpe, délègue, reçoit les notifications, vérifie, intègre localement et sollicite les accords.
- **TOUS les sous-agents** (scouts, implémenteurs, diagnostics, testeurs, correcteurs et relecteurs) sont **Codex GPT-6 Luna XHigh** : `codex:gpt-6-luna`, `effort=xhigh`. Pas de High/Medium de substitution ; en cas d'indisponibilité : `BLOCKED`. Vérifier le modèle/effort effectif de chaque session.
- **6 enfants simultanés au maximum**, aucune sous-délégation, worktrees séparés, fichier possédé par un seul agent, un opérateur ADB/Pixel/AVD à la fois. Les relecteurs doivent être techniquement en lecture seule.
- **Lire `.agents/WORKFLOW.md`**, appliquer le contrat `.agents/TASK_TEMPLATE.md`, et charger le skill pertinent depuis `.agents/skills/README.md`. Chaque ticket exige base SHA, scope, permissions, oracle et preuve. Une notification n'est pas une validation.
- Notifications de fin et reprise événementielle ; **jamais de boucle `sleep` ou polling de statut**. Si le réveil n'existe pas, handoff et reprise explicite.
- Clôture V6.2 : **Android 17/API 37 Pixel physique**, pas de suite Android 14 ; JVM, lint, build, backend et contrats restent obligatoires. Les actions sensibles sur téléphone demandent consentement ciblé et sauvegarde, selon `android17-device-qa`.

## Invariants produit

- Lire d'abord README et docs, puis établir un inventaire de l'environnement (`git`, JDK, Android SDK, Python, outils de test) et des contraintes.
- Ne jamais supposer qu'un appareil Pixel réel est connecté. Utiliser émulateur / tests unitaires pour les résultats automatisables, signaler clairement tout test non exécuté.
- Construire par **vertical slices** exécutables, compiler et tester à chaque étape, plutôt que produire simultanément une multitude de modules inachevés.
- Garantir un mode de démonstration **sans serveur**, avec stockage local et un adaptateur IA simulé déterministe ; aucun abonnement, identifiant ou secret ne doit être requis pour montrer l'interface.
- Ne pas créer de root, Device Owner, kiosque, installation silencieuse, shell système privilégié, manipulation d'APK YouTube/ReVanced ou escalade de permissions.
- Pas d'AccessibilityService dans le MVP par défaut : mener un audit séparé avant toute expérimentation, avec consentement, tests sur téléphone secondaire et revue des politiques de distribution. `UsageStatsManager` n'autorise pas à promettre une interception exacte et instantanée.
- L'IA émet des **propositions** ; seules des règles déterministes consenties et vérifiées exécutent les actions Android. Les opérations externes significatives requièrent confirmation spécifique.
- Une pause d'urgence, une désactivation du suivi et un retour au launcher d'origine doivent rester accessibles, même hors ligne.
- Respecter la séparation entre code propriétaire ATLAS, références tierces et code effectivement repris. Tout emprunt doit mentionner origine, commit, fichiers et licence. En cas de doute juridique : ne pas copier.
- Aucune donnée intime ou écran complet sans nécessité et consentement. Ne pas utiliser les données personnelles dans des exemples/fixtures.
- Toutes les fonctionnalités optionnelles doivent être activables/désactivables indépendamment via capability flags.
- Documenter les hypothèses, commandes lancées, sorties de tests, limites et travaux restant à faire. Ne jamais annoncer « testé sur Pixel » sans appareil effectivement testé.
- Si une intégration externe manque d'API autorisée, construire un adaptateur `unsupported` propre et préserver un flux principal fonctionnel, sans bidouille fragile.
- Ne pas poser de question bloquante pour une décision réversible : choisir un défaut explicite et tracer ce choix dans `docs/DECISIONS_AUTOMATIQUES.md`.
- Les skills sont des procédures conditionnelles, **pas** des autorisations de shell, ADB, réseau ni modification GitHub. Toute QA Android 17 physique doit suivre `android17-device-qa`, sans action destructive ni permission implicite.
