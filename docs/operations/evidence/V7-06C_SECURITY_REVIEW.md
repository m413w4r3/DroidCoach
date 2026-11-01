# V7-06C — Revue indépendante (lecture seule)

**Date :** 2026-11-01
**Revue :** Paseo `d4a996c5-d07a-45d2-ad7e-9c837159c60b`,
`provider=codex`, `model=gpt-6-luna`, effort effectif `xhigh` (métadonnées
vérifiées), worktree détaché `/tmp/atlas-v7-06c-review-ro` en lecture seule
(`chmod -R a-w`).
**SHA revu :** `8cab6fcf64ac2a6265ac68ae896d83ee3f3bfa55`
**Base mission :** `3e0d1f4c9ca591f903a894be87183395d30fee0b`
**Verdict global :** `GO_WITH_LIMITATIONS` pour les parcours **QA APPLY/REJECT** ;
`GO_PIXEL_PRODUCT` et `GO_PRODUCT_REMOTE` restent **NO GO**.

La session de revue a confirmé `HEAD=8cab6fc`, `git status --short` vide, aucun
fichier modifié, aucun test/ADB/Docker/réseau lancé. Elle n’a pas interrogé le
serveur distant ; la ref locale `origin/main` pointait sur la base.

## 1. Matrice de vérification

| Claim | Preuve examinée | Verdict |
|---|---|---|
| Versions, commits, modèles, endpoints, APK distincts | SHA, commits, hashes APK et docs concordants ; hashes APK des proofs = docs ; modèles/endpoints local/public concordants | **INCONCLUSIVE** sur le lien binaire APK↔SHA : les proofs JSON ne sérialisent pas le SHA source et aucun APK n’était présent dans le worktree. Aucun désaccord visible. |
| APPLY : v2→v3, ACK APPLIED v3, rejeu sans second effet | SQLite Core : 1 proposition, 1 événement ACK, snapshots v2/600 et v3/610, révision concordante ; journal connecteur, proxy et logcat concordants | **PASS** |
| REJECT : plan v2, aucun v3, ACK REJECTED | SQLite Core : 1 ACK REJECTED, snapshot v2 seul ; logcat 12 étapes PASS ; proof locale marquée `post_hoc_existing_stack`, proof publique en run normal | **PASS** |
| Oracles indépendants | Croisement SQLite, `operations.json`, trace proxy, attestation Android liée à l’ACK courant ; 1 ACK Core par scénario | **PASS** |
| Harnais reproductible et nettoyage propriétaire | Scripts versionnés, `--relay-port 4400`, support WSS, codes PASS/FAIL/BLOCKED, arrêt par identité ; pas de dépendance exécutable à `/tmp/atlas-v7-06b-backend` | **PASS**, réserve P2 sur le champ pull |
| Transport public | APPLY et REJECT séparés en `public_wss`, endpoint WSS sur LTE, `adb_reverse=false`, clé Noise épinglée, relais loopback, Core/Gateway/proxy loopback, Funnel restauré | **PASS** pour ces exécutions QA |
| Révocation | `devices.json` révoqué ; métriques : 2 connexions, 1 session, 1 erreur, 0 session active ; probe FAIL `ERROR`/`close_code=null` | **FAIL** d’observabilité A4-4 ; refus serveur étayé |
| Fiabilité avant chat | Premier APPLY public bloqué avant toute requête chat ; relance PASS ; cause racine non confirmée | **FAIL** de fiabilité ; cause **INCONCLUSIVE** |
| P2 HTTP 422 | Mapping limité à `VALIDATION_FAILED` ; test HTTP 422 + absence de proposition ; backend 372/1 | **PASS** |
| Secrets et données | Aucune valeur bearer/secret ; série brute absente des proofs ; identifiants hashés/synthétiques ; jeton relay chiffré AES-GCM Keystore | **PASS** |
| Nettoyage et identités | Docs : 18 paquets QA désinstallés, produit/HOME/Keystore intacts, Wi‑Fi/Funnel restaurés, identités live résiduelles documentées | **INCONCLUSIVE** côté revue (pas d’ADB) ; vérification parent directe ci-dessous |

## 2. Constats

### P0

**Aucun P0 identifié.**

### P1 — blocage intermittent avant chat

Le premier run APPLY public s’est bloqué après les synchronisations, sans requête
`v4.coach.chat.request` reçue par le connecteur ; une relance sur stack neuve a
réussi. La cause racine est correctement laissée non confirmée (hypothèse
`consentGate`/WebSocket). Cela limite les verdicts de fiabilité publique et de
boucle Coach. Détails : `V7-06C_PUBLIC_WSS.md` §6 et `V7-06C_FINAL_REPORT.md` §6.

### P1 — révocation A4-4 mal exposée côté client

Les métriques étayent l’absence de nouvelle session, mais l’app ne donne ni
`REVOKED`/`AUTH_REQUIRED` ni code de fermeture exploitable ; la sonde
`V7_06C_RevokedDeviceReconnectInstrumentedTest` échoue explicitement. Le refus
serveur est confirmé, mais **A4-4 ne doit pas être déclaré qualifié**. Le probe
reste **FAIL**, sans transformation en PASS.

### P2 — champ de pull mal étiqueté

`run_scenario.py` affectait `pending_proposal_count` à `pull_count_after_runner` ;
le Core ne compte que les propositions pendantes, pas les pulls. Les proofs
affichaient donc `pull_count_after_runner=0` alors que le test Android atteste le
second pull. Le champ était trompeur, sans invalider le pull observé ni
l’idempotence. **Corrigé après la revue** dans le commit
`20445bfe8aa679a35f9b165389baeaaa5b77c07c` (libellé
`Pending proposals after runner`, suppression du champ trompeur ; 17/17 tests
harnais).

### P2 — traçabilité APK↔SHA source

Les docs associent SHAs source et hashes APK, mais les proofs JSON ne
sérialisent pas le SHA source et aucun APK n’était présent dans le worktree de
revue. C’est une lacune de traçabilité, pas un désaccord découvert. Les hashes
APK exacts et les SHAs d’exécution restent documentés dans les preuves
APPLY/REJECT/WSS ; une amélioration future doit ajouter un champ de provenance
aux proofs et/ou archiver un manifeste APK.

## 3. Recommandations de verdict

| Verdict | Recommandation de la revue | Décision parent |
|---|---|---|
| `GO_E2E_HARNESS_REPRODUCIBLE` | **GO_WITH_LIMITATIONS** — scénarios cohérents ; corriger le champ pull et renforcer SHA/APK | **GO_WITH_LIMITATIONS** ; P2 pull corrigé `20445bf`, P2 traçabilité suivi |
| `GO_ANDROID_QA_CHAT` | **GO_WITH_LIMITATIONS** — chat observé, blocage public intermittent | **GO_WITH_LIMITATIONS** |
| `GO_ANDROID_QA_PLAN_APPLY` | **GO** | **GO** |
| `GO_ANDROID_QA_REJECT` | **GO** | **GO** |
| `GO_ANDROID_QA_ACK` | **GO** | **GO** |
| `GO_QA_COACH_LOOP` | **GO_WITH_LIMITATIONS** — APPLY/REJECT QA réussis, P1 fiabilité | **GO_WITH_LIMITATIONS** |
| `GO_REMOTE_V4_PUBLIC` | **GO_WITH_LIMITATIONS** uniquement pour les parcours QA APPLY/REJECT réussis sur LTE ; **NO GO** pour une qualification générale de fiabilité, A4-4 ou produit | **GO_WITH_LIMITATIONS (QA APPLY/REJECT)** ; pas de qualification générale |
| `GO_PIXEL_PRODUCT` | **NO GO** — produit non testé | **NO GO** |
| `GO_PRODUCT_REMOTE` | **NO GO** — produit distant non testé | **NO GO** |

## 4. Vérifications parent après revue

- **P2 pull** : corrigé `20445bf` ; `py_compile`, 17/17 tests harnais,
  `git diff --check` PASS.
- **Nettoyage physique** (vérifié par le parent, hors revue read-only) : 18
  paquets QA (`dev.atlas.coach.qa*`, y compris anciens qa/qa2/qa3/qa4)
  désinstallés avec `Success` ; seuls `dev.atlas.coach` et
  `dev.atlas.coach.test` restent ; `lastUpdateTime` produit inchangé
  (`2026-10-31 00:18:13`), rôle HOME inchangé, Wi‑Fi restauré (`wifi_on=1`,
  réseau 152), aucun `adb reverse`.
- **Funnel** : cible `127.0.0.1:4400` de nouveau sans listener, état initial ;
  relais live `atlas-v7-relay` sur 4402 et Core/Gateway live non modifiés.
- **Identités live** : `pixel-v7`, `r3c-pixel-final` et le `device_ref`
  `android-9a3f0f…` (`GRANTED v2`) restent non révoqués ; demande de révocation
  ciblée préparée, non exécutée.

## 5. Limites de la revue

- Revue statique et documentaire ; aucun test, ADB, Docker ni réseau exécuté.
- Vérification du nettoyage physique, de l’APK installé et du lien
  cryptographique APK↔SHA hors de portée en lecture seule ; le parent a fourni
  les sorties directes.
- `origin/main` local seulement ; pas d’interrogation du serveur distant.
- Les deux P1 restent ouverts ; aucun correctif produit n’a été appliqué pendant
  la campagne.
