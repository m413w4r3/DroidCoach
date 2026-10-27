# R3-A+ evidence provenance

Chaque preuve finale doit porter : SHA source exact, SHA-256 APK si applicable,
environnement (`Pixel 9 Pro XL physique, Android 17 / API 37`), commande,
horodatage et statut. Les preuves R3-A historiques ne sont pas reclassées.

| Fichier | Commit source | APK | Portée | Statut |
|---|---|---|---|---|
| `preflight-pixel.txt` | `012b101` | installé `6ab95fac` | pré-vol, sauvegarde, baseline Pixel | **final (pré-vol)** |
| `backend-baseline.txt` | `012b101` | n/a | backend et routes connecteur | **final (baseline)** |
| `calendar-unit-tests.txt` | `e66d2ef` | n/a | JVM calendrier et compilation androidTest | **final (JVM)** |
| `calendar-androidtest-commands.txt` | `e66d2ef` | n/a | commandes Pixel C1–C5 | **à exécuter** |
| `calendar-merged-targeted.txt` | `0f3426a` (merge) | n/a | 64 tests JVM ciblés rejoués par le parent | **final (JVM)** |
| `jvm-merged-calendar.txt` | `fe54e20` | n/a | suite JVM complète 91/654, 0 échec | **final (JVM partiel)** |
| `wss-policy.txt` | `b2cbebc` | n/a | politique PC/Android et tests JVM | **final (Node/JVM)** |
| `wss-tls-harness.txt` | `b2cbebc` | n/a | harnais TLS/WSS de l'agent | **final (Node/TLS)** |
| `node-merged-wss.txt` | `733cd78` | n/a | 40 tests Node rejoués par le parent | **final (Node)** |
| `android-merged-wss.txt` | `733cd78` | n/a | 36 tests JVM relais + androidTest compilé | **final (JVM)** |
| `wss-harness-merged.txt` | `733cd78` | n/a | 6 contrôles WSS/TLS rejoués par le parent | **final (Node/TLS)** |
| `packaging-clean-install.txt` | `d8a425d` | n/a | installation propre sans `references/` | **final (PC)** |
| `packaging-artifact.txt` | `d8a425d` | n/a | provenance et hash du tarball Paseo | **final (PC)** |
| `packaging-systemd.txt` | `d8a425d` | n/a | vérification statique systemd | **PARTIAL** |
| `config-policy-integration.txt` | `722cdde` | n/a | correction parent WSS du config checker | **final (PC)** |
| `node-merged-packaging.txt` | `722cdde` | n/a | 10 fichiers / 40 tests Node avec tarball vendoré | **final (Node)** |
| PENDING | PENDING | PENDING | PENDING | PENDING |

## Règles

- Aucun token, secret, identifiant matériel, compte personnel ou donnée intime.
- Les captures ne montrent que des fixtures synthétiques `ATLAS TEST`.
- Une preuve JVM n'est pas une preuve instrumentée.
- Une preuve Pixel n'est pas une preuve Internet réelle.
