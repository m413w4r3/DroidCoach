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
| **Coach app → Core v5** | **PASS** — `POST /v2/agent/chat` **200** à 12:25:52Z, session connecteur active, réponse visible dans l'app |
| **CoreV4SyncWorker dédié** | **NOT_OBSERVED** — le job a tourné mais n'a pas émis de requête HTTP séparée |

La connexion app→Core v5 est **prouvée** par le chat Coach : l'utilisateur a écrit « test », le Core v5 a répondu `200` sur `/v2/agent/chat`, le connecteur a ouvert une session (1 requête, 0 erreur) et l'app a affiché la réponse. Le worker de synchronisation d'état n'a pas émis de requête séparée ; cela ne remet pas en cause la connectivité prouvée par le chat.

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
| Pixel | mode avion **désactivé par l'utilisateur** vers 12:13Z ; Wi-Fi connecté et validé ; **aucun VPN actif** |
| Tailscale mobile | paquet installé, nœud **hors ligne**, dernière vue il y a 34 jours |
| Core | écoute uniquement sur `127.0.0.1:8100` |
| Relay | écoute uniquement sur `127.0.0.1:4402`, inchangé |
| Funnel Tailscale | `8443 → 127.0.0.1:4400` ; proxy 4400 **actif** |
| Tailscale Serve | `/ → 127.0.0.1:8770` ; ne sert pas le Core |
| Proxy/connecteur PC | proxy 4400 **actif** (health 200) ; connecteur 4401 **actif** (`relay_connected=true`, 0 erreur) |
| Requêtes app dans le Core | **`POST /v2/agent/chat` 200** à 12:25:52Z (hors `/health` et test legacy synthétique) |
| Session connecteur | `connections_opened_total=1`, `sessions_opened_total=1`, `active_sessions=1`, `requests_total=1`, `errors_total=0`, latence ~13 s |
| WebSocket proxy | upgrades **101** à 12:25:19Z |
| Job de sync | `CoreV4SyncWorker` exécuté via job 253 (`Running job [FORCED]`), puis replanifié ; aucune requête HTTP séparée |
| État app | `atlas_local_state.xml` et `atlas_relay_configuration.xml` réécrits à 12:25Z ; réponse Coach visible |

Seul le mode avion a été désactivé par l'utilisateur. Le proxy et le connecteur ont ensuite été démarrés à la demande, sans modification du bearer, du Relay ni de la configuration Core. Aucun réglage Wi-Fi/Tailscale/Funnel n'a été changé.

### 3.1 Activation du chemin Funnel et preuve de connexion Coach

- Proxy 4400 : health 200, WebSocket upgrade 101, amont Relay 4402.
- Connecteur 4401 : metrics 200, `relay_connected=true`, `errors_total=0`.
- `MainActivity` ouverte ; l'utilisateur a envoyé « test » au Coach.
- Core v5 : **`POST /v2/agent/chat` 200** à 12:25:52Z.
- Connecteur : `connections_opened_total=1`, `sessions_opened_total=1`, `active_sessions=1`, `requests_total=1`, `errors_total=0`, latence ~13 s.
- Proxy : upgrades **101** à 12:25:19Z.
- App : `atlas_local_state.xml` et `atlas_relay_configuration.xml` réécrits à 12:25Z ; réponse Coach affichée.
- `CoreV4SyncWorker` forcé (job 253) : le job a démarré puis a été replanifié, sans requête HTTP séparée. Ce worker d'état n'est pas la preuve de connectivité ; le chat l'est.
- Le proxy et le connecteur restent actifs mais ne sont pas supervisés ; ils s'arrêteront à la fin de session/au redémarrage. Aucun bearer ni Relay n'a été modifié.

## 4. Ce qui n'a pas été testé

- Sync d'état du `CoreV4SyncWorker` en tant que requête HTTP séparée ; le chat Coach a prouvé la connectivité app→Core v5.
- Rafraîchissement des consentements et des propositions via l'app.
- APPLY, backfill, réconciliation d'expiration, révocation, changement de bearer.
- Keystore au-delà de l'absence d'erreur (clé non exportable).

## 5. Gates

| Gate | Statut |
|---|---|
| `GO_V7_11_ANDROID_POST_MIGRATION_SYNC` | **PASS avec limitation** — chat Coach `POST /v2/agent/chat` 200 et session connecteur ; worker d'état dédié non observé |
| `GO_V7_11_IDENTITY_BINDING` | **NOT_RUN** — auth legacy, aucun principal/binding |
| `GO_V7_11_LIVE_CHANGE_READINESS` | **NO-GO** — cutover complet |

## 6. Conditions restantes

Le chemin Funnel/connecteur est actif et la connexion Coach est prouvée. Les points suivants restent hors de cette preuve :

1. **Binding/principal V4** : auth legacy, 0 principal et 0 credential ; aucune identité/binding configurée.
2. **Relay/connecteur** : proxy et connecteur actifs mais non supervisés ; recréation Relay non testée, sauvegarde connecteur 3 fichiers non déployée.
3. **Backup** : local au même hôte, sans chiffrement au repos ni copie hors site ; DB live `0644`.
4. **Données** : 3 propositions (APPLIED/EXPIRED/REJECTED), aucun APPLY/backfill/réconciliation supplémentaire.
5. **Sync d'état dédiée** : worker non observé en requête HTTP séparée ; le chat Coach suffit à la connectivité, pas à qualifier chaque cycle de sync.

Le Core v5, les backups, l'image finale, le proxy et le connecteur restent en place. Aucune permission, aucun consentement et aucun bearer n'ont été modifiés.

## 7. Sanitisation

Aucun numéro de série, SSID, adresse IP, nom de tailnet, bearer, token, clé privée, contenu de préférence ou identifiant de proposition n'apparaît dans ce document. Seuls états, compteurs, noms de fichiers génériques et statuts sont publiés.
