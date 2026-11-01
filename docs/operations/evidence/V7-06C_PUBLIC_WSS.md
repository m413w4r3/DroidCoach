# V7-06C — Transport public WSS depuis réseau extérieur

**Date :** 2026-11-01
**Résultat :** **APPLY et REJECT PASS** via `wss://all.taildc7886.ts.net:8443`
depuis le Pixel en **LTE**, sans `adb reverse`. Deux P1 documentés (fiabilité du
premier essai, observabilité de révocation A4-4).
**Code :** backend P2 `b33d43b`, harnais `3b9b3d1`, sonde Android `fa960d5`.
**Preuves privées :** `remote-apply2/v7-06c-apply-e4557615f525.json`,
`remote-reject/v7-06c-reject-be5168ed2070.json`, `remote-revoke/*`.
**Registre :** `docs/operations/REGISTRY_V7_06C.md`.

## 1. Modalité et autorisation

Le propriétaire a autorisé une tentative publique avec **relais isolé sur le port
4400**, déjà ciblé par le Funnel existant. Aucun changement Funnel/Tailscale, aucun
`sudo`, aucun secret n'a été nécessaire. Le relais live `atlas-v7-relay`
(`127.0.0.1:4402`) et le Core/Gateway live n'ont pas été utilisés ni modifiés.

## 2. Réseau et endpoint

| Élément | Constat |
|---|---|
| Appareil | Pixel 9 Pro XL, Android 17 / API 37, arm64-v8a ; série hashée `f2e9258b…` |
| Réseau extérieur | LTE `MOBILE[LTE]`, réseau 151, Wi‑Fi désactivé ; DNS public résolu ; ping Funnel OK |
| Endpoint | `wss://all.taildc7886.ts.net:8443` |
| DNS | `all.taildc7886.ts.net` résolu depuis le téléphone |
| TLS | `https://all.taildc7886.ts.net:8443/health` → HTTP 200, `ssl_verify_result=0` |
| Relay | conteneur isolé `atlas-paseo-relay:r3a` (digest épinglé) publié `127.0.0.1:4400` |
| Funnel | inchangé `tcp://all.taildc7886.ts.net:8443 → 127.0.0.1:4400` ; après arrêt, cible de nouveau sans listener |
| Exposition | Core `8111`, Gateway `19076`, proxy `19075` en loopback uniquement ; aucun port Core/Gateway public |
| `adb reverse` | **absent** sur APPLY et REJECT (`requested=false`, `added=false`) |
| Noise | Noise NK protégé, `server_public_key` épinglée, session device authentifiée |
| Capabilities | session V4 authentifiée, opérations autorisées uniquement |

## 3. APPLY public — PASS

| Élément | Valeur |
|---|---|
| Stack | `android-apply-1940890-6120a0` |
| Paquet QA | `dev.atlas.coach.qa.v706b.v706capply.f1e2d3c4b5a6` |
| APK app / test | `92fb97087e91de682747532af31711c08573321d5f7fa80d9fefcb9a407c337f` / `57321867b06dc00956f65f34df59feab518501b47b387095102a1aad3eb25ca2` |
| Runner | `V7_06B_RUNTIME_PASS`, 12 étapes, `ack_replay=identical` |
| Proposition | `26240af5-7309-4f4b-9ea1-af6f0920ef25`, `APPLIED`, `base_version=2`, `A0_USER_REVIEW`, `NONE_AT_CORE` |
| Plan | `5d939d3e-b6c2-45e8-9db9-1fb7106a5faa` ; v2 start=600, v3 start=610 |
| ACK | `80962380-afbf-4ce9-b045-a6838592ab96`, `APPLIED`, `applied_plan_version=3`, 1 événement |
| Consentements | `DAY_PLAN` et `ACTIVITY_STATUS` `REVOKED` v3 |
| Trace outils | 3 tours proxy, 6 outils, `atlas_get_day_plan` + `atlas_propose_plan_revision`, corrélation, `stop` |
| Réseau | `public_wss`, endpoint `all.taildc7886.ts.net:8443` |

## 4. REJECT public — PASS

| Élément | Valeur |
|---|---|
| Stack | `android-reject-1968579-464043` |
| Paquet QA | `dev.atlas.coach.qa.v706b.v706creject.bf5b0d3e2c4a` |
| APK app / test | `7759c26b18f0c58bbf94fb5b3c21583c5ad5cee2647d7825d1f488becb89c36b` / `c6eebb6a2631f71bb0079aafa91e7d1832c1148804f5cfa6882068cc3efc4aed` |
| Proposition | `9adb680d-9cb9-429b-92ac-9ceffc76e1bb`, `REJECTED`, `base_version=2`, `A0_USER_REVIEW`, `NONE_AT_CORE` |
| Plan | `faac0fc9-0390-40ee-866e-28cbcc761f81`, v2 conservé start=600 ; **aucun v3** |
| ACK | `6122332c-0066-4ec7-b941-ea253c866972`, `REJECTED`, `applied_plan_version=NULL`, 1 événement |
| Consentements | `REVOKED` v3 |
| Trace outils | 3 tours, 6 outils, corrélation, `stop` |
| Réseau | `public_wss`, endpoint `all.taildc7886.ts.net:8443` |

## 5. Révocation du device QA — refus serveur, observabilité P1

Le device `968aff85-ab1e-4b50-a398-10085e1d2c25` a été révoqué dans le registre du
connecteur isolé (`revoked_at=2026-11-01T14:05:07Z`). La sonde instrumentée
`V7_06C_RevokedDeviceReconnectInstrumentedTest` a reconnecté l'app QA existante :
elle doit observer un refus explicite.

| Oracle | Constat |
|---|---|
| Registre | `revoked_at` posé pour le device ; aucune autre identité |
| Connexions relay | `connections_opened_total=2` |
| Sessions authentifiées | `sessions_opened_total=1` — **aucune nouvelle session** |
| Erreurs | `errors_total=1` |
| État app | `state=ERROR`, `close_code=null`, credentials présents |
| Résultat sonde | **FAIL** — `explicit_refusal_not_observed` ; pas de `REVOKED`/`AUTH_REQUIRED` ni code 4001 |

Conclusion : la révocation est **effective côté serveur** (le device révoqué ne peut
pas ouvrir de session ni exécuter d'opération), mais l'observabilité client A4-4 est
défectueuse. Le code de fermeture effectivement observable côté app est **`null`**
(état `ERROR`) ; le 4001 attendu et le défaut historique 1012 ne sont pas exposés
proprement. Le test n'est pas transformé en PASS.

## 6. Incident de fiabilité — blocage intermittent avant chat (P1)

Au **premier** essai APPLY public (stack `android-apply-1853166`), le test a franchi
les consentements, le plan v2 et le pull, persisté le message USER, puis **aucune
requête `v4.coach.chat.request`** n'a atteint le connecteur. Le runner hôte a été
coupé après 540 s ; l'instrumentation est restée active jusqu'à `am force-stop`.

Faits : journal connecteur = 2 consentements + 1 snapshot + 1 pull, aucun chat ;
session relay ouverte ; conversation locale = 1 message USER ; aucune proposition.
Une relance sur stack neuve a **PASS** (APPLY public).

Hypothèse dominante non confirmée : inversion de verrous `consentGate` ↔ WebSocket
(`AgentContextCoordinator.consentStillAllows` acquiert la gate puis son moniteur ;
`RelayBackendTransport.pump` tient la gate pendant `beforeDispatch`). Le P2 V7-06A
signalait déjà cette inversion « non reproduite » ; V7-06C la reproduit une fois.
Aucun thread-dump n'a été obtenu (debuggerd root requis ; le second essai a passé
avant la fenêtre de dump).

## 7. Limites

- Le transport public a été testé sur un paquet QA, pas sur l'application produit.
- Le Funnel existant ciblait déjà 4400 ; aucun changement Funnel/Tailscale n'est
  revendiqué. Le relais live 4402 n'a pas été utilisé.
- Le premier essai public a échoué par blocage intermittent ; un seul retry a réussi.
- La sonde de révocation est FAIL au sens observabilité ; le refus serveur est
  confirmé par les métriques du connecteur.
- `GO_PIXEL_PRODUCT` et `GO_PRODUCT_REMOTE` restent **NO GO**.
