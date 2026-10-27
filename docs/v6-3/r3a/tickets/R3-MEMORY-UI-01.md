# R3-MEMORY-UI-01 — Parcours mémoire local durable

| Clé | Valeur |
|---|---|
| TASK_ID / statut | `R3-MEMORY-UI-01 / READY` |
| Objectif | Rendre `MemoryFact` durable et utilisable : voir, ajouter une préférence, confirmer une hypothèse, corriger, supprimer, révoquer, gérer la rétention, avec quarantaine d'un stockage corrompu |
| Dépendances | noyau mémoire R2 intégré |
| BASE_SHA | à remplir au lancement |
| Profil exigé | `codex:gpt-6-luna` / `xhigh` |
| Worktree | dédié |
| Écrire | `android/app/src/main/java/dev/atlas/coach/memory/**`, nouveaux `android/app/src/main/java/dev/atlas/coach/app/Memory*.kt`, tests `memory/**` et `app/Memory*` |
| Ne pas écrire | `AtlasController.kt`, `AtlasApp.kt`, `PlanScreen.kt`, manifeste, backend, relay, calendar |
| Oracle | JVM : persistance après réinstanciation, ajout/confirmation/correction/suppression/révocation, rétention, quarantaine d'un fichier corrompu sans écrasement silencieux, récupération explicite ; compilation debug |
| Stop conditions | besoin de modifier hors scope ; perte silencieuse de données ; modèle/effort non conforme |
| Livrables | commit SHA, diff, tests, rapport |

## Détail

1. `FileMemoryStore` : un document JSON versionné, écriture temp+fsync+rename,
   rechargement au démarrage, aucune donnée personnelle dans les logs.
2. Corruption : copie de quarantaine datée, état `quarantine` exposé, aucune
   écriture d'un état vierge sans action explicite de récupération.
3. Parcours UI : liste avec catégorie, provenance, sensibilité, confirmation,
   expiration ; ajout d'une préférence avec consentement explicite ; confirmation
   d'une hypothèse ; correction ; suppression ; révocation ; purge de rétention.
4. Une hypothèse reste non confirmée jusqu'à action utilisateur ; une préférence
   ou habitude confirmée exige une confirmation explicite.
5. Tests JVM complets ; tests UI logiques. Le parent câble l'écran dans la
   navigation et `AtlasController`.

## Reprise

Session ID, SHA, tests, blocages.
