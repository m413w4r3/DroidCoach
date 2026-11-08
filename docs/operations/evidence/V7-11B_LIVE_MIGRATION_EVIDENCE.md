# V7-11B — Migration live v3→v5 et déploiement de l'image finale

**Date :** 2026-11-08
**Autorisation :** l'utilisateur a choisi « Migrer le live v3→v5 maintenant », en acceptant l'expiration de la proposition `PENDING_ANDROID` au premier démarrage.
**Périmètre :** arrêt Core, backup frais v3, déploiement de l'image finale, migration du volume live, vérification, rollback documenté. **Aucun changement de bearer, aucune révocation, aucun APPLY, aucun backfill/expiration manuel, aucune modification Relay.**

## 1. Verdict

| Élément | Résultat |
|---|---|
| Backup frais v3 à l'arrêt | **PASS** — `9f5c8fd2…`, integrity ok, FK 0, compteurs 3/2/2/4/1 |
| Déploiement image finale | **PASS** — conteneur recréé sur `sha256:1e4c8e6c…` |
| Migration live | **PASS** — `user_version=5`, ledger `[1..5]`, integrity ok, FK 0 |
| Backup B7 automatique | **PASS** — v3, `0600`, intégrité ok, `193fcae4…` |
| Verrou / cache anti-rejeu | **PASS** — lock `0600` ; `/data/replay` `0700` |
| Legacy | **PASS** — token live 200, faux token 401 |
| Relay | **PASS** — health 200, jamais arrêté |
| OpenClaw | **PASS** — adapter et capabilities préservés, aucune erreur de démarrage |
| Sync Android post-migration | **NOT_RUN** — Pixel déconnecté d'ADB |
| Binding/principal | **NOT_RUN** — hors périmètre |

## 2. Fenêtre

| Événement | Horodatage UTC |
|---|---|
| Backup frais / arrêt Core | 2026-11-08T22:45:25Z (exit 0) |
| Démarrage du nouveau Core | 2026-11-08T22:45:41Z |
| Indisponibilité Core | **~16 s** |
| Relay | jamais arrêté, `StartedAt=2026-11-07T10:05:46.958Z`, `restarts=0` |
| App/Pixel | déconnecté d'ADB ; sync post-migration non observable |

## 3. Backup frais avant migration

| Contrôle | Résultat |
|---|---|
| DB source | `9f5c8fd24e816ad105ce852db75fedc0b1423a9a4f30e96b1bc9c055e3d5ca55` |
| Version / intégrité / FK | v3 / `ok` / 0 |
| Compteurs | 3 propositions V4, 2 événements, 2 consentements, 4 événements de consentement, 1 historique |
| États | APPLIED 1 / PENDING_ANDROID 1 / REJECTED 1 |
| Sidecar archivé | `b8210d25…`, `user_version=0`, conservé |
| Emplacement | répertoire privé `0700`, fichiers `0600`, hors dépôt |

## 4. Déploiement

- Image : `sha256:1e4c8e6c599a3846e6870143b78cf3d39ae9d2da050db04e26d7cb3af8ecd35a`, 150 520 138 octets, construite à `7efbefc`.
- Compose : `compose.yaml` + `compose.openclaw.yaml` + override privé persistant (`0600`) ajoutant l'image finale, `ATLAS_V4_REPLAY_CACHE_DIR=/data/replay` et `ATLAS_V4_AUTH_MODE=legacy`.
- Config préservée : `network_mode: host`, `read_only: true`, tmpfs, volume `atlas-core-data:/data`, les trois secrets Compose existants (Atlas, outil, Gateway), `cap_drop: ALL`, `no-new-privileges`, `restart: unless-stopped`, healthcheck.
- Ancienne image/tag `atlas-core-atlas-core` = `sha256:8604535e…`, **conservée** pour rollback.
- Adapter OpenClaw et `ATLAS_V4_CAPABILITIES_JSON` préservés ; aucune valeur de secret affichée.

## 5. État après migration

| Contrôle | Résultat |
|---|---|
| `user_version` | **5** |
| Ledger | `[1,2,3,4,5]` |
| Intégrité / FK | `ok` / 0 |
| Propositions V4 | 3 |
| États | **APPLIED 1 / EXPIRED 1 / REJECTED 1** |
| Événements de proposition | 2 |
| Événements d'expiration | **1** |
| Consentements | 2 états / 4 événements |
| Historique | 1 |
| Hash DB migrée | `9f2cd235…` |
| Backup B7 | `atlas.sqlite3.pre-v4.bak`, 249 856 octets, **0600**, v3, intégrité ok, `193fcae4…` |
| Verrou | `atlas.sqlite3.lock`, **0600** |
| Cache | `/data/replay`, **0700** |
| Health | **200** |
| Legacy | token live **200** / faux token **401** |
| Erreurs Core | 0 traceback/exception/fatal/error depuis le démarrage |
| Relay | health **200**, `restarts=0`, `StartedAt` inchangé |

La proposition `PENDING_ANDROID` est passée à `EXPIRED` au premier démarrage, comme autorisé. Le backup v3 pré-migration conserve l'état APPLIED/PENDING_ANDROID/REJECTED.

## 6. Rollback disponible (non exécuté)

- Image précédente conservée : `sha256:8604535e…` (tag `atlas-core-atlas-core`).
- Backup frais v3 : `9f5c8fd2…` (privé, `0600`).
- Backup automatique B7 : `193fcae4…` (v3, `0600`), dans le volume.
- Procédure : arrêter le Core, restaurer la DB v3 depuis le backup frais dans le volume, redémarrer via Compose avec l'image précédente, vérifier v3/intégrité/compteurs/health. Toute écriture post-backup serait perdue.
- Le rollback n'a pas été exercé.

## 7. Limites

1. **Pixel/ADB déconnecté** : la synchronisation Android post-migration, le rôle HOME, le Keystore et les consentements ne sont pas vérifiés après migration.
2. **Binding/principal non configurés** : la base est en v5 mais l'authentification reste legacy ; aucun principal, credential ou binding n'a été créé. Aucun APPLY, backfill ou révocation.
3. **OpenClaw** : adapter et capabilities conservés, mais aucun tour de chat réel n'a été exécuté après migration.
4. **Backup local uniquement** : pas de copie hors site ni chiffrement au repos ; DB live toujours en mode `0644`.
5. **Relay non administré** : toujours sans recréation testée ; health 200 mais état volatile.
6. **Anti-rejeu** : cache durable configuré (`/data/replay`, `0700`) mais aucune assertion Relay live n'a été exercée.
7. **Fenêtre courte** : ~16 s d'indisponibilité ; aucune garantie sur les écritures concurrentes d'un client non observé.

## 8. Gates

| Gate | Statut |
|---|---|
| `GO_V7_11_LIVE_MIGRATION` | **GO local** — migration v3→v5 exécutée et vérifiée |
| `GO_V7_11_SCHEMA_V5_LIVE` | **GO local** — v5, ledger, intégrité, FK, backup |
| `GO_V7_11_ANDROID_POST_MIGRATION_SYNC` | **NOT_RUN** — Pixel déconnecté |
| `GO_V7_11_IDENTITY_BINDING` | **NOT_RUN** — inchangé, legacy |
| `GO_V7_11_LIVE_CHANGE_READINESS` | **NO-GO** pour le cutover complet (binding, Relay, sync Android, hors-site) |

## 9. Sanitisation

Aucun numéro de série, bearer, token, clé privée, contenu de proposition, `device_ref`, identifiant de ligne ou chemin personnel n'apparaît dans ce document. Seuls hashes, compteurs, modes, horodatages et statuts sont publiés.
