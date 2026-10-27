# ATLAS V6.3 — R3-A+ — Revue indépendante

## 1. Revue initiale (lecture seule)

- Session : `a5dc2140-f437-4f2c-bda8-be459d661327`, provider `codex`, modèle
  `gpt-6-luna`, effort effectif `xhigh`, plan mode lecture seule.
- Cible : docs `8f38f2aa033266edf799da20a63a7eb39bef2bc1`, code
  `38fd4a09fcb0d151632389bf220644de3529b598`.
- Verdict : **NO_GO** (1 P1, 3 P2, 1 P3).
- La revue s'est arrêtée après confirmation du P1 ; la grille ci-dessous est
  fondée sur la matrice et les preuves inspectées, sans rejeu de tests, builds,
  ADB ou services.

| Sévérité | Constat | Correction / suite | Statut |
|---|---|---|---|
| P1 | `RUNBOOK_R3B.md` documentait `ATLAS_SERVICE_TOKEN_FILE`, absent de l'allowlist et non lu par le connecteur, qui attend `ATLAS_SERVICE_TOKEN` | runbook aligné sur `ATLAS_SERVICE_TOKEN` dans le fichier `relay.env` 0600 chargé par systemd | **CORRIGÉ** |
| P2 | après `DEVICE_REVOKED`, la fermeture 4001 pouvait rapporter `AUTH_REQUIRED` et écraser le message de révocation | `onSocketClosed` préserve `REVOKED` et les requêtes en vol reçoivent `DEVICE_REVOKED` ; test JVM ajouté | **CORRIGÉ** |
| P2 | le config checker acceptait `wss://host/` alors que le connecteur refuse le chemin `/` | checker aligné sur le chemin brut du connecteur : vide ou `/ws` uniquement ; tests 7/7 | **CORRIGÉ** |
| P2 | SHA APK Pixel `66645143…` et runner `1cc96587…` non expliqués | comparaison : tous les `classes*.dex`, `resources.arsc` et `AndroidManifest.xml` sont identiques ; la différence vient des métadonnées de signature/zip | **EXPLIQUÉ** |
| P3 | placeholders `PENDING` dans le rapport et la provenance | rapport et provenance mis à jour | **CORRIGÉ** |

## 2. Grille de la revue initiale

| Gate | Statut initial | Observation |
|---|---|---|
| W1–W8 | PASS | round-trip WSS Pixel, refus TLS/Noise, `ws://` distant, interruption/reconnexion, isolation FastAPI |
| W9 | PARTIAL → corrigé | `DEVICE_REVOKED` avant fermeture ; message UI corrigé depuis |
| W10 | PASS | reprise après redémarrage connecteur et relais sur Pixel |
| C1–C5 | PASS | SIGKILL réel, `FOREIGN_EVENT`, fail-closed, permissions, DST/externe/parallèle/force-stop |
| R1–R2, R4–R13 | PASS | résilience et idempotence du connecteur |
| R3 | PARTIAL | redémarrage réel de FastAPI non rejoué |
| P1–P7 | PASS | appairage atomique, concurrence, token absent de stdout |
| T1 backend | PASS | 187 passés, 1 ignoré |
| T2 JVM | PASS | 94 suites, 668 tests |
| T3 lint | PASS | 0 erreur, 62 avertissements |
| T4–T5 builds | PASS | APK debug et androidTest compilés |
| T6 Pixel | PASS | WSS et C1–C5 ciblés ; suite UI R3-A historique NOT_RUN |
| T7 Node | PASS | 14 fichiers, 62 tests ; 3 advisories de développement |
| T8 relais local | PASS | image épinglée, health loopback |
| T9 installation propre | PASS | copie hors dépôt, offline, sans `references/` |
| T10 systemd | PARTIAL | validation statique seulement |
| T11–T12 Pixel | PASS | WSS et calendrier |
| T13 route backend | PARTIAL | mapping et pytest ; wger réel NOT_RUN |
| T14 revue | FAIL initial | P1 bloquant identifié |
| T15 module relay | PASS | inclus dans le JVM |
| T16 config | PARTIAL → corrigé | divergence trailing slash corrigée |

## 3. Corrections parent et re-revue delta

Cible delta : `f6c11d07c8c6764f60ef93eeb2a82ffe538b19c2` (`f6c11d0`), diff
`8f38f2a..f6c11d0`. Les corrections sont détaillées dans
`preuves/review-fixes.txt` et liées au commit de clôture. La re-revue delta doit
vérifier :

- le runbook utilise `ATLAS_SERVICE_TOKEN` dans l'environnement 0600, sans
  variable inexistante ;
- la fermeture 4001 après révocation ne réintroduit pas `AUTH_REQUIRED` ;
- le checker et `parseRelayUrl` appliquent la même politique de chemin ;
- les APK Pixel/runner ont le même code compilé et la différence de SHA est
  documentée ;
- les placeholders sont résolus et le rapport final reflète les statuts exacts.

Verdict delta : **PENDING** (à compléter après la re-revue).

## 4. Limites de la revue

- Revue documentaire et statique : aucun test, build, ADB ou service n'a été
  exécuté par le relecteur.
- La revue initiale s'est arrêtée au P1 ; la re-revue delta doit confirmer
  l'absence de P0/P1 sur le périmètre livré.
- Les limites produit restent celles du rapport final : pas de relais public,
  LTE/5G, H24, Doze, systemd transitoire, wger réel, R3 FastAPI réel, A3/A4.
