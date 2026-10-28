# ATLAS V6.3 — Handoff R4 → chantier « Coach & densité textuelle »

- **Date** : 2026-10-29
- **Parent sortant** : session DeepSeek Flash max (cette session)
- **Parent entrant demandé** : nouvelle session **DeepSeek Flash max**
- **Enfants** : **tous** `codex:gpt-6-luna`, effort `xhigh`, sans sous-délégation
- **Branche courante** : `feat/atlas-v6-3-ux-daily-use`
- **HEAD** : `7d7b5f5` (`fix(plan): keep tomorrow activities ahead of today's clock`)
- **Base `origin/main`** : `566668ac9cd0778f43d7f4d90a93c6a45645935c` — **aucun push effectué**
- **État de travail** : code commité ; docs/captures/handoff à committer avec ce fichier.

## 0. Reprise immédiate

```sh
cd /home/nill/perso/AndroCoach/atlas-preparation
git status --short
git rev-parse HEAD          # doit être 7d7b5f5 ou le commit docs qui suit
git log --oneline 566668a..HEAD
adb devices -l              # le Pixel physique peut être reconnecté ou non
```

Lire dans cet ordre : `AGENTS.md`, `.agents/WORKFLOW.md`,
`docs/v6-3/r4-ux/AUDIT_UX_V63.md`, ce handoff, puis
`docs/captures/v6-3-r4/README.md`.

**Ne pas pousser sur `main`** tant que la validation Pixel et la revue
indépendante ne sont pas faites, sauf demande explicite du propriétaire.
Le propriétaire a autorisé le push final dans le prompt initial ; cet accord
reste valable, mais la validation physique est encore `NOT_RUN`.

## 1. Ce qui a été livré en R4

### Navigation et accueil

- Navigation basse à 5 onglets : `Accueil · Plan · Coach · Focus · Plus`.
- Accueil : date/heure, prochaine activité du plan, boutons
  `Parler au coach` / `Voir le planning` / focus rapide, directive compacte,
  habitudes cochables, notices fermables.
- `Plus` expose désormais `Habitudes`, `Partage backend` et `Connexion relayée`.
- Le bouton `Urgence` reste dans le cadre commun sur tous les écrans.

### Coach

- Statut humain : `Connecté`, `Connexion en cours…`, `Hors ligne`,
  `Connexion requise`, `Appairage nécessaire`, `Accès révoqué`,
  `Erreur de connexion`, `Mode local`.
- Conversation en tête, envoi clavier, brouillon conservé, source compacte
  (`OpenClaw` / `Local`), propositions séparées avec confirmation explicite.
- Objectifs, localisation, contexte de partage, tâches de fond et liens
  déplacés vers `Contexte du coach`.
- `Contexte du coach` est câblé dans `AtlasApp` (`AtlasScreenId.COACH_CONTEXT`).

### Planning

- Sélecteur `Aujourd'hui / Demain`, hero « Prochaine activité », actions
  distinctes `Ajouter une activité` et `Demander au coach`.
- Coach/Calendrier/Détails repliés sous `Options du planning`.
- Correctif : la vue `Demain` n'utilise plus l'heure courante d'aujourd'hui
  pour marquer les activités du matin comme « passées ».

### Connexion

- `RelaySettingsScreen` : état humain en tête, `PC appairé`, erreurs TLS /
  révocation visibles, identifiants (`URL`, `serverId`, clé X25519, UUID,
  jeton) dans `Avancé` replié.
- `SyncSettingsScreen` : statut et consentements visibles, configuration
  backend et purges dans `Configuration avancée`.
- `ConnectionStatusPresentation.kt` : vocabulaire partagé + tests JVM.

### Launcher HOME

- Correctif P0 : `LauncherActivity.openMainActivity()` n'utilise plus
  `FLAG_ACTIVITY_NEW_TASK` ; `REORDER_TO_FRONT | SINGLE_TOP`. Vérifié sur
  AVD API 37 : les raccourcis `Plan` et `Coach` ouvrent bien `MainActivity`.
- Footer `Apps · Plan · Coach · Pause · Plus`, bouton `Planifier ma journée` /
  `Voir le planning` sous la prochaine action, `Choisir mes applications`
  quand aucun favori.
- Tiroir : texte de fermeture raccourci.

### Écrans secondaires

- Sport : état humain unique, détails techniques repliés, sections séance /
  résultats conservées.
- Habitudes : doublon du bouton d'ajout supprimé.
- Mémoire : introduction raccourcie.

## 2. Preuves et tests

| Élément | Résultat |
|---|---|
| JVM `:app:testDebugUnitTest --rerun-tasks` | **720 tests, 0 échec, 0 erreur, 0 ignoré, 102 suites** |
| `:app:lintDebug` | **PASS** (SHA `7d7b5f5`) |
| `:app:assembleDebug` | **PASS** |
| `:app:assembleDebugAndroidTest` | **PASS** |
| AVD API 37 — groupe 1 | **33/33 PASS** (AtlasPresentation, PlanScreen, PlanCalendarScroll, LotE, HomeNextActionAccessibility, AtlasLaunch) |
| AVD API 37 — groupe 2 | **16/16 PASS** (LauncherPresentation, LauncherDrawerLazy, HomeLandscapeFooter, HabitsScreen, V61PortraitCapture, LauncherFavoriteOpen) |
| AVD API 37 — police système 2.0 | **PASS** (`V62NativeUiCaptureTest#systemFontScaleTwoKeepsEmergencyControlsAccessible`) |
| AVD API 37 — launcher P0 | **PASS manuel** : footer `Plan` → `Planning` ; footer `Coach` → `Coach` |
| Pixel physique | **NOT_RUN / BLOCKED_DEVICE** : appareil déconnecté avant installation du nouvel APK |

### Identité APK finale

- APK debug : SHA-256 `c3c0f2ff0fa5bd7eb14d94231dfd71b70a432f92cd5bfd9123e102b3dab1f160`
- APK androidTest : SHA-256 `c6c3f922f4ff3edadd423789157287fee3f4e225e36b935fddae93db95eda25e`
- Signature : `CN=Android Debug`, certificat SHA-256
  `da3014662eb65e50a87ef364a2e583c331398795b222e3b37b1a16553a00eeee`
- `applicationId` : `dev.atlas.coach`, versionCode 1, versionName 0.1.0

### Pixel — état et sauvegarde

- Pixel 9 Pro XL, Android 17 / API 37.
- Rôle HOME : `dev.atlas.coach` (activé par le propriétaire ; ne pas modifier).
- Sauvegarde app-only : `~/atlas-backups/r4/pixel-app-backup-pre-r4.tar`,
  mode 0600, SHA-256
  `30cca099a1498cc9c6afcf85f965ddd61aab640ddf0b1fab8e404cdabdbe050c`.
- Empreintes pré-installation : `~/atlas-backups/r4/pixel-app-files-preinstall.sha256`.
- **Le Pixel s'est déconnecté avant l'installation du nouvel APK.** Aucun
  `pm clear`, aucune désinstallation, aucun test physique final.

### Revue indépendante

**Pas encore faite.** À lancer sur le SHA final après reconnexion Pixel ou,
au minimum, sur `7d7b5f5` + commit docs. Reviewer : **nouvelle** session
`gpt-6-luna / xhigh`, worktree séparé, `chmod -R a-w`, vérification
`git status`/HEAD après exécution. Aucun P0/P1 ouvert avant push.

## 3. Captures

- `docs/captures/v6-3-r4/avant/` : Pixel physique, code `566668a`, expurgé
  (bulle Signal et UUID masqués).
- `docs/captures/v6-3-r4/apres/` : AVD API 37, 360 dp, code final, données
  synthétiques. Aucune donnée personnelle.
- Captures Pixel « après » : **à refaire** après reconnexion, avec le même
  protocole d'expurgation.
- Le tiroir d'applications du Pixel n'est pas publié (noms d'applications
  personnelles).

## 4. Nouvelles entrées propriétaire (à traiter en priorité)

Le propriétaire juge qu'il reste **beaucoup trop de texte** dans les captures.
Exemples cités : la page `Conversation` du Coach n'est pas ergonomique,
`Contexte du coach` contient beaucoup de texte inutile.
**« Pas besoin de tout expliquer. »**

### 4.1 Régime textuel / densité

- Réduire les paragraphes explicatifs au profit d'états, de titres et d'actions.
- Appliquer une divulgation progressive : une ligne par défaut, détail sur
  demande.
- Cibles prioritaires : `AgentCoachScreen`, `CoachContextScreen`, `PlanScreen`,
  `SyncSettingsScreen`, `RelaySettingsScreen`, `SettingsScreen`, `SportScreen`.
- Ne pas supprimer les explications de sécurité, de consentement, de
  permission, de révocation et de confirmation ; les déplacer au bon moment
  (avant l'action) ou derrière un détail.
- Livrable : audit écran par écran « à supprimer / réduire / déplacer », puis
  implémentation par petits lots.

### 4.2 Page de synthèses / avis du Coach

- Ajouter une page, sous-page ou onglet dans le Coach pour afficher des
  synthèses et avis sur **la journée, la semaine, le mois passé**.
- Objectifs : évaluer les actions passées, les progrès, les défauts récurrents,
  les patterns.
- Sources locales disponibles à inspecter : `FocusRepository` / sessions,
  `Habits`, `PlanController` / plans par date, `GoalRepository`,
  `MemoryController`, `AgentEffectRecord`, `UsageSummary` si consenti.
- Afficher `Données insuffisantes` si la période ne contient pas assez de
  données ; ne jamais inventer une tendance.
- Commencer par une synthèse **locale déterministe** (règles et agrégats),
  puis optionnellement une interprétation distante via le coach existant.
  Aucune nouvelle permission, aucun nouvel accès silencieux.

### 4.3 Actions du Coach plus simples

- Rendre les appels au coach pour :
  - organiser la journée ;
  - planifier un objectif avec contraintes ;
  - organiser des temps de focus pré-remplis.
- Attendu : raccourcis/actions visibles, formulaires pré-remplis, peu de
  texte, confirmation explicite avant effet.
- Réutiliser les contrôleurs existants (`PlanController`, `GoalRepository`,
  `FocusPlan`) ; ne pas dupliquer la logique ni créer de nouvelles permissions.

### 4.4 Jauge de temps Focus

- Ajouter une jauge de temps (progression circulaire ou équivalent) pour la
  séance de focus en cours.
- Réduire le texte autour : tâche, temps restant, phase, actions.
- Conserver pause, reprise, annulation, fin et la pause d'urgence.

## 5. Tickets proposés pour le prochain chantier

> Maximum 3 implémentations simultanées, 6 enfants directs au total, aucune
> sous-délégation. Un seul propriétaire ADB/Pixel. Worktrees séparés.

### Ticket F — Régime textuel Coach & Contexte (P0 UX)

- **But** : supprimer les paragraphes qui répètent l'interface ; garder les
  consentements et confirmations au bon moment.
- **Fichiers pressentis** : `AgentCoachScreen.kt`, `CoachContextScreen.kt`,
  puis `PlanScreen.kt` / `SyncSettingsScreen.kt` / `RelaySettingsScreen.kt`.
- **Oracle** : liste avant/après des textes supprimés/déplacés ; captures ;
  tests JVM/lint/build ; vérifier qu'aucun consentement, refus, révocation ou
  confirmation n'a été masqué.

### Ticket G — Coach Insights (jour / semaine / mois)

- **But** : page de synthèse et d'avis fondée sur les données locales.
- **Fichiers pressentis** : nouveau `CoachInsightsScreen.kt`,
  `AgentCoachScreen.kt` (lien/onglet), agrégateurs JVM purs.
- **Oracle** : tests JVM des agrégats (périodes vides, données partielles,
  séries, patterns) ; instrumentation de la page ; aucun chiffre inventé ;
  `Données insuffisantes` explicite.

### Ticket H — Actions Coach simplifiées

- **But** : organiser la journée, planifier un objectif avec contraintes,
  lancer un focus pré-rempli en peu de gestes.
- **Fichiers pressentis** : `AgentCoachScreen.kt`, nouveaux composants
  d'action, formulaires pré-remplis ; réutilisation des contrôleurs.
- **Oracle** : parcours instrumentation ; aucun effet sans confirmation ;
  tests des contraintes et des valeurs pré-remplies.

### Ticket I — Jauge Focus + réduction de texte

- **But** : jauge de progression pour la séance en cours, texte minimal.
- **Fichiers pressentis** : `FocusRoutinesScreen.kt`, `AtlasScreens.kt`
  (`FocusScreen`), éventuellement `AtlasDesign.kt`.
- **Oracle** : instrumentation à 100 %, 50 %, pause ; police 2× ; pause
  d'urgence toujours accessible.

## 6. Orchestration du prochain chantier

- **Parent** : nouvelle session **DeepSeek Flash max** (celle qui reprend ce
  handoff). Le parent découpe, délègue, vérifie, intègre localement, sollicite
  les accords et tient le registre.
- **Enfants** : `codex:gpt-6-luna`, `xhigh`, sans exception, sans fallback.
  Vérifier `model`, `thinkingOptionId` et `effectiveThinkingOptionId` dans les
  métadonnées de chaque session.
- **Aucune sous-délégation** : chaque enfant reçoit un ticket borné.
- **Maximum 6 enfants directs**, dont **3 implémentations** simultanées.
- **Un fichier = un propriétaire** ; worktrees séparés ; le parent seul
  intègre.
- **Un seul opérateur ADB/Pixel/AVD** : le parent.
- **Notifications événementielles uniquement** ; jamais de boucle `sleep` ni de
  polling de statut.
- **Revue indépendante** : nouvelle session Luna XHigh, lecture seule
  technique (`chmod -R a-w` + contrôle Git).
- **Pas de push** avant validation Pixel, revue indépendante et accord
  explicite ; le propriétaire a déjà autorisé le push final, mais la porte de
  validation reste ouverte.

## 7. Décisions ouvertes à trancher

1. **Push R4** : pousser `7d7b5f5` + docs sur `main` immédiatement, ou après
   validation Pixel ? Recommandation : après reconnexion Pixel + revue.
2. **Insights** : agrégats locaux uniquement, ou interprétation distante via
   OpenClaw ? Recommandation : local déterministe d'abord ; distant seulement
   avec consentement et capability existants.
3. **Emplacement Insights** : sous-onglet dans `Coach` ou 6e onglet bas ?
   Recommandation : sous-onglet / segmented control dans `Coach` pour ne pas
   surcharger la barre basse.
4. **Périmètre du régime textuel** : Coach + Contexte d'abord, puis Plan /
   Connexion / Paramètres dans un second lot.

## 8. Journal des sessions R4

| Session | Rôle | Modèle vérifié | Résultat |
|---|---|---|---|
| `2a0c75b9…` | Scout lecture seule | gpt-6-luna / xhigh | rapport, aucun write |
| `aa9dd88c…` | A — navigation/accueil | gpt-6-luna / xhigh | commit `d2dd2ea` |
| `1bbc14bb…` | B — Coach/Contexte | gpt-6-luna / xhigh | commit `cc549c1` |
| `dbb93d03…` | C — Plan aujourd'hui/demain | gpt-6-luna / xhigh | commit `b6722cc` |
| `aa4c4916…` | D — Connexion | gpt-6-luna / xhigh | commit `0838d6a` |
| `fe7c80ba…` | E — Launcher/écrans secondaires | gpt-6-luna / xhigh | commit `30151d3` |

Les commits ci-dessus ont été intégrés par le parent, puis complétés par
`eba32ad`, `5dcd933`, `f029bac` et `7d7b5f5`.

## 9. Checklist de reprise

- [ ] Vérifier `git status`, `HEAD`, `origin/main` et l'absence de push.
- [ ] Lire AGENTS/WORKFLOW/audit/handoff.
- [ ] Vérifier `adb devices -l` et reconnecter le Pixel si possible.
- [ ] Vérifier la sauvegarde `~/atlas-backups/r4/pixel-app-backup-pre-r4.tar`
      et son SHA.
- [ ] Installer l'APK final sur le Pixel **seulement** après accord et avec
      `adb install -r` (jamais `pm clear`/`uninstall`).
- [ ] Rejouer les parcours : launcher → Plan/Coach, accueil, plan,
      coach, connexion, hors ligne, retour HOME.
- [ ] Capturer les écrans Pixel « après » et les expurger.
- [ ] Revue indépendante Luna XHigh en lecture seule.
- [ ] Mettre à jour `docs/v6-3/r4-ux/RAPPORT_UX_V63.md` avec les preuves
      Pixel et le verdict.
- [ ] Commit docs/captures, puis push sur `main` si GO.
- [ ] Lancer le chantier F/G/H/I avec des tickets séparés et les mêmes gates.
