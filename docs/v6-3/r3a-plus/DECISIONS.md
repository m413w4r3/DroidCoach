# ATLAS V6.3 — R3-A+ — Décisions

Les décisions historiques R3-A restent dans
`docs/v6-3/r3a/DECISIONS_ARCHITECTURE.md` et ne sont pas réécrites.

## D1 — Pixel physique remplace l'émulateur

- Date : 2026-10-27.
- Décision : toute la recette Android de R3-A+ utilise le Pixel 9 Pro XL
  physique, Android 17 / API 37, à la place de `emulator-5554`.
- Motif : preuves matérielles plus fortes, demande explicite du propriétaire.
- Conséquences : sauvegarde app-only avant toute action, aucune donnée
  personnelle modifiée, un seul opérateur ADB, pas de `pm clear`/`uninstall`,
  pas de changement global de fuseau ni de redémarrage du téléphone sans accord
  ciblé. Les tests qui exigeraient ces actions sont remplacés par des tests
  applicatifs injectés ou marqués `NOT_RUN`.

## D2 — Politique d'URL du relais

- `wss://` est obligatoire pour tout hôte non-loopback.
- `ws://` n'est accepté que vers `127.0.0.1`, `localhost`, `::1` ou `[::1]`,
  et seulement si le mode développement local est explicitement activé côté PC.
- Sont refusés : identifiants dans l'URL, query fournie par l'utilisateur,
  fragment, chemin autre que `/` ou `/ws`, schéma autre que `ws`/`wss`, hôte
  vide, redirection non vérifiée.
- `ATLAS_BACKEND_URL` reste limité au loopback, sans chemin, query ni
  identifiants. Aucun proxy HTTP générique n'est ajouté.
- Côté Android, la release n'accepte que `wss://` pour un relais distant ; le
  debug peut accepter un relais local pour les tests. La confiance d'une CA de
  test est confinée au source set `debug` et ne peut pas affecter une release.
- TLS et Noise sont indépendants et cumulatifs : WSS ne remplace pas Noise
  `protected`, et Noise ne justifie pas de désactiver TLS.

## D3 — Infrastructure TLS de test

- Le nom de test est résolu par injection dans les clients (`lookup` Node,
  `Dns`/socket factory Android), sans modification de `/etc/hosts` ni du DNS
  système.
- Une CA de test dédiée signe les certificats de test. La clé privée de la CA
  reste hors du dépôt, sous `~/.local/share/atlas-v63-r3a-plus/pki/` ; seule la
  certificat public peut être versionné dans le source set debug.
- Le proxy TLS de test termine TLS puis relaie les octets vers le relais Paseo
  en clair sur loopback. Le relais upstream n'est pas modifié.
- Les certificats de test ne sont jamais approuvés par une build release.

## D4 — Packaging du connecteur

- Le connecteur ne dépend plus d'un clone Paseo à un chemin relatif.
- La dépendance `@getpaseo/relay` est distribuée avec une version exacte, un
  SHA upstream, un hash d'artefact et la licence Apache-2.0.
- Le mécanisme retenu est un paquet généré depuis le commit upstream épinglé,
  versionné dans `integrations/relay/pc/vendor/`, avec lockfile et intégrité.
  Un paquet npm publié peut remplacer ce mécanisme si son contenu est vérifié
  et si l'installation reste reproductible hors ligne.
- Aucune copie arbitraire de la bibliothèque Paseo complète ni nouveau fork.

## D5 — Supervision PC

- Une unité systemd **utilisateur** est fournie et validée en environnement
  isolé. Elle n'est pas installée sur la session personnelle du propriétaire
  pendant la campagne.
- Redémarrage `on-failure`, arrêt propre, configuration externe, identité
  persistante, logs bornés, aucun secret dans la ligne de commande.
- La persistance de session (`loginctl enable-linger`) est documentée mais non
  activée automatiquement.

## D6 — Données de l'appareil

- Sauvegarde app-only avant toute installation ou modification.
- Seuls des calendriers/événements synthétiques préfixés `ATLAS TEST` sont
  créés. Les calendriers Google personnels sont interdits d'écriture.
- La mort réelle du processus est provoquée par `Process.killProcess` dans un
  test instrumenté en deux phases, pas par une injection simulée.
- Les changements de fuseau/DST sont testés par injection applicative et par
  des fixtures à fuseau explicite, pas par modification du fuseau global.
- La révocation de permissions ne porte que sur `dev.atlas.coach`, jamais sur
  Etar ni sur une application personnelle.

## D7 — Statuts

`PASS` exige une commande, un SHA, un environnement et une preuve expurgée.
`PARTIAL` signale une couverture incomplète. `BLOCKED` exige une cause externe.
`NOT_RUN` est préférable à une revendication non prouvée. V6.3 global reste
`NO_GO` jusqu'aux recettes R3-B.
