# R3-B — Redaction des identifiants d'environnement

Le **2026-10-28**, lors de la campagne R3-C, les identifiants d'environnement
présents dans les preuves R3-B ont été remplacés par des pseudonymes :

| Valeur d'origine | Pseudonyme |
|---|---|
| numéro de série du Pixel | `$PIXEL_SERIAL` |
| adresses publiques du Funnel | `$FUNNEL_PUBLIC_IP` |
| IP physique du PC | `$PC_PHYSICAL_IP` |
| IP publique du PC | `$PC_PUBLIC_IP` |

Les valeurs exactes sont conservées **hors dépôt** dans
`~/atlas-backups/r3c/` (`pixel-serial.txt`, `funnel-public-ip.txt`,
`pc-physical-ip.txt`, `pc-public-ip.txt`, mode 0600).

Cette redaction ne modifie **aucun** SHA, commande, horodatage, résultat,
verdict ou statut de preuve. Seuls les identifiants d'environnement ont été
pseudonymisés. Le hostname public `all.taildc7886.ts.net` est conservé car il
fait partie de l'infrastructure imposée et reste nécessaire aux commandes.

Fichiers concernés : `DECISIONS.md`, `DEPLOIEMENT_TAILSCALE_FUNNEL.md`,
`HANDOFF.md`, `MATRICE_RECETTE.md`, `PREUVES.md`, `REGISTRE.md`,
`RUNBOOK_PIXEL.md`, `tickets/R3B-ANDROID-01.md` et les fichiers
`preuves/*.txt` qui contenaient ces valeurs.
