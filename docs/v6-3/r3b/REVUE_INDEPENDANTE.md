# ATLAS V6.3 — R3-B — Revue indépendante

## Session de revue 1

| Élément | Valeur |
|---|---|
| Agent Paseo | `f7add52f-8fac-4a09-bc91-546f7b6726cd` |
| Provider / modèle | `codex` / `gpt-6-luna` |
| Effort effectif | `xhigh` (`thinkingOptionId=xhigh`, `effectiveThinkingOptionId=xhigh`) |
| Mode | `auto-review`, worktree isolé |
| SHA relu | `beb8f338dba18296af10abe1f1eb42a608f02701` |
| État du worktree | `git status --porcelain` vide ; aucun fichier modifié |
| Limites de session | lecture seule ; aucun test, build, ADB, service, réseau externe ni installation exécuté ; état live du Funnel non vérifié |

### Verdict de la revue 1 : BLOQUÉ

Aucun P0. Un P1 et plusieurs P2 ont été relevés.

#### P1 — consentement périmé à l'envoi

`RelayAgentChatClient` préparait et auditait un contexte minimisé, puis le
mettait en file. Si une catégorie était retirée pendant l'attente,
`beforeDispatch` ne vérifiait que le mandat global, pas le snapshot déjà figé :
le payload pouvait partir avec une catégorie retirée.

**Correction `7754005`** :
- `AgentContextSnapshot.stillAllows()` re-minimise le snapshot avec le mandat,
  les dates de consentement et le consentement de domaine courants, puis
  compare le payload exact ;
- `AgentContextCoordinator.consentStillAllows()` fournit ce contrôle sans
  reconstruire les domaines ni réécrire l'audit ;
- `RelayAgentChatClient.beforeDispatch` appelle ce contrôle avant toute
  construction de payload ;
- `AtlasController` fournit un consentement de domaine bon marché (usage,
  localisation, objectifs existants), sans assemblage complet des domaines ;
- tests JVM : retrait de catégorie après préparation => `POLICY_DENIED`, zéro
  payload construit, zéro envoi ; test coordinateur dédié.

#### P2 — grâce de révocation non appliquée au chemin `receive()`

La fermeture 4001 était différée de 200 ms sur le chemin de polling, mais un
`DEVICE_REVOKED` reçu dans un frame client fermait immédiatement.

**Correction `7754005`** : `closeForRevocation()` partage une promesse de
fermeture bornée entre le polling et le chemin `receive()`. Test Node ajouté :
un frame client après révocation reçoit `DEVICE_REVOKED`, puis la fermeture
4001. Node 76/76.

#### P2 — ancien appairage sans `coach.chat`

Le hello demandant `coach.chat` était rejeté en `AUTH_FAILED` si l'appareil
enregistré ne possédait pas cette capacité, bloquant aussi les opérations
existantes.

**Correction `7754005`** : le connecteur accorde l'intersection des capacités
demandées et stockées ; l'ancien appareil reçoit `hello_ok` avec ses capacités
existantes et `OPERATION_UNAVAILABLE` pour le chat. Test Node ajouté avec un
appareil `sync.status` demandant `coach.chat`. Node 76/76.

#### P2 — A4-4 non directement étayé

Le test instrumenté attendait `REVOKED` mais n'affirmait pas le close 4001.

**Correction `7754005`** : `RelayBackendTransport.lastCloseCode` est exposé par
`RelaySettingsController.lastCloseCode` ; le test A4 attend puis affirme 4001.
**La ré-exécution Pixel de ce test renforcé est bloquée par la panne d'ingress
public Funnel** (voir plus bas) ; A4-4 est donc marqué `PARTIAL` tant que le
test renforcé n'a pas tourné sur l'APK final.

#### P2 — A4-8 trop large

Les preuves disaient « clé Keystore effacée » alors que `clear()` supprimait le
ciphertext et l'identité mais pas l'alias AES.

**Correction `7754005`** : `KeystoreRelayCredentialStore.clear()` supprime
l'alias `atlas_relay_device_v1` ; le test A4 vérifie l'absence de l'alias.
**Ré-exécution Pixel bloquée par la panne d'ingress public** ; A4-8 est marqué
`PARTIAL` avec la preuve antérieure d'effacement du credential.

#### A3 — journaux après redémarrage

Les résultats A3 persistence sont des observations parentales, sans tous les
journaux horodatés. La limite est documentée en R-19 et dans
`preuves/pixel-instrumentation-final.txt`. A3 reste PASS sur les preuves
disponibles, avec cette réserve.

### Ingress public, secrets et périmètre

- La qualification publique initiale (DNS, TLS, WSS, Noise, chat) est PASS.
- Le recontrôle final par IP publique a échoué au TLS car `tailscaled` ne
  joignait plus le plan de contrôle Tailscale (`network is unreachable`).
  Aucune configuration Tailscale n'a été modifiée.
- Le rejeu final via MagicDNS a réussi 7/7, mais ne prouve pas l'ingress
  Internet public actuel. Voir R-18.
- Le scan heuristique du relecteur n'a trouvé aucun motif de clé privée, token
  long ou bearer en clair.
- Le relecteur n'a exécuté aucun test dynamique ; la revue est statique.

### Corrections et re-tests après revue 1

| Vérification | Résultat |
|---|---|
| Node après corrections | 76/76, 15 fichiers |
| JVM après corrections | 690 tests, 97 suites, 0 échec |
| Lint | 0 erreur, 71 avertissements |
| APK principal final | `1a877422…` |
| APK androidTest final | `d9213e63…` |
| Tests Pixel du nouvel APK | **BLOCKED** — ingress public Funnel indisponible depuis Wi-Fi et LTE ; données Pixel restaurées 5/5, calendrier 12/91 |

## Session de revue 2

À compléter après la re-revue du SHA `7754005` (code) et du SHA documentaire
final. La re-revue doit vérifier les correctifs P1/P2 ci-dessus et l'absence de
régression.

## Session de revue 2

| Élément | Valeur |
|---|---|
| Agent Paseo | `b8a95ba6-5b00-4b5b-a256-acf6d77a9799` |
| Provider / modèle | `codex` / `gpt-6-luna` |
| Effort effectif | `xhigh` |
| Mode | `auto-review`, worktree isolé |
| SHA relu | `8af78c574f29bb47b1c8f555941421b16a914efc` |
| État du worktree | propre, aucun fichier modifié |
| Limites | lecture seule ; aucun test/build/ADB/réseau exécuté |

### Verdict de la revue 2 : BLOQUÉ

Aucun P0. La revue a confirmé que le correctif précédent fermait le retrait
**avant** `beforeDispatch`, mais a identifié une fenêtre résiduelle : `pump()`
relâchait le verrou du coordinateur avant de construire le payload et
d'envoyer la trame. Un retrait pouvait s'intercaler entre le contrôle et
l'écriture. Elle a aussi relevé que le consentement de domaine était capturé
avant l'appel au coordinateur, qu'une opération déjà en vol n'était pas
annulée au début de la grâce de révocation, et que le test d'intersection de
capacités ne vérifiait pas une opération existante.

### Correctif `91e7aa6`

- `AgentConsentGate` (ReentrantLock) partagé entre le coordinateur, la
  barrière de révocation et le transport. `RelayBackendTransport.pump()` tient
  ce verrou du contrôle `beforeDispatch` jusqu'à l'écriture WebSocket
  (`pumpUnderConsentGate`) ; les mutations de consentement, d'objectifs et de
  barrière prennent le même verrou. Un retrait ne peut plus s'intercaler.
- Le consentement de domaine est fourni par un provider évalué **à
  l'intérieur** du verrou, dans `consentStillAllows`.
- `revoke()` et `closeForRevocation()` annulent immédiatement les opérations
  en vol (`AbortController`) ; la grâce de 200 ms ne retarde plus que la
  fermeture de la socket.
- Le test d'ancien appairage vérifie désormais qu'une opération existante
  (`sync.status`) réussit après `hello_ok`, en plus du refus `OPERATION_UNAVAILABLE`
  pour `coach.chat`.
- Test de concurrence ajouté : une mutation de consentement attend que le
  payload et l'écriture WebSocket soient terminés.
- Résultats : Node 77/77, JVM 691/691, lint 0 erreur/71 avertissements.

### Limite Pixel du binaire final

L'APK de cette étape `20672e07…` et l'APK androidTest `5aef1321…` sont construits, mais
le Pixel physique s'est déconnecté de l'USB avant l'installation ; l'APK
installé reste `1a877422…` (correctif de consentement, sans la barrière de
dispatch), et l'APK antérieur `b55776a9…` reste la dernière version testée sur
Pixel. Le re-test Pixel est donc bloqué à la fois par la déconnexion de
l'appareil et par l'ingress public Funnel indisponible (R-20/R-21). Aucun
PASS Pixel du binaire final n'est revendiqué.

## Session de revue 3

À exécuter sur le SHA documentaire final incluant `91e7aa6`.

## Session de revue 3

| Élément | Valeur |
|---|---|
| Agent Paseo | `cf3df1c2-0006-4039-80ff-f01f98fd5e0f` |
| Provider / modèle | `codex` / `gpt-6-luna` |
| Effort effectif | `xhigh` |
| Mode | `auto-review`, worktree isolé |
| SHA relu | `caf92e44603ba38294522524b04e9a83428fefa3` |
| État du worktree | propre, aucun fichier modifié |
| Limites | lecture seule ; aucun test/build/ADB/réseau exécuté |

### Verdict de la revue 3 : BLOQUÉ

Aucun P0. Le verrou de dispatch a été reconnu correct, mais la révocation du
**consentement d'usage** (`revokeUsageObservation`) ne prenait pas ce verrou :
un snapshot d'usage pouvait encore partir si le retrait intervenait après le
dernier contrôle. La revue a aussi relevé que le test de capacité utilisait
`coach.status` au lieu de `coach.chat.request`, que le chemin `receive()`
attendait l'envoi de l'erreur avant d'annuler les opérations en vol, et que
l'index des preuves appelait `1a877422` « APK final » alors que la matrice
désigne `20672e07` puis `05756094`.

### Correctif `738ef93`

- `revokeUsageObservation()` mute le consentement d'usage et les capacités
  sous `agentConsentGate`, le même verrou que le dispatch relayé.
- Le chemin `receive()` `DEVICE_REVOKED` annule les opérations en vol **avant**
  d'attendre `sendError()`.
- Le test d'ancien appairage envoie une requête `coach.chat.request` valide et
  vérifie `OPERATION_UNAVAILABLE`, puis une requête `sync.status` réussie.
- Le test de concurrence du transport est nommé et commenté comme scénario de
  révocation du consentement d'usage.
- Index des preuves corrigé : `1a877422` est l'APK installé, le binaire final
  est `05756094` (après `20672e07`).
- Résultats : Node 77/77, JVM 691/691, lint 0 erreur/71 avertissements.

### Limite Pixel du binaire final

L'APK final `05756094…` n'a pas été installé : le Pixel est déconnecté de
l'USB et l'ingress public Funnel reste indisponible. Aucun PASS Pixel n'est
revendiqué pour ce binaire ; les parcours Pixel documentés portent sur
`b55776a9…`, avant les correctifs de consentement.

## Session de revue 4

À exécuter sur le SHA documentaire final incluant `738ef93`.

## Session de revue 4

| Élément | Valeur |
|---|---|
| Agent Paseo | `53a84822-d2e7-4747-9613-758b0dcad273` |
| Provider / modèle | `codex` / `gpt-6-luna` |
| Effort effectif | `xhigh` |
| Mode | `auto-review`, worktree isolé |
| SHA relu | `4be991de8adc58e27e20beebef816c4728b2b0c3` |
| Code final relu | `738ef93` |
| État du worktree | propre, aucun fichier modifié |
| Limites | lecture seule ; aucun test/build/ADB/réseau exécuté |

### Verdict de la revue 4 : APPROUVE_AVEC_RESERVES

Aucun P0/P1 confirmé. La revue valide :
- le partage effectif d'`AgentConsentGate` entre `revokeUsageObservation()`,
  `pump()`, le coordinateur et la barrière ;
- l'absence de cycle de verrous observée (les callbacks de dispatch ne
  reprennent pas le verrou proactif ; les mutations de catégorie relâchent le
  verrou du coordinateur avant les mises à jour proactives) ;
- l'annulation des opérations en vol avant `sendError()` sur `receive()`,
  suivie de la grâce 200 ms et du close 4001 ;
- le test `coach.chat.request` refusé puis `sync.status` réussi pour l'ancien
  appairage ;
- les assertions A4 4001/alias Keystore et les statuts PARTIAL du binaire
  final, sans PASS Pixel revendiqué pour `05756094`.

### P2 documentaires corrigés

- `DECISIONS.md` appelait encore `20672e07` « APK final » ; corrigé en
  `05756094` (final), `1a877422` (installé), `b55776a9` (testé Pixel).
- La matrice et l'index des preuves rattachaient les derniers résultats à
  `7754005`/`dc8f55e`/`91e7aa6` ; ils sont désormais rattachés à `738ef93`
  pour les vérifications finales. Les commits `7754005`/`91e7aa6` restent
  cités comme historique des correctifs.
- Les deux commits de correction documentaire n'ont pas été rejoués par la
  revue 4 ; ils ne modifient ni le code ni les hashes d'APK.

### Réserve de couverture des courses

La revue 4 note que les tests ne combinent pas entièrement deux scénarios :
la mutation de consentement et l'écriture socket sont testées par le verrou
partagé mais pas avec une synchronisation séparée de l'écriture ; l'annulation
en vol couvre le chemin de polling, tandis que le test `receive()` vérifie
l'erreur et la fermeture 4001 séparément. Le code source respecte les ordres
demandés ; cette réserve est reportée en R-22 et dans le rapport final.

### Conclusion de la revue 4

`APPROUVE_AVEC_RESERVES`. Les réserves portent sur la couverture de tests de
concurrence et sur le binaire final non installé/testé sur Pixel (R-20/R-21),
pas sur un défaut P0/P1 identifié.
