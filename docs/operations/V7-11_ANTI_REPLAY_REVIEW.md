# V7-11 — Revue anti-rejeu HMAC et topologie Core/Relay

**Statut : NO-GO live. La protection mono-processus fonctionne, mais le rejeu inter-worker et après redémarrage est reproduit.**
**Base auditée :** `8dac699cdb37e2f858a76e4cf7f9fc3bcd613e9c`.
**Preuve détaillée :** [V7-11_A2_RELAY_REPLAY_EVIDENCE.md](evidence/V7-11_A2_RELAY_REPLAY_EVIDENCE.md).

## Topologie observée (lecture seule)

| Composant | Constat |
|---|---|
| Core | `atlas-core-atlas-core-1`, image du 2026-11-04, réseau host, **un seul processus uvicorn** `--host 127.0.0.1 --port 8100`, sans `--workers` ni `--reload`. |
| Relay | `atlas-v7-relay`, image `atlas-paseo-relay:r3a` du 2026-10-26, bridge, `127.0.0.1:4402→4000`, processus Elixir `paseo_relay`. |
| Proxy 4400 | Aucun listener, aucun processus. |
| Connecteur 4401 | Aucun listener, aucun processus. |
| Trajet Android → Relay → connecteur → Core | **Non opérationnel** dans l'état observé : le proxy et le connecteur manquent. |

Le Core live est antérieur à V7-10 (`v4_auth.py`/`v4_assertion.py` absents) ; les contrôles anti-rejeu V7-10 ne sont donc pas encore actifs en production. La topologie devra être revalidée après déploiement du code V7-11 en V7-11B.

## Matrice anti-rejeu (code V7-10)

| Cas | Résultat |
|---|---|
| Assertion valide, premier usage | PASS — acceptée une fois |
| Rejeu identique, même cache | PASS — refusé |
| Rejeu identique, deux caches (2 workers simulés) | **FAIL — accepté par chaque cache** |
| Rejeu après recréation du cache (redémarrage simulé) | **FAIL — accepté si le timestamp reste dans la fenêtre** |
| Timestamp hors ±120 s | PASS — refusé (futur et passé) |
| Même nonce sur deux connexions | PASS — deuxième refusé |
| Cache saturé | PASS sécurité — refus fail-closed (indisponibilité temporaire possible) |
| Erreur I/O du cache | HTTP 500 sans mutation ; pas de normalisation en 401 |
| Rotation de clé avec chevauchement | PASS synthétique — 2 clés max, ancien `key_id` refusé après retrait |
| Test d'intégration backend A/A, B/B, A/B, replay, expiration | PASS |

Le TTL du cache (300 s) dépasse la fenêtre temporelle (±120 s, soit 240 s de validité maximale). En mémoire, le cache protège donc correctement un processus unique ; après redémarrage, il est vide.

## Finding

**P2 confirmé — rejeu inter-worker et après redémarrage.** Le cache `ReplayNonceCache` est un `OrderedDict` local au processus. Deux workers ou un redémarrage Core peuvent accepter la même assertion encore valide. La sévérité P2 correspond au risque déjà documenté dans V7-10 ; elle est bloquante pour un GO live multi-worker.

## Conditions avant tout GO live

Deux options, à trancher en V7-11B :

1. **Cache partagé atomique et durable** : réservation de nonce cross-worker qui survit au redémarrage, avec test de rejeu entre processus, après redémarrage, et refus sur erreur/saturation.
2. **Cache mémoire avec worker unique renforcé** : un seul processus Core vérifié à chaque déploiement, interdiction automatisée de scale/instances parallèles, et blocage vérifiable de toute entrée V4 Relay pendant au moins 240 s après chaque redémarrage. Cette option dépend d'horloges stables et doit être testée à chaque redémarrage.

La présence d'un seul worker aujourd'hui ne suffit pas : Compose ne fixe pas `deploy.replicas`, et une commande `uvicorn --workers N` ou une seconde instance contournerait la protection.

## Verdict

`GO_V7_11_ANTI_REPLAY_TOPOLOGY` : **NO-GO live**. Le gate local peut documenter la topologie mono-worker observée, mais il ne peut pas être levé pour un déploiement tant que l'une des deux conditions ci-dessus n'est pas satisfaite et prouvée.
