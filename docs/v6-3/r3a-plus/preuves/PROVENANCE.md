# R3-A+ evidence provenance

Chaque preuve finale doit porter : SHA source exact, SHA-256 APK si applicable,
environnement (`Pixel 9 Pro XL physique, Android 17 / API 37`), commande,
horodatage et statut. Les preuves R3-A historiques ne sont pas reclassées.

| Fichier | Commit source | APK | Portée | Statut |
|---|---|---|---|---|
| `preflight-pixel.txt` | `012b101` | installé `6ab95fac` | pré-vol, sauvegarde, baseline Pixel | **final (pré-vol)** |
| `backend-baseline.txt` | `012b101` | n/a | backend et routes connecteur | **final (baseline)** |
| PENDING | PENDING | PENDING | PENDING | PENDING |

## Règles

- Aucun token, secret, identifiant matériel, compte personnel ou donnée intime.
- Les captures ne montrent que des fixtures synthétiques `ATLAS TEST`.
- Une preuve JVM n'est pas une preuve instrumentée.
- Une preuve Pixel n'est pas une preuve Internet réelle.
