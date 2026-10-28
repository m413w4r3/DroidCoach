# ATLAS V6.3 — R3-C — Handoff

Date : **2026-10-28**
Verdict : **`GO_PREPARED_WITH_LIMITATIONS`**
Branche : `feat/atlas-v6-3-r3c-qualification`
Base : `eedf91502227297be5d2ff35524e79983ea36f49`
Code Android final : `d2419b3`
Infrastructure finale : `e3a9867`
Commit de clôture : voir `git rev-parse HEAD` (docs + test androidTest)
Push : effectué sur la branche de campagne, **aucune PR ni merge**.
CI distante : **success** sur `a72ba9e` (run `40733692711`) ; dernier run requis
après le commit de clôture.

## 1. Où en est le système

- Les trois correctifs de la vague A sont intégrés et testés : NET-01
  (`OFFLINE`/`ERROR`), COMPAT-01 (anciens appairages), RACE-01 (courses).
- Le couple APK R3-C est **installé sur le Pixel**, utilisateur 0 :
  `365bd84a…` / `09ecfd7a…`.
- **A3 PASS** : appairage Keystore, force-stop, redémarrage Android, identité
  et token conservés, reconnexion sans ressaisie.
- **A4 partiel** : `DEVICE_REVOKED`, `REVOKED`, aucune reconnexion, effacement
  explicite, `PAIRING_REQUIRED`, réappairage PASS ; **A4-4 FAIL** — le relais
  ferme avec `1012` au lieu de `4001`.
- **Chat réel Android PASS** : OpenClaw via le Funnel, provenance, aucune
  action non confirmée, brouillon/historique.
- **NET-07 PASS** : coupure Wi-Fi+données → `OFFLINE`, reconnexion,
  conversation conservée.
- **NET-09 PASS** : handover Wi-Fi → cellulaire → Wi-Fi.
- **UI-06/UI-07 PASS**, police 1,3 et paysage PASS ; UI-04 et TalkBack
  restent PARTIAL/NOT_RUN.
- Le chemin public Funnel est opérationnel : TLS 1.3, health/ready 200,
  metrics 404, WSS 101, Noise `protected`.
- Les services de test isolés **tournent encore** : relais `127.0.0.1:4402`,
  proxy `127.0.0.1:4400`, FastAPI `127.0.0.1:8100` (actuellement OpenClaw),
  connecteur `127.0.0.1:4401`, `relay_connected=true`.

## 2. Artefacts

| Élément | Valeur |
|---|---|
| APK principal | `365bd84a14ab05a35e2333688c57addb825b42aede8e7e2e1a11b7e0079cd4f3` |
| APK androidTest | `09ecfd7a3d527e98351e224d70b5f44b81c276e7e514300cd9fd859c99ac8c98` |
| Signature | debug `da3014662eb65e50a87ef364a2e583c331398795b222e3b37b1a16553a00eeee` |
| Version | `versionCode=1`, `versionName=0.1.0`, compileSdk 37 |
| APK principal sur disque | `android/app/build/outputs/apk/debug/app-debug.apk` |
| APK androidTest sur disque | `android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` |
| Identifiants hors dépôt | `~/atlas-backups/r3c/*.txt` (0600) |
| Sauvegarde Pixel R3-B | `~/atlas-backups/r3b/pixel-app-backup-pre-r3b.tar` (0600) |
| APK rollback R3-B | `~/atlas-backups/r3b/pixel-current-r3a-plus.apk` (0600) |

## 3. État Pixel et prochaine décision

L'utilisateur 0 n'avait **plus l'app ni ses données** au pré-vol ; les profils
12/13 avaient encore l'app et n'ont pas été modifiés. L'installation R3-C a
créé des données neuves pour l'utilisateur 0. Aucune donnée R3-B de
l'utilisateur 0 n'a été restaurée ; la sauvegarde R3-B reste disponible pour
une restauration exceptionnelle avec autorisation ciblée.

Le seul écart bloquant pour `GO_REMOTE` est **A4-4** :

1. corriger la propagation du code `4001` dans le relais amont, **ou**
2. accepter explicitement `1012` comme fermeture de transport après
   `DEVICE_REVOKED` et adapter le contrat A4-4.

Après décision, rejouer le test A4 UI complet (clic « Supprimer » et
confirmation) sur le binaire retenu. Les autres conditions GO_REMOTE sont
réunies sur le périmètre vérifié.

## 4. Services PC

Ordre et commandes : [`RUNBOOK_REPRISE_PC.md`](RUNBOOK_REPRISE_PC.md).

État actuel : services de test isolés actifs, FastAPI en adaptateur
`openclaw` (le passage en `mock` se fait en relançant uvicorn avec
`ATLAS_AGENT_ADAPTER=mock`). Vérifier :

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

- Appareils synthétiques actifs : `r3c-pixel-chat` (utilisé par le Pixel) et
  `r3c-pixel-a4-repair` ; les autres appareils de test sont révoqués.
- Tokens temporaires consommés ou supprimés ; aucun token en clair.
- Marqueurs de test synthétiques dans `files/` (`r3b-a3-marker.json`,
  `r3b-a4-cleared-marker.json`, `r3b-net-*.marker`) ; ils ne contiennent
  aucune donnée personnelle.
- OpenClaw personnel et Bridge non modifiés ; une conversation synthétique a
  été créée côté gateway.
- Aucun `pm clear`, `uninstall`, factory reset, `rm -rf` applicatif ni
  suppression d'alias Keystore hors scénario explicite.

## 6. Revue indépendante

Relecteur `codex/gpt-6-luna`, `xhigh` effectif, lecture seule, agent
`88c30a0b-573b-4cbb-941d-1ef9b1effd2d`. Verdict final **`APPROUVE`**, aucun
P0/P1. Les P2 identifiants/image/conteneur/placeholder ont été corrigés et
confirmés. Le test Android `R3cA4ClearInstrumentedTest` a été ajouté après la
revue pour qualifier l'effacement sans clic UI ; il est test-only et doit être
relu lors de la prochaine revue.

## 7. Index

- `RAPPORT_FINAL.md` — bilan et verdict.
- `MATRICE_RECETTE.md` — statuts R3-B → R3-C.
- `PREUVES.md` — preuves horodatées et limites.
- `CORRECTIFS.md` — correctifs et constat A4-4.
- `RISQUES_RESIDUELS.md` — risques ouverts, dont R-31 (1012).
- `RUNBOOK_PIXEL_FINAL.md`, `RUNBOOK_REPRISE_PC.md`.
- `REGISTRE.md`, `tickets/`.
