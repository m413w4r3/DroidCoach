# R3-A evidence provenance

Le code final de la campagne est `d89a626a3b879b9ddce7486ec8f1b76b61845a37`,
installé sous l'APK `fb191804571595a478f89e007f6c30cc4d6d83373b2e8005baa6fdd61dbb4d90`.
Les preuves historiques produits pendant les vagues antérieures conservent leur
propre commit/APK ; elles ne servent pas à revendiquer un PASS sur l'APK final.

| Fichier | Commit source | APK | Portée | Statut |
|---|---|---|---|---|
| `preflight.txt` | base `4f7b926` | n/a | environnement et contrats | historique |
| `calendar-provider-events.txt` | non enregistré (dump précoce) | non enregistré | dump provider brut | historique |
| `calendar-local.txt` | `f0b4cb9` | `3be84fd` | calendrier local + visibilité Etar | historique |
| `calendar-etar-lifecycle.txt` | `531f51d` | non enregistré | lifecycle provider/Etar | historique |
| `calendar-engine-instrumented.txt` | `fa4d290` | `983d47a` | moteur durable + permissions | historique ; rejoué sur l'APK final |
| `calendar-divergence-etar.txt` | `fa4d290` | non enregistré | divergence après édition Etar | historique ; chemin couvert par tests JVM |
| `calendar-final-instrumented.txt` | `eaad907` | `af5ac60` | instrumentation pré-Wave-C | historique |
| `calendar-ui-flow.txt` | `eaad907` | non enregistré | parcours UI création/déplacement/suppression | historique ; remplacé par `calendar-ui-final.txt` |
| `e2e-emulator.txt` | `fa4d290` | `e7dd637` | E2E Android relayé, 5 phases | historique ; rejoué sur l'APK final |
| `sport-ui.txt` | `4d6a875` | non enregistré | UI sport indisponible + démo | historique ; code et tests rejoués sur `d89a626` |
| `relay-build.txt` | `d89a626` | n/a | image relais et santé loopback | **final** |
| `relay-tests.txt` | `d89a626` | n/a | Node 29 + module relay Android 31 | **final** |
| `pc-live-fastapi.txt` | `d89a626` | n/a | Node → relais → connecteur → FastAPI | **final** |
| `gates-final.txt` | `d89a626` | `fb19180` | backend, JVM, lint, builds, Node, Bridge | **final** |
| `instrumented.txt` | `d89a626` | `fb19180` | 17 R2 + 4 calendrier + E2E relais + offline + app controller + lifecycle + authenticator | **final** |
| `calendar-lifecycle-final.txt` | `d89a626` | `fb19180` | restore/move/delete/restore | **final** |
| `calendar-ui-final.txt` | `d89a626` | `fb19180` | parcours UI v2→v3→v4 + idempotence | **final** |
| `memory-ui.txt` | `d89a626` | `fb19180` | parcours mémoire + persistance après force-stop | **final** |
| `usage-global-ui.txt` | `d89a626` | `fb19180` | Usage Access réel, quota persisté, révocation | **final** |

## Règles de lecture

- Un PASS final exige un fichier marqué **final** ci-dessus, avec le SHA, l'APK et
  la commande.
- Les preuves historiques documentent la progression de campagne et les captures
  d'interface ; elles ne remplacent pas un rejeu final.
- Les captures de `captures/` sont synthétiques ; aucune donnée personnelle,
  aucun secret et aucun identifiant matériel n'y figure.
- `android_e2e=NOT_RUN` dans `pc-live-fastapi.txt` est volontaire : ce contrôle
  est Node-to-Node. L'E2E Android est couvert par `instrumented.txt`.
