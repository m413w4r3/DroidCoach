# R3-A evidence provenance

Toute preuve finale est rattachée au SHA de source `ad42b1c515074e563699c599ce2f4ca52f6bd58c`
et à l'APK `e8937c62f9096fbb01d5667a4eacba213140368119b32292d3bb2804355caebd`.
Les fichiers historiques produits pendant les vagues antérieures conservent leur
propre commit/APK ; ils ne servent pas à revendiquer un PASS sur l'APK final.

| Fichier | Commit source | APK | Portée | Statut final |
|---|---|---|---|---|
| `preflight.txt` | base `4f7b926` | n/a | environnement et contrats | historique |
| `calendar-provider-events.txt` | non enregistré (dump précoce) | non enregistré | dump provider brut | historique |
| `calendar-local.txt` | `f0b4cb9` | `3be84fd` | calendrier local + visibilité Etar | historique ; rejoué dans `instrumented.txt` |
| `calendar-etar-lifecycle.txt` | `531f51d` | non enregistré | lifecycle provider/Etar | historique ; rejoué dans `calendar-lifecycle-final.txt` |
| `calendar-engine-instrumented.txt` | `fa4d290` | `983d47a` | moteur durable + permissions | historique ; rejoué dans `instrumented.txt` |
| `calendar-divergence-etar.txt` | `fa4d290` | non enregistré | divergence après édition Etar | historique ; chemin couvert par tests JVM sur `ad42b1c` |
| `calendar-final-instrumented.txt` | `eaad907` | `af5ac60` | instrumentation pré-Wave-C | historique ; rejoué dans `instrumented.txt` |
| `calendar-ui-flow.txt` | `eaad907` | non enregistré | parcours UI création/déplacement/suppression | historique ; captures antérieures aux correctifs `bfbb9db` |
| `e2e-emulator.txt` | `fa4d290` | `e7dd637` | E2E Android relayé, 5 phases | historique ; rejoué dans `instrumented.txt` |
| `sport-ui.txt` | `4d6a875` | non enregistré | UI sport indisponible + démo | historique ; tests backend/JVM rejoués sur `ad42b1c` |
| `pc-live-fastapi.txt` | `ad42b1c` | n/a | Node → relais → connecteur → FastAPI | **final** |
| `instrumented.txt` | `ad42b1c` | `e8937c62` | 17 R2 + 4 calendrier + E2E relais + app controller | **final** |
| `calendar-lifecycle-final.txt` | `ad42b1c` | `e8937c62` | restore/move/delete/restore | **final** |
| `memory-ui.txt` | `ad42b1c` | `e8937c62` | parcours mémoire + persistance après force-stop | **final** |
| `usage-global-ui.txt` | `ad42b1c` | `e8937c62` | Usage Access réel + consentement + révocation | **final** |

## Règles de lecture

- Un PASS final exige un fichier marqué **final** ci-dessus, avec le SHA, l'APK et
  la commande.
- Les preuves historiques documentent la progression de campagne et les captures
  d'interface ; elles ne remplacent pas un rejeu final.
- Les captures de `captures/` sont synthétiques ; aucune donnée personnelle,
  aucun secret et aucun identifiant matériel n'y figure.
- `android_e2e=NOT_RUN` dans `pc-live-fastapi.txt` est volontaire : ce contrôle
  est Node-to-Node. L'E2E Android est couvert par `instrumented.txt`.
