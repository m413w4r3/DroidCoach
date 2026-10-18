# 05 — Arbitrages, hypothèses et évolutivité

## Décisions recommandées maintenant
- **D01** : expérience Android unifiée ; backend séparé et facultatif pour l'usage de base.
- **D02** : architecture modulaire par domaines métier, pas fusion du code source complet des candidats.
- **D03** : règles Android déterministes, IA derrière adaptateur, mandat local révocable.
- **D04** : Reef = audit ciblé avant toute reprise ; Loop = app externe + éventuelle interop ; ReVanced = lecteur externe.
- **D05** : mode focus/usage/habitudes fonctionnel hors réseau ; le serveur ne peut pas durcir les restrictions à distance.
- **D06** : prototypes par paliers compilables et évaluables, pas une méga livraison.

## Hypothèses par défaut tant que l'environnement n'est pas connu
- Téléphone Pixel personnel **sans root**, Android récent ; test initial sur émulateur ; test réel en phase suivante.
- Usage personnel, distribution APK privée en premier ; **ne pas** déduire que toutes les restrictions Android ou obligations légales disparaissent hors Play Store.
- Serveur non garanti : mode mock-local obligatoire ; backend auto-hébergeable optionnel, pas de compte requis.
- Un utilisateur, un appareil, fuseau horaire système, interface française ; support EN ultérieur.
- Le LLM choisi ultérieurement est externe au cœur du code et configurable ; aucune clé API dans Git.

## Décisions à prendre après preuve de concept
- Périmètre de copie de code Reef : aucun, quelques classes isolées ou fork profond ? Conditionné par analyse de dépendances et licence.
- Launcher minimal Compose conçu pour ATLAS ou fork Lawnchair : comparer le besoin réel de gestes/récents/widgets vs coût du fork.
- Intégration Loop : Tasker, import/export, ou simple deep link.
- Valeur ajoutée de Miniflux ; coût/fiabilité du modèle IA ; déploiement serveur ; sauvegarde et migration vers PostgreSQL.
- Contrôles d'accès avancés et accessibilité : prototype sur téléphone secondaire seulement, validation policy.

## Extensions prévues, sans engagement de construction maintenant
- Révision hebdomadaire intelligente, planification multi-projets, calendrier, notifications conversationnelles, notes Markdown, curation multimédia, voix, multi-appareils, statistiques longitudinales, nouveaux fournisseurs de LLM ou de sources de contenu.

## Points de décision à réserver à l'utilisateur
- Modèle exact du Pixel et version Android ; consentements réels ; apps surveillées ; plages horaires ; niveau de fermeté ; disponibilité du serveur ; choix d'un fournisseur IA ; niveau de confidentialité et de synchronisation ; publication ou non sur Play Store.

L'agent peut progresser **sans ces réponses** grâce aux défauts indiqués, mais il ne peut ni inventer des consentements ni prétendre avoir testé du matériel absent.
