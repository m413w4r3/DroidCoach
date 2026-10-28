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
| `R5-F` | Régime textuel Coach + Contexte | `AgentCoachScreen.kt`, `CoachContextScreen.kt` | 1 | READY |
| `R5-G1` | Agrégateur Insights JVM pur | nouveaux `insights/*.kt` + tests | 1 | READY |
| `R5-I` | Jauge Focus + texte | `FocusRoutinesScreen.kt`, nouveau `FocusGauge.kt` + tests | 1 | READY |
| `R5-F2` | Régime textuel 2ᵉ lot | `PlanScreen.kt`, `SyncSettingsScreen.kt`, `RelaySettingsScreen.kt`, `SportScreen.kt`, `AtlasScreens.kt` (SettingsScreen uniquement) | 2 | READY (attend F terminé pour cohérence de style) |
| `R5-G2` | UI Insights + onglet Coach | nouveaux `CoachInsightsScreen.kt`, `CoachTabScreen.kt`, `AtlasApp.kt`, `AtlasScreenId.kt`, `PlanController.kt` | 2 | BLOCKED_BY G1 |
| `R5-H` | Actions Coach simplifiées | `AgentCoachScreen.kt`, nouveau `CoachQuickActions.kt` + tests | 2 | BLOCKED_BY F |

## Gates d'intégration

- `git diff --check`, diff borné, aucun secret ni donnée personnelle.
- JVM `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug`, `:app:assembleDebugAndroidTest` sur le SHA intégré.
- Instrumentation ciblée sur AVD API 37 ; **jamais** la suite Android 14.
- Captures AVD avec données synthétiques, puis Pixel uniquement si reconnecté et avec accord.
- Revue indépendante finale : nouvelle session Luna XHigh en lecture seule technique.
- Aucun push avant validation Pixel, revue indépendante et GO.

## Journal

| Heure (locale) | Événement |
|---|---|
| 2026-10-29 | Pré-vol : Git propre, HEAD `e12a8bf`, Pixel absent (`BLOCKED_DEVICE`), AVD API 37 disponible. Registre ouvert. |
