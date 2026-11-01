# V7-06D — Revalidation physique F1 et A4-4 (Pixel 9 Pro XL / API 37)

**Date :** 2026-11-01
**Statut :** **PASS** — 5/5 démarrages froids PASS, sonde A4-4 PASS.
**Campagne :** `V7-06D-F1-PHYS`.
**Appareil :** Pixel 9 Pro XL physique, Android 17, SDK 37 (full `37.1`),
patch de sécurité `2026-09-05`, non-émulateur. Serial et empreinte complets non
publiés : `serial_sha256=f2e9258b85327628c8d25b1652b00601e698f9ccb2a8b23fb1e913ff355a5946`,
`fingerprint_sha256=a44a971009b24780b39f441fd2f0b23c33bbb16682408cb3cf57ae626069f2a6`.
**Code revu :** `07490370e7afa964c1eb6223945544af7b21b887`.
**Code construit :** `9149c475de0e8fd22214ce4c83c3d87b8e1d825b` (delta
`docs/` uniquement ; `git diff 0749037..9149c47 -- android backend integrations
contracts scripts` vide ; arbre propre).
**Autorisation ciblée :** « F1 + sonde A4-4 » — paquet QA uniquement, stack
isolée, identités synthétiques, sauvegarde app QA, désinstallation QA à la fin ;
aucun test produit, aucun changement HOME/Keystore/données personnelles/service
live, aucun test sur `dev.atlas.coach`.
**Preuves privées :** `/home/nill/.local/state/atlas-v7-06d-f1-phys/`, mode
`0600`/`0700`, hors dépôt (`series-summary.json`, `preflight-f1.json`,
`cleanup-final.json`, cinq répertoires `evidence-f1-cold-*`).

## 1. Pré-vol

| Contrôle | Constat |
|---|---|
| État ADB | `device`, un seul appareil, non-émulateur |
| Version | Android `17`, SDK `37`, full `37.1`, patch `2026-09-05` |
| Modèle | Google Pixel 9 Pro XL |
| Déverrouillage | `showing=false`, `mWakefulness=Awake` |
| HOME avant | `dev.atlas.coach/.app.LauncherActivity` |
| Paquets ATLAS avant | `dev.atlas.coach`, `dev.atlas.coach.test` |
| Paquets QA avant | **aucun** (`NO_PRIOR_QA_DATA`) |
| Produit | `0.1.0`, versionCode `1`, `lastUpdateTime 2026-10-31 00:18:13` |
| Stockage | 32,9 Go libres sur `/data/user/0` |
| `adb reverse` avant | aucun |
| Git | `9149c47`, `git status --porcelain` vide |

Aucune archive produit n'a été créée ou restaurée ; aucune donnée QA
préexistante n'était à sauvegarder. Les scripts ont été relus avant lancement :
le runner n'exécute ni `pm clear`, ni changement de HOME, ni test produit ; il
ajoute puis retire son propre mapping `adb reverse`.

## 2. Sonde A4-4 — PASS

Device QA synthétique révoqué dans le registre du connecteur isolé après le
premier démarrage froid réussi, puis sonde
`V7_06C_RevokedDeviceReconnectInstrumentedTest` exécutée en lecture seule côté
appareil avec `adb reverse` local.

| Oracle | Constat |
|---|---|
| Instrumentation | **PASS** — `OK (1 test)` |
| État observé | **`REVOKED`** |
| Type d'échec observé | **`REVOKED`** |
| `close_code` observé | **`null`** (mapping relay documenté ; pas de faux 4001/1012) |
| Identifiants configurés | `true` |
| Connexions relay avant → après | `1` → `2` |
| Sessions authentifiées avant → après | `1` → **`1`** (aucune nouvelle session) |
| Erreurs avant → après | `0` → `1` |
| Sessions actives après | `0` |
| `device_id_sha256` | `64dd69edbcfd885c84296a1658e007b5a0a0762c09269d7915853bbc70c3aecb` |
| Ligne de preuve | `v7_06c_revoked_reconnect result=PASS state=REVOKED failure=REVOKED close_code=null credentials=true` |

**Conclusion :** refus explicite via trame authentifiée, aucune session
ouverte. `GO_A4_4_CLIENT` reste **GO** pour ce périmètre.

## 3. Cinq démarrages froids — 5/5 PASS

Cinq stacks `android-apply` neuves, cinq paquets QA neufs, cinq builds propres,
gate de provenance installé↔construit exercé avant chaque instrumentation.
Chaque tentative exécute le parcours complet consentements → plan → chat →
proposition → APPLY/ACK, avec le prompt F1 corrigé.

| # | Résultat | Proposition | ACK | App APK SHA-256 (16) | Test APK SHA-256 (16) | Proof SHA-256 (16) |
|---|---|---|---|---|---|---|
| 1 | **PASS** | APPLIED | APPLIED | `65ed66297a7eb9a9…` | `5bb67efd53347acd…` | `c3e6c846805ad37c…` |
| 2 | **PASS** | APPLIED | APPLIED | `28dd90d4789b7a13…` | `f1ea232aff33d447…` | `d31acb0269e741ee…` |
| 3 | **PASS** | APPLIED | APPLIED | `af857a3660c39942…` | `c6bc31be88c9da5a…` | `94f8db11930a67d5…` |
| 4 | **PASS** | APPLIED | APPLIED | `71b091d1eb6788be…` | `3ddf530cd5b9b024…` | `f02c1ec4d8e21e69…` |
| 5 | **PASS** | APPLIED | APPLIED | `d36fa0eda1cec063…` | `3f3bfde43a8e188f…` | `34ece60b3624eee3…` |

Pour chaque tentative :

- `status=PASS`, `proposal_state=APPLIED`, `ack_state=APPLIED`,
  `ack_result_code=APPLIED`, `pending_proposal_count=0`, `change_count=1` ;
- logcat : `step=05 result=PASS`, `agent_status=CONNECTED`,
  `tool_state=completed`, `tool_call_count=2`, exactement un identifiant de
  proposition ;
- `tool_trace` : six outils demandés, `atlas_get_day_plan` et
  `atlas_propose_plan_revision` retournés, `forbidden_tool_count=0`,
  `tool_call_ids_correlated=true` ;
- `ack_replay=ATTESTED_BY_ANDROID_TEST`, rejeu ACK identique, aucun ACK
  en attente.

**Aucun échec F1, aucune réponse à zéro proposition, aucune exception
`parseToolMetadata`/`PROPOSAL_MISSING` dans les cinq exécutions.**

## 4. Provenance des APK installés

Chaque proof porte :

| Élément | Constat |
|---|---|
| Source | `source_sha=9149c475…`, `source_tree_sha=3cb8461b39b334db14b35cef7acc079ab7512181`, `worktree_clean=true` |
| Variante / SDK | `qa`, compile `37.2`, runtime `37`, min `26`, target `36` |
| Certificat | `da3014662eb65e50a87ef364a2e583c331398795b222e3b37b1a16553a00eeee` |
| APK | hashes app/test construits **égaux** aux hashes installés, `installed_apk_verified=true`, `verification_result=VERIFIED` |
| Endpoint | `ws://127.0.0.1:<port>` via `adb reverse` local, `network=local_adb_reverse` |
| Scénario | `v7-06d-f1-phys-cold-1…5` |

Les cinq gates installés ont réussi avant instrumentation ; aucun mismatch de
hash, de manifeste, de certificat, de variante ou d'état sale.

## 5. Nettoyage et état final

Le nettoyage automatique par tentative a laissé des paquets QA à cause d'une
boucle qui consommait son `stdin` via `adb` ; le parent a désinstallé
manuellement tous les paquets `dev.atlas.coach.qa.*` restants. Les valeurs
`qa_packages_after_cleanup` du fichier brut `series-results.jsonl` ne
constituent donc **pas** une preuve de nettoyage ; l'état final vérifié est
ci-dessous (`cleanup-final.json`).

| Contrôle final | Constat |
|---|---|
| Paquets QA restants | **aucun** |
| Paquets ATLAS | `dev.atlas.coach`, `dev.atlas.coach.test` uniquement |
| HOME après | `dev.atlas.coach/.app.LauncherActivity` |
| Produit après | `0.1.0`, versionCode `1`, `lastUpdateTime 2026-10-31 00:18:13` — inchangé |
| `adb reverse` après | aucun |
| Stacks isolées / conteneurs | aucune |
| Données produit / Keystore / HOME | inchangés ; aucun `pm clear`, aucune désinstallation produit |
| Services live | aucun changement |

## 6. Verdicts

| Verdict | État |
|---|---|
| Série physique F1 | **PASS** — 5/5, zéro échec F1 |
| Sonde A4-4 | **PASS** — `REVOKED`/`REVOKED`, aucune nouvelle session |
| `GO_CHAT_RELIABILITY_QA` | **GO sous réserve de la revue indépendante de cette preuve** ; sinon inchangé |
| `GO_CHAT_CONCURRENCY` | **GO** — inchangé |
| `GO_REVOKED_DEVICE_SERVER` | **GO** — inchangé |
| `GO_A4_4_CLIENT` | **GO** — confirmé |
| `GO_APK_PROVENANCE` | **GO** — gate installé exercé sur les cinq APK QA |
| `GO_REMOTE_V4_PUBLIC` | **GO_WITH_LIMITATIONS hérité** |
| `GO_PIXEL_PRODUCT` / `GO_PRODUCT_REMOTE` | **NO GO** — aucun test produit |

Limites : APK QA uniquement, réseau local `adb reverse`, un seul Pixel
API 37.1, identités synthétiques. Aucun test produit, public ou distant.
