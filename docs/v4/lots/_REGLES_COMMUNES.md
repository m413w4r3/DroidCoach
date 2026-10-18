# Règles communes à tous les lots V4

Contexte : lire `AGENTS.md`, `README.md`, `prompts/REPRISE_V4_ANDROID17.md` (§ du lot concerné + §6/§7) et `docs/v4/00-baseline-android17.md`.

## Environnement
- Dépôt : `/home/nill/perso/AndroCoach/atlas-preparation`, branche `feat/atlas-v4-android17`. Ne jamais `push`, ne jamais toucher `main`, jamais `--force`/`reset --hard`/`clean`.
- adb : `$HOME/Android/Sdk/platform-tools/adb`. Émulateur Android 17 / API 37.2 : **`emulator-5556`** (lancé par Android Studio). Toujours `export ANDROID_SERIAL=emulator-5556`. Ne pas lancer, recréer, wiper ni arrêter d'AVD. Ne jamais supposer un Pixel physique.
- Un seul module Gradle `:app`. Un seul émulateur partagé : n'installer/tester sur l'AVD que si le brief l'autorise ; sinon tests JVM uniquement.
- Backend : venv `/tmp/v4/venv` (`/tmp/v4/venv/bin/python -m pytest -q`).
- Un seul lot modifie un fichier donné. **Ne modifie que les chemins autorisés** par ton brief. Si tu as besoin d'un changement dans un fichier interdit (build.gradle.kts, AndroidManifest.xml, MainActivity.kt, AtlasApp.kt, AtlasController.kt, `contracts/**`, navigation), ne le fais pas : décris-le précisément (diff proposé) dans ton rapport ; l'orchestrateur l'intègre.

## Interdits (AGENTS.md + V4)
Pas de root, Device Owner/Admin, AccessibilityService par défaut, manipulation ReVanced/YouTube APK, activation silencieuse de permission, LLM / OpenClaw / agent autonome, secret dans Git/logs, donnée personnelle dans fixtures, blocage d'apps vitales. Aucune fonction factice présentée comme réelle.

## Exigences de livraison
- Compile + tests concernés exécutés réellement ; coller dans le rapport commandes, nombres de tests pass/fail, SHA du commit. Jamais PASS sans exécution.
- Commits logiques sur la branche (`git add` des seuls fichiers du lot ; messages `V4 <lot>: ...`). Ne pas committer d'APK/binaires. Autres sessions peuvent committer en parallèle : `git add` par chemin, pas `-A`.
- Rapport honnête : `docs/v4/lots/<ID>.md` avec : objectif, fichiers touchés, décisions (+ `docs/DECISIONS_AUTOMATIQUES.md` pour choix réversibles), preuves, limites, travaux restants, propositions de diff pour fichiers interdits, statut PASS/FAIL/BLOCKED par critère.
- Provenance de tout code tiers repris : origine, SHA, fichiers, licence dans `THIRD_PARTY_NOTICES.md` et `docs/AUDIT_CIBLE.md` (en cas de doute juridique : ne pas copier).
- Réponse finale de session : résumé court (≤ 25 lignes) + chemin du rapport.
