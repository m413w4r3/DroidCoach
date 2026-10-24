# ATLAS — Ticket de sous-session Paseo

| Clé | À renseigner avant lancement |
|---|---|
| TASK_ID / statut | `V62-CLOSE-XX / READY` |
| Objectif | Un résultat observable, borné et démontrable |
| Dépendances | Tickets `ACCEPTED` requis |
| BASE_SHA | SHA Git complet, jamais une branche mouvante |
| Profil constaté | `openai-codex:gpt-6-luna` / `xhigh` / ID de session |
| Worktree / accès réel | Répertoire isolé, mode lecture seule ou écriture bornée |
| Lire / écrire | 3–8 chemins utiles / propriété exclusive des fichiers écrits |
| Oracle | Commande ciblée et condition de réussite ou grille de revue |
| Stop conditions | Permission absente, donnée privée, modèle incorrect, conflit de fichiers |
| Livrables | Output SHA, diff, commandes, `PASS/FAIL/BLOCKED/NOT_RUN`, preuves expurgées |
| Revue parent | `ACCEPTED / CORRECTION_REQUIRED / BLOCKED` |
| Reprise | Session ID, prochain oracle, nouvelle autorisation éventuellement nécessaire |

Aucun agent enfant ne crée d'autres agents. Une notification de fin entraîne examen, pas acceptation.
