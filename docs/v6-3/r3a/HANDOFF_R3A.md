# ATLAS V6.3 R3-A — Handoff

Statut : **campagne locale, GO_WITH_LIMITATIONS sous réserve de la cinquième
revue indépendante**. Aucun push, PR, merge distant ni déploiement public n'a
été effectué.

## 1. Reprise immédiate

| Élément | Valeur |
|---|---|
| Branche de campagne | `feat/atlas-v6-3-r3a-relay-calendar` |
| Base `main` vérifiée | `6a22c12bc5a2c63c3d3ab56253679c897fadb75d` |
| SHA des contrats | `4f7b9263c76986c0a410f87a22268993568b3646` |
| Dernier SHA de référence | `c45b64f3543453c0887eb00a5e29b0ad239e7c2a` (commits R3-A locaux) |
| APK final | `8ad8d2d53de6794894219b13ca62641c59e96ffd4966f52c69309f2f4cfb4c8c` |
| Registre | `docs/v6-3/r3a/REGISTRE_R3A.md` |
| Décisions | `docs/v6-3/r3a/DECISIONS_ARCHITECTURE.md` |
| Transport | `docs/v6-3/r3a/TRANSPORT_PASEO.md` |
| Calendrier | `docs/v6-3/r3a/CALENDRIER_ETAR.md` |
| Matrice | `docs/v6-3/r3a/MATRICE_RECETTE.md` |
| Sécurité | `docs/v6-3/r3a/PREUVES_SECURITE.md` |
| Déploiement | `docs/v6-3/r3a/DEPLOIEMENT_DISTANT.md` |
| Revue | `docs/v6-3/r3a/REVUE_INDEPENDANTE_R3A.md` |
| Tickets | `docs/v6-3/r3a/tickets/**` |
| Preuves | `docs/v6-3/r3a/preuves/**` |

## 2. Environnement vérifié

- `emulator-5554`, Android 17 / API 37, AVD `Pixel_9_Pro_XL_2`, headless.
- Etar `ws.xsoh.etar` 1.0.56, calendrier local `ATLAS R3A TEST`.
- JDK 21, Gradle wrapper 9.6.0, Node 24, Python 3.12, Docker 27.
- Relais Paseo upstream `3fc41c96…`, Paseo `902b0c7…`.
- Pixel physique : **non disponible, non touché**.
- Sauvegardes : snapshot AVD `atlas-r3a-pre`, données app
  `~/.local/share/atlas-v63-r3a/backups/atlas-app-data-pre-r3a.tar`.

## 3. Ce qui est réellement prouvé

### Transport

- Android Kotlin → relais Paseo v2 local → Noise `protected` → connecteur Node
  → FastAPI loopback → réponse → Android, sur `emulator-5554` via
  `10.0.2.2:4400`, **sans `adb reverse`**.
- Révision `DayPlan` réelle : proposition V3 déterministe, base_version 4,
  rejeu idempotent (`replayed=true`), cache de résultat.
- Coupure du relais → `OFFLINE` ; relais rétabli → reconnexion et nouvelle
  révision ; redémarrage du connecteur PC → reconnexion Android valide.
- `AtlasController.planRevisionClient()` en mode `RELAY` obtient une proposition
  via le relais (mandat/capacités réels).
- Écran de connexion : `LOCAL`, `RELAY_CONNECTED`, `OFFLINE`, identité masquée,
  dernière connexion, révocation locale, sélection direct/relais/hors ligne.
- Preuves : `preuves/e2e-emulator.txt`, `preuves/instrumented.txt`,
  `preuves/pc-live-fastapi.txt`, captures `relay-settings-*.png`.

### Calendrier

- Création légitime du calendrier local via authentificateur + sync adapter
  (aucune insertion SQL directe), compte synthétique `atlas-r3a-test`.
- Parcours UI réel : plan approuvé v2 → confirmation groupée « 1 effet » →
  événement CalendarContract → relecture → visible dans Etar.
- Déplacement v3 et suppression v4 via le même parcours, même `event_id`,
  sans doublon, disparition visible dans Etar.
- Journal et liens durables, reprise après redémarrage, crash injecté sans
  doublon, révocation externe de `WRITE_CALENDAR`, refus fail-closed.
- Modification manuelle réelle dans l'UI Etar → `DIVERGED` /
  `READ_BACK_MISMATCH`.
- Preuves : `preuves/calendar-*.txt`, `preuves/instrumented.txt`, captures
  `calendar-confirm-*.png`, `etar-ui-*.png`.

### Sport

- Route FastAPI `/v1/sport/plan` en lecture seule, contrat `sport-plan-v1`,
  refus de `jwt_refresh`, absence de secret.
- Client Android borné, états `FRESH/STALE/UNAVAILABLE/MISMATCH`, écran réel
  avec `UNAVAILABLE` sans invention, fixture uniquement en mode démo explicite.
- `trainingRef` optionnel, rétrocompatible et préservé par les révisions.
- Preuve : `preuves/sport-ui.txt`, captures `sport-*.png`.

### Mémoire et usage global

- Mémoire locale durable : trois préférences synthétiques avec consentement
  explicite, affichage des métadonnées, persistance après `force-stop` et
  relance. Capture `memory-preference-final.png`.
- Usage Access réel sur `emulator-5554` : `GET_USAGE_STATS: allow`, consentement
  séparé avec catégorie `package_names`, résumé final de 6 événements réels
  (ATLAS, Etar, Pixel Launcher), révocation et purge, persistance après
  redémarrage.
- Quota quotidien configurable et persisté (90 min dans la recette), validation
  1–1440 sans crash ; affiché `UNKNOWN` tant que la fenêtre 24 h n'est pas
  pleinement observée. La fenêtre hebdomadaire est prise en charge par
  l'observateur jusqu'à 7 jours et couverte en JVM.
- Preuves : `preuves/memory-ui.txt`, `preuves/usage-global-ui.txt`.

### Intégrations C

- Le plugin Bridge optionnel conserve ses 50 tests PASS ; il n'a été ni installé
  ni activé, et le Gateway personnel n'a pas été modifié.

## 4. Ce qui n'est PAS prouvé

| Élément | Statut |
|---|---|
| Pixel physique Android 17 | **BLOCKED_HARDWARE** — aucun appareil |
| LTE/5G, Internet, 24 h/24 | **BLOCKED_INFRA** — aucun déploiement |
| Relais public, TLS/WSS réel | **NOT_RUN** — procédure seulement |
| wger réel en lecture | **NOT_RUN** — pas de credentials utilisés |
| Usage Access réel sur Pixel | **NOT_RUN** — émulateur qualifié seulement |
| Quotas d'usage configurés | **NOT_RUN / PARTIAL** — aucun quota réel, évaluateur JVM |
| SIGKILL réel pendant une écriture | **PARTIAL** — crash injecté |
| Événement calendrier étranger sur émulateur | **PASS JVM**, non rejoué provider réel |
| Suppression/indisponibilité du calendrier | **PASS JVM**, non rejoué provider réel |
| TalkBack matériel | **NOT_RUN** |
| Défaut de sécurité npm du Bridge | **non corrigé**, plugin non activé |
| Rotation/supervision 24 h/24 du connecteur | procédure seulement |

## 5. Gates finales locales

| Gate | Résultat |
|---|---|
| Backend `pytest -q` | **187 passed, 1 skipped** |
| Android JVM | **91 suites, 651 tests, 0 échec** |
| Lint | **PASS** (0 erreur, 70 avertissements) |
| `assembleDebug` / `assembleDebugAndroidTest` | **PASS** |
| Instrumentés de référence | **17/17 R2 + 4/4 calendrier + 1/1 E2E relais + 1/1 offline + 1/1 app controller + 4/4 lifecycle + 1/1 authenticator = 29** |
| Relay Node | **33 tests PASS** + relais réel + live FastAPI |
| Bridge plugin (non activé) | **50 tests PASS** |
| Parcours mémoire / usage | **PASS** (quota configuré et persisté ; évaluation effective PARTIAL) |
| APK final | SHA-256 `8ad8d2d53de6794894219b13ca62641c59e96ffd4966f52c69309f2f4cfb4c8c` |

Détail : `preuves/gates-final.txt`, `preuves/PROVENANCE.md`.

## 6. Prochaines actions

1. Lire la revue indépendante et traiter tout P0/P1.
2. Exécuter `DEPLOIEMENT_DISTANT.md` après autorisation d'hébergement/TLS.
3. Recette Pixel physique selon la section 16 du document de déploiement.
4. Configurer un `WGER_API_TOKEN` en lecture seule pour la recette wger réelle.
5. Qualifier Usage Access réel sur Pixel et TalkBack.
6. Revoir les vulnérabilités npm du Bridge avant toute activation.
7. Ne pas pousser la branche sans accord explicite.

## 7. Sessions Paseo

| Ticket | Session | Modèle effectif |
|---|---|---|
| R3-RELAY-PC-01 | `b879770c…` | `codex/gpt-6-luna/xhigh` |
| R3-RELAY-ANDROID-01 | `7697f6dd…` | `codex/gpt-6-luna/xhigh` |
| R3-CALENDAR-01 | `cfb46cd6…` | `codex/gpt-6-luna/xhigh` |
| R3-SPORT-WIRE-01 | `d2ad8ba9…` | `codex/gpt-6-luna/xhigh` |
| R3-CALENDAR-ETAR-01 | `44871d16…` | `codex/gpt-6-luna/xhigh` |
| R3-MEMORY-UI-01 | `89bbab64…` | `codex/gpt-6-luna/xhigh` |
| R3-USAGE-UI-01 | `33b6c3b3…` | `codex/gpt-6-luna/xhigh` |
| Revue indépendante (1) | `c3f97221…` | `codex/gpt-6-luna/xhigh` |
| Re-revue indépendante (2) | `d87dec39…` (NO_GO sur `e28b7a9`) | `codex/gpt-6-luna/xhigh` |
| Re-revue indépendante (3) | `ebb8d049…` (NO_GO sur `d89a626`) | `codex/gpt-6-luna/xhigh` |
| Re-revue indépendante (4) | `17a3189e…` (NO_GO sur `3811ea5`) | `codex/gpt-6-luna/xhigh` |
| Re-revue indépendante (5) | à lancer sur `c45b64f` | `codex/gpt-6-luna/xhigh` |

## 8. Verdict

Verdict local proposé : **GO_WITH_LIMITATIONS** pour la portée R3-A sur
émulateur, sous réserve de la cinquième revue indépendante sur `c45b64f`.
V6.3 reste **NO_GO** globalement tant que Pixel physique, accès Internet réel,
TLS/WSS public, recette LTE/5G et veille/Doze ne sont pas qualifiés. L'évaluation
effective des quotas et la supervision 24 h/24 restent partielles.
