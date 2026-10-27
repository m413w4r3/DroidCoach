# ATLAS V6.3 — R3-A+ — Rapport final

Date de campagne : 2026-10-27.

## Décision

**GO_PREDEPLOYMENT_WITH_LIMITATIONS**, confirmé par la re-revue indépendante
`R3AP-REVIEW-01` : **aucun P0/P1** sur le périmètre livré. La revue initiale
avait rendu **NO_GO** sur un P1 de runbook, corrigé, et trois P2. La re-revue
delta a ensuite relevé deux P2 de qualité de vérification/test et un P3
documentaire ; ils sont corrigés dans `2bcce51` et ce commit documentaire. Ce
statut autorise la préparation de R3-B, pas un déploiement. **V6.3 global reste
`NO_GO`** jusqu’à la recette R3-B.

## Provenance

| Élément | Référence |
|---|---|
| Base de campagne | `9a05dcab71e2327c58c720d82623882fc4d1cfc1` |
| Branche de campagne | `feat/atlas-v6-3-r3a-plus` |
| Code final de clôture | `2bcce51f9c24f3faff7dba64b3e045f377e034e3` (`2bcce51`) — correctifs P2 delta |
| Code revu par la re-revue delta | `f6c11d07c8c6764f60ef93eeb2a82ffe538b19c2` (`f6c11d0`) |
| Code source de l’APK Pixel | `38fd4a09fcb0d151632389bf220644de3529b598` (`38fd4a0`) |
| Branche de gates | `test/r3a-plus-gates` |
| SHA documentaire précédent | `eff4272859234eb8f0edf7f7486ef098983244f9` (`eff4272`) |
| Commit documentaire de clôture | présent commit, enfant direct de `2bcce51` |
| Verdict re-revue delta | **GO_PREDEPLOYMENT_WITH_LIMITATIONS — aucun P0/P1** |

Note de version : l’APK Pixel `66645143…` a été construit depuis `38fd4a0`,
avant le correctif P2 de message de révocation. Le code `f6c11d0` contient ce
correctif (couvert par le test JVM
`revocationCloseDoesNotOverwriteRevokedFailureWithAuthentication`) ainsi que les
corrections P1/P2 du runbook et du config checker. Le code final `2bcce51`
ajoute la parité du checker pour les marqueurs bruts `?`/`#` et rend le test de
révocation déterministe ; ces changements sont limités au script opérationnel
et aux tests JVM, sans modification du runtime connecteur ni du code Android
principal. Les parcours WSS et calendrier ne sont pas affectés ; les preuves
Pixel restent liées à `38fd4a0` / APK `66645143…`, la re-revue delta est liée à
`f6c11d0`, et les gates finales sont rejouées sur `2bcce51`.

Commits de tickets : A1 WSS `b2cbebc`, A2 packaging `d8a425d`, A3 calendrier
`e66d2ef`, B1 résilience `d19a5e3`, B2 appairage `afa4615`, B3 corrections
Android P3 `90739a6`. Les corrections d’intégration et de reprise parent sont
dans `89f881e`, `38fd4a0`, `f6c11d0` (P1/P2 de revue) et `2bcce51` (P2 delta de
la re-revue). La preuve des gates non-device est liée à `2bcce51` ; la
re-revue delta porte sur `f6c11d0`, et les preuves Pixel sont liées à
`38fd4a0` / APK `66645143…`.

## APK et environnement Pixel

La recette matérielle a été menée sur un Pixel 9 Pro XL physique, Android 17 /
API 37. L’APK principal installé et testé a le SHA-256
`66645143b0e6d861b90bbad4acfcc8fe85cae5c939e1d394e3e81c9ecad518b5`.
L’APK de test final installé est
`6ae90e170fbf12af13814c941af5de0c79428cc95ae66d75b62c1e910e42ccfb` ; il
contient les classes WSS et calendrier. Les preuves WSS ont été capturées avec
l’APK de test WSS
`5d8370e9cb46e87c7fa41237bd523524b2f34145f7e093313ce623b3525dea2a`, qui
contient la même classe WSS ; l’APK final ajoute seulement la classe de
nettoyage calendrier.
L’APK principal a été mis à jour et reste installé ; l’APK de test reste
également installé. L’APK produit par le runner de gates est
`1cc96587fb92f436b32bac4e7e4e23703899ac7a5f76a60c36295daa8b0e2d32`.
Provenance des APK : code final `38fd4a0`,
`preuves/pixel-apk-migration.txt`, `pixel-wss-summary.txt`,
`calendar-pixel-summary.txt`, `gates-agent-run.txt` et
`gates-agent-run-delta.txt`.

Les deux builds de `38fd4a0` (Pixel `66645143…`, runner `1cc96587…`) ont tous
leurs `classes*.dex`, `resources.arsc` et `AndroidManifest.xml` byte-identiques :
la différence de SHA APK vient des métadonnées de signature/zip, pas du code.
Le build runner de `f6c11d0` et le build delta de `2bcce51` sont tous deux
`5dd79984fb1c0212d6b20bf955b15d4db3ef33566db2a1702660d0321c3d102d` ; le code de
production est inchangé par le delta checker/tests. L’APK androidTest du delta
est `2d0c2726e8d53b7a3a6e416ea6671aa96091b6fce520696c5bb2d279b787ec8e`. Le
build `f6c11d0` inclut le correctif P2 de message de révocation, donc son dex
diffère du Pixel par ce correctif. L’APK Pixel `66645143…` (source `38fd4a0`)
ne contient pas ce correctif de message, couvert par le test JVM. Détail :
`preuves/pixel-apk-migration.txt` et `preuves/gates-agent-run-delta.txt`.

## Tickets et gates

Chaque résultat ci-dessous renvoie à son SHA de code ou d’intégration, à l’APK
quand un appareil est concerné, et à la preuve correspondante.

| Ticket / gate | Statut et portée | SHA / APK | Preuve |
|---|---|---|---|
| A1 — transport WSS | **PASS** — W1–W10 sur Pixel, dont refus TLS/identité, coupures, redémarrages et révocation | `38fd4a0`, APK `66645143…` | `preuves/pixel-wss-summary.txt`, `pixel-wss-*.txt` |
| A2 — packaging PC | **PASS (packaging)** — copie propre hors dépôt, installation offline et tests PASS ; **PARTIAL (systemd)** — test de service isolé non exécuté | `38fd4a0`; ticket `d8a425d` | `preuves/gates-agent-run.txt`, `packaging-clean-merged.txt`, `packaging-systemd.txt` |
| A3 — calendrier | **PASS** — C1–C5 et nettoyage sur Pixel | `38fd4a0`, APK `66645143…`, test `6ae90e17…` | `preuves/calendar-pixel-summary.txt`, `calendar-pixel-c*.txt`, `calendar-pixel-cleanup.txt` |
| B1 — résilience connecteur | **PARTIAL** — R1, R2, R4–R13 PASS ; R3 ne couvre pas un redémarrage réel de FastAPI | `38fd4a0`; ticket `d19a5e3` | `preuves/resilience-tests.txt`, `stale-client-timeout.txt`, `heartbeat-jvm.txt`, `MATRICE_RECETTE.md` |
| B2 — appairage/révocation | **PASS** — P1–P7, token livré avant autorisation, tests Node finaux | `38fd4a0`; ticket `afa4615` | `preuves/pairing-tests.txt`, `gates-agent-run.txt` |
| B3 — Android P3 | **PASS** — tests JVM et gates de build ; la suite UI historique reste NOT_RUN | `38fd4a0`; ticket `90739a6` | `preuves/android-p3-tests.txt`, `gates-agent-run.txt`, `instrumentation-smoke.txt` |
| Backend | **PASS** — 187 réussis, 1 ignoré, 0 échec | `2bcce51` | `preuves/gates-agent-run-delta.txt` |
| JVM Android | **PASS** — 94 suites, 669 tests, 0 échec/erreur/ignoré | `2bcce51` | `preuves/delta-review-p2-fixes.txt`, `preuves/gates-agent-run-delta.txt` |
| Node connecteur | **PASS** — 14 fichiers, 62 tests, 0 échec, sans variable d’environnement | `2bcce51` | `preuves/delta-review-p2-fixes.txt`, `preuves/gates-agent-run-delta.txt` |
| Lint et builds Android | **PASS** — 0 erreur, 71 avertissements ; APK debug et APK androidTest compilés | `2bcce51`; APK debug `5dd79984…`, androidTest `2d0c2726…` | `preuves/gates-agent-run-delta.txt` |
| Installation propre offline | **PASS** — `npm ci --offline`, build, politique 7/7, artefact vérifié, 62 tests | `2bcce51` | `preuves/gates-agent-run-delta.txt` |
| WSS Pixel W1–W10 | **PASS** — round-trip protégé, refus TLS/Noise, reprise et révocation | `38fd4a0`, APK `66645143…` ; code final `2bcce51` (correctif P2 message + test) | `preuves/pixel-wss-summary.txt`, `pixel-wss-roundtrip.txt`, `pixel-wss-negative.txt`, `pixel-wss-revocation.txt` |
| Calendrier Pixel C1–C5 + nettoyage | **PASS** — scénarios synthétiques, permissions et nettoyage | `38fd4a0`, APK `66645143…`, test `6ae90e17…` ; code final `2bcce51` | `preuves/calendar-pixel-summary.txt` et preuves C1–C5/nettoyage |
| systemd utilisateur | **PARTIAL** — validation statique PASS ; service transitoire isolé NOT_RUN | ticket `d8a425d` | `preuves/packaging-systemd.txt` |
| Lecture réelle wger | **NOT_RUN** — configuration d’accès absente ; seuls tests existants/fakes | baseline `012b101`, gate backend `38fd4a0` | `preuves/backend-baseline.txt`, `MATRICE_RECETTE.md` |
| Persistance Android A3 — identité relayée après redémarrage | **NOT_RUN** — keystore/app non couvert par le transport direct des tests WSS | `38fd4a0`, APK `66645143…` | `MATRICE_RECETTE.md`, `preuves/pixel-wss-summary.txt` |
| Persistance Android A4 — suppression locale des credentials après révocation | **PARTIAL** — révocation PC et état REVOKED testés ; suppression via UI à rejouer | `38fd4a0`, APK `66645143…` | `MATRICE_RECETTE.md`, `preuves/pixel-wss-revocation.txt` |
| Suite UI R3-A historique | **NOT_RUN** — risque d’action sur des données personnelles ; aucune réussite revendiquée | `38fd4a0` | `REGISTRE.md`, `preuves/instrumentation-smoke.txt` |
| Revue indépendante R3AP-REVIEW-01 | **GO_PREDEPLOYMENT_WITH_LIMITATIONS — aucun P0/P1** ; revue initiale NO_GO (1 P1, 3 P2, 1 P3), corrections `f6c11d0`, puis 2 P2 de vérification + 1 P3 documentaire corrigés dans `2bcce51` | revue `f6c11d0` ; correctifs `2bcce51` | `preuves/review-fixes.txt`, `preuves/delta-review-p2-fixes.txt`, `REVUE_INDEPENDANTE_R3A_PLUS.md` |

Gates finales sur `2bcce51` : le runner a validé backend, JVM et lint/builds. Son
relais local a échoué au health check avant la gate Node (`Connection reset by
peer`) ; le parent a rejoué Node, config policy et clean-copy avec un conteneur
neuf : 62/62 tests, 7/7 politique et clean-copy 62/62 PASS. L’incident et la
reprise sont consignés sans masquage dans `preuves/gates-agent-run-delta.txt`.

## Revue indépendante et corrections

- Session `a5dc2140…`, `codex/gpt-6-luna`, effort `xhigh`, plan mode lecture
  seule. Revue initiale ciblée docs `8f38f2a` / code `38fd4a0`, puis re-revue
  delta ciblée code `f6c11d0`.
- Verdict initial : **NO_GO** (1 P1, 3 P2, 1 P3).
- **P1 corrigé** : le runbook utilisait `ATLAS_SERVICE_TOKEN_FILE`, absent de
  l’allowlist et non lu par le connecteur. Il utilise désormais
  `ATLAS_SERVICE_TOKEN` dans le fichier d’environnement 0600 chargé par
  systemd, sans secret dans `ExecStart`.
- **P2 corrigé** : la fermeture 4001 après `DEVICE_REVOKED` ne remplace plus le
  message de révocation par `AUTH_REQUIRED` ; test JVM dédié.
- **P2 corrigé** : le config checker applique la même règle de chemin que le
  connecteur ; `wss://relay.example.org/` est refusé, `/ws` reste accepté.
- **P2 expliqué** : les APK Pixel et runner ont des SHA différents mais des
  `classes*.dex`, `resources.arsc` et `AndroidManifest.xml` identiques ; la
  différence est dans les métadonnées de signature/zip, pas dans le code.
- **P3 corrigé** : placeholders de provenance et de rapport retirés/mis à jour.
- **Re-revue delta : `GO_PREDEPLOYMENT_WITH_LIMITATIONS`, aucun P0/P1.** Elle a
  relevé deux P2 de qualité de vérification et un P3 documentaire :
  - le checker acceptait encore `?`/`#` bruts alors que `parseRelayUrl` les
    refuse ; corrigé dans `2bcce51`, tests 7/7 ;
  - le test de révocation pouvait passer sans prouver la fermeture 4001 après
    `DEVICE_REVOKED` ; remplacé par un test sur session établie avec latches
    REVOKED/AUTH, JVM 669/669 ;
  - les statuts de revue restaient en attente ; enregistrés dans ce commit.
- Détail et commandes : `preuves/review-fixes.txt` et
  `preuves/delta-review-p2-fixes.txt`. Aucune nouvelle revue indépendante n’a
  été exécutée après `2bcce51` : ce delta ne touche que le script de
  vérification et les tests JVM, sans modifier le runtime.

## Conservation des données

Avant campagne, la baseline comptait 12 calendriers et 91 événements
personnels. Aucune donnée personnelle n’a été copiée dans les preuves
versionnées : seuls des compteurs, des hashes et des identifiants synthétiques
sont utilisés, et aucun calendrier/événement personnel n’a été modifié. Après
nettoyage, les 12 calendriers et 91 événements sont inchangés, il reste 0
compte et 0 calendrier ATLAS, et les permissions `READ_CALENDAR` /
`WRITE_CALENDAR` sont revenues à refusé. Les quatre hashes de données ATLAS
(plan, état local, configuration de synchronisation, boîte de réception) sont
identiques avant et après la mise à jour et les scénarios. La sauvegarde
app-only est conservée hors dépôt et son extraction de contrôle a réussi.
Références : `preuves/preflight-pixel.txt`, `pixel-apk-migration.txt`,
`calendar-pixel-summary.txt`, `calendar-pixel-cleanup.txt`,
`cleanup-final-r3a-plus.txt` (code `38fd4a0`, APK Pixel `66645143…`, test
calendrier `6ae90e17…`).

## Corrections d’intégration

- Le vérificateur de configuration PC accepte maintenant `wss://` distant et
  réserve `ws://` au loopback avec opt-in ; `README.md` et
  `INSTALLATION_PC.md` décrivent la même politique. Vérification intégrée
  `89f881e`, 7/7 tests : `preuves/config-policy-integration.txt` et
  `gates-agent-run.txt`.
- Un timeout de handshake ferme les connexions relais périmées après
  redémarrage du connecteur ; le heartbeat Android détecte l’absence de pong
  et reconnecte. Vérifiés sur le code final `38fd4a0`, APK `66645143…` :
  `preuves/stale-client-timeout.txt`, `heartbeat-jvm.txt`,
  `pixel-wss-connector-restart.txt`.
- À la révocation, le connecteur transmet `DEVICE_REVOKED` avant la fermeture
  WebSocket `4001`. Vérifié sur `38fd4a0`, APK `66645143…` :
  `preuves/pixel-wss-revocation.txt`.

## Limites et risques restants

- Pas de relais public, Internet, LTE/5G, fonctionnement H24, veille/Doze ou
  livraison instantanée qualifiés. Le Pixel a utilisé `adb reverse` et un
  terminateur TLS de test local ; cela ne vaut pas preuve réseau public.
- La CA de recette est limitée au debug et ne doit pas être approuvée en
  release. Le service systemd n’a pas été lancé dans un gestionnaire isolé.
- `npm ci` signale trois advisories de dépendances de développement : une
  modérée et deux critiques. L’audit runtime était à zéro
  (`preuves/packaging-clean-install.txt`) ; les dépendances de développement
  n’ont pas été modernisées dans cette campagne.
- Le lint final sur `2bcce51` compte 0 erreur et 71 avertissements (62 sur
  `f6c11d0`) ; aucun avertissement n’est bloquant.
- Les APK de builds indépendants ne sont pas identiques octet pour octet ; voir
  les SHA et limites de reproductibilité ci-dessus.
- L’APK principal est mis à jour sur le Pixel et l’APK de test reste installé.
  Aucune désinstallation ni remise à zéro n’a été faite.
- R3 reste partiel pour le redémarrage réel de FastAPI ; lecture wger réelle
  non exécutée ; persistance Android A3 non exécutée ; A4 partielle ; suite UI
  historique NOT_RUN. La revue indépendante initiale a rendu NO_GO sur un P1
  de runbook, corrigé avec les P2 associés. La re-revue delta a rendu
  **GO_PREDEPLOYMENT_WITH_LIMITATIONS sans P0/P1** ; ses deux P2 de vérification
  et son P3 documentaire sont corrigés dans `2bcce51`/ce commit, sans nouvelle
  revue indépendante après ce delta limité aux scripts/tests.
- L’APK Pixel `66645143…` a été construit depuis `38fd4a0`. Le correctif P2 de
  message de révocation de `f6c11d0` et les améliorations de test de `2bcce51`
  sont couverts par les tests JVM mais n’ont pas été reconstruits dans l’APK
  Pixel ; les parcours WSS et calendrier ne sont pas affectés par ces
  corrections de message et de test.

## Préparation R3-B

Suivre [`RUNBOOK_R3B.md`](RUNBOOK_R3B.md), après le verdict delta favorable,
les corrections de clôture et les autorisations ciblées. Il faut notamment faire
approuver l’hébergement, le domaine et le certificat publics ; vérifier TLS et
l’absence de port relais en clair exposé ; confirmer les SHA du relais, du
connecteur et de l’APK ; disposer d’une sauvegarde app-only récente et
restaurable ; installer et vérifier l’APK autorisé ; tester sur Pixel API 37 via
LTE/5G sans `adb reverse` ni proxy USB ; couvrir Doze/veille, coupures, reprise,
révocation et suppression locale des credentials ; vérifier les sauvegardes et
le retour arrière. Chaque scénario R3-B doit garder son statut propre et sa
preuve liée aux SHA. Cette préparation ne change pas le verdict : **V6.3 global
reste `NO_GO` avant la clôture de R3-B**.

Le rapport et les preuves versionnées ne contiennent aucun secret, token, clé
privée, nom de compte personnel ni donnée intime.
