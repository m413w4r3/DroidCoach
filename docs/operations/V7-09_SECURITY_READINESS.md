# ATLAS V7-09 — Sécurité et fiabilité : état et plan

**Date :** 2026-11-05
**Branche :** `feat/atlas-v7-09-reject-and-readiness`
**BASE_SHA :** `efcfdcd0936ab2791f8ea14c151247257b05d19b`
**Portée :** synthèse des audits A1 et A3, plan de correction du P1 bearer
Android partagé et classification des défauts de fiabilité V4.
**État :** aucun changement live n'a été appliqué. Aucun bearer, secret,
Funnel, Serve, ACL, Gateway, modèle ou identité Relay n'a été modifié.

Les rapports détaillés sont :

- `docs/operations/evidence/V7-09_A1_CORE_AUDIT.md` — cycle de vie Core A0,
  expiration, pull, ACK, idempotence ;
- `docs/operations/evidence/V7-09_A3_SECURITY_READINESS.md` — P1 bearer,
  architecture cible et fiabilité V4 ;
- `docs/operations/V7-09_REJECT_QA.md` — preuves physiques V7-09.

## 1. Verdict sécurité

- **P0 : aucun défaut P0 confirmé.**
- **P1 : bearer Core Android partagé, toujours ouvert.** Le connecteur utilise
  le même bearer pour toute session Relay appairée ; le Core dérive `device_ref`
  de ce bearer. Un porteur du bearer peut donc agir sous la même identité
  d'appareil. Le risque est préexistant, non introduit par V7-09.
- **P1 : proposition expirée jamais tirée.** `2da9c5e2…` reste
  `PENDING_ANDROID` en Core, sans ACK, car le pull l'omet et aucun balayeur
  serveur ne la clôture. Voir §3.
- **Pilote quotidien : NO-GO** tant que le P1 bearer n'est pas traité et que le
  pull immédiat en mode relais n'est pas corrigé.

Le REJECT V7-09 prouve le parcours fonctionnel malgré ces risques. Il ne les
réduit pas.

## 2. P1 — bearer Android partagé

### 2.1 Constat

| Élément | Constat |
|---|---|
| Bearer Android | un seul bearer Core, chiffré sur l'appareil avec l'alias Keystore `atlas_backend_bearer_v1` |
| Connecteur PC | lit `ATLAS_V4_ANDROID_TOKEN_FILE` et l'utilise pour toutes les sessions Relay appairées |
| Core | `device_ref` = dérivation du bearer ; pas de registre bearer → principal → capabilities |
| Identité Relay | `pixel-v7` active, distincte du bearer Core |
| Token outil / Gateway | distincts ; hors périmètre de rotation |

Conséquence : le bearer est un secret partagé entre l'app Android et le
connecteur. Sa compromission ne se limite pas à un appareil et ne peut pas être
révoquée sans couper le chemin Relay actuel.

### 2.2 Architecture cible

1. **Principal stable par appareil** (`pixel-v7`) côté Core, avec un hash de
   bearer propre à l'appareil et des capabilities explicites.
2. **Mapping Relay → principal** : le connecteur traduit l'identité Relay
   authentifiée vers le credential Core du principal. Il ne choisit jamais un
   credential depuis le payload client.
3. **Résolution Core** : bearer vérifié → principal → `device_ref` stable. Un
   bearer inconnu, révoqué ou associé à un autre principal échoue en 401/403,
   sans repli.
4. **Historique préservé** : les données attribuées à l'ancien bearer restent
   dans leur espace historique ; pas de réattribution silencieuse. Les
   consentements historiques ne valent pas consentement courant pour le
   nouveau principal.
5. **`pixel-v7` préservé** : le token de pairing Relay et l'identité Noise du
   serveur ne sont ni tournés ni révoqués pendant la migration. Seul le bearer
   Core partagé est révoqué, après cutover vérifié.

### 2.3 Migration proposée (non exécutée)

| Étape | Action | Oracle |
|---|---|---|
| 0 | Implémenter hors live le registre bearer → principal → capabilities, le mapping Relay → principal et le provisioning idempotent. Maintenir temporairement l'ancien bearer accepté pour le rollback. | Token `pixel-v7` accepté seulement pour ses routes ; token inconnu/révoqué/mauvais pairing → 401/403 ; aucun principal fourni par le payload. |
| 1 | Sauvegarder Relay (registre, identité Noise, journal) et Core (SQLite cohérent, WAL) hors dépôt, avec manifeste et hashes. Sauvegarder l'app Android. | Restauration vérifiée sur copie isolée ; aucun secret dans les preuves ; réconciliation des compteurs. |
| 2 | Provisionner un bearer aléatoire propre à `pixel-v7` et le transmettre par canal sécurisé. Ne stocker que son hash côté serveur. | Un credential distinct par principal ; `pixel-v7` toujours actif ; token outil/Gateway distincts. |
| 3 | Mettre à jour l'app **in-place**, même `applicationId` et signature. Écrire le nouveau bearer dans la préférence chiffrée existante. Ne pas exporter ni recréer la clé Keystore. | Plans, inbox, ACK, consentements, pairing Relay et alias Keystore préservés ; aucun effacement. |
| 4 | Valider les deux chemins (Relay et direct) sous le nouveau principal. Reconfirmer `DAY_PLAN` sous le nouveau principal avant toute projection. | Nouveau bearer accepté, `pixel-v7` non révoqué, ancien `device_ref` conservé, aucun `GRANTED` historique réutilisé implicitement. |
| 5 | Révoquer l'ancien bearer partagé après validation complète. Ne jamais inclure `pixel-v7` dans la révocation. | Ancien bearer → 401 depuis un harness isolé ; nouveau bearer fonctionnel ; session Relay `pixel-v7` toujours ouvrable ; aucun repli. |

**Rollback :** avant révocation, désactiver le mapping et conserver l'ancien
bearer et `pixel-v7` ; revenir à l'APK précédent par mise à jour in-place si
nécessaire. Après révocation, ne pas réactiver l'ancien bearer depuis une
sauvegarde ; revenir en local/offline ou provisionner un nouveau bearer propre.

**GO migration** exige : registre par appareil, sauvegarde/restauration
testées, mise à jour in-place, préservation Keystore/pairing/inbox/consentements,
consentement courant explicite sous le nouveau principal, preuve de révocation
fail-closed, identité Relay inchangée.

**NO-GO** si le Core n'accepte qu'un bearer global, si le connecteur le réutilise
pour tous les pairings, si la restauration dépend d'une clé Keystore exportée,
ou si `pixel-v7` doit être révoqué pour que la migration fonctionne.

**Aucune de ces étapes n'a été exécutée en V7-09.** Le bearer n'a pas été
tourné ; `pixel-v7` n'a pas été révoqué ; aucun secret n'a été copié dans le
dépôt.

## 3. P1 — cycle de vie des propositions expirées

A1 a établi, preuves code à l'appui :

- TTL V4 par défaut : **24 heures** à la création, sans override de production.
- Le pull filtre `expires_at > now` et **omet** les propositions expirées sans
  changer leur état.
- Un nouvel ACK `APPLIED` ou `REJECTED` après expiration reçoit
  **409 `PROPOSAL_EXPIRED`**.
- Seul un ACK `EXPIRED / PROPOSAL_EXPIRED` accepté met l'état Core à `EXPIRED`.
- Aucun balayeur périodique, aucun trigger SQLite, aucune expiration paresseuse
  côté serveur.

Conséquence observée en live : `2da9c5e2…` est expirée par l'horloge depuis le
`2026-11-05T19:28:58Z`, n'a jamais été livrée à l'inbox Android, et reste
`PENDING_ANDROID` en Core. Aucun ACK n'a été émis. La clôture native est
**impossible sans correction produit** ; il ne faut ni SQL manuel, ni ACK
antidaté, ni ACK `EXPIRED` fabriqué.

**Correction recommandée (non appliquée) :** expiration serveur paresseuse ou
balayeur idempotent qui passe les propositions échues à `EXPIRED`, avec
événement d'audit append-only et sans reçu Android inventé. Un ACK `EXPIRED`
ne devrait être accepté qu'après `expires_at <= now`.

Risques P2 associés relevés par A1 :

- ACK `EXPIRED` accepté avant l'échéance (garde temporelle absente) ;
- après expiration, un corps différent sous un `ack_id` existant renvoie
  `PROPOSAL_EXPIRED` avant `ACK_ID_CONFLICT` ;
- la preuve `APPLIED` est validée en forme mais non reliée au plan/version
  stockés de la proposition.

## 4. Fiabilité V4 — classification

`a` = nécessaire avant le pilote E ; `b` = correction souhaitable à court
terme ; `c` = dette P2 acceptable documentée. Les correctifs sont conceptuels
et ne sont pas appliqués.

| Point | Classe | Constat et correctif minimal |
|---|---|---|
| Pull immédiat en mode relais | **a** | Le bouton « Actualiser » de Coach ne fait aucun appel HTTP en RELAY ; le worker périodique est à 15 min et best effort. « Enregistrer la connexion » est un contournement caché. Ajouter une action de pull V4 explicite avec état (`en attente/en cours/terminé/échec`). Oracle : un tap déclenche le transport, l'inbox affiche la proposition ou une erreur vérifiable, sans doublon. |
| Toggle « Conversation V4 » au cold start | **b** | Flag mémoire initialisé à `false`, non persisté. Persister l'opt-in ou afficher clairement qu'il est désactivé à chaque lancement. Oracle : après cold start, l'état affiché et la route négociée concordent, sans repli V2 silencieux. |
| Inbox et propositions expirées | **b** | Une proposition expirée déjà locale reçoit un ACK `EXPIRED` au prochain worker ; une proposition expirée jamais reçue reste invisible et non clôturée. Ajouter une transition terminale durable au pull, sans la rendre examinable. Oracle : aucune application possible ; ACK durable même hors ligne ; Core terminal au prochain replay. |
| `STALE_DATA` → HTTP 409 dans le chat | **b** | Le mapping général V4 classe `STALE_DATA` en 409, mais `_chat_error_response` retombe sur 400 et le corps avec outils ne porte pas `error.code`. Ajouter le code et un message stable. Oracle : même cause → 409 + `STALE_DATA`, autres codes inchangés. |
| Exposition sûre des codes d'erreur | **b** | Le corps d'échec après outil perd le code applicatif. Ajouter un champ borné à l'enum autorisé, avec messages génériques et request id corrélable, sans exception provider, prompt, argument, token ni donnée personnelle. |
| Annulation des outils synchrones après deadline | **c** | `Future.cancel()` n'interrompt pas un callable déjà lancé. Dette acceptable si un timeout après effet reste `uncertain`, sans retry automatique. Correction ultérieure : annulation coopérative et idempotence persistante. |
| Cohérence des snapshots DayPlan / versions | **c** | Les barrières snapshot/version sont présentes ; aucun défaut confirmé dans le chemin audité. Ajouter un test de concurrence/régression de version dans une tranche ultérieure. |

## 5. Risques résiduels et gates

| Risque | Gravité | Effet | Gate |
|---|---|---|---|
| Bearer Android partagé | **P1** | Accès Core sous le même `device_ref` pour tout porteur | `GO_LIVE_SECURITY` = GO_WITH_LIMITATIONS ; `GO_DAILY_PILOT` = NO-GO |
| Proposition expirée jamais tirée | **P1** | État Core non terminal, clôture native impossible | `GO_PRODUCT_PLAN_REVISION` = GO_WITH_LIMITATIONS |
| Pull immédiat relay | **a** | Dépendance au workaround ou au worker 15 min | `GO_PRODUCT_REMOTE_CHAT` = GO_WITH_LIMITATIONS |
| Toggle V4 non persistant | b | Risque d'oubli avant un tour | limitation documentée |
| `STALE_DATA` → 400 | b | Diagnostic/retry moins précis | limitation documentée |
| Annulation synchrone | c | Effet incertain après timeout | dette P2 acceptée |
| Marge de fraîcheur 5 min | b | Tour A0 peut échouer si la projection vieillit | limitation V7-09 |

Aucun de ces points n'est corrigé par V7-09. Le pilote quotidien de sept jours
reste **NO-GO et non autorisé**.
