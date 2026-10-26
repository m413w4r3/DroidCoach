# ATLAS V6.3 — Audit de confidentialité des preuves versionnées

Date : 2026-10-26. Périmètre : `docs/v6-3/**` et `docs/v6/**` de l'arbre
courant. Aucune réécriture d'historique Git n'a été effectuée.

## 1. Constats

| Élément | Où | Sensibilité | Correction dans l'arbre courant |
|---|---|---|---|
| Identifiant matériel de l'appareil physique | `R1_RUNBOOK.md`, `HANDOFF_R1.md`, `preuves/r1-physical.txt` | identifiant d'appareil | remplacé par `$PIXEL_SERIAL` ou `<serial masqué>` |
| Nom d'un calendrier personnel | `preuves/r1-physical.txt` | donnée personnelle | remplacé par « un nom non identifiant » |
| Chemins absolus contenant le nom d'utilisateur de l'hôte | plusieurs documents et preuves | détail d'environnement | remplacés par `$HOME/...` |
| Jeton OpenClaw, jeton backend, clé Bridge | uniquement des chemins de fichiers et des variables, jamais de valeur | secret | aucun changement nécessaire ; aucune valeur n'était versionnée |
| Motifs e-mail | aucun identifiant de compte trouvé dans les preuves courantes | données de compte | vérification conservée |

Aucun secret réel, aucune valeur de `.env`, aucun contenu d'événement et aucune
donnée de santé n'a été trouvé dans les documents versionnés.

## 2. Exposition historique résiduelle

Les commits antérieurs au correctif peuvent encore contenir l'identifiant
matériel et le nom de calendrier dans l'historique Git local. Ces objets ne
sont pas réécrits par R2, conformément à la mission :

- aucun `git filter-branch`, `git filter-repo`, rebase interactif ou
  `push --force` n'a été exécuté ;
- si le dépôt est un jour poussé ou partagé, une décision explicite du
  propriétaire sera nécessaire pour décider d'une réécriture d'historique ou
  d'une rotation/considération de l'identifiant exposé ;
- l'identifiant matériel n'est pas un secret d'authentification, mais il peut
  servir de corrélation ; il ne doit pas être recopié dans de nouveaux
  documents ou commits.

## 3. Règles pour les preuves R2

- Captures et fixtures exclusivement synthétiques.
- Aucun serial, compte, adresse e-mail, chemin privé, jeton ou nom de
  calendrier personnel.
- Les identifiants de session Paseo, SHA et codes d'erreur sont autorisés.
- Les chemins de travail sont référencés relativement au dépôt ou via
  `$HOME` lorsqu'ils sont utiles.
- Toute nouvelle preuve est relue avant `git add`.

## 4. Vérification

Commandes de contrôle utilisées :

```bash
rg -n "45301|[A-Z0-9]{14,}" docs/v6-3 docs/v6
rg -n -i "[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}" docs/v6-3 docs/v6
rg -n "/home/[a-z]" docs/v6-3 docs/v6
```

Résultat après correction : aucun identifiant matériel, aucune adresse e-mail
personnelle et aucun chemin absolu d'utilisateur dans les documents courants.
Les faux positifs de type SHA ou identifiants techniques sont explicitement
acceptés.
