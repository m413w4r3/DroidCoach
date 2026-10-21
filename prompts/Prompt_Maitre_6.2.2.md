# ATLAS V6.2.2 — Qualification réelle, fiabilité Android et liaison Objectif → Focus

## PROMPT MAÎTRE POUR L'ORCHESTRATEUR NIMBALYST

### 0. Mission

Tu es l'orchestrateur principal du chantier **ATLAS V6.2.2**.

Dépôt : `https://github.com/m413w4r3/DroidCoach`

Base de référence vérifiée :

```text
main
bf647cc18a142dd0f5cd5e68b21c36d511014388
```

Tu dois revérifier le HEAD au démarrage. Si `main` a évolué, identifier les changements, adapter les points de raccordement et consigner la différence avant de modifier le code.

**La mission est d'obtenir une version fiable, utilisable quotidiennement et correctement qualifiée sur Android, avant le chantier fonctionnel V6.3.**

Ce n'est pas une mission de refonte générale. Ce n'est pas une mission de recherche de nouvelles idées produit.

Les décisions d'architecture sont fixées ici. Ton travail est de les réaliser, de tester les scénarios réels, de corriger les défauts prouvés et de fournir des preuves vérifiables.

### Orchestration imposée

Toutes les sous-sessions d'implémentation utilisent :

```yaml
model: openai-codex:gpt-6-luna
effortLevel: xhigh
notifyOnComplete: true
```

- Maximum trois sous-sessions simultanées.
- Un propriétaire exclusif par fichier modifié.
- Aucun fichier global édité par deux agents simultanément.
- Chaque agent livre code, tests, constats et limites.
- Une revue indépendante finale est obligatoire.
- L'orchestrateur doit reprendre et corriger les livrables incomplets.
- Ne pas pousser ni fusionner sans autorisation explicite.
- Ne pas modifier ou effacer un AVD personnel.
- Ne jamais effacer les données d'un téléphone physique.

Créer une branche de travail :

```text
feat/atlas-v6-2-2
```

Ne pas travailler directement sur `main`.

---

## 1. Sources et état initial

Lire impérativement :

```text
AGENTS.md
README.md
docs/01-produit.md
docs/v6/SECURITE_ET_MANDAT.md
docs/v6-2/STATUT_V621.md
docs/v6-2/RECETTE_V621.md
docs/v6-2/REVUE_V621.md
docs/v6-2/ISSUE_1_LOCALISATION.md
docs/v6-2/RAPPORT_ANDROID17.md
docs/v6-2/GUIDE_PIXEL.md
docs/v6-2/INDEX_PREUVES.md
```

Inspecter les vrais fichiers de production et de tests.

Ne pas considérer les rapports comme une preuve suffisante lorsqu'ils contredisent le code actuel.

### Baseline attendue

Au commit de référence :

```text
Android JVM         290 PASS / 0 FAIL / 0 IGNORE
Backend Python      66 PASS / 1 SKIP
Android API 34      63 PASS / 1 FAIL, sur 64 cas
GitHub Actions      SUCCESS
```

Échec Android connu :

```text
V62MobilityServiceInstrumentedTest
.visibleStartShowsStopNotificationAndServiceStopsWithoutRawTrail
```

L'AVD a retourné une position sans rapport avec la position injectée, à une distance d'environ 8 900 km.

Android 17, Pixel physique, TalkBack complet et mesures de batterie n'ont pas été validés en V6.2.1.

### Interdictions

Ne pas ajouter dans V6.2.2 :

- AccessibilityService.
- Blocage effectif de packages tiers.
- Kiosque ou Device Owner.
- Root ou permissions système privilégiées.
- Géolocalisation permanente en arrière-plan.
- Nouvelle Gateway sur Android.
- Nouveau modèle de mémoire distante.
- Nouveau moteur de coaching.
- Refonte complète de `AtlasController`.
- Nouvelle bibliothèque de persistance.
- Redesign du launcher.

Ne pas cloner de nouvelles références open source : les sources nécessaires sont déjà identifiées ou intégrées.

---

# LOT A — QUALIFICATION ANDROID ET ISSUE #1

## A1. Comprendre l'échec de localisation, pas le masquer

Fichiers à inspecter :

```text
android/app/src/main/java/dev/atlas/coach/location/
  LocationCaptureManager.kt
  LocationDisclosure.kt
  LocationConsentStore.kt
  MobilitySessionService.kt

android/app/src/androidTest/java/dev/atlas/coach/location/
  V62LocationCaptureInstrumentedTest.kt
  V62MobilityServiceInstrumentedTest.kt

scripts/v62-location-visible-recipe.sh
scripts/v62-complete-instrumentation.sh
```

Référence initiale : `docs/v6-2/ISSUE_1_LOCALISATION.md`.

### Décision technique

**Conserver le mécanisme de localisation Android existant.**

Ne pas remplacer `LocationManager` par un autre fournisseur uniquement parce qu'un test d'émulateur est instable.

L'erreur de distance doit être isolée entre :

1. Injection effective du fix dans l'émulateur.
2. Fournisseur Android réellement utilisé.
3. Position mise en cache.
4. Fraîcheur de l'horodatage.
5. Précision grossière imposée par Android.
6. Capture par le service de mobilité.
7. Arrondissement protecteur par ATLAS.
8. Comparaison et synchronisation du test.

### Protocole de diagnostic imposé

Créer un AVD dédié **jetable** Android 14, distinct de tous les AVD personnels.

Avant de lancer la suite :

- Enregistrer version émulateur, API, image système et état des fournisseurs.
- Révoquer `ACCESS_FINE_LOCATION`.
- Accorder uniquement `ACCESS_COARSE_LOCATION`.
- Refuser `ACCESS_BACKGROUND_LOCATION`.
- Activer le fournisseur de localisation.
- Injecter une position connue et horodatée.

La commande utilise impérativement :

```bash
adb -s "$SERIAL" emu geo fix 2.123456 48.123456
```

L'ordre est `longitude latitude`.

Synchroniser l'injection avec le début de la requête de localisation et consigner les horodatages des deux côtés.

Ne pas accepter un fix ancien, même si sa distance correspond au point attendu.

Ne pas comparer directement un fix GPS brut à un point ATLAS déjà grossièrement arrondi avec une tolérance irréaliste.

### Correction autorisée

Adapter prioritairement le harnais de test et sa synchronisation.

Modifier `LocationCaptureManager` uniquement si les traces démontrent une vraie erreur de sélection de fournisseur, d'âge maximal, de timeout ou de permission.

La correction doit rester compatible avec `minSdk=26`.

**Interdiction absolue :** supprimer l'assertion de localisation, ignorer le test, augmenter arbitrairement la tolérance à plusieurs milliers de kilomètres ou forcer une coordonnée de démonstration dans le code de production.

### Critère de sortie A1

Le scénario doit prouver séparément :

- Service lancé depuis une Activity visible.
- Permission approximative suffisante.
- Notification persistante visible.
- Première mesure cohérente et récente, quand le fournisseur en donne une.
- Seuls des résumés approximatifs sont conservés.
- Aucune trajectoire GPS brute enregistrée.
- Action « Arrêter » fonctionnelle.
- Révocation du partage entraînant l'arrêt prévu.
- Pas de relancement silencieux après interruption.

Si le fournisseur Android de l'AVD reste défaillant, produire deux preuves distinctes :

**TEST_LOGIQUE_PASS** : acquisition, divulgation, TTL et cycle du service vérifiés avec un fournisseur contrôlé.

**TEST_SYSTEM_BLOCKED** : reproduction instrumentée documentée de l'anomalie du fournisseur Android.

Ne pas écrire `PASS` pour le scénario système dans ce cas.

---

## A2. Android 17 : nouvelle recette propre

Le précédent AVD Android 17 présentait un défaut de `screencap` observé avant installation d'ATLAS.

**Ce problème ne constitue pas une preuve que l'application est incompatible avec Android 17.**

Créer un nouvel AVD jetable si le SDK et les images système nécessaires sont disponibles.

Nom conseillé :

```text
atlas_v622_disposable_api37
```

Conserver le `targetSdk` actuel de l'application. Ne pas effectuer de migration vers `targetSdk 37` pendant ce chantier.

Vérifier :

```text
ro.build.version.sdk
ro.build.version.sdk_full
ro.boot.qemu.avd_name
```

### Séparer les capacités de l'environnement

Ne pas utiliser `screencap` comme condition préalable à toutes les validations.

Évaluer indépendamment :

- Installation APK.
- Démarrage de l'application.
- Exécution de l'instrumentation.
- Permissions runtime.
- Service de localisation.
- Notification.
- HOME Android.
- Captures d'écran.
- Commandes UI.

Un `screencap` en échec ne doit pas empêcher d'exécuter des tests instrumentés qui n'en dépendent pas.

### Matrice obligatoire Android 17

Tester :

1. Installation propre.
2. Lancement sans rôle HOME.
3. Attribution volontaire du rôle HOME.
4. Retour au launcher précédent.
5. Localisation approximative accordée pendant l'utilisation.
6. Refus de localisation.
7. Révocation de localisation.
8. Fournisseur désactivé.
9. Timeout de capture.
10. Service de mobilité démarré explicitement.
11. Notification Arrêter.
12. Force-stop : absence de relancement silencieux.
13. Rotation portrait/paysage.
14. Police Android 2×.
15. Focus en cours, pause, retour application.
16. Révocation du Coach.
17. HOME et pause d'urgence sans backend.

Ne pas utiliser de coordonnées réelles dans les fixtures de test.

### Critère de sortie A2

Un rapport par scénario :

```text
PASS / FAIL / BLOCKED / NOT_RUN
```

Chaque état possède une preuve : journal, résultat instrumenté, capture si disponible, ou diagnostic d'environnement.

Un test non exécuté ne peut pas être qualifié de réussi.

---

## A3. Fermeture de l'issue GitHub #1

Issue :

```text
https://github.com/m413w4r3/DroidCoach/issues/1
```

Elle est actuellement ouverte, conformément à son critère de sortie.

**Ne la fermer que si :**

- Tous ses critères fonctionnels sont satisfaits.
- La matrice Android 17 demandée a été réellement exécutée sur un environnement utilisable.
- Les scénarios critiques de refus, révocation et absence de fuite de position sont réussis.
- Le scénario de mobilité n'est plus en échec non expliqué.
- Les preuves et limitations sont référencées dans `docs/v6-2/`.
- Une vérification indépendante valide la fermeture.

Si les permissions GitHub sont disponibles et toutes ces conditions satisfaites, fermer l'issue avec un commentaire de preuve et `state_reason=completed`.

Sinon la laisser ouverte, ajouter un commentaire factuel si possible, et identifier précisément ce qui manque.

Ne pas fermer l'issue uniquement parce que le code existe.

---

# LOT B — FIABILITÉ QUOTIDIENNE

## B1. Test de reprise sur interruption

Ne pas modifier la persistance V6.2.1 sans défaut reproductible.

Les garanties existantes reposent sur :

```text
LocalStateStore
PersistResult
RevocationBarrier
ActionDispatcher
ProactiveCoachScheduler
```

Conserver ces frontières.

### Scénarios obligatoires

- Démarrer un Focus, fermer l'Activity, puis la rouvrir.
- Reprendre une session en pause.
- Terminer une session.
- Annuler une session.
- Recréer le contrôleur.
- Recréer le processus.
- Simuler une écriture locale échouée.
- Vérifier que le journal d'effets reste cohérent.
- Vérifier qu'une action agent exécutée ne l'est pas deux fois.
- Vérifier que le mandat révoqué ne réactive pas le transport.
- Vérifier qu'une notification tardive n'accepte pas une proposition périmée.
- Vérifier le fonctionnement hors connexion.

### Décision de réparation

Corriger exclusivement le chemin qui échoue.

Ne pas migrer l'ensemble du stockage.

Ne pas introduire une nouvelle couche `DurableStateCoordinator` sans reproduction démontrant que la solution existante ne suffit plus.

---

## B2. Dernière frontière de confidentialité des notifications proactives

Inspecter :

```text
coach/proactive/android/ProactiveCoachWorker.kt
app/ProactiveCoachCoordinator.kt
app/ProactiveCoachNotificationPublisher.kt
app/AtlasController.kt
```

La V6.2.1 revérifie le consentement avant publication, mais le code du worker documente encore une fenêtre de concurrence entre le dernier contrôle et l'appel Android de notification.

### Correction imposée

- Introduire un identifiant de génération de capacité proactive, géré au niveau contrôleur.
- Vérifier cette génération et l'autorisation au dernier point possible avant `NotificationManager.notify`.
- Sur révocation, invalider immédiatement les générations en cours, annuler le worker et retirer les notifications proactives existantes dont le contenu n'est plus autorisé.
- Ne jamais annuler une notification système étrangère à ATLAS.
- Préserver les quotas, le silence nocturne, la déduplication et l'idempotence.
- Ne pas ajouter d'appel réseau dans le publisher.

Documenter la limite de concurrence incompressible entre l'ultime vérification et la publication OS : ne pas prétendre disposer d'une transaction atomique avec Android.

### Tests imposés

- Révocation avant la réponse réseau.
- Révocation pendant le traitement de la réponse.
- Révocation entre réservation et publication.
- Notification déjà affichée au moment de la révocation.
- Absence de publication après invalidation de génération dans les scénarios contrôlés.
- Aucun doublon après redémarrage.

---

## B3. Usage réel sur appareil secondaire

Si un téléphone Android physique explicitement identifié est disponible, exécuter une recette **non destructive**.

Ne jamais :

```text
pm clear
wipe-data
factory reset
root
adb uninstall
```

sur un appareil personnel sans autorisation explicite.

Ne pas changer le launcher par défaut sans une action volontaire de son utilisateur.

Consigner :

- Modèle.
- Version Android.
- Numéro de build.
- Version APK.
- Durée réelle d'essai.
- Interruptions observées.
- Résultat des reprises.
- Notifications.
- Comportement sans connexion.
- Utilisation du rôle HOME.

Si aucun téléphone physique n'est disponible, produire un guide d'essai reproductible et classer la validation `NOT_RUN`.

Ne jamais fabriquer un rapport Pixel à partir d'un émulateur.

---

# LOT C — LIAISON AUTOMATIQUE OBJECTIF → FOCUS

Ce lot constitue **le seul ajout fonctionnel V6.2.2**.

Il résout un défaut produit précis : aujourd'hui, les sessions ne peuvent être liées à un objectif qu'après leur terminaison, ce qui impose une opération manuelle et empêche une progression naturellement intégrée.

## C1. Décision de modèle

**Ne pas créer une nouvelle table de progression.**

Utiliser les composants déjà présents :

```text
goals/GoalRecord.kt
goals/GoalRepository.kt
goals/GoalProgressCalculator.kt

app/GoalPanelCoordinator.kt
app/AtlasController.kt

focus/FocusEngine.kt
focus/FocusModels.kt
```

`GoalRecord.linkedFocusSessionIds` reste la source de vérité du lien.

`GoalProgressCalculator` reste le seul calculateur de progression.

Ne pas ajouter de champ `goalId` à `FocusSession` ni de clé `goalId` au protocole OpenClaw V2.

Une session Focus peut être associée automatiquement à **un seul objectif principal** lors du démarrage.

Le lien doit être un choix utilisateur explicite. ATLAS ne peut pas déduire l'objectif uniquement du titre de tâche.

Aucune attribution rétroactive automatique des anciennes sessions.

---

## C2. Nouvelle transaction de démarrage

Ajouter au contrôleur une entrée :

```kotlin
fun startFocus(
    plan: FocusPlan,
    goalId: String? = null,
)
```

La surcharge existante `startFocus(plan)` reste compatible.

### Préconditions

Si `goalId` est non nul :

- L'objectif existe.
- Il est actif.
- Sa métrique est `FOCUS_MINUTES`.
- Aucun autre objectif actif ne revendique déjà ce même ID de session dans cette opération.
- Le moteur Focus n'est ni `RUNNING` ni `PAUSED` sur une autre session.
- Le démarrage local est autorisé.

Si `goalId == null`, la session reste libre et fonctionne comme avant.

### Atomicité imposée

**Ne pas faire deux commits indépendants, un pour Focus puis un pour Goals.**

Les deux informations sont déjà dans le même document racine ATLAS.

Implémenter une préparation sans effet :

```text
FocusEngine.prepareStart(...)
GoalRepository.prepareFocusLink(...)
```

Ces opérations produisent respectivement :

```text
FocusSession candidate
Goals subdocument candidate
```

Elles ne modifient ni l'état publié ni le stockage.

Construire ensuite un seul document candidat :

```text
stateMap()
  + nouvelle session Focus
  + sous-document Goals modifié
  + historique des sessions terminales nécessaires
  + état de navigation/message correspondant
```

Utiliser le chemin strict :

```text
persistOrThrow(...)
```

**Après confirmation du commit uniquement :**

1. Publier la session préparée dans `FocusEngine`.
2. Publier le sous-document Goals déjà committé dans `GoalRepository`, sans seconde écriture.
3. Planifier les alarmes Focus.
4. Émettre l'événement local Focus démarré.
5. Mettre à jour l'UI et la navigation.

Adapter les signatures exactes des méthodes de préparation au style du dépôt, mais conserver strictement leur caractère pur et cet ordre des effets.

Ne pas conserver un état partiellement démarré si le commit échoue.

Ne pas déclencher d'alarme ou d'événement sync avant le commit.

### Important : anciens chemins

Les fonctions de démarrage existantes comprennent :

```text
startFocus(FocusPreset)
startFocus(FocusPlan)
startFocus(ReefPomodoroConfig)
startFocus(ReefPomodoroConfig, taskTitle)
startDemoFocus()
```

Elles doivent partager la transaction de démarrage, sans dupliquer les effets.

Les propositions OpenClaw et leur mécanisme d'acceptation restent soumis à `MandateVerifier` et à leur transaction agent V6.2.1.

Ne pas fusionner la transaction d'action agent avec une mutation d'objectif implicite : si l'utilisateur ne choisit aucun objectif dans le parcours d'acceptation, la session reste non liée.

---

## C3. Interface imposée

Dans l'écran Focus existant, avant de démarrer une session :

```text
OBJECTIF ASSOCIÉ

[ Sans objectif                         v ]
  Avancer sur mon projet
  Étudier l'anglais
  Sans objectif
```

Afficher uniquement les objectifs actifs de type `FOCUS_MINUTES`.

Par défaut :

- Si un seul objectif éligible existe : le proposer, mais afficher clairement cette sélection avant démarrage.
- Si plusieurs objectifs existent : afficher « Sans objectif » jusqu'au choix explicite.
- Si aucun objectif éligible n'existe : ne pas afficher de sélection inutile.

Le choix est local et ne doit pas nécessiter OpenClaw.

Pendant une session liée :

```text
Objectif : Avancer sur mon projet
25 minutes prévues
```

Après terminaison :

```text
Session terminée
25 minutes validées
Objectif : 25 / 120 minutes
```

Les chiffres viennent de `GoalProgressCalculator`, jamais d'un calcul d'interface parallèle.

Si une session est simplement démarrée ou mise en pause, sa durée n'est pas encore comptabilisée.

Si elle est annulée, elle ne crédite aucune minute.

La liaison manuelle historique à une session déjà terminée reste accessible, sans permettre le double comptage.

Conserver le style sobre du HOME et du Coach. Aucun nouveau dashboard.

---

## C4. Tests métier obligatoires

Avec horloge contrôlée :

1. Objectif actif de 120 min.
2. Préparation d'une session de 25 min liée.
3. Avant le démarrage : progression `0 / 120`.
4. Après démarrage : progression toujours `0 / 120`.
5. En pause : progression toujours `0 / 120`.
6. Après une session réellement terminée selon le modèle Focus : progression `25 / 120`.
7. Après recréation du contrôleur : `25 / 120`.
8. Après recalcul répété : `25 / 120`, jamais `50 / 120`.
9. Une session annulée : contribution nulle.
10. Une session libre : aucune modification d'objectif.
11. Objectif inactif : association refusée.
12. Objectif `MANUAL` : association automatique refusée.
13. Double appui sur Démarrer : une seule session.
14. Échec du commit initial : aucune session lancée, aucun lien conservé, aucune alarme.
15. Force-stop/reprise : état local et progression cohérents.
16. Sessions historiques V6.2.1 : migration compatible, aucun lien inventé.

Ne pas interpréter le temps de pause ou les pauses Pomodoro comme du travail effectif.

### Frontière V6.3

Ne pas ajouter maintenant :

- Génération automatique d'objectifs par OpenClaw.
- Réorganisation autonome de la journée.
- Nouvelles actions de mandat.
- Politique de blocage d'applications.
- Récompenses, points ou sanctions.
- Synchronisation des objectifs vers une seconde mémoire.

La V6.3 utilisera ce nouveau lien comme fondation.

---

# LOT D — PERFORMANCE, ACCESSIBILITÉ ET AUTONOMIE

## D1. Launcher

Reprendre les tests du HOME :

- 320 dp.
- 360 dp.
- Paysage.
- Font scale 1×, 1,5×, 2×.
- 100 et 250 applications.
- Recherche.
- Favoris.
- Pied fixe.
- Pause d'urgence.
- Retour au launcher précédent.

Contrôler les recompositions inutiles, les accès bloquants au thread principal et le comportement de la recherche.

Ne changer visuellement un composant que si un défaut mesuré est démontré.

---

## D2. TalkBack

Exécuter autant que possible une recette réelle :

- Ordre de lecture de HOME.
- Favoris.
- Bouton Pause.
- Réglages et révocation.
- Choix d'objectif Focus.
- Démarrer / Pause / Reprendre / Terminer.
- Propositions Coach.
- Localisation et notification de mobilité.

Corriger les libellés et zones tactiles nécessaires.

Ne pas écrire « TalkBack validé » si seul un contrôle statique des semantics a été réalisé.

---

## D3. Batterie et charge au repos

Deux conditions de mesure :

**Mode local seul**

```text
Backend désactivé
Agent proactif désactivé
Observation UsageStats désactivée
Aucun suivi de mobilité
Aucune session Focus active
```

**Mode coaching consenti**

```text
Agent configuré
Proactivité activée
UsageStats autorisé pour une application de test
Pas de localisation continue
```

Pour chacune :

- Relever activité CPU et réveils disponibles.
- Consigner les tâches WorkManager planifiées et exécutées.
- Observer les requêtes réseau sortantes.
- Vérifier l'absence de boucle de polling inadaptée.
- Mesurer une durée réelle et préciser l'environnement.
- Consigner la consommation disponible sans extrapolation arbitraire.

Une mesure sur AVD ne doit pas être présentée comme une estimation fiable d'autonomie sur Pixel.

Ne pas modifier une fonction uniquement sur une impression de lenteur ou de consommation.

Si une anomalie est détectée, fournir mesure avant/après.

---

# LOT E — RECETTE E2E RÉELLE

Le précédent E2E OpenClaw utilisait une progression de Focus simulée.

Ce test reste pertinent, mais ne remplace pas un parcours utilisateur.

### Scénario 1 : entièrement local

1. Installer ATLAS sur un AVD jetable.
2. Créer un objectif de 120 min.
3. Sélectionner cet objectif au démarrage d'un Focus de 25 min.
4. Vérifier le lien stocké immédiatement.
5. Simuler proprement l'écoulement des 25 minutes au moyen d'une horloge contrôlée.
6. Terminer la session.
7. Vérifier `25 / 120`.
8. Redémarrer le processus.
9. Vérifier à nouveau `25 / 120`.
10. Annuler une autre session liée.
11. Vérifier que la progression reste `25 / 120`.
12. Désactiver le backend et vérifier que tout reste accessible.

### Scénario 2 : OpenClaw réel

1. Démarrer un backend FastAPI de test avec jeton éphémère.
2. Utiliser le vrai adaptateur OpenClaw déjà intégré.
3. Autoriser explicitement les catégories `focus` et `goals`.
4. Envoyer le contexte contenant une session terminée et une progression `25 / 120`.
5. Vérifier que la réponse est personnalisée à partir de cette progression.
6. Vérifier les propositions acceptées par le backend.
7. Appliquer une proposition autorisée.
8. Vérifier la décision `MandateVerifier`.
9. Vérifier effet Android et journal.
10. Révoquer `goals`.
11. Envoyer un nouveau message.
12. Prouver que l'objectif n'apparaît plus dans le nouveau payload.
13. Couper OpenClaw.
14. Vérifier HOME, Focus, pause et objectifs hors ligne.

**Aucune réponse mockée ne doit être présentée comme une réussite d'OpenClaw réel.**

Le test accéléré doit être nommé comme tel ; il ne prouve pas une endurance de 25 minutes en conditions réelles.

---

# 2. Ordonnancement des sous-sessions

Utiliser la matrice suivante :

| Session | Mission | Propriété principale |
| --- | --- | --- |
| S0 | Baseline, inventaire, reproduction des défauts | Documentation et commandes uniquement |
| S1 | A1 : localisation, harnais et tests | `location/**`, scripts de localisation |
| S2 | B2 : confidentialité des notifications proactives | `coach/proactive/**`, publisher, tests |
| S3 | C : transaction de démarrage et liaison Goal-Focus | `focus/**`, `goals/**`, `GoalPanelCoordinator.kt`, tests métier |
| S4 | A2 : nouvelle recette Android 17 | Scripts de recette Android 17, rapports et preuves |
| S5 | Intégration controller + UI | `AtlasController.kt`, `AtlasScreens.kt`, écran Focus, fichiers globaux |
| S6 | Recette complète et physique si disponible | Tests, captures et diagnostics, pas de refonte |
| S7 | Revue critique indépendante | Lecture seule, puis corrections attribuées aux propriétaires |

Les agents S3 et S5 ne modifient jamais `AtlasController.kt` simultanément.

L'intégrateur S5 est le seul propriétaire des fichiers globaux Android nécessaires.

Geler les signatures entre S3 et S5 avant toute implémentation parallèle.

### Barrières d'exécution

**Gate 1**

Baseline enregistrée, défaut de localisation reproduit et classifié.

**Gate 2**

Les régressions de stockage et de révocation restent vertes.

**Gate 3**

Le nouveau démarrage Goal-Focus passe les tests d'échec d'écriture et de redémarrage.

**Gate 4**

Les nouvelles classes instrumentées et le parcours E2E sont exécutés.

**Gate 5**

Revue indépendante sans constat critique ouvert.

Ne pas laisser S6 et S7 valider des modules encore modifiés par une autre sous-session.

---

# 3. Commandes de validation

Exécuter, depuis les répertoires réels :

```bash
cd android

./gradlew clean testDebugUnitTest lintDebug \
  assembleDebug assembleDebugAndroidTest

cd ..

backend/.venv/bin/python -m pytest backend/tests -q
```

Si le venv est absent, documenter l'environnement de remplacement et les dépendances utilisées.

Sur AVD jetable :

```bash
ANDROID_SERIAL=emulator-XXXX \
  bash scripts/v62-complete-instrumentation.sh
```

Adapter le harnais pour le nouvel AVD V6.2.2 sans affaiblir ses gardes empêchant l'utilisation accidentelle sur un appareil personnel.

Exécuter les scénarios Android 17 distinctement, même si la fonction de capture d'écran du système reste indisponible.

Relancer les tests de persistance V6.2.1 :

```text
GoalPersistenceTransactionTest
PersistenceSafetyTest
RevocationBarrierTest
AgentCategoryRevocationTest
V621ReviewFindingsTest
ActionDispatcherTest
ProactiveCoachWorkerInstrumentationTest
AgentPersistenceFailureInstrumentationTest
```

Ajouter et exécuter les tests Goal-Focus V6.2.2.

---

# 4. Règles de preuve et de rapport

Créer :

```text
docs/v6-2/
  STATUT_V622.md
  RECETTE_V622.md
  ANDROID17_V622.md
  LOCALISATION_V622.md
  QUALIFICATION_PRODUIT_V622.md
  GUIDE_ESSAI_REEL_V622.md
  INDEX_PREUVES_V622.md
```

Ajouter les preuves nécessaires sous :

```text
docs/v6-2/preuves-v622/
```

Ne pas importer des centaines de captures et logs bruts.

Conserver les journaux complets dans un dossier local de travail et ne suivre dans Git que les résumés, traces minimales et captures représentatives utiles.

Les preuves ne doivent pas contenir :

- Jetons.
- Positions réelles de l'utilisateur.
- Titres d'objectifs privés.
- Payloads OpenClaw privés.
- Identifiants de compte.
- Informations personnelles de l'appareil.

Distinguer :

```text
CODE_REVIEWED
JVM_PASS
INSTRUMENTED_PASS
REAL_OPENCLAW_PASS
REAL_DEVICE_PASS
BLOCKED
NOT_RUN
```

Ne jamais transformer une preuve de simulation en preuve d'appareil réel.

Documenter les échecs avant de les corriger, pour rendre l'amélioration vérifiable.

---

# 5. Définition de terminé

V6.2.2 est **validée techniquement** si :

- Aucun échec JVM nouveau ou test ignoré ajouté.
- Backend toujours vert.
- L'échec de mobilité est corrigé ou explicitement reproduit comme problème environnemental indépendant.
- Android 17 a été effectivement testé, ou demeure `BLOCKED` avec cause prouvée.
- Aucun contournement du mandat ou de la révocation n'est introduit.
- Les notifications proactives sont réévaluées jusqu'au dernier point possible.
- Le nouveau lien Goal-Focus est enregistré durablement avant lancement.
- Une session terminée crédite une seule fois les minutes de travail réelles.
- Une session annulée ne crédite aucune progression.
- Une erreur d'écriture ne démarre pas artificiellement un Focus lié.
- Les anciennes données locales restent lisibles.
- Le launcher et la pause d'urgence fonctionnent sans réseau.
- La recette OpenClaw réelle passe.
- Les limites non vérifiées sont déclarées explicitement.

**La qualification pour usage quotidien sur téléphone réel est distincte.** Elle ne peut être déclarée acquise qu'après un essai effectif, non destructif et documenté, sur un appareil physique.

L'issue #1 reste ouverte tant que son propre critère Android 17 n'est pas satisfait.

Ne pas maquiller un environnement bloqué en livraison complète.

---

# 6. Ce qui reste réservé à V6.3

Ne pas développer maintenant la boucle complète de coaching quotidien.

V6.3 aura pour objectif :

```text
Objectif choisi
    → plan personnel OpenClaw
    → action concrète
    → session Focus associée
    → accomplissement observé
    → bilan contextualisé
    → ajustement du prochain plan
```

Le futur mode de friction ferme sur le launcher et le blocage volontaire d'applications seront spécifiés séparément.

**V6.2.2 doit rendre fiables les éléments sur lesquels cette boucle reposera.**

---

# 7. Directive finale de l'orchestrateur

Ne commence pas par proposer trois architectures possibles.

L'architecture est arrêtée :

- Stockage local existant.
- Une transaction racine pour Focus + association Goal.
- `GoalRecord.linkedFocusSessionIds` comme source de vérité.
- `GoalProgressCalculator` comme calculateur unique.
- Pas de modification du protocole agent V2.
- Localisation Android existante, corrigée seulement sur preuve.
- Worker proactif existant avec revalidation renforcée.
- Aucun blocage d'application en V6.2.2.
- Aucun nouveau dépôt open source à intégrer.

**Exécute, teste, confronte les résultats au code, corrige et fais réaliser une revue indépendante.**

Ta sortie finale doit fournir :

1. Le HEAD de travail et les fichiers modifiés.
2. Les défauts initialement reproduits.
3. Les correctifs effectivement intégrés.
4. Les résultats précis de chaque suite.
5. Le statut réel de l'issue #1.
6. Les résultats Android 14, Android 17 et physique, séparés.
7. La démonstration Objective → Focus → progression.
8. Les régressions éventuelles.
9. Les limites encore ouvertes.
10. Le verdict `V6.2.2 VALIDÉE`, `PARTIELLE` ou `BLOQUÉE`, avec justification.

**N'annonce pas la fin du chantier avant la vérification des critères.**
