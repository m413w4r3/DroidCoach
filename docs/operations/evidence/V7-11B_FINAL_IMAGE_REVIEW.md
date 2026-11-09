# V7-11B — revue indépendante de l’image Core finale et du stack isolé

**Verdict : GO_WITH_LIMITATIONS pour la qualification locale documentée ; aucune validation indépendante de l’image ou des conteneurs.** Le NO-GO live reste justifié.

- **Base et résultat examinés :** `5f2377290e8f9a246b202545f2d4a5c8fbdb991d`
- **SHA source de l’image revendiquée :** `7efbefcc5b747823cfd6d8da34b0c402953a0b0f`
**Date :** 2026-11-08

## Portée et méthode

Revue indépendante en lecture seule des preuves B12, des preuves de backup et de migration sur copie, de la préparation V7-11B, du registre, du Dockerfile, de l’entrypoint et du Compose. Aucun test, build, ADB, migration ou accès en écriture à Docker n’a été exécuté.

Les quatre commandes `docker image inspect` / `docker inspect` autorisées ont échoué avec `permission denied` sur le socket Docker du sandbox. Conformément au mandat, les affirmations dépendant de l’état réel de l’image, des conteneurs ou du nettoyage sont donc notées **UNKNOWN**. Aucun accès élargi n’a été demandé.

## Grille de revue

| # | Statut | Conclusion |
|---|---|---|
| 1. Base et périmètre | **PASS** | `HEAD` vaut le SHA attendu et l’arbre était propre au début de la revue. Le commit B12 (`5f23772`) ne modifie que `V7-11B_FINAL_IMAGE_STACK_EVIDENCE.md` ; aucun code n’a changé. |
| 2. Build propre | **UNKNOWN** | B12 rapporte l’archive de `7efbefc`, le contexte `contracts`, le tag dédié `atlas-core-v7-11b-final-7efbefc`, l’image `sha256:1e4c8e6c…`, 150 520 138 octets et les valeurs ENTRYPOINT/CMD attendues. Ces dernières correspondent au Dockerfile (`backend/Dockerfile:23-24`). L’inspection de l’image, de son tag et du tag/image live `atlas-core-atlas-core` (`8604535e…`) a été bloquée par le sandbox ; leur état présent n’est pas vérifiable ici. |
| 3. Code B7 dans l’image | **UNKNOWN** | La provenance source est cohérente : `7efbefc` descend du commit B7 `fae7531`, et le code source à ce SHA contient `O_NOFOLLOW`, `MigrationIntegrityError`, `_create_coherent_pre_migration_backup` et `FileReplayNonceCache`. L’image stale `528af664…` est rapportée construite à `a3d72db`, antérieur à B7 ; le code de `a3d72db` ne contient pas les protections `O_EXCL`/`O_NOFOLLOW` dans le helper. En revanche, le contenu réel de l’image reconstruite et les marqueurs qu’elle embarque n’ont pas pu être inspectés. |
| 4. Export réel et incident d’oracle | **UNKNOWN** | B12 corrige l’oracle : il utilise l’export courant `9f5c8fd2…` et les tables V4, avec version 3, intégrité OK, FK 0, propositions 3 (APPLIED/PENDING_ANDROID/REJECTED), événements 2, consentements 2 (GRANTED/REVOKED), événements de consentement 4 et historique 1. La comparaison initiale avec `752919f2…` et les tables V1/V2 `0/0/4/0` était inadéquate. L’explication du hash binaire différent par les écritures `PRAGMA user_version` à la réouverture est cohérente avec le code et la revue B10. Les agrégats confirment les comptes annoncés, mais ne démontrent pas à eux seuls l’égalité logique complète des lignes avant/après ; aucune perte n’est indiquée, sans être exclue par un comparatif logique complet dans ces preuves. |
| 5. Migration et backup | **UNKNOWN** | Le code B7 correspond au contrat décrit : création exclusive avec `O_NOFOLLOW`, mode 0600, contrôles et validation du snapshot v3. B12 rapporte v5, ledger `[1..5]`, `device_ref`, compteurs préservés, backup régulier 0600/v3/intègre (`193fcae4…`), verrou 0600 et cache `/data/replay` 0700. Les attributs du code sont cohérents avec le résultat attendu, mais l’exécution du conteneur et ses fichiers n’ont pas pu être inspectés indépendamment. |
| 6. Expiration au démarrage | **PASS** | C’est le comportement du code : le sweeper est activé par défaut (`backend/atlas_backend/main.py:366`) et sa première expiration est exécutée dès le lancement de sa tâche (`:420-428`). Il expire les états actifs dont `expires_at <= now` et ajoute l’événement dans la même transaction (`backend/atlas_backend/v4_projection_store.py:109-130`). L’état APPLIED 1 / EXPIRED 1 / REJECTED 1 rapporté est donc attendu ; le backup v3 pré-migration garde l’état antérieur. La préparation demande déjà une décision explicite sur la candidate avant réconciliation (`V7-11B_PREPARATION.md:241-250`). À préciser dans le gate : cette décision doit précéder le premier démarrage du Core migré, car le sweeper s’exécute immédiatement. |
| 7. Legacy, Relay, proxy et connecteur | **UNKNOWN** | B12 rapporte legacy 200 avec token synthétique et 401 avec faux token, health 200, `relay_connected=true`, WebSocket 101 et enregistrement synthétique. Aucun appareil n’a été appairé ; aucun Android réel n’est qualifié. Ces résultats de service sont rapportés par B12 et ne sont pas reproductibles depuis ce sandbox. |
| 8. Fail-closed | **UNKNOWN** | Le chemin B7 est cohérent avec le refus d’un backup préexistant et l’exception `MigrationIntegrityError`. B12 rapporte exit 1, message « vérifier puis déplacer », DB v3 et backup inchangés, sans santé. Le résultat de conteneur ne peut pas être confirmé directement ici. |
| 9. Live, nettoyage et limites | **UNKNOWN** | B12 rapporte l’image/tag live, les compteurs de redémarrage, `StartedAt`, ports et santé inchangés, les ports isolés libres, le nettoyage des ressources temporaires et la conservation de l’image finale. Aucun de ces états Docker n’est directement vérifiable dans cette revue. La publication loopback avec bind `0.0.0.0` à l’intérieur du conteneur est décrite comme déviation de test ; limites correctement déclarées : mock, aucun Android/appairage, aucune migration live, image non poussée. |
| 10. Gates et conditions live | **PASS** | `GO_V7_11_LIVE_CHANGE_READINESS = NO-GO` reste correct : aucune migration live, aucun binding réel vérifié, Relay non administrable, backup local sans copie hors site/chiffrement, et autorisation nominative distincte requise. La préparation liste aussi la décision d’expiration et les opérations de backup connecteur (`V7-11B_PREPARATION.md:241-252,287-293`). Les gates locaux finaux ne sont pas encore reflétés dans la matrice : `GO_V7_11_FINAL_IMAGE_PROVENANCE` y reste `NOT_RUN / STALE` (ligne 92) et `GO_V7_11_ISOLATED_STACK` ne référence que B1 (ligne 104). Vu l’inspection Docker indisponible, cette revue ne promeut pas ces statuts ; les preuves B12 les soutiennent comme résultats rapportés, avec inspection indépendante UNKNOWN. |

## Findings

### P0

Aucun constat P0.

### P1

Aucun défaut P1 confirmé. Aucun résultat de cette revue n’autorise une migration live.

### P2

1. **EVIDENCE_GAP — état d’image et de conteneurs non corroboré indépendamment.** Les commandes Docker autorisées sont bloquées par le sandbox. L’ID/taille/config de l’image finale, l’absence de mutation live, les ports et le nettoyage restent UNKNOWN dans cette revue. Garder ce statut dans l’évaluation indépendante jusqu’à ce qu’une inspection Docker en lecture soit possible ou qu’une preuve d’inspection vérifiable soit jointe.
2. **EVIDENCE_GAP — égalité logique complète du dump réel.** L’oracle corrigé et les compteurs pertinents sont cohérents ; le hash courant diffère du hash d’export précédent. La cause SQLite est crédible et déjà décrite dans les revues antérieures, mais les compteurs ne prouvent pas l’égalité logique de toutes les données. Avant d’utiliser cette comparaison comme preuve de conservation complète, ajouter un comparatif logique ou une empreinte agrégée déterministe, sans publier de contenu de ligne.
3. **RISK opératoire — expiration automatique avant disponibilité.** Le rapport B12 révèle que le premier tour du sweeper expire la candidate dès le démarrage. La préparation demande une décision avant réconciliation, mais devrait nommer explicitement le premier démarrage du Core migré comme échéance de cette décision.
4. **EVIDENCE_GAP — matrice de gates non réconciliée avec B12.** La préparation garde l’image finale en `NOT_RUN / STALE` et rattache le gate de stack à B1. Consigner B12 comme preuve locale finale seulement après résolution du point 1 ; ne pas présenter ces gates locaux comme une autorisation live.

## Conditions avant tout changement live

- Autorisation nominative spécifique pour la migration ; aucune promotion implicite depuis la copie.
- Décision explicite sur l’expiration de la proposition `PENDING_ANDROID` avant le premier démarrage du Core migré.
- Vérification du principal et du binding réel ; aucun bearer, credential, APPLY ou révocation sans autorisation ciblée.
- Relay administrable et recréation qualifiée hors live ; backup/restauration connecteur complet couvrant les trois fichiers d’état.
- Backup vérifié et restaurable, avec copie hors site avant le changement ; le backup actuel est local au même hôte et sans chiffrement au repos.
- Ancien sidecar vérifié et déplacé, chemin exact absent ; nouveau backup pré-migration régulier, propriétaire attendu, 0600, v3 et intègre.

## Limites de la revue

Le build, l’image et les scénarios Docker ne sont pas reproduits ici. Les contrôles dépendant de Docker sont `UNKNOWN`, pas PASS. Les assertions détaillées restent celles rapportées dans B12. Aucun Android physique, appairage, migration live, restore live ou modification de donnée live n’a été effectué par cette revue.
