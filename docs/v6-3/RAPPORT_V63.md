# ATLAS V6.3 — Rapport de mission (état au 2026-10-26)

Branche locale : `feat/atlas-v6-3`. Base : `acda7a967b26aa3ccc8325eb194c8daa2f9a58e4`.
Aucun push, aucune PR, aucune fusion distante, aucune action sur un téléphone.

## 1. Fonctionnalités réellement livrées

| Capacité | État | Preuve |
|---|---|---|
| Contrats de domaine V6.3 (plan, calendrier, mémoire, mandat, effets) | **LIVRÉ (document)** | `docs/v6-3/CONTRATS_V63.md` |
| Contrat backend `plan.revise_day` inactif, validation stricte | **LIVRÉ (module + tests)** | `backend/atlas_backend/plan_v3.py`, 24 tests |
| Programme journalier local `DayPlan` versionné et persistant | **LIVRÉ (JVM)** | package `dev.atlas.coach.plan`, tests à compléter |
| Révisions avec diff, aperçu, confirmation groupée, idempotence | **LIVRÉ (JVM)** | `PlanRevisionEngine`, `DayPlanRepository` |
| Écran « Plan du jour » + navigation depuis Plus | **LIVRÉ (compile/JVM)** | `PlanScreen.kt`, `AtlasApp.kt` |
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
| Plan domain ciblé | à compléter | à compléter | à compléter |
| JVM final | à compléter | à compléter | à compléter |
| Lint | à compléter | à compléter | à compléter |
| Build APK debug | à compléter | à compléter | à compléter |
| Instrumenté AVD/Pixel | non exécuté | **NOT_RUN** | — |

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

## 6. Actions manuelles nécessaires

- Connecter le Pixel, exécuter le pré-vol et une sauvegarde app-only.
- Approuver l'ajout des permissions calendrier, puis qualifier Etar.
- Fournir les identifiants wger et un environnement de test avant toute écriture.
- Déployer/configurer le Bridge avant toute recherche réelle.
- Décider du push/PR/merge distant.

## 7. Verdict proposé

**`GO_WITH_LIMITATIONS` pour le périmètre L1/L2-lecture**, sous réserve des gates
JVM/lint/build finaux et de la revue indépendante. **Aucun GO** pour les parcours
calendrier réels, wger, Bridge, Pixel, localisation ou blocage inter-applications :
ces éléments sont `NOT_RUN` ou `BLOCKED` et ne doivent pas être présentés comme
livrés.
