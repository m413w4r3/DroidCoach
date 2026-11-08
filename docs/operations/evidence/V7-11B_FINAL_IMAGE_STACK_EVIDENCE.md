# V7-11B — preuve de reconstruction image et stack final

**Statut : BLOCKED — précondition de base live non conforme**

**BASE_SHA :** `7efbefcc5b747823cfd6d8da34b0c402953a0b0f`

**Date :** 2026-11-08

## Résultat

Le build propre depuis l’archive Git a réussi et l’image contenait les marqueurs B7 ainsi que `FileReplayNonceCache`. L’export en lecture seule de la base live a ensuite échoué à deux contrôles attendus : le SHA-256 de la copie était `9f5c8fd24e816ad105ce852db75fedc0b1423a9a4f30e96b1bc9c055e3d5ca55` (attendu : préfixe `752919f2`) et les compteurs étaient `0/0/4/0` plutôt que `3/2/2/1`. L’exécution s’est arrêtée avant toute migration, tout démarrage de Core ou tout autre composant isolé.

## Build propre

La source a été extraite avec `git -C "$CODE_ROOT" archive 7efbefcc5b747823cfd6d8da34b0c402953a0b0f | tar -x -C "$TMP/archive"`, puis construite par :

```sh
docker build \
  --build-context contracts="$TMP/archive/contracts" \
  -t atlas-core-v7-11b-final-7efbefc \
  "$TMP/archive/backend"
```

Résultat du build : **PASS**. L’ID produit était `sha256:fdc96178a67d27f2031b9edd6551d1a3caa0e0a9582e0d03d164d38488d20479`, distinct de l’image live. Taille : **150520138 octets**. ENTRYPOINT : `python /usr/local/bin/atlas-core-entrypoint.py`. CMD : `uvicorn atlas_backend.main:app --host 127.0.0.1 --port 8100`.

Inspection de l’image : `O_NOFOLLOW=PASS`, `MigrationIntegrityError=PASS`, `_create_coherent_pre_migration_backup=PASS`, `FileReplayNonceCache=PASS`. L’image dédiée a été retirée lors du nettoyage après l’arrêt.

## Copie de base et motif d’arrêt

Seul `/data/atlas.sqlite3` a été copié par `docker cp`; aucun sidecar live n’a été copié. Le fichier de copie était régulier, mode `0600`. Les contrôles en lecture seule ont donné :

- `PRAGMA user_version`: **3**
- `PRAGMA integrity_check`: **ok**
- lignes de `PRAGMA foreign_key_check`: **0**
- compteurs `events / directive_responses / agent_chat_cache / plan_revision_requests`: **0 / 0 / 4 / 0** (attendu : **3 / 2 / 2 / 1**)
- SHA-256 observé : **`9f5c8fd24e816ad105ce852db75fedc0b1423a9a4f30e96b1bc9c055e3d5ca55`** (attendu : préfixe **`752919f2`**)

La base est bien en version 3 et intègre, mais son empreinte et ses compteurs ne correspondent pas à la base prévue pour cette recette. Aucun test de migration v5, backup pré-migration, verrou, cache, API legacy, Relay/proxy/connecteur ou fail-closed n’a été tenté.

## Préservation live et nettoyage

- Tag Core live et conteneur Core : image `sha256:8604535e144795cc0c5ed079f39ca04a5fab179ad89280d7d18a11d5126e0c55`; état `running`, redémarrages `0`, `StartedAt` inchangé (`2026-11-08T20:29:18.855959725Z`).
- Conteneur Relay : image `sha256:75d68fd8bdb9d60a26203cd7c49c07c035f1f400c47740770056814e76846889`; état `running`, redémarrages `0`, `StartedAt` inchangé (`2026-11-07T10:05:46.958256848Z`), publication toujours sur `127.0.0.1:4402`.
- Le port live Core `127.0.0.1:8100` était toujours à l’écoute. Les ports isolés `18100`, `18101`, `14400`, `14401` et `14402` étaient libres après nettoyage.
- Tag/image dédiés retirés; répertoire temporaire supprimé; aucun conteneur, réseau ou volume dédié créé ou restant. Aucun processus proxy/connecteur n’a été démarré.
- Aucun secret live, contenu de ligne, identifiant d’appareil ou chemin personnel n’a été inclus dans cette preuve. `CODE_ROOT` est resté propre au SHA de base. Aucun push n’a été effectué.

## Limites et reprise

Cette exécution ne qualifie pas l’image finale ni le stack isolé. Avant de reprendre, il faut établir pourquoi l’export live ne correspond pas au hash et aux compteurs attendus, puis fixer une empreinte et des compteurs autorisés pour la copie. Aucun changement live ne découle de cette exécution. Le cache de build Docker n’a pas été purgé globalement.
