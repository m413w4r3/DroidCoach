# ATLAS V7-08 — Plan QA A0 Android/Core

**Base de l’audit :** `cbdf0e2fb26190015a0d529a3ef713ed4b4379ef`
**Branche :** `v7-08/a3-qa`
**Portée :** protocole Core V4 et préparation d’une recette Android 17/API 37 sur Pixel physique. Ce document ne constitue pas une preuve d’exécution Pixel.

**Écriture autorisée :** nouveau test `backend/tests/test_v7_08_a0_protocol.py` et ce plan. L’exécution utilise uniquement SQLite temporaire et `MockTransport`. Aucune opération ADB/Pixel, Docker, redémarrage de service, push/merge, modification de secret ou donnée personnelle n’est incluse.

## Objectif et limites

Le parcours vérifie qu’un chat V4 outillé crée une proposition A0 unique, que le Core ne modifie pas le plan, et que l’application ne se produit qu’après l’action explicite sur Android. Le chemin APPLY synchronise ensuite une nouvelle version du DayPlan et son ACK `APPLIED`. Le chemin REJECT conserve le DayPlan et envoie l’ACK `REJECTED`. Les replays, le pull après reconnexion et le consentement `DAY_PLAN` font partie des contrôles.

Les tests backend utilisent l’API FastAPI et SQLite réels, avec un `MockTransport` qui renvoie des appels d’outils préécrits. Ils testent l’orchestration réelle du routeur, pas un modèle hébergé ni le trajet réseau Pixel. Le code Core n’expose pas de route APPLY : les tests représentent l’effet Android par l’envoi du snapshot DayPlan après approbation. Seule une recette sur appareil peut valider le geste UI, le stockage local, le worker et le transport réellement configuré.

## Inventaire des tests existants

| Fichier | Test(s) existant(s) | Couverture | Écart restant avant ce ticket |
|---|---|---|---|
| `backend/tests/test_chat_orchestration_v4.py` | `test_v4_chat_sends_only_six_tools_and_returns_exact_tool_call_id`; `test_structured_proposal_is_accepted_with_backend_owned_idempotency_key`; `test_proposal_is_deduplicated_and_remains_a0_user_review`; `test_six_tool_and_one_proposal_quotas_are_enforced_before_dispatch`; `test_timeout_after_proposal_reports_completed_effect_and_replays_without_retry`; `test_chat_capability_and_requested_consent_are_required_before_provider` | Chat outillé, un seul outil de proposition, A0/aucune application Core, cache et consentement requis | Pas de trajet intégré jusqu’au snapshot Android versionné et ACK durable |
| `backend/tests/test_v4_proposals.py` | `test_proposal_validation_idempotency_lookup_and_plan_is_not_applied`; `test_inbox_cursor_pagination_ack_states_and_replay_conflicts`; `test_proposal_requires_fresh_snapshot_and_never_accepts_arbitrary_route_data`; `test_consent_revocation_tombstone_blocks_reads_proposals_pull_and_ack_until_regrant` | Proposition persistée, pull paginé, ACK APPLIED/REJECTED, rejouabilité/conflit, expiration et révocation | Les scénarios démarrent via la route outil directe, séparément du chat |
| `backend/tests/test_v4_projection_store.py` | `test_proposal_idempotency_lookup_and_ack_replay_conflicts`; `test_proposal_ack_requires_complete_application_proof_and_rejects_proof_without_application`; `test_all_six_tools_match_contracts_and_do_not_leak_notes_or_apply_proposal` | Persistance SQLite, preuve ACK, conflits, outils et absence d’application Core | Pas de parcours API complet avec resynchronisation DayPlan après accord |
| `backend/tests/test_v4_routes.py` | `test_consent_state_sync_is_android_only_typed_and_idempotent`; `test_consent_versions_are_monotonic_in_either_arrival_order`; `test_capability_flags_and_consent_version_gate_reads_and_sync` | Consentement et enveloppes de synchronisation | Le trajet complet chat → pull → décision reste séparé |
| `backend/tests/test_v4_contracts.py` | `test_uncertain_ack_does_not_claim_applied_effects`; `test_reconciled_applied_ack_requires_complete_application_proof`; `test_reconciled_not_applied_and_rejected_ack_cannot_claim_application` | Contraintes des fragments de contrat ACK | Pas de vérification des effets de la séquence complète |
| `android/app/src/androidTest/java/dev/atlas/coach/e2e/V7RemoteV4InstrumentedTest.kt` | `remoteV4ProposalCanBeAppliedOrRejectedAfterExplicitTestAction` | Sur Android, action APPLY ou REJECT explicitement passée au test, mutation locale/version, inbox et ACK durable | Dépend d’un plan synthétique déjà préparé et d’un backend distant configuré; pas exécuté dans ce ticket |
| `android/app/src/test/java/dev/atlas/coach/app/PlanControllerAgentRevisionTest.kt` | `a0AndA2KeepTheirExistingPlanRevisionGateAndSpecificConfirmationPath`; `refusingA1IsTerminalAndNeverWritesThePlan` | Application A0 locale et refus sans mutation, sur contrôleur JVM | Ne couvre ni le vrai chat distant ni le Core distant |

Le nouveau fichier `backend/tests/test_v7_08_a0_protocol.py` ajoute le lien entre ces briques : chat outillé, une proposition par requête, pull sur une nouvelle instance client/Core, snapshot versionné représentant l’action approuvée, ACK persistant et rejeu, puis proposition de décalage refusée sans mutation. Il couvre aussi les refus chat sans consentement, pull/ACK après révocation et ACK d’une proposition inconnue. Le cas d’expiration est déjà exercé par `test_inbox_cursor_pagination_ack_states_and_replay_conflicts` et n’est pas recopié.

## Contrats et validateurs

- `chat-request.schema.json` et `chat-response.schema.json` sont validés directement. Les résultats d’outils passent le validateur `tool-results.schema.json`.
- Les propositions et ACK sont validés avec les définitions publiées `$defs.proposal` et `$defs.ack` de `proposal-inbox-ack.schema.json`. Les snapshots, consentements et reçus utilisent les définitions de `sync.schema.json` et `sync-receipts.schema.json`.
- `confirmation_mode=A0_USER_REVIEW` et `auto_application=NONE_AT_CORE` sont présents dans le fragment contrat de proposition. Le routeur/tool service les renvoie et les persiste. Le test vérifie les deux formes.
- La ligne SQLite conserve aussi `body_hash` pour l’idempotence interne; `_public_proposal()` l’omet avant validation et pull. Le test valide la proposition interne après retrait de ce seul champ et confirme son absence sur le fil public. Ce champ interne n’est pas ajouté au contrat.
- **Écart contrat/code :** `proposal-inbox-ack.schema.json` publie des fragments `proposal`, `transition` et `ack`, mais pas de schéma pour l’enveloppe `/v4/agent/proposals/pull`. `v4_router.py` impose actuellement les clés exactes `contract_version`, `schema_version`, `request_id`, `capability`, `consent_version`, `page_size`, `cursor`, et renvoie `contract_version`, `schema_version`, `request_id`, `capability`, `proposals`, `next_cursor`. Le test copie ces clés depuis le validateur réel et valide chaque élément avec le fragment publié; aucun champ supplémentaire n’est supposé.
- Le snapshot Android V4 provient de `CoreV4SyncWorker.readTodayPlan()` / `todayPlanFile()`, limité au plan de la date système. La recette physique doit donc conserver une activité future du jour même.

## Matrice des nouveaux tests

| Test | Oracle PASS | FAIL à relever |
|---|---|---|
| `test_a0_chat_pull_apply_ack_restart_and_reject_no_mutation` | Deux tours de chat successifs déclenchent `atlas_get_day_plan` puis `atlas_propose_plan_revision`; chaque réponse contient un seul `proposal_id`; la proposition est `PENDING_ANDROID`, `A0_USER_REVIEW`, `NONE_AT_CORE`; le plan reste en v1 avec la séance à 13:00 avant action. Le pull sur une nouvelle instance retourne le même identifiant. Le snapshot Android version 2 est accepté avant l’ACK APPLIED, dont le rejeu après réouverture SQLite est marqué rejoué et ne crée pas de second événement. Un même `ack_id` avec un corps différent donne `ACK_ID_CONFLICT`. La seconde proposition de décalage est REJECTED; le plan v2 reste identique et l’ACK est durable/idempotent. | Tout plan modifié avant l’action/snapshot, plus d’une proposition par chat, état ou champs A0 incorrects, ACK sans preuve accepté, conflit non détecté, événement doublé, ou plan modifié après REJECT. |
| `test_day_plan_consent_absent_or_revoked_blocks_chat_pull_and_ack` | Sans consentement, le chat retourne `CONSENT_REQUIRED` avant tout appel fournisseur. Après création d’une proposition avec consentement, une révocation bloque pull et ACK; la proposition reste en attente et aucun ACK n’est écrit. | Un appel fournisseur ou une proposition avant consentement, ou un pull/ACK accepté après révocation. |
| `test_unknown_proposal_ack_is_not_persisted` | Un ACK conforme au fragment publié visant un UUID absent retourne `NOT_FOUND`; aucune proposition ni aucun événement n’est persisté. | Tout succès ou écriture pour l’identifiant inconnu. |

Exécution backend attendue : `cd backend && .venv/bin/python -m pytest tests/test_v7_08_a0_protocol.py -q`, puis `cd backend && .venv/bin/python -m pytest -q`. Les résultats observés doivent être consignés avec le SHA réellement testé; un test avec `MockTransport` ne vaut pas preuve de chat distant.

### Exécution de ce ticket

`backend/.venv/bin/python` n’existe pas dans ce worktree : la commande exacte demandée s’arrête avec `zsh: no such file or directory: .venv/bin/python` (code 127). L’équivalent a été exécuté avec le Python système, sans cache pytest ni fichiers bytecode :

- Ciblé : `PYTHONDONTWRITEBYTECODE=1 PYTEST_ADDOPTS='-p no:cacheprovider' python3 -m pytest tests/test_v7_08_a0_protocol.py -q` — **3 passed, 1 warning, 1.15 s**.
- Backend complet : `PYTHONDONTWRITEBYTECODE=1 PYTEST_ADDOPTS='-p no:cacheprovider' python3 -m pytest -q` — **377 passed, 1 skipped, 3 warnings, 35.22 s**.
- Avertissements : dépréciation Starlette/httpx et deux Operation ID OpenAPI dupliqués sur le routeur V4 existant. Aucun de ces avertissements ne fait échouer la suite.
- Android/Pixel : **NOT_RUN** dans ce ticket; aucune preuve physique n’est revendiquée.

## Procédure Pixel pour le parent

Exécuter uniquement sur Pixel physique Android 17/API 37, selon le skill `android17-device-qa`. Cette procédure combine un pré-vol en lecture seule et des changements bornés aux fixtures de recette consenties. Ne pas exécuter cette procédure dans le cadre du ticket A3.

### 1. Pré-vol en lecture seule et sauvegarde

1. Noter le SHA source, le SHA-256 APK, l’heure locale, le modèle Pixel, `SDK=37`/Android 17, la version/signature de `dev.atlas.coach`, le HOME courant, l’état réseau et les capacités V4 visibles. Masquer le numéro de série; conserver seulement son empreinte dans les preuves.
2. Vérifier qu’un seul Pixel cible est connecté et en état `device`. Les seules commandes ADB de pré-vol autorisées sont des lectures (`adb devices -l`, `getprop` pour modèle/SDK, inspection package/HOME/permissions); aucune écriture, installation, changement de permission ou shell privilégié.
3. Faire une sauvegarde de l’application uniquement, hors dépôt et hors `/tmp` seul, puis vérifier son archive et son SHA-256. Si la sauvegarde restaurable n’est pas possible, ou si un autre opérateur utilise ADB, arrêter et classer `BLOCKED`.
4. Lire l’état Core/quota et vérifier que chat, `day_plan.read` et `plan_revision.propose` sont disponibles. La reprise V7-07 a observé `daily_tokens=102062` pour une limite de `100000`; ne pas supposer que le quota a été réinitialisé. Si le Core refuse par quota, ne pas répéter le chat : classer l’étape `BLOCKED`.
5. Dans l’application, relever les valeurs initiales du consentement `DAY_PLAN`, de la capability de révision de plan, du mode relay/direct, du mode Conversation V4 et du plan courant. Garder l’état HOME et les autres catégories de consentement inchangés.

### 2. Fixture du jour et consentement ciblé

1. Dans Plan, lire le programme du jour. N’effacer ni ne remplacer aucune activité personnelle. Choisir une plage libre de 45 minutes **future aujourd’hui**. Si aucune plage sûre ne reste aujourd’hui, arrêter la recette; ne pas déplacer la fixture à demain, car le worker ne synchronise que le jour courant.
2. Utiliser une date/heure cohérente avec la zone locale du Pixel. Pour une plage vide, créer uniquement l’activité synthétique **« Test ATLAS — séance Focus »**, type Focus, durée 45 minutes. Si le plan contient déjà des activités, ajouter la fixture à un créneau libre sans les modifier. Noter la version du plan avant la fixture et celle après.
3. Dans Plus → Partage backend, accorder uniquement `Plan approuvé (DAY_PLAN)` si nécessaire. Relever version et état avant/après. Ne pas activer `ACTIVITY_STATUS` ni une autre catégorie pour cette recette.
4. Activer la capability « révisions par le coach » et Conversation V4 si elles sont actuellement désactivées et si le parent a l’autorisation de modifier ces flags. Ne pas activer A1 ni préautoriser l’application.
5. Dans Plus → Partage backend, toucher **« Pousser les projections V4 »**. Vérifier que le plan du jour est frais côté Core et que l’état de synchronisation n’indique ni consentement périmé ni erreur. Ne poursuivre que lorsque la version locale/Core concorde.

### 3. Chat, pull après reconnexion et APPLY explicite

1. Dans Coach, vérifier que **Conversation V4** est sélectionnée; son texte indique qu’aucun repli V2 ne sera tenté. Saisir au clavier, sans ADB : « Propose de décaler de 15 minutes aujourd’hui “Test ATLAS — séance Focus”, qui dure 45 minutes. Ne l’applique pas sans mon accord explicite. »
2. Envoyer une seule fois. Garder l’identifiant de requête/proposition exposé par les diagnostics expurgés. Le résultat attendu est un chat réussi avec appels d’outils et une seule proposition. En cas de timeout, quota ou résultat incertain, ne pas renvoyer le message; consulter d’abord l’idempotence et l’état Core.
3. Pour le pull/reconnexion, rétablir le transport par son chemin préexistant, sans modifier Wi-Fi, Tailscale, bearer ou configuration serveur. Puis utiliser **« Pousser les projections V4 »** (le worker effectue le pull lorsque la capability est active) et ouvrir Plan → **Propositions reçues**. L’identifiant doit correspondre à celui du chat; le mode doit être A0 et la proposition doit afficher les changements synthétiques.
4. Vérifier avant toute décision que le plan est encore à sa version précédente et que la séance synthétique est toujours à son horaire initial. Ouvrir **« Examiner les changements »**, confirmer le titre, le jour, le nouvel horaire proposé et les 45 minutes, puis seulement toucher **« Confirmer et appliquer »**.
5. Vérifier localement que le plan a exactement une nouvelle version, que l’activité synthétique est présente une seule fois et que les autres activités sont inchangées. Dans Plus → Partage backend, pousser les projections V4 pour envoyer le snapshot et l’ACK. Attendre un ACK livré; ne pas créer de second ACK manuellement.

### 4. REJECT explicite et ACK

1. Depuis Coach V4, demander une seconde proposition qui décale uniquement l’activité synthétique de 15 minutes, avec l’instruction explicite de ne pas l’appliquer automatiquement. Ne faire cela qu’après synchronisation de la version produite par APPLY.
2. Pousser les projections V4 puis ouvrir la proposition reçue dans Plan. Vérifier l’identifiant, A0, la version de base et le diff de cette activité uniquement.
3. Choisir **« Refuser définitivement »**. Vérifier que la version du plan et les horaires de toutes les activités restent identiques. Pousser les projections V4 et vérifier que l’ACK `REJECTED` est livré, sans preuve d’application.
4. En cas de perte réseau pendant un ACK, utiliser uniquement le bouton de synchronisation existant après reconnexion. Ne pas recréer le chat et ne pas changer l’identifiant de proposition; comparer l’état final/ACK par identifiant.

### 5. Preuves et rollback

Collecter, avec horodatage et données expurgées : SHA source/APK et signature, modèle/API, version du plan avant fixture, version après APPLY, version inchangée après REJECT, nombre d’activités synthétiques, identifiants proposition/ACK expurgés, confirmation du mode A0, états de consentement/version, états du worker et du Core, captures des écrans de revue/apply/reject, résultat du pull après reconnexion, et SHA de la sauvegarde. Ne joindre aucun bearer, texte de conversation personnel, note, calendrier ou numéro de série en clair.

Après preuve, retirer uniquement l’activité synthétique identifiée, par le parcours normal Plan, si l’écran permet de vérifier sans ambiguïté qu’il s’agit de la fixture. Cette suppression crée une version de rollback distincte; synchroniser cette version et la noter. Si la fixture ne peut pas être isolée sans toucher au planning personnel, la laisser en place et marquer le nettoyage `BLOCKED`; ne pas restaurer une archive par-dessus l’état actif. Restaurer les toggles modifiés et la sémantique initiale du consentement `DAY_PLAN` seulement si le parent peut le faire sans ambiguïté; les versions de consentement restent monotones. Vérifier enfin le HOME, le transport et l’usage normal du téléphone.

## Ce qui reste impossible à prouver hors Pixel physique

- Le passage Coach V4 → transport relayé/direct réellement configuré → modèle distant et appels d’outils réels; les tests backend utilisent un fournisseur simulé.
- Le geste utilisateur de revue A0, le verrouillage contre l’application automatique dans l’UI, la mutation locale du vrai fichier de plan et la production d’un reçu local cohérent.
- La lecture par le worker du seul plan du jour, la persistance locale de l’inbox/ACK, la reconnexion réseau et la livraison WorkManager au Core réel.
- La compatibilité de cette version APK, ses migrations, sa signature et son comportement sur Android 17/API 37 physique.
- Le comportement avec le quota Core réellement courant, le relay/PC réellement configuré et la latence du modèle réel.

Verdicts de recette à reporter séparément : `PASS`, `FAIL`, `BLOCKED` ou `NOT_RUN`, chacun associé au SHA APK/source et aux preuves pertinentes. Aucun résultat Pixel n’est présumé par ce plan.
