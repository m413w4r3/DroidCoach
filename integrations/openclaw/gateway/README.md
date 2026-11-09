# OpenClaw Gateway container

Image validée : `ghcr.io/openclaw/openclaw@sha256:f40d1e66fd14d8c5b49c832f4100f83b53fdc01f5108f7db11de38fd0ee6dbe5` (2026.10.5).

Définition à intégrer au service Compose `openclaw-gateway` :

```yaml
image: ghcr.io/openclaw/openclaw@sha256:f40d1e66fd14d8c5b49c832f4100f83b53fdc01f5108f7db11de38fd0ee6dbe5
user: "1000:1000"
environment:
  HOME: /home/nill
  OPENCLAW_STATE_DIR: /home/nill/.openclaw
  OPENCLAW_CONFIG_PATH: /home/nill/.openclaw/openclaw.json
  TMPDIR: /tmp
  XDG_CACHE_HOME: /home/nill/.cache
command: ["node", "openclaw.mjs", "gateway", "--port", "18789", "--bind", "lan"]
read_only: true
tmpfs:
  - /tmp:rw,nosuid,nodev,size=512m,mode=1777
  - /home/nill/.cache:rw,nosuid,nodev,size=128m,uid=1000,gid=1000,mode=0700
cap_drop: [ALL]
security_opt: [no-new-privileges:true]
restart: unless-stopped
logging:
  driver: json-file
  options:
    max-size: 10m
    max-file: "3"
volumes:
  - type: bind
    source: /home/nill/.openclaw
    target: /home/nill/.openclaw
    bind:
      create_host_path: false
  - type: bind
    source: /home/nill/.openclaw.update-captures
    target: /home/nill/.openclaw.update-captures
    bind:
      create_host_path: false
networks: [atlas-internal, atlas-egress]
healthcheck:
  test: ["CMD", "node", "openclaw.mjs", "gateway", "health", "--port", "18789", "--timeout", "5000"]
  interval: 10s
  timeout: 21s
  retries: 12
  start_period: 20s
```

Le bind complet est en lecture-écriture pour conserver `openclaw.json`, `state/`, `agents/`, les workspaces, `secrets/`, les extensions, les sessions et SQLite à leurs chemins absolus existants. Le bootstrap lance aussi la capture Doctor pré-réparation sous le sibling `/home/nill/.openclaw.update-captures`; sans ce répertoire, il journalise `EROFS` et continue sans capture de récupération. Ce second bind dédié garde ces captures persistantes. Le préparer côté hôte avec propriétaire UID/GID `1000:1000` et mode `0700`. Les seuls autres chemins inscriptibles requis sont les tmpfs `/tmp` et `/home/nill/.cache`; le root filesystem reste en lecture seule. Aucun port n’est publié en production ; `--bind lan` écoute sur `0.0.0.0:18789` à l’intérieur des réseaux Docker. Le fichier live garde `gateway.bind=loopback` : l’option de commande surcharge le bind uniquement pour ce processus.

Le healthcheck authentifié utilise la configuration montée. Le conteneur garde le token configuré ; ne pas définir `OPENCLAW_GATEWAY_TOKEN` ni désactiver l’authentification. Le plugin `atlas_core_tools` reste désactivé, l’agent `atlas` conserve son profil `minimal`, son modèle hérité `openai/gpt-6-luna` et sa deny-list. Les chemins configurés `workspace`, `agentDir` et `tokenFile` restent sous `/home/nill/.openclaw`.

Réseaux Compose : `atlas-internal` (interne) et `atlas-egress` (sortie Gateway). Ne pas ajouter `ports`, `network_mode: host`, `privileged`, un montage `/var/run/docker.sock` ou des capacités. Le service systemd `openclaw-gateway.service` reste inchangé pour le rollback ; le test ci-dessous ne l’arrête pas.

Répétition isolée, sans appel de modèle ni chat facturé :

```sh
integrations/openclaw/gateway/validate-container.sh
```

Le script copie les données utiles sous un répertoire temporaire `0700` hors dépôt, vérifie les fichiers SQLite, publie seulement `127.0.0.1:18790`, et utilise deux bridges dédiés : un réseau `internal` pour la sonde entre conteneurs et un réseau egress pour reproduire le lookup npm de Doctor. Les commandes de validation restent healthcheck, listings et inventaire d’outils; aucun chat ni appel provider n’est lancé. Il teste aussi les écritures autorisées, `read_only`, SIGTERM puis le redémarrage. Il n’utilise jamais le vrai état comme volume du conteneur. Les montages tmpfs sont éphémères ; les captures Doctor sont supprimées avec la copie temporaire de test, sans toucher à un backup live.

Limite : cette répétition prouve le démarrage, le healthcheck, la lecture de configuration/auth locale et la persistance sur copie. Le bridge egress autorise le lookup npm effectué au démarrage, mais aucune requête d’authentification ou de modèle n’est envoyée à OpenAI. Le runtime a normalisé les racines `skills` et `wizard` dans la copie ; les réglages protégés sont restés identiques. Aucun chat, accès Pixel, basculement systemd ou déploiement Compose n’est couvert.

Dernière répétition complète : PASS pour healthcheck, listings, read-only, contrat Docker, SQLite, SIGTERM/redémarrage, manifeste live et nettoyage. Le manifeste live était identique avant/après : 106053 fichiers, 3095465071 octets, SHA-256 `13c6fcc91a159e6266a2e672b5cacbecedc3b6e7d099db8b3b12f4bd03bfc44c`. `openclaw-gateway.service` est resté actif avec le même PID. Une tentative précédente avait détecté deux entrées variables sous `state/` pendant une écriture SQLite concurrente ; la répétition finale, exécutée dans une fenêtre stable, a satisfait l’oracle strict.
