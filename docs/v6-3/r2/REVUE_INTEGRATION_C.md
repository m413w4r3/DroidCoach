# ATLAS V6.3 — R2 — Revue d'intégration de la vague C

Date : 2026-10-26. Relecture parent sur les diffs et oracles rejoués sur le SHA
intégré.

## 1. R2-USAGE-01 — observation globale et résumés

| Élément | Valeur |
|---|---|
| Commit auteur | `69b75affa99ac0e512ceb322bfd3630b24e91ee5` |
| Scope | 10 fichiers neufs sous `usage/**`, aucun fichier existant modifié |
| Intégration | merge local |
| Oracle auteur | Gradle BLOCKED (sandbox) |
| Oracle parent | 17 tests usage PASS ; suite JVM 563 PASS ; lint/build PASS |

Constats :

- voie globale additive, la voie par allowlist existante reste intacte ;
- calcul d'intervalles appariés, pause manquante, fiabilité, fenêtres bornées,
  DST, fenêtre vide, grande fenêtre tronquée ;
- `UsageSummary` minimisé, `appRef` pseudonyme stable, noms de packages
  uniquement si la catégorie `package_names` est consentie ;
- consentement global explicite et révocable, baseline sans rétro-remplissage,
  permission refusée purge le curseur et le résumé ;
- quotas locaux (`CALENDAR_DAY`, `ROLLING_24H`, `WEEK`) et états
  `OK/WARNING/EXCEEDED/UNKNOWN` ;
- aucun `AccessibilityService`, aucun blocage, aucune UI.

Limites / P2 :

- non câblé à l'UI ni au backend ; aucun test Usage Access réel sur l'émulateur
  dans cette campagne ;
- `GlobalUsageObserver` n'a pas de throttle de polling (le chemin allowlist en
  a un) ; à ajouter avant un usage visible ;
- la précision réelle sur Pixel reste `NOT_RUN`.

Verdict : **ACCEPTED_WITH_P2** pour le noyau ; câblage et test matériel
ultérieurs.

## 2. R2-BRIDGE-QA-01 — plugin de recherche OpenClaw

| Élément | Valeur |
|---|---|
| Commit auteur | `22a68abe8a793d1c92841460391ff0d9a6d56d2b` |
| Scope | plugin uniquement, y compris `dist` généré |
| Intégration | merge local |
| Oracle parent | `npm test` 50/50, `npm run plugin:build`, `openclaw plugins validate` PASS |

Constats :

- `/health` et `/ready` vérifiés avant tout run ; erreurs typées
  `health_unavailable` / `extension_unavailable` ;
- 401 **et** 403 classés comme erreur d'authentification, sans fuite de clé ;
- 429 avec `Retry-After` conservé ; 409 sans retry avec corps différent ;
- même clé + même payload = même run ;
- POST background puis poll des états `queued|running|completed|failed|needs_review` ;
- citations `visible_citations` traitées comme données non fiables ;
- `prompt_instructed` explicitement rendu `PROMPT_INSTRUCTED_UNVERIFIED` et
  `verified_search=false` ;
- un seul outil `atlas_chatgpt_research`, `optional: true`, sans outil shell,
  fichier, session ou mutation ;
- 16 recettes documentées dans `VALIDATION_R2.md`.

Limites / P2 :

- `npm ci` a signalé **10 vulnérabilités** dans l'arbre installé, dont
  **2 critiques** et 5 élevées (dépendances de développement/OpenClaw). Le
  plugin n'est ni installé ni activé ; une revue `npm audit` et une mise à jour
  des dépendances sont requises avant activation ;
- aucun appel au Bridge réel, aucune extension ChatGPT, aucun Gateway ; le mode
  réel reste `PROMPT_INSTRUCTED_UNVERIFIED` ;
- activation **BLOCKED_POLICY** : configuration active, installation et
  redémarrage exigent un accord ciblé.

Verdict : **ACCEPTED_WITH_P2 (code)** ; activation non autorisée.

## 3. État des gates après la vague C

- Backend : 178 passed, 1 skipped (inchangé).
- JVM Android : 78 suites, **563 tests**, 0 échec.
- Lint : 0 erreur, 54 avertissements.
- Build debug et androidTest : PASS.
- Instrumentés API 37 : 17/17 PASS (vague B ; aucun nouveau test instrumenté
  usage/bridge).
- Aucun P0/P1 dans le périmètre livré ; P2 documentés ci-dessus.
