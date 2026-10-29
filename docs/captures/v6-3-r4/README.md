# Captures ATLAS V6.3 — R4

## Avant

- Appareil : Pixel 9 Pro XL physique, Android 17 / API 37.
- Code : `main` au SHA `566668ac9cd0778f43d7f4d90a93c6a45645935c`.
- Date : 2026-10-28.
- Expurgation : bulle de conversation Signal masquée ; UUID de test du Coach masqué. Aucun nom d'application personnelle, aucun jeton, aucun `serverId`.

## Après

- Appareil : AVD API 37 (`atlas_v62_disposable_api37`), 360 dp, environnement jetable.
- Code : branche `feat/atlas-v6-3-ux-daily-use`, SHA `7d7b5f5`.
- Données : plan, conversation et propositions synthétiques ; aucun compte, aucun secret, aucune donnée personnelle.
- Le Pixel physique n'était plus connecté au moment de la capture finale. Les captures « après » sur Pixel restent à refaire après reconnexion ; aucune validation Pixel n'est revendiquée.

> **Mise à jour R5 (2026-10-29)** : le Pixel a été reconnecté et la validation
> ciblée a été effectuée avec l'APK final. Les captures Pixel « après » sont
> dans `apres-pixel/`. Les captures AVD des écrans R5 restent dans
> `docs/captures/v6-3-r5/`.

## Après — Pixel (2026-10-29)

- Appareil : Pixel 9 Pro XL, Android 17 / API 37.
- APK : SHA-256 `605b9454ee77b1fe2fdef5ef71add41d5aa61055162b29e6cfdabd19a9e0bc07`, signature debug `da301466…`.
- Installation : `adb install -r`, aucun `pm clear`/uninstall ; données ATLAS préservées (voir `RAPPORT_UX_V63.md`).
- Expurgation : icônes de notification de la barre d'état masquées ; UUID de test de conversation masqué. Aucun `serverId`, aucun jeton, aucune capture du tiroir d'applications.

| Fichier | Écran |
|---|---|
| `apres-pixel/15-pixel-launcher.png` | Launcher HOME ATLAS avec footer Apps/Plan/Coach/Pause/Plus |
| `apres-pixel/16-pixel-accueil.png` | Accueil ATLAS : prochaine activité, actions rapides, directive |
| `apres-pixel/17-pixel-plan-aujourdhui.png` | Planning Aujourd'hui : sélecteur, préparation, Détails |
| `apres-pixel/18-pixel-plan-demain.png` | Planning Demain : date distincte, préparation du lendemain |
| `apres-pixel/19-pixel-coach.png` | Coach : onglets Échanges/Synthèses, actions rapides, conversation |
| `apres-pixel/20-pixel-insights.png` | Synthèses Jour : `Données insuffisantes` et raison |
| `apres-pixel/21-pixel-connexion.png` | Connexion relayée : état, PC appairé, assurance E2E, Avancé replié |
| `apres-pixel/22-pixel-hors-ligne.png` | Mode hors ligne : coach distant désactivé, données locales disponibles |

## Fichiers

| Fichier | Écran |
|---|---|
| `apres/01-launcher.png` | Launcher HOME avec footer Apps/Plan/Coach/Pause/Plus et prochaine action actionnable |
| `apres/02-accueil.png` | Accueil Aujourd'hui : date/heure, prochaine activité, actions rapides |
| `apres/03-plan-aujourdhui.png` | Planning Aujourd'hui : hero, actions, options |
| `apres/04-plan-demain.png` | Planning Demain : activités du lendemain non marquées « passées » |
| `apres/05-coach.png` | Coach : statut humain, conversation, source locale |
| `apres/06-coach-proposition.png` | Proposition du coach avec confirmation explicite |
| `apres/07-contexte-du-coach.png` | Contexte du coach : objectifs, localisation, consentements |
| `apres/08-connexion.png` | Connexion relayée : état humain, détails techniques repliés |
| `apres/09-partage-backend.png` | Partage backend : statut et catégories |
| `apres/10-plus.png` | Plus : Habitudes et Connexion accessibles |
| `apres/11-focus.png` | Focus |
| `apres/12-habitudes.png` | Habitudes sans bouton d'ajout dupliqué |
| `apres/13-sport.png` | Sport : état humain, détails repliés |
| `apres/14-parametres.png` | Paramètres |
| `avant/*.png` | Écrans correspondants avant la passe |
