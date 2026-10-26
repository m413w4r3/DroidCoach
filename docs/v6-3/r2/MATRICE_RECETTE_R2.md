# ATLAS V6.3 — Matrice de recette R2

Statuts : `PASS`, `FAIL`, `BLOCKED`, `NOT_RUN`. Chaque ligne exécutée doit
porter le SHA source, l'environnement, la commande/geste et une preuve.

Environnement cible : `emulator-5554`, Android 17 / API 37.2, 1344×2992 @480.
Le Pixel physique est **hors périmètre**.

## 1. Gates techniques

| ID | Gate | Commande | Statut |
|---|---|---|---|
| T1 | Backend complet | `cd backend && .venv/bin/python -m pytest -q` | NOT_RUN |
| T2 | JVM Android complet | `cd android && ./gradlew :app:testDebugUnitTest --rerun-tasks --max-workers=1` | NOT_RUN |
| T3 | Lint | `cd android && ./gradlew :app:lintDebug` | NOT_RUN |
| T4 | Build debug | `cd android && ./gradlew :app:assembleDebug` | NOT_RUN |
| T5 | Compilation androidTest | `cd android && ./gradlew :app:assembleDebugAndroidTest` | NOT_RUN |
| T6 | Tests instrumentés API 37 | `adb -s emulator-5554 shell am instrument ...` | NOT_RUN |
| T7 | Plugin OpenClaw | `npm test`, `npm run plugin:build`, `openclaw plugins validate` | NOT_RUN |
| T8 | APK = SHA source annoncé | `sha256sum` + `dumpsys package` | NOT_RUN |

## 2. Critères UX mesurables (mission §14)

| # | Critère | Avant | Après | Statut |
|---|---|---|---|---|
| 1 | Action principale visible sans paragraphe d'aide | 2 boutons pleine largeur concurrents | à évaluer | NOT_RUN |
| 2 | Trois informations utiles immédiatement identifiables | date/version/stockage occupent l'en-tête | à évaluer | NOT_RUN |
| 3 | Détails techniques non dominants | version/fuseau/stockage permanents | à évaluer | NOT_RUN |
| 4 | Actions secondaires non toutes visibles | 4 boutons par activité | à évaluer | NOT_RUN |
| 5 | Confirmation adéquate des actions dangereuses | suppression confirmée ; archivage ambigu | à évaluer | NOT_RUN |
| 6 | État vide indique clairement quoi faire | oui, verbeux | à évaluer | NOT_RUN |
| 7 | Chargement et erreur explicites | succès en carte neutre | à évaluer | NOT_RUN |
| 8 | Couleurs cohérentes et accessibles | 3 palettes testées AA | à évaluer | NOT_RUN |
| 9 | Statut compréhensible en niveaux de gris | texte + barre ; glyphes identiques | à évaluer | NOT_RUN |
| 10 | Fonctionne en police 2× | non testé | à évaluer | NOT_RUN |
| 11 | Cibles ≥ 48 dp | oui pour la plupart | à évaluer | NOT_RUN |
| 12 | Actions accessibles au lecteur d'écran | contentDescription partiels | à évaluer | NOT_RUN |
| 13 | Pas de crash Compose / scroll imbriqué | R1 corrigé | à évaluer | NOT_RUN |
| 14 | Thèmes et palettes persistent après redémarrage | thèmes oui, accents non | à évaluer | NOT_RUN |
| 15 | Données stockées lisibles après refonte | plan v4 et habitudes existants | à évaluer | NOT_RUN |

## 3. Habitudes — 16 cas obligatoires (mission §8.5)

| # | Cas | Statut |
|---|---|---|
| H1 | Aucune habitude | NOT_RUN |
| H2 | Une habitude quotidienne | NOT_RUN |
| H3 | Cinq habitudes | NOT_RUN |
| H4 | Quota hebdomadaire | NOT_RUN |
| H5 | Jour non planifié | NOT_RUN |
| H6 | Validation d'aujourd'hui | NOT_RUN |
| H7 | Annulation de validation | NOT_RUN |
| H8 | Habitude archivée | NOT_RUN |
| H9 | Historique traversant un changement de mois | NOT_RUN |
| H10 | Changement de fuseau | NOT_RUN |
| H11 | Rotation | NOT_RUN |
| H12 | Police 2× | NOT_RUN |
| H13 | Thème clair/sombre/AMOLED | NOT_RUN |
| H14 | Couleurs d'accent | NOT_RUN |
| H15 | Persistance après redémarrage | NOT_RUN |
| H16 | Export ou sauvegarde préexistante non dégradée | NOT_RUN |

## 4. Plan du jour

| # | Cas | Statut |
|---|---|---|
| P1 | Plan sans document | NOT_RUN |
| P2 | Plan vide (0 activité) | NOT_RUN |
| P3 | Plan 3 activités, ordre horaire | NOT_RUN |
| P4 | Activité passée / en cours / à venir distinguables sans couleur | NOT_RUN |
| P5 | Prochaine activité mise en évidence | NOT_RUN |
| P6 | Menu « Plus » secondaire par ligne | NOT_RUN |
| P7 | Suppression confirmée, activité terminée non supprimable | NOT_RUN |
| P8 | Diff coach : création, déplacement, modification, suppression, conflit | NOT_RUN |
| P9 | Confirmation groupée exacte, hashes/version/effets conservés | NOT_RUN |
| P10 | Refus de confirmation, aperçu abandonné | NOT_RUN |
| P11 | Clavier ouvert, tous les champs accessibles | NOT_RUN |
| P12 | Persistance fermeture/réouverture | NOT_RUN |
| P13 | Hors ligne | NOT_RUN |
| P14 | Détails techniques à la demande | NOT_RUN |

## 5. Thèmes et palettes

| # | Cas | Statut |
|---|---|---|
| C1 | Sombre | NOT_RUN |
| C2 | AMOLED (fond noir pur) | NOT_RUN |
| C3 | Suivre le système | NOT_RUN |
| C4 | Clair | NOT_RUN |
| C5 | Accent ATLAS classique | NOT_RUN |
| C6 | Accent Forêt | NOT_RUN |
| C7 | Accent Océan | NOT_RUN |
| C8 | Accent Ardoise | NOT_RUN |
| C9 | Couleur dynamique Android (si implémentée) | NOT_RUN |
| C10 | Persistance après redémarrage | NOT_RUN |
| C11 | Rotation | NOT_RUN |
| C12 | Police 2× | NOT_RUN |
| C13 | Contraste JVM des 4 modes × accents | NOT_RUN |
| C14 | États actif/refusé/désactivé/erreur | NOT_RUN |

## 6. Calendrier — oracle provider simulé (mission §9.5)

| # | Cas | Statut |
|---|---|---|
| K1 | Insertion | NOT_RUN |
| K2 | Modification | NOT_RUN |
| K3 | Suppression | NOT_RUN |
| K4 | Doublon / URI dupliquée | NOT_RUN |
| K5 | Crash avant effet | NOT_RUN |
| K6 | Crash après effet | NOT_RUN |
| K7 | Réponse incertaine → `UNKNOWN` sans retry | NOT_RUN |
| K8 | Marqueur manquant → `PROVENANCE_AMBIGUOUS` | NOT_RUN |
| K9 | Mauvais calendrier | NOT_RUN |
| K10 | Permission retirée | NOT_RUN |
| K11 | Heure d'été/hiver | NOT_RUN |
| K12 | Rejeu d'une confirmation | NOT_RUN |
| K13 | Activité supprimée → `MISSING`, pas de recréation | NOT_RUN |
| K14 | Événement étranger jamais modifié | NOT_RUN |
| K15 | Récurrence refusée | NOT_RUN |
| K16 | Read-back divergent → `DIVERGED` | NOT_RUN |
| K17 | CalendarContract réel sur émulateur | BLOCKED_ENVIRONMENT (aucun calendrier local initial) |
| K18 | Visibilité Etar réelle | BLOCKED_ENVIRONMENT (Etar non installé) |

## 7. Sport

| # | Cas | Statut |
|---|---|---|
| S1 | Fixture synthétique affichée | NOT_RUN |
| S2 | Séance du jour, exercices, séries/répétitions | NOT_RUN |
| S3 | Distinction prévu / effectué | NOT_RUN |
| S4 | Service indisponible → état dégradé explicite | NOT_RUN |
| S5 | Aucune écriture wger | PASS par construction (lecture seule) |
| S6 | Séance planifiée visible dans le Plan | NOT_RUN |

## 8. Mémoire et usage

| # | Cas | Statut |
|---|---|---|
| M1 | Faits, provenance, catégorie, validation | NOT_RUN |
| M2 | Hypothèse non confirmée expire | NOT_RUN |
| M3 | Révocation exclut du contexte | NOT_RUN |
| U1 | Agrégation synthétique `UsageSummary` | NOT_RUN |
| U2 | Permission refusée | NOT_RUN |
| U3 | Consentement révocable | NOT_RUN |
| U4 | Quotas/fenêtres et pause d'urgence | NOT_RUN |

## 9. Bridge / OpenClaw

| # | Cas | Statut |
|---|---|---|
| B1 | Tests plugin, build, validate | NOT_RUN |
| B2 | Faux Bridge : 401, 429, timeout, JSON invalide, injection | NOT_RUN |
| B3 | Aucun outil de mutation déclaré | NOT_RUN |
| B4 | Activation réelle | BLOCKED_POLICY (non autorisée) |

## 10. Accessibilité

| # | Cas | Statut |
|---|---|---|
| A1 | TalkBack : noms et rôles des actions principales | NOT_RUN |
| A2 | Cibles ≥ 48 dp mesurées | NOT_RUN |
| A3 | Statut non porté par la couleur seule | NOT_RUN |
| A4 | Police 2× sans perte d'action | NOT_RUN |
| A5 | Rotation sans perte d'état | NOT_RUN |
| A6 | Contraste des paires texte/fond | NOT_RUN |
