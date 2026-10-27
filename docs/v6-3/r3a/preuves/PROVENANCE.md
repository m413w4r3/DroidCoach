# R3-A evidence provenance

Le code final de la campagne est `02a5040283d087c4d401fd0f6e8f95f9cda752c3`,
installé sous l'APK `8ad8d2d53de6794894219b13ca62641c59e96ffd4966f52c69309f2f4cfb4c8c`.
Le commit `3811ea5` portait les correctifs Android et connecteur ; `c45b64f` et
`02a5040` ne modifient que le connecteur Node, ses tests et sa documentation.
L'APK Android reconstruit est byte-identique (`8ad8d2d…`), donc les preuves
instrumentées et UI restent valides pour le code Android final. Les preuves historiques produits
pendant les vagues antérieures conservent leur propre commit/APK ; elles ne
servent pas à revendiquer un PASS sur l'APK final.

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
| `sport-ui.txt` | `4d6a875` | non enregistré | UI sport indisponible + démo | historique ; code et tests rejoués sur `02a5040` |
| `relay-build.txt` | `02a5040` | n/a | image relais et santé loopback | **final** |
| `relay-tests.txt` | `02a5040` | n/a | Node 35 + module relay Android 31 | **final** |
| `pc-live-fastapi.txt` | `02a5040` | n/a | Node → relais → connecteur → FastAPI | **final** |
| `gates-final.txt` | `02a5040` | `8ad8d2d` | backend, JVM, lint, builds, Node, Bridge | **final** |
| `instrumented.txt` | `02a5040` | `8ad8d2d` | 17 R2 + 4 calendrier + E2E relais + offline + app controller + lifecycle + authenticator | **final** |
| `calendar-lifecycle-final.txt` | `02a5040` | `8ad8d2d` | restore/move/delete/restore | **final** |
| `calendar-ui-final.txt` | `02a5040` | `8ad8d2d` | parcours UI v2→v3→v4 + idempotence | **final** |
| `memory-ui.txt` | `02a5040` | `8ad8d2d` | parcours mémoire + persistance après force-stop | **final** |
| `usage-global-ui.txt` | `02a5040` | `8ad8d2d` | Usage Access réel, quota persisté, révocation | **final** |
| `cleanup-final.txt` | `02a5040` | `8ad8d2d` | restauration données app, événements, permissions, services | **final** |

## Règles de lecture

- Un PASS final exige un fichier marqué **final** ci-dessus, avec le SHA, l'APK et
  la commande.
- Les preuves historiques documentent la progression de campagne et les captures
  d'interface ; elles ne remplacent pas un rejeu final.
- Les captures de `captures/` sont synthétiques ; aucune donnée personnelle,
  aucun secret et aucun identifiant matériel n'y figure.
- `android_e2e=NOT_RUN` dans `pc-live-fastapi.txt` est volontaire : ce contrôle
  est Node-to-Node. L'E2E Android est couvert par `instrumented.txt`.
