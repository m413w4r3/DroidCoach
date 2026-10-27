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

- Périmètre demandé : SHA `3811ea51c8c4bffeb7b70d1197c3c4003e16c948`,
  APK `8ad8d2d53de6794894219b13ca62641c59e96ffd4966f52c69309f2f4cfb4c8c`.
- Session : à lancer.
- Verdict : **EN ATTENTE**.

La quatrième revue doit vérifier au minimum :

1. Absence de token ou de secret dans stdout, logs, preuves et captures.
2. Verrou bakery : records stables, reprise sans perte d'exclusivité, stress
   concurrent, révocation non écrasée.
3. Re-création calendrier, parcours UI final et absence de doublon.
4. Provenance : chaque PASS final lié à `3811ea5` et à l'APK `8ad8d2d…`.
5. Quota : validation 1–1440, persistance, sémantique `UNKNOWN` sur couverture
   partielle, fenêtre hebdomadaire.
6. Authenticator : permission de liaison et AccountManager fonctionnel.
7. Mémoire et Usage Access : consentement, purge, persistance, aucune lecture de
   contenu d'écran.
8. Aucune activation du Bridge/OpenClaw, aucun déploiement public, aucun push.
