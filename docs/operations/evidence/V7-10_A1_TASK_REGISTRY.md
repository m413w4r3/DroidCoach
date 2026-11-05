# V7-10 A1 — Registre de tâche

- **Task ID:** `V7-10-A1-DEVICE-AUTH`
- **Base SHA:** `710a49a0094e49ae1e794d009e55536fe1aba7fe`
- **Branche / worktree:** `v7-10/a1-device-auth` — `/home/nill/.paseo/worktrees/073527gm/v7-10-a1-device-auth`
- **Profil effectif:** `codex / gpt-6-luna / xhigh` (confirmé par l’orchestrateur Paseo)
- **Scope:** Core backend V4 + connecteur Relay PC, tests locaux, migration/provisionnement offline et documentation. Android, services/base live, secrets réels, ADB, Pixel, réseau externe, push/PR/merge exclus.
- **Base inspectée:** worktree assigné sur la base SHA indiquée; `.codegraph/` absent.
- **Permissions d’exécution:** SQLite synthétique seulement sous `tmp_path`/`/tmp`; npm offline; tests loopback locaux seulement; aucun fichier `.env` ou secret réel lu.
- **Oracle:** suites backend et Node demandées; isolation credential/principal A/B; migration additif et dry-run/apply; `git diff --check`; commits locaux audités; rapport PASS/FAIL/BLOCKED/NOT_RUN.
- **Référence:** `V7-10_S1_AUTH_DESIGN.md`; les décisions d’orchestration attachées au ticket fixent les modes `legacy|dual|principal` et priment sur ses suggestions divergentes. `V7-10_S2_EXPIRATION_DESIGN.md` lu, mais non implémenté.
- **Décision d’implémentation réversible:** `legacy-import` exige `--grant-defaults`; cet indicateur accorde explicitement les capabilities V4 allowlistées nécessaires à la compatibilité APK. Le mode principal refuse ensuite l’audience LEGACY.
