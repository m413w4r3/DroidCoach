# V7-11-A5 — Preuve CI, provenance et scan secrets/logs

- Date : 2026-11-08
- TASK_ID : V7-11-A5
- Modèle / effort : Codex gpt-6-luna / xhigh, vérifiés par le parent
- Branche : audit/v7-11-a5-ci-provenance
- HEAD audité : 8dac699cdb37e2f858a76e4cf7f9fc3bcd613e9c
- BASE_SHA du ticket : a5aca4b54088d43f4829adf31049417dd8cf32a6, sortie finale V7-09
- Écriture autorisée : ce fichier uniquement
- Push, PR, merge, ADB, Pixel, Relay 4402 et mutation de service live : non effectués

## Provenance Git et CI distante

Le pré-vol a confirmé le répertoire demandé, la branche dédiée, HEAD 8dac699cdb37e2f858a76e4cf7f9fc3bcd613e9c et un arbre propre. git ls-remote origin refs/heads/main et gh api repos/m413w4r3/DroidCoach/branches/main renvoient tous deux le SHA audité. Les références locales main et origin/main pointent également sur ce SHA.

Le rapport final V7-09 identifie a5aca4b54088d43f4829adf31049417dd8cf32a6 comme résultat de cette campagne. Depuis ce commit, HEAD contient **40 commits et 107 chemins modifiés**. Le BASE_SHA d’entrée V7-09 était efcfdcd0936ab2791f8ea14c151247257b05d19b ; compter depuis cette base d’entrée inclut les 8 commits et 10 chemins de clôture V7-09 et donne 48 commits / 117 chemins.

État des objets :

- Sous-modules suivis : 0.
- Règles Git LFS dans .gitattributes : 0 ; pointeurs LFS dans l’arbre courant : 0. Le binaire git-lfs n’est pas installé.
- L’arbre était propre avant les tests. Les sorties locales ignorées des outils n’ont ajouté aucun changement suivi.

Le seul run trouvé par gh run list --commit 8dac699cdb37e2f858a76e4cf7f9fc3bcd613e9c est le run **42467740775**, push du SHA audité, créé le 2026-11-08 à 09:34:13 UTC :

- Job relay-node (128691060568) : **success**. Installation connecteur, 131 tests unitaires et tests proxy CI terminés avec succès.
- Job verify (128691060615) : **failure**. JVM, lint, build debug et assemblage androidTest ont réussi ; l’installation des dépendances backend a réussi ; l’étape Run backend tests a échoué.
- Aucun autre run ni job n’a été trouvé pour ce SHA. Run : https://github.com/m413w4r3/DroidCoach/actions/runs/42467740775

Les deux méthodes CI en échec sont :

1. scripts/v7-06c/tests/test_harness.py::CoreOracleTests::test_apply_and_reject_core_oracles_pass
2. scripts/v7-06c/tests/test_harness.py::CoreOracleTests::test_core_oracle_rejects_required_falsifications

L’exception SQLite est : table agent_proposals_v4 has 11 columns but 10 values were supplied. Le harnais insère 10 valeurs dans la table à 11 colonnes.

## Reproductions locales

Environnement observé : Python 3.12.3, JDK 21.0.12.1, Node 24.18.0, npm 11.16.0. ANDROID_HOME est configuré et son chemin existe ; sdkmanager n’est pas installé. Le wrapper Gradle et la commande adb existent, mais aucun ne fut lancé ou utilisé pour interroger un appareil. gh est disponible ; gitleaks ne l’est pas.

| Commande | Statut | Résultat observé sur le HEAD audité |
|---|---|---|
| python -m pytest -q depuis la racine | **FAIL** | 496 passed, 1 skipped, 9 sous-cas échoués, 39 sous-cas réussis, 4 warnings. Les 9 échecs sont répartis dans les deux méthodes CI ci-dessus : apply/reject dans la première ; wrong_plan_version, missing_v3, second_ack, modified_v2, wrong_v3_revision, future_v3_observed_at et consent_not_revoked dans la seconde. Ils ont le même décalage SQLite 11/10. Une relance diagnostique avec --tb=no -r f n’a exposé que les identifiants des sous-cas. |
| cd backend && python -m pytest -q | **PASS** | 451 passed, 1 skipped, 3 warnings, 27,26 s. |
| cd integrations/relay/pc && npm ci --offline | **PASS** | Installation hors ligne réussie ; aucun fallback réseau requis. |
| cd integrations/relay/pc && npm run test:unit | **PASS** | TypeScript compilé ; 18 fichiers, 131/131 tests passés. |
| node --test --test-skip-pattern="pinned relay integration on 4402" integrations/relay/proxy/test/proxy.test.mjs | **PASS** | 18 tests, 18 passés, 0 échec. Le cas épinglé 4402 a été exclu par le filtre et n’a pas été exécuté ; aucun accès au Relay live. |
| cd android && ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest | **DEFERRED / NOT_RUN** | Vague 1 : commande exacte à relancer sur le SHA intégré après notification du parent. Aucun Gradle, AVD ou Pixel lancé. L’étape Android distante de la CI au SHA audité a réussi. |
| Test Android physique / AVD | **NOT_RUN** | Hors de cette passe ; aucun appareil n’a été interrogé. |

## Scan secrets, chemins et identifiants

gitleaks n’étant pas disponible, un scan ciblé Python a examiné le contenu courant des 107 chemins modifiés, le patch git diff, les patches de git log -p sur les 40 commits, ainsi que les 86 fichiers de preuve versionnés sous docs/operations/evidence/. Les motifs couvraient clés privées, bearer/provider tokens, JWT, affectations de secrets, chemins personnels, adresses, téléphones, MAC et identifiants d’appareil. Les résultats ont été revus sans recopier de valeur sensible.

Résultats et faux positifs :

- Aucune clé privée, aucun token fournisseur, JWT, adresse email réelle, numéro de téléphone ou adresse MAC détecté.
- Les 21 détections de forme Bearer et 12 détections d’affectation de secret se trouvent dans des tests ; ce sont des valeurs de fixture et des objets de test, pas des credentials de service.
- Les 5 correspondances du motif email sont des annotations Kotlin de capacités CAP_*, pas des adresses.
- Les autres correspondances de forme deviceId dans le code sont des références de variables ou des fixtures de test.
- Un seul fichier suivi a une extension .pem : android/app/src/debug/res/raw/atlas_test_ca.pem. C’est le certificat public d’une CA de test debug ; il ne contient pas de clé privée.
- Aucun fichier .env exact n’est suivi ou présent parmi les fichiers non suivis. Aucun fichier de credential privé n’est suivi.
- Les 86 fichiers de preuve ont le mode Git 100644 ; aucun n’est exécutable ou inscriptible par le groupe/les autres.

Findings P2, valeurs expurgées :

| Chemin | Finding |
|---|---|
| docs/operations/evidence/V7-01B_READONLY_AMBIGUITIES_AUDIT.md:23 | Chemin absolu local vers une base SQLite Gateway. Le composant de répertoire personnel est omis ici. |
| docs/operations/evidence/V7-06A_TEST_RED.md:28 | Chemin local Android SDK dans ANDROID_HOME, composant personnel omis. |
| docs/operations/evidence/V7_INTEGRATION_ANDROID_RELAY.md:17 | Chemin absolu local de checkout/service Relay, composant personnel omis. |
| docs/operations/evidence/V7_LIVE_RELAY_C.md:23 | Identifiant device_id UUID non nul dans une preuve Relay. Valeur omise. |
| docs/operations/evidence/V7-10_A5_R2_EMULATOR.log:81 | Valeur brute androidboot.serialno générée par un AVD (17 caractères), pas un serial Pixel. Valeur omise. |

git log -p contient aussi des chemins locaux dans des versions antérieures, ensuite expurgés de l’arbre courant. Exemples historiques : docs/operations/V7-10_TEST_REPORT.md:48-49 et docs/operations/evidence/V7-10_A5_R3_FINAL_ANDROID_FULL.log:1 (patch 27851ef), ainsi que docs/operations/evidence/V7-10_A1_TASK_REGISTRY.md:5 (patch cd14329). Les valeurs restent présentes dans l’historique des commits. Les autres occurrences sont des répétitions des mêmes logs dans les patches ultérieurs.

## Limites et reprise

- La CI distante couvre ce SHA et est globalement rouge à cause des deux méthodes backend identifiées ; aucun rerun ou correctif n’a été poussé.
- Le test racine rapporte neuf sous-cas rouges, regroupés dans les deux méthodes rouges de la CI.
- JVM, lint, build et assemblage Android locaux restent différés ; aucun résultat local Android n’est revendiqué.
- Le scan ciblé ne remplace pas un scanner dédié. Aucun secret réel n’a été trouvé ; les chemins personnels et identifiants P2 ci-dessus restent des findings à corriger ou à accepter explicitement.
- Vague 2 : après notification du parent, relancer la commande Android exacte sur le SHA intégré et compléter la preuve de provenance.

## Suivi F1 — validation du SHA intégré et sanitisation

- Date : 2026-11-08
- Worktree de validation : worktree temporaire détaché sous `/tmp`, sur `04fdc77f38a8d215644cecfa4d5707030fbfa2ee` (branche intégrée `feat/atlas-v7-11-migration-readiness`). Le worktree d’audit est resté sur `audit/v7-11-a5-ci-provenance`; les builds n’ont pas modifié cette branche.
- SHA de l’audit initial : `8dac699cdb37e2f858a76e4cf7f9fc3bcd613e9c`; base V7-09 : `a5aca4b54088d43f4829adf31049417dd8cf32a6` (40 commits / 107 chemins).
- Aucune suite distante GitHub n’a été interrogée pour `04fdc77`; ces résultats sont des reproductions locales.

### Suites locales sur `04fdc77`

| Commande | Statut | Résultat |
|---|---|---|
| `python3 -m pytest -q` (racine) | **PASS** | 496 passés, 1 ignoré, 48 sous-cas passés, 4 warnings. |
| `cd backend && python3 -m pytest -q` | **PASS** | 451 passés, 1 ignoré, 3 warnings. |
| `cd integrations/relay/pc && npm ci --offline && npm run test:unit` | **PASS** | Installation hors ligne réussie ; 18 fichiers, 131/131 tests passés. |
| `node --test --test-skip-pattern='pinned relay integration on 4402' integrations/relay/proxy/test/proxy.test.mjs` | **PASS** | 18/18 tests passés ; le cas épinglé sur 4402 a été exclu et aucun Relay live n’a été contacté. |

### Android local sur `04fdc77`

Commande exécutée depuis `android/` :

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --offline --max-workers=2 --console=plain
```

Statut global : **PASS**, `BUILD SUCCESSFUL`, 84 tâches exécutées. Le rapport JUnit contient 934 tests, 0 échec, 0 erreur et 0 test ignoré. `lintDebug` a produit 0 erreur et 80 avertissements. Aucun émulateur ni appareil n’a été lancé.

| Artefact | Taille | SHA-256 |
|---|---:|---|
| `app-debug.apk` | 15 838 975 octets | `27baca26312fa6981b8e11fc846eb32b08f41c7a631df36a69df1c986239d13e` |
| `app-debug-androidTest.apk` | 1 578 552 octets | `9db0a3ff434d0d9ac87780a3dcaf5bb89c19bb37b656f7b398ebb6ed332e1bff` |

### Image Core locale sur `04fdc77`

Commande exécutée : `docker build --build-context contracts=/tmp/atlas-v7-11-a5-f1-04fdc77/contracts -t atlas-core-v7-11-candidate /tmp/atlas-v7-11-a5-f1-04fdc77/backend`.

- Statut : **PASS** ; image locale `sha256:2c5ae216e1ed292cd11cbc87ec392b9e546187a52727156754afb440c2f1cd57`, taille 150 498 024 octets. Aucun digest de dépôt n’est attaché (`RepoDigests=[]`).
- `ENTRYPOINT`: `python /usr/local/bin/atlas-core-entrypoint.py` ; `CMD`: `uvicorn atlas_backend.main:app --host 127.0.0.1 --port 8100`.
- Vérification par conteneur éphémère `--rm` : `backend/atlas_backend/v4_auth.py` et `backend/atlas_backend/v4_assertion.py` sont présents. Le processus de vérification s’est terminé ; aucun service Core n’a été démarré ni laissé actif.

### Sanitisation et scan postérieur

Les cinq fichiers demandés ont été corrigés dans l’arbre courant, sans altérer le sens des preuves :

| Chemin:ligne | Statut | Marqueur appliqué |
|---|---|---|
| `docs/operations/evidence/V7-01B_READONLY_AMBIGUITIES_AUDIT.md:23` | **CORRIGÉ — P2** | ``<HOME>/<REPO>/<GATEWAY_DB_PATH>`` |
| `docs/operations/evidence/V7-06A_TEST_RED.md:6,28` | **CORRIGÉ — P2** | ``<SESSION_ID>`` et `<ANDROID_SDK>` |
| `docs/operations/evidence/V7_INTEGRATION_ANDROID_RELAY.md:17,70` | **CORRIGÉ — P2** | ``<HOME>/<REPO>`` et ``<PLAN_ID>`` |
| `docs/operations/evidence/V7_LIVE_RELAY_C.md:23` | **CORRIGÉ — P2** | `<RELAY_DEVICE_ID>` |
| `docs/operations/evidence/V7-10_A5_R2_EMULATOR.log:81` | **CORRIGÉ — P2** | `<AVD_SERIAL>` |

Scan après sanitisation : 107 chemins de la campagne V7-09→HEAD et 87 preuves versionnées, soit 149 fichiers distincts examinés dans leur contenu courant. Le scanner dédié `gitleaks` n’est pas installé ; les motifs ciblés et la revue manuelle ont été utilisés. Aucun chemin absolu personnel, serial AVD brut, `device_id` brut, clé privée, token de service, credential réel, adresse MAC ou donnée personnelle réelle n’a été trouvé dans l’arbre courant. Les correspondances de forme restantes sont des valeurs de tests/fixtures, du code de validation et des noms de capacités Kotlin ; aucune n’a été évaluée comme secret réel. Aucun `.env` ni chemin nommé credentials/secrets n’est suivi ; les 87 preuves ont le mode Git `100644`.

**Résidu historique Git :** les valeurs remplacées restent accessibles dans les commits précédents. Le commit de sanitisation conserve aussi leur forme supprimée dans son patch Git. Aucun historique n’a été réécrit et aucun push n’a eu lieu ; le scan courant ne doit donc pas être interprété comme une purge des anciens objets Git.

### Statut et limites F1

- Android JVM, lint, APK debug et APK androidTest : **PASS** sur `04fdc77`; test sur émulateur/appareil : **NOT_RUN**.
- Image Core construite et fichiers v4 vérifiés : **PASS** ; conteneur de service : **NOT_RUN**.
- Suites racine, backend, connecteur et proxy : **PASS** sur `04fdc77`.
- Sanitisation du contenu courant et scan ciblé : **PASS** ; historiques Git : **résidu P2 documenté**.
- GitHub Actions au SHA intégré, Relay live 4402, Core 8100, Gateway 18789, Funnel, ADB et Pixel : **NOT_RUN**, conformément au périmètre.
- Cette validation porte sur le SHA intégré `04fdc77` fourni pour F1. Elle ne remplace pas le rerun final demandé par le parent après intégration du correctif A1-F2.
