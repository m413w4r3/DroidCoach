# ATLAS V7-07 — Recette du pilote produit quotidien

**Date :** 2026-11-01
**BASE_SHA :** `32e7c70c78fbe295f66f67d6be50dc797a3f6e6d`
**Statut :** recette préparée. **Exécution 2026-11-04 :** installation produit
et smoke local ciblé PASS ; transport public WSS/Noise PASS et synchronisation
V4 PASS, mais aucun tour outillé fiable ni proposition A0 ; `GO_PRODUCT_REMOTE_CHAT`
et `GO_PRODUCT_PLAN_REVISION` sont **NO-GO**. Aucune période quotidienne.
Détail : `V7-07_D_RESUME.md`.

## 1. Principe

Le pilote commence par un **smoke non destructif** sur l'application produit
réellement installée, puis, seulement si le smoke est PASS et si les
autorisations sont données, par une période supervisée de plusieurs jours.

Le pilote utilise une **activité d'essai synthétique explicitement désignée**.
Aucun rendez-vous réel, aucune habitude réelle, aucun objectif personnel ne doit
être déplacé, modifié ou supprimé pour la recette.

### Activité d'essai

- Date/heure choisies avec le propriétaire, sur un plan de test dédié.
- Titre synthétique du type `Essai ATLAS V7-07`.
- Identifiant d'activité généré pour l'essai, jamais réutilisé.
- Durée courte (10–15 min), sans conséquence sur un engagement réel.
- Aucun événement calendrier réel, aucune habitude réelle.
- La proposition A0 de test ne porte que sur cette activité.

## 2. Préconditions bloquantes

1. `GO_SOURCE_AND_CI` = GO sur le SHA du candidat.
2. `GO_DATA_BACKUP` et `GO_DATA_RESTORE` démontrés (AVD jetable au minimum,
   produit réel si autorisé), avec archive hashée et restauration vérifiée.
3. `GO_HOME_ROLLBACK` démontré sur AVD et procédure de retour HOME acceptée.
4. `GO_LIVE_OPENCLAW_CONFIG` = GO : modèle qualifié, six outils client,
   `tools.effective` sans outil interdit, plugin natif concurrent désactivé.
5. `GO_LIVE_SECURITY` = GO : identités de test révoquées, consentements
   réconciliés, bearer produit distinct ou risque résiduel explicitement
   accepté.
6. `GO_PRODUCT_APK_CANDIDATE` = GO et revue indépendante sans P0/P1.
7. Autorisations ciblées A–E accordées, au moins partiellement.
8. Pixel connecté, un seul opérateur ADB, batterie et stockage suffisants.

Si une précondition échoue, le pilote ne démarre pas.

## 3. Smoke produit — 16 points

Chaque point est observé sur l'application produit réellement installée, avec
une preuve expurgée. Un seul échec bloquant arrête le pilote.

| # | Point | Observation attendue | Arrêt si |
|---|---|---|---|
| 1 | HOME accessible | appui HOME ouvre ATLAS ; retour au launcher possible | HOME ne s'ouvre plus ou boucle |
| 2 | Applications essentielles | téléphone, messages, appareil photo, navigateur, réglages se lancent | une app essentielle ne se lance plus |
| 3 | Pause d'urgence hors ligne | mode avion, pause activée, état persistant ; reprise après redémarrage | pause inopérante ou non persistante |
| 4 | Données locales préexistantes | plans, objectifs, habitudes, Focus et réglages visibles avant/après update | perte ou quarantaine inattendue |
| 5 | Timers et rappels locaux | un timer Focus et un rappel de test se déclenchent hors ligne | aucun déclenchement ou doublon |
| 6 | Connexion Relay | l'écran affiche l'état réel (connecté/déconnecté/erreur), pas un faux succès | état mensonger ou blocage |
| 7 | Consentements | désactivation et révocation accessibles, effet immédiat | consentement non révocable |
| 8 | Coach réel | réponse du modèle réellement configuré, sans fallback mock | réponse mock présentée comme réelle |
| 9 | Lecture DayPlan | l'outil retourne le plan autorisé, version et fraîcheur exactes | plan non autorisé ou version fausse |
| 10 | Proposition A0 | une proposition structurée, en attente de revue, aucune application automatique | proposition appliquée sans accord |
| 11 | APPLY | seule l'activité d'essai est modifiée ; version v→v+1 ; ACK APPLIED | autre activité modifiée |
| 12 | REJECT | plan inchangé, aucune nouvelle version, ACK REJECTED | modification malgré rejet |
| 13 | ACK durable | ACK retrouvé côté Core après redémarrage ; idempotent | ACK perdu ou dupliqué |
| 14 | Coupure réseau | le launcher et les fonctions locales restent disponibles | launcher bloqué |
| 15 | Reconnexion | aucune double proposition, aucun double effet | doublon |
| 16 | Redémarrage app | état cohérent, pas de quarantaine ni de perte | corruption |

### Preuves du smoke

- hash du candidat et version installée ;
- `versionCode`/`versionName`, certificat, `lastUpdateTime` avant/après ;
- état du rôle HOME avant/après ;
- plan local avant/après (version, activité d'essai, hash expurgé) ;
- identifiant de proposition haché, état, `tool_state`, nombre d'appels ;
- ACK : état, `applied_plan_version`, rejeu identique ;
- latence du tour et code d'erreur éventuel ;
- aucune capture d'écran complète, aucun contenu personnel.

## 4. Pilote supervisé — durée et seuils

### Durée proposée

- **7 jours consécutifs minimum**, dont au moins **5 jours avec usage réel de
  test**.
- **5 démarrages froids** documentés.
- **2 transitions réseau** Wi‑Fi ↔ LTE.
- **2 pertes puis retours Internet**.
- **1 redémarrage contrôlé** du Core/Relay/connecteur.
- **1 indisponibilité du Gateway** simulée puis rétablie.
- Toute prolongation au-delà de 7 jours est une décision explicite, pas une
  reconduction automatique.

Le pilote n'est pas supervisé en permanence par un agent : les mesures sont
collectées lors de sessions planifiées et par les journaux techniques expurgés.

### Seuils de succès

| Domaine | Seuil |
|---|---|
| Intégrité des données | 0 perte, 0 quarantaine inattendue, 0 plan écrasé |
| Effets | 0 modification hors activité autorisée, 0 APPLY sans validation |
| Idempotence | 0 double proposition, 0 double ACK, rejeu identique |
| HOME | rôle inchangé hors action volontaire ; retour au launcher testé |
| Disponibilité locale | launcher, Focus, rappels et pause utilisables hors ligne à chaque essai |
| Erreurs | états d'erreur exacts ; aucun succès simulé |
| Crash | 0 crash bloquant ; taux de sessions sans crash ≥ 99 % |
| Batterie | pas de wakelock anormal ; consommation veille ≤ 3 %/h en moyenne, ou écart ≤ 1,5× la baseline mesurée avant pilote |
| Réseau | reconnexions sans doublon ; erreurs correctement présentées |
| Sécurité | aucun secret/donnée personnelle dans les journaux ; aucun accès non consenti |
| Modèle | réponses réelles du modèle configuré, sans fallback silencieux |

Les seuils batterie sont indicatifs et doivent être confirmés par une baseline
mesurée avant le pilote. Un seuil non atteint est un `FAIL` ou une limitation
explicite, pas un succès.

## 5. Métriques techniques expurgées

Collecter uniquement :

- versions, hashes d'APK, certificat, SDK ;
- états `LOCAL_ONLY`/`SYNCED`/`ERROR`/`REVOKED`, compteurs de files ;
- latence des tours, codes d'erreur bornés, `tool_state`, nombre d'appels ;
- identifiants de proposition/ACK **hachés** ;
- versions de plan et résultats APPLY/REJECT ;
- événements réseau (type, durée, reconnexion) sans SSID/adresse ;
- batterie (pourcentage/heure, wakelocks) sans usage applicatif détaillé ;
- rôle HOME avant/après ;
- crashs et ANR (type, pile expurgée).

Ne jamais collecter : contenu des messages, titres réels d'activités, objectifs,
habitudes, données de calendrier, localisation, identifiants bruts, tokens,
captures d'écran complètes.

## 6. Arrêt et rollback

### Arrêt immédiat

- toute perte de données ou modification hors activité autorisée ;
- HOME cassé ou applications essentielles inaccessibles ;
- APPLY sans validation, double effet, ACK incohérent ;
- crash loop, corruption, quarantaine inattendue ;
- fuite de secret ou accès non consenti ;
- consentement impossible à révoquer ;
- batterie anormale confirmée.

### Procédure d'arrêt

1. Pause d'urgence et désactivation du suivi/consentements.
2. Arrêt des workers et du transport produit si possible.
3. Révocation de l'identité produit côté connecteur.
4. Conservation des preuves techniques expurgées.
5. Rollback APK : arrêter l'app, réinstaller l'APK précédent avec
   `adb install -r`, vérifier signature/versionCode.
6. Rollback données : restaurer l'archive vérifiée si l'état est incompatible.
7. Rollback HOME : choisir le launcher précédent via les réglages Android et
   vérifier le rôle.
8. Ne pas `pm clear`, ne pas désinstaller sans nécessité et sans autorisation.

## 7. Autorisations

Le pilote exige des scopes indépendants :

- **A — Sécurité live :** révocation des identités de test, réconciliation des
  consentements, rotation éventuelle du bearer.
- **B — Gateway/Core :** configuration, redémarrage contrôlé, sauvegarde et
  rollback.
- **C — APK produit :** mise à jour in-place avec sauvegarde vérifiée.
- **D — Appairage et recette distante :** identité produit, consentements,
  test OpenClaw réel sur l'activité d'essai.
- **E — Pilote quotidien :** durée, seuils, collecte expurgée.

**Dépendance des scopes :** le smoke produit (D) exige au minimum A, B et C
autorisés et GO ; le pilote quotidien (E) exige en plus D GO et une nouvelle
autorisation explicite de durée. Une autorisation « partielle » ne suffit pas
à démarrer E : elle limite l'exécution aux seules phases dont les scopes sont
accordés. Aucun scope n'est implicite par l'accord d'un autre.

## 8. Verdicts

| Verdict | Condition |
|---|---|
| `GO_PRODUCT_LOCAL_SMOKE` | 16/16 points PASS sur le produit installé |
| `GO_PRODUCT_REMOTE_CHAT` | chat réel produit + modèle configuré + outils corrélés |
| `GO_PRODUCT_PLAN_REVISION` | proposition/APPLY/REJECT/ACK sur l'activité d'essai |
| `GO_PRODUCT_REMOTE` | parcours complet produit avec la configuration destinée à l'usage personnel |
| `GO_DAILY_PILOT` | smoke PASS + 7 jours supervisés aux seuils §4 + restauration maîtrisée + fonctionnement local sûr |

**Distinction finale :**

- **Apte à commencer un pilote quotidien** : gates préparatoires GO, smoke PASS,
  rollback testé.
- **Fiable pour un usage quotidien durable** : plusieurs journées conformes aux
  seuils, aucune perte, aucun effet non autorisé, supervision et restauration
  éprouvées.

Un smoke PASS ne suffit jamais à déclarer la fiabilité durable. Un pilote de
7 jours ne suffit pas non plus à garantir un usage indéfini sans supervision.
