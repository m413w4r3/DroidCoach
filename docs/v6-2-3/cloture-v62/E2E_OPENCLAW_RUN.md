# E2E OpenClaw réel — V62-E2E-OPENCLAW

## Verdict

**BLOCKED** pour la clôture complète — **PASS pour le parcours host-side OpenClaw réel**. Source évaluée : `8890046012965f3a3112ff0c419014c74e189d11`.

Le premier contrôle dans le sandbox a échoué avec `curl` code 7 / HTTP `000`. Après l’élévation réseau loopback demandée, le même endpoint a répondu HTTP 200; le backend ATLAS réel, son Bearer éphémère et sa SQLite jetable ont ensuite été lancés et supprimés. Le parcours host-side a réussi. Le gate global reste bloqué parce que ce ticket interdit ADB ici : `MandateVerifier`, l’effet local Android et l’absence d’effet lors d’une révocation en vol restent à vérifier par le parent. Résultats expurgés : [`preflight.txt`](preuves-openclaw/preflight.txt) et [`host-run.txt`](preuves-openclaw/host-run.txt).

## Contexte synthétique prévu

Le parcours Android ciblé configure uniquement les catégories consenties `focus` et `goals`, un mandat autorisant `home.set_directive`, `focus.propose` et `focus.start`, un objectif synthétique « Réviser l'algèbre » (cible 120 minutes), et une session Focus terminée de 25 minutes liée à cet objectif. Ces valeurs synthétiques ont aussi été envoyées lors du parcours host-side réel; le test Android lui-même n’a pas été exécuté.

Le payload réel envoyé à OpenClaw contenait `focus=["completed | 25 min | 1 session"]` et `goals=["Objectif synthétique Réviser l’algèbre : cible 120 min ; validé 25 min ; prochaine action Relire le prochain exercice."]`. L’adaptateur transmet les catégories consenties sous forme de dictionnaire minimisé, sans champ `consented_categories` dans le corps destiné au gateway. La réponse réelle mentionne les 25 minutes, la cible de 120 minutes et l’étape d’algèbre. Elle propose `home.set_directive` et `focus.propose` de 25 minutes, tous deux permis par le mandat synthétique.

Une seconde requête réelle après retrait de `goals` a envoyé seulement `focus`; ni le titre, ni la catégorie `goals`, ni la cible 120 n’étaient dans ce payload. Cette vérification porte sur la requête suivante, pas sur une requête déjà en vol.

## Résultats observés

| Vérification | Résultat | Preuve / limite |
|---|---|---|
| Gateway réel et réponse HTTP | **PASS** | `/health` a répondu 200; deux requêtes ont atteint `/v1/chat/completions` sur `openclaw/atlas`. |
| Authentification backend Bearer | **PASS** | `/v2/agent/status` : Bearer invalide → 401; Bearer aléatoire valide → 200 et `adapter=openclaw`. |
| Backend ATLAS et SQLite jetables | **PASS** | Backend lié à `127.0.0.1`, health 200; token mode 600 et base sous `$XDG_RUNTIME_DIR`, runtime supprimé après le run. |
| Payload et réponse OpenClaw contextualisée | **PASS** | Le payload capturé et la réponse réelle sont résumés dans `host-run.txt`; la réponse reprend `25/120` et l’algèbre, puis propose deux actions conformes au mandat. |
| Policy/mandat backend | **PASS** | Les propositions retournées sont incluses dans `allowed_actions` et le backend les valide avant de les persister. `MandateVerifier` Android n’a pas été exécuté. |
| ACK et rejeu identique | **PASS côté journal backend** | Même proposition, même corps `executed` rejoué : réponses identiques et une ligne `agent_proposal_acks`. L’effet associé est explicitement une sonde synthétique du journal, pas une preuve d’effet Android. |
| Effet local Android | **NOT_RUN** | ADB n’a pas été utilisé. L’application n’a pas appliqué la directive et son journal local n’a pas été inspecté. |
| Révocation, requête suivante | **PASS côté payload backend** | Le payload réel suivant ne contient que `focus`; `goals` et ses valeurs en ont disparu. |
| Révocation en vol sans effet | **NOT_RUN** | Le parcours host ne peut pas démontrer qu’Android refuse une proposition après révocation pendant une requête en cours. |
| Fallback gateway indisponible | **PASS** | Avec le gateway remplacé par un port loopback fermé, le backend a répondu 200, `agent_status=unavailable`, `FALLBACK_REPLY`, zéro proposition et zéro ligne de proposition persistée. |
| APK, SHA et certificat | **NOT_RUN** | Aucun APK préconstruit n’existe dans `android/app/build/outputs/apk`; le wrapper de build est `android/gradlew`. La compilation et la comparaison de certificat restent à faire pour la reprise Android. |
| Pixel / AVD Android 17/API 37 | **NOT_RUN** | Aucun accès ADB n’a été tenté. |

## Lecture des contrats et tests

- `backend/atlas_backend/openclaw_adapter.py` utilise `OPENCLAW_BASE_URL`, `OPENCLAW_GATEWAY_TOKEN_FILE`, `OPENCLAW_AGENT_ID` et `OPENCLAW_TIMEOUT_S`, appelle `/v1/chat/completions` avec un Bearer, et ne fournit pas d’interface d’outils. `chat_v2` absorbe les erreurs et renvoie le texte de fallback sans proposition. Le host-run a vérifié la requête réelle, la réponse, le fallback et le retrait de catégorie dans l’appel suivant.
- `V62RealGatewayContextBridgeTest#realGatewayUsesAccelerated25MinuteFocusContextAndAcceptedProposal` prépare le Focus/objectif synthétique, attend un adaptateur connecté et une réponse mentionnant les 25 minutes, vérifie une directive praticable, son acceptation, l’effet/journal et l’ACK backend, puis vérifie le contexte après révocation de `goals`.
- `V6RealAgentE2eInstrumentationTest#realCoachResponseCreatesDirectiveAndStartsFocus` couvre l’acceptation d’une directive et une proposition Focus par l’interface. `RemoteBackendE2eInstrumentationTest#runRequestedBackendScenario` couvre d’autres scénarios backend selon les arguments.
- `V62OpenClawClientInstrumentationTest#chatUsesRealGoalProgressAndPlanDropsGoalsAfterRevocation` valide la construction du contexte et le retrait de `goals` avec un client de test; ce n’est pas une réponse d’un gateway réel.
- `scripts/v622-s6-openclaw-real-recipe.sh` exige `emulator-5580` / API 34 et met ses temporaires sous `/tmp`. Il ne satisfait donc pas le gate de clôture Android 17/API 37 ni l’emplacement de stockage temporaire exigé par ce ticket.

## Reprise Android — parent

Le host-side réel est terminé. Pour le test instrumenté du parent, le gateway doit répondre sur loopback et le backend doit être relancé avec un runtime temporaire hors dépôt et hors `/tmp`; vérifier que `$XDG_RUNTIME_DIR` pointe bien vers un répertoire privé local avant création. Le token ATLAS doit être généré au hasard, enregistré en mode 600 dans ce runtime, et la base SQLite doit rester dans ce même répertoire.

Préparer les variables dans un shell hôte privé :

```sh
cd <worktree>/v62-openclaw-e2e
case "$(realpath "$XDG_RUNTIME_DIR")" in /tmp|/tmp/*) exit 1 ;; esac
export ATLAS_RUN_DIR="$(mktemp -d "$XDG_RUNTIME_DIR/atlas-openclaw.XXXXXX")"
chmod 700 "$ATLAS_RUN_DIR"
python3 -c 'import secrets; print(secrets.token_urlsafe(32))' > "$ATLAS_RUN_DIR/backend-token"
chmod 600 "$ATLAS_RUN_DIR/backend-token"
export ATLAS_TOKEN="$(cat "$ATLAS_RUN_DIR/backend-token")"
export ATLAS_PORT="$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1]); s.close()')"
printf '%s\n' "$ATLAS_PORT" > "$ATLAS_RUN_DIR/backend-port"
export ATLAS_DB_PATH="$ATLAS_RUN_DIR/atlas.sqlite3"
export ATLAS_AGENT_ADAPTER=openclaw
export OPENCLAW_BASE_URL=http://127.0.0.1:18789
export OPENCLAW_GATEWAY_TOKEN_FILE="$HOME/.config/atlas/openclaw-gateway-token"
export OPENCLAW_AGENT_ID=atlas
export OPENCLAW_TIMEOUT_S=60
PYTHONPATH="$PWD/backend" ATLAS_TOKEN="$ATLAS_TOKEN" ATLAS_DB_PATH="$ATLAS_DB_PATH" \
  ATLAS_AGENT_ADAPTER="$ATLAS_AGENT_ADAPTER" OPENCLAW_BASE_URL="$OPENCLAW_BASE_URL" \
  OPENCLAW_GATEWAY_TOKEN_FILE="$OPENCLAW_GATEWAY_TOKEN_FILE" OPENCLAW_AGENT_ID="$OPENCLAW_AGENT_ID" \
  OPENCLAW_TIMEOUT_S="$OPENCLAW_TIMEOUT_S" \
  python3 -m uvicorn atlas_backend.main:app --host 127.0.0.1 --port "$ATLAS_PORT" \
  --no-access-log --log-level warning
```

In a second private shell, copy only the runtime directory path from the first shell (never the token), load the port and token from its protected files, then verify gateway/backend health and Bearer status without printing either token. Keep the backend process running for instrumentation:

```sh
export ATLAS_RUN_DIR='<runtime directory created in the first shell>'
export ATLAS_PORT="$(cat "$ATLAS_RUN_DIR/backend-port")"
export ATLAS_TOKEN="$(cat "$ATLAS_RUN_DIR/backend-token")"
curl -fsS --max-time 3 http://127.0.0.1:18789/health -o /dev/null
curl -fsS --max-time 3 "http://127.0.0.1:$ATLAS_PORT/health" -o /dev/null
curl -fsS --max-time 3 -H "Authorization: Bearer $ATLAS_TOKEN" \
  "http://127.0.0.1:$ATLAS_PORT/v2/agent/status" -o /dev/null
```

Après sauvegarde durable app-only, vérification de l’appareil Android 17/API 37, et comparaison du certificat installé avec les APK de build, exécuter le test ciblé ci-dessous. Il utilise uniquement le port backend via `adb reverse`; le backend reste lié à `127.0.0.1` sur l’hôte. Ne pas employer `pm clear` ni désinstaller l’application.

```sh
cd <worktree>/v62-openclaw-e2e/android
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
sha256sum app/build/outputs/apk/debug/app-debug.apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
apksigner verify --print-certs app/build/outputs/apk/debug/app-debug.apk
apksigner verify --print-certs app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s "$ANDROID_SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$ANDROID_SERIAL" install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s "$ANDROID_SERIAL" reverse "tcp:$ATLAS_PORT" "tcp:$ATLAS_PORT"
adb -s "$ANDROID_SERIAL" shell am instrument -w -r \
  -e class dev.atlas.coach.app.V62RealGatewayContextBridgeTest#realGatewayUsesAccelerated25MinuteFocusContextAndAcceptedProposal \
  -e backend_url "http://127.0.0.1:$ATLAS_PORT" \
  -e backend_token "$ATLAS_TOKEN" \
  dev.atlas.coach.test/androidx.test.runner.AndroidJUnitRunner
```

### Oracle de reprise

Le test ciblé doit réussir avec `agent_status=connected`; la réponse doit référencer les 25 minutes du Focus synthétique et proposer une directive exploitable. L’acceptation doit laisser la proposition `EXECUTED`, une directive HOME issue de cette proposition, un ACK accepté et un effet dans le journal. La requête suivante après révocation doit conserver `focus`, retirer `goals` et supprimer les valeurs de l’objectif révoqué. Pour l’idempotence, rejouer exactement le même ACK sur le même `proposal_id`, puis vérifier une réponse stable et un seul effet durable. Pour la révocation en vol et le fallback, conserver deux assertions distinctes : aucune action ne doit être exécutée après retrait du mandat pendant la requête; gateway arrêté, le backend doit répondre proprement sans proposition ni action distante.

Après collecte expurgée des preuves, arrêter le backend et supprimer uniquement `$ATLAS_RUN_DIR`, qui a été créé pour ce run. Ne jamais inclure le token gateway, le token ATLAS ni les en-têtes Bearer dans les preuves.

## Sortie de ce run

- Parcours host-side réel exécuté via Python/Uvicorn sur loopback, deux appels au gateway OpenClaw réel et un appel fallback vers un port loopback fermé.
- Backend ATLAS : Bearer invalide 401, valide 200; health 200; ACK exact rejoué avec une ligne de journal.
- Réseau externe : aucun; uniquement `127.0.0.1`.
- Commandes de build, ADB et instrumentation : non exécutées.
- Pixel/AVD : non touché.
- Jetons : fichiers mode 600; aucune valeur de jeton imprimée ni incluse dans les preuves; runtime et bases SQLite nettoyés.
- Fichiers de preuves : ce rapport, `preuves-openclaw/preflight.txt` et `preuves-openclaw/host-run.txt`.
- Le SHA source évalué reste `8890046012965f3a3112ff0c419014c74e189d11`; le nouveau SHA de commit sera communiqué après mise à jour.
