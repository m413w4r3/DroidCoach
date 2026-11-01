# V7-06A — Plan de qualification VM/AVD isolée

Date : 2026-10-31
Statut : **préparé, non exécuté**. Dépend du SHA intégré après V7-06A-FIX.
Aucun ADB sur le Pixel physique. Aucun service live modifié.

## 0. Principe

Qualifier la synchronisation V4 sur un émulateur Android 17 / API 37.2 avec :
- un Core ATLAS isolé (port 8110, base SQLite temporaire, jeton synthétique) ;
- un relais Paseo isolé (conteneur épinglé, port 4412, loopback) ;
- un connecteur PC isolé (état temporaire, identité et appairage neufs, métriques 4411) ;
- un APK compilé depuis le SHA intégré, hash et signature enregistrés ;
- des consentements, un DayPlan et une identité de test synthétiques neufs.

Interdits : réutiliser `pixel-v7`, `r3c-pixel-final`, le bearer du Core live, la base
locale du Pixel, ses tokens ou ses données ; toucher au relais/Core/connecteur live ;
`adb -d` ; tout appareil non `emulator-*`.

## 1. Composants et ports (tous loopback)

| Composant | Port | État / isolement |
|---|---|---|
| Core ATLAS de test | 8110 | `ATLAS_DB_PATH=/tmp/atlas-v7-06a-vm/core.sqlite3`, `ATLAS_TOKEN` synthétique 0600 |
| Relais Paseo | 4412 | conteneur `atlas-v7-06a-relay-<pid>`, image `atlas-paseo-relay:r3a`, ID `sha256:75d68f…6889` |
| Connecteur PC | 4411 (métriques) | `ATLAS_RELAY_STATE_DIR=/tmp/atlas-v7-06a-vm/connector`, identité neuve |
| Émulateur | série `emulator-5580` | AVD neuf `atlas_v7_06a_api37` (API 37.2, x86_64, google_apis_ps16k) |

Le connecteur joint `ws://127.0.0.1:4412` et `http://127.0.0.1:8110` ; l'émulateur joint
`ws://10.0.2.2:4412` (hôte de l'émulateur, autorisé en debug par
`RelayConfigurationValidator`). Aucune exposition publique, aucun Funnel.

## 2. Pré-vol

1. `git rev-parse HEAD` = SHA intégré annoncé ; arbre propre ; APK construit depuis ce SHA.
2. KVM présent (`/dev/kvm`), 67 Gio libres, image système API 37.2 installée.
3. Ports 4411/4412/8110/5580 libres ; conteneur live `atlas-v7-relay` non touché.
4. AVD neuf `atlas_v7_06a_api37` **créé et boot vérifié le 2026-10-31** (profil Nexus 5,
   partition data 6 442 450 944 octets, x86_64) : `sys.boot_completed=1`,
   `ro.build.version.sdk=37`, `ro.build.version.sdk_full=37.2`,
   `ro.boot.qemu.avd_name=atlas_v7_06a_api37`, série `emulator-5580`, puis arrêt propre.
   Preuve : `evidence/V7-06A_AVD_BOOT.md`.
   - Mémoire hôte tendue (~4 Gio disponibles au pré-vol) : vérifier avant la recette ;
     si insuffisant, `BLOCKED_ENVIRONMENT` plutôt qu'un arrêt de service live non autorisé.
5. Générer le jeton Core synthétique (0600, hors dépôt) ; ne jamais l'afficher.
6. Démarrer le Core isolé et vérifier `/health` + capabilities V4 (sept flags true).
7. Démarrer le relais isolé et vérifier `/health`.
8. `show-identity` du connecteur → `public_key`, `fingerprint` ;
   `pair-device --label v7-06a-vm --token-file /tmp/.../pairing.json` → device_id,
   capabilities 14 ; le fichier contient aussi le token (0600, jamais loggé).
9. Démarrer le connecteur isolé avec `ATLAS_V4_ENABLED=1`,
   `ATLAS_V4_ANDROID_TOKEN_FILE` = jeton Core synthétique, `ATLAS_SERVICE_TOKEN` idem.
10. Pousser la configuration d'appairage dans le stockage privé de l'app (via
    `run-as`, sans affichage) ; valeurs non secrètes (URL, serverId, deviceId,
    clé publique) via arguments d'instrumentation.

## 3. Recette VM-1 — synchronisation

| # | Action | Oracle |
|---|---|---|
| 1 | Appairage VM | configuration persistée, `credentialsConfigured=true`, state `RELAY_CONNECTED` |
| 2 | Connexion Relay | session authentifiée, génération de session tracée, aucune erreur |
| 3 | Consentement DAY_PLAN | `GRANTED:v/v` local, reçu Core `ACCEPTED/DUPLICATE` |
| 4 | Consentement ACTIVITY_STATUS | `GRANTED:v/v` local, reçu Core `ACCEPTED/DUPLICATE` |
| 5 | Confirmation des deux ACK | état local `syncedVersion==version`, trace ACK `APPLIED` |
| 6 | DayPlan approuvé synthétique | envoi `day-plan-snapshots`, reçu version attendue |
| 7 | Reçu DayPlan | `latestPlanReceiptVersion == version`, fraîcheur `FRESH` |
| 8 | Lecture Core | lecture SQLite isolée : `consent_states_v4` GRANTED vN, `day_plan_snapshots_v4` 1 ligne, consent_version cohérente |
| 9 | Versions/provenance | version plan, consent_version, `device_ref` synthétique, `source=ANDROID_SYNC` |
| 10 | Passe sans doublon | aucun nouvel effet métier (rejeu `DUPLICATE`/`replayed`), une seule ligne par clé |
| 11 | Coupure réseau | `disconnect()` puis changement local ; consentement/plan reste pending, aucun ACK fictif |
| 12 | Reprise/réconciliation | `connect()` + passe ; reçu récupéré, `syncedVersion==version`, pas de double effet |
| 13 | Révocation | `REVOKED:v/v` local pour les deux catégories |
| 14 | Confirmation Core | `consent_states_v4` = REVOKED vN via lecture SQLite isolée |

Ne pas effacer l'état pour transformer un échec en PASS. Conserver les FAIL et les traces.

## 4. VM-2 — chaîne V4 (conditionnelle)

Uniquement après VM-1 **PASS**. Chat V4 réel, lecture du plan par l'agent, proposition
A0, pull inbox, application locale explicite sur activité synthétique, plan v2→v3,
ACK durable, vérification Core, absence de double application.

Le modèle et les outils réellement invoqués doivent être prouvés côté serveur, pas
inférés du texte. Si l'environnement isolé ne fournit pas de modèle réel, VM-2 est
`BLOCKED_ENVIRONMENT` ; aucun mock n'est présenté comme preuve V4 réelle.

## 5. Nettoyage et preuves

- Arrêter uniquement les processus/conteneurs créés par ce ticket (PID/noms capturés).
- Ne pas toucher `atlas-v7-relay`, `atlas-core-atlas-core-1`, le connecteur live, ni
  `~/.local/state/atlas-relay`.
- Conserver : SHA, hash APK, version/signature Android, série `emulator-5580`, API,
  commandes, résultats PASS/FAIL/BLOCKED, extraits de trace expurgés, compteurs SQLite
  isolés, matrice de synchronisation/idempotence.
- Supprimer les jetons synthétiques temporaires après collecte ; ne jamais les publier.

## 6. Limites honnêtes

- Un PASS VM ne vaut pas `GO_PIXEL` ni une preuve de réseau cellulaire réel.
- L'API 37.2 x86_64 est la cible de qualification ; toute différence arm64/Pixel est
  une limite explicite.
- Aucune donnée personnelle, aucun fichier du Pixel, aucune identité réelle.
