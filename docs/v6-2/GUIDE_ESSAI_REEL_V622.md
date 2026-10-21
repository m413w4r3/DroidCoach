# ATLAS V6.2.2 — guide d'essai réel B3 / D2 / D3

**Statut : guide des essais physiques restant à faire, actualisé après S6.** Un contrôle limité aux deux méthodes ACK a été effectué sur un Pixel 9 Pro XL / API37 : la course passe, le chemin positif échoue de façon reproduite. Ce résultat ne qualifie pas les autres parcours physiques; aucun `REAL_DEVICE_PASS`, `TALKBACK_REAL_PASS` ou `BATTERY_REAL_PASS` n'est acquis.

### Résumé des essais S6 déjà exécutés

| Parcours | Statut S6 | Résultat et limite |
| --- | --- | --- |
| UI locale et matrices D1 | `PASS` pour les cas du run courant | Sur app `ce10…` / AndroidTest `893da1…`, suite principale 75/75 à 360 dp et captures HOME/Coach ciblées 1/1 chacune. Les compléments 320 dp, paysage et captures additionnelles restent historiques `0b5c…`; le harnais complet n'est pas revalidé. |
| Objectif/Focus et recréation | `PASS` dans deux E2E Gateway actuels | Deux parcours réels sur app `ce10…` / AndroidTest `893da1…` prouvent le lien Goal–Focus durable avant l'avancement, 0/120 puis 25/120, révocation et requête sans Goal. Le ciblé Goal–Focus 7/7 reste historique `8edb`; voir [preuve courante](preuves-v622/s6-revocation-fixture-exact-restore-20261021.txt) et [preuve de correction antérieure](preuves-v622/s6-correction-quarantaine-20261021.txt). |
| GPS visible et mobilité | `TEST_SYSTEM_BLOCKED` historique après sous-cas PASS | Sur app `ce10…` / AndroidTest `0b5c…`, logique, activité visible et préparation mobilité passent; Android ne fournit pas de fix coarse frais après injection synchronisée. Non rejoué sur `893da1…`. Voir [preuve historique](preuves-v622/s6-correction-quarantaine-20261021.txt). |
| TalkBack | `PARTIAL` AVD, historique | Service système lié temporairement et semantics inspectées; aucun contrôle fiable de la parole ou des gestes. Aucun parcours TalkBack réel n'est passé. |
| E2E OpenClaw | `PASS` 2/2 Gateway et hors ligne 1/1 sur la paire finale | Deux tests Gateway S6 et le test hors ligne passent sur `ce10…` / `893da1…`, avec 2 ACK exécutés avec effet. `V6RealAgentE2e` est `NOT_RUN` sur cette paire; son ancien `FAIL` sur `8edb…` reste distinct et non classé. Voir [preuve finale](preuves-v622/s6-revocation-fixture-exact-restore-20261021.txt). |
| Repos / D3 | `NOT_RUN` sur la paire finale | Aucun test UsageStats ni consentement coaching n'a été lancé pour `893da1…`. La fenêtre locale antérieure de 60 s et l'ancien blocage coaching restent historiques, sans paire comparable actuelle. |
| ACK physique ciblé | `PARTIAL` sur Pixel 9 Pro XL / API37 | Sur app `7c6c…` / AndroidTest `542280…`, course ACK `PASS`, chemin positif `FAIL` reproduit. Nettoyage du premier run vérifié; l'état package après reprise interrompue avant instrumentation est `NOT_RECHECKED`. Voir [preuve physique](preuves-v622/s6-ack-physical-runtime-20261021.txt). |
| Recette physique complète | `NOT_RUN` | Le contrôle ACK ne couvre pas les parcours UI, persistance, localisation, D3 ou accessibilité. |

Le rapport d'exécution est [RECETTE_V622.md](RECETTE_V622.md); les statuts globaux sont dans [QUALIFICATION_PRODUIT_V622.md](QUALIFICATION_PRODUIT_V622.md). Les étapes ci-dessous restent le protocole des parcours physiques non exécutés.

## Préparer un essai non destructif

Pour un essai futur, choisir un appareil Android secondaire uniquement après l'avoir identifié et obtenu l'accord de son utilisateur. Si aucun appareil approprié n'est disponible ou si l'utilisateur ne souhaite pas l'essai, classer les scénarios physiques `NOT_RUN` et conserver ce statut.

Avant l'installation, consigner dans une fiche séparée et expurgée :

- alias local de l'appareil, modèle, version/build Android;
- version ATLAS, commit candidat et empreinte SHA-256 de l'APK;
- date, réseau et réglages pertinents; heure réelle de début et de fin de chaque essai;
- source de chaque preuve et statut `PASS`, `FAIL`, `BLOCKED` ou `NOT_RUN`.

Ne pas publier le numéro de série, un identifiant de compte, des titres d'objectifs, un jeton, un payload, une position ou une capture contenant ces données. Utiliser des objectifs synthétiques.

Installer ou mettre à jour l'APK par le parcours normal du Package Installer Android après confirmation de l'utilisateur. Démarrer ATLAS comme application ordinaire, backend non configuré. Ne pas utiliser `pm clear`, `wipe-data`, réinitialisation usine, root, désinstallation, installation silencieuse, changement forcé de HOME ou modification d'AppOps. Aucun script destructif AVD ne doit cibler ce téléphone.

Si le sélecteur d'objectif, le lien Goal–Focus ou un contrôle décrit ci-dessous n'existe pas dans l'APK candidate, arrêter ce scénario et noter `BLOCKED` avec la version de l'APK; ne pas créer un lien rétroactif ni simuler son résultat.

## Scénarios produit hors ligne et interruptions

### Objectif → Focus → progression

1. Laisser le backend désactivé. Créer un objectif synthétique `FOCUS_MINUTES` de **120 minutes**.
2. Dans Focus, choisir explicitement cet objectif avant de démarrer une session de **25 minutes**. Vérifier que la sélection est visible; si plusieurs objectifs existent, choisir celui voulu. Ne pas inférer le choix du titre d'une tâche.
3. Vérifier `0 / 120` avant le démarrage, pendant la session et en pause. La liaison doit être visible ou vérifiable immédiatement après le commit local, sans que les minutes prévues soient créditées.
4. Laisser réellement s'écouler 25 minutes de travail, en excluant les pauses, puis terminer selon le modèle Focus. Vérifier `25 / 120`, rouvrir ATLAS et vérifier de nouveau `25 / 120`. Réafficher/recalculer plusieurs fois ne doit pas doubler la progression.
5. Démarrer une seconde session liée puis l'annuler. Vérifier que la progression reste `25 / 120`. Une session libre ne doit pas modifier l'objectif.

Si l'écoulement réel de 25 minutes n'est pas effectué, noter la durée effectivement observée et classer le scénario comme partiel ou `NOT_RUN`; une horloge accélérée ou une fixture ne constitue pas un essai réel de 25 minutes.

### Reprise, pause et hors connexion

- Démarrer une session, la mettre en pause, revenir à HOME, rouvrir ATLAS et vérifier l'état en pause. Reprendre seulement par un geste explicite. Vérifier qu'aucune minute de pause n'est comptée.
- Faire pivoter l'écran et mettre l'application en arrière-plan puis la rouvrir. Si l'utilisateur l'autorise, fermer l'application depuis l'interface Android et vérifier la restauration. Une tentative après « Forcer l'arrêt » dans les réglages Android est facultative; ne jamais effacer les données.
- Activer temporairement le mode avion par un geste de l'utilisateur, vérifier HOME, objectifs, Focus et pause d'urgence, puis restaurer le réseau. Ne pas interpréter une requête distante déjà envoyée comme annulée par la révocation ou le mode hors ligne.
- Vérifier que terminer, annuler ou recréer l'écran/contrôleur ne crédite pas deux fois une session. Consigner les interruptions et l'heure réelle; ne pas laisser un Focus actif en quittant l'essai.

### Consentements, révocations, Coach et notifications

- Tester les capabilities et catégories de partage séparément. Laisser les partages désactivés par défaut; n'activer que la catégorie synthétique nécessaire au scénario, après consentement explicite.
- Si l'aperçu de contexte est disponible, vérifier ce qui partirait avant tout envoi. Retirer ensuite `goals` ou `focus` et confirmer que la catégorie n'apparaît plus dans l'aperçu suivant. La révocation bloque les futurs envois; elle ne supprime pas une copie déjà reçue par un serveur.
- Garder le mode sans serveur pour les scénarios locaux. Un scénario Coach distant ne se fait qu'avec un backend de test autorisé fourni par S6; aucun jeton ou payload ne va dans les preuves.
- Vérifier la permission Android de notifications et le réglage ATLAS séparément. Si une notification proactive est testée, confirmer le consentement, la révocation et le retrait des notifications ATLAS déjà affichées; ne pas toucher aux notifications d'autres applications.

### HOME volontaire et matrice d'affichage

- Démarrer sans rôle HOME. N'attribuer ATLAS comme accueil que depuis le sélecteur Android et par un geste volontaire de l'utilisateur. Vérifier le retour au launcher précédent par le chemin ATLAS et par les réglages Android.
- Sur le téléphone, vérifier l'orientation portrait/paysage disponible, échelles de police 1×, 1,5× et 2×, recherche, favoris, pied fixe, pause d'urgence et accès aux réglages. Restaurer les réglages d'affichage de l'utilisateur à la fin.
- Tester le tiroir avec les applications réellement présentes sans en installer pour gonfler la liste. La matrice synthétique 100/250 applications, les tailles 320/360 dp et les cas impossibles à reproduire sur le téléphone sont des **exécutions AVD distinctes**; rapporter l'API et l'image de cet AVD. Ne jamais présenter ces résultats comme une validation téléphone.

### Localisation et mobilité

- Depuis une action visible, demander uniquement l'accès approximatif pendant l'utilisation. La permission OS et le partage Coach sont deux consentements distincts. Refuser la précision fine et l'accès arrière-plan; aucune permission permanente ne fait partie de l'essai.
- Contrôler refus, révocation, fournisseur désactivé et timeout. Un timeout doit rester un timeout; ne pas inventer ni enregistrer un fix. Ne pas utiliser de coordonnées personnelles comme fixture et ne pas conserver de trace brute.
- Si l'utilisateur choisit explicitement la mobilité, la démarrer depuis ATLAS visible. Vérifier la notification persistante, son action Arrêter, puis l'arrêt après révocation du consentement et après la pause d'urgence. Arrêter le suivi à la fin. Ne pas déduire une interception ou une précision instantanée de `UsageStatsManager`.

## TalkBack réel — D2

Cette partie exige TalkBack effectivement activé sur le téléphone secondaire et une interaction réelle. Une revue statique des libellés Compose ou des semantics est `CODE_REVIEWED`, jamais `TALKBACK_REAL_PASS`.

Avec l'accord de l'utilisateur, parcourir avec la navigation séquentielle de TalkBack et ses gestes d'activation : HOME et son ordre de lecture, favoris, Pause, réglages/révocation, choix d'objectif, démarrer/pause/reprise/terminer Focus, propositions Coach, localisation et notification de mobilité. Vérifier nom, rôle, état, ordre, focus visible, activation et possibilité de revenir en arrière. Noter les éléments illisibles, répétés ou inaccessibles. Désactiver TalkBack par le parcours système à la fin si l'utilisateur le souhaite.

Consigner le modèle et build Android, version de TalkBack, version APK, parcours essayé, durée et défauts observés. N'écrire `TALKBACK_REAL_PASS` qu'après le parcours réel complet et avec une preuve expurgée; sinon `FAIL`, `BLOCKED` ou `NOT_RUN` selon le cas.

## Mesures réelles CPU, réveils, réseau et batterie — D3

Mesurer séparément les deux conditions du prompt, sur le même appareil et avec des réglages aussi comparables que possible :

| Mode local seul | Mode coaching consenti |
| --- | --- |
| Backend désactivé | Agent configuré dans un environnement de test autorisé |
| Proactivité désactivée | Proactivité activée avec opt-in explicite |
| UsageStats désactivé | UsageStats autorisé pour une application synthétique/de test |
| Pas de mobilité | Pas de localisation continue |
| Pas de Focus actif | Aucun Focus actif pendant la fenêtre de repos mesurée |

Pour chaque fenêtre, noter une durée réellement chronométrée; fenêtre comparative proposée : **60 minutes par mode**, à prolonger si nécessaire. Noter niveau de batterie et état de charge au début/à la fin, écran, luminosité, réseau, mode économie, température disponible, applications en arrière-plan et toute interruption. Répéter chaque mode dans des conditions comparables si une différence est rapportée.

Une fenêtre locale AVD de 60 s à écran éteint a été mesurée lors d'une exécution précédente; CPU processus/threads et réseau UID y étaient `unavailable_snapshot`. La fenêtre coaching précédente était `BLOCKED`: l'AppOp Android était accordé mais l'allowlist ATLAS restait vide, sans cause localisée. Pour le rejeu du candidat CLEAN, D3 est `NOT_RUN`: avant la fermeture de l'AVD, `GET_USAGE_STATS` était `ignore` au niveau UID et package; aucune sélection/allowlist n'a été lue ni permission forcée. L'ancienne fenêtre locale n'est pas réutilisée, car l'état et l'exécution candidate ne sont pas continus. Une prochaine paire exige deux fenêtres de durée réelle identique et d'au moins 60 s, sans Focus, mobilité, E2E ni message Coach; confirmer l'état écran et le consentement avant de démarrer. Le protocole mesure CPU ATLAS, tâches planifiées et octets réseau exposés, mais pas les réveils/alarmes/wakelocks par application; un compteur absent reste `unavailable`, jamais zéro. Aucune projection AVD vers téléphone ni mesure batterie n'est permise. Voir [diagnostic du consentement](preuves-v622/s6-d3-consentement-ui-diagnostic.txt), [s6-protocole-repos-coaching.txt](preuves-v622/s6-protocole-repos-coaching.txt), [rapport D3 historique](preuves-v622/s6-d3-blocked-pause-urgence.txt) et [preuve du rejeu CLEAN](preuves-v622/s6-rejeu-clean-20261021.txt).

Les rejeux `0b5c…` puis `893da1…` n'ont pas exécuté D3. La preuve `0b5c…` consigne le retour antérieur d'AppOp à `ignore`; la reprise `893da1…` n'a modifié ni testé AppOp ou consentement coaching. Aucun des deux compléments UsageStats destructifs n'a été exécuté. Voir [preuve de restauration exacte](preuves-v622/s6-revocation-fixture-exact-restore-20261021.txt) et [preuve D3 historique](preuves-v622/s6-correction-quarantaine-20261021.txt).

Conserver les durées et valeurs mesurées avec le modèle d'appareil, build, outil/version, conditions et méthode. Résumer seulement les compteurs nécessaires; expurger UID, identifiants, URL privées, en-têtes, tokens et contenu. Ne pas publier des payloads réseau.

Une mesure AVD peut qualifier le comportement observé sur cet AVD seulement. Elle ne prédit pas l'autonomie d'un téléphone et ne devient jamais `BATTERY_REAL_PASS`. Même une mesure physique limitée n'autorise pas d'extrapolation arbitraire à une journée ou à une autonomie Pixel.

## Fiche de résultat pour un nouvel essai physique

Créer une ligne par scénario, sans données personnelles :

| Scénario | Type de preuve | Statut | Appareil/API ou AVD | Durée réelle | Référence de preuve expurgée | Écart/limite |
| --- | --- | --- | --- | --- | --- | --- |
| Objectif 120 / Focus 25 | appareil réel ou AVD explicitement étiqueté | `PASS` / `FAIL` / `BLOCKED` / `NOT_RUN` |  |  |  |  |
| Reprise / hors ligne / révocation | idem |  |  |  |  |  |
| HOME / matrice d'affichage | téléphone et AVD séparés |  |  |  |  |  |
| TalkBack | TalkBack physique réel requis |  |  |  |  |  |
| CPU / réveils / réseau | mesures réelles |  |  |  |  |  |

Les preuves brutes restent hors dépôt. Ne suivre qu'un résumé minimal expurgé. Compléter le statut global uniquement après les essais; ce guide ne constitue aucune preuve de validation.

