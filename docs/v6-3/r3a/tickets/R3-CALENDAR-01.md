# R3-CALENDAR-01 — Journal calendrier durable

| Clé | Valeur |
|---|---|
| TASK_ID / statut | `R3-CALENDAR-01 / READY` |
| Objectif | Rendre le moteur d'écriture R2 durable et réconciliable : journal persistant, liens persistants, reprise après crash, états `UNKNOWN`, divergence, révocation, aucun doublon |
| Dépendances | `R3-PREFLIGHT-01` ; moteur R2 intégré |
| BASE_SHA | à remplir par le parent au lancement |
| Profil exigé | `codex:gpt-6-luna` / `xhigh` |
| Worktree / accès | worktree dédié, écriture limitée au scope |
| Lire | `docs/v6-3/CONTRATS_V63.md` §3.4, `docs/v6-3/r3a/CALENDRIER_ETAR.md`, `docs/v6-3/r3a/DECISIONS_ARCHITECTURE.md` D6, tout `calendar/write/**` |
| Écrire | `android/app/src/main/java/dev/atlas/coach/calendar/write/**` et `android/app/src/test/java/dev/atlas/coach/calendar/write/**` exclusivement |
| Oracle | tests JVM : persistance après réinstanciation, crash avant/après effet, reprise `UNKNOWN`, rejeu `APPLIED`, idempotence, doublon, événement étranger, calendrier indisponible, permission révoquée, modification externe `DIVERGED`, suppression relue, corruption de fichier sans perte du dernier état complet ; suite JVM ciblée PASS |
| Stop conditions | besoin de modifier manifeste/UI/fichiers hors scope ; stockage non durable ; modèle/effort non conforme |
| Livrables | commit SHA, diff, tests, rapport `PASS/FAIL/BLOCKED`, documentation du format de persistance |
| Interdictions | modifier `PlanScreen.kt`, `CalendarPermissionScreen.kt`, manifeste ; écrire un calendrier réel depuis les tests JVM ; utiliser `InMemoryCalendarWriteJournal` comme autorité de production |

## Détail

1. `FileCalendarWriteJournal` : un fichier JSON par opération, écriture
   temp+fsync+rename, index reconstruit, version de format, tolérance aux fichiers
   incomplets, refus des collisions d'idempotence.
2. `CalendarWriteLinkStore` + `FileCalendarWriteLinkStore` : lien durable par
   activité, atomicité, index par `activity_id`, `event_id`, `provenance_uri`.
3. Intégrer le store au `CalendarWriteService` : créer/mettre à jour le lien après
   chaque effet et chaque réconciliation.
4. `recoverPending()` : reparcourt les opérations `PREPARED`/`UNKNOWN`, réconcilie
   par URI sans retry aveugle, met à jour journal + lien.
5. `verify(link)` : relecture et état `CONFIRMED` / `MISSING` / `DIVERGED` /
   `ORPHANED` / `UNKNOWN`.
6. Suppression : contrôler le nombre de lignes, relire, produire `MISSING` ou
   `DIVERGED`; jamais de disparition silencieuse.
7. Détection de modification externe : comparaison `content_hash`, conservation du
   lien, aucune réécriture automatique.
8. Révocation : toute opération vérifie les permissions avant l'effet ; refus
   `PERMISSION_DENIED` sans mutation.
9. Tests de crash par redémarrage du service sur le même répertoire ; test de
   fichier corrompu ; test de doublon ; test de lien orphelin.
10. Documenter le format, les garanties et les limites.

## Reprise

Session ID, SHA produit, tests exécutés, blocages. Aucune sous-délégation.
