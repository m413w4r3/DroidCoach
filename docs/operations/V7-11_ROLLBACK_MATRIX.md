# V7-11 — Matrice de rollback

**Statut : plan audité, non exécuté. Aucune restauration live, aucun rollback Pixel.**
**Source :** [V7-11_A6_CUTOVER_GATES.md](evidence/V7-11_A6_CUTOVER_GATES.md), [V7-11_LIVE_INVENTORY.md](evidence/V7-11_LIVE_INVENTORY.md).

## Règles générales

- Ne jamais utiliser `pm clear`, `adb uninstall`, `docker compose down -v`, `git reset --hard`, un force-push ou une suppression de table/colonne.
- Toute restauration SQLite se fait sur une copie neuve vérifiée, après arrêt du writer, avec `integrity_check`, `foreign_key_check`, `user_version` et comparaison des comptes.
- Le Keystore Android n'est pas exportable : une réinstallation/restauration de fichiers ne restaure pas les clés.
- Une révocation est monotone : ne jamais réactiver un credential/principal/binding révoqué.
- **P1 legacy** : le mode `ATLAS_V4_AUTH_MODE=legacy` compare directement le bearer à `ATLAS_TOKEN` et ignore le registre. Après révocation, ne jamais revenir en legacy avec l'ancien token ; faire tourner `ATLAS_TOKEN` ou rester en `principal` fail-closed.

## Matrice

| Cas / déclencheur | Action autorisée | Preuve | Risque résiduel | État utilisable |
|---|---|---|---|---|
| **Avant dual** — staging, backup ou copie échoue | Jeter uniquement la copie/les fichiers staged ; conserver DB/config live ; vérifier hashes, intégrité et santé actuelle. | Hashs, `integrity_check`, FK, comptes ; santé Core/Relay. | Aucune mutation si les consignes sont suivies ; artefacts privés à protéger. | Produit actuel en legacy. |
| **Après dual, avant révocation** — auth/Relay échoue | Arrêter les clients V4 Relay ; arrêter Core/connecteur par leur superviseur exact ; restaurer la config précédente ou revenir explicitement en `dual` (voire `legacy` seulement si l'ancien bearer est encore ACTIVE et le risque accepté). Conserver le schéma v4/v5. | `health`, token attendu, comptes DB, logs redacted. | Revenir en legacy réouvre le bearer partagé ; écritures post-snapshot perdues ; backfill LEGACY immuable. | Local/offline ; remote LEGACY en dual/legacy sous risque accepté. |
| **Après installation APK, avant révocation** — crash, identité refusée, donnée manquante | Ne pas désinstaller ni `pm clear`. Conserver l'app et l'état défectueux ; couper le réseau ; réinstaller l'APK précédent avec `adb install -r` si package/cert/version compatibles. Restaurer les données seulement après avoir sauvegardé l'état en échec et prouvé le schéma de restauration. | Version/cert/package, hashes des données, logs. | Le retour APK ne défait pas les migrations de données ; une restauration perd les écritures post-backup ; HOME/permissions/Keystore non restaurés. | UI/offline si l'app démarre ; remote legacy seulement si credential encore active. |
| **Après passage principal, avant révocation** — LEGACY refusé ou nouvel ID non accepté | Si l'ancien credential est encore ACTIVE, arrêter le remote et revenir explicitement au dual staged ; sinon rester fail-closed/local. Réparer principal/map/binding avec une credential neuve, sans réattribuer l'historique. | Refus LEGACY, auth nouveau principal, comptes, intégrité. | Le mode principal peut couper le remote ; le retour dual réouvre le bearer legacy jusqu'à révocation. | Local/offline ; remote dual après validation, ou principal après correction. |
| **Après révocation credential/bearer** — nouveau chemin échoue | Ne pas réimporter/réactiver l'identifiant ni restaurer une DB pré-révocation active. Préserver la DB affectée et WAL/SHM ; restaurer un snapshot contrôlé sur copie neuve ; réappliquer les tombstones ; vérifier int/FK/version ; émettre un credential neuf ; mettre à jour map/binding. | Tombstone réappliqué, `integrity_check`, FK, refus ancien token, acceptation nouveau. | Fenêtre d'écritures post-snapshot perdue ; un retour legacy avec l'ancien `ATLAS_TOKEN` contourne le tombstone (P1). | Local/offline, ou remote principal via credential neuve. Aucun fallback ancien. |
| **Après rotation du credential Relay** — map/credential divergent | Garder Core en `principal` ; générer une credential Relay neuve (jamais réutiliser l'ancienne révoquée), mettre à jour map et vérifier audience/binding ; ne pas restaurer l'ancienne DB active. | `check-config`, `check-secret`, auth Relay, hash map. | Une panne map/credential coupe le Relay V4 fail-closed. | Direct/local possible ; Relay indisponible jusqu'à correction. |
| **Après révocation du binding Relay** — `pixel-v7` refusé | Le code interdit la réactivation et `binding add` refuse un `relay_device_id` tombstoné. Créer une identité Relay nouvelle et un binding neuf après approbation, ou procédure de migration de données revue. | Nouvel appairage, nouveau binding, auth. | L'historique lié à l'ancien `device_ref` ne suit pas automatiquement ; réappairage physique possible. | Local/offline jusqu'au nouvel appairage ; aucune reprise garantie sur `pixel-v7`. |
| **Migration/backfill/expiration interrompu** | Arrêter le writer exact ; préserver la DB affectée + WAL/SHM ; restaurer le backup vérifié sur un chemin neuf ; contrôler `PRAGMA integrity_check`, `foreign_key_check`, `user_version`, checksums et comptes avant promotion. Si après révocation, réappliquer les tombstones. | Backup vérifié, checksums, comptes avant/après. | Écritures depuis le snapshot perdues ; `--apply` expiration par lots non globalement atomique ; `UNCERTAIN` à résoudre explicitement. | Core arrêté/offline jusqu'à preuve ; puis remote selon le mode restauré. |
| **Perte/désinstallation/nouvel appareil (Keystore)** | Le ciphertext Android reste inutilisable sans alias Keystore. Réinstaller/restaurer les fichiers ne restaure pas la clé ; conserver l'archive, redemander configuration/appairage et consentements actuels, émettre un nouveau credential. | Nouvel appairage, consentements frais, audit. | Perte des tokens backend/Relay chiffrés, état système/HOME/permissions non restaurés ; historique serveur non réattribué automatiquement. | App utilisable localement après première config ; remote après réauthentification et consentement neuf. |
| **Installation OK, Core refuse l'identité** | Arrêter les retries distants ; ne pas exposer/retaper les secrets ; conserver l'app et les données ; vérifier en redacted audience/principal/binding/credential. Avant révocation, rollback dual si autorisé ; après révocation, credential neuve uniquement. | Refus 401, logs redacted, état local. | Chat/sync distant indisponible ; fail-closed correct. | Mode local/offline, launcher et pause ; remote après correction. |
| **Retour à legacy demandé** | Avant révocation : possible seulement si la credential partagée est encore ACTIVE, changement contrôlé et expirant. Après révocation : **ne pas** basculer en legacy avec l'ancien `ATLAS_TOKEN` ; le code legacy ignore le tombstone. Si un bearer d'urgence est nécessaire, faire tourner le secret, auditer l'impact V1–V3 et les `device_ref` historiques. | Rotation `ATLAS_TOKEN`, test ancien token refusé, audit V1–V3. | Réactivation accidentelle du bearer révoqué si l'ancienne variable reste montée ; un nouveau bearer legacy ne récupère pas l'historique. | Pré-révocation : legacy temporaire avec risque P1. Post-révocation : principal neuf ou local seulement. |

## Rollback par étape du handoff V7-10

| Étape | Rollback |
|---|---|
| 1–4 (inventaire, backup, restore-test) | Aucun changement live ; jeter les copies. |
| 5 (provisionnement sur copie) | Jeter la copie ; jamais de tombstone réactivé. |
| 6 (dual) | Arrêt Core, restauration config précédente ou retour contrôlé en legacy si credential encore ACTIVE ; schéma conservé. |
| 7 (backfill) | Ne pas promouvoir la copie si un ACK/compte diffère ; restaurer le backup vérifié sur copie neuve. |
| 8 (Relay dual) | Arrêter le connecteur, restaurer map/config précédente, redémarrer le superviseur exact. |
| 9 (APK) | Réinstaller l'APK précédent avec `-r` si compatible ; ne pas désinstaller. |
| 10 (tests A/B) | Arrêter le remote, garder Core dual, isoler les fichiers ; ne pas passer en principal. |
| 11 (principal) | Avant révocation, retour dual possible si l'ancien credential est ACTIVE et le risque accepté ; sinon rester fail-closed. |
| 12 (révocation) | Aucune réactivation ; snapshot neuf + tombstones + credential neuve. |
| 13 (clôture) | Ne pas lancer le pilote si une gate est NOT_RUN. |

## Verdict

`GO_V7_11_BACKUP_ROLLBACK_PLAN` : **GO local avec limitations** — le plan est audité et couvre les cas majeurs, mais aucun backup/restore Pixel ni snapshot live n'a été exécuté. Les points P1 (legacy/tombstone, Relay administrable, identité/binding, anti-rejeu) restent bloquants pour `GO_V7_11_LIVE_CHANGE_READINESS`.
