# Lot S3b — Corrections après revue S5

Date : 2026-10-18  
Périmètre : `android/**`, ce rapport, la section S5 de `VALIDATION_VM.md`, les captures `vm1b-*` et les décisions automatiques associées.

## Constats et verdicts

### P0 — Restauration mal formée et version de schéma

**Vérifié par repro/test.** La revue signalait un crash plausible avec `policy.quietStart` invalide ou une ouverture récente sans `eventId`. Les décodeurs étaient non transactionnels et `schemaVersion` n'était pas contrôlée. Les tests JVM reproduisent maintenant des sections policy/rules/focus invalides; le parcours instrumenté vérifie l'ouverture de l'application, l'isolement du fallback et la copie brute en quarantaine. Une version inconnue est testée séparément et traitée comme une donnée non prise en charge. La construction du contrôleur ne laisse pas sortir ces erreurs de décodage.

Correction : lecture séparant `Missing`, état valide et corruption; validation de `schemaVersion`; validation transactionnelle par sous-état, valeur de secours limitée au sous-état invalide, puis quarantaine. Le blob brut est copié sous une clé dédiée avant toute écriture de remplacement. Si cette copie échoue, l'original n'est pas écrasé.

### P1 — JSON invalide confondu avec première installation

**Vérifié par repro sur émulateur.** Après insertion de `{broken` dans `state_v1` via `run-as`, l'application s'est relancée sur Accueil. Elle a affiché : « Certaines données locales ont été écartées (JSON local invalide). Une copie brute a été mise en quarantaine; les valeurs de secours sont actives. » Le XML des SharedPreferences contient le texte brut `{broken` sous `state_quarantine_*` et la raison `JSON local invalide`. La capture correspondante est relue dans [vm1b-corruption.png](../captures/vm1b-corruption.png).

Correction : distinguer clé absente et JSON corrompu; afficher un message non bloquant; ne remplacer les valeurs que si la mise en quarantaine a abouti.

### P1 — Désactivation de capacités

**Vérifié par test JVM et test Compose connecté.** Habitudes désactivées, la confirmation de lien est refusée par le contrôleur et le bouton disparaît. Focus désactivé pendant une session, celle-ci passe en pause, les ticks/reprises sont refusés et le bouton Reprendre n'est pas exposé.

### P1 — Timer, visibilité et persistance

**Vérifié par test instrumenté et repro force-stop.** Le tick est attaché à l'Activity au stade `STARTED` et seulement si Focus est actif. Les transitions critiques sont persistées durablement; les checkpoints utilisent `apply()` au plus toutes les 20 secondes visibles, sans commit SharedPreferences chaque seconde. Les sessions restaurent le temps écoulé via l'horloge murale.

Sur l'émulateur, l'UIAutomator a lu `24:58`, puis après `am force-stop` et relance, `24:54`; le statut « Concentration · En cours » et le lien `Lis/avance 10 min` étaient toujours présents. L'écran Focus est [vm1b-focus.png](../captures/vm1b-focus.png). La durée de rattrapage après un arrêt très long reste bornée à 24 heures par le moteur.

### P1 — Sortie vers les réglages HOME

**Vérifié par clic UI et résolution système.** Le bouton « Choisir l'application d'accueil par défaut » ouvre `Settings.ACTION_HOME_SETTINGS`; sur l'AVD, l'action ouvre `com.android.permissioncontroller/.role.ui.DefaultAppActivity`. Après l'ouverture, la résolution de `MAIN`/`HOME` reste `android/com.android.internal.app.ResolverActivity`. La sélection d'ATLAS n'a pas été exercée afin de ne pas modifier le défaut HOME de l'AVD.

### P1 — `ACTION_VIEW` sans handler

**Vérifié par test JVM; comparaison émulateur exécutée.** La passerelle factice sans activité compatible renvoie `NO_COMPATIBLE_APP` avant le chooser. Le manifeste déclare la visibilité des activités `VIEW` HTTPS pour API 30+. En comparaison système, `adb shell am start -a android.intent.action.VIEW -d 'atlas-no-handler://example.invalid/path'` a répondu `Activity not started, unable to resolve Intent`. L'application n'accepte que des URI HTTPS et le navigateur de l'AVD est compatible : son chemin réel sans handler HTTPS demeure **NON TESTÉ** sur cet appareil.

### P2 — Rétention et libellés

**Vérifié par tests JVM.** Les IDs de déduplication expirent après la fenêtre de règle; les historiques d'interventions et le journal de coach sont limités à 200 entrées. Les statuts/étapes Focus et effets de coach ont des libellés français explicites; les derniers textes visibles « timer » et « cooldown » ont été remplacés par « compte à rebours » et « délai d'attente ».

## Validation finale

Commande build, tests unitaires et lint, depuis `android/` :

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --rerun-tasks
```

Résultat : **PASS**, `BUILD SUCCESSFUL in 13s`, 51 tâches exécutées. Les rapports XML comptent 44 tests JVM, 0 échec, 0 erreur et 0 test ignoré. Deux avertissements de cast non vérifié subsistent dans `PersistedRepositoriesTest`.

Commande instrumentée, depuis `android/` :

```sh
./gradlew :app:connectedDebugAndroidTest
```

Résultat : **PASS**, `BUILD SUCCESSFUL in 43s`, 6 tests sur `Pixel_9_Pro_XL_API_30_2(AVD) - 11`, 0 échec et 0 ignoré. Le parcours vérifie les voyages UI principaux, les flags, les sections mal formées, la version inconnue et un checkpoint Focus après 20 secondes visibles.

APK debug final : `android/app/build/outputs/apk/debug/app-debug.apk`, 9 660 087 octets, SHA-256 `24c48f833b15ef66cf2ca95bda92b624a3917e0b2193c973f2b6acecab32ebf2`. Build, journaux, commandes ADB et captures sont détaillés dans [VALIDATION_VM.md](../VALIDATION_VM.md), section « Après revue S5 ».

## Limites

- Validation sur l'AVD `emulator-5554`, Android 11/API 30, sans appareil Pixel réel.
- Le scénario ACTION_VIEW sans handler est couvert par la passerelle factice JVM et la résolution système d'un schéma inconnu; l'absence d'un handler HTTPS dans l'application n'a pas pu être créée sur cet AVD.
- Aucun launcher par défaut de l'AVD n'a été changé.
