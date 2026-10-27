# R3-A — Revue indépendante

## 1. Première revue (lecture seule)

- Session : `c3f97221…`, modèle `codex/gpt-6-luna`, effort `xhigh`.
- Périmètre : SHA `48413dd308e12dacd2799d96280e919821610738`.
- Verdict : **NO_GO**.

| Sévérité | Constat | Correction | Statut |
|---|---|---|---|
| P1 | `pair-device` imprimait le token sur stdout | écriture dans un fichier 0600 avec `wx`, stdout sans token | **CORRIGÉ** |
| P1 | registre d'appareils sans verrou, mise à jour perdue possible | verrou fichier inter-processus, timeout 5 s, reprise après 10 s, test de concurrence | **CORRIGÉ** |
| P1 | preuves non rattachées au SHA ; matrice T1–T6 non rejouée | `source_sha`/`apk_sha256` dans les preuves finales, `PROVENANCE.md`, gates T1–T6 rejouées sur `ad42b1c` | **CORRIGÉ** |
| P2 | compte local nominatif visible dans une preuve | preuve redactée, compte synthétique `synthetic-local` dans les tests | **CORRIGÉ** |
| P2 | re-création d'un événement supprimé bloquée par l'ancienne clé d'idempotence | clé `-recreate-<lastVerifiedAt>` et test `recreatesEventAfterExternalDeletionAtTheSamePlanVersion` | **CORRIGÉ** |
| P2 | documentation crypto mentionnait Tink au lieu de Bouncy Castle | documentation corrigée (Bouncy Castle 1.86 + JCA) | **CORRIGÉ** |

## 2. Seconde revue (lecture seule)

- Périmètre demandé : SHA `ad42b1c515074e563699c599ce2f4ca52f6bd58c`,
  correctifs P1/P2, Wave C (mémoire, usage), provenance des preuves.
- Session : à lancer.
- Verdict : **EN ATTENTE**.

La seconde revue doit vérifier au minimum :

1. Absence de token ou de secret dans stdout, logs, preuves et captures.
2. Verrou du registre et absence de mise à jour perdue.
3. Re-création calendrier et absence de doublon.
4. Provenance : chaque PASS final lié à `ad42b1c` et à l'APK `e8937c62…`.
5. Parcours mémoire et Usage Access : consentement, purge, persistance, aucune
   lecture de contenu d'écran.
6. Aucune activation du Bridge/OpenClaw, aucun déploiement public, aucun push.
