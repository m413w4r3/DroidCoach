# ATLAS V6.3 — R3-A+ — Transport de prédéploiement

Statut : **contrat de campagne**, figé avant délégation. Toute divergence doit
être inscrite dans `DECISIONS.md` et validée par le parent.

## 1. Topologie cible de la recette

```text
Pixel Android 17 (ATLAS debug)
  │  WSS 443, certificat de test approuvé uniquement en debug
  ▼
Proxy TLS de test (loopback)
  │  ws://127.0.0.1:<port-relais>
  ▼
Paseo Relay v2 (image upstream épinglée)
  │  trames Noise NK protected
  ▼
Connecteur PC (Node 24)
  │  HTTP 127.0.0.1:<port FastAPI>
  ▼
FastAPI ATLAS (loopback uniquement)
```

Le relais ne voit que `serverId`, `connectionId`, rôle et trames chiffrées. Le
proxy TLS de test ne voit que le WebSocket. FastAPI n'est jamais joignable
depuis l'adresse publique du relais ni depuis le proxy.

## 2. Politique d'URL

| Entrée | PC connecteur | Android release | Android debug |
|---|---|---|---|
| `wss://relay.example.org` | accepté si TLS valide | accepté | accepté |
| `wss://relay.example.org:8443` | accepté | accepté | accepté |
| `ws://127.0.0.1:4400` | accepté si dev local activé | refusé | accepté |
| `ws://localhost:4400` | accepté si dev local activé | refusé | accepté |
| `ws://relay.example.org` | refusé | refusé | refusé |
| URL avec `user:pass@` | refusé | refusé | refusé |
| URL avec query/fragment | refusé | refusé | refusé |
| Chemin arbitraire | refusé | refusé | refusé |
| Certificat inconnu / mauvais hostname | refusé | refusé | refusé hors CA de test |

Le mode développement local est un flag explicite
(`ATLAS_ALLOW_LOOPBACK_WS=1`), jamais un défaut de production.

## 3. TLS

- Release Android : trust store système uniquement, aucun « accepter tout ».
- Debug Android : CA de test via `src/debug/res/xml/network_security_config.xml`,
  jamais via le manifeste principal ni une release.
- Le connecteur Node utilise le trust store système par défaut. Une CA de test
  ne peut être fournie que par variable de test explicite, documentée et
  absente de la configuration de production.
- Toute erreur TLS, identité Noise ou permission est distinguée dans l'état UI.

## 4. Identité et Noise

- L'identité X25519 du connecteur est épinglée hors bande dans la configuration
  Android. Une mauvaise clé publique fait échouer le handshake Noise.
- Noise `protected` reste obligatoire. Un `e2ee_ready` legacy est refusé.
- WSS et Noise sont cumulatifs.
- La révocation PC ferme la session et interdit tout nouveau `hello`.

## 5. Frontières

- Le connecteur n'expose aucune route générique, aucun proxy HTTP et aucun
  accès fichier/shell.
- Les métriques restent sur loopback.
- Aucun port public n'est ouvert pendant R3-A+.
- Aucun service cloud, domaine ou certificat public n'est créé.

## 6. Scénarios WSS obligatoires

| ID | Scénario | Attendu |
|---|---|---|
| W1 | WSS + CA de test approuvée | connexion et handshake protégé |
| W2 | Mauvais hostname | refus TLS, aucune connexion applicative |
| W3 | Certificat non approuvé | refus TLS |
| W4 | TLS interrompu en cours de session | déconnexion, état hors ligne |
| W5 | Hôte distant en `ws://` | refus de configuration |
| W6 | Mauvaise identité Noise | handshake refusé |
| W7 | Reconnexion après coupure TLS | nouvelle connexion et nouveau handshake |
| W8 | FastAPI depuis l'interface publique | inaccessible |
| W9 | Révocation puis reconnexion | session refusée |
| W10 | Redémarrage connecteur/relais | reprise sans doublon |
