# Preuve — permissions et provenance du backup pré-migration

## Résultat

Le backup pré-migration est créé exclusivement avec `O_CREAT | O_EXCL | O_RDWR | O_NOFOLLOW` et un mode demandé de `0600`, puis `fchmod(0600)` est appliqué sur le descripteur ouvert. Le fichier et son entrée de répertoire sont vérifiés après écriture : fichier régulier, même inode que le descripteur, propriétaire effectif du processus et mode exact `0600`. Une erreur de création ou de validation supprime le fichier partiel appartenant à l'opération et lève `MigrationIntegrityError`.

Le snapshot SQLite est produit par l'API de backup dans une base mémoire, écrit via le descripteur exclusif, synchronisé, relu depuis ce descripteur et validé à nouveau avec `integrity_check` et `user_version`. Le chemin du backup n'est jamais rouvert par SQLite.

Tout fichier, symlink ou symlink cassé déjà présent provoque un refus immédiat avec l'instruction opératoire de vérifier puis déplacer l'ancien backup avant de relancer. Aucun contenu ni mode de la cible préexistante n'est modifié. Le helper est appelé avant le passage au schéma V4 ; une mise à niveau qui ne manque que la version V5 ne crée ni ne consomme de backup pré-V4. La réouverture d'une base déjà migrée en V5 réussit.

## Rouge avant correctif

Les nouveaux cas de régression exécutés sur l'implémentation initiale ont confirmé :

- sous `umask 022`, le backup était créé en mode `0644` ;
- des backups préexistants valides en modes `0600` et `0644` étaient réutilisés sans erreur ;
- un symlink vers une cible existante et un symlink cassé ne provoquaient pas le refus attendu.

## Vérifications après correctif

- Backup d'une base de version 3 : mode `0600`, propriétaire euid, fichier régulier non symlink, `user_version=3` et `integrity_check=ok` ; migration jusqu'à la version 5 réussie et compteurs des tables héritées inchangés.
- Fichiers préexistants `0600` et `0644`, symlink valide et symlink cassé : refus `MigrationIntegrityError`; contenu/mode du fichier et cible du symlink préservés.
- Échec simulé à l'écriture : backup partiel supprimé et migration non appliquée.
- Réouverture après migration V5 : réussie.
- Tests ciblés `test_v4_migrations.py` : **9 réussis**.
- Suite backend : **474 réussis, 1 ignoré**.
- Suite racine : **519 réussis, 1 ignoré**.
- `git diff --check` : **PASS**.

Commandes de test exécutées avec Python 3.12 et `ATLAS_AGENT_ADAPTER=mock` :

```sh
python -m pytest -q -p no:cacheprovider tests/test_v4_migrations.py
python -m pytest -q -p no:cacheprovider
```

## Conséquence opératoire et limites

Avant toute migration live, l'ancien backup signalé lors de la revue (mode `0644`, taille `64 K`) doit être vérifié puis déplacé. Le prochain backup sera créé en mode `0600`. Aucune base live, copie de base, donnée réelle, appareil, ADB ou secret n'a été utilisé. La validation porte sur des bases temporaires synthétiques dans l'environnement local ; aucune migration live ni QA Android n'a été exécutée.

Les suites conservent des avertissements préexistants de dépréciation et de schéma OpenAPI ; aucun avertissement n'a fait échouer les suites.
