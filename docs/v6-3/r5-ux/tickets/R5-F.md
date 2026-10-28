# R5-F — Régime textuel Coach & Contexte du coach

| Clé | Valeur |
|---|---|
| TASK_ID / statut | `R5-F / READY` |
| Catégorie | Implémentation UI bornée |
| Depuis | `e12a8bf5c37ccd1db1a6022fda4d177e5e9c11a8` |
| Profil exigé | `codex:gpt-6-luna`, `effort=xhigh`, vérifié dans les métadonnées |
| Worktree | Paseo-managed, branche `feat/r5-f-coach-text`, aucune sous-délégation |
| Propriété d'écriture | `android/app/src/main/java/dev/atlas/coach/app/AgentCoachScreen.kt`<br>`android/app/src/main/java/dev/atlas/coach/app/CoachContextScreen.kt`<br>`android/app/src/androidTest/java/dev/atlas/coach/app/CoachTextDensityInstrumentationTest.kt` (nouveau) |
| Lecture | tout le dépôt en lecture seule ; en particulier `AtlasApp.kt`, tests `AtlasLaunchTest`, `LotEUiInstrumentationTest`, `R3bRelayChatInstrumentedTest`, `V62RealGatewayContextBridgeTest` |

## Objectif observable

Réduire la densité textuelle de l'écran **Coach** et de **Contexte du coach** sans retirer aucune information de sécurité, de consentement, de permission, de révocation ou de confirmation. Le propriétaire doit voir la conversation et les actions en premier, les explications seulement à la demande.

## Règles de contenu

1. **Une ligne par défaut, détail sur demande.** Les paragraphes explicatifs deviennent un titre court + état + action ; le texte long passe derrière un bouton `Détails` / `Avancé` ou disparaît s'il ne fait que répéter l'interface.
2. **Jamais supprimés** (mais raccourcis et placés au bon moment) : consentement de localisation, consentement de résumé, consentement UsageStats, explication de révocation, avertissement sur la suppression d'historique, confirmation d'acceptation d'une proposition, erreur TLS/révocation/consentement, quota de notifications.
3. **Ne pas inventer de nouveaux réglages.** Pas de nouvelle permission, pas de nouvelle capability, aucun envoi réseau.
4. **Préserver les test tags existants** utilisés par les suites : `agent_connection_status`, `agent_refresh`, `agent_message_input`, `agent_send_message`, `agent_chat_send_status`, `agent_open_context`, `agent_priority_proposal_<id>`, `agent_settings_link`, `agent_sharing_link`, `agent_effects_link`, `agent_next_context_payload_toggle`, `agent_last_shared_context_payload_toggle`, `agent_delete_context_history`, `proactive_proposal_unavailable`.
5. **Accessibilité** : cibles ≥ 48 dp, texte lisible à police 2×, pas de texte tronqué porteur de sens, `contentDescription` si un libellé devient une icône.
6. **Pas de refonte de la navigation.** L'écran reste `AgentCoachScreen` + `CoachContextScreen` aux mêmes routes.

## Cibles concrètes (indicatives, pas exhaustives)

- Bandeau de connexion : libellé humain + une ligne d'état ; `Détail technique` conservé et replié ; `Actualiser` compact.
- Conversation : supprimer le titre redondant si le cadre suffit ; état vide en une phrase ; supprimer le texte d'aide sous le champ (la limite de 2 000 caractères reste appliquée et peut être un `supportingText` d'une ligne) ; libellé du bouton d'annulation raccourci sans masquer la conséquence.
- Proposition : motif, provenance et état compactés ; **la confirmation reste explicite** et les quatre actions restent visibles.
- Contexte : intro en une ligne ; `Contexte du prochain envoi` replié par défaut avec compteurs essentiels visibles ; JSON derrière les boutons existants ; historique et suppression conservés ; `Tâches en arrière-plan` compactées mais consentements/quota visibles.
- Objectifs/localisation : les formulaires et consentements restent complets ; réduire seulement les phrases d'accompagnement.

## Oracle

1. **Avant/après** : produire dans le livrable une liste `texte supprimé / raccourci / déplacé` avec fichier et fonction. Vérifier qu'aucun texte de sécurité n'est dans la colonne « supprimé ».
2. `cd android && ./gradlew :app:testDebugUnitTest --rerun-tasks` → PASS.
3. `cd android && ./gradlew :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest` → PASS.
4. Le nouveau test instrumenté doit compiler et vérifier au minimum : le nombre de nœuds texte visibles par défaut a diminué sur Contexte, les consentements restent actionnables, `agent_open_context` reste visible, et la confirmation de proposition reste visible quand une proposition existe. Le parent l'exécutera sur AVD API 37.
5. Aucune régression des tags listés ci-dessus.

## Stop conditions

- Modèle/effort effectif différent de `gpt-6-luna` / `xhigh` → `BLOCKED`.
- Conflit d'écriture hors des deux fichiers propriétaires → arrêter et signaler.
- Un texte de sécurité ne peut être raccourci sans changer le sens → le conserver intégralement et le noter.
- Test JVM/lint échouant pour une cause hors périmètre → rapporter la sortie exacte, ne pas modifier les tests non concernés.

## Livrables

- Un commit unique sur `feat/r5-f-coach-text`, SHA complet.
- Liste avant/après des textes.
- Sorties exactes des commandes, statut `PASS/FAIL/BLOCKED/NOT_RUN` par oracle.
- Fichiers modifiés et limites.

## Revue parent

`ACCEPTED / CORRECTION_REQUIRED / BLOCKED`, puis intégration locale et relance des tests sur le SHA intégré.
