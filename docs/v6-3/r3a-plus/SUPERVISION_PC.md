# ATLAS V6.3 R3-A+ — Supervision systemd utilisateur

Cette unité Linux est un modèle. Elle n'a pas été installée ou activée dans la
session personnelle du propriétaire pendant sa validation.

## Préparer les chemins

Par défaut, l'unité attend le connecteur dans
`$HOME/.local/lib/atlas-relay`, le fichier d'environnement dans
`$HOME/.config/atlas/relay.env` et l'état dans
`$HOME/.local/state/atlas-relay`. Si le répertoire d'installation diffère,
modifiez `WorkingDirectory=` dans
`integrations/relay/pc/systemd/atlas-relay.service` **avant** de copier l'unité.
Si `ATLAS_RELAY_STATE_DIR` est personnalisé, adaptez aussi `ReadWritePaths=` à
ce chemin et créez-le en mode `0700` avant le démarrage.
Le fichier externe doit être en mode `0600`; le répertoire et les fichiers
d'identité/registre sont préservés sous `~/.local/state/atlas-relay`.

## Installer et démarrer

Après l'installation du paquet et la vérification de configuration décrites
dans [`INSTALLATION_PC.md`](INSTALLATION_PC.md), exécutez les commandes
suivantes sur le compte destiné à faire tourner le connecteur :

```sh
install -d -m 0700 "$HOME/.config/systemd/user"
install -m 0644 \
  integrations/relay/pc/systemd/atlas-relay.service \
  "$HOME/.config/systemd/user/atlas-relay.service"
systemd-analyze --user verify "$HOME/.config/systemd/user/atlas-relay.service"
systemctl --user daemon-reload
systemctl --user enable --now atlas-relay.service
systemctl --user status atlas-relay.service --no-pager
```

L'unité lance `node dist/main.js start`; aucun secret n'est dans `ExecStart` ni
dans les arguments du processus. systemd charge le token depuis `EnvironmentFile`
sans l'imprimer. Le service se relance après une erreur avec `RestartSec=5s` et
est limité à cinq démarrages dans une fenêtre de cinq minutes. SIGTERM est
transmis pour arrêter proprement les WebSockets. Journald limite le débit de
chaque unité à 100 messages par tranche de 30 secondes.

Contrôle d'exploitation :

```sh
systemctl --user is-active atlas-relay.service
journalctl --user -u atlas-relay.service -n 100 --no-pager
node "$HOME/.local/lib/atlas-relay/scripts/check-config.mjs" \
  --env-file "$HOME/.config/atlas/relay.env"
```

Le démarrage automatique à la connexion suffit pour un poste de travail. Le
linger utilisateur est **opt-in** si l'opérateur décide explicitement que le
service doit démarrer avant toute connexion interactive :

```sh
loginctl enable-linger "$USER"
```

Cette option modifie le comportement du compte et n'est pas activée par cette
procédure.

## Validation sans activation

Depuis `integrations/relay/pc` :

```sh
systemd-analyze --user verify systemd/atlas-relay.service
```

La validation effectuée pour ce ticket utilise systemd 255. Aucun service n'a
été installé, activé ou lancé dans la session personnelle. Un test transitoire
dans un gestionnaire utilisateur isolé n'était pas disponible pour cette
validation ; le test n'a donc pas été lancé. Utiliser le gestionnaire personnel
actif n'aurait pas constitué un test isolé.

## Arrêt et retour arrière

```sh
systemctl --user stop atlas-relay.service
systemctl --user disable atlas-relay.service
systemctl --user status atlas-relay.service --no-pager
```

Pour retirer l'unité, supprimez uniquement le fichier installé puis rechargez
systemd :

```sh
rm "$HOME/.config/systemd/user/atlas-relay.service"
systemctl --user daemon-reload
```

Le répertoire d'état n'est pas supprimé par le retour arrière. Conservez-le
pour garder l'identité et le registre, ou restaurez-les avec la procédure
explicite décrite dans `INSTALLATION_PC.md`.

Avant une mise à jour, conservez une copie du paquet installé :

```sh
install_dir="$HOME/.local/lib/atlas-relay"
previous_dir="$HOME/.local/lib/atlas-relay.previous"
test ! -e "$previous_dir"
cp -a "$install_dir" "$previous_dir"
```

Pour revenir à cette copie après une mise à jour défaillante, arrêtez le
service, vérifiez que le chemin d'échec n'existe pas encore, puis restaurez le
paquet et vérifiez son état :

```sh
systemctl --user stop atlas-relay.service
install_dir="$HOME/.local/lib/atlas-relay"
failed_dir="$HOME/.local/lib/atlas-relay.failed"
previous_dir="$HOME/.local/lib/atlas-relay.previous"
test -d "$previous_dir"
test ! -e "$failed_dir"
mv "$install_dir" "$failed_dir"
cp -a "$previous_dir" "$install_dir"
node "$install_dir/scripts/check-config.mjs" \
  --env-file "$HOME/.config/atlas/relay.env"
systemctl --user start atlas-relay.service
systemctl --user status atlas-relay.service --no-pager
```

Le fichier d'état demeure dans `$HOME/.local/state/atlas-relay`; la copie du
paquet ne remplace ni l'identité ni le registre.

Le fichier `relay.env` reste externe à l'unité et aux journaux. Ne le supprimez
pas avant d'avoir révoqué les appareils concernés et sauvegardé l'état si une
restauration est nécessaire.
