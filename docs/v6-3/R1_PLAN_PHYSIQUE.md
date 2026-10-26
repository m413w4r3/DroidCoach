# R1 — Plan du premier parcours physique Pixel

Statut : **PRÉPARATION** — aucune action sur le téléphone n'est autorisée par ce
document seul. Chaque porte demandée doit être approuvée explicitement.

## Objectif du gate

Une journée négociée avec le coach OpenClaw, approuvée dans ATLAS, persistée,
puis visible dans Etar sur le Pixel Android 17.

## Prérequis techniques

| Élément | État / décision |
|---|---|
| Backend ATLAS | `127.0.0.1:8010` (port 8000 occupé par un tiers) |
| Token backend | généré pour la session, stocké hors dépôt en `0600`, jamais affiché |
| OpenClaw | gateway `127.0.0.1:18789`, agent `atlas`, token fichier `0600` |
| Capability backend | `plan_revision=true` explicite dans `ATLAS_CAPABILITIES_JSON` |
| Capability Android | `planRevision` désactivée par défaut ; activée par action UI explicite |
| Réseau Android | `adb reverse tcp:8010 tcp:8010` ; client debug vers `http://127.0.0.1:8010` |
| APK | debug signé avec la même clé que l'APK installé ; mise à jour en place possible |
| Calendar | `READ_CALENDAR` seulement après accord ; `WRITE_CALENDAR` dans un lot séparé |
| Etar | `ws.xsoh.etar` installé |

## Séquence

1. **Pré-vol lecture seule** (déjà fait) : appareil, packages, signature, HOME,
   stockage. Ne rien modifier.
2. **Backend** : lancer sur 8010 avec DB temporaire, adapter OpenClaw, token
   aléatoire, capability `plan_revision=true`.
3. **ADB reverse** : `adb reverse tcp:8010 tcp:8010`, vérifier avec
   `adb reverse --list`.
4. **APK** : construire le debug, vérifier le SHA-256, demander l'accord
   d'installation, installer en mise à jour (pas de désinstallation).
5. **Configuration app** : URL `http://127.0.0.1:8010`, Bearer saisi par le
   propriétaire ou injecté par un canal local qui ne journalise pas le secret.
6. **Parcours plan local** : créer le programme du jour, ajouter deux activités,
   vérifier l'aperçu et la confirmation groupée.
7. **Parcours coach** : activer explicitement l'envoi du plan au coach local,
   demander une révision, vérifier le diff avant/après, confirmer.
8. **Persistance** : fermer l'application, la rouvrir, vérifier le plan approuvé
   et la version.
9. **Hors ligne** : couper le backend, vérifier que le plan reste lisible et que
   les actions locales restent disponibles.
10. **Calendrier** : après accord séparé `READ_CALENDAR`, lire les calendriers,
    afficher Etar installé ; l'écriture réelle est un lot ultérieur.
11. **Etar** : après accord `WRITE_CALENDAR` et sauvegarde, créer un événement
    synthétique marqué ATLAS, le voir dans Etar, le déplacer, le supprimer, puis
    vérifier l'absence de doublon.

## Portes d'approbation (à demander au moment utile)

| Porte | Action | Données / risque | Sauvegarde | Retour arrière |
|---|---|---|---|---|
| P1 | Installer l'APK debug | remplace l'APK ATLAS installé ; données app conservées | export app-only si possible ; `allowBackup=false` | réinstaller l'APK précédent (SHA conservé) |
| P2 | Configurer URL/token dans l'app | token local, non affiché | aucun secret journalisé | effacer la configuration |
| P3 | Ajouter `READ_CALENDAR` + octroi | lecture des calendriers | aucune écriture | révoquer la permission |
| P4 | Écrire un événement de test | calendrier réel | export/sauvegarde du calendrier ciblé | supprimer uniquement l'événement marqué ATLAS |

## Preuves attendues

Chaque étape : SHA source et APK, date, appareil/API, commande ou geste,
`PASS/FAIL/BLOCKED/NOT_RUN`, capture expurgée, état final. Aucun `PASS` sans
exécution réelle. Une notification d'agent ou un test JVM ne remplace pas un
test physique.

## Limites connues avant exécution

- Aucun calendrier local inscriptible n'est encore qualifié ; L4 peut être
  `BLOCKED_ENVIRONMENT`.
- La recherche Bridge est `prompt_instructed` non vérifiée ; elle ne prouve pas
  une fraîcheur réelle.
- Le HOME du Pixel est NexusLauncher ; ATLAS n'est pas promu HOME dans R1.
- L'APK installé est `targetSdk 36` ; la cible Android 17/API 37 est qualifiée
  par l'appareil, pas par le manifeste.
