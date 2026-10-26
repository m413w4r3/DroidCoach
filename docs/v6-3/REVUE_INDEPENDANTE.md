# V6.3 — Revue indépendante finale

| Élément | Valeur |
|---|---|
| Relecteur | Codex `gpt-6-luna`, effort effectif `xhigh`, session distincte |
| Worktree | `v63-final-review`, `chmod -R a-w`, écriture vérifiée refusée |
| BASE_SHA | `acda7a967b26aa3ccc8325eb194c8daa2f9a58e4` |
| Premier RESULT_SHA | `5dedcf667dff1479996f8bb99858e19ebdb7b643` |
| Deuxième SHA | `6cef04b11c05f6405199e348ee70cf926dad1aa2` |
| SHA corrigé final | `40030950f9b21dece8a0af63f6e7249930da6c11` |
| Tests exécutés par le relecteur | **aucun** (lecture seule technique) |
| Tests parent sur `6cef04b` | 66 ciblés, 424 JVM, backend 91/1 skip, lint 0 erreur, build et androidTest compile PASS |
| Tests parent sur `4003095` (SHA final) | 68 ciblés, 426 JVM, backend 91/1 skip, lint 0 erreur, build et androidTest compile PASS |
| Pixel / instrumenté | **NOT_RUN** |

## Premier verdict : `NO_GO` sur `5dedcf6`

| Sévérité | Type | Fichier | Constat | Correction |
|---|---|---|---|---|
| P1 | DEFECT_CONFIRMED | `PlanController.kt`, `PlanScreen.kt`, `CONTRATS_V63.md` | Un statut futur pouvait être `MISSED`, `CANCELLED` n'était pas terminal, `POSTPONED` était sélectionnable sans replanification | `PlanStatusPolicy` : `MISSED` après fin seulement, terminaux explicites, `POSTPONED` refusé ; tests ajoutés |
| P1 | DEFECT_CONFIRMED | `AtlasApplication.kt`, `AtlasApp.kt`, `PlanController.kt`, `PlanScreen.kt` | Un processus laissé ouvert jusqu'au lendemain pouvait afficher le plan de la veille sous la nouvelle date | Rechargement `ON_RESUME` + minuit local ; test même instance |
| P1 | DEFECT_CONFIRMED | `PlanRevisionEngine.kt`, `DayPlanRepository.kt` | `scopeHash` ne liait que les identifiants d'effets ; un contenu modifié avec les mêmes IDs était accepté ; des effets `CALENDAR_*` étaient annoncés sans écriture calendrier | Hash canonique des changements avant/après + effets ; effets locaux `PLAN_ACTIVITY_*`/`PLAN_APPLY` ; test de contenu altéré |
| P2 | DEFECT_CONFIRMED | `backend/atlas_backend/plan_v3.py` | L'algorithme de chevauchement adjacent manquait les intervalles imbriqués | Toutes paires avec arrêt correct ; test A/B/C imbriqué |
| P1 | EVIDENCE_GAP | `preuves/l1-plan-final.txt`, `RAPPORT_V63.md`, `REGISTRE_TACHES.md` | Preuves non rattachées au SHA final, résultats « à compléter », APK non vérifiable dans le worktree de revue | SHA `6cef04b`, commandes, compteurs et SHA APK consignés ; APK local non versionné |

## Deuxième revue (`6cef04b`) : `NO_GO`

| Sévérité | Type | Fichier | Constat | Correction |
|---|---|---|---|---|
| P1 | DEFECT_CONFIRMED | `PlanRevisionEngine.kt` | `notes = null` et `notes = "<null>"` produisaient le même hash de portée | Encodage length-prefixed (`N` vs `S<longueur>:<valeur>`) ; tests moteur et dépôt ajoutés |
| P1 | EVIDENCE_GAP | `preuves/l1-plan-final.txt`, `RAPPORT_V63.md` | Le relecteur, sur son worktree à `5dedcf6`, ne voyait pas les preuves actualisées | Preuves et rapport mis à jour avec le SHA `4003095`, commandes, compteurs et APK ; la re-revue doit lire les docs au SHA courant |

Les règles de statut, le rechargement au retour au premier plan et les
chevauchements imbriqués ont été confirmés corrigés par le relecteur.

## Re-revue finale : `GO_WITH_LIMITATIONS` sur `4003095`

Le relecteur confirme :

- la collision `null` / `"<null>"` est corrigée par l'encodage length-prefixed et
  couverte par les tests moteur et dépôt ;
- les preuves finales de `4003095` sont visibles et cohérentes (68 ciblés, 426
  JVM, backend 91/1 skip, lint/build, APK SHA-256) ;
- aucun nouveau P0/P1 n'est introduit par le diff ciblé.

**P2 `EVIDENCE_GAP` résiduel** : la ligne de synthèse de ce document indiquait
66/424 sans rattacher ce chiffre à `6cef04b`. Il est corrigé ci-dessus : 66/424
= `6cef04b`, 68/426 = `4003095` (SHA final). Ce P2 est documentaire, sans effet
sur le code.

**Verdict final : `GO_WITH_LIMITATIONS`.** Limites maintenues : Pixel/Etar/
instrumenté `NOT_RUN`, calendrier/wger/Bridge/localisation non livrés, carte
HOME non livrée, accessibilité réelle non testée, V6.3 complète `NO_GO`.

## Limites maintenues

- Aucun test Pixel/AVD instrumenté du nouvel écran : `NOT_RUN`.
- Etar réel, calendrier, wger, Bridge, localisation : non livrés.
- La carte « prochaine action » HOME n'est pas livrée.
- Aucun test d'accessibilité réelle (TalkBack, police 2×) sur le nouvel écran.
