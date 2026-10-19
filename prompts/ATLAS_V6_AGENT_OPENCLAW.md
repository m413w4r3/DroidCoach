# ATLAS / DroidCoach — V6 : intégration d'un coach OpenClaw opérationnel

## Mission

Tu es l'orchestrateur principal de l'implémentation V6. Tu travailles sur le dépôt existant `https://github.com/m413w4r3/DroidCoach`, branche `main` au départ (référence d'audit : `e10f2fc2449d9e1e0e04ef2f42f157bcb2973316`, à revérifier). Crée une branche `feat/atlas-v6-openclaw` ; ne pousse ni ne fusionne sans accord explicite.

**But :** donner à un véritable coach IA la capacité de personnaliser ATLAS HOME, proposer des contenus, planifier et, sous mandat explicite, déclencher des actions de concentration permises, gérer les recommandations et proposer des ajustements aux règles d'applications. Ce doit être un système fonctionnel, pas seulement une architecture ou un mock.

**Priorité produit :** l'agent devient le cerveau ; Android reste source de vérité et autorité finale sur les effets locaux et les permissions. La voix/ton du coach peut être direct, mais les actions sont révocables, auditées et limitées. Ne pas installer de mécanisme d'interception forcée ni promettre de contrôle système qu'Android n'offre pas.

**Conserver absolument :** `LauncherActivity` HOME dédié (V5), `MainActivity` coach, Focus et alarmes inexactes, habitudes, observation UsageStats consentie, règles de friction douce, sécurité des packages vitaux, contenus externes, sync backend optionnelle, données locales hors ligne, tests JVM/instrumentés et backend. Aucune refonte totale et aucun fork de Lawnchair/Reef.

**Non-objectifs V6 :** refonte graphique lourde, graphiques et dashboard statistiques, reconnaissance vocale, contrôle arbitraire de l'écran Android, root, Device Owner, accessibilité intrusive, blocage infaillible d'autres applications, patchs ReVanced/YouTube, exposition publique d'OpenClaw.

## Références à lire avant le développement

- `AGENTS.md`, `README.md` ; `docs/v5/01-architecture-home.md`, `docs/v5/02-observation-hors-interface.md`, `docs/v5/03-recette-home-emulateur.md`, `docs/v5/04-recette-finale.md`.
- `docs/v4/02-architecture-realisee.md`, `docs/v4/04-defauts-et-limites.md`, `docs/v4/05-suite-openclaw-future.md`.
- Android : `android/app/src/main/java/dev/atlas/coach/sync/agent/AgentContracts.kt`, `app/AtlasController.kt`, `app/LauncherActivity.kt`, `app/LauncherHomeScreen.kt`, `focus/`, `rules/`, `usage/`, `sync/`.
- Backend : `backend/atlas_backend/{main,agent,policy,store}.py`, `backend/tests/` ; contrats dans `contracts/`.
- Documentation OpenClaw officielle, à confronter à la **version installée** :
  - https://docs.openclaw.ai/gateway/openai-http-api
  - https://docs.openclaw.ai/gateway/external-apps
  - https://docs.openclaw.ai/gateway/config-tools/tool-policy
  - https://docs.openclaw.ai/gateway/security
  - https://github.com/openclaw/openclaw

Ne pas inventer un endpoint ou une option de configuration. L'API compatible `/v1/chat/completions` peut être désactivée par défaut et doit être activée explicitement. L'accès HTTP avec jeton Gateway confère une autorité d'opérateur élevée : NE JAMAIS exposer ce jeton à Android, le laisser dans des captures/journaux ou ouvrir le Gateway sur Internet. Le backend seul peut posséder ce secret, par configuration sécurisée côté serveur. NE PAS utiliser le Gateway `/tools/invoke` pour fournir un accès système à ATLAS.

## Expérience cible et critères utilisateur

1. En ouvrant ATLAS HOME, je vois une mission contextualisée, une action principale, mon Focus en cours si présent, mes applications favorites et l'accès simple au coach. Les données viennent de l'état local synchronisé, jamais d'un chargement réseau obligatoire.
2. Je peux parler au coach de mes projets et contraintes, négocier une directive, demander une alternative et consulter pourquoi elle a été proposée. Ce ne sont plus des réponses constantes et préprogrammées.
3. Je peux dire « prépare-moi 25 minutes de travail après le déjeuner » ; le coach élabore la proposition, demande une confirmation si nécessaire, et ATLAS peut enregistrer la session et le rappel local. Un mandat granulaire peut autoriser la planification automatique dans une plage horaire définie.
4. Je peux choisir les applications distrayantes autorisées à l'observation, puis demander « propose des règles plus strictes pour cette application ». Pour ces seules applications, l'agent peut suggérer un seuil, une fenêtre, un mode friction et une durée, ou ajuster automatiquement une règle **à l'intérieur des bornes préautorisées par mon mandat**, avec durée de validité et annulation accessibles. Toute extension de l'observation, toute augmentation de sévérité hors bornes ou tout changement de permission nécessite une confirmation humaine. Il ne peut pas défaire les exceptions vitales.
5. Le coach peut proposer des vidéos ou articles à partir de sources explicitement autorisées ; les liens sont vérifiés et ouverts seulement par mon action. Aucun contrôle interne de ReVanced présumé.
6. Sans backend, Internet ou OpenClaw, HOME, Focus, habitudes, mandats locaux et contenus déjà sauvegardés restent utilisables ; l'interface signale clairement que l'agent est hors ligne.

## Lot A — Baseline et sécurité du Gateway

- Évaluer l'état réel de `main`, la CI (dernier run vert), l'AVD disponible Android 17 / API 37.2, `targetSdk=36`, `compileSdk=37.2`, l'APK et les scripts de recette. Ne pas détruire/recréer l'AVD ni supprimer les données utilisateur.
- Diagnostiquer si OpenClaw est déjà installé, sa version, son mode d'exécution et la configuration accessible. Ne pas présumer qu'il tourne sur le Pixel : privilégier **OpenClaw sur la machine Linux / serveur personnel**, avec FastAPI comme médiateur. Si absent, documenter le setup officiel et demander seulement les informations/approbations réellement indispensables ; développer l'adaptateur et les tests avec serveur factice en attendant.
- Préparer un agent OpenClaw **dédié ATLAS** à outils minimaux/aucun outil dangereux : pas de shell, exécution, navigateur arbitraire, accès aux fichiers personnels, automation générale, gestion du Gateway, nœuds, appareils ou secrets. Configurer et prouver le filtrage selon la version installée (une simple consigne dans le prompt n'est pas une barrière de sécurité). Aucun accès direct aux permissions Android.
- Utiliser Gateway sur loopback ou réseau privé sécurisé. Pour l'AVD, la communication de test Android↔FastAPI peut conserver l'exception debug `10.0.2.2`; la relation FastAPI↔OpenClaw reste côté hôte. Pour un Pixel réel, documenter HTTPS et authentification ; ne pas créer une exposition WAN sans demande.
- Conserver et vérifier l'isolation des jetons, logs nettoyés, timeouts, limites de payload, redémarrages, revocation. Afficher une preuve de comportement sans fuite de secrets.

## Lot B — Contrats V6, mandat et moteur de décisions

Partir de `AgentContracts.kt`, `CoachDecisionProvider`, `MandateVerifier` et du `PolicyEngine` backend existants. Les faire évoluer avec migrations compatibles ; ne pas ajouter un second validateur divergent ni laisser deux « sources de vérité ».

Créer des contrats JSON/Pydantic/Kotlin versionnés, tests de compatibilité et champs obligatoires : `proposal_id`, `schema_version`, `mandate_id/version`, `created_at`, `expires_at`, `action_type`, paramètres typés, motif, origine/context_version, niveau d'autorisation requis, idempotency_key et état (`proposed`, `pending_confirmation`, `accepted`, `executed`, `rejected`, `expired`, `cancelled`). Pas de confiance dans un champ `accepted` provenant du modèle. La décision d'autoriser doit toujours être recalculée localement au moment de l'exécution.

**Actions V6 candidates, à définir définitivement avec tests :**

| Action | Mode d'exécution |
|---|---|
| `home.set_directive` / `home.reorder_cards` | Automatique seulement si coaching personnalisé activé, et dans des zones UI prédéfinies ; ne peut pas masquer favoris, accès secours ni préférences UI utilisateur. |
| `focus.propose` / `focus.plan` | Proposition par défaut ; planification locale automatique possible seulement avec mandat explicite (heures, jours, durée et volume maximum). |
| `focus.start` / `focus.pause` | Confirmation par défaut ; démarrage automatique facultatif, révocable et borné par mandat, sans lancer automatiquement une Activity au premier plan. Respect des sessions existantes et du choix « pause » de l'utilisateur. |
| `habit.suggest` / `habit.reminder_propose` | Suggestions ; aucun check-in automatique non vérifié ni rappel non consenti. |
| `app_policy.propose` / `app_policy.adjust` | L'agent peut ajuster des seuils/horaires doux **uniquement** pour des packages déjà sélectionnés, dans les bornes et pour la durée préautorisées (mandat explicite par package). Au-delà des bornes, pour une nouvelle app ou pour durcir un mode au-delà du mandat : confirmation. Jamais pour accorder une permission OS, retirer la denylist vitale ou supprimer l'accès d'urgence. |
| `content.recommend` | Ajouter une suggestion validée aux cartes autorisées ; ouverture externe uniquement après tap explicite, avec fallback. |

Ne pas permettre au modèle de choisir librement un package système ou un Intent arbitraire. Valider durées, plages horaires et fuseaux, seuils, destinations, domaines/URL, packages déjà consentis, max de changements/jour, états actuels, priorité des choix utilisateurs et cooldown. Les actions sont refusées par défaut lorsqu'inconnues/expirées/révoquées/rejouées. Empêcher une boucle qui replanifie à l'infini ou ressuscite une session annulée.

Distinguer clairement : (a) autorisation **Android OS** accordée par écran système exclusivement ; (b) consentement **ATLAS** d'observer une application ; (c) mandat donné à l'**agent** pour suggérer ou modifier une règle. Ne pas confondre ces trois niveaux dans le code ou l'UI.

## Lot C — Intégration OpenClaw côté backend

- Conserver `MockAgentAdapter`. Ajouter un `OpenClawAgentAdapter` sélectionnable par configuration (`mock` par défaut tant que l'utilisateur n'active pas explicitement l'agent). Le backend valide la proposition via Pydantic + policy et n'exécute aucune commande système. Ajouter un endpoint de discussion et un endpoint de plan/proposition compatibles avec le client Android, auth existante, quotas et journal.
- Préférer l'API officielle `/v1/chat/completions` du Gateway, activée explicitement, avec le modèle d'agent dédié `openclaw/<atlas-agent-id>` ; tester le nom effectif sur la version installée. Garder la possibilité de changer de transport derrière l'interface sans refonte Android.
- Produire des objets structurés, parser strictement sans accepter du texte libre comme commande. Échec de parsing = aucune action. Vérifier les limites et le support réel des outils-fonctions si utilisés ; ne pas permettre au modèle de déclencher ses propres outils système.
- Gestion sessions/conversations : identifiant stable non sensible, pas de mélange entre utilisateurs/conversations, temps d'expiration, budget de tokens/coût configurable, limite d'appels/minute, timeout, retry non dangereux, circuit breaker, fallback mock/local explicite.
- Proactivité bornée : calcul d'un plan du jour ou revue contextuelle sur demande, et éventuellement à cadence basse configurable (par ex. une fois le matin), avec quota/coût et interrupteur global. Ne pas créer de boucle agent continue, réveil agressif ou notifications incessantes.
- Curation : proposer des URL existantes issues de la bibliothèque locale ou, après activation, de flux RSS/API publics explicitement sélectionnés et vérifiés côté backend. Ne jamais inventer une URL ni prétendre accéder au flux de recommandations privé YouTube. Une recommandation peut être affichée automatiquement, mais le lien ne s'ouvre qu'après un tap.
- Mémoire de projets **simple mais utile** : objectifs, projets actifs, priorités, fenêtres de disponibilité, préférences de coaching, décisions antérieures, engagements ; conservation et suppression explicites. Les projets peuvent être édités/corrigés manuellement. Pas de collecte clandestine d'applications, sites, SMS, écran ou historiques privés.
- Contexte transmis au LLM : sélection et minimisation avec panneau « données partagées avec le coach ». Par défaut, catégories non consenties vides ; ne pas transmettre d'identifiants de paquets, URLs, titres d'habitudes ou projets sans consentement adéquat. Les contenus externes sont des **données non fiables**, incapables de modifier le mandat ou la politique.
- Tests complets d'authentification, sécurité et appels mock OpenClaw HTTP ; tests d'intégration Gateway réel si disponible, clairement séparés.

## Lot D — Acheminement Android et exécution locale effective

- Réutiliser l'authentification HTTPS/Bearer et l'outbox V4, tout en évitant d'exposer les secrets OpenClaw à l'APK. Android reçoit les propositions/ordres en attente par un canal de données authentifié et **récupéré par le client** ; ne pas créer de serveur HTTP accessible à l'agent sur le téléphone.
- Ajouter boîte de réception durable, reprise réseau, idempotence, TTL, acquittement, journal d'effets, horloges/fuseaux robustes. Une proposition reçue n'est jamais équivalente à « action exécutée ».
- Ajouter un petit `ActionDispatcher` Android par action allowlistée, qui vérifie le mandat et l'état local au moment de la consommation ; route vers les méthodes/domaines existants du `AtlasController`, Focus, routines, contenus, observation et HOME. Interdire tout Intent ou code arbitraire. Pour un effet en arrière-plan, respecter AlarmManager/notifications et restrictions d'Android 17 (pas de lancement forcé d'écran). Pour un démarrage programmé, persister et rendre une annulation immédiate visible ; préciser si l'alarme est inexacte.
- Réception lorsque l'interface est ouverte ou via travail différé explicitement autorisé ; ne pas promettre de livraison instantanée sous Doze, après force-stop ou sans réseau. Pour une commande nécessitant interaction, notification ou carte à confirmer au retour dans ATLAS.
- Préserver le LocalMock et un bouton unique « désactiver agent » ; les sessions locales doivent rester utilisables hors ligne. Annulation locale / pause d'urgence prioritaires sur tout plan de l'agent.

## Lot E — UX de coaching et affinage ciblé

- **Thème sombre par défaut**, persistant et indépendant du thème Android, avec préférence `Sombre / Noir AMOLED / Suivre le système / Clair`. Pour OLED, privilégier les surfaces noires ou quasi noires sans sacrifier contraste et lisibilité ; réduire les surfaces claires et animations inutiles. Ne pas imposer le thème à l'utilisateur qui choisit le clair.
- HOME : une directive claire, courte, contextualisée, une seule action dominante, cartes secondaires compactes pour prochain focus et suggestion de contenu ; favoris visibles, tiroir rapide, paramètres/sortie secours préservés. Ne pas afficher de texte long généré par IA comme titre de launcher ; borner la longueur et tronquer proprement avec ouverture du détail.
- Focus : écran simple `Session proposée` → `Démarrer`, `Pause/Reprendre`, `Terminer/Annuler` ; paramètres avancés Reef/Pomodoro repliés dans « Personnaliser ». Le coach prépare durée et cycles, mais l'utilisateur garde la possibilité de corriger. Montrer l'heure de fin approximative et l'état des notifications sans faire croire à des alarmes exactes.
- Écran Coach : vraie conversation, propositions visibles avec motif et sources de contexte, actions `Accepter`, `Modifier`, `Plus tard`, `Refuser`, et statut réel. Panneau `Mandat et autonomie` permettant d'activer/désactiver chaque action, les horaires/quotas, les catégories de données partagées, le mode `Propositions uniquement / Actions préautorisées`, et la révocation totale.
- Indiquer clairement « Agent connecté », « Agent indisponible — fonctions locales actives », « Action refusée par la politique », « Action planifiée » ; ne pas masquer les erreurs.
- **Les graphiques et statistiques avancées attendront V7** ; ne faire que conserver les événements structurés qui permettront leur calcul ultérieur.
- Screenshots avant/après en thèmes sombre et AMOLED ; vérifier fontScale 2, paysage, contraste, navigation HOME et permissions.

## Lot F — Recette bout en bout et sécurité

Préparer et exécuter au minimum :

1. CI GitHub verte ; JVM, Android instrumenté sur l'AVD existant Android 17 / API 37.2, lint, build, pytest backend. Documenter les nombres réellement exécutés, pas ceux des rapports V5.
2. Réponse OpenClaw réelle à une demande « Que dois-je faire maintenant ? », convertie en directive HOME visible **si** Gateway installé/autorisé ; sinon, preuve HTTP avec faux Gateway et statut « intégration réelle non vérifiée ».
3. Proposition de Focus préparée par l'agent ; acceptation manuelle, persistance, minuteur local, notification, pause et annulation. Essai séparé d'une action préautorisée dans un mandat limité.
4. Tentative de changement d'application surveillée sans consentement → refus ; réglage de friction **à l'intérieur d'un mandat spécifique par package** → action bornée et journalisée ; durcissement au-delà du mandat → confirmation ; package protégé → refus ; révocation de mandat pendant planification → aucune exécution.
5. Projet/objectif utilisateur → proposition contextuelle cohérente ; une donnée non consentie n'est pas transmise. Prompt injection dans un titre ou contenu → aucune escalade d'outil ou permissions.
6. 401, timeout, erreur de JSON, réponse inconnue, replays, TTL expiré, réseau coupé/repris, reboot, force-stop, perte de notification → fonctionnement local maintenu et aucune action surprise.
7. Contenu proposé avec URL contrôlée, ouverture externe après tap, échec de handler et repli navigateur. Aucun contrôle privé de ReVanced revendiqué.
8. HOME réel choisi comme défaut puis retour au Pixel Launcher ; galerie captures ; aucune donnée utilisateur réelle/secret dans les preuves.
9. Mesure et documentation des limites de détection UsageStats hors UI selon `docs/v5/02-observation-hors-interface.md` ; n'en déduire aucune surveillance permanente imaginaire.
10. Livraison d'un rapport honnête `docs/v6/RECETTE.md`, `docs/v6/SECURITE_ET_MANDAT.md`, `docs/v6/CONTRATS.md`, `docs/v6/LIMITES.md`, et d'un guide utilisateur pour configurer OpenClaw localement et révoquer l'accès.

**Critère de réussite :** sur Android 17, l'utilisateur formule une demande au coach, le modèle réel renvoie une proposition contextualisée, celle-ci apparaît dans ATLAS HOME/Coach, et une action de Focus acceptée/explicitement préautorisée est réellement exécutée, persistée et journalisée par Android. Si OpenClaw n'est pas installé, la V6 ne peut pas être déclarée « intégration agent validée » ; seule l'infrastructure/mock peut être déclarée prête.

## Délégation et coordination Nimbalyst

Ton rôle est l'orchestration. Si le MCP Nimbalyst est disponible, utiliser `spawn_session` obligatoirement pour les lots indépendants, en vérifiant d'abord la signature réelle :

- `model: "openai-codex:gpt-6-luna"`
- `effortLevel: "xhigh"`
- `notifyOnComplete: true`

Suggérer des sessions bornées : A baseline/Gateway ; B contrats et vérificateur ; C backend/OpenClaw (après B) ; D Android dispatcher (après B) ; E UX (après B, parallélisable avec C/D en isolant les fichiers) ; F tests/recette finale (après intégration). Pour chaque session : périmètre, fichiers autorisés/interdits, dépendances, preuves de test et rapport de fin. Ne pas laisser deux agents modifier simultanément `AtlasController.kt`, le manifeste, les contrats ou les fichiers Gradle. Utiliser le suivi des sessions pour examiner et corriger les contributions. Si les outils Nimbalyst sont absents, le signaler précisément plutôt que prétendre les avoir utilisés.

Ne travaille pas à coups de maquettes statiques ; code, intègre, teste et documente. Demande une décision à l'utilisateur seulement si nécessaire : notamment choix du serveur OpenClaw, authentification/modèle, ou autorisation d'installation/activation d'un service.

## Compte rendu final exigé

- Commit(s), branche, changements de contrats et fichiers principaux.
- Tests réels, logs/screenshot, résultat CI.
- Scénarios avec agent **réel** versus faux Gateway/mock, explicitement séparés.
- Matrice des actions : proposée / confirmable / préautorisable / interdite ; limites Android et révocation.
- Instructions minimales de démarrage OpenClaw et backend sans secret dans le dépôt.
- Limites restantes et prochaine amélioration recommandée, sans appeler « terminé » ce qui n'a pas été testé.

**Démarre par le lot A, puis livre des tranches verticales exécutables : une conversation réelle, une directive HOME réelle, un Focus réellement planifié avant d'ajouter les autres outils.**
