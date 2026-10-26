# ATLAS V6.3 — Matrice de recette R2 — état final

Statuts : `PASS`, `FAIL`, `BLOCKED`, `NOT_RUN`, `PARTIAL`.
Environnement cible : `emulator-5554`, Android 17 / API 37.2, 1344×2992 @480.
Le Pixel physique est **hors périmètre** et n'a pas été sollicité.

Références : `preuves/gates-finales.txt`, `preuves/instrumented.txt`,
`preuves/ui-after.txt`, `AUDIT_UI_AVANT.md`, `AUDIT_UI_APRES.md`.

## 1. Gates techniques

| ID | Gate | Commande | Statut |
|---|---|---|---|
| T1 | Backend complet | `cd backend && .venv/bin/python -m pytest -q` | **PASS** — 178 passed, 1 skipped |
| T2 | JVM Android complet | `./gradlew :app:testDebugUnitTest --rerun-tasks --max-workers=1` | **PASS** — 77 suites, 546 tests, 0 échec |
| T3 | Lint | `./gradlew :app:lintDebug` | **PASS** — 0 erreur, 54 avertissements |
| T4 | Build debug | `./gradlew :app:assembleDebug` | **PASS** — APK `cc88e649…` |
| T5 | Compilation androidTest | `./gradlew :app:assembleDebugAndroidTest` | **PASS** |
| T6 | Tests instrumentés API 37 | `am instrument` (17 tests ciblés) | **PASS** — 17/17 |
| T7 | Plugin OpenClaw | `npm test`, build, validate | **PASS** — 50 tests, build/validate PASS |
| T8 | APK = SHA source annoncé | `sha256sum` + `dumpsys package` + pull | **PASS** — `cc88e649…` identique sur l'appareil |

## 2. Critères UX mesurables (mission §14)

| # | Critère | Avant | Après | Statut |
|---|---|---|---|---|
| 1 | Action principale visible sans paragraphe | 2 CTA concurrents | 1 CTA contextuel | **PASS** |
| 2 | Trois informations utiles immédiates | date/version/stockage | date, progression, prochaine activité | **PASS** |
| 3 | Détails techniques non dominants | permanents | dialogue Détails | **PASS** |
| 4 | Actions secondaires non toutes visibles | 4 par activité | menu Plus | **PASS** |
| 5 | Confirmation des actions dangereuses | suppression confirmée, archivage ambigu | suppression + archivage confirmés, style erreur | **PASS** |
| 6 | État vide indique quoi faire | verbeux | concis + action | **PASS** |
| 7 | Chargement/erreur explicites | succès neutre | corruption, sync, permission visibles | **PASS** |
| 8 | Couleurs cohérentes et accessibles | 3 palettes | 4 accents × 3 bases, AA | **PASS** |
| 9 | Statut compréhensible en niveaux de gris | texte + barre | glyphes + texte + formes | **PASS** |
| 10 | Police 2× | non testé | tests instrumentés Plan + Habitudes | **PASS** |
| 11 | Cibles ≥ 48 dp | la plupart | boutons, menus, cases | **PASS** |
| 12 | Lecteur d'écran | partiel | contentDescriptions vérifiées | **PASS partiel** — TalkBack matériel non testé |
| 13 | Pas de crash / scroll imbriqué | corrigé R1 | scroll unique rétabli, 17 tests | **PASS** |
| 14 | Thèmes/palettes persistants | thèmes oui | thèmes + accents après redémarrage | **PASS** |
| 15 | Données stockées lisibles après refonte | plan v4, habitudes | données pré-R2 (1 habitude) lues par l'APK final | **PASS** |

## 3. Habitudes — 16 cas obligatoires

| # | Cas | Statut |
|---|---|---|
| H1 | Aucune habitude | **PASS** — test instrumenté + capture |
| H2 | Une habitude quotidienne | **PASS** — état pré-R2 |
| H3 | Cinq habitudes | **PASS** — test + capture |
| H4 | Quota hebdomadaire | **PASS** — fixture « Appel famille » |
| H5 | Jour non planifié | **PASS** — libellé + légende |
| H6 | Validation d'aujourd'hui | **PASS** — test |
| H7 | Annulation de validation | **PASS** — test |
| H8 | Habitude archivée | **PASS** — confirmation + test |
| H9 | Historique traversant un changement de mois | **PASS** — historique 30 j (septembre/octobre) |
| H10 | Changement de fuseau | **NOT_RUN** |
| H11 | Rotation | **PASS** — capture paysage |
| H12 | Police 2× | **PASS** — test instrumenté |
| H13 | Thème clair/sombre/AMOLED | **PASS** — captures |
| H14 | Couleurs d'accent | **PASS** — captures |
| H15 | Persistance après redémarrage | **PASS** — app + émulateur redémarrés |
| H16 | Export/sauvegarde préexistante | **PASS** — données pré-R2 restaurées et lues; export CSV accessible |

## 4. Plan du jour

| # | Cas | Statut |
|---|---|---|
| P1 | Plan sans document | **PASS** |
| P2 | Plan vide (0 activité) | **PASS** |
| P3 | Plan 3 activités, ordre horaire | **PASS** |
| P4 | Passé/en cours/à venir sans couleur | **PASS** — glyphes + texte |
| P5 | Prochaine activité mise en évidence | **PASS** |
| P6 | Menu Plus secondaire par ligne | **PASS** — test |
| P7 | Suppression confirmée, terminée non supprimable | **PASS** — test |
| P8 | Diff coach : création, déplacement, modification, suppression, conflit | **PASS** — test 5 types + aperçu réel add/move |
| P9 | Confirmation groupée exacte, hashes/version/effets | **PASS** — version 4→5, 3 effets |
| P10 | Refus/abandon de confirmation | **PASS partiel** — abandon UI exercé; pas de test dédié |
| P11 | Clavier ouvert, champs accessibles | **NOT_RUN** |
| P12 | Persistance fermeture/réouverture | **PASS** |
| P13 | Hors ligne | **NOT_RUN** pour R2 (validé R1) |
| P14 | Détails techniques à la demande | **PASS** |

## 5. Thèmes et palettes

| # | Cas | Statut |
|---|---|---|
| C1 | Sombre | **PASS** |
| C2 | AMOLED (fond noir pur) | **PASS** |
| C3 | Suivre le système | **PASS** — nuit clair/sombre |
| C4 | Clair | **PASS** |
| C5 | Accent ATLAS classique | **PASS** |
| C6 | Accent Forêt | **PASS** |
| C7 | Accent Océan | **PASS** |
| C8 | Accent Ardoise | **PASS** |
| C9 | Couleur dynamique Android | **NOT_RUN** — option visible, non activée pour éviter un écrasement AMOLED |
| C10 | Persistance après redémarrage | **PASS** |
| C11 | Rotation | **PASS** |
| C12 | Police 2× | **PASS** |
| C13 | Contraste JVM 4 modes × accents | **PASS** — tests |
| C14 | États actif/refusé/désactivé/erreur | **PASS** |

## 6. Calendrier — oracle provider simulé

| # | Cas | Statut |
|---|---|---|
| K1–K16 | Insertion, modification, suppression, doublon, crash avant/après, UNKNOWN, marqueur, mauvais calendrier, permission, DST, rejeu, MISSING, FOREIGN, récurrence, DIVERGED | **PASS** — 18 tests JVM |
| K17 | CalendarContract réel sur émulateur | **BLOCKED_ENVIRONMENT** — aucun calendrier local |
| K18 | Visibilité Etar réelle | **BLOCKED_ENVIRONMENT** — Etar non installée |
| — | Diagnostic d'écriture désactivée par défaut | **PASS** — capture, WRITE_CALENDAR non accordée |

## 7. Sport

| # | Cas | Statut |
|---|---|---|
| S1 | Fixture synthétique affichée | **PASS** |
| S2 | Séance du jour, exercices, séries/répétitions | **PASS** |
| S3 | Distinction prévu / effectué | **PASS** |
| S4 | Service indisponible → état dégradé | **NOT_RUN** (UI lit une fixture; les états sont modélisés) |
| S5 | Aucune écriture wger | **PASS par construction** |
| S6 | Séance planifiée visible dans le DayPlan | **PARTIAL** — l'activité TRAINING est visible dans le plan; la référence wger n'est pas persistée dans `trainingRef` et le lien automatique reste à faire |

## 8. Mémoire et usage

| # | Cas | Statut |
|---|---|---|
| M1–M3 | Faits/provenance, hypothèse, révocation | **PASS** — 15 tests JVM |
| U1–U4 | Agrégation, permission refusée, consentement, quotas | **PASS (JVM)** — 17 tests synthétiques ; Usage Access réel **NOT_RUN** |

## 9. Bridge / OpenClaw

| # | Cas | Statut |
|---|---|---|
| B1–B3 | Tests plugin, faux Bridge, un seul outil | **PASS** — 50 tests, 16 recettes, build/validate |
| B4 | Activation réelle | **BLOCKED_POLICY** — non autorisée ; aucun Bridge/Gateway réel |
| B5 | Vulnérabilités npm | **P2** — 10 signalées (2 critiques) dans l'arbre installé; à revoir avant activation |

## 10. Accessibilité

| # | Cas | Statut |
|---|---|---|
| A1 | TalkBack matériel | **NOT_RUN** |
| A2 | Cibles ≥ 48 dp | **PASS** — assertions instrumentées |
| A3 | Statut non porté par la couleur seule | **PASS** |
| A4 | Police 2× | **PASS** |
| A5 | Rotation | **PASS** |
| A6 | Contraste | **PASS** — JVM + captures |
