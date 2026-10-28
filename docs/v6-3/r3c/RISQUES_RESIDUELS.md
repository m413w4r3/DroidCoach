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

## R-20 — Preuve « réseau extérieur »

La qualification publique R3-C a été faite depuis le PC avec une résolution
DNS publique indépendante (`1.1.1.1`) et l'IP publique du Funnel. Le Pixel a
ensuite utilisé l'URL publique `wss://all.taildc7886.ts.net:8443` sur Wi-Fi
puis sur réseau cellulaire (NET-09), sans VPN actif : Tailscale est installé
mais aucun transport VPN n'est actif et aucun VPN permanent n'est configuré.
La preuve Pixel existe donc sur Wi-Fi domestique et cellulaire ; il n'y a pas
de test depuis un réseau tiers totalement indépendant (autre opérateur ou
réseau public), qui reste une limite mineure.

## R-21 — APK final et A4

Le binaire R3-C `365bd84a…` est installé sur l'utilisateur 0 du Pixel et A3
est PASS sur ce binaire. A4-5 à A4-10 sont PASS, mais **A4-4 échoue** : le
relais ferme avec 1012 au lieu de 4001. L'utilisateur 0 n'avait plus l'app ni
ses données au pré-vol ; les profils 12/13 n'ont pas été modifiés. Aucune
donnée R3-B de l'utilisateur 0 n'a été restaurée.

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

## R-24 — CI distante

La CI a été poussée après autorisation. Le premier run `40730173153` a échoué
sur `stale-client-timeout.test.ts` (relais réel absent du runner). Après
séparation des tests, le run `40733692711` sur `a72ba9e` est **success**,
puis le run `40744237164` sur `a6a5602` est **success** (`verify` et
`relay-node`, compilation androidTest incluse). Un dernier run est requis
après le correctif fail-closed du contrôle d'alias.

## R-25 — OpenClaw réel

Le chat R3-C a été qualifié via l'adaptateur `mock`, via l'OpenClaw réel du PC
(message synthétique) et via l'UI Android sur le Pixel : `agent_status=connected`,
provenance « OpenClaw via PC », aucune action exécutée sans confirmation.
L'OpenClaw personnel et le Bridge n'ont pas été modifiés, mais une conversation
synthétique a été créée côté gateway.

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

Le chat public a été mesuré avec l'adaptateur mock et OpenClaw réel ; aucune
promesse de latence OpenClaw réelle n'est faite. Les tests de charge du relais
public ne sont pas au périmètre R3-C.

## R-31 — Code de fermeture 4001 non propagé

Le relais épinglé ferme le socket client avec **1012 « Server disconnected »**
après `DEVICE_REVOKED`, au lieu de propager le `4001` émis par le connecteur.
Trois probes (public, proxy local, relais direct) le confirment. La révocation
reste effective, mais la gate A4-4 échoue. Décision requise : corriger le
relais amont ou accepter explicitement 1012 comme fermeture de transport.
Aucune modification du relais n'a été tentée.

## R-32 — Effacement A4 validé hors clic UI

L'échec A4-4 interrompt le test UI avant le clic « Supprimer les identifiants ».
L'effacement, l'absence d'alias Keystore et `PAIRING_REQUIRED` ont été validés
par un test R3-C dédié appelant le même chemin de production
`clearCredentials()`. Le clic UI et la boîte de confirmation ne sont pas
rejoués après l'échec A4-4 ; ce point reste `PARTIAL`.
