# Vérification rapide Android Studio et émulateur (Linux)

> Android Studio est installé sous `/opt/android-studio/bin`. Cette installation **n’indique pas** le chemin réel du SDK. Dans Android Studio, aller dans **Settings > Languages & Frameworks > Android SDK** ou **Tools > SDK Manager** pour vérifier le chemin, les plateformes Android, Android Emulator et Android SDK Platform-Tools.

## Préparer une VM Android (AVD)
1. Lancer Android Studio avec `/opt/android-studio/bin/studio.sh`.
2. Dans **Device Manager**, créer un téléphone virtuel de profil Pixel et une image Android **stable** compatible avec le projet ; démarrer cet AVD.
3. Sur Linux, contrôler KVM et les outils :

```bash
ls -l /dev/kvm
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
"$SDK/emulator/emulator" -accel-check
"$SDK/emulator/emulator" -list-avds
"$SDK/platform-tools/adb" devices -l
```

Les commandes supposent que le SDK existe à l'emplacement choisi. En cas d’erreur « fichier introuvable », retrouver son vrai chemin via le SDK Manager. En cas d’erreur KVM, diagnostiquer la prise en charge de la virtualisation, les groupes et les permissions avant de modifier le système.

## Résultat VM-1 attendu de l’agent de code
- `APK debug` construit sans erreur avec le SDK réel.
- Application installée et ouverte sur l'AVD.
- Écran d'accueil inspiré visuellement d'Olauncher (sans copie de son code), directive de démo, Pomodoro, habitudes, règle de friction simulée, liens vidéo avec fallback.
- `docs/VALIDATION_VM.md` avec commandes, version d'AVD, captures, journal des tests.
- Hors de ce périmètre : validation des restrictions sur le Pixel réel, fonctionnement complet ReVanced, IA OpenClaw effective et précision de l'observation UsageStats sur le téléphone physique.

Sources officielles :
- https://developer.android.com/studio/run/emulator-commandline
- https://developer.android.com/studio/run/emulator-acceleration
