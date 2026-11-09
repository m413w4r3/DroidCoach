# V7-11A — Notes de l'orchestrateur (travail interne)

**But :** conserver la matrice de vérification, les écarts déjà constatés et le plan d'intégration pendant que les enfants travaillent. Ce fichier n'est pas un livrable final ; il sera synthétisé ou archivé.

## Écarts majeurs déjà constatés

1. **CI rouge** : `scripts/v7-06c/tests/test_harness.py` insère 10 valeurs dans `agent_proposals_v4` (11 colonnes après v4) ; 2 tests échouent. Correctif borné A1 attendu ; CI distante restera rouge tant qu'aucun push n'est autorisé.
2. **Live pré-V7-10** : le conteneur Core (image du 2026-11-04) ne contient ni `v4_auth.py` ni `v4_assertion.py`. La DB live est en `user_version=3`, intègre, avec 3 propositions et 2 ACK. Aucune migration V7-10 n'a eu lieu.
3. **Chaîne Relay incomplète** : Relay 4402 actif (image `r3a` du 26 oct.), mais aucun listener 4400/4401 ; le Funnel 8443 pointe vers 4400 sans service. Le connecteur/proxy doivent être rétablis et qualifiés avant toute V7-11B.
4. **Topologie Core** : un seul processus uvicorn, host network, sans `--workers`. La mitigation multiworker peut être « worker unique garanti » si aucune mise à l'échelle ni reload n'est introduit.
5. **Handoff V7-10 incomplet pour le live** : il suppose le code V7-10 déjà déployé et un superviseur `$RELAY_UNIT`; il ne contient pas d'étape de build/déploiement de l'image Core V7-10 ni de démarrage du connecteur/proxy. A6 doit le confirmer et le plan V7-11B doit ajouter ces étapes.
6. **APK** : le candidat documenté `2517f241…` est reconstructible depuis `739ad878` (arbre Android identique au code final). La signature debug historique complète est `da3014662eb65e50a87ef364a2e583c331398795b222e3b37b1a16553a00eeee` (V7-07). A3 doit comparer le certificat reconstruit.
7. **Pixel** : présent sur ADB USB (`<PIXEL_SERIAL>`) mais nœud Tailscale offline depuis 33 jours. L'utilisateur a autorisé l'écrasement de l'application ; cette autorisation reste non exercée en V7-11A.

## Findings confirmés (mis à jour en cours de mission)

- **P1 legacy/tombstone** : `v4_router.py:767-789` accepte en mode legacy tout bearer égal à `ATLAS_TOKEN` sans consulter le registre. Après révocation d'un credential LEGACY, un retour en legacy avec l'ancien token ressuscite l'accès. A6 l'a signalé ; le parent l'a confirmé dans le code. Ticket A1-F2 ouvert pour une garde fail-closed + tests rouge/vert. Mitigation opératoire immédiate : ne jamais revenir en legacy avec l'ancien `ATLAS_TOKEN` après révocation ; rotation obligatoire.
- **P2 anti-rejeu inter-worker et redémarrage** : cache mémoire par processus ; deux workers ou un redémarrage <240 s acceptent le rejeu. Mitigation : cache partagé durable, ou worker unique + blocage V4 vérifiable ≥240 s après redémarrage. Le live actuel a un seul worker mais Compose ne l'impose pas.
- **P1 Relay non administrable** : conteneur `atlas-v7-relay` sans labels Compose, sans volume, sans unité systemd ; le handoff V7-10 utilise `$RELAY_UNIT` et `backup-state.mjs` qui ne correspondent pas au runtime. Le connecteur/proxy 4400/4401 sont absents.
- **P1 identité Relay/binding inconnus** : `pixel-v7` non observable sans accès live/ADB supplémentaire ; un binding révoqué ne peut pas être réactivé.
- **P1 bascule Android incomplète** : `install -r` ne change pas l'identité ; il faut un geste UI/API explicite (Relay mapping ou bearer DIRECT) et un état local/offline fail-closed en cas de refus Core.
- **P2 provenance APK** : certificat exact V7-10 non consigné ; l'APK /tmp a disparu ; A3 doit reconstruire et comparer au certificat Pixel historique `da301466…eeee`.

## Matrice de vérification des tickets

| Ticket | Preuves minimales acceptables | Motifs de rejet |
|---|---|---|
| A1 | échec CI reproduit avant correction ; correctif limité au harness ; `pytest -q` racine PASS ; dry-run migration/backfill/expiration sur DB synthétique avec integrity/FK/comptes ; commit local | correction hors scope, trigger affaibli, test modifié pour masquer, accès live, donnée personnelle |
| A2 | topologie réelle ; rejeu mono-cache refusé ; rejeu inter-cache démontré ; fenêtre/nonce/rotation/saturation testés ; connecteur 131/131 ; proxy 18/18 hors 4402 ; verdict de gate conditionnel | test tautologique, contact 4402, conclusion sans reproduction, mutation live |
| A3 | APK rebuild hash/cert/versionCode ; AVD API 37 ; install -r avec firstInstallTime stable et données/hash préservés ; cold start sans FATAL ; instrumentation 9/9 + 1/1 ; rollback in-place testé ; émulateur arrêté | ADB Pixel, `pm clear`/uninstall, preuve fabriquée, AVD != API 37, données personnelles |
| A4 | findings P0/P1/P2 avec fichier:ligne, scénario et reproduction ; verdict GO/NO_GO ; lecture seule vérifiée | correctif appliqué, finding sans preuve, secret dans le rapport |
| A5 | provenance Git/CI ; pytest racine et backend ; connecteur/proxy ; scan secrets avec valeurs expurgées ; Android différé puis rerun | secret publié, résultat non lié au SHA, test exclu silencieusement |
| A6 | 13 étapes auditées ; plan V7-11B par blocs ; rollback matrix ; 18 gates ; questions ouvertes tranchées ; aucun live | étape non vérifiée présentée comme prouvée, commande destructive non signalée |

## Gates V7-11A — cadre d'évaluation initial

| Gate | Attendu V7-11A |
|---|---|
| GO_V7_11_MAIN_AND_CI | **NO-GO** tant que la CI distante du SHA final n'est pas verte et poussée ; GO local conditionnel après correctif + suite complète. |
| GO_V7_11_CODE_PROVENANCE | GO local si HEAD = `origin/main` = `8dac699`, APK reconstruit et hashes/cert comparés. |
| GO_V7_11_ANTI_REPLAY_TOPOLOGY | GO conditionnel si worker unique réellement garanti et rejeu inter-worker documenté ; sinon NO-GO. |
| GO_V7_11_AUTH_STAGING | GO local seulement si matrice négative dual/principal sur stack isolée ; sinon NOT_RUN/NO-GO. |
| GO_V7_11_DEVICE_ISOLATION | GO local si tests A/B et ownership passent ; sinon NO-GO. |
| GO_V7_11_LEGACY_COMPATIBILITY | GO local si mode legacy et import LEGACY testés. |
| GO_V7_11_SQLITE_MIGRATION_DRY_RUN | GO local si migration v3→v5 sur copie synthétique, integrity/FK/idempotence. |
| GO_V7_11_HISTORICAL_BACKFILL_DRY_RUN | GO local si dry-run + apply sur copie avec backup vérifié et comptes inchangés. |
| GO_V7_11_EXPIRATION_DRY_RUN | GO local si expiration/reconciliation dry-run sur copie, aucun ACK fictif. |
| GO_V7_11_ANDROID_APK_CANDIDATE | GO local si rebuild/hash/cert/versionCode/manifest validés. |
| GO_V7_11_ANDROID_PRESERVATION_AVD | GO local si install -r + données préservées + cold start + rollback. |
| GO_V7_11_RELAY_SYNC_AVD | GO local si un Relay synthétique isolé est utilisé ; sinon NOT_RUN. |
| GO_V7_11_BACKUP_ROLLBACK_PLAN | GO local si plan corrigé et testé sur copies/AVD ; pas de preuve Pixel. |
| GO_V7_11_INDEPENDENT_SECURITY_REVIEW | GO seulement si A4 rend GO sans P0 ouvert sur le SHA final. |
| GO_V7_11_LIVE_CHANGE_READINESS | READY_FOR_AUTHORIZATION, pas GO_LIVE. |
| GO_PRODUCT_INSTALL | NO-GO / NOT_RUN (aucune installation Pixel en V7-11A). |
| GO_LIVE_SECURITY | NO-GO / NOT_RUN. |
| GO_DAILY_PILOT | NO-GO / NOT_RUN (interdit). |

## Plan d'intégration

1. Recevoir chaque ticket, vérifier `git -C <worktree> status`, `git log`, diff, rapport, commandes.
2. Accepter ou demander correction via la même session (max 3 tentatives).
3. Cherry-pick/merge local des correctifs acceptés sur `feat/atlas-v7-11-migration-readiness` (parent seul intégrateur).
4. Rerun parent des oracles sur le SHA intégré.
5. Faire relire le SHA intégré par A4 (sécurité) et A6 (gates) avant clôture.
6. Rédiger les 8 livrables V7-11, le dossier d'autorisation V7-11B, et présenter les gates.
7. Aucun push/PR/merge distant sans accord utilisateur.

## Dossier d'autorisation V7-11B — catégories à produire

1. Réversible avant cutover : backup, copie DB, provisioning sur copie, tests isolés, build APK.
2. Peut interrompre le Coach : arrêt/redémarrage Core, arrêt/redémarrage connecteur, bascule dual, redémarrage Relay.
3. Nécessite le Pixel physique : inventaire ADB, backup app-only, installation APK in-place, vérification HOME/Keystore/consentements, tests de connexion.
4. Irréversible : révocation du bearer LEGACY, rotation de credential, révocation Relay, promotion `principal`, suppression/restauration écrasante.
5. Risques résiduels : multiworker, UNCERTAIN, backfill ambigu, restauration app-only liée au serial, perte Keystore, coupure Funnel/connecteur, rollback après révocation.

## Questions à trancher avant V7-11B

- Conserver l'appairage Relay `pixel-v7` existant ou créer une nouvelle identité ? (préférence : préserver si le binding et le credential peuvent être provisionnés sans réappairage risqué)
- Le credential Android sera-t-il `DIRECT` ou `RELAY` ? Le mode actuel du produit est Relay ; le cutover doit préserver le chemin Funnel/connecteur.
- Où sont réellement installés le connecteur et le proxy ? Aucun superviseur n'est actif ; il faut un plan de démarrage/rollback.
- L'APK candidat doit-il être release ou debug ? Le produit installé est debug, signature debug ; une release exigerait une autre signature et casserait la mise à jour in-place.
- Qui autorise la révocation, et quel est le critère de rollback avant/après ?
