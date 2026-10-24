# Références OSS et provenance — clôture V6.2

## État de cette clôture

Code source évalué : `8890046012965f3a3112ff0c419014c74e189d11`. Aucun code ni asset tiers supplémentaire n’a été copié pour produire ces six documents. Les adaptations déjà présentes sont celles documentées dans [`docs/v6-2/REEMPLOI_OSS.md`](../../v6-2/REEMPLOI_OSS.md) et [`THIRD_PARTY_NOTICES.md`](../../../THIRD_PARTY_NOTICES.md); ces références n’ont pas été modifiées.

| Projet / source | SHA amont local | Licence consignée | Usage/adaptation ATLAS déjà documenté |
|---|---|---|---|
| Reef | `044358fb26bf11aa85dddadf919106ca4053d926` | MIT avec attribution additionnelle Reef | Adaptations historiques Focus, routines et présentation; lien amont dans À propos / Licences |
| Olauncher | `f8e8d6497c457734c7a3171d36374157678e1f33` | GNU GPL v3 | Modèle et HOME du launcher; aucune nouvelle source ou asset dans cette clôture |
| Loop Habit Tracker / uhabits-core | `c06b682f6b7b9c09eb278a06ffb57a42f0cddd38` | GNU GPL v3-or-later | Calculs et exports sous `habits/thirdparty/loop/` |
| Lawnchair | `fc100ae9095a83b8b51c447a3b0a4ce2b399ad59` | Apache-2.0 | Référence technique seulement; aucun fichier repris |
| OpenClaw officiel | `e3fe108378f6eca2d4e2a4333fd08bf81bb87105` | MIT, OpenClaw Foundation © 2026 | Adaptations de localisation : acquisition, coarsening et divulgation sous consentement ATLAS; aucun payload Gateway repris |
| AndyClaw | `fd4a9af917f51d7b1c10e09e6496f54a290b253b` | GNU GPL v3 | Politique de déclenchement adaptée en garde de propositions de coaching; sans service Android de notifications ni runtime AndyClaw |
| AOSP `LocationFudger.java` | `81f52b053da6` | Apache-2.0 | Approche offset + grille attribuée dans `LocationCoarsener.kt`; classe Java et dépendances Android non copiées |

Les chemins précis, modifications, limites et notices de licence sont tenus dans les deux documents de référence cités. En particulier, les références de localisation ne justifient pas l’import de coordonnées brutes, de modes de collecte arrière-plan, de runtime d’agent tiers ou de composants privilégiés.

## Contrôle de clôture

- Aucun nouveau clone, fork ou code tiers introduit par les six livrables.
- Aucune nouvelle licence ni notice nécessaire pour ces documents.
- Les textes de licence MIT, GPLv3 et Apache-2.0 restent dans `THIRD_PARTY_NOTICES.md`.
- La provenance de toute adaptation de code déjà livrée reste à vérifier sur ses fichiers et références existants lors de la revue indépendante; cette synthèse ne constitue pas un avis juridique.
