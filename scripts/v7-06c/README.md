# Harnais E2E V7-06C

Ce harnais prépare des stacks isolées pour les scénarios backend et Android V7-06B. Les scénarios Android APPLY et REJECT doivent utiliser chacun une stack neuve.

## Prérequis

- `DEEPSEEK_API_KEY` doit être fourni à l’environnement du harnais. S’il manque, `stack.py` retourne `BLOCKED_CREDENTIALS`; aucun modèle simulé n’est utilisé.
- `openclaw`, Docker, Node.js, `npm` et Python 3 doivent être disponibles.
- L’image Relay locale doit avoir exactement l’identifiant `sha256:75d68fd8bdb9d60a26203cd7c49c07c035f1f400c47740770056814e76846889` sous le tag `atlas-paseo-relay:r3a`.
- Les dépendances npm du connecteur doivent être installées dans `integrations/relay/pc`. Le démarrage exécute `npm run build`.
- L’interpréteur Core (celui de `backend/.venv/bin/python`, ou `ATLAS_V7_06C_PYTHON` si défini, puis l’interpréteur courant) doit importer `fastapi`, `uvicorn` et `atlas_backend`.
- Pour exécuter une recette Android, ADB, l’Android SDK/Gradle et un appareil explicitement sélectionné, API 37 par défaut, sont nécessaires. Le runner V7-06B construit les APK QA. Un appareil physique exige `ATLAS_ALLOW_PHYSICAL_QA=1`.

Le modèle configuré est le modèle réel `deepseek/deepseek-chat`. Le harnais ne remplace pas une preuve Android réelle et ce dépôt ne contient aucune preuve d’exécution Android de V7-06C.

## Scénarios backend

Chaque commande démarre Relay, Gateway OpenClaw, proxy de métadonnées, Core et connecteur. Le scénario est joué automatiquement; la stack reste démarrée après succès pour inspection et arrêt explicite.

```sh
python3 scripts/v7-06c/backend/stack.py start --scenario backend-positive
python3 scripts/v7-06c/backend/stack.py start --scenario backend-negative
```

La sortie est un objet JSON. Dans les exemples suivants, `$PRIVATE_ROOT` désigne exactement la valeur du champ `private_root` retournée par le démarrage correspondant.

```sh
python3 scripts/v7-06c/backend/stack.py stop --private-root "$PRIVATE_ROOT"
```

Le scénario positif vérifie la proposition réelle, son ACK et son rejeu Relay/Core, le snapshot v3 et l’absence de proposition après ACK. Le scénario négatif vérifie les refus backend attendus, notamment l’absence de proposition et d’ACK persistés.

## Scénarios Android locaux

Démarrer une stack neuve pour APPLY, puis exécuter le runner avec le serial obligatoire. Par défaut, l’URL Android vise le bridge émulateur `10.0.2.2`; aucun `adb reverse` n’est nécessaire pour ce mode.

```sh
python3 scripts/v7-06c/backend/stack.py start --scenario android-apply
python3 scripts/v7-06c/android/run_scenario.py \
  --serial emulator-5554 --action apply --stack-root "$PRIVATE_ROOT"
```

REJECT est une exécution indépendante sur une autre stack vide:

```sh
python3 scripts/v7-06c/backend/stack.py start --scenario android-reject
python3 scripts/v7-06c/android/run_scenario.py \
  --serial emulator-5554 --action reject --stack-root "$PRIVATE_ROOT"
```

Pour un téléphone local utilisant `adb reverse`, démarrer avec `--android-relay-host 127.0.0.1` et passer `--adb-reverse` au runner. Le wrapper mémorise les reverse existants et ne retire que le mapping qu’il a lui-même ajouté.

Le suffixe QA est généré avec 12 caractères hexadécimaux minuscules et commence toujours par `a` à `f`, pour former un segment d’identifiant Android valide; une valeur explicite de 8 à 12 caractères suit la même règle et porte l’action demandée. Le package cible reste dans `dev.atlas.coach.qa.v706b.v706c{apply|reject}.<suffix>`. Le package produit `dev.atlas.coach` est refusé. Avant `am instrument`, le runner vérifie le manifeste embarqué des deux APK, leur identifiant et leur version, le certificat de signature, puis compare les SHA-256 des APK installés à ceux des APK construits. Le test doit aussi produire exactement `OK (1 test)`; le seul code `INSTRUMENTATION_CODE: -1` ne suffit plus. Le logcat privé doit contenir l’attestation `step=11 result=PASS ack_replay=identical` émise par le test instrumenté.

Le JSON `assets/atlas-build-provenance.json` est généré au build, trié par clé et sans horodatage. Il lie chaque APK à `git rev-parse HEAD`, à l’arbre Git, à l’état propre du worktree, à la variante, aux IDs, aux versions et aux niveaux SDK. Les sorties `build/` sont exclues du calcul de propreté. Un run V7-06C exige un worktree propre; un manifeste marqué sale est refusé avant installation ou instrumentation. Le scénario est fourni au build par `-PatlasScenarioId=...` (ou `ATLAS_SCENARIO_ID`); les runs APPLY/REJECT utilisent `v7-06c-android-apply` et `v7-06c-android-reject`.

Chaque preuve normale contient un objet `provenance`. Exemple synthétique (toutes les valeurs sont fictives):

```json
{
  "source_sha": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "source_tree_sha": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
  "worktree_clean": true,
  "variant": "qa",
  "application_id": "dev.atlas.coach.qa.v706b.v706capply.a123456789ab",
  "test_application_id": "dev.atlas.coach.qa.v706b.v706capply.a123456789ab.test",
  "version_code": 1,
  "version_name": "0.1.0-qa",
  "compile_sdk": "37.2",
  "min_sdk": 26,
  "target_sdk": 36,
  "runtime_sdk": 37,
  "app_apk_sha256": "1111111111111111111111111111111111111111111111111111111111111111",
  "android_test_apk_sha256": "2222222222222222222222222222222222222222222222222222222222222222",
  "installed_apk_sha256": {"app": "1111111111111111111111111111111111111111111111111111111111111111", "android_test": "2222222222222222222222222222222222222222222222222222222222222222"},
  "signing_cert_sha256": "3333333333333333333333333333333333333333333333333333333333333333",
  "build_provenance_sha256": "4444444444444444444444444444444444444444444444444444444444444444",
  "android_manifest_sha256": "5555555555555555555555555555555555555555555555555555555555555555",
  "android_test_manifest_sha256": "6666666666666666666666666666666666666666666666666666666666666666",
  "scenario_id": "v7-06c-android-apply",
  "network": "local_emulator_bridge",
  "endpoint": {"scheme": "ws", "host": "10.0.2.2", "port": 4413},
  "installed_apk_verified": true,
  "verification_result": "VERIFIED"
}
```

`installed_apk_sha256` conserve les hashes lus après installation pour l’app et l’APK AndroidTest. Le validateur exige exactement ces deux clés, des hashes SHA-256 minuscules, et leur égalité avec `app_apk_sha256` et `android_test_apk_sha256` avant d’accepter une preuve PASS.

L’oracle lit ensuite SQLite en mode lecture seule, le journal `operations.json` du connecteur et la trace de métadonnées du proxy. Il exige les cardinalités ACK/proposition/snapshots, les versions et horaires attendus, les consentements révoqués, les outils autorisés et la corrélation des tool calls. Le rejeu ACK n’est pas lancé par ce wrapper: il est attesté par le test Android avant révocation. La preuve indique `ack_replay=ATTESTED_BY_ANDROID_TEST`, un événement ACK et `ack_body_hash_match=not_applicable`.

Les preuves JSON et résumé sont expurgés et en mode `0600`. Par défaut, elles sont placées dans un répertoire `evidence-*` privé à côté de la racine de stack, pour survivre à son arrêt. `--evidence-dir` permet de choisir un autre répertoire privé. Le serial et l’identifiant de l’appareil sont représentés par leurs hashes; le résumé logcat contient seulement la ligne d’attestation expurgée et le hash du fichier.

Arrêter chaque stack après vérification:

```sh
python3 scripts/v7-06c/backend/stack.py stop --private-root "$PRIVATE_ROOT"
```

L’arrêt vérifie marqueur, signature de commande et groupe de processus avant de signaler un enfant; il vérifie image et label propriétaire avant d’arrêter Relay. Il supprime uniquement la racine privée de cette stack. Les packages QA installés par le runner restent sur l’appareil et ne sont pas nettoyés.

## Vérifier une exécution existante

Si le test instrumenté a réussi mais que le wrapper s’est arrêté avant d’écrire la preuve, `verify-existing` relit le runner log, le logcat, SQLite, `operations.json` et la trace d’outils. Il ne relance pas `run_qa.py`, ne crée ni ne retire de mapping ADB et ne modifie pas la stack ou l’appareil. La seule écriture est le JSON de preuve et son résumé dans le répertoire d’évidence. Il lit les métadonnées appareil avec ADB en lecture seule.

```sh
python3 scripts/v7-06c/android/run_scenario.py verify-existing \
  --serial "$ANDROID_SERIAL" --action reject --stack-root "$PRIVATE_ROOT" \
  --suffix .qa.v706b.v706creject.b4d9e602
```

Le logcat peut conserver les attestations de plusieurs exécutions. Le vérificateur relie la ligne `step=11 result=PASS ack_replay=identical` à l’`ack_id` et à l’état lus dans l’ACK Core courant; une ancienne ligne APPLY ne peut donc pas attester un REJECT ultérieur. Il exige aussi le marqueur `V7_06B_RUNTIME_PASS` dans le runner log. La preuve porte `execution_mode=post_hoc_existing_stack`, `status=INCONCLUSIVE`, `verification_result=INCONCLUSIVE` et `installed_apk_verified=false`; les champs de provenance APK, dont `installed_apk_sha256`, restent `null`, même si des chemins APK sont fournis, car ils ne démontrent pas ce qui était installé. Aucune preuve historique n’est réécrite.

## Recette publique `wss`

La stack peut préparer une URL Relay publique fournie par l’environnement de recette:

```sh
python3 scripts/v7-06c/backend/stack.py start --scenario android-apply \
  --android-relay-url wss://all.example.ts.net:8443
python3 scripts/v7-06c/android/run_scenario.py \
  --serial "$ANDROID_SERIAL" --action apply --stack-root "$PRIVATE_ROOT" \
  --no-adb-reverse
```

`wss://` accepte un hôte public et est enregistré comme `relay_transport=public_wss`. Le runner exige l’option explicite `--no-adb-reverse` et ne crée aucun mapping ADB. Une URL `ws://` n’est acceptée que pour loopback (avec `--adb-reverse`) ou l’endpoint bridge `10.0.2.2` quand l’appareil sélectionné est un émulateur. La preuve indique `network=public_wss`, l’hôte et le port de l’endpoint. Une exécution locale (`local_adb_reverse` ou `local_emulator_bridge`) ne doit jamais être présentée comme une preuve publique.

Cette tranche ne modifie ni Funnel, ni DNS, ni Tailscale et n’exécute pas la recette distante. Le parent exécutera cette recette après autorisation ciblée. La recette publique V7-06C n’utilise aucun `adb reverse`.

### Réutiliser un port local déjà exposé par Funnel

Si une règle Funnel existante dirige déjà `tcp://all.taildc7886.ts.net:8443` vers `127.0.0.1:4400`, démarrer la stack avec ce port loopback épinglé et l’URL publique correspondante:

```sh
python3 scripts/v7-06c/backend/stack.py start --scenario android-apply \
  --relay-port 4400 --android-relay-url wss://all.taildc7886.ts.net:8443
```

Le Relay isolé publie alors exactement `127.0.0.1:4400:4000/tcp`; `stack.json`, l’URL du connecteur et le health check utilisent le port 4400. Si ce port est occupé, le démarrage retourne `BLOCKED_RELAY_PORT` avant de créer la stack. Sans `--relay-port`, le choix actuel reste inchangé: préférence 4413 puis incréments libres. Cette commande ne modifie ni Funnel, ni Tailscale, ni DNS, et n’utilise pas `sudo`. Le harnais n’autorise pas à lui seul une exposition publique: celle-ci exige l’autorisation du propriétaire du service et du réseau.

Pour annuler, arrêter la stack avec `stack.py stop --private-root "$PRIVATE_ROOT"`. Cela arrête uniquement le Relay isolé et libère le port 4400; la configuration Funnel préexistante reste inchangée.

## Sécurité et limites

Les stacks vivent directement sous `/tmp/atlas-v7-06c-private`, avec répertoire `0700`, marqueur propriétaire et lifecycle `0600`. Les identités, appairages et jetons Core/connecteur sont synthétiques et leurs fichiers sont `0600`. La configuration, l’état, le HOME et le workspace OpenClaw pointent dans la stack. Le Gateway utilise `deepseek/deepseek-chat`, le profil d’outils `minimal` et la liste de refus explicite; aucun plugin `atlas_core_tools` n’est activé. Le proxy conserve des métadonnées de requête et d’outils, jamais les messages, arguments ou réponses du modèle.

Le runner ne sélectionne jamais un appareil implicitement, ne cible pas `dev.atlas.coach`, n’efface pas les données de l’appareil et ne désinstalle pas les packages QA. Un appareil physique requiert l’option d’environnement explicite `ATLAS_ALLOW_PHYSICAL_QA=1`. Le succès local via émulateur ou ADB reverse ne constitue pas une preuve publique; seul le mode `public_wss` avec l’endpoint public réellement testé peut être rapporté comme tel. Aucun résultat Android réel ne peut être revendiqué avant l’exécution de la recette sur Android 17/API 37.

## Vérifications locales

```sh
python3 -m py_compile scripts/v7-06c/backend/*.py scripts/v7-06c/android/*.py scripts/v7-06c/tests/*.py
python3 -m unittest discover -s scripts/v7-06c/tests -v
node --check integrations/relay/pc/scripts/v7-06c-relay-client.mjs
```

Ces vérifications ne démarrent ni Docker, ni OpenClaw, ni ADB et n’appellent pas DeepSeek.
