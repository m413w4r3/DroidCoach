# ATLAS V6.2 — essai sur Pixel secondaire

Aucun Pixel physique n'a été testé pendant cette mission. Ce guide est une procédure à exécuter, pas une preuve de compatibilité. Sauvegarder les données utiles du téléphone et vérifier son identifiant avant toute installation ; les scripts de recette AVD, qui effacent des données synthétiques, ne doivent jamais cibler ce téléphone.

## Installation et démo locale

1. Compiler `cd android && ./gradlew assembleDebug`, puis identifier le téléphone avec `adb devices -l`. Installer l'APK debug avec `adb -s IDENTIFIANT_EXPLICITE install -r android/app/build/outputs/apk/debug/app-debug.apk` depuis la racine. Ne pas utiliser `pm clear`, `wipe-data`, root ou installation silencieuse de packages tiers.
2. Ouvrir ATLAS comme application ordinaire. Laisser le backend non configuré ; vérifier Focus, objectif local, habitudes et pause d'urgence. Les données personnelles existantes ne sont pas des fixtures de test.
3. Choisir ATLAS comme HOME uniquement depuis le bouton et l'écran Android correspondants. Vérifier favoris, recherche, Coach, Pause et Plus. Plus donne accès au launcher précédent et aux réglages HOME. En cas de problème, rétablir l'application d'accueil dans les réglages Android.

## Objectif et partage

Créer un objectif synthétique de 120 minutes et consentir séparément aux catégories Focus/Objectifs dans le Coach. Les anciennes préférences migrées commencent à la date de migration, sans rétrospective antérieure. Lier explicitement la session à l'objectif ; seule sa durée réellement terminée contribue à la progression. Les objectifs manuels demandent une confirmation.

Pour le Coach distant, configurer une URL backend HTTPS et le Bearer ATLAS via les réglages. Le jeton Gateway ne doit jamais être saisi dans Android. Suivre le [guide OpenClaw](../v6/GUIDE_UTILISATEUR.md) pour le backend et son agent dédié ; ne pas exposer le Gateway sur Internet. Examiner l'aperçu du prochain envoi, puis envoyer explicitement. Vérifier la progression comprise par le Coach, accepter ou reporter la proposition, consulter le journal, révoquer Goals et confirmer que le prochain aperçu ne contient plus cet objectif.

Couper le backend et vérifier que HOME, favoris, Focus et Pause restent accessibles. Le mode renforcé modifie les quotas de coaching, sans accorder de permission Android ni élargir le mandat d'action.

## Localisation et mobilité

1. Depuis une action visible, demander une position approximative et choisir l'accès pendant l'utilisation. Ne demander la précision fine que pour une fonction qui l'exige ou un choix explicite. L'autorisation OS est distincte du partage avec le Coach.
2. Contrôler le refus de permission, le timeout, la désactivation du fournisseur et la révocation. La capture requiert un fix récent ; ne pas transformer un timeout en position inventée. Le contexte automatique reste une zone approximative.
3. Démarrer manuellement « Suivre mon déplacement » pendant qu'ATLAS est visible. Vérifier la notification persistante et son action Arrêter. Les mesures sont espacées d'au moins cinq minutes, la session est limitée à 60 minutes, et seuls des changements de zone minimisés peuvent être conservés.
4. Vérifier Arrêter, la révocation du consentement et la pause d'urgence. Le service ne doit pas repartir silencieusement après force-stop ou reboot. Aucun accès background, Always, reconnaissance de transport ou trajet GPS détaillé n'est prévu.

## Recette non destructive à consigner

Noter modèle, version Android, APK et date. Vérifier portrait/paysage, police 1,5×/2×, TalkBack, ordre de focus, gestes et clavier. Mettre un Focus en pause, fermer/relancer l'application puis effectuer un force-stop et vérifier sa restauration. Désactiver Focus et confirmer que la session reste visible en pause, sans bouton de reprise actif. Pour UsageStats, ouvrir les réglages Android et choisir explicitement une application de test non vitale ; ne pas modifier AppOps sur le téléphone personnel. Révoquer ensuite observation et partages. Aucune performance batterie ou compatibilité Android 17 ne doit être déclarée sans mesures et exécution réelles.
