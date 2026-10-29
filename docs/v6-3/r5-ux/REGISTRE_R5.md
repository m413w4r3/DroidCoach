# ATLAS V6.3 — Registre du chantier R5 « Coach & densité »

- **Date d'ouverture** : 2026-10-29
- **Parent** : session Paseo DeepSeek Flash 1M thinking max
- **Base de campagne** : `e12a8bf5c37ccd1db1a6022fda4d177e5e9c11a8`
- **Branche d'intégration** : `feat/atlas-v6-3-ux-daily-use`
- **`origin/main`** : `566668ac9cd0778f43d7f4d90a93c6a45645935c` — aucun push
- **Enfants** : **tous** `codex:gpt-6-luna`, effort `xhigh`, aucune sous-délégation
- **Concurrence** : 6 enfants directs max, 3 implémentations max, 1 propriétaire par fichier, 1 opérateur ADB (le parent)
- **Notifications** : événementielles uniquement, aucun `sleep`/polling

## État reçu de R4

| Élément | État |
|---|---|
| Code R4 sur branche locale | intégré jusqu'à `e12a8bf` |
| JVM / lint / build R4 | PASS sur `7d7b5f5`, 720 tests |
| AVD API 37 R4 | 50 tests ciblés PASS |
| Pixel physique R4 | **BLOCKED_DEVICE** — appareil absent au pré-vol `adb devices -l` (seul `emulator-5554` présent) |
| Installation APK final Pixel | **NOT_RUN** |
| Captures Pixel « après » | **NOT_RUN** |
| Revue indépendante R4 | **NOT_RUN** |
| Push | **NON** |

Le Pixel n'étant pas reconnecté, R4 Pixel reste `BLOCKED_DEVICE`. Le chantier R5 démarre sans bloquer.

## Décisions d'ouverture (réversibles, tracées)

1. **Insights locaux déterministes d'abord** : agrégats JVM purs, aucune permission, aucun envoi réseau. Interprétation distante hors périmètre R5.
2. **Emplacement Insights** : sous-onglet dans `Coach` (`Conversation · Synthèses`), pas de 6ᵉ onglet bas.
3. **Seuil de données** : `Données insuffisantes` explicite ; aucun pattern si le seuil n'est pas atteint. Seuils documentés dans le ticket G1 et testés.
4. **Régime textuel en deux lots** : F (Coach/Contexte) puis F2 (Plan/Connexion/Paramètres/Sport). Les consentements, permissions, révocations et confirmations ne sont jamais supprimés ; ils sont raccourcis ou déplacés au moment de l'action.
5. **Jauge Focus** : jauge de phase dans `FocusSessionScreen`, texte secondaire replié ; pause d'urgence intacte.

## Tickets

| ID | Objet | Fichiers propriétaires | Vague | État |
|---|---|---|---|---|
| `R5-F` | Régime textuel Coach + Contexte | `AgentCoachScreen.kt`, `CoachContextScreen.kt` | 1 | ACCEPTED + INTEGRATED — `e9e1193` → `62fbca4` ; harnais corrigé `00beca8`, `455fc43`, `257b912` ; AVD PASS |
| `R5-G1` | Agrégateur Insights JVM pur | nouveaux `insights/*.kt` + tests | 1 | ACCEPTED + INTEGRATED — `d0d2c1f` → `2834fa3` |
| `R5-I` | Jauge Focus + texte | `FocusRoutinesScreen.kt`, nouveau `FocusGauge.kt` + tests | 1 | ACCEPTED + INTEGRATED — `897ab0d` → `11d015b` |
| `R5-F2` | Régime textuel 2ᵉ lot | `PlanScreen.kt`, `SyncSettingsScreen.kt`, `RelaySettingsScreen.kt`, `SportScreen.kt`, `AtlasScreens.kt` (SettingsScreen uniquement) | 2 | ACCEPTED + INTEGRATED — `f868e2f` → `afb3d77` ; harnais `44be789` → `49a29f9` |
| `R5-G2` | UI Insights + onglet Coach | nouveaux `CoachInsightsScreen.kt`, `CoachTabScreen.kt`, `AtlasApp.kt`, `AtlasScreenId.kt`, `PlanController.kt` | 2 | ACCEPTED + INTEGRATED — `f5a2377` → `d3d7951` ; harnais `3c799dd` |
| `R5-H` | Actions Coach simplifiées | `AgentCoachScreen.kt`, nouveau `CoachQuickActions.kt` + tests | 2 | ACCEPTED + INTEGRATED — `6795b64` + `5b53955` → `e0d026f` + `1daed73` |

## Gates d'intégration

- `git diff --check`, diff borné, aucun secret ni donnée personnelle.
- JVM `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug`, `:app:assembleDebugAndroidTest` sur le SHA intégré.
- Instrumentation ciblée sur AVD API 37 ; **jamais** la suite Android 14.
- Captures AVD avec données synthétiques, puis Pixel uniquement si reconnecté et avec accord.
- Revue indépendante finale : nouvelle session Luna XHigh en lecture seule technique.
- Aucun push avant validation Pixel, revue indépendante et GO.

## Recette AVD prévue (parent uniquement)

- Appareil : `emulator-5554`, API 37, `sdk_gphone16k_x86_64`, `user_setup_complete=1`, `dev.atlas.coach` installé.
- Commandes ciblées après intégration : `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest`, puis `adb -s emulator-5554 install -r` des deux APK, puis `adb -s emulator-5554 shell am instrument -w -r -e class <classes> dev.atlas.coach.test/androidx.test.runner.AndroidJUnitRunner`.
- Classes prioritaires : `AtlasLaunchTest`, `AtlasPresentationTest`, `LotEUiInstrumentationTest`, `GoalFocusStartInstrumentationTest`, `PlanScreenInstrumentationTest`, `PlanCalendarScrollInstrumentationTest`, `SyncSettingsScreenCaptureTest`, plus les nouveaux tests R5.
- Les tests ne sont **jamais** lancés sur le Pixel sans accord ciblé ; la suite Android 14 n'est pas exécutée.
- Captures R5 : AVD 360 dp, données synthétiques, dans `docs/captures/v6-3-r5/`.

## Sauvegarde Pixel R4

- `~/atlas-backups/r4/pixel-app-backup-pre-r4.tar` présent, mode 0600, SHA-256 vérifié
  `30cca099a1498cc9c6afcf85f965ddd61aab640ddf0b1fab8e404cdabdbe050c`.
- `pixel-app-files-preinstall.sha256` présent mais vide (0 octet) : à reconstituer avant toute installation Pixel.

## Journal

| Heure (locale) | Événement |
|---|---|
| 2026-10-29 | Pré-vol : Git propre, HEAD `e12a8bf`, Pixel absent (`BLOCKED_DEVICE`), AVD API 37 disponible. Registre ouvert. |
| 2026-10-29 | Docs/tickets commités en `681cc44`. Worktrees F/G1/I créés depuis `e12a8bf` ; profils vérifiés `codex/gpt-6-luna` + `xhigh` effectif. |
| 2026-10-29 | Sauvegarde Pixel R4 vérifiée ; `pixel-app-files-preinstall.sha256` vide, à refaire avant installation. |
| 2026-10-29 | G1 `d0d2c1f` et I `897ab0d` relus, scope conforme, tests ciblés PASS, `git diff --check` PASS. Intégrés en `2834fa3` et `11d015b`. Suite JVM/lint/build complète lancée sur `11d015b`. |
| 2026-10-29 | Constat outillage : Gradle 9.6 rejette `--test` ; utiliser `--tests`. Tickets corrigés. |
| 2026-10-29 | Suite complète sur `11d015b` (G1+I) : **736 tests, 0 échec, 0 erreur, 0 ignoré, 104 suites** ; `lintDebug`, `assembleDebug`, `assembleDebugAndroidTest` PASS ; exit 0. |
| 2026-10-29 | AVD API 37 sur `11d015b` : `FocusGaugeInstrumentationTest` **PASS 1/1** ; `AtlasLaunchTest` + `GoalFocusStartInstrumentationTest` **PASS 19/19**. |
| 2026-10-29 | F relu (scope 2 écrans + test, textes de sécurité conservés), intégré en `62fbca4`. H lancé sur cette base. Suite complète relancée. |
| 2026-10-29 | Suite complète sur `62fbca4` : **736 tests, 0 échec**, lint/build/androidTest PASS. AVD : `AtlasPresentationTest` 5/5, `LotEUiInstrumentationTest` 5/5, `AtlasLaunchTest` 12/12. `CoachTextDensityInstrumentationTest` **FAIL harnais** (`isolatedContext` check), correction demandée à F ; produit non en cause. |
| 2026-10-29 | Correction harnais F `f7ce236` intégrée en `e0dfc14`. 2ᵉ exécution AVD : échec de scroll sur l'assertion du titre Contexte ; correction test-only demandée (tentative 3/3). Produit non en cause. |

| 2026-10-29 | `CoachTextDensityInstrumentationTest` **PASS 1/1** après correction du harnais (pluriel + hauteur de ligne). En cours : correction H CAP_COACH, suite complète G2+F2. |
| 2026-10-29 | G2 et F2 corrigés côté harnais, H corrigé CAP_COACH. Tout R5 intégré en `1daed73`. Suite complète finale lancée. |
| 2026-10-29 | AVD API 37 sur build R5 complet : 4 nouveaux tests **10/10 PASS** ; régression Coach/Plan/Focus/Launch/Sync **49/49 PASS** (AtlasPresentation, LotEUi, PlanScreen, PlanCalendarScroll, GoalFocusStart, FocusGauge, AtlasLaunch, SyncSettingsScreenCapture, CoachTextDensity, CoachInsights, CoachQuickActions, SecondLotTextDensity). |
| 2026-10-29 | Corrections d'intégration : onglet « Échanges » pour éviter le doublon « Conversation » ; restauration de « Dernière connexion »/« Identité d'appareil » dans la carte d'état relay. Harnais H/F2 durcis. SHA final `072424f`. |
| 2026-10-29 | **SHA final `072424f`** : JVM **743/743**, 105 suites ; lint/build/androidTest PASS ; AVD **49/49 PASS** + smoke APK final **15/15 PASS**. APK `dfe02a7f…`, androidTest `a3d63aed…`, signature `da301466…`. Pixel `BLOCKED_DEVICE`. Rapport et captures R5 produits. Revue indépendante à lancer. |
| 2026-10-29 | P2 de revue corrigés en `16ed841` : assurance E2E relay, test Plan vide, assertion « Données insuffisantes », LF. Suite finale **743/743**, AVD **50/50 PASS**, APK `605b9454…`, androidTest `3816473f…`. Verdict revue **GO_WITH_LIMITATIONS**, aucun P0/P1. |
| 2026-10-29 | Pixel reconnecté (`Pixel 9 Pro XL`, API 37). Pré-vol : HOME ATLAS inchangé, signature installée `da301466…`. Sauvegarde app-only `~/atlas-backups/r5/pixel-app-backup-pre-r5.tar` (SHA-256 `8cfed885…`), manifestes pré/post hors dépôt. `adb install -r` de l'APK `605b9454…`, aucun `pm clear`/uninstall. Données ATLAS identiques ; seuls `profileInstalled` et le `-shm` WorkManager changent. Parcours launcher/Accueil/Plan Aujourd'hui-Demain/Coach/Synthèses/Connexion/hors ligne PASS ; mode Relais restauré. Captures expurgées dans `docs/captures/v6-3-r4/apres-pixel/`. |
| 2026-10-29 | **Push final effectué** : `566668a..f16bf12` sur `main` (code validé `16ed841`, APK `605b9454…`, validation Pixel ciblée, revue GO_WITH_LIMITATIONS). Docs de clôture poussées dans le commit suivant. |
