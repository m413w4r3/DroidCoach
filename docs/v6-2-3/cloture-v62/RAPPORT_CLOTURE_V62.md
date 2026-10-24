# Rapport de clôture V6.2

Date : 2026-10-25. Branche : `chore/cloture-v62-android17` (locale, aucun push).
**Décision proposée : `V6.2_NOT_CLOSED`** — aucun défaut P0/P1 de code n'est ouvert, mais deux preuves finales manquent encore : revue indépendante du SHA final et réexécution OpenClaw Android sur ce même SHA. Les autres gates sont PASS ou explicitement limités par décision du propriétaire.

## Révisions et artefacts

| Élément | Valeur |
|---|---|
| Base de clôture | `8890046012965f3a3112ff0c419014c74e189d11` |
| SHA final du code | `1251009` (`fix: reject focus plans crossing the mandate day`) |
| Commit du correctif principal | `4aff91a` (`fix: bound total focus plan duration and window end`) |
| APK de campagne Pixel | SHA-256 `48c7766e9090010692f0eaafc1d0cbaf4b22e51259847f94b642a2d060b33583`, source `8890046…` |
| APK final (AVD) | SHA-256 `80b1d0041e291fb41203c5f37f2242976765bcfe5a95273160a85fefdfcfe4d9`, source `1251009` |
| APK androidTest final | SHA-256 `4a916f12f324b4cf10574038d42750bc6fe006f8dbc856ad9903225c824afa00` |
| Certificat debug | SHA-256 `da3014662eb65e50a87ef364a2e583c331398795b222e3b37b1a16553a00eeee` |
| Appareil physique | Pixel 9 Pro XL, Android 17/API 37, build `CP3A.260905.009`; numéro de série non consigné |
| Émulateur | `emulator-5554`, Android 17/API 37 |
| Sauvegarde Pixel | archive hors dépôt et hors `/tmp`, SHA-256 `26912d37b7f98ca88d9e1c42076698120c25e0bfb4de867a096d4609dc3160e0`, 12 entrées, 8 fichiers réguliers |

## Gates

| Gate | Statut | Preuve / portée |
|---|---|---|
| JVM sur SHA final | **PASS** | `:app:testDebugUnitTest --rerun-tasks` : 328 tests, 0 échec, 0 erreur, 0 ignoré (57 classes) |
| Lint sur SHA final | **PASS** | `:app:lintDebug --rerun-tasks` : 0 erreur, 47 avertissements |
| Build debug et androidTest | **PASS** | `assembleDebug` + `assembleDebugAndroidTest`, BUILD SUCCESSFUL, 81 tâches exécutées |
| Backend pytest sur SHA final | **PASS** | `66 passed, 1 skipped, 1 warning in 2.64 s` |
| Suite instrumentée locale AVD | **PASS** | 78 méthodes : 77 PASS + 1 échec de harnais `expected_system_font_scale`; retest ciblé avec paramètre = PASS; 78/78 effectifs sur l'APK final |
| OpenClaw host-side réel | **PASS historique** | Run du 2026-10-24T22:40:46Z sur `8890046…` : réponse 25/120, propositions dans le mandat, ACK unique, révocation avant requête suivante, fallback propre |
| OpenClaw Android réel | **PASS historique sur `8890046…`** | `V62RealGatewayContextBridgeTest#realGatewayUsesAccelerated25MinuteFocusContextAndAcceptedProposal` OK (1 test), 11.143 s, gateway réel |
| OpenClaw Android sur SHA final | **BLOCKED_ENVIRONMENT** | Gateway local `HTTP 429` : `Codex error: The usage limit has been reached`, réessai possible après 02:44. Le backend et l'émulateur sont sains; aucun échec de code observé |
| P0 installation sans perte | **PASS** | `adb install -r`, même certificat, 8 empreintes de données identiques à la sauvegarde |
| P0 démarrage à froid | **PASS** | `Status: ok`, `COLD`, 548 ms; état Focus restauré |
| P0 HOME et sortie | **PASS** | Rôle HOME, tiroir, recherche, favori, ouverture Brave, retour Pixel Launcher |
| P0 hors ligne, Pause d'urgence | **PASS** | Mode avion : HOME, directive locale et Pause « Fin »; réseau et Pixel Launcher restaurés |
| P0 Focus/objectif/progression | **PASS Pixel + JVM** | Voir E2E local ci-dessous : 0 → 25, redémarrage 25/120, annulation sans crédit |
| P0 stockage/reprise | **PASS JVM + AVD** | Tests de panne d'écriture et de reprise; reprise après panne matérielle réelle non testée |
| P0 agent désactivé/révocation | **PASS code + AVD, limite** | Barrière de révocation, génération de requête, expiration et inbox testées; révocation *pendant* une requête OpenClaw réelle non rejouée sur le SHA final |
| P1 notifications | **PASS** | Permission refusée/accordée, publisher 2/2, routage 6/6, permission restaurée refusée |
| P1 rotation, police 2×, TalkBack | **PASS** | Rotation, police 2× et libellés vérifiés; P2 de contenu rogné documenté |
| P1 localisation/mobilité | **NOT_RUN — désactivées par défaut** | `CAP_LOCATION=false`, `CAP_MOBILITY=false` dans les capabilities par défaut; aucune revendication de validation Android 17; issue #1 reste ouverte |
| P1 quotas/anti-doublon/performance | **PARTIEL / NOT_RUN** | Tests AVD de déduplication et de silence PASS; quotas complets et mesure de consommation non exécutés |
| Restauration finale Pixel | **NON EXÉCUTÉE — décision propriétaire** | Le propriétaire a choisi « Garder l'état QA »; les données ATLAS contiennent donc l'objectif et la session synthétiques. La sauvegarde d'origine reste intacte hors dépôt |
| Revue indépendante fonctionnelle | **NO_GO sur `8890046…`, à rejouer sur `1251009`** | Rapport récupéré : aucun P0/P1 code, mais E2E local et révocation en vol alors non conclus; le E2E local est désormais PASS |
| Revue indépendante sécurité | **P1 corrigé, à rejouer sur `1251009`** | P1 confirmé : bornes Focus locales insuffisantes; corrigé par `4aff91a` + `1251009`. P2 OpenClaw URL sans garde de transport reste ouvert |

## E2E local Pixel — PASS

Exécuté sur le Pixel avec l'APK `48c7766e…` (source `8890046…`), données synthétiques uniquement :

1. Objectif « QA V6.2 objectif 120 min » créé; Focus de 25 min explicitement lié démarré à `22:47:09Z`.
2. Avant la fin : **0 / 120 min**.
3. Après la fin : session `FINISHED`, 1500 s de travail; état lié à l'objectif.
4. Arrêt de processus puis relance à froid : `COLD`, 583 ms; l'interface Coach affiche **25 / 120 min (20 %)**.
5. Nouvelle session Focus liée de 25 min démarrée puis annulée après ~2 s : l'interface affiche toujours **25 / 120 min**; aucune minute créditée.
6. Pause d'urgence : activation et fin vérifiées séparément (matrice Pixel).

Limite : ce parcours a été exécuté sur l'APK de campagne `8890046…`, pas sur l'APK final `1251009`. Le correctif final ne touche que `MandateVerifier` (propositions d'agent), pas le moteur Focus ni le calcul de progression. Le propriétaire a explicitement demandé l'arrêt des tests sur le téléphone physique après ce parcours.

## Correctif P1 « mandate focus bounds »

Constat du reviewer sécurité sur `8890046…` : `MandateVerifier` ne bornait que `focus_minutes` par cycle et ne vérifiait pas la fin d'un `focus.plan` dans la fenêtre du mandat.

Correctif livré :

- `4aff91a` : durée totale = `focus_minutes * cycles + break_minutes * max(0, cycles - 1)` pour `focus.propose` et `focus.plan`; rejet au-delà de `maxMinutesPerSession`; contrôle de fin de plan dans la fenêtre, fenêtres journalières et nocturnes.
- `1251009` : contrôle de fin **date + heure**; un plan qui traverse minuit est refusé même si son heure locale reste dans la plage (ex. 23:50 + 25 min sur une fenêtre 00:00–23:59). Une fin exactement à la borne reste acceptée.
- Tests ajoutés dans `ActionDispatcherTest.kt` : durée totale (propose/plan), fin hors fenêtre journalière, borne exacte, fenêtre nocturne, traversée de minuit, borne exacte de fin de journée.
- Oracle : test ciblé 24/24 PASS; suite JVM finale 328/328 PASS; `focusPlanCanBePreauthorizedInsideTheMandateWindow` reste vert.
- `focus.start` et le moteur Focus ne sont pas modifiés.

## OpenClaw — état exact

- Host-side réel : PASS sur `8890046…` (réponse contextualisée 25/120, propositions dans le mandat, ACK idempotent, retrait de `goals` après révocation, fallback).
- Android réel : PASS sur `8890046…` sur AVD API 37 (1 test, 11.143 s), avec `MandateVerifier`, proposition `EXECUTED`, ACK et effet local.
- Réexécution sur `1251009` : **BLOCKED_ENVIRONMENT**. Le gateway local répond `HTTP 200 /health` et `HTTP 200 /v1/models`, mais un appel `/v1/chat/completions` renvoie `HTTP 429` avec `Codex error: The usage limit has been reached`. Le quota est annoncé comme réinitialisé à 02:44. Aucun jeton, en-tête ou payload brut n'est consigné.
- La révocation *pendant* une requête réelle reste `NOT_RUN`; les tests locaux de barrière, génération et réponse tardive sont PASS.

## Décisions du propriétaire

1. **Aucun autre test sur le Pixel physique** : le propriétaire récupère l'appareil. Tous les tests Android restants ont été exécutés sur `emulator-5554`.
2. **Pas de test de 25 minutes en temps réel** : les vérifications Focus finales utilisent les tests JVM/AVD à horloge injectée; le parcours Pixel 25 min déjà réalisé n'est pas rejoué.
3. **État QA conservé sur le Pixel** : la restauration des données ATLAS et des réglages n'est pas exécutée. L'objectif et la session synthétiques restent visibles; la sauvegarde d'origine n'est pas modifiée.
4. `stay_on_while_plugged_in` : la valeur initiale réellement observée au pré-vol est **7** (et non 0); elle est restée à 7. La mention « restauration à 0 » du rapport précédent était erronée et est corrigée.

## Limites ouvertes

- Revue indépendante du SHA final `1251009` : **PENDING** (reviewers Luna indisponibles jusqu'à la réinitialisation du quota; les rapports du SHA de base sont conservés).
- OpenClaw Android sur `1251009` : **BLOCKED_ENVIRONMENT** (429).
- Localisation/mobilité : **NOT_RUN** et désactivées par défaut; issue #1 ouverte.
- Performance/consommation au repos et variantes 320/360 dp : **NOT_RUN**.
- P2 sécurité : `OPENCLAW_BASE_URL` accepte une URL distante sans garde de transport; la valeur par défaut est loopback. Non corrigé dans cette clôture.
- P2 privacy : certains documents de preuve contiennent des chemins locaux; ils sont expurgés dans la mesure du possible, mais les rapports bruts restent hors dépôt.

## Branches et travaux conservés

| Branche | Commit | Contenu |
|---|---|---|
| `fix/v62-mandate-focus-bounds` | `364a125`, `18e0f4c` | Correctif P1 et tests |
| `test/v62-avd-api37-suite` | `8fdf37d` | Rapport et preuves AVD API 37 |
| `test/v62-ci-final` | `f23e2d1` | Rapport CI et preuves |
| `test/v62-openclaw-e2e` | `7a07333`, `c4841ed` | Run bloqué historique + run réel host-side |
| `docs/v62-closure-pack` | `d4705bf` | Première version du paquet documentaire |
| `docs/v62-ui-captures` | `a523317` | 15 captures UI AVD API 37 + index |
| `review/v62-functional` | aucun commit | Rapport récupéré depuis la notification; à rejouer sur le SHA final |
| `review/v62-security` | aucun commit | Rapport récupéré; P1 corrigé, à rejouer sur le SHA final |
| `audit/v62-smoke-scout-docs` / `-harness` | aucun commit | Rapports de scouts récupérés depuis les notifications |

Aucun push, merge distant ou fermeture d'issue n'a été effectué.

## Conditions pour passer à `V6.2_CLOSED`

1. Revue indépendante du diff final `4aff91a..1251009` (ou du fichier final) par un Luna XHigh, sans P0.
2. Réexécution OpenClaw Android sur `1251009` après réinitialisation du quota, ou acceptation explicite du propriétaire de conserver le PASS historique sur `8890046…` comme preuve du transport et du contexte.
3. Acceptation explicite des limites P1 restantes (localisation/mobilité non validées mais désactivées par défaut; absence de test Pixel sur le SHA final; restauration non exécutée).
