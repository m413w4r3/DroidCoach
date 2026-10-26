# V6.3 — L1 : programme journalier local

Statut : **implémenté localement, tests JVM obligatoires ; aucune preuve Pixel**.
Ce document décrit exactement ce qui est livré et ce qui ne l'est pas.

## Parcours utilisateur

1. Ouvrir **Plus → Programme → Plan du jour**.
2. Si aucun programme n'existe pour la date locale, appuyer sur
   **Créer le programme du jour**. Le document est créé localement, en état
   approuvé et version 1.
3. Appuyer sur **Ajouter une activité** : titre, type, début, durée, priorité,
   note locale. L'application affiche un **aperçu de révision** (version N → N+1,
   liste des changements, nombre d'effets locaux, chevauchements).
4. **Confirmer** applique exactement cet ensemble d'effets locaux. **Abandonner**
   n'écrit rien.
5. Une activité peut être déplacée, modifiée, changée de statut ou supprimée.
   Chaque opération repasse par un aperçu et une confirmation.
6. Le programme est relu après fermeture, redémarrage de l'application ou
   changement de jour ; chaque date locale possède son propre fichier
   `day-plan-YYYY-MM-DD.json` sous `filesDir/plans/`.

## Invariants réellement implémentés et testés

| Invariant | Implémentation | Test |
|---|---|---|
| `plan_id` stable et `version` monotone | `DayPlan`, `DayPlanRepository.save` | JVM plan |
| Plan approuvé lisible hors ligne | fichier privé atomique, aucune dépendance réseau | JVM plan + controller |
| Diff compréhensible | `PlanRevisionEngine.calculateChanges` + dialogue d'aperçu | JVM plan + controller |
| Confirmation groupée | `PlanConfirmation.effectIds` + `scopeHash` | JVM plan |
| Effet supplémentaire refusé | comparaison stricte des effets | JVM plan |
| Proposition périmée refusée | `base_version` / `STALE_REVISION` | JVM plan |
| Retry sans doublon | `revision_id` déjà appliquée = no-op | JVM plan |
| Résultat inconnu → réconciliation | champs `UNKNOWN` prévus dans le contrat ; **pas encore d'écriture externe** | n/a |
| Fuseaux / DST | `LocalSchedule` + `ZoneId` explicite, trou/chevauchement définis | JVM plan |
| Statuts distincts | `PLANNED`, `IN_PROGRESS`, `DONE`, `MISSED`, `CANCELLED`, `POSTPONED` | JVM plan + controller |
| Divergence plan/calendrier | champ `CalendarEventLink` prévu ; **aucune écriture calendrier** | n/a |
| Heure passée ≠ activité faite | aucun auto-`DONE` ; statut explicite seulement | JVM controller |

## Ce qui n'est pas livré

- **Aucun effet calendrier** : la permission `READ_CALENDAR`/`WRITE_CALENDAR`
  n'est pas déclarée ; aucun événement Etar n'est créé, déplacé ou supprimé.
- **Aucune proposition OpenClaw** : la négociation de révision (L3) n'est pas
  branchée ; les révisions sont locales et explicites.
- **Aucune carte « prochaine action » sur HOME** : le plan est accessible via
  Plus. L'intégration HOME est un ticket suivant.
- **Aucun test instrumenté ni Pixel** : la seule preuve est JVM.
- **Pas de récurrence, de fuseau multiple ni de calendrier réel** dans ce lot.

## Fichiers

- `android/app/src/main/java/dev/atlas/coach/plan/` : domaine, codec JSON strict,
  stockage atomique, moteur de révision, tests.
- `android/app/src/main/java/dev/atlas/coach/app/PlanController.kt` : adaptateur
  UI, un document par date, aperçu + confirmation.
- `android/app/src/main/java/dev/atlas/coach/app/PlanScreen.kt` : écran Compose.
- `android/app/src/test/java/dev/atlas/coach/app/PlanControllerTest.kt` : oracle
  du parcours local.

## Preuves

Base d'intégration des deux enfants : `f78e3d93d3c96db9308336141f51ef83409f3576`.

| Gate | Résultat |
|---|---|
| Ciblé plan + contrôleur + navigation | **57 PASS** (42 plan, 10 contrôleur, 5 navigation), 0 échec |
| Suite JVM complète | **415 PASS**, 0 échec, 0 ignoré, 62 suites |
| Lint | **0 erreur, 52 avertissements**, aucun avertissement ciblé Plan/Calendar |
| `assembleDebug` | **PASS** |
| `compileDebugAndroidTestKotlin` | **PASS** |
| APK debug | SHA-256 `7725894b478c8230732a017a2c6458ae458b059ac6f63859f15c66eee51e70bb` |
| Appareil / Pixel / Etar | **NOT_RUN** — aucun appareil connecté |

Le détail est consigné dans `docs/v6-3/preuves/l1-plan-final.txt`.
