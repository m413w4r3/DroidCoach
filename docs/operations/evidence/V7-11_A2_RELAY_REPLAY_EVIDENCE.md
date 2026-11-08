# V7-11-A2 — preuve Relay / HMAC / anti-rejeu / topologie

## Contrat de tâche

- `TASK_ID`: `V7-11-A2` (compléments `V7-11-A2-F1`, `V7-11B-P4`)
- Statut sous-session: `REVIEW_REQUIRED`
- `BASE_SHA`: `8dac699cdb37e2f858a76e4cf7f9fc3bcd613e9c`
- Base du complément F1: `e4a4461059f5f0e8c57316533c61d63cee3b07c9`
- Base du complément P4: `291b5f78d2f770dbb02dcf353b0fe6f6a4fd1030`
- Branche: `audit/v7-11-a2-relay-replay`
- Profil transmis et vérifié par le parent: `provider=codex`, `model=gpt-6-luna`, `effort=xhigh`
- Périmètre initial A2: inspection runtime et code en lecture seule; script(s) temporaires sous `/tmp`; preuve seule modifiée, commit local demandé, aucun push. P4 autorise en plus `backend/atlas_backend/v4_router.py` et `backend/tests/test_v4_principal_auth.py`.
- Oracle: scénarios HMAC/cache ci-dessous, inventaire Docker/processus/sockets, tests connecteur et proxy.

## Verdict

**`GO_V7_11_ANTI_REPLAY_TOPOLOGY = NO-GO` pour toute mise en service V4 Relay.** Le rejeu inter-worker est reproduit: deux caches Core distincts acceptent la même assertion. F1 a aussi reproduit le rejeu après redémarrage; P4 ajoute un garde qui refuse les assertions antérieures au démarrage de plus de 5 s, tout en acceptant les assertions dans cette tolérance. Ce garde ne partage pas les nonces entre workers. L’environnement observé n’a par ailleurs ni proxy `4400` ni connecteur `4401`; le trajet Relay→connecteur→Core n’est pas présent.

Finding sécurité: **P2 — confirmé, gate live obligatoire**. Les caches isolés entre workers restent vulnérables; après redémarrage, une assertion datée dans les 5 s précédant le démarrage peut aussi passer le garde de tolérance si elle est encore dans la fenêtre ±120 s. Cette sévérité correspond au risque multiworker déjà documenté dans le handoff V7-10 (sections 12–13, lignes 621 et 642).

## Topologie observée en lecture seule

Commandes d’inventaire utilisées: `docker context show`, `docker ps -a --no-trunc --format ...`, `docker inspect --format ...` sur les deux conteneurs ATLAS, `docker top`, `docker logs --tail 40` avec filtrage/expurgation, `ss -ltnp '( sport = :4400 or sport = :4401 or sport = :4402 or sport = :8100 )'`, et `ps -eo pid,ppid,comm`. Aucun `docker exec`, aucune requête HTTP de santé manuelle, aucun redémarrage ou changement live n’a été fait pendant l’inventaire.

| Composant | Observation |
|---|---|
| Core | Conteneur `atlas-core-atlas-core-1`, image `atlas-core-atlas-core`, actif; `network_mode: host`; un processus `uvicorn` dans `docker top`, commande `uvicorn atlas_backend.main:app --host 127.0.0.1 --port 8100`; listener `127.0.0.1:8100`. |
| Relay | Conteneur `atlas-v7-relay`, image `atlas-paseo-relay:r3a`, processus `/app/bin/paseo_relay start`; réseau `bridge`; publication hôte `127.0.0.1:4402` vers le port conteneur `4000`; listener `127.0.0.1:4402`. Les logs de queue ne sont pas une preuve de connexion du connecteur. |
| Proxy | Aucun listener `4400`, aucun conteneur/processus proxy observé. L’unité `integrations/relay/proxy/systemd/atlas-relay-proxy.service` se décrit explicitement comme modèle non installé/non activé; son routage prévu est `127.0.0.1:4400` → `127.0.0.1:4402`. |
| Connecteur PC | Aucun listener `4401` et aucun processus `atlas-relay` observé. Le service modèle lance `node dist/main.js start`; le code prévoit Relay `4400`, métriques `4401`, Core `8100`. |

Le chemin de production attendu est Android → session Relay authentifiée → connecteur PC → Core V4. Dans l’état observé, seul le Relay local sur `4402` et Core sur `8100` sont présents: le proxy et le connecteur manquent, donc ce trajet n’est pas opérationnel. Les ports `4400`/`4401` étaient absents de la sortie `ss`; `4402`/`8100` étaient présents.

### Processus Core et garantie d’un worker

Le Dockerfile définit directement Uvicorn avec `--host 127.0.0.1 --port 8100`, sans `--workers` ni `--reload` ([Dockerfile](../../../backend/Dockerfile:24), [compose.yaml](../../../compose.yaml:10)). L’entrypoint charge les secrets puis remplace son processus par la commande avec `os.execvp` ([docker-entrypoint.py](../../../backend/docker-entrypoint.py:67)). Le conteneur inspecté et `docker top` montraient un seul processus `uvicorn`: **un worker est observé et le lancement actuel démarre un seul worker**.

Ce n’est pas une interdiction de scale garantie par Compose: le service ne fixe pas `deploy.replicas` et rien n’empêche une modification de commande, `uvicorn --workers N`, l’ajout de `--reload`, un autre superviseur multiworker ou plusieurs instances derrière un répartiteur. Le mode host peut faire échouer une deuxième instance sur le même port, mais ne protège pas contre des workers Uvicorn partageant le socket ou des instances séparées sur d’autres ports/hôtes. Un redéploiement inchangé ou la politique `restart: unless-stopped` ne crée pas seul de workers additionnels.

## Contrôle HMAC, binding et cache

- La chaîne signée contient version, méthode, chemin, timestamp, nonce, ID Relay et hash SHA-256 du corps ([`v4_assertion.py`](../../../backend/atlas_backend/v4_assertion.py:105), [`backend.ts`](../../../integrations/relay/pc/src/backend.ts:136)). Le connecteur tire un nonce aléatoire par requête et émet les en-têtes d’assertion.
- `verify_relay_assertion` vérifie la fenêtre de ±120 000 ms, le `key_id`, HMAC-SHA256 et l’égalité en temps constant avant la réservation du nonce ([`v4_assertion.py`](../../../backend/atlas_backend/v4_assertion.py:137)).
- `ReplayNonceCache` est un `OrderedDict` protégé par un verrou **dans la mémoire d’un processus**; TTL 300 s, capacité 8192, clé `(key_id, nonce)`. Un doublon ou un cache plein renvoie `False` (refus) ([`v4_assertion.py`](../../../backend/atlas_backend/v4_assertion.py:113), [`v4_assertion.py`](../../../backend/atlas_backend/v4_assertion.py:119)). `create_v4_router` instancie le cache lors de la création du routeur ([`v4_router.py`](../../../backend/atlas_backend/v4_router.py:522)).
- Core résout d’abord le bearer, vérifie l’assertion, puis exige l’audience `RELAY`, un binding actif et l’égalité du principal et du `device_ref` ([`v4_router.py`](../../../backend/atlas_backend/v4_router.py:805), [`v4_auth.py`](../../../backend/atlas_backend/v4_auth.py:236)). Le connecteur choisit son credential depuis la map privée de l’ID Relay de la session authentifiée ([`connector.ts`](../../../integrations/relay/pc/src/connector.ts:82), [`connector.ts`](../../../integrations/relay/pc/src/connector.ts:263)).
- Le README décrit Noise NK avec mode `protected`; le connecteur impose `minimumMode: "protected"` au canal sécurisé ([README.md](../../../integrations/relay/pc/README.md:1), [`relay-link.ts`](../../../integrations/relay/pc/src/relay-link.ts:221)). Ce contrôle est une propriété du code, pas une observation runtime ici, car le connecteur est absent.
- La configuration du connecteur désactive V4 si la map ou la clé privée manque/invalide; `check-config.mjs` exige les champs principal-bound et masque les valeurs, tandis que `check-secret.mjs` vérifie la map et les fichiers privés ([`config.mjs`](../../../integrations/relay/pc/scripts/lib/config.mjs:232), [`check-config.mjs`](../../../integrations/relay/pc/scripts/check-config.mjs:45), [`check-secret.mjs`](../../../integrations/relay/pc/scripts/check-secret.mjs:20)).
- La configuration de rotation accepte une ou deux clés et refuse plus de deux ([`v4_assertion.py`](../../../backend/atlas_backend/v4_assertion.py:31)). La procédure V7-10 prescrit l’overlap old+new, le basculement du connecteur, une preuve avec la nouvelle clé puis le retrait de l’ancienne (handoff lignes 533 et 599–601). Ce test est synthétique; aucune rotation live n’a eu lieu.

## Matrice anti-rejeu

Le script temporaire `/tmp/v7_11_a2_replay_probe.py` utilise uniquement clés, ID Relay, corps, horloge et nonce synthétiques; il appelle les fonctions réelles de `v4_assertion.py`.

Commande: `PYTHONDONTWRITEBYTECODE=1 PYTHONPATH="$PWD/backend" python3 /tmp/v7_11_a2_replay_probe.py`.

| Cas | Résultat | Observation |
|---|---|---|
| (a) Assertion HMAC valide, premier usage | PASS | Acceptée une fois. |
| (b) Rejeu identique, même cache | PASS | Refusé. |
| (c) Même assertion, deux caches simulant deux workers | **FAIL — finding reproduit** | Chaque cache accepte l’assertion; le nonce n’est pas partagé entre workers. |
| (d) Timestamp à plus de ±120 s | PASS | Rejeté des deux côtés, futur et passé. |
| (e) Même nonce sur deux connexions simulées | PASS | Le deuxième appel est refusé; l’identité de connexion n’entre pas dans la clé du cache. |
| (f1) Cache saturé | PASS | Le cache plein refuse le nouvel nonce (fail-closed). Une saturation peut donc causer une indisponibilité temporaire. |
| (f2) Erreur I/O de cache | PASS sécurité / FAIL disponibilité | Probe ASGI temporaire: `OSError` propagée par le cache ⇒ réponse HTTP 500, aucun événement de consentement créé. Le bloc d’authentification ne capture que `RelayAssertionError`, `UnicodeError`, `ValueError` ([`v4_router.py`](../../../backend/atlas_backend/v4_router.py:803)); ce chemin ne donne pas l’accès, mais n’est pas normalisé en refus 401. |
| (g) Overlap contrôlé de clé | PASS synthétique | Le parseur autorise old+new, les deux signent pendant l’overlap; après retrait de l’ancienne entrée, l’ancien `key_id` est refusé; plus de deux clés sont refusées. |

Le test d’intégration existant `test_relay_assertion_binds_credential_to_active_session_and_rejects_replay` confirme également A/A et B/B, le refus d’un bearer B signé pour le binding A, le rejeu, l’expiration, l’altération de corps/chemin/timestamp, la clé inconnue et la révocation du binding ([`test_v4_principal_auth.py`](../../../backend/tests/test_v4_principal_auth.py:383)).

### Complément F1 — perte du cache au redémarrage

Commande: `PYTHONDONTWRITEBYTECODE=1 PYTHONPATH="$PWD/backend" python3 /tmp/v7_11_a2_f1_restart_probe.py`. Le script fabrique une assertion HMAC et un nonce synthétiques, appelle le vérificateur réel avec des horloges contrôlées et ne contacte aucun service. Le routeur crée un nouveau `ReplayNonceCache` lors de son initialisation ([`v4_router.py`](../../../backend/atlas_backend/v4_router.py:522)); les entrées sont un `OrderedDict` local au processus ([`v4_assertion.py`](../../../backend/atlas_backend/v4_assertion.py:110)).

| Cas F1 | Résultat | Observation |
|---|---|---|
| Assertion synthétique dans la fenêtre, premier cache | PASS | Première vérification acceptée. |
| Même assertion encore valide, nouvelle instance de cache (redémarrage simulé) | **FAIL — rejeu après redémarrage reproduit** | La nouvelle instance accepte l’assertion: le nonce de l’ancien processus n’a pas persisté. |
| Assertion synthétique horodatée à plus de +120 s après le redémarrage, cache neuf | PASS | Refusée par la vérification temporelle, même sans nonce préexistant dans le cache. |
| TTL et fenêtre temporelle | PASS — analyse des bornes | TTL cache 300 s; fenêtre du timestamp ±120 s. La fenêtre totale de validité peut couvrir 240 s. Avec une horloge stable, le cache retient donc le nonce 60 s de plus que la durée maximale pendant laquelle une assertion acceptée peut rester temporellement valide. |
| Rejeu près de +240 s avec cache d’origine, puis cache neuf | **FAIL — conséquence du redémarrage confirmée** | Le cache d’origine bloque le doublon; un cache neuf l’accepte tant que son timestamp est encore dans la fenêtre. Au-delà de +240 s, le contrôle d’horodatage le refuse, bien que le TTL initial de 300 s ne soit pas écoulé. |

Le TTL de 300 s dépasse le seuil de dérive d’un seul côté (120 s), et de 60 s la borne complète de 240 s à partir d’une acceptation au timestamp futur maximal. Avant redémarrage, le cache local bloque le nonce pendant son TTL; le contrôle de timestamp fournit aussi un refus lorsque l’assertion sort de ±120 s. Après redémarrage, le cache est vide: le même rejeu est accepté si le timestamp reste dans la fenêtre, puis refusé par le timestamp hors fenêtre. La borne de 240 s suppose une horloge Core stable et des assertions antérieures au redémarrage; elle n’est pas une protection contre des horloges incohérentes.

### Complément P4 — garde d’assertion au démarrage Core

`create_v4_router` capture une seule fois `int(time.time() * 1000)` au moment de la création de l’application/du routeur. Cette valeur est conservée dans la closure de `require_android`; chaque nouvelle app simulant un redémarrage a donc un nouveau repère de démarrage et un nouveau cache nonce. La constante documentée `CLOCK_SKEW_MS = 5_000` ne modifie pas la fenêtre HMAC ±120 000 ms. Pour une requête avec assertion Relay, après vérification HMAC/fenêtre et binding principal actif, `require_android` lit `x-atlas-relay-time`; un timestamp inférieur à `core_started_at_ms - CLOCK_SKEW_MS` renvoie l’échec d’authentification générique (401). Les credentials DIRECT sans assertion, les routes legacy sans assertion et les autres flux n’empruntent pas ce garde. Aucun nonce n’est persisté et aucun schéma SQLite n’est changé ([`v4_router.py`](../../../backend/atlas_backend/v4_router.py:70), [capture](../../../backend/atlas_backend/v4_router.py:504), [garde après binding](../../../backend/atlas_backend/v4_router.py:820)).

Test ciblé `test_relay_assertion_pre_restart_is_rejected_but_startup_skew_is_tolerated`, horloge contrôlée et assertions synthétiques:

| Oracle rouge/vert | Avant le garde | Après le garde |
|---|---|---|
| Assertion acceptée par l’app initiale puis rejouée 10 s avant la nouvelle app, cache neuf | FAIL attendu: réponse réelle `200` | PASS: `401` générique |
| Assertion signée à/après le démarrage de la nouvelle app | — | PASS: `200` |
| Assertion signée 3 s avant le démarrage (tolérance 5 s) | — | PASS: `200` |

La tolérance est une permission explicite: le garde borne l’ancienneté du timestamp par rapport au démarrage, pas le délai après lequel une assertion tolérée expire. Une assertion horodatée dans les 5 s précédant le démarrage peut donc rester admissible jusqu’à sa sortie de la fenêtre normale ±120 s; une fois réservée, le cache mono-processus bloque ses duplicatas pendant son TTL de 300 s. Le garde seul ne corrige pas le finding inter-worker et ne constitue pas un cache partagé/durable.

## Suites et preuves d’exécution

- **PASS rouge/vert ciblé** — Depuis `backend/`, `ATLAS_AGENT_ADAPTER=mock python3 -m pytest -q tests/test_v4_principal_auth.py::test_relay_assertion_pre_restart_is_rejected_but_startup_skew_is_tolerated`: avant le patch, l’assertion `401` échoue avec réponse réelle `200`; après patch, **1 passed**, couvrant rejeu post-redémarrage `401`, assertion post-démarrage `200` et tolérance de 3 s `200`.
- **PASS** — Depuis `backend/`, `ATLAS_AGENT_ADAPTER=mock python3 -m pytest -q` → **452 passed, 1 skipped**, 3 avertissements. La suite couvre aussi rejeu mono-cache, timestamp ±120 s, rotation de clés, binding A/B et accès DIRECT sans assertion.
- **FAIL hors périmètre** — Depuis la racine, `python3 -m pytest -q` → **9 failed, 497 passed, 1 skipped** (39 sous-cas passés). Les 9 échecs sont dans `scripts/v7-06c/tests/test_harness.py`: l’INSERT de test fournit 10 valeurs pour `agent_proposals_v4`, qui a 11 colonnes (`sqlite3.OperationalError`). Aucun de ces fichiers n’est dans le périmètre P4; aucun changement n’y a été apporté.
- **PASS** — `git diff --check` après les modifications P4.
- **PASS** — `PYTHONDONTWRITEBYTECODE=1 PYTHONPATH="$PWD/backend" python3 -m pytest -q -p no:cacheprovider backend/tests/test_v4_principal_auth.py::test_relay_assertion_binds_credential_to_active_session_and_rejects_replay` → `1 passed`.
- **PASS** — injection temporaire d’une erreur de cache via `/tmp/test_v7_11_a2_cache_error.py` → `1 passed`; assertion authentique refusée par réponse 500, aucun événement créé. Un premier essai du harnais avait un `key_id` synthétique incohérent et s’arrêtait en 401 avant le cache; l’essai corrigé est celui retenu.
- **PASS** — `npm ci --offline` dans une copie temporaire, puis `npm run test:unit` depuis un checkout temporaire imbriqué (`/tmp/.../integrations/relay/pc` avec marqueur `AGENTS.md`), build TypeScript inclus → **131/131**. Une première exécution depuis une copie plate hors racine ATLAS avait 129 passés et 2 échecs de fixture (`findRepositoryRoot` indéfini); relancée dans la structure attendue, **131/131**. Aucun `node_modules` ni fichier de build n’a été créé dans le dépôt.
- **PASS** — proxy, copie temporaire: `node --test proxy.test.mjs`, après remplacement explicite dans cette copie uniquement du cas `pinned relay integration on 4402` par `test.skip` → **18 passés, 1 ignoré**. Ce résultat final ne lance pas le cas épinglé.
- **NOT RUN** — Pixel/ADB, tests Android, qualification manuelle du trajet Relay/Noise/connecteur/Core, rotation réelle des clés. L’appel santé/ready via le test proxy lors de la déviation ci-dessous est consigné séparément et ne vaut pas qualification.

### Déviation pendant l’exécution du test proxy

Une première tentative a utilisé `node --test --test-name-pattern='^(?!pinned relay integration on 4402$)' proxy.test.mjs`. Le motif n’a pas exclu le cas épinglé: le test a contacté le Relay live `4402` (GET de santé/ready et handshake WebSocket via proxy). Aucun payload applicatif, commande mutante, changement de conteneur ou écriture n’a été envoyé. Cette exécution est **FAIL — restriction de tâche non respectée** et n’est pas comptée comme preuve. La suite a ensuite été exécutée depuis une copie `/tmp` où le cas est explicitement `skip`; aucun autre accès à `4402` n’a eu lieu.

## Commandes et environnement

- Pré-vol: `pwd`, `git status --short --branch`, `git rev-parse HEAD`, `git branch --show-current`.
- Runtime: Docker context `default`; `docker ps`, `docker inspect` formaté sans variables d’environnement, `docker top`, `docker logs --tail`; `ss` et `ps` ciblés. Pas de `docker exec`, `systemctl`, `curl`, ADB, changement de service, push ou accès aux secrets.
- Outils: Python 3.12.3, Node 24.18.0, npm 11.16.0, OpenJDK 21.0.12.1; adb est installé mais aucune commande ADB n’a été exécutée.
- `git diff --cached --check`: PASS avant le commit local.

## Gate avant tout live

Ne lever `GO_V7_11_ANTI_REPLAY_TOPOLOGY` qu’après l’une des deux solutions suivantes:

1. **Cache anti-rejeu partagé et durable**: réservation atomique cross-worker qui survit au redémarrage Core (par exemple `SET NX` avec expiration persistante), avec tests de rejeu entre processus indépendants, après redémarrage, ainsi que comportement fermé sur erreur/saturation; ou
2. **Cache mémoire avec restrictions supplémentaires**: worker/instance Core unique vérifié à chaque déploiement, interdiction opérationnelle et automatisée de scale/instances parallèles, et garde P4 vérifié sur le code déployé avec le test de rejeu après redémarrage. Le seuil toléré de 5 s avant démarrage doit être accepté explicitement; une assertion dans cette tolérance peut rester valide jusqu’à sa limite ±120 s. Le worker unique seul n’est pas un gate suffisant sans le garde de redémarrage.

Avant le live, vérifier aussi le routage complet `4400 → 4402`, la présence du connecteur et ses métriques `4401`, le binding principal réel, le keyring overlap puis le retrait contrôlé de la clé ancienne. La présence d’un seul worker à cet instant n’est pas une barrière contre un futur changement de commande ou de déploiement.

## Traçabilité finale

- Base examinée: `8dac699cdb37e2f858a76e4cf7f9fc3bcd613e9c`.
- Compléments F1/P4: reproduction puis mitigation conditionnelle du rejeu après recréation du cache; tests rouge/vert et limites documentés; commit local SHA rapporté dans la notification finale; aucun push.
- Secrets, tokens, identifiants Relay personnels et valeurs de credentials exclus de ce document.
