# R3C-FUNNEL-01 — Ticket

| Clé | Valeur |
|---|---|
| Statut | **PASS public PC / Pixel mobile BLOCKED_DEVICE** |
| Objectif | Rétablir et qualifier l'accès public `wss://all.taildc7886.ts.net:8443` sans modifier Tailscale, DNS, port ou TLS |
| Dépendances | Relais, proxy, FastAPI, connecteur |
| BASE_SHA | `eedf915…` + services de test |
| Profil | parent, diagnostic non destructif |
| Oracle | DNS public indépendant, TLS valide, `/health` 200, `/ready` 200, `/metrics` 404, `/ws` 101, Noise `protected`, auth, `backend.health`, chat, rejeu, refus sans capacité, ports internes fermés |
| Résultat | TLS 1.3 valide via `$FUNNEL_PUBLIC_IP` (hors dépôt) ; health/ready/metrics PASS ; WSS 101 ; E2E 7/7 ; ancien appairage 4/4 ; FastAPI down/up PASS ; source physique hors dépôt ; ports 4400/4401/4402/8100 fermés publiquement |
| Preuve | `PREUVES.md` P-FUNNEL-01..08 |
| Limite | Pas de test depuis le réseau mobile Pixel sans VPN ; OpenClaw réel non rejoué ; dépendance PC/Tailscale |
