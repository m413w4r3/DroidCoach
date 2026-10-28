# Proxy Paseo local

Ce proxy Node 24 expose une entrée loopback filtrée vers le relais Paseo local.
Il n'utilise que les modules intégrés de Node et n'ajoute aucune dépendance npm.

## Routes et filtrage

Le listener est fixé à `127.0.0.1:4400` par défaut. Il ne peut pas écouter sur
une adresse différente. L'amont par défaut est `127.0.0.1:4402`; une
configuration différente doit rester une adresse IP loopback littérale, sans
nom DNS, chemin, query ou identifiant.

- `GET /health` et `GET /ready` : requêtes GET vers les deux routes fixes de
  l'amont. Le statut, le corps et `content-type` sont préservés.
- `GET /ws?serverId=…&role=server|client&v=2[&connectionId=…]` avec upgrade
  WebSocket : query validée puis relayée vers le Paseo v2 épinglé. Le tunnel
  transporte les octets TCP des frames sans les décoder ni les modifier.
- `/metrics`, les autres chemins et les méthodes autres que GET sont refusés.
  Une requête HTTP normale vers `/ws` reçoit `426`; une query invalide reçoit
  `400`; un chemin inconnu reçoit `404`.

Les clés de query autorisées sont exactement `serverId`, `role`, `v` et
`connectionId`. Les doublons, clés inconnues, identifiants vides ou de plus de
256 octets, caractères de contrôle et versions autres que `2` sont refusés.
Les queries ne sont jamais consignées.

## Limites

| Limite | Valeur par défaut |
|---|---:|
| Taille cumulée des en-têtes HTTP | 8 KiB |
| Taille de la cible HTTP | 2 KiB |
| Connexions clientes simultanées | 64, puis `503` |
| Connexion à l'amont | 2 s |
| Réponse HTTP complète | 5 s |
| Handshake WebSocket complet | 3 s |
| Inactivité WebSocket | 60 s |
| Corps HTTP reçu de l'amont | 64 KiB, puis `502` |
| Chaque en-tête WebSocket retransmis | 2 KiB |

Les valeurs des limites sont conservées dans le code afin que l'opérateur ne
puisse pas les élargir par une variable d'environnement. Les tests peuvent
injecter des limites plus courtes pour vérifier les refus.

## Lancement et arrêt

```sh
node integrations/relay/proxy/src/server.mjs
```

Variables reconnues : `LISTEN_PORT` (défaut `4400`), `UPSTREAM_HOST` (défaut
`127.0.0.1`) et `UPSTREAM_PORT` (défaut `4402`). `LISTEN_HOST` est accepté
uniquement s'il vaut `127.0.0.1`. Les ports sont validés au démarrage; le
listener ne peut pas être exposé sur une interface publique. Aucun chemin ou
paramètre fourni par le client ne peut modifier l'adresse amont.

`SIGTERM` et `SIGINT` ferment l'écouteur et détruisent les sockets HTTP et
WebSocket suivis. La sortie normale vaut `0`. Le port est alors libéré. Le
modèle systemd de `systemd/atlas-relay-proxy.service` est fourni à titre
d'exemple; cette livraison ne l'installe ni ne l'active.

## Journaux

Chaque ligne est un objet JSON borné : événement, route fixe (`health`, `ready`
ou `ws`), statut, durée et code de refus. Aucun en-tête client, adresse,
query, erreur brute, payload HTTP, frame WebSocket ou contenu Noise n'est
journalisé. L'unité d'exemple envoie stdout/stderr au journal systemd.

## Tests

```sh
node --test integrations/relay/proxy/test
```

La suite démarre des amonts synthétiques loopback et vérifie les corps/status,
les refus, le tunnel WebSocket binaire et texte octet pour octet, les limites,
les logs et les deux signaux d'arrêt. Elle exécute aussi un contrôle du relais
épinglé sur `127.0.0.1:4402` si ce port répond; sinon ce contrôle est marqué
ignoré sans démarrer ni modifier le relais.

## Retour arrière

Aucun service n'est installé par ce dossier. Pour arrêter un lancement manuel,
envoyer `SIGTERM` ou `SIGINT` au processus Node et vérifier que le listener
`127.0.0.1:4400` n'est plus ouvert. Le fichier systemd reste un modèle; sa
copie, son activation et son retrait sont des opérations distinctes de cette
livraison.
