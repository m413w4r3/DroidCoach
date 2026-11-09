# V7-11B — Plan de changement live corrigé

**Statut : dossier de préparation, non autorisé. Aucune opération live n'a été exécutée.**
**Base d'audit :** [V7-11_A6_CUTOVER_GATES.md](evidence/V7-11_A6_CUTOVER_GATES.md), [V7-11_LIVE_INVENTORY.md](evidence/V7-11_LIVE_INVENTORY.md), [V7-11_RELAY_ADMIN_RECON.md](evidence/V7-11_RELAY_ADMIN_RECON.md).
**Règle :** chaque bloc ci-dessous exige une autorisation ciblée distincte. Une autorisation de backup ne vaut pas autorisation de migration, d'installation ou de révocation.

## État runtime constaté (2026-11-08, lecture seule)

| Composant | État réel |
|---|---|
| Core | Conteneur `atlas-core-atlas-core-1`, image du 2026-11-04, **sans `v4_auth.py`/`v4_assertion.py`** (pré-V7-10), réseau host, 1 uvicorn sur `127.0.0.1:8100`, volume `atlas-core-data:/data`. |
| Base Core | `user_version=3`, `integrity_check=ok`, 0 violation FK, 3 propositions (`APPLIED` 1, `PENDING_ANDROID` 1, `REJECTED` 1), 2 ACK. |
| Relay | Conteneur `atlas-v7-relay`, image `atlas-paseo-relay:r3a` du 2026-10-26, bridge, `127.0.0.1:4402→4000`, aucun label Compose, aucun volume, aucun healthcheck, aucune unité systemd. Supervisé uniquement par `restart=unless-stopped`. Origine/commande de création inconnues ; état probablement volatil. `scripts/r3a/start-relay.sh` n'est pas idempotent (`docker rm -f`) et ne doit pas être utilisé sur le live. |
| Proxy 4400 / connecteur 4401 | **Absents** ; le Funnel `all.taildc7886.ts.net:8443` pointe vers 4400 sans listener. |
| Gateway | `openclaw-gateway.service` actif sur `127.0.0.1:18789`. |
| Pixel | Présent sur ADB USB (`<PIXEL_SERIAL>`) ; nœud Tailscale offline depuis 33 jours ; aucune commande ADB de mutation exécutée en V7-11A. |

**Conséquence :** le code V7-10/V7-11 n'est pas déployé sur le Core live ; le chemin Relay/connecteur n'est pas opérationnel. Un déploiement de code et un rétablissement du connecteur/proxy sont des prérequis avant toute migration.

## Bloc A — Inventaire et sauvegardes (réversible, capture serveur interruptive)

### A1. Inventaire
1. Confirmer le change ID, l'opérateur unique, le Pixel/serial autorisé, la fenêtre et les voies d'arrêt.
2. Vérifier `git rev-parse HEAD` du checkout source, l'image Core réellement déployée, le chemin DB, l'UID/GID, le volume, les secrets montés (métadonnées seulement), le Relay ID/session et le binding `pixel-v7`.
3. Vérifier le Pixel : `adb devices -l`, `getprop ro.build.version.sdk`, `run-as dev.atlas.coach id`, `pm path`, rôle HOME, versionCode/versionName/certificat de l'APK installé, hash du backup app-only.

### A2. Sauvegardes
1. Sauvegarder et vérifier l'état applicatif `dev.atlas.coach` avec l'outil V7-07 validé (`preflight`, `backup`, `verify`), sur le Pixel exact.
2. Arrêter Core et le connecteur/superviseur réellement identifié, vérifier l'absence de WAL/SHM, capturer une copie SQLite via l'API Backup, vérifier `integrity_check`, `foreign_key_check`, `user_version`, taille et SHA-256.
3. Sauvegarder la configuration privée Core/Relay, la map, le keyring, le secret d'assertion, les credentials et les APK précédent/candidat, en `0700/0600`, hors dépôt.
4. Tester la restauration sur copie jetable et sur AVD API 37 ; ne jamais restaurer l'archive Pixel sur un autre serial.
5. **Stop** : hash/intégrité/DB/chemin/UID/service non identifiés, WAL/SHM résiduel, `run-as` refusé, signature incohérente.

## Bloc B — Staging Core/Relay (réversible ; promotion interruptive)

1. **Déployer le code V7-11** : construire l'image Core depuis le SHA final, vérifier la présence de `v4_auth.py`/`v4_assertion.py`, l'entrypoint et les secrets, puis remplacer le conteneur live uniquement dans la fenêtre autorisée. Le Compose actuel ne fournit pas le keyring V4 ; ajouter le montage privé nécessaire.
2. Sur une copie vérifiée : `preview-migration`, migration v3→v5, import LEGACY explicite, création des principaux Android/Relay/Tool, credentials distincts, grants minimaux, binding Relay, backfill dry-run puis `--apply --backup`, expiration dry-run. Aucune promotion de copie sans contrôles d'intégrité, FK, version, comptes et ACK.
3. **Rétablir et qualifier le Relay/connecteur** : identifier le propriétaire du conteneur `atlas-v7-relay`, son fichier de configuration, son identité de session, son volume/état et sa commande de recréation. Aucune commande de remplacement sûre n'est démontrée à ce jour ; ne pas utiliser `start-relay.sh` sur le live. Démarrer le proxy 14400 et le connecteur 14401 **sur des ports isolés** pour la qualification, puis seulement ensuite envisager les ports live. Vérifier le trajet `Android → Funnel 8443 → proxy 4400 → Relay 4402 → connecteur → Core 8100`. Le script `run-isolated-relay-tests.sh` doit être lancé avec un port distinct de 4402 (par défaut il vise le Relay live).
4. Passer en `dual` avec principal LEGACY explicite et secret curseur. Vérifier `check-config`, `check-secret`, les modes 0600/0700, les chemins absolus hors dépôt, l'absence de symlink et le fail-closed.
5. Décider et documenter la topologie anti-rejeu : cache partagé durable, ou worker unique + blocage V4 vérifiable ≥240 s après redémarrage.
6. **Stop** : image/code non prouvé, Relay non administrable, map/binding incohérents, assertion absente, anti-rejeu non tranché, DB non vérifiée.

## Bloc C — Android et cutover réversible (Pixel physique, réversible avant révocation)

1. Qualifier l'APK V7-11 final sur AVD API 37 : rebuild, hash, certificat, versionCode, permissions, installation in-place, préservation, cold start, rôle HOME, rollback.
2. Sur le Pixel, après backup vérifié et autorisation nominative : `preflight`, `adb install -r` du candidat, vérifier package/certificat/versionCode, rôle HOME, Keystore, plans, consentements, opt-in V4 (`false` jusqu'au geste explicite).
3. **Bascule d'identité** :
   - **Mode Relay** : conserver l'identité Relay/Noise appariée ; mettre à jour le mapping Relay→principal/credential Core et le binding ; Android ne reçoit pas le bearer Core.
   - **Mode DIRECT** : installer le nouveau bearer DIRECT dans l'app sans supprimer l'ancien tant que le nouveau n'a pas réussi ; prévoir un écran/geste explicite et réversible.
4. Premier refus Core : arrêter les essais distants, conserver l'app, le Keystore et les données, rester local/offline, ne pas désinstaller/`pm clear`/restaurer.
5. **Stop** : signature/package/version incompatibles, donnée manquante, consentement modifié, prompt de réappairage inattendu.

## Bloc D — Qualification sécurité (réversible jusqu'au cutover)

0. Rejouer sur le Pixel, avec un plan/projection Android réel et une proposition synthétique, le parcours A0 APPLY (version incrémentée, ACK `APPLIED`, rejeu idempotent) et REJECT. Le test AVD sur plan `DEMO` est bloqué par la sémantique produit et ne remplace pas cette preuve ; V7-08 est historique.
1. A/A et B/B sur identités synthétiques et sessions Relay authentifiées.
2. A avec credential B refusé, sans lecture/mutation croisée.
3. Credential révoqué refusé après redémarrage ; nouvelle credential acceptée.
4. Rejeu mono/multi-worker et après redémarrage refusé (selon la mitigation retenue).
5. Expiration A0, ACK, `UNCERTAIN`, audit append-only et idempotence vérifiés sur copies.
6. Logs redacted, absence de bearer/secret, fail-closed sur config manquante.
7. Aucune révocation de `pixel-v7` ni du seul credential produit pour fabriquer un test négatif.

## Bloc E — Cutover irréversible (autorisation distincte)

1. Snapshot immédiat et testé.
2. Arrêt contrôlé Core/connecteur, mutation offline d'une copie : `credential revoke` du LEGACY, `credential rotate` du RELAY, keyring overlap.
3. Contrôles `integrity_check`, FK, `user_version`, vérification ancien refusé / nouveau accepté.
4. Promotion atomique selon le mécanisme de déploiement vérifié ; mise à jour map/keyring.
5. Redémarrage contrôlé, test de bout en bout, retrait de l'ancienne clé après preuve du nouveau `key_id`.
6. **Interdiction absolue** : ne jamais remettre `ATLAS_V4_AUTH_MODE=legacy` avec l'ancien `ATLAS_TOKEN` après la révocation. Faire tourner `ATLAS_TOKEN` avant tout rollback legacy ou maintenir le mode `principal` fail-closed.
7. Un rollback après révocation ne réactive jamais l'identité révoquée : restaurer sur copie neuve, réappliquer les tombstones, émettre un credential neuf.

## Décisions ouvertes avant V7-11B

1. **`pixel-v7`** : conserver l'appairage Relay existant si l'identité de session et le binding restent valides ; ne pas révoquer ce binding pour un test. Un binding révoqué ne peut pas être réactivé par `binding add`.
2. **Historique** : conserver les propositions historiques sous LEGACY, avec backfill explicite uniquement si l'attribution est certaine. Ne pas copier les consentements/grants historiques vers le nouveau principal.
3. **Bascule Android** : préciser le geste UI/API exact pour Relay et DIRECT, et l'état local/offline si Core refuse.
4. **Anti-rejeu** : choisir cache partagé durable ou worker unique renforcé, et l'inscrire comme stop condition à chaque redémarrage.
5. **Relay** : établir le propriétaire, le fichier de configuration, le volume/état, le supervisor et la procédure de recréation avant tout arrêt.
6. **Autorisation** : le propriétaire des données/Pixel autorise l'installation et les consentements ; le propriétaire Core/Relay autorise les arrêts/configs ; le responsable sécurité accepte les risques résiduels ; l'orchestrateur n'exécute que les scopes autorisés.
7. **Sauvegarde connecteur incomplète** : `backup-state.mjs`/`state-backup.mjs` sauvegardent `identity.json` et `devices.json` mais **pas `operations.json`**. Une sauvegarde complète de l'état connecteur doit inclure ce fichier et être testée avant toute opération sur l'identité/l'appairage.
8. **Ne pas exécuter `show-identity`, `pair-device` ou `revoke-device`** sur le connecteur live sans autorisation distincte : `show-identity` peut créer une identité si elle manque. Les fichiers d'état existent en `0600`, mais leur appairage effectif n'est pas établi.

## Verdict

`GO_V7_11_LIVE_CHANGE_READINESS` : **NO-GO / dossier incomplet**. Les preuves locales progressent, mais le code V7-11 n'est pas déployé, le Relay/connecteur n'est pas administrable, l'identité/binding réels ne sont pas vérifiés, l'anti-rejeu n'est pas tranché et l'installation Pixel n'est pas autorisée. Aucun bloc B–E ne doit démarrer sans autorisation ciblée.
