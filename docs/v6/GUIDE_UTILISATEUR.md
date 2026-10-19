# ATLAS V6 — Guide de configuration locale

Ce guide configure ATLAS avec le Gateway OpenClaw local. Le mode `mock` reste
le mode démo hors ligne et ne demande ni compte ni secret.

## Prérequis OpenClaw

Utiliser le Gateway local avec l'agent `atlas`, l'endpoint Chat Completions
activé et les outils refusés (`tools.deny: ["*"]`). ATLAS appelle seulement
`POST /v1/chat/completions` avec `model=openclaw/atlas`; il n'appelle pas
`/tools/invoke`. Le backend devrait joindre le Gateway à
`http://127.0.0.1:18789` depuis la même machine.

Le backend lit le jeton Gateway depuis
`~/.config/atlas/openclaw-gateway-token`. Ce fichier doit être hors du dépôt et
avoir le mode `0600`. Si le fichier manque, le script de recette le crée en
lisant `gateway.auth.token` dans `~/.openclaw/openclaw.json` sans afficher sa
valeur. La procédure manuelle équivalente est :

```sh
umask 077
mkdir -p "$HOME/.config/atlas"
python3 - <<'PY'
import json
import os
from pathlib import Path

source = Path.home() / ".openclaw/openclaw.json"
target = Path.home() / ".config/atlas/openclaw-gateway-token"
token = json.loads(source.read_text(encoding="utf-8"))["gateway"]["auth"]["token"]
if not isinstance(token, str) or not token.strip():
    raise SystemExit("Jeton Gateway absent")
fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
os.fchmod(fd, 0o600)
with os.fdopen(fd, "w", encoding="utf-8") as stream:
    stream.write(token.strip() + "\n")
PY
chmod 600 "$HOME/.config/atlas/openclaw-gateway-token"
```

Ne copiez pas la valeur du jeton dans une commande, un issue, une capture ou un
fichier du dépôt. La configuration OpenClaw existante n'a pas été modifiée par
la recette.

## Démarrer le backend pour l'AVD

Dans un terminal, depuis la racine du dépôt :

```sh
export ATLAS_AGENT_ADAPTER=openclaw
export OPENCLAW_BASE_URL=http://127.0.0.1:18789
export OPENCLAW_AGENT_ID=atlas
export OPENCLAW_GATEWAY_TOKEN_FILE="$HOME/.config/atlas/openclaw-gateway-token"
export ATLAS_TOKEN="$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')"
export ATLAS_DB_PATH="${TMPDIR:-/tmp}/atlas-v6.sqlite3"
PYTHONPATH=backend python3 -m uvicorn atlas_backend.main:app --host 0.0.0.0 --port 8000
```

Dans l'AVD, `10.0.2.2` désigne l'hôte. Configurer l'URL du backend dans ATLAS
comme `http://10.0.2.2:8000`; HTTP n'est accepté que par l'APK debug vers cet
alias. Le jeton ATLAS est distinct du jeton Gateway et est saisi dans ATLAS.
Pour la recette scriptée, préférer `./scripts/v6-e2e-real.sh`, qui génère un
Bearer aléatoire temporaire, configure l'app par instrumentation et l'efface
après le scénario.

## Appareil Android réel

Ne pas utiliser `10.0.2.2` sur un téléphone. Héberger le backend sur une
adresse joignable par le téléphone, utiliser HTTPS avec un certificat valide,
protéger le backend par un Bearer ATLAS, et vérifier pare-feu/réseau avant de
configurer l'URL HTTPS dans l'application. Le Gateway peut rester en loopback
sur la machine backend. Aucun résultat de cette recette ne provient d'un
téléphone physique.

## Désactivation et révocation

Dans ATLAS, ouvrir Coach puis « Mandat et autonomie ». « Désactiver agent »
coupe le chemin agent; « Suspendre le mandat » le suspend temporairement et
« Révoquer tout » invalide les propositions en attente. Décochez chaque
catégorie dans « Données partagées avec le coach » pour arrêter son inclusion
dans les prochains messages. Le consentement UsageStats et les permissions OS
se révoquent séparément dans leurs écrans ATLAS/Android. Une révocation n'annule
pas une requête déjà arrivée au Gateway.

