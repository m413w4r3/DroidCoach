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

> **Mise à jour R5 (2026-10-29)** : le Pixel est toujours absent. Les nouvelles
> captures des écrans Coach/Synthèses/Focus sont dans
> `docs/captures/v6-3-r5/` (AVD API 37, données synthétiques). Aucune capture
> Pixel « après » n'a été produite et R4 Pixel reste `BLOCKED_DEVICE`.

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
