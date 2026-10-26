# R2-PLAN-UI-01 — Refonte de Plan du jour

| Clé | Valeur |
|---|---|
| TASK_ID / statut | `R2-PLAN-UI-01 / READY (vague B)` |
| BASE_SHA | HEAD de la branche de campagne **après intégration de `R2-UI-01`** (SHA exact fourni au lancement) |
| Profil imposé | `provider=codex`, `model=gpt-6-luna`, `effort=xhigh` |
| Worktree | `feat/r2-plan-ui` |
| Accès | écriture bornée ci-dessous ; aucun ADB, aucun réseau |
| Dépendances | `R2-UI-01` intégré et accepté |

## Objectif observable

Rendre Plan du jour immédiatement lisible : action principale évidente,
hiérarchie claire, liste chronologique compacte, prochaine activité mise en
avant, actions secondaires rangées, détails techniques à la demande. Le
différentiel du coach et la confirmation groupée restent **exactement** aussi
stricts qu'avant.

## Scope d'écriture exclusif

```text
android/app/src/main/java/dev/atlas/coach/app/PlanScreen.kt
android/app/src/main/java/dev/atlas/coach/app/PlanUiComponents.kt (neuf)
android/app/src/androidTest/java/dev/atlas/coach/app/PlanScreenInstrumentationTest.kt (neuf)
android/app/src/test/java/dev/atlas/coach/app/PlanUiLogicTest.kt (neuf)
```

Interdictions : `AtlasScreens.kt`, `PlanController.kt`, `AtlasApp.kt`,
`AtlasScreenId.kt`, `AtlasController.kt`, `app/ui/**`, `plan/**`, manifeste,
`build.gradle.kts`, `docs/**`. Ne pas modifier les contrats, le stockage, les
hashes, les effets ou l'idempotence.

## Exigences visuelles et fonctionnelles

### En-tête

- Date lisible et titre court (« Aujourd'hui » ou « Plan du jour »).
- Progression courte (par exemple « 1 sur 3 terminée » ou état vide).
- État de synchronisation/erreur **uniquement s'il demande l'attention**.
- `version`, fuseau et emplacement de stockage déplacés dans un détail
  consultable (section repliable ou dialogue « Détails »).
- Conserver l'état « programme illisible » visible et non masqué.

### Corps

- Activités dans l'ordre horaire, avec heure, durée, titre, type/catégorie,
  état, priorité si pertinente.
- Liste compacte avec repère temporel ; éviter une succession de grandes
  cartes identiques.
- Passé / en cours / à venir distinguables par **texte + glyphe/forme**, pas
  seulement par couleur.
- Prochaine activité pertinente clairement mise en avant (libellé « Prochaine »
  et style).
- Action principale unique et contextuelle : « Demander au coach » si la
  capability et la configuration sont prêtes, sinon « Ajouter une activité ».
- Actions de gestion par ligne dans un menu « Plus » (`DropdownMenu`) :
  Déplacer, Modifier, Statut, Supprimer. L'action destructive est explicitement
  libellée et confirmée.
- Aucune rangée de 4 boutons visibles simultanément sur chaque activité.
- États vides : « journée libre » avec une seule action claire.

### Proposition du coach

- Conserver `RevisionPreviewDialog` et le contenu exact :
  création, déplacement (ancien → nouveau), modification des champs,
  suppression, conflit/chevauchement.
- Afficher la version avant → après, le nombre exact d'effets, le résumé et les
  avertissements.
- Ne pas réduire la confirmation à un bouton qui cache les détails.
- Conserver les dialogues de consentement, la configuration requise et les
  erreurs de manière lisible.

### Contraintes techniques

- Aucun `verticalScroll` racine : `AtlasScreenFrame` fournit le scroll unique.
- Aucun scroll imbriqué à hauteur infinie.
- Tous les champs du formulaire restent accessibles avec le clavier ouvert.
- Cibles tactiles ≥ 48 dp.
- Sémantique Compose : libellés explicites, menu accessible, statut annoncé.
- Police 2× : pas de hauteur fixe qui coupe le texte ; les actions restent
  atteignables.
- Rotation : état local préservé autant que possible (`rememberSaveable` pour
  les dialogues/sélections).
- Aucune donnée persistée perdue ; le format `DayPlan` est inchangé.

## Oracle

Tests JVM (dans `PlanUiLogicTest.kt`) :

1. ordre horaire stable ;
2. sélection de la prochaine activité (passée/en cours/à venir) ;
3. libellés de statut et de priorité ;
4. état vide et état sans document ;
5. libellé du différentiel pour chaque type de changement.

Tests instrumentés (à compiler, exécution par le parent) :

1. plan vide → action principale visible ;
2. 3 activités → ordre et prochaine activité ;
3. menu « Plus » → actions présentes, suppression confirmée ;
4. police 2× → actions principales visibles ;
5. aperçu coach → tous les changements listés.

Commandes :

```bash
cd android && ./gradlew :app:testDebugUnitTest \
  --tests "dev.atlas.coach.app.PlanUiLogicTest" --max-workers=1
cd android && ./gradlew :app:testDebugUnitTest --max-workers=1
cd android && ./gradlew :app:assembleDebug
cd android && ./gradlew :app:assembleDebugAndroidTest
```

## Livrables

Commit local, SHA de sortie, fichiers, commandes, résultats, limites.
Aucune capture (le parent capture avant/après sur l'émulateur).

## Stop conditions

Modèle/effort non conformes, conflit de fichier, besoin de modifier
`PlanController`/`PlanModels`/manifeste, besoin d'ADB/réseau, ou toute
modification qui affaiblit la confirmation groupée.
