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
| **Sync Android → Core v5** | **BLOCKED / PARTIAL** — chemin activé, aucune requête émise par l'app |

La sync n'a pas échoué côté Core : aucune requête applicative n'a été émise. Après activation du chemin Funnel/connecteur et exécution forcée du worker, l'app n'a toujours envoyé aucune requête. Le blocage est désormais **côté déclenchement/configuration de l'app**, pas côté Core.

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
| Requêtes app dans le Core | **aucune** (hors `/health` et le test legacy synthétique) |
| Job de sync | `CoreV4SyncWorker` exécuté via job 253 (`Running job [FORCED]`), puis replanifié ; aucune requête |

Seul le mode avion a été désactivé par l'utilisateur. Le proxy et le connecteur ont ensuite été démarrés à la demande, sans modification du bearer, du Relay ni de la configuration Core. Aucun réglage Wi-Fi/Tailscale/Funnel n'a été changé.

### 3.1 Activation du chemin Funnel et tentative de sync

- Proxy 4400 : health 200, WebSocket upgrade 101, amont Relay 4402.
- Connecteur 4401 : metrics 200, `relay_connected=true`, `errors_total=0`.
- `MainActivity` ouverte ; job `CoreV4SyncWorker` forcé ; le job a démarré puis a été replanifié.
- Aucune requête non-health dans le Core, aucun `requests_total` côté connecteur, aucune réécriture de `atlas_core_v4_sync.xml` (mtime inchangé à 12:07Z).
- Conclusion : le chemin est sain, mais l'app n'émet pas de requête de sync (absence de données en attente, endpoint/trigger non aligné, ou sync non déclenchée). Le Core v5 n'est pas en cause.
- Le proxy et le connecteur restent actifs mais ne sont pas supervisés ; ils s'arrêteront à la fin de session/au redémarrage. Aucun bearer ni Relay n'a été modifié.

## 4. Ce qui n'a pas été testé

- Sync Android avec le Core v5.
- Rafraîchissement des consentements et des propositions via l'app.
- Chat Coach/OpenClaw réel.
- Keystore au-delà de l'absence d'erreur (clé non exportable).
- APPLY, backfill, révocation, changement de bearer.

## 5. Gates

| Gate | Statut |
|---|---|
| `GO_V7_11_ANDROID_POST_MIGRATION_SYNC` | **BLOCKED / PARTIAL** — chemin Funnel/connecteur sain, aucune requête app |
| `GO_V7_11_IDENTITY_BINDING` | **NOT_RUN** — auth legacy, aucun principal/binding |
| `GO_V7_11_LIVE_CHANGE_READINESS` | **NO-GO** — cutover complet |

## 6. Conditions pour débloquer

Le chemin Funnel/connecteur est désormais actif. Pour finaliser la vérification, il faut soit :

1. déclencher une action de sync côté app (écran/paramètre de synchronisation ou donnée en attente) et vérifier les requêtes Core/connecteur ;
2. confirmer l'endpoint attendu par l'app (le fichier de sync ne contient pas d'URL extractible) et l'aligner si nécessaire ;
3. exposer le Core au tailnet (Tailscale Serve `8100`) si l'app utilise ce chemin plutôt que le Funnel ;
4. confirmer que l'application doit rester locale et que la sync distante est hors périmètre.

Le Core v5, les backups, l'image finale, le proxy et le connecteur restent en place. Aucune permission, aucun consentement et aucun bearer n'ont été modifiés.

## 7. Sanitisation

Aucun numéro de série, SSID, adresse IP, nom de tailnet, bearer, token, clé privée, contenu de préférence ou identifiant de proposition n'apparaît dans ce document. Seuls états, compteurs, noms de fichiers génériques et statuts sont publiés.
