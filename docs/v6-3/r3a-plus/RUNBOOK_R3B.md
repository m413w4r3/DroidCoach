# ATLAS V6.3 — R3-A+ — Runbook R3-B

Statut : **DRAFT EXÉCUTABLE APRÈS AUTORISATION D'HÉBERGEMENT**. Aucune étape de
ce runbook n'est exécutée pendant R3-A+. Le statut V6.3 global reste `NO_GO`
jusqu'à la recette R3-B.

## 0. Préconditions et autorisations

- Revue indépendante `R3AP-REVIEW-01` terminée sans P0/P1 ouvert sur le SHA
  livré ; rapport final `GO_PREDEPLOYMENT_WITH_LIMITATIONS` disponible.
- Hébergement, domaine et certificat publics approuvés par le propriétaire.
- Autorisation ciblée pour : installation/mise à jour sur le Pixel, données
  mobiles, révocation d'appareil, sauvegarde et restauration app-only.
- SHA de l'APK, du connecteur et de l'image relais consignés avant de commencer.
- Sauvegarde app-only récente du Pixel et archive restaurable vérifiée.
- Aucun secret dans Git, un argument de commande, un log ou une capture.

## 1. Relais public

1. Construire l'image depuis le commit upstream épinglé et vérifier son SHA.
2. Déployer derrière un terminateur TLS avec upgrade WebSocket.
3. Vérifier `wss://<relais>/health` et le routage v2.
4. Vérifier qu'aucun port applicatif en clair n'est public, notamment 4000.
5. Vérifier que le relais ne voit que `serverId`, `connectionId`, rôle et trames
   chiffrées ; il ne constitue pas une authentification.
6. Vérifier HSTS, TLS 1.2+, certificat valide et renouvellement.
7. Consigner l'URL, le SHA d'image, l'empreinte du certificat et la date.

Critère d'arrêt : certificat invalide, port clair public, ou relais qui accepte
un mode non `protected`.

## 2. Connecteur PC

1. Installer depuis la distribution reproductible R3-A+ (`npm ci` vérifié,
   artefact `@getpaseo/relay` épinglé). Ne pas utiliser un clone Paseo manuel.
2. Créer ou restaurer le répertoire d'état privé (0700) et l'identité X25519
   (0600). Vérifier l'empreinte par un canal hors bande.
3. Configurer :
   ```text
   ATLAS_RELAY_URL=wss://<relais>
   ATLAS_RELAY_SERVER_ID=atlas-pc-<opaque>
   ATLAS_RELAY_STATE_DIR=~/.local/state/atlas-relay
   ATLAS_BACKEND_URL=http://127.0.0.1:8100
   ATLAS_METRICS_PORT=4401
   ```
   Ajouter `ATLAS_SERVICE_TOKEN=<valeur>` dans le fichier d'environnement
   privé `~/.config/atlas/relay.env` (0600), chargé par systemd via
   `EnvironmentFile`. Le connecteur lit cette variable d'environnement ; il
   n'existe pas de variable `ATLAS_SERVICE_TOKEN_FILE` dans le connecteur
   actuel. Le token n'apparaît jamais dans `ExecStart`, les arguments du
   processus ou un log.
4. Démarrer sous l'unité systemd utilisateur fournie. Vérifier :
   - démarrage automatique à l'ouverture de session ;
   - redémarrage après crash ;
   - arrêt propre ;
   - logs bornés et sans secret.
5. Vérifier `show-identity`, le diagnostic réseau et la connexion WSS.
6. Vérifier `/health` FastAPI en loopback et l'inaccessibilité depuis
   l'interface publique.

Critère d'arrêt : identité non persistante, token visible, FastAPI joignable
depuis le relais, ou certificat non vérifié.

## 3. Appairage

1. Sur le PC : `pair-device --label <label> --token-file <fichier 0600>`.
2. Vérifier que stdout ne contient pas le token et que le fichier est 0600.
3. Vérifier que l'appareil n'est autorisé qu'après livraison réussie du secret.
4. Sur le Pixel : saisir l'URL WSS, le `serverId`, la clé publique X25519, le
   `deviceId` et le token une seule fois.
5. Vérifier l'état « Connecté via relais », `backend.health`, puis une révision
   `DayPlan`.
6. Vérifier qu'aucun token n'apparaît dans l'UI, les logs ou les captures.

## 4. Pixel physique

1. Vérifier Android 17/API 37, le SHA de l'APK, la signature et la version.
2. Sauvegarder l'app-only avant mise à jour ; ne pas utiliser `pm clear`.
3. Installer l'APK autorisé et vérifier la migration des données ATLAS
   (plan, habitudes, mémoire, consentements, identité relayée).
4. Vérifier les permissions calendrier et le calendrier synthétique ATLAS.
5. Vérifier la pause d'urgence, le retour au launcher et l'usage hors ligne.

## 5. Réseau réel

1. Désactiver Wi-Fi, activer les données mobiles, vérifier l'absence
   d'`adb reverse` et de proxy USB pour la recette LTE/5G.
2. Vérifier `backend.health` et une révision `DayPlan` via le relais public.
3. Couper les données mobiles : état hors ligne, plan local préservé, aucune
   requête périmée rejouée.
4. Rétablir : reconnexion, nouvelle requête idempotente, aucun doublon.
5. Redémarrer le relais puis le connecteur : reconnexion et identité stable.
6. Révoquer l'appareil : session fermée (`4001`), nouvelles sessions refusées,
   credentials locaux supprimés après révocation explicite.
7. Vérifier les métriques loopback (latence, erreurs, mémoire) et les journaux.
8. Ne pas conclure à une disponibilité H24 sans test de veille/Doze dédié.

## 6. Calendrier et effets

1. Vérifier le calendrier synthétique `ATLAS TEST` et l'absence de doublon.
2. Créer/déplacer/supprimer un événement synthétique via le parcours approuvé.
3. Vérifier la relecture provider et la visibilité Etar.
4. Modifier puis supprimer l'événement dans Etar ; vérifier `DIVERGED` puis
   `MISSING`, sans effet supplémentaire.
5. Refuser `READ_CALENDAR` puis `WRITE_CALENDAR` ; vérifier le fail-closed et
   l'absence d'écriture.
6. Restaurer les permissions et supprimer uniquement les fixtures ATLAS.

## 7. Preuves et clôture

Chaque scénario produit : `PASS / FAIL / PARTIAL / BLOCKED / NOT_RUN`, SHA
source, SHA APK, environnement, commande, horodatage et preuve expurgée.
Ne pas revendiquer : disponibilité H24, LTE/5G qualifié, notifications
instantanées en Doze, ou `GO` V6.3 complet.

## 8. Retour arrière

1. Arrêter le connecteur supervisé et le relais.
2. Révoquer l'appareil et supprimer les identifiants Android.
3. Restaurer la configuration PC précédente depuis une sauvegarde.
4. Réinstaller l'APK précédent et restaurer les données app-only si nécessaire.
5. Supprimer uniquement les fixtures ATLAS (calendrier/événements/credentials).
6. Vérifier que FastAPI, OpenClaw et le Bridge restent locaux et inchangés.
7. Consigner les SHA, journaux et limites.
