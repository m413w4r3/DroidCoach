# Sauvegarde app-only ATLAS

Outil local pour le paquet `dev.atlas.coach` uniquement. Il utilise `run-as` et ne réalise pas de sauvegarde Android système.

## Préconditions

- Linux, Bash, Python 3, Android SDK Platform Tools (`adb`) et Build Tools (`apksigner`, `aapt`) dans `PATH`.
- Un seul appareil ADB connecté, paquet installé et `run-as` autorisé (APK debuggable).
- La version prise en charge est `versionCode=1`, `versionName=0.1.0`, signée par le certificat debug ATLAS `da3014662eb65e50a87ef364a2e583c331398795b222e3b37b1a16553a00eeee`.
- Le répertoire de sauvegarde doit appartenir à l'utilisateur courant et avoir le mode `0700`. L'archive et le manifeste ont le mode `0600`.

## Commandes

```sh
scripts/v7-07/atlas-product-backup.sh preflight \
  --serial emulator-5554 --apk /chemin/app-debug.apk

scripts/v7-07/atlas-product-backup.sh backup \
  --serial emulator-5554 --backup-dir "$HOME/.local/state/atlas-backups/session-01"

scripts/v7-07/atlas-product-backup.sh verify \
  --archive "$HOME/.local/state/atlas-backups/session-01/dev.atlas.coach-appdata.tar" \
  --manifest "$HOME/.local/state/atlas-backups/session-01/manifest.json"

ATLAS_CONFIRM_RESTORE='RESTORE dev.atlas.coach' \
  scripts/v7-07/atlas-product-backup.sh restore \
    --serial emulator-5554 \
    --archive "$HOME/.local/state/atlas-backups/session-01/dev.atlas.coach-appdata.tar" \
    --manifest "$HOME/.local/state/atlas-backups/session-01/manifest.json" \
    --confirm-restore

scripts/v7-07/atlas-product-backup.sh plan-rollback \
  --backup-dir "$HOME/.local/state/atlas-backups/session-01"
```

`preflight` est en lecture seule. `backup` arrête ATLAS, écrit le tar et son manifeste puis vérifie le listing et les hashes. La sortie attendue commence par `PREFLIGHT_OK`, `BACKUP_OK` ou `VERIFY_OK`. Le manifeste conserve les noms de fichiers, tailles, hashes, version, certificat, date et SHA-256 du serial ; il ne contient ni serial brut ni contenu applicatif.

`restore` exige le drapeau `--confirm-restore` et soit la variable exacte `ATLAS_CONFIRM_RESTORE='RESTORE dev.atlas.coach'`, soit la saisie de cette phrase au prompt. Il vérifie l'archive avant ADB, sauvegarde l'état courant sous `$ATLAS_BACKUP_ROOT` (ou `$XDG_STATE_HOME/atlas-product-backups`, sinon `$HOME/.local/state/atlas-product-backups`), remplace uniquement les fichiers non-cache du paquet, compare les hashes après extraction puis démarre l'application. Un échec affiche le chemin de la sauvegarde de rollback ; l'outil tente aussi de remettre l'état antérieur.

## Limites

- Les caches et `code_cache` sont exclus. Les liens, fichiers spéciaux, chemins absolus, `..` et archives contenant des entrées cache sont refusés.
- Les clés Android Keystore ne sont pas exportables. Les tokens chiffrés sauvegardés peuvent être inutilisables après réinstallation ; un nouvel appairage peut être nécessaire.
- Ce n'est pas un backup Android système, ne restaure ni APK, ni HOME, ni permissions, ni Keystore, et ne convient pas à un paquet/signataire/version différent.
- `plan-rollback` affiche seulement la procédure : réinstaller l'APK précédent de même paquet, signature et versionCode avec `adb install -r`, puis choisir le launcher précédent dans les paramètres Android.
- Les tests sont hors ligne et utilisent de faux exécutables. Ils ne remplacent pas l'exécution ultérieure sur l'AVD jetable prévu.
