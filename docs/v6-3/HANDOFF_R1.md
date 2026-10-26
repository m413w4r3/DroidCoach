# ATLAS V6.3 — Handoff orchestrateur — 26 octobre 2026

## 1. Mandat et état

Tu reprends l'orchestration du chantier `prompts/PROMPT_MAITRE.md` à l'état
**R1 en cours**. Lis d'abord, dans cet ordre :

1. `AGENTS.md` et `.agents/WORKFLOW.md` (6 enfants max, 3 implémentations,
   aucune sous-délégation, Luna XHigh).
2. `docs/v6-3/REGISTRE_TACHES.md` (source de vérité des tickets).
3. `docs/v6-3/R1_RUNBOOK.md` (procédure Pixel).
4. `docs/v6-3/AUTORISATIONS_R1.md` (portes d'approbation).
5. `docs/v6-3/PREFLIGHT.md`, `docs/DECISIONS_AUTOMATIQUES.md`.
6. Les preuves `docs/v6-3/preuves/**` et les audits `docs/v6-3/AUDIT_*.md`.

Branche de mission : `feat/atlas-v6-3-reprise`.
HEAD au handoff : `2ffee7738860800cded2e6a39f3dd50a5720b524`.
Les seules modifications locales non commitées sont les suppressions
utilisateur sous `prompts/` et `prompts/PROMPT_MAITRE.md` non suivi : **ne pas
les committer, ne pas les restaurer**.

## 2. Décisions et approbations du propriétaire

Le propriétaire a explicitement approuvé :

- **P1 + P2** : sauvegarde app-only, installation en place de l'APK debug
  `5956bb6f...`, backend local 8010 + `adb reverse`, configuration URL/jeton,
  et **parent conduit** le parcours Plan/coach.
- **P3** : `READ_CALENDAR` pour le diagnostic calendrier, **après** le parcours
  Plan, lecture seule, aucun événement affiché, aucune écriture.

Non approuvé : écriture calendrier (`WRITE_CALENDAR`), écriture wger,
modification de la configuration OpenClaw active, activation du plugin de
recherche, redémarrage du Gateway, déploiement de relais, push/PR/merge
distant.

## 3. État physique exact (26 octobre, ~13:55)

- Pixel 9 Pro XL Android 17/API 37, connecté, HOME = NexusLauncher.
- APK installé : `dev.atlas.coach` 0.1.0, versionCode 1, source
  `1791030`, APK SHA-256
  `5956bb6f59d46c1f18f28f9fd764d2f51fda1a0250b32a148fb2d277d6c03d5f`.
- APK 0.1.0 précédent sauvegardé :
  `$HOME/.local/share/atlas-qa/r1/atlas-installed-0.1.0-48c7766e.apk`.
- Sauvegarde app-only avant mise à jour :
  `$HOME/.local/share/atlas-qa/r1/app-data-before-r1.tar` (SHA-256
  `28a74099402c6ca2f2d28d4733f7e1638734a4188e0d42c6aa8cbc7c767bfcdd`).
- Backend ATLAS : `127.0.0.1:8010`, `/health` 200, adapter `openclaw`,
  capability `plan_revision=true`, DB
  `$HOME/.local/share/atlas-qa/r1/atlas-r1.sqlite3`.
- Token de session : `$HOME/.local/share/atlas-qa/r1/backend-token.txt`
  (0600). Il est déjà saisi dans l'app (Keystore) ; **ne jamais l'afficher**.
- `adb -s $PIXEL_SERIAL reverse tcp:8010 tcp:8010` actif.
- App : Sync activée, URL `http://127.0.0.1:8010` enregistrée, jeton enregistré
  et chiffré, aucune catégorie de partage sélectionnée. L'écran courant est
  **Plus > Diagnostic avancé > Configurer le partage backend**.
- `READ_CALENDAR` déclaré dans l'APK mais **granted=false**. Aucune lecture
  réelle de calendrier n'a été faite.
- Le backend est lancé comme tâche de fond de l'ancienne session ; s'il est
  arrêté à la fin de celle-ci, le relancer avec la commande du runbook.

## 4. Tickets intégrés (état vérifié)

| Ticket | SHA final | Preuve |
|---|---|---|
| R0 pré-vol + docs | `f625f0d` | 91 backend, 426 JVM, smoke Luna xhigh |
| V63-L1-03 HOME next action | `38c70d7` | 439 JVM, lint 0/52, APK |
| V63-L3-01a backend plan revision | `8c402ee` | 30 ciblés, 152 backend, re-revue `ACCEPTED_WITH_P2` |
| V63-L2-02 préparation READ_CALENDAR | `c990a75` | 31 ciblés, lint 0/54, 447 JVM ; **non installé/accordé à l'époque** |
| V63-L5-01 wger lecture | `a56de74` | 37 ciblés, 158 backend ; diagnostic réel routine/exercise/workoutlog/workoutsession 200/200/200/200 |
| V63-L3-01b Android V3 | `4df7e68` | 87 ciblés, 471 JVM, lint 0/54, APK |
| V63-L3-01c câblage UI | `1791030` | 474 JVM, lint 0/54, APK `5956bb6f` |
| Docs/runbook | `2ffee77` | HEAD |

Le build intégré final : 70 suites, **474 tests**, 0 échec, lint **0 erreur /
54 avertissements**, `assembleDebug` PASS.

## 5. Ticket non intégré

**V63-L8-02** — plugin OpenClaw `atlas_chatgpt_research` :
- branche `feat/v63-l8-02-openclaw-research`, commit `c6efa58`.
- Relecture indépendante : **ACCEPTED_WITH_P2**.
- Oracle parent : `npm ci` PASS, 34 tests PASS, `npm run plugin:build` PASS,
  manifeste généré, `openclaw plugins validate` → *Plugin atlas_chatgpt_research
  is valid*.
- P2 restant : dans `recoverByKey`, un `429` pendant la reprise perd son
  `Retry-After` ; la clé et le corps restent identiques (idempotence
  préservée). À corriger avant activation, pas bloquant pour le merge du code.
- **Ne pas installer ni activer le plugin, ne pas modifier
  `~/.openclaw/openclaw.json`, ne pas redémarrer le Gateway** sans accord.
- L'activation du parcours recherche reste `BLOCKED_POLICY` (le planificateur
  `atlas` a `tools.deny:["*"]`) ; le plan d'activation est dans
  `docs/v6-3/OPENCLAW_ACTIVATION_PLAN.md`.

## 6. Prochaines actions immédiates (parent)

1. Vérifier `curl http://127.0.0.1:8010/health` et `adb reverse --list`.
   Si le backend est arrêté, le relancer avec `R1_RUNBOOK.md` §1 (le token et
   la DB sont conservés).
2. Revenir à l'app : **Retour**, puis **Plus > Plan du jour**.
3. Créer le programme du jour, ajouter deux activités, vérifier l'aperçu et la
   confirmation groupée. Fermer/rouvrir pour vérifier la persistance.
4. Section **Coach** : activer les révisions (dialogue de consentement),
   « Demander au coach », message court, envoyer.
   - Attendu : proposition V3, diff avant/après, confirmation groupée ; aucun
     effet avant confirmation.
   - Si `invalid_output`, quota, timeout ou proposition nulle : consigner la
     réponse expurgée, ne pas inventer, ne pas modifier le mandat. La première
     exécution réelle du prompt plan revision reste à qualifier.
5. Confirmer, vérifier la version, fermer/rouvrir, couper le backend et vérifier
   la lecture hors ligne.
6. **Après le plan**, exécuter P3 : **Plus > Diagnostic calendrier**, accorder
   `READ_CALENDAR`, vérifier la liste des calendriers et Etar, aucun événement.
7. Consigner dans `docs/v6-3/preuves/r1-physical.txt` : SHA APK/source, date,
   commandes/gestes, réponses expurgées, `PASS/FAIL/BLOCKED/NOT_RUN`, état final.
8. Mettre le registre à jour et committer les preuves.

## 7. Délégation attendue (le propriétaire demande plus de délégation)

Respecter 6 enfants max, 3 tickets d'implémentation max, aucune
sous-délégation. Tous les enfants : `provider=codex`, `model=gpt-6-luna`,
`effectiveThinkingOptionId=xhigh`, worktrees séparés, fichier possédé par un
seul agent.

Vague proposée :

| Enfant | Ticket | Scope | Oracle |
|---|---|---|---|
| Luna #1 | L8-02-P2 | `src/bridge-client.ts` + test : conserver `Retry-After` d'un 429 de `recoverByKey` | `npm test`, 34+ tests, build, validate |
| Luna #2 | L5-02-P2 | `integrations/wger.py` + test : valider `WorkoutLogSummary.date` en ISO-8601 | pytest ciblé + suite backend |
| Luna #3 | L1-03-ACCESS | accessibilité/Compose du HOME next-action, ou L9-01 UsageStats | JVM ciblé + suite |
| Parent | R1 | parcours physique, merges, registre, approbations | preuves Pixel |

Le parent reste le **seul opérateur ADB/Pixel**. Les enfants n'ont ni ADB ni
accès aux secrets.

## 8. Points de vigilance

- **Ne pas installer un APK d'un autre SHA sans nouvel accord** : l'accord P1
  porte sur `5956bb6f...`. Toute modification de code Android ultérieure exige
  un rebuild et une nouvelle demande.
- Le texte de `PlanScreen` indique « Plus > Partage backend » ; le chemin réel
  est **Plus > Diagnostic avancé > Configurer le partage backend**. Corriger ce
  texte après R1 (P2), pas pendant le parcours.
- L'OpenClaw Gateway n'est pas redémarré. Le plan revision utilise l'agent
  `atlas` sans outil ; aucune modification de config OpenClaw n'est nécessaire
  pour R1.
- La recherche Bridge reste `PROMPT_INSTRUCTED_UNVERIFIED` ; ne jamais la
  présenter comme vérifiée.
- `.env` et token sont en 0600, jamais affichés. Les preuves QA sont dans
  `$HOME/.local/share/atlas-qa/r1/` (0700).
- Un résultat inconnu (calendrier, wger, OpenClaw) ne justifie jamais un second
  effet aveugle ; réconcilier ou signaler `UNKNOWN`.
- Aucun push, PR, merge distant, ni action destructive sur le téléphone sans
  accord ciblé.

## 9. Sessions Paseo utiles

- Ancien parent : `578b5f80-7ff6-47f2-ba71-b746f3dfbcdc`.
- Enfants terminés : L3-01b `65d84b7e-...`, L8-02 `21d2cdbe-...`,
  L5-01 `998e3ac3-...`, L3-01a `6d98e14c-...`, L1-03 `b5722d8a-...`.
- Relecteurs : L5-01 `f4bfb411-...`, L3-01a `97538bfb-...`,
  L8-02 `f87c50b3-...`.
- Worktrees sous `$HOME/.paseo/worktrees/073527gm/` ; les scouts RO sont
  en `chmod -R a-w`. Ne pas réutiliser un worktree d'un autre agent.

## 10. Fichiers de référence

- `docs/v6-3/REGISTRE_TACHES.md` — registre unique.
- `docs/v6-3/R1_RUNBOOK.md` — commandes Pixel/backend.
- `docs/v6-3/AUTORISATIONS_R1.md` — portes et retours arrière.
- `docs/v6-3/preuves/` — preuves par ticket.
- `docs/v6-3/AUDIT_BRIDGE_PLUGIN.md`, `AUDIT_TRANSPORT_PASEO.md`,
  `AUDIT_CALENDAR_WRITE.md`.
- `docs/v6-3/OPENCLAW_ACTIVATION_PLAN.md` — activation future.
- `docs/DECISIONS_AUTOMATIQUES.md` — décisions réversibles.

**Reprise attendue** : lire ce document, vérifier l'état, continuer le parcours
R1 sans redemander les accords déjà donnés, déléguer les correctifs P2, et
notifier le propriétaire uniquement aux portes restantes (écriture calendrier,
activation OpenClaw, push).
