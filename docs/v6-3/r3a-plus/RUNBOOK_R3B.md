# ATLAS V6.3 — R3-A+ — Runbook R3-B

Statut : **DRAFT** — à finaliser après la recette WSS R3-A+.

Ce runbook décrit les étapes à exécuter après autorisation d'hébergement. Il ne
constitue pas une autorisation et aucune étape n'est exécutée pendant R3-A+.

## 1. Préconditions

- Revue R3-A+ `GO_PREDEPLOYMENT_WITH_LIMITATIONS`, aucun P0/P1.
- Hébergement approuvé, domaine et certificat publics disponibles.
- Autorisation explicite du propriétaire pour le Pixel, les données mobiles et
  la révocation.
- Sauvegarde app-only récente et SHA de l'APK final.

## 2. Relais

1. Construire l'image relais depuis le commit upstream épinglé.
2. Déployer derrière TLS/WSS avec upgrade WebSocket.
3. Vérifier le routage v2 et le mode `protected`.
4. Vérifier qu'aucun port applicatif clair n'est public.
5. Vérifier que FastAPI, le connecteur, les métriques et ADB restent privés.

## 3. Connecteur PC

1. Installer depuis le paquet reproductible R3-A+.
2. Restaurer/configurer l'identité existante ou créer une nouvelle identité.
3. Vérifier l'empreinte hors bande.
4. Configurer `wss://`, `serverId`, FastAPI loopback et le token de service.
5. Démarrer sous supervision utilisateur ; vérifier redémarrage et logs bornés.
6. Appairer le Pixel avec un token remis une seule fois, sans stdout.

## 4. Pixel

1. Vérifier Android 17/API 37, APK et signature.
2. Sauvegarder l'app avant mise à jour.
3. Installer l'APK release/debug autorisé et vérifier la migration.
4. Saisir le relais WSS, `serverId`, clé publique et identifiants.
5. Vérifier `backend.health` puis une révision `DayPlan`.
6. Couper Wi-Fi/données, vérifier l'état hors ligne et le plan local.
7. Rétablir le réseau, vérifier la reconnexion idempotente.
8. Révoquer l'appareil, vérifier la fermeture et le refus des nouvelles sessions.
9. Vérifier l'absence de doublon, de secret en clair et d'`adb reverse`.

## 5. Recette LTE/5G

Exécuter la section 16 de `docs/v6-3/r3a/DEPLOIEMENT_DISTANT.md` avec accord
ciblé. Ne pas conclure à une disponibilité H24, à une réception instantanée en
Doze ou à un `GO` V6.3 sans campagne dédiée.

## 6. Retour arrière

1. Arrêter le connecteur supervisé et le relais de test.
2. Révoquer l'appareil et supprimer les identifiants Android.
3. Restaurer la configuration PC précédente.
4. Réinstaller l'APK précédent et restaurer les données app-only si nécessaire.
5. Vérifier FastAPI/OpenClaw/Bridge inchangés et consigner les SHA.
