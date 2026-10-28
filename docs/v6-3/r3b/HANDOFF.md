# ATLAS V6.3 — R3-B — Handoff

Date: **2026-10-28**
Verdict: **`GO_PREPARED_WITH_LIMITATIONS`**
Branche: `feat/atlas-v6-3-r3b-remote-openclaw`
Base: `5795d378d38b3e86ce99f1551c41d5044cd4a81c`
Code final: `738ef93`
Revue 4 (code + docs): `4be991de8adc58e27e20beebef816c4728b2b0c3`
SHA final: voir `git rev-parse HEAD` après le commit de clôture de ce handoff.
Aucun push, aucune PR, aucune modification GitHub distante.

## 1. Où en est le système

- La chaîne Android RELAY → Funnel TLS → proxy 4400 → relais 4402 → connecteur
  → FastAPI 8100 → OpenClaw est construite, testée et revue.
- Les services de test sont **arrêtés** : 4400/4401/4402/8100 libres.
- Le Funnel Tailscale existant est **inchangé** et pointe toujours vers
  `127.0.0.1:4400`, mais sans backend actif. Le plan de contrôle Tailscale
  n'était plus joignable au recontrôle final ; l'ingress public est à
  revalider avant tout usage distant.
- Le Pixel `45301FDAS001QT` est **déconnecté de l'USB**. Sa dernière
  restauration vérifiée est 5/5 hashes et calendrier 12/91.
- Aucun PASS Pixel n'est revendiqué pour l'APK final `05756094`.

## 2. Artefacts et rollback

| Élément | Valeur |
|---|---|
| APK principal final (construit, non installé) | `android/app/build/outputs/apk/debug/app-debug.apk`, sha256 `057560940cf078bb90b19bb32a8d288fe3f294c8d2cbf5f256ada5e907033e8c` |
| APK androidTest final (construit) | `android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`, sha256 `5aef13210dc2760fb03fbf757f58b040a2cb2e20838e444427cec211dab70f04` |
| APK installé avant déconnexion | `1a8774220d1ff72568c5262199b3875b6086c1b581aff097f9eac1e2427399b2` |
| Dernier APK testé sur Pixel | `b55776a91d523e9752594abe9de11b2d1ff53e304cbc94020892837948704fcc` |
| Sauvegarde Pixel app-only | `~/atlas-backups/r3b/pixel-app-backup-pre-r3b.tar`, sha256 `fbca987a…`, 0600 |
| APK de rollback Pixel | `~/atlas-backups/r3b/pixel-current-r3a-plus.apk`, 0600 |
| Sauvegarde état connecteur | `~/atlas-backups/r3b/relay-state-post-campaign` (0700/0600) |

Pour reprendre le Pixel :
```sh
adb devices   # attendre 45301FDAS001QT
adb -s 45301FDAS001QT install -r android/app/build/outputs/apk/debug/app-debug.apk
adb -s 45301FDAS001QT install -r -t android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```
Ne pas utiliser `pm clear` ni `uninstall` comme rollback. Restaurer les données
app-only uniquement après contrôle de la sauvegarde et consentement ciblé.

## 3. Redémarrage des services de test

Suivre `RUNBOOK_PC.md` et `DEPLOIEMENT_TAILSCALE_FUNNEL.md`. Ordre :
1. relais Paseo épinglé sur `127.0.0.1:4402` ;
2. proxy filtrant sur `127.0.0.1:4400` ;
3. FastAPI de test sur `127.0.0.1:8100` (adaptateur mock ou OpenClaw) ;
4. connecteur avec `~/.config/atlas/relay.env` (0600) ;
5. vérifier `/health`, `/ready`, `/metrics` 404, puis le Funnel.

L'identité X25519 du connecteur est conservée ; les 8 appareils synthétiques
sont révoqués, 0 actif. Les tokens synthétiques et bases FastAPI de test ont
été supprimés. Le Funnel n'a pas été modifié.

## 4. Checklist de reprise obligatoire

- [ ] Reconnecter le Pixel ; vérifier `adb devices`.
- [ ] Installer l'APK final `05756094` et l'androidTest `5aef1321`.
- [ ] Rejouer A3 pairing/persistence, A4 révocation/after-clear/repair (dont
      close 4001 et absence d'alias Keystore), chat relayé et réseau.
- [ ] Rétablir la connectivité Tailscale du PC ; vérifier TLS public,
      `/health`/`/ready`, `/metrics` 404, WSS/Noise, puis E2E public.
- [ ] Rejouer NET-07 en notant l'état exact (`ERROR` vs `OFFLINE`).
- [ ] Rejouer NET-13 (expiration session/token), CHAT-05/06, UI-04/06/07/08.
- [ ] Qualifier TalkBack/police agrandie et Doze/H24 si promis.
- [ ] WGER-01 uniquement avec autorisation explicite (lecture seule).
- [ ] Installer systemd uniquement après autorisation ciblée (pas de linger).

## 5. Revue indépendante

Quatre sessions `codex/gpt-6-luna`, `xhigh` effectif, worktrees isolés,
lecture seule :
- revue 1 `beb8f33` : BLOQUÉ P1 consentement périmé → `7754005` ;
- revue 2 `8af78c5` : BLOQUÉ course contrôle/écriture → `91e7aa6` ;
- revue 3 `caf92e4` : BLOQUÉ consentement d'usage/receive → `738ef93` ;
- revue 4 `4be991d` : **APPROUVE_AVEC_RESERVES**, aucun P0/P1.
Réserve R-22 : couverture de tests de concurrence perfectible.

## 6. Limites à ne pas masquer

- Ingress public Funnel indisponible au final (R-18/R-20) ; le rejeu MagicDNS
  n'est pas une preuve d'Internet public.
- APK final non installé/testé sur Pixel (R-21).
- NET-07, NET-13, A4-4/A4-8, A3 logs, CHAT-05/06 et plusieurs points UI sont
  PARTIAL.
- wger non exécuté (WGER-01 BLOCKED), systemd non installé, Doze/H24 non testés.
- Le PC éteint rend le service indisponible ; aucune haute disponibilité.

## 7. Sécurité et données

- Aucun token, clé privée, secret ou donnée personnelle dans les fichiers
  versionnés ; scan effectué.
- Aucune modification de la configuration Tailscale, de FastAPI/OpenClaw
  personnels, du Bridge ou d'ADB ; aucun root/Device Owner/kiosk.
- La sauvegarde Pixel et l'APK de rollback sont hors dépôt, en 0600.
- Aucun push/PR/merge distant sans autorisation explicite.

## 8. Index

- `RAPPORT_FINAL.md` — bilan et verdict.
- `REVUE_INDEPENDANTE.md` — quatre revues et correctifs.
- `MATRICE_RECETTE.md`, `PREUVES.md`, `RISQUES.md`, `DECISIONS.md`,
  `REGISTRE.md` — statuts et preuves.
- `preuves/cleanup-final.txt` — nettoyage final.
- `tickets/` — contrats et clôtures des tickets.
