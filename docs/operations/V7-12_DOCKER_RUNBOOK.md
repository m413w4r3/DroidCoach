# V7-12 — Runbook opérateur de la stack Docker

**Objet :** exploiter ATLAS au quotidien avec un seul point d'entrée : `./scripts/atlas-stack`.
**Projet Compose :** `atlas-core`.
**Ne jamais utiliser `docker compose down -v`.**

## 1. Fichiers et chemins

| Élément | Chemin |
|---|---|
| Compose de production | `compose.yaml` |
| Override de répétition | `compose.isolated.yaml` |
| Wrapper | `scripts/atlas-stack` |
| Env privé | `~/.config/atlas/stack.env` (0600) |
| Volume Core | `atlas-core-data` |
| État connecteur | `~/.local/state/atlas-relay` |
| Secrets connecteur V7-12 | `~/.local/state/atlas-relay/v7-12` |
| Secrets Core V7-12 | `~/.local/state/atlas-core/secrets/v7-12` |
| État OpenClaw | `~/.openclaw` |
| Backups | `~/.local/state/atlas-stack/backups` (0700) |

Le wrapper lit `ATLAS_STACK_ENV_FILE` ou, par défaut, `~/.config/atlas/stack.env`. Il refuse un fichier absent ou dont le mode n'est pas `0600`.

## 2. Commandes quotidiennes

```sh
# Diagnostic complet, sans afficher de secret
./scripts/atlas-stack doctor

# Démarrer ou mettre à jour la stack
./scripts/atlas-stack up

# État lisible des cinq services et du transport Pixel
./scripts/atlas-stack status

# Journaux récents
./scripts/atlas-stack logs

# Sauvegarde cohérente avec manifeste et rotation
./scripts/atlas-stack backup

# Redémarrer les services sans supprimer l'état
./scripts/atlas-stack restart

# Arrêter et retirer les conteneurs/réseaux, SANS volume
./scripts/atlas-stack down
```

`up` arrête d'abord le service systemd `openclaw-gateway.service` s'il est actif, afin qu'un seul Gateway écrive dans `~/.openclaw`. Il construit les images si nécessaire, démarre les services et attend les healthchecks.

## 3. Ce que `status` doit montrer

| Ligne | Attendu |
|---|---|
| Core | `healthy`, image et uptime, volume `atlas-core-data`. |
| Relay | `healthy`, aucune publication hôte. |
| Proxy | `healthy`, `127.0.0.1:4400` uniquement. |
| Connector | `healthy`, `relay_connected=true`, `active_sessions` (0 ou 1). |
| Gateway | `healthy`, image épinglée, aucun port hôte. |
| Pixel / transport | `pixel-v7` active ou dernière connexion observable ; `BLOCKED` si absent. |
| Ports | aucun `8100`, `4000`, `4401` ou `18789` publié sur l'hôte. |
| Tailscale | Funnel `8443 → 127.0.0.1:4400`, inchangé. |

## 4. Diagnostic par symptôme

### Le proxy répond mais le Pixel ne se connecte pas

1. `./scripts/atlas-stack status` : vérifier `relay_connected`, `active_sessions`, l'état du Funnel.
2. Vérifier `curl -fsS http://127.0.0.1:4400/health` et `curl -kfsS https://all.taildc7886.ts.net:8443/health`.
3. Vérifier que le connecteur a bien la même identité : ne jamais régénérer `identity.json`, ne jamais réappairer `pixel-v7`.
4. Vérifier les logs du connecteur et du Relay, sans lire de payload.

### Le Coach ne répond pas

1. Vérifier `atlas-core` et `openclaw-gateway` healthy.
2. Vérifier que le Gateway utilise bien `openai/gpt-6-luna` et l'agent `atlas`.
3. Vérifier que `ATLAS_AGENT_ADAPTER=openclaw` et `OPENCLAW_BASE_URL=http://openclaw-gateway:18789`.
4. Ne pas modifier le modèle ni les outils pendant la période d'usage réel.

### La synchronisation V4 échoue après le cutover

1. Vérifier le mode : `ATLAS_V4_AUTH_MODE=dual` (cutover) ou `principal`.
2. Vérifier la map connecteur et le credential RELAY.
3. Vérifier `ATLAS_REQUIRE_PRINCIPAL_BINDING=1`, le key ID et le fichier d'assertion.
4. Un `CONSENT_REQUIRED` après cutover est attendu : le nouveau `device_ref` n'hérite pas des consentements historiques. L'utilisateur doit re-consentir explicitement dans l'application. Ne jamais modifier les consentements par script.

### Le conteneur Gateway ne démarre pas

1. Vérifier que `openclaw-gateway.service` est arrêté.
2. Vérifier les permissions de `~/.openclaw` : UID/GID 1000, répertoires 0700, fichiers 0600.
3. Vérifier `HOME=/home/nill`, `OPENCLAW_STATE_DIR`, `OPENCLAW_CONFIG_PATH`.
4. Vérifier que le conteneur peut écrire uniquement dans le mount d'état et les tmpfs prévus.
5. Ne jamais monter `/var/run/docker.sock`.

### Le Core ne démarre pas

1. Vérifier `user_version=5`, `integrity_check=ok`, `foreign_key_check` vide.
2. Vérifier le propriétaire 10001 et le mode 0600 de la base, 0700 de `/data/replay`.
3. Vérifier le keyring et le curseur : 0600, propriétaire 10001, montés read-only.
4. Vérifier qu'un seul Core tourne et qu'aucun ancien processus n'écrit dans le volume.

### Port 4400 déjà occupé

Un ancien proxy hôte ou un ancien conteneur tourne encore. Identifier le processus avec `ss -ltnp`, l'arrêter proprement, puis relancer `up`. Ne pas tuer un service sans avoir identifié son rôle.

## 5. Logs

```sh
./scripts/atlas-stack logs
docker compose -p atlas-core logs --tail=200 atlas-core
docker compose -p atlas-core logs --tail=200 atlas-connector
docker compose -p atlas-core logs --tail=200 openclaw-gateway
```

Les journaux ne doivent contenir ni bearer, ni clé HMAC, ni contenu de conversation. Si un secret apparaît, arrêter la stack, révoquer le secret concerné et documenter l'incident.

## 6. Sauvegarde et restauration

- `backup` capture le Core via l'API SQLite de sauvegarde, les trois fichiers du connecteur et l'état OpenClaw, avec manifeste SHA-256, contrôle de permissions et `integrity_check`.
- La rotation quotidienne conserve les sauvegardes récentes ; ne pas supprimer manuellement les archives de rollback.
- Le stockage hôte est chiffré ZFS `aes-256-gcm`. Ne pas copier les archives vers un support non chiffré.
- Une restauration remplace l'état : elle exige l'arrêt du service concerné, une sauvegarde de l'état courant et l'affichage explicite de la perte des écritures postérieures au backup.

## 7. Arrêt et redémarrage de l'hôte

- Au redémarrage de l'hôte, Docker démarre les services `restart: unless-stopped`.
- Le service systemd Gateway ne doit pas être réactivé tant que le conteneur Gateway utilise le même état.
- Après un redémarrage, exécuter `./scripts/atlas-stack doctor` puis `status`.
- Le Funnel Tailscale reste géré par l'hôte ; ne pas le dockeriser.

## 8. Règles de sécurité permanentes

- Un seul Core, un seul worker, un seul écrivain SQLite, un seul Gateway.
- Aucun port Core/Gateway/Relay/métriques publié sur Internet ou sur l'hôte.
- Aucun socket Docker monté.
- Aucun secret dans Git, un journal ou un argument de processus.
- Aucune révocation LEGACY sans confirmation explicite du propriétaire.
- Aucun `pm clear`, aucune désinstallation, aucun changement de rôle HOME sur le Pixel.
- Toute nouvelle fonctionnalité attend un retour d'usage réel.
