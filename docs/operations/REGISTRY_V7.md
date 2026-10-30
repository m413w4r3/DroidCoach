# ATLAS V7 — Registre unique de campagne

**Mission :** rendre le coach OpenClaw réellement utilisable à distance depuis le Pixel.
**Base SHA :** `b759bdafbf0c67eda1a14ae2cf2f3fac75239e4c` (local = `origin/main` vérifié le 2026-10-30).
**Branche d'intégration locale :** `feat/atlas-v7-remote-operational` (aucun push sans autorisation distincte).
**Environnement cible de clôture :** Pixel physique Android 17 / API 37, réseau cellulaire, sans Wi-Fi.
**Modèle des sous-agents :** `codex:gpt-6-luna`, effort effectif `xhigh` — vérifié dans les métadonnées Paseo de chaque session. Aucune substitution.
**Règle :** une notification de fin n'est pas une acceptation. Chaque résultat est examiné, revu, puis intégré localement par le parent.

## 1. Contraintes d'exécution

- 6 enfants simultanés maximum ; 3 implémentations indépendantes maximum ; 1 propriétaire d'écriture par fichier ; 1 seul opérateur ADB/Pixel/AVD à la fois.
- Aucune sous-délégation par un enfant.
- Worktree isolé par ticket d'écriture ; les audits V7-00 utilisent des worktrees détachés en lecture seule.
- Aucun push, PR, merge distant, rotation de secret, redémarrage de service ou action Pixel sans autorisation ciblée.
- Aucun secret, token, donnée personnelle ou contenu de sauvegarde dans une preuve.
- Pas de polling : reprise sur notification de fin.

## 2. Tickets V7-00 — pré-vol (lecture seule)

| TASK_ID | Domaine | Session Paseo | Modèle/effort | Worktree | Statut |
|---|---|---|---|---|---|
| V7-00-SCOUT-OPENCLAW | Gateway, agent atlas, plugin, tools effectifs, risque exec | `dfbcbace-8ec2-4616-9926-fe1ff19d3d53` | codex/gpt-6-luna, xhigh | `v7-00-openclaw` | FINISHED — plugin chargé, six outils au catalogue, `tools.effective`=0 ; risque exec historique P1 |
| V7-00-SCOUT-CORE | routes V4 → adapter, mock vs réel, auth, chat loop | `37de4829-9f1b-45d5-8a4e-6b04ccb480bb` | codex/gpt-6-luna, xhigh | `v7-00-core` | FINISHED — rapport reçu |
| V7-00-SCOUT-RELAY | relais, proxy, Noise, Funnel, A4-4, ports | `dcac7b01-9c4f-4107-a964-53105e390c9f` | codex/gpt-6-luna, xhigh | `v7-00-relay` | CANCELLED — bloqué sur permission systemd expirée ; preuves partielles conservées |
| V7-00-SCOUT-RELAY-R2 | relais/proxy/Funnel/A4-4, sans systemd ni sudo | `b8162eed-eb00-4e13-9257-505879b98626` | codex/gpt-6-luna, xhigh | `v7-00-relay` | FINISHED — rapport reçu ; état hôte non vérifié dans son sandbox ; V4 connecteur corrigé par V7-02A ; Funnel `8443→4400` **confirmé par le parent** ; 4400/4401/4402 absents ; A4-4 `4001→1012` non clos |
| V7-00-SCOUT-ANDROID | appairage, consentement, chat, sync, inbox, ACK | `ca750c95-bc9d-4a8c-b906-62a7f11a6c31` | codex/gpt-6-luna, xhigh | `v7-00-android` | FINISHED — rapport reçu |
| V7-00-SCOUT-CONTRACTS | contrats V4, couverture de tests, exécution locale ciblée | `d512d169-29b3-41da-b897-65de487b1781` | codex/gpt-6-luna, xhigh | `v7-00-contracts` | FINISHED — 119 tests V4 PASS_LOCAL ; parité erreurs catalogue FAIL |
| V7-00-SCOUT-GITSEC | Git, secrets, processus/ports, disque, sécurité | `834fb661-5291-463b-8461-b00ebbcb2b6c` | codex/gpt-6-luna, xhigh | `v7-00-gitsec` | FINISHED — aucun P0 ; réconciliation worktrees demandée |

**Livrable V7-00 :** carte des processus et tableau `EXISTE / CONFIGURÉ / ACTIF / TESTÉ_RÉEL / BLOQUÉ`, blocages classés par dépendance, chemin minimal vers le premier échange réel sur Pixel.

## 3. Tickets d'implémentation

| TASK_ID | Objectif | Dépendances | Statut | Scope d'écriture prévu | Oracle |
|---|---|---|---|---|---|
| V7-01B-ISOLATED-CORE-PROOF | Core isolé 18100 → Gateway réel → agent atlas → six outils, sans toucher au live | V7-00 | FINISHED — commit `4f8d844` ; Core réel PASS, tour réel PASS (`agent_status=ok`, `tool_state=completed`), aucun fallback mock ; `SIX_TOOLS_EFFECTIVE: FAIL` (`tools.effective` = 0 outil ATLAS, 66 exclus) ; anomalie `exec tool.result ok` dans la trajectoire malgré `excluded` ; corrélation native 20:18:44 douteuse (GET interne 7 ms après le POST) | worktree `v7-01b-isolated-core-proof`, rapport `docs/operations/evidence/V7-01B_ISOLATED_CORE_PROOF.md` | tour réel `agent_status=ok`, tool call, proposition unique, `tools.effective` sans outil interdit |
| V7-01A-COMPOSE-OPENCLAW | Plomberie Compose/entrypoint pour adapter réel et token Gateway optionnel | V7-01B | INTEGRATED — merge `3d8d7bd` ; 17 tests PASS sur SHA fusionné ; revue P2 close | `compose.openclaw.yaml`, entrypoint, tests, docs | défaut démo mock inchangé ; override réel testé ; secret hors Git |
| V7-02A-RELAY-V4-ENABLE | Activer V4 dans le connecteur avec bearer Android dédié et liaison d'appareil | V7-00 | INTEGRATED — merge `8841d6f` ; revue ACCEPTED ; 91/91 tests PASS sur SHA fusionné | `integrations/relay/pc/src|test|scripts`, README | V4 off par défaut ; refus si non appairé ; bearer Android, jamais token service ; tests unitaires |
| V7-01C-BOUNDED-TOOL-ERRORS | Erreurs d'arguments/nom d'outil bornées au lieu d'abandon du tour | V7-01B | ACCEPTED — commit `9633841d59e620f937bf9aa65e05a57b1c8aefce` ; revue indépendante ACCEPT (P2 logs non testés, aucune fuite observée), pytest 36/36 PASS en clone read-only | `openclaw_adapter.py`, tests chat V4 | outil inconnu/invalide → role=tool borné, aucune exécution, tour continue |
| V7-03B-ANDROID-RELAY-V4 | Transport V4 Android via relais pour sync/inbox, sans HTTP direct | V7-02A | REJECTED par revue — commit `76f29277387689078f2844c47fd08487bf4264c0` ; P1 : `request_id` payload ≠ `request_id` enveloppe, rejet PC avant Core pour six snapshots, pull et chat ; P2 codec erreurs V4 ; suite JVM auteur PASS mais non rejouée en revue | Android sync/v4 + inbox + tests | mapping chemins→opérations, bearer absent côté relais, refus fail-closed, tests JVM |
| V7-03C-RELAY-REQUEST-ID | Corriger l'alignement enveloppe/payload V4 et le codec d'erreur V2 | V7-03B review | RUNNING — `c1386943-960d-4153-bfc4-4633601834a9`, worktree `v7-03c-relay-request-id`, base `76f2927` | Android relais/V4 + tests/docs | tests qui échouent sur la base, PASS complet JVM sur le SHA corrigé |
| V7-01E-HEALTH-MODE | Faire rapporter à `/health` l'adaptateur réellement actif | V7-01C | CORRECTION — commit initial `80c6cea` REJECT P1 (valeur d'adaptateur arbitraire injectable) ; correction `17907b33137237cc669fc39d90ecd02711213003` borne `mock|openclaw` et rejette le canari ; revue ciblée en cours | `backend/atlas_backend/main.py`, tests backend | mock par défaut, openclaw avec adaptateur réel, pas de réseau ni secret |
| V7-LIVE-A-OPENCLAW | Durcissement OpenClaw live : Code Mode/exec, allowlist, token plugin | audit `19154ae`, autorisation A | APPLIED — backup `~/.openclaw/backups/v7-code-mode-20261030T213932Z` ; config valid ; Gateway running ; preuve trajectoire 9 outils sans exec, `atlas_get_day_plan` natif reçu par Core en 403 (capabilities off) | `~/.openclaw/openclaw.json`, token plugin privé | aucun exec/wait Code Mode ; six outils catalogués ; rollback config+token |
| V7-01-OPENCLAW-REAL | Core → Gateway réel, sans fallback mock ; six outils effectifs ; exec interdit | V7-00, V7-01B | NOT_STARTED | backend adapter/config/tests | requête Core traitée par l'agent atlas réel + six appels natifs + refus exec |
| V7-02-TRANSPORT | relais PC, proxy, Noise, entrée TLS/WSS, opérations V4 | V7-00 | NOT_STARTED | integrations/relay/**, unités/scripts | client externe autorisé → Core ; refus inconnu/révoqué |
| V7-03-ANDROID-REMOTE | appairage, consentement, chat distant, sync, inbox/ACK | V7-01, V7-02 | NOT_STARTED | android/** (scope par ticket) | chat réel depuis Pixel cellulaire + sync versionnée |
| V7-04-COACH-LOOP | actions locales, report, proposition, application, ACK | V7-03 | NOT_STARTED | android/** + docs | fil rouge 14 étapes avec nouvelle version observée |
| V7-05-COMPOSE | étendre Docker sans régression | V7-01..V7-03 | NOT_STARTED | compose.yaml, backend/**, integrations/relay/** | pile reproductible, backup/restore |
| V7-06-RESILIENCE | offline, reconnexion, redémarrages, révocation, restauration | V7-04 | NOT_STARTED | tests/preuves | GATE 6/7 |
| V7-07-DOCS | documentation finale alignée sur l'état livré | tous | NOT_STARTED | docs/operations/** | documents vérifiés contre SHA/APK |

## 3bis. Constats consolidés au 2026-10-30 (V7-00 partiel + exécution V7)

- **Core live** : conteneur healthy, mais `ATLAS_AGENT_ADAPTER=mock` et les sept capabilities V4 (`day_plan.read`, `activity_status.read`, `plan_revision.propose`, `usage_summary.read`, `goal_progress.read`, `training_plan.read`, `coach.chat`) sont `false`. `/health` répond `mode=mock`.
- **Plugin OpenClaw** : `atlas_core_tools` est activé, capabilities `true`, `coreBaseUrl=http://127.0.0.1:18100` (aucun listener), `tokenFile` en 0600 mais **ne correspond pas** au token outil du Core live. La politique agent `atlas` contient `profile=minimal`, `deny=[gateway,session_status,presence]` et `alsoAllow` des six outils ; `tools.effective` reste à requalifier.
- **Gateway** : 2026.10.5, 127.0.0.1:18789, `chatCompletions.enabled=true` ; aucun redémarrage effectué.
- **Relais** : le protocole et le mapping V4 existent déjà (`v4.coach.chat.request`, snapshots, pull, ACK). Le connecteur désactive V4 par construction : `createConnector` ne transmet ni `v4Enabled` ni `pairedDeviceToken`. Les ports 4400/4401/4402 et 18100 ne sont pas en écoute.
- **Entrée publique** : DNS résolu ; `tailscale funnel status` (lecture parent, 2026-10-30) confirme `tcp://all.taildc7886.ts.net:8443 → tcp://127.0.0.1:4400` et le service privé `8770`. Les ports 4400/4401/4402 sont absents ; `GET https://all.taildc7886.ts.net:8443/health` échoue avant toute réponse (TLS terminé, aucun upstream). Aucune preuve distante valide à ce stade ; A4-4 reste non qualifié.
- **Android** : appairage, consentement, chat V4, sync, inbox et ACK existent. Le toggle V4 est désactivé par défaut, aucune donnée d'appairage/consentement n'est provisionnée, et la sync/inbox utilisent un transport HTTP direct vers le Core (non joignable depuis le cellulaire), distinct du chemin relais du chat.
- **Gateway — preuves directes du parent** : un chat minimal sans outil renvoie HTTP 200 ; un outil simple et les **six définitions exactes** renvoient HTTP 200 avec `tool_calls` (`atlas_get_day_plan`) ; le tour de continuation assistant `tool_calls` + `role=tool` renvoie HTTP 200 et une réponse finale. Depuis le conteneur isolé, le payload exact de l'adaptateur avec le token PID 1 renvoie aussi 200 + `tool_calls`. Le Gateway, l'auth, le réseau host, les schémas et le format de continuation sont donc compatibles.
- **Cause racine probable de l'échec isolé** : l'adaptateur interrompt tout le tour (`UPSTREAM_UNAVAILABLE`) lorsqu'un tool_call reconnu a des arguments JSON/schéma invalides (par ex. `atlas_get_activity_status` avec `{}`), au lieu de renvoyer un résultat d'erreur borné. Le contrat V4 exige un résultat borné et la poursuite du tour. Correctif V7-01C en cours ; la preuve isolée sera rejouée après intégration.
- **Sécurité** : aucune valeur de secret n'a été affichée pendant les audits ; les comparaisons de tokens ont été faites par égalité booléenne. Les tests directs Gateway n'ont utilisé que des messages synthétiques.
- **Relais R2** : l'état hôte n'est pas vérifiable depuis son sandbox (`ss`/`tailscale` refusés, `listen EPERM`) ; aucun service n'a été démarré. Le mapping Funnel est incohérent entre les preuves historiques (`8443→4400`) et l'observation de repli (`443→4400`) ; A4-4 reste un écart `4001→1012` non expliqué. Le connecteur V4 est désormais activable par V7-02A, mais l'identité appareil→bearer et le chemin public restent à qualifier en runtime.
- **Double chemin d'outils observé** : la 1re session isolée a exécuté `atlas_get_day_plan` nativement (GET interne Core corrélé) ; le 3e tour réussi a exécuté l'outil côté adaptateur (aucun GET interne, `tool_state=completed`). Les deux chemins existent donc. Il faut choisir le chemin canonique, prouver les six outils et la proposition dessus, et garantir l'absence de double exécution avant de qualifier `GO_OPENCLAW_TOOLS`.
- **Revues indépendantes** : V7-01C `ACCEPT` (aucun P0/P1, un P2 de preuve sur les logs) ; V7-03B `REJECT` (P1 request_id enveloppe≠payload, P2 codec d'erreur V4, preuves de tests incomplètes). V7-03C corrige ces points. Une notification de fin ne vaut pas acceptation.
- **Intégration** : V7-01C mergé (`9331f88`) et V7-01B evidence mergé (`2cb1214`) sur la branche d'intégration ; suite backend complète `344 passed, 1 skipped` avec `ATLAS_DB_PATH` temporaire. V7-03B n'est pas intégré.
- **Relais public C (partiel)** : relais `atlas-v7-relay` (image r3a), proxy 4400, connecteur `relay_connected=true` ; Funnel TLS `8443→4400` ; `r3b-public-e2e.mjs` PASS pour Noise/auth/health/sync/chat V1/rejeu/conflit/payload. Un device `pixel-v7` est appairé (token privé, `server_id=atlas-pc-7c7df95437e1bd5c65ba74abdf8181c9`). V4 et Pixel non encore testés ; V7-02B corrige le chargeur `relay.env` qui rejetait les clés V4 ; le connecteur courant a été lancé avec l'environnement en processus.
- **Pixel backup** : ticket envoyé à l'opérateur préflight pour une archive `run-as` privée, hash/vérification, et empreinte du signataire APK installé avant toute installation. La Keystore Android n'est pas incluse dans un backup app-only ; un ré-appairage peut être nécessaire.
- **Pixel préflight** (`01eace63-b56f-4ce1-beb3-e532a46adb05`, codex/gpt-6-luna/xhigh vérifié) : Pixel 9 Pro XL `komodo`, Android 17/API 37, ABI arm64-v8a, ADB USB, package `dev.atlas.coach` 0.1.0 (code 1, debuggable, targetSdk 36). `allowBackup=false` : le backup app-only devra passer par `run-as` (debuggable), pas par `adb backup` ; sauvegarde non encore faite. Aucun APK candidat/signature vérifiée ; installation non tentée. Appareil prêt, mais D reste bloqué tant que C et l'APK V7 ne sont pas prêts.
- **Audit V7-01B terminé** (`19154ae`, rapport `V7-01B_READONLY_AMBIGUITIES_AUDIT.md`) :
  - **`EXEC_ANOMALY: CONFIRMED — CODE_MODE`** : le modèle a réellement demandé `exec` avec `code`/`title` et la cellule JavaScript s'est terminée avec succès, dans trois sessions synthétiques. La configuration n'active pas explicitement `tools.codeMode`; l'audit relie la surface à Code Mode (façade JS, distincte du shell mais capacité d'exécution de code). **P0 sécurité : blocage live.**
  - **`NATIVE_TOOL_PROOF: BLOCKED`** : six outils plugin et six outils client homonymes ; OpenClaw priorise le client. Le GET interne n'a pas d'identifiant corrélable et survient après `session.ended`; la preuve native `PASS` du rapport initial est requalifiée.
  - **`TOOLS_EFFECTIVE: FAIL`** : 66 outils Core `excluded`, aucun outil ATLAS ; l'aperçu n'est pas l'inventaire exact d'un run Code Mode.
  - **Fix proposé (test seulement)** : tester le grant `atlas_core_tools` avec `profile=minimal` et les mêmes refus, dans une config jetable ; ne pas passer en `full`. La désactivation de Code Mode/`exec` doit être traitée séparément comme précondition P0.
- **Décision de sécurité** : aucun chat distant ni déploiement OpenClaw live avant désactivation vérifiée de Code Mode/`exec`, `process`, `code_execution` et de toute capacité équivalente, avec preuve sur trajectoire réelle et absence de régression des six outils client.
- **Catalogue Gateway (lecture parent)** : `tools.catalog` liste bien les six outils `atlas_*` sous `plugin:atlas_core_tools`, tous `optional: true`, plus d'autres plugins. Le catalogue ne prouve donc pas la projection session ; `tools.effective` reste FAIL. La politique agent contient `profile=minimal`, `deny=[gateway,session_status,presence]`, `alsoAllow=[six outils]` ; la cause de l'absence des six dans la projection est encore à établir.

## 4. Verdicts

| Verdict | État | Preuve / limite |
|---|---|---|
| GO_CORE | GO_LIVE (B) | Core live `mode=openclaw`, sept capabilities V4 vraies, chat V4 réel HTTP 200 `agent_status=ok`, image `00b88ef…`, volume conservé ; `adapter.connected=false` reste un champ à expliquer, non bloquant pour le chat |
| GO_OPENCLAW_TOOLS | PARTIAL — A+B | Code Mode/exec désactivé et vérifié ; six outils catalogués ; plugin natif atteint le Core live et reçoit le gate `CONSENT_REQUIRED` ; chemin client disponible ; preuve des six en exécution avec données consenties après D |
| GO_REMOTE_NETWORK | GO_PUBLIC_V1 (C partiel) | Funnel `8443→4400` confirmé ; relais 4402, proxy 4400, connecteur `relay_connected=true` ; WSS public Noise/auth/V1 PASS ; V4/Pixel et A4-4 non qualifiés |
| GO_ANDROID_SYNC | BLOCKED | V7-03C en cours ; transport relayé non encore accepté ; aucun reçu runtime |
| GO_REMOTE_CHAT | PARTIAL (B) | Chat V4 réel live PASS en local ; chat V4 depuis Pixel via relais non exécuté ; Code Mode corrigé |
| GO_PLAN_REVISION | NOT_RUN | aucune proposition appliquée puis ACK |
| GO_DOCKER_OPERATIONS | GO_LIMITED (B) | Bascule Core live avec backup SQLite, image candidate, healthcheck, rollback image conservé ; pile complète relais encore non testée |
| GO_PIXEL | PASS_PARTIEL (historique) | APK UI installé ; pas d'E2E réel |
| GO_PRODUCT_REMOTE | NOT_RUN | interdit sans chat réel + fil rouge sur Pixel |

## 5. Règles de preuve

Chaque preuve cite : SHA source, environnement, commande, résultat `PASS / FAIL / BLOCKED / NOT_RUN`, APK si applicable, session et modèle/effort effectifs. Un mock, un test local, un catalogue statique ou un conteneur healthy ne vaut jamais une preuve runtime réelle.
