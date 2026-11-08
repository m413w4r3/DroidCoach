# V7-11B — revue indépendante de la migration live v3→v5

**Verdict : GO_WITH_LIMITATIONS pour l’exécution documentée ; aucun GO indépendant du runtime live.** Aucun P0/P1 confirmé. Le gate de préparation au cutover complet reste correctement **NO-GO**.

**Base et cible examinées :** `ffc5e74e78153d9b250334189514a0b86ba97564`.

## Portée et méthode

Revue en lecture seule de la preuve de migration, des preuves d’image et de backup, de la préparation, du registre et des deux fichiers Compose. Au début de la revue, `HEAD` était exactement la base demandée et l’arbre était propre. Le commit audité ajoute uniquement la preuve de migration.

Le `docker ps` en lecture seule a montré le Core sur le tag de l’image finale avec l’état `healthy`, et le Relay actif. `docker inspect`, l’inventaire des images et réseaux, les logs Core et les sondes HTTP sur les ports locaux étaient bloqués par le sandbox ou le namespace réseau. Aucun ADB, accès en écriture Docker, test, restore ou changement live n’a été effectué. Les assertions dépendant de ces accès restent **UNKNOWN**, même si la preuve opérateur les rapporte comme PASS.

## Grille de revue

| # | Statut | Conclusion |
|---|---|---|
| 1. Base et périmètre | **PASS** | La base exacte et l’arbre propre au début sont confirmés. Le commit `ffc5e74` ne modifie que `V7-11B_LIVE_MIGRATION_EVIDENCE.md`. |
| 2. Timeline et disponibilité | **UNKNOWN** | La preuve rapporte Core arrêté à `22:45:25Z`, redémarré à `22:45:41Z` (~16 s), Relay jamais arrêté (`StartedAt` et compteur de redémarrages stables), app/Pixel déconnecté. Le listing Docker confirme seulement Core sain et Relay actif ; `StartedAt`, redémarrages et état Pixel n’ont pas été vérifiés indépendamment. |
| 3. Backup frais | **PASS (preuve documentaire)** | La preuve donne SHA `9f5c8fd2…`, v3, intégrité OK, FK 0, compteurs 3/2/2/4/1 et les trois états attendus ; elle rapporte aussi un répertoire `0700`, des fichiers `0600` et le sidecar archivé `b8210d25…`. Le backup privé n’a pas été relu ni re-haché par cette revue. |
| 4. Déploiement | **UNKNOWN** | Le listing Docker voit le conteneur sur le tag final et `healthy`. L’image immuable, sa taille, l’image précédente conservée et les options effectives du conteneur ne sont pas vérifiables ici. Le Compose versionné contient bien `network_mode: host`, `read_only`, tmpfs, volume, secrets, `cap_drop`, `no-new-privileges`, restart et healthcheck (`compose.yaml:10-35`, `compose.openclaw.yaml:3-18`) ; cela ne prouve pas les valeurs effectives de l’override privé. |
| 5. Migration | **UNKNOWN** | La preuve rapporte v5, ledger `[1..5]`, intégrité OK, FK 0, trois propositions avec états APPLIED/EXPIRED/REJECTED, un événement d’expiration, backup B7 v3 en `0600`, verrou `0600` et cache en `0700` (`V7-11B_LIVE_MIGRATION_EVIDENCE.md:55-73`). L’état SQLite live n’a pas pu être inspecté directement. Les compteurs ne démontrent pas à eux seuls l’égalité logique de toutes les lignes avant/après. |
| 6. Legacy, OpenClaw et Relay | **UNKNOWN** | La preuve rapporte legacy 200/401, adapter et capabilities préservés, zéro erreur Core et Relay health 200 sans arrêt. Le Compose confirme la configuration OpenClaw déclarée (`compose.openclaw.yaml:3-18`) ; les sondes HTTP, les logs et l’inspection live n’étaient pas accessibles. Aucun chat réel ni sync Android n’a été exécuté. |
| 7. État live, sécurité et nettoyage | **UNKNOWN** | La preuve rapporte health 200, ports 8100/4402, DB live `0644`, backup local sans chiffrement ni copie hors site, absence de résidus isolés et conservation de l’image finale (`V7-11B_LIVE_MIGRATION_EVIDENCE.md:83-91`). Le Core `healthy` et le Relay actif sont visibles dans `docker ps`, mais les ports, le health Relay, les réseaux résiduels et les modes live ne sont pas confirmables dans ce sandbox. |
| 8. Sanitisation | **PASS** | Aucun secret, identifiant d’appareil, contenu de proposition ou chemin personnel n’est publié. La déclaration de sanitisation nomme des catégories absentes ; elle ne révèle pas leurs valeurs. Les hashes, compteurs, modes et statuts sont acceptables. |
| 9. Rollback | **FAIL — P2 documentaire** | L’image précédente et deux backups v3 sont identifiés, et la perte des écritures post-backup est annoncée. La procédure non exécutée ne précise pas comment sélectionner l’image précédente dans l’override Compose persistant, quel fichier/volume restaurer, comment traiter les fichiers SQLite annexes et les permissions, ni les contrôles avant redémarrage. Elle n’est donc pas entièrement non ambiguë (`V7-11B_LIVE_MIGRATION_EVIDENCE.md:75-81`). |
| 10. Gates | **PASS** | `GO_V7_11_LIVE_MIGRATION` et `GO_V7_11_SCHEMA_V5_LIVE` sont cohérents comme résultats **locaux rapportés**. `GO_V7_11_ANDROID_POST_MIGRATION_SYNC` et `GO_V7_11_IDENTITY_BINDING` restent correctement `NOT_RUN`. `GO_V7_11_LIVE_CHANGE_READINESS` reste correctement `NO-GO`. Aucun gate ne justifie une qualification Android, un binding ou un cutover complet. |

## Findings

### P0

Aucun.

### P1

Aucun défaut P1 confirmé dans les preuves examinées.

### P2

1. **EVIDENCE_GAP — vérification runtime indépendante indisponible.** L’inspection Docker, les logs, l’inventaire images/réseaux et les sondes HTTP sont bloqués. Plusieurs statuts de la grille restent donc UNKNOWN dans cette revue ; `docker ps` ne confirme qu’un Core sur le tag final déclaré `healthy` et un Relay actif. Ne pas transformer les résultats rapportés en validation indépendante.
2. **EVIDENCE_GAP — rollback pas assez opérable.** Ajouter à une procédure privée d’exploitation la sélection explicite de l’image précédente et de l’override Compose, la source exacte du backup identifiée par hash complet (la preuve ne donne que des préfixes pour certains artefacts), le volume et le remplacement de la DB, le traitement des fichiers SQLite annexes, les modes/propriétaires, puis les contrôles v3 avant de remettre le service en ligne. Le restore post-migration n’a pas été exercé.
3. **RISK — protection et reprise des backups.** La DB live reste `0644` et la sauvegarde reste locale au même hôte, sans chiffrement au repos ni copie hors site (`V7-11B_LIVE_MIGRATION_EVIDENCE.md:88`). Ces limites sont déclarées ; elles empêchent de considérer la reprise après perte de l’hôte comme qualifiée.
4. **EVIDENCE_GAP — conservation logique.** Les compteurs, états, intégrité et FK sont cohérents, mais aucun comparatif logique déterministe des données avant/après n’est présenté. Ils ne suffisent pas à exclure une altération de valeurs avec totaux inchangés.

## Conditions restantes

- Conserver les deux gates de migration en portée locale et les gates Android/binding en `NOT_RUN` jusqu’à leurs vérifications dédiées.
- Garder `GO_V7_11_LIVE_CHANGE_READINESS` à **NO-GO** tant que le binding, le sync Android, l’administration/restauration Relay et une stratégie de backup hors hôte ne sont pas qualifiés.
- Clarifier la procédure de rollback avant de s’appuyer sur elle ; ne pas annoncer que le rollback lui-même a été testé.
- Toute validation indépendante future des états Docker doit rester en lecture seule et être liée au SHA de preuve qu’elle examine.

## Limites

Cette revue ne vérifie pas les valeurs live de la base, des secrets, de l’override Compose, des ports ou des fichiers dans le volume. Les commandes `docker inspect`, `docker logs`, liste d’images/réseaux et `curl` n’ont pas fourni de preuve exploitable dans le sandbox. Aucun test Android, tour OpenClaw, sync, migration ou restauration n’a été exécuté par le relecteur.

## Re-revue après correction

**Verdict révisé : GO_WITH_LIMITATIONS local / NO-GO pour le cutover complet.** Aucun P0/P1 confirmé. Le contrôle logique ferme le P2 de conservation. Le P2 de rollback n’est pas entièrement clos : le runbook couvre le parcours demandé, mais conserve une ambiguïté d’exécution à corriger avant de s’y fier.

| Point repris | Statut | Vérification |
|---|---|---|
| P2 #2 — rollback | **FAIL — reste ouvert** | Le runbook identifie l’image précédente, l’override de rollback, le volume persistant, le backup frais par empreinte, sa restauration, le renommage du backup B7, le retrait du verrou, les permissions/propriétaire, les contrôles v3/intégrité/FK/compteurs, le démarrage avec l’image précédente et l’échec de démarrage (`V7-11B_LIVE_ROLLBACK_RUNBOOK.md:10`, `:15`, `:41`, `:69`, `:74`, `:77`, `:107`, `:133`). Toutefois, la commande de renommage contient un marqueur d’horodatage entre chevrons non déclaré parmi les placeholders ; inséré tel quel dans le programme shell, il est interprété comme une redirection et rend la commande invalide (`V7-11B_LIVE_ROLLBACK_RUNBOOK.md:77`). Le chemin d’échec couvre l’image ancienne qui ne démarre pas, mais ne précise pas quoi faire si la copie de restauration échoue ou s’interrompt. Remplacer le marqueur par une expression shell valide et expliciter cette branche d’échec avant de fermer ce P2. |
| P2 #4 — conservation logique | **PASS — clos** | Le document compare 19 tables v3 aux 25 tables v5 : 17 tables sont MATCH, les seules différences rapportées sont une modification de `state` autorisée pour la ligne expirée et l’ajout de deux entrées au ledger ; six tables nouvelles sont listées (`V7-11B_LIVE_MIGRATION_LOGICAL_CHECK.md:15`, `:16`, `:26`, `:35`, `:43`, `:44`, `:46`). Le hash SHA-256 déterministe des tuples triés sur les colonnes communes, sans publier les valeurs, est une méthode convaincante pour détecter une altération accidentelle des données existantes. |
| P2 #1 — vérification Docker indépendante | **UNKNOWN — reste ouvert** | Cette re-revue n’a exécuté aucune commande Docker, conformément au mandat. L’UNKNOWN précédent n’est pas promu en PASS. |
| P2 #3 — protection/reprise des backups | **Risque accepté — reste une limite** | Le backup demeure local au même hôte, sans chiffrement au repos ni copie hors site ; la DB live est documentée en `0644`. Cette limite n’est pas transformée en PASS. |

Le contrôle logique est convaincant dans sa portée déclarée, mais ne compare pas les nouvelles colonnes ni les données des six tables nouvelles et n’a pas été rejoué indépendamment : sa copie cible a été supprimée (`V7-11B_LIVE_MIGRATION_LOGICAL_CHECK.md:48`, `:52`). Le runbook est explicitement non exécuté ; aucune restauration ou mutation n’a été tentée. Les gates Android post-migration et binding restent `NOT_RUN`, et `GO_V7_11_LIVE_CHANGE_READINESS` reste **NO-GO**.
