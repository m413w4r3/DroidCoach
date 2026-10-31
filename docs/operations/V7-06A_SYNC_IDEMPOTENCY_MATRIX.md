# V7-06A — Matrice de synchronisation et d’idempotence

Date : 2026-10-31
Base : `55625299c364e1c71eba5924fcfe933af1852a2f`
Statut : matrice de conception, à confirmer par les tests Phase C et la recette VM-1.

## 1. Consentements V4

| Élément | Contraat / garantie |
|---|---|
| Clé d’idempotence | UUID durable par catégorie/version, conservé dans le corps pending Android ; réutilisé à l’identique lors d’une reprise |
| Version | Incrémentée à chaque changement d’état local ; `syncedVersion` ne progresse qu’après un reçu validé |
| Core | Transaction SQLite `BEGIN IMMEDIATE` : projection + événement dans la même transaction ; version strictement croissante ; événements immuables |
| Rejeu exact Core | Même clé + même corps → `DUPLICATE`/`replayed=true`, un seul événement, version d’origine |
| Conflit Core | Même clé + corps différent → `IDEMPOTENCY_CONFLICT` ; version inférieure → `STALE_VERSION` ; même version + état différent → conflit |
| Connecteur en mémoire | Même clé/corps → réponse mémorisée `replayed=true`, aucun second POST ; clé/corps différent → `IDEMPOTENCY_CONFLICT` |
| Connecteur après redémarrage | `completed` conservé pour consentement (cible du correctif) → reçu rejoué sans POST ; `uncertain` consentement → ré-exécution sûre avec la même clé/corps ; autres V4 → bloqués |
| Android | `acknowledgeConsent` → `APPLIED`, `SUPERSEDED` ou `MISSING` ; un reçu d’ancienne version ne réactive jamais une révocation |
| Révocation | Version v3 prime ; si v2 est déjà parti, le Core peut temporairement garder v2 jusqu’au reçu v3 ; le reçu v3 est requis pour `syncedVersion=3` |
| Perte de réponse | Reprise bornée avec le même corps/clé ; aucun succès fictif ; aucune progression de version sans reçu |
| Redémarrage Android | Les corps pending sont durables ; une nouvelle passe reprend la même version/clé |

## 2. Projections DayPlan / statuts

| Élément | Garantie |
|---|---|
| Clé plan | `plan:<planId>:consent:<version>` ; version de plan comme version de source |
| Reçu plan | `stored_version`, `snapshot_hash`, `last_synced_at` validés ; pas d’ACK sans reçu |
| Statut | `event_id` + séquence globale réservée ; rejeu exact idempotent ; rejet définitif typé |
| Perte de réponse | Même corps/clé durable ; reprise bornée ; aucune double version |
| Révocation | Pas d’envoi de plan/statut sans consentements DAY_PLAN + ACTIVITY_STATUS synchronisés |

## 3. Concurrence des workers

| Situation | Comportement cible |
|---|---|
| Immédiat + périodique | Une seule passe active (single-flight borné) ; pas de double POST du même `request_id` |
| Nouveau `schedule()` pendant une passe | Immédiat `APPEND_OR_REPLACE` : la passe en cours n’est pas annulée, une passe de suivi reprend l’état courant |
| Périodique replanifié | `UPDATE` : pas d’annulation de la passe en cours |
| Même `request_id` en vol | Le premier appelant conserve l’attente ; le doublon reçoit un échec typé, sans attente orpheline |
| Snapshot de consentements périmé | Relecture de `syncedVersion` avant envoi ; un consentement déjà acquitté n’est pas renvoyé |

## 4. Sessions Relay

| Situation | Comportement cible |
|---|---|
| Réponse corrélée | `request_id` + `session_id` + opération doivent correspondre |
| Réponse tardive après timeout | Ignorée si mémorisée ; une duplication ultérieure ne doit pas corrompre une autre attente |
| Réponse d’ancienne session | Ignorée sans abort de la session courante |
| `request_id` inconnu sur la session courante | Échec protocolaire explicite |
| Reconnexion | Requêtes en vol terminées explicitement ; requêtes en file peuvent partir sur la nouvelle session ; aucune réponse attribuée à la mauvaise génération |
| Révocation | La porte de consentement empêche tout nouveau dispatch ; les requêtes déjà parties ne sont pas annulées rétroactivement |

## 5. Preuves attendues

- Tests JVM C1/C3/C8 rouges avant correctif, verts après.
- Tests connecteur C5 rouges avant correctif, verts après ; C5c (chat) reste bloqué.
- Tests Verts C4/C6/C7 de non-régression.
- Révocation confirmée localement et côté Core sur VM.
- Reçu DayPlan relu depuis le Core isolé, versions/provenance cohérentes.
- Aucune duplication d’événement/consentement sur rejeu.
