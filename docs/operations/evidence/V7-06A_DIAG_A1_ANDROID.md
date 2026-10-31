# V7-06A-DIAG-A1 — Worker Android et persistance (audit lecture seule)

Date : 2026-10-31
BASE_SHA : `55625299c364e1c71eba5924fcfe933af1852a2f`
Session Paseo : `148404ff-897a-407e-b686-2cf92976cf1f`
Profil vérifié par le parent : `provider=codex`, `model=gpt-6-luna`,
`effectiveThinkingOptionId=xhigh`, mode `auto`.
Worktree : `/tmp/atlas-v7-06a-a1-android-ro` (détaché, `chmod -R a-w`), HEAD et arbre
propres après audit. Aucun test, build, réseau, ADB ni écriture.

## Faits établis

1. **Deux voies WorkManager indépendantes.** `schedule()` enfile toujours un travail
   immédiat `atlas_core_v4_push_now` avec `ExistingWorkPolicy.REPLACE` ; s’il existe un
   consentement GRANTED, il enfile aussi un périodique `atlas_core_v4_push_periodic`
   avec `ExistingPeriodicWorkPolicy.REPLACE` (15 min, réseau CONNECTED). Aucun verrou
   commun entre les deux voies ; chaque `doWork()` construit son propre client et son
   adaptateur de transport (`CoreV4SyncWorker.kt:944-970`).
2. **Le verrou de stockage ne couvre pas l’aller-retour réseau.** `AndroidCoreV4SyncStore`
   sérialise les lectures/écritures JSON dans le processus, et une courte barrière
   avant l’ouverture du transport ; l’attente de la réponse est hors verrou
   (`AndroidSyncStorage.kt:199,783`, `CoreV4SyncWorker.kt:874`, `CoreV4SyncClient.kt:853`).
3. **Snapshot sans relecture de `syncedVersion`.** `pendingConsentRecords()` est un
   instantané ; la relecture avant envoi compare version et état, pas `syncedVersion`.
   Deux workers ayant capturé la même liste avant le premier ACK peuvent donc envoyer
   le même consentement (`AndroidSyncStorage.kt:290-295`, `CoreV4SyncWorker.kt:732-741`).
4. **Collision de `requestId`.** La session relayée partagée accepte plusieurs requêtes
   en vol ; `inFlight` est indexé par `requestId` sans refus d’un identifiant déjà en
   vol. Une seconde requête du même identifiant peut remplacer la première : une seule
   attente est réveillée, l’autre expire (`RelayBackendTransport.kt:313-335,519`).
   Risque code-level, non établi pour V7-05.
5. **Arrêt/remplacement après POST.** `REPLACE`, l’annulation du périodique et
   `onStopped()` peuvent interrompre le transport ; aucune compensation serveur. Le
   corps de consentement est durable avant envoi et peut être rejoué avec la même clé
   (`CoreV4SyncWorker.kt:208,958-970`, `AndroidSyncStorage.kt:297-319`).
6. **Ordre d’une passe.** `pendingConsentRecords()` suit l’ordre de l’enum :
   DAY_PLAN avant ACTIVITY_STATUS. Le worker relit version/état, prépare ou réutilise
   le corps durable, envoie, valide le reçu, puis écrit `synced_version` localement.
   Il n’y a pas de POST d’ACK séparé. Une réponse malformée échoue avant l’écriture ;
   un reçu devenu ancien est ignoré **silencieusement** par `acknowledgeConsent`
   (`CoreV4SyncWorker.kt:726-742`, `CoreV4SyncClient.kt:215-240`,
   `AndroidSyncStorage.kt:321-341`).
7. **Révocation protégée localement.** `setConsent` incrémente la version et conserve
   `syncedVersion`. Un reçu v2 ne peut pas écraser une révocation v3 : l’ACK n’est
   appliqué que si catégorie, version et état correspondent encore. Si v3 gagne, v2
   est un no-op ; si v2 gagne, v3 reste pending. Un POST v2 déjà parti peut toutefois
   être traité à distance avant v3 (`AndroidSyncStorage.kt:235-288,321-341`,
   `CoreV4SyncClient.kt:814`).
8. **Toute erreur consentement arrête la passe avant le plan.** `syncPendingCoreV4ConsentsFirst`
   ne capture pas par catégorie ; le worker vérifie ensuite que toutes les versions non
   UNKNOWN sont acquittées, sinon `CONSENT_PENDING`. Les exceptions remontent à
   `doWork()` (`CoreV4SyncWorker.kt:114-124,196-201`).
9. **Classification relayée.** `Reason.TIMEOUT → UNAVAILABLE` avec `retryable=false` ;
   il n’existe pas de `CoreV4FailureKind.TIMEOUT`. En DIRECT, une `IOException`
   (timeout socket) devient `OFFLINE` retryable. Le code d’erreur détaillé n’est pas
   conservé dans la présentation globale, qui est partagée et peut être réécrite par
   un autre worker (`CoreV4SyncClient.kt:87-110,325-350`,
   `RelayCoreV4HttpTransport.kt:194,237-251`, `AndroidSyncStorage.kt:659-665`).
10. **Deux POST à 165 ms.** Compatibles avec une passe séquentielle, deux workers
    (immédiat + périodique) ou un remplacement `REPLACE`. Un `Result.retry` observe un
    backoff de 30 s et n’explique pas seul cet intervalle
    (`CoreV4SyncWorker.kt:726,956`).

## Matrice d’hypothèses

| # | Hypothèse | Compatible | Limitée par | Test discriminant |
|---|---|---|---|---|
| H1 | Passe séquentielle : DAY_PLAN acquitté, ACTIVITY_STATUS accepté par Core puis timeout de retour | deux POST proches, ACK 2/2 vs 0/2, aucun plan | ne dit pas si Android a reçu la seconde réponse | succès simulé DAY_PLAN puis acceptation ACTIVITY_STATUS + timeout ; oracle : deux POST, un seul ACK, aucun plan |
| H2 | Chevauchement immédiat/périodique | un seul `schedule()` crée les deux travaux ; aucune exclusion | aucun ordinal worker observé | WorkManager de test + barrière transport ; compter les POST simultanés par catégorie/version |
| H3 | `REPLACE` arrête un worker après acceptation Core | `onStopped` annule le transport ; corps rejouable | aucun nouvel appel scheduler établi | suspendre après acceptation, replanifier, libérer ; vérifier ACK et rejeu |
| H4 | Retour relayé perdu/expiré après 200 Core | timeout relayé → UNAVAILABLE non retryable ; 200 Core ne prouve pas la réception | aucune réponse V4 valide observée par Android | session simulée : accepter, omettre la réponse ; oracle : catégorie d’erreur, état, résultat `doWork`, aucun plan |
| H5 | Reçu v2 pendant révocation v3 | ACK v2 no-op, v3 reste pending | ne produit pas directement UNAVAILABLE | barrière après reçu v2 valide, écrire v3, libérer ; état final v3 |
| H6 | Reçu validé non durablement enregistré | commit après POST ; JSON illisible relu comme racine vide | un échec de commit serait `LOCAL_ERROR`, pas UNAVAILABLE | persistance en échec pendant l’ACK ; résultat local explicite, pending conservé |

**Aucune hypothèse n’est établie comme cause du run V7-05.**

## Cycle de vie local d’un consentement

```text
absent → UNKNOWN v1 (synced=0)
  setConsent(true)  → GRANTED vN, synced inchangé
  setConsent(false) → REVOKED vN, synced inchangé
pending (synced < N)
  → schedule : immediate toujours ; periodic si ≥1 GRANTED
  → snapshot pendingConsentRecords()
  → relecture version/état (pas syncedVersion)
  → corps durable (réutilisé pour la même version)
  → POST → reçu validé
      ├─ ligne courante toujours vN/état identique → synced=N, pending retiré
      ├─ ligne passée à vN+1 → ACK ignoré silencieusement, vN+1 reste pending
      └─ transport/validation/commit en échec → pas d’ACK, reprise requise
```

## Risques et correctifs candidats

1. **P1** Single-flight couvrant immédiat et périodique, avec coalescence d’une
   nouvelle demande ; ne pas annuler inutilement un travail en vol.
2. **P1** Refuser/coalescer un `requestId` déjà en vol, ou distinguer l’identifiant de
   tentative de la clé d’idempotence métier.
3. **P2** Rendre `Reason.TIMEOUT` reprenable lorsque le corps est stable et idempotent.
4. **P2** Résultat d’ACK typé (`ACKED/STALE/MISSING/COMMIT_FAILED`) et trace expurgée ;
   ne jamais marquer une version plus récente comme synchronisée.
5. **P2** Ne pas traiter une racine JSON illisible comme un état neuf silencieux.

## Tests Phase C proposés

- C2 : deux consentements en attente ; DAY_PLAN réussit, ACTIVITY_STATUS accepté puis
  timeout de retour ; vérifier l’ordre, les ACK, l’absence de plan.
- C3 : immédiat + périodique simultanés avec barrière ; vérifier le nombre d’appels en
  vol, les catégories/versions et la collision de `requestId`.
- C4 : remplacement après acceptation ; vérifier l’arrêt, le rejeu exact et l’ACK final.
- C6 : révocation v3 pendant un reçu v2 ; aucun marquage de v3 avant son reçu propre.
- C7 : échec de commit après reçu valide ; timeout relayé/direct ; résultat typé,
  pending conservé, rejeu stable, aucun plan avant ACK courant.

## UNKNOWN restants

- Les deux POST correspondent-ils à deux catégories, deux workers ou un remplacement ?
- Android a-t-il reçu/validé la seconde réponse ? Le commit local a-t-il réussi ?
- Quelle erreur relay précise a produit `UNAVAILABLE` ?
- Un travail a-t-il été annulé/remplacé pendant l’envoi ?

Instrumentation minimale : ordinal worker, génération d’exécution, type immédiat/périodique,
`runAttemptCount`, catégorie, version, état, étape, durée, résultat transport typé et
résultat d’ACK. Ni secret, ni corps, ni identifiant de requête, ni UUID personnel.
