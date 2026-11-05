# V7-10 S1 — Conception de l’authentification par principal

**Statut :** conception statique, sans modification du code applicatif.
**Base :** `a5aca4b54088d43f4829adf31049417dd8cf32a6`.
**Branche :** `v7-10/s1-auth-design`.
**Profil constaté dans les métadonnées Paseo :** `codex / gpt-6-luna / xhigh`.
**Périmètre d’écriture :** ce document uniquement.

Les numéros ci-dessous décrivent les fichiers à la base indiquée. Les identifiants A et B dans les scénarios sont des valeurs synthétiques.

## 1. Cartographie exacte de l’authentification

### Android direct → Core

1. `AtlasController` instancie `SharedPreferencesBearerTokenStore` (`android/app/src/main/java/dev/atlas/coach/app/AtlasController.kt:336-339`). Le token configuré est chiffré en AES/GCM dans les préférences privées, avec une clé Android Keystore alias `atlas_backend_bearer_v1`; le code déchiffre le token à la lecture (`android/app/src/main/java/dev/atlas/coach/sync/AndroidSyncStorage.kt:31-47,70-97`). L’écran de configuration peut remplacer ce token sans changement de protocole (`AtlasController.kt:1346-1365`).
2. Le worker construit le transport HTTP direct et le transport Relay, sélectionne selon le mode, puis ne lit le bearer Android que pour le mode direct (`android/app/src/main/java/dev/atlas/coach/sync/v4/CoreV4SyncWorker.kt:119-170`; sélection : `sync/v4/RelayCoreV4HttpTransport.kt:251-259`).
3. Le client V4 transmet le token au transport direct (`sync/v4/CoreV4SyncClient.kt:346-354`); le transport HTTP le met dans `Authorization: Bearer …` (`CoreV4SyncClient.kt:875-896`). Le transport de l’inbox fait de même en mode direct (`sync/agent/inbox/v4/InboxV4HttpTransport.kt:187-195,235-267`).
4. `create_app(db_path, token, …)` crée `SQLiteStore`, puis passe son unique argument `token` à `create_v4_router` (`backend/atlas_backend/main.py:276-284,318-335`). L’auth locale historique lit ce même argument ou `ATLAS_TOKEN` (`main.py:369-384`). Côté V4, `current_android_token()` utilise aussi l’argument ou `ATLAS_TOKEN` (`backend/atlas_backend/v4_router.py:517-518`).
5. `require_android` compare le bearer fourni à cette valeur unique, rejette l’égalité avec `ATLAS_TOOL_TOKEN`, puis retourne `_device_ref(expected)` (`v4_router.py:613-625`). `_device_ref` vaut `android-` suivi des 32 premiers caractères hexadécimaux du SHA-256 du bearer (`v4_router.py:400-402`). Aucune identité de session Relay n’est présente dans cette requête HTTP directe.

### Android Relay → Relay → connecteur PC → Core

1. Le contrôleur reçoit `deviceId` et le token de pairing dans sa configuration; il enregistre l’ID dans les préférences non secrètes et le token dans `KeystoreRelayCredentialStore` (`android/app/src/main/java/dev/atlas/coach/app/RelaySettingsController.kt:134-183`; `sync/relay/RelaySettingsStore.kt:21-47,88-117,169-173`). Le token Relay a son propre alias Keystore `atlas_relay_device_v1`, distinct du bearer Core.
2. À l’ouverture du canal protégé, Android envoie un `hello` contenant `device_id`, `auth.scheme=device-token-v1` et le token Relay (`sync/relay/RelayBackendTransport.kt:287-308`; assemblage : `sync/relay/AtlasRemoteProtocol.kt:110-134`). Le protocole PC valide les clés, le format du token et l’ID (`integrations/relay/pc/src/protocol.ts:59-68,190-210`).
3. Le registre PC conserve l’ID Relay, le SHA-256 du token Relay, les capabilities et `revoked_at` (`integrations/relay/pc/src/device-registry.ts:7-18`). `SessionManager.authenticate` retrouve le registre par l’ID, vérifie le hash en temps constant, refuse un appareil inconnu ou révoqué et intersecte les capabilities demandées avec celles enregistrées (`session.ts:69-115`).
4. Pour chaque opération, la session passe `auth.record.device_id` à `OperationExecutor`, qui le transmet comme `deviceId` à `BackendClient.execute`; l’ID provient donc de la session Relay authentifiée, pas du payload de l’opération (`session.ts:298-318`; `operations.ts:86-105,245-264`).
5. Le défaut de cloisonnement est dans le raccord suivant : `connector.ts` lit un seul `ATLAS_V4_ANDROID_TOKEN_FILE` et construit une callback qui retourne ce même bearer dès que l’ID Relay est non vide (`connector.ts:37-48,54-78`). `BackendClient.execute` appelle bien la callback avec l’ID issu de la session, mais envoie son résultat comme bearer Core V4 (`backend.ts:102-123`). Ainsi, A et B sont authentifiés séparément par Relay, puis Core reçoit le même bearer pour les deux et calcule le même `device_ref`.
6. Côté Android, `RelayCoreV4HttpTransport` transforme seulement chemin et JSON en une opération Relay fermée; il supprime le bearer Core et ne transmet ni URL, ni méthode, ni en-tête libre (`android/app/src/main/java/dev/atlas/coach/sync/v4/RelayCoreV4HttpTransport.kt:77-90,97-135`). L’identité Relay disponible au connecteur ne traverse donc pas le saut HTTP Core actuel. `BackendClient` fait ensuite l’appel V4 vers `/v4/...` avec le bearer qu’il a choisi (`backend.ts:102-123,249-278`).

`pairedDeviceId` est donc une entrée utile uniquement parce qu’elle est produite par la session authentifiée. Aujourd’hui la callback ne fait aucune recherche par appareil : `pairedDeviceId.length > 0` sélectionne le token global (`connector.ts:74-78`). Le Core ne peut pas distinguer une session A d’une session B avec ce bearer (`v4_router.py:613-625`). La présence de `pairedDeviceId` seule ne prouve donc pas au Core que le bearer correspond au principal Relay.

### Identité, projections, consentement, propositions et curseur

- Le `device_ref` actuel est exclusivement dérivé du bearer (`v4_router.py:400-402`). `require_android` et `require_tool` le réutilisent (`v4_router.py:569-625`). Les tables projections et états de consentement V4 sont partitionnés par `device_ref`; le store de consentement lit et écrit l’état et son événement idempotent sous cette clé (`backend/atlas_backend/store.py:18-24,41-46`; `backend/atlas_backend/v4_projection_store.py:83-92,94-190`). Les outils utilisent cette clé pour lire les snapshots et contrôler consentement/capability (`backend/atlas_backend/v4_tool_service.py:103-110,732-748`).
- Le chat authentifie d’abord la requête Android et capture son `device_ref` (`v4_router.py:1218-1229`). Son `execute_tool` ferme sur cette valeur et passe explicitement `device_ref` au dispatch, sans prendre un principal des arguments du modèle (`v4_router.py:1289-1300,1330-1345`). Ce chemin est le bon modèle de propagation pour une opération liée à un tour utilisateur.
- **Trou critique découvert :** `agent_proposals_v4` n’a pas de colonne `device_ref` (`store.py:25`). `create_agent_proposal` n’en écrit pas et l’idempotence est recherchée globalement (`v4_projection_store.py:705-733`). Le pull ne filtre que par état, expiration et curseur (`v4_router.py:1588-1596`). L’ACK cherche la proposition par `proposal_id`, puis lit uniquement `state, expires_at` (`v4_router.py:1664-1675`). Le lookup par clé d’idempotence authentifie un contexte `device_ref`, mais sa requête en base est globale (`v4_router.py:954-1002`; helper : `v4_projection_store.py:735-745`). Avec plusieurs principaux, A pourrait donc lire ou ACK une proposition de B; le contrôle `plan_id` côté client ne constitue pas une autorisation serveur.
- Le curseur de pull est signé par une clé dérivée du bearer global (`v4_router.py:1756-1766`), et son JSON lie `device_ref`, date et ID proposition (`v4_router.py:1760-1789`). Le pull vérifie et crée ce curseur à partir du bearer global courant (`v4_router.py:1582-1586,1608-1612`). Il faut donc changer à la fois la clé et la liaison d’identité pour rendre la rotation et l’isolation par appareil correctes.

### Séparation des tokens observée

| Secret logique | Usage lu dans le code | Séparation à maintenir |
|---|---|---|
| Bearer Core Android (`ATLAS_TOKEN` dans l’app Core) | `create_app`/`create_v4_router`, `require_android`, et actuellement `device_ref`; l’app le conserve chiffré avec `atlas_backend_bearer_v1` (`main.py:276-335`; `v4_router.py:517-625`; `AndroidSyncStorage.kt:31-97`). | Un credential propre par principal en cible. Ne pas l’utiliser comme clé d’identité ni comme clé HMAC. |
| Token Core outil (`ATLAS_TOOL_TOKEN`) | Authentifie les routes `/internal/v4/*`, loopback seulement; Core vérifie qu’il diffère du bearer Android (`v4_router.py:569-598`). | Token d’appel outil distinct. Il ne doit plus choisir le principal en réutilisant implicitement le bearer Android global. |
| Token de service connecteur (`ATLAS_SERVICE_TOKEN`) | Configuré séparément du bearer V4 (`connector.ts:45-48`) et choisi par `BackendClient` pour les routes non V4; V4 utilise `pairedDeviceToken` (`backend.ts:108-116`). | Auth de service des anciennes opérations; aucun rôle d’identité Android ou Relay en mode principal. |
| Token Relay `device-token-v1` | Authentifie le pairing Android→Relay; le PC conserve son hash, ses capabilities et son état de révocation (`AtlasRemoteProtocol.kt:124-134`; `device-registry.ts:7-18,93-99`). | Jamais accepté comme bearer Core, token outil ou token Gateway. |
| Token Gateway OpenClaw | L’adaptateur lit `OPENCLAW_GATEWAY_TOKEN_FILE` ou `OPENCLAW_GATEWAY_TOKEN` puis appelle le Gateway avec son propre `Authorization` (`backend/atlas_backend/openclaw_adapter.py:574-579,637-659`). | Secret de la connexion Core→Gateway; ne pas le réutiliser pour Core Android, outil, connecteur ou Relay. |

## 2. Conception minimale recommandée

### Migration SQLite V4 suivante

La version de migration actuelle est 3 (`backend/atlas_backend/store.py:175-181`). Ajouter une migration **version 4** dans le ledger existant, avec checksum, transaction, sauvegarde cohérente avant migration et `integrity_check` comme les migrations présentes (`store.py:182-250,252-275`). La migration ne supprime ni projections ni événements ni ACK.

Tables recommandées, toutes nommées avec un suffixe versionné :

| Table | Colonnes et contraintes minimales |
|---|---|
| `principals_v4` | `principal_id` opaque PK; `device_ref` unique, immuable et généré côté Core; `kind` (`ANDROID`, `TOOL`, `LEGACY`); `state` (`ACTIVE`, `REVOKED`); `created_at`, `revoked_at`. Le `device_ref` n’est jamais calculé depuis un credential. |
| `credentials_v4` | `credential_id` PK; `principal_id` FK; `token_hash` unique (32 octets SHA-256 du bearer ASCII exact); `hash_algorithm`; `audience` (`DIRECT`, `RELAY`, `LEGACY_DIRECT`, `TOOL`); `state` (`ACTIVE`, `REVOKED`); `created_at`, `revoked_at`, `replaced_by`. **Aucun bearer en clair** dans SQLite. Les valeurs de token principal sont générées aléatoirement et comparées en temps constant après hash. |
| `principal_capabilities_v4` | `(principal_id, capability)` PK, capability validée dans l’allowlist, grant courant explicite et horodatage. Une capability effective n’est accordée que si le flag global V4 ET le grant du principal sont vrais. |
| `principal_events_v4` | ID événement PK, principal/credential concernés, type (`PROVISIONED`, `ROTATED`, `CREDENTIAL_REVOKED`, `PRINCIPAL_REVOKED`, `CAPABILITY_CHANGED`, `RELAY_BOUND`), horodatage et métadonnées autorisées; triggers interdisant `UPDATE` et `DELETE`. Aucun token, secret HMAC ou payload produit. |
| `relay_principal_bindings_v4` | `relay_device_id` PK, `principal_id` FK, état actif/révoqué, dates et ID événement. Cette relation serveur, créée par l’outil offline, est l’autorité Relay→Core. Un principal legacy ne peut être lié qu’à un seul appareil Relay. |
| `relay_assertion_nonces_v4` | Hash du nonce et key ID avec unicité, date reçue/expiration; insertion transactionnelle avant effet pour empêcher le rejeu. Conserver assez longtemps pour couvrir la fenêtre d’acceptation temporelle. |

Ajouter `device_ref TEXT REFERENCES principals_v4(device_ref)` à `agent_proposals_v4` et l’index `(device_ref,state,expires_at,created_at,proposal_id)`. La colonne peut être nullable uniquement pour les lignes historiques pendant la migration; tout nouvel INSERT sans `device_ref` est refusé par le code et un garde SQLite. Le champ `device_ref` est rempli à la création depuis l’AuthContext résolu, jamais depuis le JSON. Préserver les lignes d’ACK et leur FK vers `proposal_id`.

Pour les propositions :

1. Le pull ajoute `AND device_ref=?`, paramétré avec l’AuthContext; aucune ligne NULL ou sentinelle ne sort en mode `principal`.
2. L’ACK lit `device_ref` avec l’état et vérifie l’appartenance avant de consulter un `ack_id` existant ou d’appeler `append_proposal_ack`. Le store refait le contrôle dans la même transaction. Une proposition d’un autre principal retourne `404 NOT_FOUND`, identique à une proposition absente.
3. Le lookup d’idempotence et la déduplication dans `create_agent_proposal` sont également propriétaires. Un lookup inter-principal retourne `404`; une collision d’insertion sous la contrainte globale actuelle retourne un conflit générique sans corps de la proposition. Les ACK restent accessibles seulement via le propriétaire de leur proposition.
4. Le backfill est une commande offline avec aperçu. Si le propriétaire historique est démontrable par le credential legacy unique, associer les projections et propositions à son `device_ref` legacy conservé. Si l’historique ne peut pas être attribué sans supposition, utiliser un propriétaire sentinelle inaccessible en mode `principal`. Ne jamais répartir les anciennes propositions entre appareils ni réattribuer silencieusement leurs ACK.

### Résolution et modes explicites

Proposer `ATLAS_V4_AUTH_MODE=dual` comme défaut de déploiement pour qu’un APK déjà installé continue à utiliser le bearer historique pendant la migration. La valeur doit être une enum exacte; valeur inconnue ou migration incomplète = échec au démarrage, sans fallback. `legacy` est un mode de compatibilité/rollback explicitement choisi; `principal` est la cible de clôture.

| Mode | Résolution et compatibilité |
|---|---|
| `legacy` | Conserve uniquement la comparaison au bearer existant et l’ancien `device_ref` dérivé, pour restauration explicite du comportement antérieur. Ce mode n’offre pas l’isolation multi-appareils et ne constitue pas le critère de clôture sécurité. |
| `dual` | Le bearer historique n’est accepté que s’il correspond au hash d’un credential `LEGACY` actif explicitement provisionné. Les credentials de principaux enregistrés sont résolus par hash vers principal stable. Un hash connu mais révoqué échoue; un hash inconnu ne tombe jamais sur le legacy. Les APK antérieurs gardent leur bearer direct et leur `device_ref` historique. Le connecteur V4, lui, doit avoir une entrée de mapping et une assertion Relay; mapping absent = opération V4 indisponible, jamais réutilisation globale du token. |
| `principal` | Seuls les credentials actifs de `credentials_v4` sont acceptés. Aucun chemin `ATLAS_TOKEN`, aucun mode legacy implicite et aucun fallback si table, credential, binding, capability ou clé d’assertion manque. L’activation exige que toutes les identités Relay visées soient provisionnées. Credential inconnu/révoqué = 401 générique; capability absente = 403; stockage/migration indisponible = refus fail-closed. |

Le résolveur Core produit un objet serveur immuable `AuthContext(principal_id, device_ref, credential_id, audience, capabilities)`. Il suit strictement `Bearer → hash exact → credential unique → état credential/principal → principal → device_ref + grants`. Une ligne inconnue donne 401; une ligne révoquée ne déclenche aucune autre recherche; aucun principal du payload, query string ou en-tête non signé n’est consulté. Pour chaque route, `effective_capabilities = global_flags ∩ principal_capabilities`; refus 403 avant lecture ou mutation si le résultat ne contient pas la capability de route.

`require_tool` reste un authentificateur d’appel outil distinct. Pour les routes `/internal/v4/*` autonomes, choisir le principal via un `ATLAS_V4_TOOL_PRINCIPAL_ID` configuré côté Core (et limiter ses grants), ou via un contexte de tour déjà authentifié transmis en mémoire par Core. Si ce choix serveur manque, refus fail-closed; ne pas dériver le principal de `ATLAS_TOKEN`, du token outil ou du payload. Le chat V4 possède déjà le contexte authentifié du demandeur dans `execute_tool` (`v4_router.py:1218-1224,1330-1345`) : conserver cette liaison serveur et l’étendre à l’AuthContext typé.

`main.py:369-384` montre aussi que les anciennes routes non V4 ont leur propre dépendance globale `require_local_token`. Pour déclarer la migration globale terminée, `principal` doit appliquer le résolveur à toute route Android qui accepte encore `ATLAS_TOKEN`, ou désactiver ces routes dans ce mode. Les routes de service V1–V3 doivent rester sur une identité de service distincte avec grants limités. Garder seulement le garde V4 tout en laissant l’ancien bearer global agir ailleurs ne suffit pas à annoncer la suppression complète du P1.

### Assertion Relay signée et mapping connecteur

Recommandation : défense en profondeur par assertion signée pour chaque opération V4 qui traverse le connecteur. La callback ne résout plus un token global; elle recherche `authenticatedRelayDeviceId → {principal_id, Core credential}`. Les identifiants viennent exclusivement de `auth.record.device_id` et de la configuration locale sûre (`session.ts:102-109,318`; `operations.ts:258-264`).

En-têtes proposés :

```text
X-Atlas-Relay-Device-Id: <device_id Relay issu de la session authentifiée>
X-Atlas-Relay-Time: <epoch millisecondes UTC>
X-Atlas-Relay-Nonce: <base64url de 16 octets aléatoires>
X-Atlas-Relay-Key-Id: <identifiant de clé non secret>
X-Atlas-Relay-Signature: v1=<base64url HMAC-SHA256>
```

La signature couvre les octets UTF-8 de la forme canonique :

```text
v1\n<METHOD>\n<path exact>\n<timestamp>\n<nonce>\n<relay_device_id>\n<hex SHA256 des octets exacts du body>
```

Le secret HMAC aléatoire d’au moins 256 bits est dédié au lien connecteur→Core, séparé des bearers Relay/Core, du token outil, du token service et du Gateway; il n’est pas stocké en SQLite. Le Core accepte une fenêtre temporelle bornée (recommandation : ±60 s), insère atomiquement le hash du nonce avec une contrainte unique, et refuse nonce dupliqué, signature invalide, key ID inconnu ou assertion expirée. Une répétition métier idempotente émet un nouveau nonce/signature avec la même clé d’idempotence V4.

Après validation de l’assertion, Core charge `relay_principal_bindings_v4` et exige simultanément : binding actif; credential bearer actif avec audience `RELAY`; `credential.principal_id == binding.principal_id`. Un credential B signé pour la session Relay A échoue en 401/403 générique, sans révéler si l’ID, la binding ou le credential était incorrect. Un credential audience `RELAY` sans assertion échoue; une requête directe n’a pas d’assertion et exige audience `DIRECT` ou `LEGACY_DIRECT`. Ainsi, l’absence d’assertion ne devient pas une route de contournement.

Le PC stocke le mapping de credentials par principal dans un fichier privé distinct : répertoire propriétaire mode `0700`, fichiers owner du compte connecteur mode `0600`, ouverture `O_NOFOLLOW`, vérification `lstat`/`fstat` et inode, écriture atomique et création exclusive. Le bearer Core doit être conservé uniquement dans ce fichier privé, jamais dans SQLite, les journaux ou `devices.json`; la clé HMAC suit la même protection. `readPrivateV4AndroidToken` fournit déjà les contrôles owner/mode/symlink à reprendre (`connector.ts:122-149`). Ne pas réutiliser naïvement `readPrivateJson` pour des credentials : il ouvre le chemin sans `O_NOFOLLOW` ni contrôle propriétaire (`secure-files.ts:10-24`). Mapping absent, fichier unsafe, ID Relay inconnu/révoqué ou mismatch = V4 refusé; V1–V3 ne changent pas leur bearer de service.

### Provisioning, révocation, rotation et rollback

- Fournir une CLI/script **offline** administrant la base locale et les fichiers de mapping. Sous-commandes minimales : `preview-migration`, `provision`, `bind-relay`, `rotate`, `revoke`, `list` et `verify`. Aperçu sans écriture par défaut; application requiert un drapeau explicite.
- `provision` crée un principal avec `device_ref` stable, capability allowlistée et un bearer aléatoire; SQLite ne reçoit que son hash. Émettre le bearer une fois vers un fichier de sortie créé en exclusif, owner/0600/sans symlink. Une répétition du même événement idempotent ne révèle pas le secret; s’il est perdu, créer une rotation.
- `rotate` insère le nouveau hash et révoque l’ancien dans une transaction, émet un événement append-only et garde le même principal/`device_ref`. Les curseurs précédents deviennent invalides.
- `revoke` est idempotent et monotone : révoquer un credential ou principal ne supprime ni événement ni projection et ne réactive rien au prochain provisionnement, démarrage ou rollback de mode. Réactivation éventuelle doit être une opération administrative séparée avec événement explicite; recommandation : une identité révoquée reste tombstone et reçoit un nouveau principal après consentement courant.
- La migration conserve les projections, consentements, propositions et événements ACK. Les anciennes données restent sous leur `device_ref` legacy; une nouvelle identité ne reçoit jamais automatiquement un ancien `GRANTED`. Tout transfert d’identité exige aperçu et consentement courant de chaque catégorie. Si la provenance est ambiguë, conserver sous principal legacy inaccessible ou en quarantaine, jamais attribuer à A ou B par heuristique.
- Rollback d’application : rester sur un binaire qui comprend le schéma 4 et revenir explicitement à `dual`/`legacy` tant que les credentials concernés sont encore actifs. Ne pas appliquer une migration descendante ni restaurer une sauvegarde qui annule des révocations/ACK. Après révocation du bearer historique, revenir au mode local/offline ou provisionner explicitement un nouveau credential; ne pas réactiver silencieusement l’ancien. Un ancien binaire v3 ne doit pas être supposé compatible avec la migration v4; son usage doit être bloqué par une preuve dédiée avant d’être autorisé.

## 3. Matrice de tests à ajouter

Les tests existants prouvent déjà quelques gardes de base, mais pas l’isolation entre principaux : `backend/tests/test_v4_routes.py::test_tool_auth_is_separate_loopback_only_and_headers_are_strict` (lignes 527-553), `test_capability_flags_and_consent_version_gate_reads_and_sync` (702-718), les tests de consentement/révocation de `test_v4_proposals.py:387-535`, les migrations checksum de `test_v4_migrations.py:20-135`, et Relay `test/session.test.ts:32-100`, `test/connector-resilience.test.ts:106-153`. Le tableau suivant est une proposition de couverture, pas un résultat exécuté.

| # | Fichier / test proposé | Entrée | Résultat attendu et oracle exact |
|---:|---|---|---|
| 1 | `backend/tests/test_v4_relay_principal_auth.py::test_a_credential_a_in_relay_session_a` | Credential actif A + assertion valide signée pour Relay A + consentement courant A. | `200`; `AuthContext.principal_id/device_ref` égale A; le pull ne contient que les propositions A. |
| 2 | `backend/tests/test_v4_relay_principal_auth.py::test_b_credential_b_in_relay_session_b` | Credential actif B + assertion valide Relay B + données propres à B. | `200`; le pull ne contient que B; aucune ligne A lue ou ACKée. |
| 3 | `backend/tests/test_v4_relay_principal_auth.py::test_a_session_with_b_bearer_is_denied` | Assertion signée Relay A, bearer Core du principal B, les deux actifs. | `401` générique (ou `403` générique contractuellement choisi); zéro body métier, zéro événement ou mutation. C’est l’oracle littéral « A avec bearer B dans session Relay valide ». |
| 4 | `backend/tests/test_v4_principal_auth.py::test_unknown_bearer_never_falls_back` | Bearer aléatoire absent du registre, en mode `principal` puis `dual`. | `401 AUTH_REQUIRED`; aucun principal legacy, aucune lecture/écriture, aucune requête au tool. |
| 5 | `backend/tests/test_v4_principal_auth.py::test_revoked_credential_is_denied_without_fallback` | Hash connu mais credential ou principal `REVOKED`, avec credential legacy actif disponible. | `401` générique; statut révoqué inchangé; aucun fallback legacy; aucune donnée révélée. |
| 6 | `integrations/relay/pc/test/session.test.ts::rejects_unknown_or_revoked_identity_before_backend` | Hello avec ID Relay inconnu puis ID révoqué. | Session inconnue `AUTH_FAILED`, session révoquée `DEVICE_REVOKED`; le compteur d’appels Backend reste exactement à zéro. |
| 7 | `backend/tests/test_v4_relay_principal_auth.py::test_server_binding_disagreement_fails_closed` | Assertion Relay A, binding serveur A→A mais credential injecté B, puis mapping local A→B divergent du Core. | Chaque variante donne le même `401/403` générique; aucune donnée, aucun ACK/proposition, aucune fuite du principal attendu. |
| 8 | `backend/tests/test_v4_principal_auth.py::test_payload_cannot_choose_principal` et `integrations/relay/pc/test/operations.test.ts` | Ajouter `principal_id`/`device_ref` forgé au payload Android, outil et Relay. | Schéma strict `400` ou rejet d’opération; sinon la valeur est ignorée; dans tous les cas `AuthContext` reste celui du bearer/assertion et la base est inchangée. |
| 9 | `backend/tests/test_v4_principal_auth.py::test_new_identity_requires_current_consent` | Principal nouvellement provisionné sans état de consentement courant; appel pull/read/write. | `403 CONSENT_REQUIRED`, zéro résultat/proposition/ACK; un consentement historique d’un autre `device_ref` ne suffit pas. |
| 10 | `backend/tests/test_v4_principal_auth.py::test_revocation_is_not_implicitly_reactivated` | Révoquer principal/credential, redémarrer, rejouer `provision` idempotent ou rotation non explicite. | Credential toujours refusé; état tombstone reste `REVOKED`; aucun événement de réactivation. Une nouvelle identité exige commande explicite et consentement frais. |
| 11 | `backend/tests/test_v4_migrations.py::test_v4_principal_migration_and_explicit_legacy_rollback` | Base version 3 avec snapshots/consentements/propositions/ACK; migration en preview/apply; mode `dual`, rollback de configuration vers `legacy` explicitement autorisé. | Version 4 + `integrity_check=ok`; compte/hash logique de chaque historique et ACK conservé; backfill uniquement vers legacy confirmé ou sentinelle; ancien bearer accepté seulement quand le mode et le credential legacy sont explicitement actifs; en `principal`, ce bearer reçoit 401. |
| 12 | `backend/tests/test_v4_proposals.py::test_proposals_pull_lookup_and_ack_are_device_scoped` | Propositions A/B; A fait pull, lookup de la clé de B, puis ACK de la proposition B avec consentement A courant. | Pull A ne contient que A; lookup et ACK de B répondent `404 NOT_FOUND`; aucun événement ACK B créé et état B inchangé. Inclure aussi `create_agent_proposal` avec clé déjà possédée par B : aucune proposition B retournée. |
| 13 | `backend/tests/test_v4_relay_assertion.py::test_relay_audience_requires_assertion` | Credential audience `RELAY`, requête sans les en-têtes signés; comparer audience `DIRECT` sur chemin direct sans assertion. | Relay `401`; direct `DIRECT` peut continuer; aucune assertion absente ne transforme un bearer Relay en accès direct. |
| 14 | `backend/tests/test_v4_relay_assertion.py::test_invalid_or_expired_assertion_is_denied` | Signature modifiée, body/path différent de la signature ou timestamp hors fenêtre. | `401` générique avant effet; nonce n’autorise aucune seconde tentative métier. |
| 15 | `backend/tests/test_v4_relay_assertion.py::test_assertion_nonce_cannot_be_replayed` | Rejouer exactement timestamp, nonce, chemin, corps et signature, puis retenter avec nouveau nonce et même clé idempotence. | Premier appel accepté; rejeu du nonce `401`; nouvelle assertion atteint le garde d’idempotence et ne crée pas de second événement. |
| 16 | `backend/tests/test_v4_principal_auth.py::test_capabilities_are_the_intersection` | Flag global vrai/grant principal faux, puis flag global faux/grant principal vrai. | Les deux variantes répondent `403 CAPABILITY_UNAVAILABLE`; seule l’intersection vraie passe. |
| 17 | `integrations/relay/pc/test/principal-mapping.test.ts::unsafe_mapping_storage_disables_v4` | Mapping/secret par symlink, mauvais owner ou mode trop permissif, fichier remplacé entre `lstat` et lecture. | V4 reste indisponible, zéro fetch Core; aucune donnée du fichier ni bearer n’apparaît dans réponse/log. |

## 4. Décisions recommandées, questions et risques

### Décisions recommandées

1. Prendre `dual` comme défaut de la première livraison pour ne pas invalider le bearer chiffré de l’APK déjà installé; conserver le même `device_ref` pour son identité legacy. `dual` reste transitoire et n’est pas un GO sécurité si le bearer legacy sert à plusieurs appareils. La clôture P1 exige `principal`, après provisioning explicite de chaque appareil et révocation legacy contrôlée.
2. Ne jamais laisser le connecteur V4 appeler `pairedDeviceToken` avec une valeur globale. Mapping Relay→principal côté PC et binding correspondant côté Core sont tous deux exigés. Ajouter l’assertion HMAC pour que le Core puisse refuser un credential B sous une assertion/session A.
3. Ajouter l’ownership `device_ref` aux propositions dans la même migration que le registre; protéger pull, ACK et les deux chemins de lookup idempotent. Cette correction est une exigence de sécurité multi-principal, pas une optimisation.
4. Garder le principal outil autonome explicitement configuré; le chat V4 hérite seulement du contexte authentifié du demandeur. Pas de principal implicite dérivé du bearer Android partagé.
5. Garder les consentements historiques sur l’identité legacy. Ne pas migrer un `GRANTED` vers un nouveau principal; demander un consentement courant après changement d’identité.

### Questions à trancher par l’orchestrateur

- Le gate de sortie V7-10 couvre-t-il toutes les routes `ATLAS_TOKEN` V1–V4? Recommandation : oui, ou désactiver explicitement les routes legacy sous `principal`; sinon le retrait du bearer global ne peut être annoncé que pour V4.
- Les endpoints `/internal/v4/*` autonomes sont-ils réservés à un principal outil unique configuré par instance, ou faut-il un contexte de tour utilisateur pour une instance multi-utilisateur? Dans les deux cas, la valeur vient du serveur, jamais du payload.
- Quelle durée maximale de `dual` est admise? Recommandation : date/gate de sortie explicite, un seul binding Relay legacy et aucune nouvelle paire sur ce credential.
- Pour les propositions historiques sans propriétaire démontrable, faut-il tout laisser à la sentinelle inaccessible, ou autoriser uniquement un backfill legacy après aperçu? Ne pas choisir automatiquement un appareil.
- Qui garde et fait tourner le secret HMAC connecteur→Core, et quel chemin owner/0600 est retenu sur les deux hôtes? La rotation doit accepter deux key IDs pendant une fenêtre courte, sans accepter une signature absente.
- Le rollback autorisé est-il uniquement un changement de mode sur le binaire compatible schéma 4? Recommandation : oui; ne pas faire tourner un ancien binaire non validé contre cette base.

### Risques

| Gravité | Risque | Fermeture/oracle |
|---|---|---|
| **P1** | Bearer Android global et `device_ref` dérivé du bearer donnent la même identité à chaque session Relay (`connector.ts:74-78`; `v4_router.py:400-402,613-625`). | Principal par appareil + mapping Relay signé; tests 1–7 et 13–15. |
| **P1** | Propositions sans propriétaire : pull global, ACK par ID global, lookup idempotence global (`store.py:25`; `v4_projection_store.py:726,738`; `v4_router.py:1591-1596,1664-1675`). | `device_ref` stocké/backfill explicite; pull, lookup et ACK propriétaires; test 12. |
| **P1** | Un défaut de fallback dans `dual` peut réactiver un credential inconnu/révoqué, et un ancien binaire peut continuer à utiliser le bearer global. | Mode enum strict, hash connu révoqué terminal, compatibilité versionnée; tests 4, 5, 10, 11; ancien binaire bloqué tant qu’il n’est pas qualifié. |
| **P1** | Mapping de credentials ou clé HMAC mal protégés compromet tous les principaux gérés par le connecteur. | Fichier owner/0600/sans symlink, validation inode et échec fermé; test 17. |
| **P2** | Consentements, curseurs ou propositions historiques sont attribués au mauvais principal pendant le backfill. | Dry-run avec comptages/hashes, confirmation d’owner, sentinelle inaccessible si ambigu, consentement courant requis. |
| **P2** | La clé actuelle des curseurs dépend du bearer et n’est invalidée qu’indirectement par changement du token; elle ne formalise pas credential/principal. | HMAC serveur dérivé de `principal_id + credential_id`; payload cursor signé avec principal, `device_ref`, credential, date et proposition; rotation de credential invalide l’ancien cursor; test Core A/B et rotation. |
| **P2** | La callback PC actuelle identifie la présence d’un `pairedDeviceId`, pas son mapping; les capabilities relay négociées ne sont pas un grant Core par principal. | Mapping serveur explicite et intersection indépendante Core; tests 2, 6, 7, 16. |

Pour le curseur cible : dériver la clé de signature depuis un secret serveur aléatoire et le couple `principal_id, credential_id`, jamais du bearer brut. Inclure le même couple dans le payload signé. La rotation change `credential_id`, invalide immédiatement les anciens curseurs et empêche leur usage croisé entre appareils.

## 5. Limites de preuve et commandes

Audit statique au HEAD de base seulement. Les directives, le workflow, le template, le README, les documents V7-09 requis, les sources Core/Relay/Android demandées et les tests V4/auth/consentement ciblés ont été consultés. `.codegraph/` est absent, donc aucune recherche CodeGraph n’a été possible. Le profil effectif a été vérifié dans les métadonnées Paseo. Aucun test n’a été lancé; aucune base, API, Gateway, Relay ou appareil live n’a été consulté. Aucun ADB n’a été utilisé. Aucun secret, token, identifiant d’appareil réel ou donnée personnelle n’est inclus dans ce rapport.

Commandes de pré-vol/inspection exécutées : `pwd`; `git status --short --branch`; `git rev-parse HEAD`; `git branch --show-current`; `git worktree list --porcelain`; `test -d .codegraph && echo present || echo absent`; lectures ciblées `sed -n` et `nl -ba … | sed -n`; recherches `rg -n` limitées aux sources, tests et documents explicitement requis. Les gates après rédaction sont `git diff --check`, `git show --check --oneline HEAD` et `git status --porcelain=v1`.
