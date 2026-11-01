# V7-06A VM-1 — BLOCKED_ENVIRONMENT

## Résultat

**BLOCKED_ENVIRONMENT.** VM-1 n’a pas commencé. Ce résultat ne constitue ni un PASS ni un FAIL du correctif du connecteur : l’AVD n’a pas atteint l’état de boot requis avant toute installation ou instrumentation.

Le SHA testé pour compilation est `be31d097fe0cf6a5bc50688b421bc9eecbbc847e`.

| Artefact | Métadonnées | SHA-256 |
|---|---|---|
| APK app `dev.atlas.coach` | version `0.1.0` (code `1`) | `59c29bba0f54188a9eea058ecb9809bf2515622d50534d6df6105a7d7a4fb949` |
| APK AndroidTest `dev.atlas.coach.test` | signé avec le même certificat debug | `df112be47385cae6a6745a169f8635ebb7acc62d758b9a375fc662051494c888` |

Compile SDK : 37. Target SDK : 36. Certificat debug SHA-256 : `da3014662eb65e50a87ef364a2e583c331398795b222e3b37b1a16553a00eeee`. Émulateur : 37.2.12.0, AVD `atlas_v7_06a_api37`, API 37 / Android 17, série `emulator-5580`.

Le build APK a réussi depuis `android/` avec :

```text
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --offline --rerun-tasks
BUILD SUCCESSFUL — 67 tâches exécutées
```

Le build du connecteur `npm run build` a également réussi. La suite Node **109/109** a été exécutée par le parent sur le SHA intégré `be31d09` ; l’agent VM ne l’a pas relancée.

## Historique des boots

Le pré-vol [V7-06A_AVD_BOOT.md](V7-06A_AVD_BOOT.md) atteste qu’un boot API 37.2 de cet AVD avait réussi le **2026-10-31** : `sys.boot_completed=1`, SDK 37, API complète 37.2 et nom AVD vérifiés. L’échec décrit ci-dessous est environnemental et transitoire ; il ne prouve pas un défaut du correctif.

1. **Cold boot initial**, commande `emulator -avd atlas_v7_06a_api37 -port 5580 -no-snapshot-load -no-audio`. Après plus de six minutes, le boot restait incomplet et le CPU QEMU était élevé. `sys.boot_completed` est resté vide. Les services `activity` et `package` étaient d’abord absents, puis visibles par intermittence. Les contrôles système filtrés n’ont fourni aucune ligne d’erreur. L’AVD a été arrêté avec `adb -s emulator-5580 emu kill`.
2. **Redémarrage sans animation**, commande `emulator -avd atlas_v7_06a_api37 -port 5580 -no-snapshot-load -no-audio -no-boot-anim`. L’attente bornée de 300 secondes a fait 60 contrôles ciblés. `sys.boot_completed` n’a jamais valu `1`. `activity` et `package` sont devenus disponibles, mais le contrôle final les a trouvés absents. Les logs `BootReceiver`, `SystemServer`, `AndroidRuntime`, `PackageManager` et `ActivityManager` filtrés étaient vides. L’AVD a été arrêté proprement.
3. **Unique remédiation autorisée**, après arrêt, commande `emulator -avd atlas_v7_06a_api37 -port 5580 -wipe-data -no-snapshot-load -no-audio -no-boot-anim`. Cette remise à zéro n’a concerné que l’userdata de cet AVD. Après 300 secondes et 60 contrôles, `sys.boot_completed` n’a jamais valu `1`. `activity` et `package` ont fluctué entre absent et trouvé, puis étaient absents au contrôle final. Les mêmes logs système filtrés étaient vides. L’AVD a ensuite été arrêté proprement.

Aucune tentative de boot supplémentaire n’a été faite. L’AVD a été vérifié arrêté.

## Périmètre non exécuté

- Aucun APK installé et aucun appairage poussé sur l’émulateur.
- Aucune instrumentation, aucun test VM-1, aucun oracle Core ni aucune collecte de trace Android/connecteur de qualification.
- Aucun VM-2.
- Aucun état app n’a été créé sur l’AVD ; les suites de qualification n’ont donc donné ni PASS ni FAIL.

Le gate **GO_VM_V4_E2E n’est pas accordé**. Seules les suites déterministes JVM, Node et backend peuvent fournir une preuve pour ce gate ; cette exécution bloquée n’en ajoute aucune.

## Arrêt et nettoyage de la stack isolée

`python3 scripts/v7-06a/stop_vm1_stack.py` a terminé avec :

```text
PASS: connector=stopped_sigint relay=stopped_removed core=stopped_sigint; database and local state preserved
```

La vérification des PID consignés a retourné `owned_processes_remaining=none`. `docker ps -a --filter name=atlas-v7-06a-relay` n’a retourné aucun conteneur. Aucun service live n’a été touché ; la stack utilisait uniquement Core loopback `127.0.0.1:8110`, relay `127.0.0.1:4412` et connecteur `127.0.0.1:4411`.

`cleanup_vm1_tokens.py` a confirmé la suppression des trois fichiers synthétiques `core-token`, `tool-token` et `pairing.json`, sans mismatch d’inode. Une commande de nettoyage distincte, bornée aux chemins de cette stack, a ensuite supprimé les logs privés, la configuration/manifest privés, `core.sqlite3`, sa sauvegarde de migration et le répertoire d’état `connector/`. Vérification finale : répertoire privé absent, fichiers token/pairing absents, processus isolés absents et conteneur relay absent.
