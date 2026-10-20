# ATLAS V6.2 — Mission d'implémentation à architecture verrouillée
## 0. Rôle de l'orchestrateur Nimbalyst
Tu es l'orchestrateur principal de l'implémentation ATLAS V6.2.
Dépôt : [https://github.com/m413w4r3/DroidCoach](https://github.com/m413w4r3/DroidCoach)
Base attendue : `main`, commit `ee1e7b58c06531f6fdc631a42cdd7522d8ee90e1`, à vérifier.
**L'architecture, les composants open source à reprendre, les comportements produit et les contrats de cette itération sont définis ci-dessous. Tu dois les exécuter, pas les redéfinir.**
Utiliser les sous-sessions Nimbalyst :
- `model: "openai-codex:gpt-6-luna"`
- `effortLevel: "xhigh"`
- `notifyOnComplete: true`
- Maximum 3 exécutants simultanés.
Vérifier les signatures réelles des outils avant invocation. Utiliser worktrees ou branches isolées quand disponibles.
Tu es responsable de l'intégration, de la revue des diffs, des tests, des corrections et du résultat final.
Ne pas pousser ou fusionner sans accord explicite. Ne pas effacer les données ni l'AVD personnel.
### Documents à lire
`AGENTS.md`, `docs/01-produit.md`, `docs/06-direction-visuelle.md`, `docs/v6/SECURITE_ET_MANDAT.md`, `docs/v6/ANDROID_API.md`, `docs/v6-1/RECETTE.md`, `docs/v6-1/REVUE_VISUELLE.md`, `THIRD_PARTY_NOTICES.md`, `prompts/ATLAS_V6_AGENT_OPENCLAW.md`.
Les décisions de ce prompt prévalent sur les anciennes orientations d'implémentation en cas de divergence, sans supprimer les garanties de sécurité V6.1.
## 1. Références open source — sélection imposée
Les références existantes sont conservées :
```javascript
references/repos/
  lawnchair/
  Olauncher/
  Reef/
  uhabits/
```
### Cloner OpenClaw officiel
URL : [https://github.com/openclaw/openclaw](https://github.com/openclaw/openclaw)
SHA de référence : `e3fe108378f6eca2d4e2a4333fd08bf81bb87105`
Destination : `references/repos/openclaw/`
Licence : MIT, sous réserve de vérifier les en-têtes des fichiers repris.
Effectuer un clone partiel avec sparse checkout des répertoires utiles.
**Fichiers à adapter dans ATLAS :**
```javascript
apps/android/app/src/main/java/ai/openclaw/app/node/
  LocationCaptureManager.kt
  LocationCoarsener.kt
  LocationDisclosure.kt

apps/android/app/src/main/java/ai/openclaw/app/
  LocationMode.kt
```
**Fichiers à consulter sans copier intégralement :**
```javascript
apps/android/app/src/main/java/ai/openclaw/app/node/
  LocationHandler.kt

apps/android/app/src/main/java/ai/openclaw/app/
  PermissionRequester.kt
  NodeForegroundService.kt

docs/nodes/location-command.md
```
Tests à adapter :
```javascript
apps/android/app/src/test/java/ai/openclaw/app/node/
  LocationCaptureManagerTest.kt
  LocationPrivacyTest.kt
  LocationHandlerTest.kt
```
Les tests sources utilisant Robolectric, ajouter uniquement les dépendances de test nécessaires si leur adaptation est rentable.
Ne pas importer la Gateway, NodeRuntime, le système de pairing, les permissions universelles ou le framework d'outils OpenClaw.
### Cloner AndyClaw
URL : [https://github.com/EthereumPhone/AndyClaw](https://github.com/EthereumPhone/AndyClaw)
SHA : `fd4a9af917f51d7b1c10e09e6496f54a290b253b`
Destination : `references/repos/AndyClaw/`
Licence : GPL-3.0, vérifier les fichiers ciblés.
**Adaptation prévue :**
```javascript
app/src/main/java/org/ethereumphone/andyclaw/heartbeat/
  NotificationTriggerPolicy.kt
```
Réutiliser la logique de déduplication et de temporisation, mais pour les événements de coaching ATLAS.
Nom du composant ATLAS : `ProactiveTriggerGate`.
Le code adapté ne doit pas dépendre d'un `NotificationListenerService`, d'ethOS ou du runtime AndyClaw.
**Consultation uniquement :**
```javascript
skills/builtin/LocationSkill.kt
skills/builtin/ScreenTimeSkill.kt
safety/ToolAttenuation.kt
```
Ne pas reprendre `ScreenTimeSkill` : son exécution est liée aux privilèges système d'AndyClaw. Ne pas importer shell, portefeuille, agent autonome ou lecture générale de notifications.
### Références historiques
- Olauncher : réutiliser les adaptations déjà présentes dans `LauncherAppModel.kt`, sans importer ses fragments Views.
- Lawnchair : référence technique pour le comportement HOME, aucun fork.
- Reef : garder les adaptations Focus/routines existantes.
- uhabits : conserver les calculs et exports déjà intégrés.
Ne pas cloner Kai, BotDrop ou KISS dans cette itération. Leur examen a déjà montré qu'aucun composant indispensable ne justifie leur intégration immédiate.
Pour le classement des apps, ATLAS conserve `LauncherAppRules`. Il sera amélioré localement sans importer `QuerySearcher` et ses dépendances Java/DB de KISS.
Chaque reprise de code doit être tracée dans `docs/v6-2/REEMPLOI_OSS.md`, avec chemin d'origine, SHA, modifications et licence. Mettre à jour les notices.
## 2. Architecture figée
Les responsabilités sont les suivantes :
```javascript
Android local
  FocusRepository
  Habits
  GoalRepository
  UsageObserver
  LocationRepository
         |
         v
 AgentContextAssembler
         |
         v
 AgentApiClient
         |
         v
 Backend FastAPI existant
         |
         v
 OpenClaw
         |
         v
 ProposalV2
         |
         v
 MandateVerifier
         |
         v
 ActionDispatcher
         |
         v
 Effet Android + journal
```
`AtlasController` reste le point d'intégration existant. Aucune nouvelle logique volumineuse n'y est écrite directement.
Créer les nouveaux services dans leurs modules respectifs.
**Ne pas créer une architecture multi-modules Gradle, un nouveau framework de plugins, une seconde Gateway ou une nouvelle API principale.**
Conserver les endpoints backend :
- `POST /v2/agent/chat`
- `POST /v2/agent/plan`
- `GET /v2/agent/inbox`
- `POST /v2/agent/proposals/{id}/ack`
- Les endpoints `/v2/agent/memory` existants.
Ne pas inventer un nouvel endpoint si l'endpoint présent suffit.
## 3. P0 — Context Bridge Android → OpenClaw
### Défaut actuel
`AgentApiClient.chat()` transmet un objet de catégories vide.
Les catégories Android et les catégories strictes du backend divergent.
### Décision de contrat
Conserver les cinq catégories Android actuelles :
```javascript
focus
habit
content
coach_interaction
app_usage_summary
```
Ajouter exactement :
```javascript
goals
location_summary
```
Les noms existants côté Android ne doivent pas être renommés dans le stockage local.
Adapter `ContextCategory` dans `backend/atlas_backend/models_v2.py` pour accepter ces sept catégories ainsi que les catégories historiques backend :
```javascript
goals
projects
availability
preferences
package_names
urls
habit_titles
```
Les catégories historiques restent admises pour compatibilité. Les nouvelles requêtes Android utilisent les catégories ATLAS définies ci-dessus.
Adapter les limites de longueur des collections de catégories pour refléter cet ensemble, en conservant les validations strictes.
Ne pas casser `ProposalV2`, les mandats existants ou les autres endpoints V2.
### Composant à créer
```javascript
sync/agent/context/AgentContextAssembler.kt
sync/agent/context/AgentContextSnapshot.kt
```
`AgentContextAssembler` reçoit une vue en lecture seule des domaines Android. Il ne dépend pas d'une Activity.
Il construit le contexte au moment de la requête, à partir du mandat courant.
Le payload conserve la forme :
```javascript
{
  "consented_categories": ["focus", "goals"],
  "categories": {
    "focus": ["2026-10-19T18:00:00Z | completed | 25 min"],
    "goals": ["Projet personnel | cible 120 min | validé 50 min"]
  }
}
```
Le format illustré doit être validé strictement ; les chaînes sont des données non fiables, jamais des instructions de l'agent.
**Bornes ATLAS imposées :**
- Maximum 10 éléments par catégorie.
- Maximum 200 caractères par élément.
- Budget total de contexte Android : 8 Kio.
- Sessions Focus : rétrospective maximale de 7 jours.
- Habitudes : résumés sur 30 jours.
- Usage applications : agrégations sur 24 heures.
- Localisation : mesure de 15 minutes maximum.
- Aucune donnée antérieure au consentement de la catégorie.
En cas de dépassement, réduire les résumés par priorité et ancienneté ; ne pas transmettre silencieusement des données supplémentaires.
### Intégration
Modifier `AgentApiClient.chat()` pour recevoir un contexte assemblé réellement rempli.
Ajouter la même capacité à un appel `plan()` qui utilise `/v2/agent/plan`.
Le backend continue d'utiliser son `SharedContext.minimized()` et ne transmet à OpenClaw que les catégories consenties.
Afficher dans le Coach un aperçu lisible des **données effectivement incluses dans le prochain envoi**.
Ne pas prétendre qu'une catégorie est « partagée » si sa liste est vide : distinguer « autorisée » et « données disponibles ».
### Tests
- Contexte totalement vide.
- Focus seul.
- Multi-catégories.
- Catégorie révoquée pendant une requête.
- Ancienne préférence de partage migrée.
- Contrat backend Pydantic compatible.
- Données non consenties absentes des payloads.
- Aucun secret ou contenu brut non autorisé.
- Réponse OpenClaw réelle fondée sur un Focus terminé.
## 4. Objectifs — modèle local imposé
Créer :
```javascript
goals/
  GoalRecord.kt
  GoalRepository.kt
  GoalProgressCalculator.kt
```
Persister les objectifs dans le système local ATLAS existant, avec migration versionnée. Ne pas ajouter Room ou une autre base de données uniquement pour cette fonctionnalité.
Modèle :
```javascript
GoalRecord
  id
  title
  description
  metric
  targetValue
  dueAt?
  status
  nextAction?
  linkedFocusSessionIds
  linkedHabitIds
  createdAt
  updatedAt
```
Valeurs de `metric` :
```javascript
focus_minutes
habit_checkins
manual
```
Valeurs de `status` :
```javascript
active
completed
paused
abandoned
```
**Règle de progression :**
- `focus_minutes` : additionner uniquement les minutes de sessions terminées et reliées explicitement à l'objectif.
- `habit_checkins` : compter les validations réelles correspondantes.
- `manual` : seule une confirmation de l'utilisateur valide l'accomplissement.
- Ne jamais compter automatiquement une ouverture d'application ou un déplacement comme un objectif atteint.
- Une proposition OpenClaw ne modifie pas directement `GoalRecord`.
Limiter initialement l'interface à trois objectifs actifs affichables dans l'espace Coach. Le HOME n'en montre que la prochaine action prioritaire.
Le backend possède déjà `ProjectMemory`. Ne pas créer une deuxième mémoire distante concurrente. Les objectifs locaux sont transmis via le Context Bridge uniquement lorsque `goals` est autorisé.
Aucune synchronisation implicite vers `/v2/agent/memory`.
## 5. Observation des applications — mécanisme imposé
Conserver :
```javascript
UsageObserver.kt
AndroidUsageStatsEventSource.kt
RuleEvaluator.kt
SafetyDenylist.kt
AndroidFrictionPresenter.kt
```
### Collecte
Utiliser deux chemins complémentaires :
**Chemin A : app visible**
Conserver le polling de 10 secondes actuellement présent dans `AtlasApp.kt`, lorsqu'il est autorisé.
**Chemin B : app non visible**
Ajouter un `PeriodicWorkRequest` WorkManager toutes les 30 minutes, avec nom unique `atlas_usage_observation`.
Le worker appelle le même moteur `UsageObserver` et le même stockage, sans dupliquer les règles de filtrage.
Les accès au curseur et au stockage doivent être sérialisés, y compris en cas de concurrence avec le polling visible.
L'observation est désactivée sans permission OS ou sans consentement ATLAS. Ne pas relire des événements antérieurs à la date de consentement.
Ne pas augmenter par défaut la conservation actuelle des événements bruts.
### Sémantique
L'activité d'une application ne prouve pas que l'utilisateur travaille, apprend ou se distrait. Présenter les usages observés comme des indices.
Le traitement périodique peut être retardé. Ne jamais promettre une interception en temps réel.
L'agent reçoit des résumés agrégés, jamais le journal complet des événements d'ouverture.
Ne pas activer d'AccessibilityService ou de service de capture d'écran.
## 6. Coach proactif — comportement figé
Créer :
```javascript
coach/proactive/
  CoachTrigger.kt
  ProactiveTriggerGate.kt
  ProactiveCoachScheduler.kt
```
`ProactiveTriggerGate` adapte la déduplication et le cooldown du composant AndyClaw précédemment identifié.
Sources d'événements autorisées :
```javascript
focus_completed
goal_review_due
habit_reminder_due
distraction_threshold_reached
mobility_session_completed
daily_plan_due
```
Les événements sont enregistrés localement et regroupés avant un appel réseau.
### Règles imposées
- Pas d'appel OpenClaw systématique pour chaque événement.
- Au maximum 6 demandes proactives OpenClaw par jour.
- Au moins 60 minutes entre deux demandes portant sur le même motif.
- Au maximum 3 notifications proactives par jour en mode normal.
- Plage silencieuse par défaut : 22 h à 8 h, sauf action manuelle.
- Aucun déclencheur lié à une donnée dont le consentement a été révoqué.
- Un événement sans donnée fiable n'entraîne pas de conclusion comportementale.
Pour les plans proactifs, appeler `/v2/agent/plan`, déjà existant.
Ne pas injecter artificiellement les requêtes programmatiques dans la conversation de l'utilisateur.
Le backend continue à filtrer les propositions. Android continue à les revalider avec `MandateVerifier`.
### Mode Engagement renforcé
Créer un réglage explicite `normal / reinforced`.
En mode renforcé :
- Jusqu'à 5 notifications de coaching par jour.
- Toujours au moins 60 minutes entre deux relances du même motif.
- Fenêtres de Focus et quotas définis par le mandat local.
- Messages plus directs et orientés vers une action concrète.
- Possibilité de reporter, modifier, suspendre ou révoquer.
- Aucune suppression des accès vitaux ou sortie HOME.
- Aucune boucle d'alerte ou notification répétée sans nouveau motif.
Le mode renforcé n'ajoute aucune permission Android et n'autorise aucune nouvelle action du dispatcher par lui-même.
Ne pas modifier la sémantique du mode `preauthorized` existant.
## 7. Localisation — implémentation déterminée
Créer :
```javascript
location/
  AtlasLocationProvider.kt
  LocationCaptureManager.kt
  LocationCoarsener.kt
  LocationDisclosure.kt
  LocationMode.kt
  LocationConsentStore.kt
  MobilitySession.kt
  MobilitySessionService.kt
```
La partie acquisition/divulgation dérive des quatre fichiers officiels OpenClaw sélectionnés.
### A. Position ponctuelle
Permissions :
```javascript
ACCESS_COARSE_LOCATION
ACCESS_FINE_LOCATION
```
La demande initiale utilise la localisation approximative. La précision fine est demandée seulement pour une fonctionnalité qui l'exige ou à la demande de l'utilisateur.
Distinguer l'autorisation OS du consentement d'envoi vers OpenClaw.
Une localisation précise ne peut être transmise à distance que si l'utilisateur confirme explicitement cette transmission. Le contexte automatique `location_summary` reste approximatif.
Paramètres par défaut :
```javascript
timeoutMs = 10000
maxAgeMs = 15000
desiredAccuracy = coarse
```
Utiliser les codes d'erreur fonctionnels OpenClaw :
```javascript
LOCATION_DISABLED
LOCATION_PERMISSION_REQUIRED
LOCATION_BACKGROUND_UNAVAILABLE
LOCATION_TIMEOUT
LOCATION_UNAVAILABLE
```
Adapter `LocationCaptureManager` pour fonctionner avec `minSdk=26`, notamment sur API 26–29 où `LocationManager.getCurrentLocation` n'est pas disponible.
Garder `LocationCoarsener` comme frontière d'anonymisation avant partage.
### B. Suivi temporaire de déplacement
**Ne pas utiliser `ACCESS_BACKGROUND_LOCATION` en V6.2.**
Implémenter un service de premier plan de type `location`, lancé uniquement depuis une action explicite pendant que l'application est visible.
Ajouter les permissions de foreground service nécessaires pour le niveau de SDK actuel.
Règles :
- Activation manuelle « Suivre mon déplacement ».
- Notification persistante avec bouton Arrêter.
- Durée maximale : 60 minutes.
- Arrêt sur révocation, fin de session ou action manuelle.
- Mesures espacées d'au moins 5 minutes en usage standard.
- Pas de journal brut de points GPS.
- Pas de suivi silencieux au redémarrage.
- Ne pas déduire « à pied », « en voiture » ou « au travail » sans une source réellement fiable.
- N'exposer au Coach que des changements de zone approximatifs et horodatés.
Le fonctionnement sous Android 14–17 doit être testé, notamment la création du service depuis une activité visible, les permissions, la notification et l'arrêt.
La session peut utiliser la localisation précise si elle est expressément autorisée, mais n'enregistre que des résumés minimisés.
Les géofences permanentes, le mode `Always` et la reconnaissance d'activité restent hors V6.2. Ne pas les implémenter par anticipation.
## 8. UI — spécification visuelle verrouillée
L'interface doit être une véritable expérience de launcher, pas un dashboard.
### HOME
Implémentation : Compose existant, sans nouveau framework UI.
Référence esthétique principale : Olauncher, mais design ATLAS original.
**Supprimer les grosses cartes Material du HOME.**
Disposition portrait cible, pour un appareil d'environ 360 × 800 dp :
```javascript
ATLAS                              •

20:30
Lundi 19 octobre


AUJOURD'HUI

Avancer sur mon projet
25 minutes de concentration

Commencer     Discuter     Reporter


FOCUS
25:00 · Prêt à commencer


APPLICATIONS

Téléphone
Messages
Navigateur
Agenda
Notes


Applications      Coach      Pause      Plus
```
L'exemple n'impose pas ces libellés de démonstration ; le contenu vient des états réels.
### Mesures et règles
- Conserver la palette sombre/AMOLED existante.
- AMOLED : fond noir véritable.
- Aucun gradient, illustration décorative ou animation permanente.
- Accent couleur sur une seule action principale.
- Typographie hiérarchisée plutôt que surfaces colorées.
- Horloge autour de 44–48 sp sur la vue portrait normale.
- Titre de directive autour de 20–24 sp.
- 4 à 6 favoris configurables, selon la hauteur disponible.
- Lignes d'applications d'au moins 48 dp.
- Cibles tactiles d'au moins 48 × 48 dp.
- Espacements cohérents fondés sur une grille de 4/8 dp.
- La directive occupe au plus environ un tiers de la première vue standard, sans imposer de troncature inaccessible.
- Aucun texte technique backend en permanence.
- Une session Focus active devient une ligne de statut prioritaire.
- Une proposition OpenClaw n'efface jamais les favoris ou la sortie de secours.
Sur écran étroit et à font scale élevé, le contenu défile mais le pied ne masque pas les contrôles.
### Pied HOME
Conserver en accès immédiat :
- Applications.
- Coach.
- Pause.
- Menu Plus.
Le retour au launcher précédent et les réglages HOME restent accessibles depuis Plus, en un chemin court et explicite.
Le bouton Pause reste visible et utilisable sans réseau, avec zone tactile correcte. Il ne doit plus être le plus gros bouton de l'écran lorsqu'aucune urgence n'est active.
### Recherche
Conserver `LauncherAppRules` comme moteur de base.
Ajouter un classement déterministe local :
1. Correspondance exacte.
2. Début de nom.
3. Correspondance partielle.
4. Priorité des favoris.
5. Historique récent local, si disponible.
6. Ordre alphabétique pour départager.
Les règles doivent être testées sans nécessiter de nouvelle base de données.
Ne pas importer KISS.
### Coach
- Conversation comme contenu principal.
- Propositions en attente visibles avant les réglages.
- Mandat et confidentialité dans des destinations séparées.
- Réglages avancés regroupés, non exposés en permanence.
- Affichage de la provenance en termes lisibles : « Local », « Coach connecté », « Dernière directive conservée ».
- Pas d'identifiants `sha256` dans le parcours principal.
- Français par défaut dans le prompt système OpenClaw et les contenus démontrés.
- Afficher ce qui a réellement été partagé avec l'agent.
### Tests UI obligatoires
Captures natives avant/après :
- HOME sombre et AMOLED.
- HOME avec Focus actif.
- HOME sans directive.
- HOME avec 5 favoris.
- Tiroir et recherche.
- Coach réel.
- Mandat et partage de données.
- Focus.
- Paysage.
- Texte 1,5× et 2×.
- Largeur 320/360 dp.
Contraste normal ≥4,5:1, sous réserve des exceptions WCAG applicables. Vérifier TalkBack, ordre de focus, gestes, clavier et zones tactiles.
Une CI verte sans revue visuelle n'est pas suffisante.
## 9. Intégration Android
Un seul agent intégrateur modifie les fichiers globaux :
```javascript
app/AtlasController.kt
app/AtlasApp.kt
app/MainActivity.kt
app/LauncherActivity.kt
AndroidManifest.xml
app/build.gradle.kts
sync/agent/coach/AgentApiClient.kt
```
Aucun autre agent ne doit les éditer simultanément.
L'intégration doit :
- Préserver les sessions Focus et leur reprise.
- Préserver la restauration locale après force-stop.
- Conserver les anciennes données et contrats.
- Garantir la révocation effective.
- Éviter les doubles exécutions et doubles notifications.
- Préserver la boîte de réception agent.
- Ne pas exposer le jeton Gateway sur Android.
- Garder le backend optionnel.
Ne pas remplacer les repositories ATLAS par ceux des projets tiers.
## 10. Ordre d'exécution obligatoire
| Session | Travail | Fichiers exclusifs |
| --- | --- | --- |
| S0 | Audit baseline, clones ciblés, inventaire des reprises | Docs et références uniquement |
| S1 | Backend + contrats de contexte | `backend/**`, `contracts/**` |
| S2 | GoalRepository + AgentContextAssembler | `goals/**`, `sync/agent/context/**` |
| S3 | Localisation OpenClaw adaptée | `location/**` et tests associés |
| S4 | UI launcher et Coach | Écrans Compose et fichiers UI explicitement attribués |
| S5 | ProactiveTriggerGate + scheduler | `coach/proactive/**`, nouveaux workers |
| S6 | Intégration globale | Fichiers globaux réservés |
| S7 | Revue indépendante, recette et corrections | Lecture seule puis corrections par propriétaire |
Maximum trois exécutants simultanés. Geler les interfaces partagées avant leur implémentation concurrente.
Après chaque lot :
1. Examiner les fichiers changés.
2. Vérifier que l'agent a réutilisé les sources imposées.
3. Vérifier qu'il n'a pas importé de système tiers entier.
4. Relancer les tests pertinents.
5. Corriger les régressions avant intégration.
6. Produire un rapport court avec preuves.
## 11. Recette de livraison
Baseline obligatoire :
```javascript
cd android
./gradlew testDebugUnitTest lintDebug assembleDebug
```
Backend :
```javascript
python -m pytest backend/tests -q
```
Utiliser le Python et l'environnement virtuel réellement disponibles.
Exécuter les tests instrumentés dans un AVD dédié et jetable. Les classes précédemment exclues pour risque de destruction de données doivent y être exécutées.
### Scénario de bout en bout indispensable
1. Configurer un objectif local de 120 minutes.
2. Activer le partage des objectifs et de Focus.
3. Démarrer et terminer 25 minutes de Focus.
4. Vérifier que la progression locale est de 25/120.
5. Appeler OpenClaw réel avec le contexte consenti.
6. Vérifier que le Coach connaît les 25 minutes réellement validées.
7. Obtenir une prochaine action cohérente.
8. Accepter ou reporter la proposition.
9. Confirmer l'effet Android et le journal.
10. Désactiver le partage des objectifs.
11. Confirmer que la prochaine requête ne contient plus l'objectif.
12. Couper le backend et vérifier HOME, favoris, Focus et pause d'urgence.
### Localisation
1. Autoriser une position approximative.
2. Injecter une position de test sur AVD.
3. Vérifier une acquisition valide.
4. Vérifier l'arrondissement protecteur avant partage.
5. Refuser la permission et vérifier l'absence de crash.
6. Révoquer pendant une acquisition.
7. Vérifier le timeout et les fournisseurs indisponibles.
8. Tester une session temporaire de mobilité, son notification et son arrêt.
9. Vérifier qu'aucun trajet détaillé n'est conservé ou transmis sans consentement.
### Fiabilité
Vérifier :
- Build, lint, JVM, Python.
- Tests instrumentés.
- Retour au launcher précédent.
- Reprise Focus après force-stop.
- Idempotence des propositions.
- Restrictions du mandat.
- Absence de fuite de secrets.
- État hors ligne.
- Performances et activité réseau au repos.
- Notifications et batterie, sans inventer de mesures.
- Compatibilité Android 17 sur émulateur.
- Pixel physique uniquement si disponible.
## 12. Définition de terminé
La mission est terminée seulement si :
- Le HOME est significativement plus minimaliste.
- Les favoris et applications restent facilement accessibles.
- Le Context Bridge transmet des données réelles et consenties.
- OpenClaw utilise effectivement ces données dans une démonstration.
- Un objectif peut être défini, suivi et mis à jour localement.
- Les déclencheurs proactifs respectent leurs quotas.
- La localisation sur demande fonctionne.
- Le mode de déplacement temporaire fonctionne ou est explicitement déclaré bloqué avec cause vérifiée.
- Les tests essentiels sont exécutés.
- Les fonctionnalités V6.1 ne régressent pas.
- Les sources OSS reprises sont documentées.
- Aucun élément sensible n'est transmis hors consentement.
Créer les preuves dans `docs/v6-2/` : rapports, sources OSS, contrats, tests, captures, limites et instructions Pixel.
Ne pas qualifier de fonctionnelle une intégration testée uniquement avec des fixtures.
### Directive finale
**Implémente cette architecture. Ne propose pas une architecture alternative.**
Tu peux prendre des décisions locales sur les noms de fonctions internes, les imports, les corrections de compilation et les adaptations nécessaires aux versions Android réellement présentes.
Tu ne peux pas changer sans justification documentée et validation de l'orchestrateur :
- Les responsabilités des modules.
- Les catégories de données partagées.
- Les permissions et leurs conditions.
- Les contraintes de sécurité.
- Le rôle de `MandateVerifier`.
- Les choix de réemploi open source.
- Les règles de rétention et de proactivité.
- La direction visuelle du HOME.
- Le modèle de progression des objectifs.
En présence d'un obstacle démontré, fournir le fichier concerné, la cause technique et la correction minimale. Ne pas élargir silencieusement le périmètre.
**L'orchestrateur doit livrer une V6.2 intégrée et testée, pas une collection de modules incomplets.**