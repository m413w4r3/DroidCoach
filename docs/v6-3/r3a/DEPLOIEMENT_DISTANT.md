# ATLAS V6.3 R3-A — Handoff de déploiement distant

**Aucune étape de ce document n'a été exécutée pendant R3-A.** Aucun port n'a été
ouvert sur Internet, aucun relais public n'a été utilisé, aucun secrets n'a été
déployé. Ce document prépare une procédure approuvable pour la campagne suivante.

## 1. Composant relais

- **Paseo Relay upstream**, Elixir/Cowboy, révision
  `3fc41c96c8c63f3a7109e832899cc57d473c4531`, image construite depuis
  `references/repos/paseo-relay`.
- Le listener applicatif est `cowboy_clear` : **TLS/WSS doit être terminé par un
  reverse proxy ou la plateforme d'hébergement**. Ne jamais exposer le port 4000
  en clair.
- Le relais route `serverId`, rôle et `connectionId`; il ne voit que des trames
  chiffrées et ne constitue pas une authentification.

## 2. Hébergement

- Option recommandée : petite VM/container avec WebSocket et TLS (Fly.io, VM
  OVH/Hetzner, etc.), ou plateforme équivalente. Le dépôt Paseo Relay fournit
  une configuration Fly (`deployment/fly`) à adapter.
- Le PC n'ouvre **aucun port entrant**. Le connecteur et FastAPI restent sur
  loopback.
- L'Android se connecte en sortie à `wss://<relais>` sur 443.
- Coût indicatif : hébergement conteneur + domaine + certificat ; aucun service
  payant n'est requis pour la recette locale.

## 3. Ce qui tourne sur le PC

1. FastAPI ATLAS : `127.0.0.1:8100` (ou port local choisi).
2. `atlas-relay-connector` : Node 24, identité et registre 0600, métriques
   `127.0.0.1:4401`.
3. Un superviseur utilisateur (systemd user unit, `launchd`, etc.) pour
   redémarrer le connecteur ; ne pas modifier les services personnels existants
   sans autorisation.
4. OpenClaw/Gateway/Bridge restent locaux et inchangés.

## 4. Variables de configuration

PC (exemple, aucune valeur secrète dans Git) :

```text
ATLAS_RELAY_URL=wss://relay.example.org
ATLAS_RELAY_SERVER_ID=atlas-pc-<identifiant opaque>
ATLAS_RELAY_STATE_DIR=~/.local/state/atlas-relay
ATLAS_BACKEND_URL=http://127.0.0.1:8100
ATLAS_SERVICE_TOKEN=<lu depuis un fichier 0600 ou l'environnement>
ATLAS_METRICS_PORT=4401
```

Android (saisi dans l'écran de connexion, jamais dans un log) :

```text
relayUrl = wss://relay.example.org
serverId = atlas-pc-<identifiant opaque>
serverPublicKey = <clé publique X25519 du PC, hors bande>
deviceId = <UUID de l'appareil appairé>
deviceToken = <jeton remis une seule fois>
```

## 5. Appairage

1. Sur le PC : `node dist/main.js show-identity` → clé publique + empreinte.
2. Vérifier l'empreinte par un canal hors bande avec le propriétaire.
3. `pair-device --label <label> --token-file <fichier 0600> --capabilities ...` → device_id + fichier de token (le token n'est jamais imprimé).
4. Saisir l'URL, le serverId, la clé publique, le deviceId et le token sur
   Android. Le token n'est plus réaffiché.
5. Vérifier l'état « Connecté via relais » et une opération `backend.health`.
6. Révocation : `revoke-device <uuid>` sur le PC, puis suppression locale des
   identifiants sur Android. La révocation PC est l'autorité.

## 6. Ports

| Composant | Exposition |
|---|---|
| Relais | 443/TCP WSS public (ou port HTTPS standard) |
| FastAPI | loopback uniquement |
| Connecteur | sortant vers le relais; métriques loopback |
| ADB | jamais exposé |
| OpenClaw Gateway / Bridge | loopback uniquement |

## 7. Ce qui ne doit pas être exposé

FastAPI, le daemon Paseo, le Gateway OpenClaw, le Bridge, les métriques du
connecteur, le répertoire d'état (clé privée, registre), ADB, et toute route
générique vers `127.0.0.1`.

## 8. TLS / WSS

- Certificat valide pour le nom du relais ; redirection HTTP→HTTPS.
- Terminaison TLS par un reverse proxy (Caddy, Nginx, Cloudflare Tunnel, ou TLS
  de la plateforme), avec upgrade WebSocket.
- Le relais applicatif reste en clair sur le réseau privé du conteneur.
- HSTS, TLS 1.2+, suites modernes, journaux sans query sensible.
- Ne pas remplacer le chiffrement de bout en bout par un simple TLS terminé chez
  un tiers : Noise NK reste obligatoire entre Android et le connecteur.

## 9. Clés et rotation

- Clé privée X25519 du serveur : uniquement sur le PC, fichier 0600, sauvegarde
  chiffrée hors ligne. Jamais dans Git, un log ou l'APK.
- Rotation de la clé serveur : nouvelle paire, nouvelle empreinte vérifiée hors
  bande, réappairage des appareils. Les anciennes sessions doivent être closes.
- Jeton d'appareil : 32 octets aléatoires, hash SHA-256 côté PC, stocké chiffré
  côté Android. Rotation = nouveau token + révocation de l'ancien.
- Révocation immédiate : registre PC, fermeture `4001` de la session, refus des
  nouveaux `hello`.

## 10. Reconnexion après redémarrage

- Le connecteur se reconnecte au relais avec backoff borné.
- Android se reconnecte à l'ouverture/reprise de l'application.
- Une session expirée refait un `hello` avec un nouveau nonce.
- Aucun retry aveugle de requête ; l'idempotence et l'expiration restent
  appliquées.

## 11. Mode hors ligne

- Le plan local, les habitudes et les données déjà présentes restent lisibles.
- Le coach distant et le sport sont indisponibles ; l'interface l'affiche.
- Les files locales sont bornées ; aucune requête périmée n'est rejouée.
- Les notifications locales ne dépendent pas du backend.

## 12. Limites Android en arrière-plan

- Android ne garantit pas un WebSocket permanent (Doze, restrictions batterie,
  réseau). R3-A ne promet pas une réception instantanée.
- Pas de service caché, pas de contournement de Doze, pas d'AccessibilityService.
- Un mécanisme push (FCM) et une stratégie de réveil doivent être qualifiés
  séparément avant toute promesse « temps réel ».

## 13. Coût et dépendances

- Un hôte relais (conteneur/VM) + domaine + TLS.
- Node 24 et dépendances du connecteur ; image Elixir pour le relais.
- Supervision, logs et alertes minimales.
- Aucun secret tiers (wger, Bridge, OpenClaw) ne doit être copié sur le relais.

## 14. Commandes d'installation envisagées

```sh
# Relais (exemple local; remplacer par l'hébergement approuvé)
docker build -t atlas-paseo-relay:<sha> references/repos/paseo-relay
docker run -d --name atlas-relay -p 127.0.0.1:4400:4000 \
  -e PASEO_RELAY_HOST=0.0.0.0 -e PASEO_RELAY_PORT=4000 atlas-paseo-relay:<sha>

# PC
cd integrations/relay/pc
npm ci && npm run build
ATLAS_RELAY_URL=wss://relay.example.org \
ATLAS_RELAY_SERVER_ID=atlas-pc-... \
ATLAS_RELAY_STATE_DIR=~/.local/state/atlas-relay \
ATLAS_BACKEND_URL=http://127.0.0.1:8100 \
ATLAS_SERVICE_TOKEN="$(cat ~/.config/atlas/service-token)" \
ATLAS_METRICS_PORT=4401 \
node dist/main.js start
```

Unité systemd utilisateur (esquisse) :

```ini
[Unit]
Description=ATLAS relay connector
After=network-online.target

[Service]
WorkingDirectory=%h/atlas/integrations/relay/pc
EnvironmentFile=%h/.config/atlas/relay.env
ExecStart=/usr/bin/node dist/main.js start
Restart=on-failure
RestartSec=5

[Install]
WantedBy=default.target
```

## 15. Retour arrière

1. Arrêter le connecteur et le relais de test.
2. Révoquer l'appareil (`revoke-device`) et supprimer les identifiants locaux.
3. Restaurer la configuration PC précédente depuis une sauvegarde.
4. Réinstaller l'APK précédent si nécessaire et restaurer les données app-only.
5. Vérifier que FastAPI, OpenClaw et le Bridge restent locaux et inchangés.
6. Consigner les SHA, journaux et limites.

## 16. Recette LTE/5G sur Pixel

**À exécuter uniquement avec l'accord du propriétaire et un relais approuvé.**

1. Vérifier le SHA de l'APK et la version du relais/connecteur.
2. Sauvegarder l'appareil et le calendrier ciblé ; ne pas utiliser `pm clear`.
3. Appairer l'appareil, vérifier l'empreinte hors bande.
4. Désactiver Wi-Fi, activer les données mobiles, vérifier l'absence d'`adb
   reverse` et de proxy USB.
5. Vérifier `backend.health`, une révision `DayPlan`, et la réponse FastAPI.
6. Couper les données mobiles → état hors ligne, plan local préservé.
7. Rétablir → reconnexion et nouvelle requête idempotente.
8. Redémarrer le connecteur PC → reconnexion.
9. Révoquer l'appareil → session fermée, nouvelles requêtes refusées.
10. Vérifier les métriques (latence, erreurs, mémoire) et l'absence de doublon.
11. Consigner `PASS/FAIL/BLOCKED`, SHA, opérateur, heure et limites.
12. Ne pas conclure à une disponibilité 24 h/24 sans test de veille/Doze dédié.
