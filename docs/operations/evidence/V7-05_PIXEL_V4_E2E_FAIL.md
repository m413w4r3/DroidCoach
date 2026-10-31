# V7-05 — scénario V4 réel sur Pixel : FAIL (run unique)

Date : 2026-10-31 (UTC)
Branche : `feat/atlas-v7-remote-operational`
SHA de preuve : `fdaef7b38601b8143a023615de559b9954cf7d53`
Source du test : `V7RemoteV4InstrumentedTest.kt`, commit `6de83c8`, mergé `e52a07d`
Appareil : Pixel 9 Pro XL, USB, Android 17 / API 37, `arm64-v8a`
Opérateur unique : `01eace63-b56f-4ce1-beb3-e532a46adb05`, provider Codex,
modèle `gpt-6-luna`, effort effectif `xhigh` (vérifié dans les métadonnées Paseo).
APK app : SHA-256 `0d0ce524f1166690f5399ea7197ab4eb3b39bfa93604d3feb98613d75bc57ca4`.
APK test : SHA-256 `94f24d4917e6fe01a91e60f43cbc4b1b10019e193df1cb6191ec345051b53250`.
Seed : plan `b0902f1b-8404-4ed7-a614-96aeb93a6bb1`, version 2, 1 activité synthétique.

Aucun secret, token, contenu de conversation ou donnée personnelle n’est reproduit.
Aucune valeur de requête (request_id/idempotency_key) n’est publiée.

## Commande exécutée une seule fois

```sh
timeout 600s adb -d shell am instrument -w -r \
  -e class dev.atlas.coach.e2e.V7RemoteV4InstrumentedTest \
  -e test_action apply \
  dev.atlas.coach.test/androidx.test.runner.AndroidJUnitRunner
```

- Une seule exécution ; aucun `pm clear`, aucune désinstallation, aucun token touché.
- Code processus `0`, mais runner **`FAILURES!!!`**, `numtests=1`.
- Échec après **61,6 s**, pendant l’attente du reçu V4 initial.
- Tag `ATLAS_V7_V4_E2E` : **aucune ligne non sensible**.

## Assertion exacte

```text
initial V4 consent and day-plan receipt timed out: v4_code=UNAVAILABLE;
day_freshness=Aucun reçu; day_consent=GRANTED:2/2;
status_consent=GRANTED:0/2; plan_receipt=null; plan_version=2
```

Conséquences observées : le chat V4 réel, la proposition A0, l’application locale
`v2→v3` (+10 min) et l’ACK durable n’ont **jamais** été atteints. Le plan est resté en
version 2. Aucun `GO_PRODUCT_REMOTE` ne peut être prononcé.

## Ce qui est prouvé

1. **Core live sain et joignable.** `docker logs atlas-core-atlas-core-1` contient
   exactement deux `POST /v4/sync/consent-state` → **200**, à
   `2026-10-31T00:23:51.286831784Z` et `2026-10-31T00:23:51.452352841Z`. Aucun autre
   appel V4 dans la fenêtre : aucun `day-plan-snapshot`, aucun chat, aucun pull, aucun ACK.
2. **Connecteur : deux consentements complétés.** Le journal privé
   `~/.local/state/atlas-relay/operations.json` contient deux entrées distinctes
   `v4.consent.state.upsert`, toutes deux `completed_redacted`, avec
   `idempotencyKey`, `requestHash` et `businessHash` différents. Leurs `expiresAt`
   correspondent à `2026-10-31T00:33:51Z` ; avec le TTL de 10 min, elles ont été créées
   à `00:23:51Z`, c’est-à-dire pendant ce run. Core et connecteur ont donc bien traité
   et complété les **deux** consentements.
3. **Appairage complet.** Le device `pixel-v7` possède les 14 capabilities, dont
   `v4.consent.state.write`, `v4.day_plan.snapshot.write`, `v4.activity_status.write`,
   `v4.proposal.ack` et `v4.coach.chat`. L’hypothèse d’une capability manquante est
   exclue pour ce run.
4. **Relais public opérationnel après l’essai.** `/health` et `/ready` publics et le
   proxy local répondent **200** ; `proxy.log` montre deux `ws_upgrade` à
   `00:23:50.829Z` et `00:23:50.832Z`, puis aucun autre.
5. **Échec côté Android sur le second consentement.** `day_consent` est passé à
   `GRANTED:2/2` (reçu traité et acquitté), mais `status_consent` est resté
   `GRANTED:0/2`. Le code de présentation final est `UNAVAILABLE`, sans reçu de plan.
   Le worker `CoreV4SyncWorker.syncConsentFirst` envoie DAY_PLAN puis ACTIVITY_STATUS ;
   une exception de transport sur le second enregistre exactement
   `CoreV4FailureKind.UNAVAILABLE` et interrompt la passe avant le push du plan.
6. **Nettoyage partiel.** La révocation des consentements n’a **pas** été confirmée
   par synchronisation car le relais était déconnecté au moment du cleanup ; la
   révocation locale a été tentée. Les workers Core V4 ont été annulés sans échec
   signalé. Aucun échec de cleanup autre que la confirmation de révocation n’a été
   rapporté.

## Ce qui n’est pas prouvé

- **Le maillon exact qui a perdu ou retardé le reçu ACTIVITY_STATUS** : côté Android
  (timeout de transport, corrélation, acquittement), côté relais (retour), côté
  connecteur (session/replay) ou cycle de vie de la session (deux upgrades à 3 ms
  d’écart, déconnexion ultérieure).
- **Corrélation des noms d’outils Core/OpenClaw : NOT_RUN.** Aucun chat V4 ni aucun
  tool call n’a eu lieu : le tag `ATLAS_V7_V4_E2E` est vide et le Core n’a reçu aucun
  `POST /v4/agent/chat` pendant le run. Il n’y a donc rien à corréler.
- Les métriques du connecteur (`requests_total=10`, `errors_total=3`,
  `latency_max_ms=15034`) **ne sont pas attribuables à V7-05** : le connecteur avait
  déjà servi un chat V2 à `23:57:16Z` et d’autres trafics depuis son démarrage. Elles
  ne sont donc pas utilisées comme preuve de ce run.
- Aucune cause racine n’est établie : le diagnostic indépendant conclut explicitement
  **`cause racine UNKNOWN`** et ne fournit qu’une hypothèse dominante (voir ci-dessous).
  Il n’a reçu aucun accès ADB et n’a modifié aucun fichier ni état live.

## Diagnostic indépendant (V7-05-DIAG-01)

- Session `81c27313-820a-470a-90f7-68ea8e9af00e`, worktree détaché
  `/tmp/atlas-v7-05-diag-ro`, HEAD `fdaef7b`, worktree propre.
- Profil vérifié par le parent dans les métadonnées Paseo : provider Codex, modèle
  `gpt-6-luna`, `thinkingOptionId=xhigh`, effort effectif **`xhigh`**. L’agent n’a pas
  pu auto-vérifier son profil car l’accès MCP Paseo lui a été refusé par le parent,
  précisément pour interdire toute orchestration ; la vérification est parentale.
- Lecture seule : aucun ADB, aucun test, aucune écriture, aucun redémarrage.

**Conclusion : FAIL confirmé ; cause racine UNKNOWN.** L’hypothèse la mieux étayée est
que Core a accepté le second consentement mais que son reçu n’a pas été appliqué
localement, par exemple après un timeout du transport relayé (8 s → `UNAVAILABLE`).
Les 200 Core et les entrées `completed_redacted` ne prouvent pas la réception du reçu
par Android.

Points vérifiés dans le code :

- Chemins vers `UNAVAILABLE` : `TIMEOUT`, `EXPIRED`, `QUEUE_FULL`, capacité relayée
  absente, ou certaines erreurs distantes dont `BACKEND_UNAVAILABLE`. Une fermeture ou
  annulation se classe plutôt `OFFLINE`, un reçu mal formé `PROTOCOL`
  (`RelayCoreV4HttpTransport.kt:135-153,193-250`, `RelayBackendTransport.kt:120-201`,
  `CoreV4SyncClient.kt:215-240,319-350`).
- Le worker parcourt les consentements séquentiellement ; le verrou du store protège
  chaque lecture/écriture, pas l’aller-retour réseau. Les travaux immédiat et périodique
  ont des noms uniques distincts et aucun verrou global de worker n’apparaît : un
  chevauchement est possible, sans preuve qu’il se soit produit
  (`CoreV4SyncWorker.kt:726-742`, `AndroidSyncStorage.kt:290-341`).
- `setV4ChatEnabled(true)` peut forcer un redémarrage si le relais est déjà
  connecté/en connexion, puis `connect()` force aussi `startTransport` : cela peut
  expliquer les deux upgrades. Les upgrades précèdent le premier POST Core d’environ
  0,45 s et le test attend la connexion avant d’accorder les consentements ; sans
  worker préexistant en vol, ces deux upgrades ne suffisent pas à expliquer la perte
  du second reçu. Aucune upgrade ultérieure n’est rapportée, donc aucune reconnexion
  n’est étayée (`RelaySettingsController.kt:230-239,296-390`,
  `V7RemoteV4InstrumentedTest.kt:158-197`).
- Replay `completed_redacted` : pas de `BACKEND_UNAVAILABLE` dû à la rédaction dans le
  même `OperationExecutor` vivant. `persistJournal()` rédige une copie disque ; l’entrée
  mémoire conserve `completed` et sa réponse. Après redémarrage, le journal chargé
  contient `completed_redacted` et un replay renverrait `BACKEND_UNAVAILABLE` ; aucun
  redémarrage du connecteur n’est établi pendant le run
  (`operations.ts:244-305,320-360`, `connector.ts:72-78`).
- Le worker vérifie que tous les consentements non inconnus sont synchronisés avant de
  poursuivre vers le plan (`CoreV4SyncWorker.kt:114-124`) ; l’absence de POST
  `day-plan-snapshot` est donc cohérente avec `ACTIVITY_STATUS 0/2`. `recordFailure`
  n’enregistre qu’un code global, sans catégorie ni identité de worker
  (`AndroidSyncStorage.kt:659-665`).

Vérification discriminante restante, non réalisable sans instrumentation : corréler le
démarrage/redémarrage du connecteur autour des deux POST, l’issue d’envoi WebSocket de
chaque réponse et le code de fermeture de session. Une future exécution autorisée
devrait journaliser : ordinal de worker, catégorie/version, étapes
`dispatch → réponse reçue → reçu validé → ACK appliqué`, durée, motif d’échec,
génération de session et code de fermeture — sans UUID, secret ni contenu de
conversation.

## État résiduel (relevé read-only, 2026-10-31)

Relevé unique après l’essai, commandes séparées, aucune mutation :

- API : **37**. Le nombre d’appareils n’a pas été revérifié pendant cette passe ; le
  relevé utilise `adb -d` (appareil unique) et fait suite au contrôle USB initial.
- **DAY_PLAN** : `state=REVOKED`, `version=3`, `synced_version=2`.
- **ACTIVITY_STATUS** : `state=REVOKED`, `version=3`, `synced_version=0`.
- **Code de présentation** : `UNAVAILABLE`.
- **Receipt du plan** : aucun receipt correspondant trouvé.
- **Plan du jour** : version **2**, `APPROVED`, date `2026-10-31`, 1 activité —
  l’application `v2→v3` n’a pas eu lieu.
- **Inbox V4** : fichier absent (`NOT_RUN`) — aucun pull de proposition n’a eu lieu ;
  le nombre d’ACK pending n’est pas mesurable.
- **Instrumentation** : aucun processus `instrument` / `dev.atlas.coach.test` détecté.
- **Relais/V4** : le cleanup du test appelle `disconnect()` et force
  `v4ChatEnabled=false` ; le relais était déconnecté au moment du cleanup. Cet état
  n’a pas été re-mesuré dans le relevé read-only.

Lecture : le cleanup a bien appliqué la révocation **locale** (version 3) des deux
consentements, mais aucune des deux révocations n’est synchronisée (DAY_PLAN reste
`synced_version=2`, ACTIVITY_STATUS `0`). Le Core peut donc encore détenir le
consentement DAY_PLAN `GRANTED` v2. **La prochaine exécution autorisée devra d’abord
synchroniser ces révocations** avant tout nouveau test. Aucune synchronisation n’a été
tentée après le run : le relais était déconnecté et le téléphone a été rendu au
propriétaire.

## Limites et suite

- **FAIL** à la première synchronisation V4 ; aucune preuve de chat, proposition,
  application ou ACK.
- Le budget de corrections du test est épuisé ; **aucune relance en boucle** n’est
  autorisée. Le propriétaire récupère le téléphone.
- Toute correction ou nouvelle exécution exige d’abord le diagnostic indépendant,
  puis une autorisation ciblée et une nouvelle preuve liée à un SHA exact.
- Verdicts associés : `GO_ANDROID_SYNC` reste **BLOCKED**, `GO_PLAN_REVISION`
  **NOT_RUN**, `GO_REMOTE_CHAT` **PARTIAL (B)**, `GO_PRODUCT_REMOTE` **NOT_RUN**.
