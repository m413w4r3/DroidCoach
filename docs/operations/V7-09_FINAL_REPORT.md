# ATLAS V7-09 — Rapport final

**Date :** 2026-11-05
**Branche :** `feat/atlas-v7-09-reject-and-readiness`
**BASE_SHA :** `efcfdcd0936ab2791f8ea14c151247257b05d19b`
**`origin/main` au démarrage :** `efcfdcd0936ab2791f8ea14c151247257b05d19b`
**Pilote quotidien :** **NO-GO**, non lancé.
**Publication distante :** aucune. Aucun push, PR ou merge n'a été effectué.

## 1. Résultat exécutif

Le parcours REJECT est **prouvé de bout en bout sur le Pixel physique** :

> GPT-6 Luna → `atlas_get_day_plan` → `atlas_propose_plan_revision` →
> proposition A0 `2a29a35f…` → réception inbox Android → refus explicite →
> ACK durable `REJECTED / USER_REJECTED` → plan inchangé (v2 / 1380) →
> rejeu idempotent `X-Atlas-Replayed: true` → un seul événement.

Les preuves APPLY V7-08 restent valides et n'ont pas été rejouées.

La proposition V7-08 expirée `2da9c5e2…` n'a pas été réutilisée. Elle est
**expirée par l'horloge** depuis `2026-11-05T19:28:58Z`, n'a jamais été livrée
à l'inbox Android, n'a reçu aucun ACK, et reste `PENDING_ANDROID` en Core :
défaut de cycle de vie P1 documenté, non contourné.

## 2. État Git

| Référence | SHA / état |
|---|---|
| `main` local au démarrage | `efcfdcd0936ab2791f8ea14c151247257b05d19b` |
| `origin/main` au démarrage | `efcfdcd0936ab2791f8ea14c151247257b05d19b` |
| Branche V7-09 | `feat/atlas-v7-09-reject-and-readiness`, créée sur `efcfdcd` |
| A1 Core audit | `7fa8000` — `docs/operations/evidence/V7-09_A1_CORE_AUDIT.md` |
| A2 QA assertions | `2dde10c` — `docs/operations/evidence/V7-09_A2_QA_ASSERTIONS.md` |
| A3 sécurité | `9741903` — `docs/operations/evidence/V7-09_A3_SECURITY_READINESS.md` |
| Documents V7-09 | commit `docs(v7-09): …` (voir `git log`) |
| A4 revue indépendante | à exécuter après le commit documentaire |
| Push / PR / merge | **aucun** |

Historique V7-08 préservé ; aucun `reset --hard`, aucun force-push, aucune
réécriture destructive.

## 3. Orchestration

| Tâche | Rôle | Modèle / effort | Worktree | Sortie |
|---|---|---|---|---|
| A1 | audit cycle Core A0, expiration, pull, ACK, idempotence | `codex/gpt-6-luna`, `xhigh` | `v7-09-a1-core-audit` | `7fa8000` |
| A2 | assertions QA et observabilité, sans ADB | `codex/gpt-6-luna`, `xhigh` | `v7-09-a2-qa-recipe` | `2dde10c` |
| A3 | P1 bearer, fiabilité V4 | `codex/gpt-6-luna`, `xhigh` | `v7-09-a3-security` | `9741903` |
| A4 | revue indépendante lecture seule | `codex/gpt-6-luna`, `xhigh` | à créer | en cours / à clôturer |
| Paseo | préflight, ADB/UI, sauvegardes, intégration, rédaction | orchestrateur | dépôt principal | docs V7-09 |

Aucune sous-délégation. Paseo est le seul opérateur ADB et le seul intégrateur
Git. Les trois rapports sont des commits d'un fichier chacun, cherry-pickés
sans conflit.

## 4. Recette physique — synthèse

| Étape | Preuve |
|---|---|
| Appareil | Pixel 9 Pro XL, Android 17 / API 37, `Europe/Paris` |
| APK | `dev.atlas.coach` v1 / 0.1.0, SHA-256 `c7b0f71f…`, certificat `da301466…eeee` |
| HOME | `dev.atlas.coach/.app.LauncherActivity` |
| Sauvegarde | archive `9867e9ca…`, 25 fichiers, `VERIFY_OK` |
| Transport | LTE → Funnel TLS → proxy 4400 → Relay/Noise 4402 → connecteur 4401 → Core 8100 → Gateway 18789 |
| Modèle | `openai/gpt-6-luna`, 6 outils client, `profile=minimal` |
| Conversation | rotation unique `c86468d5…` → `dcf947e6…`, un seul champ modifié |
| Plan | `3d2dba67…`, 2026-11-05, activité `9245884e…` 23:00–23:45, 45 min |
| Projection pré-chat | v2, `observed_at=2026-11-05T20:56:01.250780Z` |
| Chat | `POST /v4/agent/chat` 200, 2 appels d'outils, `tool_state=completed` |
| Proposition | `2a29a35f-23b4-4aae-9d28-2904320d6029`, base v2, A0, `NONE_AT_CORE` |
| Réception | inbox Android `RECEIVED`, pull 200 |
| REJECT | geste Android à `21:02:53Z` |
| ACK | `9114cf22…`, `REJECTED`, `USER_REJECTED`, `applied_plan_version` absent, `received_at=21:03:16Z` |
| Plan après | v2 / 1380 / 45 min, révision `28d1c55c…` inchangée |
| Rejeu | POST exact → 200, `X-Atlas-Replayed: true`, événements toujours 1 |
| Nettoyage | plan local v3 vide, Core v3 vide, `observed_at=21:07:04Z` |
| État final | Wi-Fi restauré, mode avion 0, HOME ATLAS, proxy/connecteur arrêtés |

Détails complets : `V7-09_REJECT_QA.md`.

## 5. Tests réellement exécutés

| Suite | Commande | Résultat |
|---|---|---|
| Backend ciblé | `pytest tests/test_v4_proposals.py tests/test_v7_08_a0_protocol.py tests/test_chat_orchestration_v4.py tests/test_v4_projection_store.py tests/test_v4_contracts.py -q` | **121 passed**, 1 warning |
| Backend complet | `pytest -q` | **408 passed, 1 skipped**, 3 warnings |
| Android JVM | `./gradlew :app:testDebugUnitTest` | **BUILD SUCCESSFUL**, 922 tests, 0 échec, 0 skip |
| Android lint | `./gradlew :app:lintDebug` | **BUILD SUCCESSFUL**, 0 erreur, 89 avertissements préexistants |
| `git diff --check` / `git show --check` | — | voir §9 |
| Pixel physique | recette REJECT | **PASS** (voir §4) |

Les avertissements backend sont préexistants (dépréciation Starlette/httpx,
deux Operation ID OpenAPI dupliqués). Aucun test Android instrumenté n'a été
exécuté et aucun APK n'a été installé.

## 6. Gates

| Gate | Verdict | Portée |
|---|---|---|
| `GO_V7_09_REJECT_PROPOSAL` | **GO** | proposition A0 unique, persistée, liée au bon plan/version |
| `GO_V7_09_REJECT_ANDROID` | **GO** | geste explicite, inbox, plan inchangé |
| `GO_V7_09_REJECT_ACK` | **GO** | ACK durable `REJECTED / USER_REJECTED`, sans version appliquée |
| `GO_V7_09_REJECT_IDEMPOTENCE` | **GO_WITH_LIMITATIONS** | rejeu Core exact prouvé ; le worker n'a pas rejoué spontanément un ACK `DELIVERED` |
| `GO_PRODUCT_PLAN_REVISION` | **GO_WITH_LIMITATIONS** | APPLY V7-08 + REJECT V7-09 ; P1 cycle de vie expiré |
| `GO_PRODUCT_REMOTE_CHAT` | **GO_WITH_LIMITATIONS** | chat outillé réel ; marge de fraîcheur ~4 s ; pull via workaround |
| `GO_PRODUCT_REMOTE` | **GO_WITH_LIMITATIONS** | transport public réel ; P1 bearer |
| `GO_PRODUCT_LOCAL_SMOKE` | **GO** | HOME, Plan, Coach, Focus, hors ligne ; aucun crash/ANR |
| `GO_LIVE_SECURITY` | **GO_WITH_LIMITATIONS** | aucun P0 ; P1 bearer partagé toujours ouvert |
| `GO_SOURCE_AND_CI` | **GO_WITH_LIMITATIONS** | backend 408/1, Android JVM 922/0, lint 0 erreur / 89 warnings ; pas de CI distante |
| `GO_MAIN_INTEGRATION` | **GO_WITH_LIMITATIONS** | branche locale prête, revue A4 à clôturer ; aucun push/merge |
| `GO_DAILY_PILOT` | **NO-GO** | non autorisé ; P1 bearer et pull relay non corrigés |

## 7. Risques ouverts

| Gravité | Risque | État |
|---|---|---|
| **P1** | bearer Android partagé | inchangé, non tourné ; plan de migration dans `V7-09_SECURITY_READINESS.md` |
| **P1** | proposition expirée jamais tirée | `2da9c5e2…` reste `PENDING_ANDROID` ; clôture native impossible |
| P2 | pull immédiat en mode relais | workaround « Enregistrer la connexion » ; correction priorité `a` |
| P2 | toggle Conversation V4 non persistant | réactivé manuellement |
| P2 | `STALE_DATA` → HTTP 400 dans le chat | non corrigé |
| P2 | `Future.cancel()` n'interrompt pas un outil synchrone | dette acceptée |
| P2 | enveloppe de pull non publiée dans le contrat | dette acceptée |
| P2 | compteur de quota en mémoire | dette acceptée |
| P2 | résidu synthétique du plan 2026-11-04 | signalé, non manipulé |
| P2 | marge de fraîcheur de 5 min | tour réussi avec ~4 s de marge |

Aucun P0 identifié.

## 8. Défauts UI

La recette a relevé des défauts d'ergonomie et de ton, documentés dans
`V7-09_UI_DEFECTS.md` : absence de « nouvelle conversation », historique très
long sans saut au composer, jargon d'outils/UUID dans l'UI, doublons de
messages, workaround de synchronisation caché, dates UTC brutes, bloc A1
surdimensionné, libellés « Programme/Planning » et « Pause/Fin » ambigus.
Aucune correction n'a été appliquée.

## 9. Fichiers et intégrité Git

Fichiers ajoutés par V7-09 :

- `docs/operations/evidence/V7-09_A1_CORE_AUDIT.md`
- `docs/operations/evidence/V7-09_A2_QA_ASSERTIONS.md`
- `docs/operations/evidence/V7-09_A3_SECURITY_READINESS.md`
- `docs/operations/V7-09_REJECT_QA.md`
- `docs/operations/V7-09_SECURITY_READINESS.md`
- `docs/operations/V7-09_FINAL_REPORT.md`
- `docs/operations/V7-09_UI_DEFECTS.md`
- `docs/operations/V7-08_ADDENDUM_V7_09.md`

Aucun fichier de code produit n'a été modifié. Aucun secret, token, bearer,
identifiant de série en clair ou donnée personnelle n'a été ajouté.

Différence Git cumulée sur la branche : **8 fichiers, 1496 insertions,
0 suppression** (`git diff --stat efcfdcd..HEAD`), tous sous
`docs/operations/`.

Contrôles prévus avant clôture : `git status`, `git diff --check`,
`git show --check`, `git log --oneline`, vérification des worktrees.

## 10. Proposition de publication

La branche locale est prête pour examen. **Aucun push, PR ou merge n'est
effectué sans nouvelle autorisation du propriétaire.** La publication proposée
est un push de `feat/atlas-v7-09-reject-and-readiness` puis une PR vers
`main`, après lecture du présent rapport et de la revue A4.

Le pilote quotidien de sept jours reste **NO-GO et NON AUTORISÉ**.
