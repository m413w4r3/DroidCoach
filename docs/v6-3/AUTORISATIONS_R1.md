# R1 — Autorisations ciblées à demander avant le parcours Pixel

Ce document **ne vaut pas autorisation**. Il prépare les demandes qui seront
soumises au propriétaire au moment utile, avec action, données, risque,
sauvegarde, retour arrière, test attendu et état final.

## État vérifié avant R1 (2026-10-26)

- Pixel 9 Pro XL, Android 17 / API 37, connecté en USB.
- ATLAS installé : `dev.atlas.coach` 0.1.0, versionCode 1, `DEBUGGABLE`,
  certificat `CN=Android Debug` SHA-256 `da3014662eb65e50a87ef364a2e583c331398795b222e3b37b1a16553a00eeee`.
- APK installé sauvegardé hors dépôt :
  `/home/nill/.local/share/atlas-qa/r1/atlas-installed-0.1.0-48c7766e.apk`
  (mode 600), SHA-256 `48c7766e9090010692f0eaafc1d0cbaf4b22e51259847f94b642a2d060b33583`.
- `run-as dev.atlas.coach` accessible (app debug) ; `tar` Toybox disponible.
- Données app actuelles : `shared_prefs/atlas_local_state.xml` (13 976 octets),
  plusieurs préférences privées ; `files/plans` **n'existe pas encore**.
- HOME = NexusLauncher. Aucune permission calendrier demandée.
- Backend ATLAS non démarré ; port cible 8010 ; `adb reverse` vide.

## Sauvegarde app-only préalable (à exécuter avec accord P1)

```bash
adb -s <serial> shell run-as dev.atlas.coach tar -cf - shared_prefs files \
  > /home/nill/.local/share/atlas-qa/r1/app-data-before-r1.tar
chmod 600 /home/nill/.local/share/atlas-qa/r1/app-data-before-r1.tar
sha256sum /home/nill/.local/share/atlas-qa/r1/app-data-before-r1.tar
```

- Archive hors dépôt, hors `/tmp` seul, mode 600.
- Vérifier la lisibilité de l'archive (`tar -tf`) sans la réinjecter.
- `allowBackup=false` : la sauvegarde Android ordinaire n'est pas supposée
  fonctionner ; `run-as` est utilisé uniquement parce que l'APK est debug.

## P1 — Installer la nouvelle APK debug

| Élément | Contenu |
|---|---|
| Action | `adb install -r <nouvelle APK debug>` (mise à jour en place, **sans** `uninstall`, **sans** `pm clear`) |
| Données / services | données privées ATLAS conservées ; aucune donnée système, aucun rôle HOME, aucune permission modifiés |
| Risque | régression ou crash de l'app ; migration de données locale ; pas d'effacement attendu |
| Sauvegarde | APK 0.1.0 sauvegardée + sauvegarde app-only `shared_prefs`/`files` ci-dessus |
| Retour arrière | `adb install -r` de l'APK 0.1.0 sauvegardée ; restauration de l'archive app-only si nécessaire |
| Test attendu | l'app démarre ; l'écran Plan existant s'ouvre ; les données existantes restent lisibles ; aucun crash |
| État final | nouvelle APK installée ; HOME inchangé ; aucune permission calendrier accordée ; aucun événement calendrier |

## P2 — Configurer l'URL et le jeton du backend local

| Élément | Contenu |
|---|---|
| Action | saisir dans l'app `http://127.0.0.1:8010` et un Bearer token généré pour la session |
| Données | token stocké dans Android Keystore ; URL locale seulement |
| Risque | secret local visible pendant la saisie ; aucune exposition réseau au-delà du loopback USB |
| Sauvegarde | token conservé hors dépôt en 600 pour la session ; aucun token dans Git/logs |
| Retour arrière | effacer la configuration dans l'app ; révoquer/remplacer le token backend |
| Test attendu | backend `/health` 200 via `adb reverse` ; l'app affiche l'état configuré |
| État final | URL et token configurés ; `adb reverse tcp:8010 tcp:8010` actif ; backend arrêtable sans casser le plan local |

## P3 — Accorder `READ_CALENDAR` et lire les calendriers

| Élément | Contenu |
|---|---|
| Action | installer l'APK avec `READ_CALENDAR` déclaré (P1), puis accorder la permission runtime depuis l'écran de diagnostic |
| Données | liste des calendriers visibles (nom, compte expurgé, niveau d'accès, visibilité), présence d'Etar ; **aucun événement affiché**, aucune écriture |
| Risque | la permission autorise techniquement la lecture d'événements ; l'écran de diagnostic n'en lit pas ; révocation possible |
| Sauvegarde | sauvegarde app-only P1 ; aucune donnée calendrier modifiée |
| Retour arrière | révoquer `READ_CALENDAR` dans les réglages Android ; l'écran repasse en état refusé |
| Test attendu | permission refusée → aucun accès ; accordée + consentement ATLAS → liste des calendriers ; Etar détectée ; aucun événement |
| État final | permission accordée ou révoquée selon la décision ; aucun calendrier modifié |

## P4 — Écriture calendrier (lot ultérieur, non demandé ici)

Sera soumis séparément après `V63-L2-03` (lecture réelle Pixel/Etar) et
`V63-L4-01` (écriture, liens, réconciliation). Exigera : `WRITE_CALENDAR`,
choix explicite d'un calendrier local inscriptible, sauvegarde du calendrier
ciblé, événement synthétique marqué ATLAS, read-back, visibilité Etar,
déplacement, suppression, absence de doublon, restauration de l'état final.

## P5 — Configuration OpenClaw active et redémarrage (lot ultérieur)

Sera soumis séparément pour le plugin `atlas_chatgpt_research` :
allowlist du planificateur `atlas` limitée à `sessions_spawn`, agent
`atlas-research` limité à l'outil de recherche, rechargement/redémarrage du
Gateway, puis recherche réelle. Sans accord : `BLOCKED_POLICY`.

## Ce qui n'est pas demandé par ce document

- Aucune désinstallation, aucun `pm clear`, aucun factory reset.
- Aucun changement de rôle HOME.
- Aucune permission `WRITE_CALENDAR` dans R1.
- Aucune écriture wger.
- Aucun déploiement de relais externe.
- Aucun push GitHub, PR ou merge distant.
