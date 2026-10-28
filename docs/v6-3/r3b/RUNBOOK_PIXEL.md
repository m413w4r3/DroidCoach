# ATLAS V6.3 — R3-B — Runbook Pixel

Cible de qualification : **Pixel 9 Pro XL physique**, Android 17 / API 37,
numéro de série `45301FDAS001QT`. Aucun émulateur ne vaut preuve Pixel.

## 1. État de référence

| Élément | Valeur au pré-vol |
|---|---|
| Modèle | Pixel 9 Pro XL (`komodo`) |
| Android | 17 / API 37 |
| Build | `google/komodo/komodo:17/CP3A.260905.009/16091614:user/release-keys` |
| Paquet | `dev.atlas.coach`, versionCode 1, versionName 0.1.0, DEBUGGABLE |
| APK installé de référence | SHA-256 `66645143b0e6d861b90bbad4acfcc8fe85cae5c939e1d394e3e81c9ecad518b5` |
| Signature de référence | debug, certificat SHA-256 `da3014662eb65e50a87ef364a2e583c331398795b222e3b37b1a16553a00eeee` |
| Sauvegarde app-only | `~/atlas-backups/r3b/pixel-app-backup-pre-r3b.tar` (0600), SHA-256 `fbca987ad521f99bdc4c626b35367a1a9de674da12fdfc4fcf42c5417696aabd` |
| APK de rollback extrait | `~/atlas-backups/r3b/pixel-current-r3a-plus.apk` (0600), même SHA-256 `66645143…` |

Hashes ATLAS de référence :

```text
shared_prefs/atlas_local_state.xml              6e72d8b2d8462d72310db02a505f5051b16e097e9aa53ecfa56e1d1f7724b9a5
shared_prefs/atlas_sync_configuration.xml       745038187c38bbc4907268bf002d8d7fd24d357fd46613f1d643005506508742
shared_prefs/atlas_agent_revocation_barrier.xml edcfba43fe11ddccea1273e8e0749b31e0fd9b36db257dd3b8a8ef348fece6a6
files/atlas_agent_inbox_v2.json                 c5a1f5ed484e57d8470fae0c02e420591230ac451a17d625198e26a6d9bf7c5d
files/plans/day-plan-2026-10-26.json            fc003a7f8734de958a8aa97d6d0c204d404d39b22c420e75192ba2800863af3d
```

Calendrier : 12 calendriers et 91 événements personnels au pré-vol. Ne jamais
copier leur contenu dans une preuve.

## 2. Pré-vol lecture seule

```sh
adb devices -l
adb -s 45301FDAS001QT shell getprop ro.build.version.release
adb -s 45301FDAS001QT shell getprop ro.build.version.sdk
adb -s 45301FDAS001QT shell pm path dev.atlas.coach
adb -s 45301FDAS001QT reverse --list          # doit être vide
adb -s 45301FDAS001QT shell settings get global http_proxy
```

## 3. Sauvegarde et extraction de contrôle

```sh
mkdir -p ~/atlas-backups/r3b && chmod 700 ~/atlas-backups ~/atlas-backups/r3b
cd ~/atlas-backups/r3b
adb -s 45301FDAS001QT exec-out run-as dev.atlas.coach tar -cf - . \
  > pixel-app-backup-pre-r3b.tar
chmod 600 pixel-app-backup-pre-r3b.tar
sha256sum pixel-app-backup-pre-r3b.tar
rm -rf /tmp/atlas-backup-check && mkdir -m 700 /tmp/atlas-backup-check
tar -xf pixel-app-backup-pre-r3b.tar -C /tmp/atlas-backup-check
find /tmp/atlas-backup-check -type f | wc -l
rm -rf /tmp/atlas-backup-check
```

La sauvegarde reste hors dépôt, en `0600`, sur un volume privé. Ne jamais la
committer ni y lire de données personnelles dans une preuve.

## 4. Construction et installation de l'APK R3-B

L'APK doit être construit depuis le SHA intégré final, pas depuis un ancien
commit. Consigner avant installation :

```sh
cd android
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
apk=app/build/outputs/apk/debug/app-debug.apk
sha256sum "$apk"
apksigner=$(ls -d ~/Android/Sdk/build-tools/*/apksigner | sort -V | tail -1)
"$apksigner" verify --print-certs "$apk"
aapt2=$(ls -d ~/Android/Sdk/build-tools/*/aapt2 | sort -V | tail -1)
"$aapt2" dump badging "$apk" | grep -E '^(package|application-label)'
```

La signature debug doit correspondre au certificat de référence
`da301466…` pour que `adb install -r` conserve les données. Si la signature
diffère, **ne pas désinstaller** : arrêter et traiter comme `BLOCKED`.

```sh
adb -s 45301FDAS001QT install -r "$apk"
adb -s 45301FDAS001QT shell pm path dev.atlas.coach
adb -s 45301FDAS001QT shell dumpsys package dev.atlas.coach \
  | grep -E 'versionCode=|versionName='
```

Interdits : `pm clear`, `uninstall`, factory reset, modification du launcher
HOME, permission non consentie, écriture wger réelle non autorisée.

Après installation, vérifier les cinq hashes ATLAS et les compteurs calendrier.
Ils doivent être identiques à la référence.

## 5. A3 — Identité persistante

Utiliser le vrai `KeystoreRelayCredentialStore` et le parcours intégré, jamais
un faux provider.

1. **Phase A3-1** : appairage synthétique (URL WSS publique, serverId, clé
   X25519 du PC, deviceId, token fourni une seule fois), connexion
   `RELAY_CONNECTED`, vérification que le prefs ne contient pas le token en
   clair et que le Keystore le déchiffre.
2. **Phase A3-2** : `adb shell am force-stop dev.atlas.coach`, relance, même
   deviceId et même clé publique, token déchiffré, connexion sans ressaisie.
3. **Phase A3-3** : redémarrage Android (`adb reboot`), attente du boot,
   relance, mêmes vérifications, connexion sans ressaisie.
4. Vérifier les cinq hashes ATLAS et les compteurs calendrier.

Commandes de contrôle :

```sh
adb -s 45301FDAS001QT shell am force-stop dev.atlas.coach
adb -s 45301FDAS001QT reboot
adb -s 45301FDAS001QT wait-for-device
adb -s 45301FDAS001QT shell getprop sys.boot_completed
```

## 6. A4 — Révocation complète

1. Session authentifiée active.
2. Sur le PC : `scripts/revoke-device.sh "$HOME/.config/atlas/relay.env" <DEVICE_UUID>`.
3. Observer `DEVICE_REVOKED`, la fermeture WebSocket `4001` et l'état Android
   `REVOKED`.
4. Vérifier qu'aucune reconnexion authentifiée n'est tentée.
5. Dans l'UI, « Supprimer les identifiants de cet appareil » puis confirmer.
6. Vérifier `credentialsConfigured=false`, absence du token dans les prefs et
   effacement du deviceId/clé.
7. Relancer : `PAIRING_REQUIRED`, aucune reconnexion implicite.
8. Réappairer explicitement avec un second appareil synthétique.
9. Vérifier les données ATLAS et les autres comptes.

## 7. Réseau réel LTE/5G

Préconditions : Wi-Fi désactivé, données mobiles actives, aucun `adb reverse`,
aucun proxy USB, Funnel public autorisé.

```sh
adb -s 45301FDAS001QT shell svc wifi disable
adb -s 45301FDAS001QT shell svc data enable
adb -s 45301FDAS001QT reverse --list       # vide
adb -s 45301FDAS001QT shell settings get global http_proxy   # null
```

Scénarios :

| ID | Scénario |
|---|---|
| NET-02 | WSS public 8443, sans VPN Android |
| NET-03 | Pas d'`adb reverse`, pas de proxy USB |
| NET-04 | Noise `protected`, identité épinglée |
| NET-05 | `backend.health` via Funnel |
| NET-06 | Chat réel via relais et adaptateur autorisé |
| NET-07 | Coupure données -> OFFLINE, plan local préservé |
| NET-08 | Rétablissement -> reconnexion, aucune requête périmée rejouée |
| NET-09 | Wi-Fi -> LTE/5G -> Wi-Fi |
| NET-10 | Redémarrage connecteur/FastAPI |
| NET-11 | Redémarrage proxy/relais |
| NET-12 | Révocation avec session active |
| NET-13 | Expiration requête/session/token |
| NET-14 | Version/payload invalides |
| NET-15 | `/metrics` inaccessible publiquement |
| NET-16 | Données locales inchangées |

Ne pas confondre Wi-Fi personnel et LTE/5G. Ne pas promettre de réception
instantanée en Doze ; tester la reprise à l'ouverture, au premier plan, après
écran éteint si possible, et documenter `NOT_RUN` sinon.

## 8. wger

Lecture seule uniquement si le jeton existant le permet. Réutiliser
`/v1/sport/plan`, ne rien écrire, ne pas afficher le token, ne pas renouveler
de secret. Sinon `BLOCKED_AUTH`. Vérifier la projection, `trainingRef` et
l'affichage Android.

## 9. UI et accessibilité

Rejouer uniquement les régressions utiles : connexion/révocation, conversation,
erreur réseau, saisie du token, persistance de l'appairage, retour HOME, mode
hors ligne, police agrandie, rotation, TalkBack. Aucune refonte générale.

## 10. Rollback

1. Arrêter les services de test.
2. Réinstaller l'APK de rollback :
   `adb -s 45301FDAS001QT install -r ~/atlas-backups/r3b/pixel-current-r3a-plus.apk`.
3. Si nécessaire, restaurer les données app-only depuis la sauvegarde, après
   avoir vérifié son extraction.
4. Révoquer les appareils de test sur le PC.
5. Vérifier les hashes ATLAS et les compteurs calendrier.
6. Consigner SHA, versions, journaux et limites.

Ne jamais utiliser `pm clear` ni `uninstall` comme moyen de rollback.

## 11. État final de la campagne R3-B (2026-10-28)

- APK principal installé : `b55776a9…` (code Android `8eb4845`, tests
  test-only ensuite).
- A3 : appairage Keystore réel, force-stop, redémarrage Android, identité et
  token chiffré conservés — PASS.
- A4 : révocation PC, `DEVICE_REVOKED`, fermeture 4001, état `REVOKED`,
  effacement explicite, relance `PAIRING_REQUIRED`, réappairage explicite —
  PASS. Le connecteur attend 200 ms bornés avant la fermeture 4001
  (`dc8f55e`).
- Chat relayé : réponse OpenClaw via PC, provenance, aucun fallback direct,
  proposition non exécutée sans confirmation, brouillon et historique après
  rotation — PASS.
- Réseau : coupure mode avion et reprise, Wi-Fi → LTE/5G → Wi-Fi, redémarrage
  connecteur — PASS ; NET-07 PARTIAL car l'état observé est `ERROR`, pas
  `OFFLINE`.
- Données finales : 5/5 hashes d'avant campagne, calendriers 12, événements 91,
  aucun fichier de test. Voir `preuves/pixel-restore-final.txt`.
- Limites : TalkBack, police agrandie, Doze/H24, saisie manuelle du token,
  retour HOME et mode OFFLINE Pixel non qualifiés.
