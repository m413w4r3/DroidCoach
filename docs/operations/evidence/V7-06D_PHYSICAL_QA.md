# V7-06D — Qualification physique complémentaire (Pixel 9 Pro XL / API 37)

**Date :** 2026-11-01
**Appareil :** Pixel 9 Pro XL physique, Android 17, SDK 37 (full `37.1`), patch
de sécurité `2026-09-05`, non-émulateur. Serial et empreinte complets non
publiés : `serial_sha256=f2e9258b85327628c8d25b1652b00601e698f9ccb2a8b23fb1e913ff355a5946`,
`fingerprint_sha256=a44a971009b24780b39f441fd2f0b23c33bbb16682408cb3cf57ae626069f2a6`.
**SHA de code :** `2fa90bd618a4ed36bb652fda49b1c1ec8222e34b`
(code Android/Node/backend identique à `bfb1965` ; delta docs-only).
**Branche :** `feat/atlas-v7-06d-remote-hardening`, aucun push/PR.
**Autorisation ciblée :** A4-4 + fiabilité chat ; paquet QA uniquement, stack
isolée, identités synthétiques, sauvegarde app QA, désinstallation QA à la fin ;
aucun changement HOME, Keystore, données personnelles, `dev.atlas.coach` ou
service live.

## 1. Pré-vol et sauvegarde

- Appareil déverrouillé (`isKeyguardShowing=false`, `mWakefulness=Awake`), ADB en
  `uid=2000(shell)`, aucun root.
- HOME avant/après : `dev.atlas.coach/.app.LauncherActivity` (application
  produit). Aucun changement de rôle HOME.
- Application produit `dev.atlas.coach` version `0.1.0`, `lastUpdateTime`
  `2026-10-31 00:18:13`, inchangée. Aucun accès à ses données.
- Aucun paquet `dev.atlas.coach.qa*` avant la phase ; les paquets QA créés ont
  été désinstallés après chaque tentative.
- Sauvegarde : **aucune donnée QA préexistante** (`NO_PRIOR_QA_DATA`) ; le paquet
  QA est neuf et ses données sont synthétiques. Aucune archive produit n'a été
  créée ou restaurée. État et preuves privés en `0600` hors dépôt.
- Stockage : `32G` libres sur `/data/user/0`.

## 2. A4-4 — sonde de révocation sur appareil

Stack isolée `android-apply`, device QA synthétique
`device_id_sha256=309d90e4bec07e799a260a76143506807875cc6ab8dee6e704f19c4baa80eddb`,
révoqué dans le registre du connecteur isolé (aucune autre identité). Sonde
`V7_06C_RevokedDeviceReconnectInstrumentedTest`, lecture seule côté appareil,
avec `adb reverse` local temporaire vers le relay isolé.

| Oracle | Constat |
|---|---|
| Instrumentation | **PASS** — `OK (1 test)`, 0,667 s |
| État observé | **`REVOKED`** |
| Type d'échec observé | **`REVOKED`** |
| `close_code` observé | **`null`** (le relais transforme le close serveur 4001 ; la trame applicative authentifiée porte le refus) |
| Identifiants configurés | `true` |
| Connexions relay avant → après | `1` → `2` (une tentative refusée) |
| Sessions authentifiées avant → après | `1` → **`1`** (aucune nouvelle session) |
| Erreurs avant → après | `0` → `1` |
| Sessions actives après | `0` |
| Ligne de preuve | `v7_06c_revoked_reconnect result=PASS state=REVOKED failure=REVOKED close_code=null credentials=true` |

**Conclusion :** la sonde strictement prévue est **PASS**. Le refus est explicite
et provient de la trame authentifiée ; aucune session n'est ouverte. La limite
`close_code=null` reste documentée et n'est pas maquillée en 4001/1012.
**Verdict : `GO_A4_4_CLIENT` = GO** (périmètre : un Pixel 9 Pro XL API 37,
stack isolée, réseau local ADB reverse ; pas de qualification publique).

## 3. Série de démarrages froids / chat

Cinq tentatives indépendantes, chacune avec une stack `android-apply` neuve, un
paquet QA neuf, une identité synthétique et un build QA propre. Chaque tentative
exécute le parcours complet consentements → plan v2 → chat → proposition →
APPLY/ACK, avec vérification des APK installés avant instrumentation.

| # | Résultat | Paquet QA (hash) | Preuve | Détail |
|---|---|---|---|---|
| 1 | **PASS** | `4d47ccf4…` | proof `sha256=8c9819e6…` | APPLIED, ACK APPLIED, replay identique ; 3 outils requis retournés |
| 2 | **FAIL** | `43595037…` | pas de proof | `ANDROID_RUNNER_PASS_MARKER_MISSING` ; `IllegalStateException` à `parseToolMetadata` (ligne 641) ; même signature que #3, app-state non capturé |
| 3 | **FAIL** | `23c98c30…` | pas de proof | même exception ; app-state capturé : `tool_state=completed`, `tool_call_count=2`, **0 proposition** ; le modèle indique que l'appel de proposition a été refusé localement puis rejeté par le service |
| 4 | **PASS** | `2a16099f…` | proof `sha256=12175f4b…` | APPLIED, ACK APPLIED, replay identique ; 3 outils requis retournés |
| 5 | **PASS** | `f1ec5369…` | proof `sha256=7b099722…` | APPLIED, ACK APPLIED, replay identique ; 3 outils requis retournés |

**Résultat brut : 3 PASS / 2 FAIL.** Les deux échecs sont survenus après la
réponse du modèle, pas avant l'émission du chat :

- les stages `BEFORE_DISPATCH`, `SOCKET_SEND_ACCEPTED`, `RESPONSE_RECEIVED`,
  `CORRELATED` et `UI_RESULT=SUCCESS` sont présents dans les tentatives en
  échec ; le blocage G↔C de V7-06C n'est **pas** reproduit ;
- l'échec #3 montre une réponse assistant valide avec **zéro proposition** :
  l'appel `atlas_propose_plan_revision` a été émis puis rejeté par le service,
  et le modèle n'a pas retenté ; le test échoue ensuite dans son parseur de
  métadonnées, qui ne sait pas lire la forme légitime « Aucune proposition (0) » ;
- l'échec #2 présente la même exception et le même enchaînement ; son app-state
  n'a pas été capturé avant nettoyage.

**Verdict : `GO_CHAT_RELIABILITY_QA` = NO GO.** Le transport tient, mais le
parcours coach complet n'est pas fiable sur cette série (2 échecs sur 5). Le
parseur de test est un défaut de diagnostic P2 qui masque le vrai échec ; le
refus de l'outil de proposition est un nouveau blocage de fiabilité à traiter
dans un ticket séparé. Aucun `PASS` global n'est revendiqué.

## 4. Provenance APK installée sur appareil

Le runner a vérifié, avant chaque `am instrument`, le manifeste embarqué, les
métadonnées, le certificat et les SHA-256 des APK installés contre les APK
construits. Les cinq tentatives ont franchi ce gate (elles ont toutes exécuté
l'instrumentation) ; les trois preuves PASS le documentent explicitement.

| Élément | Constat |
|---|---|
| Source / arbre | `2fa90bd6…` / `40c9e5bc…`, `worktree_clean=true` |
| Variante / SDK | `qa` / compile `37.2`, min `26`, target `36` |
| Version | code `1`, nom `0.1.0-qa` |
| Certificat | `da3014662eb65e50a87ef364a2e583c331398795b222e3b37b1a16553a00eeee` |
| Tentative 1 | app `9cf3d847…`, test `b9915e94…`, installés = construits, `VERIFIED` |
| Tentative 4 | app `45683196…`, test `08aeb076…`, installés = construits, `VERIFIED` |
| Tentative 5 | app `472035b0…`, test `10fc3c21…`, installés = construits, `VERIFIED` |

**Verdict : `GO_APK_PROVENANCE` = GO** pour la chaîne APK QA effectivement
installée et vérifiée sur ce Pixel. Limites : APK QA uniquement, pas d'APK
produit ; build `2fa90bd` (code Android identique à `bfb1965`).

## 5. Nettoyage et état final

- Tous les paquets `dev.atlas.coach.qa*` ont été désinstallés ; aucun ne reste.
- HOME et application produit inchangés ; `dev.atlas.coach.test` préexistant
  intact ; aucune donnée produit modifiée.
- Tous les mappings `adb reverse` créés ont été retirés ; aucune stack
  `android-apply-*` ne reste ; aucun conteneur/processus de campagne.
- Aucun service live, Funnel, DNS, Tailscale, Core live ou secret modifié.
- Aucun push, PR ou merge distant.

## 6. Limites

- Un seul appareil (Pixel 9 Pro XL API 37), un seul opérateur ADB, réseau local
  `adb reverse` ; aucune preuve WSS publique dans cette phase.
- Les échecs #2/#3 ne sont pas la deadlock G↔C : ils se produisent après la
  réponse du chat, sur l'issue de l'outil de proposition. La cause produit exacte
  n'est pas encore corrigée ; le parseur de métadonnées du test doit aussi être
  durci pour produire un diagnostic exact (`PROPOSAL_MISSING`).
- `close_code=null` reste une limite A4-4 documentée.
- Aucun test produit, HOME, Keystore ou donnée personnelle.
