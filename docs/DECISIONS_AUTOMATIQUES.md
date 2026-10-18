# Décisions automatiques

| Date | Décision | Raison |
|---|---|---|
| 2026-10-18 | Package `dev.atlas.coach`, module unique `:app`, minSdk 26 | Exemple de 09 ; réversible |
| 2026-10-18 | Gradle wrapper (système = 4.4.1 trop ancien) | Compose/AGP l'exigent |
| 2026-10-18 | Pas de worktrees (dépôt non git) ; lots sérialisés/dossiers disjoints | Contrainte d'environnement |
| 2026-10-18 | Cible d'essai : emulator-5554 (API 30) déjà actif | Évite de créer/modifier des AVD |
| 2026-10-18 | Gradle 8.9, AGP 8.7.3, Kotlin + plugin Compose 2.0.21, Compose BOM 2024.12.01 ; compile/target SDK 35 | Compatibles entre eux et avec JDK 21 ; minSdk 26 conservé |
| 2026-10-18 | Persistance S3 : un document JSON versionné dans `SharedPreferences` privé, écrit avec `commit()` | Petit volume local, restaure les maps S1/S2 sans Room/DataStore ni service; aucun réseau requis |
| 2026-10-18 | `org.json` fourni par Android à l'exécution; dépendance JSON JVM seulement en `testImplementation` | Tests unitaires du round-trip sans embarquer une seconde implémentation JSON dans l'APK |
| 2026-10-18 | L'horloge applicative combine `System.currentTimeMillis()` UTC et `SystemClock.elapsedRealtime()`; le timer ne démarre aucun service | Restaure le temps écoulé au relancement et tick uniquement quand l'interface Focus est visible |
| 2026-10-18 | Mode accéléré UI fixé à 20 secondes; flags focus, habitudes, contenu, coach, règles simulées activés; consentement d'observation réel désactivé | Démo immédiatement utilisable; aucune observation réelle n'est livrée en VM-1 |
| 2026-10-18 | `allowBackup=false` et règles Android Cloud/Device Transfer excluant la racine | Données personnelles locales non incluses dans sauvegarde/transfer Android |
| 2026-10-18 | Rôle HOME demandé seulement via le bouton explicite; le bouton de sortie ouvre `Settings.ACTION_HOME_SETTINGS`, avec fallback vers les réglages généraux | L'utilisateur peut choisir un autre launcher sans que l'application modifie le HOME par défaut |
| 2026-10-18 | Avant S6, aucune référence de code Olauncher n'était ajoutée; seule la direction visuelle était reprise | Décision historique remplacée en S6 par l'adaptation GPLv3 de deux composants, à la demande explicite du porteur |
| 2026-10-18 | Restauration locale validée par sous-état; toute corruption/version inconnue déclenche une valeur de repli isolée et une copie brute en quarantaine avant remplacement | La corruption d'une section ne doit pas supprimer les autres données ni bloquer l'ouverture; si la quarantaine échoue, ne pas écraser le blob |
| 2026-10-18 | La désactivation de Focus met en pause la session; les ticks et reprises sont alors refusés. La désactivation d'Habitudes retire les actions de lien et refuse toute confirmation | Effet immédiat, explicite et réversible des capability flags |
| 2026-10-18 | Le timer ticke uniquement lorsque l'écran est visible et l'Activity démarrée; checkpoints au plus toutes les 20 s visibles, commits durables aux transitions | Évite une écriture synchrone par seconde tout en restaurant précisément le temps après arrêt forcé via l'horloge murale |
| 2026-10-18 | `ACTION_VIEW` n'ouvre le chooser qu'après résolution d'un handler compatible | Retour `NO_COMPATIBLE_APP` explicite quand le gestionnaire manque |
| 2026-10-18 | Expiration des IDs de déduplication selon la fenêtre de règle; plafonds de 200 entrées pour historiques d'interventions et du coach | Borne les données conservées et la taille des snapshots locaux |
| 2026-10-18 | Réutilisation maximale du code utile des dépôts Reef (MIT), Olauncher (GPLv3) et Loop/uhabits (GPLv3) pour un usage exclusivement privé; APK non distribuée | Décision explicite de l'utilisateur, qui supplante l'ancien principe de ne pas copier par défaut. Toute future distribution requiert sources complètes sous licence compatible et notices. |
| 2026-10-18 | Réutilisations S6 restent dans `android/**`; stockage JSON/SharedPreferences, contrats locaux et règles consenties conservés | Évite d'importer les services et stockages des références; aucune dépendance Gradle tierce n'est ajoutée sans nécessité démontrée. |
| 2026-10-18 | L'accueil launcher ATLAS est une capability indépendante, désactivée par défaut, et l'app list est découverte par `<queries>` avec `MAIN` + `LAUNCHER` | Aucun `QUERY_ALL_PACKAGES`; ne modifie pas le rôle HOME sans action explicite et conserve le chemin vers les réglages système. |
| 2026-10-18 | Adaptation des modèles Pomodoro Reef sans `strictMode`, blocage ni service en arrière-plan | Le moteur Focus et sa persistance locale restent la source de vérité; les durées et cycles sont traduits vers `FocusPlan`. |
| 2026-10-18 | Les routines Reef sont ramenées à des fenêtres locales de focus; elles ne lancent une session qu'après action explicite | Reprend le calcul jour/semaine/nuit sans quotas d'apps, alarme, service ou observation. Capability `focusRoutines` séparée et désactivée par défaut. |
| 2026-10-18 | Historique et statistiques de focus Reef enregistrés dans le document JSON ATLAS, plafond de 100 sessions terminées ou annulées | Évite le fichier JSON Reef séparé et garde le cycle de sauvegarde/quarantaine ATLAS. |
| 2026-10-18 | Force Loop calculée depuis les jours dus et check-ins ATLAS; jours non dus = `SKIP`, échéance manquée = `NO` | Préserve `HabitRepository` et son historique local; SQLite/Room Loop et ses données ne sont pas utilisés. |
