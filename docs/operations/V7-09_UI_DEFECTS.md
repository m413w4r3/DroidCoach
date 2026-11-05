# ATLAS V7-09 — Défauts UI observés pendant la recette

**Date :** 2026-11-05
**Portée :** observations faites sur le Pixel 9 Pro XL pendant la recette
REJECT V7-09. Aucune correction n'a été appliquée. Les captures d'écran sont
conservées hors dépôt pour ne pas publier de données personnelles.

Les constats sont classés **OBSERVÉ** (écran réellement vu) ou **INFÉRÉ**
(déduit du code ou d'un comportement partiel).

## 1. Coach et conversation

| # | Défaut | Preuve / contexte | Gravité ergonomie |
|---|---|---|---|
| 1 | **Impossible de démarrer une nouvelle conversation depuis l'UI.** L'historique local de 24 messages reste affiché et le seul moyen de repartir propre est de modifier `agentConversationId` dans les préférences. | OBSERVÉ : écran Coach après rotation ; aucune action « Nouvelle conversation » dans le code UI. | Élevée |
| 2 | **Historique très long sans saut au dernier message.** Le composer est sous 24 messages ; il faut plusieurs swipes pour l'atteindre. | OBSERVÉ : 5 swipes pour atteindre « Message au coach ». | Élevée |
| 3 | **Jargon technique dans les réponses utilisateur.** « État des outils : none (0 appel(s)). Aucune proposition (0). », noms `atlas_get_day_plan` / `atlas_propose_plan_revision`, UUID bruts. | OBSERVÉ dans l'historique et la réponse A0. | Élevée |
| 4 | **Réponses trop longues et trop scolaires.** Le modèle répète le contexte, décrit ses outils et justifie chaque étape au lieu de donner une action courte. | OBSERVÉ : plusieurs réponses de 6–10 lignes pour un simple décalage. | Moyenne |
| 5 | **Doublons de messages utilisateur.** Deux messages identiques « Decale cette seance de 10 minutes… » apparaissent dans l'historique. | OBSERVÉ. Cause non déterminée : double tap, renvoi ou reprise. Le bouton Envoyer devrait se désactiver pendant l'envoi. | Élevée |
| 6 | **Bandeau « En mode relais, aucune boîte HTTP directe… » répété** sur Coach, Plan et Partage backend. | OBSERVÉ sur au moins 4 écrans. | Faible |
| 7 | **Statut « Hors ligne » peu actionnable.** Après redémarrage, Coach affiche « Hors ligne — Données locales » sans bouton de reconnexion ; il faut passer par Plus → Connexion relayée. | OBSERVÉ. | Moyenne |
| 8 | **Bouton « Actualiser » sans effet en mode relais.** Il ne déclenche aucun pull et renvoie un message d'indisponibilité au lieu d'un état explicite. | OBSERVÉ / confirmé par A3. | Moyenne |
| 9 | **Mélange de formats d'heure.** « 23 h 00 », « 23:00 », « 2026-11-06T21:00:57Z » dans le même parcours. | OBSERVÉ. | Faible |
| 10 | **Le toggle « Conversation V4 » n'est pas persistant.** Il repart désactivé au cold start, sans avertissement durable. | OBSERVÉ / confirmé par A3. | Moyenne |

## 2. Plan et propositions

| # | Défaut | Preuve / contexte | Gravité ergonomie |
|---|---|---|---|
| 11 | **Identifiants et versions bruts dans la revue.** « Programme 3d2dba67 · version de base 2 », « Proposition 2a29a35f ». | OBSERVÉ dans le dialogue de revue et la carte « Propositions reçues ». | Moyenne |
| 12 | **Date d'expiration brute en UTC.** « expire 2026-11-06T21:00:57Z » au lieu d'une date locale lisible. | OBSERVÉ. | Faible |
| 13 | **Diff peu humain.** Le dialogue dit seulement « déplacer … de 23:00 à 23:10 · 45 min » ; pas de before/after visuel, pas de mise en évidence du delta. | OBSERVÉ. | Moyenne |
| 14 | **Bloc « Réorganisation A1 » très volumineux** avant les activités et les propositions, avec deux boutons de consentement et un texte dense. Il domine la hiérarchie visuelle alors que la fonction est inactive. | OBSERVÉ. | Moyenne |
| 15 | **Suppression d'activité en trois étapes** : menu « Plus » → « Supprimer l'activité » → confirmation → « Aperçu de la révision » → confirmation. Sûr, mais lourd ; le verbe « Annuler » du menu signifie « annuler l'activité », pas « annuler le dialogue ». | OBSERVÉ. | Moyenne |
| 16 | **« Origine du statut · Origine du statut inconnue »** : libellé dupliqué. | OBSERVÉ. | Faible |
| 17 | **« 0 sur 1 terminées »** : accord grammatical incorrect. | OBSERVÉ. | Faible |
| 18 | **Après refus, la carte disparaît** et seul un message transitoire « Proposition refusée » reste. Pas de badge/historique « refusée » consultable dans l'inbox. | OBSERVÉ. | Moyenne |
| 19 | **« Plus tard » dans le dialogue de revue** peut laisser une proposition expirer sans décision explicite ; aucune indication du délai restant. | OBSERVÉ. | Moyenne |
| 20 | **Le bouton « Pousser les projections V4 » est grisé** en mode relais, alors que l'état « État Core V4 · OFFLINE (ERROR) » est affiché. L'utilisateur ne sait pas quel geste déclenche réellement la synchronisation. | OBSERVÉ. | Élevée |

## 3. HOME, Focus et navigation

| # | Défaut | Preuve / contexte | Gravité ergonomie |
|---|---|---|---|
| 21 | **Navigation basse ambiguë « Pause » / « Fin »** selon l'état Focus, sans libellé explicite de la session concernée. | OBSERVÉ : le quatrième onglet est passé de « Pause » à « Fin ». | Moyenne |
| 22 | **Session Focus synthétique ancienne encore visible** (`CANCELLED`, 2026-11-04) et reprise dans l'état local, sans purge ni indication « test ». | OBSERVÉ dans l'état local. | Faible |
| 23 | **« Programme » et « Planning »** utilisés pour le même écran selon les libellés. | OBSERVÉ. | Faible |
| 24 | **Le bouton HOME n'est pas toujours visible** dans les sous-écrans ; certains n'ont qu'un « Retour » textuel. | OBSERVÉ. | Faible |
| 25 | **Le PiP YouTube reste au-dessus de l'interface ATLAS** et masque une partie de l'écran. Ce n'est pas un défaut ATLAS, mais l'UI ne prévoit pas de marge/contraste pour ce cas. | OBSERVÉ. | Faible |

## 4. Recommandations d'épuration (non appliquées)

1. Ajouter une action « Nouvelle conversation » explicite, avec confirmation
   et conservation de l'historique consultable.
2. Masquer les noms d'outils, UUID, versions et codes techniques derrière un
   panneau « Détails » replié. Garder une phrase utilisateur courte.
3. Rendre le bouton Envoyer inactif pendant un tour et afficher un état
   « envoi en cours » ; empêcher les doublons.
4. Ajouter un bouton de pull V4 explicite et un état de synchronisation clair
   dans Coach/Plan, au lieu du workaround « Enregistrer la connexion ».
5. Persister le toggle V4 et afficher son état réel.
6. Simplifier le bloc A1 : le replier par défaut, ou le déplacer dans Plus.
7. Humaniser les dates : « demain 21:00 », « dans 24 h », fuseau local.
8. Afficher un diff before/after compact avec le delta en minutes.
9. Réduire la verbosité du modèle : réponse cible de 1–3 phrases, action
   proposée, bouton de revue ; pas de rappel des outils.
10. Unifier les libellés « Planning / Programme » et corriger les accords.

Ces défauts sont des observations de recette ; ils ne sont pas des gates de
V7-09 et aucune correction n'a été faite dans cette mission.
