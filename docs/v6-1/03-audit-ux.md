# Lot D — Audit critique de l’UX actuelle

**Date :** 2026-10-19  
**Branche :** feat/atlas-v6-1-stabilisation  
**Périmètre :** HOME, Coach, Pomodoro, thème et accessibilité. Lot D n’a modifié aucun code source, test ou configuration de l’app. Les modifications concurrentes de Lot C sont restées intactes.

## Résumé

- **P0 : aucun défaut bloquant relevé** dans les parcours observés.
- **P1 :** le bouton Coach du launcher ouvre le tableau de bord; HOME empile des cartes et relègue Focus sous les favoris; le pied fixe gêne l’affichage en paysage; Coach regroupe conversation et réglages avancés dans le même long défilement; Pomodoro ne met pas la tâche au premier plan et développe ses réglages dans le flux principal.
- **P2 :** une action de carte HOME déclare 44 dp minimum; le thème AMOLED supprime aussi le retour visuel animé au toucher. TalkBack et les petits formats d’écran restent à vérifier.

## Périmètre et méthode

L’émulateur existant Pixel_9_Pro_XL_2, série emulator-5556, Android 17 / API 37, a fourni un écran natif de 1344 × 2992 pixels à 480 dpi, soit environ 448 × 997 dp en portrait. Le paysage capturé mesure 2992 × 1344 pixels. Aucun téléphone physique n’a été utilisé.

Les captures sont des images PNG natives, sans retouche. Les captures 01–02 et 04–18 ont été prises avec adb exec-out screencap -p depuis l’écran affiché. La capture 03 est l’exception : elle provient du test Compose isolé LotEUiInstrumentationTest et a été extraite telle quelle. Elle représente un HomeAgentState synthétique prérempli, sans connexion OpenClaw. **Aucune capture de ce lot ne provient d’OpenClaw réel.** Le message Coach, la proposition Focus et la session active sont des fixtures locales déterministes; la liste d’applications est celle des applications système de l’AVD.

AtlasVisualCaptureTest n’a pas été lancé : son newController() utilise le contexte cible puis efface les préférences atlas_local_state. Cela risquait de supprimer les données de l’AVD, ce que la consigne interdit. LotEUiInstrumentationTest utilise explicitement le contexte isolé du paquet de test.

## Constats priorisés

### P1 — HOME présente trop de blocs et remplit l’état vide des favoris

**Défaut et preuve.** La capture [01-home-sombre.png](captures/avant/01-home-sombre.png) montre l’heure, la date, l’accès applications, une grande carte directive avec provenance et CTA, un état vide des favoris en deux lignes et une carte suggestion. Avec un Focus actif, [04-home-focus-actif.png](captures/avant/04-home-focus-actif.png) ajoute une carte Focus et garde la suggestion sous le bloc favoris. La fixture [03-home-directive-agent-fixture.png](captures/avant/03-home-directive-agent-fixture.png) affiche simultanément directive, Focus et contenu recommandé. Les propositions et Focus existent aussi dans le contrat HOME : [HomeAgentState.kt]($HOME/perso/AndroCoach/atlas-preparation/android/app/src/main/java/dev/atlas/coach/app/HomeAgentState.kt:59), puis les cartes sont rendues après les favoris dans [LauncherHomeScreen.kt]($HOME/perso/AndroCoach/atlas-preparation/android/app/src/main/java/dev/atlas/coach/app/LauncherHomeScreen.kt:306).

**Correctif proposé.** Garder la directive comme premier élément; placer juste après un Focus actif ou une proposition qui requiert une décision; déplacer la suggestion de contenu vers la bibliothèque et réduire l’état sans favoris à une ligne discrète. N’afficher qu’une carte de service à la fois sur HOME.

**Référence OSS / AndroidX.** Adapter le motif textuel d’Olauncher documenté dans [Lot A]($HOME/perso/AndroCoach/atlas-preparation/docs/v6-1/00-diagnostic-et-reemploi.md:57), sans reprendre son écran. Employer les composants Compose Material 3 déjà présents, notamment ListItem et LazyColumn, pour limiter les surfaces et garder le défilement régulier.

**Fichiers concernés.** [LauncherHomeScreen.kt]($HOME/perso/AndroCoach/atlas-preparation/android/app/src/main/java/dev/atlas/coach/app/LauncherHomeScreen.kt:253), [HomeAgentState.kt]($HOME/perso/AndroCoach/atlas-preparation/android/app/src/main/java/dev/atlas/coach/app/HomeAgentState.kt:34).

### P1 — Le Focus actif et la proposition passent après le contenu secondaire

**Défaut et preuve.** Sur [04-home-focus-actif.png](captures/avant/04-home-focus-actif.png), « Focus en cours » arrive après la section « Applications favorites », même quand celle-ci est vide. La proposition et la suggestion sont rendues dans le même ordre de cartes après les favoris ([LauncherHomeScreen.kt]($HOME/perso/AndroCoach/atlas-preparation/android/app/src/main/java/dev/atlas/coach/app/LauncherHomeScreen.kt:306)). En texte agrandi, cette position augmente le risque que l’état Focus ou la proposition soit hors du premier écran.

**Correctif proposé.** Faire remonter toute session active ou proposition qui attend une confirmation immédiatement sous la directive. Laisser les favoris sous ces états, et ne rendre leur explication vide que si l’utilisateur ouvre le tiroir.

**Référence OSS / AndroidX.** Motif text-first Olauncher de Lot A; composants Material 3 Text/ListItem/Surface au lieu d’une suite de cartes de même poids visuel.

**Fichiers concernés.** [LauncherHomeScreen.kt]($HOME/perso/AndroCoach/atlas-preparation/android/app/src/main/java/dev/atlas/coach/app/LauncherHomeScreen.kt:306), [HomeAgentState.kt]($HOME/perso/AndroCoach/atlas-preparation/android/app/src/main/java/dev/atlas/coach/app/HomeAgentState.kt:23).

### P1 — Le raccourci Coach de HOME ouvre le tableau de bord

**Défaut et preuve.** En partant du HOME frais, le bouton Coach a lancé MainActivity sur Tableau de bord. LauncherActivity.openCoach() ne sélectionne pas la destination COACH; seul openFocus() sélectionne explicitement Focus. Le tableau de bord est la destination par défaut de MainActivity. Le chemin explicite Coach et journal existe derrière Plus ([MoreScreen]($HOME/perso/AndroCoach/atlas-preparation/android/app/src/main/java/dev/atlas/coach/app/AtlasScreens.kt:556)).

**Correctif proposé.** Avant d’ouvrir MainActivity depuis le CTA HOME, naviguer à AtlasScreenId.COACH. Garder les destinations Focus et Bibliothèque explicites comme elles le sont déjà.

**Référence OSS / AndroidX.** Réutiliser AtlasNavigationState et la navigation Material 3 existante; aucune dépendance ou copie tierce n’est nécessaire.

**Fichiers concernés.** [LauncherActivity.kt]($HOME/perso/AndroCoach/atlas-preparation/android/app/src/main/java/dev/atlas/coach/app/LauncherActivity.kt:94), [AtlasApp.kt]($HOME/perso/AndroCoach/atlas-preparation/android/app/src/main/java/dev/atlas/coach/app/AtlasApp.kt:98), [AtlasScreens.kt]($HOME/perso/AndroCoach/atlas-preparation/android/app/src/main/java/dev/atlas/coach/app/AtlasScreens.kt:556).

### P1 — Coach mélange le flux quotidien et les réglages avancés

**Défaut et preuve.** [07-coach-conversation.png](captures/avant/07-coach-conversation.png) place en tête le statut agent, deux actions de boîte/activation et la directive locale avant Conversation. La proposition apparaît après le champ de saisie. Mandat et autonomie, données partagées et journal sont ensuite trois panneaux du même écran défilant ([AgentCoachScreen.kt]($HOME/perso/AndroCoach/atlas-preparation/android/app/src/main/java/dev/atlas/coach/app/AgentCoachScreen.kt:65), [AgentCoachScreen.kt]($HOME/perso/AndroCoach/atlas-preparation/android/app/src/main/java/dev/atlas/coach/app/AgentCoachScreen.kt:199)). Les captures [12-coach-mandats.png](captures/avant/12-coach-mandats.png), [17-coach-donnees-partagees.png](captures/avant/17-coach-donnees-partagees.png) et [18-coach-journal-effets.png](captures/avant/18-coach-journal-effets.png) montrent le déplacement nécessaire. Le texte « Agent indisponible » voisin du bouton « Désactiver agent » rend aussi l’état courant ambigu.

**Correctif proposé.** Réduire Coach quotidien à la conversation, à la proposition prioritaire et à ses actions Accepter / Modifier / Plus tard / Refuser. Mettre le statut réseau dans un indicateur discret; placer mandat, catégories de partage et journal dans des destinations avancées distinctes. Aucun JSON brut n’apparaît dans le parcours observé; conserver cette présentation lisible.

**Référence OSS / AndroidX.** Utiliser la navigation déjà présente dans AtlasNavigationState et les composants Material 3 ListItem, NavigationBar et Scaffold. Lot A recommande les composants officiels existants; aucun code tiers n’est à importer.

**Fichiers concernés.** [AgentCoachScreen.kt]($HOME/perso/AndroCoach/atlas-preparation/android/app/src/main/java/dev/atlas/coach/app/AgentCoachScreen.kt:75), [AgentCoachScreen.kt]($HOME/perso/AndroCoach/atlas-preparation/android/app/src/main/java/dev/atlas/coach/app/AgentCoachScreen.kt:281), [AtlasScreens.kt]($HOME/perso/AndroCoach/atlas-preparation/android/app/src/main/java/dev/atlas/coach/app/AtlasScreens.kt:556).

### P1 — Pomodoro n’est pas encore centré sur la tâche; l’avancé reste dans le flux principal

**Défaut et preuve.** [08-pomodoro-propose.png](captures/avant/08-pomodoro-propose.png) juxtapose statistiques, deux presets, un mode démo de 20 secondes et la configuration « Session proposée ». Elle présente durée, pause et cycles, mais pas de champ de tâche. [09-pomodoro-en-cours.png](captures/avant/09-pomodoro-en-cours.png) montre phase, cycle, compte à rebours, lien vers la directive, Pause, Annuler et Terminer; durée totale et nom de tâche sont moins visibles que le minuteur. Les réglages sont développés sous le démarrage sur [10-pomodoro-avance.png](captures/avant/10-pomodoro-avance.png) et continuent sous le pli sur [11-pomodoro-avance-suite.png](captures/avant/11-pomodoro-avance-suite.png). Le flux est dans [AtlasScreens.kt]($HOME/perso/AndroCoach/atlas-preparation/android/app/src/main/java/dev/atlas/coach/app/AtlasScreens.kt:207) et [ReefPomodoroSetup.kt]($HOME/perso/AndroCoach/atlas-preparation/android/app/src/main/java/dev/atlas/coach/app/ReefPomodoroSetup.kt:45).

**Correctif proposé.** Placer en premier la tâche, la durée choisie, le temps restant, la phase et le cycle. Garder Démarrer avant la session, puis Pause et Annuler pendant celle-ci. Faire de Personnaliser un panneau secondaire; retirer le mode démo de l’usage quotidien. Le lien vers la directive peut fournir le nom initial, modifiable avant démarrage.

**Référence OSS / AndroidX.** Conserver uniquement le motif pur de durée/cycles Reef déjà adapté selon Lot A; employer Material 3 ModalBottomSheet pour l’avancé, sans importer TimerScreen, service ou stockage Reef.

**Fichiers concernés.** [AtlasScreens.kt]($HOME/perso/AndroCoach/atlas-preparation/android/app/src/main/java/dev/atlas/coach/app/AtlasScreens.kt:207), [ReefPomodoroSetup.kt]($HOME/perso/AndroCoach/atlas-preparation/android/app/src/main/java/dev/atlas/coach/app/ReefPomodoroSetup.kt:45).

### P1 — Le pied fixe comprime HOME en paysage et à 2×

**Défaut et preuve.** La capture paysage [16-home-paysage.png](captures/avant/16-home-paysage.png) ne laisse qu’une faible hauteur au contenu et montre la carte directive sous le grand pied d’actions. À 1,5× [14-home-fontscale-1.5.png](captures/avant/14-home-fontscale-1.5.png), les liens de pied se replient; à 2× [15-home-fontscale-2.0.png](captures/avant/15-home-fontscale-2.0.png), directive et CTA restent lisibles mais le contenu inférieur passe sous la zone fixe et requiert le défilement. Le pied contenant pause d’urgence, launcher précédent, réglages et Coach est distinct de la colonne défilante ([LauncherHomeScreen.kt]($HOME/perso/AndroCoach/atlas-preparation/android/app/src/main/java/dev/atlas/coach/app/LauncherHomeScreen.kt:190), [LauncherHomeScreen.kt]($HOME/perso/AndroCoach/atlas-preparation/android/app/src/main/java/dev/atlas/coach/app/LauncherHomeScreen.kt:339)).

**Correctif proposé.** Préserver une action d’urgence immédiatement accessible, mais adapter le reste du pied aux hauteurs courtes : réduire les liens persistants ou les déplacer dans le tiroir / un menu, et faire recalculer la zone de défilement pour qu’aucune carte ne soit visuellement couverte. Tester à 2× paysage avant validation.

**Référence OSS / AndroidX.** S’appuyer sur Compose WindowInsets, Scaffold et une liste défilante LazyColumn; garder la structure Material 3 déjà présente.

**Fichiers concernés.** [LauncherHomeScreen.kt]($HOME/perso/AndroCoach/atlas-preparation/android/app/src/main/java/dev/atlas/coach/app/LauncherHomeScreen.kt:190), [AtlasDesign.kt]($HOME/perso/AndroCoach/atlas-preparation/android/app/src/main/java/dev/atlas/coach/app/ui/AtlasDesign.kt:176).

### P2 — Une action de carte HOME déclare 44 dp

**Défaut et preuve.** HomeAgentCardView fixe la hauteur minimale de son TextButton à 44 dp ([LauncherHomeScreen.kt]($HOME/perso/AndroCoach/atlas-preparation/android/app/src/main/java/dev/atlas/coach/app/LauncherHomeScreen.kt:383)). Plusieurs actions centrales sont explicitement à 48 ou 52 dp, mais cette action n’atteint pas ce seuil dans son contrat local. Les captures HOME montrent ces actions Focus et bibliothèque dans [03-home-directive-agent-fixture.png](captures/avant/03-home-directive-agent-fixture.png) et [04-home-focus-actif.png](captures/avant/04-home-focus-actif.png).

**Correctif proposé.** Déclarer au moins 48 dp et vérifier la zone sémantique/tactile réellement exposée par Compose.

**Référence OSS / AndroidX.** Matérialiser la cible avec les composants Compose Material 3 et les sémantiques AndroidX existantes.

**Fichiers concernés.** [LauncherHomeScreen.kt]($HOME/perso/AndroCoach/atlas-preparation/android/app/src/main/java/dev/atlas/coach/app/LauncherHomeScreen.kt:383).

### P2 — L’indication AMOLED supprime le retour visuel au toucher

**Défaut et preuve.** AMOLED choisit NoAnimationIndication; son nœud dessine seulement le contenu, sans indication d’interaction ([AtlasTheme.kt]($HOME/perso/AndroCoach/atlas-preparation/android/app/src/main/java/dev/atlas/coach/app/ui/AtlasTheme.kt:65), [AtlasTheme.kt]($HOME/perso/AndroCoach/atlas-preparation/android/app/src/main/java/dev/atlas/coach/app/ui/AtlasTheme.kt:119)). Aucun profil énergétique n’a été mesuré pour justifier cette perte de retour visuel.

**Correctif proposé.** Garder un retour tactile non animé ou une indication d’état explicite; ne retenir la suppression globale qu’après mesure sur un appareil pertinent.

**Référence OSS / AndroidX.** Indication et InteractionSource Material 3 déjà présentes; aucune nouvelle bibliothèque.

**Fichiers concernés.** [AtlasTheme.kt]($HOME/perso/AndroCoach/atlas-preparation/android/app/src/main/java/dev/atlas/coach/app/ui/AtlasTheme.kt:65).

## D1 — HOME et navigation

Le HOME est textuel et lisible, avec une heure sans secondes, la date, une action Applications et recherche, un titre de directive, un corps limité et un CTA. C’est une bonne base Olauncher. La carte de directive reste toutefois dominante, les blocs Focus/contenu apparaissent en concurrence et le vide des favoris est décrit comme une instruction de deux lignes. Le statut « Mode local · hors ligne » est utile pour la provenance, mais son affichage au même niveau que le contenu attire une attention technique dans le parcours quotidien. À l’usage, le tiroir s’ouvre par le bouton explicite et par glissement vers le haut depuis l’en-tête; recherche Chrome fonctionne. Le geste réduit la portée nécessaire pour ouvrir les applications, tandis que le bouton explicite et l’horloge restent dans la partie haute de l’écran. Le grand AVD utilisé ne permet pas de conclure sur la portée du pouce sur petit téléphone; celle-ci n’a pas été mesurée. Les captures sont [01](captures/avant/01-home-sombre.png), [04](captures/avant/04-home-focus-actif.png), [05](captures/avant/05-tiroir-applications.png) et [06](captures/avant/06-recherche.png).

Retour sur HOME ferme le tiroir s’il est ouvert et ne quitte pas le launcher ([LauncherHomeScreen.kt]($HOME/perso/AndroCoach/atlas-preparation/android/app/src/main/java/dev/atlas/coach/app/LauncherHomeScreen.kt:149)); le geste système Retour de Coach vers Plus et l’action système Home vers Pixel Launcher ont été exercés sur l’AVD. Focus actif et proposition sont accessibles, mais l’ordre actuel les rend moins visibles que nécessaire.

## D2 — Sombre, AMOLED et travail périodique

Le défaut de préférence est Sombre ([ThemePreference.kt]($HOME/perso/AndroCoach/atlas-preparation/android/app/src/main/java/dev/atlas/coach/app/ui/ThemePreference.kt:27)). AMOLED utilise bien un fond et une surface #000000, avec conteneurs #0A0A0A à #202020 ([AtlasThemePalette.kt]($HOME/perso/AndroCoach/atlas-preparation/android/app/src/main/java/dev/atlas/coach/app/ui/AtlasThemePalette.kt:74)). Sur la capture [02-home-amoled.png](captures/avant/02-home-amoled.png), les cartes restent visibles mais proches du noir; le contraste des surfaces ne paraît pas excessif.

Calcul statique WCAG 2.x sur les tokens du code, avec la même formule que [AtlasThemePalette.kt]($HOME/perso/AndroCoach/atlas-preparation/android/app/src/main/java/dev/atlas/coach/app/ui/AtlasThemePalette.kt:111) :

- sombre : texte principal/surface 14,74:1; texte secondaire/carte 8,20:1; primaire/fond 14,63:1;
- AMOLED : texte principal/noir 19,26:1; texte secondaire/surface #111111 11,40:1.

Ces chiffres couvrent les tokens, pas les pixels de chaque état désactivé ou superposé.

Lecture statique des coûts : l’horloge du launcher se met à jour à la minute ([LauncherHomeScreen.kt]($HOME/perso/AndroCoach/atlas-preparation/android/app/src/main/java/dev/atlas/coach/app/LauncherHomeScreen.kt:168)); le Focus ticke à la seconde uniquement quand MainActivity est visible sur Focus avec une session active ([AtlasApp.kt]($HOME/perso/AndroCoach/atlas-preparation/android/app/src/main/java/dev/atlas/coach/app/AtlasApp.kt:67)); UsageStats poll toutes les 10 secondes seulement lorsque le suivi facultatif est actif ([AtlasApp.kt]($HOME/perso/AndroCoach/atlas-preparation/android/app/src/main/java/dev/atlas/coach/app/AtlasApp.kt:78), [UsageModels.kt]($HOME/perso/AndroCoach/atlas-preparation/android/app/src/main/java/dev/atlas/coach/usage/UsageModels.kt:98)). Le code ne définit pas de transition d’écran; AMOLED retire les ripples animés. Aucune mesure batterie, FPS, recomposition ou trace énergétique n’a été effectuée.

## D3 — Coach : conversation, proposition, puis réglages avancés

Le parcours observé commence par le statut agent, des actions de boîte/activation et la directive avant la conversation; la proposition suit le champ de saisie ([07-coach-conversation.png](captures/avant/07-coach-conversation.png)). Les mandats, autorisations, données partagées et journal exigent de parcourir le même écran plus loin ([12-coach-mandats.png](captures/avant/12-coach-mandats.png), [17-coach-donnees-partagees.png](captures/avant/17-coach-donnees-partagees.png), [18-coach-journal-effets.png](captures/avant/18-coach-journal-effets.png)). Le retour système depuis Coach mène à Plus; le bouton Coach du HOME, en revanche, ouvre le tableau de bord, comme détaillé dans les constats P1.

Priorité recommandée : conversation en premier, proposition exigeant une décision juste après et actions Accepter / Modifier / Plus tard / Refuser. Mandats, partage et journal vont dans des sections dédiées, accessibles depuis une entrée secondaire claire. Le parcours observé ne montre pas de JSON brut; conserver le contenu actionnable sous forme lisible et signaler discrètement l’état hors ligne/provenance.

## D4 — Pomodoro : tâche et contrôle de session

Avant démarrage, la capture [08-pomodoro-propose.png](captures/avant/08-pomodoro-propose.png) montre les statistiques, presets, mode démo puis la durée, la pause et les cycles d’une session proposée. Aucun champ ne donne un nom de tâche visible sur cet écran. Pendant une session, [09-pomodoro-en-cours.png](captures/avant/09-pomodoro-en-cours.png) montre la phase, le cycle, le compte à rebours, un lien vers la directive et les actions Pause, Annuler et Terminer; le nom de tâche et la durée totale passent derrière le compte à rebours. La personnalisation apparaît en ligne après le démarrage ([10-pomodoro-avance.png](captures/avant/10-pomodoro-avance.png), [11-pomodoro-avance-suite.png](captures/avant/11-pomodoro-avance-suite.png)).

Priorité recommandée : sur l’écran principal, tâche, durée, temps restant, phase et cycles, puis Démarrer avant session et Pause / Annuler pendant session. Mettre Personnaliser dans un panneau secondaire et sortir le mode démo du parcours quotidien. Voir le constat P1 Pomodoro pour les fichiers de mise en œuvre et le motif Reef/Material 3 proposé.

## D5 — Accessibilité : testé et non vérifié

- **Échelle du texte :** captures réelles HOME à 1,5× et 2,0× ([14](captures/avant/14-home-fontscale-1.5.png), [15](captures/avant/15-home-fontscale-2.0.png)); valeur système restaurée à 1,0 après capture. L’AVD était un format 448 dp de large, pas un petit téléphone.
- **Paysage :** capture réelle [16](captures/avant/16-home-paysage.png), 997 × 448 dp. Le contenu devient fortement contraint par le pied. Test instrumenté existant à 2× paysage vérifié sur la visibilité de la pause d’urgence, Launcher précédent, Réglages HOME et Coach; il ne vérifie pas l’absence de recouvrement pixel à pixel.
- **Cibles :** bouton ouvrir tiroir 48 dp, favori 52 dp, pause d’urgence 52 dp et liens de pied 48 dp dans LauncherHomeScreen; l’action de carte citée plus haut ne déclare que 44 dp. Le minimum effectif AndroidX pour ce TextButton n’a pas été mesuré.
- **TalkBack :** non lancé. Le code fournit des descriptions pour pause d’urgence, favori et actions personnalisées dans LauncherHomeScreen; le test instrumenté vérifie la présence de descriptions et l’affichage, pas l’ordre ni la restitution vocale. Les contrastes statiques calculés dans D2 sont tous au-dessus de 4,5:1 pour les paires principales.
- **Habitudes :** écran réel vide, sans données utilisateur, dans [13-habitudes.png](captures/avant/13-habitudes.png). Aucun petit émulateur, TalkBack, Switch Access ou audit par scanner d’accessibilité n’a été utilisé.

## Captures « avant » et provenance

| Fichier | État montré | Provenance |
|---|---|---|
| [01-home-sombre.png](captures/avant/01-home-sombre.png) | HOME sombre et directive locale | Fixture locale déterministe; aucun OpenClaw |
| [02-home-amoled.png](captures/avant/02-home-amoled.png) | HOME AMOLED | Fixture locale déterministe; aucun OpenClaw |
| [03-home-directive-agent-fixture.png](captures/avant/03-home-directive-agent-fixture.png) | Directive, Focus et contenu préremplis | Fixture synthétique HomeAgentState du test LotEUiInstrumentationTest; aucun OpenClaw |
| [04-home-focus-actif.png](captures/avant/04-home-focus-actif.png) | HOME pendant Focus actif | Fixture locale, session 25 min créée puis annulée après capture |
| [05-tiroir-applications.png](captures/avant/05-tiroir-applications.png) | Tiroir et liste d’applications | Paquets système de l’AVD, aucune donnée personnelle |
| [06-recherche.png](captures/avant/06-recherche.png) | Recherche Chrome | Recherche locale dans les applications système |
| [07-coach-conversation.png](captures/avant/07-coach-conversation.png) | Échange et proposition Focus | Message synthétique, adaptateur déterministe hors ligne |
| [08-pomodoro-propose.png](captures/avant/08-pomodoro-propose.png) | Préparation 25/5, 4 cycles | Configuration locale, pas proposition OpenClaw |
| [09-pomodoro-en-cours.png](captures/avant/09-pomodoro-en-cours.png) | Focus 25 min en cours | Session locale synthétique |
| [10-pomodoro-avance.png](captures/avant/10-pomodoro-avance.png) | Personnalisation, haut | Configuration locale |
| [11-pomodoro-avance-suite.png](captures/avant/11-pomodoro-avance-suite.png) | Personnalisation, bas | Configuration locale |
| [12-coach-mandats.png](captures/avant/12-coach-mandats.png) | Mandat et autorisations | Mandat local par défaut et proposition synthétique |
| [13-habitudes.png](captures/avant/13-habitudes.png) | Habitudes, état vide | Aucune habitude ni donnée personnelle |
| [14-home-fontscale-1.5.png](captures/avant/14-home-fontscale-1.5.png) | HOME, fontScale 1,5 | Directive locale et Focus synthétique |
| [15-home-fontscale-2.0.png](captures/avant/15-home-fontscale-2.0.png) | HOME, fontScale 2,0 | Directive locale et Focus synthétique |
| [16-home-paysage.png](captures/avant/16-home-paysage.png) | HOME paysage | État local après restauration fontScale 1,0 |
| [17-coach-donnees-partagees.png](captures/avant/17-coach-donnees-partagees.png) | Catégories de partage | Mandat local; partage désactivé |
| [18-coach-journal-effets.png](captures/avant/18-coach-journal-effets.png) | Journal des effets, état vide | Fixture locale, aucun effet externe |

## Validation, ADB et limites

- Commande exécutée : ANDROID_SERIAL=emulator-5556 ./gradlew --console=plain :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.atlas.coach.app.LotEUiInstrumentationTest
- Résultat : 3 tests, 0 échec, 0 erreur, 0 ignoré. Couverture ciblée : Home synthétique sombre/AMOLED, proposition Focus exigeant un appui, et actions d’urgence/sortie visibles à fontScale 2 en paysage.
- Le lot B rapporte 35 tests instrumentés applicables réussis. La suite complète n’a pas été relancée pour ce lot.
- ADB a cessé de lister l’émulateur pendant l’audit. Le serveur ADB restait actif sur 5037, mais aucun processus emulator ou qemu n’était présent; l’état était absent, pas device offline. Le répertoire de crash de l’émulateur ne contenait pas de rapport permettant d’établir la cause. Le même AVD existant a été relancé avec les paramètres documentés, sans -wipe-data ni recréation; il est resté device pour les captures et le test ciblé.
- L’APK debug n’était pas installée; elle a été installée avec adb install -r. Le rôle Accueil ATLAS a été sélectionné par les dialogues Android normaux pour les captures HOME puis Pixel Launcher a été rétabli. Aucune donnée de l’AVD n’a été effacée. À la fin : rôle HOME Pixel Launcher, fontScale 1,0, rotation automatique, thème ATLAS Sombre.
- Aucun backend/OpenClaw réel, script E2E distant, téléphone physique, profil batterie, test complet TalkBack, petit format ou revue de publication n’a été exécuté.

## Décisions à valider pour Lot E

Ces choix sont réversibles; les valeurs recommandées permettent de continuer sans bloquer l’implémentation.

1. **HOME :** montrer uniquement directive + Focus/proposition prioritaire, ou garder aussi les suggestions de contenu visibles? Recommandation : une carte directive et au plus une carte Focus/proposition; contenu dans la bibliothèque.
2. **Tâche Pomodoro :** reprendre le titre de directive comme nom initial, ou demander un nom distinct? Recommandation : reprendre la directive et permettre sa modification avant Démarrer.
3. **Directive distante hors ligne :** conserver la dernière directive avec provenance et fraîcheur, ou revenir à la directive locale? Recommandation : conserver l’état distant avec statut indisponible et date de dernière confirmation, sans le présenter comme actuel.
4. **Pied HOME :** garder Launcher précédent, Réglages HOME et Coach toujours visibles à côté de l’urgence, ou déplacer les actions secondaires dans le tiroir? Recommandation : garder l’urgence fixe et rendre sortie/Coach accessibles en une action depuis le menu.
