# V7 — relais public et connecteur (scope C, état intermédiaire)

Date : 2026-10-30  
Autorisation : scopes A–D. Ce document couvre C ; le Pixel et le chemin V4 Android ne sont pas encore qualifiés.

## Composants démarrés

- Relais épinglé : image `atlas-paseo-relay:r3a`
  (`sha256:75d68fd8bdb9d60a26203cd7c49c07c035f1f400c47740770056814e76846889`),
  conteneur `atlas-v7-relay`, publication **127.0.0.1:4402→4000**. `/health` local : 200.
- Proxy filtrant : `node integrations/relay/proxy/src/server.mjs`, PID `1607746`,
  `127.0.0.1:4400` → `127.0.0.1:4402` ; `/health` et `/ready` : 200 ; `/metrics` : 404.
- Connecteur PC : `dist/main.js start`, PID `1614858`, état metrics `relay_connected=true` ;
  metrics `127.0.0.1:4401` : 200. Il a été lancé avec l’environnement V4 en processus
  (le chargeur de fichier V4 est corrigé séparément par V7-02B).
- Funnel : `tcp://all.taildc7886.ts.net:8443 → tcp://127.0.0.1:4400`.
  `GET https://all.taildc7886.ts.net:8443/health` et `/ready` : **200**, TLS vérifié
  (`ssl_verify_result=0`).

## Preuve publique V1

Client `scripts/r3b-public-e2e.mjs`, WSS public, Noise NK protégé, appareil appairé
`pixel-v7` (`device_id=563a01fa-b7a0-4a37-a401-d511499a553a`) :
- `hello_ok` protégé et authentifié ;
- `backend.health` = `ok` ;
- `sync.status` = connecté/backend configuré ;
- `coach.chat.request` relayé, réponse non vide, rejeu `replayed=true`,
  conflit d’idempotence `IDEMPOTENCY_CONFLICT`, payload trop grand refusé.
- Aucun token, texte de message ou trame brute n’a été publié.

## Appairage Pixel préparé

- Token de device généré par le connecteur et écrit une seule fois dans
  `~/.local/state/atlas-relay/pixel-v7.token` (`0600`, JSON device/token).
- Métadonnées non secrètes : `~/.local/state/atlas-relay/pixel-v7-pairing.json`
  (URL, `server_id`, clé publique/fingerprint, `device_id`, chemin du token).
- `server_id = atlas-pc-7c7df95437e1bd5c65ba74abdf8181c9`.
- L’appairage est autorisé mais **aucune connexion Pixel** n’a encore eu lieu.

## Limites et état

- Le client public ci-dessus utilise le protocole V1 et le chat legacy mock ; il ne prouve
  ni le chat V4 réel ni la sync V4 Android. Ces preuves exigent V7-03C + APK V7 + Pixel.
- Le chargeur `relay.env` rejetait les clés V4 : V7-02B est en cours ; le connecteur courant
  a été démarré avec l’environnement en processus, pas par le chemin documenté.
- A4-4 (4001→1012) n’est pas testé ; révocation/Funnel non qualifiés.
- Rollback : arrêter le connecteur et le proxy (PIDs enregistrés), puis
  `docker stop atlas-v7-relay && docker rm atlas-v7-relay` ; conserver
  `~/.local/state/atlas-relay` et ne pas toucher au Funnel, au Core ni au Gateway.
