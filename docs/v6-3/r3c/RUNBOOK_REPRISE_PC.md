# ATLAS V6.3 — R3-C — Runbook de reprise PC

Ce runbook décrit l'exploitation **supervisée** du PC pour R3-C. Il complète
`docs/v6-3/r3b/RUNBOOK_PC.md` sans le dupliquer : les chemins, unités et
scripts R3-B restent valides, sauf les corrections ci-dessous.

## 1. Composants et ports

| Composant | Écoute | Rôle |
|---|---|---|
| Relais Paseo épinglé | `127.0.0.1:4402` | routage Paseo v2 |
| Proxy filtrant | `127.0.0.1:4400` | entrée Funnel, filtre `/health`, `/ready`, `/ws` |
| Connecteur Node | sortant WSS + `127.0.0.1:4401` | Noise, auth appareil, opérations |
| FastAPI | `127.0.0.1:8100` | chat/plan/sport, adaptateur configuré |
| OpenClaw | `127.0.0.1:18789` | adaptateur IA, jamais modifié sans mandat |
| Funnel public | `all.taildc7886.ts.net:8443` | TLS public → `127.0.0.1:4400` |

Ne jamais publier `4400`, `4401`, `4402`, `8100` ni OpenClaw.

## 2. Pré-vol lecture seule

```sh
tailscale status
tailscale funnel status --json
tailscale netcheck
systemctl is-active tailscaled
ss -ltnp | grep -E ':(4400|4401|4402|8100|8443)\b'
```

Vérifier que le Funnel pointe toujours vers `127.0.0.1:4400`, que le domaine et
le port `8443` sont inchangés, et qu'aucun port interne n'écoute ailleurs que
sur loopback. Ne pas modifier la configuration Tailscale pour masquer une
panne réseau.

`linger` était déjà `yes` avant R3-C. Son état est lu, **pas modifié**.
Aucune unité systemd ATLAS n'est installée.

## 3. Ordre de démarrage (services de test isolés)

```sh
REPO=$(git rev-parse --show-toplevel)

# 1. Relais épinglé
cd "$REPO"
ATLAS_RELAY_PORT=4402 scripts/r3a/start-relay.sh

# 2. Proxy filtrant
setsid -f env LISTEN_HOST=127.0.0.1 LISTEN_PORT=4400 \
  UPSTREAM_HOST=127.0.0.1 UPSTREAM_PORT=4402 \
  node integrations/relay/proxy/src/server.mjs \
  > /tmp/atlas-r3c/proxy.log 2>&1

# 3. FastAPI isolé (adaptateur mock pour la recette)
cd backend
set -a; . "$HOME/.config/atlas/relay.env"; set +a
export ATLAS_TOKEN="$ATLAS_SERVICE_TOKEN"
export ATLAS_DB_PATH="/tmp/atlas-r3c/fastapi.sqlite3"
export ATLAS_AGENT_ADAPTER=mock
setsid -f env ATLAS_TOKEN="$ATLAS_TOKEN" ATLAS_DB_PATH="$ATLAS_DB_PATH" \
  ATLAS_AGENT_ADAPTER="$ATLAS_AGENT_ADAPTER" \
  .venv/bin/python -m uvicorn atlas_backend.main:app --host 127.0.0.1 --port 8100 \
  > /tmp/atlas-r3c/fastapi.log 2>&1

# 4. Connecteur (après relais, proxy et FastAPI)
cd "$REPO/integrations/relay/pc"
npm run build
setsid -f scripts/start.sh "$HOME/.config/atlas/relay.env" \
  > /tmp/atlas-r3c/connector.log 2>&1
```

Le connecteur se reconnecte avec un backoff borné. L'ordre ci-dessus évite un
premier échec transitoire.

## 4. Contrôles de santé

```sh
curl -sS http://127.0.0.1:4402/health
curl -sS -o /dev/null -w '%{http_code}\n' http://127.0.0.1:4400/health   # 200
curl -sS -o /dev/null -w '%{http_code}\n' http://127.0.0.1:4400/ready    # 200
curl -sS -o /dev/null -w '%{http_code}\n' http://127.0.0.1:4400/metrics  # 404
curl -sS http://127.0.0.1:8100/health
curl -sS http://127.0.0.1:4401/metrics | python3 -m json.tool
```

Attendu connecteur : `relay_connected=true`.

Contrôle public **avec DNS indépendant** (ne pas se contenter de MagicDNS) :

```sh
pubip=$(curl -sS -H 'accept: application/dns-json' \
  'https://1.1.1.1/dns-query?name=all.taildc7886.ts.net&type=A' \
  | python3 -c 'import sys,json; d=json.load(sys.stdin); print(next(a["data"] for a in d["Answer"] if a["type"]==1))')
curl --resolve "all.taildc7886.ts.net:8443:$pubip" \
  -sS -o /dev/null -w '%{http_code}\n' \
  https://all.taildc7886.ts.net:8443/health
```

Attendu : `200`. Vérifier aussi `ready=200`, `metrics=404`, puis
`tools/relay-tls-test` ou `r3b-public-e2e.mjs` avec `ATLAS_E2E_PUBLIC_IP`.

## 5. Tests de supervision à exécuter

| # | Test | Méthode | Attendu |
|---|---|---|---|
| 1 | Démarrage ordonné | arrêt complet puis ordre §3 | health/ready 200, connecteur connecté |
| 2 | Disponibilité sans lancer ATLAS Android | health + E2E depuis le PC | services joignables |
| 3 | SIGKILL connecteur | `kill -9`, relance | même identité, même registre, reconnexion |
| 4 | Perte/retour réseau | couper le réseau du PC (mandat), restaurer | reconnexion sans changement d'identité |
| 5 | Redémarrage FastAPI | arrêt/relance | `BACKEND_UNAVAILABLE` puis `ok` |
| 6 | Logs bornés | `check-secret.mjs` + inspection | aucun token/payload |
| 7 | Port 4400 | `ss -ltnp` | `127.0.0.1:4400` uniquement |
| 8 | Aucun port interne public | Funnel status + scan externe | seuls 8443 et 443 privé |
| 9 | Rollback | arrêt services, restauration état vérifiée | identité/registre intacts |

Le test de redémarrage complet du PC exige un **redémarrage réel** ; un
SIGKILL ne suffit pas. Il reste `NOT_RUN` tant qu'il n'est pas exécuté.

## 6. Harnais de test relais isolé

```sh
integrations/relay/pc/scripts/run-isolated-relay-tests.sh
```

- vérifie l'image `atlas-paseo-relay:r3a` ;
- démarre un conteneur loopback dédié ;
- lance `npm test` avec `ATLAS_TEST_RELAY_URL=ws://127.0.0.1:<port>` ;
- supprime le conteneur en sortie.

Sans Docker ou sans image, le harnais retourne `BLOCKED` : ne pas le remplacer
silencieusement par des tests simulés.

## 7. Arrêt et rollback

1. Arrêter le connecteur, le proxy, FastAPI isolé et le relais.
2. Révoquer les appareils synthétiques de test et supprimer leurs tokens.
3. Sauvegarder/restaurer `identity.json`, `devices.json` et
   `operations.json` avec `backup-state.mjs` / `restore-state.mjs` uniquement
   après vérification du manifeste.
4. Vérifier que FastAPI, OpenClaw et le Bridge personnels sont inchangés.
5. Consigner SHA, commandes, journaux et limites.

## 8. Services persistants

Les unités `integrations/relay/pc/systemd/atlas-relay.service` et
`integrations/relay/proxy/systemd/atlas-relay-proxy.service` restent des
**modèles non installés**. Une installation persistante exige un mandat
ciblé ; ne pas activer `linger`, ne pas modifier le service personnel
d'OpenClaw. La disponibilité H24 n'est pas promise.

## 9. Diagnostic des pannes Funnel

Distinguer, dans cet ordre :

1. Funnel configuré mais backend arrêté → TLS peut échouer ou se réinitialiser ;
2. proxy local indisponible → `4400` absent ;
3. relais indisponible → `4402` absent, `/health` proxy en erreur ;
4. connecteur non connecté → `4401/metrics` `relay_connected=false` ;
5. panne Internet du PC → netcheck/DNS public en échec ;
6. plan de contrôle Tailscale inaccessible → `tailscale status` en erreur ;
7. ingress public inaccessible malgré MagicDNS → tester via IP publique ;
8. erreur TLS/certificat → `openssl s_client` et vérification de la chaîne.

Ne jamais modifier le routage, le pare-feu, `tailscaled` ou une configuration
persistante pour contourner une panne. Si le Funnel reste indisponible,
consigner le point exact et marquer `BLOCKED_NETWORK`.
