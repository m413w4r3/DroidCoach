# 01 — Produit et exigences

## Vision
ATLAS transforme objectifs choisis par l'utilisateur en **une prochaine action concrète**, l'aide à la commencer, lui propose une friction lors d'ouvertures répétées d'applications sélectionnées, suit les sessions et les habitudes, et propose une petite sélection de lectures/vidéos. Le coach est ferme **mais contestable**, ses règles sont explicites et révocables.

## Ce qui distingue ATLAS d'une juxtaposition d'apps
Le même objet `ActionPlan` relie directive, Pomodoro, app distractions, habitude, projet, contenu et bilan. Les événements produits par une session ou un choix sont visibles dans le journal et peuvent influencer la prochaine directive, sans modification unilatérale du mandat.

## Parcours prioritaires (MVP)
1. Première ouverture : mandat local minimal (objectif, projet, plage de repos, applications surveillées, intensité, permissions séparées) et possibilité de tout refuser sans perdre l'app.
2. Accueil : directive explicite + raison + « Commencer », « Discuter », « Alternative », « Reporter/Pause » ; navigation minimale vers focus/habitudes/bibliothèque/paramètres.
3. Focus : une directive propose 10 ou 25 minutes ; le moteur local planifie/démarre selon le mandat, affiche minuterie, pause, interruption, confirmation de fin et journal. Mode « imposé » = pré-engagement explicite : horaire déjà accepté, rappels ferme, friction optionnelle ; jamais verrouillage permanent.
4. Habitudes : créer une habitude (nom, cadence, objectif, association facultative à un projet), valider un accomplissement, voir 7/30 jours ; ne pas assimiler automatiquement une session écoulée à un accomplissement sans règle explicite.
5. Distractions : consentement séparé à UsageStats, seuil déterministe (4 ouvertures d'une app sélectionnée dans 25 min), une friction non bloquante et déduplication ; si précision réelle insuffisante, notifier les limites sans mentir.
6. Contenu : montrer au plus 3 recommandations sourcées ; ouvrir une vidéo YouTube via ACTION_VIEW/résolution Android, éventuellement dans ReVanced **si installé/configuré**. Aucun contrôle garanti du feed ou des préférences internes de ReVanced.
7. Hors réseau : accueil, directives mises en cache, timer, habitudes, règles locales, pause et export de données fonctionnent.
8. Sécurité : appels, paramètres système, authentification et navigation essentielle restent disponibles ; possibilité de désactiver le rôle HOME et de désinstaller normalement.

## Fonctionnement précis du coach
- Propose UNE action principale courte et vérifiable, expliquant son lien avec un objectif choisi.
- Mode `observation` : information ; `firm` : proposition prioritaire + relances bornées + demande de raison non obligatoire ; `reinforced` : **hors MVP**, uniquement après validation et validation hardware.
- Les réponses `start`, `discuss`, `alternative`, `postpone`, `pause` forment un protocole explicite.
- Ne mesure pas l'obéissance ; mesure utilité perçue, attention, sessions volontairement terminées et capacité d'agir.
- Limiter les interventions : cooldown par règle et par application (défaut 15 min), heures calmes, pause générale de 30 minutes configurable.

## Définition du Pomodoro
- Durée de base : focus 25 min, pause 5 min, configurable ; session courte 10 min ; pause longue après 4 cycles : 15 min, configurable.
- États : `planned`, `active`, `break`, `completed`, `interrupted`, `cancelled` ; chaque transition datée et idempotente.
- La durée est calculée via l'horloge monotone tant que l'app fonctionne, complétée par horodatage persistant pour reprise après redémarrage. Contrôler les changements d'horloge et ne pas promettre une exactitude impossible lorsque le système suspend l'app.
- Les restrictions éventuelles sont la conséquence de règles locales consenties et doivent cesser au plus tard à l'expiration ou à une pause d'urgence.
- Ne pas démarrer automatiquement une session hors des créneaux préautorisés ; avertir au préalable et laisser annuler.

## Suivi des habitudes
- Champs minimaux : id, nom, fréquence/cadence, jours attendus, validateur, résultats horodatés, source manuelle/session.
- L'utilisateur peut corriger une validation ou marquer un repos ; export JSON et CSV.
- Interopérabilité Loop : vérifier en audit le mécanisme Tasker/intent/backup ; ne pas écrire directement dans sa DB privée.

## Limites et fonctionnalités futures
- Moteur de projet plus avancé, mémoire sémantique, voix, synchronisation Obsidian, tableaux de bord, calendrier, curation Miniflux, suggestions de videos via sources autorisées, synchronisation multi-appareils.
- Mode renforcé Device Owner séparé sur Pixel de test : risque important, jamais prérequis V1.
- Version ReVanced et disponibilité de patches changeantes ; ne jamais traiter le gestionnaire de patches comme une API de pilotage vidéo.

## Distinction produit / implémentation
Une seule expérience (ATLAS Android) ; deux programmes principaux (application et serveur) ; des intégrations (Loop, ReVanced, Miniflux, fichiers Markdown) seulement quand elles sont vérifiées et utiles.
