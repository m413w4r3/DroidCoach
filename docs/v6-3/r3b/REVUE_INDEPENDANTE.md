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

L'APK final `20672e07…` et l'APK androidTest `5aef1321…` sont construits, mais
le Pixel physique s'est déconnecté de l'USB avant l'installation ; l'APK
installé reste `1a877422…` (correctif de consentement, sans la barrière de
dispatch), et l'APK antérieur `b55776a9…` reste la dernière version testée sur
Pixel. Le re-test Pixel est donc bloqué à la fois par la déconnexion de
l'appareil et par l'ingress public Funnel indisponible (R-20/R-21). Aucun
PASS Pixel du binaire final n'est revendiqué.

## Session de revue 3

À exécuter sur le SHA documentaire final incluant `91e7aa6`.
