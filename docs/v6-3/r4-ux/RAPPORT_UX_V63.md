# ATLAS V6.3 — Rapport UX/UI R4

- **Date** : 2026-10-29
- **Branche** : `feat/atlas-v6-3-ux-daily-use`
- **HEAD** : `7d7b5f5`
- **Base `origin/main`** : `566668ac9cd0778f43d7f4d90a93c6a45645935c`
- **Statut** : code et tests AVD terminés ; **validation Pixel bloquée par la déconnexion USB** ; pas de push.

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
- Pixel : **NOT_RUN** (appareil déconnecté avant installation).
- Captures : `docs/captures/v6-3-r4/avant/` et `apres/`.

## Identité APK

- APK debug SHA-256 `c3c0f2ff0fa5bd7eb14d94231dfd71b70a432f92cd5bfd9123e102b3dab1f160`.
- Signature debug SHA-256 `da3014662eb65e50a87ef364a2e583c331398795b222e3b37b1a16553a00eeee`.

## Limites

- Aucune installation ni validation sur le Pixel physique dans cette passe.
- Rotation et TalkBack non exécutés sur le Pixel ; la police 2× et le paysage sont couverts sur AVD API 37.
- Le backend relayé réel n'a pas été rejoué ; les états locaux et les tests existants couvrent le mode local.
- Revue indépendante finale à exécuter après reconnexion Pixel.

## Suite

Le propriétaire demande un second chantier : **réduction textuelle**, **page Insights Coach (jour/semaine/mois)**, **actions Coach simplifiées** (journée, objectif avec contraintes, focus pré-rempli) et **jauge de temps Focus**. Voir `HANDOFF_R4_NEXT.md`.
