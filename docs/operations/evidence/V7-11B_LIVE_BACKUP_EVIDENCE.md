# V7-11B — Fenêtre de backup live et archivage du sidecar

**Date :** 2026-11-08
**Autorisation :** l'utilisateur a choisi le paquet « Fenêtre de backup live + sidecar ». Périmètre : arrêt temporaire de Core, backup Pixel/server/connecteur, vérification et déplacement de l'ancien `.pre-v4.bak`, tests de restauration sur copies. **Aucune migration live, aucun changement de bearer, aucune révocation, aucune promotion de copie.**
**Opérateur :** parent Paseo, seul opérateur ADB et Docker. Relay jamais arrêté.

## 1. Verdict

| Élément | Résultat |
|---|---|
| Backup Pixel V7-07 (preflight/backup/verify) | **PASS** — archive `7e5908cd…`, 25 fichiers |
| Backup Core cohérent à l'arrêt | **PASS** — DB `752919f2…`, v3, intégrité ok, FK vide |
| Backup connecteur (3 fichiers + secrets) | **PASS** — 30 entrées, 3/3 restaurées à l'identique sur copie |
| Sidecar stale vérifié et archivé | **PASS** — `user_version=0`, archivé `0600`, chemin exact absent |
| Restauration testée sur copies | **PASS** — Core v3/intègre, connecteur 3/3, Pixel archive vérifiée |
| Retour du service | **PASS** — Core health 200 après 14 s, Relay health 200 sans arrêt |
| Migration live | **NOT_RUN** — hors périmètre |

## 2. Fenêtre et disponibilité

| Événement | Horodatage UTC |
|---|---|
| Ouverture de la fenêtre / racine de backup | 2026-11-08T20:28:23Z |
| Backup Pixel V7-07 | dans la fenêtre |
| Arrêt Core (`docker stop -t 30`) | 2026-11-08T20:29:04Z, exit 0 |
| Redémarrage Core (`docker start`) | 2026-11-08T20:29:18Z |
| Indisponibilité Core | **~14 s** |
| Relay | jamais arrêté, `StartedAt=2026-11-07T10:05:46.958Z`, `restarts=0` |
| Connecteur | inactif avant/pendant/après (aucun processus, aucun listener 4400/4401) |

## 3. Pixel — backup app-only

Outil `scripts/v7-07/atlas-product-backup.sh`, exécuté avec un seul appareil ADB, SDK 37, APK exact `bb703ec7…`.

| Contrôle | Résultat |
|---|---|
| `preflight` | **PREFLIGHT_OK** — `dev.atlas.coach`, version 1/0.1.0, certificat `da301466…eeee`, SDK 37 |
| `backup` | **BACKUP_OK** — archive `7e5908cdaf60940934d8ecafd7a8784f7862c93bc04df60fac2ef795c47ea06b`, 25 fichiers |
| `verify` | **VERIFY_OK** — archive/manifeste cohérents |
| Manifeste | `2ac75fa7f7c1a7346787b5d1a0f97392ad1c5ef2856f528e794cb0f6faa4aedb` |
| Contenu | 15 `shared_prefs`, 7 `files`, 3 `no_backup` ; caches exclus |
| Modes | répertoire `0700`, archive/manifeste `0600` |
| Après fenêtre | ATLAS relancé, `LauncherActivity` au premier plan, rôle HOME conservé, 15/15 noms `shared_prefs` toujours présents |

Aucune restauration sur l'appareil. Le Keystore Android n'est pas exportable.

## 4. Core — copie cohérente et sidecar

| Contrôle | Résultat |
|---|---|
| DB avant arrêt | `752919f29d0abe17ee1bbb67508fecb0116dbd7946df404856264aefe4b9062a` |
| DB après arrêt | **même SHA-256** `752919f2…`, 249 856 octets |
| Version / intégrité / FK | v3 / `ok` / 0 violation |
| Compteurs | 3 propositions V4, 2 événements, 2 consentements, 1 ligne historique |
| Ancien sidecar | `b8210d25283385ffbd2fd666fd1fb55e1aa9c6de4847f1a731fac41629cc4ceb`, 65 536 octets, `user_version=0`, intégrité ok |
| Déplacement | renommé dans le volume en `atlas.sqlite3.pre-v4.bak.v7-11b-archive`, mode **0600** |
| Chemin exact | **absent** après déplacement |
| Redémarrage | health 200, DB v3 intègre, `restarts=0` (arrêt/démarrage manuel), `StartedAt=2026-11-08T20:29:18.855Z` |
| Manifeste Core | `e4c9ac0f8be7c6e12100fdd7c302ef7cc0b7009d9e3acab48819ee03973d3829` |
| Inspect Core filtré | `8a7f7be001fc23ef8a2a56dedad3378b7d5b9a75a5fc23edd275531f6dba9030` |

L'ancien sidecar est en `user_version=0` : ce n'est **pas** une sauvegarde pré-migration v3 valide. Il est conservé pour provenance, mais ne doit jamais être utilisé comme rollback. Le chemin exact étant absent, le futur Core corrigé créera un nouveau backup `0600` lors de la migration.

## 5. Relay — snapshot sans arrêt

- Conteneur `atlas-v7-relay`, image `atlas-paseo-relay:r3a`, jamais arrêté.
- `StartedAt=2026-11-07T10:05:46.958Z`, `RestartCount=0`, health **200** avant/après.
- Aucun montage ; état probablement volatile ; snapshot limité aux métadonnées filtrées (`a6ec1ae46b0a92bc5e863d0e6d1fcd2edf85d70a7a7a853598f4c8764a19540e`).
- Aucune recréation, aucun `start-relay.sh`, aucune modification.

## 6. Connecteur — backup complet et test de restauration

Le connecteur n'était pas en cours d'exécution. Son état a été copié à froid, sans arrêt de service.

| Contrôle | Résultat |
|---|---|
| État copié | 30 entrées, dont 3 fichiers d'état principaux, une configuration credential et des archives historiques |
| Manifeste | `469fb0432995d5ad4d797cf970324ce12fc3872979ee2f2f5d922383023aedb7` |
| Configuration credential | `46106b8cb9cdddb139a1f45a4cd255061cece3b72b9cecd157bc7d51dfaa33de` |
| Identité live = backup | `cf172580f8cfa8e2ebea936e268857e9aa006923cf265e57f8fc78516d47f926` |
| Registre live = backup | `d27a6bede136747f0ac01d6747964146f6e314c65864a7adf354b89a0ed78a9d` |
| Journal live = backup | `7946db8ada4c0ec32182b30bd8373286dbef68e62e3c4ad843160f915eb30f01` |
| Restauration sur copie | **3/3 fichiers identiques** (manifeste source/restauré `1a0946a3…`) |
| Modes | état `0700`, fichiers `0600` |
| Mutation live | aucune : aucun processus, aucun démarrage/arrêt |

L'état contient des credentials/tokens ; ils restent dans le backup privé et ne sont pas publiés.

## 7. Backup global

| Contrôle | Résultat |
|---|---|
| Racine privée | `0700`, hors dépôt |
| Fichiers | `0600`, aucun world-readable |
| Taille | 427 K |
| Manifeste global | `73a173ce714b3db0d1e14c88aa2e4d27d48534d5a0a9893d34d36c6a3167dade` |
| Couverture | 14 fichiers : 10 artefacts principaux + 4 auxiliaires (1 digest, 3 logs) |
| Copies de test temporaires | supprimées après capture des hashes |

## 8. État final

| Composant | État |
|---|---|
| Core | running, health 200, DB v3, 3 propositions, 0 erreur récente |
| Relay | running, health 200, `restarts=0`, démarré le 2026-11-07 |
| Pixel | ATLAS au premier plan, rôle HOME, 15/15 `shared_prefs` présents |
| Sidecar | chemin exact absent ; archive `0600` présente |
| Connecteur | inactif, état inchangé |
| Logs | 0 ligne error/traceback/fatal Core et Relay depuis le redémarrage |

## 9. Limites

1. Le backup est **local au même hôte** ; pas de copie hors site ni de chiffrement au repos. Le filesystem home affiche 56 G libres (97 % utilisé).
2. La DB live reste en mode `0644` (P2 antérieur) ; seule la copie de backup est `0600`.
3. Le backup connecteur contient des tokens/secrets ; il doit rester privé et ne pas être copié vers le dépôt.
4. Le Relay n'a pas été arrêté ni sauvegardé au-delà des métadonnées : état volatile, recréation non testée.
5. Aucune restauration live n'a été effectuée ; seules des copies ont été vérifiées.
6. Le sidecar archivé est en `user_version=0` et ne vaut pas rollback pré-migration.
7. Le backup Pixel est app-only, sans Keystore ni APK système ; le Keystore reste non exportable.
8. L'indisponibilité Core a été d'environ 14 s ; les écritures pendant la fenêtre sont celles du redémarrage, pas une migration.

## 10. Gates

| Gate | Statut |
|---|---|
| `GO_V7_11_LIVE_BACKUP` | **GO local** — backup exécuté et vérifié |
| `GO_V7_11_BACKUP_ROLLBACK_PLAN` | **GO local avec limites** — restauration testée sur copies, pas en live ; pas de copie hors site |
| `GO_V7_11_LIVE_CHANGE_READINESS` | **NO-GO** — migration live, binding, Relay admin et autorisation nominative restent à faire |
| `GO_V7_11_REAL_COPY_MIGRATION` | GO local final (inchangé) |

## 11. Sanitisation

Aucun numéro de série, bearer, token, clé privée, contenu de préférence, identifiant de proposition, `device_ref`, nom de fichier de credential ou chemin personnel n'apparaît dans ce document. Seuls hashes, compteurs, modes, horodatages et statuts sont publiés. Le chemin du répertoire de backup reste privé et n'est pas reproduit.
