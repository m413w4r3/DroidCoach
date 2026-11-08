# V7-11 — Qualification de l'authentification et du staging

**Statut : GO local au niveau composants pour les tests positifs et négatifs ; stack Core+Relay complète isolée NOT_RUN ; P1 legacy corrigé localement (à re-revoir).**
**Base auditée :** `8dac699cdb37e2f858a76e4cf7f9fc3bcd613e9c`, puis SHA d'intégration V7-11.
**Preuves :** [A1](evidence/V7-11_A1_CORE_MIGRATION_EVIDENCE.md), [A2](evidence/V7-11_A2_RELAY_REPLAY_EVIDENCE.md), [A4](evidence/V7-11_A4_SECURITY_REVIEW.md).

## Ce qui a été testé

| Domaine | Résultat | Source |
|---|---|---|
| Backend complet | **451 passed, 1 skipped** | A5, backend isolé |
| Suite racine après correctif harness | **496 passed, 1 skipped, 48 subtests** | A1 + rerun parent |
| Connecteur Relay PC | **131/131** | A2/A5 |
| Proxy Relay | **18/18**, cas épinglé 4402 exclu | A2/A5 |
| Tests backend ciblés auth/expiration | **82 passed** | A4 |
| Migration et provisioning synthétiques | PASS | A1 |
| Refus A avec credential B | PASS (tests backend et connecteur) | A1/A2/A4 |
| Credential inconnu/révoqué | PASS | A4 |
| Identité Relay inconnue/révoquée, binding incohérent | PASS | A2/A4 |
| Capabilities non accordées / kill switch global | PASS | A1/A4 |
| Consentements et ownership des propositions/ACK | PASS | A1/A4 |
| Expiration, ACK tardif/prématuré, rejeu, concurrence | PASS | A1/A4 |
| Anti-rejeu mono-processus | PASS | A2 |
| Anti-rejeu inter-worker / après redémarrage | **FAIL — P2 confirmé** | A2 |
| Mode legacy après révocation d'un credential LEGACY | **FAIL — P1 confirmé** | A4/A6, corrigé par A1-F2 |
| Stack Core+Relay+connecteur complète isolée | **NOT_RUN** | — |

## P1 — mode legacy et tombstone

Le chemin `require_android` en mode `ATLAS_V4_AUTH_MODE=legacy` compare directement le bearer à `ATLAS_TOKEN` et construit un contexte LEGACY sans consulter le registre (`backend/atlas_backend/v4_router.py:767-789`). Un test ASGI réel l'a confirmé : après import puis révocation d'un credential LEGACY, le même bearer obtient **200** en mode legacy, alors qu'il obtient **401** en dual et principal.

**Correctif A1-F2 intégré** (`2c3e178`, SHA intégré `65759dd`) : `V4PrincipalRegistry.credential_state(token)` lit l'état d'un credential `LEGACY` par hash et `require_android` refuse désormais un token `REVOKED` en mode legacy. La compatibilité est conservée pour un token jamais importé ou une table de registre absente ; les credentials d'autres audiences ne créent pas de faux positif. Trois tests couvrent ces cas ; le test rouge/vert a été reproduit (200 avant, 401 après). Suites : backend 454 passed / 1 skipped ; racine 499 passed / 1 skipped / 48 subtests. La re-revue A4-F1 sur `65759dd` est **GO avec réserves P2** : le P1 est fermé pour les révocations du registre et les redémarrages/reconstructions normaux. Scénarios a–h PASS (LEGACY actif 200, révoqué 401, token inconnu 200, table absente 200 sans 500, autre audience 200, principal révoqué 401, dual/principal inchangés). Une écriture SQLite directe des colonnes `audience`/`token_hash` peut rendre le tombstone invisible (P2 d'intégrité, accès DB en écriture requis) ; garder la base inaccessible en écriture hors Core reste obligatoire.

**Mitigation opératoire immédiate, même après correctif** : ne jamais revenir en mode legacy avec l'ancien `ATLAS_TOKEN` après une révocation ; faire tourner `ATLAS_TOKEN` avant tout rollback et documenter l'impact V1–V3.

## P2 — capabilities vides

`ATLAS_V4_CAPABILITIES_JSON=""` est traité comme absent et conserve les valeurs par défaut, où les mutations sont actives. Cela ne contourne pas les grants du principal, mais peut surprendre un opérateur qui croit tout désactiver. Recommandation : refuser explicitement une valeur vide au démarrage, ou documenter que vide = absent. Pour un arrêt d'urgence, fournir des overrides `false` explicites et vérifier l'état effectif.

## P2 — permissions SQLite

`SQLiteStore` ne force pas le mode du fichier/répertoire. Sous `umask 022`, une base synthétique a été créée en `0644` dans un répertoire `0755`. Le volume Docker live est root-owned et le Core tourne en UID 10001 ; l'exposition dépend du chemin réel. Recommandation pour V7-11B : vérifier les permissions du volume avant déploiement, et/ou durcir `SQLiteStore` pour imposer `0700/0600` et refuser les symlinks.

## Ce qui reste NOT_RUN

- Stack isolée complète Core + Relay + connecteur avec sessions authentifiées réelles et matrice A/A, B/B, A/B, révocation et reconnexion.
- Tests live sur le Relay/connecteur actuels : le proxy 4400 et le connecteur 4401 sont absents, le Funnel pointe vers 4400 sans service.
- Rotation réelle de clé HMAC et révocation réelle.

## Verdict

`GO_V7_11_AUTH_STAGING` : **GO local partiel** (composants et tests négatifs) — la stack complète isolée reste **NOT_RUN**.
`GO_V7_11_DEVICE_ISOLATION` : **GO local** sur les tests A/B et ownership ; preuve live **NOT_RUN**.
`GO_V7_11_LEGACY_COMPATIBILITY` : **GO local** — le contournement P1 est fermé par A1-F2, testé et revu indépendamment ; la compatibilité legacy pré-import reste vérifiée.
`GO_V7_11_INDEPENDENT_SECURITY_REVIEW` : **GO avec réserves P2** sur `65759dd` (A4-F1). Aucun P0/P1 restant ; 5 P2 à traiter ou accepter avant le live.
