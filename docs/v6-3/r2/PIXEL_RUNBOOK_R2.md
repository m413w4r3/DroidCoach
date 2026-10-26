# ATLAS V6.3 — Runbook Pixel pour la suite de R2

**Aucune action de ce runbook n'a été exécutée pendant R2.** Le Pixel physique
était indisponible et reste hors périmètre. Ce document prépare uniquement les
vérifications qui ne peuvent pas être closes sur `emulator-5554`.

## 0. Préconditions et autorisations

- Accord explicite du propriétaire par SHA d'APK avant toute installation.
- Appareil Pixel Android 17/API 37, déverrouillé, sauvegardé.
- `PIXEL_SERIAL` défini localement, jamais recopié dans un document.
- Une sauvegarde app-only et une sauvegarde du calendrier ciblé sont réalisées
  avant les tests d'écriture.
- Aucun `pm clear`, aucune désinstallation, aucun factory reset.

## 1. Sauvegarde et installation

```bash
export PIXEL_SERIAL="$(adb devices | awk 'NR==2 {print $1}')"
adb -s "$PIXEL_SERIAL" shell run-as dev.atlas.coach tar -cf - shared_prefs files \
  > "$HOME/.local/share/atlas-qa/r2/app-data-before-pixel.tar"
chmod 600 "$HOME/.local/share/atlas-qa/r2/app-data-before-pixel.tar"
sha256sum "$HOME/.local/share/atlas-qa/r2/app-data-before-pixel.tar"
adb -s "$PIXEL_SERIAL" install -r "$APK_R2"
adb -s "$PIXEL_SERIAL" shell dumpsys package dev.atlas.coach | grep -E 'versionName|versionCode|lastUpdateTime'
```

Vérifier que le SHA-256 de l'APK installé correspond à l'APK de recette annoncé.

## 2. Migrations et données existantes

- Ouvrir ATLAS, vérifier le plan local, les habitudes, les réglages de thème et
  d'accent, le mandat et la configuration Sync existants.
- Vérifier qu'aucune donnée n'est perdue ni dupliquée.
- Vérifier que l'ancien thème et l'absence d'accent restent lisibles (défaut
  `ATLAS classique`).
- Fermer/rouvrir l'application et vérifier la persistance.

## 3. Calendrier local et permissions

1. Ouvrir **Plus > Diagnostic calendrier**.
2. Donner le consentement ATLAS, puis accorder `READ_CALENDAR` par le dialogue
   Android.
3. Vérifier la liste expurgée : aucun nom de compte/e-mail, aucun événement.
4. Identifier un calendrier **local** inscriptible (compte `LOCAL`). Ne jamais
   choisir un calendrier Google pour ce test.
5. Accorder `WRITE_CALENDAR` uniquement si l'APK de recette le déclare et si le
   propriétaire a donné l'accord ciblé.
6. Sauvegarder le calendrier ciblé avant toute écriture.

### Test d'écriture synthétique

- Créer une activité ATLAS avec un titre synthétique et un horaire de test.
- Créer l'événement, relire le provider, vérifier le marqueur
  `CUSTOM_APP_PACKAGE`/`CUSTOM_APP_URI`.
- Déplacer, relire, vérifier l'absence de doublon.
- Supprimer, relire, vérifier l'absence.
- Vérifier dans Etar (`ws.xsoh.etar` ou `.debug`) que l'événement de test est
  visible avant suppression.
- Révoguer `WRITE_CALENDAR` et vérifier l'arrêt fail-closed.
- Restaurer le calendrier et l'état applicatif.

## 4. wger réel

- Ne pas consommer le refresh token rotatif.
- Configurer le mode `api_token` en lecture seule.
- Vérifier l'écran Sport : séance du jour, exercices, séries/répétitions,
  dernières performances, état de synchronisation.
- Vérifier qu'une séance prévue n'est jamais marquée effectuée sans
  `WorkoutResult` ou confirmation utilisateur.
- Toute écriture wger exige un nouveau mandat, une confirmation et une
  relecture API ; hors R2.

## 5. UsageStats réel

- Accorder Usage Access et le consentement global ATLAS.
- Comparer les durées observées à l'usage réel sur une fenêtre courte.
- Vérifier la révocation, la purge du curseur et l'état permission refusée.
- Ne pas revendiquer une précision à la milliseconde : documenter la fiabilité.

## 6. Accessibilité réelle

- TalkBack : Plan du jour, Mes habitudes, détail, éditeur, dialogues de
  confirmation.
- Vérifier les libellés des validations, des cases d'historique, du menu
  « Plus » et des actions destructrices.
- Vérifier la police 2× et la rotation sur l'appareil.
- Vérifier que le statut reste compréhensible sans couleur.

## 7. Retour arrière

```bash
adb -s "$PIXEL_SERIAL" install -r "$APK_ROLLBACK_R2"
```

- Restaurer les données app-only depuis la sauvegarde.
- Restaurer le calendrier ciblé depuis sa sauvegarde.
- Révoquer les permissions accordées.
- Vérifier que le plan, les habitudes et les réglages d'origine sont lisibles.
- Consigner l'état final et les éventuelles limites.

## 8. Ce qui reste explicitement non validé après R2

- `WRITE_CALENDAR` réel et visibilité Etar sur Pixel.
- Écriture wger réelle.
- Activation du plugin OpenClaw (reste `BLOCKED_POLICY`).
- Mesures UsageStats sur matériel réel.
- TalkBack matériel et accessibilité finale.
- Relay/LTE et transport Noise Android.
