# ATLAS V7 — Plan de déploiement réversible et autorisations

**Base SHA :** `b759bdafbf0c67eda1a14ae2cf2f3fac75239e4c`
**Branche d'intégration :** `feat/atlas-v7-remote-operational`
**État :** plan préparé, **aucune opération sensible appliquée**.
**Règle :** chaque section indique le composant visé, l'effet, le backup exigé, la vérification et le rollback. Une autorisation distincte du propriétaire est requise avant application.

## 0. Préconditions bloquantes (état au 2026-10-30)

1. **V7-03C** doit corriger et faire accepter l'alignement `request_id` enveloppe/payload V4 ; sans cela, la sync et le chat relayés sont rejetés par le connecteur PC.
2. **Outils natifs** : `tools.effective` de l'agent `atlas` doit montrer les six outils ATLAS et aucun outil interdit. L'état isolé actuel est FAIL (0 outil ATLAS, 66 exclus) et une anomalie `exec tool.result ok` subsiste ; l'audit doit qualifier la sémantique `alsoAllow`/plugin optionnel et la réalité de l'exécution avant toute configuration live.
3. **Token plugin** : le `tokenFile` du plugin doit correspondre au token outil du Core live ; la copie doit rester privée `0600`, hors dépôt, et la valeur ne doit jamais être affichée.
4. **Entrée publique** : `tailscale funnel status` confirme le 2026-10-30 `tcp://all.taildc7886.ts.net:8443 → tcp://127.0.0.1:4400` ; le port privé `8770` reste hors périmètre. Les ports 4400/4401/4402 sont absents, donc le prochain démarrage doit d'abord écouter le proxy/relais local avant de qualifier le public. A4-4 (`4001→1012`) doit être accepté explicitement ou corrigé ; il ne doit pas être masqué.
5. **Core live** : `/health` rapporte `mode=mock` en dur ; la sélection réelle se prouve par `/internal/v4/capabilities` (sept flags) et un chat V4 `agent_status=ok`, jamais par `/health`.

## 1. OpenClaw Gateway (configuration active)

### Changements envisagés
1. `plugins.entries.atlas_core_tools.config.coreBaseUrl` : `http://127.0.0.1:18100` → `http://127.0.0.1:8100`.
2. Aligner le `tokenFile` du plugin avec le token outil du Core live :
   - lire le token outil depuis le secret Compose / PID 1 du Core ;
   - écrire une copie privée `0600`, propriétaire du compte Gateway, hors dépôt ;
   - comparer par hash, sans afficher la valeur ;
   - conserver l'ancien fichier en backup privé.
3. Vérifier `tools.effective` pour l'agent `atlas` dans une **nouvelle** session :
   - six outils ATLAS exacts ;
   - aucun `exec`, `process`, `ls/read/write/edit`, `web_*`, `browser`, `gateway`, `session_status`, `plugins`, `nodes`, `computer`, `mobile_ui`, `sessions_spawn`, `subagents`.
4. Si `alsoAllow` ne sélectionne toujours pas les outils plugin optionnels :
   - ne pas passer le profil global en `full` ;
   - utiliser une allowlist explicite au niveau agent (`allow` avec les six noms, ou `group:plugins` + deny de tous les autres), après validation de la syntaxe et de l'effet réel ;
   - conserver `deny` pour `exec`, `process`, `gateway`, `session_status`, `presence` et tout outil généraliste.

### Backup
- `~/.openclaw/openclaw.json` (copie horodatée `0600` dans `~/.openclaw/backups/`) ;
- identités et workspace Atlas (`IDENTITY.md`, `SOUL.md`, `USER.md`, `AGENTS.md`, `MEMORY.md`, `HEARTBEAT.md`, `openclaw-agent.sqlite`) ;
- hash SHA-256 de chaque fichier sauvegardé ;
- vérifier l'espace disque avant la copie.

### Application
- modification atomique de `openclaw.json` (écriture temporaire + `mv`) ;
- redémarrage contrôlé du service utilisateur `openclaw-gateway.service` (ou `openclaw gateway restart`) ;
- vérification `/health`, agent `atlas` visible, `tools.effective` non vide, aucune erreur d'auth.

### Rollback
- restaurer `openclaw.json` depuis le backup vérifié ;
- redémarrer le Gateway ;
- vérifier que l'agent `atlas` répond et que la politique précédente est active.

### Risques
- interruption temporaire de l'agent personnel pendant le redémarrage ;
- si le token outil ne correspond pas, les outils échouent en `AUTH_REQUIRED` ;
- une politique trop large exposerait `exec`/FS : la vérification `tools.effective` est bloquante.

## 2. Core live (Docker)

### Changements envisagés
1. Reconstruire l'image avec l'entrypoint V7-01A (secret Gateway optionnel).
2. Créer les copies de livraison privées des secrets :
   - `atlas-core.token` (Android) ;
   - `atlas-tool.token` (outil) ;
   - `openclaw-gateway.token` (bearer Gateway, nouveau).
3. Lancer :
   ```sh
   docker compose -f compose.yaml -f compose.openclaw.yaml up -d --force-recreate atlas-core
   ```
   avec `ATLAS_TOKEN_FILE`, `ATLAS_TOOL_TOKEN_FILE`, `OPENCLAW_GATEWAY_TOKEN_FILE`.
4. Vérifier :
   - conteneur healthy ;
   - `/internal/v4/capabilities` : sept flags `true` ;
   - requête V4 synthétique : `agent_status=ok`, `tool_state=completed`, `tool_call_count>=1` ;
   - aucune réponse `mock` présentée comme réelle ;
   - le volume `atlas-core-data` et la base SQLite sont conservés.

### Backup
- copie SQLite cohérente (API backup ou arrêt contrôlé) ;
- `PRAGMA quick_check=ok` sur la copie ;
- hash de la copie ;
- conservation du volume existant.

### Rollback
```sh
docker compose -f compose.yaml up -d --force-recreate atlas-core
```
- retour au mode mock ;
- **jamais** `docker compose down -v` ;
- ne pas supprimer `atlas-core-data`.

### Risques
- indisponibilité courte du Core ;
- si le token Gateway est absent/invalide, le chat V4 échoue proprement (`AUTH_UPSTREAM_REQUIRED`/`UPSTREAM_UNAVAILABLE`), sans fallback mock ;
- l'image reconstruite doit rester sans secret.

## 3. Relais, proxy et connecteur PC

### Changements envisagés
1. Démarrer le relais Paseo épinglé sur `127.0.0.1:4402` (runtime exact à confirmer par le scout Relay R2).
2. Démarrer le proxy filtrant `127.0.0.1:4400` → `127.0.0.1:4402`.
3. Démarrer le connecteur PC avec :
   - `ATLAS_RELAY_URL=ws://127.0.0.1:4400` (local) ou `wss://…` selon l'entrée ;
   - `ATLAS_BACKEND_URL=http://127.0.0.1:8100` ;
   - `ATLAS_SERVICE_TOKEN` (routes V1/V2/V3) ;
   - `ATLAS_V4_ENABLED=1` ;
   - `ATLAS_V4_ANDROID_TOKEN_FILE` (fichier `0600`, hors dépôt) ;
   - `ATLAS_RELAY_STATE_DIR`, `ATLAS_METRICS_PORT=4401`.
4. Vérifier :
   - `/health` et `/ready` du proxy ;
   - métriques loopback ;
   - session relay authentifiée ;
   - opérations V4 autorisées, V4 refusé si désactivé ;
   - Funnel public `:8443` → `:4400` ;
   - refus d'un appareil inconnu, révocation, code de fermeture observable.

### Backup
- identité et registre d'appareils du connecteur (`identity.json`, `devices.json`) ;
- fichier d'environnement privé ;
- hash et permissions.

### Rollback
- arrêter le connecteur/proxy/relais démarrés pour ce test ;
- restaurer le fichier d'environnement précédent ;
- laisser le Core et le Gateway actifs ;
- aucun changement de DNS/Funnel non documenté.

### Risques
- exposition publique involontaire : le Funnel ne doit exposer que le proxy filtrant, jamais Core/Gateway ;
- le bearer Android partagé entre appareils est une limite assumée ; l'identité reste vérifiée par la session ;
- A4-4 (4001→1012) doit être requalifié, pas masqué.

## 4. Pixel Android 17 / API 37

### Changements envisagés
1. Vérifier l'APK installé (hash, version) ; installer l'APK V7 uniquement si un changement Android l'exige.
2. Saisir les données d'appairage (URL relay, serverId, clé X25519, deviceId, token) dans l'écran Connexion.
3. Activer le toggle « Conversation V4 » et le consentement de partage nécessaire.
4. Envoyer un message synthétique, vérifier le tour OpenClaw réel et l'appel d'outil.
5. Vérifier la persistance de conversation autorisée et les gardes de révocation.

### Prérequis
- sauvegarde app-only vérifiée avant toute installation/permission ;
- consentement ciblé du propriétaire ;
- un seul opérateur ADB ;
- aucune action destructive (`pm clear`, `uninstall`) par défaut.

### Rollback
- désactiver le toggle V4 et retirer le consentement ;
- révoquer l'appareil côté connecteur ;
- réinstaller l'APK précédent si nécessaire (après backup).

## 5. Autorisations à demander

| Opération | Effet | Backup | Rollback | Autorisation |
|---|---|---|---|---|
| Modifier `openclaw.json` + redémarrer le Gateway | applique la politique plugin/coreBaseUrl | config + identités | restauration + redémarrage | **requise** |
| Reconstruire et recréer le Core live | active l'adapter réel et V4 | SQLite + volume | retour au compose mock | **requise** |
| Démarrer relais/proxy/connecteur | ouvre le transport distant | état connecteur | arrêt + restauration | **requise** |
| Appairer le Pixel / activer V4 / consentir | permet le chat réel | sauvegarde app-only | révocation/désactivation | **requise** |

Aucune de ces opérations n'est exécutée par le plan seul. Le parent demandera une autorisation ciblée après les preuves isolées.
