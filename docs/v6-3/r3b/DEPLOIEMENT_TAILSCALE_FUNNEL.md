# ATLAS V6.3 — R3-B — Déploiement Tailscale Funnel

Ce document décrit la configuration **effective** utilisée par la campagne
R3-B. Il ne crée ni domaine, ni hébergeur, ni certificat : le Funnel et le
domaine sont déjà approuvés et configurés par le propriétaire.

## 1. Architecture

```text
Pixel Android — LTE/5G / Internet
          |
          | WSS/TLS public :8443
          v
all.taildc7886.ts.net
          |
      Tailscale Funnel
      Terminaison TLS (tailscaled)
          |
          | HTTP/WebSocket local, sans TLS supplémentaire
          v
127.0.0.1:4400
Proxy HTTP/WebSocket filtrant (Node 24, modules standard)
          |
          v
Paseo Relay v2 épinglé
127.0.0.1:4402
          |
          | Routage serverId / role / connectionId / trames Noise
          v
Connecteur Node PC (contrôle + data Paseo v2)
          |
          | HTTP authentifié loopback
          v
FastAPI 127.0.0.1:8100
          |
          v
Adaptateur OpenClaw
```

Le PC n'ouvre aucun port entrant hors Funnel. Le Funnel ne termine que le TLS
public ; **Noise NK `protected` reste de bout en bout** entre Android et le
connecteur PC. Aucun TLS local redondant n'est ajouté entre le Funnel et le
proxy.

## 2. Adresses et ports définitifs

```dotenv
ATLAS_RELAY_URL=wss://all.taildc7886.ts.net:8443
ATLAS_BACKEND_URL=http://127.0.0.1:8100
ATLAS_METRICS_PORT=4401
```

| Usage | Adresse |
|---|---|
| Base WSS (Android et connecteur) | `wss://all.taildc7886.ts.net:8443` |
| WebSocket Paseo v2 | `wss://all.taildc7886.ts.net:8443/ws` |
| Santé | `https://all.taildc7886.ts.net:8443/health` |
| Prêt | `https://all.taildc7886.ts.net:8443/ready` |
| Métriques relais | **non exposées** ; refusées par le proxy |
| Métriques connecteur | `http://127.0.0.1:4401/metrics` (loopback) |
| Proxy | `127.0.0.1:4400` |
| Relais Paseo | `127.0.0.1:4402` |
| FastAPI | `127.0.0.1:8100` |
| OpenClaw | `127.0.0.1:18789` (loopback, hors périmètre) |
| HTTPS privé tailnet | `https://all.taildc7886.ts.net` → `127.0.0.1:8770` (hors périmètre) |

Le port **8443 est obligatoire**. L'URL de base ne contient ni `/ws`, ni token,
ni `serverId`, ni query : les clients construisent le chemin et les paramètres
Paseo v2.

`100.119.62.63` et `fd7a:115c:a1e0::3b01:3ed6` sont des adresses Tailscale,
pas les adresses publiques. Les IP publiques observées via DNS public sont
`176.58.90.46`, `176.58.90.63` et `176.58.90.145` (TTL 300).

## 3. État Funnel de référence

```text
Funnel on:
    tcp://all.taildc7886.ts.net:8443

tcp://all.taildc7886.ts.net:8443
  TLS-terminated TCP, Funnel on
  -> tcp://127.0.0.1:4400

https://all.taildc7886.ts.net
  tailnet only
  / -> http://127.0.0.1:8770
```

Ne pas réinitialiser ce Funnel. Ne pas modifier le service privé `8770`.

## 4. Proxy filtrant

Source : `integrations/relay/proxy/src/server.mjs`. Node 24, aucune dépendance
npm. Le listener est fixé à `127.0.0.1:4400` ; l'amont doit être une IP loopback
littérale, `127.0.0.1:4402` par défaut.

| Requête | Traitement |
|---|---|
| `GET /health` | proxy fixe vers `GET /health` de l'amont |
| `GET /ready` | proxy fixe vers `GET /ready` de l'amont |
| `GET /ws?...` + upgrade WebSocket | validation stricte puis tunnel TCP brut |
| `GET /ws` sans upgrade | `426` |
| `GET /metrics` | `404` |
| autre chemin | `404` |
| méthode autre que GET | `405`, `Allow: GET` |
| corps HTTP sur `/health`, `/ready` ou upgrade `/ws` | refusé |

Query `/ws` autorisée : clés exactes `serverId`, `role`, `v`, `connectionId`
(facultatif). `serverId` 1..256 octets sans caractère de contrôle ; `role`
`server` ou `client` ; `v` exactement `2` ; `connectionId` 1..256 octets s'il
est présent ; doublons et clés inconnues refusés. Le proxy ne transmet que les
en-têtes de handshake nécessaires ; il ne transmet ni cookie, ni
`Authorization`, ni en-tête privé.

Limites par défaut : en-têtes 8 KiB, cible 2 KiB, 64 connexions, connexion
amont 2 s, réponse HTTP 5 s, handshake 3 s, inactivité WebSocket 60 s, corps
amont 64 KiB, en-tête WebSocket retransmis 2 KiB.

### Démarrage manuel

```sh
cd /home/nill/perso/AndroCoach/atlas-preparation
LISTEN_HOST=127.0.0.1 LISTEN_PORT=4400 \
UPSTREAM_HOST=127.0.0.1 UPSTREAM_PORT=4402 \
node integrations/relay/proxy/src/server.mjs
```

### Arrêt

Envoyer `SIGTERM` ou `SIGINT` au processus. Vérifier que
`127.0.0.1:4400` n'écoute plus.

## 5. Relais Paseo épinglé

Upstream : `getpaseo/paseo-relay`,
commit `3fc41c96c8c63f3a7109e832899cc57d473c4531`, image locale
`atlas-paseo-relay:r3a`. Le relais expose nativement `/health`, `/ready`,
`/metrics` et `/ws`. Il est lancé sur `127.0.0.1:4402` uniquement, derrière le
proxy.

```sh
docker run -d --name atlas-relay \
  -p 127.0.0.1:4402:4000 \
  -e PASEO_RELAY_HOST=0.0.0.0 -e PASEO_RELAY_PORT=4000 \
  atlas-paseo-relay:r3a
```

Ne jamais publier `4402` ni connecter le relais directement au Funnel : le
relais exposerait `/metrics` et les autres routes sur Internet.

## 6. Connecteur PC

Configuration privée `~/.config/atlas/relay.env` en `0600` :

```dotenv
ATLAS_RELAY_URL=wss://all.taildc7886.ts.net:8443
ATLAS_RELAY_STATE_DIR=/home/<user>/.local/state/atlas-relay
ATLAS_BACKEND_URL=http://127.0.0.1:8100
ATLAS_METRICS_PORT=4401
ATLAS_SERVICE_TOKEN=<secret dans le fichier, jamais affiché>
```

`ATLAS_SERVICE_TOKEN_FILE` n'existe pas dans le connecteur actuel : ne pas
l'introduire. Le token est lu depuis l'environnement chargé par systemd ou le
lanceur, jamais depuis `ExecStart`.

Démarrage manuel :

```sh
cd /home/nill/perso/AndroCoach/atlas-preparation/integrations/relay/pc
scripts/start.sh "$HOME/.config/atlas/relay.env"
```

Contrôles :

```sh
node scripts/check-config.mjs --env-file "$HOME/.config/atlas/relay.env"
node scripts/check-secret.mjs --env-file "$HOME/.config/atlas/relay.env"
scripts/show-identity.sh "$HOME/.config/atlas/relay.env"
node scripts/diagnose-network.mjs wss://all.taildc7886.ts.net:8443
```

L'identité X25519 et le registre restent sous
`~/.local/state/atlas-relay` (`0700`, fichiers `0600`). Le `serverId` par défaut
est dérivé de l'empreinte publique :
`atlas-pc-<32 premiers caractères hexadécimaux>`.

## 7. Contrôles TLS et DNS

Depuis le PC, le MagicDNS peut résoudre le nom vers l'adresse tailnet
`100.119.62.63`. Pour qualifier l'ingress **public**, résoudre via DNS public :

```sh
curl -s -H 'accept: application/dns-json' \
  'https://1.1.1.1/dns-query?name=all.taildc7886.ts.net&type=A'
```

Puis vérifier le certificat et le chemin public :

```sh
pubip=176.58.90.46
openssl s_client -connect "$pubip:8443" \
  -servername all.taildc7886.ts.net -verify_return_error -brief </dev/null
curl --resolve "all.taildc7886.ts.net:8443:$pubip" \
  -i https://all.taildc7886.ts.net:8443/health
curl --resolve "all.taildc7886.ts.net:8443:$pubip" \
  -i https://all.taildc7886.ts.net:8443/ready
curl --resolve "all.taildc7886.ts.net:8443:$pubip" \
  -i https://all.taildc7886.ts.net:8443/metrics   # doit être 404
```

Critères d'arrêt : certificat invalide, hostname non couvert, redirection vers
un autre hôte, ou `/metrics` accessible.

## 8. Contrôles WSS et Noise

Depuis le PC et depuis Android :

1. Ouvrir un WebSocket `wss://all.taildc7886.ts.net:8443/ws` avec
   `serverId`, `role`, `v=2` et vérifier `101`.
2. Vérifier que le handshake Noise NK aboutit en mode `protected` avec la clé
   publique X25519 épinglée.
3. Vérifier `backend.health`, puis une opération authentifiée
   (`sync.status` ou `coach.chat.request`).
4. Vérifier qu'une mauvaise clé publique, un `v=1`, un `ws://` distant ou un
   certificat non approuvé sont refusés.

Le relais ne constitue pas une authentification : il ne voit que `serverId`,
`connectionId`, rôle et trames chiffrées. Toute la confiance applicative vient
de Noise et du token d'appareil.

## 9. Révocation

```sh
cd /home/nill/perso/AndroCoach/atlas-preparation/integrations/relay/pc
scripts/revoke-device.sh "$HOME/.config/atlas/relay.env" <DEVICE_UUID>
```

Le connecteur ferme la session active avec `DEVICE_REVOKED` puis WebSocket
`4001`. Android passe à `REVOKED`, ne se reconnecte plus authentifié, puis
l'utilisateur supprime explicitement les identifiants locaux. La révocation PC
est l'autorité ; la suppression locale empêche la reconnexion depuis cet
appareil.

## 10. Supervision et système

Les modèles systemd fournis :

- `integrations/relay/proxy/systemd/atlas-relay-proxy.service`
- `integrations/relay/pc/systemd/atlas-relay.service`

Ils ne sont **pas installés ni activés** pendant la campagne. Toute
installation persistante, activation de `linger` ou redémarrage d'un service
personnel exige une autorisation ciblée. Le PC éteint rend le service
indisponible : le Funnel n'apporte pas de haute disponibilité.

## 11. Diagnostic rapide

```sh
tailscale funnel status
ss -ltnp | grep -E ':(4400|4401|4402|8100)\b'
curl -sS -m 3 http://127.0.0.1:4402/health
curl -sS -m 3 http://127.0.0.1:4400/health
curl -sS -m 3 http://127.0.0.1:4401/metrics
systemctl --user status atlas-relay.service --no-pager 2>/dev/null
journalctl --user -u atlas-relay.service -n 50 --no-pager 2>/dev/null
```

## 12. Rollback

1. Arrêter le connecteur supervisé et le proxy.
2. Arrêter le relais local.
3. Révoquer les appareils de test et supprimer leurs identifiants Android.
4. Restaurer la configuration PC précédente depuis une sauvegarde.
5. Réinstaller l'APK précédent et restaurer les données app-only si nécessaire.
6. Vérifier que FastAPI, OpenClaw et le Bridge restent locaux et inchangés.
7. Consigner les SHA, journaux et limites.

Le Funnel existant n'est pas modifié par ce rollback : il continue de pointer
vers `127.0.0.1:4400`, qui n'écoute plus.

## 13. Recontrôle public final R3-B (2026-10-28)

La qualification publique initiale (DNS, certificat Let's Encrypt, TLS 1.3,
`/health`, `/ready`, WSS 101, Noise protégé, refus `/metrics`) est PASS. Le
recontrôle final par IP publique a échoué au TLS parce que `tailscaled` ne
joignait plus le plan de contrôle (`network is unreachable`) ; la configuration
Funnel n'a pas été modifiée. Le rejeu final via MagicDNS a réussi les sept
contrôles E2E. Avant un usage distant, vérifier en lecture seule :

```sh
tailscale status
tailscale funnel status
tailscale netcheck
```

Ne pas modifier la configuration Tailscale pour contourner une panne de
connectivité du plan de contrôle.
