# ATLAS V7-06C — recette de transport public (préparation)

Date : 2026-11-01
Statut : **préparation uniquement — aucun test distant exécuté, aucun service
public modifié.**
Référence : `V7-06B` démontre un fil rouge local/QA via `adb reverse` et une
stack isolée. Un succès local ne vaut **pas** `GO_REMOTE_V4_PUBLIC` ni
`GO_PRODUCT_REMOTE`.

## 1. Objectif

Qualifier le même fil rouge Android QA → Relay → connecteur PC → Core →
OpenClaw réel → proposition A0 → application Android → ACK Core **sans**
`adb reverse`, sans proxy local, sans HTTP Core/Gateway exposé, depuis un réseau
extérieur réellement distinct.

## 2. Prérequis bloquants

1. **Endpoint public WSS autorisé** : un endpoint `wss://…` dédié au relay,
   avec certificat TLS valide et vérifié côté Android. Aucun `ws://` public.
2. **Identité serveur épinglée** : `server_id` et clé publique X25519 du
   connecteur épinglés dans l’appairage QA ; toute rotation exige un nouvel
   appairage et une preuve.
3. **Noise NK protégé** : handshake protégé négocié, aucune session en clair,
   aucun fallback non protégé ; la version du protocole reste `v2`.
4. **Connecteur authentifié** : session relay authentifiée, device QA déclaré,
   capabilities V4 exactes, jeton de device propre à l’essai.
5. **Aucun HTTP Core/Gateway public** : le Core et le Gateway restent sur
   loopback ou un réseau privé non exposé ; seul le relay/proxy filtrant peut
   être joignable. Le Funnel ne doit jamais pointer vers 8100/18789.
6. **Aucun ADB/proxy local dans le fil rouge distant** : pas d’`adb reverse`,
   pas de proxy HTTP local, pas de tunnel SSH dans le scénario mesuré. Le réseau
   extérieur doit être distinct de l’hôte Core (par exemple données mobiles).
7. **Sauvegarde et autorisation ciblée** : toute modification du Funnel, du
   relay public, du connecteur live ou des secrets exige une sauvegarde, un
   rollback et un accord explicite du propriétaire. V7-06B ne les applique pas.
8. **Révocation/reconnexion** : révocation du device QA et fermeture de session
   observables ; reconnexion propre sans réutilisation d’un jeton révoqué.

## 3. Environment attendu

| Composant | Exigence |
|---|---|
| Core | Réel, SQLite isolée ou base de campagne, capabilities V4, adapter OpenClaw réel ; jamais exposé publiquement |
| Gateway OpenClaw | Instance isolée ou live autorisée, agent à outils strictement limités, aucun `exec`/process/shell/FS/Code Mode |
| Connecteur PC V4 | État et identité dédiés à la campagne, jeton Core Android distinct, V4 activé |
| Relay | Relais Paseo épinglé, endpoint public WSS, TLS + Noise NK |
| Android QA | APK QA séparé (`dev.atlas.coach.qa*`), pairing neuf, réseau extérieur réel |
| Témoin | Horodatage, réseau, hash d’APK, `server_id`, empreinte de clé, IDs de proposition/ACK |

## 4. Séquence de recette

1. Pré-vol lecture seule : versions, santé locale, endpoint public, certificat,
   absence d’écoute publique Core/Gateway, espace disque, sauvegarde.
2. Sauvegarde du connecteur (identité, registre, journal), du Funnel et des
   secrets concernés ; hashes privés.
3. Démarrer/qualifier le relay public et le connecteur avec une identité neuve ;
   vérifier la session authentifiée et les capabilities V4.
4. Appairer le paquet QA avec l’endpoint public et la clé épinglée ; vérifier le
   handshake protégé et l’absence de fallback.
5. Depuis le réseau extérieur : consentements, plan v2, chat V4 réel, pull de la
   proposition A0, validation utilisateur, application v2→v3, ACK APPLIED.
6. Vérifier côté Core : une proposition, `base_version=2`, `NONE_AT_CORE`,
   ACK APPLIED durable, plan v3 cohérent, aucun double dépôt.
7. Rejouer le même chat : réponse en cache, aucun nouvel appel modèle, aucune
   seconde proposition.
8. Scénario reject séparé : plan inchangé, ACK REJECTED durable, Core ne
   prétend pas une application.
9. Révoquer le device QA et les consentements d’essai ; vérifier la propagation
   côté Core/connecteur ; tenter une reconnexion avec le jeton révoqué et
   constater le refus.
10. Arrêter uniquement les processus/conteneurs créés pour la campagne ;
    restaurer le Funnel/les secrets si modifiés ; conserver les preuves
    expurgées.

## 5. Oracle et verdicts

- `GO_REMOTE_V4_PUBLIC` exige : WSS public réel, TLS vérifié, Noise NK,
  identité épinglée, aucune exposition Core/Gateway, réseau extérieur distinct,
  fil rouge complet et révocation observée.
- `GO_PRODUCT_REMOTE` exige en plus l’**application produit installée**
  (`dev.atlas.coach`), avec sauvegarde, autorisation ciblée et preuve sur
  l’app produit — pas seulement le paquet QA.
- Un test QA local ou `adb reverse` ne satisfait aucun des deux.
- Les preuves doivent distinguer `PASS`, `FAIL`, `BLOCKED`, `NOT_RUN` et citer
  le SHA, l’APK, le réseau et l’endpoint exacts.

## 6. Risques et rollback

| Risque | Mesure |
|---|---|
| Exposition involontaire du Core/Gateway | Vérifier les listeners et le Funnel avant/après ; refuser tout bind public |
| Certificat/TLS invalide | Refuser le scénario, ne pas désactiver la vérification |
| Fallback non protégé | Refuser la session si `securityMode != protected` |
| Réutilisation d’identité/consentement | Identité et base de campagne neuves, révocation en fin de recette |
| Perte de session en cours de route | Reprise idempotente, aucun nouvel effet ; preuve du rejeu |
| Modification live irréversible | Sauvegarde + rollback documentés ; accord ciblé obligatoire |

## 7. État actuel

- `GO_REMOTE_V4_PUBLIC` : **NO GO — NOT_RUN**.
- `GO_PRODUCT_REMOTE` : **NO GO — NOT_RUN**.
- Aucun changement Funnel, DNS, Tailscale, service live ou secret n’a été
  effectué pour V7-06B.
- Le Pixel physique n’a pas été utilisé par V7-06B sans autorisation ciblée.
