# ATLAS V6.3 — R2 — Revue d'intégration de la vague B

Date : 2026-10-26. Relecture parent sur les diffs, corrections d'intégration et
oracles rejoués sur le SHA intégré. Les trois auteurs ont livré un commit local
propre ; aucun n'a pu exécuter Gradle (sandbox Codex), donc la compilation et
les tests ont été faits par le parent.

## 1. R2-PLAN-UI-01 — refonte Plan du jour

| Élément | Valeur |
|---|---|
| Commit auteur | `e7c79f52ac2f468d023833624fca8ffe2e2172e3` |
| Scope | `PlanScreen.kt`, `PlanUiComponents.kt`, 2 tests neufs |
| Intégration | merge local |
| Oracle parent | JVM complet 546 PASS ; androidTest 17/17 PASS |

Constats :

- en-tête court, date, progression, action principale contextuelle ;
- liste chronologique compacte, prochaine activité mise en avant, glyphes
  ✓ / ● / ○ + libellés ;
- menu « Plus » par ligne, suppression confirmée ;
- détails techniques dans un dialogue ;
- différentiel coach complet (résumé, version, nombre d'effets, changements,
  chevauchements, confirmation groupée) ;
- aucun scroll racine supplémentaire.

Corrections parent :

- test d'absence `assertDoesNotExist` non disponible dans cette version de
  Compose Test → `onAllNodesWithText(...).assertCountEquals(0)` ;
- assertions du dialogue coach alignées sur le préfixe « • » et le contenu
  scrollable ;
- test de scroll R1 mis à jour : l'en-tête est désormais « Aujourd'hui », le
  titre « Plan du jour » n'apparaît qu'une fois ;
- couleur du repère « à venir » alignée sur la palette ATLAS (plus de
  `tertiary` Material par défaut).

Verdict : **ACCEPTED**.

## 2. R2-HABITS-UI-01 — Habitudes en trois espaces

| Élément | Valeur |
|---|---|
| Commit auteur | `b21b2e4216a6c5949727ed33a660ece54c73b391` |
| Scope | `AtlasScreens.kt` (retrait de l'ancien bloc) + 5 fichiers neufs + 2 tests |
| Intégration | merge local |
| Oracle parent | JVM 546 PASS ; 17/17 instrumentés PASS |

Constats :

- liste du jour, détail et éditeur séparés ;
- formulaire de création retiré du-dessus de la liste ;
- grille d'historique 7/30 jours avec ✓ / ! / – / · / › et légende ;
- progression réelle, séries, régularité, rappel ;
- archivage confirmé et historique conservé ;
- signature `HabitsScreen(controller, revision, onExport)` conservée.

Corrections parent :

- **P1** — `HabitsScreen` réintroduisait un `verticalScroll` racine dans
  `AtlasScreenFrame` (même classe de crash que R1). Retiré avant acceptation ;
- imports `androidx.compose.foundation.layout.weight` invalides retirés ;
- `@Composable` dupliqué laissé par l'extraction corrigé ;
- bouton « Archiver » rendu outlined + couleur d'erreur (action destructive) ;
- case « à faire aujourd'hui » alignée sur le rôle sémantique warning au lieu
  du `tertiary` Material par défaut ;
- test instrumenté de grille réécrit avec un historique synthétique : une
  habitude créée le jour même ne doit pas afficher de jours manqués
  antérieurs ; le test précédent confondait date de création et jour manqué.

Verdict : **ACCEPTED** après corrections P1/P2.

## 3. R2-MEMORY-01 — noyau mémoire local

| Élément | Valeur |
|---|---|
| Commit auteur | `5744eeea3dcaa4fcf932ddc81c0972606e915353` |
| Scope | 4 fichiers main + 1 test, package `memory/**` neuf |
| Intégration | merge local |
| Oracle parent | 15 tests mémoire PASS ; suite JVM 546 PASS |

Constats :

- catégories, provenance, confirmation explicite, correction, suppression,
  révocation, expiration, rétention ;
- une observation seule n'est pas promue ; une hypothèse reste non confirmée ;
- sélection de contexte bornée, filtre les révoqués/expirés et les faits
  sensibles sans consentement de catégorie ;
- codec JSON versionné et backend injectable ;
- aucune donnée personnelle, aucun accès Android.

Limites / P2 :

- un backend corrompu au chargement n'est pas mis en quarantaine : le codec
  lève une exception. À traiter avant un branchement sur un stockage réel ;
- aucune persistance Android ni UI n'est branchée en R2 ;
- les durées 90 j (plans/résultats) et 30 j (usage détaillé) sont exposées dans
  la politique mais ne s'appliquent pas aux catégories `MemoryFact`.

Verdict : **ACCEPTED_WITH_P2** ; branchement Android ultérieur.

## 4. Points transverses

- Aucun P0.
- Un P1 (scroll imbriqué Habitudes) a été détecté et corrigé avant acceptation.
- Deux défauts de compilation (annotation dupliquée, imports `weight`) et un
  clash JVM UI (vague A) ont été corrigés par le parent.
- Aucun secret, aucune donnée personnelle, aucun ADB côté enfants.
- Tests instrumentés : 17/17 sur `emulator-5554` API 37.
