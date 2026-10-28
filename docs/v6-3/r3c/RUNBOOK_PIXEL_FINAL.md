# ATLAS V6.3 — R3-C — Runbook Pixel final (non destructif)

Cible : **Pixel 9 Pro XL physique**, Android 17 / API 37, série
`45301FDAS001QT`. Aucun émulateur ne vaut preuve Pixel. Ce runbook remplace
les instructions de restauration R3-B devenues dangereuses.

## 0. Autorisations et principe

Actions autorisées uniquement après mandat ciblé de l'utilisateur :
installation d'APK, appairage, révocation, suppression explicite des
identifiants de l'appareil, redémarrage Android, coupure réseau contrôlée.

**Interdits par défaut :**

```text
pm clear
uninstall
factory reset
rm -rf de répertoires applicatifs
écrasement global des préférences ou bases
suppression de l'alias Keystore hors du scénario explicite d'appairage
lecture du contenu des calendriers ou des messages personnels
```

Le principe est : **mise à jour avec conservation des données**, sauvegarde
app-only vérifiée, comparaison ciblée avant/après, nettoyage uniquement des
fixtures synthétiques identifiées. Une restauration complète depuis l'archive
est un **recours exceptionnel**, après analyse d'impact et autorisation
ciblée.

## 1. Pré-vol lecture seule

```sh
adb devices -l
adb -s 45301FDAS001QT shell getprop ro.build.version.release
adb -s 45301FDAS001QT shell getprop ro.build.version.sdk
adb -s 45301FDAS001QT shell pm path dev.atlas.coach
adb -s 45301FDAS001QT shell dumpsys package dev.atlas.coach | grep -E 'versionCode=|versionName='
adb -s 45301FDAS001QT reverse --list          # doit être vide
adb -s 45301FDAS001QT shell settings get global http_proxy
```

Vérifier aussi que l'appareil est bien celui attendu et que la batterie est
suffisante. Aucun `adb root`, aucun Device Owner, aucun kiosque.

## 2. Sauvegarde app-only vérifiée

```sh
backup_dir="$HOME/atlas-backups/r3c"
umask 077
mkdir -p "$backup_dir" && chmod 700 "$HOME/atlas-backups" "$backup_dir"
adb -s 45301FDAS001QT exec-out run-as dev.atlas.coach tar -cf - . \
  > "$backup_dir/pixel-app-backup-pre-r3c.tar"
chmod 600 "$backup_dir/pixel-app-backup-pre-r3c.tar"
sha256sum "$backup_dir/pixel-app-backup-pre-r3c.tar"
```

Contrôle de lisibilité dans un répertoire temporaire privé, puis suppression
**uniquement** de ce répertoire temporaire :

```sh
check=$(mktemp -d)
chmod 700 "$check"
tar -tf "$backup_dir/pixel-app-backup-pre-r3c.tar" >/dev/null
tar -xf "$backup_dir/pixel-app-backup-pre-r3c.tar" -C "$check"
find "$check" -type f | wc -l
rm -rf "$check"
```

La sauvegarde reste **hors dépôt**, en `0600`. Ne jamais la committer ni en
extraire des données personnelles dans une preuve.

Les cinq hashes R3-B (`atlas_local_state.xml`, `atlas_sync_configuration.xml`,
`atlas_agent_revocation_barrier.xml`, `atlas_agent_inbox_v2.json`,
`plans/day-plan-2026-10-26.json`) sont des **témoins de référence**, pas une
preuve d'intégrité exhaustive. Comparer uniquement les fichiers pertinents
avant/après, sans publier leur contenu.

Contrôle Keystore **indépendant de l'archive** : le Keystore n'est pas dans le
tar. Vérifier que l'app déchiffre encore le credential après restauration via
son parcours réel ; ne jamais supprimer l'alias hors du scénario A4
« supprimer les identifiants » demandé explicitement.

Calendrier : compter les calendriers et événements, ne jamais lire les titres.

```sh
adb -s 45301FDAS001QT shell content query --uri content://com.android.calendar/calendars --projection _id | wc -l
adb -s 45301FDAS001QT shell content query --uri content://com.android.calendar/events --projection _id | wc -l
```

## 3. Construction et contrôle de l'APK R3-C

Construire depuis le **SHA R3-C intégré**, pas depuis un ancien commit :

```sh
cd android
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --max-workers=1
apk=app/build/outputs/apk/debug/app-debug.apk
testapk=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
sha256sum "$apk" "$testapk"
apksigner=$(ls -d ~/Android/Sdk/build-tools/*/apksigner | sort -V | tail -1)
"$apksigner" verify --print-certs "$apk"
aapt2=$(ls -d ~/Android/Sdk/build-tools/*/aapt2 | sort -V | tail -1)
"$aapt2" dump badging "$apk" | grep -E '^(package|application-label)'
```

Consigner : SHA source, SHA APK principal, SHA APK androidTest, signature,
`versionCode`, `versionName`, appareil, version Android, date.

Avant installation :
- signature identique au certificat debug de référence
  `da3014662eb65e50a87ef364a2e583c331398795b222e3b37b1a16553a00eeee` ;
- `versionCode` compatible avec les données existantes ;
- absence de migration non maîtrisée ;
- sauvegarde lisible et intégrité vérifiée ;
- mandat d'installation obtenu.

Si la signature diffère : **ne pas désinstaller**, statut `BLOCKED`.

```sh
adb -s 45301FDAS001QT install -r "$apk"
adb -s 45301FDAS001QT install -r -t "$testapk"
adb -s 45301FDAS001QT shell dumpsys package dev.atlas.coach | grep -E 'versionCode=|versionName='
```

Après installation, comparer les témoins et les compteurs calendrier. Aucun
`pm clear`, aucune suppression de répertoire.

## 4. A3 — Appairage réel et persistance

Utiliser le **vrai `KeystoreRelayCredentialStore`** et le parcours intégré.
Appareil synthétique dédié, token fourni une seule fois par fichier `0600`.

1. **A3-1** : appairage WSS public, `RELAY_CONNECTED`, token absent des prefs
   en clair, déchiffrement Keystore OK.
2. **A3-2** : `adb shell am force-stop dev.atlas.coach`, relance, même
   `deviceId` et même clé publique, token déchiffré, reconnexion sans ressaisie.
3. **A3-3** : `adb reboot`, attente `sys.boot_completed=1`, relance, mêmes
   vérifications.
4. Vérifier les témoins de données et les compteurs calendrier.

Commandes de contrôle :

```sh
adb -s 45301FDAS001QT shell am force-stop dev.atlas.coach
adb -s 45301FDAS001QT reboot
adb -s 45301FDAS001QT wait-for-device
adb -s 45301FDAS001QT shell getprop sys.boot_completed
```

## 5. A4 — Révocation complète avec session établie

1. Session authentifiée active.
2. Sur le PC : `scripts/revoke-device.sh "$HOME/.config/atlas/relay.env" <DEVICE_UUID>`.
3. Observer `DEVICE_REVOKED` **avant** la fermeture, puis la fermeture
   WebSocket `4001`. L'assertion `lastCloseCode == 4001` doit être explicite
   dans le test instrumenté.
4. État Android `REVOKED`.
5. Aucune reconnexion authentifiée : vérifier le journal et l'absence de
   nouvelle session côté connecteur.
6. Dans l'UI, action explicite « Supprimer les identifiants de cet appareil »,
   puis confirmation.
7. Vérifier `credentialsConfigured=false`, absence du token dans les prefs,
   suppression du `deviceId`/clé **et de l'alias Keystore associé** via le
   parcours applicatif.
8. Relancer : `PAIRING_REQUIRED`, aucune reconnexion implicite.
9. Réappairer explicitement avec un second appareil synthétique.
10. Vérifier les données ATLAS et les autres comptes.

Ne jamais supprimer l'alias Keystore par une commande système ou un script
externe : uniquement via l'action applicative consentie.

## 6. Conversation relayée

- Message relayé jusqu'au backend approuvé, réponse réelle si l'accès est
  autorisé (OpenClaw personnel ou instance isolée).
- Contexte consenti et minimisé ; aucune catégorie non consentie.
- Provenance correcte (`mock` vs `OpenClaw via PC`).
- Aucune action non confirmée : mode `PROPOSALS_ONLY`, rien d'`EXECUTED`
  sans confirmation explicite.
- Brouillon et historique préservés après rotation et après coupure réseau.
- Aucun fallback direct : tout passe par le relais et le Funnel.

## 7. Réseau — matrice discriminante

| ID | Scénario | Attendu |
|---|---|---|
| NET-07a | mode avion | `OFFLINE`, aucune erreur de sécurité masquée |
| NET-07b | Wi-Fi coupé | `OFFLINE` |
| NET-07c | données mobiles coupées | `OFFLINE` |
| NET-07d | Internet perdu, Wi-Fi encore actif | `OFFLINE` |
| NET-07e | relais inaccessible, Internet disponible | `OFFLINE`, pas `ERROR` |
| NET-07f | FastAPI arrêté | session `RELAY_CONNECTED`, opération `BACKEND_UNAVAILABLE` |
| NET-07g | certificat TLS invalide (endpoint de test uniquement) | `ERROR`, jamais `OFFLINE` |
| NET-07h | token invalide | `AUTH_REQUIRED` |
| NET-07i | appareil révoqué | `REVOKED` |
| NET-07j | réseau rétabli | `RELAY_CONNECTING` puis `RELAY_CONNECTED` |
| NET-13 | expiration requête/session/token | refus explicite, pas de rejeu automatique |
| CHAT-05 | mandat révoqué/expiré | refus, aucun envoi |
| CHAT-06 | déconnexion pendant chat en vol | résultat incertain non rejoué, brouillon conservé |

Ne jamais modifier un certificat système ni installer une autorité racine sur
le Pixel pour tester TLS. Utiliser un endpoint de test dont le certificat est
invalide, ou un test JVM.

La reprise après coupure ne doit ni perdre un brouillon ni rejouer
automatiquement une opération incertaine. Le mode mock local doit rester
utilisable hors ligne.

## 8. UI et accessibilité

| ID | Cas | Attendu |
|---|---|---|
| UI-04 | saisie et stockage du token | pas de token en clair, parcours sûr |
| UI-06 | retour HOME | aucune régression, launcher d'origine accessible |
| UI-07 | démonstration locale hors ligne | mock utilisable sans serveur |
| UI-08 | police agrandie, rotation, TalkBack | lisible, focus et libellés corrects |

Aucune refonte de navigation ou d'interface hors de ce périmètre.

## 9. Rollback

1. Arrêter les services de test.
2. Vérifier que l'APK de rollback a un `versionCode` **supérieur ou égal** et
   la même signature, et que les données sont compatibles.
3. `adb install -r` peut échouer sur un downgrade : ce n'est **pas** une
   garantie. Analyser l'impact avant toute tentative.
4. Si une restauration app-only est indispensable : autorisation ciblée après
   analyse d'impact, extraction vérifiée, comparaison des témoins. Ne jamais
   faire `pm clear`, `uninstall` ou `rm -rf` des répertoires applicatifs.
5. Révoquer les appareils de test sur le PC et supprimer les tokens de test.
6. Consigner SHA, versions, journaux et limites.

## 10. Preuves à conserver

Pour chaque test : identifiant, commande exacte, horodatage de début et de
fin, durée, sortie instrumentée (expurgée), verdict `PASS/FAIL/BLOCKED/NOT_RUN`,
SHA source et SHA APK. Une preuve JVM ne vaut pas preuve instrumentée ; une
preuve émulateur ne vaut pas preuve Pixel ; une preuve MagicDNS ne vaut pas
preuve publique.
