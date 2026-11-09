# V7-11B-B17 — Revue indépendante du design de binding V4

**Verdict : GO_WITH_LIMITATIONS local pour le design documentaire ; NO-GO pour exécuter la phase Relay dual tant que les P1 ci-dessous ne sont pas corrigés.** Aucun P0. Cette revue ne vaut aucune autorisation live.

| Référence | Valeur |
|---|---|
| BASE_SHA / HEAD examiné | `c533e17618e1ab8998b97eab4d62baff09916751` |
| Commit B16 | `c533e17618e1ab8998b97eab4d62baff09916751`, parent `256d517e1f97856dcd9e3431c75ae564f352919c` |
| Portée | Le commit B16 ajoute uniquement `V7-11B_BINDING_DESIGN.md` ; aucun code modifié. Arbre propre au début de la revue. |
| Exécution | Lecture du dépôt et des preuves uniquement. Aucun Docker, ADB, test, accès live, secret ou mutation SQLite exécuté. |

## Résultats par point

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

## Constatations

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

1. Corriger P1-01 et P1-02, puis faire relire le runbook corrigé sur un SHA distinct.
2. Répéter provisioning, audience, binding, HMAC, rejeu, mauvais key ID, map invalide/incomplète, redémarrage et rollback sur une copie synthétique v5.
3. Vérifier un backup frais Core et un backup cohérent du connecteur avec `identity.json`, `devices.json` et `operations.json` ; démontrer leur restauration sur copie.
4. Faire confirmer par le propriétaire le contrôle Paseo qui arrête et relance le connecteur, l’UID effectif des services, le volume Core réel et le comportement de reprise après échec.
5. Revoir P2-01 et les choix de partage du `device_ref`, grants, consentements, clés, cursor secret et principal TOOL. Maintenir le bearer app inchangé et ne pas importer de credential live sans approbation séparée.
6. Garder `GO_V7_11_RELAY_DUAL` à NOT_RUN/NO-GO et `GO_V7_11_LIVE_CHANGE_READINESS` à NO-GO jusqu’à satisfaction de ces conditions et autorisation ciblée.

**Limites :** cette revue vérifie la cohérence des documents et des sources au HEAD indiqué. Elle ne confirme aucun état live, n’exécute aucune commande proposée, et ne qualifie ni Android, ni le Relay, ni le binding en production.
