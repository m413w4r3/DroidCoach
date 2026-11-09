# V7-11B — reconstruction de l’image Core finale et stack isolé

**Statut : PASS local — image et stack isolé qualifiés sur une copie de la base live**

**SHA du code archivé :** `7efbefcc5b747823cfd6d8da34b0c402953a0b0f`

**SHA du commit de preuve précédent :** `85dc0d55eefe5a8d8fabd75ff2a45484a329a3ea`

**Date :** 2026-11-08

## Incident d’oracle et correction

La première exécution B12 s’est arrêtée à tort sur deux comparaisons inadéquates. Elle a comparé le SHA du fichier SQLite courant au hash d’un export antérieur (`752919f2…`) et a pris les compteurs de tables V1/V2 (`events`, `directive_responses`, `agent_chat_cache`, `plan_revision_requests`, soit `0/0/4/0`) pour les compteurs attendus. La confirmation en lecture seule du parent a établi que le changement de hash vient des métadonnées SQLite après redémarrage et que le contenu logique est identique.

La reprise utilise le hash courant `9f5c8fd24e816ad105ce852db75fedc0b1423a9a4f30e96b1bc9c055e3d5ca55` comme référence et vérifie les agrégats V4 pertinents. L’export frais a confirmé `user_version=3`, `integrity_check=ok`, aucune violation FK, `agent_proposals_v4=3` (APPLIED 1, PENDING_ANDROID 1, REJECTED 1), `agent_proposal_events_v4=2`, `consent_states_v4=2` (GRANTED 1, REVOKED 1), `consent_state_events_v4=4` et `agent_proposals=1`. Seul le fichier principal a été copié, sans sidecar live; la copie était régulière et en mode `0600`.

## Build propre

La source a été archivée au SHA indiqué, puis construite avec le cache Docker :

```sh
git -C "$CODE_ROOT" archive 7efbefcc5b747823cfd6d8da34b0c402953a0b0f | tar -x -C "$TMP/archive"
docker build \
  --build-context contracts="$TMP/archive/contracts" \
  -t atlas-core-v7-11b-final-7efbefc \
  "$TMP/archive/backend"
```

Build : **PASS**. ID : `sha256:1e4c8e6c599a3846e6870143b78cf3d39ae9d2da050db04e26d7cb3af8ecd35a`, distinct des images live. Taille : **150520138 octets**. ENTRYPOINT : `python /usr/local/bin/atlas-core-entrypoint.py`. CMD de l’image : `uvicorn atlas_backend.main:app --host 127.0.0.1 --port 8100`.

Les quatre marqueurs inspectés dans l’image sont présents : `O_NOFOLLOW`, `MigrationIntegrityError`, `_create_coherent_pre_migration_backup` et `FileReplayNonceCache`. Le test principal a remplacé le bind interne du CMD par `--host 0.0.0.0`, nécessaire au port publié depuis le bridge; la publication hôte est restée liée exclusivement à `127.0.0.1:18100`.

## Stack principal sur la copie réelle

Le volume de données dédié a été initialisé avec la copie v3. Le volume secrets privé contenait deux tokens synthétiques distincts, fichiers `0400`, répertoire `0700`, possédés par UID 10001. Le Core a utilisé l’adaptateur `mock`, V4 désactivé, le mode d’authentification `legacy` et le cache de replay sous `/data/replay`. Le healthcheck `/health` a répondu **200**.

Après démarrage :

- `user_version=5`, `integrity_check=ok`, aucune violation FK, ledger `[1, 2, 3, 4, 5]` et colonne `device_ref` présente.
- Les totaux V4 restent 3 propositions, 2 événements de proposition, 2 états de consentement et 4 événements de consentement; la table historique `agent_proposals` contient 1 ligne.
- La candidate `PENDING_ANDROID` a expiré au démarrage du service : l’état observé est devenu APPLIED 1, EXPIRED 1, REJECTED 1, avec 1 événement d’expiration. Le backup pré-migration conserve l’état v3 initial et ses trois états originaux.
- Le backup créé par B7 est un fichier régulier, mode `0600`, `user_version=3`, intégrité `ok`, SHA-256 `193fcae48f10310bc6b39e928730ec79464993245b5e7367ed3ad724eb419138`.
- Le chemin résultant du code B7 est `/data/atlas.sqlite3.pre-v4.bak` : le suffixe `.pre-v4.bak` est ajouté au nom de la base. Le verrou `/data/atlas.sqlite3.lock` est en `0600`; `/data/replay` est en `0700`.
- Les logs Core ne contenaient ni fatal, ni traceback, ni `MigrationIntegrityError`.

L’authentification legacy a été exercée avec des fichiers curl privés en `0600` : le token Android synthétique a obtenu **200** sur `GET /v1/directives/current`; le faux token a obtenu **401**. Aucune valeur de token n’a été affichée ni passée en argument.

## Relay, proxy et connecteur

Le Relay épinglé (`sha256:75d68fd8bdb9d60a26203cd7c49c07c035f1f400c47740770056814e76846889`) a été lancé sans volume et avec `--restart no`, sur un bridge dédié non-interne nécessaire au port publié. Son unique publication hôte était `127.0.0.1:14402`. Proxy et connecteur, lancés depuis la copie CODE_ROOT en lecture seule, écoutaient seulement sur loopback (`14400` et `14401`).

- `/health` Relay et proxy : **200**.
- `/metrics` connecteur : **200**, `relay_connected=true`.
- Le proxy a observé trois upgrades WebSocket **101**. Un client WebSocket synthétique a ouvert un canal de données; `connections_opened_total=1`, ce qui confirme l’enregistrement du serveur côté Relay et son contrôle depuis le connecteur.
- Le répertoire d’état connecteur était en `0700`, son identité synthétique en `0600`. Aucun appareil n’a été appairé et aucune opération produit n’a été envoyée.

## Fail-closed

Un volume distinct a reçu la copie v3 et un backup synthétique préexistant, régulier et en `0600`, au chemin attendu par B7 (`atlas.sqlite3.pre-v4.bak`). Le conteneur dédié est sorti avec code **1**; son log contenait `MigrationIntegrityError` et l’instruction « vérifier puis déplacer ». `/health` sur `18101` n’a pas répondu (**000**).

Après l’échec, la DB est restée octet pour octet au SHA v3 `9f5c8fd24e816ad105ce852db75fedc0b1423a9a4f30e96b1bc9c055e3d5ca55`, version 3, intègre et sans violation FK. Le hash du backup synthétique est resté `aab8c6662ec66aa7041f45b958723b63f8c4811d98b1a9568f6458576ff0c0f6`, avec le mode `0600` inchangé.

## Live, nettoyage et limites

- L’image live et le tag `atlas-core-atlas-core:latest` sont restés `sha256:8604535e144795cc0c5ed079f39ca04a5fab179ad89280d7d18a11d5126e0c55`. Le Core live est resté actif, `restarts=0`, `StartedAt=2026-11-08T20:29:18.855959725Z`, port `127.0.0.1:8100`.
- Le Relay live est resté sur l’image épinglée, actif, `restarts=0`, `StartedAt=2026-11-07T10:05:46.958256848Z`, port `127.0.0.1:4402`.
- Un nouvel export final en lecture seule a gardé le hash `9f5c8fd2…`, v3, l’intégrité, les FK et les compteurs initiaux. Aucune écriture du volume live, aucun secret live et aucun ADB n’ont été utilisés.
- Tous les conteneurs, le réseau, les volumes privés, le proxy, le connecteur et le répertoire temporaire de l’exécution ont été supprimés. Les ports `18100`, `18101`, `14400`, `14401` et `14402` sont libres. L’image finale dédiée est conservée sous son tag unique. Le cache Docker partagé n’a pas été purgé.
- Le worktree CODE_ROOT est resté propre au SHA de code; aucun code n’a été modifié et aucun push n’a été effectué. La preuve ne contient aucun secret, contenu de ligne, identifiant d’appareil ni chemin personnel.

Cette qualification porte sur le build Core et un stack local avec connecteur synthétique et adaptateur IA `mock`. Elle ne qualifie ni migration live, ni appareil Android/Pixel, ni appairage ou opération réelle.
