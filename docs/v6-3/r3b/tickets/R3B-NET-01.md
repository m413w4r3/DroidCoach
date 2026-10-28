# R3B-NET-01 — Entrée Internet et proxy filtrant

| Clé | Valeur |
|---|---|
| TASK_ID / statut | `R3B-NET-01 / INTEGRATED` (2026-10-28) |
| Vague | A |
| BASE_SHA | `5795d378d38b3e86ce99f1551c41d5044cd4a81c` |
| Profil obligatoire | `codex:gpt-6-luna`, effort `xhigh`, session vérifiée par le parent |
| Worktree | worktree Paseo dédié, écriture limitée au scope ci-dessous |
| Dépendances | aucune |

## Objectif observable

Implémenter et tester un proxy HTTP/WebSocket local minimal qui expose
uniquement `GET /health`, `GET /ready` et l'upgrade `GET /ws` Paseo v2 depuis
`127.0.0.1:4400` vers le relais Paseo épinglé sur `127.0.0.1:4402`, et refuse
tout le reste, en particulier `/metrics`.

## Scope d'écriture exclusif

- `integrations/relay/proxy/**` (nouveau répertoire uniquement)
- `docs/v6-3/r3b/preuves/net-01*.txt` (nouveaux fichiers de preuve)

Interdit : modifier `integrations/relay/pc/**`, `android/**`, `backend/**`,
`contracts/**`, la configuration Tailscale, un service personnel, ou installer
un service persistant.

## Contrat technique

- Node 24 ESM, modules standard uniquement (aucune dépendance npm).
- Écoute **exclusivement** `127.0.0.1:4400` (port configurable, mais toute
  adresse non-loopback doit être refusée au démarrage).
- Amont **exclusivement** `127.0.0.1:4402` (configurable, loopback uniquement,
  aucun chemin, aucune query, aucun userinfo).
- `GET /health` et `GET /ready` : proxy HTTP vers l'amont, statut, corps et
  `content-type` préservés ; taille de réponse bornée ; timeout.
- `GET /ws` avec `Connection: Upgrade` et `Upgrade: websocket` : pipe TCP brut
  bidirectionnel après upgrade, sans modifier les frames. Transmettre les
  en-têtes d'upgrade nécessaires (`Sec-WebSocket-Key`, `Sec-WebSocket-Version`,
  `Sec-WebSocket-Protocol`, `Sec-WebSocket-Extensions`) et les paramètres de
  routage.
- Query `/ws` : clés exactes `serverId`, `role`, `v`, `connectionId`
  (facultatif). `serverId` requis, 1..256 octets, sans caractère de contrôle ;
  `role` ∈ `server|client` ; `v` exactement `2` ; `connectionId` 1..256 octets
  s'il est présent ; doublons de clés et clés inconnues refusés.
- Refuser `/metrics` et tous les autres chemins (`404`, sans révéler l'amont).
- Refuser toutes les méthodes autres que `GET` (`405` + `Allow: GET`).
- Refuser un `/ws` sans upgrade (`426`) et tout upgrade invalide (`400`).
- Limites explicites : taille d'en-têtes, nombre de connexions simultanées,
  timeout de connexion amont, timeout d'inactivité WebSocket, délai de
  handshake, taille maximale des réponses HTTP.
- Journalisation : événements bornés (démarrage, arrêt, refus, erreur, code,
  durée). **Jamais** de token, d'en-tête d'authentification, de query
  complète, de payload, de frame Noise, ni d'adresse personnelle.
- Arrêt propre sur `SIGTERM`/`SIGINT` : fermeture de l'écouteur et des sockets
  ouverts, code de sortie 0.
- Fournir un modèle d'unité systemd utilisateur (`integrations/relay/proxy/systemd/`)
  et un README d'exploitation. Ne pas installer ni activer l'unité.
- Le proxy ne doit pouvoir joindre que le relais loopback : aucun proxy vers
  FastAPI, OpenClaw, Bridge, ADB ou un chemin choisi par le client.

## Oracle

Depuis le worktree :

```sh
node --test integrations/relay/proxy/test
```

Doit prouver au minimum :

1. `/health` et `/ready` proxifiés avec statut/corps exacts.
2. `/metrics` refusé (404) même quand l'amont l'exposerait.
3. chemin inconnu refusé, méthode POST/PUT/DELETE refusée.
4. upgrade WebSocket réel : un client envoie des frames texte et binaires, le
   serveur d'écho amont les reçoit et les renvoie **octet pour octet**.
5. `v=1`, `v` absent, `role` invalide, `serverId` absent, query inconnue,
   doublon de clé → refus.
6. amont indisponible → `502` borné, sans fuite d'erreur brute.
7. limites : en-têtes trop grands, nombre de connexions, timeout.
8. aucun secret ni payload dans les logs capturés par le test.
9. arrêt propre et libération du port.

Test d'intégration réel (à exécuter si le relais épinglé est disponible sur
`127.0.0.1:4402`) : démarrer le relais, le proxy sur `127.0.0.1:4400`, vérifier
`/health`, `/ready`, refus `/metrics`, et un upgrade `/ws` avec
`serverId=synthetic&role=server&v=2`. Le test doit être rejouable et ne doit
pas dépendre d'un état personnel.

## Livrables

- Code et tests du proxy.
- README d'exploitation (ports, filtrage, limites, rollback, logs).
- Modèle systemd non installé.
- `docs/v6-3/r3b/preuves/net-01.txt` : commandes, résultats, limites.
- Commit local sur la branche du ticket ; **aucun push**.

## Stop conditions

- Modèle/effort non `gpt-6-luna`/`xhigh` : `BLOCKED`.
- Besoin de modifier un fichier hors scope : arrêter et rendre la main au
  parent.
- Besoin d'ouvrir un port non-loopback, de modifier Tailscale ou d'installer
  un service : `BLOCKED_AUTHORIZATION`.
- Toute fuite de secret, de payload ou de frame dans un log : `STOP`.
- Toute tentative de contourner l'épinglage du relais ou d'accepter `v=1` :
  `STOP`.

## Clôture — 2026-10-28

Statut final : **INTEGRATED**. Tests proxy 18/18 sur l'arbre final `dc8f55e`,
intégration relais réel incluse ; qualification publique initiale PASS ; proxy
et relais redémarrés, `/health`/`/ready` 200 et `/metrics` 404 en local et via
MagicDNS. Limite : recontrôle public final empêché par la connectivité
Tailscale du PC (R-18). Preuves : `preuves/net-01.txt`,
`preuves/funnel-public.txt`, MATRICE NET-01/NET-11/NET-15.
