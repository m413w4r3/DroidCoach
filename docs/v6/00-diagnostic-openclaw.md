# V6 — Diagnostic OpenClaw (Lot A)

Date : 2026-10-19. Machine Linux de l'utilisateur. Aucune valeur secrète n'est reproduite ici.

## État constaté (commandes réellement lancées)

| Élément | Résultat |
|---|---|
| `openclaw --version` | `OpenClaw 2026.10.5 (1b58cb9)` (nvm Node 24) |
| `openclaw gateway status` | service systemd utilisateur actif, `bind=loopback 127.0.0.1:18789`, auth `token`, connectivité OK, versions CLI = Gateway |
| Avertissement existant | « Service config looks out of date » (PATH du service) — **non modifié** (hors périmètre) |
| Modèle | `openai/gpt-6-luna`, runtime Codex, OAuth OpenAI valide (profil de l'agent `atlas`) |
| Requête réelle | `openclaw agent --agent atlas --message "Réponds uniquement par le mot: OK"` → `OK` (≈4 s, ≈22 k tokens prompt avec tous les outils) |
| Agent d'origine | `atlas`, agent par défaut, workspace partagé `~/.openclaw/workspace`, `tools.profile: full` |

## Constat de sécurité avant durcissement

L'agent `atlas` avec `tools.profile: full` a **réellement exécuté** une commande shell (`bash`, `id`) à la demande d'un prompt.
Une consigne dans le prompt n'est donc pas une barrière : un filtrage de politique d'outils était nécessaire.

## Changements de configuration effectués (minimaux, sauvegarde `openclaw.json` faite hors dépôt)

1. `gateway.http.endpoints.chatCompletions.enabled = true` — désactivé par défaut, requis par le plan V6 (API officielle `/v1/chat/completions`). Gateway non redémarré (« Change will apply without restarting »), liaison loopback inchangée.
2. `agents.entries.atlas.workspace = ~/.openclaw/workspaces/atlas` (workspace dédié, mode 700, contenant uniquement un `AGENTS.md` de rôle).
3. `agents.entries.atlas.tools.deny = ["*"]`.

Un agent distinct `atlas-coach` a été essayé puis supprimé : le store d'auth OAuth est par agent et le profil existant appartient à `atlas`. Sur décision de l'utilisateur (« il est tout neuf, fais la configuration toi-même »), l'agent `atlas` — seul agent de cette installation propre — est l'agent dédié ATLAS. Aucun autre agent n'existe à préserver. Le champ global `agents.defaults.authInheritance` a été touché puis **rétabli** à sa valeur d'origine.

## Preuves après durcissement

- Même prompt (« liste tes outils, exécute `id` ») : `successfulToolNames: []`, l'agent répond que les outils fichiers/commande sont désactivés ; prompt ≈11 k tokens (au lieu de ≈22 k).
- Limite honnête : le harness Codex expose encore quelques outils internes d'orchestration (`functions.exec` enveloppe, `clock`, `request_user_input`) que l'agent dit inertes. Aucun outil n'a été appelé avec succès. À re-tester dans la recette F.
- `GET /v1/models` avec Bearer → 200 (`openclaw`, `openclaw/default`, `openclaw/atlas`) ; sans Bearer → 401.
- `POST /v1/chat/completions` (`model: "openclaw/atlas"`, champ `user` stable) → 200, JSON strict renvoyé par le modèle réel `gpt-6-luna`.

## Risque résiduel documenté

Le jeton Gateway reste un jeton opérateur : il donne accès à n'importe quel agent du Gateway. Seul le backend FastAPI le détient (variable d'environnement / fichier mode 600 hors dépôt) ; jamais l'APK. Ne pas utiliser `/tools/invoke`. Gateway reste sur loopback.
