# Mise à jour V4 — DroidCoach

**But :** préparer un run Nimbalyst de consolidation Android 17 (API 37.2), avec réutilisation OSS, corrections, améliorations UX, vraies intégrations locales et backend mock ; OpenClaw/agent IA reportés.

## Installation

Depuis la racine de ton clone Git local de DroidCoach :

```bash
unzip -o /chemin/vers/atlas-correctif-v4.zip -d .
```

Cela ajoute `prompts/REPRISE_V4_ANDROID17.md` et ce README. Aucun fichier de code ni prompt V3 existant n'est écrasé.

## Message à copier dans Nimbalyst

> Tu es l'orchestrateur principal de DroidCoach. Lis `AGENTS.md`, `README.md` puis **intégralement** `prompts/REPRISE_V4_ANDROID17.md`. Exécute la V4 à partir de l'état réel du dépôt, par sous-sessions Nimbalyst `spawn_session` paramétrées si disponibles avec `model: "openai-codex:gpt-6-luna"`, `effortLevel: "xhigh"`, `notifyOnComplete: true`. Respecte les lots, les fichiers non concurrents, la recette et la branche de travail prévus. Mon **unique AVD est Android 17 QPR2 / API 37.2** : ne recrée pas les anciens Android 11. Corrige et améliore le code existant, réutilise les parties utiles de Reef/Olauncher/Loop et réévalue Lawnchair sans l'écarter par réflexe. **Aucun OpenClaw/LLM/agent IA pendant ce run.** Commence par R0 et montre les preuves de validation sur Android 17 avant de conclure.

## Sources utilisées

Audit du dépôt public `m413w4r3/DroidCoach` au commit `4cc8542622aadc2348de3c981f37cb2e1ed92939` et documentation Android officielle référencée dans le prompt. L'orchestrateur doit commencer par vérifier l'état réel du clone avant toute action.
