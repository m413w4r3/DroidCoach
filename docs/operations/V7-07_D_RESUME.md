# ATLAS V7-07 — Reprise D sur Pixel : transport public PASS, plan revision NO-GO

**Date :** 2026-11-04
**Branche locale :** `feat/atlas-v7-07-preproduction` (aucun push/PR/merge)
**Pixel :** 9 Pro XL, Android 17 / API 37.1, serial hash
`f2e9258b85327628c8d25b1652b00601e698f9ccb2a8b23fb1e913ff355a5946`
**APK installé :** `c7b0f71f43680f4bb45d8c9e6b808713ea09ff5f826fd79db31acce8ff4355a0`
**Statut :** transport public, appairage et synchronisation V4 vérifiés ; **aucune
proposition A0 n'a pu être obtenue**. E non autorisé.

## 1. Préflight

| Contrôle | Résultat |
|---|---|
| ADB | un seul appareil, `45301FDAS001QT`, état `device` |
| Modèle / OS | Pixel 9 Pro XL, Android 17 / SDK 37 / SDK full 37.1 |
| Paquet | `dev.atlas.coach`, versionCode 1, versionName 0.1.0 |
| APK installé | hash `c7b0f71f…`, signature `da301466…eeee` |
| HOME | `dev.atlas.coach/.app.LauncherActivity` |
| Sauvegarde réelle | archive `5969966b…` et APK précédent `0d0ce524…` toujours disponibles et hashés |
| Données | aucune désinstallation, réinitialisation ou modification de masse |

Le premier scan ADB était vide car un ancien serveur ADB isolé (port 5038,
créé pour l’AVD) retenait l’interface USB. Ce serveur a été arrêté ; le serveur
par défaut 5037 a alors vu le Pixel. Aucun autre opérateur ADB.

## 2. Transport public

- DNS public `all.taildc7886.ts.net` : **PASS** depuis `1.1.1.1` et `8.8.8.8`
  (adresses publiques `176.58.90.46/63/145`).
- TLS avec SNI : **PASS** (`Verify return code: 0`).
- `curl https://all.taildc7886.ts.net:8443/health` : **200 `{"status":"ok"}`**.
- Proxy `127.0.0.1:4400` et connecteur PC démarrés ; métriques
  `relay_connected:true`.
- Pixel en **LTE, Wi-Fi désactivé, application Tailscale non exécutée**.
- Aucun `adb reverse`, aucun tunnel privé, aucun mock.
- L’application produit s’est connectée : UI **« Connecté — Le PC répond via
  le relais chiffré »**, session active côté connecteur (`active_sessions:1`).
- Le pinning de la clé publique du connecteur correspond à l’identité
  enregistrée ; l’identité `pixel-v7` est restée active et inchangée.

## 3. Appairage, consentements et plan synthétique

- Identité utilisée : `pixel-v7`, déjà installée dans le produit ; aucune
  nouvelle identité n’a été créée pour cette reprise.
- Consentements V4 explicites créés dans l’application :
  - `DAY_PLAN` **GRANTED v4** ;
  - `ACTIVITY_STATUS` **REVOKED v3** ;
  - les anciens `GRANTED v2` ne sont pas réutilisés.
- Plan synthétique du 2026-11-04 créé localement : la date était sans programme
  avant l'essai ; une seule activité « Test ATLAS - seance Focus », 10:00–10:45,
  Focus, priorité Importante, classe A1 `Personnel` déverrouillée. Aucun autre
  plan personnel n’a été modifié.
- Le plan v3 a été synchronisé dans Core : `plan_version=3`,
  `consent_version=4`, `local_date=2026-11-04`,
  `observed_at=2026-11-04T15:52:00Z`.
- Le consentement local A1 version 1 est actif pour cette activité.

## 4. Chat réel et tentatives de proposition

Toutes les requêtes ont utilisé le chemin produit : Pixel LTE → Funnel public
TLS → proxy 4400 → Relay/Noise → connecteur → Core → Gateway OpenClaw →
`openai/gpt-6-luna`. Le Gateway était en `profile=minimal`, plugin natif
désactivé ; les six outils client ont été catalogués dans les logs.

| Tentative | Résultat | Preuve |
|---|---|---|
| Message demandé, normalisé ASCII pour `adb shell input text` | **504** après 20 s ; `tool_call_count=1`, `tool_state=completed` | Core cache ; Gateway `stopReason=tool_calls` puis abort |
| Relance / clarification | **200**, `tool_call_count=0` ; le modèle répond que la séance de 10:00 est passée et demande clarification | Core cache ; UI Coach |
| Demande explicite d’appeler les deux outils | **400**, `tool_call_count=2`, `tool_state=partial`, aucune proposition | Core cache ; l’erreur applicative affichée est « serveur a refusé la requête relayée » |
| Après reconnexion et resynchronisation du plan v3 frais | **200**, `tool_call_count=0` ; le modèle affirme que les données sont anciennes et qu’une proposition est « en attente », sans proposition réelle | Core cache ; UI Coach |

Aucune proposition n’a été créée (`agent_proposals_v4 = 0`). Aucun APPLY,
REJECT ou ACK n’a été exécuté. Aucun double effet n’a été observé.

### Causes constatées ou probables

1. **Délai Core de 20 s** : le premier tour a réellement appelé
   `atlas_get_day_plan`, puis la seconde complétion modèle a été coupée par le
   délai Core (`504`). Le Gateway a enregistré un `caller-abort` et un
   `ClientDisconnectError`.
2. **Contexte de conversation persistant** : les tours suivants ont réutilisé
   le résultat d’outil du tour interrompu, avec `tool_call_count=0`. Le modèle
   n’a pas relu le plan et a répondu depuis l’historique.
3. **Fraîcheur de projection de 5 min** : `plan_revision.propose` exige un
   snapshot frais. La tentative avec `tool_call_count=2` et `partial` est
   compatible avec un rejet `STALE_DATA`, mais le code exact n’est pas exposé
   dans la réponse cache ; il est donc **INCONCLUSIVE** et non affirmé.
4. **Absence de contrôle « nouvelle conversation »** dans l’application
   produit : le `conversation_id` persiste et le Gateway conserve le contexte
   d’outil. Aucun mécanisme UI ne permet de repartir d’une session propre.
5. **Limite de l’outil ADB** : `adb shell input text` ne peut pas saisir les
   caractères non ASCII (NPE sur `é`/`—`). Le message a été normalisé en ASCII
   (« Decale cette seance... proposez-moi... ») ; la sémantique est conservée,
   mais le texte n’est pas byte-identique à la consigne.

## 5. État après la tentative

- Application : **Hors ligne** ; aucun transport actif.
- Proxy/connecteur : arrêtés ; `4400`/`4401` ne sont plus en écoute.
- Relay `4402` : toujours sain.
- Wi-Fi : réactivé ; mode avion 0 ; téléphone utilisable normalement.
- Plan synthétique et consentement A1/V4 : conservés localement pour une
  éventuelle reprise autorisée ; aucune donnée personnelle n’a été touchée.
- Core : plan synthétique v3 et consentement `DAY_PLAN` v4 conservés dans
  l’historique ; aucune proposition.
- Aucun `adb reverse`, aucune rotation de bearer, aucune modification Tailscale.

## 6. Verdicts

| Verdict | État | Portée |
|---|---|---|
| `GO_PRODUCT_REMOTE_CHAT` | **NO-GO** | le chat atteint le modèle et le transport, mais aucun tour réussi n’appelle les outils et ne produit une réponse outillée fiable |
| `GO_PRODUCT_PLAN_REVISION` | **NO-GO** | aucune proposition A0, aucun APPLY/REJECT/ACK |
| `GO_PRODUCT_REMOTE` | **NO-GO** | parcours produit distant incomplet |
| `GO_PRODUCT_LOCAL_SMOKE` | **GO_WITH_LIMITATIONS** | smoke local ciblé précédent toujours valide ; points distants non franchis |
| `GO_LIVE_SECURITY` | **GO_WITH_LIMITATIONS / P1 bearer** | révocation ciblée et consentements explicites ; bearer partagé non tourné |
| `GO_DAILY_PILOT` | **NO-GO** | D non franchi, E non autorisé |

## 7. Autorisation demandée pour une reprise ciblée

Deux voies sont possibles, sans modifier Tailscale, Core, Gateway, secrets ou
bearer :

1. **Rotation locale non destructive du `conversation_id`** de l’application
   (arrêt de l’app, nouveau UUID de conversation, conservation de l’historique
   existant), puis nouvelle tentative avec un plan frais. Cela teste si le
   défaut vient du contexte persistant. Une édition directe de préférence
   Android n’est pas autorisée par la procédure de QA ; elle nécessite votre
   accord explicite.
2. **Autorisation d’une modification produit bornée** pour ajouter un contrôle
   « nouvelle conversation » et/ou augmenter le délai Core de 20 s, avec
   nouvelle revue, build et installation. Cela dépasse D et exige une
   autorisation séparée.

Sans accord, le résultat reste **NO-GO distant précisément documenté** ; E ne
doit pas être lancé.
