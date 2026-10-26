# R1 — Runbook d'exécution sur le Pixel

Préparation uniquement. Chaque porte de `AUTORISATIONS_R1.md` doit être
approuvée avant l'action correspondante. Le parent reste le seul opérateur ADB.

## État de départ

- Pixel 9 Pro XL Android 17/API 37 connecté (`device`).
- APK 0.1.0 installé, sauvegardé :
  `/home/nill/.local/share/atlas-qa/r1/atlas-installed-0.1.0-48c7766e.apk`.
- Aucune permission calendrier accordée.
- Backend ATLAS arrêté ; port 8010 libre ; `adb reverse` vide.
- OpenClaw gateway `127.0.0.1:18789` actif, agent `atlas` sans outil.
- Bridge `127.0.0.1:8001` actif (non utilisé par R1).

## 1. Backend local (port 8010)

Générer un token de session sans l'afficher :

```bash
umask 077
mkdir -p /home/nill/.local/share/atlas-qa/r1
python3 -c 'import secrets; print(secrets.token_urlsafe(32))' \
  > /home/nill/.local/share/atlas-qa/r1/backend-token.txt
chmod 600 /home/nill/.local/share/atlas-qa/r1/backend-token.txt
```

Lancer le backend (adapter OpenClaw, capability `plan_revision` explicitement
activée pour ce test de développement) :

```bash
cd /home/nill/perso/AndroCoach/atlas-preparation
ATLAS_AGENT_ADAPTER=openclaw \
ATLAS_TOKEN="$(cat /home/nill/.local/share/atlas-qa/r1/backend-token.txt)" \
ATLAS_DB_PATH=/home/nill/.local/share/atlas-qa/r1/atlas-r1.sqlite3 \
ATLAS_CAPABILITIES_JSON='{"plan_revision":true}' \
OPENCLAW_BASE_URL=http://127.0.0.1:18789 \
OPENCLAW_AGENT_ID=atlas \
OPENCLAW_GATEWAY_TOKEN_FILE=/home/nill/.config/atlas/openclaw-gateway-token \
ATLAS_AGENT_RPM=30 \
PYTHONPATH=backend \
  backend/.venv/bin/python -m uvicorn atlas_backend.main:app \
    --host 127.0.0.1 --port 8010 --no-access-log --log-level warning
```

Vérifier sans secret :

```bash
curl -sS http://127.0.0.1:8010/health
curl -sS -H "Authorization: Bearer $(cat /home/nill/.local/share/atlas-qa/r1/backend-token.txt)" \
  http://127.0.0.1:8010/v2/agent/status
```

Attendu : `/health` 200 ; adapter `openclaw`, breaker fermé.

## 2. ADB reverse

```bash
adb -s 45301FDAS001QT reverse tcp:8010 tcp:8010
adb -s 45301FDAS001QT reverse --list
```

Attendu : `tcp:8010 tcp:8010`. Aucun autre reverse/forward actif.

## 3. Installation de l'APK (porte P1)

Après accord P1 :

```bash
# Sauvegarde app-only avant mise à jour
adb -s 45301FDAS001QT shell run-as dev.atlas.coach tar -cf - shared_prefs files \
  > /home/nill/.local/share/atlas-qa/r1/app-data-before-r1.tar
chmod 600 /home/nill/.local/share/atlas-qa/r1/app-data-before-r1.tar
sha256sum /home/nill/.local/share/atlas-qa/r1/app-data-before-r1.tar
tar -tf /home/nill/.local/share/atlas-qa/r1/app-data-before-r1.tar | head

# APK final du build intégré (code 1791030, branche 668ed4b)
# SHA-256 : 5956bb6f59d46c1f18f28f9fd764d2f51fda1a0250b32a148fb2d277d6c03d5f
APK=/home/nill/perso/AndroCoach/atlas-preparation/android/app/build/outputs/apk/debug/app-debug.apk
sha256sum "$APK"
adb -s 45301FDAS001QT install -r "$APK"
```

Attendu : `Success`. Ne pas utiliser `uninstall`, `pm clear`, `-d` sans
justification ni `run-as` pour modifier les données.

Vérifier après installation :

```bash
adb -s 45301FDAS001QT shell dumpsys package dev.atlas.coach | grep -E 'versionName|versionCode|firstInstallTime|lastUpdateTime'
adb -s 45301FDAS001QT shell pm list permissions -g -d | grep -E 'READ_CALENDAR|WRITE_CALENDAR' || true
```

## 4. Configuration app (porte P2)

Dans ATLAS : **Plus > Partage backend**.

- URL : `http://127.0.0.1:8010`
- Jeton : contenu de
  `/home/nill/.local/share/atlas-qa/r1/backend-token.txt` (saisie manuelle ou
  canal local, jamais dans un log).
- Enregistrer.

Attendu : l'app indique la configuration enregistrée ; le token n'est pas
affiché en clair après saisie.

## 5. Parcours plan local

1. **Plus > Plan du jour**.
2. Si aucun plan : « Créer le programme du jour ».
3. Ajouter deux activités (titre, heure, durée, priorité).
4. Vérifier l'aperçu avant application et la confirmation groupée.
5. Fermer l'app, la rouvrir : le plan et sa version sont conservés.

## 6. Parcours coach (cœur du gate R1)

1. Dans Plan du jour, section **Coach** : « Activer les révisions par le
   coach » → lire la divulgation → confirmer.
2. « Demander au coach » → message, par exemple :
   « Propose une organisation de ma journée en gardant mes priorités. »
3. Envoyer.
4. Attendu : une proposition `plan.revise_day` revient ; l'écran affiche le
   diff avant/après et la confirmation groupée. Aucun effet avant
   « Confirmer ».
5. Confirmer ; vérifier la nouvelle version et les activités.
6. Rejouer la même demande / le même identifiant si possible : aucun doublon.
7. Fermer/rouvrir : le plan confirmé reste lisible hors ligne.
8. Couper le backend (Ctrl-C) : le plan local reste lisible ; les actions
   locales restent disponibles ; une demande coach échoue proprement.

## 7. Calendrier (porte P3, séparée)

Après accord P3 :

1. **Plus > Diagnostic calendrier**.
2. Vérifier l'état de permission et le consentement ATLAS.
3. « Autoriser la lecture du calendrier » → accorder la permission runtime.
4. Attendu : liste des calendriers visibles (nom, compte expurgé, accès,
   visibilité), présence d'Etar, **aucun événement**.
5. Refuser/révoquer ensuite : l'écran repasse en état refusé, aucune lecture.

Aucune écriture calendrier, aucun événement synthétique dans R1.

## 8. Clôture et état final

```bash
adb -s 45301FDAS001QT reverse --remove tcp:8010
adb -s 45301FDAS001QT reverse --list
# Arrêter le backend (Ctrl-C ou kill du PID uvicorn)
```

- Conserver `/home/nill/.local/share/atlas-qa/r1/` (APK 0.1.0, token de
  session, sauvegarde app-only, preuves) hors dépôt, mode 600.
- Ne pas supprimer le plan utilisateur ni les préférences sans accord.
- Si le Pixel se débranche : arrêter, marquer les étapes restantes `NOT_RUN`,
  consigner le dernier état lu ; ne pas deviner la restauration.
- Si l'OpenClaw renvoie `invalid_output` : consigner la réponse expurgée, ne
  pas inventer de proposition, ne pas modifier le mandat.
- Si le calendrier n'a aucun calendrier local inscriptible : L4 sera
  `BLOCKED_ENVIRONMENT`, sans fallback Google ni sync adapter simulé.

## Preuves à consigner

SHA source intégré, SHA APK, date, appareil/API, commandes, réponses
expurgées, `PASS/FAIL/BLOCKED/NOT_RUN`, état final. Aucun titre personnel,
nom de compte, token ou contenu d'événement dans les logs.
