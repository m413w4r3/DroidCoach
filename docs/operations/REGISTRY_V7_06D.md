# ATLAS V7-06D — Fiabilité remote et révocation A4-4 : registre de campagne

**Mission :** fermer les deux P1 laissés ouverts par V7-06C — blocage
intermittent du chat Android avant émission vers le connecteur, et restitution
côté Android du refus d'un appareil révoqué (A4-4) — puis fermer le P2 de
traçabilité APK↔SHA. Aucune nouvelle campagne produit.

**BASE_SHA :** `9ad90c7a079736ffc5f7bb6a7f0d4ee3eed70580`
(local = `origin/main`, vérifié le 2026-11-01).
**Branche de travail :** `feat/atlas-v7-06d-remote-hardening`, créée depuis ce
SHA. Aucun push, PR, merge distant ni service live modifié à ce stade.

**Statut global :** `RUNNING — audits indépendants en cours`.

## 1. Contraintes d'exécution

- Parent Paseo orchestrateur : découpe, délègue, contrôle, intègre localement.
- **Tous** les sous-agents : `provider=codex`, `model=gpt-6-luna`, effort effectif
  `xhigh`, vérifié dans les métadonnées Paseo de chaque session.
- 6 enfants simultanés maximum, 3 implémentations simultanées maximum, aucune
  sous-délégation, un worktree et une propriété d'écriture par enfant.
- Audits et revues techniquement en lecture seule (Codex `collaborationMode=Plan`,
  worktree détaché, aucun test/ADB/Docker/réseau lancé).
- Un seul opérateur ADB/AVD à la fois. **Aucun ADB sur le Pixel physique** sans
  nouvelle autorisation spécifique.
- Une notification de fin n'est pas une validation : le parent relit le rapport
  et la preuve avant `ACCEPTED`/`INTEGRATED`.
- Aucun token, bearer, secret, UUID brut, contenu utilisateur ou donnée
  personnelle dans les preuves.

## 2. Pré-vol V7-06D-00 (lecture seule, 2026-11-01)

| Élément | Constat |
|---|---|
| Git local | `HEAD=9ad90c7a079736ffc5f7bb6a7f0d4ee3eed70580`, arbre propre |
| Git distant (réf. locale) | `origin/main=9ad90c7a079736ffc5f7bb6a7f0d4ee3eed70580` (identique) |
| CI GitHub | run `41316272473` sur `9ad90c7`, workflow `CI`, conclusion `success` |
| Branche | `feat/atlas-v7-06d-remote-hardening` |
| JDK | OpenJDK `21.0.12.1` |
| Node / npm | `v24.18.0` |
| Python | `3.12.3` |
| Docker | `27.1.1` |
| SDK Android | platforms `android-37.0`, `37.1`, `37.2` ; build-tools `37.0.0` |
| Paseo | provider `codex` disponible ; `gpt-6-luna` avec effort `xhigh` disponible |
| Appareil physique | **non contacté** — aucun ADB lancé ; aucun test physique autorisé à ce stade |
| Services live | non inspectés en écriture ; aucune action live prévue pendant les audits |

**Décision de pré-vol :** les trois audits A1–A3 sont lancés en lecture seule sur
des worktrees détachés au `BASE_SHA`. Aucun code n'est modifié avant
l'établissement de la nécessité des correctifs et de leurs invariants.

## 3. Tickets

| TASK_ID | Objectif | Dépendance | Statut | Session Paseo | Worktree | Oracle |
|---|---|---|---|---|---|---|
| V7-06D-00-PREFLIGHT | SHA, CI, outils, worktrees, registre | — | **ACCEPTED (lecture seule)** | parent | racine | SHA local=distant, arbre propre, aucun ADB/live |
| V7-06D-A1-ANDROID-CONCURRENCY | Graphe verrous/threads, cycle complet ou absence, tests à barrières du P1 chat | PREFLIGHT | **ACCEPTED (lecture seule)** — cycle G↔C complet et atteignable prouvé (`pump` tient G et attend C dans `sendTextFrame` ; callback OkHttp tient C et `pump()` attend G) ; timeout 55 s inopérant car `pump()` précède `await` ; cause historique reste UNKNOWN ; autres hypothèses classées | `db85f131-1494-48b8-b227-23dd60ee4f40` | `/tmp/atlas-v7-06d-a1-android-ro` | cycle inter-threads nommé, file:line, tests à barrières, invariants |
| V7-06D-A2-REVOCATION-A4-4 | Matrice des refus et perte du code/état côté client | PREFLIGHT | **ACCEPTED (lecture seule)** — cause exacte : trame pré-auth `DEVICE_REVOKED` émise en V1 pour un client V2 → parse strict Android → `ERROR` + `abort(4003)` + `close_code=null` ; le relais transforme le close serveur en 1012 ; `revoked_at` vérifié avant le token (divulgation) | `3b0bbe77-4d1b-44db-8da1-16f06f61f854` | `/tmp/atlas-v7-06d-a2-revocation-ro` | matrice, point de perte, ordre token, tests discriminants |
| V7-06D-A3-RELIABILITY-PROVENANCE | Durabilité/corrélation/harnais + traçabilité APK↔SHA | PREFLIGHT | **ACCEPTED (lecture seule)** — aucun P0 ; P1 identité de requête non persistée, P1 attente de gate non bornée avant le timeout, P2 provenance documentaire, P2 403 direct, P2 gate d'instrumentation permissif, P2 timeouts ADB ; design manifeste fourni | `5f2eb93c-cc6c-4e9d-9f36-5db1fc31e3dc` | `/tmp/atlas-v7-06d-a3-proof-ro` | rapport structuré, file:line, champs absents, tests négatifs proposés |

| V7-06D-X-CHAT-CONCURRENCY | Test rouge G↔C, correction verrous/attente bornée, distinction timeout avant/après envoi, traces bornées | A1 | **RUNNING** | `ac0cad8c-1364-498e-b6c9-a0eceb302a71` | `/tmp/atlas-v7-06d-fix-chat` (`fix/v7-06d-chat-concurrency`) | test rouge échoue sur BASE puis vert ; JVM complet ; aucun envoi après refus/timeout pré-envoi |
| V7-06D-Y-A4-4-REVOCATION | Protocole DEVICE_REVOKED pré-auth, ordre token/révocation, état REVOKED collant, sonde stricte renforcée | A2 | **RUNNING** | `e3d364f5-0545-4692-a02d-46a538d8b0ca` | `/tmp/atlas-v7-06d-fix-a4-4` (`fix/v7-06d-a4-4-revocation`) | test rouge V1→V2, Node complet, JVM policy, probe compilée |
| V7-06D-Z-APK-PROVENANCE | Manifeste de build embarqué + vérification APK installés + champs proof + tests négatifs | A3 | **RUNNING** | `29a8a6fc-ab40-410a-9419-b90839ad6e57` | `/tmp/atlas-v7-06d-feat-provenance` (`feat/v7-06d-apk-provenance`) | manifeste extrait des deux APK, gate installé→build, tests dirty/mismatch/absent, build QA PASS |

## 4. Journal des sessions

| Date | Session | Rôle | Provider/modèle/effort constatés | Résultat |
|---|---|---|---|---|
| 2026-11-01 | parent | Pré-vol V7-06D-00 | session parente | `ACCEPTED` lecture seule ; branche créée ; aucun ADB/live |
| 2026-11-01 | `db85f131-1494-48b8-b227-23dd60ee4f40` | V7-06D-A1 audit concurrence | codex / `gpt-6-luna` / `xhigh` (`effectiveThinkingOptionId=xhigh`, `collaborationMode=Plan`) | RUNNING — première tentative arrêtée sur auto-vérification du modèle, reprise après vérification parent |
| 2026-11-01 | `3b0bbe77-4d1b-44db-8da1-16f06f61f854` | V7-06D-A2 audit révocation | codex / `gpt-6-luna` / `xhigh` (`effectiveThinkingOptionId=xhigh`, `collaborationMode=Plan`) | RUNNING — première tentative arrêtée sur auto-vérification du modèle, reprise après vérification parent |
| 2026-11-01 | `5f2eb93c-cc6c-4e9d-9f36-5db1fc31e3dc` | V7-06D-A3 audit fiabilité/provenance | codex / `gpt-6-luna` / `xhigh` (`effectiveThinkingOptionId=xhigh`, `collaborationMode=Plan`) | RUNNING — première tentative arrêtée sur auto-vérification du modèle, reprise après vérification parent |

## 5. Règles de preuve

- Chaque résultat porte `PASS`, `FAIL`, `BLOCKED`, `NOT_RUN` ou `INCONCLUSIVE`,
  lié au SHA exact, à l'environnement et à la commande.
- Un test compilé n'est pas un test exécuté ; un test JVM n'est pas une preuve
  sur téléphone ; une correction préventive n'est pas la cause historique.
- Une seule relance qui PASS après un blocage ne prouve pas la fiabilité.
- Les preuves V7-06C restent intactes ; aucun verdict historique n'est réécrit.
- Toute action live (révocation, rotation) exige une autorisation ciblée et une
  sauvegarde vérifiée ; elle n'est pas exécutée dans cette phase.
