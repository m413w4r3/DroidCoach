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

## 2. Verdict

**`APPROUVE_AVEC_RESERVES`** — réserve d'historique Git (R-33), aucun P0/P1,
aucun écart de code ouvert.

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

## 6. Post-revue — `a6a5602` → `98824c8`

### 6.1 Contrôle d'alias Keystore fail-open — **CORRIGÉ**

**Constat du relecteur.** `r3bRelayKeystoreAliasPresent()` retournait `false`
sur toute exception, donc `assertFalse` pouvait passer sans prouver l'absence
de l'alias.

**Correction `29052f2`.** Le helper lève une `AssertionError` si le Keystore
est illisible. `R3cA4ClearInstrumentedTest` vérifie l'alias **présent avant**
`clearCredentials()` puis **absent après**. Rejeu Pixel : **PASS (0,192 s)**.
Aucun code de production modifié.

### 6.2 Identifiants dans les lignes supprimées du diff — **RÉSERVE D'HISTORIQUE**

**Constat du relecteur.** Le diff `c5ec1f4..a6a5602` montre les anciennes
valeurs dans les lignes `-` des documents R3-B.

**Contexte.** Ces valeurs étaient **déjà publiées dans les commits R3-B de
`origin/main`** avant R3-C. La redaction les retire de l'arbre courant ; leur
présence dans les lignes supprimées est inévitable sans réécriture
d'historique. La mission impose de préserver l'historique R3-B et interdit les
opérations destructives non autorisées ; une purge complète serait une
opération séparée (réécriture + force-push) nécessitant une autorisation
explicite. La redaction ne crée aucune nouvelle exposition. Les valeurs
exactes sont hors dépôt en 0600. Voir `RISQUES_RESIDUELS.md` R-33.

### 6.3 Verdict post-revue

**`APPROUVE_AVEC_RESERVES`** (relecteur, `98824c8`).

- Fail-closed Keystore : **CONFIRMÉ**.
- Diff `a6a5602..98824c8` : limité à deux fichiers androidTest et huit
  documents, aucun code de production.
- Réserve unique : l'historique Git antérieur (R3-B déjà sur `origin/main`)
  contient encore les identifiants retirés de l'arbre courant. Le relecteur
  ne recommande **pas** de réécrire l'historique dans ce périmètre.

Aucun `GO_REMOTE` n'est revendiqué : la réserve est documentée dans
`RISQUES_RESIDUELS.md` R-33 et la gate A4-4 reste FAIL.
