# ATLAS V6.3 R3-A+ — Installation du connecteur PC

Cette procédure installe le connecteur Node 24 avec un tarball Paseo Relay
épinglé. Elle ne requiert pas de clone Paseo ni de dossier `references/`.
L’installation et la configuration restent locales au compte utilisateur.

## Prérequis

- Linux, Node.js 24 et npm.
- Le relais Paseo joignable à l’adresse configurée. En recette locale, le port
  par défaut est `127.0.0.1:4400` ; en R3-B, le relais public est en `wss://`.
- FastAPI sur `127.0.0.1:8100` si les opérations authentifiées doivent être
  utilisées.
- Pour supervision automatique, systemd avec un gestionnaire utilisateur.

Le connecteur accepte un relais distant en `wss://` (TLS système, certificat et
hostname vérifiés) et n'accepte `ws://` que sur loopback exact, avec l'opt-in
explicite `ATLAS_ALLOW_LOOPBACK_WS=1`. Les identifiants, query, fragment et
chemins arbitraires sont refusés. FastAPI et les métriques restent limités au
loopback. La recette locale peut utiliser le relais loopback ; le déploiement
R3-B utilise le WSS public.

## Installation reproductible

Depuis la racine du dépôt, choisissez un répertoire d'installation neuf :

```sh
install_dir="$HOME/.local/lib/atlas-relay"
install -d -m 0700 "$HOME/.local/lib" "$HOME/.local/state/atlas-relay"
chmod 0700 "$HOME/.local/state/atlas-relay"
install -d -m 0700 "$install_dir"
cp -a integrations/relay/pc/. "$install_dir/"
cd "$install_dir"
node --version
npm ci
npm run build
npm run verify:artifact
```

`node --version` doit afficher `v24.x`. Le pré-install npm vérifie le SHA-256 du
tarball avant d'installer les dépendances. `package-lock.json` épingle toutes
les dépendances transitives. `npm ci` peut télécharger les dépendances npm si
elles ne sont pas déjà dans le cache.

Créer un fichier d'environnement externe privé. Les valeurs ci-dessous ne sont
pas des secrets ; indiquez un server ID opaque si vous souhaitez le fixer :

```sh
install -d -m 0700 "$HOME/.config/atlas"
umask 077
cat > "$HOME/.config/atlas/relay.env" <<EOF
ATLAS_RELAY_URL=wss://relay.example.org
ATLAS_RELAY_STATE_DIR=$HOME/.local/state/atlas-relay
ATLAS_BACKEND_URL=http://127.0.0.1:8100
ATLAS_METRICS_PORT=4401
EOF
chmod 0600 "$HOME/.config/atlas/relay.env"
```

Pour la recette locale loopback uniquement, remplacez la première ligne par
`ATLAS_RELAY_URL=ws://127.0.0.1:4400` et ajoutez
`ATLAS_ALLOW_LOOPBACK_WS=1`. Ne conservez pas cet opt-in pour un relais distant :
un `ws://` distant est toujours refusé.

Ajoutez `ATLAS_SERVICE_TOKEN` à ce fichier uniquement si FastAPI exige un token
pour les opérations protégées. Éditez-le avec un éditeur local fiable, sans
coller sa valeur dans une commande ni l'afficher dans un terminal. Le fichier
doit rester en mode `0600`.

## Vérifications et commandes opérationnelles

Depuis `$HOME/.local/lib/atlas-relay` :

```sh
node scripts/check-config.mjs --env-file "$HOME/.config/atlas/relay.env"
node scripts/check-secret.mjs --env-file "$HOME/.config/atlas/relay.env"
node scripts/diagnose-network.mjs ws://127.0.0.1:4400
npm run test:unit
```

Pour un démarrage manuel au premier plan avec ce fichier externe :

```sh
scripts/start.sh "$HOME/.config/atlas/relay.env"
```

Le contrôle de configuration vérifie l'URL du relais (WSS distant, ou ws
loopback avec `ATLAS_ALLOW_LOOPBACK_WS=1`), l'URL loopback FastAPI, le port
métriques fixe sur `127.0.0.1`, et les modes `0700`/`0600` du répertoire d'état,
de l'identité et du registre. Il ne lit ni n'affiche la valeur du token. Le
contrôle secret affiche uniquement `present` ou `absent` et le mode du fichier
de configuration.

Pour vérifier l'identité publique et la comparer hors bande :

```sh
scripts/show-identity.sh "$HOME/.config/atlas/relay.env"
```

La commande reprend le comportement existant `show-identity` : elle crée
l'identité si elle n'existe pas, puis affiche uniquement la clé publique et
l'empreinte SHA-256. Gardez le répertoire d'état pour conserver cette identité.

Pour sonder un relais TLS distant sans envoyer de payload applicatif :

```sh
node scripts/diagnose-network.mjs wss://relay.example.org
```

Le diagnostic affiche seulement les résultats DNS, TCP, TLS et WebSocket. Il
n'envoie aucun token et ne journalise ni frames ni payloads.

Les commandes CLI existantes restent disponibles via le lanceur qui charge le
fichier externe sans l'afficher :

```sh
node scripts/run-cli.mjs "$HOME/.config/atlas/relay.env" pair-device \
  --label "synthetic-device" \
  --token-file "$HOME/.local/state/atlas-relay/device.json" \
  --capabilities sync.status,plan.revision
scripts/revoke-device.sh "$HOME/.config/atlas/relay.env" DEVICE_UUID
```

Le token d'appareil n'est écrit qu'une fois dans le fichier `--token-file` en
mode `0600`. La révocation passe par le CLI existant `revoke-device` et ne
révoque que l'appareil indiqué.

## Retour arrière après échec

Arrêtez d'abord le service si systemd le supervise, puis créez une sauvegarde
des seuls `identity.json` et `devices.json` dans un emplacement chiffré et
privé :

```sh
systemctl --user stop atlas-relay.service
install -d -m 0700 "$HOME/.local/backups"
node scripts/backup-state.mjs \
  "$HOME/.config/atlas/relay.env" \
  "$HOME/.local/backups/atlas-relay-before-restore"
```

La sauvegarde contient la clé privée du serveur et le registre ; le helper crée
un répertoire `0700`, des fichiers `0600` et un manifeste de hachages. Elle
n'est pas chiffrée par le helper : conservez-la sur un volume chiffré, hors
ligne. Avant toute restauration, vérifiez que le service est arrêté et que
l'empreinte de l'identité dans la sauvegarde est celle attendue :

```sh
backup_dir="$HOME/.local/backups/atlas-relay-known-good"
fingerprint=$(node -e 'const fs=require("node:fs"); const identity=JSON.parse(fs.readFileSync(process.argv[1],"utf8")); process.stdout.write(identity.fingerprint)' "$backup_dir/identity.json")
printf 'Vérifiez hors bande cette empreinte sauvegardée : %s\n' "$fingerprint"
node scripts/restore-state.mjs \
  "$HOME/.config/atlas/relay.env" \
  "$backup_dir" \
  --confirm-fingerprint "$fingerprint" \
  --pre-restore-backup "$HOME/.local/backups/atlas-relay-failed-state" \
  --service-stopped
```

`--service-stopped` est une attestation explicite ; vérifiez séparément
`systemctl --user is-active atlas-relay.service` avant l'appel. Le helper
contrôle l'empreinte confirmée, l'intégrité et les modes de la sauvegarde, puis
conserve une copie préalable de l'état en échec. Il ne restaure aucun token
d'appareil en clair.

## Limites

- Cette recette ne prouve pas un échange Android ni un test sur Pixel.
- Le relais WSS public n'est pas déployé pendant R3-A+ ; le WSS de recette est
  un proxy TLS local avec une CA de test strictement confinée au debug.
- `npm run test:unit` omet le test d'interopérabilité qui exige un relais réel
  sur `127.0.0.1:4400`; `npm test` exécute aussi ce test.
- FastAPI et les métriques restent sur loopback. Aucun port entrant du PC n'est
  nécessaire.
- L'identité du serveur doit rester stable et son empreinte doit être vérifiée
  hors bande lors de l'appairage.
