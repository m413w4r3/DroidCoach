---
name: implement-spec
description: "Exécuter un correctif ou une tranche verticale ATLAS autorisée avec tests et intégration contrôlée dans Paseo."
disable-model-invocation: true
---

# Modification bornée ATLAS

Le parent orchestrateur décide les priorités, l'arbre de dépendances, les autorisations et l'intégration. **Toutes les sous-sessions sont GPT-6 Luna XHigh, sans sous-délégation** (voir `.agents/WORKFLOW.md`). Une mission = un résultat testable dans un contexte frais.

Avant la sous-session :
- Confirmer base SHA, branche de travail, propriété exclusive des fichiers, worktree, état Git initial et résultat attendu.
- Donner 3 à 8 chemins pertinents, une spécification précise, un oracle exécutable et les interdictions de la mission.
- Sélectionner réellement via Paseo le provider Codex, modèle `gpt-6-luna`, effort **`xhigh` obligatoire pour TOUS les enfants**, et vérifier sa métadonnée effective ; en cas d'indisponibilité, `BLOCKED` et jamais fallback High.

Pendant :
1. Vérifier les chemins et refuser un scope implicite hors mission.
2. Faire échouer un test ciblé si le problème est reproductible.
3. Appliquer le patch minimum, exécuter test ciblé, JVM/lint/backend pertinents et analyser les échecs.
4. Produire commit **local au worktree** avec SHA, fichiers changés et preuve des tests. Pas de push.
5. Notifier le parent de la fin, sans boucle de polling.
6. Le parent inspecte le patch ; si défaut, reprend la session ou crée une tâche corrective précise. Une notification `done` ne constitue pas une acceptation.

Éviter les changements simultanés de `AtlasController.kt`, manifeste, fichiers Gradle, navigation et thème par plusieurs agents. Une seule session intégratrice détient les fichiers transversaux. **Aucune** fusion distante, déploiement ni action sur le Pixel en dehors du skill `android17-device-qa`.
