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

