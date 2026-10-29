# ATLAS V6.3 — Rapport UX/UI R4

- **Date** : 2026-10-29
- **Branche** : `feat/atlas-v6-3-ux-daily-use`
- **HEAD** : `7d7b5f5`
- **Base `origin/main`** : `566668ac9cd0778f43d7f4d90a93c6a45645935c`
- **Statut** : code et tests AVD terminés ; **validation Pixel ciblée effectuée le 2026-10-29** ; revue indépendante **GO_WITH_LIMITATIONS** ; **push final effectué sur `main`** après validation et revue.

## Résultat

La passe rend les parcours **Coach** et **Planning** directs, allège l'accueil, remplace les états techniques du relais par un vocabulaire humain, corrige le raccourci launcher cassé et ajoute une vue `Demain` réellement distincte.

| Parcours | Avant | Après |
|---|---|---|
| Accueil | directive dominante, aucun accès Coach/Plan | date/heure, prochaine activité, `Parler au coach`, `Voir le planning`, focus rapide |
| Coach | conversation noyée entre 6 panneaux techniques | conversation + proposition + `Contexte du coach` séparé |
| Planning | bouton primaire ambigu, aucun Demain | hero prochaine activité, Ajouter/Demander distincts, Aujourd'hui/Demain, options repliées |
| Connexion | URL, serverId, X25519, UUID visibles | état humain, `PC appairé`, identifiants dans `Avancé` |
| Launcher | raccourci Coach cassé quand ATLAS est HOME | `REORDER_TO_FRONT` + footer `Plan`, prochaine action actionnable |

## Preuves

- JVM : **720/720**, 102 suites, 0 échec.
- Lint et builds debug/androidTest : **PASS**.
- AVD API 37 : **50 tests ciblés PASS** (33 + 16 + 1 police 2.0), Plan **9/9** après correctif Demain.
- Launcher AVD : footer `Plan` → `Planning`, footer `Coach` → `Coach` ; **PASS**.
- Pixel : **PASS ciblé** le 2026-10-29 (Pixel 9 Pro XL, Android 17/API 37) — installation `adb install -r` de l'APK `605b9454…`, données préservées, parcours launcher/Accueil/Plan/Demain/Coach/Synthèses/Connexion/hors ligne. Détails et limites ci-dessous.
- Captures : `docs/captures/v6-3-r4/avant/` et `apres/`.

## Identité APK

- APK debug SHA-256 `c3c0f2ff0fa5bd7eb14d94231dfd71b70a432f92cd5bfd9123e102b3dab1f160`.
- Signature debug SHA-256 `da3014662eb65e50a87ef364a2e583c331398795b222e3b37b1a16553a00eeee`.

## Limites

- La validation Pixel est **ciblée** : elle couvre l'installation non destructive et les parcours listés ci-dessous, pas l'ensemble des scénarios de permissions, notifications, localisation et mobilité.
- Rotation, TalkBack complet et police 2× sur Pixel non exécutés dans cette passe ; la police 2× et le paysage restent couverts par l'AVD API 37.
- Le backend relayé réel n'a pas été rejoué ; les états locaux et les tests existants couvrent le mode local. Le mode hors ligne a été validé via le mode `Hors ligne` de l'application, puis le mode `Relais` a été restauré.
- Revue indépendante finale exécutée sur le code R5 : **GO_WITH_LIMITATIONS**, aucun P0/P1.
- **Push final** effectué sur `main` après validation Pixel et revue indépendante (voir le registre R5 pour le SHA).

## Validation Pixel du 2026-10-29

- Appareil : Pixel 9 Pro XL (`komodo`), Android 17 / API 37, rôle HOME toujours `dev.atlas.coach/.app.LauncherActivity` (aucun changement).
- APK installé : SHA-256 `605b9454ee77b1fe2fdef5ef71add41d5aa61055162b29e6cfdabd19a9e0bc07`, signature debug `da301466…`, version 0.1.0.
- Installation : `adb install -r` uniquement ; aucun `pm clear`, aucune désinstallation, aucun reset.
- Intégrité des données : sauvegarde app-only `~/atlas-backups/r5/pixel-app-backup-pre-r5.tar` (SHA-256 `8cfed885…`), manifeste pré/post hors dépôt. Tous les fichiers ATLAS (`atlas_local_state.xml`, plans, consentements, relais, etc.) sont **identiques** après installation ; seuls `files/profileInstalled` (marqueur d'installation) et `no_backup/androidx.work.workdb-shm` (mémoire partagée SQLite transitoire) ont changé.
- Parcours : launcher HOME → Plan et Coach ; Accueil ; Plan Aujourd'hui et Demain ; Coach avec actions rapides ; Synthèses avec `Données insuffisantes` et raison ; Connexion relayée avec état humain, PC appairé et assurance de chiffrement ; mode `Hors ligne` avec Accueil/Plan disponibles, puis restauration du mode `Relais` (`Connecté`).
- Pause d'urgence : visible sur les écrans parcourus ; non activée.
- Captures expurgées : `docs/captures/v6-3-r4/apres-pixel/` (icônes de notification et UUID de test masqués ; aucune capture du tiroir d'applications).

## Suite

Le propriétaire demande un second chantier : **réduction textuelle**, **page Insights Coach (jour/semaine/mois)**, **actions Coach simplifiées** (journée, objectif avec contraintes, focus pré-rempli) et **jauge de temps Focus**. Voir `HANDOFF_R4_NEXT.md`.
