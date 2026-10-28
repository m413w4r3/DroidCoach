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
| R-18 | Au recontrôle final (2026-10-28 15:15), `tailscaled` ne joignait plus le plan de contrôle Tailscale (`network is unreachable`) ; l'ingress public fermait le TLS | Accès distant public indisponible tant que la connectivité Tailscale du PC n'est pas rétablie | aucune configuration modifiée ; qualification publique antérieure PASS ; rejeu final via MagicDNS PASS ; vérifier `tailscale status`/connectivité avant usage distant |
| R-19 | Les journaux horodatés de certains tests Pixel (A3 persistence, A4 after-clear/repair, chat) n'ont pas tous survécu à la compaction de session | Preuve moins directe pour ces lignes | résultats recoupés par les preuves JVM/Node, les fichiers de restauration et l'observation parentale ; aucun PASS inventé |
| R-20 | Le re-test Pixel de l'APK final (correctifs P1, close 4001, alias Keystore, verrou de dispatch) n'a pas pu être exécuté : l'ingress public Funnel est indisponible depuis Wi-Fi et LTE | Les correctifs ne sont prouvés que par JVM/static sur le binaire final | JVM 691/691, Node 77/77 ; parcours Pixel antérieur PASS sur `b55776a9` ; rejouer A3/A4/chat dès le retour de l'ingress |
| R-21 | Le Pixel physique s'est déconnecté de l'USB avant l'installation de l'APK final `05756094` | Le binaire final n'est pas installé ni exécuté sur le Pixel ; l'APK `1a877422` reste installé | APK final `05756094` et androidTest `5aef1321` conservés dans `android/app/build/outputs/apk/` ; réinstaller et rejouer dès reconnexion |

## Limitations connues à ce stade

- Aucune haute disponibilité : le PC éteint rend le service indisponible.
- Aucune réception instantanée en arrière-plan ni FCM dans R3-B.
- Le Funnel dépend de Tailscale, du plan de contrôle Tailscale, de MagicDNS
  public et de la machine locale. Le rejeu public final a été empêché par une
  perte de connectivité au plan de contrôle (R-18).
- L'état applicatif observé pendant une coupure est `ERROR`, pas `OFFLINE` ;
  NET-07 reste PARTIAL sur le libellé exact.
- Le P1 de consentement périmé est corrigé et couvert JVM, mais le re-test
  Pixel de l'APK final n'a pas pu être exécuté (R-20), et l'APK final n'a pas
  pu être installé avant la déconnexion USB du Pixel (R-21).
- A4-4 (close 4001) et A4-8 (alias Keystore) sont PARTIAL sur le binaire final
  pour la même raison ; les parcours antérieurs restent PASS.
- Le consentement d'usage est désormais muté sous le verrou de dispatch
  (`738ef93`), mais le binaire final n'a pas été exécuté sur le Pixel.
- L'expiration de session (1 h) et de token n'a pas été rejouée sur Pixel ;
  NET-13 reste PARTIAL.
- Mandat révoqué/expiré et déconnexion pendant un chat en vol : couverture
  JVM/Node, pas de parcours Pixel dédié (CHAT-05/CHAT-06 PARTIAL).
- Saisie manuelle du token (UI-04), retour HOME (UI-06), mode OFFLINE Pixel
  (UI-07), TalkBack/police agrandie (UI-08) non qualifiés sur Pixel.
- Aucun test Doze/arrière-plan ni disponibilité H24.
- wger réel non exécuté : autorisation explicite absente (WGER-01 BLOCKED).
- Le service systemd n'a pas été installé ; seule la validation statique est
  fournie (T10 PARTIAL).
- Les tests LTE/5G dépendent de l'opérateur, de la couverture et de
  l'autorisation d'utiliser le Pixel physique.
- Un résultat de test isolé ne vaut pas qualification de production.
- Le relais Paseo reste un composant tiers épinglé ; sa mise à jour exige une
  nouvelle revue et une nouvelle preuve.
