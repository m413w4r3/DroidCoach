# ATLAS V6.3 — R3-C — Revue indépendante

## 1. Session

| Élément | Valeur |
|---|---|
| Agent | `88c30a0b-573b-4cbb-941d-1ef9b1effd2d` |
| Profil constaté | `provider=codex`, `model=gpt-6-luna`, `thinkingOptionId=xhigh`, `effectiveThinkingOptionId=xhigh`, mode `auto-review` |
| Worktree | `<worktree Paseo>/review-r3c-final-c5ec1f4` |
| Branche | `review/r3c-final-c5ec1f4` |
| Droits | lecture seule effective ; aucun fichier modifié ; `git status --short` vide à chaque retour |
| SHA revu initialement | `c5ec1f4` (code `d2419b3` + docs) |
| Correctifs vérifiés | `d51525f`, `e3a9867`, `2796dad`, `e1de88f` (lus depuis les objets Git partagés) |

## 2. Verdict final

**`APPROUVE`** — aucun P0, aucun P1, aucune réserve ouverte.

Historique :
1. revue initiale : `APPROUVE_AVEC_RESERVES`, trois P2 ;
2. vérification des corrections : les trois P2 **CONFIRMÉS** ;
3. vérification de la pseudonymisation : une réserve P2 résiduelle
   (`curl --interface` avec un placeholder en prose) ;
4. vérification finale : réserve **CONFIRMÉE corrigée**, verdict `APPROUVE`.

## 3. Findings et corrections

### P2-1 — Identifiants d'environnement dans les preuves

**Constat.** Le numéro de série Pixel et les IP publiques/privées apparaissaient
dans les documents versionnés.

**Correction.** Pseudonymisation dans R3-C (`d51525f`) puis dans l'historique
R3-B (`2796dad`) : `$PIXEL_SERIAL`, `$FUNNEL_PUBLIC_IP`, `$PC_PHYSICAL_IP`,
`$PC_INTERFACE`, `$PC_PUBLIC_IP`. Valeurs exactes conservées hors dépôt en
0600 dans `~/atlas-backups/r3c/`. Aucun SHA, commande, horodatage, résultat
ni verdict modifié ; mapping dans
[`../r3b/REDACTION_IDENTIFIANTS.md`](../r3b/REDACTION_IDENTIFIANTS.md).

**Vérification.** Scan `git grep` sur `docs/` : aucun identifiant. Les
placeholders sont utilisés dans les commandes concernées (`adb`, `curl`) et
la convention demande de les exporter avant usage.

### P2-2 — Image relais non vérifiée par digest

**Constat.** Le harnais acceptait un tag Docker mutable et un override
`ATLAS_RELAY_IMAGE` sans vérifier l'artefact.

**Correction.** `run-isolated-relay-tests.sh` vérifie l'ID immuable
`sha256:75d68fd8…` avant lancement et après démarrage du conteneur ; tout
écart retourne `BLOCKED`. L'override `ATLAS_RELAY_IMAGE_ID` reste explicite.

**Vérification.** Test de mismatch : `BLOCKED`, code 2, aucun conteneur lancé.
Test nominal : 81/81 PASS, conteneur supprimé.

### P2-3 — Collision de nom du conteneur

**Constat.** Le nom fixe pouvait entrer en collision avec un conteneur d'un
autre processus, et le nettoyage pouvait viser ce nom.

**Correction.** Nom unique par invocation (horodatage + PID), capture de l'ID
après lancement, capture de secours en cas d'échec du lanceur, nettoyage
uniquement de l'ID capturé.

**Vérification.** Confirmé par le relecteur ; aucun conteneur résiduel après
les runs.

### P2-4 — Placeholder invalide dans une commande

**Constat.** La commande `curl --interface` contenait « IP physique du PC
(hors dépôt) » au lieu d'une variable.

**Correction.** `e1de88f` : `curl --interface "$PC_PHYSICAL_IP" --resolve
all.taildc7886.ts.net:8443:"$FUNNEL_PUBLIC_IP" …`, avec les variables définies
dans la convention.

**Vérification.** CONFIRMÉ ; plus aucune formule en prose dans une commande.

## 4. Points vérifiés sans finding

- NET-01 : réseau → `OFFLINE`, TLS/Noise/permission/inconnu → `ERROR`
  terminal, pas de double reconnexion, callbacks périmés invalidés par
  génération, budgets bornés.
- AUTH_REQUIRED et REVOKED préservés ; `BACKEND_UNAVAILABLE` ne termine pas
  la session connectée.
- COMPAT-01 : intersection stricte, aucune élévation, opérations anciennes
  disponibles, refus ciblé `OPERATION_UNAVAILABLE`, session maintenue.
- RACE-01 : barrières déterministes, deux chemins de révocation, ordre
  `DEVICE_REVOKED` avant `4001`, rejet des réponses tardives, aucun code de
  production modifié.
- CI/harnais : job Node 24 correct, image épinglée, loopback uniquement,
  cleanup par ID, aucun secret ni port publié.
- Documentation : pas de PASS Pixel/CI/OpenClaw revendiqué sans exécution ;
  limites `BLOCKED_DEVICE`, `BLOCKED_AUTH`, `BLOCKED_AUTHORIZATION` explicites ;
  aucun secret ni donnée personnelle versionnée.

## 5. Limites de la revue

- Revue **statique** : le relecteur n'a lancé ni Gradle ni npm (bac à sable),
  et n'a pas exécuté les tests. Les preuves d'exécution sont celles du parent.
- Aucun accès au Pixel ni à l'APK installé.
- Les valeurs exactes hors dépôt n'ont pas été vérifiées par le relecteur.
- La revue porte sur le code `d2419b3` et les documents jusqu'à `e1de88f` ;
  toute modification de code ultérieure exige une nouvelle revue.
