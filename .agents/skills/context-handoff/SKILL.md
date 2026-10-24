---
name: context-handoff
description: "Reprendre proprement un chantier Paseo interrompu, transférer à une session fraîche ou réconcilier les notifications."
---

# Handoff reproductible

Créer ou actualiser un état bref dans la documentation du chantier, sans texte de conversation ni logs volumineux.

Champs requis :
- `TASK_ID`, objectif précis, autorisation, risques et actions encore soumises à consentement.
- `BASE_SHA`, `RESULT_SHA`, branche, worktree, `git status --short`, fichiers détenus.
- Sessions Paseo : identifiants, modèle effectivement constaté, statut et notifications déjà consommées.
- Tests : commande, environnement, SHA, `PASS/FAIL/BLOCKED/NOT_RUN`, lien vers preuve **expurgée**.
- Observations décisives, correctifs déjà intégrés, demandes de correction non satisfaites.
- Prochaine action atomique et oracle permettant de la valider.

À la reprise : recontrôler Git et l'état réel des sessions **une seule fois** par événement/reconnexion. Ne pas recréer un agent déjà actif, réexécuter inutilement le travail, ou déclarer acquis le résultat d'un ancien HEAD.

Si aucun mécanisme d'attente d'événement n'est disponible, enregistrer cet état et rendre la main. Interdit : polling, boucle répétée `sleep` ou mission autonome sans limite.

Ne jamais enregistrer backup Pixel, tokens, payloads personnels, numéros de série ni chemins privés dans Git.
