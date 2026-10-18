# 09 — Plan concret de délégation, dépendances et recette sur émulateur

**Portée** : VM-0 et VM-1 Android en priorité. ATLAS Core après preuve d'un APK local. Conserver un historique de commandes/tests *effectivement* exécutés.

## Contrat de base figé avant parallélisation

### Packages / responsabilités proposés

```
android/
  settings.gradle.kts, build.gradle.kts, gradle/, gradlew
  app/
    src/main/java/<package-atlas>/
      app/             # Activity, thème, navigation, DI manuelle, rôle HOME
      coach/           # modèle et écran directive + réponses
      focus/           # minuteur et son moteur de transitions
      rules/           # compteur d'ouvertures, cooldown et exceptions
      habits/          # modèle et CRUD local
      content/         # 3 liens démo + ACTION_VIEW
      data/            # persistance et export (propriétaire unique)
      settings/        # mandat, pause, consentements
    src/test/...       # tests unitaires par package
    src/androidTest/...# UI smoke tests si SDK/AVD
backend/               # absent ou minimal jusqu'à VM-1 ; créé ensuite
contracts/             # schémas versionnés existants
scripts/               # commandes de validation reproductibles
```

**Ne pas créer sept modules Gradle au premier jour.** Un seul module `:app` avec packages séparés est suffisant pour VM-1 ; extraction en modules plus tard si elle résout un problème réel. Le répertoire exact `<package-atlas>` doit être fixé une fois par le responsable Android (par exemple `dev.atlas.coach`, à ajuster selon l'existant).

### Interfaces Kotlin minimales

```kotlin
interface CoachRepository {
    suspend fun currentDirective(): Directive
    suspend fun respond(id: String, choice: DirectiveChoice): DirectiveResult
}
interface FocusRepository {
    suspend fun start(durationSeconds: Long, directiveId: String?): FocusSession
    suspend fun pause(id: String): FocusSession
    suspend fun resume(id: String): FocusSession
    suspend fun finish(id: String): FocusSession
    suspend fun cancel(id: String): FocusSession
}
interface HabitRepository {
    suspend fun list(): List<Habit>
    suspend fun add(name: String, schedule: HabitSchedule): Habit
    suspend fun checkIn(habitId: String, date: LocalDate): HabitCheckIn
    suspend fun undoCheckIn(habitId: String, date: LocalDate)
}
interface RuleEvaluator {
    fun evaluate(event: AppOpenEvent, policy: LocalPolicy): Intervention?
}
```

Ces signatures servent de **contrat de coordination**, pas de code imposé identique à la lettre. Si l'agent choisit un résultat scellé (`Result<T>`) ou une autre structure, figer son choix avant de lancer des lots concurrents. Les entités utilisent des IDs stables, des timestamps UTC et un état local persistant. Pas de navigation ou de logique Android framework dans les évaluateurs purs.

## Découpage en sessions Nimbalyst

| Lot | Délégation et fichiers autorisés | Dépend de | Preuve de réception |
|---|---|---|---|
| **S0 — Fondations** | `android/` fichiers Gradle, wrapper et manifest, `app/` Activity minimale, `app/app/` navigation simple, `scripts/`, `docs/VALIDATION_VM.md` ; seul propriétaire des fichiers globaux Gradle/Manifest. | SDK disponible ou diagnostic | `assembleDebug` réel ; `adb install`/lancement si AVD. Capture de VM-0 ou statut NON TESTÉ. |
| **S1 — Focus + règles** | Seulement `android/app/src/main/java/<pkg>/{focus,rules}/**` et chemins tests correspondants. Pas de manifest, Gradle, navigation. | S0 + interfaces stables | Tests 25/5 et 10 min, pause/reprise/fin, séquence 4/25, cooldown 15, doublons, horaires calmes, urgence. |
| **S2 — Habitudes + coach + contenu** | Seulement `<pkg>/{habits,coach,content}/**` et tests correspondants ; stockage temporaire via interfaces si propriétaire data absent. Ne pas modifier nav, Gradle, manifest. | S0 + interfaces stables | Tests des réponses directive, habitudes, correction, contenus limités à 3, ouverture URL par intent/fallback (test instrumenté si possible). |
| **S3 — Intégration UI/local** | Un seul agent désigné intègre composants S1/S2, navigation, écran d'accueil style Olauncher, paramètres/consentements, persistance et export ; **reprend seul les fichiers globaux**. | S1 + S2 terminés | Chemin utilisateur VM-1 sans serveur, sans permissions système requises, capture après installation ADB. |
| **S4 — Backend léger** | `backend/**`, tests backend, exemples API, documentation d'installation serveur (pas de fichiers Android). | Contrats JSON existants ; peut démarrer lorsque S3 est en validation, mais VM-1 prioritaire | Pytest, OpenAPI, idempotence events, refus hors mandat, mock agent. Pas de fausse intégration OpenClaw. |
| **S5 — Revue indépendante** | Lecture seule des changements et tests en priorité ; corrections dans un lot dédié après accord de l'orchestrateur. | S3 et éventuellement S4 | Liste courte de bugs vérifiés avec fichier/ligne et repro ; re-tests. |

**Concurrence conseillée :** `S0` seul (bloqueur) → `S1` et `S2` en parallèle (**seulement si** interfaces figées et isolation disponible) → `S3` → `S4` et `S5`. Au maximum 2–3 agents actifs. Surtout, ne pas laisser deux sessions écrire simultanément le manifest, les fichiers Gradle, la navigation ou `data/`.

## Instructions pour chaque spawn_session

L'orchestrateur doit :
1. Découvrir **le schéma exact** de `spawn_session` dans Nimbalyst. Employer le modèle `openai-codex:gpt-6-luna`, l'effort `xhigh` et la notification activée **si ces valeurs sont acceptées** ; sinon journaliser précisément la limitation et demander un ajustement seulement si elle est bloquante.
2. Fournir à la session : lot, chemins d'écriture exclusifs, dépendances/contrats, critères d'acceptation, livrables et fichiers interdits ; rappeler de ne pas toucher aux fichiers d'autrui.
3. Utiliser **worktrees dédiés** si disponibles. Sinon sérialiser les écritures conflictuelles. Ne pas merger aveuglément ; vérifier diff/status et tests.
4. Utiliser les outils de suivi Nimbalyst (`list_spawned_sessions`, `get_session_status`, `get_session_result`, `send_prompt`, etc.) avec leurs **vraies signatures**. Demander des corrections sur échec, reprendre les résultats réels.
5. Sur chaque lot, obtenir : fichiers changés, commandes exécutées, tests PASS/FAIL/NON TESTÉ, risques et commande suivante. Aucune « validation théorique » ne vaut un build lancé.

## Jalon VM-0 (à atteindre le plus tôt possible)

- Projet Gradle Kotlin/Compose minimal, Activity affiche `ATLAS` et un bouton visible.
- Si SDK et AVD disponibles : compilation `assembleDebug`, installation APK par `adb`, Activity ouverte, screenshot réel, logcat sans crash au démarrage.
- Conserver `docs/VALIDATION_VM.md` avec l'API level de l'AVD, les commandes et le chemin de capture.
- Si pas d'AVD ou de SDK : **NON TESTÉ** (avec cause exacte, commandes possibles, sans prétendre que l'APK a été exécuté).

## Jalon VM-1 (application locale ; sans IA ni serveur)

Cas d'essai ordonné à documenter après un vrai lancement :

1. **Accueil** : consigne prioritaire « Lis/avance 10 min » et justification affichées ; raccourcis essentiels accessibles ; thème minimaliste inspiré d'Olauncher.
2. **Directive** : `Commencer` déclenche un focus ; `Discuter`, `Alternative`, `Reporter`, `Pause` produisent chacune un résultat visible et journalisé, sans backend.
3. **Pomodoro** : preset 25/5, alternatif 10 ; mode démo à durée courte pour tests ; pause/reprise/annulation/fin ; reprise après recréation d'Activity, puis test de redémarrage si réalisable.
4. **Habitudes** : créer une habitude, cocher, annuler, consulter historique ; relier une session accomplie à une habitude **uniquement** après confirmation ou pré-engagement configuré.
5. **Règles** : injecter quatre ouvertures simulées d'une app dans 25 min → une intervention maximum ; vérifier doublon, cooldown 15 min, fenêtre de repos, pause, exceptions ; aucune requête LLM au déclenchement.
6. **YouTube** : trois liens démo au maximum ; ouvrir par `ACTION_VIEW`, laisser Android choisir un client compatible ; aucune promesse sur ReVanced installé dans l'AVD.
7. **Mode avion / aucune permission** : app reste utile, timer et habitudes continuent, données retrouvées après relancement.
8. **Sortie** : mode HOME facultatif, retour aux paramètres / launcher système ; pause d'urgence 30 min toujours accessible ; pas de contrôle des appels, paramètres ou authentification.
9. **Preuves** : tests unitaires, APK chemin réel, deux screenshots si possible (accueil et focus), logcat, verdict PASS/FAIL/NON TESTÉ par scénario.

**Événements simulés ≠ observation réelle des apps.** Le test de `UsageStatsManager` sur Pixel réel est un jalon distinct. `AccessibilityService`, Device Owner, root, patches ReVanced et blocages durs restent exclus de VM-1.

## Préparation locale et exemple de validation (commandes indicatives)

```bash
# Le chemin de l'IDE n'est pas celui du SDK.
test -x /opt/android-studio/bin/studio.sh && echo 'IDE présent'
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
test -x "$SDK/platform-tools/adb" && "$SDK/platform-tools/adb" devices -l
test -x "$SDK/emulator/emulator" && "$SDK/emulator/emulator" -list-avds
# Depuis le projet Gradle réellement généré (dans android/) :
# ./gradlew :app:assembleDebug
# "$SDK/platform-tools/adb" install -r <chemin-APK-reel>
# "$SDK/platform-tools/adb" shell am start -n <package>/<activity-reels>
# "$SDK/platform-tools/adb" exec-out screencap -p > docs/captures/vm1-accueil.png
```

**Ne pas exécuter les lignes avec placeholders tels quels** ; découvrir les chemins dans le build et le manifest. Ne pas inventer de captures et ne jamais effacer les AVD de l'utilisateur.
