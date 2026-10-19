# ATLAS V6 — Limites connues

- **`app_policy.adjust` est refusée.** L'observateur UsageStats connaît des
  événements d'ouverture, pas une durée quotidienne exacte. ATLAS n'invente
  donc pas un plafond d'utilisation ni une interception.
- **Pas de WorkManager pour l'agent.** La boîte de propositions est tirée à
  l'ouverture de Coach et par actualisation manuelle. Les messages ne sont
  envoyés qu'après « Envoyer ».
- **Doze et alarmes inexactes.** Les notifications et `focus.plan` s'appuient
  sur `AlarmManager` inexact; Android peut retarder ou regrouper les alarmes en
  Doze. Le minuteur Focus est local et son état est persisté, mais le système ne
  promet ni notification à la seconde ni lancement au moment exact.
- **UsageStats n'intercepte pas les applications.** L'accès Usage Access OS,
  le consentement ATLAS et une allowlist de packages sont distincts. Android
  peut retarder/regrouper les événements; ATLAS ne promet pas une détection
  instantanée, un blocage ni une couverture complète.
- **Pas d'AccessibilityService dans ce MVP.** Pas de lecture d'écran complet,
  surveillance de saisie, kiosk, installation silencieuse, root ou Device
  Owner. La sortie vers le launcher et la pause d'urgence restent locales.
- **Le modèle peut ignorer une consigne.** Pendant la recette réelle, OpenClaw a
  proposé 10 minutes malgré la demande de 25. Le backend recopie les durées de
  travail et de pause, ainsi que les cycles explicitement formulés en chiffres,
  lorsque chacun possède une interprétation unique et respecte les bornes du
  contrat. Une formulation ambiguë ou hors limites retire la proposition Focus
  et demande une précision. Les cardinaux français usuels en toutes lettres sont
  pris en charge; secondes, quarts et demi-heures demandent une précision plutôt
  que conversion implicite. La trace journalise seulement le résultat, les
  champs corrigés et un motif contrôlé, jamais le message utilisateur.
- **Aucune garantie sur une génération hostile.** La sortie est analysée en
  JSON strict, limitée à cinq actions typées et revalidée par deux policies,
  mais la recette réelle n'est pas un audit adversarial complet du modèle.
- **Transport AVD versus appareil réel.** HTTP cleartext de debug est autorisé
  seulement vers `10.0.2.2`; sur un appareil réel, déployer le backend sur une
  adresse joignable via HTTPS et un certificat valide. Le Gateway par défaut
  reste loopback côté backend.
- **Pas de test sur téléphone physique.** Tous les résultats Android cités
  proviennent de `emulator-5554`, image Android 17 API 37.
- **Backend local.** Les quotas/breaker sont en mémoire; SQLite et la mémoire
  ne sont pas chiffrées. Un backend `0.0.0.0` ne doit être exposé que dans le
  réseau isolé de test documenté, avec son Bearer ATLAS temporaire.

