# V6.3 — Revue indépendante finale

| Élément | Valeur |
|---|---|
| Relecteur | Codex `gpt-6-luna`, effort effectif `xhigh`, session distincte |
| Worktree | `v63-final-review`, `chmod -R a-w`, écriture vérifiée refusée |
| BASE_SHA | `acda7a967b26aa3ccc8325eb194c8daa2f9a58e4` |
| Premier RESULT_SHA | `5dedcf667dff1479996f8bb99858e19ebdb7b643` |
| SHA corrigé | `6cef04b11c05f6405199e348ee70cf926dad1aa2` |
| Tests exécutés par le relecteur | **aucun** (lecture seule technique) |
| Tests parent sur SHA corrigé | 66 ciblés, 424 JVM, backend 91/1 skip, lint 0 erreur, build et androidTest compile PASS |
| Pixel / instrumenté | **NOT_RUN** |

## Premier verdict : `NO_GO` sur `5dedcf6`

| Sévérité | Type | Fichier | Constat | Correction |
|---|---|---|---|---|
| P1 | DEFECT_CONFIRMED | `PlanController.kt`, `PlanScreen.kt`, `CONTRATS_V63.md` | Un statut futur pouvait être `MISSED`, `CANCELLED` n'était pas terminal, `POSTPONED` était sélectionnable sans replanification | `PlanStatusPolicy` : `MISSED` après fin seulement, terminaux explicites, `POSTPONED` refusé ; tests ajoutés |
| P1 | DEFECT_CONFIRMED | `AtlasApplication.kt`, `AtlasApp.kt`, `PlanController.kt`, `PlanScreen.kt` | Un processus laissé ouvert jusqu'au lendemain pouvait afficher le plan de la veille sous la nouvelle date | Rechargement `ON_RESUME` + minuit local ; test même instance |
| P1 | DEFECT_CONFIRMED | `PlanRevisionEngine.kt`, `DayPlanRepository.kt` | `scopeHash` ne liait que les identifiants d'effets ; un contenu modifié avec les mêmes IDs était accepté ; des effets `CALENDAR_*` étaient annoncés sans écriture calendrier | Hash canonique des changements avant/après + effets ; effets locaux `PLAN_ACTIVITY_*`/`PLAN_APPLY` ; test de contenu altéré |
| P2 | DEFECT_CONFIRMED | `backend/atlas_backend/plan_v3.py` | L'algorithme de chevauchement adjacent manquait les intervalles imbriqués | Toutes paires avec arrêt correct ; test A/B/C imbriqué |
| P1 | EVIDENCE_GAP | `preuves/l1-plan-final.txt`, `RAPPORT_V63.md`, `REGISTRE_TACHES.md` | Preuves non rattachées au SHA final, résultats « à compléter », APK non vérifiable dans le worktree de revue | SHA `6cef04b`, commandes, compteurs et SHA APK consignés ; APK local non versionné |

## Re-revue ciblée

Une re-revue du diff `5dedcf6..6cef04b` a été demandée à la même session, en
lecture seule, pour vérifier la correction de chaque constat et l'absence de
régression. **Verdict final à consigner à réception.**

## Limites maintenues

- Aucun test Pixel/AVD instrumenté du nouvel écran : `NOT_RUN`.
- Etar réel, calendrier, wger, Bridge, localisation : non livrés.
- La carte « prochaine action » HOME n'est pas livrée.
- Aucun test d'accessibilité réelle (TalkBack, police 2×) sur le nouvel écran.
