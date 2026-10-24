# Compétences d'ingénierie ATLAS / Paseo

Ces cinq skills **adaptés à DroidCoach** sont des procédures facultatives, chargées à la demande. Ils ne créent pas de profils Paseo et ne confèrent aucune permission de shell, d'écriture ou d'ADB.

| Skill | Déclencheur | Contrôle essentiel |
| --- | --- | --- |
| `code-review` | Audit d'une branche, d'un correctif ou préparation d'un GO/NO-GO | Deux axes indépendants : exigences et qualité/sécurité |
| `diagnosing-bugs` | Crash, test instable, anomalie Android ou E2E | Reproduction qui peut échouer avant correction |
| `implement-spec` | Petites modifications autorisées par le parent | Worktree dédié, diff borné, test ciblé, parent intégrateur |
| `context-handoff` | Reprise après interruption ou changement de session | SHA, état Git, tests et prochaine action, sans transcription |
| `android17-device-qa` | Pixel réel ou AVD API 37 de qualification | Pré-vol, sauvegarde, permissions par action et preuve honnête |

## Provenance et adaptation

Procédures originales rédigées pour ATLAS à partir des concepts consultés dans [AutoWork](https://github.com/m413w4r3/AutoWork/tree/d7cc6b590d3be41dae10299cc03364771fe6bda1/.agents/skills) : `code-review`, `diagnosing-bugs`, `implement-spec`, `autowork-context-handoff`, ainsi que le guide Paseo. Aucun contenu CTI/bridge de ce projet ne doit être appliqué à ATLAS. Les skills AutoWork « curated » mentionnent une adaptation de [mattpocock/skills](https://github.com/mattpocock/skills) sous MIT ; les présentes procédures ne copient pas leur texte.

Priorité des règles : `AGENTS.md` > consignes explicites de l'utilisateur > mission approuvée > skill. Un skill ne vaut jamais autorisation de pousser, fusionner, modifier le téléphone, révoquer des permissions ou clôturer une issue.

### Configuration Paseo

**Toutes les sous-sessions sont obligatoirement `openai-codex:gpt-6-luna` en `xhigh`** : lecture, code, test, correction et revue. Le parent est la session Paseo choisie par l'utilisateur. Maximum trois enfants simultanés, aucune sous-délégation, un propriétaire par fichier et un propriétaire ADB. Voir le contrat normatif [`.agents/WORKFLOW.md`](../WORKFLOW.md). Vérifier provider/modèle/effort dans la session créée ; aucun fallback High.

Les notifications de fin servent à reprendre le parent. Aucun polling récurrent ou boucle de statut avec `sleep`. Si la reprise événementielle est indisponible, consigner un handoff et attendre une reprise explicite.

Les copies locales de `references/repos/` sont des **références** et non des dépendances de build ; respecter `THIRD_PARTY_NOTICES.md` avant toute reprise de code.
