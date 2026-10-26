# V63-R5 — Audit du transport Paseo / Paseo Relay (lecture seule)

| Élément | Valeur |
|---|---|
| TASK_ID | `V63-R5-SCOUT-01` |
| Session Paseo | `5c393291-ad7a-467a-80ee-e90a78595b0b` |
| Profil | `provider=codex`, `model=gpt-6-luna`, `effectiveThinkingOptionId=xhigh` |
| Base | `f625f0de21725485c91247e96986fdccd4eee597` |
| Worktree | `audit/v63-r5-paseo-transport`, `chmod -R a-w`, HEAD et statut inchangés après audit |
| SHA amont | Paseo `902b0c7a1637cc7a6c2ee268dca8161965a35def` ; Relay `3fc41c96c8c63f3a7109e832899cc57d473c4531` |
| Tests | **aucun exécuté** ; audit statique uniquement |

## 1. Noise NK — vérifié

Le canal utilise bien `Noise_NK_25519_ChaChaPoly_SHA256` entre pairs à jour :

- pattern `-> e, es` puis `<- e, ee` ; la clé statique du répondant est connue
  hors bande ;
- prologue Paseo `paseo-relay-e2ee/1` ;
- message `protected` dans les enveloppes `e2ee_hello` / `e2ee_ready` ;
- négociation de `binaryCiphertext`.

Références : `packages/relay/src/secure-channel.ts:114,165`,
`packages/relay/src/noise/index.ts:2`.

Primitives : `@noble/curves` (X25519), `@noble/ciphers`
(ChaCha20-Poly1305), `@noble/hashes` (SHA-256/HMAC), HKDF selon Noise.
Nonce ChaChaPoly = 4 octets nuls + compteur 64 bits little-endian.
Références : `noise/index.ts:15`, `noise/symmetric-state.ts:66`,
`noise/cipher-state.ts:1`, `packages/relay/package.json:37`.

## 2. Fallback legacy — risque majeur vérifié

Le fallback legacy est **présent et permissif par défaut** :

- le client accepte le legacy si `ready` omet `protected` ;
- le daemon accepte le legacy si `hello` l'omet ;
- `minimumMode = "legacy"` par défaut des deux côtés ;
- `daemon.relay.requireProtectedSecurity` est désactivé par défaut ;
- un `protected` présent mais invalide échoue sans fallback.

Références : `secure-channel.ts:158,267,419`,
`packages/server/src/server/pairing-offer.ts:21`,
`docs/relay-endpoint-security.md:70`.

**Exigence ATLAS : les deux extrémités doivent imposer
`minimumMode: "protected"` ; aucun fallback legacy, même en cas d'échec de
négociation.** C'est une condition d'acceptation du futur ticket R5.

## 3. Vecteurs amont disponibles

- `Noise_NK_25519_ChaChaPoly_SHA256` dérivé de
  `noise-c/tests/vector/cacophony.txt`, prologue `4a6f686e2047616c74` :
  `packages/relay/src/noise/fixtures/nk-25519-chachapoly-sha256.json:4,9,41`.
- Même pattern dérivé de `noise-c/tests/vector/noise-c-basic.txt`, prologue
  `50726f6c6f677565313233`, handshake hash
  `d5c4ce9ffe8bcd940aa50f842a5d4d90d3f7163f4b3916deb87a5d747712d718` :
  fixture lignes 44,49,81.
- `noise.test.ts:24` couvre handshake, clés, ciphertexts, rejeu, réflexion,
  désordre, mauvaise clé, clés éphémères invalides, épuisement de nonce.
- `secure-channel.test.ts:414` et `protected-records.test.ts:57` couvrent
  downgrade, handshake altéré, refus du legacy et framing.
- Ces tests sont **référencés, pas exécutés**. Les vecteurs standards ne
  couvrent pas le prologue/enveloppe Paseo : un test croisé avec le profil de
  production reste nécessaire.

## 4. Réemploi PC et Android

**PC / Node :** le sous-chemin public `@getpaseo/relay/e2ee`
(`packages/relay/package.json:11`, `src/e2ee.ts:1`) expose les deux côtés du
canal et permet d'imposer `minimumMode: "protected"`. Ne pas importer les
modules Noise/records privés. Ne pas réutiliser
`daemon-client-relay-e2ee-transport.ts` ni `server/relay-transport.ts`, qui
sont liés au daemon Paseo.

**Android :** aucun code Noise Android n'est fourni. Piste à qualifier sur
`minSdk 26` → API 37 : primitives JCA/Conscrypt pour X25519,
ChaCha20-Poly1305, SHA-256/HMAC ; Android Keystore pour protéger l'identité
ATLAS. Le téléphone est l'initiateur NK : le handshake n'authentifie pas son
identité persistante, donc l'autorisation du téléphone doit être ajoutée au
niveau applicatif. Si aucune bibliothèque Noise NK maintenue ne passe les
vecteurs, implémenter uniquement l'adaptateur NK et le framing nécessaires en
Kotlin, sans porter le TypeScript.

## 5. Licences

Paseo et Paseo Relay sont **Apache-2.0** (`LICENSE:3` / `LICENSE:1`).
ATLAS est GPLv3. Toute reprise de code ou binaire doit ajouter à
`THIRD_PARTY_NOTICES.md` : origine, SHA, fichiers exacts, modifications,
licence et notices. Les licences transitives (Noble, tweetnacl, base64-js,
Cowboy, Jason, Syn, etc.) n'ont pas été auditées.

## 6. Rôles et topologie cible

- Android ATLAS → relais WebSocket dédié → connecteur PC ATLAS en connexion
  sortante → FastAPI loopback.
- Le relais Elixir route `serverId`, rôle et `connectionId`, transmet les
  trames et ne vérifie que la forme de certaines clés publiques ; il **ne
  valide pas Noise**. Voir `connection.ex:14`,
  `handshake_validation.ex:28`, `README.md:102`.
- Les identifiants de route sont visibles et ne valent pas autorisation.
- Le listener est `cowboy_clear` (`listener.ex:8`) : un déploiement réseau
  exige une terminaison TLS distincte.
- Interdits : API de contrôle des agents Paseo, terminal distant, clés et
  identités du daemon Paseo, relais public Paseo.
- Appairage confirmé sur PC, empreinte de clé serveur vérifiée hors bande,
  identité ATLAS distincte protégée sur Android, liste d'appareils autorisés,
  capacités limitées, révocation qui ferme les sessions et invalide les
  anciennes autorisations.
- Le connecteur n'expose que des opérations métier ATLAS structurées, sans
  shell ni accès fichiers générique.

## 7. Tests et infrastructure

Tests d'interopérabilité possibles avec un relais Elixir local
(`PASEO_RELAY_E2E_PORT`, `relay-endpoint-security.md:88`). Les tests Relay
couvrent routage v1/v2, ordre des trames, validation de clé, reconnexions et
pression, mais ne sont pas des vecteurs cryptographiques.

Oracle du futur ticket : vecteurs JSON dans des tests JVM, vérification octet
par octet du handshake et des records, test croisé Kotlin ↔ Node via un relais
local ; mauvaise clé serveur, handshakes altérés/répétés, records
modifiés/dupliqués/réordonnés, compteurs, refus du legacy des deux côtés,
coupure/reprise, reconnexions concurrentes, tailles/pression, révocation.

**`BLOCKED_INFRA`** : le test extérieur LTE/5G reste bloqué sans infrastructure
ATLAS approuvée. Aucun port, relais public ou cloud n'a été ouvert.

## 8. Verdict de l'audit

**ACCEPTED (audit)** — aucun code écrit, SHA et worktree inchangés. Le rapport
fournit un plan de réemploi, les vecteurs, les licences et la condition
impérative `protected`-only. Aucun test n'est revendiqué.
