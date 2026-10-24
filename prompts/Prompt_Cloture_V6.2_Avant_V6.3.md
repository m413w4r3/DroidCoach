# ATLAS — Clôture de V6.2 avant V6.3
## Prompt maître pour Paseo / qualification Android 17 physique

**Statut : mandat de travail, NON une validation déjà obtenue.**
**Dépôt :** `m413w4r3/DroidCoach` ; point de départ : `main`, après la fusion de `feat/atlas-v6-2-3` (PR #2, merge `b1b10c87c8e48a57ea59225744a3c41188edd21b`).
**Date de décision produit :** 24 octobre 2026.
**Mission :** terminer les correctifs et les validations de V6.2/V6.2.3 pour permettre ensuite le chantier V6.3 (coaching quotidien personnalisé + engagement local volontaire). Tu dois **exécuter**, pas uniquement proposer un plan.

### 0. Décisions de l'utilisateur : ne pas redébattre

1. **Android 17 / API 37 est la seule cible de qualification Android instrumentée** de cette clôture. **Aucun test Android 14/API 34** à relancer et aucun gate associé. Maintenir JVM, lint, build, backend et tests métier.
2. **Pixel physique Android 17 prioritaire.** L'utilisateur va connecter le téléphone pour une campagne guidée. Un AVD API 37 n'est qu'un complément si son environnement est fonctionnel ; ne pas poursuivre indéfiniment `surfaceflinger`/GPU. Conserver les résultats historiques comme faits, pas comme validation actuelle.
3. Intégration de V6.2.3 dans `main` **déjà autorisée et effectuée**. Ne pas retravailler sur l'ancienne branche. Ouvrir une **nouvelle branche de clôture** sur le HEAD réel de `main` ; aucun push/merge sans nouvelle autorisation explicite.
4. Corriger les **défauts prouvés** et les obstacles concrets à une utilisation quotidienne. Ne pas lancer une grande réarchitecture, une V6.3 déguisée, un nouveau composant de blocage, ni une refonte UX générale.
5. Exploiter les **skills ATLAS sous `.agents/skills/`** ; vérifier leurs chargements et les profils Paseo réellement disponibles. Le prompt présent reste la source du périmètre produit.

### 1. Orchestration Paseo

**Architecture non négociable : un seul orchestrateur parent Paseo**, modèle choisi par l'utilisateur (ex. GPT-6.1 Sol Medium), **et TOUTES les sous-sessions `openai-codex:gpt-6-luna` avec effort `xhigh`** : exploration, diagnostics, tests, implémentation, corrections, intégration déléguée et revue indépendante. **Aucun autre modèle, aucun effort `high`/Medium**. Si le profil Luna XHigh n'est pas disponible ou si ses métadonnées effectives ne correspondent pas, déclarer `BLOCKED`, sans remplacement silencieux.

**Lire et appliquer `.agents/WORKFLOW.md` et `.agents/TASK_TEMPLATE.md` avant le premier lancement.** Le parent gère les autorisations, tickets, sessions et preuves et approuve les changements. Maximum **3 enfants actifs au total**, **aucune sous-délégation récursive**, worktrees isolés, **1 propriétaire par fichier** et **1 seul acteur ADB/AVD/téléphone**. Les fichiers `AtlasController.kt`, navigation, manifest et Gradle appartiennent à une unique phase d'intégration ; faire revoir le diff final par un **autre Luna XHigh**.

Ne pas présumer que la configuration d'AutoWork, les profils Paseo ou les permissions existent sur cet hôte : afficher les véritables modèles/efforts effectifs et les outils disponibles, sans substituer silencieusement un autre modèle.

Utiliser les notifications / événements de fin des agents pour réceptionner les travaux, examiner les diffs, relancer une correction ciblée et intégrer après contrôle. **Pas de boucle `sleep`, `while` ou polling récurrent** de statut. En absence de réveil événementiel, enregistrer un handoff, rendre la main et reprendre explicitement. Après une interruption, reconstituer Git, les sessions et les derniers tests avant toute nouvelle délégation.

Skills à charger selon leur description :
- `android17-device-qa` pour toute opération ADB sur le Pixel.
- `diagnosing-bugs` pour échecs/crash/flakes.
- `implement-spec` pour correctifs délégués.
- `code-review` pour contrôle indépendant et GO/NO-GO.
- `context-handoff` pour toute coupure.

Le parent rédige `docs/v6-2-3/CLOTURE_V62_ETAT.md` avec le registre de travaux et les décisions fondées sur preuves. Les rapports historiques ne doivent pas être écrasés.

### 2. Pré-vol obligatoire : un état réel, une seule baseline

Sur `main`, enregistrer `git status`, HEAD, historique après PR #2, version Android SDK/Gradle/JDK/Python, CI effective et scripts disponibles.

Lire :
- `AGENTS.md`, `.agents/skills/README.md` et skills ciblés ;
- `docs/v6-2-3/API37_DIAGNOSTIC.md`, `API37_MATRICE.md`, `STABILISATION_RAPPORT.md`, `UI_RAPPORT.md` ;
- `docs/v6-2/ISSUE_1_LOCALISATION.md`, `docs/v6-2/RECETTE_V621.md` ;
- `docs/01-produit.md`, `docs/02-architecture.md`, `docs/06-direction-visuelle.md` ;
- `docs/AUDIT_CIBLE.md`, `docs/v6-2/REEMPLOI_OSS.md`, `THIRD_PARTY_NOTICES.md`.

Séparer résultats sur SHA historique et résultats sur le HEAD courant. La CI existante valide JVM/lint/build/Python ; elle **ne** prouve **pas** les parcours système Android 17. Les 14 tests Pixel de QA-R4 concernent la révision décrite dans le rapport, pas automatiquement tout le futur code.

Établir la table initiale : `PASS / FAIL / BLOCKED / NOT_RUN`, pour chaque scénario et SHA. Ne pas compter une liste de classes annotées `@Test` comme des tests exécutés.

### 3. Téléphone Pixel : préserver l'utilisateur

Le propriétaire branchera le Pixel. **Attendre la connexion/notification ou sa confirmation sans polling.** Aucune action physique possible tant que `adb devices -l` ne montre pas un seul appareil autorisé et sélectionné ; ne jamais rediriger un script destructif vers un AVD/personnel par erreur.

**Lecture seule d'abord :** identification API 37, modèle, signature et version APK, HOME actuel, autorisations, notifications, stockage, chargement et service/commandes disponibles. Garder les numéros de série hors Git.

**Sauvegarde :** vérifier les anciennes archives si présentes, mais ne pas supposer que `/tmp/v623-qa/...` existe encore. Produire une **nouvelle sauvegarde cohérente des seules données ATLAS** si techniquement possible ; SHA-256 et stockage sûr hors dépôt et hors `/tmp` uniquement. Si impossible, classer les scénarios à risque `BLOCKED` et choisir seulement les actions non destructives.

Inspecter les classes `androidTest` et scripts : identifier `pm clear`, suppression, `GrantPermissionRule`, changement appops/permissions, tests qui modifient le stockage local, rôle HOME, rotation et fixtures. **Ne pas lancer `connectedAndroidTest` en aveugle sur un téléphone contenant des données personnelles.** Construire une **allowlist explicite** de tests sans effets dangereux ou travailler avec une installation/identité de test séparée seulement après vérification de sécurité.

Actions physiques autorisées uniquement dans le cadre convenu et après pré-vol :
- Lire journaux ciblés `adb`, statut, UI ; lancer ATLAS et un test identifié non destructif.
- Installer par remplacement le même package seulement si sauvegarde vérifiée, signature compatible, version/migration acceptables et autorisation explicite du propriétaire.
- Demander une confirmation par opération pour changement du launcher HOME, permissions, partage de localisation, suivi mobilité, rotation/police globale, usage réseau externe ou restauration.
- Rétablir les permissions et réglages modifiés en fin de session ; préserver données et anciens choix HOME.
- Toujours laisser `Pause` et retour au launcher système immédiatement accessibles.

**Interdits sans autorisation distincte :** `adb uninstall`, `pm clear`, reset, root, Device Owner, écriture directe dans les bases/prefererences privées, suppression de WAL/SHM WorkManager, installation de tiers, effacement de sauvegarde, modification d'autres apps. Une déconnexion impose arrêt des actions et bilan `NOT_RUN` des étapes restantes ; ne pas annoncer une restauration finale non vérifiée.

### 4. Matrice de qualification Android 17

Tester d'abord les chemins essentiels, puis les extensions. Chaque item reçoit `PASS/FAIL/BLOCKED/NOT_RUN`, type de test (automatisé, manuel, simulé), SHA, preuve expurgée et état après test.

**P0 — vital pour une première version personnelle**
1. Installer/mettre à jour sans effacer les données, démarrer à froid, revenir après arrêt de processus.
2. HOME : choix volontaire du rôle Android, liste/favoris/recherche, ouverture téléphone/messages et sortie vers launcher initial ; aucun piège.
3. Hors réseau/backend indisponible : HOME, Pause d'urgence, paramètres de confidentialité, Focus et objectifs restent utilisables.
4. Focus libre et associé à objectif actif ; pause/reprise/annulation/fin ; progression exactement une fois, y compris après redémarrage.
5. Stockage : panne d'écriture simulée sur JVM, contrôle réel des parcours de reprise sur Android, aucune réussite mensongère.
6. Agent désactivé ou mandat révoqué : aucun nouvel effet réseau interdit, proposition expirée refusée, contexte actuel retiré des nouveaux payloads.

**P1 — intégrations réelles à clore**
7. Notifications : permission refusée/accordée, activation des routines, notification proactif, tap ouvrant le Coach, retrait après révocation.
8. Location : refus, permission approximative, timeout, fournisseur arrêté, âge max, coarsening, consentement Coach indépendant.
9. Mobilité : démarrage visible après geste, notification `Arrêter`, absence de trace brute et de redémarrage silencieux.
10. Rotation/paysage, police 2×, 320/360 dp dans les configurations disponibles, TalkBack sur actions critiques.
11. Mode silencieux, WorkManager/proactivité bornée, quotas/anti-double notification et absence de boucle gourmande.
12. Performance et consommation au repos : mesures avec durée, modèle et conditions ; aucune généralisation abusive de l'AVD au Pixel.

L'AVD API 37 : si disponible sans crash système, compléter les variantes contrôlées (ex. permission mock/géo) avec zéro utilisation de données privées. Si `surfaceflinger` échoue avant lancement d'ATLAS, enregistrer `AVD_ENVIRONMENT_BLOCKED`, ne pas en déduire un bug de l'app et **ne pas l'utiliser comme gate**. **Ne pas exécuter Android 14.**

### 5. E2E coaching réellement personnalisé : gate produit

Établir deux parcours distincts.

**A. Sans backend :** créer un objectif local de 120 minutes, démarrer un Focus explicitement lié de 25 minutes, vérifier progression 0 avant fin puis 25 après fin, redémarrer et obtenir toujours 25, vérifier annulation sans crédit et Pause d'urgence. Une FakeClock doit être annoncée comme simulation.

**B. Backend FastAPI + adaptateur OpenClaw réel :**
- Configurer un environnement et un jeton **éphémères**, non publiés, avec mandat explicite de partage.
- Faire parvenir au Coach l'objectif actif, le Focus et la progression du contexte réellement consenti.
- Vérifier qu'une réponse se réfère **effectivement au contexte actuel** et propose une prochaine action praticable.
- Accepter une action autorisée : vérifier `MandateVerifier`, journal idempotent et effet local.
- Refuser ou révoquer en cours de requête : vérifier aucune action et aucune catégorie retirée dans la requête suivante.
- Couper le réseau : retour au fonctionnement local.
- Ne pas qualifier de `REAL_OPENCLAW_PASS` une réponse mockée, un screenshot du chat ou une assertion purement textuelle sans contrôle du payload et des effets.

Ne pas transmettre de vraies habitudes, objectifs ou coordonnées personnels aux fixtures ou logs. Utiliser des données synthétiques pour le test distant.

### 6. Qualité UX et réemploi des références

Le design noir/ivoire/gravure de V6.2.3 est la base. Ne pas revenir à Material chargé. **Prioriser un parcours réellement maniable**, pas une nouvelle DA ou une galerie de PNG.

Contrôler en conditions réelles : arrivée HOME, découverte de l'action du jour, accès favoris, ouvrir Coach, lancer Focus lié, revenir HOME, modifier consentement, pause d'urgence. Demander au propriétaire trois irritants UX maximum ; corriger d'abord les erreurs d'ergonomie vérifiables : actions absentes, hiérarchie ambiguë, tap targets, troncatures, scroll dangereux, contraste, TalkBack et navigation.

Comparer les implémentations existantes aux clones locaux `references/repos/AndyClaw`, `Lawnchair`, `Olauncher`, `openclaw`, `Reef`, `uhabits`. Vérifier leurs SHA, licences, les adaptations attribuées dans `THIRD_PARTY_NOTICES.md` et le coût de maintenance. **Ne pas intégrer du code juste pour « utiliser davantage les références »**. Toute adaptation nouvelle exige un problème précis et une revue de licence.

### 7. Réparation bornée et revue finale

Pour chaque défaut classé `APP_BUG` :
1. Reproduction ciblée qui échoue avant correctif.
2. Correctif minimal par sous-session **GPT-6 Luna XHigh**, propriétaire unique du fichier.
3. Test précédent devenu vert + régressions pertinentes.
4. Revue du diff réel et test de reprise quand stockage/mandat concernés.
5. Intégration locale par le parent ; pas de push.
6. Après toutes les corrections, revoir **la révision intégrée finale**, pas seulement les commits antérieurs.

Deux reviewers indépendants, si disponible : conformité au besoin produit et sécurité/qualité. Un `NO_GO` exige une correction du P0 avant clôture. Faire au plus trois tentatives sur un défaut donné, puis produire une analyse de blocage plutôt qu'une boucle infinie.

Ne pas déployer de système coercitif, d'AccessibilityService, de root, de Device Owner, de blocage d'autres apps ou de nouvelle autonomie de l'agent ; ces éléments appartiennent au périmètre V6.3+ et à un consentement dédié.

### 8. Documentation et critères de clôture

Documenter sous `docs/v6-2-3/cloture-v62/` :
- `MATRICE_ANDROID17_PIXEL.md` : tests exécutés, révisions, sorties et état des permissions/restaurations.
- `E2E_PERSONNALISE.md` : entrée/fonctions du coach, mandat, synthèse du payload, action vérifiée, révocation, sans secrets.
- `REVUE_INDEPENDANTE.md` : constats P0/P1/P2 par `chemin:ligne`, correction et nouvel oracle.
- `UI_PARCOURS.md` : irritants et remèdes, captures seulement si réellement produites et expurgées.
- `REFERENCES_OSS.md` : SHA locaux, adaptations, licences, aucune nouvelle copie non justifiée.
- `RAPPORT_CLOTURE_V62.md` : synthèse avec HEAD exact, preuve et GO/NO-GO.

Conserver la sauvegarde physique hors Git et ne référencer que son existence/empreinte si nécessaire. Ne jamais publier de serial, localisation réelle, jeton, textes privés de conversation, fichier backup ou log brut sensible.

**GO vers V6.3 seulement si :**
- JVM/lint/build/backend vert sur HEAD final ; aucune régression nouvelle.
- Sur Pixel Android 17 : HOME et sortie, Focus/objectif/fin unique, Pause/hors ligne, permissions/révocation critiques et notifications passés ; pas de corruption de données observée.
- Localisation/mobilité consenties et contrôlées, ou fonctionnalités désactivées et explicitement classées non validées avec décision du propriétaire (pas de « PASS » fictif).
- E2E OpenClaw réel démontré, ou absence de backend réel documentée comme **NO-GO coaching personnalisé**.
- Une revue indépendante du code final ne contient aucun P0.
- Des limites P1 non critiques peuvent être acceptées **uniquement par décision explicite de l'utilisateur**.
- AVD API 37 bloqué par l'environnement : peut être non-bloquant si Pixel physique validé ; issue #1 ne peut être fermée que si son critère de recette spécifique est satisfait ou modifié officiellement avec accord utilisateur.

**Livrer :** branche/SHAs, changements, tests avec `PASS/FAIL/BLOCKED/NOT_RUN`, scénario Pixel, état de la sauvegarde, action utilisateur requise, issue #1, conclusion `V6.2_CLOSED` ou `V6.2_NOT_CLOSED`, et description courte du vrai périmètre V6.3.

**Dès le lancement :** vérifie le vrai HEAD et les skills, prépare la campagne Pixel non destructive, délègue les explorations possibles en attendant l'appareil, puis exécute les tests physiques seulement lorsque le téléphone et les autorisations nécessaires sont présents.
