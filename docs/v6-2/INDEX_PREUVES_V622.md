# ATLAS V6.2.2 — index des preuves

Les preuves de recette S6 sont des résumés expurgés. Les journaux complets des runners, captures et sorties de scripts restent sous `/tmp/atlas-v622-s6/`; ne pas recopier les journaux bruts OpenClaw ou les valeurs d'authentification dans le dépôt.

## Rapports

- [Recette complète S6](RECETTE_V622.md)
- [Qualification produit](QUALIFICATION_PRODUIT_V622.md)
- [Guide d'essai physique actualisé](GUIDE_ESSAI_REEL_V622.md)
- [Baseline S0](BASELINE_V622.md)

## Preuves S5 conservées

- [S5 Goal–Focus](preuves-v622/s5-focus-goals-validation.txt)
- [S5 B2 notifications](preuves-v622/s5-b2-notifications-validation.txt)
- [État Android 17 S4](preuves-v622/android17-s4-snapshot.txt)
- [Candidat Android 17](preuves-v622/android17-v622-final-candidate.txt)

## Preuves S6

- [Handoff S5 final ACK : hashes candidat vérifiés, préflight initial 5037 bloqué, deux tests `NOT_RUN`](preuves-v622/s6-ack-api34-final-20261021.txt)
- [Tentative d'isolation ACK via ADB 5038 : serveur bloqué avant inventaire, installation/tests `NOT_RUN`](preuves-v622/s6-ack-api34-5038-isolation-20261021.txt)
- [Run ACK API34 final : identité AVD, hashes installés, deux méthodes `PASS`, AVD arrêté](preuves-v622/s6-ack-api34-runtime-20261021.txt)
- [Contrôle physique ACK : Pixel 9 Pro XL API37, course `PASS`, chemin positif `FAIL` reproduit; premier cleanup vérifié, reprise interrompue avant instrumentation et état package `NOT_RECHECKED`](preuves-v622/s6-ack-physical-runtime-20261021.txt)
- [Préflight partagé initial : registre par défaut incomplet, remplacé par le run final](preuves-v622/s6-ack-api34-shared-adb-preflight-20261021.txt)

- [Restauration exacte de la fixture et rejeux finaux app `ce10…` / AndroidTest `893da1…`](preuves-v622/s6-revocation-fixture-exact-restore-20261021.txt)
- [Correction quarantaine et rejeux AndroidTest `0b5c…` — historique](preuves-v622/s6-correction-quarantaine-20261021.txt)
- [Rejeu E2E app `ce10…` / AndroidTest `0b5c…` — historique](preuves-v622/s6-e2e-final-0b5c-20261021.txt)
- [Rejeu frais du candidat S5 CLEAN, hashes, harnais, localisation, E2E et D3](preuves-v622/s6-rejeu-clean-20261021.txt)
- [Candidat S5/S7-P1-01 reçu `ea53…` : résultats historiques](preuves-v622/s6-candidat-s5-s7-p1-01-final.txt)

- [Inventaire ADB/AVD et limites](preuves-v622/s6-inventaire.txt)
- [Relecture S4 Android 17 : environnement bloqué avant installation](preuves-v622/s6-android17.txt)
- [Régressions avant correction](preuves-v622/s6-regressions-avant-correction.txt)
- [Régressions explicites, passage antérieur](preuves-v622/s6-regressions-corrigees.txt)
- [Premier harnais, avant corrections de fixture](preuves-v622/s6-harnais-avant-corrections.txt)
- [Harnais historique, 74/74 + 10/10 et captures](preuves-v622/s6-harnais-corrigees.txt)
- [Localisation visible : logique PASS / fournisseur bloqué](preuves-v622/s6-localisation.txt)
- [E2E OpenClaw antérieur et historique des refus](preuves-v622/s6-openclaw.txt)
- [Diagnostic E1/E2 expurgé et patches staging hors dépôt — historique](preuves-v622/s6-e2e-diagnostic.txt)
- [Correctifs E1/E2, verdict S7-P2-03 et protocole D3 préparé](preuves-v622/s6-harnais-verdict-p2-03.txt)
- [Focus hors ligne et processus OS](preuves-v622/s6-hors-ligne-processus.txt)
- [TalkBack AVD partiel et restauration](preuves-v622/s6-accessibilite.txt)
- [Mesures AVD locales et transaction coaching](preuves-v622/s6-mesures.txt)
- [Résultats historiques de la paire AndroidTest ea53e429…](preuves-v622/s6-candidat-s5-resultats.txt)
- [Passage AndroidTest 8edb antérieur au rebuild CLEAN : historique distinct](preuves-v622/s6-candidat-s5-corrige-final.txt)
- [Localisation/mobilité visible sur la paire corrigée](preuves-v622/s6-localisation-candidat-corrige.txt)
- [D3 : mesure locale et blocage du consentement coaching](preuves-v622/s6-d3-blocked-pause-urgence.txt)
- [D3 : diagnostic statique du consentement et séquence UI sûre](preuves-v622/s6-d3-consentement-ui-diagnostic.txt)
- [Protocole D3 coaching et statut réel](preuves-v622/s6-protocole-repos-coaching.txt)
- [Régressions hôte du script D3, 19 tests purs](../../scripts/test-v622-d3-rest-recipe.py)
- [Inventaire pré-exécution du candidat S5/S7-P1-01](preuves-v622/s6-candidat-s5-inventaire.txt)
- [Baseline Gradle précédente](preuves-v622/s6-baseline.txt)

## Sources de logs temporaires

- Préflight de régressions déplacé hors dépôt : `/tmp/atlas-v622-s6/regressions-instrumentation-20261020T224206Z/preflight.txt` (163 octets, SHA-256 `c900f77daac5460b6e8a85fca2c0bc1306d3f684da48c4b851cf078f112bd81d`).

- Harnais final : `/tmp/atlas-v622-s6/full-harness-final/20261020T230129Z/` (manifeste SHA-256 `d3fe0c5c8809627f209f043e61d1d33319f4a4d1842dd3083b256d0e2e3225d6`).
- Régressions ciblées : `/tmp/atlas-v622-s6/regression-*.log`.
- Localisation visible : `/tmp/atlas-v622-s1/logs/recipe-20261020T224246Z/`.
- E2E OpenClaw : `/tmp/atlas-v622-s6/openclaw-real-20261020T224832Z/`; rejeux historiques `20261020T225427Z`, `20261020T225535Z`; diagnostic structuré `/tmp/atlas-v622-s6/e2e-refusal-diagnostic-20261020T232808Z/` et paire de reproduction `/tmp/atlas-v622-s6/e2e-refusal-diagnostic-20261020T233034Z/`.
- E2E réel du candidat reçu `ea53…`, deux passages consécutifs et offline : `/tmp/atlas-v622-s6/openclaw-real-candidate-ce10c968-pass12/`; résumés expurgés seulement.
- Rejeu CLEAN `ce10…` / `8edb…` du 21 octobre : `/tmp/atlas-v622-s6/clean-replay-20261021/` (20/20 ciblés; suite principale 75 cas / 1 échec; 8 compléments PASS, 2 `NOT_RUN`; états et APK installées vérifiés).
- Correction ciblée AndroidTest `ce10…` / `0b5c…` : `/tmp/atlas-v622-s6/quarantine-correction-20261021/`; runner cible `/tmp/atlas-v622-s6/quarantine-corrected-target/`, 75 cas `/tmp/atlas-v622-s6/quarantine-corrected-main-20261021/`, compléments `/tmp/atlas-v622-s6/quarantine-corrected-complements-20261021/` (8/8), localisation `/tmp/atlas-v622-s6/quarantine-corrected-location-20261021/` (logique/activité/préparation PASS; acquisition mobilité `TEST_SYSTEM_BLOCKED`). Ces résultats et hashes `0b5c` sont historiques.
- Localisation visible du rejeu CLEAN : `/tmp/atlas-v622-s6/location-clean-replay-20261021/` (logique et activité visible PASS, mobilité `TEST_SYSTEM_BLOCKED`).
- E2E CLEAN historique `ce10…` / `8edb…` : `/tmp/atlas-v622-s6/openclaw-real-clean-context-20261021/` (2 passes S6 consécutives + test hors ligne); préambule `V6RealAgentE2e` en échec distinct sous `/tmp/atlas-v622-s6/openclaw-real-clean-20261021/`.
- E2E final `ce10…` / `0b5c…` : `/tmp/atlas-v622-s6/openclaw-real-final-0b5c-20261021/recipe-results/` (résultat historique, hashes pré/post identiques).
- Restauration exacte et rejeux finaux `ce10…` / `893da1…` : `/tmp/atlas-v622-s6/quarantine-exact-restore-20261021/` et `/tmp/atlas-v622-s6/openclaw-real-final-893da1-20261021/recipe-results/` (test ciblé 1/1, suite principale 75/75 à 360 dp, deux captures de viewport 1/1 chacune, E2E Gateway 2/2 consécutifs et hors ligne 1/1; hashes installés conformes; AVD arrêté, données conservées).
- Rejeu supplémentaire de cette paire le 21 octobre, 11:37–11:42 UTC : `/tmp/atlas-v622-s6/quarantine-exact-restore-20261021/rerun/` et `/tmp/atlas-v622-s6/openclaw-real-final-893da1-rerun-20261021/recipe-results/` (ciblé 1/1, principal 75/75, Gateway 2/2, hors ligne 1/1; AndroidTest seulement remplacé par `install -r -t`, hashes pré/post conformes, `V6RealAgentE2e NOT_RUN`, AVD arrêté).
- Le script complet `v62-complete-instrumentation.sh` n'a pas été lancé sur le rejeu CLEAN : il exécute deux `pm clear` et les deux scénarios UsageStats effacent l'état de consentement/historique. Aucun statut de 21 captures n'est transféré au rejeu courant.
- Harnais du candidat reçu `ea53…`, historique : `/tmp/atlas-v622-s6/full-harness-candidate-ce10c968-final/` (`recipe=FAIL`, 75 exécutés / 1 échec; 10 compléments et 21 captures PASS).
- Régressions ciblées du candidat reçu : `/tmp/atlas-v622-s6/regressions-candidate-ce10c968/`; résumé runner expurgé.
- Baseline propre du candidat : `/tmp/atlas-v622-s6/baseline-candidate-ce10c968/`.
- D3 local candidat reçu : `/tmp/atlas-v622-s6/d3-rest-screenfix-ce10c968-20261021/local.json`; coaching `BLOCKED`, rapport dans `s6-d3-blocked-pause-urgence.txt`.
- Localisation candidat reçu : `/tmp/atlas-v622-s6/location-candidate-ce10-ea53-final/`; logique PASS puis `BLOCKED` sur mismatch AndroidTest installé (`8edb…` vs candidat `ea53…`), sans réinstallation ni acquisition GPS. Le `TEST_SYSTEM_BLOCKED` de `/tmp/atlas-v622-s6/location-corrected-test-8edb71a8/` est historique.
- Baseline finale : `/tmp/atlas-v622-s6/baseline-final/`.

Les preuves `s6-candidat-s5-resultats.txt`, `s6-e2e-candidat-s5-final.txt` et `s6-candidat-s5-corrige-final.txt` conservent des exécutions antérieures. Le chemin signalé `logs/regressions-instrumentation/20261020T224206Z23Ko` est absent du worktree; les sorties S6 conservées sont sous `/tmp/atlas-v622-s6/`. Les preuves `8edb…` et `0b5c…` restent historiques et distinctes; [la preuve de restauration exacte](preuves-v622/s6-revocation-fixture-exact-restore-20261021.txt) porte seule les derniers statuts du candidat `893da1…`.
