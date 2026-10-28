# R3C-PIXEL-QUALIFY-01 — Ticket

| Clé | Valeur |
|---|---|
| Statut | **BLOCKED_DEVICE** |
| Objectif | Construire un couple APK R3-C, l'installer sur Pixel et rejouer A3/A4/chat/réseau sur ce binaire |
| Dépendances | Pixel `PIXEL_SERIAL` (hors dépôt) connecté, sauvegarde vérifiée, mandat d'installation |
| BASE_SHA | à figer après revue |
| Profil | parent, opérateur ADB unique |
| Oracle | A3 Keystore/force-stop/reboot/identité ; A4 DEVICE_REVOKED/4001/REVOKED/effacement/réappairage ; chat relayé ; NET-07 OFFLINE ; NET-13 ; CHAT-05/06 ; UI-04/06/07/08 |
| Stop conditions | Appareil absent, signature différente, migration non maîtrisée, sauvegarde illisible, permission manquante |
| Résultat | Aucun test exécuté : aucun appareil ADB au pré-vol, Tailscale `offline` depuis 23 jours |
| Preuve | `PREUVES.md` P-BASE-04, P-APK-R3C-01 |
| Limite | Aucun PASS Pixel ne peut être revendiqué ; le runbook `RUNBOOK_PIXEL_FINAL.md` est prêt |
