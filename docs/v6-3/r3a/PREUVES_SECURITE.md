# ATLAS V6.3 R3-A — Preuves et règles de sécurité

## 1. Modèle de menace retenu

| Actif | Menace | Mesure R3-A |
|---|---|---|
| Clé privée serveur ATLAS | fuite, copie dans l'APK | générée et conservée uniquement sur le PC (0600) ; jamais dans Git, log ou APK |
| Token d'appareil | vol, rejeu, URL leak | remis hors bande, stocké hashé sur PC, protégé par Keystore Android, jamais dans l'URL |
| Trafic relayé | lecture/modification par le relais | Noise NK + records authentifiés ; relais ne voit que du chiffré |
| FastAPI | exposition publique | loopback uniquement ; le connecteur est le seul client relayé |
| Commandes arbitraires | abus du canal | liste fermée d'opérations, payloads bornés, aucune URL/chemin/commande |
| Rejeu de requêtes | double effet | request_id, idempotency key, expiration, cache borné |
| Appareil révoqué | accès persistant | registre PC, refus new hello, close 4001 |
| Données personnelles | traces | fixtures synthétiques, logs sans payload, métriques agrégées |

## 2. Règles opérationnelles

- Aucun secret dans Git. Les répertoires d'état du connecteur, tokens, clés et
  journaux détaillés sont hors dépôt ou sous `.gitignore`.
- Aucun token, payload complet, nom de calendrier personnel ni identifiant
  matériel dans les preuves versionnées.
- Les logs du connecteur n'incluent ni token, ni Authorization, ni corps de
  requête FastAPI. Les erreurs sont des codes stables.
- Les captures d'écran n'utilisent que des fixtures synthétiques.
- Le relais local écoute sur `127.0.0.1`; le conteneur publie uniquement
  `127.0.0.1:4400`.
- Aucune ouverture de port sur la box/Internet. Aucun déploiement public.
- Aucune action sur le Gateway, le Bridge ou l'OpenClaw personnel.
- Aucune permission Android n'est accordée par le code ; chaque permission est
  demandée par un parcours utilisateur explicite.

## 3. Choix cryptographiques

- Noise : `Noise_NK_25519_ChaChaPoly_SHA256`, prologue `paseo-relay-e2ee/1`.
- Mode `protected` obligatoire ; `legacy` refusé des deux côtés.
- X25519 : Bouncy Castle 1.86 côté Android, `@noble/curves` côté Node.
- ChaCha20-Poly1305 : Bouncy Castle 1.86 (nonce explicite) côté Android, `@noble/ciphers` côté Node.
- SHA-256/HMAC : JCA côté Android, `@noble/hashes` côté Node.
- Aucune primitive inventée. Les vecteurs upstream et les tests croisés Kotlin ↔
  Node sont la preuve d'équivalence.
- Les nonces sont réservés avant tout `await` et ne se réutilisent jamais.
- Les clés sont effacées au mieux à la fermeture.

## 4. Risques connus non résolus en R3-A

| Risque | Statut | Limite |
|---|---|---|
| Relais local sans TLS | assumé | loopback uniquement ; TLS/WSS requis avant déploiement distant |
| Relais non authentifiant | assumé | Noise + auth applicative côté extrémités |
| WebSocket non permanent en Doze | assumé | reconnexion à l'ouverture ; push hors périmètre |
| Tokens d'appareil sans rotation automatique | partiel | révocation manuelle testée ; rotation planifiée hors R3-A |
| Appairage par CLI, pas QR | partiel | identités synthétiques en R3-A ; flux QR futur compatible |
| Vulnérabilités npm du plugin Bridge | **non corrigées** | audit différé, plugin non activé, non présenté comme sûr |
| Émulateur ≠ Pixel | assumé | aucune revendication Pixel |
| Loopback ≠ Internet 24 h/24 | assumé | recette LTE/5G dans le handoff distant |

## 5. Preuves attendues

| Preuve | Fichier |
|---|---|
| Pré-vol et backups | `preuves/preflight.txt` |
| Build relais | `preuves/relay-build.txt` |
| Tests Node transport | `preuves/relay-tests.txt`, `preuves/gates-final.txt` |
| Vecteurs Noise Kotlin | `preuves/relay-tests.txt` (cas `NoiseProtocolTest`) |
| Interop Kotlin ↔ Node | `preuves/instrumented.txt`, `preuves/e2e-emulator.txt` |
| E2E émulateur | `preuves/e2e-emulator.txt`, `preuves/instrumented.txt` |
| Calendrier provider/Etar | `preuves/calendar-*.txt`, captures |
| Gates finales | `preuves/gates-final.txt` |
| Provenance | `preuves/PROVENANCE.md` |
| Revue indépendante | `REVUE_INDEPENDANTE_R3A.md` |

Chaque preuve mentionne le SHA exact, la commande, l'environnement et le statut.
