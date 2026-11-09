# V7-11B — Vérification Pixel après migration v5

**Date :** 2026-11-09
**Autorisation :** l'utilisateur a choisi « Reconnecter le Pixel et vérifier la sync v5 », sans nouveau consentement ni changement de bearer.
**Périmètre :** lecture seule sur le Pixel et le Core ; aucun changement de permission, de réseau, de rôle, de consentement ou de credential.

## 1. Verdict

| Élément | Résultat |
|---|---|
| Pixel détecté | **PASS** — un appareil, Android 17 / API 37 |
| APK installé | **PASS** — `bb703ec7…`, version 1/0.1.0, certificat `da301466…eeee` |
| Rôle HOME | **PASS** — `dev.atlas.coach`, `LauncherActivity` au premier plan |
| Données applicatives | **PASS** — 15/15 noms `shared_prefs` présents, permissions 23/23 identiques |
| Crash/ANR | **PASS** — aucun FATAL/ANR pour l'app |
| Core v5 | **PASS** — health 200, v5, intégrité ok, FK 0 |
| **Sync Android → Core v5** | **BLOCKED / NOT_RUN** — aucun chemin réseau disponible |

La sync n'a pas échoué côté Core : elle n'a **jamais été tentée avec succès** faute de chemin réseau. Aucune requête applicative n'apparaît dans les logs Core depuis la migration.

## 2. État Pixel

| Contrôle | Résultat |
|---|---|
| Appareil | Pixel 9 Pro XL, API 37, utilisateur 0 ; identifiant non publié |
| Package | `dev.atlas.coach`, `versionCode=1`, `versionName=0.1.0` |
| APK | SHA-256 `bb703ec770f4ebe7e20602db65fecfb75e7a783d1a30040f5f7d8796c29f1dff` |
| Certificat | `da301466…eeee` |
| HOME | `dev.atlas.coach` |
| Processus | actif ; `LauncherActivity` au premier plan |
| `shared_prefs` | 15/15 noms présents (identiques au backup d'installation) |
| Permissions | 23/23 identiques au backup d'installation |
| Erreurs | aucune ligne FATAL/ANR/Keystore pour l'app |

Le fichier de synchronisation applicatif a été réécrit localement vers 12:07Z, mais aucune requête correspondante n'a atteint le Core : ce n'est pas une preuve de sync distante.

## 3. Chemin réseau — cause du BLOCKED

| Constat | Détail |
|---|---|
| Pixel | mode avion actif ; Wi-Fi connecté et validé ; **aucun VPN actif** |
| Tailscale mobile | nœud `pixel-9-pro-xl` **hors ligne**, dernière vue il y a 34 jours |
| Core | écoute uniquement sur `127.0.0.1:8100` |
| Relay | écoute uniquement sur `127.0.0.1:4402` |
| Funnel Tailscale | `8443 → 127.0.0.1:4400` ; **proxy 4400 absent** |
| Tailscale Serve | `/ → 127.0.0.1:8770` ; ne sert pas le Core |
| Proxy/connecteur PC | absents (aucun listener 4400/4401) |
| Requêtes app dans le Core | **aucune** (hors `/health` et le test legacy synthétique) |
| Job de sync | `CoreV4SyncWorker` planifié mais non exécuté (latence restante) |

Aucune modification réseau n'a été faite : le mode avion, le Wi-Fi, Tailscale, le Funnel, le Serve et les services 4400/4401 sont restés en l'état.

## 4. Ce qui n'a pas été testé

- Sync Android avec le Core v5.
- Rafraîchissement des consentements et des propositions via l'app.
- Chat Coach/OpenClaw réel.
- Keystore au-delà de l'absence d'erreur (clé non exportable).
- APPLY, backfill, révocation, changement de bearer.

## 5. Gates

| Gate | Statut |
|---|---|
| `GO_V7_11_ANDROID_POST_MIGRATION_SYNC` | **BLOCKED / NOT_RUN** — chemin réseau absent |
| `GO_V7_11_IDENTITY_BINDING` | **NOT_RUN** — auth legacy, aucun principal/binding |
| `GO_V7_11_LIVE_CHANGE_READINESS` | **NO-GO** — cutover complet |

## 6. Conditions pour débloquer

Une autorisation distincte est nécessaire pour l'un des chemins suivants :

1. exposer le Core au tailnet (Tailscale Serve sur `8100`, tailnet uniquement), puis relancer la vérification avec l'app ;
2. démarrer le proxy 4400 et le connecteur 4401 avec leur configuration/identité, puis vérifier le chemin Relay ;
3. confirmer que l'application doit rester locale et que la sync distante est hors périmètre.

Aucune de ces actions n'est couverte par la présente preuve. Le Core v5, les backups et l'image finale restent en place.

## 7. Sanitisation

Aucun numéro de série, SSID, adresse IP, nom de tailnet, bearer, token, clé privée, contenu de préférence ou identifiant de proposition n'apparaît dans ce document. Seuls états, compteurs, noms de fichiers génériques et statuts sont publiés.
