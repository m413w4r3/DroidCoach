# ATLAS — PROMPT PRINCIPAL V3 : orchestration Nimbalyst, préanalyse réalisée, objectif VM-1

**Mission impérative** : construire un MVP Android réellement compilé et testé sur émulateur lorsque l'environnement le permet, puis un backend léger. Tu es **orchestrateur de réalisation** : délègue le code en lots bornés à des sessions Nimbalyst ; assure interfaces, intégration, contrôle des modifications, vérifications et corrections. Ne transforme pas cette mission en rapport d'étude.

## 0. Documents à lire et ordre de priorité

1. `README.md` et `AGENTS.md` (cadre produit et sécurité) ;
2. **`docs/08-phase1-preanalyse.md`** (sélection open source **déjà faite**) ;
3. **`docs/09-plan-lots-et-recette-vm.md`** (contrats, lots, protocole VM-0/VM-1) ;
4. `docs/01-produit.md`, `docs/02-architecture.md`, `docs/04-recette-et-tests.md`, `docs/06-direction-visuelle.md`, `docs/07-emulateur-linux.md`, `contracts/directive.schema.json`.

`docs/00-specification-initiale.md` demeure le contexte historique ; le lire **en cas de besoin**, sans consommer des tokens pour le relire intégralement en début de chaque sous-session. `docs/03-audit-open-source.md` est un **protocole historique initial** : **ne pas réexécuter son audit complet**. En cas de divergence sur la phase 1 ou l'organisation des lots, **08 et 09 prévalent**. Ne pas supprimer l'historique.

## 1. Décisions déjà prises — appliquer, ne pas redébattre sans obstacle démontré

- Produit : **ATLAS Android** (expérience principale) + **ATLAS Core** (backend optionnel/remplaçable). Le MVP Android doit fonctionner entièrement sans serveur, IA, clé ni autre app installée.
- **Olauncher UX** : https://github.com/tanujnotes/Olauncher — référence prioritaire pour la sobriété du launcher, texte, peu de commandes ; **ne pas forker** pour VM-1.
- **Lawnchair technique** : https://github.com/LawnchairLauncher/lawnchair — référence pour les fonctions avancées HOME, **ne pas forker** pour VM-1.
- **Reef focus** : https://github.com/aload0/Reef — source d'idées timer, focus/routines/intervention ; **ne pas intégrer AccessibilityService, blocker ou fork** pour VM-1. Fichiers ciblés documentés dans 08.
- **Loop habitudes** : https://github.com/iSoron/uhabits — tracker externe facultatif à terme ; **ATLAS a son propre modèle d'habitudes simple** dès VM-1.
- **ReVanced vidéo** : https://github.com/ReVanced/revanced-manager et https://gitlab.com/revanced/revanced-patches — appli externe non pilotée ; vidéo via `ACTION_VIEW`/choix Android ; pas de modification d'APK.
- **AnkiDroid** https://github.com/ankidroid/Anki-Android, **Miniflux** https://github.com/miniflux/v2, **OpenClaw** https://docs.openclaw.ai/ sont des options **hors VM-1**.
- Objectif *usage privé*. Ne pas consacrer une session entière aux licences ; enregistrer l'origine des éventuelles portions de code réutilisées, respectant les notices. **Ne pas copier du code tiers par défaut**.
- Pas de root, Device Owner, écoute permanente, collecte d'écran, AccessibilityService, blocage d'applications tierces ou permissions automatiques pour la V0. Ne pas durcir les restrictions au moyen de décisions LLM.

## 2. Orchestration Nimbalyst — obligatoire si les outils sont exposés

- Utiliser le **MCP hôte Nimbalyst `spawn_session`** pour déléguer les lots ; préférences de session demandées :
  - `model: "openai-codex:gpt-6-luna"`
  - `effortLevel: "xhigh"`
  - `notifyOnComplete: true`
- **Avant tout appel**, vérifier les signatures de `spawn_session` et outils de suivi réellement exposées dans le contexte. Ne pas inventer des paramètres ni prétendre qu'une session a été créée. Si une valeur n'est pas reconnue, documenter l'erreur et utiliser une méthode officiellement prise en charge **seulement si elle respecte l'intention de l'utilisateur** ; ne pas changer silencieusement de modèle.
- Outils attendus si accessibles : `spawn_session`, `list_spawned_sessions`, `get_session_status`, `get_session_result`, `send_prompt`, éventuellement `get_workstream_edited_files`, `list_worktrees`. Les employer avec leur schéma réel.
- **Tu orchestras, tu ne codes pas tous les modules toi-même.** Ton écriture directe doit se limiter aux contrats, plan de tâches, intégration mineure, gestion de conflits, revue et rapport. Les modifications importantes de code passent par les sous-sessions assignées.
- Chaque tâche déléguée indique explicitement : **objectif**, **fichiers/dossiers autorisés**, **fichiers interdits**, **contrats d'entrée/sortie**, **dépendances**, **tests obligatoires**, **définition de terminé**. Demander au sous-agent de renvoyer les commandes vraiment lancées, résultats et chemins des artefacts.
- Privilégier des worktrees/branches par lot lorsqu'ils existent ; sinon disjoindre strictement les dossiers. Inspecter les diffs avant intégration. Ne jamais lancer deux agents sur les mêmes fichiers globaux, `android/app/build.gradle.kts`, manifest, configuration Gradle, navigation, ou base `data/`.
- Maximum **trois sessions exécutantes simultanément**. Ne pas multiplier les agents de recherche ni leur faire relire toute la spécification. En cas de dépendance bloquante, finir le lot amont avant de lancer l'aval.
- Le suivi est obligatoire : attendre et récupérer les résultats, analyser le diff et les tests ; demander la correction des erreurs avec les outils Nimbalyst. Ne pas déclarer un lot terminé au simple « j'ai codé » d'un sous-agent.
- Si `spawn_session` est absent, l'indiquer clairement comme blocage d'orchestration (ne pas simuler des sous-agents). Produire un plan de lots utilisable et exécuter seulement les vérifications d'environnement possibles en attendant l'accès aux outils ; ne pas contourner silencieusement l'obligation de délégation.

## 3. Démarrage : environnement puis première preuve VM-0

1. Relever `git status`, le dossier racine, `uname -m`, JDK, Python, SDK/ADB/emulator, KVM/AVD. L'IDE est installé dans **`/opt/android-studio/bin`** ; **ce n'est pas nécessairement le chemin du SDK**. Détecter chemin SDK dans variables d'environnement, `local.properties`, Android Studio, puis `$HOME/Android/Sdk`.
2. Ne pas demander une clé IA, un serveur, une installation de ReVanced ou des permissions de surveillance. Ne pas effacer les AVD existants. Installer des dépendances système ou changer groupes/permissions Linux seulement après consentement si nécessaire.
3. Créer `docs/PLAN_EXECUTION.md` en partant **des lots déjà spécifiés** dans 09, pas d'un nouveau plan abstrait. Verrouiller un nom de package Android et la source de vérité des interfaces.
4. **Déléguer S0** : projet Gradle Android Compose minimal, `ATLAS` affiché sur Activity ordinaire, manifest HOME admissible mais non imposé, build debug. Si SDK+AVD opérationnels, installer, lancer, capturer, inspecter logcat.
5. Consigner les preuves `docs/VALIDATION_VM.md` et démarrer VM-0 le plus tôt possible. Si AVD absent, écrire la commande ou l'action Android Studio nécessaire, marquer `NON TESTÉ — environnement` et avancer sur tests de code exécutables.

## 4. Lots à déléguer — ordre, limites, tests

Le découpage détaillé et les chemins sont dans `docs/09-plan-lots-et-recette-vm.md`. **Utiliser ces lots sans les redécouper arbitrairement** :

### S0 — Fondation / build / HOME minimal
- Propriétaire unique : `android/` Gradle, wrapper, manifest, package root, navigation de base et script `scripts/build-android.sh`.
- Compose minimal, activité `MAIN/LAUNCHER`, possibilité d'être HOME via mécanisme Android standard/opt-in ; jamais remplacement forcé du launcher de l'AVD.
- Critère : `:app:assembleDebug` PASS ; et si AVD actif, ADB install + lancement + screenshot sans crash. **Même sans fonctionnalités, VM-0 est un jalon utile**.

### S1 — Domaine focus + règles (en parallèle de S2 après S0)
- Seulement `<pkg>/focus/**` + `<pkg>/rules/**` et tests unitaires correspondants, aucun fichier Gradle/manifest/nav.
- Pomodoro 25/5, variante 10, repos long 15 après 4 cycles ; horloge monotone pendant activité, données persistables pour reprise ; états et transitions idempotentes. Mode test accéléré.
- Règle déterministe 4 ouvertures/25 min, cooldown 15 min par règle/app, déduplication d'events, quiet hours, pause 30 min et exceptions vitales. Simulateur d'événements injectable sans usage réel.
- Tests unitaires PASS ; aucune boucle de notifications, aucun blocage Android réel et aucune dépendance au réseau/LLM.

### S2 — Habitudes + directives + catalogue limité (en parallèle de S1 après S0)
- Seulement `<pkg>/habits/**`, `<pkg>/coach/**`, `<pkg>/content/**` et tests correspondants. Pas de nav ni manifest/Gradle.
- Habitudes : nom, fréquence simple, ajout/check-in/correction, historique 7/30j ; enregistrements exportables. Le passage focus→habitude exige consentement/règle, pas un check automatique implicite.
- Directive mock liée à un objectif, cinq réponses `start/discuss/alternative/postpone/pause` avec état et journal testables ; aucune vraie IA requise.
- Catalogue max 3 exemples avec URL validée ; ouverture externe par intent `ACTION_VIEW`, fallback. Aucune détection/gestion privée de ReVanced.
- Tests unitaires PASS ; DTO et règles explicites, sans accès à la DB privée de Loop.

### S3 — Intégration app Compose + persistance + UX Olauncher
- **Après S1 et S2** ; seul propriétaire des fichiers globaux `Activity`, navigation, écran HOME, paramètres/consentements, stockage local et export.
- UI originale **priorité Olauncher** : peu d'éléments et typographie dominante, une directive clairement visible, accès Focus/Habitudes/Bibliothèque/Coach/Paramètres, commandes explicites dont pause d'urgence ; contraste/accessibilité.
- Attacher domaines S1/S2 via interfaces stables, adapter Room/DataStore ou stockage local simple selon coût réel. Démo complète hors ligne ; demander les permissions d'usage **seulement** depuis une action explicite de réglages, échec traité proprement.
- **VM-1 obligatoire à tenter** : écran accueil → directive et action → focus court/pause/reprise/fin → habitude ajout/check/correction → simulateur 4 événements/friction unique → ouvrir vidéo → mode hors réseau → sortie HOME.
- Si émulateur disponible : build, install, `adb shell am start`, tests, screenshots accueil/focus, logcat, preuves. Sinon `NON TESTÉ` avec diagnostic exact.

### S4 — Backend mock/testable (après VM-1, sauf lot totalement indépendant)
- Uniquement `backend/**`, tests, README serveur ; adapter les schémas `contracts/**` seulement avec coordination.
- FastAPI/Pydantic et SQLite minimal : `GET /health`, `GET /v1/directives/current`, `POST /v1/directives/{id}/respond`, `POST /v1/events`, `GET /v1/plan/today`, `GET /v1/policy/effective`, `GET /v1/content/recommendations`, `POST /v1/coach/message` (mock), `POST /v1/sync`, `GET /v1/export`.
- Mode mock sans clé IA ; `AgentAdapter` extensible mais OpenClaw **non prétendu intégré**. Rejeter actions hors mandat et les messages de contenu non fiables ; idempotence `event_id` ; tests pytest ; app Android ne doit pas dépendre du serveur pour fonctionner.
- Pas d'API non authentifiée exposée publiquement ; local seulement par défaut.

### S5 — Revue ciblée + reprise des erreurs
- Relecture indépendante des diffs et validation des résultats ; focus sur crashes, timer, redémarrage, répétition notifications, sortie HOME, permissions, cohérence entre modules.
- Faire corriger dans **la session propriétaire des fichiers** (ou créer une session de correction à périmètre exclusif). Relancer les tests après chaque correction. Produire un relevé court de preuve, pas une dissertation.

## 5. La « phase 1 — audit open source » est pour l'essentiel déjà réalisée

Tu n'as **PAS** pour mission de :
- re-lister toutes les applications open source du projet, ni produire un nouvel essai comparatif ;
- cloner tous les repos `references/repos-candidats.yaml`, compiler leurs applications ou indexer tout leur contenu ;
- faire un audit de licences de plusieurs heures pour un simple APK privé sans emprunts de code ;
- proposer cinq architectures différentes à l'utilisateur avant de compiler quoi que ce soit.

**Tu dois seulement** vérifier les incertitudes d'implémentation qui bloquent réellement un lot. Si un code tiers devient nécessaire, ouvrir directement son URL/fichier ciblé (08), récupérer seulement ce dépôt sous `_research/` (ignoré par Git), relever le SHA, inspecter le fichier/manifest et la dépendance, puis noter décision en 10–20 lignes dans `docs/AUDIT_CIBLE.md`. Les lois Android (permissions, arrière-plan, politiques) sont de véritables contraintes et ne disparaissent pas parce que l'usage est personnel.

## 6. Tests et protocole d'honnêteté

Pour chaque rapport/déclaration : distinguer explicitement **PASS**, **FAIL** et **NON TESTÉ**. Une capture d'écran n'existe que si elle a été générée sur AVD. Pas d'assertion de test Pixel physique ni d'inférence d'API ReVanced.

- Android : Gradle avec chemins réels ; tests JUnit pour focus/rules/habits/directive ; tests Android instrumentés si AVD accessible.
- VM-1 : `adb` sur AVD identifié, install APK, démarrage via vrai package/Activity, logcat sans crash au lancement, screenshots, essais manuels/instrumentés des parcours.
- Backend : `pytest`, tests autorisation/consentement et idempotence. Si pas de Python ou dépendance, déclarer blocage et procédure.
- Conserver `docs/VALIDATION_VM.md`, `docs/VALIDATION.md`, captures/logs sous `docs/captures/` si produits, README commande de lancement et chemin réel de l'APK.
- S'assurer que la pause d'urgence et le retour au launcher système fonctionnent sans réseau ; ne pas tenter un blocage tiers par accessibilité.

## 7. Définition de terminé

**VM-0** : sources Kotlin + build vérifié, installation et capture sur AVD si disponible. **VM-1** : premier ATLAS local effectivement utilisable sur émulateur avec directive, minuteur, habitudes, simulateur de friction, liens vidéo et sortie de secours. **Backend** : service mock testé et indépendant, déployable ultérieurement.

Dans le rapport final, mettre : sessions Nimbalyst lancées, lot/owner, status obtenu par outil, fichiers effectivement changés, builds/tests effectivement exécutés et leur sortie, APK et captures vrais ou `NON TESTÉ`, limites rencontrées et commandes concrètes pour reproduire.

**Commence directement.** Diagnostic minimal, spawn S0, VM-0, puis S1/S2, intégration S3, VM-1, S4, S5. Pas de phase de recherche générale redondante.
