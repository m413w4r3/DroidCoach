# ATLAS V7-07 — Plan de configuration live et transition d'identités

**Date :** 2026-11-01
**BASE_SHA :** `32e7c70c78fbe295f66f67d6be50dc797a3f6e6d`
**Statut :** plan préparé ; **scope B exécuté le 2026-11-02** (configuration
minimale, plugin natif désactivé, appel modèle réel PASS). Le transport public
D est **BLOCKED** par le DNS/TLS Funnel ; aucun chat produit distant. Détail :
`V7-07_PHYSICAL_EXECUTION.md`.

## 1. Architecture retenue

```text
Android dev.atlas.coach
  → WSS public (Funnel 8443 → proxy 4400 → Relay 4402)
  → connecteur PC (loopback, Noise NK, auth appareil)
  → Core FastAPI 127.0.0.1:8100
  → Gateway OpenClaw 127.0.0.1:18789
  → modèle
```

Le Core orchestre les six définitions d'outils client V4. Le Gateway ne doit
pas exposer un second chemin natif concurrent qui pourrait soumettre deux fois
une proposition ou exécuter un outil hors du contrôle Core/Android.

Le Core et le Gateway restent en loopback. Seul le proxy filtrant est publié par
le Funnel Tailscale existant.

## 2. État live initial (lecture seule, 2026-11-01)

| Élément | Actuel | Problème |
|---|---|---|
| Core | image `00b88ef…`, `healthy`, 7 capabilities, DB stable | aucun |
| Gateway | service utilisateur actif, `127.0.0.1:18789` | aucun pour le transport local |
| Modèle | `openai/gpt-6-luna` hérité par l'agent `atlas` | non qualifié sur les propositions structurées ; dépend d'un OAuth personnel |
| Agent `atlas` | `profile=full`, `allow=[atlas_core_tools]`, deny `exec/process/code_execution/wait/gateway/session_status/presence`, `codeMode=false` | `full` sélectionne aussi les plugins optionnels ; plugin natif activé en parallèle du chemin Core |
| Plugin `atlas_core_tools` | activé, `coreBaseUrl=127.0.0.1:8100`, token = token outil Core | chemin natif concurrent ; à désactiver pour le chemin produit |
| Six outils client | présents dans le code Core et dans le plugin | contrat identique à préserver |
| Relay | conteneur `atlas-v7-relay` sain sur `127.0.0.1:4402` | aucun |
| Proxy 4400 | **arrêté** | entrée publique sans backend |
| Connecteur PC | **arrêté**, PID obsolète, pas de service systemd installé | transport distant indisponible |
| Entrée publique | Funnel `8443→4400`, sans listener | health TLS en échec |
| Bearer Android | même token Core et connecteur ; `device_ref` unique `android-9a3f0f75…` | pas de séparation par appareil |
| Identités Relay | `pixel-v7`, `r3c-pixel-final` actives | identités de campagne à révoquer |
| Consentements Core | `DAY_PLAN`, `ACTIVITY_STATUS` `GRANTED` v2 | à réconcilier avec un consentement actuel |

## 3. Décision de modèle

### Option A — conserver `openai/gpt-6-luna` (recommandée pour le premier pilote)

- Aucun nouveau credential, aucun profil OAuth copié.
- Le modèle est déjà celui du Gateway personnel.
- Il doit être qualifié sur : sortie structurée `changes`/`justification`,
  exactement une proposition, absence d'`idempotency_key` inventée, appel des
  six outils client, aucun outil interdit.
- Risque : modèle différent de la stack QA DeepSeek ; le PASS QA ne le qualifie
  pas.

### Option B — `deepseek/deepseek-v4-pro` (fallback, autorisation séparée)

- Nécessite l'ajout d'une authentification DeepSeek au Gateway live, via le
  mécanisme de secret OpenClaw, jamais en argument de commande ni dans un diff.
- La documentation OpenClaw installée indique le retrait de
  `deepseek/deepseek-chat` au 24 juillet 2026. Le PASS QA V7-06C déclare
  pourtant `deepseek/deepseek-chat` le 1er novembre 2026 : **la référence
  exacte utilisée par le harnais QA doit être revérifiée** avant de s'appuyer
  dessus. Ne pas reconduire `deepseek-chat` par assimilation.
- `deepseek/deepseek-v4-pro` n'est pas configuré aujourd'hui ; son format
  d'appel d'outils et sa fiabilité de proposition doivent être testés.

**Décision proposée :** Option A pour le premier pilote, avec test de
qualification bloquant. Option B seulement si A échoue ou si le propriétaire
préfère la stack DeepSeek et fournit explicitement l'accès.

## 4. Configuration candidate du Gateway

Fichier : `~/.openclaw/openclaw.json`. Les valeurs secrètes ne sont pas
modifiées par ce plan.

```json
{
  "agents": {
    "entries": {
      "atlas": {
        "model": { "primary": "openai/gpt-6-luna" },
        "tools": {
          "profile": "minimal",
          "deny": [
            "gateway",
            "session_status",
            "presence",
            "exec",
            "process",
            "code_execution",
            "wait",
            "group:plugins",
            "group:fs",
            "group:runtime",
            "group:web",
            "group:sessions",
            "group:nodes",
            "group:ui",
            "sessions_spawn",
            "subagents"
          ],
          "codeMode": { "enabled": false }
        }
      }
    }
  },
  "plugins": {
    "entries": {
      "atlas_core_tools": {
        "enabled": false,
        "config": { "enabled": false }
      }
    }
  },
  "tools": {
    "codeMode": { "enabled": false }
  }
}
```

Points d'attention :

- Ne pas combiner `allow` et `alsoAllow` : la configuration installée le
  rejette. Le plugin étant désactivé, `allow=[atlas_core_tools]` est retiré.
- `profile=minimal` inclut `session_status` et `gateway` ; ils sont refusés
  explicitement.
- Le token du plugin reste configuré mais inutilisé ; ne pas le supprimer avant
  d'avoir décidé si un usage natif est conservé pour un autre agent.
- Les six outils restent fournis par le Core dans la requête chat. La
  qualification doit prouver ce chemin, pas l'apparition des outils dans
  `tools.effective`.
- Si le propriétaire veut conserver le plugin natif pour un usage personnel,
  créer un agent distinct pour le produit et laisser l'agent personnel
  inchangé. Ne pas partager un agent entre le coach produit et un usage
  personnel non borné.

## 5. Configuration Core

Aucun changement de contrat V4, de capabilities ni de code n'est requis pour le
transport. Les capabilities sont déjà toutes `true`.

Changements live possibles, sur autorisation séparée :

1. rotation éventuelle du bearer Android (voir §7) ;
2. conservation du volume `atlas-core-data` et de la DB ;
3. aucune publication de port ; le réseau hôte reste limité à loopback par le
   code Uvicorn.

## 6. Bring-up du transport distant (scope C)

La configuration existe déjà mais n'est pas active :

- `~/.config/atlas/relay.env` (0600) : `ATLAS_RELAY_URL=ws://127.0.0.1:4400`,
  `ATLAS_ALLOW_LOOPBACK_WS=1`, backend `127.0.0.1:8100`, métriques `4401`,
  V4 activé, token Android dans `~/.local/state/atlas-relay/android-token`.
- Le proxy source est `integrations/relay/proxy/src/server.mjs` (écoute
  `127.0.0.1:4400`, amont `127.0.0.1:4402`).
- Le connecteur installé est attendu sous `~/.local/lib/atlas-relay` ; vérifier
  sa présence et sa version avant démarrage.
- Les unités systemd modèles existent mais ne sont pas installées : seul
  `openclaw-gateway.service` est présent dans `~/.config/systemd/user`.

Commandes préparées (non exécutées) :

```sh
# Sauvegarde préalable de l'état connecteur
install -d -m 0700 "$HOME/.local/backups"
node "$HOME/.local/lib/atlas-relay/scripts/backup-state.mjs" \
  "$HOME/.config/atlas/relay.env" \
  "$HOME/.local/backups/atlas-relay-before-v7-07"

# Proxy loopback
node integrations/relay/proxy/src/server.mjs   # ou unité utilisateur dédiée

# Connecteur
cd "$HOME/.local/lib/atlas-relay"
node scripts/check-config.mjs --env-file "$HOME/.config/atlas/relay.env"
node scripts/check-secret.mjs --env-file "$HOME/.config/atlas/relay.env"
node dist/main.js start                      # ou unité utilisateur dédiée

# Vérifications
curl -fsS http://127.0.0.1:4400/health
curl -fsS http://127.0.0.1:4400/ready
curl -fsS http://127.0.0.1:4401/metrics | head
curl -fsS https://all.taildc7886.ts.net:8443/health
```

Pour un service durable, installer les unités utilisateur
`atlas-relay-proxy.service` et `atlas-relay.service` (modèles fournis), avec des
chemins absolus vérifiés. `loginctl enable-linger` est une décision séparée qui
modifie le comportement du compte.

**Rollback :** arrêter les deux processus/unités, vérifier que `4400`/`4401` ne
écoutent plus, restaurer l'état connecteur depuis la sauvegarde si nécessaire.
Ne pas supprimer `~/.local/state/atlas-relay`.

## 7. Transition d'identités et de consentements (scope A)

### 7.1 Constat

- `pixel-v7` et `r3c-pixel-final` ne sont pas révoqués. Leurs UUID exacts se
  lisent dans `devices.json` au moment de l'action et ne sont pas publiés dans
  les preuves.
- Le connecteur utilise le même bearer Android que le Core ;
  `device_ref=android-9a3f0f75…` est partagé par tous les appareils Relay.
- Le Core conserve `DAY_PLAN` et `ACTIVITY_STATUS` `GRANTED` v2 pour ce
  `device_ref`. Un ancien `GRANTED` n'est pas un consentement actuel de
  l'utilisateur.

### 7.2 Politique proposée

1. **Sauvegarder** `devices.json`, `operations.json`, `identity.json`,
   `android-token` et les consentements Core (export lecture seule), avec
   hashes et modes.
2. **Révoquer** dans le connecteur les deux appareils de campagne.
3. **Réconcilier** les consentements Core par des événements `REVOKED` append-only
   pour l'ancien `device_ref` (si l'API le permet avec le token Android actuel),
   sans supprimer l'historique.
4. **Séparer l'identité produit** : recommandé, rotation du bearer Android
   avant appairage du produit. Générer un nouveau token aléatoire, mettre à jour
   la copie Core et la copie connecteur, redémarrer Core et connecteur, puis
   appairer le produit avec une nouvelle identité Relay et de nouveaux
   consentements explicites.
5. **Vérifier** : ancien token refusé, ancien device_ref sans nouvelle
   opération, appareils révoqués sans nouvelle session, produit avec un
   `device_ref` distinct.
6. **Ne pas** restaurer automatiquement une révocation volontaire ; le rollback
   du registre n'est prévu qu'en cas d'erreur de ciblage.

### 7.3 Commandes préparées (non exécutées)

```sh
# Sauvegarde privée
node "$HOME/.local/lib/atlas-relay/scripts/backup-state.mjs" \
  "$HOME/.config/atlas/relay.env" \
  "$HOME/.local/backups/atlas-relay-before-revocation"

# Révocation ciblée des deux devices (UUID lus dans devices.json, non publiés)
node "$HOME/.local/lib/atlas-relay/scripts/run-cli.mjs" \
  "$HOME/.config/atlas/relay.env" revoke-device <UUID_PIXEL_V7>
node "$HOME/.local/lib/atlas-relay/scripts/run-cli.mjs" \
  "$HOME/.config/atlas/relay.env" revoke-device <UUID_R3C_PIXEL_FINAL>

# Vérification du registre (lecture seule, sans afficher de token)
node -e '...'   # nombre d entrées, revoked_at non nul, aucun token imprimé
```

La rotation du bearer est une opération distincte : générer un secret aléatoire
dans un fichier privé, remplacer atomiquement la copie Core et la copie
connecteur, redémarrer les deux services, vérifier l'ancien token refusé et le
nouveau `device_ref`. Ne jamais afficher la valeur. Ne pas exécuter sans
autorisation A et sauvegarde vérifiée.

## 8. Qualification obligatoire avant activation produit

1. **Statique :** `openclaw config validate` ; capture de `tools.catalog` et
   `tools.effective` ; absence d'outil natif `atlas_*`, d'`exec`, `wait`,
   `process`, `code_execution`, `gateway`, `session_status`, `presence`.
2. **Trajectoire réelle :** un chat synthétique Core → Gateway → modèle, avec
   les six définitions client, sans donnée personnelle. Vérifier les appels
   d'outils corrélés, `tool_state=completed`, exactement une proposition si
   demandée, aucun outil interdit.
3. **Proposition :** `plan_id`, `base_version`, `changes`, `justification` ;
   aucune clé d'idempotence inventée par le modèle ; une proposition en attente,
   aucun APPLY/ACK automatique.
4. **Training :** ne pas déclarer `atlas_get_training_plan` qualifié tant que
   l'écart de schéma `slot_id`/`routine_id`/`source` et le HTTP 500 historique
   ne sont pas traités. Le cas échéant, désactiver la capability
   `training_plan.read` pour le pilote plutôt que masquer l'échec.
5. **Transport :** health proxy/connecteur, session Relay authentifiée, refus
   d'un appareil inconnu/révoqué, coupure et reprise sans double proposition.
6. **Sécurité :** aucun port Core/Gateway public, aucun secret dans les logs,
   tokens distincts, rotation vérifiée.

## 9. Rollback

| Composant | Rollback |
|---|---|
| `openclaw.json` | restaurer la copie `0600` horodatée, `openclaw config validate`, redémarrer le service utilisateur |
| Plugin/agent | restaurer la configuration précédente ; le plugin désactivé peut être réactivé si un usage natif est décidé |
| Core | restaurer les secrets depuis la sauvegarde, `docker compose ... up -d --force-recreate atlas-core`, sans `down -v` |
| Connecteur | restaurer `devices.json`/`identity.json` depuis la sauvegarde vérifiée, redémarrer |
| Proxy | arrêter le processus/unité, vérifier la libération de 4400 |
| Bearer | revenir à l'ancien fichier secret et redémarrer Core/connecteur ; l'ancien `device_ref` redevient actif, donc à ne faire qu'en cas d'erreur et avec une nouvelle vérification |

## 10. Autorisations demandées

- **A — Sécurité live :** sauvegarde, révocation de `pixel-v7` et
  `r3c-pixel-final`, réconciliation des consentements Core, rotation
  éventuelle du bearer Android.
- **B — Gateway/Core :** sauvegarde, modification de `openclaw.json` (modèle,
  profil minimal, plugin désactivé), redémarrage contrôlé, vérification
  `tools.effective` et trajectoire réelle.
- **C — APK produit :** mise à jour in-place de `dev.atlas.coach` après
  sauvegarde app-only vérifiée, même signature, test local et rollback.
- **D — Appairage et recette distante :** identité produit dédiée,
  consentements choisis, test OpenClaw réel sur une activité synthétique
  explicitement approuvée.
- **E — Pilote quotidien supervisé :** durée, seuils, métriques expurgées et
  arrêt d'urgence.

Aucune de ces autorisations n'est implicite. Le plan seul n'exécute rien.

## 11. Exécution B du 2026-11-02

La configuration candidate du §4 a été appliquée après sauvegarde et dry-run.
`profile=minimal`, `allow` supprimé, deny étendu et plugin
`atlas_core_tools` désactivé ; `openclaw-gateway.service` redémarré et sain.
`tools.effective` exclut 66/66 outils natifs et aucun outil `atlas_*` natif
n'est chargé. Un harnais Core isolé a réalisé un appel modèle réel
`gpt-6-luna`/`openai` : six outils, lecture DayPlan, proposition A0 à 10:10,
`NONE_AT_CORE`, rejeu sans double effet. D n'a pas pu utiliser ce chemin
jusqu'au produit, le transport public Funnel étant indisponible (DNS
`NXDOMAIN`, TLS en échec). La rotation du bearer et les consentements n'ont
pas été modifiés. Voir `V7-07_PHYSICAL_EXECUTION.md`.
