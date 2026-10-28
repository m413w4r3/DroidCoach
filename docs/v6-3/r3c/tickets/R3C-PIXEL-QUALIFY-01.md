# R3C-PIXEL-QUALIFY-01 — Ticket

| Clé | Valeur |
|---|---|
| Statut | **PARTIAL** — A3/chat/NET/UI PASS, **A4-4 FAIL** |
| Objectif | Construire un couple APK R3-C, l'installer sur Pixel et rejouer A3/A4/chat/réseau sur ce binaire |
| Dépendances | Pixel `PIXEL_SERIAL` (hors dépôt) connecté, sauvegarde vérifiée, mandat d'installation |
| BASE_SHA | à figer après revue |
| Profil | parent, opérateur ADB unique |
| Oracle | A3 Keystore/force-stop/reboot/identité ; A4 DEVICE_REVOKED/4001/REVOKED/effacement/réappairage ; chat relayé ; NET-07 OFFLINE ; NET-13 ; CHAT-05/06 ; UI-04/06/07/08 |
| Stop conditions | Appareil absent, signature différente, migration non maîtrisée, sauvegarde illisible, permission manquante |
| Résultat | APK `365bd84a…` / androidTest `d79031f8…` installé user 0 ; A3 PASS ; A4-5..10 PASS ; A4-4 FAIL (relais 1012) ; chat OpenClaw réel PASS ; NET-07 OFFLINE PASS ; NET-09 PASS ; UI-06/07/08 PASS ; UI-04/TalkBack PARTIAL |
| Preuve | `PREUVES.md` P-PIXEL-* ; `DECISIONS.md` |
| Limite | Aucune donnée R3-B pour user 0 ; A4-4 à trancher ; TalkBack non activé |
