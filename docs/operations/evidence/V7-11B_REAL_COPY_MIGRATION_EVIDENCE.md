# V7-11B-B5 — Migration SQLite v3→v5 sur copie privée

- **Date :** 2026-11-08
- **BASE_SHA :** ba28b44de0b85fe1576826c84b8ac1741d8b2958
- **Branche :** v7-11b-b5-real-copy-migration
- **Profil :** codex:gpt-6-luna / xhigh, vérification de session assurée par le parent Paseo.
- **Écriture du dépôt :** ce seul fichier de preuve.
- **Décision :** aucune mutation live, aucune promotion de copie, aucun push.

## Résumé de sécurité et pré-vol

Le worktree et CODE_ROOT avaient le SHA demandé et aucun changement au début. CODE_ROOT était encore au même SHA et propre à la fin. Les commandes Python/scripts ont été lancées avec les chemins passés par variable ; le scan des caches Python n’a trouvé aucun fichier .pyc modifié après le début de la tâche.

Le conteneur Core était actif, RestartCount=0, démarré le 2026-11-07T10:05:46.547498879Z. Le pré-vol a confirmé 8100 et 4402 actifs, et 18100, 14400, 14401, 14402 libres. Docker était 27.1.1, Python du venv backend 3.12.3. Aucun ADB, Pixel, secret live ou fichier de configuration de secret n’a été consulté.

Seules des commandes de lecture du conteneur ont été utilisées (docker inspect, docker exec ... ls / test -f) avec les deux exports docker cp. /data/atlas.sqlite3-wal et -shm étaient absents ; le backup live préexistant a été laissé intact. Aucun docker stop, restart, exec d’écriture ou accès en écriture à /data n’a eu lieu.

La copie privée avait un répertoire mode 0700, toutes ses bases, sauvegardes et traces mode 0600. Le répertoire entier et ses fichiers ont été supprimés après collecte ; aucune copie ni script temporaire ne subsiste.

## Tableau des oracles

| Oracle | Statut | Résultat |
|---|---|---|
| HEAD / worktree initial | **PASS** | SHA = BASE_SHA ; worktree propre. |
| CODE_ROOT initial et final | **PASS** | SHA = BASE_SHA ; git status --short vide avant et après. |
| Pré-vol conteneur et ports | **PASS** | Core actif, 0 redémarrage ; ports conformes aux états attendus. |
| Export 1 | **PASS** | v3, intégrité ok, 0 violation FK, compteurs 3 propositions / 2 événements / 2 consentements ; 1 ligne historique. |
| Export 2 et stabilité | **PASS** | Même intégrité, version, compteurs et SHA-256 que l’export 1 ; aucune variance. |
| Preview avant migration | **PASS** | user_version=3, ledger [1,2,3], migrations 4 et 5 en attente. |
| Migration de la copie | **PASS** | user_version=5, ledger [1,2,3,4,5], intégrité ok, 0 violation FK ; tables V4/V5 et colonne device_ref présentes. |
| Sauvegarde pré-migration | **PASS** | Fichier présent, mode 0600, version 3, intégrité ok, 0 violation FK ; SHA-256 consigné ci-dessous. |
| Historique et propositions après migration | **PASS** | Table historique : 1 ligne avant/après ; propositions V4 : 3 avant/après ; événements : 2 ; états de consentement : 2 ; propositions sans propriétaire : 3 avant backfill. |
| Preview après migration | **PASS** | v5 ; aucune migration en attente. |
| Backfill dry-run | **PASS** | Cible LEGACY synthétique uniquement ; 3 propositions sans propriétaire ; 0 credential ; SHA-256 et compteurs inchangés pendant le dry-run. |
| Expiration dry-run | **PASS** | 3 propositions ; 1 candidate PENDING_ANDROID ; aucun audit/candidat avec ACK ; aucune anomalie ; SHA-256 inchangé. |
| Idempotence | **PASS** | Réouverture conserve v5, ledger, intégrité, FK, historique et compteurs. Le SHA binaire change à chaque initialisation ; empreinte SQL logique identique avant/après la seconde réouverture. |
| Export live final | **PASS** | Toujours v3, intègre, FK vide, tables principales V4/V5 absentes ; SHA identique aux deux exports initiaux. |
| Nettoyage copie privée | **PASS** | Répertoire supprimé et absence vérifiée. |
| ADB / Pixel / suites Android | **UNKNOWN** | Explicitement hors périmètre ; aucune commande ADB ni suite Android lancée. |

## Commandes exécutées

Les variables ci-dessous représentent les chemins réels sans les inscrire dans cette preuve : CODE_ROOT désignait la racine de code en lecture seule ; COPY_ROOT le répertoire privé unique ; COPY_DB la base exportée. Les sorties ont été filtrées pour ne conserver que schéma, compteurs, hashes et statuts.

### Pré-vol et exports

~~~sh
docker inspect --format 'running={{.State.Running}} restarts={{.RestartCount}} started={{.State.StartedAt}}' atlas-core-atlas-core-1
docker exec atlas-core-atlas-core-1 ls -la /data
docker cp atlas-core-atlas-core-1:/data/atlas.sqlite3 "$COPY_ROOT/live-1.sqlite3"
chmod 600 "$COPY_ROOT/live-1.sqlite3"
if docker exec atlas-core-atlas-core-1 test -f /data/atlas.sqlite3-wal; then
    docker cp atlas-core-atlas-core-1:/data/atlas.sqlite3-wal "$COPY_ROOT/live-1.sqlite3-wal"
    chmod 600 "$COPY_ROOT/live-1.sqlite3-wal"
fi
if docker exec atlas-core-atlas-core-1 test -f /data/atlas.sqlite3-shm; then
    docker cp atlas-core-atlas-core-1:/data/atlas.sqlite3-shm "$COPY_ROOT/live-1.sqlite3-shm"
    chmod 600 "$COPY_ROOT/live-1.sqlite3-shm"
fi
docker cp atlas-core-atlas-core-1:/data/atlas.sqlite3 "$COPY_ROOT/live-2.sqlite3"
chmod 600 "$COPY_ROOT/live-2.sqlite3"
# live-2 a été exporté après 2,5 secondes.
# Les suffixes -wal et -shm ont été recherchés en lecture seule et étaient absents.
docker cp atlas-core-atlas-core-1:/data/atlas.sqlite3 "$COPY_ROOT/live-final.sqlite3"
chmod 600 "$COPY_ROOT/live-final.sqlite3"
~~~

Chaque export a été ouvert uniquement en SQLite mode=ro pour PRAGMA user_version, integrity_check, foreign_key_check, noms de tables, colonnes et COUNT(*). Aucun contenu de ligne n’a été affiché.

~~~sh
python3 - <<'PY'
import socket
for port in (18100, 14400, 14401, 14402):
    s = socket.socket()
    try:
        s.bind(("127.0.0.1", port))
        result = "FREE"
    except OSError:
        result = "BUSY"
    finally:
        s.close()
    print(f"port_{port}={result}")
for port in (8100, 4402):
    s = socket.socket()
    s.settimeout(1)
    try:
        s.connect(("127.0.0.1", port))
        result = "LIVE"
    except OSError:
        result = "NOT_LIVE"
    finally:
        s.close()
    print(f"port_{port}={result}")
PY
~~~

### Preview, migration et contrôles

~~~sh
"$CODE_ROOT/backend/.venv/bin/python" "$CODE_ROOT/backend/scripts/atlas_v4_principals.py" --db "$COPY_DB" preview-migration
PYTHONPATH="$CODE_ROOT/backend" "$CODE_ROOT/backend/.venv/bin/python" -c "from atlas_backend.store import SQLiteStore; SQLiteStore('$COPY_DB')"
chmod 600 "$COPY_DB.pre-v4.bak"
"$CODE_ROOT/backend/.venv/bin/python" "$CODE_ROOT/backend/scripts/atlas_v4_principals.py" --db "$COPY_DB" preview-migration
~~~

Le constructeur a été exécuté exclusivement sur COPY_DB. Le backup était v3 avant migration, mode 0600, intégrité ok, 0 violation FK, SHA-256 df644a94a6466046b8c31ba8106ced89cabce2f4786a8ad98f500827bd37d6f8.

Les contrôles de version, intégrité, clés étrangères, ledger, schéma et compteurs ont été faits sur les exports et le backup via des connexions SQLite mode=ro ; seules des requêtes PRAGMA, sqlite_master, table_info et COUNT(*) ont été utilisées.

### Backfill et expiration — dry-run uniquement

~~~sh
"$CODE_ROOT/backend/.venv/bin/python" "$CODE_ROOT/backend/scripts/atlas_v4_principals.py" --db "$COPY_DB" principal add --kind LEGACY --device-ref '<valeur synthétique masquée>'
"$CODE_ROOT/backend/.venv/bin/python" "$CODE_ROOT/backend/scripts/atlas_v4_principals.py" --db "$COPY_DB" backfill-proposals
"$CODE_ROOT/backend/.venv/bin/python" "$CODE_ROOT/backend/scripts/atlas_v4_expiration_reconcile.py" --db "$COPY_DB" --now 2026-11-08T00:00:00Z
~~~

La sortie de principal add et l’identifiant cible du dry-run ont été capturés en mémoire puis filtrés ; aucun identifiant, bearer, token ou valeur de device_ref n’a été imprimé. Aucun --apply n’a été passé et aucune credential n’a été créée.

Résultats du backfill : mode=dry_run, unowned_proposals=3, cible synthétique ; SHA-256 avant et après : 4b931e22bdce7fb0caf8dc8f7f92c69ce6ef5fd385dde6716fcd6f6b7bf9e787.

Résultats expiration : mode=dry_run, proposal_count=3, états APPLIED=1, PENDING_ANDROID=1, REJECTED=1 ; candidate_count=1, candidat PENDING_ANDROID=1, candidates_with_ack=0, toutes les anomalies à 0. Le SHA-256 est resté 4b931e22bdce7fb0caf8dc8f7f92c69ce6ef5fd385dde6716fcd6f6b7bf9e787.

### Idempotence et contrôle live final

~~~sh
PYTHONPATH="$CODE_ROOT/backend" "$CODE_ROOT/backend/.venv/bin/python" -c "from atlas_backend.store import SQLiteStore; SQLiteStore('$COPY_DB')"
docker cp atlas-core-atlas-core-1:/data/atlas.sqlite3 "$COPY_ROOT/live-final.sqlite3"
~~~

Une nouvelle invocation de SQLiteStore(COPY_DB) a gardé v5, les 5 migrations, 3 propositions, 2 événements, 2 états de consentement, 1 ligne historique, 3 propositions sans propriétaire et 0 credential. integrity_check=ok, foreign_key_check=[].

Le hash fichier est passé de 4b931e22bdce7fb0caf8dc8f7f92c69ce6ef5fd385dde6716fcd6f6b7bf9e787 à bdd1522aa861831ed576407b3d22cfac97746ae1269fcd5b6111f4287f5a6d6c. Sur une seconde réouverture, l’empreinte du dump SQL logique est restée identique (085d2cbb7719797e905c204a7a70d3e06232f03478f085ba6e51e6dc7b448503), tandis que le SHA binaire est passé de bdd1522aa861831ed576407b3d22cfac97746ae1269fcd5b6111f4287f5a6d6c à 007fe6c734d7daf978f63520fae0577ae2490005ce3b5a2d4a759897c70fc5dd. Le code d’initialisation réassigne PRAGMA user_version pour chaque migration déjà appliquée avant de terminer à 5 ; la stabilité sémantique demandée passe, mais le fichier n’est pas byte-identique après réouverture.

Le premier export, le second export et l’export live final ont tous le même SHA-256 : 752919f29d0abe17ee1bbb67508fecb0116dbd7946df404856264aefe4b9062a. L’export final confirme user_version=3, integrity_check=ok, 0 violation FK, 3 propositions V4, 2 événements, 2 consentements, 1 ligne historique ; tables de principals/credentials/bindings et audit d’expiration absentes, tandis que la table des propositions V4 existe déjà au schéma v3. Le conteneur est resté actif avec 0 redémarrage et la même heure de démarrage qu’au pré-vol.

## Nettoyage, limites et état final

- Les hashes des artefacts ont été consignés avant suppression : export initial/final 752919f29d0abe17ee1bbb67508fecb0116dbd7946df404856264aefe4b9062a, backup .pre-v4.bak df644a94a6466046b8c31ba8106ced89cabce2f4786a8ad98f500827bd37d6f8, copie après dernière réouverture 007fe6c734d7daf978f63520fae0577ae2490005ce3b5a2d4a759897c70fc5dd.
- Le répertoire privé a été supprimé et son absence vérifiée. Le live n’a jamais été ouvert autrement que par les commandes de listing/test d’existence et les exports docker cp.
- La preuve valide uniquement la migration additive de la copie et les dry-runs. Elle ne prétend pas migrer le service live, appliquer backfill/expiration, créer des credentials, ou qualifier Android/Pixel.
- CODE_ROOT est resté à ba28b44de0b85fe1576826c84b8ac1741d8b2958 avec git status --short vide avant et après.
