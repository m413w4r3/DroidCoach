# ATLAS V7-06C — Coach Android puis transport public : registre de campagne

**Mission :** prouver depuis l’application Android QA réellement exécutée que le coach
OpenClaw lit le DayPlan v2, propose un déplacement de 10 minutes, qu’Android applique
ou rejette explicitement, et que le Core enregistre durablement le résultat et l’ACK ;
puis qualifier le même parcours via un WSS public sans `adb reverse`.

**BASE_SHA :** `3e0d1f4c9ca591f903a894be87183395d30fee0b` (local = `origin/main`
vérifié le 2026-11-01). **Branche de travail :**
`feat/atlas-v7-06c-android-coach-loop`, aucun push, PR, merge distant ni service live
modifié à ce stade.

**Environnement :** identités, appairage, jetons, base et stack synthétiques neufs.
Aucune identité, session, donnée ou configuration personnelle n’est réutilisée.

## 1. Contraintes d’exécution

- Parent Paseo orchestrateur ; **tous** les sous-agents `provider=codex`,
  `model=gpt-6-luna`, effort effectif `xhigh`, vérifié dans les métadonnées Paseo.
- 6 enfants simultanés maximum, dont 3 implémentations indépendantes au plus ;
  aucune sous-délégation.
- Worktree distinct et propriété d’écriture exclusive par enfant ; le parent intègre
  localement.
- Un seul opérateur ADB/AVD à la fois. Aucune opération ADB en écriture sans
  autorisation ciblée.
- Une notification de fin n’est pas une validation : le parent relit diff, tests,
  preuves et limites avant `ACCEPTED`/`INTEGRATED`.
- Aucun token, bearer, secret, UUID personnel, contenu de conversation ou donnée
  personnelle dans les preuves.

## 2. Pré-vol V7-06C-00 (2026-11-01, lecture seule)

| Élément | Constat |
|---|---|
| Git local | `HEAD=3e0d1f4c9ca591f903a894be87183395d30fee0b`, arbre propre au lancement |
| Git distant | `origin/main=3e0d1f4c9ca591f903a894be87183395d30fee0b` (identique) |
| Branche | `feat/atlas-v7-06c-android-coach-loop` créée depuis ce SHA |
| Paseo | provider Codex disponible ; modèle `gpt-6-luna` et effort `xhigh` disponibles |
| Enfant V7-06C-01 | session `3b012d9f-efaf-424f-9668-52c42be6cefc`, `codex/gpt-6-luna/xhigh`, mode `full-access`, worktree `feat-v7-06c-harness` |
| Modèle réel | `DEEPSEEK_API_KEY` présente dans l’environnement (valeur non lue, non écrite) |
| OpenClaw | `2026.10.5` (`1b58cb9`) |
| Node / npm | `v24.18.0` / `11.16.0` |
| Docker | `27.1.1` ; image `atlas-paseo-relay:r3a` = digest `sha256:75d68fd8bdb9d60a26203cd7c49c07c035f1f400c47740770056814e76846889` |
| Backend | venv Python 3.12 présent ; `dist/main.js` du connecteur présent |
| JDK / SDK | OpenJDK `21.0.12.1`, SDK Android 37, `sdk.dir=/home/nill/Android/Sdk` |
| KVM | `/dev/kvm` présent ; émulateur `37.2.12.0` |
| Images AVD | API 37.2 `google_apis_ps16k`, API 37.1 `google_apis_playstore_ps16k`, API 35 `google_apis`/`ps16k` (x86_64 et arm64) |
| AVD existants | `atlas_v7_06a_api37` (blocage V7-06B documenté), `atlas_v62_disposable_api37`, `Pixel_9_Pro_XL*` |
| Résidus V7-06B | Aucun conteneur/processus de stack isolée V7-06B ; `/tmp/atlas-v7-06b-backend` existe encore (worktree `dd7b2de`, harnais non commité) |
| Services live | Core personnel `127.0.0.1:8100`, Gateway personnel `127.0.0.1:18789`, conteneurs `atlas-core-atlas-core-1` et `atlas-v7-relay` actifs ; **non modifiés** |
| Funnel public | `tcp://all.taildc7886.ts.net:8443` → `127.0.0.1:4400` ; aucun listener sur 4400 ; le relais live publie `4000/tcp -> 127.0.0.1:4402` ; sonde TLS en échec |
| DNS public | `all.taildc7886.ts.net` résolu (176.58.90.46/63/145) depuis l’hôte |
| Appareil physique | Pixel 9 Pro XL, série hashée `f2e9258b85327628c8d25b1652b00601e698f9ccb2a8b23fb1e913ff355a5946`, Android 17 / API 37, build `CP3A.260905.009`, arm64-v8a, `sys.boot_completed=1`, batterie 100 % |
| Paquet produit | `dev.atlas.coach` 0.1.0, `lastUpdateTime=2026-10-31 00:18:13`, rôle HOME `dev.atlas.coach/.app.LauncherActivity` |
| Paquets QA antérieurs | `dev.atlas.coach.qa`, `.qa2`, `.qa3`, `.qa4` et leurs `.test` installés ; non réutilisés |
| Autorisation physique | demandée et accordée explicitement le 2026-11-01 pour **APPLY + REJECT** sur ce Pixel, paquets QA seulement, sans toucher au produit, HOME, Keystore ni données personnelles |

**Décision de pré-vol :** la cible Android est le Pixel physique autorisé ; l’AVD
API 37.2 16 Ko n’est pas relancé (blocage V7-06B documenté). Le transport public reste
`BLOCKED_NETWORK` tant que le Funnel ne pointe pas vers un relais vivant et qu’une
autorisation ciblée de changement Funnel n’est pas donnée.

## 3. Tickets

| TASK_ID | Objectif | Dépendance | Statut | Session Paseo | Worktree | Oracle |
|---|---|---|---|---|---|---|
| V7-06C-00-PREFLIGHT | SHA, CI, services live, ressources, harnais, identités | — | **ACCEPTED (lecture seule)** | parent | racine | SHA local=distant, inventaire complet, aucun service modifié |
| V7-06C-01-HARNESS | Harnais reproductible Core/Relay/connecteur/Gateway + RUN Android + oracles | PREFLIGHT | **RUNNING** | `3b012d9f-efaf-424f-9668-52c42be6cefc` | `feat-v7-06c-harness` | py_compile, unittest, node --check, `git diff --check` ; runtime parent ensuite |
| V7-06C-02-ANDROID-RUNTIME | Pré-vol physique, installation QA, Keystore/WorkManager | PREFLIGHT + autorisation | **READY** (autorisation accordée) | parent opérateur unique | racine | device boot/services stables, APK QA installé, package produit intact |
| V7-06C-03B-ACK-REPLAY-TEST | Rejeu de l’enveloppe ACK exacte depuis Android avant révocation | 02 | **RUNNING** | `3b0bd988-c977-46ee-b1ff-c099874461d2` | `feat-v7-06c-ack-replay-test` | compilation QA, `ack_replay=identical`, 1 seul ACK local, aucun second effet |
| V7-06C-03-APPLY | DayPlan v2→v3 par Android, ACK APPLIED durable, rejeu sans second effet | 01 + 02 + 03B | **READY** | parent opérateur | racine | runner PASS + oracle Core/journal/trace outils indépendants |
| V7-06C-03-REJECT | Refus explicite sans modification du plan, ACK REJECTED durable | 01 + 02 + 03B | **READY** | parent opérateur | racine | runner PASS + plan v2 intact + aucun v3 + oracle Core |
| V7-06C-04-WSS-PUBLIC | Même parcours via WSS public, réseau extérieur, sans `adb reverse` | 03 + endpoint public autorisé | **BLOCKED_NETWORK** | — | — | TLS/Noise/auth/révocation + fil rouge complet |
| V7-06C-05-REVIEW | Revue indépendante lecture seule du SHA intégré | 01–03 | **PENDING** | à créer | worktree détaché | grille P0/P1/P2, aucun P0 ouvert |

## 4. Journal des sessions

| Date | Session | Rôle | Provider/modèle/effort constatés | Résultat |
|---|---|---|---|---|
| 2026-11-01 | parent | Pré-vol V7-06C-00 | session parente | `ACCEPTED` lecture seule ; aucune modification live |
| 2026-11-01 | `3b012d9f-efaf-424f-9668-52c42be6cefc` | V7-06C-01 harnais | codex / `gpt-6-luna` / `xhigh` (métadonnées vérifiées) | `RUNNING` |
| 2026-11-01 | `3b0bd988-c977-46ee-b1ff-c099874461d2` | V7-06C-03B rejeu ACK test | codex / `gpt-6-luna` / `xhigh` (métadonnées à vérifier à la fin) | `RUNNING` |
| 2026-11-01 | parent + autorisation propriétaire | QA physique APPLY+REJECT | — | autorisation ciblée accordée ; aucune installation avant harnais prêt |

## 5. Décisions et limites

- Le harnais V7-06B sous `/tmp` est audité comme source de reprise ; sa version
  minimale reproductible est portée sous `scripts/v7-06c/` par le ticket 01.
- Le test instrumenté `V7_06B_CoachLoopInstrumentedTest` et son runner
  `scripts/v7-06b/android/run_qa.py` sont réutilisés sans création d’un troisième
  harnais ; le namespace de paquet reste `dev.atlas.coach.qa.v706b.*`.
- Le paquet produit `dev.atlas.coach` n’est ni installé, ni remplacé, ni effacé,
  ni instrumenté.
- Le rejeu du même ACK est prévu côté harnais à partir de l’enveloppe reconstruite
  et vérifiée par `content_hash` ; le test Android n’est pas modifié pour cette
  preuve.
- Aucun verdict GO n’est prononcé avant exécution réelle et oracle indépendant.
