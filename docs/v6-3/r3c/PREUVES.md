# ATLAS V6.3 — R3-C — Preuves

Chaque preuve indique : SHA source, SHA APK si applicable, environnement,
commande, horodatage, résultat brut ou résumé vérifiable, résultat attendu,
résultat observé, statut, limites. Les horodatages sont locaux
(`Europe/Paris`, +01:00) sauf mention UTC.

## Convention de pseudonymisation

- `PIXEL_SERIAL` : numéro de série exact conservé **hors dépôt** dans
  `~/atlas-backups/r3c/pixel-serial.txt` (0600).
- `$FUNNEL_PUBLIC_IP` : adresse publique résolue par DoH, conservée hors
  dépôt dans `~/atlas-backups/r3c/funnel-public-ip.txt` (0600).
- `$PC_PHYSICAL_IP` et `$PC_INTERFACE` : IP et interface physiques du PC,
  conservées hors dépôt dans `~/atlas-backups/r3c/pc-physical-ip.txt`
  (0600) ; `$PC_PUBLIC_IP` (IP publique du PC) n'est pas publiée.
  Exporter ces variables avant d'exécuter les commandes qui les utilisent.
- Le hostname Funnel `all.taildc7886.ts.net` est l'infrastructure **imposée
  par la mission** et n'est pas un secret ; il reste nécessaire aux commandes
  d'exploitation. Aucun token, clé privée ou contenu personnel n'est publié.
- Les documents R3-B ont été pseudonymisés le 2026-10-28 ; voir
  [`../r3b/REDACTION_IDENTIFIANTS.md`](../r3b/REDACTION_IDENTIFIANTS.md).
  Aucun SHA, résultat ni verdict R3-B n'a été modifié.

## P-BASE-01 — Git local et distant

| Champ | Valeur |
|---|---|
| SHA source | `eedf91502227297be5d2ff35524e79983ea36f49` |
| Environnement | PC hôte, dépôt principal |
| Commande | `git rev-parse HEAD` ; `git ls-remote --heads origin main` ; `git merge-base --is-ancestor <sha> origin/main` |
| Horodatage | 2026-10-28T16:52+01:00 |
| Attendu | `main` local = distant = base annoncée ; R3-B ancêtre |
| Observé | local et distant `eedf915…` ; `5795d37`, `738ef93`, `4be991d` ancêtres : OK |
| Statut | **PASS** |
| Limites | La mention « aucun push » du rapport R3-B est contredite par `origin/main` ; réconciliée sans réécriture |

## P-BASE-02 — CI sur `eedf915`

| Champ | Valeur |
|---|---|
| SHA source | `eedf91502227297be5d2ff35524e79983ea36f49` |
| Environnement | GitHub Actions, workflow `CI` |
| Commande | `gh run list --limit 10 --json …` |
| Horodatage | créé `2026-10-28T15:44:22Z`, terminé `2026-10-28T15:49:40Z` |
| Attendu | résultat réel connu avant toute modification |
| Observé | run `40718987949`, `status=completed`, `conclusion=success` |
| Statut | **PASS** |
| Limites | L'information « in_progress » du brief était périmée ; aucun succès n'est déclaré sans workflow terminé |

## P-BASE-03 — Artefacts APK et sauvegardes

| Champ | Valeur |
|---|---|
| Environnement | PC hôte, artefacts R3-B |
| Commande | `sha256sum android/app/build/outputs/apk/…` ; `apksigner verify --print-certs` ; `aapt2 dump badging` ; `sha256sum ~/atlas-backups/r3b/…` |
| Horodatage | 2026-10-28T17:05+01:00 |
| Attendu | Identifier sans ambiguïté le binaire et les sauvegardes |
| Observé | APK principal `057560940cf078bb90b19bb32a8d288fe3f294c8d2cbf5f256ada5e907033e8c` ; androidTest `5aef13210dc2760fb03fbf757f58b040a2cb2e20838e444427cec211dab70f04` ; signature debug `da3014662eb65e50a87ef364a2e583c331398795b222e3b37b1a16553a00eeee` ; `versionCode=1`, `versionName=0.1.0`, API 37 ; sauvegarde app-only `fbca987ad521f99bdc4c626b35367a1a9de674da12fdfc4fcf42c5417696aabd` (0600) ; APK rollback `66645143b0e6d861b90bbad4acfcc8fe85cae5c939e1d394e3e81c9ecad518b5` (0600) |
| Statut | **PASS (inventaire)** |
| Limites | Ces APK sont ceux de R3-B. **Aucun n'est une preuve du code R3-C.** Un nouveau couple APK sera construit depuis le SHA R3-C intégré |

## P-BASE-04 — Environnement

| Champ | Valeur |
|---|---|
| Environnement | PC hôte |
| Commande | `node --version`, `java -version`, `python3 --version`, `adb devices -l`, `tailscale version` |
| Horodatage | 2026-10-28T16:52+01:00 |
| Observé | Node v24.18.0 ; OpenJDK 21.0.12.1 ; Python 3.12.3 ; **aucun appareil ADB** ; Tailscale 1.104.4 |
| Statut | **PASS (inventaire)** |
| Limites | Le Pixel `PIXEL_SERIAL` est déconnecté ; toute recette Pixel est `BLOCKED_DEVICE` jusqu'à reconnexion et mandat |

## P-FUNNEL-01 — Diagnostic non destructif

| Champ | Valeur |
|---|---|
| SHA source | `eedf915…` + services de test R3-C |
| Environnement | PC hôte, `tailscaled` actif |
| Commande | `tailscale status` ; `tailscale funnel status --json` ; `tailscale netcheck` ; `systemctl is-active tailscaled` |
| Horodatage | 2026-10-28T16:52+01:00 |
| Attendu | Distinguer panne réseau, backend arrêté, proxy/relais/connecteur, TLS |
| Observé | `Self.Online=true`, `Health=[]` ; Funnel `8443 → 127.0.0.1:4400`, `AllowFunnel=true` ; netcheck UDP OK, IPv4 publique `$PC_PUBLIC_IP` (hors dépôt), DERP Francfort ; `tailscaled` actif |
| Statut | **PASS (diagnostic)** |
| Limites | Le plan de contrôle était injoignable en R3-B ; il est de nouveau joignable. Aucune configuration Tailscale modifiée |

## P-FUNNEL-02 — Chemin local rétabli

| Champ | Valeur |
|---|---|
| Environnement | services de test isolés |
| Commande | relais `atlas-paseo-relay:r3a` sur `127.0.0.1:4402` ; proxy `4400→4402` ; FastAPI `8100` ; connecteur |
| Horodatage | 2026-10-28T16:56+01:00 |
| Observé | `4402/health` 200 ; `4400/health` 200 ; `4400/ready` 200 ; `4400/metrics` 404 ; `8100/health` 200 ; `4401/metrics` `relay_connected=true` |
| Statut | **PASS** |
| Limites | Services isolés, adaptateur FastAPI `mock` ; aucune installation persistante |

## P-FUNNEL-03 — TLS et HTTP publics avec DNS indépendant

| Champ | Valeur |
|---|---|
| Environnement | PC, résolution DoH `1.1.1.1`, IP publique `$FUNNEL_PUBLIC_IP` |
| Commande | DoH A/AAAA ; `openssl s_client -connect $FUNNEL_PUBLIC_IP:8443 -servername all.taildc7886.ts.net` ; `curl --resolve all.taildc7886.ts.net:8443:$FUNNEL_PUBLIC_IP …` |
| Horodatage | 2026-10-28T16:57+01:00 |
| Attendu | TLS public valide, `/health` 200, `/ready` 200, `/metrics` 404 |
| Observé | A `$FUNNEL_PUBLIC_IP/.63/.145`, AAAA `2a00:dd80:3e::47e/…` ; TLSv1.3, `CN=all.taildc7886.ts.net`, `Verification: OK` ; `health_http=200 tls_verify=0`, `ready_http=200`, `metrics_http=404` |
| Statut | **PASS** |
| Limites | Connexion initiée par le PC via l'IP publique, sans MagicDNS ni `100.119.62.63` ; ce n'est pas encore une preuve depuis le réseau mobile Pixel |

## P-FUNNEL-04 — WSS public, paramètres invalides, absence d'exposition

| Champ | Valeur |
|---|---|
| Environnement | PC, IP publique du Funnel |
| Commande | `curl --http1.1 -H 'Connection: Upgrade' … https://all.taildc7886.ts.net:8443/ws?…` ; `curl … /metrics?…` ; `ss -ltnp` |
| Horodatage | 2026-10-28T17:05+01:00 |
| Attendu | `/ws` valide 101 ; `v=1`, query inconnue, `serverId` absent refusés 400 ; `/metrics` 404 ; ports internes non publics |
| Observé | `/ws?serverId=…&role=client&v=2` → `HTTP/1.1 101 Switching Protocols` ; `v=1` → 400 ; `serverId` absent → 400 ; query inconnue → 400 ; upgrade `/metrics` → 404 ; listeners `4400/4401/4402/8100` sur `127.0.0.1` uniquement ; `8443` sur `tailscale0` |
| Statut | **PASS** |
| Limites | Le 101 prouve l'upgrade et le filtrage ; la couche Noise/auth est prouvée par P-FUNNEL-05 |

## P-FUNNEL-05 — E2E public complet (Noise, auth, chat)

| Champ | Valeur |
|---|---|
| SHA source | `eedf915…` (code R3-B) + services de test |
| Environnement | `wss://all.taildc7886.ts.net:8443` via IP publique `$FUNNEL_PUBLIC_IP`, appareil synthétique `49f67626-…` |
| Commande | `ATLAS_E2E_PUBLIC_IP=$FUNNEL_PUBLIC_IP node scripts/r3b-public-e2e.mjs` |
| Horodatage | 2026-10-28T16:59+01:00 |
| Attendu | Noise `protected`, auth appareil, `backend.health`, `sync.status`, chat relayé, rejeu, idempotence, limite payload |
| Observé | **7/7 PASS** : `noise_protected_and_device_auth`, `backend_health_via_funnel` (`ok`), `sync_status_via_funnel` (`relay_connected=true`), `coach_chat_relayed` (`agent_status=mock`, 2 propositions), `coach_chat_replay_no_duplicate` (`replayed=true`), `coach_chat_idempotency_conflict` (`IDEMPOTENCY_CONFLICT`), `coach_chat_payload_limit` (`INVALID_REQUEST`) |
| Statut | **PASS (PC, ingress public)** |
| Limites | Adaptateur `mock`, pas OpenClaw réel ; connexion initiée par le PC ; pas encore de Pixel sur réseau mobile. Les preuves R3-B de chat OpenClaw réel restent historiques, pas des preuves R3-C |

## P-FUNNEL-06 — Chemin public via l'interface physique, ports internes fermés

| Champ | Valeur |
|---|---|
| Environnement | PC, interface `$PC_INTERFACE`, IP source `$PC_PHYSICAL_IP`, IP publique du Funnel `$FUNNEL_PUBLIC_IP` (valeurs hors dépôt) |
| Commande | `curl --interface "$PC_PHYSICAL_IP" --resolve all.taildc7886.ts.net:8443:"$FUNNEL_PUBLIC_IP" …` ; tests TCP vers `4400/4401/4402/8100` |
| Horodatage | 2026-10-28T17:13+01:00 |
| Observé | `health_http=200`, `local_ip=$PC_PHYSICAL_IP` (donc hors `tailscale0`) ; ports `4400`, `4401`, `4402`, `8100` **closed** sur l'IP publique |
| Statut | **PASS** |
| Limites | Le PC reste sur le même réseau domestique ; ce n'est pas un réseau mobile indépendant |

## P-FUNNEL-07 — Ancien appairage sans `coach.chat` via le Funnel public

| Champ | Valeur |
|---|---|
| Environnement | appareil synthétique `95a472e3-…`, capacités `sync.status` uniquement, IP publique |
| Commande | `ATLAS_E2E_CAPABILITIES=sync.status ATLAS_E2E_EXPECT_CHAT_UNAVAILABLE=1 node scripts/r3b-public-e2e.mjs` |
| Horodatage | 2026-10-28T17:13+01:00 |
| Observé | **4/4 PASS** : Noise/auth 101 protégé, `backend.health` ok, `sync.status` `relay_connected=true`, `coach.chat.request` → `OPERATION_UNAVAILABLE` ; session non fermée |
| Statut | **PASS (connecteur public)** |
| Limites | Le client de test est Node, pas l'app Android ; la correction Android COMPAT est couverte en JVM |

## P-FUNNEL-08 — FastAPI arrêté puis rétabli via le Funnel public

| Champ | Valeur |
|---|---|
| Environnement | FastAPI isolé `127.0.0.1:8100`, IP publique |
| Commande | arrêt contrôlé ; `ATLAS_E2E_EXPECT_HEALTH=BACKEND_UNAVAILABLE ATLAS_E2E_ONLY=health node scripts/r3b-public-e2e.mjs` ; relance ; même test avec `ok` |
| Horodatage | 2026-10-28T17:14+01:00 |
| Observé | arrêt : `kind=error`, `health_status=BACKEND_UNAVAILABLE`, session Noise toujours authentifiée ; relance : `kind=response`, `health_status=ok`, `fastapi_health=200` |
| Statut | **PASS** |
| Limites | Redémarrage d'une instance isolée, pas d'un service persistant |

## P-PC-SIGKILL — Résilience du connecteur

| Champ | Valeur |
|---|---|
| Environnement | connecteur réel, état `~/.local/state/atlas-relay` |
| Commande | identité/registre hashés ; `kill -9` ; relance `scripts/start.sh` ; attente métriques |
| Horodatage | 2026-10-28T17:14+01:00 |
| Observé | identité `7c7df954…` et hash registre `bb9ad1b1…` **identiques avant/après** ; `relay_connected=true` après relance |
| Statut | **PASS** |
| Limites | Un redémarrage complet réel du PC reste `NOT_RUN` |

## P-FUNNEL-09 — Chat OpenClaw réel via le Funnel public

| Champ | Valeur |
|---|---|
| Environnement | FastAPI isolé `127.0.0.1:8100`, `ATLAS_AGENT_ADAPTER=openclaw`, jeton existant 0600, IP publique `$FUNNEL_PUBLIC_IP`, appareil synthétique `49f67626-…` |
| Commande | `ATLAS_E2E_CHAT_MESSAGE='Ceci est un test synthétique ATLAS R3C. Réponds en une phrase, sans action.' node scripts/r3b-public-e2e.mjs` |
| Horodatage | 2026-10-28T17:19+01:00 |
| Observé | **7/7 PASS** : Noise/auth protégé, `backend.health` ok, `sync.status` `relay_connected=true`, chat `agent_status=connected`, réponse de 32 caractères, 0 proposition, rejeu `replayed=true`, idempotence `IDEMPOTENCY_CONFLICT`, limite payload `INVALID_REQUEST` |
| Statut | **PASS (PC public, OpenClaw réel)** |
| Limites | Message synthétique uniquement ; OpenClaw personnel non modifié ; une conversation synthétique a été créée côté gateway ; aucun test Pixel ni UI de provenance |

## P-CLEANUP-01 — Nettoyage des fixtures synthétiques

| Champ | Valeur |
|---|---|
| Environnement | connecteur réel, registre `~/.local/state/atlas-relay/devices.json` |
| Commande | `scripts/revoke-device.sh … <device>` pour `49f67626-…` et `95a472e3-…` ; `rm -f /tmp/atlas-r3c/device-r3c-*.json` |
| Horodatage | 2026-10-28T17:47+01:00 |
| Observé | 10 appareils au total, **0 actif, 10 révoqués** ; les deux appareils de test sont révoqués ; tokens temporaires supprimés ; `relay_connected=true`, `active_sessions=0` |
| Statut | **PASS** |
| Limites | L'identité X25519 et le registre historique sont conservés ; aucun appareil utilisateur n'a été touché |

## P-PIXEL-INSTALL-01 — Installation du couple APK R3-C sur Pixel

| Champ | Valeur |
|---|---|
| SHA source Android | `d2419b3` |
| APK principal | `365bd84a14ab05a35e2333688c57addb825b42aede8e7e2e1a11b7e0079cd4f3` |
| APK androidTest | `09ecfd7a3d527e98351e224d70b5f44b81c276e7e514300cd9fd859c99ac8c98` (inclut le test R3-C d'effacement) |
| Environnement | Pixel 9 Pro XL `PIXEL_SERIAL`, Android 17/API 37, utilisateur 0 |
| Commande | `adb install -r --user 0 …` ; hash du `base.apk` installé |
| Horodatage | 2026-10-28T18:16+01:00 |
| Observé | installation **Success** ; hash installé identique à l'APK hôte ; `versionCode=1`, `versionName=0.1.0`, signature debug `da301466…` ; données utilisateur 0 créées ; profils 12/13 non modifiés |
| Statut | **PASS** |
| Limites | L'app n'était pas installée pour l'utilisateur 0 au pré-vol (données absentes) ; aucun test de préservation des données R3-B n'est donc revendiqué. Voir `DECISIONS.md` |

## P-PIXEL-A3-01 — A3 Keystore, force-stop, redémarrage

| Champ | Valeur |
|---|---|
| Environnement | Pixel API 37, appareil synthétique `2bcb073d-…` puis `f3a2b54f-…` |
| Commande | `am instrument … R3bA3PairingInstrumentedTest` ; force-stop ; `R3bA3PersistenceInstrumentedTest` ; `adb reboot` ; rejeu persistance |
| Horodatage | 2026-10-28T18:17–18:19+01:00 |
| Observé | appairage **OK (1 test, 1,149 s)** ; persistance force-stop **OK (0,89 s)** ; redémarrage Android puis persistance **OK (1,439 s)** ; token chiffré Keystore déchiffré, préfs sans token en clair, marqueur A3 conservé, reconnexion sans ressaisie |
| Statut | **PASS** |
| Limites | Données utilisateur 0 créées pour R3-C (pas de données R3-B à préserver) |

## P-PIXEL-A4-01 — A4 révocation, fermeture et effacement

| Champ | Valeur |
|---|---|
| Environnement | Pixel API 37, appareils synthétiques dédiés |
| Commande | `R3bA4RevocationInstrumentedTest` avec révocation PC sur marqueur ; `R3cA4ClearInstrumentedTest` ; force-stop + `R3bA4AfterClearInstrumentedTest` ; `R3bA4RepairInstrumentedTest` |
| Horodatage | 2026-10-28T18:25–18:59+01:00 |
| Observé | `DEVICE_REVOKED` reçu, état `REVOKED`, erreur de révocation, aucune reconnexion authentifiée : **PASS** ; effacement explicite credential + alias Keystore et `PAIRING_REQUIRED` : **PASS (0,284 s)** ; relance sans reconnexion : **PASS (1,122 s)** ; réappairage explicite : **PASS (2,386 s)** ; **mais** `lastCloseCode` n'est pas `4001` : le relais ferme avec **1012 « Server disconnected »** |
| Statut | **FAIL A4-4 / PASS A4-5..A4-10** |
| Limites | Le relais épinglé ne propage pas le code 4001 du connecteur ; trois probes (public, proxy local, relais direct) observent 1012. Le test échoue à l'assertion 4001 avant l'effacement UI ; l'effacement a été validé par le même chemin de production `clearCredentials()`, pas par le clic UI. Détail dans `CORRECTIFS.md` et `RISQUES_RESIDUELS.md` |

## P-PIXEL-CHAT-01 — Chat réel Android via OpenClaw

| Champ | Valeur |
|---|---|
| Environnement | Pixel API 37, FastAPI isolé `openclaw`, Funnel public, appareil synthétique `0aba4868-…` |
| Commande | `am instrument … R3bRelayChatInstrumentedTest` |
| Horodatage | 2026-10-28T18:59+01:00 |
| Observé | **OK (1 test, 15,658 s)** : message relayé, réponse OpenClaw réelle, `agent_status=connected`, provenance « OpenClaw via PC », aucune action exécutée sans confirmation, brouillon/historique après rotation, aucun fallback HTTP direct |
| Statut | **PASS** |
| Limites | Message synthétique ; l'OpenClaw personnel n'a pas été modifié ; conversation synthétique créée côté gateway |

## P-PIXEL-NET-01 — NET-07 coupure et reprise

| Champ | Valeur |
|---|---|
| Environnement | Pixel API 37, Wi-Fi et données mobiles désactivés par le parent |
| Commande | `R3bNetworkStateInstrumentedTest#relayRecoversAfterForcedOffline` avec orchestration parent |
| Horodatage | 2026-10-28T19:04+01:00 |
| Observé | marqueur `{"state":"OFFLINE"}` ; reconnexion `RELAY_CONNECTED` ; conversation locale conservée ; **OK (1 test, 10,346 s)** |
| Statut | **PASS** |
| Limites | Le mode avion seul laissait la Wi-Fi active sur ce Pixel ; la coupure réelle a été obtenue en désactivant Wi-Fi + données. Le mode avion n'a donc pas produit OFFLINE à lui seul, ce qui est un comportement de l'appareil, pas un ERROR applicatif |

## P-PIXEL-NET-02 — NET-09 handover Wi-Fi / cellulaire / Wi-Fi

| Champ | Valeur |
|---|---|
| Environnement | Pixel API 37, réseau cellulaire actif puis retour Wi-Fi |
| Commande | `R3bNetworkStateInstrumentedTest#relayRemainsUsableAfterWifiCellularHandover` |
| Horodatage | 2026-10-28T19:05+01:00 |
| Observé | `Transports: CELLULAR` observé, retour `Transports: WIFI`, `RELAY_CONNECTED`, conversation conservée ; **OK (1 test, 9,931 s)** |
| Statut | **PASS** |
| Limites | Aucune mesure de débit ; pas de test 5G standalone séparé |

## P-PIXEL-UI-01 — UI-06, UI-07, UI-08

| Champ | Valeur |
|---|---|
| Environnement | Pixel API 37 |
| Commande | `input keyevent HOME`, relance, hash `atlas_local_state.xml` ; coupure réseau + capture ; `font_scale=1.3` + capture ; rotation paysage + capture |
| Horodatage | 2026-10-28T19:05–19:08+01:00 |
| Observé | HOME → launcher, relance ATLAS, hash local **inchangé** ; mode hors ligne : écran local « Proposition locale · disponible hors ligne », aucun crash ; police 1,3 : rendu complet 1008×2244 ; paysage : rendu complet 2244×1008 ; réglages restaurés (`font_scale=1.0`, rotation 0, mode avion 0) |
| Statut | **PASS UI-06/UI-07/UI-08 (police + rotation)** ; **PARTIAL UI-04 et TalkBack** |
| Limites | Saisie manuelle du token non rejouée par frappe UI (champ masqué et stockage Keystore vérifiés statiquement + A3) ; TalkBack non activé |

## P-PIXEL-DATA-01 — Préservation des données locales

| Champ | Valeur |
|---|---|
| Environnement | Pixel API 37, utilisateur 0 |
| Commande | hash `atlas_local_state.xml` avant/après HOME ; assertions du test réseau sur `agentConversation.size` ; marqueur A3 après reboot |
| Horodatage | 2026-10-28T18:17–19:08+01:00 |
| Observé | hash local identique après HOME/relance ; conversation conservée pendant OFFLINE et handover ; marqueur A3 conservé après reboot ; credentials effacés uniquement sur action explicite |
| Statut | **PASS (périmètre R3-C)** |
| Limites | Aucune donnée R3-B pour l'utilisateur 0 au pré-vol ; les hashes R3-B restent des témoins historiques |

## P-COMPAT-01 — Ancien appairage sans `coach.chat`

| Champ | Valeur |
|---|---|
| SHA source | `a162f70` (intégré sur `feat/atlas-v6-3-r3c-qualification`) |
| Environnement | JVM Android + Node 24 |
| Commande | `./gradlew :app:testDebugUnitTest --tests 'dev.atlas.coach.sync.relay.*' --max-workers=1` ; `npx vitest run test/r3c-compat.test.ts` |
| Horodatage | 2026-10-28T17:02+01:00 |
| Attendu | Session `RELAY_CONNECTED`, plan.revision utilisable, chat `OPERATION_UNAVAILABLE`, aucun close, aucune élévation |
| Observé | Gradle `BUILD SUCCESSFUL` ; Vitest `1 test passed` ; tests Android `RelayCapabilityNegotiationTest` 4 cas PASS |
| Statut | **PASS** |
| Limites | Node test utilise le canal chiffré simulé et un backend factice ; l'E2E public réel est P-FUNNEL-05 |

## P-RACE-01 — Courses consentement/révocation

| Champ | Valeur |
|---|---|
| SHA source | `22ba24f` |
| Environnement | JVM Android + Node 24 |
| Commande | `./gradlew :app:testDebugUnitTest --tests '…RelayConcurrencyGateTest' …` ; `npx vitest run test/r3c-race.test.ts` |
| Horodatage | 2026-10-28T17:04+01:00 |
| Attendu | Aucun envoi après révocation, ordre `DEVICE_REVOKED` avant `4001`, aucune réponse tardive acceptée, deux chemins de révocation |
| Observé | Gradle `BUILD SUCCESSFUL` ; Vitest **3/3 PASS** (polling, réception directe, écriture DEVICE_REVOKED bloquée) |
| Statut | **PASS** |
| Limites | Tests déterministes JVM/Node ; la course réelle sur Pixel reste à rejouer en vague C si l'appareil est disponible |

## P-NODE-FULL — Suite connecteur complète avec relais réel

| Champ | Valeur |
|---|---|
| Environnement | relais épinglé `127.0.0.1:4402` |
| Commande | `ATLAS_TEST_RELAY_URL=ws://127.0.0.1:4402 npm test` |
| Horodatage | 2026-10-28T17:03+01:00 |
| Observé | **16 fichiers, 78 tests PASS**, dont `relay.integration.test.ts` 1/1 |
| Statut | **PASS** |
| Limites | Le relais est un conteneur local épinglé ; ce n'est pas le Funnel public |

## P-NET-01 — Classification réseau OFFLINE / ERROR

| Champ | Valeur |
|---|---|
| SHA source | `d2419b3` |
| Environnement | JVM Android, tests déterministes |
| Commande | `./gradlew :app:testDebugUnitTest --tests 'dev.atlas.coach.sync.relay.*' --max-workers=1` |
| Horodatage | 2026-10-28T17:09+01:00 |
| Attendu | réseau → OFFLINE ; TLS/Noise/permission/inconnu → ERROR terminal ; auth → AUTH_REQUIRED ; révocation → REVOKED ; reprise réseau sans ERROR |
| Observé | **BUILD SUCCESSFUL** ; 14 cas `RelayNetworkClassificationTest` PASS, dont mode avion, Wi-Fi, données mobiles, perte Internet, relais injoignable, FastAPI arrêté, certificat TLS invalide, jeton invalide, révocation, close protégé, reprise réseau et échec I/O pendant le handshake |
| Statut | **PASS (JVM)** |
| Limites | La matrice réelle sur Pixel (mode avion, Wi-Fi/LTE, TLS) reste à rejouer en vague C |

## P-GATES-01 — Gates complètes sur le SHA intégré

| Champ | Valeur |
|---|---|
| SHA source | `d2419b3` |
| Environnement | PC hôte, JDK 21, Node 24, Python 3.12 |
| Commande | `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --max-workers=1` ; `backend/.venv/bin/python -m pytest -q` ; `ATLAS_TEST_RELAY_URL=ws://127.0.0.1:4402 npm test` |
| Horodatage | 2026-10-28T17:10+01:00 |
| Observé | Android : **100 suites, 712 tests, 0 échec/erreur/ignoré** ; lint **0 erreur, 71 avertissements** ; build APK principal et androidTest **PASS** ; backend **196 passed, 1 skipped** ; Node **17 fichiers, 81 tests PASS** |
| Statut | **PASS** |
| Limites | Ce SHA n'est pas encore celui du gel final ; les gates seront rejouées après revue et avant le gel APK |

## P-APK-R3C-01 — APK construits depuis le SHA intégré

| Champ | Valeur |
|---|---|
| SHA source | code Android `d2419b3` (inchangé par les commits docs suivants) ; arbre de build `d51525f` |
| Environnement | build debug **propre** (`./gradlew clean`), API 37 |
| Commande | `./gradlew clean :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --max-workers=1` ; `sha256sum` ; `apksigner verify --print-certs` ; `aapt2 dump badging` |
| Horodatage | 2026-10-28T17:25+01:00 |
| Observé | APK principal `365bd84a14ab05a35e2333688c57addb825b42aede8e7e2e1a11b7e0079cd4f3` ; APK androidTest `143aae62cba4eef4eca1579be36a8ca40be57a98a97fe1388b271e3546b8df43` ; signature debug `da301466…` ; `versionCode=1`, `versionName=0.1.0`, compileSdk 37 |
| Statut | **PASS (build propre)** |
| Limites | APK **non installé** sur Pixel ; aucune preuve Pixel pour ce couple. Les hashes diffèrent du build incrémental précédent (artefact debug non reproductible bit-à-bit) ; seuls ces hashes du build propre font foi |

## P-HARNESS-01 — Relais isolé reproductible

| Champ | Valeur |
|---|---|
| SHA source | `d2419b3` |
| Environnement | Docker, image `atlas-paseo-relay:r3a`, port loopback `4403` |
| Commande | `ATLAS_TEST_RELAY_PORT=4403 integrations/relay/pc/scripts/run-isolated-relay-tests.sh` |
| Horodatage | 2026-10-28T17:11+01:00 |
| Observé | relais démarré, **17 fichiers / 81 tests PASS**, conteneur `atlas-r3c-test-relay` supprimé |
| Statut | **PASS** |
| Limites | Dépend de Docker et de l'image locale épinglée ; sur un runner sans image, le harnais retourne `BLOCKED` |

## P-PROXY-01 — Suite proxy filtrant

| Champ | Valeur |
|---|---|
| Environnement | relais de test actif sur `4402`, port `4400` libéré |
| Commande | arrêt contrôlé du proxy de qualification ; `node --test integrations/relay/proxy/test` ; redémarrage |
| Horodatage | 2026-10-28T17:11+01:00 |
| Observé | **18 tests, 18 PASS, 0 FAIL** ; proxy redémarré, `local_health=200`, `public_health=200`, connecteur reconnecté |
| Statut | **PASS** |
| Limites | Le premier essai avait échoué en `EADDRINUSE` parce que le proxy de qualification occupait `4400` ; le rejeu avec port libre confirme le comportement |

## P-CI-01 — Durcissement CI (à rejouer sur la branche)

| Champ | Valeur |
|---|---|
| SHA source | `cd6e751` |
| Environnement | GitHub Actions |
| Commande | workflow `CI`, jobs `verify` et `relay-node` |
| Horodatage | préparation 2026-10-28T17:04+01:00 |
| Attendu | Android + backend + Node + proxy |
| Observé | run `40730173153` : `verify` PASS, `relay-node` FAIL (`stale-client-timeout.test.ts`, relais réel absent). Correctif : `test:unit` exclut les deux tests à relais réel. Run `40733692711` sur `a72ba9e` : **success**, `verify` et `relay-node` PASS. Le workflow compile désormais aussi `assembleDebugAndroidTest`. Dernier run requis après le commit de clôture. |
| Statut | **PASS sur `a72ba9e` ; dernier run en attente** |
| Limites | Aucun PASS CI pour le commit de clôture tant que son run n'est pas terminé avec `success` |

## P-CI-02 — Reproduction locale du job `relay-node`

| Champ | Valeur |
|---|---|
| SHA source | `c5ec1f4` (code `d2419b3`) |
| Environnement | Node 24.18.0, `integrations/relay/pc` |
| Commande | `npm ci` puis `ATLAS_TEST_RELAY_URL=ws://127.0.0.1:9 npm run test:unit` ; `ATLAS_TEST_RELAY_URL=ws://127.0.0.1:4402 npm test` |
| Horodatage | 2026-10-28T18:06+01:00 |
| Observé | sans relais : **15 fichiers, 79 tests PASS** ; avec relais : **17 fichiers, 81 tests PASS** |
| Statut | **PASS (local)** |
| Limites | Ce n'est pas le runner GitHub ; le job distant reste `NOT_RUN` |

## P-SUPERVISION-01 — Unités systemd et hygiène des secrets

| Champ | Valeur |
|---|---|
| Environnement | PC hôte |
| Commande | `systemd-analyze --user verify …` ; `stat -c '%a'` ; `check-secret.mjs` ; `rg` sur les logs |
| Horodatage | 2026-10-28T17:17+01:00 |
| Observé | unités relais et proxy **rc=0** ; `relay.env` 0600, état 0700, `identity.json`/`devices.json`/`operations.json` 0600, sauvegarde Pixel 0600 ; aucun motif de secret dans les logs `/tmp/atlas-r3c/*.log` |
| Statut | **PASS (statique)** |
| Limites | Unités non installées ; `linger` déjà actif non modifié ; redémarrage complet du PC `NOT_RUN` |

## P-DATA-01 — Sauvegarde et réversibilité

| Champ | Valeur |
|---|---|
| Environnement | `~/atlas-backups/r3b` |
| Commande | `sha256sum` ; `stat` ; lecture des permissions |
| Horodatage | 2026-10-28T17:05+01:00 |
| Observé | sauvegarde app-only `fbca987a…` 0600, APK rollback `66645143…` 0600, état connecteur `identity.json`/`devices.json`/`manifest.json` 0600 |
| Statut | **PASS (inventaire)** |
| Limites | Les hashes ATLAS ne sont pas une preuve d'intégrité exhaustive ; aucune restauration n'a été tentée pendant R3-C |
