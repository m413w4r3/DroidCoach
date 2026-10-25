# Rapport de clôture V6.2

Date : 2026-10-25. Branche : `chore/cloture-v62-android17` (locale, aucun push).
**Décision proposée : `V6.2_NOT_CLOSED`** jusqu'à la re-revue indépendante du SHA final `1a6f761`; les P1 identifiés ont été corrigés au fil des revues, mais la suite AVD complète du SHA final est classée `UI_FLAKY_ENVIRONMENT` et la revue finale du dernier delta reste à rendre.

## Révisions et artefacts

| Élément | Valeur |
|---|---|
| Base de clôture | `8890046012965f3a3112ff0c419014c74e189d11` |
| SHA final du code | `1a6f761` (`fix: reject continuous agent focus sessions`) |
| Commits du correctif mandat | `4aff91a`, `1251009`, `b13b3fb`/`d718540`, `18484e2`/`f21fa8e`, `1a6f761`/`edbb3f5` |
| APK de campagne Pixel | SHA-256 `48c7766e9090010692f0eaafc1d0cbaf4b22e51259847f94b642a2d060b33583`, source `8890046…` |
| APK final (AVD) | SHA-256 `dc1c3e004fb7a3394c47dfc4b28407e211a06f48db51e86bbc7b665f05d62d87`, source `1a6f761` |
| APK androidTest final | SHA-256 `4a916f12f324b4cf10574038d42750bc6fe006f8dbc856ad9903225c824afa00` |
| Certificat debug | SHA-256 `da3014662eb65e50a87ef364a2e583c331398795b222e3b37b1a16553a00eeee` |
| Appareil physique | Pixel 9 Pro XL, Android 17/API 37, build `CP3A.260905.009`; numéro de série non consigné |
| Émulateur | `emulator-5554` (`Pixel_9_Pro_XL_2`), Android 17/API 37 |
| Sauvegarde Pixel | archive hors dépôt et hors `/tmp`, SHA-256 `26912d37b7f98ca88d9e1c42076698120c25e0bfb4de867a096d4609dc3160e0`, 12 entrées, 8 fichiers réguliers |

## Gates

| Gate | Statut | Preuve / portée |
|---|---|---|
| JVM sur SHA final | **PASS** | 339 tests, 0 échec, 0 erreur, 0 ignoré (57 classes) |
| Lint sur SHA final | **PASS** | 0 erreur, 47 avertissements |
| Build debug et androidTest | **PASS** | BUILD SUCCESSFUL, 81 tâches exécutées, 1 min 16 s |
| Backend pytest sur SHA final | **PASS** | 66 passed, 1 skipped, 1 warning, 2,72 s |
| Suite instrumentée locale AVD | **UI_FLAKY_ENVIRONMENT** | 78 méthodes : 73 PASS, 5 échecs UI Compose (landscape/font scale/tiroir), 0 skip; classes agent/focus ciblées 15/15 PASS; suite complète sur `18484e2` (même code UI) 78/78 effectifs après deux retests |
| OpenClaw host-side réel | **PASS historique** | Run du 2026-10-24T22:40:46Z sur `8890046…` |
| OpenClaw Android réel sur SHA final | **PASS** | `V62RealGatewayContextBridgeTest#realGatewayUsesAccelerated25MinuteFocusContextAndAcceptedProposal` : OK (1 test), 13,817 s, gateway réel, backend éphémère, APK final |
| P0 installation sans perte | **PASS historique** | `adb install -r`, même certificat, 8 empreintes identiques à la sauvegarde |
| P0 démarrage à froid | **PASS historique** | `Status: ok`, `COLD`, 548 ms; état Focus restauré |
| P0 HOME et sortie | **PASS historique** | Rôle HOME, tiroir, recherche, favori, Brave, retour Pixel Launcher |
| P0 hors ligne, Pause d'urgence | **PASS historique** | Mode avion : HOME, directive locale, Pause « Fin »; réseau et Pixel Launcher restaurés |
| P0 Focus/objectif/progression | **PASS historique Pixel + JVM final** | 0 → 25, redémarrage 25/120, annulation sans crédit; JVM final 339/339 |
| P0 stockage/reprise | **PASS JVM + AVD ciblé** | Tests de panne d'écriture et de reprise; reprise matérielle réelle non testée |
| P0 agent désactivé/révocation | **PASS code + AVD ciblé, limite** | Barrière, génération, expiration et inbox testées; révocation en vol OpenClaw réelle non rejouée |
| P1 notifications | **PASS historique** | Permission refusée/accordée, publisher 2/2, routage 6/6 |
| P1 rotation, police 2×, TalkBack | **PASS historique** | Rotation, police 2× et libellés vérifiés; P2 de contenu rogné |
| P1 localisation/mobilité | **NOT_RUN — désactivées par défaut** | `CAP_LOCATION=false`, `CAP_MOBILITY=false`; issue #1 ouverte |
| P1 quotas/anti-doublon/performance | **PARTIEL / NOT_RUN** | Tests AVD de déduplication et de silence PASS; quotas complets et performance non mesurés |
| Restauration finale Pixel | **NON EXÉCUTÉE — décision propriétaire** | État QA synthétique conservé; sauvegarde d'origine intacte |
| Revue indépendante fonctionnelle | **NO_GO sur `18484e2`, re-revue de `1a6f761` PENDING** | P1 `continuous` confirmé, corrigé par `1a6f761`; P2 UI flaky et limites explicites |
| Revue indépendante sécurité | **NO_GO sur `18484e2`, re-revue de `1a6f761` PENDING** | P1 `continuous` confirmé, corrigé par `1a6f761`; P2 transport/pull restent ouverts |

## E2E local Pixel — PASS historique

Exécuté sur le Pixel avec l'APK `48c7766e…` (source `8890046…`), données synthétiques uniquement :

1. Objectif « QA V6.2 objectif 120 min » créé; Focus de 25 min explicitement lié démarré à `22:47:09Z`.
2. Avant la fin : **0 / 120 min**.
3. Après la fin : session `FINISHED`, 1500 s de travail; état lié à l'objectif.
4. Arrêt de processus puis relance à froid : `COLD`, 583 ms; l'interface Coach affiche **25 / 120 min (20 %)**.
5. Nouvelle session Focus liée de 25 min démarrée puis annulée après ~2 s : toujours **25 / 120 min**; aucune minute créditée.
6. Pause d'urgence : activation et fin vérifiées séparément.

Limite : parcours exécuté avant les correctifs de mandat; le propriétaire a demandé l'arrêt des tests physiques.

## Correctif P1 « mandate focus bounds »

Constats successifs des reviewers :

- durée totale incomplète : pauses longues de 15 min non comptées;
- fin de fenêtre décalée au lendemain lors du retour DST;
- pause courte de 0 minute sous-comptée alors que le moteur impose au moins 1 minute;
- `focus.start` ne revalidait pas la carte persistée;
- mode `continuous` non bornable, alors que le moteur continue les cycles.

Correctifs livrés :

- `4aff91a` : durée totale, borne de session, fin de fenêtre;
- `1251009` : date + heure, traversée de minuit;
- `b13b3fb` : pauses longues et DST;
- `18484e2` : pause courte minimale, helpers partagés, revalidation complète de la carte persistée dans `startAgentFocusTransaction` avant mutation;
- `1a6f761` : refus explicite des cartes agent `continuous`, helper non borné, tests `focus.propose`/`focus.plan` refusant le paramètre.

Tests : ciblé `ActionDispatcherTest` 35/35; suite JVM finale 339/339.

## OpenClaw — état final

- Host-side réel : PASS sur `8890046…`.
- Android réel : PASS sur `8890046…`, `b13b3fb`, `18484e2` et le SHA final `1a6f761` (1 test, 13,817 s), sur `emulator-5554`, backend éphémère et gateway réel.
- Le premier essai sur `1251009` avait été bloqué par `HTTP 429` (quota Codex); après réinitialisation, les runs finaux ont réussi.
- La révocation *pendant* une requête réelle reste `NOT_RUN`; les tests locaux de barrière, génération et réponse tardive sont PASS.

## Décisions du propriétaire

1. **Aucun autre test sur le Pixel physique**; les tests Android finaux ont été exécutés sur `emulator-5554`.
2. **Pas de test de 25 minutes en temps réel**.
3. **État QA conservé sur le Pixel** : restauration non exécutée; sauvegarde d'origine intacte.
4. `stay_on_while_plugged_in` : valeur initiale réelle **7**, jamais modifiée.

## Limites ouvertes

- Re-revue indépendante du SHA final `1a6f761` : **PENDING**.
- Suite AVD complète sur `1a6f761` : `UI_FLAKY_ENVIRONMENT` (5 tests UI Compose); classes agent/focus ciblées 15/15 PASS; suite complète sur `18484e2` 78/78 effective.
- Révocation pendant une requête OpenClaw réelle : `NOT_RUN`.
- Localisation/mobilité : `NOT_RUN`, désactivées par défaut; issue #1 ouverte.
- Performance/consommation et variantes 320/360 dp : `NOT_RUN`.
- P2 sécurité : URL OpenClaw configurable; callback de pull tardif.
- Pixel non retesté sur le SHA final.

## Branches et travaux conservés

| Branche | Commit | Contenu |
|---|---|---|
| `fix/v62-mandate-focus-bounds` | `364a125`, `18e0f4c`, `d718540`, `f21fa8e`, `edbb3f5` | Correctif P1 et tests |
| `test/v62-avd-api37-suite` | `8fdf37d` | Rapport et preuves AVD API 37 |
| `test/v62-ci-final` | `f23e2d1` | Rapport CI et preuves |
| `test/v62-openclaw-e2e` | `7a07333`, `c4841ed` | Runs OpenClaw |
| `docs/v62-closure-pack` | `d4705bf` | Première version documentaire |
| `docs/v62-ui-captures` | `a523317` | 15 captures UI AVD |
| `review/v62-functional`, `review/v62-security` | aucun commit | Rapports récupérés; re-revue finale en cours |
| `audit/v62-smoke-scout-docs`, `-harness` | aucun commit | Rapports de scouts récupérés |

Aucun push, merge distant ou fermeture d'issue.

## Conditions pour passer à `V6.2_CLOSED`

1. Re-revue fonctionnelle et sécurité de `1a6f761` : aucun P0/P1.
2. Acceptation explicite des limites restantes (Pixel non retesté, localisation/mobilité off, restauration non exécutée, révocation en vol non rejouée, AVD UI flaky, P2 sécurité ouverts).
3. Verdict final consigné dans [REVUE_INDEPENDANTE.md](REVUE_INDEPENDANTE.md) et [CLOTURE_V62_ETAT.md](CLOTURE_V62_ETAT.md).
