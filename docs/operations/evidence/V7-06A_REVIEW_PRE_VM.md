# V7-06A — Revue indépendante pré-VM

Date : 2026-11-01
Base revue : `b04a5ff0e6f5f120f6c88370e815b336cadcfa24` (code `39746bd`)
Session : `d1a7590b-a0c0-49b6-bc47-cf57dd4d262e` — `codex/gpt-6-luna`, effort `xhigh`
Worktree lecture seule : `/tmp/atlas-v7-06a-review` (détaché, `chmod -R a-w`)
Verdict : **`CORRECTION_REQUIRED`** — aucun P0, un P1, trois P2.

## P1 — Rejeu refusé quand l’enveloppe Relay est renouvelée

`integrations/relay/pc/src/operations.ts:121` calcule `requestHash = hash(request)`
sur toute l’enveloppe, puis les branches de rejeu par `request_id`
(`operations.ts:126`, `operations.ts:156`) exigent l’égalité complète avant de
consulter le cache. Or `RelayBackendTransport.executeDeferred` crée une nouvelle
`Pending` à chaque tentative (`RelayBackendTransport.kt:124` et suivantes) et
`AtlasRemoteProtocol.request` y injecte `created_at`, `expires_at` et le
`session_id` courant (`AtlasRemoteProtocol.kt:137`). Android réutilise le même
corps, la même clé d’idempotence et le même `request_id` lors de sa reprise
`UNAVAILABLE` ; seule l’enveloppe de transport change.

Scénario : le Core accepte le consentement, la réponse WS est perdue ou Android
expire ; la tentative suivante arrive avec une nouvelle enveloppe ; le connecteur
répond `IDEMPOTENCY_CONFLICT` au lieu de rejouer le reçu ; Android ne rejoue pas
ce conflit et le consentement reste en attente.

Le test existant `consentReceiptSurvivesRestartAndReplays` réutilise le même objet
d’enveloppe et ne couvre donc pas ce cas. Oracle demandé : première réponse perdue,
puis même requête métier avec nouveaux horodatages et nouvelle session ; reçu
rejoué, un seul POST Core, ACK Android appliqué.

Correctif borné : ticket `V7-06A-FIX-PC-R2`, identité métier stable
(opération + clé d’idempotence + payload) pour le rejeu V4 par `request_id` ;
métadonnées de transport ignorées ; conflit maintenu si le payload ou la clé
change ; comportement plein-enveloppe conservé pour les opérations non-V4.

## P2 documentés (non bloquants)

1. `isConsentReceipt` (`operations.ts:504`) rend facultatifs `request_id`,
   `capability`, `source`, les versions de contrat et `replayed`, et ne lie pas le
   `request_id` reçu à celui de la requête. Un reçu Core incomplet pourrait être
   persisté puis rejoué après redémarrage, alors qu’Android le refuserait comme
   `PROTOCOL`. Proposition : validation stricte du schéma complet et corrélation
   avant persistance, ou maintien dans l’état incertain.
2. Les demandes immédiates s’accumulent : `APPEND_OR_REPLACE` ajoute un travail
   par appel de `schedule()` (`CoreV4SyncWorker.kt:1447`) ; le single-flight ne
   coalesce que les passes d’une même instance. Proposition : borner à une passe
   active et un suivi, avec test de rafale.
3. Risque pré-existant d’inversion `consentGate` ↔ verrou WebSocket pendant
   `pump()` depuis le thread de réception ; non reproduit, non corrigé dans le
   lot minimal. Risque analogue en mode DIRECT : le contrôle d’autorisation tient
   le verrou de stockage pendant l’ouverture réseau.

## Portée

Aucune preuve VM n’a été produite sur cette base. La qualification VM reprend
après intégration du correctif P1 et utilise un environnement isolé, un AVD
`emulator-5580` et des données synthétiques.
