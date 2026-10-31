# V7 — R3B UI chat réel depuis le Pixel

Date : 2026-10-31  
Appareil : Pixel 9 Pro XL (USB, `device`), Android 17 / API 37, `arm64-v8a`.  
Portée : D (Pixel), avec autorisation explicite du propriétaire.  
Aucune donnée personnelle, token, message ou chemin opaque n’est reproduit.

## Artefacts

- APK app installé : SHA-256
  `0d0ce524f1166690f5399ea7197ab4eb3b39bfa93604d3feb98613d75bc57ca4`,
  signataire debug `da301466…eeee`, package `dev.atlas.coach` 0.1.0.
- APK de test d’intégration c6d93f5 : SHA-256
  `d867ad6e882c72dba92a3f087087c8cbb3d2b5bddb9ce96f9041548b15d17e35`,
  package `dev.atlas.coach.test`, même signataire.
- Backup préalable conservé (`388f2250…`, archive `0600`, répertoire `0700`).

## Résultat

- Test : `dev.atlas.coach.sync.relay.R3bRelayChatInstrumentedTest`.
- Transport : **RELAY** public `wss://all.taildc7886.ts.net:8443` ; aucun fallback
  HTTP direct ; token fourni uniquement par fichier privé dans `filesDir` (`0600`,
  supprimé par le test).
- Commande d’instrumentation exécutée une fois après correction d’une clé publique
  tronquée dans la première tentative ; aucun token en argument.
- Résultat runner : **`OK (1 test)`**, 1 test, 0 échec, 0 ignoré ; code processus `0`.
- Le test a exercé l’UI Compose réelle (conversation, envoi, attente de la réponse,
  rotation), la connexion relayée authentifiée et la réponse du Core/OpenClaw.
- Le token temporaire a été supprimé ; sa vérification finale indique absent.
- Métriques connecteur après l’essai : `relay_connected=true`, 2 connexions,
  2 sessions ouvertes, 7 requêtes cumulées, 0 résultat incertain. Compteur d’erreurs
  cumulé à 2, attribué aux cas négatifs du harnais public antérieur ; aucune erreur
  n’a été observée dans le résultat du test Pixel.

## Limites et état laissé

- Ce test utilise la route **V2** de chat relayé ; il ne prouve pas le chat V4 ni
  l’inbox/ACK V4.
- La première tentative a échoué avant connexion (`production relay configuration was
  rejected`) à cause d’une clé publique tronquée ; elle ne compte pas comme preuve.
- La configuration relay/agent d’essai est restée active après le test (le test ne la
  restaure pas) ; toute restauration nécessite un accord distinct. Le backup app-only
  est intact.
- Aucune action Core/Gateway/relais n’a été effectuée par cet essai.
