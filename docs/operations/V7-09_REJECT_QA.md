# ATLAS V7-09 — Recette physique REJECT et preuves

**Date :** 2026-11-05
**Branche :** `feat/atlas-v7-09-reject-and-readiness`
**BASE_SHA :** `efcfdcd0936ab2791f8ea14c151247257b05d19b`
**APK installé :** `dev.atlas.coach` v1 / 0.1.0, SHA-256 `c7b0f71f43680f4bb45d8c9e6b808713ea09ff5f826fd79db31acce8ff4355a0`
**Certificat :** `da3014662eb65e50a87ef364a2e583c331398795b222e3b37b1a16553a00eeee`
**Appareil :** Pixel 9 Pro XL, Android 17 / API 37, `Europe/Paris`
**Opérateur ADB :** Paseo uniquement. Aucun sous-agent n'a exécuté de commande ADB.

Cette recette est une preuve **physique** du parcours :

`GPT-6 Luna → atlas_get_day_plan → atlas_propose_plan_revision → proposition A0 → inbox Android → refus explicite → ACK REJECTED durable → plan inchangé → rejeu idempotent`.

Elle ne réutilise ni la proposition APPLIED V7-08 `e867c13c…`, ni la proposition expirée `2da9c5e2…`.

## 1. Préflight

| Contrôle | Résultat observé |
|---|---|
| Appareil ADB | un seul appareil `device`, Pixel 9 Pro XL |
| Android | API 37, Android 17, fuseau `Europe/Paris` |
| Paquet | `dev.atlas.coach`, versionCode 1, versionName 0.1.0, `DEBUGGABLE` |
| APK installé | hash SHA-256 identique au candidat `c7b0f71f…` |
| Signature | certificat `da301466…eeee`, inchangé |
| HOME | `dev.atlas.coach/.app.LauncherActivity` (`isDefault=true`) |
| Réseau initial | Wi-Fi actif, mode avion 0, LTE enregistré |
| `adb reverse` | liste vide |
| Core | `atlas-core-atlas-core-1` healthy, image V7-08 |
| Core flags | `ATLAS_AGENT_DAILY_TOKENS_ENFORCED=false`, `ATLAS_CHAT_V4_DEADLINE_S=40` |
| Relay / Gateway | `atlas-v7-relay` actif `:4402`, Gateway actif `:18789` |
| Proxy / connecteur | arrêtés au départ (`4400/4401` fermés) |
| Consentements Core | `DAY_PLAN v4 GRANTED`, `ACTIVITY_STATUS v3 REVOKED` |
| Proposition V7-08 | `e867c13c…` APPLIED ; `2da9c5e2…` PENDING_ANDROID |
| Sauvegarde app-only | `v7-09-20261105T205330Z`, archive `9867e9ca…`, 25 fichiers, `VERIFY_OK` |

La sauvegarde a été prise après la création de la fixture du jour et avant les
mutations réseau/inbox. L'état pré-fixture (plan du jour inexistant, plan local
vide) est documenté ci-dessous ; la suppression de la fixture a été faite par
le parcours applicatif normal, sans restauration d'archive.

## 2. Proposition V7-08 expirée `2da9c5e2…`

**État Core réel, lecture seule :**

| Champ | Valeur |
|---|---|
| `proposal_id` | `2da9c5e2-57ed-4bac-8f5f-01eb0e4b780e` |
| `base_version` | 16 |
| `created_at` | `2026-11-04T19:28:58Z` |
| `expires_at` | `2026-11-05T19:28:58Z` |
| `state` | `PENDING_ANDROID` |
| ACK | aucun |
| Événement | aucun |

- L'échéance est dépassée depuis le **2026-11-05 à 19:28:58Z** (20:28:58 à
  Paris). Au moment de la recette, elle était expirée d'environ 1 h 30.
- Le pull V4 l'omet : elle n'a jamais été livrée à l'inbox Android.
- Aucun ACK `REJECTED` tardif n'a été tenté. Aucun ACK `EXPIRED` n'a été
  fabriqué. Aucun `expires_at` n'a été modifié. Aucun SQL d'écriture n'a été
  exécuté.
- Elle reste `PENDING_ANDROID` en Core : c'est un défaut de cycle de vie
  documenté par A1 (voir `evidence/V7-09_A1_CORE_AUDIT.md`).
- Verdict de clôture : **EXPIRED_BY_CLOCK / Core PENDING_ANDROID / REJECT
  NOT_RUN pour cette proposition**. La procédure V7-08 de reprise sur cette
  proposition est périmée.

## 3. Nouveau scénario synthétique du jour

Le worker V4 Android ne synchronise que le plan de la date système. Au
2026-11-05, le plan V7-08 du 2026-11-04 n'était plus éligible. Un nouveau plan
du jour a donc été créé par l'interface Plan, sans donnée personnelle.

| Élément | Valeur |
|---|---|
| Plan Core | `3d2dba67-7f9e-4600-99fd-9afae9f6d4e3` |
| Date locale | `2026-11-05`, `Europe/Paris` |
| Version avant fixture | plan du jour vide, v1 |
| Version après ajout | **v2**, révision `28d1c55c-7f28-44fb-a4ea-bec9218215fa` (préfixe `28d1c55c`) |
| Activité | `9245884e-50df-49b5-b6bf-91c9660a04fa` |
| Titre | `Test ATLAS - seance Focus` (translittération ASCII de « Test ATLAS — séance Focus ») |
| Horaire | 23:00–23:45, minute locale `1380`, 45 minutes, `PLANNED` |
| Consentement | `DAY_PLAN v4 GRANTED` |
| Projection pré-chat | `observed_at=2026-11-05T20:56:01.250780Z`, v2 |

La translittération ASCII du titre vient de la limite d'`adb shell input text`
(accents non fiables). L'identité de la fixture est portée par
`activity_id`, le plan et la version, pas par le titre.

Aucune activité personnelle n'a été remplacée, déplacée ou supprimée.

## 4. Transport public réel

| Composant | Preuve |
|---|---|
| Proxy local `4400` | `{"status":"ok"}` |
| Connecteur `4401` | `relay_connected=true`, `active_sessions=1` |
| Relay `4402` | `{"status":"ok"}` |
| Core `8100` | `{"status":"ok","mode":"openclaw"}` |
| Funnel public | `https://all.taildc7886.ts.net:8443/health` → HTTP 200 |
| Pixel | LTE, Wi-Fi désactivé, Tailscale Android non lancé, aucun `adb reverse` |

Le Pixel s'est connecté par le relais public : le compteur connecteur est passé
de `connections_opened_total=0` à `1`, puis `active_sessions=1`.

## 5. Conversation et modèle

- Une rotation unique et non destructive de `agentConversationId` a été
  effectuée, car l'ancienne conversation contenait un contexte R3B/V7-08 sans
  rapport avec le plan du jour.
- Procédure : sauvegarde déjà vérifiée, application mise en arrière-plan puis
  arrêtée, remplacement d'un seul champ dans `atlas_local_state.xml`, hash
  device identique à l'attendu (`69ae18fe…`), redémarrage.
- Ancien identifiant (préfixe) : `c86468d5…` ; nouveau : `dcf947e6…`.
- 24 messages, plans, consentements, identité Relay et Keystore préservés.
- « Conversation V4 » activée dans Coach avant le tour.
- Session Gateway neuve : `agent:atlas:openai-user:atlas-a66b4b81…`, dérivée du
  nouveau `conversation_id`.
- Modèle réel : `openai/gpt-6-luna` via Gateway OpenClaw, `profile=minimal`,
  6 outils client.

## 6. Tour A0 réel

Message envoyé depuis l'interface Coach du produit installé :

> « Pour aujourd hui, decale l activite Test ATLAS - seance Focus de 10 minutes
> et propose moi la modification a confirmer. Ne la applique pas
> automatiquement. »

(Texte ASCII, accents omis pour la saisie ADB.)

Chronologie UTC :

| Heure | Événement |
|---|---|
| `21:00:33` | envoi du message |
| `21:00:38` | Gateway : catalogue des 6 outils client |
| `21:00:45` | run 1 `stopReason=tool_calls` (`atlas_get_day_plan`) |
| `21:00:56` | run 2 `stopReason=tool_calls` (`atlas_propose_plan_revision`) |
| `21:00:59` | run final `stopReason=stop`, réponse annonçant 23:00 → 23:10 |
| — | `POST /v4/agent/chat` → **200 OK** |

Preuve Core (`agent_chat_cache`) :

```json
{
  "agent_status": "ok",
  "tool_state": "completed",
  "tool_call_count": 2,
  "proposal_ids": ["2a29a35f-23b4-4aae-9d28-2904320d6029"]
}
```

La proposition a été créée à `2026-11-05T21:00:57Z`, soit environ 4 secondes
avant l'expiration de la fenêtre de fraîcheur de 5 minutes du snapshot
`observed_at=20:56:01.250780Z` (limite `21:01:01.250780Z`). La marge était
faible mais le contrôle Core a accepté le snapshot ; aucun `STALE_DATA` n'a
été observé.

## 7. Proposition A0 nouvelle

| Champ | Valeur |
|---|---|
| `proposal_id` | `2a29a35f-23b4-4aae-9d28-2904320d6029` |
| `plan_id` | `3d2dba67-7f9e-4600-99fd-9afae9f6d4e3` |
| `base_version` | 2 |
| `confirmation_mode` | `A0_USER_REVIEW` |
| `auto_application` | `NONE_AT_CORE` |
| Changement | `reschedule` activité `9245884e…`, cible `1390` (23:10) |
| Diff | `before.start_minute=1380`, `after.start_minute=1390`, durée 45 |
| Créée | `2026-11-05T21:00:57Z` |
| Expire | `2026-11-06T21:00:57Z` |
| État initial Core | `PENDING_ANDROID` |

Aucune application automatique n'a eu lieu : le plan local et la projection
Core sont restés en v2 avec l'activité à 1380.

## 8. Réception dans l'inbox Android

Un work immédiat a été déclenché par le parcours documenté
« Plus → Partage backend → Enregistrer la connexion » (le bouton « Pousser les
projections V4 » reste grisé en mode relais, P2 connu).

- `POST /v4/agent/proposals/pull` → 200 OK.
- L'inbox locale contient alors deux entrées :
  - `e867c13c…` APPLIED (historique V7-08) ;
  - `2a29a35f…` RECEIVED, base 2, expiration `2026-11-06T21:00:57Z`.
- L'écran Plan → « Propositions reçues » affiche la proposition avec
  `A0 · revue utilisateur`, le programme v2 et le diff 23:00 → 23:10.

## 9. REJECT explicite

Action effectuée dans l'interface Android :

`Plan → Propositions reçues → Examiner les changements → Refuser définitivement`

- Le dialogue affichait : « Programme 3d2dba67 · version de base 2 »,
  « déplacer “Test ATLAS - seance Focus” de 23:00 à 23:10 · 45 min ».
- Le bouton « Confirmer et appliquer » n'a pas été utilisé.
- Heure du geste : `2026-11-05T21:02:53Z`.
- Immédiatement après : la proposition disparaît de l'inbox visible ; le plan
  affiche toujours 23:00–23:45 et « 1 ACK en attente de livraison ».

**Plan local avant/après :**

| Champ | Avant | Après |
|---|---|---|
| Version | 2 | **2** |
| Révision | `28d1c55c…` | `28d1c55c…` |
| Activité | `9245884e…` | `9245884e…` |
| Début | 1380 (23:00) | **1380 (23:00)** |
| Durée | 45 | 45 |

Aucune mutation du plan, aucune version appliquée.

## 10. ACK durable dans Core

ACK local :

| Champ | Valeur |
|---|---|
| `ack_id` | `9114cf22-b010-4ecb-93a3-1ad198f69ca7` |
| `proposal_id` | `2a29a35f-23b4-4aae-9d28-2904320d6029` |
| `sequence` | 1 |
| `state` | `REJECTED` |
| `result_code` | `USER_REJECTED` |
| `applied_plan_version` | absent / `null` |
| `revision_id` | `null` |
| `effect_ids` | `[]` |
| `occurred_at` | `2026-11-05T21:02:54.589Z` |
| `received_at` | `2026-11-05T21:03:16Z` |
| Livraison Android | `DELIVERED` |

Core après livraison :

- `agent_proposals_v4.state = REJECTED` ;
- `agent_proposal_events_v4` : **1 seul** événement pour `2a29a35f…` ;
- total des événements : **2** (1 APPLIED historique + 1 REJECTED nouveau) ;
- dernier snapshot du jour : **v2**, révision `28d1c55c…`, activité 1380,
  `observed_at=2026-11-05T20:56:01Z` — inchangé ;
- aucun `applied_plan_version`, aucun effet, aucune preuve d'application.

`POST /v4/agent/proposals/2a29a35f…/ack` → 200 OK.

## 11. Rejeu idempotent

Le worker Android ne renvoie pas un ACK déjà `DELIVERED` : un second
déclenchement de synchronisation n'a produit qu'un pull, sans second POST ACK.
L'absence de doublon a donc été vérifiée, mais cela ne constitue pas à soi seul
un rejeu.

Un **unique rejeu contrôlé** du corps ACK exact a ensuite été effectué sur le
même endpoint Core, avec le bearer Android existant (aucune rotation, aucun
secret journalisé) :

```http
POST /v4/agent/proposals/2a29a35f-23b4-4aae-9d28-2904320d6029/ack
Authorization: Bearer <existant, non journalisé>
Content-Type: application/json

{"contract_version":4,"schema_version":4,
 "ack_id":"9114cf22-b010-4ecb-93a3-1ad198f69ca7",
 "capability":"proposal.ack",
 "proposal_id":"2a29a35f-23b4-4aae-9d28-2904320d6029",
 "sequence":1,"state":"REJECTED","result_code":"USER_REJECTED",
 "occurred_at":"2026-11-05T21:02:54.589Z","consent_version":4}
```

Résultat :

- HTTP **200 OK** ;
- en-tête **`X-Atlas-Replayed: true`** ;
- corps identique renvoyé ;
- événements `2a29a35f…` toujours **1** ; total toujours **2** ;
- proposition toujours `REJECTED` ;
- plan local et snapshot Core toujours v2 / 1380.

Ce rejeu est un rejeu du **corps ACK réel persisté**, pas un ACK fabriqué et
pas un ACK antidaté. Il prouve l'idempotence Core ; il ne prétend pas que le
worker Android a renvoyé spontanément un ACK déjà livré.

## 12. Contrôles après REJECT

| Contrôle | Résultat |
|---|---|
| Persistance après redémarrage de l'app (rotation) | inbox et plan conservés |
| Déconnexion relay | « Hors ligne — Le PC n'est pas joignable. Les données restent locales et rien n'est renvoyé automatiquement. » |
| État pendant hors ligne | inbox 2 entrées / 2 ACK, plan v2 / 1380 conservés |
| Reconnexion relay | `active_sessions=1`, état inchangé |
| Doublon de proposition | aucun (`GROUP BY idempotency_key` vide) |
| Cache chat | 1 proposition, 2 appels d'outils, `tool_state=completed` |
| Consentements | `DAY_PLAN v4 GRANTED`, `ACTIVITY_STATUS v3 REVOKED` inchangés |
| Crash / ANR | aucun `FATAL EXCEPTION` ni `ANR in dev.atlas.coach` dans logcat |
| HOME | `dev.atlas.coach/.app.LauncherActivity` |
| Plan / Coach / Focus | écrans fonctionnels, pas de régression observée |

## 13. Nettoyage contrôlé

Après validation de l'ACK et de la sauvegarde :

1. L'activité synthétique du jour `9245884e…` a été supprimée par le parcours
   normal : Plan → « Plus » → « Supprimer l'activité » → confirmation →
   aperçu de révision → confirmation.
2. Le plan local est passé à **v3**, révision
   `136e3940-d865-4878-9e0c-b6138f7d95b6`, avec **0 activité**.
3. La projection Core a été resynchronisée : dernier snapshot
   `local_date=2026-11-05`, `plan_version=3`, `revision_id=136e3940…`,
   `observed_at=2026-11-05T21:07:04.186653Z`, **0 activité**.
4. Les traces d'audit Core des propositions et ACK ont été conservées.
5. Le plan historique du **2026-11-04** (6 activités synthétiques V7-08 :
   5 `POSTPONED` + 1 `PLANNED` à 1375) n'était plus accessible par l'écran
   Plan du jour. Il n'a pas été modifié par manipulation de fichier ni par
   restauration d'archive. Il est signalé comme résidu synthétique historique.
6. Aucun consentement historique, aucune identité Relay, aucune clé Keystore
   n'a été supprimé.

## 14. État final du Pixel et des services

| Élément | État final |
|---|---|
| Wi-Fi | restauré, connecté |
| Mode avion | 0 |
| Données mobiles | disponibles (LTE enregistré) |
| HOME | `dev.atlas.coach/.app.LauncherActivity` |
| `adb reverse` | aucun |
| Proxy `4400` / connecteur `4401` | arrêtés, ports fermés |
| Relay `4402` / Core `8100` / Gateway `18789` | laissés actifs (état de départ) |
| Application | utilisable en local et hors ligne |
| Données personnelles | aucune modifiée sciemment |

Le Pixel est libre et utilisable.

## 15. Écarts par rapport au plan A2 et limites

1. **Scénario du jour différent du plan A2.** A2 avait préparé la recette sur
   le plan V7-08 du 2026-11-04 (v16 / 1375). Le worker ne synchronisant que le
   jour courant, le 2026-11-05 exigeait un nouveau plan. Le scénario réel est
   donc v2 / 1380, avec la même activité synthétique et la même consigne
   +10 min. Ce n'est pas une réutilisation d'une proposition expirée.
2. **Actions UI par ADB.** Paseo, seul opérateur ADB autorisé, a utilisé
   `input tap` / `input text` pour la recette. A2 recommandait des gestes
   manuels ; la mission V7-09 autorise explicitement Paseo à opérer l'UI/ADB.
3. **Arrêt de l'app pour la rotation.** L'arrêt a été nécessaire pour écrire
   `agentConversationId` sans course avec l'état mémoire. Il n'y a eu ni
   `pm clear`, ni désinstallation, ni modification d'un autre champ.
4. **Marge de fraîcheur très faible.** La proposition a été créée environ
   4 secondes avant la limite de 5 minutes. Le tour a réussi, mais la
   reproductibilité exige un rafraîchissement de projection plus fiable
   (voir A3, point `a`).
5. **Rejeu ACK contrôlé hors worker.** Le worker ne rejoue pas un ACK
   `DELIVERED` ; le rejeu idempotent a été fait une seule fois par le même
   endpoint avec le corps exact. Cela prouve le Core, pas le comportement de
   rejeu spontané de l'app.
6. **Proposition expirée non clôturée.** `2da9c5e2…` reste
   `PENDING_ANDROID` ; aucune procédure native ne permet de la clôturer sans
   correction produit. Ne pas utiliser de SQL manuel ni fabriquer d'ACK.
7. **Résidu synthétique historique.** Le plan du 2026-11-04 reste dans les
   fichiers locaux et dans l'historique Core. Il est clairement synthétique.

## 16. Verdicts de recette

| Assertion | Verdict |
|---|---|
| Préflight appareil/APK/HOME/réseau | **PASS** |
| Sauvegarde app-only vérifiée | **PASS** |
| Ancienne proposition expirée identifiée et non réutilisée | **PASS** |
| Nouveau plan synthétique du jour | **PASS** |
| Consentement `DAY_PLAN` valide | **PASS** |
| Projection Core fraîche (< 5 min) | **PASS** (marge ~4 s) |
| Transport public LTE/Funnel/Relay/Noise | **PASS** |
| Modèle réel `openai/gpt-6-luna` | **PASS** |
| Outils réels `atlas_get_day_plan` + `atlas_propose_plan_revision` | **PASS** |
| Proposition A0 unique, `A0_USER_REVIEW`, `NONE_AT_CORE` | **PASS** |
| Réception inbox Android | **PASS** |
| REJECT explicite dans l'UI | **PASS** |
| Plan local inchangé (v2 / 1380) | **PASS** |
| ACK durable `REJECTED` / `USER_REJECTED`, sans version appliquée | **PASS** |
| Un seul événement ACK | **PASS** |
| Rejeu idempotent sans doublon | **PASS** (rejeu contrôlé Core) |
| Persistance après reconnexion | **PASS** |
| Hors ligne honnête | **PASS** |
| Nettoyage de la fixture du jour | **PASS** |
| Nettoyage du plan historique V7-08 | **NOT_RUN / résidu signalé** |
| Clôture Core de `2da9c5e2…` | **BLOCKED** (défaut de cycle de vie) |
