# V7-06A — Reproduction des défaillances (tests rouges)

Date : 2026-10-31
Base instrumentée : `0b0ba01` (Phase B intégrée, aucun correctif)
Commit des tests : `6d749c73d824adf9a13b3ae76622efdad3b64f14`
Session auteur : `<SESSION_ID>` — `codex/gpt-6-luna`, `xhigh`
Worktree : `/tmp/atlas-v7-06a-test-android`

## Résultat de la suite complète sur la base instrumentée

- **895 tests, 891 réussis, 4 échecs rouges attendus.**
- Les quatre tests verts ajoutés (annulation/rejeu, révocation v3, idempotence
  même clé/même corps, conflit même clé/corps différent) passent.

## Rouges reproduits (vérifiés aussi par le parent)

| Test | Symptôme observé |
|---|---|
| `CoreV4SyncClientTest.lostConsentResponseIsRetriedWithSameBodyAndKey` | `CoreV4SyncException: Le serveur V4 est temporairement indisponible. (TIMEOUT)` ; le consentement reste pending, aucune reprise avec le même corps/clé |
| `CoreV4SyncClientTest.lostPlanResponseIsRetriedWithSameBodyAndKey` | même `UNAVAILABLE (TIMEOUT)` ; aucun reçu de plan, projection non acquittée |
| `RelayBackendTransportTest.duplicateInFlightRequestIdDoesNotReplaceFirstWaiter` | `AssertionError: the first waiter must receive the response` ; le doublon remplace l’attente, la première expire |
| `RelayBackendTransportTest.staleSessionResponseDoesNotAbortCurrentSession` | état attendu `RELAY_CONNECTED`, obtenu `ERROR` après une réponse marquée ancienne session |

Commande parent de vérification ciblée :

```sh
cd /tmp/atlas-v7-06a-test-android/android
ANDROID_HOME=<ANDROID_SDK> ./gradlew :app:testDebugUnitTest --offline \
  --tests '...CoreV4SyncClientTest.lostConsentResponseIsRetriedWithSameBodyAndKey' \
  --tests '...CoreV4SyncClientTest.lostPlanResponseIsRetriedWithSameBodyAndKey' \
  --tests '...RelayBackendTransportTest.duplicateInFlightRequestIdDoesNotReplaceFirstWaiter' \
  --tests '...RelayBackendTransportTest.staleSessionResponseDoesNotAbortCurrentSession'
```

Sortie : `BUILD FAILED`, quatre `FAILED` attendus, aux lignes
`CoreV4SyncClientTest.kt:86,125` et `RelayBackendTransportTest.kt:151,195`.

## Lecture

- Le premier consentement/plan peut être accepté côté Core puis perdu côté retour :
  l’erreur `UNAVAILABLE/TIMEOUT` n’est pas reprise alors que le corps et la clé sont
  durables et rejouables.
- Deux appels portant le même `request_id` peuvent se remplacer dans `inFlight` :
  la première attente devient orpheline et expire.
- Une réponse portant un `session_id` ancien abort la session courante.

Ces trois mécanismes sont désormais reproduits de façon déterministe, sans appareil,
sans Core réel et sans réseau. La cause exacte de la perte V7-05 reste **UNKNOWN** ;
ces tests bornent les mécanismes de perte et de non-récupération.
