# V7-11-A2 — preuve Relay / HMAC / anti-rejeu / topologie

## Contrat de tâche

- `TASK_ID`: `V7-11-A2` (compléments `V7-11-A2-F1`, `V7-11B-P4`, `V7-11B-P6`)
- Statut sous-session: `REVIEW_REQUIRED`
- `BASE_SHA`: `8dac699cdb37e2f858a76e4cf7f9fc3bcd613e9c`
- Base du complément F1: `e4a4461059f5f0e8c57316533c61d63cee3b07c9`
- Base du complément P4: `291b5f78d2f770dbb02dcf353b0fe6f6a4fd1030`
- Base du complément P6: `168fc87c3c6461d427899dd10807f9f1fe988018`
- Branche: `audit/v7-11-a2-relay-replay`
- Profil transmis et vérifié par le parent: `provider=codex`, `model=gpt-6-luna`, `effort=xhigh`
- Périmètre initial A2: inspection runtime et code en lecture seule; script(s) temporaires sous `/tmp`; preuve seule modifiée, commit local demandé, aucun push. P4/P6 autorisent `backend/atlas_backend/v4_router.py`, `backend/atlas_backend/v4_assertion.py`, un test backend ciblé et cette preuve.
- Oracle: scénarios HMAC/cache ci-dessous, inventaire Docker/processus/sockets, tests connecteur et proxy.

## Verdict

**`GO_V7_11_ANTI_REPLAY_TOPOLOGY = NO-GO` pour toute mise en service V4 Relay.** P6 ajoute un cache fichier durable, atomique et partagé lorsque `ATLAS_V4_REPLAY_CACHE_DIR` est configuré; les tests synthétiques couvrent les workers, le redémarrage et le recul d’horloge. En l’absence de cette configuration, le Core garde le cache mémoire historique et reste vulnérable au multiworker/redémarrage. Le répertoire durable n’a pas été configuré ni validé sur l’instance live; l’environnement observé n’a par ailleurs ni proxy `4400` ni connecteur `4401`.

Finding sécurité: **P2 — conditionnel à la configuration, gate live obligatoire**. Le cache mémoire reste vulnérable; le cache fichier réduit ce risque uniquement si son répertoire privé, persistant et commun à tous les workers est configuré sur le volume Core. Le garde P4 de 5 s reste une défense secondaire, pas une protection contre le recul d’horloge. Cette sévérité correspond au risque multiworker déjà documenté dans le handoff V7-10 (sections 12–13, lignes 621 et 642).

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
- `ReplayNonceCache` reste le défaut si `ATLAS_V4_REPLAY_CACHE_DIR` est absent: `OrderedDict` et verrou en mémoire d’un processus, TTL 300 s, capacité 8192, clé `(key_id, nonce)` ([`v4_assertion.py`](../../../backend/atlas_backend/v4_assertion.py:113)). Si la variable est définie, `create_v4_router` sélectionne `FileReplayNonceCache`; une erreur de construction empêche le démarrage et ne retombe jamais silencieusement sur le cache mémoire ([`v4_router.py`](../../../backend/atlas_backend/v4_router.py:530)).
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

`create_v4_router` capture une seule fois `int(time.time() * 1000)` au moment de la création de l’application/du routeur. Cette valeur est conservée dans la closure de `require_android`. La constante `CLOCK_SKEW_MS = 5_000` ne modifie pas la fenêtre HMAC ±120 000 ms; le garde P4 renvoie l’échec d’authentification générique si le timestamp est plus ancien que le démarrage moins 5 s. Les credentials DIRECT sans assertion et les routes non Relay n’empruntent pas ce garde. P4 seul ne persistait aucun nonce ni schéma SQLite; P6 ajoute le stockage fichier hors SQLite ([`v4_router.py`](../../../backend/atlas_backend/v4_router.py:71), [capture](../../../backend/atlas_backend/v4_router.py:505), [garde après binding](../../../backend/atlas_backend/v4_router.py:829)).

Test ciblé `test_relay_assertion_pre_restart_is_rejected_but_startup_skew_is_tolerated`, horloge contrôlée et assertions synthétiques:

| Oracle rouge/vert | Avant le garde | Après le garde |
|---|---|---|
| Assertion acceptée par l’app initiale puis rejouée 10 s avant la nouvelle app, cache neuf | FAIL attendu: réponse réelle `200` | PASS: `401` générique |
| Assertion signée à/après le démarrage de la nouvelle app | — | PASS: `200` |
| Assertion signée 3 s avant le démarrage (tolérance 5 s) | — | PASS: `200` |

La tolérance est une permission explicite: le garde borne l’ancienneté du timestamp par rapport au démarrage, pas le délai après lequel une assertion tolérée expire. Une assertion horodatée dans les 5 s précédant le démarrage peut donc rester admissible jusqu’à sa sortie de la fenêtre normale ±120 s; une fois réservée, le cache mono-processus bloque ses duplicatas pendant son TTL de 300 s. Le garde seul ne corrige pas le finding inter-worker et ne constitue pas un cache partagé/durable.

### Complément P6 — cache fichier anti-rejeu durable

`FileReplayNonceCache` est sélectionné quand `ATLAS_V4_REPLAY_CACHE_DIR` est défini. La configuration exige un chemin absolu, normalisé, hors racine du dépôt. Il ouvre les composants sans suivre de symlink; le répertoire cible doit appartenir à l’utilisateur Core et être en mode `0700` (les répertoires manquants sont créés ainsi). Une configuration invalide empêche la création du routeur; aucun fallback mémoire n’est appliqué.

Pour chaque paire `(key_id, nonce)`, le nom est le SHA-256 hexadécimal de la concaténation UTF-8 `key_id`, d’un octet NUL, puis `nonce`. La réservation est atomique sous verrou de répertoire partagé (`flock`) et utilise aussi `os.open(O_CREAT|O_EXCL|O_WRONLY|O_NOFOLLOW, 0o600)`. `EEXIST` signifie rejeu. Le fichier ne contient que l’expiration epoch en millisecondes; les noms sont hashés et le bearer/nonce ne sont pas écrits en clair. TTL par défaut 300 s, capacité 8192, nettoyage opportuniste limité à 128 entrées par réservation. Les erreurs de verrou, lecture, nettoyage, capacité, écriture ou `fsync` renvoient un refus; une marque partielle après échec reste présente et échoue fermée. Aucun schéma SQLite n’est modifié.

Configuration de production attendue: `ATLAS_V4_REPLAY_CACHE_DIR=/data/relay-replay-cache`, sur un sous-répertoire privé du volume persistant Core monté au même chemin pour tous les workers et conservé aux redémarrages. Le volume doit garantir les sémantiques atomiques de `O_EXCL`, `flock` interprocessus et `fsync`; cette propriété n’a été testée ici que sur un répertoire local temporaire. Avec la variable absente, le cache mémoire historique reste actif: cette configuration par défaut n’est pas admissible pour le gate live.

| Oracle P6 | Résultat | Observation |
|---|---|---|
| Même `(key_id, nonce)` réservé deux fois, même instance | PASS | La seconde réservation est refusée. |
| Deux instances sur le même répertoire; appels concurrents | PASS | La seconde instance refuse; sur 8 tentatives concurrentes, exactement une réserve. |
| Nonce différent | PASS | Réservation acceptée. |
| TTL expiré | PASS | À `now=400 s`, une entrée réservée à `now=100 s` (expiration 400 000 ms) peut être réservée de nouveau. |
| Permissions et confidentialité | PASS | Répertoire `0700`, fichier nonce `0600`, nom SHA-256; le contenu ne contient ni nonce ni bearer en clair, uniquement l’expiration. |
| Symlink et chemin dans le dépôt | PASS | La construction refuse un répertoire symlink et tout chemin situé dans le dépôt. |
| Capacité/erreurs disque ou lecture | PASS fail-closed | Capacité `1` pleine, `scandir` en `PermissionError`, et écriture simulée `ENOSPC` refusent la réservation; une marque partielle reste bloquante. |
| Rejeu après recréation d’app et recul de l’horloge Core de 30 s | PASS | L’assertion devient un timestamp futur de 30 s et passe la fenêtre/P4; le cache fichier persistant trouve le nonce et répond `401`. |

La réservation d’identité ne dépend pas de l’horloge: le nom déterministe et `O_EXCL` arbitrent le doublon; l’horloge epoch ne sert qu’à calculer/retirer les expirations. Un recul d’horloge retarde le nettoyage, donc peut prolonger un refus. Un saut vers l’avant peut au contraire rendre des fichiers expirés prématurément si le temps revient ensuite; ce risque dépend de l’horloge murale et reste une limite du TTL epoch exigé. Un fichier incomplet ou une erreur persistante peut bloquer des requêtes jusqu’à une maintenance contrôlée. Pas de test de crash système, de reboot hôte ou de sémantiques d’un volume Docker/NFS réel.

## Suites et preuves d’exécution

- **PASS ciblé P6** — Depuis `backend/`, exécution des quatre tests `test_file_replay_nonce_cache_*` et `test_router_file_replay_cache_rejects_replay_after_clock_rollback` → **4 passed**; comprend la réservation concurrente, le TTL, les permissions, le refus sur symlink/capacité/I/O et le recul simulé de 30 s.
- **PASS** — Depuis `backend/`, `ATLAS_AGENT_ADAPTER=mock python3 -m pytest -q` → **456 passed, 1 skipped**, 3 avertissements.
- **FAIL hors périmètre** — Depuis la racine, `python3 -m pytest -q` → **9 failed, 501 passed, 1 skipped** (39 sous-cas passés). Les 9 erreurs restent dans `scripts/v7-06c/tests/test_harness.py`: l’INSERT fournit 10 valeurs à la table `agent_proposals_v4` qui compte 11 colonnes. Ce fichier n’est pas autorisé dans le périmètre P6 et n’a pas été modifié.
- **PASS** — `git diff --check` après P6.
- **PASS rouge/vert ciblé** — Depuis `backend/`, `ATLAS_AGENT_ADAPTER=mock python3 -m pytest -q tests/test_v4_principal_auth.py::test_relay_assertion_pre_restart_is_rejected_but_startup_skew_is_tolerated`: avant le patch, l’assertion `401` échoue avec réponse réelle `200`; après patch, **1 passed**, couvrant rejeu post-redémarrage `401`, assertion post-démarrage `200` et tolérance de 3 s `200`.
- **PASS au commit P4** — La suite backend avait donné **452 passed, 1 skipped**; la suite racine, **9 failed, 497 passed, 1 skipped**. Les échecs étaient les mêmes insertions V7-06C documentées ci-dessus.
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

Ne lever `GO_V7_11_ANTI_REPLAY_TOPOLOGY` qu’après configuration et vérification de `ATLAS_V4_REPLAY_CACHE_DIR` sur un volume persistant privé, absolu, hors dépôt, mode `0700`, monté au même chemin pour tous les workers Core. Vérifier sur l’environnement de déploiement que `O_EXCL`, `flock` et `fsync` ont des sémantiques interprocessus fiables, puis exécuter les tests de doublon entre workers, après recréation/redémarrage, recul d’horloge, saturation et erreur. Si la variable est absente, `ReplayNonceCache` mémoire reste actif et le gate reste NO-GO, même avec un worker unique. Le garde P4 est une défense secondaire; le cache partagé configuré est requis pour traiter le recul d’horloge A4-F2.

Avant le live, vérifier aussi le routage complet `4400 → 4402`, la présence du connecteur et ses métriques `4401`, le binding principal réel, le keyring overlap puis le retrait contrôlé de la clé ancienne. La présence d’un seul worker à cet instant n’est pas une barrière contre un futur changement de commande ou de déploiement.

## Traçabilité finale

- Base examinée: `8dac699cdb37e2f858a76e4cf7f9fc3bcd613e9c`.
- Compléments F1/P4/P6: reproduction, garde d’horloge et cache fichier durable testés synthétiquement; configuration live et volume persistant non validés; commit local SHA rapporté dans la notification finale; aucun push.
- Secrets, tokens, identifiants Relay personnels et valeurs de credentials exclus de ce document.
