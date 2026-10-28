# ATLAS V6.3 — R3-B — Décisions techniques

Décisions réversibles prises par le parent, tracées ici. Toute divergence
d'implémentation doit être signalée au parent avant intégration.

| ID | Décision | Justification | Réversibilité |
|---|---|---|---|
| D-R3B-01 | `ATLAS_RELAY_URL=wss://all.taildc7886.ts.net:8443`, sans `/ws`, sans token, sans `serverId` | `RelayConfig.webSocketUrl()` et le connecteur construisent `/ws` et les paramètres Paseo v2 ; le port 8443 est celui du Funnel TLS-terminated TCP | changer la variable d'environnement et le champ Android |
| D-R3B-02 | Relais Paseo épinglé sur `127.0.0.1:4402` ; proxy filtrant sur `127.0.0.1:4400` | le Funnel pointe déjà sur 4400 ; le relais reste inchangé et non exposé directement | arrêter le proxy, relancer le relais sur un autre port |
| D-R3B-03 | Proxy en Node 24, modules standard uniquement | surface minimale, aucune dépendance transitive nouvelle, contrôle explicite de l'upgrade et des limites | remplacer le binaire sans changer les contrats |
| D-R3B-04 | Proxy : `GET /health`, `GET /ready`, upgrade `GET /ws` uniquement ; `v=2` obligatoire ; clés de requête `serverId`, `role`, `v`, `connectionId` uniquement | ferme `/metrics`, les autres chemins, les méthodes non GET et le mode relais v1 legacy | élargir la liste blanche après revue |
| D-R3B-05 | Aucun TLS local entre Funnel et proxy | Tailscale termine le TLS public ; Noise NK reste de bout en bout ; un TLS loopback redondant ajouterait des secrets et de la complexité | ajouter un terminateur local si le modèle de menace change |
| D-R3B-06 | Capacité `coach.chat`, opération `coach.chat.request`, protocole `atlas.remote.v1` conservé | extension additive : les anciens clients et appareils appairés sans la capacité restent valides | retirer l'opération et la capacité |
| D-R3B-07 | `coach.chat.request` est une opération à effet : journal d'idempotence, résultat incertain non rejoué automatiquement | la déduplication de bout en bout n'est pas démontrée ; un POST incertain ne doit pas produire un second appel | repasser l'opération en lecture seule après preuve de déduplication |
| D-R3B-08 | Rejeu strictement identique (même `request_id`/`idempotency_key`, même payload) : réponse mémorisée `replayed=true` ; payload différent : `IDEMPOTENCY_CONFLICT` ; résultat incertain : `BACKEND_UNAVAILABLE` non retentable | empêche les doublons sans prétendre à une idempotence non prouvée | ajuster la fenêtre de cache |
| D-R3B-09 | Android : en mode `RELAY`, le chat utilise `coach.chat.request` ; en `DIRECT`, le HTTP existant ; en `OFFLINE`, le mock local | aucun second écran, aucun nouveau parcours ; la provenance reste visible | changer le routage du contrôleur |
| D-R3B-10 | La provenance `mock` / `OpenClaw personnel` est affichée dans la conversation et provient de `agent_status` renvoyé par FastAPI | l'utilisateur doit distinguer une démonstration d'une vraie réponse distante | ajuster les libellés UI |
| D-R3B-11 | A4 : la révocation PC conduit à `REVOKED`, puis l'effacement local des credentials est une action explicite de l'utilisateur ; la relance ne réappaire pas implicitement | conserve la maîtrise utilisateur et évite toute suppression silencieuse | automatiser l'effacement après confirmation explicite |
| D-R3B-12 | Aucun FCM, aucune promesse de réception instantanée en arrière-plan | hors périmètre R3-B ; Doze doit être mesuré, pas supposé | campagne push séparée |
| D-R3B-13 | Le Pixel physique `45301FDAS001QT` est la cible de qualification réelle, avec sauvegarde app-only avant toute installation | autorisation explicite de l'utilisateur ; aucune donnée personnelle ne doit être perdue | restaurer la sauvegarde et l'APK précédent |
| D-R3B-14 | Tests réseau réels : LTE/5G avec Wi-Fi désactivé, sans `adb reverse` ni proxy USB ; état restauré après test | le Funnel public est le seul chemin autorisé pour la preuve Internet | réactiver les interfaces |
| D-R3B-15 | Instance FastAPI de test isolée sur `127.0.0.1:8100` avec jeton de service synthétique ; l'OpenClaw personnel n'est utilisé qu'en lecture/chat, sans modification de configuration | préserve les services personnels et permet les tests reproductibles | arrêter l'instance de test |
| D-R3B-16 | wger réel en lecture seule uniquement si le jeton existant le permet ; aucun token affiché, aucune écriture, aucun renouvellement | le `.env` privé contient déjà un token ; sinon `BLOCKED_AUTH` | retirer le test réel |
| D-R3B-17 | Le relais et le proxy sont testés comme services isolés ; aucune installation systemd persistante ni `linger` pendant la campagne | limite d'autorisation explicite de la mission | installer après autorisation ciblée |
