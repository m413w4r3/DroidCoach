# V7-06A — Audit lecture seule de l’accès résiduel (Pixel rendu)

Date : 2026-10-31 (UTC)
Base : `55625299c364e1c71eba5924fcfe933af1852a2f`
Périmètre : Core live et registre du connecteur PC. **Aucune modification, aucun ADB,
aucune utilisation du Pixel.** Aucun contenu de projection, token ou identifiant complet
n’est reproduit.

## 1. Constats

### 1.1 Core live (`atlas-core-atlas-core-1`, healthy)

Requête SQLite en lecture seule (`file:/data/atlas.sqlite3?mode=ro`) sur le
`device_ref` dérivé du bearer Android live (`android-<sha256 tronqué>`) :

| Table | Lignes pour ce device_ref |
|---|---|
| `consent_states_v4` | 2 |
| `consent_state_events_v4` | 2 |
| `day_plan_snapshots_v4` | 0 |
| `activity_status_events_v4` | 0 |
| `usage_summaries_v4` | 0 |
| `goal_progress_snapshots_v4` | 0 |
| `training_snapshots_v4` | 0 |

États de consentement résiduels :

- `DAY_PLAN` : `GRANTED`, version 2, `updated_at=2026-10-31T00:23:51Z`.
- `ACTIVITY_STATUS` : `GRANTED`, version 2, `updated_at=2026-10-31T00:23:51Z`.

Un seul `device_ref` existe dans `consent_states_v4`. **Aucune projection personnelle
V4 n’est stockée** pour cet appareil ; les deux consentements V7-05 restent toutefois
accordés côté serveur. Le bearer Android live est dérivé en `device_ref` : tant qu’il
n’est pas révoqué/roté, un client qui le possède peut agir sur ce `device_ref`.

### 1.2 Connecteur PC (`~/.local/state/atlas-relay/devices.json`)

Deux appareils ne sont **pas révoqués** :

- `pixel-v7` (`563a01fa…`), 14 capabilities, dont `v4.consent.state.write`,
  `v4.day_plan.snapshot.write`, `v4.activity_status.write`, `v4.proposal.ack`,
  `v4.coach.chat`.
- `r3c-pixel-final` (`f4315995…`), 6 capabilities V1/V2, dont `coach.chat`.

Les autres entrées du registre sont révoquées.

### 1.3 Journal d’opérations connecteur

Le journal privé ne contient plus les deux entrées V7-05 : leur TTL de 10 minutes est
expiré et le fichier a été réécrit. Une entrée `coach.chat.request` `completed` du
device `pixel-v7` existe avec `expiresAt=2026-10-31T15:50:43Z` (créée à
`15:40:43Z`), preuve que le jeton `pixel-v7` était encore utilisé ce jour. Aucun
contenu de conversation n’a été lu ni reproduit.

## 2. Risque

- Le consentement serveur `DAY_PLAN`/`ACTIVITY_STATUS` reste `GRANTED` v2 pour le
  `device_ref` du bearer Android live.
- Aucune projection personnelle n’est exposée par ce `device_ref` dans le Core live.
- Le bearer Android et deux appareils du connecteur restent valides. Une révocation
  ciblée (connecteur) et/ou une rotation du bearer Core fermeraient l’accès résiduel,
  mais constituent une **modification de service live** : elles exigent une
  autorisation ciblée et une sauvegarde préalable, conformément à `AGENTS.md`.
- L’état local du Pixel (révocations v3 non synchronisées) reste inchangé et ne doit
  pas être manipulé.

## 3. Décision

- **Aucune action live effectuée.**
- Procédure proposée, à autoriser séparément :
  1. sauvegarde privée du registre `devices.json` et du journal ;
  2. révocation ciblée des devices `pixel-v7` et `r3c-pixel-final` par la CLI du
     connecteur (`revoke-device`), sans toucher aux autres appareils ;
  3. vérification lecture seule que le Core ne reçoit plus de requêtes V4 du bearer
     concerné ; la rotation du bearer Core reste une option distincte ;
  4. conservation des preuves hashées, sans secret.
- Le chantier V7-06A continue sur un **environnement isolé** (Core, connecteur,
  relay et appairage synthétiques) sans réutiliser ce bearer ni ces appareils.
