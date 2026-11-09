# V7-12 — Migration vers la stack Docker et rollback

**Statut :** runbook opérateur. Les commandes exactes du wrapper sont complétées après intégration de `scripts/atlas-stack`.
**Principe :** une seule fenêtre de migration, sauvegardes fraîches avant toute coupure, rollback par couche, aucune suppression de volume.

## 1. Préconditions obligatoires

| Précondition | Contrôle |
|---|---|
| Branche et SHA | `git rev-parse HEAD` = SHA intégré V7-12, arbre propre. |
| CI | run GitHub vert sur le SHA final, y compris le harness V7-11 corrigé. |
| Stack isolée | health, HMAC, rejeu, révocation, redémarrage, persistance, backup/restore PASS. |
| Backup Core | snapshot cohérent, `PRAGMA integrity_check=ok`, `foreign_key_check` vide, `user_version=5`. |
| Backup connecteur | `identity.json`, `devices.json`, `operations.json` présents, 0600, JSON valides. |
| Backup OpenClaw | état complet, SQLite cohérente, configuration/agent/auth/workspace/clé préservés. |
| Volume | `atlas-core-data` existe, n'est ni renommé ni recréé. |
| Relay | 1 seul appareil actif `pixel-v7`, identité `identity.json` inchangée. |
| Gateway | `openclaw-gateway.service` actif, arrêtable proprement, état `~/.openclaw` intact. |
| Tailscale | Funnel `8443 → 127.0.0.1:4400`, aucun changement prévu. |
| Pixel | présent ou clairement absent ; les gates Pixel restent BLOCKED si absent. |

## 2. Sauvegardes avant coupure

1. Exécuter `./scripts/atlas-stack backup` (ou le fallback documenté) et vérifier la sortie.
2. Conserver le backup de sécurité `~/.local/state/atlas-stack/backups/pre-v7-12-*` créé le 2026-11-09.
3. Ne jamais restaurer un backup ancien sans afficher les écritures intervenues depuis sa création.
4. Le stockage hôte est chiffré ZFS `aes-256-gcm` ; ne pas déplacer les archives vers un support non chiffré.

## 3. Séquence de migration

### Étape 0 — Inventaire de la fenêtre

```sh
docker ps --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}\t{{.Ports}}'
ss -ltnp | grep -E ':(4400|4401|8100|4402|18789)\b'
systemctl --user is-active openclaw-gateway.service
adb devices -l
tailscale funnel status
```

Consigner les PID/ports. Ne pas modifier Tailscale.

### Étape 1 — Arrêt des anciens processus hôte

Arrêter le proxy et le connecteur par SIGTERM, dans cet ordre :

1. proxy (port 4400) ;
2. connecteur (port 4401).

Vérifier que 4400/4401 ne sont plus en écoute. Ne pas lancer `scripts/r3a/start-relay.sh`.

### Étape 2 — Fenêtre Core et provisioning identité

1. Arrêter le conteneur Core historique (`docker stop atlas-core-atlas-core-1`).
2. Vérifier le volume et la base : `user_version=5`, intégrité, FK, registre vide.
3. Exécuter le provisioning hors ligne, conteneur one-off, UID 10001, Core arrêté :
   - principal `ANDROID` avec **nouveau** `device_ref` ;
   - credential `RELAY` généré ;
   - binding vers l'ID Relay réel de `pixel-v7` ;
   - capabilities produit minimales ;
   - vérification des deltas exacts et des compteurs.
4. Écrire la map privée du connecteur (`relay_device_id → principal_id + credential_file`) avec le chemin **interne au conteneur**.
5. Vérifier les permissions : credentials et map 0600, propriétaire 1000 ; keyring/cursor 0600, propriétaire 10001.
6. Ne jamais afficher ni journaliser un bearer, un secret HMAC ou un identifiant brut.

### Étape 3 — Bascule des conteneurs

1. Arrêter l'ancien Relay `atlas-v7-relay` (aucun volume).
2. Arrêter `openclaw-gateway.service` et attendre la libération de 18789.
3. Vérifier qu'aucun processus n'écrit dans `~/.openclaw`.
4. Démarrer la stack : `./scripts/atlas-stack up`.
5. Vérifier les cinq healthchecks et l'absence de publication des ports 8100/4000/4401/18789.

### Étape 4 — Oracles internes

```sh
curl -fsS http://127.0.0.1:4400/health
curl -fsS http://127.0.0.1:4400/ready
curl -kfsS https://all.taildc7886.ts.net:8443/health
curl -kfsS https://all.taildc7886.ts.net:8443/ready
```

- Core healthy, Relay healthy, proxy healthy, connecteur `relay_connected=true`, Gateway health OK.
- Un seul écrivain SQLite, un seul worker Core, un seul Gateway.
- `ATLAS_V4_AUTH_MODE=dual`, map présente, assertion chargée.
- Aucun secret dans les logs.

### Étape 5 — Cutover Pixel

Préconditions : Pixel connecté en USB, APK V7-11 présent et hash vérifié, session Relay `pixel-v7` active.

1. Pré-vol lecture seule (API 37, package, hash, rôle HOME, permissions).
2. Vérifier que l'app est en mode RELAY et que la session Noise se connecte via le Funnel public. L'APK V7-11 propose encore DIRECT et le sélectionne par défaut sur une configuration neuve : choisir explicitement RELAY dans l'écran de configuration, sans réappairer ni régénérer l'identité.
3. Coach : démarrage à froid, nouvelle conversation, plusieurs messages, persistance.
4. Sync : « Synchroniser maintenant ». Comme le `device_ref` est nouveau, l'app doit demander un **re-consentement explicite** ; ne jamais modifier les consentements par script.
5. Plan : proposition A0, diff, REJECT sans mutation, APPLY seulement après validation UI, ACK durable.
6. Vérifier les métriques/événements côté Core sans lire le contenu des conversations.
7. Si un oracle échoue : revenir en `dual`/legacy config, conserver les lignes, ne pas révoquer.

### Étape 6 — Mode principal et révocation LEGACY

1. Après preuve Pixel complète, passer Core et connecteur en `ATLAS_V4_AUTH_MODE=principal`.
2. Redémarrer et vérifier que le credential RELAY + assertion + binding fonctionnent.
3. Vérifier que LEGACY est refusé sur les routes V4 Android.
4. **Point d'arrêt propriétaire :** présenter les contrôles et obtenir une confirmation explicite unique avant toute révocation du bearer partagé LEGACY/V2.
5. Ne jamais réactiver automatiquement une identité révoquée. Une révocation est monotone.
6. `ACTIVITY_STATUS` reste révoqué ; `DAY_PLAN` conserve son état vérifié.

## 4. Rollback par couche

| Couche | Rollback | Données |
|---|---|---|
| Proxy | Revenir au processus hôte loopback 4400→4402 et à l'ancien Relay | Aucune. |
| Relay | Relancer `atlas-v7-relay` sur 127.0.0.1:4402 | Aucun volume. |
| Connecteur | Restaurer l'ancien env-file, relancer le processus hôte, vérifier identity/devices/operations | État préservé ; une map/credential dormant peut rester. |
| Core | Revenir à l'image précédente `sha256:1e4c8e6c…` et à l'override legacy ; restaurer la DB seulement si nécessaire | Un restore perd les écritures postérieures au backup ; afficher la perte. |
| Gateway | Arrêter le conteneur, redémarrer `openclaw-gateway.service`, vérifier le même état | Aucun double écrivain. |
| Docker | `docker compose down` sans `-v`, volumes conservés | Aucune suppression. |
| Pixel | Installer l'APK précédent en place, données préservées | Pas de `pm clear`, pas de désinstallation. |
| Tailscale | Aucune modification | — |

Commandes de rollback Core (forme historique) :

```sh
docker stop -t 30 atlas-core-atlas-core-1
# Conserver la base v5 pour diagnostic avant toute restauration.
docker cp atlas-core-atlas-core-1:/data/atlas.sqlite3 <backup-root>/failed-v5/atlas.sqlite3
# Restaurer uniquement après affichage de la perte potentielle.
```

Ne jamais utiliser `docker compose down -v`. Ne jamais supprimer `atlas-core-data`.

## 5. Après migration

- Le service systemd Gateway reste installé et arrêté ; il ne doit jamais tourner en même temps que le conteneur.
- Les anciens backups et images de rollback sont conservés.
- `docs/operations/V7-12_FINAL_REPORT.md` consigne SHA, CI, images/digests, volumes, services, tests, backups, binding, Pixel, Tailscale, rollback et verdict.
- Les prochains travaux sont pilotés par les retours d'usage quotidien, pas par un backlog spéculatif.
