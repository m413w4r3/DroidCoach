# V7-11B-B3 — preuve de sauvegarde/restauration du connecteur

Date d’exécution : 2026-11-08

Base obligatoire : `9385d1249ffdda533e16e44684d42a5000a6748b`

Worktree : `v7-11b-b3-connector-backup`

Périmètre : état synthétique isolé sous `<run-root>`.

## Résultat

Les scripts fournis ne sauvegardent et ne restaurent que `identity.json` et `devices.json`. Ils omettent `operations.json` : le manifeste officiel ne contient pas son hash et la restauration CLI laisse le journal déjà présent dans la cible tel quel. Une sauvegarde manuelle vérifiée avec manifeste peut couvrir et restaurer les trois fichiers.

Aucun état live n’a été lu ou modifié. Les répertoires et fixtures du run temporaire ont été supprimés après capture des résultats. Aucun code source n’a été modifié.

## Pré-vol et méthode

- `git rev-parse HEAD` : `9385d1249ffdda533e16e44684d42a5000a6748b` (conforme à `BASE_SHA`). La branche était `v7-11b-b3-connector-backup` et le worktree était propre au début.
- Environnement utilisé : Linux x86_64, Node `v24.18.0`, Python `3.12.3`, Git disponible. Java/Android n’étaient pas nécessaires. ADB n’a pas été exécuté ; aucun Pixel, port ou service live n’a été utilisé.
- Le run a généré sous `<run-root>` une identité X25519 synthétique, une entrée de registre synthétique et une entrée de journal synthétique. L’identité avait `schema_version=1`, une clé publique de 32 octets et une empreinte SHA-256 cohérente. Le jeton de service synthétique a été généré localement et n’a jamais été affiché. Les clés et jetons n’apparaissent pas dans cette preuve.
- Les répertoires synthétiques avaient le mode `0700`, les fichiers et les manifestes `0600`. Chaque fichier env ne contenait que `ATLAS_RELAY_STATE_DIR` et un `ATLAS_SERVICE_TOKEN` synthétique.
- L’oracle de métadonnées live a utilisé `lstat` et une liste directe du répertoire uniquement : aucun contenu de fichier n’a été lu. `~/.config/atlas/relay.env` n’a pas été touché.

## Commandes et résultats observés

La commande de sauvegarde CLI exécutée depuis la racine du dépôt était :

```sh
node integrations/relay/pc/scripts/backup-state.mjs \
  "$RUN_ROOT/source-state.env" "$RUN_ROOT/backup1-cli"
```

Sortie exacte :

```text
PASS identity and registry backup created with mode 0700/0600
```

Constats : `backup1-cli` contenait `identity.json`, `devices.json` et `manifest.json`; `operations.json` était absent. Le manifeste ne décrivait que `identity.json` et `devices.json`. Le répertoire était `0700`, les trois fichiers `0600`.

La restauration CLI, vers un nouveau répertoire cible prérempli avec un état antérieur synthétique et un journal synthétique distinct, était :

```sh
node integrations/relay/pc/scripts/restore-state.mjs \
  "$RUN_ROOT/cli-target-state.env" "$RUN_ROOT/backup1-cli" \
  --confirm-fingerprint "$SYNTHETIC_FINGERPRINT" \
  --pre-restore-backup "$RUN_ROOT/cli-pre-restore-backup" \
  --service-stopped
```

`SYNTHETIC_FINGERPRINT` provenait de l’identité synthétique et n’a pas été imprimée. La commande a reçu sa valeur en mémoire.

Sortie exacte :

```text
PASS state identity and registry restored; pre-restore backup retained
```

Constats : les hashes restaurés de `identity.json` et `devices.json` correspondaient à la source. `operations.json` dans la cible conservait le hash et l’entrée synthétique antérieurs à la restauration, différents du journal source. La sauvegarde pré-restauration CLI était conservée, mais, comme le backup CLI, elle ne contenait que l’identité et le registre.

La cible devait contenir une identité synthétique avant l’essai : `restoreState()` crée son pré-backup avec `backupState()`, qui échoue si `identity.json` manque. Une restauration CLI vers un répertoire vide n’a donc pas été exécutée; cette limite est déduite du contrôle `state identity is missing` dans `backupState()`.

La sauvegarde manuelle `backup2-complete` a copié les trois fichiers synthétiques, mis chaque copie en `0600`, puis écrit `manifest.json` (`schema_version=1`, SHA-256 de chacun des trois fichiers). La cible manuelle, nouvellement créée et préremplie avec un état antérieur synthétique, a reçu une sauvegarde complète pré-restauration avant l’essai de copie contrôlée. Le manifeste et les trois fichiers avaient les modes `0600`; les deux répertoires de sauvegarde avaient le mode `0700`.

Le restore manuel a vérifié les modes, les trois hashes de manifeste et l’empreinte confirmée de l’identité synthétique, puis a copié les trois fichiers via fichiers temporaires dans une nouvelle cible synthétique. Les hashes source, manifeste et cible étaient identiques :

| Fichier synthétique | SHA-256 source = manifeste = cible |
|---|---|
| `identity.json` | `310dc3515f4f7aad1fde4d1a91f62e53c9377eb02cb8ae34f2d4ab212cfb250e` |
| `devices.json` | `1b24a8543cccb358b27fb35b3ebe8482cdb2619f0bc51643cf22cede38895fb0` |
| `operations.json` | `1ed09a960f8b737e190791168880b0069e3328ae652cda10ff288e2e5df9a226` |

La cible restaurée était `0700`; ses trois fichiers étaient `0600`. La sauvegarde complète pré-restauration a été conservée.

## Oracle live en lecture de métadonnées

Avant et après le run, le répertoire `~/.local/state/atlas-relay` avait le même `mtime_ns` (`1793912825680328322`). Ses 14 entrées directes avaient exactement les mêmes noms, types, modes, tailles et `mtime_ns`; l’empreinte SHA-256 de l’ensemble des noms était identique (`259a8384b4b92ed5660641c6352600511b1606584c0e87de0fa9d93cc704d038`). Aucun fichier n’a été ajouté ou retiré.

Métadonnées inchangées des trois fichiers attendus (contenu jamais lu) :

| Fichier | Mode | mtime_ns avant = après | Taille |
|---|---:|---:|---:|
| `identity.json` | `0600` | `1793054060518239624` | 239 octets |
| `devices.json` | `0600` | `1793610251130415843` | 9 370 octets |
| `operations.json` | `0600` | `1793912825677328286` | 4 419 octets |

## Matrice des oracles

| Oracle | Statut | Preuve |
|---|---|---|
| HEAD égal à la base obligatoire | **PASS** | `9385d1249ffdda533e16e44684d42a5000a6748b` |
| Backup CLI réussi, gap `operations.json` confirmé | **PASS** | Sortie exacte ci-dessus; absence dans le backup et le manifeste |
| Restore CLI réussi pour identité/registre, gap opérations confirmé | **PASS** | Sortie exacte ci-dessus; hashes identité/registre restaurés, journal cible conservé |
| Backup/restore manuel complet | **PASS** | 3/3 hashes égaux; manifeste; modes `0700/0600`; pré-backup complet conservé |
| État live inchangé | **PASS** | `mtime_ns`, empreinte des noms, modes, tailles et `mtime_ns` des 14 entrées identiques |
| Absence de secret ou identifiant live dans les preuves | **PASS** | Fixtures synthétiques uniquement; valeur du jeton et des clés jamais imprimée |
| Nettoyage des fixtures | **PASS** | `<run-root>` supprimé et absence vérifiée |
| Tests Android, Pixel, ADB, ports et services | **NOT_RUN** | Hors périmètre et interdits par le ticket |

**FAIL : aucun. BLOCKED : aucun.** Le premier harnais local contenait une assertion Python chaînée incorrecte; l’oracle a été corrigé et les commandes CLI ont été rejouées avec succès. Cela ne concernait ni le code produit ni l’état live.

## Gap exact dans le code fourni

Dans `integrations/relay/pc/scripts/state-backup.mjs`, `FILES` vaut `["identity.json", "devices.json"]`. `backupState()` et `restoreState()` bouclent sur cette constante; `validateBackup()` ne vérifie que ces deux entrées. Par conséquent :

- `backup-state.mjs` ne copie pas `operations.json` et son manifeste ne porte pas son hash.
- `restore-state.mjs` n’installe ni ne retire `operations.json`. Un journal présent avant restore reste celui de la cible; un journal absent ne sera pas recréé depuis le backup.
- Ajouter `operations.json` à un répertoire de backup ne rend pas le restore fourni complet : sa validation et sa boucle de restauration l’ignorent.
- `--service-stopped` est une attestation fournie par l’opérateur, pas une vérification de l’état du service.

## Procédure opérateur complète proposée

Cette procédure est une proposition basée sur le test synthétique; elle **n’a pas été exécutée sur le service ou l’état live**. Arrêter le connecteur par son mécanisme de supervision réellement identifié et confirmer qu’il est arrêté avant de copier. Ne pas supposer un nom d’unité. Conserver un répertoire de backup distinct de l’état, tous deux accessibles uniquement à l’opérateur (`0700`), et les fichiers à `0600`.

Définir ce helper. Pendant la sauvegarde initiale, le service source doit être arrêté. Avant une restauration ultérieure, arrêter le service cible et réutiliser le helper en mode `pre-restore` pour créer un nouveau répertoire de pré-restauration depuis l’état cible courant. Choisir une seule phase par exécution.

```sh
set -euo pipefail
umask 077
STATE_DIR=/path/to/connector-state
TARGET_STATE_DIR=/path/to/restore-target-state
BACKUP_DIR=/path/to/backup
PRE_RESTORE_DIR=/path/to/pre-restore-backup
PHASE=backup # utiliser pre-restore lors d’une restauration ultérieure

make_complete_backup() {
  src=$1
  dst=$2
  test ! -e "$dst" && test ! -L "$dst"
  install -d -m 0700 "$dst"
  for name in identity.json devices.json operations.json; do
    test ! -L "$src/$name"
    test -f "$src/$name"
    install -m 0600 "$src/$name" "$dst/$name"
  done
  python3 - "$dst" <<'PY'
import hashlib, json, os, pathlib, stat, sys
directory = pathlib.Path(sys.argv[1])
names = ("identity.json", "devices.json", "operations.json")
assert stat.S_IMODE(directory.lstat().st_mode) == 0o700
files = {}
for name in names:
    path = directory / name
    assert stat.S_ISREG(path.lstat().st_mode)
    assert stat.S_IMODE(path.lstat().st_mode) == 0o600
    files[name] = hashlib.sha256(path.read_bytes()).hexdigest()
manifest = directory / "manifest.json"
manifest.write_text(json.dumps({"schema_version": 1, "files": files}, indent=2) + "\n")
os.chmod(manifest, 0o600)
print("PASS complete three-file manifest created")
PY
}

case "$PHASE" in
  backup) make_complete_backup "$STATE_DIR" "$BACKUP_DIR" ;;
  pre-restore) make_complete_backup "$TARGET_STATE_DIR" "$PRE_RESTORE_DIR" ;;
  *) echo 'PHASE must be backup or pre-restore' >&2; exit 2 ;;
esac
```

Pour restaurer, fournir une empreinte attendue obtenue d’un enregistrement de confiance, vérifier le manifeste complet et confirmer l’empreinte avant de remplacer quoi que ce soit. Les chemins suivants sont des placeholders; ne pas réutiliser les chemins temporaires de cette preuve.

```sh
EXPECTED_FINGERPRINT='<64 caractères hexadécimaux confirmés hors bande>'
python3 - "$BACKUP_DIR" "$TARGET_STATE_DIR" "$EXPECTED_FINGERPRINT" "$PRE_RESTORE_DIR" <<'PY'
import base64, hashlib, json, os, pathlib, shutil, stat, sys
backup = pathlib.Path(sys.argv[1])
target = pathlib.Path(sys.argv[2])
expected_fingerprint = sys.argv[3]
pre_restore = pathlib.Path(sys.argv[4])
names = ("identity.json", "devices.json", "operations.json")
assert len(expected_fingerprint) == 64 and all(c in "0123456789abcdef" for c in expected_fingerprint)
def verify(directory):
    assert stat.S_IMODE(directory.lstat().st_mode) == 0o700
    manifest_path = directory / "manifest.json"
    assert stat.S_IMODE(manifest_path.lstat().st_mode) == 0o600
    manifest = json.loads(manifest_path.read_text())
    assert manifest.get("schema_version") == 1 and set(manifest.get("files", {})) == set(names)
    for name in names:
        path = directory / name
        assert stat.S_ISREG(path.lstat().st_mode) and stat.S_IMODE(path.lstat().st_mode) == 0o600
        assert hashlib.sha256(path.read_bytes()).hexdigest() == manifest["files"][name]
    return manifest["files"]
manifest_files = verify(backup)
pre_restore_files = verify(pre_restore)
for name in names:
    assert hashlib.sha256((target / name).read_bytes()).hexdigest() == pre_restore_files[name]
identity = json.loads((backup / "identity.json").read_text())
public_key = base64.b64decode(identity["public_key"], validate=True)
fingerprint = hashlib.sha256(public_key).hexdigest()
assert identity.get("schema_version") == 1 and len(public_key) == 32
assert fingerprint == identity.get("fingerprint") == expected_fingerprint
assert stat.S_IMODE(target.lstat().st_mode) == 0o700
staged = []
for name in names:
    temporary = target / ("." + name + ".restore")
    shutil.copyfile(backup / name, temporary)
    os.chmod(temporary, 0o600)
    staged.append((temporary, target / name))
for temporary, destination in staged:
    os.replace(temporary, destination)
    os.chmod(destination, 0o600)
for name in names:
    assert hashlib.sha256((target / name).read_bytes()).hexdigest() == manifest_files[name]
print("PASS all three restored files match the verified manifest")
PY
```

Maintenir le service arrêté jusqu’à vérification des trois hashes et de l’empreinte restaurée. En cas d’écart, garder le service arrêté et revenir au backup pré-restauration complet. Le script `restore-state.mjs` fourni ne doit pas être présenté comme la restauration de ce backup complet sans évolution dédiée de son validateur et de sa boucle de copie.

## Limites

- Qualification réalisée uniquement avec état synthétique local; aucune conclusion sur la validité ou la cohérence de l’état live.
- Le backup manuel est une procédure opérateur proposée, pas une fonctionnalité livrée ou testée comme outil versionné.
- La cohérence multi-fichier dépend de l’arrêt effectif du connecteur. Le flag `--service-stopped` ne l’impose pas techniquement.
- Aucun test Android, Pixel, ADB, suite complète Node, backend ou service n’a été exécuté.
