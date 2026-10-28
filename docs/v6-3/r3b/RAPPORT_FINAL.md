# ATLAS V6.3 — R3-B — Rapport final

Date: **2026-10-28**
Branche: `feat/atlas-v6-3-r3b-remote-openclaw`
Base: `5795d378d38b3e86ce99f1551c41d5044cd4a81c`
Code final: `738ef93` (correctifs de revue inclus)
SHA de la revue 4: `4be991de8adc58e27e20beebef816c4728b2b0c3`

## 1. Verdict

**`GO_PREPARED_WITH_LIMITATIONS`**

Le code, les contrats, les builds et les tests automatisés sont prêts. Le
parcours distant complet (Funnel public, Pixel, OpenClaw) a été qualifié
pendant la campagne sur l'APK `b55776a9…`. Le binaire final `05756094…` n'a
pas pu être installé ni re-testé sur le Pixel : l'appareil s'est déconnecté de
l'USB et l'ingress public Funnel est devenu indisponible à cause de la
connectivité Tailscale du PC. Aucun `GO_REMOTE` ni `GO_V63` n'est donc revendiqué
pour le binaire final.

Conditions pour passer à `GO_REMOTE_WITH_LIMITATIONS` / `GO_V63` :
1. reconnecter le Pixel, installer `05756094…` et l'androidTest `5aef1321…`,
   rejouer A3, A4, chat et réseau ;
2. rétablir la connectivité Tailscale, vérifier le TLS public et rejouer
   l'E2E public (les sept contrôles ont réussi via MagicDNS le 2026-10-28) ;
3. vérifier les limites restantes ci-dessous (NET-07, expiration, UI,
   wger, Doze, systemd).

## 2. Livré et intégré

| Domaine | Contenu | État |
|---|---|---|
| Entrée Internet | Proxy filtrant Node 24 `127.0.0.1:4400 -> 4402`, liste blanche `GET /health`, `GET /ready`, upgrade `/ws` v2, refus `/metrics`, corps et autres chemins | INTEGRATED, 18/18 |
| Connecteur PC | Supervision, SIGTERM/SIGKILL, identité/registre, reconnexion relais, FastAPI réel, idempotence | INTEGRATED, Node 77/77 |
| Chat relayé | `coach.chat`, `coach.chat.request`, route fixe `POST /v2/agent/chat`, validation stricte, limites, mandats, timeout 55 s, résultat incertain non rejoué | INTEGRATED |
| Android RELAY | Transport relayé, capacité négociée, provenance, aucun fallback direct, annulation, brouillon/historique, accessibilité de base | INTEGRATED |
| A3 | Keystore réel, force-stop/redémarrage, identité et token chiffré conservés | PASS sur Pixel/APK antérieur |
| A4 | `DEVICE_REVOKED`, close 4001, `REVOKED`, effacement explicite, relance sans appairage, réappairage | PASS sur Pixel/APK antérieur ; assertions renforcées non rejouées sur le binaire final |
| Données Pixel | Sauvegarde app-only, restauration 5/5 hashes, calendrier 12/91, aucun artefact | PASS (dernier contrôle avant déconnexion) |

## 3. Vérifications finales (`738ef93`)

| Vérification | Résultat |
|---|---|
| Backend | 196 passed, 1 skipped |
| Node connecteur | 15 fichiers, 77 tests, 0 échec |
| Proxy filtrant | 18/18 |
| JVM Android | 97 suites, 691 tests, 0 échec |
| Lint | 0 erreur, 71 avertissements |
| Builds | APK principal `05756094…`, androidTest `5aef1321…` |
| E2E final via Funnel/MagicDNS | 7/7 PASS, `agent_status=connected` |
| Proxy + relais redémarrés | `/health` 200, `/ready` 200, `/metrics` 404 |
| Données Pixel | 5/5 hashes, calendriers 12, événements 91 |

Qualification publique initiale (SHA `dfe6da3`) : DNS public, certificat
Let's Encrypt, TLS 1.3, `/health`/`/ready` 200, `/metrics` 404, WSS 101, Noise
`protected`, chat mock et OpenClaw personnel, FastAPI arrêté/relancé — PASS.
Recontrôle public final : **indisponible** (TLS EOF, plan de contrôle Tailscale
injoignable) ; rejeu MagicDNS PASS, sans prétendre qualifier l'ingress public
actuel.

## 4. Revue indépendante

Quatre sessions `codex/gpt-6-luna`, effort `xhigh` effectif, worktrees isolés,
lecture seule :

| Revue | SHA | Verdict | Suite |
|---|---|---|---|
| 1 | `beb8f33` | BLOQUÉ — P1 consentement périmé | `7754005` |
| 2 | `8af78c5` | BLOQUÉ — course contrôle/écriture, opération en vol | `91e7aa6` |
| 3 | `caf92e4` | BLOQUÉ — consentement d'usage non sérialisé, receive/erreur | `738ef93` |
| 4 | `4be991d` | **APPROUVE_AVEC_RESERVES** — aucun P0/P1 | corrections documentaires |

La revue 4 valide le verrou partagé, l'absence de cycle de verrous, l'abort
avant erreur, le test `coach.chat.request` exact et les statuts PARTIAL A4.
Réserve R-22 : couverture de tests de concurrence perfectible.

## 5. Limites explicites

- **R-18 / R-20** : ingress public Funnel indisponible au recontrôle final
  (connectivité Tailscale du PC) ; le rejeu MagicDNS ne prouve pas l'Internet
  public actuel.
- **R-21** : Pixel déconnecté avant l'installation de l'APK final ; aucun PASS
  Pixel pour `05756094`. L'APK installé est `1a877422` ; l'APK testé sur Pixel
  est `b55776a9`.
- **NET-07 PARTIAL** : l'état applicatif observé en coupure est `ERROR`, pas
  `OFFLINE`.
- **A4-4/A4-8 PARTIAL** pour le binaire final : assertions 4001 et alias
  Keystore ajoutées et compilées, non rejouées sur Pixel.
- **A3** : journaux horodatés partiels (R-19).
- **NET-13 PARTIAL** : expiration de session (1 h) et de token non rejouée.
- **CHAT-05/CHAT-06 PARTIAL** : mandat expiré et déconnexion pendant un chat
  en vol couverts JVM/Node, pas de parcours Pixel dédié.
- **UI-04/UI-06/UI-07/UI-08** : saisie manuelle du token, retour HOME, mode
  OFFLINE Pixel, TalkBack/police agrandie non qualifiés.
- Aucun test Doze/H24 ; aucune installation systemd persistante.
- **WGER-01 BLOCKED** : autorisation explicite absente ; aucune lecture wger.
- Le Funnel dépend de Tailscale et du PC ; aucune haute disponibilité.
- Les tests de concurrence ne combinent pas tous les scénarios (R-22).

## 6. Nettoyage et état opérationnel

Proxy, connecteur, FastAPI isolé et conteneur relais arrêtés ; ports 4400/4401/
4402/8100 libres. Identité du connecteur conservée ; sauvegarde d'état dans
`~/atlas-backups/r3b/relay-state-post-campaign` (0700/0600) ; 8/8 appareils
synthétiques révoqués, 0 actif ; tokens et bases de test supprimés. Pixel :
dernière restauration vérifiée 5/5 et 12/91 avant déconnexion. Aucun push,
aucune PR, aucune modification GitHub distante. Voir
`preuves/cleanup-final.txt` et `HANDOFF.md`.

## 7. Conclusion

La chaîne ATLAS Android ↔ Funnel ↔ proxy ↔ relais ↔ connecteur ↔ FastAPI ↔
OpenClaw est construite, testée et revue, avec des garde-fous de consentement
et de révocation renforcés. Le verdict retenu est
**`GO_PREPARED_WITH_LIMITATIONS`** : prêt pour la reprise opérationnelle, pas
encore déclaré `GO_REMOTE`/`GO_V63` pour le binaire final tant que le Pixel et
l'ingress public n'ont pas été revalidés.
