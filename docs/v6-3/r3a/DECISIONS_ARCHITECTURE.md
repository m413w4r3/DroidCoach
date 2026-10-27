# ATLAS V6.3 R3-A — Décisions d'architecture

Chaque décision est prise par le parent avant délégation. Une décision réversible
non bloquante est tranchée ici, pas dans une question à l'utilisateur.

## D1 — Base Paseo réellement épinglée

- **Constat** : le clone `references/repos/paseo` est à
  `902b0c7a1637cc7a6c2ee268dca8161965a35def` (version 0.13.1). Le SHA
  `65ce4b590a09106372c555cb7322f6da73b9d419` cité par la mission n'existe pas
  dans ce clone non shallow.
- **Décision** : épingler `902b0c7…`, déjà utilisé par l'audit R5. Le clone n'est
  pas modifié ; `dist/` est construit localement. Paseo Relay reste épinglé à
  `3fc41c96…`.
- **Conséquence** : toute preuve d'interopérabilité cite ces deux SHA.

## D2 — Relais local

- **Décision** : utiliser le serveur **Paseo Relay upstream** (Elixir), construit
  depuis le clone et exécuté en conteneur éphémère lié à `127.0.0.1:4400`.
- **Pourquoi** : protocole v2 upstream, routage contrôle/data, validation de clé
  canonique, aucun serveur de routage ATLAS à maintenir.
- **Rejeté** : réécrire un relais Node, exposer le relais Paseo public, ouvrir un
  port Internet. Aucune substitution Cloudflare Tunnel en R3-A.
- **Repli** : si l'image ne peut pas être construite, le ticket transport PC reste
  testable avec un faux relais de test, mais l'E2E réel est `BLOCKED_INFRA` et le
  jalon B n'est pas déclaré terminé.

## D3 — Noise NK et port Kotlin

- **Décision** : mode `protected` exclusif, aucun fallback `legacy`. Le PC utilise
  `@getpaseo/relay/e2ee` avec `minimumMode: "protected"`.
- **Android** : port Kotlin minimal et isolé de NK + framing, validé octet par
  octet contre les vecteurs upstream et contre le répondant Node. Primitives :
  Bouncy Castle 1.86 pour X25519 et ChaCha20-Poly1305 à nonce explicite, JCA pour
  SHA-256/HMAC), pas de runtime Node ni de WebView cryptographique.
- **Identité serveur** : paire X25519 propre à ATLAS, privée uniquement sur le PC,
  publique épinglée côté Android. Aucune clé privée dans l'APK.
- **Conséquence** : un `e2ee_ready` sans `protected` est une erreur fatale, jamais
  une négociation descendante.

## D4 — Authentification applicative des appareils

- **Décision** : Noise NK authentifie le serveur, pas le client. Ajouter
  `device-token-v1` : token aléatoire de 32 octets remis hors bande à l'appairage,
  stocké hashé (SHA-256) sur le PC, protégé par Android Keystore côté client.
- **Anti-rejeu** : nonce client, horodatage ±120 s, `request_id` unique,
  idempotency key, expiration ≤ 60 s, cache de résultats 10 min.
- **Révocation** : registre PC, refus des nouvelles sessions et fermeture `4001`.
- **Rejeté** : identité `serverId` comme authentification, token dans l'URL,
  secret dans un log. Un flux QR/code complet est hors R3-A mais le format de
  token est compatible avec son ajout ultérieur.

## D5 — Calendrier local ATLAS

- **Constat** : le provider `com.android.calendar` ne contient **aucun** calendrier.
  Etar 1.0.56 est installé, `WRITE_CALENDAR` accordé à Etar, mais Etar ne fournit
  pas de compte calendrier.
- **Décision** : créer un compte local dédié via le mécanisme Android officiel
  **sync adapter** :
  - type de compte `dev.atlas.coach.calendar` ;
  - nom de compte synthétique `atlas-r3a-test` ;
  - calendrier `ATLAS R3A TEST`, `CAL_ACCESS_OWNER`, visible, sans réseau.
- **Pourquoi** : une insertion directe dans les tables du provider ou via `adb
  shell content insert` ne prouverait pas le parcours applicatif. Le sync adapter
  est le mécanisme prévu par `CalendarContract` pour créer un calendrier local.
- **Sélection** : le moteur accepte `accountType == "LOCAL"` ou
  `accountType == "dev.atlas.coach.calendar"`, jamais un compte Google/Exchange.
- **Conséquence** : `WRITE_CALENDAR` est ajouté au manifeste ; la permission reste
  refusée par défaut et doit être demandée explicitement.

## D6 — Persistance du journal calendrier

- **Décision** : journal et liens persistants sur fichiers atomiques versionnés
  (un enregistrement = un fichier JSON, écrit dans un fichier temporaire, `fsync`,
  puis `rename` atomique), avec index reconstruit au démarrage.
- **Pourquoi** : testable en JVM sans Robolectric, compatible du contrat R2
  `CalendarWriteJournal` qui décrit déjà temp+fsync+rename, et suffisant pour des
  enregistrements indépendants. Chaque mise à jour est atomique ; un fichier
  incomplet est ignoré à la reprise.
- **Alternative non retenue** : SQLite Android, plus lourd à tester en JVM et sans
  transaction multi-enregistrements nécessaire ici. Ce choix est réversible sans
  changer l'interface du journal.
- **Règle** : aucune instance mémoire n'est l'autorité des effets externes en
  production ; `InMemoryCalendarWriteJournal` reste réservé aux tests.

## D7 — Sélection de transport et états

- **Décision** : le transport relayé est interchangeable avec le transport direct
  via les contrats existants (`PlanRevisionProposalClient`, `CoachGateway`,
  `SyncGateway`). Aucun basculement silencieux vers un transport non authentifié.
- **États exposés** : `LOCAL`, `RELAY_CONNECTING`, `RELAY_CONNECTED`, `OFFLINE`,
  `AUTH_REQUIRED`, `PAIRING_REQUIRED`, `REVOKED`, `ERROR`.
- **Règle** : une coupure conserve le plan local et affiche l'indisponibilité du
  coach distant ; les requêtes ne sont pas renvoyées aveuglément.

## D8 — Périmètre parallèle

- Vague A : trois implémentations maximum (`R3-RELAY-PC-01`,
  `R3-RELAY-ANDROID-01`, `R3-CALENDAR-01`). Le parent possède ADB, le relais, le
  calendrier local, les manifestes, les écrans partagés et les contrats.
- Vague B : raccordements `R3-RELAY-E2E-01`, `R3-CALENDAR-ETAR-01`,
  `R3-SPORT-WIRE-01`. Le parent reste seul intégrateur.
- Vague C : résilience, mémoire, usage. Aucune sous-délégation.

## D9 — Bridge et audit npm

- **Décision** : ne pas faire de l'audit npm un chantier R3-A, ne pas mettre à jour
  les dépendances majeures, ne pas activer le plugin sur l'OpenClaw personnel.
  Les risques connus restent documentés comme non corrigés.
- **Conséquence** : les tests existants du plugin sont conservés ; aucune
  revendication de sécurité npm.

## D10 — Détection des modifications externes

- **Décision** : relecture systématique après effet et réconciliation par
  `CUSTOM_APP_URI`; comparaison de `content_hash` sur `calendar_id`, `dtstart`,
  `dtend`, `timezone`, `title`, `all_day`, `deleted`.
- **Conséquence** : une modification faite dans Etar produit `DIVERGED` visible ;
  aucune réécriture automatique, aucune suppression d'un événement non marqué.

## D11 — « 24 h/24 »

- **Décision** : R3-A ne promet pas un WebSocket permanent. La reconnexion est
  déclenchée à l'ouverture/reprise de l'application, avec persistance locale et
  synchronisation différée quand Android l'autorise. Aucun service caché, aucune
  contournement de Doze, aucun push revendiqué.
- **Conséquence** : le handoff distant décrit séparément TLS, hébergement, push et
  recette LTE/5G.
