# V7-11B-B17 — Revue indépendante du design de binding V4

**Verdict actuel : GO local pour `GO_V7_11_BINDING_DESIGN` ; NO-GO pour exécuter la phase Relay dual tant que ses prérequis restent incomplets.** P1-01/02/03/04 sont fermés dans la dernière re-revue. Aucun P0 ; cette revue ne vaut aucune autorisation live.

| Référence | Valeur |
|---|---|
| BASE_SHA / HEAD de la revue initiale | `c533e17618e1ab8998b97eab4d62baff09916751` |
| HEAD du design à la dernière re-revue | `a59dd5cce1b47e363f0816f38382cbc669e69111` |
| Commit B16 | `c533e17618e1ab8998b97eab4d62baff09916751`, parent `256d517e1f97856dcd9e3431c75ae564f352919c` |
| Portée | Le commit B16 ajoute uniquement `V7-11B_BINDING_DESIGN.md` ; aucun code modifié. Arbre propre au début de la revue. |
| Exécution | Lecture du dépôt et des preuves uniquement. Aucun Docker, ADB, test, accès live, secret ou mutation SQLite exécuté. |

## Résultats initiaux par point

| # | Point | État | Conclusion |
|---|---|---|---|
| 1 | Base et périmètre | **PASS** | HEAD demandé et arbre propre. Le commit B16 n’ajoute que le document de design. Son SHA de base déclaré est bien son parent. |
| 2 | État actuel | **UNKNOWN live / PASS documentaire** | Les preuves concordent sur Core v5, zéro principal/credential/binding après migration, cache anti-rejeu en 0700, chat V2 observé en 200 et worker de sync dédié non observé. Le document de design rapporte le connecteur en fallback legacy, sans map ni assertion ; le registre compte 20 appareils et un seul appareil actif portant le libellé ciblé. La session connecteur active lors du chat et le compteur à zéro au relevé ultérieur sont deux instantanés cohérents. B2 décrit explicitement l’état antérieur v3. Je n’ai pas revalidé ces éléments sur le live : ils restent UNKNOWN indépendamment. |
| 3 | Modèle d’authentification | **FAIL limité** | Kinds, audiences, capacités, liaison Relay, assertion HMAC, key ID, cache anti-rejeu et modes `legacy`/`dual`/`principal` correspondent globalement au code. Une erreur est confirmée sur le secret de curseur en `dual` : sans `ATLAS_V4_CURSOR_SECRET`, le code ne crée pas une clé aléatoire par processus ; il dérive une clé déterministe du bearer fourni. Le détail figure en P2-01. |
| 4 | Gaps et options A–D | **PASS avec limites** | A est fonctionnellement dormant en mode legacy, mais crée des lignes actives à révocation monotone. B préserve bien l’authentification de `/v2/agent/chat` et ne change pas le bearer app ; le passage en dual peut cependant refuser le bearer historique du worker V4 s’il n’est pas inscrit. Ce risque est correctement documenté. La couverture des autres appareils Relay par la map B doit aussi être confirmée (P2-03). C et D séparent configuration directe, changement de mode et révocation ; D n’est pas présenté comme réversible. |
| 5 | Sécurité des commandes | **FAIL** | Aucune commande du document n’a été exécutée. Les exemples n’importent pas le bearer app, n’en changent ni ne révoquent sa valeur, et ne lancent ni ADB, ni APPLY, ni chat live. La séquence de phase 1 comporte toutefois deux lacunes P1 : elle arrête Core avant toutes les vérifications et n’est pas en mode strict ; elle cible ensuite un volume hôte sans vérifier qu’il est le `/data` du conteneur live arrêté. Les commandes `sudo`, `--force-recreate` et le wrapper cursor sont documentés comme opérations futures à contrôler, pas exécutées. |
| 6 | Prérequis et phases | **PASS avec limites** | Le plan exige répétition synthétique, sauvegarde/restauration Core et connecteur incluant `operations.json`, contrôle Paseo du connecteur, clés HMAC distinctes par key ID, cursor secret, cache persistant et décisions du propriétaire. La phase Relay reste NO-GO tant que le contrôle d’arrêt/reprise du connecteur et les prérequis ne sont pas validés. Le restore connecteur multi-fichier reste explicitement non atomique et interdit comme rollback live avant durcissement. |
| 7 | Sanitisation | **PASS avec réserve** | Aucun chemin personnel, bearer, secret, clé privée, identifiant live ou contenu privé n’apparaît dans B16. Les longues chaînes détectées sont des hashes/SHA acceptables. Les commandes montrent des noms génériques de sorties privées futures, pas des noms ou chemins live. |
| 8 | Verdict et gates | **PASS sous interprétation documentaire** | `GO_V7_11_BINDING_DESIGN` peut rester GO local uniquement pour l’acceptation du document. Il serait surévalué s’il autorisait l’exécution de la phase 1. `GO_V7_11_IDENTITY_BINDING` reste NOT_RUN ; `GO_V7_11_RELAY_DUAL` reste NOT_RUN/NO-GO ; `GO_V7_11_LIVE_CHANGE_READINESS` reste NO-GO. |

## Constatations initiales

### P1-01 — La phase 1 n’échoue pas sûrement avant la coupure Core

Dans le bloc de commandes de phase 1, `docker compose stop atlas-core` précède plusieurs contrôles de variables et de fichiers (`V7-11B_BINDING_DESIGN.md:243-274`). Le bloc n’active pas `set -eu` avant ces commandes. Une précondition invalide peut donc être ignorée dans un shell ordinaire, après l’arrêt de Core ; une erreur ultérieure n’a pas non plus de reprise automatique vers la configuration précédente. La note de rollback existe plus loin, mais elle n’est pas attachée à cette séquence.

**Correction minimale :** placer les contrôles non mutateurs avant l’arrêt, activer le mode strict dès le début, puis prévoir une sortie de secours vérifiée pour restaurer Core en legacy si la promotion dual n’a pas eu lieu. Garder le connecteur arrêté jusqu’à la vérification de son état et de sa map.

### P1-02 — La cible SQLite hôte n’est pas liée au conteneur live

La phase 1 construit `CORE_DB` à partir du volume nommé `atlas-core-data`, puis lance le CLI hors conteneur avec `sudo` (`V7-11B_BINDING_DESIGN.md:264-272`). Compose déclare ce nom dans la configuration du dépôt, mais B16 signale aussi un override live privé. La séquence ne vérifie pas que ce volume est effectivement le montage `/data` du conteneur live qui vient d’être arrêté. Si la configuration effective pointe ailleurs, les mutations pourraient viser une autre base. L’identité du montage live est UNKNOWN dans cette revue.

**Correction minimale :** résoudre le montage depuis le conteneur Core effectif arrêté, comparer son identité au volume attendu et refuser toute écriture si la comparaison échoue. Vérifier aussi l’UID propriétaire et l’intégrité du fichier cible avant et après l’usage du CLI privilégié.

### P2-01 — Le comportement par défaut des curseurs en dual est décrit à tort

B16 affirme qu’en l’absence de secret configuré, une clé aléatoire par processus invalide les curseurs au redémarrage (`V7-11B_BINDING_DESIGN.md:87,441`). Le code transmet `server_secret=None` en dual, puis `_cursor_key` dérive une clé de `android_token` par SHA-256 (`v4_router.py:2082-2094`) ; l’encodage et le décodage l’utilisent (`v4_router.py:2119-2125,2147-2155`). Cette clé est stable tant que le bearer reste identique, mais elle est connue du client détenteur de ce bearer. En mode principal, le démarrage exige bien un secret d’au moins 32 caractères (`v4_router.py:518-522`).

**Correction minimale :** corriger les deux affirmations sur le mode dual et décider si la garantie attendue est seulement la stabilité du curseur ou une intégrité avec clé serveur inconnue du client. Maintenir le secret stable si cette seconde garantie est requise.

### P2-02 — Les extraits C/D ne sont pas des procédures autonomes

Le texte de phase 2 exige un nouveau backup et l’arrêt de Core, mais son extrait CLI ne contient pas ces opérations ni le redémarrage (`V7-11B_BINDING_DESIGN.md:381-394`). La révocation de phase 3 a la même dépendance à un prérequis formulé en prose (`:396-405`). Ces extraits ne doivent pas être copiés seuls ; les intégrer à un runbook fail-closed ou les marquer clairement comme commandes partielles.

### P2-03 — La map de phase 1 ne couvre explicitement qu’un appareil

Le registre Relay documenté contient 20 entrées, tandis que le script de phase 1 génère une map pour le seul appareil actif portant le libellé ciblé (`V7-11B_BINDING_DESIGN.md:241,283-308`). Une map syntaxiquement valide garde V4 activé en mode dual ; pour une session dont l’ID n’est pas dans la map, `BackendClient` répond `OPERATION_UNAVAILABLE` (`integrations/relay/pc/src/connector.ts:88-103`, `integrations/relay/pc/src/backend.ts:111-117`). La preuve examinée n’établit pas que les autres entrées sont toutes révoquées ou hors usage.

**Condition :** avant B, établir de façon expurgée le nombre d’appareils actifs et confirmer le périmètre voulu. Inclure chaque appareil actif concerné dans la map et le registre, ou documenter explicitement la désactivation attendue pour les autres.

## Conditions avant toute phase live

1. Conserver les fermetures P1-01/02/03/04 consignées dans la dernière re-revue ; toute modification du runbook exige une nouvelle relecture sur son SHA.
2. Répéter provisioning, audience, binding, HMAC, rejeu, mauvais key ID, map invalide/incomplète, redémarrage et rollback sur une copie synthétique v5.
3. Vérifier un backup frais Core et un backup cohérent du connecteur avec `identity.json`, `devices.json` et `operations.json` ; démontrer leur restauration sur copie.
4. Faire confirmer par le propriétaire le contrôle Paseo qui arrête et relance le connecteur, l’UID effectif des services, le volume Core réel et le comportement de reprise après échec.
5. Maintenir le secret de curseur stable et trancher avec le propriétaire le partage du `device_ref`, les grants, consentements, clés et principal TOOL. Garder le bearer app inchangé et ne pas importer de credential live sans approbation séparée.
6. Garder `GO_V7_11_RELAY_DUAL` à NOT_RUN/NO-GO et `GO_V7_11_LIVE_CHANGE_READINESS` à NO-GO jusqu’à satisfaction de ces conditions et autorisation ciblée.

**Limites :** cette revue vérifie la cohérence des documents et des sources au HEAD indiqué. Elle ne confirme aucun état live, n’exécute aucune commande proposée, et ne qualifie ni Android, ni le Relay, ni le binding en production.

## Re-revue après correction

Revue statique du design corrigé au commit `be7dda658d2ba5fe01206a4cbb1a03998667056d`, qui ne modifie que le document de design. Cette section remplace les statuts initiaux des constats P1/P2 ci-dessus pour cette version. Aucune commande proposée, commande Docker, opération ADB, credential, bearer ou test live n’a été exécuté.

| Référence | État | Résultat de la re-revue |
|---|---|---|
| P1-01 — précontrôles et reprise Core | **CLOSED** | `set -euo pipefail` est la première instruction du bloc. Les validations de configuration, permissions, état du registre, arrêt préalable du connecteur, résolution du montage, contrôles SQLite en lecture seule et validation Compose précèdent `docker compose stop atlas-core`. Les contrôles qui ne sont possibles qu’après l’arrêt restent des barrières avant toute écriture CLI. Un trap est armé avant l’arrêt ; avant confirmation dual, il recrée Core avec la configuration legacy et vérifie son état démarré. Si cette récupération échoue, le texte exige de garder le connecteur arrêté et une intervention opérateur. |
| P1-02 — identité de la cible SQLite | **OPEN — résiduel P1** | Le bloc résout le montage `/data` depuis le conteneur, vérifie le volume nommé et sa source attendus, puis après l’arrêt vérifie le même conteneur, montage, source, chemin et tuple device/inode/UID/GID/mode. Il contrôle également le type de fichier, `user_version=5`, `integrity_check`, `foreign_key_check` et l’état vide du registre en SQLite lecture seule avant la CLI. Toutefois, le chemin est construit en ajoutant un nom de base fixe au montage : le bloc ne compare pas ce nom au `ATLAS_DB_PATH` effectif du conteneur, alors que `backend/atlas_backend/main.py:334` accepte un chemin configurable. De plus, `stat -L` et `test -f` suivent un éventuel lien symbolique et aucun contrôle `lstat`/de confinement canonique ne prouve que la cible reste dans le montage. Une configuration effective différente ou un lien vers une autre base pourrait donc laisser passer les contrôles puis faire écrire la CLI dans une cible non liée au fichier utilisé par Core. Fermer ce P1 en vérifiant le chemin DB effectif et en refusant tout lien ou cible hors montage, avant et après arrêt. |
| P2-01 — clé de curseur en dual | **CLOSED** | Le texte décrit désormais la dérivation SHA-256 depuis le bearer en dual : stable tant que le bearer ne change pas, mais calculable par son détenteur. Il demande un secret serveur stable d’au moins 32 caractères en principal et recommande de le maintenir dès dual pour l’intégrité serveur. |
| P2-02 — extraits C/D | **CLOSED, dépendances documentées** | Les deux extraits sont explicitement qualifiés de partiels et non autonomes. C dépend d’un backup vérifié, de l’arrêt Core, des contrôles de base et d’un redémarrage/oracles dual. D dépend de backups/restauration répétés, des mêmes contrôles et d’un redémarrage/oracles principal ; l’irréversibilité d’une révocation réussie est précisée. |
| P2-03 — périmètre de la map | **CLOSED sous précondition** | Le relevé du design indique 20 appareils inscrits, dont un seul actif portant le libellé `pixel-v7`. Le précontrôle exige exactement un appareil actif de ce libellé ; la génération et l’oracle imposent que la map couvre l’ensemble des appareils actifs. Les autres doivent être explicitement désactivés avant B. Tout écart au relevé entraîne NO-GO. Ce comptage n’a pas été revérifié sur le live pendant cette revue. |
| Sanitisation | **PASS** | Cette section ne reproduit ni chemin personnel, ni valeur de bearer/token/secret, ni clé privée, ni identifiant brut ou contenu privé. Les SHA et le libellé public déjà présent dans le design ne sont pas des identifiants bruts. |

### Verdict et gates actualisés

**0 P0 ; 1 P1 ouvert (P1-02).** Les corrections ferment P1-01 et les trois constats P2, mais l’identité de la cible SQLite reste insuffisamment liée au chemin DB effectif de Core. `GO_V7_11_BINDING_DESIGN` reste **NO-GO en l’état** jusqu’à fermeture de P1-02. `GO_V7_11_IDENTITY_BINDING` reste **NOT_RUN** ; `GO_V7_11_RELAY_DUAL` reste **NOT_RUN / NO-GO** ; `GO_V7_11_LIVE_CHANGE_READINESS` reste **NO-GO**. Aucun état live n’a été confirmé par cette re-revue.

Avant toute phase Relay dual : corriger puis faire relire P1-02 sur un nouveau SHA ; exécuter la répétition synthétique isolée ; démontrer le backup et la restauration Core et connecteur, y compris `operations.json` ; faire confirmer par le propriétaire le contrôle Paseo d’arrêt/reprise du connecteur ; conserver les clés HMAC distinctes par key ID, le secret de curseur stable et le cache anti-rejeu persistant ; obtenir les décisions du propriétaire et une autorisation ciblée avant tout changement live.

## Re-revue finale — P1-03 et clarification P1-04

Design examiné : `a59dd5cce1b47e363f0816f38382cbc669e69111`. Relecture statique du runbook et comparaison avec `v4_auth.py` et le schéma SQLite. Aucun runbook, Docker, ADB, test, accès live, secret ou bearer n’a été exécuté, consulté ou modifié. Seul ce rapport est mis à jour.

| Point | État | Résultat |
|---|---|---|
| P1-01 — contrôles préalables et reprise Core | **CLOSED** | `set -euo pipefail` ouvre le bloc. Les contrôles non mutateurs et la condition d’arrêt du connecteur précèdent `docker compose stop atlas-core`. Le trap de retour Core en legacy est armé avant cet arrêt et reste actif jusqu’à validation explicite des oracles dual. |
| P1-02 — identité de la cible SQLite | **CLOSED** | Avant l’arrêt, le runbook lit l’`ATLAS_DB_PATH` effectif, n’accepte que `/data/atlas.sqlite3`, et vérifie le conteneur et le volume `/data` attendus. Après l’arrêt, il réinspecte le même conteneur et compare chemin, source et volume. Les contrôles `lstat`/`realpath` refusent les composants symlink et les cibles non régulières ; les chemins du montage et de la base doivent désigner le même inode. Propriétaire, mode, version 5, intégrité et clés étrangères sont vérifiés avant/après arrêt et autour des CLI. |
| P1-03 — oracle de mutations | **CLOSED** | `run_cli_readonly` exige le fingerprint complet (dont SHA-256), l’état de santé et le snapshot logique inchangés autour de `preview-migration`. Les quatre CLI mutatrices passent par `run_cli_mutating`, qui ne compare pas le hash global ; il vérifie l’identité de la cible et l’état de santé puis compare les multisets canoniques de toutes les tables. Les snapshots sont des fichiers privés mode 0600. |
| Deltas CLI et code réel | **PASS statique** | L’allowlist exige pour `principal add` une insertion ANDROID/ACTIVE et son événement `PROVISIONED` avec `kind`; pour `credential add`, un credential RELAY/ACTIVE dont le hash correspond au SHA-256 du bearer lu en mémoire, plus `PROVISIONED` avec l’audience ; pour `binding add`, un binding actif et `RELAY_BOUND` avec les métadonnées attendues ; pour `capability set`, le grant à 1 et `CAPABILITY_CHANGED`. Une capability déjà accordée accepte uniquement l’absence exacte de delta, conformément au no-op de `set_capability`. Les colonnes et noms de tables concordent avec `v4_auth.py` et le schéma. Tout autre ajout, modification ou retrait, y compris d’une ligne préexistante, échoue. Le chemin de registre initial vide produit les quatre événements prévus ; le contrôle final vérifie les compteurs 1/1/1/1/4 et l’identité de la cible. |
| P1-04 — hypothèse de `TypeError` sur BLOB | **CLOSED — faux positif retiré** | Le signalement précédent attribuait à tort à `canon(row)` la sérialisation de dictionnaires décodés. `canon` reçoit seulement les lignes encore encodées du snapshot ; un BLOB y reste représenté par le couple typé base64. `decode()` construit les dictionnaires de `changes` et sert aux contrôles sémantiques, mais ces dictionnaires ne sont pas passés à `json.dumps`. Je ne trouve donc aucun chemin produisant le `TypeError` annoncé. Une ligne BLOB inchangée est comparée sous sa forme encodée ; une variation de BLOB crée un delta qui échoue sur l’allowlist des tables. Aucun test synthétique n’a été exécuté dans cette re-revue. |
| Régressions, sorties et sanitisation | **PASS statique** | Aucune régression P1-01/P1-02 observée ; trap legacy et ordre Core/connecteur restent documentés. Les reçus et sorties CLI sont redirigés vers l’espace privé, le traçage shell est désactivé et le bearer n’est pas imprimé. Cette section n’ajoute aucun chemin personnel, bearer/token, clé privée, identifiant live brut ou contenu privé. |

### Verdict et gates

**P1-03 CLOSED ; P1-04 CLOSED ; 0 nouveau P0, P1 ou P2.** `GO_V7_11_BINDING_DESIGN` est **GO local** pour l’acceptation du design documentaire. La phase B / Relay dual reste **NO-GO** jusqu’à une répétition isolée réussie, la vérification de sauvegarde et restauration Core/connecteur incluant `operations.json`, le contrôle Paseo d’arrêt/reprise du connecteur, les décisions du propriétaire et une autorisation ciblée. Maintenir les clés et key ID distincts, le secret de curseur stable et le cache anti-rejeu persistant. `GO_V7_11_IDENTITY_BINDING` reste **NOT_RUN** ; `GO_V7_11_RELAY_DUAL` reste **NOT_RUN / NO-GO** ; `GO_V7_11_LIVE_CHANGE_READINESS` reste **NO-GO**.

**Limites :** cette conclusion porte sur le texte du design et sa cohérence statique avec le code au SHA indiqué. Elle ne confirme aucun état live, n’exécute aucune commande proposée et ne constitue pas une autorisation d’exécution live.
