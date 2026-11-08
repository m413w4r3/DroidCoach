# V7-11B-B11 — Revue indépendante du backup live

**Date :** 2026-11-08
**Base / HEAD à l'ouverture :** `37bd63b0e76f99e9e9132b00d7bc56ab95d5dc3c`
**Périmètre :** revue de la preuve opérateur, des procédures, des manifestes et des métadonnées lisibles en lecture seule.

## Verdict

**GO_WITH_LIMITATIONS pour le backup local ; NO-GO pour toute opération live suivante.** Aucun P0/P1 confirmé ; deux P2 documentaires/de couverture sont ouverts. La preuve établit l'exécution du backup local et les artefacts principaux se vérifient. Le sandbox a refusé l'accès au daemon Docker et les sondes loopback n'étaient pas joignables ; les états runtime ne sont donc pas validés indépendamment.

## Revue par point

| # | Verdict | Preuve et limite |
|---|---|---|
| 1. Base et périmètre | **PASS** | À l'ouverture, `HEAD` égalait la base demandée et `git status` était propre ; aucun diff de code. La preuve opérateur déclare aucune migration, aucun changement de bearer et aucune promotion. Ces trois opérations live ne sont pas directement inspectables depuis ce sandbox. |
| 2. Timeline et disponibilité | **UNKNOWN** | Les horodatages consignés donnent 14 s entre arrêt et redémarrage Core. La date de démarrage Relay et l'absence d'arrêt sont cohérentes dans la preuve. `docker inspect` a été refusé par le sandbox et les deux sondes health ont renvoyé `000` : arrêts, compteurs de redémarrage et health live restent non recoupés. |
| 3. Pixel | **UNKNOWN** | La preuve consigne `preflight`/`backup`/`verify` PASS, 25 fichiers, les modes 0700/0600, le retour HOME et 15/15 préférences. L'archive présente dans le backup passe une vérification hors appareil : hash et manifeste cohérents, 25 entrées. Aucun accès ADB n'a été fait ; état de l'application et rôle HOME restent attestés par l'opérateur seulement. |
| 4. Core | **UNKNOWN** | Les hashes de la DB et du sidecar consignés par l'opérateur sont présents dans les artefacts privés ; les copies vérifiées ont le mode 0600. Version SQLite, intégrité, FK, compteurs, health, `restarts=0` et absence du chemin live exact reposent sur la preuve opérateur. Je n'ai ni interrogé Docker ni ouvert les bases. |
| 5. Relay | **UNKNOWN** | Le hash des métadonnées Relay correspond à un artefact du manifeste global. Le démarrage continu, health 200, absence de montage et absence d'arrêt ne sont pas directement vérifiables ici. La preuve indique que l'état volatile n'a pas été sauvegardé au-delà des métadonnées. |
| 6. Connecteur | **UNKNOWN** | Le manifeste connecteur à 30 entrées est présent et ses 30 hashes correspondent aux fichiers sauvegardés. Les trois hashes d'état cités dans la preuve correspondent aussi aux fichiers de backup ; le hash du fichier de configuration sensible est inclus. L'inactivité live, l'égalité au live et la restauration 3/3 sur copie sont consignées par l'opérateur, sans vérification runtime indépendante. Aucun contenu de credential n'a été ouvert. |
| 7. Restauration | **UNKNOWN** | La preuve consigne les vérifications Core et connecteur sur copies, l'archive Pixel vérifiée et l'absence de promotion live. Le contenu des bases et la suppression des copies de test ne sont pas indépendamment recoupés. Aucun restore ou changement live n'a été exécuté par cette revue. |
| 8. Sécurité du backup | **PASS**, avec P2 | Hash du manifeste global conforme à `bc92c095…` ; taille disque `du` conforme à 427 KiB. Racine et 10 sous-répertoires en 0700 ; 44 fichiers réguliers au total en 0600, aucun world-readable ni lien symbolique. Les 10 entrées globales correspondent en taille et hash ; les 30 entrées du manifeste connecteur et les 25 entrées du manifeste Pixel sont vérifiées. Quatre petits fichiers auxiliaires (un digest et trois logs, 1 446 octets au total) restent hors de ces manifestes. |
| 9. Sanitisation | **FAIL** | Aucun chemin personnel, valeur bearer/token, UUID brut ou contenu personnel repéré. La section 6 de la preuve opérateur expose toutefois un nom de fichier de configuration credential, puis la section 11 affirme qu'aucun nom de ce type n'apparaît. Cette contradiction contrevient à la règle de sanitisation ; le nom lui-même n'est pas reproduit ici. |
| 10. Gates | **PASS** | `GO_V7_11_LIVE_BACKUP` reste justifié pour le snapshot local, avec les P2 ci-dessous ; `GO_V7_11_BACKUP_ROLLBACK_PLAN` est correctement limité aux copies et à l'absence de restauration live/hors-site ; `GO_V7_11_LIVE_CHANGE_READINESS` reste **NO-GO**. Aucun gate live n'est surévalué dans la preuve examinée. |

## Findings

### P0

Aucun.

### P1

Aucun.

### P2

1. **DEFECT_CONFIRMED — sanitisation incohérente.** `docs/operations/evidence/V7-11B_LIVE_BACKUP_EVIDENCE.md:77` et `:132` : un nom de fichier de configuration credential est publié, contrairement à l'affirmation de sanitisation. Retirer ce nom dans une correction documentaire autorisée et garder la description au niveau « configuration credential sauvegardée ».
2. **EVIDENCE_GAP — couverture des auxiliaires.** `docs/operations/evidence/V7-11B_LIVE_BACKUP_EVIDENCE.md:96` décrit l'empreinte globale. Le manifeste global et les manifestes de composants couvrent les artefacts Pixel, Core, Relay et connecteur vérifiés, mais pas quatre fichiers auxiliaires présents dans la racine privée. Leurs modes sont privés ; leur contenu n'a pas été ouvert. Les inclure dans l'inventaire ou documenter explicitement leur exclusion afin que l'empreinte globale décrive sans ambiguïté le contenu conservé.

## Conditions avant toute opération live

- Déployer et vérifier le code Core V7-11 sur le live avant migration ; le Core observé dans les dossiers de préparation est pré-V7-10.
- Obtenir une autorisation nominative distincte pour appliquer la migration v3→v5 au volume live, avec sauvegarde pré-migration v3 vérifiée et procédure de rollback.
- Après migration, établir et vérifier le principal/binding attendu ; décider explicitement du sort des **3 propositions sans propriétaire** et de la **1 candidate d'expiration**. Aucun backfill, APPLY ou expiration silencieux.
- Identifier l'administration, la persistance et la recréation sûre du Relay ; ne pas remplacer le conteneur sur la base de la seule preuve de métadonnées.
- Déployer une sauvegarde connecteur complète et durable, et confirmer la procédure de restauration avant toute opération d'identité.
- Résoudre les conditions live de l'anti-rejeu (cache durable privé, instance/volume Core unique, secrets distincts et horloge stable). Le parcours Relay sur AVD reste `NOT_RUN` ; la QA fonctionnelle/APPLY Pixel reste non exécutée.
- Ne pas traiter le sidecar v0 archivé comme rollback pré-migration. Aucune restauration live n'est couverte par cette revue.

## Limites de la revue

- `docker inspect` a échoué avec permission refusée sur le daemon ; les requêtes health loopback ont échoué depuis le sandbox. Les contrôles live des points 2, 4, 5, 6 et 7 restent `UNKNOWN`.
- Aucune commande ADB, aucun Docker en écriture, aucune migration/restauration, aucun test destructif ; aucune base, donnée applicative ou valeur de credential ouverte. Les vérifications privées ont porté sur les métadonnées et les hashes autorisés.
- Les résultats Pixel, SQLite, health, connecteur live et nettoyage des copies sont examinés comme preuve opérateur lorsqu'ils ne sont pas recoupables indépendamment.
- Revue du snapshot local uniquement ; elle n'autorise ni promotion, ni migration, ni changement de bearer, ni restauration live.
