# ATLAS V6.3 — R3-C — Rapport final

Date : **2026-10-28**
Branche de campagne : `feat/atlas-v6-3-r3c-qualification`
Base : `eedf91502227297be5d2ff35524e79983ea36f49`
Dernier commit de code Android : `d2419b3`
Dernier commit d'infrastructure : `e3a9867`
Derniers commits documentaires : `2796dad`, `e1de88f`

## 1. Verdict

**`GO_PREPARED_WITH_LIMITATIONS`**

Le code R3-C compile, les suites Android/backend/Node/proxy passent, le
chemin public Funnel est réellement qualifié avec TLS, Noise, authentification,
chat mock et chat OpenClaw réel. Le couple APK R3-C a été **installé sur le
Pixel** et A3, le chat réel, NET-07 (OFFLINE), NET-09 (handover) et les
régressions UI principales sont PASS sur ce binaire.

La gate **A4-4 échoue** : le relais épinglé ferme le socket client avec
`1012` au lieu de propager le `4001` émis par le connecteur. `DEVICE_REVOKED`,
`REVOKED`, l'absence de reconnexion, l'effacement explicite et le réappairage
sont PASS. La CI distante est **success** sur `a72ba9e` ; un dernier run est
requis après le commit final.

Aucun `GO_REMOTE_WITH_LIMITATIONS` ni `GO_V63` n'est revendiqué : A4-4 reste
un écart explicite, et H24/Doze/systemd/wger ne sont pas qualifiés.

Aucun `GO_REMOTE_WITH_LIMITATIONS` ni `GO_V63` n'est revendiqué.

## 2. Ce qui a été corrigé

| Ticket | Correctif | Commit | Preuve |
|---|---|---|---|
| `R3C-ANDROID-NET-01` | Panne réseau → `OFFLINE` ; TLS/Noise/permission/inconnu → `ERROR` terminal ; auth/révocation conservées ; `BACKEND_UNAVAILABLE` ne change pas l'état | `d2419b3` | 14 cas JVM, gates 712 tests |
| `R3C-COMPAT-01` | Session maintenue avec capacités négociées ; chat refusé `OPERATION_UNAVAILABLE` ; anciennes opérations utilisables ; aucune élévation | `a162f70` | JVM 4 cas, Node 1/1, E2E public ancien appairage 4/4 |
| `R3C-RACE-01` | Tests déterministes A1–A3 / N1–N3 ; ordre `DEVICE_REVOKED` avant `4001`, aucune réponse tardive, deux chemins de révocation | `22ba24f` | Node 3/3, JVM ciblé PASS |
| `R3C-CI-01` | Job Node 24 (`npm ci`, `test:unit`) + proxy ; harnais relais isolé épinglé par ID d'image | `cd6e751`, `e3a9867` | 81/81 Node, 18/18 proxy, harnais reproductible |
| Documentation | Runbook Pixel non destructif, runbook PC, preuves, risques, pseudonymisation R3-B/R3-C | `c5ec1f4`, `d51525f`, `2796dad`, `e1de88f` | `docs/v6-3/r3c/` |

## 3. Qualification publique Funnel

Le chemin public a été rétabli sans modifier Tailscale :

- DNS public indépendant via DoH (`1.1.1.1`), TLS 1.3, certificat valide.
- `/health` 200, `/ready` 200, `/metrics` 404.
- `/ws` 101 avec `role=client&v=2` ; `v=1`, `serverId` absent et query inconnue
  refusés 400 ; upgrade `/metrics` 404.
- E2E public **7/7 PASS** avec Noise `protected`, auth appareil,
  `backend.health`, `sync.status`, chat relayé, rejeu sans doublon,
  idempotence et limite de payload.
- Chat **OpenClaw réel** via le Funnel : `agent_status=connected`, 7/7 PASS.
- Ancien appairage sans `coach.chat` : 4/4 PASS, chat
  `OPERATION_UNAVAILABLE`, session maintenue.
- Arrêt/relance FastAPI : `BACKEND_UNAVAILABLE` explicite puis `ok`.
- SIGKILL connecteur : identité X25519 et registre conservés, reconnexion.
- Source physique du PC utilisée (hors `tailscale0`) ; ports internes
  `4400/4401/4402/8100` fermés sur l'IP publique.

## 3bis. Qualification Pixel sur le binaire R3-C

APK principal `365bd84a…`, androidTest `d79031f8…`, utilisateur 0, Android 17 /
API 37.

| Domaine | Résultat |
|---|---|
| Installation | PASS — hash installé identique, signature debug, profils 12/13 non modifiés |
| A3 appairage Keystore | PASS — 1,149 s, token chiffré, aucun clair |
| A3 force-stop | PASS — 0,89 s |
| A3 redémarrage Android | PASS — 1,439 s, identité et token conservés |
| A4 `DEVICE_REVOKED` / `REVOKED` / erreur | PASS |
| A4 fermeture 4001 | **FAIL** — relais `1012`, trois probes concordants |
| A4 effacement / PAIRING_REQUIRED / réappairage | PASS — 0,284 s / 1,122 s / 2,386 s (chemin production, clic UI non rejoué) |
| Chat réel OpenClaw | PASS — 15,658 s, provenance « OpenClaw via PC », aucune action non confirmée |
| NET-07 coupure | PASS — `OFFLINE` observé, reconnexion, conversation conservée (10,346 s) |
| NET-09 handover | PASS — CELLULAR puis WIFI, conversation conservée (9,931 s) |
| UI-06 HOME | PASS — launcher puis relance, hash local inchangé |
| UI-07 hors ligne | PASS — écran local utilisable, aucun crash |
| UI-08 police/rotation | PASS pour police 1,3 et paysage ; TalkBack NON EXÉCUTÉ |
| UI-04 token | PARTIAL — champ masqué, stockage Keystore vérifié, frappe UI non rejouée |
| Données locales | PASS sur le périmètre R3-C (aucune donnée R3-B pour user 0) |

## 4. Gates sur le SHA intégré

| Gate | Résultat |
|---|---|
| Android JVM | **100 suites, 712 tests, 0 échec/erreur/ignoré** |
| Android lint | **0 erreur, 71 avertissements** |
| Build APK principal (propre) | `365bd84a14ab05a35e2333688c57addb825b42aede8e7e2e1a11b7e0079cd4f3` |
| Build APK androidTest (propre) | `d79031f8c96c409bada82f1fcb25fe80c99218a2352b23067f6af17185fb3706` (inclut le test R3-C d'effacement) |
| Signature | debug `da3014662eb65e50a87ef364a2e583c331398795b222e3b37b1a16553a00eeee` |
| Version | `versionCode=1`, `versionName=0.1.0`, compileSdk 37 |
| Backend | **196 passed, 1 skipped** |
| Node connecteur | **17 fichiers, 81 tests PASS** (dont relais réel) |
| Proxy filtrant | **18/18 PASS** |
| CI distante | **success** sur `a6a5602` (run `40744237164`) ; dernier run requis après le correctif fail-closed |

## 5. Revue indépendante

Session `codex/gpt-6-luna`, effort `xhigh` effectif, worktree isolé,
lecture seule, agent `88c30a0b-573b-4cbb-941d-1ef9b1effd2d`.

- Verdict initial : **`APPROUVE_AVEC_RESERVES`**, aucun P0/P1.
- Trois P2 : identifiants d'environnement dans les preuves, image relais non
  vérifiée par digest, collision de nom de conteneur. Corrigés (`d51525f`,
  `e3a9867`, `2796dad`, `e1de88f`) et confirmés par le relecteur.
- Réserve résiduelle historique R3-B corrigée par pseudonymisation
  (`2796dad`) ; dernier contrôle de la commande `curl --interface` corrigé
  (`e1de88f`).
- **Verdict final : `APPROUVE`**, aucune réserve ouverte. Voir
  `REVUE_INDEPENDANTE.md` pour le détail.

## 6. Limites explicites

- **A4-4 FAIL** : le relais épinglé ferme avec `1012` au lieu de `4001`.
  `DEVICE_REVOKED`, `REVOKED`, l'effacement et le réappairage sont PASS.
- **UI-04 PARTIAL** : champ token masqué et stockage Keystore vérifiés, mais
  frappe manuelle non rejouée. **UI-01/UI-03 PARTIAL** après l'échec A4-4.
- **UI-08 PARTIAL** : police 1,3 et paysage PASS ; TalkBack NON EXÉCUTÉ.
- **CI distante** : success sur `a72ba9e` ; dernier run requis après le commit
  final (compilation androidTest incluse).
- **OpenClaw réel** : testé via le PC et via l'UI Android sur Pixel avec un
  message synthétique ; une conversation synthétique existe côté gateway.
- **NET-07** : PASS sur Pixel (OFFLINE observé). Le mode avion seul laissait
  la Wi-Fi active sur ce Pixel ; la coupure réelle a été faite en désactivant
  Wi-Fi + données.
- **NET-13, CHAT-05/06** : PARTIAL, pas de parcours Pixel dédié.
- **Données Pixel** : aucune donnée R3-B pour l'utilisateur 0 au pré-vol ;
  la préservation est démontrée sur le périmètre R3-C (hash local, conversation).
- **H24/Doze** : NOT_RUN ; aucune promesse de disponibilité permanente.
- **systemd** : modèles vérifiés statiquement, non installés ;
  `BLOCKED_AUTHORIZATION`. `linger` déjà actif, non modifié.
- **wger** : `BLOCKED_AUTH`, aucune lecture réelle.
- **Redémarrage complet du PC** : NOT_RUN ; le SIGKILL connecteur ne suffit
  pas.
- **Android 14** : suite non lancée ; cible de clôture Android 17/API 37.
- **R-15/R-17** : risque de déni de service du relais public toujours ouvert.
- **R-18** : dépendance au PC, à `tailscaled` et au plan de contrôle Tailscale.
- Les preuves R3-B restent historiques ; aucun PASS R3-B n'a été transformé
  automatiquement en preuve R3-C.

## 7. Sécurité et données

- Aucun token, clé privée, secret ou donnée personnelle versionné.
- Identifiants d'environnement (série Pixel, IP publiques/privées)
  pseudonymisés ; valeurs exactes hors dépôt en 0600.
- Sauvegarde Pixel app-only et APK de rollback conservés hors dépôt en 0600.
- Deux appareils synthétiques de test révoqués ; 0 appareil actif ; tokens
  temporaires supprimés.
- Aucune modification de l'OpenClaw personnel, du Bridge, de Tailscale, du
  DNS, du port `8443`, du proxy privé `8770` ou du transport Noise.
- Aucun `pm clear`, `uninstall`, factory reset, `rm -rf` applicatif ni
  suppression d'alias Keystore hors scénario explicite.
- Aucun push, PR, merge distant ou modification GitHub.

## 8. Condition pour passer à `GO_REMOTE_WITH_LIMITATIONS`

1. **Trancher A4-4** : corriger la propagation du code de fermeture dans le
   relais amont, ou accepter explicitement `1012` comme fermeture de transport
   après `DEVICE_REVOKED` et adapter le contrat. C'est le seul écart bloquant
   identifié pour `GO_REMOTE`.
2. Rejouer le test A4 UI complet (clic « Supprimer », confirmation) sur le
   binaire retenu après la décision A4-4.
3. Attendre le dernier run CI `success` sur le commit final.
4. Clore, si souhaité, UI-04 (frappe manuelle), TalkBack, NET-13 et CHAT-05/06.
5. Pour `GO_V63` seulement : qualifier H24/Doze, installer la supervision
   systemd avec mandat, et statuer sur wger.
