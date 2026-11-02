# V7-07 — Preuve AVD jetable : backup, restore, update in-place, HOME

**Date :** 2026-11-01
**Branche :** `feat/atlas-v7-07-preproduction`
**Objet :** valider la procédure de sauvegarde/restauration app-only et la
compatibilité de mise à jour sur un environnement **jetable**, avec des données
**synthétiques**. Aucune donnée du propriétaire, aucun appareil physique.

## 1. Environnement

| Élément | Valeur |
|---|---|
| AVD | `atlas_v7_07_api35`, image `system-images;android-35;google_apis;x86_64` |
| Serial ADB | `emulator-<port>` (serial exact non publié), serveur ADB isolé `5038` |
| SDK | 35 (`x86_64`), 6 Go de partition data |
| APK historique | `post-install-base.apk`, SHA-256 `0d0ce524f1166690f5399ea7197ab4eb3b39bfa93604d3feb98613d75bc57ca4` |
| APK candidat | `app-debug.apk`, SHA-256 `d32efc7684c131131101f476f9f7ae186933efe863cb561b2ce0039ddc45e992` |
| Certificat | `da3014662eb65e50a87ef364a2e583c331398795b222e3b37b1a16553a00eeee` |
| Données | fixtures synthétiques uniquement : marqueur texte, plan vide, préférence marqueur |
| Outil | `scripts/v7-07/atlas-product-backup.sh`, commit `2d49705` |

**Limite :** l'essai est exécuté sur API 35, pas sur le Pixel physique API 37.
Les résultats prouvent la procédure et la compatibilité de paquet, pas l'état du
produit réel du propriétaire.

## 2. Préflight

```text
PREFLIGHT_OK
package=dev.atlas.coach
version_code=1
version_name=0.1.0
certificate_sha256=da301466…eeee
android_sdk=35
app_bytes_estimate=352256
device_available_bytes=5052510208
host_available_bytes=50178686976
serial_sha256=b7ba323fe8b07096827688d38fc95cbf1b52cf52421568cf7d448ce0294e2521
```

Le préflight vérifie un seul appareil ADB, le paquet, la version, le certificat
installé et local, `run-as`, le chemin applicatif, l'espace disque et l'absence
de modification.

## 3. Sauvegarde

```text
BACKUP_OK
archive_sha256=ec88d1a2ee8676b561805f7dfdf9952bc4d2bae69257e31a7249e121ff20fdbe
files=11
version=1/0.1.0
certificate_sha256=da301466…eeee
```

Archive `0600` de 176 128 octets, répertoire privé, manifeste `0600` avec
intégrité `08fbdf291b9cf5f812d3f9b3b3ce25c40c6608e4aef87c0428c23f441f781fb2`.
Aucune entrée `cache`/`code_cache`. Le manifeste ne contient ni serial brut ni
contenu applicatif.

Fichiers capturés : plan synthétique, marqueur, `profileInstalled`, base
WorkManager (`workdb`, `-wal`, `-shm`), quatre SharedPreferences existantes et
la préférence marqueur synthétique.

## 4. Vérification

```text
VERIFY_OK
package=dev.atlas.coach
version=1/0.1.0
archive_sha256=ec88d1a2…fdbe
files=11
```

## 5. Restauration

Le marqueur a été altéré après la sauvegarde :

```text
avant altération : 41fa5da58e12c1ab0e60ae2aeef5a57a4809f35fa7e42ac9e5adac8f28774c2b
après altération : bb7c226fdf87e7bc6aa523a43cf584fe1ebc90aec2dafe3cd06e7e45a7d561ce
```

Restauration avec confirmation explicite :

```text
RESTORE_OK package=dev.atlas.coach
rollback_backup=~/.local/state/atlas-product-backups/pre-restore-20261101T225110Z-938cb8f8
```

Après restauration :

```text
41fa5da58e12c1ab0e60ae2aeef5a57a4809f35fa7e42ac9e5adac8f28774c2b  files/v7-07-synthetic-marker.txt
f72255e188905c7d86811be87b8f37b5135714887a94a169e675daaed57d0e13  files/plans/day-plan-2026-11-01.json
```

L'application a démarré (`pidof` non vide) et l'UI a rendu les écrans Accueil,
Plan, Coach, Focus, Plus et le bouton d'urgence.

## 6. Comportement local et HOME

### Hors ligne

Wi‑Fi et données mobiles désactivés, l'application reste lancée et affiche :

- « Prochaine activité », « Planifier ma journée », « Parler au coach » ;
- « Focus », « Directive du coach », « Urgence » ;
- navigation « Accueil », « Plan », « Coach », « Focus », « Plus ».

Aucune erreur de persistance ou de quarantaine applicative observée.

### HOME

- Titulaire initial : `com.google.android.apps.nexuslauncher`.
- `dev.atlas.coach` ajouté comme titulaire du rôle HOME sur l'AVD.
- L'intent HOME ouvre `dev.atlas.coach.app.LauncherActivity` ; l'UI affiche
  « ATLAS », « Prochaine action », « Pause ».
- Le rôle a été retiré et le launcher Nexus restauré et vérifié.

Sur le Pixel physique, la sélection/restauration HOME devra passer par
l'interface Settings avec action utilisateur, conformément à
`android17-device-qa`.

## 7. Mise à jour in-place

APK historique installé, données synthétiques présentes, puis :

```text
adb install -r app-debug.apk
Performing Streamed Install
Success
```

`versionCode=1`, `versionName=0.1.0`, `lastUpdateTime` modifié, `firstInstallTime`
conservé. Hashes avant/après :

| Fichier | Avant | Après |
|---|---|---|
| marqueur | `41fa5da5…` | `41fa5da5…` |
| plan | `f72255e1…` | `f72255e1…` |
| état local | `024afd90…` | `024afd90…` (puis réécrit par l'app au lancement, toujours lisible) |

L'application candidate démarre en COLD, l'UI se rend correctement. Un second
backup avec le candidat passe :

```text
PREFLIGHT_OK (candidat)
BACKUP_OK archive_sha256=9232b10d153d3c740ff3824a6c8a11ecf4ca1f88be2b90fc90ff395d1f7c6875
VERIFY_OK
```

## 8. Rollback APK

Réinstallation de l'APK historique sur le candidat :

```text
adb install -r post-install-base.apk
Success
```

Hashes des données inchangés, application démarrée, UI rendue. Le rollback de
paquet fonctionne pour ce cas synthétique. Cela ne prouve pas qu'une migration
de données future reste réversible : une version qui réécrit un schéma peut
rendre l'ancien code incapable de relire l'état. L'archive pré-mise à jour reste
la référence.

## 9. Verdicts (périmètre AVD synthétique)

| Verdict | Résultat | Portée |
|---|---|---|
| `GO_DATA_BACKUP` | **GO_WITH_LIMITATIONS (AVD API 35, synthétique)** | archive vérifiée, sans cache, manifeste intègre |
| `GO_DATA_RESTORE` | **GO_WITH_LIMITATIONS (AVD API 35, synthétique)** | restauration confirmée, hashes identiques, app démarre |
| `GO_HOME_ROLLBACK` | **GO_WITH_LIMITATIONS (AVD API 35, synthétique)** | rôle HOME posé/restauré sur AVD jetable |
| Mise à jour in-place | **PASS (AVD API 35)** | même paquet/signature/versionCode, données conservées |
| Rollback APK | **PASS (AVD API 35)** | réinstallation historique, données conservées |
| Keystore / tokens | **NOT_RUN** | aucun token de test configuré ; la conservation in-place reste à qualifier sur le produit |
| WorkManager sémantique | **PARTIAL** | base capturée/restaurée ; échéances et doublons non exhaustivement vérifiés |
| Pixel physique API 37 | **NOT_RUN / BLOCKED_DEVICE_ABSENT** | aucun appareil connecté |

## 10. Nettoyage

- paquet `dev.atlas.coach` désinstallé de l'AVD ;
- APK/AndroidTest de test supprimés ;
- émulateur arrêté et AVD jetable supprimé ;
- répertoires de sauvegarde synthétique supprimés après conservation des hashes ;
- aucun paquet, service ou donnée laissé sur le Pixel physique (non touché).

## 11. Limites honnêtes

- L'AVD API 35 n'est pas le Pixel API 37 ; les résultats ne valent pas
  `GO_PIXEL_PRODUCT`.
- Les données sont synthétiques ; aucune donnée personnelle n'a été utilisée.
- Le test Keystore/tokens n'a pas été exécuté.
- Le test HOME utilise les commandes de rôle sur un AVD jetable, pas l'interface
  Settings du Pixel.
- Aucun chat distant, aucune proposition A0, aucun ACK Core n'a été exécuté.
- Aucun test de batterie, de veille longue ou de reconnexion réseau réelle.

## 12. Revalidation finale après correctif V4 (2026-11-02)

Un second AVD API 35 jetable a été créé pour revalider le candidat final
reconstruit après le correctif V4. Aucune donnée personnelle, aucun appareil
physique.

- APK historique installé : `post-install-base.apk`, hash `0d0ce524…`.
- APK final : `c7b0f71f43680f4bb45d8c9e6b808713ea09ff5f826fd79db31acce8ff4355a0`,
  source `b9f17f4`, `worktree_clean=true`, certificat `da301466…eeee`.
- Sauvegarde de l'état historique (`session-01`) : archive
  `30b3f0453b9da1f4d0c56135d77d084de31429965bf9a608b9c185dabdfc8da6`,
  10 fichiers.
- Prise en charge par l'outil : `PREFLIGHT_OK`, `BACKUP_OK`, `VERIFY_OK`,
  refus cross-serial couvert par les tests hors ligne.
- Mise à jour in-place `adb install -r` du candidat final : **Success**,
  `versionCode=1`, `versionName=0.1.0`, hashes du plan et du marqueur
  inchangés (`ad43e0e8…`, `a80dec73…`), application COLD et UI rendue.
- Sauvegarde du candidat (`session-02`) : archive
  `d3d9fb91fd228ef9df2e789c2fb29fede8ab67feaafa237356513e068465af04`,
  10 fichiers, `VERIFY_OK`.
- Altération du marqueur (`e3ea5d35…`), puis restauration de `session-01` :
  `RESTORE_OK`, marqueur revenu à `a80dec73…`, plan à `ad43e0e8…`, application
  relancée.
- HOME : `dev.atlas.coach` titulaire, `LauncherActivity` affichée (« ATLAS »,
  « Pause »), launcher Nexus restauré et vérifié.
- Rollback APK vers l'historique : **Success**, données inchangées, application
  relancée.
- Keystore/tokens : **NOT_RUN** ; Pixel API 37 : **NOT_RUN**.
- Nettoyage : paquet désinstallé, émulateur arrêté, AVD et sauvegardes
  synthétiques supprimés après conservation des hashes.
