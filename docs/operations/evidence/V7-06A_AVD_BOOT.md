# V7-06A — Pré-vol AVD API 37.2 (boot vérifié)

Date : 2026-10-31
Hôte : Linux 6.8, KVM présent (`/dev/kvm`), 62 Gio RAM, 67 Gio libres.
AVD créé : `atlas_v7_06a_api37` (profil Nexus 5, `google_apis_ps16k`, x86_64,
partition data 6 442 450 944 octets).
Image : `system-images/android-37.2/google_apis_ps16k/x86_64/` (présente).
Série utilisée : **`emulator-5580`** uniquement. Aucun `adb -d`, aucun Pixel.

## Commandes et résultats

```sh
emulator -avd atlas_v7_06a_api37 -no-window -no-audio -no-boot-anim \
  -no-snapshot -gpu swiftshader_indirect -port 5580
adb -s emulator-5580 wait-for-device      # device visible
adb -s emulator-5580 shell getprop sys.boot_completed   # 1
adb -s emulator-5580 shell getprop ro.build.version.sdk # 37
adb -s emulator-5580 shell getprop ro.build.version.sdk_full # 37.2
adb -s emulator-5580 shell getprop ro.boot.qemu.avd_name # atlas_v7_06a_api37
adb -s emulator-5580 emu kill             # arrêt propre
```

## Conclusion

- Le boot AVD API 37.2 est **PASS** ; l’émulateur a été arrêté après vérification.
- Aucune conclusion applicative n’est tirée de ce boot : APK, appairage et tests
  restent à exécuter dans V7-06A-VM après intégration du correctif.
- Limite : x86_64 émulé, pas arm64/Pixel ; aucun réseau cellulaire réel.
