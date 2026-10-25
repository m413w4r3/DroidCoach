# Rapport de clôture V6.2

Date : 2026-10-25. Branche : `chore/cloture-v62-android17` (locale, aucun push).
**Décision proposée : `V6.2_NOT_CLOSED`** jusqu'à la re-revue indépendante du SHA final; aucun P0/P1 de code n'est ouvert après les correctifs, et toutes les preuves CI/AVD/OpenClaw sont vertes sur `b13b3fb`.

## Révisions et artefacts

| Élément | Valeur |
|---|---|
| Base de clôture | `8890046012965f3a3112ff0c419014c74e189d11` |
| SHA final du code | `b13b3fb` (`fix: account for long breaks and DST focus bounds`) |
| Commits du correctif mandat | `4aff91a` (durée totale + fin de fenêtre), `1251009` (traversée de minuit), `b13b3fb`/`d718540` (pauses longues + DST) |
| APK de campagne Pixel | SHA-256 `48c7766e9090010692f0eaafc1d0cbaf4b22e51259847f94b642a2d060b33583`, source `8890046…` |
| APK final (AVD) | SHA-256 `2743688c830a3e3df42b9db3c4d251cbf796837160d252e5976098346b2448ac`, source `b13b3fb` |
| APK androidTest final | SHA-256 `4a916f12f324b4cf10574038d42750bc6fe006f8dbc856ad9903225c824afa00` |
| Certificat debug | SHA-256 `da3014662eb65e50a87ef364a2e583c331398795b222e3b37b1a16553a00eeee` |
| Appareil physique | Pixel 9 Pro XL, Android 17/API 37, build `CP3A.260905.009`; numéro de série non consigné |
| Émulateur | `emulator-5554` (`Pixel_9_Pro_XL_2`), Android 17/API 37 |
| Sauvegarde Pixel | archive hors dépôt et hors `/tmp`, SHA-256 `26912d37b7f98ca88d9e1c42076698120c25e0bfb4de867a096d4609dc3160e0`, 12 entrées, 8 fichiers réguliers |

## Gates

| Gate | Statut | Preuve / portée |
|---|---|---|
| JVM sur SHA final | **PASS** | `:app:testDebugUnitTest --rerun-tasks` : 332 tests, 0 échec, 0 erreur, 0 ignoré (57 classes) |
| Lint sur SHA final | **PASS** | `:app:lintDebug --rerun-tasks` : 0 erreur, 47 avertissements |
| Build debug et androidTest | **PASS** | `assembleDebug` + `assembleDebugAndroidTest`, BUILD SUCCESSFUL, 81 tâches exécutées |
| Backend pytest sur SHA final | **PASS** | `66 passed, 1 skipped, 1 warning in 2.28 s` |
| Suite instrumentée locale AVD | **PASS** | 78 méthodes : 77 PASS + 1 échec de harnais `expected_system_font_scale`; retest ciblé = PASS; 78/78 effectifs sur l'APK final `2743688c…` |
| OpenClaw host-side réel | **PASS historique** | Run du 2026-10-24T22:40:46Z sur `8890046…` : réponse 25/120, propositions dans le mandat, ACK unique, révocation avant requête suivante, fallback propre |
| OpenClaw Android réel sur SHA final | **PASS** | `V62RealGatewayContextBridgeTest#realGatewayUsesAccelerated25MinuteFocusContextAndAcceptedProposal` : OK (1 test), 17,743 s, gateway réel, backend éphémère, APK final |
| P0 installation sans perte | **PASS historique** | `adb install -r`, même certificat, 8 empreintes de données identiques à la sauvegarde |
| P0 démarrage à froid | **PASS historique** | `Status: ok`, `COLD`, 548 ms; état Focus restauré |
| P0 HOME et sortie | **PASS historique** | Rôle HOME, tiroir, recherche, favori, ouverture Brave, retour Pixel Launcher |
| P0 hors ligne, Pause d'urgence | **PASS historique** | Mode avion : HOME, directive locale et Pause « Fin »; réseau et Pixel Launcher restaurés |
| P0 Focus/objectif/progression | **PASS historique Pixel + JVM final** | 0 → 25, redémarrage 25/120, annulation sans crédit; JVM final 332/332 |
| P0 stockage/reprise | **PASS JVM + AVD** | Tests de panne d'écriture et de reprise; reprise après panne matérielle réelle non testée |
| P0 agent désactivé/révocation | **PASS code + AVD, limite** | Barrière de révocation, génération de requête, expiration et inbox testées; révocation *pendant* une requête OpenClaw réelle non rejouée; tests locaux de réponse tardive PASS |
| P1 notifications | **PASS historique** | Permission refusée/accordée, publisher 2/2, routage 6/6, permission restaurée refusée |
| P1 rotation, police 2×, TalkBack | **PASS historique** | Rotation, police 2× et libellés vérifiés; P2 de contenu rogné documenté |
| P1 localisation/mobilité | **NOT_RUN — désactivées par défaut** | `CAP_LOCATION=false`, `CAP_MOBILITY=false`; aucune revendication Android 17; issue #1 ouverte |
| P1 quotas/anti-doublon/performance | **PARTIEL / NOT_RUN** | Tests AVD de déduplication et de silence PASS; quotas complets et mesure de consommation non exécutés |
| Restauration finale Pixel | **NON EXÉCUTÉE — décision propriétaire** | Le propriétaire a choisi « Garder l'état QA »; la sauvegarde d'origine reste intacte hors dépôt |
| Revue indépendante fonctionnelle | **NO_GO sur `1251009`, re-revue du SHA final PENDING** | Aucun P0/P1 code; P2 de preuve finale et de test de borne exacte, désormais traités dans les preuves `b13b3fb` |
| Revue indépendante sécurité | **NO_GO sur `1251009`, re-revue du SHA final PENDING** | Deux P1 confirmés (pauses longues, DST), corrigés par `b13b3fb`; P2 transport/pull/chemins restent ouverts |

## E2E local Pixel — PASS historique

Exécuté sur le Pixel avec l'APK `48c7766e…` (source `8890046…`), données synthétiques uniquement :

1. Objectif « QA V6.2 objectif 120 min » créé; Focus de 25 min explicitement lié démarré à `22:47:09Z`.
2. Avant la fin : **0 / 120 min**.
3. Après la fin : session `FINISHED`, 1500 s de travail; état lié à l'objectif.
4. Arrêt de processus puis relance à froid : `COLD`, 583 ms; l'interface Coach affiche **25 / 120 min (20 %)**.
5. Nouvelle session Focus liée de 25 min démarrée puis annulée après ~2 s : l'interface affiche toujours **25 / 120 min**; aucune minute créditée.
6. Pause d'urgence : activation et fin vérifiées séparément.

Limite : parcours exécuté sur l'APK de campagne `8890046…`, avant les correctifs de mandat. Le propriétaire a demandé l'arrêt des tests physiques; les correctifs ne touchent pas le moteur Focus ni le calcul de progression.

## Correctif P1 « mandate focus bounds »

Constats des reviewers sur `1251009` :

- durée totale incomplète : les pauses longues de 15 min insérées toutes les 4 cycles n'étaient pas comptées;
- fin de fenêtre décalée au lendemain lors du retour DST;
- absence d'oracle pour une durée totale exactement égale à la borne.

Correctifs livrés :

- `4aff91a` : durée totale `focus_minutes * cycles + break_minutes * max(0, cycles - 1)` pour `focus.propose` et `focus.plan`; rejet au-delà de `maxMinutesPerSession`; contrôle de fin de plan dans la fenêtre.
- `1251009` : contrôle de fin **date + heure**; traversée de minuit refusée sur une fenêtre journalière; borne exacte acceptée.
- `b13b3fb` : pauses longues de 15 min toutes les 4 cycles incluses dans la durée totale; fin de fenêtre comparée en **heure locale**, y compris retour DST; tests de durée exactement à la borne (55 min), de fin à 09:30 et de DST à Paris.
- `focus.start` et le moteur Focus ne sont pas modifiés.

Tests : ciblé `ActionDispatcherTest` 28/28 PASS; suite JVM finale 332/332 PASS; `focusPlanCanBePreauthorizedInsideTheMandateWindow` reste vert.

## OpenClaw — état final

- Host-side réel : PASS sur `8890046…`.
- Android réel : PASS sur `8890046…` (1 test, 11,143 s) puis **PASS sur le SHA final `b13b3fb`** (1 test, 17,743 s) sur `emulator-5554`, backend éphémère et gateway réel.
- Le premier essai sur `1251009` avait été bloqué par `HTTP 429` (quota Codex); après réinitialisation du quota, le test final a réussi.
- La révocation *pendant* une requête réelle reste `NOT_RUN`; les tests locaux de barrière, génération et réponse tardive sont PASS.

## Décisions du propriétaire

1. **Aucun autre test sur le Pixel physique** : le propriétaire récupère l'appareil; les tests Android finaux ont été exécutés sur `emulator-5554`.
2. **Pas de test de 25 minutes en temps réel** : les vérifications Focus finales utilisent les tests JVM/AVD à horloge injectée.
3. **État QA conservé sur le Pixel** : la restauration des données ATLAS et des réglages n'est pas exécutée; l'objectif et la session synthétiques restent visibles; la sauvegarde d'origine n'est pas modifiée.
4. `stay_on_while_plugged_in` : valeur initiale réelle **7**, jamais modifiée; la mention antérieure « restauration à 0 » était erronée.

## Limites ouvertes

- Re-revue indépendante du SHA final `b13b3fb` : **PENDING**.
- Révocation pendant une requête OpenClaw réelle : **NOT_RUN**; tests locaux PASS.
- Localisation/mobilité : **NOT_RUN** et désactivées par défaut; issue #1 ouverte.
- Performance/consommation au repos et variantes 320/360 dp : **NOT_RUN**.
- P2 sécurité : URL OpenClaw configurable sans garde de transport; callback de pull tardif après révocation; chemins locaux dans certains documents. Non corrigés dans cette clôture.
- Pixel non retesté sur le SHA final; résultats Pixel historiques sur `8890046…`.

## Branches et travaux conservés

| Branche | Commit | Contenu |
|---|---|---|
| `fix/v62-mandate-focus-bounds` | `364a125`, `18e0f4c`, `d718540` | Correctif P1 et tests |
| `test/v62-avd-api37-suite` | `8fdf37d` | Rapport et preuves AVD API 37 |
| `test/v62-ci-final` | `f23e2d1` | Rapport CI et preuves |
| `test/v62-openclaw-e2e` | `7a07333`, `c4841ed` | Run bloqué historique + run réel host-side |
| `docs/v62-closure-pack` | `d4705bf` | Première version du paquet documentaire |
| `docs/v62-ui-captures` | `a523317` | 15 captures UI AVD API 37 + index |
| `review/v62-functional`, `review/v62-security` | aucun commit | Rapports récupérés; re-revue du SHA final en cours |
| `audit/v62-smoke-scout-docs`, `-harness` | aucun commit | Rapports de scouts récupérés |

Aucun push, merge distant ou fermeture d'issue n'a été effectué.

## Conditions pour passer à `V6.2_CLOSED`

1. Re-revue fonctionnelle et sécurité du SHA final `b13b3fb` : aucun P0/P1.
2. Acceptation explicite des limites restantes (Pixel non retesté sur le SHA final, localisation/mobilité non validées mais désactivées, restauration non exécutée, révocation en vol non rejouée, P2 sécurité ouverts).
3. Verdict final consigné dans [REVUE_INDEPENDANTE.md](REVUE_INDEPENDANTE.md) et [CLOTURE_V62_ETAT.md](CLOTURE_V62_ETAT.md).
