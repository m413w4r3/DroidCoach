# ATLAS V7-07 — Sauvegarde, restauration et rollback

**Date :** 2026-11-01
**BASE_SHA :** `32e7c70c78fbe295f66f67d6be50dc797a3f6e6d`
**Objet :** définir et vérifier une procédure app-only pour le produit
`dev.atlas.coach`, sans utiliser les données personnelles du propriétaire.

**Statut :** procédure et outillage en cours de validation. Aucune restauration
n'a encore été exécutée sur le produit. L'archive historique
`20261030-pre-v7` est une preuve d'existence, pas une preuve de restauration.

## 1. Pourquoi `adb backup` ne suffit pas

Le manifeste produit contient :

- `android:allowBackup="false"` ;
- `android:fullBackupContent="@xml/backup_rules"` ;
- `android:dataExtractionRules="@xml/data_extraction_rules"` ;
- deux règles qui excluent `root` pour le cloud backup et le device transfer.

**Conséquence PROVEN :** `adb backup` et les mécanismes Android Auto Backup /
device transfer ne capturent pas les données de cette application. Toute
procédure doit passer par `run-as` (APK debuggable) ou par un export/import
intégré à l'application.

L'APK produit historique est `debuggable=true` ; `run-as` est donc
statiquement disponible. Cette éligibilité ne prouve pas l'accès effectif ni la
restauration : l'essai sur environnement jetable reste obligatoire.

## 2. Ce qui est sauvegardable et ce qui ne l'est pas

| Donnée | Backup app-only `run-as` | Restauration | Limite |
|---|---|---|---|
| Plans locaux (`files/plans/day-plan-*.json`) | oui | oui si schéma lu | schémas 3/4/5 ; l'écriture candidat passe en v5 |
| Objectifs, habitudes, Focus, routines, conversations, propositions | oui (état local JSON) | oui | lecture stricte `schemaVersion=1` ; quarantaine si invalide |
| Mémoire locale (`files/memory/memory-facts.json`) | oui si présente | oui | schéma strict v1 |
| Inbox/effets agent, outbox sync, inbox V4 | oui si présents | oui | certains stores n'ont pas de quarantaine |
| État V4 (`atlas_core_v4_sync`) | oui | oui, avec clés de quarantaine | correctif `dd343c2` : valeur brute mise en quarantaine, écriture refusée si la quarantaine échoue |
| Calendrier local ATLAS | **non** | **non** | vit dans `CalendarProvider`/`AccountManager`, hors bac de l'app |
| Permissions Android / AppOps | **non** | à réaccorder | gérées par Android |
| Rôle HOME | **non** | via Settings | géré par Android ; préférence `atlas_launcher_exit` seule |
| Canaux et réglages de notification | **non** | recréés par l'app | gérés par Android |
| Alarmes Focus/habitudes/plans | **non** | reprogrammées | `AlarmManager`/WorkManager système |
| WorkManager DB (`no_backup/androidx.work.workdb`) | oui si capturée | partielle | les échéances système ne sont pas dans le fichier |
| Bearer backend chiffré | ciphertext oui | **in-place seulement** | clé Keystore `atlas_backend_bearer_v1` non exportable |
| Token Relay chiffré | ciphertext oui | **in-place seulement** | clé Keystore `atlas_relay_device_v1` non exportable |
| Identité Relay / appairage | métadonnées oui | réappairage après désinstallation | le token opérationnel dépend du Keystore et du registre connecteur |
| Historique des consentements | oui (état local) | oui, mais à revalider | restaurer un ancien état ne vaut pas consentement actuel |

### Règle Keystore

- **Mise à jour in-place, même paquet, même signature, même appareil :** les
  alias AndroidKeyStore survivent ; les ciphertexts restent déchiffrables.
- **Désinstallation / nouvel appareil :** les clés sont perdues. Les getters du
  code retournent `null` (`runCatching { ... }.getOrNull()`) et l'application
  demande une reconfiguration ou un réappairage ; elle ne doit pas être
  considérée comme appairée.

## 3. Archive historique existante

Chemin privé : `~/.local/state/atlas-pixel/backups/20261030-pre-v7`.

| Fichier | Taille | SHA-256 |
|---|---:|---|
| `base.apk` | 17 326 339 | `a315ed0473593b2c38730eadd15cd18fa5aab721cc6d46ee3941be833fa2a515` |
| `post-install-base.apk` | 16 902 465 | `0d0ce524f1166690f5399ea7197ab4eb3b39bfa93604d3feb98613d75bc57ca4` |
| `dev.atlas.coach-appdata.tar` | 355 840 | `388f2250b04302488d305d224bcd3112aed07e660085ee4d433f95ba4993b6de` |

Le tar contient 35 entrées (17 fichiers) : 11 SharedPreferences, un plan
historique en schéma 3, l'inbox agent, la base WorkManager avec WAL/SHM, et des
répertoires de cache. Il ne contient pas les fichiers mémoire, effets agent,
outbox sync ou inbox V4. **Cette archive ne doit pas être restaurée sur le
produit du propriétaire sans autorisation et sans avoir prouvé la procédure sur
un environnement jetable.**

## 4. Outillage

Le ticket `V7-07-BACKUP-TOOL` produit `scripts/v7-07/atlas-product-backup.sh` et
ses tests hors ligne. Sous-commandes prévues :

```sh
scripts/v7-07/atlas-product-backup.sh preflight --serial SERIAL --apk APK
scripts/v7-07/atlas-product-backup.sh backup --serial SERIAL --backup-dir DIR
scripts/v7-07/atlas-product-backup.sh verify --archive ARCHIVE --manifest MANIFEST
scripts/v7-07/atlas-product-backup.sh restore --serial SERIAL \
  --archive ARCHIVE --manifest MANIFEST --confirm-restore
scripts/v7-07/atlas-product-backup.sh plan-rollback --backup-dir DIR
```

Exigences de sûreté :

- un seul appareil ciblé explicitement, paquet exact `dev.atlas.coach` ;
- vérification du certificat `da301466…eeee`, du versionCode/versionName et de
  `run-as` avant toute capture ;
- arrêt forcé de l'application avant capture ;
- archive hors cache/code_cache, répertoire `0700`, fichiers `0600`, hash
  SHA-256 et manifeste sans contenu personnel ;
- `restore` refuse sans confirmation explicite, vérifie à nouveau l'archive,
  sauvegarde l'état courant, ne remplace que les fichiers du paquet ciblé, ne
  fait jamais `pm clear` ni `uninstall`, compare les hashes après extraction et
  démarre l'application ;
- en cas d'échec, indique le répertoire de rollback et ne masque pas l'erreur.

## 5. Procédure de backup (à exécuter sur appareil autorisé)

```sh
PKG=dev.atlas.coach
SERIAL=<serial-explicite>
BACKUP_DIR="$HOME/.local/state/atlas-backups/v7-07-<UTC>"
ARCHIVE="$BACKUP_DIR/appdata.tar"

umask 077
install -d -m 0700 "$BACKUP_DIR"

adb -s "$SERIAL" get-state
adb -s "$SERIAL" shell getprop ro.build.version.sdk
adb -s "$SERIAL" shell run-as "$PKG" id
adb -s "$SERIAL" shell am force-stop "$PKG"

adb -s "$SERIAL" exec-out run-as "$PKG" sh -c \
  'cd /data/user/0/dev.atlas.coach && find . \
     \( -type d \( -name cache -o -name code_cache \) -prune \) -o \
     -type f -exec sha256sum {} \;' \
  | LC_ALL=C sort > "$BACKUP_DIR/files.before.sha256"

adb -s "$SERIAL" exec-out run-as "$PKG" tar -cf - \
  --exclude=./cache --exclude=./code_cache \
  -C /data/user/0/dev.atlas.coach . > "$ARCHIVE"

chmod 0600 "$ARCHIVE" "$BACKUP_DIR/files.before.sha256"
sha256sum "$ARCHIVE" > "$ARCHIVE.sha256"
chmod 0600 "$ARCHIVE.sha256"
sha256sum -c "$ARCHIVE.sha256"
```

Vérifications attendues : hash OK, répertoire `0700`, archive `0600`, aucune
entrée `cache`/`code_cache`, listing cohérent. **Ne pas publier le contenu de
l'archive.**

## 6. Procédure de restauration (destructive, confirmation explicite)

À utiliser uniquement sur un paquet déjà installé, même signature, après
validation de l'archive et sauvegarde de l'état courant.

```sh
adb -s "$SERIAL" shell am force-stop "$PKG"

# Sauvegarde de sécurité de l'état courant AVANT remplacement.
# (commande backup ci-dessus, répertoire distinct)

adb -s "$SERIAL" shell run-as "$PKG" sh -c \
  'cd /data/user/0/dev.atlas.coach && \
   find . -mindepth 1 -maxdepth 1 ! -name cache ! -name code_cache -exec rm -rf {} +'

adb -s "$SERIAL" exec-in run-as "$PKG" tar -xpf - \
  -C /data/user/0/dev.atlas.coach < "$ARCHIVE"

adb -s "$SERIAL" exec-out run-as "$PKG" sh -c \
  'cd /data/user/0/dev.atlas.coach && find . \
     \( -type d \( -name cache -o -name code_cache \) -prune \) -o \
     -type f -exec sha256sum {} \;' \
  | LC_ALL=C sort > "$BACKUP_DIR/files.after.sha256"

cmp -s "$BACKUP_DIR/files.before.sha256" "$BACKUP_DIR/files.after.sha256"
adb -s "$SERIAL" shell am start -W -n \
  dev.atlas.coach/dev.atlas.coach.app.MainActivity
```

La comparaison des manifestes doit être identique. Si l'application ne démarre
pas ou si un état est illisible, conserver l'état actuel, restaurer l'archive de
sécurité et appliquer le rollback APK.

## 7. Rollback

### 7.1 Échec d'une mise à jour in-place

Conserver avant l'essai :

- l'APK précédent (`post-install-base.apk` ou l'APK réellement installé) ;
- son hash, son certificat, son versionCode/versionName ;
- une archive app-only vérifiée ;
- la preuve du rôle HOME initial et du launcher précédent.

```sh
adb -s "$SERIAL" shell am force-stop "$PKG"
adb -s "$SERIAL" install -r "$PREVIOUS_APK"
adb -s "$SERIAL" shell am start -W -n \
  dev.atlas.coach/dev.atlas.coach.app.MainActivity
```

Limites :

- même paquet et même signature obligatoires ;
- `versionCode` précédent égal ou supérieur ; ne pas utiliser `-d` pour forcer ;
- une migration de données peut être irréversible : le retour APK ne défait pas
  une écriture de plan v5 ou un état local réécrit ;
- si l'APK revient mais que les données sont illisibles, restaurer l'archive
  pré-mise à jour **après** avoir conservé l'état défectueux et vérifié la
  compatibilité du schéma.

### 7.2 HOME défectueux

Le rôle HOME appartient à Android, pas à l'application. Le retour sûr passe par
l'interface des réglages Android, avec vérification du titulaire du rôle :

```sh
adb -s "$SERIAL" shell am start -W -a android.settings.HOME_SETTINGS
adb -s "$SERIAL" shell cmd role get-role-holders android.app.role.HOME
```

Sur le Pixel du propriétaire, l'action est manuelle et doit être expliquée avant
le pilote. Ne pas utiliser de commande `cmd role` pour modifier le rôle sans
autorisation ; la vérification est en lecture seule.

### 7.3 Données corrompues

1. Arrêter l'application.
2. Capturer et hasher l'état endommagé.
3. Vérifier l'archive de restauration et son manifeste.
4. Restaurer uniquement si l'APK installé lit le schéma de l'archive.
5. Si la quarantaine de l'application a déjà déplacé l'état brut, conserver la
   copie mise en quarantaine.

## 8. Essai obligatoire sur environnement jetable

L'essai cible un AVD **API 37** dédié, avec des données synthétiques uniquement,
jamais sur les données du propriétaire. Si l'image API 37 de l'hôte est instable,
un AVD jetable d'API inférieure **stable** (API 35) peut être utilisé pour
qualifier la procédure ; la limite est alors explicite : le Pixel physique
API 37 reste `NOT_RUN` et aucun verdict produit n'est déduit de l'AVD.

### 8.0 Résultat de l'essai AVD (2026-11-01)

L'image API 37.2 de l'hôte s'est révélée instable (`surfaceflinger`/UWB,
`system_server` en crash loop), comme lors de V7-06A. L'essai a donc été
exécuté sur un **AVD API 35 jetable** (`google_apis/x86_64`), avec des fixtures
synthétiques, puis nettoyé. Preuve détaillée :
`evidence/V7-07_AVD_BACKUP_RESTORE.md`.

| Oracle | Résultat |
|---|---|
| Préflight | **PASS** — paquet/version/certificat, `run-as`, espace |
| Backup | **PASS** — archive `ec88d1a2…`, 11 fichiers, zéro cache, manifeste intègre |
| Verify | **PASS** |
| Restauration après altération | **PASS** — marqueur restauré, plan intact, app démarrée |
| Hors ligne | **PASS** — accueil, Plan, Coach, Focus, Pause accessibles |
| HOME AVD | **PASS** — LauncherActivity affichée, launcher Nexus restauré |
| Update in-place historique → candidat | **PASS** — installation `-r`, hashes conservés, app COLD rendue |
| Rollback APK candidat → historique | **PASS** — données conservées, app rendue |
| Keystore/tokens | **NOT_RUN** — aucun token configuré |
| Pixel physique API 37 | **NOT_RUN / BLOCKED_DEVICE_ABSENT** |

**Revalidation finale après correctif V4 (2026-11-02) :** AVD API 35 jetable,
candidat `c7b0f71f…` (source `b9f17f4`). Update in-place **PASS** (données
conservées), backup candidat `d3d9fb91…` **PASS**, restauration de l'archive
historique `30b3f045…` **PASS**, HOME et rollback **PASS**. Keystore/tokens et
Pixel API 37 restent `NOT_RUN`. L'outil refuse désormais une archive dont le
hash de serial diffère de l'appareil cible (18 tests hors ligne).

### 8.1 Préparation

- créer un AVD neuf ou utiliser un AVD explicitement jetable ;
- serveur ADB isolé (par exemple `ANDROID_ADB_SERVER_PORT=5038`) pour ne jamais
  cibler le Pixel physique ;
- installer l'APK produit historique puis le candidat, jamais la variante QA
  comme substitut du paquet produit ;
- vérifier paquet, certificat, versionCode/versionName et `run-as`.

### 8.2 Scénario A — backup / désinstallation / restauration

1. installer l'APK produit et créer des fixtures synthétiques : plan, objectif,
   habitude, Focus, conversation, proposition ; mode Relay `OFFLINE` ;
2. exécuter `backup`, vérifier hash, modes, absence de cache ;
3. désinstaller uniquement le paquet sur l'AVD ;
4. réinstaller le même APK, vérifier que les tokens Keystore sont perdus et que
   l'app demande reconfiguration ;
5. exécuter `restore`, comparer les manifestes, démarrer l'app ;
6. vérifier chaque fixture après redémarrage, et l'absence d'effet distant.

### 8.3 Scénario B — mise à jour in-place

1. repartir d'un état synthétique distinct et capturer une sauvegarde ;
2. vérifier que l'APK historique et le candidat partagent paquet, certificat et
   versionCode ;
3. `adb install -r "$CANDIDATE_APK"` sans désinstallation ;
4. vérifier les fixtures, les réglages Relay synthétiques et un indicateur
   booléen de Keystore, sans afficher de token ;
5. vérifier que l'app ne démarre pas en état de quarantaine.

### 8.4 Scénario C — HOME

1. relever le titulaire initial du rôle HOME ;
2. choisir ATLAS via l'interface Settings, vérifier l'ouverture ;
3. restaurer le launcher initial via la même interface, vérifier le rôle ;
4. ne pas utiliser `cmd role add-role-holder` pour modifier le rôle sans
   autorisation.

### 8.5 Oracles

- archive vérifiée, sans cache, modes privés ;
- manifestes de fichiers identiques avant/après restauration ;
- fixtures synthétiques présentes après restauration et après update ;
- tokens Keystore : valides après update in-place, invalides après
  désinstallation ;
- aucune requête distante en mode `OFFLINE` ;
- WorkManager réconcilié sans doublon ;
- HOME restauré au launcher initial ;
- nettoyage final : paquets de test désinstallés, AVD supprimé, répertoires
  privés conservés ou supprimés selon la politique de preuve.

## 9. Mécanisme manquant : export/import intégré

`run-as` suffit pour un snapshot de dépannage d'un APK debugguable sur le même
appareil. Il ne fournit pas :

- une sauvegarde depuis un APK non debugguable ;
- une restauration sur un appareil neuf ;
- une restauration après perte du Keystore ;
- une sauvegarde compréhensible par l'utilisateur sans ADB.

Un export/import intégré borné reste donc **nécessaire pour une fiabilité
quotidienne durable**. Périmètre minimal proposé :

- archive ZIP versionnée avec `manifest.json`, données JSON par domaine,
  longueurs et SHA-256 ;
- accès via Storage Access Framework, limites de taille ;
- chiffrement AES-GCM avec secret fourni séparément par l'utilisateur ;
- validation stricte des chemins, schémas et versions avant remplacement
  atomique ;
- inclusion des plans, objectifs, habitudes, Focus, mémoire, conversations,
  propositions et historique d'audit ;
- exclusion des clés Keystore, bearers, token Relay, caches, base WorkManager,
  rôle HOME, permissions et état système du calendrier ;
- réappairage Relay et redemande de consentement au premier lancement importé ;
- tests : round-trip, archive altérée, mauvais secret, schéma inconnu, chemins
  ZIP malveillants, interruption avant commit, absence de requête distante avant
  nouveau consentement.

Ce mécanisme n'est pas implémenté dans V7-07 ; il est un ticket correctif
distinct, non bloquant pour l'essai `run-as` sur AVD, mais bloquant pour une
promesse de sauvegarde utilisateur durable.

## 10. Verdicts

| Verdict | État | Motif |
|---|---|---|
| `GO_DATA_BACKUP` | **GO_WITH_LIMITATIONS (AVD API 35, synthétique)** / **NOT_RUN (Pixel API 37)** | archive vérifiée, sans cache, manifeste intègre ; aucune sauvegarde du produit réel |
| `GO_DATA_RESTORE` | **GO_WITH_LIMITATIONS (AVD API 35, synthétique)** / **NOT_RUN (Pixel API 37)** | round-trip vérifié, hashes identiques, app démarrée ; aucune restauration du produit réel |
| `GO_HOME_ROLLBACK` | **GO_WITH_LIMITATIONS (AVD API 35, synthétique)** / **NOT_RUN (Pixel API 37)** | rôle HOME posé et restauré sur AVD ; procédure Settings à rejouer sur le Pixel |
| Update in-place | **PASS (AVD API 35)** / **NOT_RUN (Pixel API 37)** | même paquet/signature/versionCode, données conservées |
| Rollback APK | **PASS (AVD API 35)** / **NOT_RUN (Pixel API 37)** | réinstallation historique, données conservées |
| Export/import intégré | **NO-GO / NOT_IMPLEMENTED** | nécessaire pour une sauvegarde utilisateur durable sans ADB |

Les verdicts AVD ne constituent pas une preuve sur le produit réel. Le Pixel
physique n'était pas connecté ; aucun `GO_DATA_*` produit n'est prononcé.
