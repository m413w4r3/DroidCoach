# Revue indépendante de sécurité V7-11-A4

| Champ | Résultat |
|---|---|
| TASK_ID | `V7-11-A4` |
| Profil constaté | `codex:gpt-6-luna` / `xhigh`, vérifié par le parent Paseo |
| Branche | `audit/v7-11-a4-security-review` |
| BASE_SHA | `8dac699cdb37e2f858a76e4cf7f9fc3bcd613e9c` |
| RESULT_SHA examiné | `8dac699cdb37e2f858a76e4cf7f9fc3bcd613e9c` |
| Portée du code | Aucun delta par rapport à la base; revue de sécurité indépendante du code V4/Core et Relay PC demandé |
| Verdict | **NO_GO**; `GO_V7_11_INDEPENDENT_SECURITY_REVIEW: NO_GO` |

Un P1 confirmé empêche le GO : le mode `legacy` réaccepte un bearer après sa révocation dans le registre quand ce bearer reste configuré comme `ATLAS_TOKEN`. Les quatre réserves P2 restent ouvertes. Cette conclusion ne qualifie aucun service live.

## Constats

### P1 — Le mode Legacy réaccepte un credential LEGACY révoqué

**Classe :** `DEFECT_CONFIRMED`.

**Emplacements :** `backend/atlas_backend/v4_router.py:767-789`, surtout `:769-784`.

**Scénario :** un bearer est importé comme credential `LEGACY`, puis révoqué dans `credentials_v4`. Après un rollback/reparamétrage en `ATLAS_V4_AUTH_MODE=legacy`, si l’ancien bearer est encore configuré dans `ATLAS_TOKEN`, `require_android()` le compare directement à cette variable et construit un contexte `legacy-android` sans appeler `registry.resolve()`. Le tombstone `REVOKED` n’est alors pas consulté et un endpoint de mutation V4 accepte de nouveau ce bearer.

**Reproduction/oracle :** avec un bearer uniquement synthétique et une base SQLite temporaire, le test a importé le bearer LEGACY, appelé `revoke_credential()`, vérifié `registry.resolve(bearer) is None` et l’état stocké `REVOKED`, puis envoyé une requête réelle à `/v4/sync/consent-state` sur une nouvelle application configurée en mode `legacy` avec le même `ATLAS_TOKEN`. Résultat : `200`. Sur la même base et avec le même bearer, les modes `dual` et `principal` renvoient `401 AUTH_REQUIRED`. Le test a traversé l’application ASGI et le routeur V4; il n’a utilisé ni mock d’authentification ni service live.

**Mitigation minimale :** avant tout rollback post-révocation, faire tourner `ATLAS_TOKEN` vers un nouveau bearer et ne jamais remettre l’ancien. Correction de défense recommandée : faire consulter au chemin Legacy le tombstone du hash du bearer et refuser un credential révoqué; ajouter un test de non-réactivation après reconstruction de l’application. Les modes `dual` et `principal` ne présentent pas ce comportement dans la reproduction.

### P2 — Réserve : variable globale vide conserve les mutations activées

**Classe :** `RISK` / ambiguïté de configuration.

**Emplacement :** `backend/atlas_backend/v4_router.py:400-414`, surtout `:403-404`.

**Scénario :** un déploiement injecte `ATLAS_V4_CAPABILITIES_JSON` comme chaîne vide en pensant désactiver les opérations. `_configured_capabilities()` traite cette valeur comme absente et conserve les valeurs par défaut, où les capabilities de mutation sont actives pour compatibilité. Les grants du principal et les autres contrôles restent requis; ce cas ne contourne donc pas à lui seul ces contrôles.

**Reproduction/oracle :** appel direct en lecture à `_configured_capabilities()` avec `ATLAS_V4_CAPABILITIES_JSON=""` renvoie `True` pour `consent.state.write` et `proposal.ack`. Le comportement pour une variable absente est documenté comme activant les mutations par défaut dans `docs/operations/V7-10_ARCHITECTURE.md:33,108`; la valeur vide n’est pas explicitement documentée.

**Recommandation :** distinguer `None` de la chaîne vide et refuser une valeur explicitement vide au démarrage, ou documenter clairement que vide équivaut à absent. Pour un arrêt d’urgence, fournir les overrides `false` et vérifier le résultat effectif au démarrage.

### P2 — Risque : cache anti-rejeu Relay limité au processus

**Classe :** `RISK` connu, également noté dans la preuve A6 de V7-10.

**Emplacements :** `backend/atlas_backend/v4_router.py:522`; `backend/atlas_backend/v4_assertion.py:110-134,161-173`.

**Scénario :** si deux workers Core reçoivent la même assertion HMAC encore dans sa fenêtre de validité de ±120 secondes, chacun a son propre `ReplayNonceCache` et peut accepter le même couple `key_id`/nonce une fois. La limite de capacité de chaque cache échoue bien fermée, mais elle ne coordonne pas les workers.

**Reproduction/oracle :** une même assertion signée passée à deux instances indépendantes de `ReplayNonceCache` est acceptée par chacune; dans une seule instance, son rejeu est refusé. Le test backend `test_relay_assertion_binds_credential_to_active_session_and_rejects_replay` valide le rejeu mono-processus, pas le rejeu entre processus.

**Recommandation :** utiliser une réservation de nonce atomique partagée entre workers, ou imposer et surveiller un seul worker jusqu’à l’ajout de ce stockage. Ajouter un test multiworker avant de qualifier le déploiement correspondant.

### P2 — Réserve : permissions et identité du fichier SQLite non imposées

**Classe :** `DEFECT_CONFIRMED` pour l’absence de durcissement dans `SQLiteStore`; exposition effective conditionnelle au chemin et aux permissions du système de fichiers.

**Emplacements :** `backend/atlas_backend/store.py:101-108`.

**Scénario :** `SQLiteStore` crée les répertoires avec les modes hérités de `umask`, puis ouvre la base par chemin sans imposer le mode du fichier, vérifier son UID/GID, ni refuser un lien symbolique. Avec une base dans un répertoire bind-mount traversable ou partagé, un autre utilisateur local peut lire des projections et consentements stockés; un chemin remplacé par symlink peut aussi faire ouvrir une autre base.

**Reproduction/oracle :** création d’une base temporaire sous `umask 022`: le répertoire parent obtenu est en `0755` et le fichier en `0644`. La vérification a utilisé uniquement des données synthétiques locales et n’a pas touché de base existante.

**Recommandation :** créer/valider un répertoire privé (`0700`) et une base privée (`0600`), vérifier l’UID/GID attendu et refuser les chemins symlinkés. Pour une protection contre le remplacement de chemin, ouvrir/valider l’objet fichier et son identité avant l’usage, ou imposer un répertoire de données privé non modifiable par d’autres identités.

### P2 — Réserve : chemins locaux dans des preuves versionnées

**Classe :** `DEFECT_CONFIRMED`, exposition d’informations de chemin dans des preuves suivies par Git.

**Emplacements :** `docs/operations/evidence/V7-01B_READONLY_AMBIGUITIES_AUDIT.md:23`, `docs/operations/evidence/V7-06A_TEST_RED.md:28`, `docs/operations/evidence/V7_INTEGRATION_ANDROID_RELAY.md:17`.

**Scénario :** les preuves divulguent des chemins absolus du poste, ce qui révèle des noms locaux de compte ou d’espace de travail à toute personne ayant accès au dépôt. Aucun de ces chemins n’est recopié dans ce rapport.

**Reproduction/oracle :** recherche statique de chemins absolus sous `/home/` dans les trois fichiers indiqués; aucune donnée de credential n’a été incluse dans le résultat du rapport.

**Recommandation :** remplacer ces valeurs par des chemins relatifs ou un marqueur générique, puis rescanner les preuves versionnées.

## Contrôles examinés et résultats

- **Séparation d’identité et d’audience :** le principal Core est résolu depuis le credential bearer haché côté serveur. Le principal n’est pas choisi par le payload client. En mode Relay, Core vérifie l’assertion contre la liaison de session Relay active et le credential/principal/appareil enregistrés. Les audiences `DIRECT`, `RELAY`, `TOOL` et `LEGACY` sont traitées distinctement. Les chemins de compatibilité Legacy sont explicitement sélectionnés; aucun fallback Relay silencieux n’a été trouvé.
- **Credentials inconnus/révoqués et refus A/B :** la résolution échoue fermée pour les credentials absents ou révoqués. Les tests d’authentification utilisent deux principaux synthétiques et vérifient les refus de credential croisé ainsi que les bindings de session.
- **Capabilities, arrêt d’urgence et consentements :** une opération exige le flag global et le grant du principal; le payload ne peut pas ajouter de droits. Les flags `false` coupent les opérations; les consentements serveur sont contrôlés séparément. Les métadonnées TOOL authentifiées ne donnent pas les autres droits TOOL.
- **Ownership et isolation :** les routes contrôlent le propriétaire pour les propositions et ACK; les lectures des snapshots et consentements sont bornées au principal/appareil attendu. Les tests ciblés exercent les refus croisés et les relectures avec des bases SQLite temporaires.
- **HMAC et anti-rejeu :** la signature couvre méthode, chemin, timestamp, nonce, identité appareil et hash du corps; `key_id` est recherché dans le jeu de clés configuré et une clé inconnue est refusée. La fenêtre temporelle est de ±120 s. Le cache est verrouillé et borné; une saturation refuse les nouvelles assertions. Le défaut restant est sa portée par processus, décrit plus haut.
- **Fichiers privés Relay/Core :** la lecture du code confirme les contrôles de chemins absolus, fichier régulier, propriétaire UID du processus, mode privé, `O_NOFOLLOW` et comparaison d’identité inode/dev autour de la lecture pour les clés/configurations concernées. Le GID n’est pas vérifié séparément; le mode `0600` interdit cependant les accès groupe. Les contrôles Relay de frontière du dépôt et les scripts `check-config`/`check-secret` masquent les secrets et valident leur source. Le montage compose par défaut est un volume nommé détenu par l’utilisateur non-root Core; son mode de répertoire n’est pas forcé à `0700`. Aucun test live du conteneur ou d’un bind mount n’a été exécuté. L’exécution dynamique des tests Relay n’a pas été possible dans cet environnement.
- **Expiration et ACK :** l’expiration est terminale et sérialisée avec la création d’audit dans la transaction; le journal d’audit est distinct des reçus ACK et append-only. Une expiration serveur ne fabrique pas un ACK Android. L’état `UNCERTAIN` est préservé. Les tests ciblés couvrent les courses, rollback/erreurs, les ACK prématurés et les relectures/idempotences.
- **Commandes et dépendances :** les scripts de migration/réconciliation sont par défaut en dry-run et exigent sauvegarde et mode privé pour l’application. Les appels subprocess examinés transmettent des tableaux d’arguments plutôt qu’une commande shell interpolée; la reconstruction Relay est épinglée à un commit et vérifie l’intégrité de l’archive avant écriture optionnelle. Les dépendances directes déclarées sont épinglées ou bornées dans les manifestes consultés. Aucun audit CVE/advisory de dépendances n’a été lancé.
- **Secrets et journaux :** une recherche statique de motifs de credentials/clefs dans les fichiers suivis n’a révélé aucun secret réel identifiable; les correspondances observées étaient des placeholders ou des motifs de scripts. Ce contrôle par motifs n’est pas un scanner exhaustif. Les seuls constats de confidentialité sont les chemins locaux listés ci-dessus.

## Exécution et limites

- Depuis `backend/`, la suite ciblée `python -m pytest tests/test_v4_principal_auth.py tests/test_v4_principal_cli.py tests/test_v4_proposals.py tests/test_v4_proposal_expiration.py tests/test_v4_projection_store.py tests/test_v4_routes.py tests/test_v4_migrations.py -q` a passé : **82 passed, 1 warning**. Les tests utilisent SQLite temporaire et des routes ASGI réelles; ils ne sont pas des mocks tautologiques.
- `npm test` dans `integrations/relay/pc` s’est arrêté avant Vitest : `tsc: not found` (dépendances locales absentes). Aucun paquet n’a été installé et aucun accès réseau n’a été tenté. Les tests du connecteur et ses scripts de vérification sont donc `NOT_RUN` dans cette revue.
- La simulation anti-rejeu et la vérification des modes SQLite ont utilisé uniquement des données synthétiques temporaires.
- Le contrôle supplémentaire de révocation a utilisé un bearer synthétique, un registre V4 et une base SQLite temporaire; résultat : `legacy=200`, `dual=401`, `principal=401`. `TestClient` n’a pas rendu de réponse dans le délai d’exécution; le résultat cité provient d’un client `httpx.ASGITransport` traversant l’application et la route ASGI réelles, comme le client helper des tests ciblés existants.
- Aucun Core/Relay live, migration réelle, commande d’application destructive, ADB, émulateur ou Pixel physique n’a été utilisé. Les tests Android 17/API 37 et toute qualification live restent `NOT_RUN`.

## Liste de sévérité

| Sévérité | Nombre | Résumé |
|---|---:|---|
| P0 | 0 | Aucun constat confirmé |
| P1 | 1 | Bearer LEGACY révoqué réaccepté après rollback en mode `legacy` |
| P2 | 4 | Variable capabilities vide; rejeu entre workers; permissions/identité SQLite; chemins locaux dans les preuves |

## Re-revue A4-F1 sur 65759dd

Cette re-revue porte sur l’arbre intégré `65759dded28380c52639059b9cee8febadbd799c`, qui contient `2c3e1788601d1640f1207cfbc2e1418f10cbd83f`. Elle complète et remplace le verdict P1 de la revue ci-dessus pour ce correctif intégré; elle ne change pas les réserves P2 déjà consignées.

| Champ | Résultat |
|---|---|
| Verdict de la re-revue | **GO avec réserves P2**; `GO_V7_11_INDEPENDENT_SECURITY_REVIEW: GO` |
| P1 V7-11-A4-F1 | **Fermé** pour le chemin normal du registre et les redémarrages/reconstructions d’application |
| Archive examinée | `/tmp/v7-11-a4-final` extraite par `git archive 65759dd`; aucun fichier de code du worktree partagé modifié |
| Fichiers touchés par `2c3e178` | `backend/atlas_backend/v4_auth.py`, `backend/atlas_backend/v4_router.py`, `backend/tests/test_v4_principal_auth.py`, `docs/operations/evidence/V7-11_A1_CORE_MIGRATION_EVIDENCE.md` |

Le correctif ajoute `V4PrincipalRegistry.credential_state(token)`, qui cherche uniquement le hash d’un credential d’audience `LEGACY`, renvoie `None` si la table n’existe pas et propage les autres erreurs SQLite. `require_android()` refuse ensuite un credential LEGACY dont l’état est `REVOKED` avec la réponse générique `401 AUTH_REQUIRED` (`backend/atlas_backend/v4_auth.py:78-96`, `backend/atlas_backend/v4_router.py:767-786`).

### Reproduction des scénarios a–h

Tous les scénarios ci-dessous ont utilisé des bearers synthétiques, des bases SQLite temporaires dans `/tmp` et des requêtes via `httpx.ASGITransport` vers l’application et le routeur réels. `ATLAS_TOKEN` a été configuré par l’environnement. Aucun mock d’authentification, service live ou donnée utilisateur n’a été utilisé.

Pour d, l’application a d’abord initialisé sa base temporaire, puis le test a supprimé `credentials_v4` et fixé `PRAGMA user_version=3` afin d’exercer le chemin « table d’auth absente / schéma inférieur à 4 ». Ce n’est pas une migration indépendante depuis une image de base V3 historique.

| Cas | Scénario | Résultat |
|---|---|---|
| a | Credential `LEGACY` actif, mode `legacy` | **PASS** — état `ACTIVE`, requête `200` |
| b | Credential `LEGACY` révoqué, puis application reconstruite avec la même base et le même `ATLAS_TOKEN` | **PASS** — état `REVOKED`, requête `401` |
| c | Bearer jamais importé, mode `legacy` | **PASS** — aucun tombstone, requête `200` pour préserver la compatibilité historique |
| d | Table `credentials_v4` absente et `user_version=3` dans la base temporaire | **PASS** — requête `200`, aucun `500` |
| e | Bearer configuré comme `ATLAS_TOKEN`, seule entrée correspondante révoquée avec l’audience `DIRECT` | **PASS** — `credential_state()` ignore cette audience, requête Legacy `200` |
| f | Principal `LEGACY` révoqué par `revoke_principal()` | **PASS** — ses credentials passent à `REVOKED`, requête `401` |
| g | Modes `dual` et `principal`, credential `DIRECT` actif puis révoqué | **PASS** — actif `200`, révoqué `401` dans les deux modes |
| h1 | Révocation LEGACY, puis `UPDATE` SQLite direct de l’audience `LEGACY` vers `DIRECT` | **OBSERVÉ** — le lookup ne trouve plus de tombstone et le mode Legacy répond `200` |
| h2 | Révocation LEGACY, puis `UPDATE` SQLite direct du `token_hash` vers un autre hash | **OBSERVÉ** — le lookup ne trouve plus de tombstone et le mode Legacy répond `200` |
| h3 | `ATLAS_TOKEN` remplacé après révocation | **PASS** — ancien bearer `401`; nouveau bearer jamais importé `200`, selon le comportement de compatibilité Legacy |

Les résultats h1/h2 nécessitent une écriture SQL directe de la base; aucun appel de l’API/registre standard n’a changé l’audience ou le hash. Ils exposent une **limite P2 d’intégrité de la base** : le trigger protège la monotonie du champ `state`, mais les colonnes `audience` et `token_hash` ne sont pas immuables (`backend/atlas_backend/store.py:51,60`; lookup `backend/atlas_backend/v4_auth.py:84-96`). La réponse `200` si la table est absente est aussi une compatibilité explicitement testée dans `test_legacy_mode_accepts_historical_bearer_when_registry_table_is_absent`; ces chemins ne protègent donc pas contre un acteur qui peut modifier ou retirer le registre SQLite. Garder la base inaccessible en écriture aux identités autres que Core; des triggers d’immutabilité sur `audience` et `token_hash` réduiraient les erreurs de modification accidentelle. Ce cas requiert un accès d’écriture direct à la base et ne reproduit pas l’ancien contournement à distance par simple présentation du bearer révoqué.

### Tests sur l’archive et limites

- `python -m pytest tests/test_v4_principal_auth.py -q` depuis `/tmp/v7-11-a4-final/backend` : **21 passed, 1 warning** (avertissement de dépréciation Starlette/httpx TestClient).
- `python -m pytest --collect-only -q` : **455 tests collected**.
- `python -m pytest -q` a commencé mais n’a pas fourni de résultat final après plus de trois minutes; la commande a été interrompue. Aucun compte partiel n’est annoncé comme un PASS. Le parent rapporte séparément **499 passed, 1 skipped, 48 subtests** sur la suite racine; ce compte n’est pas un rerun indépendant de cette archive.
- Le correctif `2c3e178` ne modifie ni les quatre constats P2 de la revue initiale ni leurs emplacements vérifiés dans l’archive : configuration capabilities explicitement vide, cache anti-rejeu par processus, durcissement permissions/identité SQLite et chemins locaux dans trois anciennes preuves. S’ajoute la limite P2 d’intégrité SQLite h1/h2 décrite ci-dessus.
- Aucun Core/Relay live, ADB, émulateur ou Pixel physique n’a été utilisé; aucune modification de code ni aucun push n’a été effectué.

### État des findings après F1

| Sévérité | Nombre | Résultat |
|---|---:|---|
| P0 | 0 | Aucun |
| P1 | 0 | F1 fermé pour les révocations du registre et le rollback/restart normal |
| P2 | 5 | Capabilities vide; anti-rejeu multiworker; permissions/identité SQLite; chemins locaux dans les preuves; intégrité du tombstone sous écriture SQLite directe |

## Re-revue A4-F2 sur c031cb5

Cette re-revue porte sur l’arbre intégré `c031cb54615977f3535fce03a37c01fc65f266e2`, parent `132f17bd4cb3ac7e3e0005d284c7ca53891b315f`, extrait en lecture seule par `git archive` dans `/tmp/v7-11-a4-f2-final`. La branche d’intégration est `feat/atlas-v7-11-migration-readiness`. Seuls les tests temporaires dans `/tmp` et le présent rapport de revue ont été écrits.

| Champ | Résultat |
|---|---|
| Verdict F2 | **GO avec réserves P2**; `GO_V7_11_INDEPENDENT_SECURITY_REVIEW: GO` pour un seul Core par base canonique, des CLI exécutés Core arrêté et une horloge suffisamment stable |
| `132f17b` | `backend/atlas_backend/main.py`, `backend/tests/test_core_db_lock.py`, preuve A1 |
| `c031cb5` | `backend/atlas_backend/v4_router.py`, `backend/tests/test_v4_principal_auth.py`, preuve A2 Relay |
| P0 / P1 | **0 / 0** nouveau finding P0/P1 dans cette re-revue; le finding F1 reste fermé sur le chemin normal de révocation et de redémarrage |

### Findings F2

#### P2 — Le watermark de démarrage ne bloque pas tout rejeu après redémarrage

**Emplacements :** `backend/atlas_backend/v4_router.py:502-504,821-826`; fenêtre HMAC dans `backend/atlas_backend/v4_assertion.py:161-173`.

**Scénario :** le contrôle ne refuse que les timestamps sous `core_started_at_ms - 5000`. La vérification HMAC autorise aussi un timestamp jusqu’à 120 secondes dans le futur. Une assertion acceptée avant un redémarrage peut donc être rejouée après celui-ci si l’horloge du connecteur est en avance, ou si l’horloge murale Core recule : son timestamp paraît alors récent/futur, le cache nonce est neuf et le plancher de démarrage ne la rejette pas.

**Reproduction/oracle :** avec une clé, un bearer, un principal et une base synthétiques, une assertion HMAC a reçu `200`; après reconstruction de l’application avec l’horloge Core reculée de 30 secondes, la même assertion et le même nonce ont de nouveau reçu `200`. Le résultat applicatif était idempotent dans cette fixture; aucun effet dupliqué n’a été démontré. Les tests existants confirment aussi que `started_at - 3000 ms` est accepté, tandis qu’un probe à `-5001 ms` est refusé.

**Recommandation :** si le contrat exige un refus du rejeu après redémarrage, conserver les nonces dans un stockage partagé/durable ou lier les assertions à une époque/session Core renouvelée. Documenter explicitement le rejeu résiduel permis par la tolérance de 5 s et la dérive d’horloge future jusqu’à ±120 s. Les timestamps exactement à ±120 000 ms passent la vérification HMAC; ±120 001 ms sont refusés.

#### P2 — Le verrou est pris après l’initialisation/migration SQLite

**Emplacements :** `backend/atlas_backend/main.py:334-336,381-384`.

**Scénario :** `create_app()` construit `SQLiteStore` avant que le lifespan n’appelle `_acquire_core_database_lock()`. Un second Core qui sera ensuite refusé peut donc quand même ouvrir la base et exécuter son initialisation/migration pendant que le premier Core tient le verrou.

**Reproduction/oracle :** après création d’une application et acquisition du verrou Core sur sa base temporaire, un second `create_app()` sur cette même base a terminé; le `mtime` SQLite a changé. Son lifespan aurait ensuite été refusé par le verrou. Le test `test_same_database_lifespan_is_exclusive_and_released` couvre le refus du second lifespan, mais ne couvre pas l’écriture antérieure de `SQLiteStore`.

**Recommandation :** acquérir le verrou avant toute construction de `SQLiteStore` ou toute migration, et relâcher ce verrou si la construction de l’application échoue.

#### P2 — Les CLI SQLite ne consultent pas le verrou Core

**Emplacements :** `backend/scripts/atlas_v4_principals.py:300-302,331-339`; `backend/scripts/atlas_v4_expiration_reconcile.py:233-247`.

**Scénario :** le verrou empêche un autre Core d’acquérir le même fichier lock, mais les commandes administratives SQLite ne le consultent pas. Une commande de mutation peut modifier le registre ou les propositions pendant que Core tourne.

**Reproduction/oracle :** avec un verrou acquis par le helper Core sur une base temporaire, la CLI `credential revoke` a réussi et a révoqué un credential synthétique. La commande `list` a aussi réussi après relâchement du verrou/Core arrêté. La documentation qualifie ces outils d’offline et demande l’arrêt du Core, mais le code n’impose pas cette précondition.

**Recommandation :** faire prendre aux commandes mutantes le même verrou exclusif, ou échouer clairement si Core tient déjà le verrou. Garder l’usage offline des opérations de sauvegarde/réconciliation.

#### P2 — Deux lifespans Core sur des bases distinctes ne terminent pas leur arrêt dans le test ciblé

**Emplacement observé :** `backend/atlas_backend/main.py:428-438`, attente des tâches de fond avant la libération du lock.

**Scénario :** deux applications sur deux bases distinctes acquièrent chacune leur lock, mais le contexte externe ne termine pas lorsque le contexte interne est arrêté.

**Reproduction/oracle :** `test_different_databases_can_start_together` est entré dans les deux contextes et a vu les deux fichiers lock, puis n’a pas terminé après plus de 70 secondes. Le stack observé attendait la collecte des tâches de fond à la fermeture du lifespan externe. Les tests des autres cas de verrou passent isolément. Le helper lock synchrone confirme que deux chemins distincts peuvent être verrouillés en même temps.

**Recommandation :** corriger ou borner l’arrêt des tâches de fond, puis conserver un test avec délai maximal vérifiant que les deux lifespans terminent et libèrent leurs locks.

### Scénarios et contrôles exécutés

Les cas d’authentification utilisent de vraies signatures HMAC synthétiques, l’application ASGI et SQLite temporaire; ils ne remplacent pas le vérificateur par un mock.

| Contrôle | Résultat |
|---|---|
| Même base, deux lifespans dans un processus | **PASS** — le premier tient le lock, le second est refusé; après fermeture, le second peut acquérir le lock |
| Bases distinctes | **PASS partiel** — les deux locks sont acquis; le test ASGI ne termine pas son arrêt externe (finding P2 ci-dessus) |
| Alias symlink de base | **PASS** — `Path.resolve(strict=False)` fait partager le lock canonique |
| Fichier `.lock` symlink | **PASS** — `O_NOFOLLOW` le refuse et la cible reste intacte |
| Mode et propriétaire du lock créé | **PASS** — mode `0600`; UID/GID correspondent au processus effectif dans la fixture temporaire |
| `:memory:` | **PASS** — aucun fichier lock adjacent |
| Chemins relatif/absolu dans le même répertoire courant | **PASS** — ils se résolvent sur le même lock; un changement de répertoire courant après démarrage n’a pas été testé |
| Libération après arrêt / Core arrêté | **PASS** dans `test_same_database_lifespan_is_exclusive_and_released`; l’intégrité SQLite vaut `ok` et le second lifespan acquiert le lock |
| CLI pendant le lock / après arrêt | **CONFIRMÉ** — une révocation CLI fonctionne pendant que le lock est tenu; `list` fonctionne après relâchement |
| Relay A/A et B/B | **PASS** — assertions et credentials correctement liés acceptés (`200`) |
| Relay A/B, assertion inconnue ou altérée | **PASS** — credential/session croisés et identité, clé, méthode, chemin, timestamp, nonce ou corps modifiés refusés (`401`/erreur de vérification) |
| Rejeu mono-cache et sans assertion pour audience RELAY | **PASS** — rejeu refusé (`401`); RELAY sans assertion refusé (`401`) |
| DIRECT sans assertion | **PASS** — le credential DIRECT de test reste accepté sans assertion |
| Redémarrage, timestamp post-démarrage | **PASS** — ancienne assertion à 10 s avant le nouveau démarrage refusée; assertion à +1 s acceptée |
| Skew de démarrage et borne exacte | **PASS** — `-3000 ms` et `-5000 ms` acceptés; `-5001 ms` refusé; assertion juste après le démarrage acceptée |
| Fenêtre HMAC ±120 s | **PASS** — ±120 000 ms acceptés par le vérificateur; ±120 001 ms refusés |
| Rejeu avec recul d’horloge Core de 30 s | **FAIL du garde-fou après redémarrage** — assertion antérieure acceptée une seconde fois (`200`) car son timestamp est futur de 30 s et reste dans la fenêtre HMAC |

### Suites de tests et limites

- `ATLAS_AGENT_ADAPTER=mock python3 -m pytest -vv tests/test_v4_principal_auth.py` : **22 passed, 1 warning, 2.67 s**. Les cas couvrent l’assertion A/A, B/B, A/B, le nonce mono-cache, la rotation et clé inconnue, le binding, le redémarrage normal, le skew de 3 s et l’audience DIRECT.
- `ATLAS_AGENT_ADAPTER=mock python3 -m pytest -vv tests/test_core_db_lock.py -k 'not different_databases'` : **4 passed, 1 deselected, 1 warning, 0.32 s**.
- La commande ciblée demandée `ATLAS_AGENT_ADAPTER=mock python3 -m pytest -q tests/test_core_db_lock.py tests/test_v4_principal_auth.py` a été interrompue après plus de 70 s sans résultat final; un seul point était affiché avant le blocage sur le cas à deux bases. Le fichier auth a ensuite passé seul; le cas distinct-databases a aussi été exercé indépendamment jusqu’à l’arrêt bloqué.
- `ATLAS_AGENT_ADAPTER=mock python3 -m pytest -q` : **non terminé**. 37 points étaient affichés, puis aucune nouvelle sortie pendant 30 s; commande interrompue après environ 60 s, sans compte total ni résultat final.
- Aucun Core/Relay live, accès réseau, ADB, émulateur ou Pixel physique. Aucune donnée personnelle ni credential réel. La dérive a été simulée par horloge contrôlée.

### État après F2

Les réserves P2 précédentes restent applicables : valeur capabilities explicitement vide, permissions/identité de la base SQLite, chemins locaux dans d’anciennes preuves et modification directe SQLite de tombstones. Le verrou réduit le risque de rejeu entre workers qui partagent une même base en refusant le second lifespan; le cache nonce reste local au processus si des déploiements utilisent des copies indépendantes de la base. Le finding F2 sur l’horloge complète cette réserve anti-rejeu. S’y ajoutent les trois réserves de portée du verrou et d’arrêt concurrent décrites ci-dessus.

| Sévérité | Nombre | Résumé |
|---|---:|---|
| P0 | 0 | Aucun |
| P1 | 0 | Aucun nouveau P1 confirmé; F1 fermé pour le chemin standard |
| P2 | 8 | Capabilities vides; rejeu/horloge et cache par processus; permissions SQLite; chemins locaux historiques; intégrité tombstone sous SQL direct; verrou acquis après init DB; CLI hors verrou; arrêt concurrent de deux lifespans |

