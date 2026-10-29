# R5-I — Jauge de temps Focus + réduction du texte

| Clé | Valeur |
|---|---|
| TASK_ID / statut | `R5-I / READY` |
| Catégorie | Implémentation UI bornée |
| Depuis | `e12a8bf5c37ccd1db1a6022fda4d177e5e9c11a8` |
| Profil exigé | `codex:gpt-6-luna`, `effort=xhigh`, vérifié dans les métadonnées |
| Worktree | Paseo-managed, branche `feat/r5-i-focus-gauge`, aucune sous-délégation |
| Propriété d'écriture | `android/app/src/main/java/dev/atlas/coach/app/FocusRoutinesScreen.kt`<br>`android/app/src/main/java/dev/atlas/coach/app/FocusGauge.kt` (nouveau, calcul pur)<br>`android/app/src/test/java/dev/atlas/coach/app/FocusGaugeTest.kt` (nouveau)<br>`android/app/src/androidTest/java/dev/atlas/coach/app/FocusGaugeInstrumentationTest.kt` (nouveau) |
| Interdit d'écriture | `AtlasScreens.kt` (un autre ticket peut le modifier), tout fichier de domaine Focus |
| Lecture | `FocusModels.kt`, `FocusEngine.kt`, tests `AtlasLaunchTest`, `GoalFocusStartInstrumentationTest`, `LotEUiInstrumentationTest` |

## Objectif observable

Pendant une séance Focus en cours (ou en pause), afficher une **jauge de temps** circulaire autour du compte à rebours. Réduire le texte secondaire à : tâche, temps restant, phase, pause/reprise, annuler, terminer. La pause d'urgence du cadre commun reste accessible.

## Règles

1. **Jauge** : progression = `remainingMillis / durée_de_la_phase_courante` (WORK → `workSeconds`, SHORT_BREAK → `shortBreakSeconds`, LONG_BREAK → `longBreakSeconds`), bornée `0.0..1.0`. Une jauge pleine au début de phase, vide à la fin. La valeur est pure et testée dans `FocusGauge.kt`.
2. **Affichage** : `CircularProgressIndicator` (ou Canvas équivalent) + compte à rebours au centre. Taille adaptée portrait/paysage ; à police 2× le compte à rebours reste lisible et les boutons ≥ 48 dp.
3. **Tags et textes existants à préserver** : `focus_active_task`, `focus_time_remaining`, `focus_phase`, `focus_cycles`, `focus_pause_resume`, `focus_cancel`, `focus_finish`. Les tests existants attendent exactement `Concentration · en cours` et `Concentration · en pause` sur `focus_phase` : ne pas changer ce libellé.
4. **Nouveau tag** : `focus_time_gauge` sur la jauge, avec sémantique `progressBarRangeInfo` et `contentDescription` du type « Temps restant 12 min 30 s ».
5. **Texte réduit** : les lignes « X min de concentration · … », « Fin approximative… », « Notifications : … » passent derrière un bouton court `Détails` (replié par défaut) ou sont fusionnées en une ligne discrète. Ne pas supprimer l'information sur les notifications si elle signale une permission/refus ; dans ce cas la conserver visible.
6. **Pause d'urgence** : aucune modification de `AtlasScreenFrame` ni du cadre commun. Vérifier qu'elle reste atteignable en session.
7. **Aucune modification de domaine** : pas de changement de `FocusEngine`, `FocusRepository`, `FocusModels`, ni des notifications Android.
8. **Mode Focus désactivé** : ne pas modifier la branche correspondante dans `AtlasScreens.kt` ; le signaler comme limite si la jauge n'y apparaît pas.

## Oracle

- `cd android && ./gradlew :app:testDebugUnitTest --tests '*FocusGaugeTest*' --rerun-tasks` → PASS.
- Cas : 100 %, 50 %, 0 %, pause, pause courte, pause longue, `remainingMillis=0`, valeurs incohérentes bornées, division par zéro impossible.
- `./gradlew :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest` → PASS.
- Test instrumenté compilé (parent l'exécute sur AVD API 37) : jauge visible à 100 % et après pause, texte de phase inchangé, boutons pause/reprise/annuler/terminer visibles, urgence visible.
- Aucune régression des tests Focus existants (JVM + instrumentation ciblée).

## Stop conditions

- Modèle/effort incorrect → `BLOCKED`.
- Besoin de toucher `AtlasScreens.kt` ou le domaine Focus → arrêter, signaler, proposer un correctif au parent.
- Test existant qui exige une modification de libellé → signaler, ne pas modifier le test sans accord.

## Livrables

- Commit unique, SHA complet.
- Captures AVD non obligatoires pour l'enfant (le parent les fera).
- Sorties de tests, limites, fichiers modifiés.
