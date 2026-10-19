# ATLAS V6 — Recette du lot F

Date de recette : 2026-10-19. Checkout `feat/atlas-v6-openclaw`, AVD Android 17
`emulator-5554`. Aucun téléphone physique n'a été utilisé.

## Résultats réels — OpenClaw et Android

La commande `./scripts/v6-e2e-real.sh` a réussi avec le Gateway OpenClaw local
2026.10.5 (`agent=atlas`, chat completions, outils refusés), un backend FastAPI
configuré `ATLAS_AGENT_ADAPTER=openclaw` et l'AVD Android 17. Le script n'a pas
modifié la configuration Gateway. Il a utilisé un backend SQLite temporaire et
un Bearer ATLAS aléatoire. Les catégories de contexte partagé étaient toutes
désactivées.

Le message envoyé depuis l'UI Coach demandait une session de révision de 25
minutes et une prochaine étape. Le modèle a répondu avec une directive
personnalisée (« Réviser un sujet ») et deux propositions conformes au schéma,
dont `focus.propose`. Le modèle avait choisi la durée Pomodoro générique de 10
minutes; la garde déterministe du backend a préservé les 25 minutes explicitement
demandées avant validation de policy et envoi à Android. La proposition a été
acceptée dans le Coach. Android a démarré Focus localement avec son minuteur,
enregistré `focus.start` dans le journal d'effets, puis conservé la session
`RUNNING` après `am force-stop` et relance de `LauncherActivity`. Le test a
ensuite mis la session en pause, l'a reprise et l'a annulée.

La capture HOME montre le titre, le corps personnalisé et Focus en cours. Le dump
uiautomator contient le même titre. Ces fichiers ne contiennent que des données
de recette synthétiques :

- [Capture HOME OpenClaw](captures/v6-openclaw-home.png)
- [Dump UI HOME](captures/v6-openclaw-home.xml)
- [Log Android du scénario réel](logs/v6-e2e-real-20261019T135513Z/instrument-realCoachResponseCreatesDirectiveAndStartsFocus.log)
- [Preuve de l'état Focus après démarrage](logs/v6-e2e-real-20261019T135513Z/android-proof.log)
- [Journal d'accès backend filtré](logs/v6-e2e-real-20261019T135513Z/backend.log)

Les trois méthodes instrumentées pilotées par le script ont réussi : réponse
réelle et démarrage Focus, restauration/pause/reprise/annulation, puis un essai
séparé préautorisé `focus.plan`. Le dernier est une proposition synthétique
traitée par le véritable `ActionDispatcher` Android; il n'est pas une sortie
OpenClaw. Le test préautorisé prouve l'application de `focus.plan` dans un
mandat limité et dans les bornes.

Le script a vérifié par `grep -F` que le jeton Gateway et le Bearer ATLAS
aléatoire ne figurent dans aucun fichier du dépôt. Le fichier Gateway restait
hors dépôt avec les permissions `0600`. Les erreurs d'essais intermédiaires ont
permis de corriger le défilement Compose imbriqué dans Coach, les champs JSON
optionnels `null` incompatibles avec le parseur Kotlin, la durée explicite, la
sélection de nouvelles propositions dans une inbox persistante et la détection
du résultat `OK (1 test)` d'AndroidJUnitRunner.

## Résultats mock et suites locales

| Commande | Résultat observé |
|---|---|
| `cd android && ./gradlew testDebugUnitTest lintDebug assembleDebug` | Succès; **137 tests JVM, 0 échec**, lint et APK debug réussis |
| `python3 -m pytest -q backend` | **45 passed, 1 skipped**, un avertissement de dépréciation Starlette/httpx |
| `./scripts/v6-backend-e2e.sh` | **45 passed, 1 deselected**; le cas Gateway réel est exclu par défaut |
| `ATLAS_REAL_GATEWAY=1 ./scripts/v6-backend-e2e.sh` | **1 passed, 45 deselected**, réexécuté après les gardes de durée |

Avec le harnais Android/backend V4 existant, lancé sur le même AVD au moyen
d'une copie temporaire sans capture (pour ne pas écraser `docs/v4/captures`),
les scénarios **online**, rejeu, **401**, consentement désactivé, backend
**offline**, puis **resume** ont tous réussi. SQLite a fini avec **2 événements,
2 UUID uniques**. Le test Android a gardé l'événement hors ligne dans l'outbox
jusqu'au redémarrage du backend. Ce résultat est un mock FastAPI V1 et ne remplace
pas le scénario OpenClaw réel.

La suite backend mock couvre notamment l'authentification 401, les reprises
timeout/5xx et retour de repli, la validation stricte JSON, le rejet d'actions
inconnues, l'injection de texte, la minimisation du contexte non consenti, le
TTL, la répétition/idempotence et les paquets protégés. Ce sont des tests avec
transport mock/agent déterministe; ils ne prétendent pas prouver le comportement
du modèle réel face à une injection hostile.

La suite JVM Android (137 tests, 0 échec) couvre la révocation avant
dispatch, les applications non consenties, les paquets protégés, le TTL,
l'idempotence, les limites du mandat et la garde `app_policy.adjust`. Le test
instrumenté préautorisé `focus.plan` a aussi réussi sur AVD dans le script réel.

## Suite instrumentée complète

`cd android && ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest`
a été tentée sur l'AVD existant. Le premier passage a exécuté **38 tests : 32
passés, 6 échoués**. Quatre échecs sont des assertions UI qui ne correspondent
pas aux écrans actuels : le bouton « Démarrer le Pomodoro », le parcours Coach
local « Discuter », l'attente du tiroir launcher dans `AtlasVisualCaptureTest`,
et l'ancien libellé HOME « Directive du jour ». Deux autres échecs étaient des
tests réels sans arguments de backend; ils ont été changés ensuite en scénarios
ignorés explicitement quand le script ne les pilote pas.

Après correction du déclenchement de ces deux tests à arguments manquants, un
second lancement de la suite complète s'est interrompu après 6 tests et 2
échecs lorsqu'Android Gradle Plugin a constaté `adb: device offline` pendant le
nettoyage. Le processus AVD avait quitté; le même AVD a été relancé depuis son
stockage existant, sans `-wipe-data`, et a redémarré avec `sys.boot_completed=1`.
La suite complète n'est donc pas verte et aucun nombre de réussite complet
n'est revendiqué. Les trois méthodes Lot F ont, elles, été exécutées avec leurs
arguments par le script et ont réussi. Les quatre assertions UI précédentes
restent à réconcilier avec les parcours actuels hors du correctif ciblé Lot F.
Après cet incident, la classe ciblée `V6RealAgentE2eInstrumentationTest` a été
exécutée par `connectedDebugAndroidTest` seule : **3 tests, 0 échec**. Dans cette
exécution sans arguments, les deux méthodes qui demandent le backend réel ont
journalisé leur raison de non-exécution; l'essai `focus.plan` préautorisé a été
exécuté en mock sur Android.

## Commandes

```sh
cd android
./gradlew testDebugUnitTest lintDebug assembleDebug
ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest
cd ..
python3 -m pytest -q backend
./scripts/v6-backend-e2e.sh
./scripts/v6-e2e-real.sh
```

`scripts/v6-e2e-real.sh` exige l'AVD déjà lancé et ne le démarre, n'arrête ni ne
réinitialise. Il refuse d'écraser une installation ATLAS sans son marqueur de
recette et n'exécute pas `pm clear`. Il sélectionne brièvement ATLAS comme HOME
pour la capture puis restaure le launcher précédent. Dans cette recette,
ATLAS n'était pas installé avant la première exécution; aucun état d'application
préexistant n'a été effacé.

