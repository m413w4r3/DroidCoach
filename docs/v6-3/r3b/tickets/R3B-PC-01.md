# R3B-PC-01 — Supervision et résilience du connecteur

| Clé | Valeur |
|---|---|
| TASK_ID / statut | `R3B-PC-01 / INTEGRATED` (2026-10-28) |
| Vague | A |
| BASE_SHA | `5795d378d38b3e86ce99f1551c41d5044cd4a81c` |
| Profil obligatoire | `codex:gpt-6-luna`, effort `xhigh`, session vérifiée par le parent |
| Worktree | worktree Paseo dédié |
| Dépendances | aucune (le relais local épinglé est fourni par le parent) |

## Objectif observable

Produire une validation dynamique reproductible, en environnement isolé, du
connecteur Node existant : démarrage supervisé, arrêt propre, SIGKILL,
redémarrage, reconnexion au relais, conservation de l'identité et du registre,
absence de fuite de token, indisponibilité puis retour de FastAPI, erreur
explicite, absence de duplication des requêtes incertaines, rollback et
intégrité de l'état.

## Scope d'écriture exclusif

- `integrations/relay/pc/scripts/r3b-*` (nouveaux scripts uniquement)
- `integrations/relay/pc/test/r3b-*` (nouveaux tests uniquement)
- `integrations/relay/pc/systemd/**` (unité existante : ajustements mineurs
  documentés seulement)
- `docs/v6-3/r3b/preuves/pc-01*.txt` (nouveaux fichiers)

Interdit : modifier `integrations/relay/pc/src/**` (propriété de
`R3B-CHAT-01`), `backend/**`, `android/**`, `contracts/**` ; installer ou
activer un service systemd personnel ; activer `linger` ; utiliser le
répertoire d'état personnel `~/.local/state/atlas-relay` ; toucher Tailscale,
FastAPI ou OpenClaw personnels.

## Contrat technique

- Utiliser un répertoire d'état temporaire `0700` par exécution, une identité
  et un registre synthétiques, puis nettoyer.
- Ne jamais lire, copier ou afficher le jeton d'appareil au-delà du fichier
  `0600` synthétique ; les logs capturés sont scannés pour détecter le secret.
- Démarrer le connecteur comme processus enfant réel (`node dist/main.js start`)
  avec un environnement contrôlé.
- Pour les tests isolés, le relais local épinglé peut être joint en
  `ws://127.0.0.1:4402` avec `ATLAS_ALLOW_LOOPBACK_WS=1` ; le test de production
  `wss://all.taildc7886.ts.net:8443` est réservé au parent/vague C.
- Backend de test : serveur HTTP loopback contrôlable qui peut être arrêté,
  redémarré, ralentir, répondre 500, ou accepter puis couper la réponse. Le
  harnais doit aussi accepter une commande de backend réelle pour rejouer le
  scénario FastAPI en vague C.
- Scénarios obligatoires :
  1. démarrage propre, identité et registre créés en modes attendus ;
  2. arrêt `SIGTERM` : code 0, sockets fermés, port libéré ;
  3. `SIGKILL` : redémarrage, même empreinte d'identité, même registre,
     appareil non dupliqué ;
  4. reconnexion au relais après redémarrage du connecteur ;
  5. backend indisponible → erreur explicite `BACKEND_UNAVAILABLE`/503, pas de
     réponse inventée ;
  6. backend restauré → nouvelle requête réussie ;
  7. résultat POST incertain (backend accepte puis coupe la réponse) : le
     connecteur marque l'issue incertaine, ne renvoie pas automatiquement un
     second POST, et bloque le rejeu jusqu'à la fenêtre d'idempotence ;
  8. rejeu strictement identique d'une requête terminée : réponse mémorisée,
     aucun second appel backend ;
  9. rollback : sauvegarde d'état, restauration avec empreinte confirmée,
     intégrité vérifiée ;
  10. absence de token/payload dans stdout/stderr et journaux ;
  11. `systemd-analyze --user verify` sur l'unité (validation statique, pas
      d'installation) ;
  12. `loginctl enable-linger` non activé par le test.
- Les tests doivent être rejouables sans réseau public et sans état personnel.
  Si le relais local n'est pas disponible, le test réel doit être `NOT_RUN`
  explicite, jamais un faux PASS.

## Oracle

```sh
npm run build
node --test integrations/relay/pc/test/r3b-*.test.mjs
# ou script documenté équivalent
```

Le harnais doit produire un résumé machine-lisible avec un statut par scénario,
les durées mesurées, les codes de sortie et les empreintes d'identité. Les
preuves doivent inclure le SHA source et l'environnement.

## Livrables

- Scripts/tests de résilience et de rollback.
- Documentation d'exploitation mise à jour dans le README du proxy/connecteur
  si nécessaire (fichier hors scope interdit : préférer un nouveau
  `docs/v6-3/r3b/preuves/pc-01-notes.txt`).
- `docs/v6-3/r3b/preuves/pc-01*.txt` avec commandes, résultats, limites.
- Commit local ; aucun push.

## Stop conditions

- Modèle/effort incorrect : `BLOCKED`.
- Besoin de modifier `src/**` : arrêter, fournir le diagnostic au parent, ne
  pas modifier le fichier.
- Nécessité d'un service personnel, de `linger`, d'un port public ou d'une
  donnée réelle : `BLOCKED_AUTHORIZATION`.
- Fuite de token ou de payload : `STOP`.
- Rejeu automatique d'un POST incertain ou doublon observé : `STOP`.

## Clôture — 2026-10-28

Statut final : **INTEGRATED**. Suite Node 74/74 sur l'arbre final, harnais de
résilience 14 scénarios, SIGTERM/SIGKILL, reconnexion relais, redémarrage réel
FastAPI (`BACKEND_UNAVAILABLE` puis `ok`), identité/registre conservés, aucun
doublon de POST incertain. Preuves : `preuves/pc-01-validation.txt`,
`preuves/fastapi-restart-public.txt`.
