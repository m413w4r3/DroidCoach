# ATLAS — Correctif de préparation V3

Correctif ciblé à appliquer **à la racine** du dossier déjà décompressé contenant `README.md`, `AGENTS.md`, `docs/`, `prompts/` et `references/`.

Ce correctif :
- **remplace** `prompts/AGENT_PRINCIPAL.md` (V2 -> V3) ;
- **ajoute** `prompts/NIMBALYST_DEMARRAGE.md` pour le message court d'orchestration ;
- **ajoute** `docs/08-phase1-preanalyse.md` : URLs directes et décisions prises avant développement ;
- **ajoute** `docs/09-plan-lots-et-recette-vm.md` : contrats, sessions S0–S5, tests VM-0/VM-1 ;
- ne remplace pas les autres spécifications, `AGENTS.md`, scripts ou documents personnels.

Depuis le répertoire ATLAS :

```bash
unzip -o /chemin/vers/atlas-mise-a-jour-v3.zip -d .
head -n 2 prompts/AGENT_PRINCIPAL.md
```

La première ligne doit contenir `PROMPT PRINCIPAL V3`. Ensuite envoyer à Nimbalyst le **contenu de `prompts/NIMBALYST_DEMARRAGE.md`** ou demander à l'agent de le lire et l'appliquer.

`docs/03-audit-open-source.md` est conservé comme ancien protocole ; `docs/08-phase1-preanalyse.md` le remplace sur les décisions et l'effort de recherche.

Aucun dépôt externe à cloner manuellement. Aucun APK n'est inclus dans ce correctif : le code Android doit être construit par les sessions de développement.
