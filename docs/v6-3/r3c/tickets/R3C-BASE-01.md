# R3C-BASE-01 — Ticket

| Clé | Valeur |
|---|---|
| Statut | **ACCEPTED** (preuves) |
| Objectif | Réconcilier Git local/distant, CI, commits R3-B, APK, sauvegardes, services et Tailscale sans modification destructive |
| Dépendances | Aucune |
| BASE_SHA | `eedf91502227297be5d2ff35524e79983ea36f49` |
| Profil | parent Paseo, lecture seule pour le pré-vol |
| Oracle | SHA local = SHA distant ; commits R3-B ancêtres ; CI terminée ; artefacts et sauvegardes hashés ; ports/processus inventoriés ; Funnel inchangé |
| Résultat | Tous les points vérifiés ; CI `40718987949` success ; R3-B ancêtre ; APK R3-B et sauvegardes inventoriés ; Funnel inchangé |
| Preuve | `PREUVES.md` P-BASE-01..04, P-FUNNEL-01 |
| Limite | Aucun Pixel connecté ; aucun push R3-C |
