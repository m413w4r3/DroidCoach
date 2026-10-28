# R3B-CHAT-WIRE-01 — Intégration Android/Node de la conversation relayée

| Clé | Valeur |
|---|---|
| TASK_ID / statut | `R3B-CHAT-WIRE-01 / INTEGRATED` (2026-10-28) |
| Vague | B |
| BASE_SHA | SHA d'intégration de `R3B-CHAT-01` sur `feat/atlas-v6-3-r3b-remote-openclaw` |
| Profil obligatoire | `codex:gpt-6-luna`, effort `xhigh`, session vérifiée par le parent |
| Worktree | worktree Paseo dédié |
| Dépendances | `R3B-CHAT-01` ACCEPTED et intégré |

## Objectif observable

Raccorder la conversation existante au transport relayé : en mode `RELAY`, le
coach utilise `coach.chat.request` via `RelayBackendTransport` ; en `DIRECT`, le
HTTP existant ; en `OFFLINE`, le mock local. Aucun second écran de conversation.
L'utilisateur distingue explicitement une réponse mock d'une réponse OpenClaw
réelle. Les états connecté / connexion / hors ligne / révoqué, l'annulation, la
conservation du brouillon et de l'historique, les erreurs, les propositions
séparées et la confirmation des actions restent utilisables.

## Scope d'écriture exclusif

- `android/app/src/main/java/dev/atlas/coach/sync/relay/**`
- `android/app/src/main/java/dev/atlas/coach/sync/agent/coach/**`
- `android/app/src/main/java/dev/atlas/coach/app/RelaySettingsController.kt`
- `android/app/src/main/java/dev/atlas/coach/app/AtlasController.kt`
- `android/app/src/main/java/dev/atlas/coach/app/RelaySettingsScreen.kt`
- `android/app/src/main/java/dev/atlas/coach/app/AgentCoachScreen.kt`
- `android/app/src/test/java/dev/atlas/coach/sync/relay/**`
- `android/app/src/test/java/dev/atlas/coach/sync/agent/coach/**`
- `docs/v6-3/r3b/preuves/chat-wire-01*.txt`

Interdit : modifier `backend/**`, `contracts/**`, `integrations/relay/pc/**`,
`integrations/relay/proxy/**`, les écrans HOME/palettes/design system, ou créer
un second écran de conversation. Aucun ADB, aucun push.

## Contrat technique

- Introduire une abstraction de chat (interface Kotlin) avec deux
  implémentations : HTTP existante et relayée. Le corps JSON de chat doit être
  construit par un composant partagé unique pour que le mode RELAY envoie
  exactement le même `shared_context` minimisé, le même mandat et les mêmes
  catégories consenties que le mode DIRECT.
- En mode `RELAY`, `AtlasController` sélectionne le client relayé pour le chat
  et ne fait **aucun fallback HTTP silencieux** vers `syncBaseUrl`.
- Le client relayé vérifie avant envoi : capacité `coach.chat` demandée par la
  session, mandat non révoqué/suspendu/non expiré, consentement encore valide
  au moment de l'écriture, message 1..2000.
- L'audit local du contexte écrit (`onContextWritten`) doit continuer de
  s'exécuter avant/au moment de l'envoi relayé, et l'échec de persistance doit
  rester visible sans provoquer de double envoi.
- Idempotence : clé stable par envoi utilisateur. Un timeout relayé est
  présenté comme résultat incertain ; **aucun renvoi automatique**.
- Annulation : bouton visible pendant l'envoi, interrompt la requête relayée
  (le transport envoie `cancel` et/ou interrompt l'attente) ; état `CANCELLED`
  affiché. En mode DIRECT, annoncer honnêtement que l'annulation est
  indisponible si elle ne peut pas être garantie.
- En mode `RELAY`, la boîte de réception HTTP directe et les acquittements
  directs ne doivent pas être tentés vers un backend local inaccessible. Les
  propositions renvoyées par le chat sont fusionnées localement ; les
  acquittements restent en attente locale et ne sont pas perdus.
- La provenance affichée provient de `agent_status` : `mock` →
  « Démonstration locale (mock) » ; `connected` → « OpenClaw via PC » ;
  `unavailable` → erreur explicite. Ne pas afficher `agent_status` brut.
- Conserver l'historique local et le brouillon après coupure/réouverture :
  utiliser `rememberSaveable` pour le brouillon et l'historique existant.
- Accessibilité : libellés TalkBack sur les contrôles, ordre de focus
  cohérent, tailles minimales 48 dp, rotation et police agrandie sans perte de
  saisie.

## Tests JVM obligatoires

1. Conversation avec adaptateur mock isolé (comportement existant préservé).
2. Conversation relayée avec transport/faux socket : payload exact, réponse
   `reply`/`proposals`/`agent_status` parsée, provenance correcte.
3. Vérification que le `shared_context` réellement envoyé est le contexte
   minimisé consenti (pas de catégorie non consentie).
4. Capacité `coach.chat` absente → `OPERATION_UNAVAILABLE` visible, aucun envoi.
5. Mandat révoqué/expiré → aucun envoi, message compréhensible.
6. Déconnexion en cours de requête → état hors ligne, aucun crash, message
   local préservé, aucun renvoi automatique.
7. Reconnexion → aucune duplication d'envoi, clé d'idempotence stable.
8. Payload malformé, trop grand ou mauvaise version → erreur explicite.
9. Proposition non exécutée sans confirmation (vérifier le mandat local).
10. Distinction visible mock / OpenClaw personnel dans l'UI et les tests.

## Oracle

```sh
cd android && ./gradlew :app:testDebugUnitTest --rerun-tasks --max-workers=1
./gradlew :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

Le test doit échouer avant l'implémentation sur au moins un cas discriminant
(p. ex. chat RELAY non routé), puis passer après. Les builds doivent rester
verts ; aucun test instrumenté n'est exécuté par cet agent.

## Livrables

- Code, tests JVM, preuves `chat-wire-01*.txt`, commit local avec SHA.
- Liste des fichiers modifiés, résultats Gradle, limites.
- Aucun secret, token, message utilisateur réel ou donnée personnelle.

## Stop conditions

- Modèle/effort incorrect : `BLOCKED`.
- Besoin de modifier un fichier hors scope : arrêter et rendre la main.
- Fallback HTTP silencieux en RELAY, double envoi, proposition exécutée sans
  confirmation, fuite de token/message : `STOP`.
- Tests instrumentés ou ADB nécessaires : parent uniquement.

## Clôture — 2026-10-28

Statut final : **INTEGRATED**. JVM 688 tests sur l'arbre final ; test Pixel
`R3bRelayChatInstrumentedTest` OK : réponse OpenClaw réelle via le PC, état
RELAY_CONNECTED, provenance « OpenClaw via PC », aucun token/URL backend
direct, aucune proposition exécutée sans confirmation, brouillon et historique
conservés après rotation. APK principal `b55776a9…`. Preuve :
`preuves/pixel-instrumentation-final.txt` et `preuves/chat-wire-01-android.txt`.
