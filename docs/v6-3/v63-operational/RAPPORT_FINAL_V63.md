# ATLAS V6.3 — Rapport final de consolidation

Date : **2026-10-30**
SHA du code intégré : `d7a30c1697858e0c10d8a8496997dc4c0a321bcf`
Branche du code : `v63/integration-core-v4`
Base wave9 : `92ce000745f8188153fff4ab60933da0287fdc89`

## Verdict

**GO local du code** au SHA `d7a30c1697858e0c10d8a8496997dc4c0a321bcf` : les suites locales backend, relais PC et Android passent, les tâches lint/build indiquées passent et la revue indépendante est `ACCEPTED — GO`, sans P0/P1. La revue était statique ; le relecteur n'a pas relancé les tests.

**NO-GO pour la clôture runtime complète V6.3.** La qualification Pixel de l'APK final est BLOCKED, la découverte et l'appel des six outils OpenClaw réels ne sont pas prouvés, et A4-4 reste BLOCKED par l'écart amont 4001→1012. Aucun oracle fonctionnel runtime R01-R12 n'est PASS. Le SHA final n’a aucune qualification Pixel.

## Périmètre livré et provenance

- Intégration finale locale à partir de la base wave9 `92ce000745f8188153fff4ab60933da0287fdc89`.
- Merge `6d79755` pour sync V5, depuis `e77064856bdfccac6ee18afb81ac1df38ecd3e50`.
- Merge `82e6af2` pour A1 ACK, depuis `f1e424f46548090110a4102048550d539bd54c10`.
- Merge `d7a30c1` du harnais E2E, depuis `c83f87f78f79b8d74427266df9772c7bd36b4bfd`. Les commits `a27b3a7`, `3aad1d3`, `ced76d4` et `c83f87f` sont test-only.
- Le harnais ciblé passe 3/3 et la suite JVM 863/863 sur `c83f87f` ; ces tests sont inclus dans les 865/865 au SHA final. L'E2E backend est inclus dans les 315 tests backend passés. Cela valide des tests locaux et ne prouve pas un fil rouge réseau/Gateway/ACK runtime.
- Le plugin `atlas_core_tools` 0.1.0 est installé dans l'environnement OpenClaw mais désactivé (`enabled=false`). Le Gateway n'a pas été redémarré.
- Les intégrations Etar, wger et localisation ne sont pas livrées ni déclarées PASS.

## Sessions finales

| Rôle | Tâche / session | Modèle et effort effectifs |
|---|---|---|
| Android JVM et builds | `V63-FINAL-ANDROID-TEST` — `d9a1ad03-b7ba-43f2-a20b-ac7e66aa6ea6` | `codex/gpt-6-luna / xhigh` |
| Backend | `V63-FINAL-BACKEND-TEST` — `ff5f4f41-e72a-4a32-b708-3bebed1b9d3c` | `codex/gpt-6-luna / xhigh` |
| Relais PC | `V63-FINAL-RELAY-TEST` — `7114dedd-7bc8-49ea-9d77-a544e65baf37` | `codex/gpt-6-luna / xhigh` |
| Revue finale du code | `V63-REVIEW-FINAL-CODE` — `8c374793-f73b-4e94-a409-67e3a9d7aa2f` | `codex/gpt-6-luna / xhigh` |
| Harnais E2E | `V63-E2E-HARNESS` — `fed39621-d635-41bd-bd83-006f47a4c159` | `codex/gpt-6-luna / xhigh` |

## Tableau de preuves locales

| Domaine | Résultat au SHA final | Environnement et commande | Portée / limite |
|---|---|---|---|
| Backend | 316 collectés, 315 passed, 0 failed, 1 skipped préexistant ; 21,91 s | Depuis `backend/`, Python 3.12.3, pytest 8.4.0, environnement isolé ; `/tmp/atlas-test-venv/bin/python -m pytest -ra` (lanceur avec `/usr/bin/time`) | Session `V63-FINAL-BACKEND-TEST`, `ff5f4f41-e72a-4a32-b708-3bebed1b9d3c`, `codex/gpt-6-luna / xhigh`. Skip : `test_real_gateway_plan_returns_valid_proposals`, qui requiert `ATLAS_REAL_GATEWAY=1`. Aucun accès Gateway réel. |
| Relais PC | Typecheck PASS ; build PASS ; 17 fichiers, 89 passed / 0 failed / 0 skipped, 5,89 s total | Depuis `integrations/relay/pc`: `./node_modules/.bin/tsc -p tsconfig.json --noEmit`, `npm run build`, `npm test` ; relais local `127.0.0.1:4400` | Session `V63-FINAL-RELAY-TEST`, `7114dedd-7bc8-49ea-9d77-a544e65baf37`, `codex/gpt-6-luna / xhigh`. Vérification locale seulement. Le flake historique `session.test.ts` n'a pas été reproduit dans le run final 89/89. |
| Android JVM | 865 tests ; 0 échec, 0 erreur, 0 ignoré ; 114 suites XML ; 26 s | Depuis `android/`, `GRADLE_USER_HOME=/tmp/atlas-gradle-home ./gradlew :app:testDebugUnitTest` | Session `V63-FINAL-ANDROID-TEST`, `d9a1ad03-b7ba-43f2-a20b-ac7e66aa6ea6`, `codex/gpt-6-luna / xhigh`. Aucun test instrumenté inclus. |
| `assembleDebugAndroidTest` | PASS, 19 s | Depuis `android/`, `GRADLE_USER_HOME=/tmp/atlas-gradle-home ./gradlew :app:assembleDebugAndroidTest` | Session Android `d9a1ad03-b7ba-43f2-a20b-ac7e66aa6ea6`, `codex/gpt-6-luna / xhigh`. Aucun test instrumenté n'a été exécuté. Avertissement de dépréciation `MODE_BACKGROUND_ACTIVITY_START_ALLOWED`. |
| `lintDebug` | PASS, 0 erreur / 87 avertissements | Depuis `android/`, `GRADLE_USER_HOME=/tmp/atlas-gradle-home ./gradlew :app:lintDebug` | Session Android `d9a1ad03-b7ba-43f2-a20b-ac7e66aa6ea6`, `codex/gpt-6-luna / xhigh`. UseKtx 42, ApplySharedPref 12, ObsoleteSdkInt 14, GradleDependency 7, SetTextI18n 6, DiscouragedApi 2, NewerVersionAvailable 2, AndroidGradlePluginVersion 1, OldTargetApi 1. |
| `assembleDebug` | PASS, 140 s | Depuis `android/`, `GRADLE_USER_HOME=/tmp/atlas-gradle-home ./gradlew :app:assembleDebug` | Session Android `d9a1ad03-b7ba-43f2-a20b-ac7e66aa6ea6`, `codex/gpt-6-luna / xhigh`. Produit l'APK hashé décrit ci-dessous. |
| Revue indépendante | `ACCEPTED — GO`, aucun P0/P1 | Revue statique sur `d7a30c1` | Session `V63-REVIEW-FINAL-CODE`, `8c374793-f73b-4e94-a409-67e3a9d7aa2f`, `codex/gpt-6-luna / xhigh`. Le relecteur n'a pas relancé les tests. |

Le harnais E2E (ciblé 3/3 et JVM 863/863 sur `c83f87f`) est rattaché à la session `V63-E2E-HARNESS`, `fed39621-d635-41bd-bd83-006f47a4c159`, `codex/gpt-6-luna / xhigh`.

## APK final conservé

- Nom : `app-debug.apk`; `versionName=0.1.0`, `versionCode=1`.
- Taille : **15 740 291 octets**.
- SHA-256 : `08f45c3743df80b57f653ba41472514e8d006fc3068aca47be041f96a20975c9`.
- Copie durable : espace d’artefacts QA privé hors dépôt, sous-dossier `V63-FINAL-d7a30c1/app-debug.apk`.
- Permissions : dossier `0700`, fichier `0600` ; `cmp` a confirmé une copie identique.

La conservation de cet artefact ne signifie pas qu'il a été installé ou qualifié sur Pixel.

## Sécurité, consentement et autorité locale

- Android reste l'autorité du plan local et des effets appliqués ; un état projeté par le Core n'est pas une preuve de l'état courant du téléphone.
- Les propositions IA ne suffisent pas à autoriser une mutation. A0 reste le défaut ; A1 dépend d'un consentement distinct, révocable et vérifié par des règles déterministes. Refus ou révocation ne doivent pas produire d'effet.
- Les catégories protégées ou non classées restent verrouillées. Les limites locales A1 décrites par D63-36 — quotas, versions, périmètre et annulation — restent bornées ; cette preuve locale ne remplace pas une qualification runtime.
- Le plugin OpenClaw désactivé ne donne lieu à aucune affirmation d'outil effectivement découvert ou appelé. Aucun droit shell ou contrôle Android par OpenClaw n'est revendiqué.
- Aucune donnée personnelle, secret, token ou contenu de sauvegarde n'est reproduit dans ce rapport. Une sauvegarde privée antérieure au run Pixel existe et est référencée dans le journal QA privé ; aucun contenu, chemin personnel ni empreinte de sauvegarde n'est reproduit ici.

## Blocages runtime

### Pixel Android 17 / API 37

La qualification de l'APK final sur le Pixel est **BLOCKED**, car le téléphone n'était pas disponible. Une qualification partielle antérieure concernait un autre état logiciel : source `6f9d2010b1c7b88d8447462f7eadac3d666186b9` et APK SHA-256 `688e2da357d4e6d1dc163675514ee1df28378acadab02e505f5310d1b146cff1`, sur Pixel 9 Pro XL Android 17/API 37. Pour cette ancienne APK seulement : `install -r` PASS, smoke Aujourd'hui/Plan/retour, HOME inchangé ; calendriers, notifications et localisation refusés. Cela ne qualifie ni l'APK final ni le SHA `d7a30c1`.

### OpenClaw

`atlas_core_tools` 0.1.0 est installé mais désactivé ; aucun redémarrage du Gateway n'a eu lieu. Le runtime des six outils est **NON PROUVÉ** ; `GO_OPENCLAW_TOOLS` est BLOCKED, ainsi que l'audit runtime indépendant. Ne pas affirmer que les six outils fonctionnent via OpenClaw réel. Neuf vulnérabilités npm ont été signalées : 3 modérées, 4 élevées et 2 critiques.

### Relais A4-4

A4-4 reste **BLOCKED**. L'image amont transforme le protocole 4001 en 1012 ; le proxy local préserve 4001. La preuve locale du proxy ne corrige ni ne valide l'image amont.

## P2 et limites acceptées

### Expiration inbox et reçu durable

P2 non bloquant accepté dans D63-39 : après le commit local A1 et l'écriture durable du reçu, si l'ACK APPLIED échoue puis le repli UNCERTAIN échoue aussi, l'entrée reste RECEIVED. Le worker peut ensuite la clore EXPIRED sans consulter le reçu (`CoreV4SyncWorker.kt:221`, `InboxV4Repository.kt:327`) ; `applyInboxProposal` traite EXPIRED avant la preuve durable (`PlanController.kt:518/529`).

Impact constaté : divergence possible entre le journal ACK et le plan local. Aucun rejeu d'effet n'est identifié ; le consentement est revérifié et Android reste l'autorité. Correctif futur : réconcilier le reçu avant toute expiration dans les deux chemins ; si l'état reste inconnu, laisser l'entrée en attente.

### Autres limites P2

- La rétention de l'inbox et de la quarantaine n'est pas bornée.
- Le transport chat V4 n'est pas prouvé au niveau d'un socket réel ; le fil HTTPX chat est persistant.
- L'isolation des tests de notifications reste une limite connue.
- Le verrou de synchronisation est limité au processus.
- L'idempotence de l'enveloppe relais V1↔V2 et la couverture négative du relais restent à compléter.
- `session.test.ts` avait un flake historique ; il ne s'est pas reproduit dans le résultat final 89/89.
- `assembleDebugAndroidTest` ne signifie pas que des tests instrumentés ont tourné ; aucun test instrumenté n'a été exécuté dans cette campagne.
- Les 87 avertissements lint restent présents, selon la répartition du tableau de preuves.

## Commandes de reproduction consignées

Commandes consignées, avec leur répertoire de travail :

```sh
# Depuis backend/ ; le lanceur ajoutait seulement /usr/bin/time
/tmp/atlas-test-venv/bin/python -m pytest -ra

# Depuis integrations/relay/pc/
./node_modules/.bin/tsc -p tsconfig.json --noEmit
npm run build
npm test

# Depuis android/ ; les commandes ont été lancées dans cet ordre
GRADLE_USER_HOME=/tmp/atlas-gradle-home ./gradlew :app:testDebugUnitTest
GRADLE_USER_HOME=/tmp/atlas-gradle-home ./gradlew :app:assembleDebugAndroidTest
GRADLE_USER_HOME=/tmp/atlas-gradle-home ./gradlew :app:lintDebug
GRADLE_USER_HOME=/tmp/atlas-gradle-home ./gradlew :app:assembleDebug
```

Ce rapport documentaire n'a pas relancé les suites.

Le résultat final des matrices R01-R12 et T01-T12 se trouve dans [RECETTE_V63.md](RECETTE_V63.md) et dans la matrice canonique [ARCHITECTURE_V63.md §16](ARCHITECTURE_V63.md). R01-R12 restent NOT_RUN/BLOCKED et aucun PASS runtime n'est déclaré.

## Étapes de clôture restantes

1. Reprendre la qualification du Pixel Android 17 sur l'APK final hashé, avec sauvegarde et pré-vol selon la procédure dédiée, puis consigner le résultat sur ce SHA exact.
2. Prouver sur un Gateway isolé puis selon autorisation runtime que les six outils sont découverts et appelables, traiter l'audit indépendant et les vulnérabilités OpenClaw, sans présenter une simple installation comme une preuve.
3. Corriger l'image amont A4-4 pour préserver le protocole 4001, puis refaire le contrôle relais correspondant.
4. Corriger le P2 d'expiration en réconciliant le reçu avant toute expiration et en laissant un état inconnu en attente.
5. Compléter les recettes qui restent NOT_RUN, notamment transport réel, notifications, UI Pixel et état final. Les intégrations Etar, wger et localisation demeurent hors du périmètre livré.
