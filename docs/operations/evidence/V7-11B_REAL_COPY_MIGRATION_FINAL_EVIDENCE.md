# V7-11B-B9 — rejeu de migration sur copie réelle

Date : 2026-11-08 UTC
Base obligatoire : 6d1c5390eb4a93b56383e7f2a63d2c0e4ab229b3
Périmètre d’écriture : ce fichier de preuve uniquement.
Code exécuté depuis CODE_ROOT en lecture seule ; aucun changement de code requis.

## Résultat

Le rejeu v3→v5 sur une copie privée est PASS. Le backup pré-migration produit par le code final est un fichier régulier détenu par l’euid, mode exact 0600, v3 et intègre. Un backup préexistant et un symlink entraînent tous deux le refus attendu avant mutation de la copie. Les deux dry-runs et la réouverture idempotente sont PASS.

Le Core live n’a pas été arrêté, redémarré ni modifié. Son export final a le même SHA-256 que les deux exports initiaux, reste v3 et intègre. Le sidecar live antérieur existe toujours en mode 0644, taille 65536 octets. Il devra être vérifié puis déplacé avant toute migration live.

## Pré-vol et commandes

| Contrôle | Résultat |
|---|---|
| HEAD du worktree d’exécution | PASS — 6d1c5390eb4a93b56383e7f2a63d2c0e4ab229b3 |
| Worktree d’exécution avant preuve | PASS — propre |
| HEAD de CODE_ROOT | PASS — même SHA |
| CODE_ROOT après exécution | PASS — propre, aucun changement |
| Core avant/après | PASS — running, restarts=0, started=2026-11-07T10:05:46.547498879Z |
| Ports | PASS — 18100/14400/14401/14402 libres ; 8100 et 4402 occupés |
| Sidecars SQLite live -wal/-shm | PASS — absents avant et après |

Commandes de pré-vol exécutées :

    git rev-parse HEAD
    git status --short
    git -C "$CODE_ROOT" rev-parse HEAD
    git -C "$CODE_ROOT" status --short
    docker inspect --format '{{.RestartCount}} {{.State.StartedAt}} {{.State.Status}}' atlas-core-atlas-core-1
    docker exec atlas-core-atlas-core-1 ls -l /data/atlas.sqlite3 /data/atlas.sqlite3.pre-v4.bak
    docker exec atlas-core-atlas-core-1 stat -c '%F|%a|%s' /data/atlas.sqlite3 /data/atlas.sqlite3.pre-v4.bak
    ss -ltnH '( sport = :18100 or sport = :14400 or sport = :14401 or sport = :14402 or sport = :8100 or sport = :4402 )'

Les seules opérations dans le conteneur ont été ls, stat et test -f. Les exports ont utilisé docker cp du conteneur vers le répertoire privé. Aucun backup live n’a été copié.

## Exports initiaux et état v3

Commande d’export, répétée deux fois :

    docker cp atlas-core-atlas-core-1:/data/atlas.sqlite3 "$RUN_DIR/export1.sqlite3"
    docker cp atlas-core-atlas-core-1:/data/atlas.sqlite3 "$RUN_DIR/export2.sqlite3"
    chmod 600 "$RUN_DIR/export1.sqlite3" "$RUN_DIR/export2.sqlite3"

Les deux fichiers sont réguliers, mode 0600, 249856 octets et identiques octet pour octet. L’export stable a été conservé dans le répertoire privé.

La racine privée était en mode 0700 ; les copies de base, backups, cibles synthétiques et sorties capturées étaient en mode 0600. Le lien symbolique de l’essai dédié est un symlink, pas un fichier régulier.

| Mesure avant migration | Valeur |
|---|---:|
| user_version / ledger | 3 / [1, 2, 3] |
| integrity_check | ok |
| foreign_key_check | 0 violation |
| Propositions projetées | 3 |
| Événements de proposition | 2 |
| États de consentement | 2 |
| Ligne historique conservée | 1 |

Les agrégats correspondent respectivement aux tables agent_proposals_v4, agent_proposal_events_v4, consent_states_v4 et agent_proposals. Seuls PRAGMA, sqlite_master, table_info et COUNT(*) ont servi aux contrôles ; aucun contenu de ligne n’a été lu.

SHA-256 export 1, export 2 et export stable :
752919f29d0abe17ee1bbb67508fecb0116dbd7946df404856264aefe4b9062a

## Migration et backup créé

Commande exacte de migration, appliquée uniquement à la copie stable :

    PYTHONPATH="$CODE_ROOT/backend" "$CODE_ROOT/backend/.venv/bin/python" -c "from atlas_backend.store import SQLiteStore; SQLiteStore('$COPY_DB')"

Résultat PASS : user_version=5 ; ledger [1, 2, 3, 4, 5] ; integrity_check=ok ; 0 violation FK ; tables des migrations 4/5 présentes, dont principals_v4, credentials_v4, relay_principal_bindings_v4, principal_capabilities_v4, principal_events_v4 et agent_proposal_expiration_events_v4 ; colonne device_ref présente dans agent_proposals_v4. Les agrégats 3/2/2/1 sont conservés.

Le nouveau fichier .pre-v4.bak est régulier, non symlink, détenu par l’euid, mode exact 0600, taille 249856 octets, user_version=3, integrity_check=ok et 0 violation FK.

| Fichier privé avant suppression | SHA-256 |
|---|---|
| Copie migrée après idempotence et dry-runs | 6f3fa3714a8c8dd741d19f745ed92114281871348987330af48fcaee8ad5a336 |
| Backup créé par la migration | 492afc8ed0df80c697311237cea347fe3a44c4d195cec382bc1469e09ce2bc81 |

Revue statique de backend/atlas_backend/store.py, méthode _create_coherent_pre_migration_backup (lignes 288–393) : le code vérifie d’abord lstat, crée avec O_CREAT|O_EXCL|O_NOFOLLOW, applique fchmod(0600), contrôle fstat/lstat/propriétaire/mode, sérialise une sauvegarde SQLite cohérente en mémoire, écrit et relit via le descripteur, puis vérifie avec deserialize en mémoire. Il ne passe pas le chemin de backup à sqlite3.connect.

## Fail-closed

Une copie v3 distincte a reçu avant ouverture un backup arbitraire synthétique, régulier, 0600 et de 28 octets. SQLiteStore a levé MigrationIntegrityError avec le message :

    une sauvegarde pré-migration existe déjà ; vérifier puis déplacer l'ancien backup avant de relancer

PASS : la copie est restée v3, intègre, sans violation FK et identique octet pour octet ; le backup préexistant est resté inchangé, mode 0600.

| Fichier privé avant suppression | SHA-256 |
|---|---|
| Copie fail-closed, identique à l’export v3 | 752919f29d0abe17ee1bbb67508fecb0116dbd7946df404856264aefe4b9062a |
| Backup synthétique préexistant, avant et après | 8a612ba30c40af555d548bd41a040abbc4465c6cc70fdc26dfe8faee39a959e1 |

Un second rejeu avec un backup symlink est aussi PASS : MigrationIntegrityError contient la même phrase ; la base v3 reste inchangée, le symlink est préservé et sa cible synthétique n’a pas été modifiée.

SHA-256 de la cible du symlink, avant et après :
46bd58683b8e17b0285cc5e8a0bffdb37371d23ae138aefa700cb6646b7652f0

## Dry-runs et idempotence

Le CLI a créé un principal LEGACY synthétique sur la copie migrée. La valeur synthétique de liaison et l’identifiant généré sont expurgés ; aucune sortie brute contenant cet identifiant n’est incluse.

Commande exécutée, avec sa valeur synthétique remplacée ici par un marqueur d’expurgation :

    PYTHONDONTWRITEBYTECODE=1 PYTHONPATH="$CODE_ROOT/backend" "$CODE_ROOT/backend/.venv/bin/python" "$CODE_ROOT/backend/scripts/atlas_v4_principals.py" --db "$COPY_DB" principal add --kind LEGACY --device-ref '<valeur synthétique expurgée>'

Commande de backfill exécutée sans --apply :

    PYTHONDONTWRITEBYTECODE=1 PYTHONPATH="$CODE_ROOT/backend" "$CODE_ROOT/backend/.venv/bin/python" "$CODE_ROOT/backend/scripts/atlas_v4_principals.py" --db "$COPY_DB" backfill-proposals

Résultat PASS : mode dry_run ; unowned_proposals=3 ; 0 credential ; SHA de la base inchangé avant/après le dry-run. La création antérieure du principal synthétique est la seule mutation de données volontaire après migration.

Commande expiration, sans --apply :

    PYTHONDONTWRITEBYTECODE=1 PYTHONPATH="$CODE_ROOT/backend" "$CODE_ROOT/backend/.venv/bin/python" "$CODE_ROOT/backend/scripts/atlas_v4_expiration_reconcile.py" --db "$COPY_DB" --now 2026-11-08T00:00:00Z

Résultat PASS : 3 propositions ; 1 candidate PENDING_ANDROID ; 0 anomalie ; SHA de la base inchangé avant/après le dry-run.

SHA-256 de la copie après ajout synthétique, identique avant/après les deux dry-runs :
7bb567cb65cf238c7bee7aee937769828044b9c0ff8bfde24259f8651d8bafe8

Commande de réouverture idempotente :

    PYTHONPATH="$CODE_ROOT/backend" "$CODE_ROOT/backend/.venv/bin/python" -c "from atlas_backend.store import SQLiteStore; SQLiteStore('$COPY_DB')"

Résultat PASS : pas d’erreur sur le backup déjà présent ; v5, ledger [1, 2, 3, 4, 5], intégrité ok, FK vide, compteurs 3/2/2/1 conservés, 1 principal LEGACY synthétique et 0 credential. Le backup 0600 est inchangé.

## Live final et ancien sidecar

Commande de l’export final :

    docker cp atlas-core-atlas-core-1:/data/atlas.sqlite3 "$RUN_DIR/live-final.sqlite3"
    chmod 600 "$RUN_DIR/live-final.sqlite3"

| Mesure finale live | Valeur |
|---|---|
| SHA-256 initial et final | 752919f29d0abe17ee1bbb67508fecb0116dbd7946df404856264aefe4b9062a |
| Identique aux exports initiaux | PASS |
| user_version / ledger | 3 / [1, 2, 3] |
| integrity_check / foreign_key_check | ok / 0 violation |
| Propositions projetées | 3 |
| Événements / états de consentement / ligne historique | 2 / 2 / 1 |
| Migration 4/5 | Objets spécifiques absents ; colonne device_ref absente |

Précision de schéma : les tables de projection V4 introduites par les migrations 1–3 existent déjà dans cette base v3. Sont absents les objets ajoutés par les migrations 4/5, notamment principals_v4, credentials_v4, relay_principal_bindings_v4, principal_capabilities_v4, principal_events_v4 et agent_proposal_expiration_events_v4.

L’ancien /data/atlas.sqlite3.pre-v4.bak est toujours présent, fichier régulier, mode 0644, 65536 octets. Il n’a été ni ouvert ni copié. Avant toute migration live, ce backup devra être vérifié puis déplacé par une opération séparée autorisée.

## Tableau des oracles

| Oracle | État | Preuve |
|---|---|---|
| Base SHA et worktrees | PASS | SHA de base exact ; worktrees propres avant exécution ; CODE_ROOT propre après |
| Export stable v3 | PASS | 2 exports identiques ; v3, intègre, FK vide, compteurs 3/2/2/1 |
| Migration de copie v3→v5 | PASS | version 5, ledger 1–5, schéma attendu, compteurs conservés |
| Backup nouveau 0600/v3/intègre | PASS | régulier, non symlink, euid, 0600, 249856 octets, v3 |
| Backup préexistant fail-closed | PASS | message attendu ; copie et backup inchangés |
| Symlink fail-closed | PASS | message attendu ; copie et cible inchangées |
| Backfill dry-run | PASS | 3 non attribuées, 0 credential, hash inchangé |
| Expiration dry-run | PASS | 3 propositions, 1 candidate PENDING_ANDROID, 0 anomalie, hash inchangé |
| Idempotence | PASS | v5/ledger/compteurs conservés malgré le backup existant |
| Live final non modifié | PASS | hash identique ; v3, intégrité, FK et 3 propositions |
| Ancien sidecar live documenté | PASS | présent en 0644, 65536 octets ; déplacement requis avant migration live |
| Nettoyage privé | PASS | racine privée 0700 supprimée ; 20 fichiers réguliers 0600 et 1 symlink retirés ; absence de résidu vérifiée |

## Limites

- Cette preuve qualifie uniquement le rejeu sur copie ; aucune migration live n’a été exécutée.
- L’ancien sidecar live n’a pas été validé par lecture SQLite et n’a pas été déplacé.
- Aucun Pixel, ADB, credential ni secret live n’a été utilisé.
- Les exports sont des copies directes tandis que Core tournait ; leur stabilité a été établie par deux hashes identiques, intégrité SQLite et égalité du hash live final.
- Le résultat ne constitue pas une promotion ni une autorisation de migration live.
