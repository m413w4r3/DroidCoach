# R3B-ANDROID-01 — Fermeture A3/A4 sur le vrai Keystore

| Clé | Valeur |
|---|---|
| TASK_ID / statut | `R3B-ANDROID-01 / INTEGRATED` (2026-10-28) |
| Vague | B |
| BASE_SHA | SHA d'intégration de `R3B-CHAT-WIRE-01` |
| Profil obligatoire | `codex:gpt-6-luna`, effort `xhigh`, session vérifiée par le parent |
| Worktree | worktree Paseo dédié |
| Dépendances | `R3B-CHAT-WIRE-01` ACCEPTED et intégré |

## Objectif observable

Fermer les gates A3 (`NOT_RUN`) et A4 (`PARTIAL`) de R3-A+ avec le **véritable**
`KeystoreRelayCredentialStore` et le parcours intégré de l'application, sur un
APK R3-B reconstruit depuis le SHA intégré, sans `pm clear`, sans
désinstallation et sans perte de données.

## Scope d'écriture exclusif

- `android/app/src/androidTest/java/dev/atlas/coach/sync/relay/R3bA3*.kt`
- `android/app/src/androidTest/java/dev/atlas/coach/sync/relay/R3bA4*.kt`
- `android/app/src/androidTest/java/dev/atlas/coach/app/R3bA4RevocationUi*.kt`
- `android/app/src/androidTest/java/dev/atlas/coach/sync/relay/R3bRelayChatInstrumentedTest.kt`
- `android/app/src/androidTest/java/dev/atlas/coach/sync/relay/R3bRelayTestSupport.kt`
- `docs/v6-3/r3b/preuves/android-01*.txt`

Si un hook de production est indispensable (par exemple marqueur de phase), il
doit être proposé au parent **avant** modification ; le parent l'autorise et
l'intègre lui-même si nécessaire. L'agent ne modifie pas `src/main/**` sans
accord explicite.

Interdit : `pm clear`, `uninstall`, factory reset, modification du launcher,
permissions non consenties, écritures wger, données personnelles réelles.
Aucun ADB : l'agent écrit les tests, le parent exécute l'ADB et fournit les
preuves.

## A3 — Identité persistante

Parcours de test (phases orchestrées par le parent) :

1. Le parent crée un appareil synthétique dans le registre PC et dépose son
   jeton dans un fichier privé `0600` du répertoire `filesDir` de l'app
   (jamais dans un argument de commande). L'URL, le `serverId`, la clé publique
   X25519 et le `deviceId` sont synthétiques.
2. Phase A3-1 : le test appelle le vrai
   `RelaySettingsController.saveConfiguration(...)`, vérifie
   `credentialsConfigured=true`, que le jeton est chiffré dans
   `KeystoreRelayCredentialStore`, que le prefs ne contient pas le jeton en
   clair, puis connecte et attend `RELAY_CONNECTED`.
3. Le test écrit un marqueur public de phase (deviceId, empreinte de la clé
   publique, SHA-256 du jeton attendu, horodatage) sans jamais écrire le jeton.
4. Le parent force-stop l'app, relance la phase A3-2 : le test vérifie
   `deviceId`, `serverPublicKey` et le déchiffrement du jeton (hash attendu),
   puis connecte sans ressaisie.
5. Le parent redémarre Android, relance la phase A3-3 : mêmes vérifications,
   connexion sans ressaisie.
6. Le test vérifie que les données ATLAS préexistantes (plan, habitudes,
   mémoire, consentements, calendrier) sont inchangées (hashes fournis par le
   parent ou compteurs).

Un faux `RelayCredentialsProvider` ne valide pas A3. Le test doit utiliser le
vrai store Keystore et le vrai contrôleur.

## A4 — Révocation complète

1. Session authentifiée active (`RELAY_CONNECTED`).
2. Le parent révoque l'appareil côté PC (`revoke-device`).
3. Le test observe la réception `DEVICE_REVOKED`, la fermeture WebSocket
   `4001` (listener), et l'état Android `REVOKED`.
4. Aucune reconnexion authentifiée n'est tentée après révocation.
5. Le test exécute l'action UI explicite « Supprimer les identifiants de cet
   appareil » puis confirme la boîte de dialogue.
6. Vérifie `credentialsConfigured=false`, absence de la clé de jeton dans les
   prefs, `deviceId`/clé effacés, et que le Keystore ne déchiffre plus de jeton
   utilisé par l'app.
7. Relance : état `PAIRING_REQUIRED`, aucune reconnexion implicite.
8. Nouvel appairage explicite avec un second appareil synthétique : succès.
9. Les autres données ATLAS et comptes restent intacts.

## Contexte d'exécution fourni par le parent

- Appareils synthétiques déjà appairés dans le registre PC :
  - `pixel-r3b` : deviceId `9cbe6da6-07d0-41a3-9b47-938b79b3db43`
  - `pixel-r3b-2` : deviceId `f2167261-37d4-4960-bae4-79ec0b15fb08`
  - capacités : `sync.status,coach.status,plan.today,plan.revision,sport.plan,coach.chat`
- Jetons : fichiers 0600 hors dépôt
  `~/.local/state/atlas-r3b/device-pixel-r3b.json` et
  `device-pixel-r3b-2.json`. Le parent les pousse dans le `filesDir` privé de
  l'app sous les noms `r3b-pixel-device.json` et `r3b-pixel-device-2.json`
  (0600) ; le test les lit puis les supprime après stockage Keystore.
- `relayUrl` = `wss://all.taildc7886.ts.net:8443`
- `serverId` = valeur dérivée de l'identité PC, fournie en argument
  d'instrumentation (non secret)
- clé publique X25519 du PC fournie en argument d'instrumentation (publique)
- Le test ne doit jamais écrire le jeton dans un argument, un log, une preuve
  ou un fichier non privé ; il peut comparer son SHA-256.
- Phases A3 : le parent exécute la phase 1, `am force-stop`, la phase 2,
  `adb reboot`, puis la phase 2 à nouveau.
- A4 : le parent attend `active_sessions >= 1` sur
  `http://127.0.0.1:4401/metrics`, puis exécute
  `scripts/revoke-device.sh ... 9cbe6da6-...` pendant que le test attend
  `DEVICE_REVOKED`/`4001`/`REVOKED`. Le réappairage utilise le second fichier
  de jeton.
- L'agent ne lance aucun ADB ; il fournit les classes et les commandes exactes.

## Test instrumenté de conversation relayée (en plus de A3/A4)

Ajouter une classe `R3bRelayChatInstrumentedTest` qui prouve la conversation
Android réelle via le Funnel, sans backend HTTP direct :

1. Effacer tout jeton backend direct (`clearSyncToken`, `saveSyncConfiguration("", null)`).
2. Lire le jeton synthétique `filesDir/r3b-pixel-device.json` (0600), sans
   l'afficher, puis `relaySettings.saveConfiguration(relayUrl, serverId, clé
   publique, deviceId, token)` et `selectMode(RELAY)`/`connect()`.
3. Attendre `RELAY_CONNECTED` (≤ 25 s) et vérifier
   `syncTokenConfigured == false` (aucun fallback direct).
4. `enableAgent()`, `setCapability(CAP_AGENT, true)`, `setCapability(CAP_COACH, true)`,
   mode `PROPOSALS_ONLY`, actions autorisées limitées, catégories partagées
   désactivées, fenêtre de focus valide.
5. Ouvrir COACH, saisir un message synthétique, cliquer Envoyer.
6. Attendre ≤ 75 s une réponse ASSISTANT et `AgentStatus.CONNECTED` ; vérifier
   la provenance « OpenClaw via PC » et la présence du message utilisateur.
7. Vérifier qu'aucune proposition n'est exécutée sans confirmation (état
   `proposed`/`pending_confirmation`).
8. Vérifier que le message et le brouillon survivent à une rotation, et que
   l'état relayé est visible.

Arguments d'instrumentation non secrets : `relay_url`, `server_id`,
`server_public_key`, `device_token_file` (nom du fichier, pas le jeton).
Le jeton n'apparaît jamais dans un argument, un log ou une preuve.

## Oracle

L'agent produit les classes instrumentées et documente les commandes exactes.
Le parent exécute :

```sh
adb -s $PIXEL_SERIAL shell am instrument -w \
  -e class dev.atlas.coach.sync.relay.R3bA3PairingInstrumentedTest \
  dev.atlas.coach.test/androidx.test.runner.AndroidJUnitRunner
# force-stop, puis phase A3-2, reboot, phase A3-3
adb -s $PIXEL_SERIAL shell am instrument -w \
  -e class dev.atlas.coach.sync.relay.R3bA4RevocationInstrumentedTest \
  dev.atlas.coach.test/androidx.test.runner.AndroidJUnitRunner
```

Chaque phase produit un statut, un SHA APK, un timestamp et une preuve
expurgée. Une phase non exécutée est `NOT_RUN`, jamais `PASS`.

## Livrables

- Classes androidTest, documentation des phases et des arguments, preuves
  `android-01*.txt`.
- Commit local avec SHA ; aucun push, aucun ADB.

## Stop conditions

- Modèle/effort incorrect : `BLOCKED`.
- Besoin de modifier `src/main/**` sans accord : `BLOCKED`.
- Toute perte de données, `pm clear`, désinstallation : `STOP`.
- Révocation non effective, credentials non effacés, réappairage implicite :
  `STOP`.
- Jeton en clair dans un fichier non privé, un argument, un log ou une preuve :
  `STOP`.

## Clôture — 2026-10-28

Statut final : **INTEGRATED**. A3 pairing/persistence et A4
révocation/after-clear/repair PASS sur Pixel API 37. Le délai borné de 200 ms
avant fermeture 4001 (commit `dc8f55e`) laisse le relais router
`DEVICE_REVOKED`. Restauration finale : 5/5 hashes, calendriers 12/91, aucun
artefact. Preuves : `preuves/pixel-instrumentation-final.txt`,
`preuves/pixel-restore-final.txt`.

## Correctifs de revue 1 (7754005)

- A4-4 : `RelayBackendTransport.lastCloseCode` est exposé ; le test A4 attend
  et affirme 4001.
- A4-8 : `clear()` supprime l'alias Keystore `atlas_relay_device_v1` ; le test
  vérifie son absence.
- A3 : les journaux horodatés partiels restent une limite (R-19).
- Le re-test Pixel de l'APK final `1a877422` est bloqué par l'indisponibilité de
  l'ingress public Funnel (R-20) ; les parcours A3/A4 antérieurs restent PASS
  sur l'APK `b55776a9`.

## Correctif de revue 2 (91e7aa6)

- Le verrou de consentement partagé est tenu jusqu'à l'écriture relayée ; les
  opérations en vol sont annulées dès le début de la révocation.
- Test Node : une requête backend en vol est interrompue avant la fermeture
  4001 ; la grâce ne retarde que la socket.
- L'APK final `20672e07` et l'androidTest `5aef1321` sont construits, mais le
  Pixel s'est déconnecté avant l'installation ; l'APK installé reste
  `1a877422`. Aucun PASS Pixel du binaire final n'est revendiqué (R-21).

## Correctif de revue 3 (738ef93)

- `revokeUsageObservation()` mute le consentement d'usage et les capacités
  sous le verrou de dispatch partagé.
- L'annulation des opérations en vol précède l'envoi de l'erreur sur le
  chemin `receive()` `DEVICE_REVOKED`.
- L'APK final `05756094` reste non installé/testé sur le Pixel (R-20/R-21).

## Revue 4 (4be991d)

Revue 4 `APPROUVE_AVEC_RESERVES` : aucun P0/P1. Les écarts documentaires
APK/SHA ont été corrigés ; la réserve de couverture des tests de concurrence
est en R-22, et le binaire final reste non installé/testé sur Pixel
(R-20/R-21).
