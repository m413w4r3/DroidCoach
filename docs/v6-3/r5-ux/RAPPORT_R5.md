# ATLAS V6.3 — Rapport R5 « Coach & densité textuelle »

- **Date** : 2026-10-29
- **Branche** : `feat/atlas-v6-3-ux-daily-use`
- **Base `origin/main`** : `566668ac9cd0778f43d7f4d90a93c6a45645935c`
- **SHA final intégré** : `16ed8415972fbf884547a01815e0cc04c6af368c`
- **Statut** : R5 code, tests JVM/lint/build et recette AVD terminés ; **Pixel physique absent** ; **aucun push**.

## 1. Livré

| Ticket | Résultat |
|---|---|
| **F** — régime textuel Coach/Contexte | Textes raccourcis, catégories de contexte repliées derrière `Détails des catégories`, avertissements de partage/suppression conservés, JSON derrière les toggles existants. |
| **F2** — 2ᵉ lot textuel | Plan : note de stockage sous `Détails` ; Relay : intro courte, jeton et identifiants techniques dans `Avancé`, état humain + dernière connexion + identité visibles ; Sync : relais sous `Configuration avancée` ; Paramètres et Sport raccourcis. |
| **G1** — agrégateur Insights | API JVM pure, déterministe, sans Android/réseau ; seuils jour/semaine/mois ; sessions annulées exclues du temps Focus terminé ; tests de fuseaux, DST, seuils et ordre stable. |
| **G2** — onglet Synthèses | Sous-onglet `Échanges · Synthèses` dans Coach ; `Jour · Semaine · Mois` ; `Données insuffisantes` explicite avec raison ; détails repliés ; sources locales uniquement. |
| **H** — actions Coach simplifiées | `Organiser ma journée`, `Planifier un objectif`, `Focus prêt` ; formulaires courts pré-remplis ; confirmation explicite avant chaque effet ; réutilisation de `sendAgentMessage`, `createGoal`, `startFocus` ; actions masquées si le Coach est désactivé. |
| **I** — jauge Focus | Jauge circulaire de phase, compte à rebours central, sémantique `progressBarRangeInfo`, texte secondaire replié, pause/reprise/annuler/terminer et urgence conservés. |

## 2. Preuves

| Élément | Résultat |
|---|---|
| JVM `:app:testDebugUnitTest --rerun-tasks` | **743 tests, 0 échec, 0 erreur, 0 ignoré, 105 suites** (SHA `16ed841`) |
| `:app:lintDebug` | **PASS** |
| `:app:assembleDebug` | **PASS** |
| `:app:assembleDebugAndroidTest` | **PASS** |
| AVD API 37 — nouveaux tests R5 | **11/11 PASS** (`CoachTextDensity`, `CoachInsights`, `CoachQuickActions`, `SecondLotTextDensity` avec le test Plan vide) |
| AVD API 37 — régression + R5 | **50/50 PASS** (`AtlasPresentation`, `LotEUi`, `PlanScreen`, `PlanCalendarScroll`, `GoalFocusStart`, `FocusGauge`, `AtlasLaunch`, `SyncSettingsScreenCapture`, + 4 nouveaux) |
| AVD API 37 — smoke APK final | **50/50 PASS** sur l'APK final exact |
| Pixel physique | **PASS ciblé** le 2026-10-29 — Pixel 9 Pro XL, API 37, APK `605b9454…`, `adb install -r`, données préservées |

### Identité APK finale

- APK debug SHA-256 : `605b9454ee77b1fe2fdef5ef71add41d5aa61055162b29e6cfdabd19a9e0bc07`
- APK androidTest SHA-256 : `3816473f88949b765d2ff8db3cf1c441de58c3a5e533551eefbec342a29f9f96`
- Signature debug : certificat SHA-256 `da3014662eb65e50a87ef364a2e583c331398795b222e3b37b1a16553a00eeee`
- `applicationId` : `dev.atlas.coach`, versionCode 1, versionName 0.1.0

## 3. Captures

- `docs/captures/v6-3-r5/01-focus-gauge.png` — jauge Focus en cours.
- `docs/captures/v6-3-r5/02-coach-quick-actions.png` — onglet Coach, actions rapides, conversation.
- `docs/captures/v6-3-r5/03-coach-insights.png` — Synthèses Jour.
- `docs/captures/v6-3-r5/04-coach-day-form.png` — formulaire journée pré-rempli.
- `docs/captures/v6-3-r5/05-coach-confirmation.png` — confirmation explicite avant envoi.
- Appareil : AVD API 37, données synthétiques, aucun compte, aucun jeton, aucun `serverId`, aucune capture du tiroir d'applications.

## 4. Décisions

1. **Insights locaux déterministes** : aucune permission, aucun réseau, aucune interprétation distante en R5.
2. **Sous-onglet Coach** plutôt qu'un 6ᵉ onglet bas.
3. **Seuils** : jour sans donnée, semaine < 3 jours, mois < 7 jours → `Données insuffisantes`.
4. **Onglet « Échanges »** pour éviter le doublon visuel avec le titre `Conversation` de l'écran.
5. **Jeton d'appairage dans `Avancé`**, mais dernière connexion et identité d'appareil restent visibles dans la carte d'état.
6. **Régime textuel** : aucun consentement, permission, révocation ou confirmation supprimé ; les textes longs sont raccourcis ou déplacés au moment de l'action.

## 5. Limites

- **Validation Pixel ciblée** : installation non destructive et parcours launcher/Accueil/Plan/Demain/Coach/Synthèses/Connexion/hors ligne effectués. Permissions, notifications, localisation, mobilité, rotation et TalkBack complet ne sont pas rejoués sur Pixel.
- **R4 Pixel** est désormais validé pour les parcours ciblés ; la sauvegarde R5 `~/atlas-backups/r5/pixel-app-backup-pre-r5.tar` et les manifestes pré/post sont hors dépôt.
- **Insights** : lecture des plans passés sur le thread de composition (fichiers locaux de petite taille) ; une optimisation reste possible si la liste devient longue.
- **Zéros factuels** : une période avec un document local mais aucune action affiche des zéros dérivés ; l'état `Données insuffisantes` est couvert par les tests pour les périodes réellement vides.
- **AVD seulement** : TalkBack complet, rotation et police 2× sur Pixel ne sont pas rejoués ; la police 2× et le paysage restent couverts par les suites existantes.
- **Aucun push** : `origin/main` reste à `566668a`.

## 6. Suite

1. ~~Reconnecter le Pixel, installer l'APK final, rejouer les parcours et capturer les écrans expurgés.~~ Fait le 2026-10-29.
2. ~~Revue indépendante finale.~~ Fait : GO_WITH_LIMITATIONS, aucun P0/P1, P2 corrigés.
3. Push sur `main` après validation Pixel + revue + GO ; le propriétaire a pré-autorisé le push final.

## 7. Revue indépendante

- **Session** : nouvelle session `codex:gpt-6-luna`, `xhigh`, vérifiée dans les métadonnées.
- **Worktree** : `review/r5-final-a39a19f`, `chmod -R a-w`, aucun ADB, aucune écriture.
- **Verdict** : **GO_WITH_LIMITATIONS** — aucun P0/P1.
- **P2 relevés et traités** :
  1. Assurance de chiffrement du relais raccourcie → ligne explicite restaurée : « Relais chiffré de bout en bout ; FastAPI reste sur le PC, sans VPN. »
  2. Note de stockage du Plan non couverte après correction du harnais → test dédié `emptyPlanKeepsStorageNoteBehindDetails`.
  3. Test Insights trop permissif sur l'état vide → assertion directe de `Données insuffisantes` et de sa raison.
  4. Fins de ligne CRLF des tickets → normalisées en LF ; `git diff --check` PASS.
- **Limites maintenues** : Pixel physique `BLOCKED_DEVICE`, aucune revendication de qualification Pixel ; lecture des plans passés sur le thread de composition ; AVD uniquement.

## 8. Validation Pixel du 2026-10-29

- Appareil : Pixel 9 Pro XL (`komodo`), Android 17 / API 37, HOME ATLAS inchangé.
- Installation : `adb install -r` de l'APK `605b9454…`, sans `pm clear`, sans désinstallation, sans reset.
- Données : sauvegarde app-only `~/atlas-backups/r5/pixel-app-backup-pre-r5.tar` (SHA-256 `8cfed885…`) et manifestes pré/post hors dépôt ; tous les fichiers ATLAS sont identiques après installation, seuls `profileInstalled` et le `-shm` WorkManager changent.
- Parcours : launcher → Plan/Coach, Accueil, Plan Aujourd'hui/Demain, Coach, Synthèses (`Données insuffisantes`), Connexion relayée, mode Hors ligne puis restauration Relais (`Connecté`).
- Captures : `docs/captures/v6-3-r4/apres-pixel/` (expurgées).
- Non rejoué sur Pixel : permissions, notifications, localisation, mobilité, rotation, TalkBack complet, backend relayé réel.
