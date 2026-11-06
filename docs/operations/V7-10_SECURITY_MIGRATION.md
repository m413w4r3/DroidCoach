# V7-10 — Migration de l’authentification V4 par appareil

Ce document décrit le nouveau registre Core et le passage contrôlé du bearer Android V4 partagé à des credentials propres à chaque principal. Il ne constitue pas une autorisation de déploiement. La conception de référence est `V7-10_S1_AUTH_DESIGN.md`; les décisions d’orchestration de ce ticket priment lorsqu’elles divergent. Le cycle d’expiration S2 reste hors périmètre.

## Architecture et limites de confiance

- Core conserve un `device_ref` stable généré lors de la création du principal. Les credentials sont des bearer ASCII d’au moins 32 caractères; SQLite ne contient que leur SHA-256. Un principal peut avoir des credentials distincts pour `DIRECT`, `RELAY`, `TOOL` ou `LEGACY`.
- Les grants viennent exclusivement de `principal_capabilities_v4`. Pour toute capability de l’allowlist, l’opération est autorisée uniquement si le grant du principal et le flag global V4 sont tous deux actifs. Les flags de lecture et `coach.chat` absents valent `false`; les flags de mutation absents valent `true` pour préserver le produit installé. Un `false` explicite coupe la route avant toute mutation.
- `/internal/v4/capabilities` est une métadonnée authentifiée du principal `TOOL`: l’accès vérifie loopback, bearer, `ATLAS_V4_TOOL_PRINCIPAL_ID`, état du principal/credential, audience `TOOL` et tous les en-têtes V4 attendus. `tool_catalog.read` n’est pas un grant accordable et cette exception ne s’étend à aucune autre route.
- Les propositions V4 sont rattachées au `device_ref` résolu côté Core. Pull, recherche d’idempotence et ACK sont filtrés par propriétaire; un accès croisé renvoie `404 NOT_FOUND`. En mode `dual`, les anciennes lignes `device_ref IS NULL` ne sont accessibles qu’au principal `LEGACY` actif nommé par `ATLAS_V4_LEGACY_PRINCIPAL_ID`. Une variable absente refuse l’accès aux routes de proposition du principal LEGACY; un identifiant inconnu, de mauvais kind ou révoqué fait échouer la configuration. Les autres principaux LEGACY reçoivent un pull vide et `404` sur ACK/lookup de ces lignes. Le mode `principal` refuse les credentials `LEGACY`.
- En mode `legacy`, le bearer historique unique conserve la compatibilité du produit installé et peut encore traiter les lignes sans propriétaire. Cette compatibilité est volontairement transitoire; `dual` doit configurer le principal legacy et exécuter `backfill-proposals` sur une copie vérifiée pour attribuer les lignes anciennes.
- Le connecteur choisit un credential à partir du `device_id` Relay de la session authentifiée. Le JSON local `ATLAS_CORE_PRINCIPAL_MAP_FILE` contient `relay_device_id → { principal_id, credential_file }`. Le champ local `principal_id` est une annotation de provisionnement et n’est pas envoyé à Core comme identité faisant autorité. Core vérifie l’assertion HMAC, le binding serveur Relay→principal actif et l’égalité entre le binding et le principal du credential. Ainsi, si la map locale A choisit le credential B alors que Core lie A à A, la requête est refusée.
- Chaque opération V4 Relay porte une assertion HMAC-SHA256 sur la version, méthode, chemin exact, timestamp millisecondes, nonce, ID Relay et SHA-256 du corps exact. Core accepte une fenêtre de ±120 secondes, garde les nonces dans un cache mémoire borné et rejette les assertions rejouées. Les clés `key_id` permettent une rotation limitée à deux clés actives.
- `ATLAS_V4_TOOL_PRINCIPAL_ID` relie les appels `/internal/v4/*` autonomes à un principal `TOOL` et un credential d’audience `TOOL`. Le chat conserve le `device_ref` de son appelant lors de l’exécution des outils.

## Modes Core et connecteur

`ATLAS_V4_AUTH_MODE` vaut `legacy`, `dual` ou `principal`; le défaut du code reste `legacy` afin de préserver l’APK installé.

| Mode | Core | Connecteur |
|---|---|---|
| `legacy` | Compare au bearer historique et conserve le `device_ref` dérivé historique. | Utilise le bearer legacy uniquement si la map est absente et `ATLAS_REQUIRE_PRINCIPAL_BINDING=0`. Une map présente ou invalide désactive V4 au lieu de choisir implicitement un autre chemin. |
| `dual` | Accepte les credentials actifs provisionnés; le bearer historique ne passe que s’il a été importé comme credential `LEGACY`. | Exige une map privée valide et une clé d’assertion dédiée. Map absente/invalide: aucun appel Core V4. |
| `principal` | Accepte les credentials actifs `DIRECT` ou `RELAY`; aucun chemin `ATLAS_TOKEN` ni credential importé `LEGACY`. Un secret curseur serveur d’au moins 32 caractères est requis. | Exige la même map et l’assertion Relay. Map absente/invalide: aucun appel Core V4. |

Une valeur inconnue, une migration incomplète ou une configuration manquante ne déclenche pas de fallback silencieux. En mode `dual`, une map valide associe un credential distinct à chaque ID Relay. Les fichiers de map, credentials et assertion doivent être absolus, réguliers, détenus par l’utilisateur du processus, mode `0600`, sans symlink, lus avec `O_NOFOLLOW`, et validés par inode et taille. `ATLAS_REQUIRE_PRINCIPAL_BINDING=1` coupe le fallback legacy même en développement.

Le secret d’assertion est distinct de `ATLAS_TOKEN`, `ATLAS_TOOL_TOKEN`, `ATLAS_SERVICE_TOKEN`, `OPENCLAW_GATEWAY_TOKEN` et du token Relay. Le connecteur exige en production `ATLAS_V4_RELAY_ASSERTION_SECRET_FILE`: fichier absolu, hors dépôt, régulier, détenu par l’utilisateur du processus, mode `0600`, sans symlink et lu avec `O_NOFOLLOW`. La source `ATLAS_V4_RELAY_ASSERTION_SECRET` est acceptée uniquement lorsque `NODE_ENV=test`; les deux sources ensemble sont refusées. `configFromEnvironment` et `check-config.mjs` échouent si la variable est utilisée hors test; en production, `check-config.mjs` et `check-secret.mjs` vérifient aussi l’unique fichier, son propriétaire, son mode `0600`, son contenu et son emplacement hors dépôt sans afficher sa valeur. Côté Core, le trousseau vient de `ATLAS_V4_RELAY_ASSERTION_KEYS_FILE` avec validation propriétaire/mode/inode; `ATLAS_V4_RELAY_ASSERTION_KEYS_JSON` reste réservé à pytest. Ne pas placer de valeur de secret dans les arguments de commande ou dans les journaux. En principal, `ATLAS_V4_CURSOR_SECRET` doit être configuré et comporter au moins 32 caractères; sa rotation invalide les curseurs existants. En dual, son absence conserve la compatibilité du curseur historique.

## Provisionnement offline

L’outil `backend/scripts/atlas_v4_principals.py` ne contacte aucun service. Il administre uniquement la base passée par `--db`.

1. Faire une sauvegarde contrôlée, vérifier son intégrité, son `user_version`, son `foreign_key_check` et son SHA-256. Ne jamais essayer la migration d’abord sur une base réelle.
2. Inspecter `preview-migration`. Tester la migration et les parcours sur une copie SQLite synthétique.
3. Pour l’APK installé, exécuter `legacy-import --grant-defaults` uniquement après autorisation de migration. Le flag est obligatoire et inscrit explicitement les capabilities de compatibilité V4 présentes dans l’allowlist. Le bearer est fourni sur stdin; aucun token n’est affiché. Cet import crée le principal `LEGACY` stable et le hash, et ne réactive jamais une entrée révoquée.
4. Pour un nouvel appareil, créer un principal puis un credential `DIRECT` ou `RELAY`; utiliser `credential add --generate --output /chemin/absolu` pour créer un bearer dans un fichier exclusif mode `0600`. Accorder séparément chaque capability nécessaire avec `capability set`.
5. Pour Relay, provisionner `binding add` Core et ajouter la correspondance ID Relay→principal/fichier credential au mapping privé du connecteur. Protéger le mapping et les fichiers de clés HMAC en mode `0600`. Le principal indiqué dans la map doit correspondre au binding vérifié côté Core.
6. Vérifier le credential via `verify-token` en alimentant stdin; cette commande affiche des métadonnées redacted seulement. Utiliser `list` pour consulter l’état redacted.
7. La rotation ajoute un credential puis révoque l’ancien dans une transaction et conserve le `device_ref`. Une révocation est monotone. `credential revoke`, `principal revoke` et `binding revoke` n’ont pas d’opération implicite de réactivation.

Pour le bearer historique, configurer `ATLAS_V4_LEGACY_PRINCIPAL_ID` avec l’identifiant exact d’un principal `LEGACY` actif avant d’ouvrir `dual`. Sans variable, les lignes sans propriétaire restent refusées. `backfill-proposals --legacy-principal-id ID` attribue explicitement ces lignes à ce destinataire; vérifier d’abord le dry-run et utiliser `--apply --backup` uniquement sur une copie vérifiée.

`backfill-proposals` est en dry-run par défaut. `--apply` exige un chemin de sauvegarde absolu inexistant; l’outil crée une sauvegarde exclusive mode `0600`, vérifie intégrité, clés étrangères, version, taille, compte avant/après et hash, puis attribue uniquement les lignes NULL à un principal legacy explicitement ou sans ambiguïté unique. Faire le backfill uniquement sur une copie vérifiée avant toute procédure opératoire.

## Migration, retour arrière et prérequis

La migration SQLite est additive (version 4), transactionnelle, contrôlée par checksum et protégée par la sauvegarde/intégrité existantes. Elle ajoute le registre versionné, les contraintes d’état et de capacité, la propriété des propositions et les triggers qui interdisent les nouveaux INSERT sans propriétaire. Aucun code de cette tâche n’a migré une base live.

Séquence de déploiement recommandée après validation des gates ci-dessous:

1. Sauvegarder et vérifier la base; tester sur une copie avec `preview-migration`, migration/apply et parcours de rollback.
2. Déployer le Core en `dual`, provisionner explicitement le principal legacy et les nouveaux principaux, puis configurer les bindings Relay et les assertions. Valider les tests de bout en bout synthétiques A/A, B/B et A/B.
3. Déployer les fichiers privés de map/connecteur et vérifier que chaque ID Relay pointe vers le credential prévu. Aucun secret ne doit être copié dans un log ou un rapport.
4. Quand tous les clients et sessions sont migrés, passer explicitement en `principal`; vérifier que le bearer legacy est refusé.

Un rollback applicatif consiste à revenir explicitement en `legacy` après arrêt contrôlé et validation d’une sauvegarde. Ne pas rétrograder le schéma en supprimant des tables ou des colonnes. Si une restauration SQLite est nécessaire, arrêter Core, sauvegarder l’état courant, restaurer uniquement la sauvegarde vérifiée et contrôler à nouveau `integrity_check`, `foreign_key_check` et la version; documenter les écritures postérieures qui seraient perdues. Le rollback legacy rétablit le bearer partagé et rouvre donc le risque P1; il n’est qu’un mécanisme temporaire avec propriétaire/échéance définis.

V1–V3 restent hors de la clôture sécurité V4: les routes locales historiques continuent d’utiliser `ATLAS_TOKEN`. Le connecteur ne doit pas envoyer son token de service comme bearer Android des opérations V4. Cette livraison ne prétend donc pas supprimer toutes les utilisations de `ATLAS_TOKEN`.

## Gates de sécurité et limites

Les gates sont des approbations distinctes; l’implémentation seule ne les marque pas GO:

- `GO_CORE_DEVICE_PRINCIPALS` — revue du registre, des états, capabilities, migration, sauvegardes et isolation des propositions.
- `GO_RELAY_DEVICE_BINDING` — preuve du mapping Relay authentifié→principal, assertion HMAC, anti-rejeu, stockage des clés et rotation.
- `GO_BEARER_MIGRATION_DESIGN` — approbation du plan APK installé, grants legacy explicites, fenêtre `dual` et cutover `principal`.
- `GO_BEARER_REVOKE_FAIL_CLOSED` — preuve d’absence de réactivation après redémarrage, idempotence ou réimport, révocation Relay et rollback.

Limites connues: le cache anti-rejeu est borné et en mémoire par processus; plusieurs workers Core doivent partager un routage qui empêche le rejeu entre workers ou accepter que la protection soit locale au worker. La disponibilité repose sur le secret HMAC, l’horloge Core/connecteur et le binding à jour. Le mode `legacy` reste vulnérable à son modèle historique; `dual` autorise intentionnellement le principal legacy provisionné. La migration, la révocation des anciens bearers installés, l’approbation des gates et l’observabilité de production restent des opérations distinctes.
