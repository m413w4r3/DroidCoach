# V7-11B — Runbook de rollback après migration live v3→v5

**Statut :** procédure documentée, **non exécutée**. Elle ne doit être utilisée qu'avec une autorisation opérateur et une fenêtre dédiée.
**Objet :** revenir de l'image finale (`1e4c8e6c…`, base v5) à l'image précédente (`8604535e…`) et à la base v3 fraîche, sans supprimer le volume.

## 0. Références exactes

| Élément | Valeur |
|---|---|
| Image précédente | `sha256:8604535e144795cc0c5ed079f39ca04a5fab179ad89280d7d18a11d5126e0c55` (tag `atlas-core-atlas-core`) |
| Image finale | `sha256:1e4c8e6c599a3846e6870143b78cf3d39ae9d2da050db04e26d7cb3af8ecd35a` |
| Backup frais v3 | `9f5c8fd24e816ad105ce852db75fedc0b1423a9a4f30e96b1bc9c055e3d5ca55` |
| Backup B7 secondaire v3 | `193fcae48f10310bc6b39e928730ec79464993245b5e7367ed3ad724eb419138` |
| Base live v5 au moment de la migration | `9f2cd235…` (hash courant, non utilisé pour le rollback) |
| Volume | `atlas-core-data` |
| Override de migration persistant | `$HOME/.local/state/atlas-core/compose.v7-11b.yaml` |

Placeholders à remplacer par l'opérateur : `<backup-root>` (racine privée du backup frais, `0700`), `<compose-root>` (checkout Compose), `<rollback-override>` (fichier privé `0600`).

## 1. Arrêt et préservation de l'état en échec

```sh
docker stop -t 30 atlas-core-atlas-core-1
docker inspect atlas-core-atlas-core-1 --format 'status={{.State.Status}} exit={{.State.ExitCode}}'
```

Préserver la base v5 pour diagnostic, sans la modifier :

```sh
mkdir -m 700 <backup-root>/failed-v5
docker cp atlas-core-atlas-core-1:/data/atlas.sqlite3 <backup-root>/failed-v5/atlas.sqlite3
chmod 600 <backup-root>/failed-v5/atlas.sqlite3
sha256sum <backup-root>/failed-v5/atlas.sqlite3
```

## 2. Vérifier le backup frais v3

```sh
sha256sum <backup-root>/core/data/atlas.sqlite3
# doit être exactement :
# 9f5c8fd24e816ad105ce852db75fedc0b1423a9a4f30e96b1bc9c055e3d5ca55
```

Contrôles SQLite en lecture seule (sans contenu) :

```sh
python3 - <backup-root>/core/data/atlas.sqlite3 <<'PY'
import sqlite3,sys
c=sqlite3.connect(f'file:{sys.argv[1]}?mode=ro',uri=True)
print('user_version',c.execute('pragma user_version').fetchone()[0])
print('integrity',c.execute('pragma integrity_check').fetchone()[0])
print('fk',len(c.execute('pragma foreign_key_check').fetchall()))
print('proposals',c.execute('select count(*) from agent_proposals_v4').fetchone()[0])
print('events',c.execute('select count(*) from agent_proposal_events_v4').fetchone()[0])
print('consents',c.execute('select count(*) from consent_states_v4').fetchone()[0])
print('headers',c.execute('select count(*) from agent_proposals').fetchone()[0])
print(c.execute('select state,count(*) from agent_proposals_v4 group by state').fetchall())
PY
```

Attendu : `v3`, `ok`, FK 0, `3/2/2/1`, états `APPLIED/PENDING_ANDROID/REJECTED`.

## 3. Restaurer la DB v3 dans le volume

Helper depuis l'image finale, en root, volume uniquement :

```sh
docker run --rm --user 0 --entrypoint sh \
  -v atlas-core-data:/data \
  -v <backup-root>/core/data:/backup:ro \
  sha256:1e4c8e6c599a3846e6870143b78cf3d39ae9d2da050db04e26d7cb3af8ecd35a -c '
    cp /backup/atlas.sqlite3 /data/atlas.sqlite3 &&
    rm -f /data/atlas.sqlite3-wal /data/atlas.sqlite3-shm &&
    chown 10001:10001 /data/atlas.sqlite3 &&
    chmod 600 /data/atlas.sqlite3 &&
    if [ -e /data/atlas.sqlite3.pre-v4.bak ]; then
      mv /data/atlas.sqlite3.pre-v4.bak /data/atlas.sqlite3.pre-v4.bak.failed-migration-<timestamp>;
    fi &&
    rm -f /data/atlas.sqlite3.lock
  '
```

Notes :
- Le backup B7 v3 n'est pas supprimé : il est renommé pour diagnostic.
- Le verrou du Core v5 est retiré ; l'ancienne image ne l'utilise pas.
- Le répertoire `/data/replay` peut rester ; l'ancienne image l'ignore.

## 4. Vérifier le volume restauré

```sh
docker run --rm --user 0 --entrypoint sh -v atlas-core-data:/data \
  sha256:1e4c8e6c599a3846e6870143b78cf3d39ae9d2da050db04e26d7cb3af8ecd35a -c \
  'sha256sum /data/atlas.sqlite3; stat -c "%a %u %g %n" /data/atlas.sqlite3; test ! -e /data/atlas.sqlite3.pre-v4.bak && echo EXACT_SIDECAR_ABSENT'
```

Attendu : hash `9f5c8fd2…`, mode `600`, UID/GID `10001`, sidecar exact absent.

Contrôles SQLite identiques à l'étape 2 (`v3`, `ok`, FK 0, `3/2/2/1`, `PENDING_ANDROID` présent).

## 5. Revenir à l'image précédente

Créer `<rollback-override>` (`0600`) :

```yaml
services:
  atlas-core:
    image: atlas-core-atlas-core
```

Démarrer avec les mêmes fichiers Compose et secrets que la production :

```sh
ATLAS_TOKEN_FILE="$HOME/.local/state/atlas-core/secrets/compose/atlas-core.token" \
ATLAS_TOOL_TOKEN_FILE="$HOME/.local/state/atlas-core/secrets/compose/atlas-tool.token" \
OPENCLAW_GATEWAY_TOKEN_FILE="$HOME/.local/state/atlas-core/secrets/compose/openclaw-gateway.token" \
docker compose -p atlas-core \
  -f <compose-root>/compose.yaml \
  -f <compose-root>/compose.openclaw.yaml \
  -f <rollback-override> \
  up -d --no-build --pull never
```

Vérifier :

```sh
docker inspect atlas-core-atlas-core-1 --format 'image={{.Image}} status={{.State.Status}}'
# image doit être sha256:8604535e…
curl -fsS http://127.0.0.1:8100/health
```

Puis vérifier DB v3, compteurs, légacy 200/401 et Relay 200. Le Core doit être healthy avant de considérer le rollback terminé.

## 6. Échec du rollback

- Ne jamais supprimer le volume ni utiliser `docker compose down -v`.
- Ne pas retagger `atlas-core-atlas-core`.
- Si le backup frais est indisponible, utiliser le backup B7 secondaire `193fcae4…` (v3, `0600`) après vérification d'intégrité.
- Si l'ancienne image ne démarre pas, conserver le Core arrêté, les deux backups et la base v5 préservée ; escalader sans tentatives destructives répétées.

## 7. Après rollback

- L'état `PENDING_ANDROID` est restauré ; la migration doit être **ré-autorisée**, pas relancée automatiquement.
- La synchronisation Android reprendra à la reconnexion du Pixel.
- Le backup de la migration et la base v5 en échec restent conservés pour analyse.

## 8. Limites

Cette procédure n'a pas été exercée. Elle dépend de la disponibilité de l'image `8604535e…`, du volume intact et du backup frais vérifié. Toute écriture postérieure au backup est perdue en cas de rollback.
