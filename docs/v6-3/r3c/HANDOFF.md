# ATLAS V6.3 — R3-C — Handoff

Date : **2026-10-28**
Verdict : **`GO_PREPARED_WITH_LIMITATIONS`**
Branche : `feat/atlas-v6-3-r3c-qualification`
Base : `eedf91502227297be5d2ff35524e79983ea36f49`
Code Android final : `d2419b3`
Infrastructure finale : `e3a9867`
Documentation finale : `e1de88f`
Push/PR : **aucun** — autorisation explicite requise.

## 1. Où en est le système

- Les trois correctifs de la vague A sont intégrés et testés : NET-01
  (`OFFLINE`/`ERROR`), COMPAT-01 (anciens appairages), RACE-01 (courses).
- Le chemin public Funnel est **opérationnel et qualifié** :
  `wss://all.taildc7886.ts.net:8443`, TLS 1.3, `/health` 200, `/ready` 200,
  `/metrics` 404, WSS 101, Noise `protected`, auth appareil, chat mock et
  OpenClaw réel (message synthétique), rejeu, idempotence.
- Les services de test isolés **tournent encore** : relais `127.0.0.1:4402`,
  proxy `127.0.0.1:4400`, FastAPI `127.0.0.1:8100` (mock), connecteur
  `127.0.0.1:4401`, `relay_connected=true`.
- Le Pixel n'est **pas connecté** en ADB. Aucun APK R3-C n'a été installé.
- La CI distante n'a **pas** été lancée (aucun push).

## 2. Artefacts

| Élément | Valeur |
|---|---|
| APK principal (build propre) | `365bd84a14ab05a35e2333688c57addb825b42aede8e7e2e1a11b7e0079cd4f3` |
| APK androidTest (build propre) | `143aae62cba4eef4eca1579be36a8ca40be57a98a97fe1388b271e3546b8df43` |
| Signature | debug `da3014662eb65e50a87ef364a2e583c331398795b222e3b37b1a16553a00eeee` |
| Version | `versionCode=1`, `versionName=0.1.0`, compileSdk 37 |
| APK principal sur disque | `android/app/build/outputs/apk/debug/app-debug.apk` |
| APK androidTest sur disque | `android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` |
| Sauvegarde Pixel R3-B | `~/atlas-backups/r3b/pixel-app-backup-pre-r3b.tar` (0600) |
| APK rollback R3-B | `~/atlas-backups/r3b/pixel-current-r3a-plus.apk` (0600) |
| Identifiants hors dépôt | `~/atlas-backups/r3c/*.txt` (0600) |

## 3. Prochaine reprise obligatoire : Pixel

1. Connecter le Pixel `PIXEL_SERIAL` (valeur dans
   `~/atlas-backups/r3c/pixel-serial.txt`), vérifier `adb devices`.
2. Exécuter le pré-vol lecture seule et la sauvegarde app-only vérifiée de
   [`RUNBOOK_PIXEL_FINAL.md`](RUNBOOK_PIXEL_FINAL.md).
3. Vérifier signature, `versionCode` et absence de migration non maîtrisée.
4. Obtenir le mandat d'installation ciblé, puis installer le couple
   `365bd84a…` / `143aae62…` avec `install -r`.
5. Rejouer A3, A4, chat relayé OpenClaw, NET-07/13, CHAT-05/06 et
   UI-04/06/07/08 sur **ce binaire**.
6. Capturer les sorties instrumentées horodatées, les durées et les verdicts.
7. Comparer les témoins et les compteurs calendrier ; ne pas lire les
   contenus personnels.

Ne pas utiliser `pm clear`, `uninstall`, factory reset, `rm -rf` applicatif ni
suppression d'alias Keystore hors scénario explicite.

## 4. Services PC

Ordre et commandes : [`RUNBOOK_REPRISE_PC.md`](RUNBOOK_REPRISE_PC.md).

État actuel : services de test isolés actifs, adaptateur FastAPI `mock`.
Après une reprise ou un redémarrage, vérifier :

```sh
curl -sS http://127.0.0.1:4400/health
curl -sS http://127.0.0.1:4401/metrics
curl --resolve all.taildc7886.ts.net:8443:"$FUNNEL_PUBLIC_IP" \
  -sS -o /dev/null -w '%{http_code}\n' \
  https://all.taildc7886.ts.net:8443/health
```

Les unités systemd ne sont **pas installées**. `linger` était déjà actif et
n'a pas été modifié. Aucune promesse H24.

## 5. Fixtures et sécurité

- Deux appareils synthétiques révoqués ; registre : 10 révoqués, 0 actif.
- Tokens temporaires `/tmp/atlas-r3c/device-r3c-*.json` supprimés.
- Sauvegardes et identifiants hors dépôt en 0600.
- Aucun secret dans les documents versionnés.
- OpenClaw personnel et Bridge non modifiés ; une conversation synthétique a
  été créée côté gateway pour le test réel.
- Aucun push, PR ou merge distant.

## 6. Revue indépendante

Relecteur `codex/gpt-6-luna`, `xhigh` effectif, lecture seule, agent
`88c30a0b-573b-4cbb-941d-1ef9b1effd2d`. Aucun P0/P1. Les P2 identifiés
(identifiants, image relais, collision de conteneur, commande
`curl --interface`) ont été corrigés. Voir `REVUE_INDEPENDANTE.md`.

## 7. Index

- `RAPPORT_FINAL.md` — bilan et verdict.
- `MATRICE_RECETTE.md` — statuts R3-B → R3-C.
- `PREUVES.md` — preuves horodatées et limites.
- `CORRECTIFS.md` — détail des correctifs.
- `RISQUES_RESIDUELS.md` — risques ouverts.
- `RUNBOOK_PIXEL_FINAL.md`, `RUNBOOK_REPRISE_PC.md`.
- `REGISTRE.md`, `tickets/`.
