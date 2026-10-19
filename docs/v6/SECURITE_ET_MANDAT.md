# ATLAS V6 — Sécurité et mandat

Le modèle et le backend ne font que proposer. Android revalide chaque action au
moment du dispatch avec le mandat local courant et le `ActionDispatcher` à
branches fermées. `required_level` reçu du serveur est indicatif.

## Niveaux d'autorisation

| Niveau | Autorité | Règle |
|---|---|---|
| OS Android | Système | Une permission, l'accès Usage Access ou le rôle HOME est accordé dans les réglages ou interfaces Android. Une proposition IA ne peut jamais demander ni accorder une permission OS. |
| Consentement ATLAS | Utilisateur dans ATLAS | Autorise une capacité locale ou l'observation d'applications choisies. Les catégories de contexte partagées sont vides par défaut et chacune se règle séparément. |
| Mandat agent | Utilisateur dans Coach → Mandat et autonomie | Définit `allowed_actions`, le mode, la fenêtre et les quotas Focus, les bornes d'applications et l'expiration. Il est relu avant chaque effet. |

Ces niveaux ne se substituent pas l'un à l'autre. Un mandat `app_policy.*` ne
consent pas à observer une application. Un consentement ATLAS ne donne pas à
l'agent une permission Android. L'accès Usage Access OS ne coche pas l'allowlist
de consentement ATLAS.

## Matrice des actions

Par défaut le mode est `proposals_only`; le Coach demande donc une action
explicite sur les propositions applicables. Une action préautorisée n'est
possible qu'en mode `preauthorized`, si le type est listé dans le mandat et si
toutes les capacités, bornes, fenêtres, références, quotas et contrôles locaux
sont valides.

| Action | Proposée | Confirmable | Préautorisable dans cette version | Refus / effet borné |
|---|---:|---:|---:|---|
| `home.set_directive` | oui | oui | oui | Stocke une directive locale; ne lance aucune activité |
| `home.reorder_cards` | oui | oui | oui | Les cartes omises sont réinsérées; favoris et secours ne sont pas masqués |
| `focus.propose` | oui | oui, obligatoire | non | Crée une carte; le départ exige encore « Démarrer sans quitter le Coach » |
| `focus.plan` | oui | oui | oui | Exige `focusRoutines`, fenêtre et quotas; programme une alarme inexacte et ne démarre pas une activité |
| `focus.start` | oui | oui | oui | Référence uniquement un focus préalablement accepté et non annulé |
| `focus.pause` | oui | oui | oui | Référence uniquement la session en cours; la pause d'urgence reste indépendante |
| `habit.suggest` | oui | oui, obligatoire | non | Enregistre une suggestion; ne crée ni habitude ni validation sans confirmation |
| `app_policy.propose` | oui | oui, obligatoire | non | Suggestion locale; observation consentie et package non protégé obligatoires |
| `app_policy.adjust` | oui, candidat | refusée | refusée | Non appliquée dans cette version : la durée quotidienne fiable n'est pas mesurée |
| `content.recommend` | oui | oui pour l'ouverture | affichage local possible | Le contenu reste consultable; l'ouverture d'URL est un tap explicite sur un domaine autorisé |

Les actions système, commandes arbitraires, intents, changement de permission,
retrait de denylist, package vital, URL hors allowlist et opérations non listées
sont interdites. Aucune proposition ne peut désactiver le bouton d'urgence ou
le retour au launcher d'origine.

## Révocation et revalidation

- « Suspendre le mandat » bloque les nouveaux dispatchs; « Révoquer tout »
  incrémente/modifie le mandat, invalide les versions en attente et annule le
  travail agent en attente. Réactiver est une action locale explicite.
- Désactiver l'agent, une capacité, un partage de catégorie ou le consentement
  UsageStats bloque ce chemin indépendamment du mandat. Les propositions sont
  revalidées avant effet; une révocation entre proposition et exécution entraîne
  un refus.
- Une proposition expirée (TTL au plus 24 h), un mandat expiré, une version de
  mandat différente, un replay d'action terminée, une référence Focus annulée,
  un quota atteint ou une heure hors fenêtre sont refusés.
- Révoquer empêche les prochains envois/effets; cela ne peut pas annuler une
  requête déjà transmise au Gateway ni retirer des données déjà reçues par un
  serveur distant.
- Le serveur accepte seulement un Bearer ATLAS sur ses API; le jeton Gateway
  reste dans le backend. Ni l'un ni l'autre ne sont écrits dans les logs de
  recette ou les captures.

## Scénarios de sécurité vérifiés

| Scénario | Environnement | Résultat |
|---|---|---|
| Application non consentie / package système protégé | Android JVM `ActionDispatcherTest` | refusé avant effet |
| Mandat révoqué / TTL dépassé / replay | Android JVM + pytest V2 | refus, expiration ou réponse idempotente; pas de second effet |
| Auth 401, timeout et erreurs 5xx | transport OpenClaw mock | statut indisponible, retry borné, aucun secret renvoyé |
| Injection de prompt | tests mock strict JSON et texte synthétique | aucun outil/action inconnue; entrée non fiable; aucune preuve de résistance absolue du modèle réel |
| Catégorie non consentie | test client/backend mock | catégorie retirée du payload transmis |
| `focus.plan` préautorisé borné | AVD Android 17, proposition de fixture | dispatch réel réussi et inscrit au journal |
| Backend coupé puis repris, 401, consentement Sync désactivé et replay UUID | AVD Android 17 + FastAPI mock local V1 | scénarios V4 réussis; événement hors ligne conservé puis acquitté après reprise |

