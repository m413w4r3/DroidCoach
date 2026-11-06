# V7-10 — Transcription des revues indépendantes

**Date de consolidation :** 2026-11-06
**Code final examiné :** 3123dba4f89579d414406d8c790609d1e4a5adf6
**Base de départ :** origin/main, a5aca4b54088d43f4829adf31049417dd8cf32a6

Cette transcription rassemble les conclusions A4 et A4-delta à partir des sorties de session Paseo, de l’historique Git et des preuves V7-10 versionnées. Les essais décrits sont synthétiques et locaux. Aucun résultat ci-dessous ne qualifie un service live ni un Pixel.

## A4 initiale — 2026-11-06

| Champ | Valeur |
|---|---|
| Agent | 2a825977-b00a-4711-9b25-455ff2941bc6 |
| Profil constaté | Codex / gpt-6-luna / xhigh |
| SHA examiné | fa82af32f3bfed18d54476c9ef1b9b88092d6d36 |
| Verdict | **NO_GO** |

### Findings P1

1. **Catalogue TOOL inutilisable.** Avec un principal TOOL actif, un credential d’audience TOOL et le principal TOOL configuré, l’appel synthétique à /internal/v4/capabilities rendait 403 CAPABILITY_UNAVAILABLE. tool_catalog.read n’était pas dans l’allowlist et ne pouvait pas être accordé par la CLI.
2. **Propositions historiques sans propriétaire accessibles à plusieurs LEGACY.** En mode dual, tout principal LEGACY pouvait tirer et ACKer des lignes dont device_ref était NULL. Un second principal LEGACY pouvait ainsi accéder à une proposition historique non attribuée.

### Findings P2

- Les flags globaux ne couvraient pas toutes les mutations, le consentement et les ACK; les grants locaux pouvaient rester actifs même lorsqu’une désactivation globale était attendue.
- La source d’environnement du secret d’assertion Relay pouvait être utilisée hors test au lieu d’exiger le fichier privé contrôlé.
- Un contrôle « Synchroniser maintenant » dans la configuration avancée conservait le transport direct V1–V3 en mode RELAY.
- La matrice de sécurité ne couvrait pas tous les scénarios exigés, dont B/B, certains cas d’identité Relay et les contrôles de révocation/reprise.

### Vérifications et limites de cette revue

- Les suites ciblées auth/CLI/chat ont passé 52 tests; migrations/projections/routes ont passé 28 tests.
- L’appel synthétique au catalogue TOOL a reproduit le refus 403.
- La suite backend complète n’a pas produit de résultat global valide dans cette revue : elle a échoué après 31 réussites sur test_agent_v2.py::test_gateway_token_file_requires_mode_600_and_location_outside_repository, le chemin temporaire de l’archive ayant placé le test dans le dépôt selon son oracle. La cause d’emplacement a ensuite été isolée dans A1-FIX; ce premier échec n’est pas réinterprété comme une suite complète réussie.
- Node, Android JVM/lint, AVD, Pixel et services live n’ont pas été exécutés par cette revue. Aucun fichier du dépôt n’a été modifié; les essais étaient sur une archive temporaire.

### Remédiations enregistrées

- 440b0a5 et 0890fd2 corrigent le catalogue TOOL, l’accès LEGACY aux propositions NULL, les flags globaux, la source d’environnement réservée aux tests et la matrice de tests.
- 614b639 réserve le contrôle de synchronisation directe au mode DIRECT.
- Les correctifs successifs de frontière et de lecture du secret Relay sont suivis par 77dc105, 739ad87, d2f9826 et 3123dba.
- d96810b et docs/operations/evidence/V7-10_A1_FIX_EVIDENCE.md consignent les remédiations et leurs preuves synthétiques.

## A4-delta — 2026-11-06

| Champ | Valeur |
|---|---|
| Agent | 296a570b-6c01-4a3c-a463-90d2bb88c6f1 |
| Profil constaté | Codex / gpt-6-luna / xhigh |
| Périmètre | Revue indépendante des correctifs de frontière du secret d’assertion Relay |

### 77dc105 — NO_GO

**SHA :** 77dc105a7d17f276402311a3e69e14bc60907612.

Le calcul de racine par profondeur fixe traitait un marqueur .git parent comme la racine du dépôt sans vérifier qu’il contenait le paquet integrations/relay/pc. Une installation Relay autonome sous un HOME contenant .git voyait donc sa clé privée valide rejetée. Le défaut était un faux positif de frontière; l’absence de marqueur Git reconnu par Git dans une archive causait aussi un écart de résultats de test.

Une première suite dans une archive sans .git a donné 119/123; la répétition dans une archive complète avec .git à la racine a donné 123/123. Le scénario standalone avec un .git parent restait le finding P1. Verdict global : **NO_GO**. Correction suivante : ne reconnaître qu’un candidat contenant le paquet Relay et un marqueur .git ou AGENTS.md.

### 739ad87 — NO_GO

**SHA :** 739ad878ea9c2822505bf35b9c345e0917578c38.

La correction permettait le layout standalone et reconnaissait un checkout ATLAS, mais s’arrêtait au candidat ATLAS le plus proche. Dans un checkout extérieur contenant un checkout ATLAS imbriqué, une clé placée dans le checkout extérieur mais hors de la racine imbriquée était acceptée.

La revue a reproduit le cas avec deux checkouts synthétiques et une clé 0600. Les cas standalone, checkout simple, chemin relatif, symlink de fichier, propriétaire, mode et contenu invalide passaient; la suite connecteur était à 125/125 avec TypeScript compilé. La frontière imbriquée demeurait incorrecte : **NO_GO**. Correction suivante : parcourir tous les ancêtres et retenir le candidat ATLAS le plus externe; ce changement est d2f9826.

### d2f9826 — NO_GO

**SHA :** d2f98265b1c4ca42fafc481d7b121f5d4f2bed01.

Le choix de la racine ATLAS extérieure corrigeait le cas imbriqué, mais deux défauts P2 restaient :

- Une erreur d’inspection EACCES était traitée comme l’absence d’un marqueur. Dans un layout imbriqué, la racine intérieure devenait alors la seule racine observée et une clé du checkout extérieur était acceptée.
- La frontière était vérifiée après canonicalisation du parent, mais lstat/open reprenaient ensuite le chemin d’origine. Un remplacement synthétique du symlink parent entre vérification et lecture permettait d’atteindre une clé interne.

Les cas d’accès, permissions, source d’environnement et la suite connecteur à 127/127 ne révélaient pas ces problèmes; les deux scénarios ont été reproduits séparément par la revue. Verdict : **NO_GO**. Correction suivante : propager toute erreur sauf ENOENT, utiliser le chemin canonique vérifié pour lstat et open(O_NOFOLLOW), puis comparer l’identité dev/ino du parent avant et après la lecture.

### 3123dba — GO final

**SHA :** 3123dba4f89579d414406d8c790609d1e4a5adf6.

Le relecteur a confirmé :

- EACCES remonte au validateur et ferme l’accès; la recherche ne retient pas une racine intérieure si l’inspection de la racine extérieure échoue.
- Un parent symlink statique est résolu, puis le même chemin canonique sert à la frontière, à lstat et à open(O_NOFOLLOW).
- Une substitution synthétique du parent après canonicalisation est détectée par le contrôle dev/ino avant/après; le contenu de la clé n’est pas lu.
- Les cas standalone avec .git parent sans paquet ATLAS, checkout imbriqué, clé interne/externe, mode, propriétaire, symlink de fichier, chemin relatif, contenu invalide, sources simultanées et environnement hors test restent couverts.

La suite connecteur sur le SHA final a compilé TypeScript et passé **131/131 tests dans 18 fichiers**. Le relecteur n’a trouvé aucun autre P0/P1/P2 dans ce correctif. Verdict delta : **GO**; gates de revue de sécurité indépendante et source/connecteur locaux : **GO**.

### Limites communes aux revues A4-delta

Le relecteur n’a pas relancé le backend ni le proxy sur le correctif final. Android JVM/lint n’a pas été exécuté indépendamment avec succès et aucun AVD n’a été utilisé par le relecteur. Aucun Pixel, ADB, service Relay/Core/Gateway live ni test E2E live n’a été utilisé. La revue finale porte sur le correctif de sécurité du connecteur et ne transforme pas ces absences en PASS.

## A6 — en attente

**Statut au 2026-11-06 : EN ATTENTE.** Aucun verdict A6 n’est disponible dans les preuves examinées. Ne pas l’inférer du GO A4-delta.

Après réception réelle de la revue A6, compléter cette section avec l’identifiant agent, le profil constaté, la date, le SHA examiné, le verdict P0/P1/P2, les scénarios et tests reproduits, les limites d’exécution, ainsi que toute mise à jour justifiée des gates. Conserver le verdict A4-delta ci-dessus comme résultat séparé.
