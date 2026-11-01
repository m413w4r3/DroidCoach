# ATLAS V7-06D — Fiabilité remote et révocation A4-4 : rapport final

**Statut du document :** `CLÔTURE DE CAMPAGNE — deux P1 diagnostiqués et corrigés
localement avec tests discriminants ; P2 de provenance APK↔SHA fermé par un
mécanisme vérifié ; P1 puis P2 de revue indépendante corrigés ; revue finale
sans P0/P1, avec un P2 documentaire de libellés corrigé et confirmation delta
`06d30ed` **ACCEPTED** ; aucune qualification physique exécutée (`NOT_RUN`).`
**Campagne :** V7-06D « Fiabilité remote et révocation A4-4 ».
**BASE_SHA :** `9ad90c7a079736ffc5f7bb6a7f0d4ee3eed70580`.
**Branche :** `feat/atlas-v7-06d-remote-hardening`.
**SHA de build de provenance (APK) :** `cf82cd02a8e00f75d1cd7d476a84691a276f3011`
**SHA de code final (correctifs harnais P1/P2) :** `bfb1965` — le code Android
est identique entre `cf82cd0` et `bfb1965` (`git diff cf82cd0..bfb1965 --
android/` vide) ; le HEAD documentaire final ne contient que des changements de
docs après ces SHA.
**Registre :** `docs/operations/REGISTRY_V7_06D.md`.
**Diagnostic :** `docs/operations/V7-06D_DIAGNOSTIC.md`.
**Date :** 2026-11-01.

## 1. Résultat exécutif

Les deux P1 de V7-06C sont traités à l'échelle du code et des tests déterministes :

1. **Blocage chat** : l'audit A1 a établi un **deadlock G↔C complet et
   atteignable** — un thread de dispatch tient le consent gate **G** et attend le
   moniteur **C** de `PaseoRelayClient` dans `sendTextFrame`, tandis qu'un callback
   OkHttp tient **C** et attend **G** dans `pump()`. Un test rouge déterministe le
   reproduit sur BASE ; le correctif supprime le cycle, borne l'attente de G et
   distingue un timeout avant envoi d'un timeout après acceptation. La cause du
   run historique V7-06C reste **UNKNOWN** (aucun thread dump), mais le mécanisme
   atteignable est prouvé et supprimé. Verdict `GO_CHAT_CONCURRENCY`.
2. **A4-4** : le refus serveur était correct, mais le connecteur émettait la trame
   pré-auth `DEVICE_REVOKED` en protocole **V1** à un client **V2**, que le parseur
   strict Android rejetait (`ERROR`, `close_code=null`). Le connecteur respecte
   désormais le protocole du hello et vérifie le token **avant** de révéler la
   révocation. Android conserve l'état `REVOKED` après le close relay `1012` et la
   sonde instrumentée est renforcée (état + type d'échec). La sonde n'a pas été
   exécutée sur appareil : `GO_A4_4_CLIENT` reste **NO GO / NOT_RUN**.
3. **Provenance APK↔SHA** : un manifeste de build est généré depuis la copie
   compilée, embarqué dans les APK app et AndroidTest, vérifié avant
   instrumentation, et sérialisé dans les proofs. Le build final porte
   `source_sha=cf82cd0…`, `worktree_clean=true`, et les deux APK ont été vérifiés.
   Aucun run appareil n'a été exécuté. Verdict `GO_APK_PROVENANCE` avec limitation.

Aucun push, PR, merge distant, service live modifié, ADB ou appareil physique n'a
été utilisé. Les acquis V7-06A/B/C ne sont pas réécrits.

## 2. Périmètre et provenance

- Base et branche vérifiées ; arbre propre avant chaque intégration.
- Trois audits indépendants A1/A2/A3 en lecture seule (`codex/gpt-6-luna`,
  `xhigh`, `collaborationMode=Plan`, worktrees détachés).
- Trois tickets d'implémentation maximum en parallèle (X chat, Y A4-4, Z
  provenance), fichiers disjoints, aucune sous-délégation.
- Aucun ADB, AVD, Pixel, Docker de campagne ou réseau externe pour les
  implémentations ; les contrôles live ont été strictement en lecture seule.
- Preuves V7-06C conservées sans réécriture.

Commits intégrés (branche locale) :

| Ticket | Commits agent | Commits intégrés |
|---|---|---|
| Y — A4-4 | `686d437`, `b822192`, `207bea2` | `c6de88f`, `d3619d4`, `c1bb1d1` |
| Z — provenance | `04960c6`, `4c8b691`, `49ef354` | `173dd01`, `8d0aa1c`, `7b0ae5a` |
| X — concurrence chat | `f6e8659`, `df8f8ae` | `f0a2099`, `813432b` |
| Docs | — | `9d1b93a`, `cf82cd0`, puis HEAD documentaire final |

## 3. Diagnostic

Voir `V7-06D_DIAGNOSTIC.md`. Points établis :

- **Chat** : cycle G→C / C→G complet ; timeout de 55 s inopérant car `pump()`
  précède `pending.done.await()`. Autres hypothèses classées : assemblage
  synchrone du contexte, famine worker/audit, garde de génération sur révocation
  de catégorie, transition de session, acceptation socket sans réception
  connecteur.
- **A4-4** : branche pré-auth `DEVICE_REVOKED` sans `incomingProtocol` → enveloppe
  V1 ; parseur Android V2 strict → `ERROR` + `abort(4003)` ; relais Paseo qui
  transforme le close serveur en `1012` ; `revoked_at` vérifié avant le token.
- **Provenance** : aucun manifeste de build, aucun contrôle APK installé↔construit,
  aucun SHA source dans le proof.

## 4. Correctif concurrence du chat

### 4.1 Test rouge et reproduction

`RelayGateCycleTest.callbackWaitingForConsentGateCannotBlockTheNextSocketWrite`
(commit rouge `f6e8659`, intégré `f0a2099`) force le cycle avec des barrières
explicites. Exécuté par le parent sur BASE :

```text
RelayGateCycleTest > callbackWaitingForConsentGateCannotBlockTheNextSocketWrite FAILED
java.lang.AssertionError ... RelayGateCycleTest.kt:107
second request was not captured while its dispatch deadline was active; likely G↔C deadlock.
"relay-gate-holder" RUNNABLE ... ReentrantLock$NonfairSync
"relay-cycle-reader" WAITING on ReentrantLock$NonfairSync owned by "relay-gate-holder"
  at AgentConsentGate.withLock(...)
  at RelayBackendTransport.pump(...)
```

Durée : 2,247 s, échec borné (pas de hang de suite). Le premier point de
divergence est `QUEUED`/`DISPATCHED` sans `SOCKET_SEND_ACCEPTED` ni requête
connecteur.

### 4.2 Correctif

Commit `df8f8ae`, intégré `813432b` :

- pumps déclenchés par callbacks déplacés sur un exécuteur dédié ; le thread
  OkHttp qui tient **C** n'attend plus **G** ;
- attente de **G** bornée (`tryWithLock` par deadline de requête ; attente
  callback bornée + reprise asynchrone différée) ;
- **T** relâché avant tout appel `client.abort/stop/reconnect/sendTextFrame` ;
  `abort` de protocole déplacé hors de la section **T** ;
- `TIMEOUT_NOT_SENT` distinct de `TIMEOUT` : un timeout avant le point
  d'acceptation socket est définitif et n'écrit rien ; après acceptation, le
  résultat reste incertain et n'est pas rejoué ;
- exécuteur callback arrêté dans `close()` et recréé par `start()` ;
- diagnostics bornés `USER_PERSISTED`, `WORK_SUBMITTED`, `QUEUED`,
  `BEFORE_DISPATCH`, `PAYLOAD_READY`, `SOCKET_SEND_ACCEPTED`, `RESPONSE_RECEIVED`,
  `CORRELATED`, `UI_RESULT`, avec tag de corrélation haché et aucun contenu brut.

Invariants conservés : G couvre contrôle final → payload → écriture ; révocation
locale immédiate ; requête déjà envoyée non rejouée ; `request_id`/idempotence
stables ; aucune augmentation de timeout pour masquer le défaut.

### 4.3 Tests

| Oracle | Résultat |
|---|---|
| Rouge G↔C sur BASE | **PASS (rouge reproduit)** par le parent |
| Même test sur le code corrigé | **PASS** |
| `RelayConcurrencyGateTest` (révocation, fan-out session) | **PASS 3/3** |
| `RelayBackendTransportTest` (timeouts, close, génération, reprise) | **PASS 24/24** |
| `CoreV4DiagnosticsTest` (étapes, absence de contenu/UUID brut) | **PASS 4/4** |
| `RelayCoreV4HttpTransportTest` | **PASS 12/12** |
| Suite JVM complète intégrée | **PASS 909/909**, 119 fichiers, 0 échec/erreur/ignoré |
| Lint hors ligne | **PASS** |
| Qualification appareil | **NOT_RUN** |

Le correctif est une suppression de deadlock atteignable, pas une attribution
rétroactive certaine du run V7-06C.

## 5. Correctif A4-4

Commit connecteur `b822192` (intégré `d3619d4`) :

- `sendError` de la branche pré-auth `DEVICE_REVOKED` reçoit `incomingProtocol` ;
  un client V1 reçoit V1, un client V2 reçoit V2 ;
- le hash du token est vérifié avec `timingSafeEqual` **avant** `revoked_at` :
  token correct + révoqué → `DEVICE_REVOKED` ; mauvais token + révoqué →
  `AUTH_FAILED` (aucune divulgation) ; appareil inconnu → `AUTH_FAILED` ;
  anti-rejeu du nonce conservé après ces contrôles ;
- close 4001 et grâce existante conservés ; relais non modifié.

Android `207bea2` (intégré `c1bb1d1`) :

- politique de présentation : `REVOKED` reste terminal face aux callbacks
  `OFFLINE`/`ERROR`/`AUTH_REQUIRED`/reconnexions, y compris le close `1012` ;
  une connexion/déconnexion/reconfiguration explicite réinitialise ;
- `lastFailureKind` exposé et préservé contre les échecs réseau ultérieurs ;
- sonde `V7_06C_RevokedDeviceReconnectInstrumentedTest` **renforcée** :
  `REVOKED` + `REVOKED` ou `AUTH_REQUIRED` + `AUTHENTICATION` uniquement ;
  `ERROR`/`close_code=null` reste un échec ; borne 30 s conservée ;
- `REVOKED` n'est jamais créé par une panne réseau : il provient uniquement de la
  trame `DEVICE_REVOKED` authentifiée et parsée sous le protocole négocié.

Tests :

| Oracle | Résultat |
|---|---|
| Rouge connecteur : V2 hello révoqué → enveloppe V1 | **PASS (rouge reproduit)** sur BASE, `protocol` V1 reçu au lieu de V2 |
| Enveloppe V2 `DEVICE_REVOKED`, close 4001 | **PASS** |
| Mauvais token + révoqué → `AUTH_FAILED`, jamais `DEVICE_REVOKED` | **PASS** |
| Token correct + révoqué → `DEVICE_REVOKED` V1/V2 | **PASS** |
| Node connecteur | **PASS 114/114**, 18 fichiers |
| JVM politique d'état + enveloppe V1 rejetée sur transport V2 | **PASS** (inclus dans 909/909) |
| Compilation de la sonde QA | **PASS** (`assembleQaAndroidTest`) |
| Sonde instrumentée stricte sur appareil | **NOT_RUN** |

Limites : le relais déployé transforme le close 4001 en `1012` ; la preuve A4-4
repose sur la trame applicative authentifiée, pas sur le code de fermeture.
Aucune modification du relais n'est faite. `GO_A4_4_CLIENT` n'est **pas** accordé.

## 6. Provenance APK↔SHA

Commits `04960c6` (tests), `4c8b691`/`49ef354` (implémentation, intégrés
`173dd01`/`8d0aa1c`/`7b0ae5a`) :

- tâche Gradle générant `atlas-build-provenance.json` depuis la source compilée
  (SHA Git, arbre, propreté, variante, IDs, versions, SDK, scénario) ;
- manifeste embarqué dans les assets des APK app **et** AndroidTest ;
- runner : vérification du manifeste, des métadonnées APK, du certificat de
  signature, des SHA-256 des APK installés **avant** `am instrument` ;
- proof : objet `provenance` complet, incluant `installed_apk_sha256` (hashes
  installés égaux aux hashes construits) ; post-hoc sans provenance →
  `INCONCLUSIVE` ;
- nombre exact de tests d'instrumentation requis (le seul code
  `INSTRUMENTATION_CODE: -1` ne suffit plus) ;
- timeouts ADB/install/instrumentation bornés ;
- correctif de suffixe QA pour garantir un segment Android valide commençant par
  une lettre.

**P1 de revue indépendante et correction.** La revue a trouvé que
`verify_installed_artifacts` ajoutait `installed_apk_sha256` au proof, puis que
le validateur strict le rejetait comme champ inconnu, bloquant le run avant
`am instrument`. Correction `1031a1a` (rouge `c599661`, intégrés
`7ea8c58`/`69d0db6`) : le champ est intégré au schéma, validé comme
`{app, android_test}` de SHA-256 minuscules et comparé aux hashes construits ;
le rejet des champs inconnus reste strict ; une régression couvre le chemin
complet. Le wrapper V7-06C applique la même validation.

**P2 de revue delta et correction.** La revue delta a confirmé le P1 corrigé et
relevé deux P2 : le wrapper acceptait des champs de provenance inconnus, et le
test de succès mockait la frontière du vérificateur. Correction `4ebdf5f`
(rouge `052ca80`, intégrés `fded03d`/`bfb1965`) : le wrapper exige désormais le
schéma exact (clés inconnues refusées), un test exécute la vraie fonction
`verify_installed_artifacts` avec des primitives bas niveau simulées et valide
le proof fusionné, et le schéma post-hoc reflète les mêmes clés avec
`verification_result=INCONCLUSIVE`. Les libellés de statut obsolètes sont
corrigés dans les documents finaux.

Build final de la campagne (worktree propre, `HEAD=cf82cd0…`, code Android
inchangé par les correctifs harnais `69d0db6`/`bfb1965`) :

| Élément | Valeur |
|---|---|
| APK app QA | `1e31a6437065a0b6788e1c2d66238ab00f34f850e54e5195b5b5b34cf5ace7b8` |
| APK AndroidTest QA | `05dd465be19f969d46573ac6f8c8604860cdf6aa17b536ad094e73acef8d25fd` |
| Manifeste canonique (identique dans les deux APK) | `8d678af5b2dca8197b580a962a52078901e884bc24e5dcae7ad2f443cc815bb7` |
| Source / arbre | `cf82cd02a8e00f75d1cd7d476a84691a276f3011` / `1d7c1a6069abc2878aa11805f29b347863cbd047` |
| Variante / SDK | `qa` / compile `37.2`, min `26`, target `36` |
| Version | code `1`, nom `0.1.0-qa` |
| Signature (app et test) | certificat debug SHA-256 `da3014662eb65e50a87ef364a2e583c331398795b222e3b37b1a16553a00eeee` |
| Scénario | `v7-06d-final` |

Tests : harnais **27/27 PASS** (après corrections P1/P2), builds QA PASS,
manifests app/test identiques, gate installé↔construit couvert par tests
négatifs. Aucun run appareil ; le gate installé n'a donc pas été exercé sur un
téléphone dans V7-06D. Le code Android étant identique entre `cf82cd0` (build
APK) et `bfb1965` (correctifs harnais), les hashes APK ci-dessus restent valides
pour les deux SHA.

## 7. Qualification d'intégration

Sur le SHA intégré `cf82cd0` (Android/Node/backend, worktree propre) et le SHA
de code final `bfb1965` (correctifs harnais P1/P2 ; Android, Node et backend
inchangés) :

| Suite | Résultat |
|---|---|
| Android JVM `:app:testDebugUnitTest --offline --rerun-tasks` | **909/909 PASS**, 0 échec/erreur/ignoré, 119 fichiers |
| Android lint `:app:lintDebug --offline` | **PASS** |
| Build `:app:assembleDebug` | **PASS** |
| Builds QA `assembleQa`, `assembleQaAndroidTest` | **PASS** |
| Node connecteur `npm run test:unit` | **114/114 PASS**, 18 fichiers |
| Proxy `node --test integrations/relay/proxy/test` | **19/19 PASS** |
| Harnais `python3 -m unittest discover -s scripts/v7-06c/tests` | **27/27 PASS** (après corrections P1/P2) |
| Backend `python3 -m pytest -q` | **399 passed / 1 skipped**, 43 subtests (la découverte pytest inclut aussi les tests du harnais) |
| `py_compile` Python modifiés | **PASS** |
| `git diff --check` | **PASS** |

Aucune recette physique, aucun AVD, aucun ADB, aucun Docker de campagne, aucun
réseau externe. Les scénarios backend réels V7-06C ne sont pas rejoués : le code
backend n'est pas modifié et leurs preuves restent valides dans leur périmètre.

## 8. Verdicts

| Verdict | Décision | Portée |
|---|---|---|
| `GO_CHAT_CONCURRENCY` | **GO** | Deadlock G↔C reproduit par test rouge sur BASE, supprimé, test vert et suite JVM 909/909 ; attentes bornées et identités préservées. Ne prouve pas la cause historique. |
| `GO_CHAT_RELIABILITY_QA` | **NOT_RUN** | Aucune autorisation physique ; aucune série de démarrages froids ; une relance PASS ne serait pas une preuve suffisante. |
| `GO_REVOKED_DEVICE_SERVER` | **GO** | Refus connecteur conservé et testé (token correct → `DEVICE_REVOKED`, mauvais token → `AUTH_FAILED`) ; preuves V7-06C inchangées. |
| `GO_A4_4_CLIENT` | **NO GO — NOT_RUN** | La sonde stricte renforcée n'a pas été exécutée sur appareil ; les tests déterministes ne remplacent pas ce PASS. |
| `GO_APK_PROVENANCE` | **GO_WITH_LIMITATIONS** | Chaîne source→manifeste→APK vérifiée sur le build `cf82cd0` ; P1 et P2 de revue corrigés (`bfb1965`, rouge→vert), revue finale sans P0/P1 ; gate installé↔construit testé, mais pas exercé sur téléphone dans V7-06D. |
| `GO_REMOTE_V4_PUBLIC` | **GO_WITH_LIMITATIONS (QA APPLY/REJECT)** | Inchangé V7-06C ; aucun nouveau run public ; la fiabilité chat publique n'est pas requalifiée. |
| `GO_PIXEL_PRODUCT` | **NO GO** | Application produit non testée. |
| `GO_PRODUCT_REMOTE` | **NO GO** | Application produit distante non testée. |

## 9. Sécurité et identités résiduelles

Recontrôle lecture seule du 2026-11-01, sans modification :

- connecteur `devices.json` : 19 entrées, 17 révoquées ; **2 non révoquées** :
  `563a01fa…` (`pixel-v7`, 14 capabilities dont V4) et `f4315995…`
  (`r3c-pixel-final`, 6 capabilities V1/V2) ;
- Core live `atlas-core-atlas-core-1` healthy ; `device_ref` `android-9a3f0f75…` :
  `DAY_PLAN` et `ACTIVITY_STATUS` `GRANTED` version 2, `updated_at`
  `2026-10-31T00:23:51Z` ; aucune projection, proposition ou ACK live ;
- aucune révocation, rotation, redémarrage ou effacement effectué.

### Demande d'autorisation ciblée (non exécutée)

- **Ressources** : devices connecteur `pixel-v7` et `r3c-pixel-final` ;
  `device_ref` Core `android-9a3f0f75…` ; bearer Android live associé.
- **Sauvegarde préalable** : copie privée 0600 de `devices.json`,
  `operations.json` et export lecture seule des consentements Core, avec hashes.
- **Commandes prévues** : `node dist/main.js revoke-device <device_id>` pour
  chaque device, vérification `devices.json`, test de reconnexion refusée,
  vérification qu'aucune nouvelle session/opération n'est acceptée.
- **Rollback** : restauration du registre sauvegardé uniquement en cas de
  révocation erronée, puis nouvelle vérification ; une révocation volontaire
  n'est pas annulée automatiquement.
- **Rotation du bearer Core** : décision **séparée** ; peut nécessiter un
  réappairage/reconfiguration de clients, un redémarrage contrôlé et une mise à
  jour du client. Aucune rotation n'est exécutée sans accord explicite et
  sauvegarde vérifiée.

## 10. Nettoyage et inventaire

- Aucun ADB, Pixel, AVD, émulateur ou instrumentation exécuté.
- Aucun conteneur, stack, service ou réseau de campagne démarré ; les conteneurs
  live n'ont été lus qu'en lecture seule.
- Sessions Paseo : A1/A2/A3 (audits), X/Y/Z (implémentations), relecture
  indépendante ; aucune sous-délégation ; six enfants simultanés maximum.
- Worktrees temporaires sous `/tmp` : audits et implémentations ; les worktrees
  de validation rouges sont supprimés après usage ; branches locales conservées.
- Aucun push, PR ou merge distant ; aucun secret ou donnée personnelle dans les
  preuves.

## 10bis. Revue indépendante

- Session `5f9adb37-57bc-4068-9aa8-53899f44f00b`, `codex/gpt-6-luna`, effort
  effectif `xhigh`, `collaborationMode=Plan`, worktree détaché
  `/tmp/atlas-v7-06d-review-ro`, lecture seule.
- SHA revu : `b163202f8f0aee6d1ef5d101b180432967a2646b` (code `cf82cd0…`,
  delta docs-only). Aucun fichier modifié, aucun test/ADB/Docker/réseau.
- **Verdict initial : `CORRECTION_REQUIRED`.** Un P1 confirmé : le runner
  ajoutait `installed_apk_sha256` au proof puis le validateur strict le rejetait
  (`PROOF_PROVENANCE_FIELD_MISSING`), bloquant l'instrumentation. Un P2 de
  libellés de progression obsolètes.
- Le P1 est corrigé `1031a1a` (rouge `c599661`, intégrés
  `7ea8c58`/`69d0db6`) : champ intégré au schéma, validé et comparé aux hashes
  construits ; régression couvrant le chemin complet. Le P2 documentaire est
  corrigé dans les documents finaux.
- Recommandations initiales : `GO_CHAT_CONCURRENCY` GO,
  `GO_REVOKED_DEVICE_SERVER` GO, `GO_A4_4_CLIENT` NO GO/NOT_RUN,
  `GO_APK_PROVENANCE` NO GO en attente de revue du SHA corrigé,
  `GO_CHAT_RELIABILITY_QA` NOT_RUN, `GO_REMOTE_V4_PUBLIC`
  GO_WITH_LIMITATIONS hérité, `GO_PIXEL_PRODUCT`/`GO_PRODUCT_REMOTE` NO GO.
- **Revue delta** `c2c2abd5-0340-4bfe-8fe6-35e5cc6bfc15` sur `0c19a3f` :
  P1 confirmé corrigé ; deux P2 relevés (wrapper acceptant des clés inconnues,
  test de succès mockant la frontière du vérificateur) et libellés de statut
  obsolètes. Correction Z3 `4ebdf5f` (rouge `052ca80`, intégrés
  `fded03d`/`bfb1965`) : schéma exact côté wrapper, test exécutant la vraie
  fonction `verify_installed_artifacts`, schéma post-hoc aligné. Verdicts de la
  revue delta : `GO_CHAT_CONCURRENCY` GO, `GO_REVOKED_DEVICE_SERVER` GO,
  `GO_APK_PROVENANCE` GO_WITH_LIMITATIONS (chaîne logicielle), `GO_A4_4_CLIENT`
  NO GO/NOT_RUN, `GO_CHAT_RELIABILITY_QA` NOT_RUN,
  `GO_REMOTE_V4_PUBLIC` GO_WITH_LIMITATIONS hérité, produit NO GO.
- **Revue finale** `19c375fd-9e02-48ac-9fc8-c8c928d5562a` (`codex/gpt-6-luna`,
  effort effectif `xhigh`, worktree `/tmp/atlas-v7-06d-review3-ro` en lecture
  seule) sur `88ac0197cfb60c5743482a022c0a5848ad011b97` (code `bfb1965`) :
  **aucun P0, aucun P1**, un P2 documentaire — les libellés de statut de revue
  étaient devenus obsolètes. Le schéma exact du wrapper, le champ
  `installed_apk_sha256` validé/égal aux hashes construits, le test exécutant la
  vraie fonction `verify_installed_artifacts` et l'absence de changement
  Android/Node/backend sont confirmés. Le P2 est corrigé dans le commit
  documentaire final ; aucun code n'a été modifié. La revue n'a exécuté aucun
  test/build/ADB/Docker/réseau. Les huit recommandations restent : chat
  concurrence GO, refus serveur GO, provenance GO_WITH_LIMITATIONS, A4-4 client
  NO GO/NOT_RUN, fiabilité QA NOT_RUN, remote public V4 GO_WITH_LIMITATIONS
  hérité, produit Pixel et produit remote NO GO.
- **Confirmation delta** `06d30ed` (docs-only, code `bfb1965` inchangé) : la
  même session a rendu **`ACCEPTED`** — portée limitée à `README.md` et six
  documents `docs/`, libellés corrects, verdicts historiques préservés, huit
  recommandations inchangées, aucun nouveau P0/P1/P2, aucun test exécuté.

## 11. Limites honnêtes

- **Physique `NOT_RUN`** : aucun test sur téléphone, aucune série de démarrages
  froids, aucune sonde A4-4 exécutée. `GO_CHAT_RELIABILITY_QA` et
  `GO_A4_4_CLIENT` restent non accordés.
- **Cause historique V7-06C `UNKNOWN`** : le deadlock G↔C est un mécanisme
  atteignable et supprimé, pas une attribution rétroactive certaine.
- **Relais 1012** : le close 4001 n'atteint pas Android via le relais déployé ;
  la révocation est prouvée par la trame applicative authentifiée. Aucune
  modification du relais.
- **Identité de requête non durable** : après une réponse incertaine, un renvoi
  manuel du même texte crée une nouvelle identité et peut produire un second tour
  de modèle. Aucune reprise automatique n'existe ; le risque est documenté, pas
  corrigé dans cette campagne.
- **Autres hypothèses A1** : assemblage de contexte synchrone, famine
  worker/audit, garde de génération sur révocation de catégorie, transition de
  session ; non toutes corrigées.
- **Provenance installée** : le gate installé↔construit est couvert par tests,
  mais aucun APK installé n'a été vérifié sur appareil dans V7-06D.
- **Scénarios backend réels** : non rejoués ; le backend n'est pas modifié et les
  preuves V7-06C restent valides dans leur périmètre.
- **Revues initiale, delta et finale** : lecture seule, sans réexécution des
  suites ; les totaux sont ceux des logs de test parent et des documents. Le P1
  trouvé était réel et a été corrigé ; les P2 de revue delta sont corrigés. La
  revue finale n'a trouvé aucun P0/P1 et un seul P2 documentaire de libellés,
  corrigé après le SHA examiné.
- **Test de succès du gate installé** : il exécute la vraie fonction
  `verify_installed_artifacts` mais simule les lectures bas niveau (dumps de
  paquets, hashes installés, certificats) ; les sorties réelles d'un appareil ne
  sont pas couvertes dans V7-06D.

## 12. Prochaines actions

1. **Autorisation physique ciblée** (nouvelle, non permanente) pour :
   - exécuter la sonde A4-4 renforcée sur le Pixel 9 Pro XL API 37, avec stack
     isolée, identité synthétique révoquée et sauvegarde pré-vol ;
   - exécuter une série de démarrages froids indépendants (par ex. 5) avec
     consentements/plan synchronisés puis chat, en conservant chaque tentative ;
     un échec intermittent conserve le verdict limité ;
   - optionnellement, une recette APPLY et une REJECT sur des identités neuves.
   Aucun paquet produit, HOME, Keystore ou donnée personnelle ne doit être touché.
2. **Revue finale effectuée** sur le SHA intégré (`88ac019`, code `bfb1965`) :
   aucun P0/P1, P2 documentaire de libellés corrigé ; aucune correction de code
   requise.
3. **Décisions live séparées** : révocation ciblée des deux devices obsolètes,
   puis rotation éventuelle du bearer Core, sur autorisation explicite et
   sauvegarde vérifiée.
4. Ne pas accorder `GO_PIXEL_PRODUCT`/`GO_PRODUCT_REMOTE` par assimilation.
