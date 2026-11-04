# ATLAS V7-07 — Audit de préproduction (lecture seule)

**Date :** 2026-11-01
**BASE_SHA :** `32e7c70c78fbe295f66f67d6be50dc797a3f6e6d`
**Branche :** `feat/atlas-v7-07-preproduction`
**Portée :** audit de préproduction avant tout pilote produit. Aucune
installation, aucun ADB sur le Pixel, aucune modification live, aucune
révocation, aucune rotation de secret.

**Méthode :** pré-vol parent en lecture seule, trois audits indépendants
`codex/gpt-6-luna` / `xhigh` (A1 données produit, A2 configuration live, A3
backup/rollback), et vérifications live locales strictement non modificatrices.
Les preuves historiques V6/V7 sont citées comme telles ; elles ne valent pas
observation actuelle.

## 1. Matrice de préproduction

| Élément | Vérification | Statut | Preuve / limite |
|---|---|---|---|
| **1. Application produit** | Version, signature, données persistées, rôle HOME | **PROVEN (Pixel 2026-11-02)** | Paquet réel `dev.atlas.coach`, versionCode 1, versionName 0.1.0, `debuggable=true`, certificat `da301466…eeee`, APK initial `0d0ce524…`, APK final installé `c7b0f71f…` ; rôle HOME `LauncherActivity` ; sauvegarde réelle 17 fichiers hashée et vérifiée ; données clés inchangées après mise à jour |
| **2. Code courant** | SHA, provenance, versionCode/Name | **PROVEN** | `HEAD=32e7c70` historique, code final `b9f17f4`, CI `41350216879` `success` sur la base, JVM 922/922 et build local sur le final ; `applicationId=dev.atlas.coach`, versionCode 1, versionName 0.1.0 ; provenance `worktree_clean=true` |
| **3. Compatibilité** | Mise à jour in-place sans perte | **PASS Pixel API 37 (2026-11-02)** | Même paquet, même certificat, même versionCode ; `adb install -r` du candidat final **Success** ; hashes état local/V4/plans/WorkManager identiques au manifeste de sauvegarde ; AVD API 35 également PASS |
| **4. Android 17** | Launcher, Focus, notifications, permissions, Keystore | **PASS ciblé Pixel API 37 / NOT_RUN notifications-Keystore** | HOME ATLAS, lancement à froid, Plan, Focus, Coach, hors ligne et pause d'urgence testés sur Pixel 17/37.1 ; permissions runtime inchangées (calendrier/notifications/localisation refusés) ; alias Keystore non exportés et non inspectés |
| **5. Core live** | Image, santé, SQLite, capabilities V4 | **PROVEN** | Conteneur `atlas-core-atlas-core-1`, image `00b88ef…`, `healthy`, network host, DB `/data/atlas.sqlite3` ; `/health` 200 `mode=openclaw` ; 7 capabilities `true` ; 2 consentements, 0 projection/proposition/ACK |
| **6. Gateway live** | Modèle réel, agent Atlas, outils effectifs, restrictions | **PASS (B, 2026-11-02)** | `profile=minimal`, plugin natif `atlas_core_tools` désactivé, `allow` supprimé, deny groupes fs/runtime/web/sessions/nodes/ui ; `tools.effective` : 66/66 outils natifs exclus, aucun `atlas_*` ; appel modèle réel `gpt-6-luna`/`openai`, six outils client, une proposition A0, aucun double effet |
| **7. Relay** | Connexion publique, Noise, appairage, révocation | **PASS transport (2026-11-04) / NO-GO proposition** | Relay `4402` sain ; DNS public, TLS SNI, health public 200, app connectée en LTE sans Wi-Fi/Tailscale, session Noise active ; mais aucun tour outillé fiable et aucune proposition A0 |
| **8. Sécurité** | Identités obsolètes, consentements résiduels, secrets | **PARTIAL / P1 séparation** | `r3c-pixel-final` révoquée ; `pixel-v7` préservée car identité produit actuelle ; identité D créée puis révoquée après blocage ; 2 consentements Core `GRANTED` v2 historiques, non réutilisés ; bearer Android partagé non tourné (refusé) |
| **9. Exploitation** | Démarrage auto, reprise, sauvegardes, monitoring | **PARTIAL / NOT_RUN** | Core `restart: unless-stopped` ; Gateway service utilisateur actif ; Relay actif ; connecteur/proxy arrêtés après D ; pas de sauvegarde automatisée ; reboot hôte et panne PC non testés |

Légende : `PROVEN` = établi par code, configuration ou lecture live ;
`PROVEN_HISTORICAL` = établi sur un artefact daté, pas sur l'état actuel ;
`NOT_RUN` = non exécuté ; `BLOCKED` = dépendance indisponible ;
`FAIL` = défaut ou indisponibilité constatée.

**Note de lignée :** la base mission reste `32e7c70`. Après la revue indépendante,
la campagne a intégré localement le correctif produit V4 `dd343c2` (quarantaine
fail-closed) et les deltas test-only/tooling ; le HEAD de campagne est
`b9f17f4` pour le code, puis `0203257` pour les documents examinés par la revue
delta (état intermédiaire ; les corrections de statut issues de cette revue sont
commitées au-dessus). Le seul changement
de code produit vs `32e7c70` est `AndroidSyncStorage.kt` (correctif V4) ; les
tests, scripts et documents n'affectent pas l'APK. Le candidat final a été
reconstruit depuis `b9f17f4` : APK `c7b0f71f…`, JVM **922/922**, provenance
`worktree_clean=true`.

## 2. Application produit et données

### 2.1 Identité et signature

- L'APK produit archivé le 2026-10-30 (`post-install-base.apk`) porte
  `applicationId=dev.atlas.coach`, `versionCode=1`, `versionName=0.1.0`,
  `debuggable=true`, `allowBackup=false`, et l'activité HOME
  `dev.atlas.coach.app.LauncherActivity`.
- Son certificat V2 est `C=US, O=Android, CN=Android Debug`, SHA-256
  `da3014662eb65e50a87ef364a2e583c331398795b222e3b37b1a16553a00eeee`.
  Le certificat public du keystore debug de l'hôte est identique. La clé privée
  n'a pas été exportée.
- **Conséquence :** un APK debug construit sur cet hôte avec le même
  `applicationId` est compatible en signature pour une mise à jour in-place. La
  compatibilité de signature et l'installation in-place réelle sont **PROVEN /
  PASS (2026-11-02)** ; la restauration in-situ ne l'est pas.

### 2.2 Stockage local inventorié

L'application n'utilise pas Room. Elle persiste :

| Store | Contenu | Version / lecture |
|---|---|---|
| `shared_prefs/atlas_local_state.xml` | état principal : objectifs, habitudes, Focus, routines, coach, règles, capacités, épingles launcher, conversation agent, propositions appliquées, état proactif | JSON `schemaVersion=1`, lecture stricte ; quarantaine si invalide |
| `shared_prefs/atlas_core_v4_sync.xml` | consentements V4, projections en attente, reçus, fraîcheur, idempotence | JSON `state_v4`, schéma 1 ; **P1 corrigé** : une racine invalide est mise en quarantaine avant remplacement, et les écritures sont refusées si la quarantaine échoue |
| `shared_prefs/atlas_relay_configuration.xml` | URL, serverId, clé publique Noise, deviceId, dernier contact, token Relay chiffré | token AES-GCM, clé Keystore `atlas_relay_device_v1` |
| `shared_prefs/atlas_sync_configuration.xml` | token bearer backend chiffré | AES-GCM, clé Keystore `atlas_backend_bearer_v1` |
| `shared_prefs/atlas_sync_preferences.xml` | URL backend, catégories consenties | sans version explicite |
| 18 autres SharedPreferences | consentements localisation/calendrier/usage, diagnostics, thème, launcher précédent, notifications, quotas, pause d'urgence | voir rapport A1 |
| `files/plans/day-plan-<date>.json` | plan journalier | schémas 3/4/5 lus ; écriture v5 |
| `files/memory/memory-facts.json` | mémoire locale | schéma strict v1 |
| `files/atlas_agent_inbox_v2.json` | inbox agent | schémas 1/2 |
| `files/atlas_agent_effects_v1.json` | journal d'effets | schéma 1 |
| `files/atlas_sync_outbox_v1.json` | outbox sync | schéma 1 strict, pas de quarantaine observée |
| `files/plans/agent-proposal-inbox-v4.json` | inbox propositions V4 | schéma 1 |
| `files/calendar/write/*` | journal d'écritures calendrier et liens | formats 1 |
| `no_backup/androidx.work.workdb` | WorkManager | base SQLite + WAL/SHM |
| Android Keystore | `atlas_backend_bearer_v1`, `atlas_relay_device_v1` | non exportables |

Le détail des clés et des writers est dans le rapport A1 et dans
`V7-07_BACKUP_AND_ROLLBACK.md`.

### 2.3 Compatibilité de mise à jour

- **État local :** le code courant lit strictement `schemaVersion=1`, qui est
  aussi la version écrite. Une version future inconnue est mise en quarantaine
  et l'application bascule sur un état de repli ; elle ne doit pas être
  introduite sans migration explicite.
- **Plans :** `PlanV5JsonCodec.decodeStore` lit les schémas 3, 4 et 5. Le plan
  historique archivé est en schéma 3 et reste lisible. L'écriture courante est
  en schéma 5, donc une fois le candidat exécuté, l'ancien APK ne relira pas
  forcément le plan réécrit. C'est une limite de rollback à documenter.
- **Contrats agent :** mandat v1→v2, état HOME v1/v2, inbox v1/v2. Les migrations
  sont explicites et testées JVM.
- **versionCode :** le candidat peut conserver `versionCode=1` pour une
  installation `adb install -r` sur le même paquet ; un rollback vers l'APK
  précédent de même versionCode reste alors possible. Une vraie livraison
  ultérieure devrait incrémenter le versionCode. `adb install -d` ne doit pas
  être utilisé pour contourner un downgrade.
- **Données non couvertes par l'app :** rôle HOME, permissions Android, canaux
  de notification, alarmes système, calendrier via `CalendarContract`,
  WorkManager planifié. Une mise à jour les conserve généralement, une
  désinstallation ne les restaure pas.

### 2.4 Essai AVD jetable (2026-11-01)

L'image API 37.2 de l'hôte étant instable (`surfaceflinger`/UWB), l'essai
procédural a été exécuté sur un **AVD API 35 jetable**, avec des données
synthétiques, puis nettoyé. Résultats :

- backup app-only **PASS** (archive `ec88d1a2…`, 11 fichiers, zéro cache,
  manifeste intègre) ;
- restauration **PASS** après altération d'un marqueur ; hashes identiques et
  application démarrée ;
- mode hors ligne **PASS** ; HOME ATLAS et retour au launcher Nexus **PASS** ;
- mise à jour in-place APK historique → candidat **PASS** avec données
  conservées ; rollback candidat → historique **PASS** ;
- Keystore/tokens **NOT_RUN** ; Pixel physique API 37 **NOT_RUN**.

Preuve : `evidence/V7-07_AVD_BACKUP_RESTORE.md`. Ces résultats qualifient la
procédure et l'outil, pas le produit réel du propriétaire.

## 3. Core live

- Conteneur `atlas-core-atlas-core-1` : image `sha256:00b88ef…`, `healthy`,
  `restart=unless-stopped`, `network_mode=host`, volume `atlas-core-data`.
- `/health` : HTTP 200, `{"status":"ok","mode":"openclaw"}`.
- `/internal/v4/capabilities` avec token outil et en-têtes V4 : HTTP 200, 7
  capabilities `true` :
  `day_plan.read`, `activity_status.read`, `plan_revision.propose`,
  `usage_summary.read`, `goal_progress.read`, `training_plan.read`,
  `coach.chat`.
- SQLite : tables V4 présentes ; `consent_states_v4` = 2 lignes pour
  `device_ref` `android-9a3f0f75…` (`DAY_PLAN`, `ACTIVITY_STATUS`, `GRANTED`
  version 2, `updated_at=2026-10-31T00:23:51Z`) ; 0 proposition, 0 événement,
  0 snapshot, 0 ACK.
- Tokens : Android, outil et Gateway distincts ; le fichier du plugin
  `atlas_core_tools` a le même hash que le token outil Core. Aucun secret
  affiché.

## 4. Gateway live

- Service utilisateur `openclaw-gateway.service` actif ; `/health` HTTP 200,
  `status=live` ; loopback `127.0.0.1:18789`.
- Configuration statique : modèle primaire `openai/gpt-6-luna`, agent `atlas`,
  plugin `atlas_core_tools` avec `coreBaseUrl=http://127.0.0.1:8100`.
- Six outils déclarés par le plugin : `atlas_get_day_plan`,
  `atlas_get_activity_status`, `atlas_propose_plan_revision`,
  `atlas_get_usage_summary`, `atlas_get_goal_progress`,
  `atlas_get_training_plan`.
- Politique d'agent : `profile=full`, `allow=[atlas_core_tools]`, deny
  `gateway`, `session_status`, `presence`, `exec`, `process`,
  `code_execution`, `wait` ; `codeMode.enabled=false` global et agent.
- **P1 :** `profile=full` sélectionne aussi les outils optionnels des plugins ;
  le plugin natif est activé alors que le chemin canonique Core passe déjà les
  six définitions client. Deux chemins peuvent donc coexister. La cible
  recommandée est `profile=minimal`, plugin natif désactivé pour le chemin Core,
  deny explicite, puis vérification `tools.effective` et trajectoire réelle.
- **Modèle :** le modèle personnel `openai/gpt-6-luna` n'a pas été qualifié sur
  le workflow de propositions structurées. Le PASS `deepseek/deepseek-chat`
  V7-06C appartient à une stack isolée et à des credentials propres ; il ne
  qualifie pas le Gateway personnel. La documentation OpenClaw installée
  indique le retrait de `deepseek-chat` au 24 juillet 2026, ce qui contredit son
  usage déclaré dans la campagne QA du 1er novembre 2026 : la référence exacte
  du modèle QA doit être revérifiée avant toute décision. Aucun profil DeepSeek
  n'est configuré sur le Gateway live.

## 5. Relay et transport public

- `atlas-v7-relay` : conteneur sain, `127.0.0.1:4402`, `/health` HTTP 200.
- Connecteur PC : fichier PID obsolète, processus absent, aucun listener sur
  `4400` ; `4401` absent.
- Funnel Tailscale : `tcp://all.taildc7886.ts.net:8443 -> tcp://127.0.0.1:4400`.
  La terminaison TLS existe mais la cible est sans listener ; `curl` public
  échoue (`SSL_ERROR_SYSCALL`). **Le chemin distant produit est indisponible.**
- Registre : 19 appareils, 2 non révoqués (`pixel-v7` 14 capabilities,
  `r3c-pixel-final` 6 capabilities). Les autres sont révoqués.
- Le connecteur utilise un seul bearer Android pour tous les appareils appairés.
  Le `device_ref` Core est dérivé de ce bearer ; il n'existe donc pas de
  séparation Core par appareil Relay. **P1 de sécurité/identité.**
- Les preuves Noise/auth/appairage V7-06C/V7-06D restent valides dans leur
  périmètre QA ; l'état live actuel n'est pas rejoué.

## 6. Sécurité et identités

| Élément | Constat | Statut |
|---|---|---|
| Tokens Core Android / outil / Gateway | distincts, fichiers privés | **PROVEN** |
| Plugin ↔ token outil | hash identique | **PROVEN** |
| Devices Relay actifs | 2 identités de campagne encore actives | **P1** |
| Consentements Core | 2 `GRANTED` v2 pour un device_ref unique | **PROVEN ; à réconcilier** |
| Séparation par appareil | absente : bearer Android partagé | **P1** |
| Rotation de bearer | non exécutée | **NOT_RUN** |
| Signature produit | debug, compatible mais non production | **PROVEN ; limite acceptée pour le pilote** |
| Exposition publique Core/Gateway | aucun port public observé ; 8100/18789 loopback | **PROVEN (statique/live)** |
| Secrets dans les preuves | aucun token/valeur affiché | **PROVEN** |

## 7. Exploitation

- **Démarrage :** Core `unless-stopped`, Gateway service utilisateur actif,
  Relay conteneur actif. Le connecteur PC n'a pas de supervision observée et
  n'est pas actif ; le chemin distant ne reprend donc pas automatiquement.
- **Reprise :** aucune procédure de reprise automatique du connecteur constatée.
- **Sauvegardes :** aucune sauvegarde automatisée ; l'archive historique
  `20261030-pre-v7` est manuelle et n'a pas été restaurée.
- **Monitoring :** `/health` Core/Gateway/Relay, métriques connecteur
  uniquement si le connecteur tourne ; pas de rotation de logs observée
  (`openclaw.sqlite-wal` volumineux).
- **PC éteint :** le code Android conserve les fonctions locales, les timers et
  les rappels ; le chat distant et les propositions sont indisponibles. Non
  rejoué sur le produit actuel.
- **Reboot hôte :** non testé ; le conteneur Core et le service Gateway sont
  configurés pour repartir, le connecteur non.

## 8. Défauts et risques

### P0

Aucun P0 confirmé dans cette phase préparatoire.

### P1

1. **Restauration non démontrée sur le produit réel.** La procédure et l'outil
   sont validés sur AVD API 35 avec des données synthétiques ; le Pixel API 37
   et les données du propriétaire n'ont pas été restaurés. `allowBackup=false`
   et `adb backup` restent inutilisables ; `run-as` est la voie app-only.
2. **État Core V4 (P1 corrigé).** Une racine `atlas_core_v4_sync` invalide ou de
   schéma inconnu était remplacée par un état vide à la prochaine mutation
   (`AndroidSyncStorage.kt`), avec perte possible des consentements, projections
   et reçus en attente. Le correctif `dd343c2` (`fix/v7-07-v4-quarantine`,
   intégré `b9f17f4`) met la valeur brute en quarantaine avant tout remplacement
   et refuse toute écriture si la quarantaine échoue ; tests JVM dédiés
   (rouge→vert). La sauvegarde préalable reste requise comme défense en
   profondeur.
3. **Configuration Gateway initiale non qualifiée.** Plugin natif concurrent,
   `profile=full`, outils effectifs non lus, modèle personnel non testé sur les
   propositions. Le scope B du 2026-11-02 a appliqué `profile=minimal`, désactivé
   le plugin natif et qualifié un appel modèle réel `gpt-6-luna` ; le parcours
   produit via le transport public reste à qualifier.
4. **Parcours outillé non qualifié.** Le transport public est désormais PASS,
   et la rotation locale de conversation a été vérifiée (nouvelle session
   Gateway vide, historique préservé), mais les tours réels n’ont pas produit de
   proposition A0 fiable : 504 avec un outil, puis 200 sans appel d’outil, 400
   partiel, 200 sans outil, puis **429 quota journalier Core 102062/100000**
   sur une tentative unique avec plan futur frais. Délai Core 20 s, contexte
   de conversation persistant, fraîcheur 5 min et quota sont les causes
   constatées/probables ; voir `V7-07_D_RESUME.md`.
5. **Identités de campagne et bearer partagé.** `r3c-pixel-final` est révoquée
   et `pixel-v7` préservée car utilisée par l'app produit ; le bearer Android
   unique ne permet pas une identité Core produit distincte. Rotation non
   autorisée.
6. **Training plan.** Écart de schéma relevé par A2 entre sync et résultat
   (champs `slot_id`/`routine_id`/`source`), avec un HTTP 500 historique V7-01B ;
   `atlas_get_training_plan` n'est pas qualifié sur projection non vide.
7. **Produit observé et installé, limites restantes.** Version, données, HOME
   et permissions ont été vérifiés le 2026-11-02 ; la restauration in-situ, le
   Keystore et le parcours distant ne sont pas prouvés.

### P2

- Archive historique contenant des caches ; la nouvelle procédure les exclut.
- WorkManager, calendrier, permissions et HOME nécessitent des vérifications
  séparées après restauration.
- Plusieurs stores sans version de schéma explicite (`atlas_sync_preferences`,
  `atlas_agent_revocation_barrier`, préférences d'interface).
- Aucune rotation de logs ni sauvegarde automatisée.
- Le build produit reste debug-signed ; acceptable pour un pilote personnel
  réversible, pas pour une distribution.

## 9. Conclusion de l'audit

La préproduction est **partiellement établie** :

- le code source, la CI de la base, l'identité de paquet, la compatibilité de
  signature et le build final sont prouvés ;
- le Core live est sain et ses capabilities V4 sont actives ;
- la configuration Gateway B est appliquée et un appel modèle réel
  `gpt-6-luna` a produit une proposition A0 sans double effet ;
- la sauvegarde app-only, la mise à jour in-place et le smoke local ciblé sont
  **PASS sur le Pixel physique API 37.1** ; la restauration in-situ et le
  Keystore restent non exécutés ;
- le transport public Relay est **PASS** (DNS, TLS, WSS, Noise, app connectée
  en LTE) mais le parcours outillé et la proposition A0 restent **NO-GO** ;
  le pilote quotidien n'est pas qualifié.

Les gates `GO_DATA_BACKUP`, `GO_DATA_RESTORE` et `GO_HOME_ROLLBACK` sont
**GO_WITH_LIMITATIONS** : backup réel Pixel vérifié, restauration outil validée
sur AVD synthétique et archive réelle extraite/vérifiée, mais restauration
in-situ non exécutée.
`GO_PRODUCT_INSTALL` et `GO_PRODUCT_LOCAL_SMOKE` sont
**GO_WITH_LIMITATIONS** ; `GO_PRODUCT_REMOTE_CHAT` et `GO_PRODUCT_PLAN_REVISION` sont **NO-GO** malgré
le transport public PASS ; `GO_PRODUCT_REMOTE` et `GO_DAILY_PILOT` restent
**NO-GO**. Aucun pilote quotidien n'est autorisé par ce document.

Détail d'exécution : `V7-07_PHYSICAL_EXECUTION.md`.
