# ATLAS V6.3 — R3-B — Runbook PC

Runbook d'exploitation du PC hôte. L'architecture, le DNS, le TLS et le
filtrage sont décrits dans
[`DEPLOIEMENT_TAILSCALE_FUNNEL.md`](DEPLOIEMENT_TAILSCALE_FUNNEL.md).

## 1. Composants et ports

| Composant | Écoute | Rôle |
|---|---|---|
| Relais Paseo épinglé | `127.0.0.1:4402` | routage Paseo v2, aucune authentification |
| Proxy filtrant | `127.0.0.1:4400` | entrée Funnel, filtre `/health`, `/ready`, `/ws` |
| Connecteur Node | sortant WSS + `127.0.0.1:4401` | Noise répondant, auth appareil, opérations |
| FastAPI | `127.0.0.1:8100` | chat, plan, sport, politique |
| OpenClaw | `127.0.0.1:18789` | adaptateur IA, hors périmètre de modification |
| Funnel public | `all.taildc7886.ts.net:8443` | TLS public → `127.0.0.1:4400` |

Ne jamais publier `4400`, `4401`, `4402`, `8100` ni OpenClaw.

## 2. Fichiers privés

```text
~/.config/atlas/relay.env                 0600  (URL, état, backend, token)
~/.config/atlas/openclaw-gateway-token    0600  (existant, jamais affiché)
~/.local/state/atlas-relay/               0700  (identity.json, devices.json)
~/.local/state/atlas-relay/operations.json 0600 (journal d'idempotence)
```

`ATLAS_SERVICE_TOKEN` reste dans `relay.env`. Il n'existe pas de
`ATLAS_SERVICE_TOKEN_FILE` dans le connecteur actuel.

## 3. Ordre de démarrage

```sh
# 1. Relais épinglé
docker run -d --name atlas-relay \
  -p 127.0.0.1:4402:4000 \
  -e PASEO_RELAY_HOST=0.0.0.0 -e PASEO_RELAY_PORT=4000 \
  atlas-paseo-relay:r3a

# 2. Proxy filtrant
cd /home/nill/perso/AndroCoach/atlas-preparation
LISTEN_HOST=127.0.0.1 LISTEN_PORT=4400 \
UPSTREAM_HOST=127.0.0.1 UPSTREAM_PORT=4402 \
node integrations/relay/proxy/src/server.mjs

# 3. FastAPI (exemple de test isolé)
cd backend
set -a; . "$HOME/.config/atlas/relay.env"; set +a
export ATLAS_TOKEN="$ATLAS_SERVICE_TOKEN"
export ATLAS_DB_PATH="$HOME/.local/state/atlas-r3b/fastapi.sqlite3"
export ATLAS_AGENT_ADAPTER=mock   # ou openclaw avec le jeton existant
exec .venv/bin/python -m uvicorn atlas_backend.main:app --host 127.0.0.1 --port 8100

# 4. Connecteur
cd integrations/relay/pc
scripts/start.sh "$HOME/.config/atlas/relay.env"
```

Le connecteur se reconnecte avec un backoff borné. Le proxy et le relais
doivent être démarrés avant lui pour éviter un premier échec transitoire.

## 4. Contrôles de santé

```sh
curl -sS http://127.0.0.1:4402/health
curl -sS http://127.0.0.1:4400/health
curl -sS http://127.0.0.1:4400/ready
curl -sS -o /dev/null -w '%{http_code}\n' http://127.0.0.1:4400/metrics   # 404
curl -sS http://127.0.0.1:8100/health
curl -sS http://127.0.0.1:4401/metrics | jq .relay_connected
```

Contrôle public :

```sh
pubip=$(curl -s -H 'accept: application/dns-json' \
  'https://1.1.1.1/dns-query?name=all.taildc7886.ts.net&type=A' \
  | jq -r '.Answer[]? | select(.type==1) | .data' | head -1)
curl --resolve "all.taildc7886.ts.net:8443:$pubip" \
  -sS -o /dev/null -w '%{http_code}\n' \
  https://all.taildc7886.ts.net:8443/health
curl --resolve "all.taildc7886.ts.net:8443:$pubip" \
  -sS -o /dev/null -w '%{http_code}\n' \
  https://all.taildc7886.ts.net:8443/metrics
```

## 5. Appairage

```sh
cd integrations/relay/pc
node scripts/run-cli.mjs "$HOME/.config/atlas/relay.env" pair-device \
  --label "pixel-r3b" \
  --capabilities sync.status,coach.status,plan.today,plan.revision,sport.plan,coach.chat \
  --token-file "$HOME/.local/state/atlas-r3b/device-pixel-r3b.json"
```

Le token est écrit une seule fois dans un fichier `0600`; il n'est jamais
affiché. L'empreinte X25519 doit être vérifiée hors bande avec l'opérateur
Android. Un appareil appairé sans `coach.chat` peut utiliser les anciennes
opérations mais reçoit `OPERATION_UNAVAILABLE` pour le chat ; il doit être
réappairé avec la capacité pour utiliser le chat.

## 6. Révocation

```sh
scripts/revoke-device.sh "$HOME/.config/atlas/relay.env" <DEVICE_UUID>
```

Effet : le connecteur envoie `DEVICE_REVOKED`, ferme la session WebSocket avec
`4001`, et refuse toute nouvelle authentification. Android passe à `REVOKED`
puis l'utilisateur supprime explicitement les identifiants locaux. La
révocation PC reste l'autorité.

## 7. Redémarrages

### Connecteur

```sh
systemctl --user restart atlas-relay.service   # si installé
# ou arrêter puis relancer scripts/start.sh
```

Attendu : même empreinte d'identité, même registre, reconnexion au relais,
clients Android reconnectés. Un POST incertain reste bloqué jusqu'à la fin de
la fenêtre d'idempotence de 10 minutes.

### FastAPI

Arrêter FastAPI puis relancer. Attendu : `backend.health` devient
`BACKEND_UNAVAILABLE` (erreur explicite), puis redevient `ok` après
restauration. Aucune réponse inventée, aucun rejeu automatique.

### Proxy ou relais

Arrêter puis relancer le composant. Attendu : le connecteur se reconnecte avec
backoff ; Android repasse par `RELAY_CONNECTING` puis `RELAY_CONNECTED` ; les
requêtes en vol non envoyées sont abandonnées, les requêtes envoyées ne sont
pas rejouées automatiquement.

## 8. Supervision

Modèles fournis, non installés :

- `integrations/relay/proxy/systemd/atlas-relay-proxy.service`
- `integrations/relay/pc/systemd/atlas-relay.service`

```sh
systemd-analyze --user verify integrations/relay/proxy/systemd/atlas-relay-proxy.service
systemd-analyze --user verify integrations/relay/pc/systemd/atlas-relay.service
```

`loginctl enable-linger` n'est pas activé par la campagne. Sur l'hôte de
recette, `Linger=yes` était déjà l'état préexistant ; il n'a pas été modifié.

## 9. Journalisation et métriques

- Proxy : stdout JSON borné, aucun en-tête, query, payload ou frame.
- Connecteur : stdout/stderr sans token ni payload ; métriques JSON sur
  `127.0.0.1:4401/metrics`.
- FastAPI : journaux locaux standard ; aucun corps de message personnel dans
  les preuves.
- Ne jamais copier `relay.env`, `identity.json`, `devices.json` ou un token
  dans un ticket, un commit ou une capture.

## 10. Diagnostic

```sh
tailscale funnel status
ss -ltnp | grep -E ':(4400|4401|4402|8100)\b'
curl -sS -m 3 http://127.0.0.1:4402/health
curl -sS -m 3 http://127.0.0.1:4400/health
curl -sS -m 3 http://127.0.0.1:4401/metrics
node integrations/relay/pc/scripts/check-config.mjs \
  --env-file "$HOME/.config/atlas/relay.env"
node integrations/relay/pc/scripts/check-secret.mjs \
  --env-file "$HOME/.config/atlas/relay.env"
node integrations/relay/pc/scripts/diagnose-network.mjs \
  wss://all.taildc7886.ts.net:8443
```

## 11. Rollback

1. Arrêter le connecteur, le proxy, FastAPI de test et le relais.
2. Révoquer les appareils de test ; supprimer leurs tokens et identifiants.
3. Restaurer `identity.json`/`devices.json` depuis une sauvegarde vérifiée si
   nécessaire (`backup-state.mjs` / `restore-state.mjs`).
4. Restaurer l'APK et les données app-only du Pixel si une installation a eu
   lieu.
5. Vérifier que FastAPI, OpenClaw et le Bridge personnels sont inchangés.
6. Consigner les SHA, journaux et limites.

Le PC éteint rend le service indisponible : le Funnel n'apporte aucune haute
disponibilité.
