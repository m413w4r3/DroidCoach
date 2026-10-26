# ATLAS V6.3 — Rapport d'étape, jalon L1/L2-lecture (2026-10-26)

> **Ce document ne clôt pas V6.3.** Il qualifie le premier incrément intégré
> localement. Les parcours calendrier réels, wger, Bridge, usage/restrictions,
> mémoire, localisation, OpenClaw négocié et Pixel restent non livrés ou
> `BLOCKED`/`NOT_RUN`. Voir la section 7.

Branche locale : `feat/atlas-v6-3`. Base : `acda7a967b26aa3ccc8325eb194c8daa2f9a58e4`.
Aucun push, aucune PR, aucune fusion distante, aucune action sur un téléphone.

## 1. Fonctionnalités réellement livrées

| Capacité | État | Preuve |
|---|---|---|
| Contrats de domaine V6.3 (plan, calendrier, mémoire, mandat, effets) | **LIVRÉ (document)** | `docs/v6-3/CONTRATS_V63.md` |
| Contrat backend `plan.revise_day` inactif, validation stricte | **LIVRÉ (module + tests)** | `backend/atlas_backend/plan_v3.py`, 24 tests |
| Programme journalier local `DayPlan` versionné et persistant | **LIVRÉ (JVM)** | package `dev.atlas.coach.plan`, 45 tests |
| Révisions avec diff, aperçu, confirmation groupée, idempotence | **LIVRÉ (JVM)** | `PlanRevisionEngine`, `DayPlanRepository` |
| Écran « Plan du jour » + navigation depuis Plus | **LIVRÉ (compile/JVM)** | `PlanScreen.kt`, `PlanController.kt`, 16 tests contrôleur |
| Sonde CalendarContract lecture seule + détection Etar | **LIVRÉ (JVM/compile)** | package `dev.atlas.coach.calendar`, 23 tests |
| Permissions calendrier | **NON DEMANDÉES** | manifeste inchangé |
| Écriture calendrier / Etar | **NON LIVRÉ** | dépend de l'accord permission + appareil |
| wger | **NON LIVRÉ** | aucun identifiant, aucun appel |
| Bridge | **NON LIVRÉ** | service non configuré |
| Localisation / mobilité | **NON LIVRÉ** | capacités OFF |
| Blocage inter-applications | **NON LIVRÉ** | politique MVP |
| Pixel / Android 17 réel | **NON TESTÉ** | aucun appareil connecté |

## 2. Tests et preuves

| Gate | Commande / environnement | Résultat | SHA |
|---|---|---|---|
| Backend baseline | `pytest -q` | **PASS** 66 passed, 1 skipped | `acda7a9` |
| Contrat plan V3 | `pytest tests/test_plan_v3*.py` | **PASS** 24 | `ba38524` |
| Backend après ajout | `pytest -q` | **PASS** 88 passed, 1 skipped | `ba38524` |
| JVM baseline | `testDebugUnitTest` | **PASS** 339 | `acda7a9` |
| Calendar read ciblé | `--tests dev.atlas.coach.calendar.*` | **PASS** 23 | `81e1bd2` |
| JVM après calendar | `testDebugUnitTest` | **PASS** 362 | `81e1bd2` |
| Plan domain ciblé | `testDebugUnitTest --tests dev.atlas.coach.plan.*` | **PASS** 42 | `17bb723` |
| Plan/contrôleur/navigation ciblé | 3 filtres de classes | **PASS** 68 | `4003095` |
| JVM final | `testDebugUnitTest` | **PASS** 426, 0 échec, 0 ignoré, 62 suites | `4003095` |
| Lint | `lintDebug` | **PASS** 0 erreur, 52 avertissements | `4003095` |
| Build APK debug | `assembleDebug` | **PASS** | `4003095` |
| Compilation androidTest | `compileDebugAndroidTestKotlin` | **PASS** | `4003095` |
| Backend final | `pytest -q` | **PASS** 91 passed, 1 skipped | `4003095` |
| Instrumenté AVD/Pixel | non exécuté | **NOT_RUN** | — |

APK debug : SHA-256 `e58993f94e99607c4fe0cd7a972bba08495b141652ba8a02d856909eca9c7691`
(source `4003095`). L'APK est un artefact de build local non versionné ; son
empreinte et la commande de build sont consignées.

## 3. État du Pixel et permissions

- Aucun appareil connecté au pré-vol (`adb devices` vide). Aucun test physique.
- Aucune permission `READ_CALENDAR`/`WRITE_CALENDAR` déclarée ni demandée.
- Aucune permission supplémentaire ajoutée au manifeste.
- Rôle HOME, notifications, Usage Access et localisation : état historique V6.2,
  non rejoué sur le SHA V6.3.
- Sauvegarde : aucune action, aucun effacement, aucune désinstallation.

## 4. Services externes

- OpenClaw : gateway loopback actif, mais aucune requête V6.3 réelle effectuée.
- wger : non configuré. Aucune lecture ni écriture.
- Bridge : non configuré. Aucune recherche.
- Réseau privé : non installé, aucun port ouvert.

## 5. Risques et limites

1. `targetSdk=36` alors que la cible de qualification est API 37 ; migration non
   faite et non revendiquée.
2. Création d'un calendrier local incertaine (sync adapter requis) ; L4 peut être
   `BLOCKED_ENVIRONMENT`.
3. Aucune preuve instrumentée ni Pixel : le parcours Compose du plan n'est pas
   validé sur appareil, seulement compilé et testé via son contrôleur JVM.
4. Les marqueurs `CUSTOM_APP_PACKAGE`/`CUSTOM_APP_URI` sont vérifiés dans le SDK
   mais leur comportement d'écriture n'est pas qualifié sur le provider réel.
5. Aucun test d'accessibilité réel (TalkBack, police 2×) sur le nouvel écran.
6. La révocation/le mandat agent ne couvrent pas encore les révisions de plan,
   car aucune action agent `plan.revise_day` n'est branchée.
7. Le document de plan est local et non chiffré ; il ne contient pas de secret.
8. Le lien objectif/sous-objectif n'est **pas encore exposé dans l'écran Plan**.
   `PlanActivity.goalId` exige un UUID canonique ; les nouveaux objectifs en
   utilisent un, mais d'éventuels identifiants historiques non-UUID devraient
   être migrés avant liaison. Aucun modèle `SubGoal` local n'est implémenté.
9. La création d'un plan initial approuvé ne produit pas de `PlanRevision`
   version 1 : elle est une création locale explicite, pas un effet externe.
   L'audit des révisions commence à la première modification.
10. Le plan local est une capacité de base active par défaut, sans effet
    externe ; il n'a pas encore d'interrupteur dédié dans les réglages. Toutes
    les capacités externes (calendrier, wger, Bridge, mémoire) restent
    désactivées et non exposées.

## 6. Actions manuelles nécessaires

- Connecter le Pixel, exécuter le pré-vol et une sauvegarde app-only.
- Approuver l'ajout des permissions calendrier, puis qualifier Etar.
- Fournir les identifiants wger et un environnement de test avant toute écriture.
- Déployer/configurer le Bridge avant toute recherche réelle.
- Décider du push/PR/merge distant.

## 7. Verdict

### Jalon L1/L2-lecture

**`GO_WITH_LIMITATIONS`** — revue indépendante finale sur le SHA de code
`4003095` : les gates JVM/lint/build sont verts, le programme journalier local
et la sonde calendrier en lecture seule sont testés, mais aucun appareil n'a été
utilisé et le calendrier réel n'est pas branché. La revue a d'abord rendu
`NO_GO` (trois P1 puis une collision de hachage) ; chaque défaut a été corrigé
et re-vérifié. Le P2 documentaire résiduel (traçabilité 66/424 vs 68/426) est
corrigé dans `REVUE_INDEPENDANTE.md`. Détail : [REVUE_INDEPENDANTE.md](REVUE_INDEPENDANTE.md).

### V6.3 complète

**`NO_GO`** : le périmètre demandé n'est pas terminé. Ne sont pas livrés ou
qualifiés :

1. négociation OpenClaw d'une révision de journée et confirmation groupée
   côté Android ;
2. lecture/écriture réelles Etar et création d'un calendrier local ;
3. wger (lecture, écriture, conflits de synchronisation) ;
4. Bridge ChatGPT et recherche récente vérifiée ;
5. mémoire contrôlable, proactivité bornée et quotas ;
6. observation globale de durée et restrictions inter-applications ;
7. localisation contextuelle ;
8. parcours Pixel Android 17 et tests instrumentés du nouvel écran ;
9. accès privé au PC hors Wi-Fi ;
10. dossier de clôture complet avec tous les scénarios critiques.

Ces éléments sont `NOT_RUN`, `BLOCKED_APPROVAL`, `BLOCKED_CREDENTIALS`,
`BLOCKED_SERVICE` ou `BLOCKED_DEVICE` selon le registre. Aucun ne doit être
présenté comme un `PASS`.
