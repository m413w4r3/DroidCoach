---
name: android17-device-qa
description: "Qualifier ATLAS sur Android 17/API 37 en priorité sur Pixel physique ; sécuriser ADB, sauvegardes, permissions et preuves."
---

# Recette Android 17 sur téléphone personnel

**Appareil physique prioritaire.** Un AVD API 37 est secondaire, pas requis si son hôte/surfaceflinger est instable. Android 14/API 34 est hors cible et hors gate de sortie actuel. Conserver les tests JVM/backend/lint.

## 1. Pré-vol non mutateur

- Un seul agent propriétaire de l'ADB ; aucune autre sous-session instrumentée ou script d'AVD parallèle.
- Vérifier les outils, `adb devices -l`, état `device` d'un seul appareil choisi sans enregistrer publiquement son identifiant.
- Lire `ro.build.version.sdk`, `sdk_full`, marque/modèle, version ATLAS, signature APK, état HOME, permissions, états notifications et stockage libre.
- Vérifier les scripts et les tests d'instrumentation **avant** leur lancement : `pm clear`, nettoyages automatiques, changements de rôle, grants/revokes, données d'essai, effets réseau, restauration.
- L'utilisateur autorise l'essai sur son téléphone, **pas** l'effacement ou la modification illimitée de ses réglages.

## 2. Sauvegarde / consentements

- Produire une sauvegarde **app uniquement**, sans supposer qu'ADB peut accéder aux fichiers privés en mode release. Si aucune sauvegarde complète n'est possible, signaler `BLOCKED` pour les tests risquant les données.
- Conserver archive et SHA-256 hors repo, **hors /tmp seul** ; vérifier archive restaurable sans la réinjecter en production.
- Installer un APK de test seulement si identité de package, certificat et compatibilité de migration sont vérifiés ; ne pas utiliser `-d` sans justification et accord.
- Toute modification de rôle HOME, rotation globale, taille de police, permission sensible, service de mobilité ou connexion à un serveur externe nécessite un feu vert spécifique à cette étape. Noter l'état avant et rétablir après.
- Interdits sans autorisation distincte : `adb uninstall`, `pm clear`, factory reset, root, édition directe de `shared_prefs`, restauration globale de base active, effacement de fichiers WorkManager, modification d'applications tierces.

## 3. Parcours fonctionnels obligatoires

1. Installation/mise à jour non destructive, démarrage à froid et permissions initiales.
2. HOME : sélection explicite, favoris, recherche, ouverture d'apps essentielles, retour à Pixel Launcher.
3. Focus : libre et lié à objectif, pause, fin, annulation, progression unique après reprise.
4. Coach : contexte consenti, proposition approuvée, état du mandat, refus/révocation pendant requête ; aucune action non autorisée.
5. Notifications : autorisation/refus, retard, action de notification, révocation et nettoyage.
6. Localisation : refus, approximatif, timeout/fournisseur indisponible, consentement séparé OpenClaw.
7. Mobilité : démarrage **visible et explicite**, notification Arrêter, aucune trajectoire brute ni relance silencieuse.
8. Rotation / 320–360 dp lorsque pertinent / police 2× / TalkBack selon disponibilité.
9. Réseau coupé : HOME, Pause d'urgence, Focus et objectifs utilisables.
10. Évaluation de l'UI ivoire/noir avec l'utilisateur : vrais parcours, pas uniquement PNG.

## 4. Preuves et sortie

Chaque scénario : `PASS`, `FAIL`, `BLOCKED` ou `NOT_RUN`, SHA APK/source, horodatage, appareil/API, preuve expurgée et état restauré.
- Différencier test automatisé réel, vérification manuelle et simulation FakeClock.
- Si le Pixel se débranche : arrêter les actions, marquer les étapes restantes `NOT_RUN` et consigner le dernier état lu ; ne pas deviner la restauration.
- Ne pas fermer l'issue #1 sur la seule CI JVM. Les critères de l'issue exigent une preuve compatible avec leur portée Android 17 ; documenter les écarts AVD/physique.
- Contrôler l'état utilisateur final et supprimer **uniquement** les propres fixtures de QA avec autorisation.

La cible est la fiabilité du téléphone, pas l'obtention d'un `PASS` artificiel à tout prix.
