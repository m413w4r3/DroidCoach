# ATLAS V6.3 — R3-B — Risques et limitations

## Risques ouverts

| ID | Risque | Impact | Mitigation / état |
|---|---|---|---|
| R-01 | `/metrics` du relais accessible si le proxy est contourné ou mal configuré | Fuite d'information opérationnelle | proxy filtrant, refus explicite, test NET-15, revue |
| R-02 | Exposition involontaire de FastAPI, OpenClaw, Bridge ou ADB | Compromission locale | proxy à liste blanche fermée, aucun port public autre que 8443, tests négatifs |
| R-03 | Certificat TLS public invalide ou interstitiel | Interception ou refus Android | vérification DNS/TLS par IP publique et depuis Android, pas de fallback `ws://` |
| R-04 | Mauvaise identité Noise acceptée | Impersonation du PC | clé X25519 épinglée, tests négatifs, `minimumMode=protected` |
| R-05 | Rejeu de chat produisant un second appel OpenClaw | Effet ou coût dupliqué | opération à effet, journal, résultat incertain non rejoué, preuve PC-10 |
| R-06 | Révocation non appliquée à une session active | Appareil révoqué encore authentifié | polling registre, `DEVICE_REVOKED` + close 4001, test A4 |
| R-07 | Perte de credentials ou de données Pixel lors d'une installation | Perte utilisateur | sauvegarde app-only vérifiée, pas de `pm clear`, hashes avant/après |
| R-08 | Faux PASS sur réseau (loopback/Wi-Fi confondus avec LTE/5G) | Verdict surévalué | matrice séparée, preuves liées à l'interface réseau réelle |
| R-09 | Doze/arrière-plan non qualifiés | Promesse de temps réel non tenue | aucune promesse instantanée, pas de FCM, tests de reprise seulement |
| R-10 | OpenClaw personnel indisponible ou refusé | Chat réel non qualifié | distinguer mock / isolé / personnel ; `BLOCKED_POLICY` si nécessaire |
| R-11 | wger réel non autorisé ou token invalide | Lecture réelle non qualifiée | lecture seule uniquement, `BLOCKED_AUTH` sinon |
| R-12 | Service systemd non installé pendant la campagne | Démarrage après crash non qualifié sur le PC personnel | validation isolée + unité fournie ; installation après autorisation |
| R-13 | Proxy Node ajoutant une surface de bug | Indisponibilité ou bypass | tests unitaires, intégration relais réel, revue indépendante |
| R-14 | Secrets dans logs, preuves ou commits | Compromission | scans, preuves expurgées, revue, aucun token en argument |
| R-15 | Un tiers connaissant le `serverId` ouvre `/ws?role=server` via le Funnel et remplace la connexion de contrôle du PC (le relais Paseo v2 remplace l'ancien contrôle) | Déni de service de routage temporaire ; aucune lecture Noise possible sans la clé privée | risque résiduel du modèle relais ; reconnexion bornée du connecteur, limites de connexions, serverId non publié ; à réévaluer si le serverId fuite |
| R-16 | Le MagicDNS du PC résout `all.taildc7886.ts.net` vers l'adresse tailnet `100.119.62.63` au lieu de l'ingress public | Le connecteur PC peut emprunter le terminateur Funnel via le tailnet plutôt que l'ingress public | même certificat et même terminateur ; test public supplémentaire avec résolution IP publique ; documenter la résolution réellement utilisée |
| R-17 | Derrière le Funnel, toutes les connexions apparaissent en loopback : le proxy ne peut pas faire de rate-limit par source | Épuisement des 64 connexions ou du budget relais par un tiers | limites de connexions, budget du relais, redémarrage supervisé ; DoS générique non éliminable au niveau du proxy |

## Limitations connues à ce stade

- Aucune haute disponibilité : le PC éteint rend le service indisponible.
- Aucune réception instantanée en arrière-plan ni FCM dans R3-B.
- Le Funnel dépend de Tailscale, de MagicDNS public et de la machine locale.
- Les tests LTE/5G dépendent de l'opérateur, de la couverture et de
  l'autorisation d'utiliser le Pixel physique.
- Un résultat de test isolé ne vaut pas qualification de production.
- Le relais Paseo reste un composant tiers épinglé ; sa mise à jour exige une
  nouvelle revue et une nouvelle preuve.
