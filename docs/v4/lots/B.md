# Lot B — Audit ciblé open source V4 (R1)

Date : 2026-10-19  
Référence : orchestrateur V4; lot B, audit sans modification de l’application.

## Objectif et résultat

Vérifier les révisions Reef, Olauncher, Lawnchair et Loop demandées, auditer l’organisation officielle ReVanced et fournir aux lots C/D/E/G des décisions et chemins source précis. Résultat livré dans docs/v4/01-reemploi-open-source.md.

Décisions : extraction pure Reef limitée au timer/transition/fenêtres, pas de service ou blocage; comportements Olauncher comme référence de HOME Compose; HOME maison conservé après chiffrage Lawnchair; algorithmes Loop derrière l’unique HabitRepository; ReVanced reste un lecteur externe ouvert par Intent Android; pas de dépendance Tasker ou Miniflux en V4.

## Fichiers autorisés touchés

- docs/v4/01-reemploi-open-source.md : audit détaillé, tableau par référence, preuves, recommandations par lot.
- docs/v4/lots/B.md : ce handoff.
- THIRD_PARTY_NOTICES.md : ajout uniquement de « V4 — candidats évalués (non intégrés) ».
- docs/AUDIT_CIBLE.md : ajout uniquement de « V4 — candidats évalués (non intégrés) ».
- _research/** : clones shallow détachés aux SHA exigés, checkout sparse Lawnchair, module Gradle isolé et copies de cinq sources Reef. Le répertoire est ignoré par le .gitignore existant.

Aucun code dans android/, backend/ ou scripts/ n’a été modifié par le lot B. Les changements observés pendant la session dans android/** et scripts/** sont ceux du lot A; laissés intacts. README_APPLIQUER.md était déjà non suivi au démarrage et n’a pas été touché.

## Vérifications et preuves

| Critère | Statut | Preuve |
|---|---|---|
| SHA des quatre clones | PASS | git rev-parse HEAD égale aux SHA épinglés Reef 044358f…, Olauncher f8e8d64…, Lawnchair fc100ae…, uhabits c06b682…. git ls-remote indiquait ces mêmes HEAD. |
| Lecture des zones demandées | PASS | Sources Reef timer/stats/routines/UsageTracker/BlockerService/MindfulLaunch et FocusModeService de transition; HOME/tiroir/gestes/rôle Olauncher; modèles/rappels/widgets Loop; structure, manifests, modules, dépendances et tailles Lawnchair. |
| Spike Reef | PASS | Commande depuis le clone Reef : ./gradlew --project-dir /home/nill/perso/AndroCoach/atlas-preparation/_research/spikes/reef-extraction compileKotlin --no-daemon. Gradle 9.5.0; BUILD SUCCESSFUL, 1 tâche exécutée en 1 min 10 s, cinq fichiers compilés. |
| Compilation/test API Android 37 | BLOCKED par le périmètre | Le brief interdit le Gradle ATLAS et l’émulateur; aucun build Reef/launcher ni comportement Android runtime n’est revendiqué. Les sources Reef déclarent target/compile SDK 37, sans preuve d’exécution API 37. |
| Lawnchair | PASS — spike structurel borné | 7 348 fichiers suivis, 4 206 sources Kotlin/Java, 32 sous-projets Gradle, 682 sources Quickstep; clone sparse 42 MiB. Pas de compilation ni de fork, aucun sous-module launcher isolé trouvé. |
| ReVanced / Tasker / Miniflux | PASS — intégration évaluée, pas exécutée | Manager/locator GitHub consultés; locator officiel pointe vers GitLab, HEAD miroir relevé par ls-remote. Docs Tasker et Miniflux consultées. Aucun clone ReVanced, aucune application vidéo/Tasker lancée, aucun serveur Miniflux connecté. |
| Écritures application / permissions | PASS | Aucun fichier application modifié, aucune permission activée, aucun émulateur ou appareil utilisé. |
| Tests unitaires | BLOCKED par le périmètre | Le brief demande un spike de compilation et interdit de lancer les tests/builds du projet Android. Aucun test Reef/Loop/Olauncher n’a été ajouté ou exécuté; la seule preuve d’exécution est la compilation JVM isolée ci-dessus. |

## Limites et travaux restants

- Le ticket Reef #91 reste ouvert; ne pas activer son chemin Accessibilité/blocage. D doit garder UsageStats opt-in et proposer des notifications/friction volontaire. Tout futur provider Accessibility nécessite un audit distinct, un flag désactivé par défaut, arrêt d’urgence, consentement et tests dédiés.
- C doit transformer calculateNextPhase et RoutineTimeCalculator en reducers/calculs purs ATLAS, corriger la persistance/reprise autour de FocusEngine et tester frontières de cycle, DST, annulation et redémarrage.
- E doit séparer planning dû du score Frequency Loop, mapper SKIP/check-in sans deuxième stockage et gérer permission/refus de rappel. Widgets restent une capacité optionnelle.
- G doit porter la recherche/gestes/favoris utiles dans Compose, demander le rôle HOME par action utilisateur et ne pas reprendre les permissions Olauncher.
- Le lot B n’a pas testé le chooser vidéo avec un lecteur absent/navigateur seul, Tasker, widgets ou HOME sur l’AVD. Ces scénarios restent à valider par les lots fonctionnels.
- Les décisions réversibles sont écrites dans le rapport principal; docs/DECISIONS_AUTOMATIQUES.md est hors de l’allowlist B.

## Propositions pour les chemins interdits au lot B

Aucun diff d’application n’est livré. Les lots suivants peuvent appliquer les extractions décrites dans le rapport principal :

- C : reducer de cycle dans le moteur Focus existant, adaptation de TimerState.kt, calcul RoutineTimeCalculator derrière le modèle ATLAS.
- D : interface AppUsageObserver/UsageEventSource opt-in et provider UsageStats; aucune reprise BlockerService/MindfulLaunchActivity.
- E : modèle d’échéance ATLAS séparé de Frequency/Score, rappel via adaptateur Android unique, aucune table SQLite Loop.
- G : modèle de launcher et recherche dans l’écran Compose existant; pas de fork Launcher3 ni import de fragments Olauncher.

## Statut du lot

PASS pour l’audit demandé, le spike JVM Reef et le livrable. BLOCKED uniquement pour preuves runtime Android/API 37, volontairement hors du périmètre autorisé de B. Aucun changement d’application ne reste à intégrer par B.
