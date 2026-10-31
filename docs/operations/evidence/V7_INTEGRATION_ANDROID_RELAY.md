# V7 — intégration Android relayée et connecteur V4

Date : 2026-10-31  
Périmètre : V7-02B, V7-03B/C/D, V7-04 (seed de test). Les preuves Pixel d’exécution
sont dans le rapport E2E séparé ; ce document ne les anticipe pas.

## V7-02B — clés V4 du chargeur `relay.env`

- Commit auteur : `e86cb70b875655941abe4c680b5111b8af20d977`, base `09fff5b`.
- Revue indépendante : `ACCEPT_WITH_FINDINGS`; aucun P0/P1. P2 : la documentation
  omet le chemin de `--env-file`, et la couverture des refus de fichier token peut être
  approfondie. Aucune fuite observée.
- Merge d’intégration : `efe652f`.
- Tests rejoués par le parent dans le worktree : `npm run build` PASS,
  `npm run test:unit` **96 tests / 16 fichiers, 0 échec**.
- Redémarrage du connecteur par le chemin documenté
  `scripts/start.sh /home/nill/.config/atlas/relay.env` :
  - `check-config` : PASS (URL loopback explicite, état `0700`, identité/devices `0600`,
    chemin token V4 configuré, bearer service présent sans affichage) ;
  - `check-secret` : PASS (`environment-file`, mode `0600`, fichier bearer Android privé) ;
  - ancien connecteur `1614858` arrêté ; nouveau wrapper `1731948`, enfant `1731971` ;
  - métriques loopback : `relay_connected=true`, `requests_total=0` au démarrage.
- État relais après redémarrage : `/health` public **200**, `/ready` public **200**,
  `r3b-public-e2e.mjs` **PASS** (Noise/auth/health/sync/chat/rejeu/conflit/payload).
- État de rollback : inventaire privé
  `~/.local/state/atlas-relay/backups/v7-02b-pre-restart-20261031T000156Z` (`0700`,
  fichiers `0600`, hashes conservés) ; identités, devices, opérations et tokens conservés.

## V7-03B/C/D — transport Android V4 et corrélation

- V7-03B : `76f2927`, rejeté en revue pour `request_id` enveloppe ≠ payload.
- V7-03C : `3e050db` (base `76f2927`) aligne les IDs pour snapshots, pull et chat ;
  ACK utilise `ack_id`; le codec V2 borne `http_status`/`remote_code`.
- Revue V7-03C : P0/P1 aucun ; P2 classification chat ignorant `http_status`.
- V7-03D : `66fad22` corrige ce P2 ; test ciblé 17 PASS, suite JVM auteur 883 PASS.
- Re-revue V7-03D : **ACCEPT**, aucun P0/P1/P2.
- Merge d’intégration : `c6d93f574b29ded6282a54eb6187b66df4242375`.
- Parent, sur le SHA fusionné :
  - suite JVM complète `:app:testDebugUnitTest --rerun-tasks` : **883 tests, 0 échec,
    0 erreur, 0 ignoré** ;
  - `:app:lintDebug` : BUILD SUCCESSFUL ;
  - `:app:assembleDebug` : BUILD SUCCESSFUL ;
  - APK `app-debug.apk` : **16 902 465 octets**, SHA-256
    `0d0ce524f1166690f5399ea7197ab4eb3b39bfa93604d3feb98613d75bc57ca4`,
    signataire debug `da3014662eb65e50a87ef364a2e583c331398795b222e3b37b1a16553a00eeee`,
    package `dev.atlas.coach` versionCode 1 / versionName 0.1.0.
- Backend non modifié par ce lot ; suite rejouée avec le venv du dépôt :
  **346 passed, 1 skipped** (3 avertissements non bloquants).

## Pixel — installation de l’APK final

- Appareil : Pixel 9 Pro XL (USB, `device`), API **37**, ABI `arm64-v8a` ; serial tronqué.
- APK source : SHA-256 `0d0ce524f1166690f5399ea7197ab4eb3b39bfa93604d3feb98613d75bc57ca4`,
  signataire debug `da301466…eeee`, 16 902 465 octets.
- `adb install -r` : **Success**, code 0, sans désinstallation ni `pm clear`.
- Après installation : `base.apk` unique, versionCode 1 / versionName 0.1.0 ; copie
  `post-install-base.apk` `0600` de 16 902 465 octets, hash et certificat identiques à la
  source ; backup préalable conservé (`388f2250…`).
- Limites : aucune ouverture d’application, aucun consentement saisi et aucune preuve de
  chat à ce stade. Les clés Keystore ne sont pas dans le backup app-only.

## V7-04 — seed d’un programme synthétique (test-only)

- Commit : `2bda1e2195c2e69d00e522fdc2071668cf7801d9`, parent `c6d93f5`.
- Fichier unique `android/app/src/androidTest/.../V7PlanSeedInstrumentedTest.kt`.
- Vérifie la date locale, l’état APPROVED, la version et l’idempotence du second passage.
- Compilation AndroidTest PASS ; APK test SHA-256
  `efd39d6fddcdcfcc60dfe3d5d3ad7cdd4d8bcac8328f3c961e7bcfaa298aee9e`.
- Exécution Pixel : **PASS**, `OK (1 test)`, code processus 0.
  Plan synthétique `b0902f1b-8404-4ed7-a614-96aeb93a6bb1`, version 2, 1 activité.
  APK test final SHA-256
  `94f24d4917e6fe01a91e60f43cbc4b1b10019e193df1cb6191ec345051b53250`.
  Le seed n’est pas une preuve produit.

## Limites

- Aucun secret, token ou donnée personnelle n’est reproduit.
- Le chat public V1 (`r3b-public-e2e`) ne prouve pas le chat V4 Android ni le fil rouge.
- A4-4 (4001→1012) reste non qualifié ; aucune revendication sur ce point.
- La classification d’erreur HTTP a été revue statiquement et testée en JVM ; l’exécution
  Pixel des erreurs n’est pas revendiquée.
