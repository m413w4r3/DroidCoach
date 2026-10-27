# R3-A — Revue indépendante

## 1. Première revue (lecture seule)

- Session : `c3f97221…`, modèle `codex/gpt-6-luna`, effort `xhigh`.
- Périmètre : SHA `48413dd308e12dacd2799d96280e919821610738`.
- Verdict : **NO_GO**.

| Sévérité | Constat | Correction | Statut |
|---|---|---|---|
| P1 | `pair-device` imprimait le token sur stdout | écriture dans un fichier 0600 avec `wx`, stdout sans token | **CORRIGÉ** |
| P1 | registre d'appareils sans verrou, mise à jour perdue possible | verrou fichier inter-processus | **CORRIGÉ** |
| P1 | preuves non rattachées au SHA ; matrice T1–T6 non rejouée | `source_sha`/`apk_sha256`, `PROVENANCE.md`, gates rejouées | **CORRIGÉ** |
| P2 | compte local nominatif visible dans une preuve | preuve redactée, compte synthétique dans les tests | **CORRIGÉ** |
| P2 | re-création d'un événement supprimé bloquée | clé `-recreate-<lastVerifiedAt>` et test dédié | **CORRIGÉ** |
| P2 | documentation crypto mentionnait Tink | documentation corrigée | **CORRIGÉ** |

## 2. Seconde revue (lecture seule)

- Session : `d87dec39-12fc-4d32-8f75-f5ff1607295b`, modèle `codex/gpt-6-luna`,
  effort `xhigh`, worktree `review-r3a-final-e28b7a9`.
- Périmètre : SHA `e28b7a9bfc0d6405cd0d311eb87d4d2dd33f5832`.
- Verdict : **NO_GO**.

| Sévérité | Constat | Correction sur `d89a626` | Statut |
|---|---|---|---|
| P1 | la reprise d'un verrou périmé pouvait supprimer le verrou d'un autre processus | verrou atomique avec record propriétaire, vérification de vivacité et nonce ; tests inter-processus vivant/tué/concurrence | **CORRIGÉ** |
| P1 | PASS UI calendrier non prouvés sur l'APK final | parcours UI v2→v3→v4 rejoué sur `fb19180`, captures et provider consignés | **CORRIGÉ** |
| P1 | aucun quota disponible dans le parcours réel | quota quotidien éditable et persisté, `quota-jour` affiché ; fenêtre 7 jours supportée | **CORRIGÉ** |
| P2 | quota hebdomadaire tronqué à 24 h | collecte portée à 7 jours et test JVM `weeklyWindowIsCollectedUpToSevenDaysWhenConfigured` | **CORRIGÉ** |
| P2 | documents mentionnant encore Tink | décision et ticket corrigés avec note historique | **CORRIGÉ** |
| P2 | authenticator exporté sans permission de liaison | `android:permission="android.permission.ACCOUNT_MANAGER"` ; test instrumenté de liaison réelle | **CORRIGÉ** |
| P2 | index de provenance incomplet | `PROVENANCE.md` et `PREUVES_SECURITE.md` synchronisés | **CORRIGÉ** |

## 3. Troisième revue (lecture seule)

- Session : `ebb8d049-65a3-4bf8-bbfd-6e863e7e1cf4`, modèle `codex/gpt-6-luna`,
  effort `xhigh`, worktree `review-r3a-final-7b3dc31`.
- Périmètre : docs `7b3dc31f531fed94a6c1ef617c553a56e2f2d16d`, code
  `d89a626a3b879b9ddce7486ec8f1b76b61845a37`, APK
  `fb191804571595a478f89e007f6c30cc4d6d83373b2e8005baa6fdd61dbb4d90`.
- Verdict : **NO_GO** (1 P1, 2 P2, 1 P3).

| Sévérité | Constat | Correction sur `3811ea5` | Statut |
|---|---|---|---|
| P1 | deux récupérateurs pouvaient supprimer le nouveau verrou et entrer ensemble | verrou bakery : record stable par nonce, `choosing`, ticket total, reprise ignorée si le propriétaire est mort ; stress inter-processus 10 rounds × 6 workers | **CORRIGÉ** |
| P2 | délai 5 s < seuil de péremption 10 s | remplacé par un timeout de 15 s et une reprise immédiate d'un propriétaire mort | **CORRIGÉ** |
| P2 | quota `1441` provoquait une exception au clic | validation UI 1–1440, message d'erreur, bouton désactivé, test réel sur l'APK final | **CORRIGÉ** |
| P3 | matrice à 6 événements contre preuve à 148 | matrice synchronisée avec le rejeu final (6 événements sur l'APK final) | **CORRIGÉ** |

## 4. Quatrième revue (lecture seule)

- Session : `17a3189e-9e9a-4764-b35b-78c8b4b812d0`, modèle `codex/gpt-6-luna`,
  effort `xhigh`, worktree `review-r3a-final-bc6fb3b`.
- Périmètre : docs `bc6fb3ba028dc1ae3938b3dd4e2bff9fcf1691ea`, code
  `3811ea51c8c4bffeb7b70d1197c3c4003e16c948`, APK
  `8ad8d2d53de6794894219b13ca62641c59e96ffd4966f52c69309f2f4cfb4c8c`.
- Verdict : **NO_GO** (1 P1, 2 P2, 1 P3).

| Sévérité | Constat | Correction sur `c45b64f` | Statut |
|---|---|---|---|
| P1 | une erreur de lecture du répertoire/record était traitée comme absence de contention | `readEntries` fail-closed : toute erreur `readdir`/`readFile`/parsing est propagée ; seul un `ENOENT` par record est toléré ; tests EACCES et record invalide | **CORRIGÉ** |
| P2 | stress non déterministe sur les fenêtres choosing/ticket | barrière de départ pour 6 workers + test d'arrivée après entrée ; stress externe 50 rounds × 6 processus sans chevauchement | **CORRIGÉ** |
| P2 | PID recyclé hors Linux | README : Linux qualifié, `/proc` pour le jeton de démarrage ; hors Linux, fallback `kill(0)` avec blocage borné au timeout, sans entrée concurrente | **CORRIGÉ** |
| P3 | capture mémoire montrant un doublon | `memory-ui.txt` explique le doublon de `PrefereMatinsCalmes` (sauvegarde refaite hors écran) ; la restauration finale purge les données de test | **CORRIGÉ** |

## 5. Cinquième revue (lecture seule)

- Session : `398a9aea-3579-4155-b23a-7a21f8bba42b`, modèle `codex/gpt-6-luna`,
  effort `xhigh`, worktree `review-r3a-final-cf75d09`.
- Périmètre : docs `cf75d09884cf7381935143912e3fc9334268efdb`, code
  `c45b64f3543453c0887eb00a5e29b0ad239e7c2a`, APK
  `8ad8d2d53de6794894219b13ca62641c59e96ffd4966f52c69309f2f4cfb4c8c`.
- Verdict : **GO_WITH_LIMITATIONS** (aucun P0/P1 ; 1 P2, 3 P3).

| Sévérité | Constat | Suite donnée | Statut |
|---|---|---|---|
| P2 | `list()`/`get()` pouvaient créer un registre vide et écraser un premier appairage concurrent | `load()` sans effet de bord ; le registre n'est créé que par `pair()` sous verrou ; test lecture concurrente + premier pair | **CORRIGÉ sur `02a5040`** |
| P3 | pas de test EACCES sur la lecture d'un record | test ajouté dans `file-lock.test.ts` | **CORRIGÉ sur `02a5040`** |
| P3 | arrivée tardive non synchronisée | le worker signale `ready` avant sa tentative ; le test vérifie qu'il n'entre pas pendant la section critique | **CORRIGÉ sur `02a5040`** |
| P3 | captures mémoire avec doublon | note explicite dans `memory-ui.txt` ; les images sont identifiées comme artefacts de test et les données sont purgées au nettoyage | **DOCUMENTÉ** |
| P3 | quota désactivé affiché `UNKNOWN` | défaut d'affichage de faible impact, accepté comme limitation connue | **LIMITATION** |

## 6. Revue delta du correctif P2 (lecture seule)

- Périmètre : SHA `02a5040283d087c4d401fd0f6e8f95f9cda752c3`,
  APK inchangé `8ad8d2d53de6794894219b13ca62641c59e96ffd4966f52c69309f2f4cfb4c8c`.
- Session : reprise de `398a9aea…`.
- Verdict delta : **GO_WITH_LIMITATIONS** — aucun P0/P1/P2 résiduel.

| Sévérité | Constat | Statut |
|---|---|---|
| P2 | `load()` écrivait un registre vide et pouvait écraser un premier appairage | **CORRIGÉ** — lectures sans effet de bord, création uniquement par `pair()` sous verrou, test lecture concurrente + premier pair |
| P3 | le test d'arrivée tardive signale `ready` juste avant l'appel, sans prouver que la tentative a commencé | **LIMITATION DE TEST** — l'ordre final et le test de propriétaire vivant couvrent déjà l'exclusion ; test à resynchroniser dans un futur lot |

La revue delta n'a exécuté aucun test ; la stabilité d'exécution reste
`NOT_VERIFIED`. Le verdict final local est **GO_WITH_LIMITATIONS** ; V6.3 global
reste **NO_GO**.
