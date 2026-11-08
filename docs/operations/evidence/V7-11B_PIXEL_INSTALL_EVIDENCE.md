# V7-11B — Installation Pixel in-place et préservation

**Date :** 2026-11-08
**Branche :** `feat/atlas-v7-11-migration-readiness`
**Base d'intégration :** `2159273` (code `a3d72db`)
**Appareil :** Pixel 9 Pro XL physique, Android 17 / API 37, utilisateur 0. Identifiant d'appareil non publié.
**Autorisation :** l'utilisateur a explicitement choisi le paquet « Installation Pixel (physique) » le 2026-11-08, après avoir autorisé l'écrasement de l'application Atlas installée. Périmètre : installation in-place de l'APK exact, sauvegarde app-only, vérification des données, permissions, rôle HOME et absence d'erreur. **Hors périmètre :** APPLY, modification du bearer, révocation, modification live, `pm clear`, désinstallation, changement de rôle, autres utilisateurs Android.

## 1. Verdict

| Élément | Résultat |
|---|---|
| Installation in-place `adb install -r` | **PASS** (Success, exit 0) |
| Identité de l'APK installé | **PASS** : SHA-256 `bb703ec7…f1dff`, identique au candidat |
| Certificat | **PASS** : `da301466…eeee`, identique avant/après |
| Version | **PASS** : `versionCode=1`, `versionName=0.1.0` |
| Données applicatives | **PASS** : archive byte-identique avant/après installation (26 fichiers) |
| Préférences et consentements | **PASS** : 15/15 fichiers `shared_prefs` identiques après démarrage |
| Permissions | **PASS** : 23 entrées identiques avant/après |
| Rôle HOME | **PASS** : `dev.atlas.coach` conservé ; `LauncherActivity` affichée |
| Démarrage / stabilité | **PASS** : démarrage à froid 399 ms, aucun FATAL/ANR/Keystore |
| QA fonctionnelle complète | **NOT_RUN** (hors périmètre de ce paquet) |
| APPLY (A0) | **NOT_RUN / BLOCKED** (aucune autorisation) |
| Core/Relay live | **INCHANGÉS** : `restarts=0`, démarrés le 2026-11-07T10:05:46Z |

## 2. Pré-vol lecture seule

| Constat | Valeur |
|---|---|
| Package installé | `dev.atlas.coach`, `versionCode=1`, `versionName=0.1.0` |
| `debuggable` | oui (`flags=[ DEBUGGABLE … ]`) |
| `firstInstallTime` (user 0) | 2026-10-28 18:15:55 |
| `lastUpdateTime` avant | 2026-11-02 09:55:45 |
| `source_sha` de l'APK installé | `b9f17f4…` |
| SHA-256 APK installé avant | `c7b0f71f43680f4bb45d8c9e6b808713ea09ff5f826fd79db31acce8ff4355a0` (15 806 203 octets) |
| Certificat APK installé | `da3014662eb65e50a87ef364a2e583c331398795b222e3b37b1a16553a00eeee` |
| Détenteur du rôle HOME | `dev.atlas.coach` |
| Stockage libre `/data` | 31 G |
| Processus avant | actif (PID éphémère non publié) |

Candidat vérifié avant écriture :

| Constat | Valeur |
|---|---|
| SHA-256 | `bb703ec770f4ebe7e20602db65fecfb75e7a783d1a30040f5f7d8796c29f1dff` |
| Taille | 15 838 971 octets |
| `source_sha` embarqué | `65759dd` |
| Certificat | `da301466…eeee` (identique à l'installé) |
| Package / version | `dev.atlas.coach` / 1 / 0.1.0 |
| Compatibilité | mise à jour de même `versionCode`, même certificat, même package |

## 3. Sauvegarde app-only

La sauvegarde a été créée **avant** l'installation, dans un répertoire privé hors dépôt et hors `/tmp` seul (mode `0700`, fichiers `0600`).

| Artefact | SHA-256 / contenu |
|---|---|
| APK installé avant mise à jour | `c7b0f71f…55a0`, 15 806 203 octets |
| Archive données applicatives | `115acfbd7c539cffc739ffa171750d450b87bf04efd0a4ec339f00ecc1a3f048`, 720 K, **26 fichiers** |
| Vérification de structure | extraction locale réussie, 26/26 fichiers |
| Permissions avant | 23 entrées capturées |
| Rôle HOME avant | `dev.atlas.coach` |

L'archive contient `shared_prefs`, `files`, `no_backup`, `databases` (vide) et les répertoires de cache. Elle n'a pas été réinjectée dans l'application active.

## 4. Installation

```
adb install -r <candidat app-debug.apk>
```

- Début : 2026-11-08T16:08:42Z (17:08:42 local)
- Fin : 2026-11-08T16:08:51Z (17:08:51 local)
- Sortie : `Performing Streamed Install` / `Success`
- Code retour : 0
- Aucune option `-d`, aucun `adb uninstall`, aucun `pm clear`, aucun changement de rôle.

## 5. Vérifications après installation

| Contrôle | Résultat |
|---|---|
| APK installé après | SHA-256 `bb703ec7…f1dff`, taille 15 838 971 octets — **identique au candidat** |
| Certificat après | `da301466…eeee` |
| `versionCode` / `versionName` | 1 / 0.1.0 |
| `firstInstallTime` (user 0) | **2026-10-28 18:15:55 — inchangé** |
| `lastUpdateTime` | 2026-11-08 17:08:52 local |
| Permissions | 23/23 identiques (`diff` vide) ; notifications, localisation et calendrier restent refusés ; réseau local reste accordé |
| Rôle HOME | `dev.atlas.coach` |
| Archive données après installation | SHA-256 `115acfbd…f048` — **byte-identique à l'avant** |
| Fichiers après installation | 26/26, liste et hashes identiques |
| Démarrage à froid `MainActivity` | `Status: ok`, `LaunchState: COLD`, 399 ms, activité au premier plan |
| Intent HOME | `LauncherActivity`, 101 ms, activité au premier plan |
| Erreurs application | aucune ligne FATAL/AndroidRuntime/Keystore/ANR pour le PID applicatif |
| Processus après vérification | actif |

Après démarrage, les seuls écarts de données sont attendus :

- `files/profileInstalled` : marqueur binaire du `ProfileInstaller` ART (24 octets), réécrit à l'installation du profil ;
- `no_backup/androidx.work.workdb-shm` et `-wal` : journaux WorkManager au démarrage ; la base principale `workdb` est inchangée ;
- **15/15 fichiers `shared_prefs` identiques**, y compris `atlas_location_consent.xml`, `atlas_sync_configuration.xml`, `atlas_core_v4_sync.xml`, `atlas_agent_revocation_barrier.xml`.

## 6. État live

| Composant | État |
|---|---|
| `atlas-core-atlas-core-1` | `restarts=0`, démarré 2026-11-07T10:05:46Z, health HTTP 200 |
| `atlas-v7-relay` | `restarts=0`, démarré 2026-11-07T10:05:46Z |
| Ports | 8100 et 4402 en écoute ; 4400/4401 absents |
| Base live (lecture seule) | `user_version=3`, `integrity_check=ok`, `agent_proposals_v4=3`, `consent_states_v4=2` ; aucune migration, aucune promotion, aucune révocation |

## 7. Rollback disponible (non exécuté)

- APK antérieur conservé : `c7b0f71f…55a0`.
- Données avant installation : archive `115acfbd…f048` (26 fichiers).
- Procédure : `adb install -r <installed-before.apk>` (même package, même certificat, même `versionCode`) ; les données sont préservées par une mise à jour in-place.
- Le rollback n'a pas été exercé sur le Pixel dans ce paquet, afin de ne pas multiplier les écritures non nécessaires. Il a été validé sur AVD lors de V7-11A.

## 8. Limites et réserves

1. **Keystore non introspectable sans root** : aucune erreur Keystore n'apparaît et `atlas_core_v4_sync.xml` est inchangé, mais le déchiffrement fonctionnel du credential n'est pas prouvé par une opération de synchronisation authentifiée.
2. **QA fonctionnelle complète NOT_RUN** : focus, coach, notifications, localisation, mobilité, rotation, réseau coupé, TalkBack et parcours HOME étendus ne sont pas couverts par ce paquet.
3. **APPLY A0 toujours BLOCKED** : aucune projection Android réelle ou synthétique n'a été appliquée ; l'APK candidat reste un build debug `debuggable`.
4. **Autres utilisateurs Android** (identifiants 12 et 13 dans `dumpsys`) non examinés et non modifiés ; le périmètre est l'utilisateur 0.
5. **Aucun test de restauration réelle** de l'archive sur l'appareil : seule la structure de l'archive a été vérifiée par extraction locale.
6. **Observation de stabilité courte** (quelques minutes) ; aucun test prolongé ni redémarrage de l'appareil.

## 9. Sanitisation

Aucun numéro de série, bearer, token, secret, contenu de préférence personnelle, nom d'objectif ou identifiant Relay n'apparaît dans ce document. Les horodatages et hashes sont conservés pour la traçabilité.
