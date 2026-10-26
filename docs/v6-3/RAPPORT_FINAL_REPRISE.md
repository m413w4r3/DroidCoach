# ATLAS V6.3 — Rapport final de la reprise (2026-10-26)

Statut : **CAMPAGNE DE REPRISE CLOSE LOCALEMENT — GO_WITH_LIMITATIONS**

Périmètre livré : planification négociée backend + Android, lecture calendrier
consentie et expurgée, lecture wger en mode `api_token`, plugin de recherche
OpenClaw construit mais non activé, audits Bridge/Paseo/calendrier. Les portes
P4 (écriture calendrier/Etar), P5 (activation OpenClaw) et les écritures wger
restent non approuvées et non exécutées. Aucun push, aucune PR, aucune fusion
distante.

## 1. État Git final

| Élément | Valeur |
|---|---|
| Base `main` avant fusion | `ab477ef70dcf7749fb8283b0213c4fcdaa824341` |
| Branche de campagne | `feat/atlas-v6-3-reprise` |
| Tip de campagne | `e1b1ac317f6ceef1c365992d4c5f233f9c9317f6` |
| Merge `--no-ff` | `2b46485eb52d7ce1097b5140b8c832235e71779c` — parents `ab477ef` et `e1b1ac3` |
| Commit propriétaire `prompts updates` | `e39d57367d69d9b8825a864e533405ca83b37d39` — après le merge ; seuls `prompts/` changent |
| `main` local final | `e39d57367d69d9b8825a864e533405ca83b37d39` |
| `origin/main` | `ab477ef70dcf7749fb8283b0213c4fcdaa824341` — **aucun push** |
| Arbre de travail | propre |
| Diff de la campagne | 88 fichiers, +21 655 / −25 (hors commit `prompts`) |

Le commit `e39d573` est l'action du propriétaire sur ses prompts : il ajoute
`prompts/PROMPT_MAITRE.md` et retire les anciens prompts. Il n'a pas été modifié
par la campagne et ne change aucun code.

## 2. Fonctionnalités livrées et qualifiées

| Lot | Livrable | Intégration | Preuve |
|---|---|---|---|
| R0 | Pré-vol, alignement documentaire, baseline, décisions | `f625f0d` | `preuves/reprise-r0.txt`, 91 PASS / 1 skip à la baseline |
| L1-03 | Action suivante sur le HOME, accessibilité | `38c70d7`, `40c8693` | `preuves/l1-03-home-next.txt`, `l1-03-access-audit.txt` ; 439 JVM au lot |
| L3-01a | `POST /v3/agent/plan-revision`, validation stricte, idempotence durable, aucun outil modèle | `8c402ee` ; revue `97538bfb` | `preuves/l3-01a-plan-revision.txt` ; 152 PASS / 1 skip ; `ACCEPTED_WITH_P2` |
| L3-01b | Parseur V3 borné, mapping déterministe, client HTTP, diff/confirmation | `4df7e68` | `preuves/l3-01b-android-plan-revision.txt` ; 471 JVM |
| L3-01c | Câblage Plan/coach/calendrier, consentement explicite, `BackendUrlPolicy` | `1791030` | `preuves/l3-01c-plan-ui-wiring.txt` ; 474 JVM, lint 0/54, APK |
| L2-02 | Préparation `READ_CALENDAR`, écran de diagnostic, détection Etar | `c990a75` | `preuves/l2-02-calendar-read-prep.txt` ; permission non octroyée avant R1 |
| L5-01/02 | Client wger lecture seule, `api_token` réel, JWT rotatif inactif, dates ISO strictes | `e4b480c`, `65901ab` | `preuves/l5-01-wger.txt` ; 170 PASS / 1 skip ; réel 200/200/200/200 |
| L8-02 | Plugin OpenClaw `atlas_chatgpt_research`, un outil optionnel | `fcbe8c3` | 41/41 tests, build et `plugins validate` PASS ; non installé/activé |
| Audits | Bridge/OpenClaw, transport Paseo/Relay, écriture calendrier | — | `AUDIT_BRIDGE_PLUGIN.md`, `AUDIT_TRANSPORT_PASEO.md`, `AUDIT_CALENDAR_WRITE.md` |
| R1 | Parcours physique Pixel : plan, coach, idempotence, hors-ligne, calendrier lecture | APK `6ab95fac` | `preuves/r1-physical.txt` ; `R1_PLAN_PHYSIQUE.md` |

Le détail ticket par ticket, les revues et les SHAs intermédiaires restent dans
`docs/v6-3/REGISTRE_TACHES.md`, source de vérité.

## 3. Oracles finaux sur `main`

Exécutés sur l'arbre de `main` (`e39d573`, code identique au merge `2b46485`) :

```bash
cd backend && .venv/bin/python -m pytest -q
# 170 passed, 1 skipped, 1 warning in 6.47s
```

```bash
cd android && ./gradlew :app:testDebugUnitTest --rerun-tasks --max-workers=1
# BUILD SUCCESSFUL in 27s, 24 tâches exécutées
# 70 suites, 478 tests, 0 failure, 0 error, 0 skipped
```

```bash
cd android && ./gradlew :app:lintDebug :app:assembleDebug --max-workers=1
# BUILD SUCCESSFUL
# lint : 0 erreur, 54 avertissements
# APK debug : PASS
```

APK debug reconstruit depuis l'arbre fusionné :

- version `0.1.0`, `versionCode 1` ;
- SHA-256 `6ab95fac9d635ea9f5a4947b6fabc901526b6d953f7f930e04e39fcfbf51c886` ;
- **identique à l'APK installé et validé sur le Pixel en R1**.

Le build Gradle de l'APK et le lint étaient `UP-TO-DATE` (aucune source Android
modifiée depuis l'APK physique) ; les tests JVM ont été forcés avec
`--rerun-tasks` et réellement rejoués. Le backend a été rejoué intégralement.

Vérifications non rejouées après fusion, avec justification :

- **Plugin OpenClaw** : 41/41 tests, build et validation exécutés avant fusion
  (`fcbe8c3`) ; le code du plugin est inchangé et `node_modules` n'est pas
  versionné dans le checkout principal. Non réinstallé, non activé.
- **Tests instrumentés** : 4/4 PASS sur émulateur API 37 avant fusion ; aucune
  source Android n'a changé depuis. La validation physique de référence reste
  R1 sur Pixel.
- **P4/P5** : non approuvés, donc non exécutés.

## 4. Parcours physique R1 — résultat

Appareil : **Pixel 9 Pro XL, Android 17 / API 37**, physique.

| Étape | Résultat |
|---|---|
| Installation APK `6ab95fac` après accord P1ter | PASS — `adb install -r` Success, SHA relu identique, données conservées |
| Plan local | PASS — plan v4, 3 activités, persistance après fermeture/réouverture |
| Coach OpenClaw | PASS — proposition réelle, diff v3→v4, confirmation groupée, version 4 persistée |
| Idempotence | PASS — rejeu de la même enveloppe sans seconde application |
| Hors-ligne | PASS — backend arrêté, plan lisible, actions locales disponibles |
| Lecture calendrier P3 | PASS après correctif — consentement, `READ_CALENDAR`, Etar détectée, 4 calendriers expurgés, 0 motif e-mail, aucun événement, aucune écriture |
| Révocation | PASS — consentement révoqué, permission `granted=false`, liste masquée |
| État final | backend arrêté, port 8010 libre, `adb reverse` retiré, aucun changement OpenClaw, aucune écriture calendrier/wger, aucune désinstallation/`pm clear` |

Limites physiques : `WRITE_CALENDAR` non déclaré, aucune écriture Etar, aucune
activation du plugin OpenClaw, aucune écriture wger, aucun test relay/LTE. Ces
éléments sont des portes non approuvées, pas des résultats manquants.

## 5. Sécurité, vie privée et autorisations

Autorisations effectivement utilisées :

1. P1/P2 — le parent conduit le parcours Pixel ; installation et configuration.
2. P3 — `READ_CALENDAR` après le plan, en lecture seule.
3. Installation de l'APK `66c7d8f6…` après accord par SHA.
4. Installation de l'APK `6ab95fac…` après accord P1ter.
5. Merge local sur `main`, explicitement demandé ; **aucun push**.

Contrôles :

- **Secrets** : `.env` local en `0600`, non suivi ; stockages de jetons `0600` et
  écriture atomique ; aucun secret dans les logs, preuves, fixtures ou commits.
  La clé Bridge est fournie par `${BRIDGE_API_KEY}`, jamais dans le manifeste.
- **Proposition IA ≠ permission** : le planificateur n'a aucun outil ; une
  révision exige `plan_context_consented=true`, la capability `plan_revision`,
  puis la confirmation groupée Android. Le backend pré-valide ; Android
  recalcule diff, hash et effets.
- **Réseau** : URL release HTTPS uniquement ; cleartext debug limité à
  `10.0.2.2`, `127.0.0.1`, `localhost` ; redirections refusées ; réponses
  bornées.
- **Calendrier** : noms de calendriers identifiants de compte masqués
  (« Calendrier expurgé »), compte expurgé, aucune donnée d'événement, aucune
  écriture.
- **Aucun** `AccessibilityService`, root, Device Owner, kiosque, installation
  silencieuse, shell privilégié ni manipulation d'APK tierce.
- La pause d'urgence, la désactivation du suivi et la sortie vers le launcher
  d'origine restent disponibles hors ligne (comportement V6.2 conservé).

## 6. Portes non franchies et travaux restants

| Porte / sujet | État | Condition de reprise |
|---|---|---|
| P4 — `WRITE_CALENDAR`, écriture Etar réelle | **NON APPROUVÉ / NON EXÉCUTÉ** | Accord ciblé, calendrier local inscriptible qualifié, sauvegarde, tests de création/déplacement/suppression et anti-doublon |
| P5 — activation plugin OpenClaw, politique planificateur, redémarrage | **NON APPROUVÉ / `BLOCKED_POLICY`** | Accord ciblé ; plan `OPENCLAW_ACTIVATION_PLAN.md` ; le planificateur garde `tools.deny:["*"]` |
| Écritures wger | **NON IMPLÉMENTÉ / NON APPROUVÉ** | Nouveau ticket, consentement et sauvegarde |
| Relay/LTE, Noise côté Android | **`BLOCKED_INFRA`** | Instance dédiée, TLS, `minimumMode:"protected"`, bibliothèque Kotlin qualifiée, tests d'interopérabilité |
| Push / PR / fusion distante | **NON FAIT** | Accord explicite ; `origin/main` reste `ab477ef` |
| Recherche Bridge | **`PROMPT_INSTRUCTED_UNVERIFIED`** | Ne jamais la présenter comme une recherche web vérifiée |
| `READ_CALENDAR` | Révoqué en fin de R1 | Ré-octroi explicite nécessaire pour un futur test calendrier |
| P2 UI mineur | Texte d'aide `PlanScreen` « Plus > Partage backend » | Le chemin réel est « Plus > Diagnostic avancé > Configurer le partage backend » ; aucun impact fonctionnel |

## 7. Index des preuves

- `docs/v6-3/REGISTRE_TACHES.md` — registre et journal complets.
- `docs/v6-3/preuves/reprise-r0.txt` — pré-vol et baseline.
- `docs/v6-3/preuves/l1-03-home-next.txt`, `l1-03-access-audit.txt`.
- `docs/v6-3/preuves/l3-01a-plan-revision.txt`,
  `l3-01b-android-plan-revision.txt`, `l3-01c-plan-ui-wiring.txt`.
- `docs/v6-3/preuves/l2-02-calendar-read-prep.txt`.
- `docs/v6-3/preuves/l5-01-wger.txt`.
- `docs/v6-3/preuves/r1-physical.txt`.
- `docs/v6-3/R1_PLAN_PHYSIQUE.md`, `R1_RUNBOOK.md`, `AUTORISATIONS_R1.md`.
- `docs/v6-3/AUDIT_BRIDGE_PLUGIN.md`, `AUDIT_TRANSPORT_PASEO.md`,
  `AUDIT_CALENDAR_WRITE.md`.
- `docs/v6-3/OPENCLAW_ACTIVATION_PLAN.md`, `HANDOFF_R1.md`.
- `docs/DECISIONS_AUTOMATIQUES.md`.
- `integrations/openclaw/atlas_chatgpt_research/VALIDATION.md`.
- `docs/v6-3/RAPPORT_V63.md` — rapport du premier incrément, antérieur à R1.

## 8. Verdict

- Campagne V6.3 de reprise : **fusionnée localement sur `main`**, sans push.
- Périmètre livré : **GO_WITH_LIMITATIONS**, aucun P0/P1 ouvert dans le
  périmètre livré.
- Les portes P4/P5, les écritures wger, le relay/LTE et toute opération distante
  restent explicitement fermées ; aucune n'est revendiquée comme testée.
- Le code Android de `main` est celui de l'APK `6ab95fac` validé sur Pixel ; le
  backend, les tests JVM et le lint ont été vérifiés sur `main`.
