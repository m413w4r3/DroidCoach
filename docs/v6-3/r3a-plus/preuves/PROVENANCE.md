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
| PENDING | PENDING | PENDING | PENDING | PENDING |

## Règles

- Aucun token, secret, identifiant matériel, compte personnel ou donnée intime.
- Les captures ne montrent que des fixtures synthétiques `ATLAS TEST`.
- Une preuve JVM n'est pas une preuve instrumentée.
- Une preuve Pixel n'est pas une preuve Internet réelle.
