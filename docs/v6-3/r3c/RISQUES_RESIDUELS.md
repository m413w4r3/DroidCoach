# ATLAS V6.3 — R3-C — Risques résiduels

## R-15 / R-17 — Déni de service du relais public

Le relais Paseo reste exposé via le Funnel public sur `8443`. Les limites de
connexions, de taille de frame, de handshake et de débit du proxy/connecteur
sont conservées, mais une attaque volumétrique reste possible au niveau de
l'ingress Tailscale ou du relais. **Aucune réécriture générale du relais n'a
été tentée.** Risque ouvert, à traiter par supervision et limitation
d'ingress si le service devient permanent.

## R-18 — Dépendance Funnel / plan de contrôle / PC

Le chemin public a été rétabli et qualifié le 2026-10-28 (TLS 1.3, health,
ready, metrics 404, WSS 101, E2E 7/7). Il dépend de :

- la connectivité du PC et de `tailscaled` au plan de contrôle Tailscale ;
- du Funnel existant `all.taildc7886.ts.net:8443 → 127.0.0.1:4400` ;
- des services locaux 4400/4402/8100/4401 ;
- du PC allumé.

Le PC éteint rend le service indisponible : **aucune haute disponibilité**.
Une panne du plan de contrôle peut reproduire l'échec TLS observé en R3-B.
Aucune configuration Tailscale n'a été modifiée.

## R-19 — Journaux et preuves

Les journaux des services sont bornés et les preuves sont expurgées. Les
sorties instrumentées horodatées complètes de la vague Pixel restent
`BLOCKED_DEVICE`. Ne pas considérer les anciennes preuves R3-B comme preuves
R3-C.

## R-20 — Ingress public et preuve « réseau extérieur »

La qualification publique R3-C est faite depuis le PC avec une résolution DNS
publique indépendante (`1.1.1.1`) et l'IP publique du Funnel, sans MagicDNS ni
`100.119.62.63`. Elle traverse bien l'Internet public. Elle ne remplace
toutefois pas une preuve depuis un **réseau mobile Pixel sans VPN Tailscale**,
qui reste à exécuter. Ne pas annoncer `GO_REMOTE` sur la seule preuve PC.

## R-21 — APK final non installé sur Pixel

Le binaire R3-C (`8332afd9…` au moment de la rédaction) n'a pas été installé.
Aucun PASS Pixel A3/A4/chat/réseau ne peut être revendiqué pour ce binaire.
Le Pixel `45301FDAS001QT` est hors USB ; Tailscale le voyait `offline` depuis
23 jours. Toute installation exige une sauvegarde app-only vérifiée et un
mandat ciblé.

## R-22 — Courses consentement/révocation

Réserve R3-B fermée par des tests déterministes Android et Node :
- chat en attente + révocation → `POLICY_DENIED`, aucun payload ;
- écriture WebSocket contrôlée + révocation → ordre du verrou vérifié ;
- requête en vol + `DEVICE_REVOKED` → abort, erreur avant `4001`, aucune
  réponse tardive ;
- chemins polling et réception directe couverts.

Limite : la reproduction sur appareil réel pendant une coupure réseau reste
`PARTIAL` tant qu'elle n'est pas rejouée sur Pixel.

## R-23 — Bac à sable des sous-agents

Les trois enfants Luna XHigh ont été bloqués en écriture par le bac à sable
Codex (`.git/worktrees`, `~/.gradle`, binaires `node_modules`). Les patches
ont été produits dans les worktrees, puis les oracles et l'intégration ont été
exécutés par le parent. Ce mode de fonctionnement est traçable mais réduit
l'autonomie des enfants ; il doit être corrigé pour une campagne future
(worktrees avec métadonnées Git accessibles, caches Gradle/npm partagés en
lecture seule ou préchauffés).

## R-24 — CI distante non exécutée

La CI de la branche R3-C n'a pas été lancée : aucun push n'a été autorisé.
Les gates locales sont PASS, mais le workflow distant `relay-node` reste
`NOT_RUN`. Ne pas déclarer la CI verte avant un run terminé avec `success`.

## R-25 — OpenClaw réel non rejoué

Le chat R3-C a été qualifié via l'adaptateur `mock`. L'OpenClaw personnel et
le Bridge n'ont pas été modifiés. Une réponse OpenClaw réelle via le Funnel
reste à rejouer si l'accès est autorisé ; les preuves R3-B historiques ne
valent pas preuve R3-C.

## R-26 — Supervision persistante

Les unités systemd restent des modèles non installés. `linger` était déjà
activé et n'a pas été modifié. Aucune promesse H24, Doze ou notification
instantanée. Un redémarrage complet réel du PC n'a pas été exécuté.

## R-27 — wger

`WGER-01` reste `BLOCKED_AUTH` : aucune lecture réelle n'a été autorisée.
Aucune écriture wger n'a été tentée.

## R-28 — Périmètre de sauvegarde

La sauvegarde app-only est vérifiée et conservée hors dépôt, mais les cinq
hashes R3-B sont des témoins, pas une preuve d'intégrité exhaustive. Les
données Keystore ne sont pas dans l'archive. Une restauration complète reste
exceptionnelle.

## R-29 — Suite Android 14

La cible de clôture est **Android 17 / API 37 Pixel physique**. La suite
instrumentée Android 14 n'est pas lancée et ne doit pas l'être comme substitut.
Un émulateur API 37 ne vaut pas preuve Pixel.

## R-30 — Latence et performance

Le chat public a été mesuré avec l'adaptateur mock ; aucune promesse de latence
OpenClaw réelle n'est faite. Les tests de charge du relais public ne sont pas
au périmètre R3-C.
