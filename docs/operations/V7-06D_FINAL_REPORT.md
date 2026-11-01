# ATLAS V7-06D — Fiabilité remote et révocation A4-4 : rapport final

**Statut du document :** `EN COURS — résultats à compléter après intégration`.
**Campagne :** V7-06D « Fiabilité remote et révocation A4-4 ».
**BASE_SHA :** `9ad90c7a079736ffc5f7bb6a7f0d4ee3eed70580`.
**Branche :** `feat/atlas-v7-06d-remote-hardening`.
**Registre :** `docs/operations/REGISTRY_V7_06D.md`.
**Diagnostic :** `docs/operations/V7-06D_DIAGNOSTIC.md`.
**Date :** 2026-11-01.

## 1. Résultat exécutif

`[À COMPLÉTER]` — résumé des deux P1, du P2 de provenance, des verdicts et des
limites physiques.

## 2. Périmètre et provenance

- Base et branche vérifiées ; aucun push, PR ou merge distant.
- Audits A1/A2/A3 en lecture seule, `codex/gpt-6-luna` / `xhigh`, worktrees
  détachés.
- Aucun ADB, Pixel, AVD, Docker de campagne ou service live modifié.
- Les preuves V7-06A/B/C ne sont pas réécrites.

## 3. Diagnostic

Voir `V7-06D_DIAGNOSTIC.md`. Résumé :

1. **Chat** : deadlock G↔C atteignable (thread de dispatch tenant G et attendant
   C dans `sendTextFrame` ; callback OkHttp tenant C et attendant G dans
   `pump()`), timeout inopérant car `pump()` précède `await`. Cause historique
   V7-06C `UNKNOWN` (pas de thread dump).
2. **A4-4** : trame pré-auth `DEVICE_REVOKED` émise en V1 pour un client V2,
   rejetée par le parseur strict ; le relais transforme le close 4001 en 1012 ;
   le close 1012 écrasait `REVOKED`. `revoked_at` était vérifié avant le token.
3. **Provenance** : aucun manifeste de build ni vérification APK installé↔construit.

## 4. Correctif concurrence du chat

`[À COMPLÉTER]` — test rouge, correctif, invariants, résultats JVM.

## 5. Correctif A4-4

`[À COMPLÉTER]` — connecteur, Android, tests, sonde, limites relais.

## 6. Provenance APK↔SHA

`[À COMPLÉTER]` — manifeste, gate, tests, build final.

## 7. Qualification d'intégration

`[À COMPLÉTER]` — commandes et résultats exacts : JVM, lint, builds, Node,
backend, harnais, `git diff --check`. Aucune recette physique.

## 8. Verdicts

| Verdict | Décision | Portée |
|---|---|---|
| `GO_CHAT_CONCURRENCY` | `[À COMPLÉTER]` | `[À COMPLÉTER]` |
| `GO_CHAT_RELIABILITY_QA` | `NOT_RUN` | aucune autorisation physique ; aucune série de démarrages froids |
| `GO_REVOKED_DEVICE_SERVER` | `[À COMPLÉTER]` | refus connecteur conservé |
| `GO_A4_4_CLIENT` | `NO GO — NOT_RUN` | sonde stricte non exécutée sur appareil |
| `GO_APK_PROVENANCE` | `[À COMPLÉTER]` | mécanisme vérifié, aucun run physique |
| `GO_REMOTE_V4_PUBLIC` | `GO_WITH_LIMITATIONS (QA APPLY/REJECT)` | inchangé V7-06C, aucun nouveau run public |
| `GO_PIXEL_PRODUCT` | `NO GO` | produit non testé |
| `GO_PRODUCT_REMOTE` | `NO GO` | produit non testé |

## 9. Sécurité et identités résiduelles

`[À COMPLÉTER]` — recontrôle lecture seule, demande de révocation ciblée,
décision séparée sur la rotation du bearer.

## 10. Nettoyage et inventaire

`[À COMPLÉTER]` — worktrees, sessions, processus, aucun service lancé.

## 11. Limites honnêtes

`[À COMPLÉTER]` — physique NOT_RUN, cause historique UNKNOWN, relais 1012,
identité de requête non persistée, autres hypothèses A1.

## 12. Prochaines actions

`[À COMPLÉTER]` — autorisation physique ciblée, revue indépendante sur le SHA
final, révocation/rotation live sur accord explicite.
