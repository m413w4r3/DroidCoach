# V7-11B — Revue indépendante de la vérification Pixel après migration v5

**Date :** 2026-11-09
**BASE_SHA / HEAD examiné :** `5670febeb0fad0d45f6205bbda0cba311e07573a`
**Verdict : GO_WITH_LIMITATIONS pour le constat `BLOCKED / NOT_RUN`; aucun GO indépendant du Pixel, du Core live ou de la sync.** Aucun P0/P1 confirmé.

## Portée et méthode

Revue documentaire de la preuve Android post-migration, de la preuve et de la revue de migration live, de la préparation et du registre V7-11B. Le HEAD correspond au SHA demandé et l’arbre était propre au début de la revue. Le commit examiné ajoute seulement `V7-11B_ANDROID_POST_MIGRATION_EVIDENCE.md`; aucun changement de code, permission, configuration réseau, consentement ou bearer n’est présent dans son diff.

La revue n’a invoqué ni ADB, ni commande d’écriture Docker, ni test, ni restauration, ni migration. Les inspections directes du runtime n’étaient pas accessibles dans ce sandbox : `ss` a été refusé au socket netlink, la sonde HTTP loopback n’a pas connecté, et les commandes Tailscale et Docker n’ont pas retourné d’état exploitable. Ces échecs de visibilité ne démontrent pas une panne du Core ou du Pixel.

## Grille de revue

| # | Point | Statut | Conclusion |
|---|---|---|---|
| 1 | Base et périmètre | **PASS** | HEAD et base correspondent au SHA complet demandé, arbre propre au début, commit limité à la preuve Android. Aucun changement de permission, réseau, consentement ou bearer n’est introduit par ce commit. |
| 2 | Pixel | **UNKNOWN** | La preuve opérateur rapporte APK, version, certificat, HOME, activité au premier plan, 15/15 noms `shared_prefs`, permissions 23/23 identiques et aucun FATAL/ANR/Keystore. Aucune vérification indépendante ADB n’a été faite par ce relecteur, conformément au mandat. |
| 3 | Core v5 et requêtes | **UNKNOWN** | La preuve rapporte health 200, schéma v5, intégrité OK, FK 0 et aucune requête applicative hors health/test legacy synthétique. Les logs et la sonde locale n’étaient pas lisibles depuis ce sandbox ; ces résultats restent des constats opérateur, pas une confirmation runtime indépendante. |
| 4 | Chemin réseau et conclusion de sync | **PASS** | Selon les constats consignés (mode avion, Wi-Fi sans VPN, pair mobile Tailscale hors ligne, Core/Relay en loopback, Funnel vers un proxy absent, Serve vers un autre port, proxy/connecteur absents), aucune route vers le Core n’est établie. `BLOCKED / NOT_RUN` est donc une conclusion correcte ; ce n’est pas un FAIL du Core. Les détails de topologie n’ont pas pu être vérifiés indépendamment ici. |
| 5 | Absence de mutation | **PASS (documentaire)** | Le périmètre consigne aucune modification de permission, réseau, rôle HOME, consentement, credential ou APPLY. Le seul écrit local mentionné est le fichier de synchronisation applicatif ; il ne prouve aucune sync distante. Aucun indice contraire n’apparaît dans le diff examiné. |
| 6 | Gates | **PASS** | `GO_V7_11_ANDROID_POST_MIGRATION_SYNC` est `BLOCKED / NOT_RUN`, `GO_V7_11_IDENTITY_BINDING` reste `NOT_RUN`, et `GO_V7_11_LIVE_CHANGE_READINESS` reste `NO-GO`. La préparation et le registre emploient aussi `NOT_RUN` pour la sync ; aucune de ces formulations ne surévalue le résultat. |
| 7 | Sanitisation | **FAIL — P2** | Aucun bearer, token, secret, IP non-loopback, SSID, numéro de série, contenu de préférence ou ID de proposition n’est publié. La preuve nomme toutefois un pair/nœud Tailscale (preuve Android, ligne 42). Ce libellé identifiant dépasse les seuls états/ports génériques annoncés ; le retirer avant de réutiliser la preuve. |
| 8 | Conditions de déblocage | **PASS** | Les trois choix sont explicites : Serve limité au tailnet sur le port Core, proxy+connecteur Relay, ou confirmation d’un périmètre local-only. La preuve indique qu’aucun n’est couvert par l’autorisation actuelle ; chaque choix requiert donc une autorisation/confirmation distincte. |

## Findings

### P0

Aucun.

### P1

Aucun défaut P1 confirmé.

### P2

1. **Sanitisation — identifiant de pair Tailscale publié.** `V7-11B_ANDROID_POST_MIGRATION_EVIDENCE.md:42` donne le libellé du nœud. Le supprimer ou le remplacer par un état générique (« pair mobile hors ligne ») avant diffusion de cette preuve.
2. **EVIDENCE_GAP — runtime non vérifiable par le relecteur.** `ss`, Tailscale, Docker et la sonde HTTP locale n’ont fourni aucune observation exploitable dans ce sandbox. Les claims Pixel, health/version SQLite et absence de requêtes restent `UNKNOWN` indépendamment, bien que cohérents avec les rapports opérateur et les gates conservateurs. Ne pas les présenter comme vérifiés par cette revue.

## Conclusion et conditions de déblocage

Le résultat utile de cette preuve est que la sync post-migration n’a pas été démontrée faute de chemin réseau observé ; il est correct de la laisser `BLOCKED / NOT_RUN`. La preuve ne qualifie ni le fonctionnement de l’app contre le Core v5, ni l’identité/binding, ni le cutover complet. Le gate `GO_V7_11_LIVE_CHANGE_READINESS` doit rester `NO-GO`.

Avant une nouvelle tentative, choisir un seul chemin et obtenir son autorisation ciblée : (1) exposer le Core au tailnet avec Serve sur son port loopback ; (2) démarrer le proxy et le connecteur Relay avec leur identité/configuration ; ou (3) confirmer que la sync distante est hors périmètre. Toute activation du chemin réseau ou changement d’identité/credential demeure hors de la présente preuve.

## Limites de la revue

- Aucun accès indépendant au Pixel ou aux valeurs live Core/Relay ; aucun ADB, test, mutation ou action Android exécutés.
- Les résultats runtime présentés sont ceux rapportés dans la preuve opérateur, pas des observations reproduites.
- Une sonde HTTP a renvoyé l’absence de connexion dans le sandbox. L’auto-review a rejeté la tentative de supprimer un éventuel fichier temporaire de sortie, estimant que cette suppression violerait la lecture seule stricte. Aucun contournement n’a été tenté ; l’existence d’un tel artefact reste inconnue.

## Re-revue après activation du chemin

**Cible :** preuve mise à jour dans `dea1fb4` ; le fichier correspondant dans ce worktree (`HEAD=09d809a`) est identique. Cette re-revue et son commit s’ajoutent à `09d809a`, sans amendement.
**Verdict : GO_WITH_LIMITATIONS local / NO-GO cutover.** Aucun P0/P1 confirmé ; le P2 de sanitisation est clos et le P2 de vérification runtime reste `UNKNOWN`.

| # | Point repris | Statut | Conclusion |
|---|---|---|---|
| 1 | Sanitisation | **PASS — P2 clos** | La preuve dit désormais seulement « nœud hors ligne » (ligne 42) ; l’ancien libellé du pair n’y figure plus. Aucun identifiant Tailscale ni valeur interdite n’est publié. |
| 2 | État du chemin | **PASS (preuve opérateur)** | La preuve rapporte mode avion désactivé par l’utilisateur, Wi-Fi validé sans VPN, Tailscale mobile toujours hors ligne, proxy 4400 et connecteur 4401 actifs, health/metrics 200, `relay_connected=true`, WebSocket 101 et Funnel ciblant le proxy. Ces éléments sont documentés, non reproduits par le relecteur. |
| 3 | Tentative de sync | **PASS pour `BLOCKED / PARTIAL`** | `MainActivity` a été ouverte et `CoreV4SyncWorker` forcé (job 253) ; aucun appel app n’est observé au Core ou au connecteur et le fichier de sync reste inchangé. Le chemin de transport paraît sain selon la preuve, mais la sync n’a pas eu lieu : le blocage est bien à l’émission/déclenchement côté app, pas un échec du Core. La cause précise reste inconnue : absence de données en attente, endpoint/trigger non aligné ou sync non déclenchée sont des hypothèses, pas des défauts confirmés. |
| 4 | Vérification runtime indépendante | **UNKNOWN — P2 maintenu** | L’activation et les métriques sont des résultats rapportés par l’opérateur. Cette re-revue n’a pas utilisé ADB, Docker, `ss`, Tailscale ni sondes live ; le sandbox ne permet toujours pas de promouvoir ces états en PASS indépendant. |
| 5 | Gates | **PASS** | La preuve mise à jour indique sync `BLOCKED / PARTIAL`, binding `NOT_RUN` et `GO_V7_11_LIVE_CHANGE_READINESS` `NO-GO`. Aucun gate ne qualifie la sync réussie, le binding ou le cutover. |

### Findings après re-revue

- **P0 :** aucun.
- **P1 :** aucun.
- **P2 sanitisation :** clos par la suppression du libellé de nœud.
- **P2 runtime :** `UNKNOWN`, ouvert jusqu’à une vérification indépendante autorisée et accessible.

Le résultat partiel est correctement borné : le transport est rapporté sain, mais aucune requête de sync n’a été émise et la sync Android n’est pas qualifiée. Garder `GO_V7_11_LIVE_CHANGE_READINESS` à **NO-GO**.
