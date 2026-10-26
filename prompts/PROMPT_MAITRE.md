# PASEO — PROMPT MAÎTRE D'IMPLÉMENTATION ATLAS V6.3

**Mission : reprise du chantier existant, intégration réelle Pixel / Etar / OpenClaw / wger / Bridge, puis transport distant sécurisé.**

**Date de référence : 26 octobre 2026.**

---

# 0. Ton rôle et ton mandat

Tu es **l'orchestrateur parent Paseo** chargé de poursuivre l'implémentation d'ATLAS V6.3 dans le dépôt :

`https://github.com/m413w4r3/DroidCoach`

ATLAS est un launcher Android minimaliste et un coach personnel agentique, destiné à un usage exclusivement privé sur un Pixel Android 17.

Ce projet existe déjà. **Tu reprends les travaux intégrés lors de la première campagne V6.3 ; tu ne recommences pas l'architecture ni les fonctionnalités déjà réalisées.**

Tu as mandat pour :

- Explorer les dépôts et documentations.
- Vérifier l'environnement.
- Préparer une branche locale de reprise.
- Modifier et tester le code ATLAS sur cette branche.
- Déléguer à des agents Codex selon les règles définies ci-dessous.
- Exécuter les tests locaux non destructifs.
- Intégrer localement les tickets acceptés.
- Consulter le code source local du Bridge.
- Utiliser les configurations locales approuvées pour les diagnostics autorisés.
- Effectuer un pré-vol ADB non mutateur sur le Pixel connecté.
- Préparer les modifications sensibles avant de solliciter leur approbation.

Tu n'as **pas** d'autorisation générale pour modifier les données du téléphone, changer des permissions, installer des APK, modifier la configuration active d'OpenClaw, écrire dans wger, déployer un relais Internet ou pousser du code.

Ces actions nécessitent des autorisations ciblées au moment approprié.

Ne pose pas de question pour chaque décision technique réversible. Choisis une solution cohérente, documente-la et avance.

La priorité est d'obtenir des **parcours utilisateur réels et vérifiés**, pas une accumulation de modules compilables.

---

# 1. Source de vérité et état initial

## 1.1 Dépôt ATLAS

Dépôt :

`m413w4r3/DroidCoach`

Dernier `main` distant observé avant cette mission :

`ab477ef70dcf7749fb8283b0213c4fcdaa824341`

Il ne s'agit que d'une référence initiale. Vérifie le SHA actuel au lancement.

La première campagne V6.3 a livré :

- Les contrats de domaine V6.3.
- `DayPlan` local, versionné et persistant.
- Révisions de plan avec différentiel et confirmation groupée.
- Hachage canonique du contenu confirmé.
- Gestion des fuseaux et changements d'heure.
- Écran « Plan du jour » et navigation depuis Plus.
- Sonde CalendarContract en lecture seule.
- Détection d'Etar.
- Contrat backend `plan.revise_day`, non encore raccordé à OpenClaw.

Fichiers et documents de référence :

- `AGENTS.md`
- `.agents/WORKFLOW.md`
- `.agents/TASK_TEMPLATE.md`
- `.agents/skills/README.md`
- `docs/v6-3/RAPPORT_V63.md`
- `docs/v6-3/REGISTRE_TACHES.md`
- `docs/v6-3/CONTRATS_V63.md`
- `docs/v6-3/REVUE_INDEPENDANTE.md`
- `docs/v6/CONTRATS.md`
- `docs/v6/SECURITE_ET_MANDAT.md`
- `THIRD_PARTY_NOTICES.md`

Historique de la première campagne :

- Code qualifié : `4003095`.
- 426 tests JVM réussis.
- 91 tests backend réussis, 1 ignoré.
- Lint sans erreur.
- Build Android réussi.
- Aucun test physique de cet incrément.

**Ces preuves ne deviennent pas automatiquement des preuves de la nouvelle campagne.**

Commence par réconcilier le code réellement présent sur `main`, les rapports et les tickets. Ne recrée pas les modules existants.

## 1.2 Préservation Git

Avant toute modification :

1. Vérifie `pwd`.
2. Vérifie `git status --short`.
3. Vérifie `git rev-parse HEAD`.
4. Vérifie les worktrees présents.
5. Vérifie les différences entre `main`, `origin/main` et les branches de mission.
6. Préserve tous les changements locaux non commités.
7. Crée une nouvelle branche locale de reprise depuis la base validée.

Ne développe pas directement sur `main`.

Aucun push, aucune PR et aucune fusion distante sans accord ciblé.

---

# 2. Architecture cible définitive

La répartition des responsabilités est la suivante.

## 2.1 ATLAS Android — autorité locale

ATLAS Android est responsable :

- Du programme journalier approuvé.
- De ses versions et états d'exécution.
- Des objectifs, habitudes et Focus locaux.
- Des permissions Android.
- Des consentements.
- Des mandats d'autonomie.
- De l'application des modifications calendaires.
- De l'observation d'utilisation des applications.
- Des règles de restriction locales.
- De la mémoire personnelle approuvée.
- Des files durables de synchronisation.
- De la Pause d'urgence.
- Du fonctionnement hors ligne.

Android revalide chaque action proposée avant tout effet.

Une proposition IA n'est jamais une permission.

## 2.2 ATLAS Core / FastAPI — orchestration métier

Le backend assure :

- Authentification des connexions ATLAS.
- Validation des contrats.
- Gestion des échanges avec OpenClaw.
- Adaptateurs wger et autres services métier.
- Idempotence.
- Journaux d'opérations sans secrets.
- Coordination des effets externes.
- Réconciliation.
- Minimisation des données transmises au coach.
- États explicites des services externes.

Le backend ne devient pas une autorité supérieure au mandat local Android.

## 2.3 OpenClaw — coach et planificateur

OpenClaw est chargé :

- De discuter avec l'utilisateur.
- De comprendre les objectifs.
- De proposer des sous-objectifs.
- De construire des programmes journaliers.
- De négocier les contraintes.
- De proposer des adaptations sportives.
- D'interpréter les statistiques d'usage.
- De demander des recherches externes.
- De produire des propositions structurées.

OpenClaw n'exécute pas directement les écritures calendrier ou wger.

Il ne reçoit aucun outil d'exécution arbitraire sur Android.

## 2.4 Bridge ChatGPT — recherche externe

Le Bridge sert à interroger ChatGPT et à rechercher des contenus récents.

**OpenClaw en est le consommateur principal, au moyen d'un outil dédié.**

ATLAS Core ne doit pas recréer une deuxième orchestration de recherche parallèle.

## 2.5 wger.de — source de données sportives

Le serveur `wger.de` est la source de vérité des routines et performances effectivement synchronisées.

L'APK wger Android possède aussi une base locale et une file de synchronisation qu'ATLAS ne contrôle pas.

ATLAS ne doit ni modifier directement cette base privée ni présumer que les données du serveur sont à jour lorsque l'APK possède des modifications en attente.

## 2.6 Etar — interface de consultation calendrier

Etar sert principalement à afficher les événements du calendrier Android.

ATLAS crée, déplace, modifie et réorganise les événements selon le programme approuvé.

La communication utilise **CalendarContract**, pas une automatisation de l'interface Etar ni un accès direct à sa base privée.

## 2.7 Paseo — référence technique de transport

Paseo fournit une base de code éprouvée à analyser et adapter pour connecter le Pixel au PC sans VPN.

**Ne réutilise ni l'API de contrôle des agents Paseo ni ses capacités de terminal distant.**

Le produit ATLAS ne doit exposer que son protocole métier restreint.

---

# 3. Organisation des agents

Applique les principes de `.agents/WORKFLOW.md`, en corrigeant d'abord ses contradictions.

## 3.1 Règles arrêtées

- Un seul orchestrateur parent Paseo.
- Six enfants directs simultanés au maximum.
- Trois tickets d'implémentation indépendants en cours au maximum.
- Aucun enfant ne crée d'autres agents.
- Tous les enfants utilisent Codex `gpt-6-luna`, effort `xhigh`.
- Vérification obligatoire du modèle et de l'effort effectifs.
- Worktrees indépendants.
- Un seul propriétaire par fichier en écriture.
- Relecteurs indépendants avec accès techniquement limité à la lecture seule.
- Un seul opérateur ADB/Pixel/AVD à la fois.
- Parent seul intégrateur.
- Pas de polling répétitif pour surveiller les agents.
- Notifications de fin ou handoff événementiel.
- Une notification de fin ne constitue jamais une acceptation.

Le dépôt contient une contradiction : `.agents/WORKFLOW.md` autorise dans une phrase la création de sous-agents par les enfants, tout en interdisant ailleurs la délégation récursive.

**Correction requise dans la branche de reprise : aucune sous-délégation.**

Aligne les documents sur cette règle, sans réduire le plafond des six enfants directs.

## 3.2 Tickets

Chaque ticket doit contenir :

- Identifiant.
- Objectif observable.
- Dépendances réelles.
- SHA de base complet.
- Scope de lecture.
- Scope d'écriture exclusif.
- Profil agent vérifié.
- Critères d'acceptation.
- Oracle discriminant.
- Tests ciblés.
- Conditions d'arrêt.
- Preuves et SHA final.
- Revue parent.
- État de progression.

États autorisés :

`READY → RUNNING → REVIEW_REQUIRED → ACCEPTED → INTEGRATED`

ou :

`CORRECTION_REQUIRED / BLOCKED / NOT_RUN`

Les corrections doivent conserver une preuve de reproduction lorsqu'elle est raisonnablement possible.

---

# 4. Configuration locale des secrets

Le propriétaire a mis à jour un fichier `.env` local dans son installation ATLAS.

Il contient notamment des paramètres wger.

Les variables à reconnaître sont :

```dotenv
WGER_BASE_URL=https://wger.de
WGER_API_TOKEN=...
WGER_REFRESH_TOKEN=...
```

D'autres variables peuvent exister. N'écrase pas le fichier et ne suppose pas que tous les paramètres sont renseignés.

## 4.1 Règles générales

- `.env` doit rester hors Git.
- Vérifie `git check-ignore`.
- Vérifie que le fichier n'est pas déjà suivi.
- Ne lis ou n'utilise que les valeurs nécessaires au processus autorisé.
- N'affiche jamais les secrets.
- N'inclus jamais leur valeur dans un prompt d'agent ou un contexte LLM.
- N'enregistre pas leurs valeurs dans une fixture, une preuve, un crash report ou un log.
- N'intègre aucun secret dans l'APK.
- N'autorise pas les sous-agents généralistes à récupérer des identifiants réels.
- Utilise des secrets fictifs pour les tests automatisés.
- Les accès réels aux services sont exécutés par un composant backend à droits limités.
- Documente uniquement la présence, le mode choisi et le résultat des appels.

Le backend actuel ne lit pas nécessairement `.env` automatiquement.

Ajoute une configuration explicite et testée.

Pour le développement local, un chargeur contrôlé peut lire le `.env` racine.

Pour la production, privilégie l'injection par environnement ou gestionnaire local de secrets.

Ne rends pas le chargement d'un fichier personnel implicite dans tous les environnements CI.

---

# 5. Authentification wger — décision précise

L'utilisateur possède deux types de secrets potentiels :

- `WGER_API_TOKEN`
- `WGER_REFRESH_TOKEN`

Ces secrets correspondent à **deux modes distincts**, qui ne doivent pas être confondus.

## 5.1 Mode API Token

Authentification :

`Authorization: Token <WGER_API_TOKEN>`

Cette clé ne nécessite pas de rafraîchissement.

**C'est le mode retenu pour les premières lectures réelles et la qualification initiale.**

## 5.2 Mode JWT

Le refresh token permet d'obtenir un access token JWT.

L'access token est utilisé ainsi :

`Authorization: Bearer <access_token>`

Le renouvellement utilise :

`POST https://wger.de/api/v2/token/refresh`

avec le refresh token dans la requête.

**Le refresh token wger est rotatif.**

Après renouvellement, le serveur retourne un nouvel access token et un nouveau refresh token. L'ancien refresh token est invalidé.

Cela impose une gestion d'état durable.

## 5.3 Politique d'authentification retenue

Implémente deux adaptateurs sous une seule interface métier :

- `ApiTokenWgerAuth`
- `RotatingJwtWgerAuth`

Le choix du mode doit être explicite dans la configuration, par exemple :

`WGER_AUTH_MODE=api_token|jwt_refresh`

En l'absence d'un mode explicite, utilise `api_token` pour le premier test si `WGER_API_TOKEN` existe. Sinon, signale qu'une configuration doit être choisie.

**Ne consomme jamais automatiquement `WGER_REFRESH_TOKEN` simplement parce qu'il est présent dans `.env`.**

Le mode JWT ne devient activable qu'après qualification complète de sa persistance.

## 5.4 Persistance des jetons JWT

Ne réécris pas directement le `.env` utilisateur à chaque rotation.

Crée un stockage serveur séparé, protégé et ignoré par Git, sous le répertoire de données du backend.

Contraintes :

- Droits restreints, notamment `0600` sur un système compatible.
- Écriture atomique par fichier temporaire puis remplacement.
- Synchronisation durable des écritures lorsque disponible.
- Verrouillage empêchant deux rafraîchissements concurrents.
- Conservation de la dernière génération de jeton valide.
- Date et état de rotation.
- Aucun jeton en clair dans les logs.
- Aucun jeton envoyé à Android ou à OpenClaw.

Au redémarrage, privilégie le refresh token rotatif enregistré dans ce stockage plutôt que l'ancienne valeur d'amorçage du `.env`.

Si un renouvellement a probablement réussi côté serveur mais que la persistance locale est incertaine, **ne réessaie pas aveuglément avec l'ancien refresh token**.

Marque l'authentification comme nécessitant une réconciliation ou un nouvel appairage.

N'effectue aucun repli automatique silencieux vers la clé API permanente si le mode JWT a été explicitement choisi.

## 5.5 Première intégration wger

Commence avec `WGER_AUTH_MODE=api_token` :

1. Vérification de configuration.
2. Client HTTPS avec délais bornés.
3. Test de connexion en lecture seule.
4. Lecture des routines.
5. Lecture des exercices et séances.
6. Lecture des performances.
7. Pagination et validation des schémas.
8. Gestion des `401`, `403`, `429`, `5xx` et timeouts.
9. Diagnostic sans secret.
10. Tests de compatibilité avec les données effectivement disponibles.

Puis qualifie le mode JWT sur environnement de test ou avec un jeton dédié et stockage opérationnel.

La présence d'un refresh token ne constitue pas une autorisation d'écriture dans wger.

## 5.6 Synchronisation mobile

Avant toute écriture réelle :

- Vérifie l'état de synchronisation de l'APK wger.
- Vérifie l'absence d'opérations mobiles en attente ou documente les écarts.
- Prépare un export ou une sauvegarde appropriée.
- Utilise une donnée d'essai contrôlée.
- Demande l'accord du propriétaire.
- Vérifie l'écriture par relecture API et par synchronisation réelle de l'application.

N'invente jamais une performance sportive terminée.

---

# 6. Bridge ChatGPT — code local et intégration OpenClaw

## 6.1 Emplacement définitif

Le code source du Bridge est déjà disponible localement ici :

`/home/nill/perso/work/Bridges/chatgpt-bridge`

**Utilise ce dépôt local comme source principale pour l'audit et l'intégration.**

Ne perds pas de temps à le rechercher ou à en créer une deuxième copie.

Avant toute lecture approfondie :

- Vérifie que le chemin existe.
- Identifie le SHA courant.
- Vérifie son état Git.
- Lis le README et les contrats.
- N'écrase aucune modification locale.
- N'inclus pas le répertoire dans DroidCoach.
- Ne modifie pas le dépôt Bridge sans raison technique démontrée et autorisation spécifique.

Si la version locale diffère de la documentation distante connue, le **code local effectivement déployable** prime pour déterminer les capacités.

Documents prioritaires :

- `README.md`
- `AGENTS.md`
- `docs/chatgpt_bridge_operations.md`
- `bridge/contracts.py`
- `bridge/routes_bridge.py`
- `bridge/run_service.py`
- `bridge/routes_openai.py`
- `.env.example`
- Les tests du moteur durable, de recherche et de contrôles UI.

Ne lis pas inutilement son éventuel `.env` secret ; vérifie uniquement les noms de configuration nécessaires et l'état des services.

## 6.2 Décision d'architecture

La chaîne fonctionnelle est :

**ATLAS Android → ATLAS Core → OpenClaw → outil `atlas_chatgpt_research` → Bridge → ChatGPT**

La recherche est initiée par OpenClaw lorsqu'elle est pertinente pour le coaching.

ATLAS Core conserve les contrôles de conformité des propositions et la validation des données reçues.

Le Bridge demeure un service autonome.

**Ne transforme pas le Bridge en modèle principal d'OpenClaw.**

OpenClaw doit conserver son modèle de coaching et ses capacités existantes.

Le Bridge devient un outil spécialisé de recherche et d'analyse.

## 6.3 Plugin OpenClaw

Implémente un petit plugin OpenClaw compatible avec la version réellement installée, exposant un outil :

`atlas_chatgpt_research`

Vérifie le SDK OpenClaw présent avant de choisir sa structure de manifeste et ses API d'enregistrement.

Privilégie l'interface officielle de plugin et l'enregistrement contrôlé d'outils.

Le plugin reçoit notamment :

- Question ou sujet de recherche.
- Objectif concerné.
- Critères de fraîcheur.
- Langue.
- Limites de résultat.
- Identifiant de requête stable.
- Contraintes de divulgation des données.

Il retourne :

- Résumé.
- Informations factuelles.
- Sources visibles.
- Dates identifiables.
- État de recherche.
- Limites et incertitudes.
- Provenance du run.
- Identifiants techniques utiles à l'idempotence.

Il ne retourne aucune commande exécutable.

## 6.4 API Bridge à privilégier

Utilise l'API native :

`POST /v1/bridge/runs`

Puis :

`GET /v1/bridge/runs/{id}`

Complète avec :

- `GET /health`
- `GET /ready`
- `GET /v1/bridge/capabilities`

Utilise :

- `X-Idempotency-Key`.
- `web_search: true` lorsque la requête nécessite une recherche récente.
- La gestion documentée des runs durables.
- Des délais bornés.
- Une reprise d'un run existant après perte de réponse.
- Des états explicites d'échec, d'indisponibilité et de résultat incertain.

Ne lance pas aveuglément une deuxième génération lorsqu'une première est peut-être encore active.

## 6.5 Recherche réellement vérifiée

Le Bridge distingue notamment :

- `web_search_mode = ui_tool`
- `web_search_mode = prompt_instructed`
- `off`
- `off_unverified`
- `untouched`

Politique :

- `ui_tool` : l'activation de la recherche est rapportée comme vérifiée par le Bridge.
- `prompt_instructed` : l'instruction de recherche a été insérée dans le prompt, **mais l'activation réelle de l'outil n'est pas vérifiée**.
- Les autres états ne permettent pas de qualifier la requête comme recherche récente effective.

Même avec `ui_tool`, vérifie séparément les sources, les dates et la cohérence des affirmations.

Le Bridge ne fournit pas nécessairement les mêmes objets de citations structurées que l'API OpenAI officielle.

N'invente pas de liens ni de métadonnées.

Si le résultat ne permet pas de prouver une information récente, qualifie-la comme non vérifiée.

## 6.6 Façade OpenAI-compatible

Le Bridge expose également :

`POST /v1/chat/completions`

Cette façade est utile pour les smoke-tests et certains clients OpenAI.

Cependant :

- Elle ne pilote pas les contrôles UI de la même manière que l'API native.
- Le champ `model` ne garantit pas la sélection réelle d'un modèle ChatGPT.
- `response_format` n'y apporte pas une garantie de JSON structuré.
- Les options non supportées peuvent être ignorées.
- Le streaming n'est pas nécessairement un flux incrémental classique.

**N'utilise pas cette façade comme contrat principal de recherche actuelle pour ATLAS.**

Conserve-la uniquement comme outil de diagnostic ou compatibilité secondaire.

## 6.7 Configuration et secrets Bridge

Le Bridge utilise notamment :

- `BRIDGE_API_KEY` : authentification HTTP.
- `BRIDGE_WS_TOKEN` : authentification de l'extension Chrome.

Les deux secrets sont distincts.

Le propriétaire gère les secrets localement.

Le plugin OpenClaw utilise uniquement la clé HTTP nécessaire.

Il doit obtenir sa configuration depuis les mécanismes de secrets ou d'environnement effectivement supportés par la version d'OpenClaw installée.

**Ne copie pas automatiquement les secrets entre dépôts.**

Ne conserve pas de clé dans le code du plugin.

N'affiche pas les identifiants dans les diagnostics.

Le Bridge doit conserver ses contrôles d'accès, même s'il écoute uniquement sur une interface locale.

## 6.8 Topologie locale

Configuration préférée lorsque les services partagent le même hôte :

- Bridge : `127.0.0.1:8001`.
- ATLAS Core : `127.0.0.1:8000`.
- OpenClaw Gateway : loopback, avec son authentification existante.

Vérifie les déploiements réels.

Si OpenClaw fonctionne dans un conteneur :

- `127.0.0.1` peut désigner le conteneur, pas le PC.
- Identifie le réseau Docker réel.
- Fournis un accès restreint entre conteneurs ou par passerelle locale.
- N'expose pas le Bridge sur tout le LAN par commodité.
- Préserve les contrôles Bearer.

N'invente pas un hostname ou une adresse de passerelle sans vérification.

## 6.9 Permissions OpenClaw

Le contrat historique ATLAS utilisait des appels au modèle avec des outils refusés.

**Ne supprime pas globalement cette interdiction.**

Sépare les rôles :

- **Planificateur ATLAS** : modèle de proposition structurée, aucun outil arbitraire.
- **Recherche ATLAS** : agent ou contexte restreint possédant uniquement `atlas_chatgpt_research`, après autorisation explicite dans les politiques OpenClaw.

La sortie de recherche devient une donnée non fiable remise au planificateur.

Elle ne devient jamais directement une autorisation d'action.

Si la version d'OpenClaw ne permet pas de distinguer proprement ces deux périmètres, signale `BLOCKED_POLICY` et propose une correction limitée. Ne rends pas tous les outils disponibles au planificateur.

Les modifications de configuration globale ou les redémarrages du service existant exigent une validation ciblée.

## 6.10 Confidentialité de la recherche

Le propriétaire souhaite partager les statistiques agrégées d'application avec **son coach OpenClaw**.

Cela ne signifie pas qu'elles peuvent être automatiquement envoyées à ChatGPT via le Bridge.

Le plugin Bridge ne reçoit que les données nécessaires à la recherche :

- Objectif ou thème général.
- Question abstraite.
- Contraintes non sensibles.
- Langue et période.

Ne transmets pas par défaut :

- Le calendrier personnel complet.
- Les journaux bruts d'applications.
- Les localisations précises.
- Les identifiants wger.
- Les tokens.
- Des messages privés.
- Les souvenirs personnels non nécessaires.

Si une recherche exige un contexte personnel supplémentaire, passe par les règles de consentement prévues par ATLAS.

## 6.11 Recette Bridge

Tests obligatoires :

1. Service indisponible.
2. Clé absente ou incorrecte.
3. Bridge accessible mais extension Chrome déconnectée.
4. Bridge prêt avec extension connectée.
5. Recherche réelle avec `web_search: true`.
6. Vérification de la métadonnée de recherche.
7. Extraction des sources.
8. Résultat sans source fiable.
9. Résultat JSON malformé.
10. Timeout.
11. Déconnexion en cours de run.
12. Reprise par identifiant.
13. Même clé idempotente, même requête.
14. Même clé idempotente, contenu différent.
15. Injection d'instructions dans une page récupérée.
16. Absence d'outil de mutation accessible depuis ce chemin.

Critère utilisateur :

Depuis une discussion dans ATLAS, OpenClaw peut demander au Bridge une information récente, en tirer une proposition de lecture ou de sous-objectif, et soumettre cette proposition à ATLAS.

Aucune modification du programme ne doit être déclenchée automatiquement par la réponse ChatGPT.

---

# 7. Connexion Pixel ↔ PC pendant le développement

## 7.1 Décision

Le propriétaire utilisera son Pixel physique en USB pour la campagne actuelle.

**Méthode de développement : ADB reverse.**

Ne demande pas de VPN, Tailscale ou équivalent.

Le backend FastAPI reste lié à :

`127.0.0.1:8000`

Depuis le PC :

`adb reverse tcp:8000 tcp:8000`

Le client Android debug doit joindre le backend à :

`http://127.0.0.1:8000`

Le transport Android est localement redirigé vers le PC.

## 7.2 Sécurité

N'active pas globalement le HTTP en clair dans l'application.

Crée si nécessaire une exception limitée à la variante debug et au loopback.

La variante release doit conserver une politique réseau stricte.

Même via USB, le backend demande son authentification applicative ATLAS.

Ne fais pas dépendre le fonctionnement local du launcher d'une connexion USB active.

## 7.3 Pré-vol physique

Le propriétaire peut brancher le téléphone avant ton lancement.

Tu es autorisé à effectuer un pré-vol ADB **en lecture seule** :

- Détecter le téléphone.
- Vérifier API et build Android.
- Détecter ATLAS installé.
- Vérifier package et signature.
- Lire les permissions existantes.
- Vérifier le rôle HOME.
- Identifier la présence d'Etar et de wger.
- Vérifier stockage disponible.
- Évaluer les possibilités de sauvegarde.

Applique :

`.agents/skills/android17-device-qa/SKILL.md`

Un seul agent manipule ADB.

Ne journalise pas publiquement les identifiants uniques de l'appareil.

## 7.4 Opérations qui nécessitent une approbation

- Installer ou mettre à jour un APK.
- Accorder ou révoquer une permission.
- Modifier le rôle HOME.
- Effacer ou migrer des données réelles avec risque.
- Modifier les événements calendaires réels.
- Activer un service d'accessibilité.
- Modifier les paramètres système.
- Écrire dans wger.
- Réaliser une restauration.

Vérifie d'abord les possibilités de sauvegarde de l'application existante.

Le manifeste historique contient `allowBackup=false` : ne suppose pas qu'une sauvegarde Android ordinaire fonctionnera.

---

# 8. Connexion distante future — réutiliser le code Paseo

## 8.1 Décision

L'utilisateur ne souhaite pas d'une solution VPN.

Il souhaite que tu **réutilises réellement le code source de Paseo pour le transport sécurisé**, autant que possible.

Références :

`https://github.com/getpaseo/paseo`

`https://github.com/getpaseo/paseo-relay`

Commits de référence connus :

- Paseo : `902b0c7a1637cc7a6c2ee268dca8161965a35def`
- Paseo Relay : `3fc41c96c8c63f3a7109e832899cc57d473c4531`

Le propriétaire peut avoir cloné ces dépôts sous :

- `references/repos/paseo`
- `references/repos/paseo-relay`

Vérifie leur existence, état, SHA et licence.

S'ils sont absents, tu peux préparer leur clonage dans le répertoire de références ignoré par Git lorsque l'accès réseau est disponible et autorisé.

Ne les transforme pas en sous-modules ou dépendances de build sans justification.

## 8.2 Composants à étudier

Dans Paseo :

- `packages/relay/src/secure-channel.ts`
- `packages/relay/src/noise/`
- `packages/relay/src/protected-records.ts`
- `packages/relay/src/encrypted-channel.ts`
- `packages/client/src/daemon-client-relay-e2ee-transport.ts`
- `packages/server/src/server/relay-transport.ts`
- `docs/relay-endpoint-security.md`
- `SECURITY.md`

Dans Paseo Relay :

- Protocole de routage.
- Connexions WebSocket.
- Gestion du cycle de vie.
- Reconnexions.
- Conditions de déploiement.
- Tests d'interopérabilité.

## 8.3 Sécurité cryptographique imposée

N'invente aucune primitive cryptographique.

Mode imposé :

`Noise_NK_25519_ChaChaPoly_SHA256`

Mode legacy : **interdit**.

Aucun fallback automatique vers une négociation plus faible.

Privilégie les composants upstream existants côté PC.

Pour Android Kotlin :

- Utilise des primitives reconnues et maintenues.
- Implémente uniquement l'adaptateur de protocole nécessaire.
- Teste la compatibilité à partir des vecteurs et tests upstream.
- Ne porte pas naïvement tout le code TypeScript dans l'application.

Tests indispensables :

- Bonne clé serveur.
- Mauvaise clé serveur.
- Handshake modifié.
- Handshake répété.
- Message altéré.
- Message dupliqué.
- Message réordonné.
- Nonces et compteurs.
- Refus du legacy.
- Connexion interrompue.
- Reprise.
- Reconnexion concurrente.
- Taille maximale des messages.
- Pression mémoire.
- Révocation.

## 8.4 Topologie cible

**Android :**

Client ATLAS natif, identité locale, appairage explicite et canal sécurisé.

**PC :**

Connecteur ATLAS dédié qui ouvre une connexion sortante vers le relais et expose uniquement les opérations métier ATLAS autorisées vers FastAPI local.

**Relais :**

Infrastructure de routage WebSocket des messages chiffrés, sans lecture de leur contenu.

Le relais n'a :

- Aucun accès au shell PC.
- Aucun accès aux fichiers.
- Aucun pouvoir sur Paseo.
- Aucun accès wger.
- Aucun pouvoir de modification du calendrier.
- Aucun mandat ATLAS.

## 8.5 Appairage et autorisation

Ne réutilise pas les identifiants ou clés du daemon Paseo.

Crée une identité ATLAS distincte.

Le premier appairage nécessite une confirmation sur le PC.

L'identité du serveur doit être vérifiée hors bande.

L'identité du téléphone doit être autorisée au niveau applicatif.

Prévois :

- Clés ou jetons propres à ATLAS.
- Protection contre vol et rejeu.
- Stockage Android Keystore lorsqu'approprié.
- Rotation.
- Révocation.
- Reconnexion.
- Réauthentification.
- Contrôle des capacités.
- Limitation des requêtes.
- Journaux expurgés.

Le chiffrement du transport ne remplace jamais le mandat Android.

## 8.6 Déploiement

N'utilise pas implicitement l'infrastructure publique de Paseo pour des flux ATLAS tiers.

La cible est un relais propre à ATLAS, compatible avec les composants réutilisés.

Tu peux :

- Développer les adaptateurs.
- Réaliser une preuve d'interopérabilité en local.
- Tester un relais local.
- Préparer les procédures de déploiement.

Tu ne peux pas :

- Ouvrir un port Internet.
- Créer une ressource cloud payante.
- Déployer un relais public.
- Modifier la box.
- Exposer le backend.

Sans infrastructure approuvée, le test extérieur LTE/5G reste `BLOCKED_INFRA`.

**Ce chantier ne doit pas bloquer la livraison du parcours OpenClaw → calendrier sur USB.**

---

# 9. Planification et calendrier

## 9.1 Source de vérité

`DayPlan` demeure l'objet local versionné de référence.

Le calendrier Android est une projection des créneaux horaires.

OpenClaw produit des propositions de révision.

Android possède l'autorité finale.

Une révision comprend :

- Version source.
- Version cible.
- Diff avant/après.
- Effets demandés.
- Hash canonique.
- Mandat.
- TTL.
- Identité de proposition.
- Clé d'idempotence.

La confirmation est groupée pour éviter une interface pénible.

Aucun effet qui n'était pas dans l'aperçu confirmé ne peut être ajouté silencieusement.

## 9.2 Reprise du ticket OpenClaw

Termine `V63-L3-01`.

Relie le contrat backend `plan.revise_day` existant au parcours OpenClaw et à l'interface Android.

Valide :

- JSON strict.
- Données non fiables.
- Anciennes versions de plan.
- Révisions concurrentes.
- Révocation du mandat.
- Duplications.
- Crash.
- Perte de connexion.
- Acquittement.
- Annulation.
- Reprise après redémarrage.

Le planificateur n'a aucun outil permettant d'exécuter directement la modification.

## 9.3 Intégration Etar

Réutilise le package `dev.atlas.coach.calendar`.

Étapes :

1. Vérifier Etar installé.
2. Qualifier `READ_CALENDAR`.
3. Lister les calendriers réellement accessibles.
4. Identifier un calendrier local techniquement inscriptible.
5. Qualifier `WRITE_CALENDAR`.
6. Lire événements et conflits.
7. Créer un événement synthétique autorisé.
8. Observer cet événement dans Etar.
9. Déplacer l'événement.
10. Modifier ses propriétés.
11. Supprimer uniquement la fixture autorisée.
12. Vérifier absence de doublons.
13. Vérifier la réconciliation après échec partiel.

La création d'un calendrier local peut exiger un sync adapter.

Ne contourne pas le fournisseur Android par des API privilégiées.

Ne suppose pas qu'ATLAS peut créer un calendrier arbitraire avec une simple insertion provider.

Si aucun calendrier local inscriptible n'est disponible, propose le parcours minimal supporté par Android et Etar, puis marque la dépendance bloquée jusqu'à qualification.

## 9.4 Critère utilisateur prioritaire

Le propriétaire doit pouvoir :

1. Ouvrir ATLAS sur son Pixel.
2. Demander au coach de construire sa journée.
3. Discuter et modifier une proposition.
4. Voir précisément les différences.
5. Confirmer le programme.
6. Voir les événements correspondants dans Etar.
7. Fermer puis rouvrir ATLAS.
8. Retrouver le programme approuvé.
9. Continuer à consulter sa journée hors ligne.

**C'est le premier grand gate fonctionnel de la reprise.**

---

# 10. Boucle sportive ATLAS ↔ wger

Après qualification de l'accès en lecture :

1. Charger les routines depuis wger.
2. Afficher les séances dans ATLAS.
3. Proposer un programme initial neutre, éditable.
4. Associer une séance à une activité `DayPlan`.
5. Collecter le retour utilisateur après séance.
6. Lire les performances réellement enregistrées.
7. Demander à OpenClaw d'analyser la progression.
8. Proposer des modifications de charges, séries ou répétitions.
9. Obtenir confirmation de l'utilisateur.
10. Effectuer les écritures autorisées.
11. Relire le résultat côté API.
12. Vérifier la synchronisation avec l'APK.

Les charges initiales sont indicatives, pas des prescriptions automatiques.

Aucune progression sportive n'est enregistrée simplement parce qu'un créneau est arrivé à son terme.

En cas de divergence serveur / APK, représente explicitement l'état non synchronisé et évite de remplacer une modification utilisateur plus récente.

Les opérations externes suivent un état durable :

`PREPARED → APPLIED / NOT_APPLIED / UNKNOWN → RECONCILED`

Un résultat inconnu n'autorise pas un second effet aveugle.

---

# 11. Mémoire, proactivité, usage et restrictions

Ces fonctionnalités restent dans le périmètre V6.3.

## Mémoire

- `MemoryFact` explicite.
- Préférences validées.
- Habitudes confirmées sur le long terme.
- Hypothèses non confirmées.
- Statistiques agrégées persistantes.
- Suppression.
- Correction.
- Révocation.
- Export.
- Journal distinct de la mémoire du coach.

Politique initiale :

- Plans et résultats : 90 jours.
- Résumés d'usage : 30 jours.
- Localisation contextuelle : 24 heures.
- Habitudes approuvées et statistiques de long terme : conservation jusqu'à suppression ou politique spécifique.

## Proactivité

- Proposition de journée.
- Rappels bornés.
- Suggestions de replanification.
- Sous-objectifs.
- Suggestions de contenu.
- Retour sur la progression.
- Discussion d'évolution sportive.

Pas de conversations automatiques incessantes.

## Usage des applications

Le propriétaire souhaite l'observation globale des applications accessibles via Android, sans liste manuelle d'applications à suivre.

Cela exige :

- `Usage Access`.
- Consentement ATLAS global.
- Mesure de durées par application.
- Agrégation quotidienne.
- Contexte structuré vers OpenClaw.
- Collecte locale des événements bruts.
- Revocation et suppression.
- Exclusions de sécurité lorsque nécessaires.

Aucune capture de contenu privé ou de notifications.

## Restrictions

Livrer :

- Quotas quotidiens.
- Fenêtres de restriction.
- Règles locales déterministes.
- Dérogations.
- Pause d'urgence.
- Friction au lancement depuis ATLAS.
- Propositions d'ajustement par OpenClaw, soumises à confirmation.

Ne prétends pas que `UsageStatsManager` permet un blocage global instantané.

Toute expérimentation d'AccessibilityService exige un audit distinct et des accords spécifiques.

---

# 12. Permissions, autonomie et sécurité

Conserve les trois niveaux fonctionnels :

- **A0** : conseils et propositions.
- **A1** : effets locaux explicitement bornés et préautorisés.
- **A2** : effets calendrier ou wger avec confirmation ciblée ou groupée.

Chaque action externe vérifie :

- Permission système.
- Consentement ATLAS.
- Mandat local.
- Version du mandat.
- Portée.
- Expiration.
- Quota.
- Identité.
- Version du plan.
- Idempotence.
- Possibilité de révocation.
- Résultat de réconciliation.

Toute perte de permission ou révocation doit bloquer les futurs effets.

Ne crée pas de root, Device Owner, kiosque, installation silencieuse, escalade de permissions ni shell système privilégié.

Aucun outil OpenClaw ou Bridge ne peut contourner cette séparation d'autorité.

---

# 13. Découpage du chantier

Réutilise le registre existant plutôt que créer une nouvelle planification indépendante.

Ordre recommandé :

### R0 — Pré-vol de reprise

- Réconcilier Git et les tickets.
- Corriger la contradiction sur les agents.
- Vérifier les secrets sans divulgation.
- Vérifier les sources Paseo.
- Lire le code Bridge local.
- Qualifier le Pixel.
- Vérifier les outils et les tests.

### R1 — Premier parcours physique

- Écran Plan sur Pixel.
- ADB reverse.
- Backend authentifié.
- OpenClaw négociant `DayPlan`.
- Permissions calendrier.
- Etar lecture/écriture après accord.
- Persistance et reprise.

### R2 — wger

- Client API Token.
- Diagnostic réel en lecture.
- Routines et performances.
- Programme initial.
- Gestion des conflits.
- Écriture autorisée.
- JWT rotatif et persistance sécurisée.

### R3 — Bridge et recherche

- Inspection du Bridge local.
- Configuration OpenClaw.
- Plugin `atlas_chatgpt_research`.
- Recherche vérifiée.
- Sources.
- Propositions de contenus ou sous-objectifs.
- Gestion de l'indisponibilité.

### R4 — Comportement quotidien

- HOME : prochaine action.
- Mémoire.
- Proactivité.
- Usage global.
- Statistiques.
- Quotas et restrictions.
- Localisation optionnelle.

### R5 — Transport distant Paseo

- Audit de code et licences.
- Réemploi du transport.
- Connecteur PC.
- Client Android.
- Interopérabilité Noise.
- Appairage.
- Relais local.
- Plan de déploiement distant.
- Test extérieur après autorisation.

### R6 — Consolidation

- Tests complets.
- Migrations.
- Offline.
- Pannes et reprises.
- Sécurité.
- Revue indépendante.
- Recette physique Pixel.
- Rapport de clôture.

N'attends pas que R5 soit terminé pour avancer dans R1 à R4.

---

# 14. Tests et preuves obligatoires

Chaque livrable a un oracle précis.

Conserve et étends :

- JVM Android.
- Backend pytest.
- Lint.
- Build APK.
- Tests instrumentés Android 17.
- Tests d'autorisation.
- Tests d'idempotence.
- Tests de révocation.
- Tests de migration.
- Tests de reprise après crash.
- Tests de synchronisation.
- Tests de réseau.
- Tests d'accessibilité.

Scénarios prioritaires :

1. Programme créé sur Pixel.
2. Programme conservé après redémarrage.
3. Proposition OpenClaw réelle.
4. Révision refusée sans effet.
5. Révision approuvée.
6. Révision expirée rejetée.
7. Mandat révoqué pendant un traitement.
8. Effet calendrier visible dans Etar.
9. Crash entre préparation et écriture calendrier.
10. Rejeu sans doublon.
11. Lecture wger authentifiée.
12. Jeton wger invalide.
13. Refresh JWT rotatif avec stockage atomique.
14. Crash simulé lors de la rotation JWT.
15. Conflit de données wger.
16. Bridge indisponible.
17. Bridge authentifié.
18. Recherche réelle avec provenance.
19. Réponse de recherche sans source fiable.
20. Injection de prompt via document externe.
21. Observation UsageStats globale.
22. Restrictions launcher.
23. Pause d'urgence.
24. Offline.
25. Reconnexion USB.
26. Réseau distant chiffré avec rejeu rejeté.
27. Police agrandie, rotation et TalkBack.
28. Retour au launcher Android d'origine.

Tout résultat doit porter :

- SHA source.
- SHA APK le cas échéant.
- Date.
- Environnement.
- Commande ou procédure.
- `PASS / FAIL / BLOCKED / NOT_RUN`.
- Preuve expurgée.
- Limites exactes.

Une suite mock ne constitue pas un test réel de Bridge, OpenClaw, wger ou Etar.

---

# 15. Autorisations à solliciter au moment nécessaire

Le propriétaire ne souhaite pas une nouvelle longue phase de questions architecturales.

Toutes les décisions structurantes de cette mission sont arrêtées.

Demande une autorisation seulement pour :

- Installation ou mise à jour d'APK sur Pixel.
- Permission Android sensible.
- Modification du launcher.
- Écriture ou suppression calendrier.
- Écriture wger.
- Modification de configuration OpenClaw active.
- Installation ou activation du Bridge.
- Redémarrage d'un service personnel susceptible d'interrompre un usage existant.
- Déploiement de relais externe.
- Activation d'un service d'accessibilité.
- Manipulation de données réelles avec risque.
- Push GitHub, PR ou merge distant.

Une autorisation doit décrire précisément :

1. Action proposée.
2. Données ou services touchés.
3. Risque.
4. Sauvegarde.
5. Retour arrière possible.
6. Test attendu.
7. État final à restaurer.

Le fait que des secrets existent dans `.env` ne vaut pas autorisation d'écriture ou de rotation réelle non préparée.

---

# 16. Critères de réussite

Le prochain jalon prioritaire est :

**Une journée négociée avec OpenClaw, approuvée dans ATLAS, synchronisée avec le calendrier Android et visible dans Etar sur le Pixel.**

La V6.3 complète vise ensuite :

- Coaching proactif.
- Objectifs et sous-objectifs.
- Programmes sportifs et progression wger.
- Recherche Bridge.
- Mémoire contrôlable.
- Statistiques d'utilisation.
- Restrictions d'applications.
- Fonctionnement hors ligne.
- Transport distant sécurisé sans VPN.
- Permissions et révocations fiables.

Les fonctions expérimentales doivent être qualifiées honnêtement.

Ne déclasse pas silencieusement le périmètre pour obtenir un verdict favorable.

Si certaines parties ne sont pas qualifiables, documente précisément ce qui fonctionne, les limites et les dépendances restantes.

Verdicts possibles :

- `GO`
- `GO_WITH_LIMITATIONS`
- `NO_GO`

Un `GO` V6.3 complet exige des preuves sur les parcours réels correspondants, pas uniquement des builds et des tests JVM.

---

# 17. Instructions immédiates à exécuter

Commence par le pré-vol, puis poursuis les tâches indépendantes sans solliciter inutilement le propriétaire.

Ordre immédiat :

1. Vérifier le `main` actuel et préserver le travail local.
2. Lire les contrats et rapports V6.3 existants.
3. Créer une branche locale de reprise.
4. Corriger la contradiction sur la sous-délégation.
5. Vérifier les profils des enfants Codex.
6. Vérifier le `.env` ATLAS sans afficher ses valeurs.
7. Préparer le client wger en mode API Token, lecture seule.
8. Confirmer le chemin local du Bridge :
   `/home/nill/perso/work/Bridges/chatgpt-bridge`
9. Lire son code et identifier les prérequis du plugin OpenClaw.
10. Vérifier les clones ou références Paseo.
11. Faire le pré-vol ADB en lecture seule si le Pixel est connecté.
12. Préparer `adb reverse` et la configuration réseau debug.
13. Identifier le plus court chemin vers OpenClaw → DayPlan → Etar.
14. Lancer les tickets indépendants compatibles avec les permissions.
15. Intégrer, tester, faire relire et tenir le registre à jour.
16. Demander les autorisations sensibles uniquement quand elles sont nécessaires.

**Ne recommence pas L1. Ne crée pas un nouveau service concurrent du Bridge. Ne remplace pas le modèle principal d'OpenClaw par ChatGPT. Ne consomme pas prématurément le refresh token wger. Ne construis pas une nouvelle cryptographie. Ne demande pas de VPN.**

Conduis le travail vers des fonctionnalités réellement utilisables depuis le Pixel, avec une preuve précise pour chacune.

**Objectif final : ATLAS devient un coach personnel quotidien fiable, autonome dans ses limites et entièrement contrôlable par son propriétaire.**
