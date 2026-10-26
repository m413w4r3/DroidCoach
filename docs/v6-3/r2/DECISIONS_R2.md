# ATLAS V6.3 — Décisions automatiques R2

Ces décisions sont réversibles et bornées par la mission R2. Elles complètent
`docs/DECISIONS_AUTOMATIQUES.md` sans modifier les décisions antérieures.

| Date | Décision | Raison |
|---|---|---|
| 2026-10-26 | Branche `feat/atlas-v6-3-r2-emulator-ui` depuis `438616923a88eff814e96347d031506c6ea12f60` ; aucun push ni fusion distante | Mission R2 ; `origin/main` n'est pas modifié. |
| 2026-10-26 | L'AVD autorisé `emulator-5554` est `Pixel_9_Pro_XL_2`, API 37.2, x86_64, sans Play Store, démarré en headless | C'est l'AVD historiquement lancé sur le port 5554 ; API 37 / Android 17 vérifiée. |
| 2026-10-26 | Sauvegarde intégrale des données AVD et de l'application avant essais ; restauration possible hors dépôt | Le contenu préexistant n'est pas attribuable à R2. |
| 2026-10-26 | ATLAS reste l'application d'accueil par défaut de l'émulateur, état préexistant | Aucun changement de launcher n'est nécessaire pour la recette ; l'état final sera vérifié et documenté. |
| 2026-10-26 | Les captures n'utilisent que des fixtures synthétiques (lecture, marche, étirements, appel famille, journal) | Aucune donnée personnelle ; noms et horaires inventés. |
| 2026-10-26 | Les fixtures d'habitudes sont injectées dans le document local après sauvegarde, application arrêtée | Le HOME ATLAS redémarre automatiquement après `force-stop` ; l'injection est faite puis l'app est redémarrée une seconde fois pour relire le document. Sauvegarde et script conservés hors dépôt. |
| 2026-10-26 | Les palettes d'accent sont indépendantes des quatre modes existants ; défaut `ATLAS classique` inchangé | Ajout additif, préférences stockées préservées, aucun remplacement silencieux. |
| 2026-10-26 | La couleur dynamique Android reste optionnelle et explicite ; si elle est retenue, elle ne doit pas écraser AMOLED sans choix | Exigence mission §6.1 ; le mode AMOLED conserve un fond noir pur. |
| 2026-10-26 | Le moteur calendrier est fail-closed : aucune écriture sans calendrier local explicitement sélectionné, permission accordée et lien `CONFIRMED` | Contrats V6.3 §3.4 et audit L4 ; aucune écriture réelle sur calendrier personnel. |
| 2026-10-26 | La permission `WRITE_CALENDAR` ne sera ajoutée qu'au manifeste **debug** pour la qualification émulateur, si un calendrier local inscriptible est disponible ; le build release n'est pas modifié | Réversible, ne revendique aucune capacité release ; la feature flag reste désactivée par défaut. |
| 2026-10-26 | Aucun calendrier local n'existe initialement dans l'émulateur ; le test provider réel est `BLOCKED_ENVIRONMENT` tant qu'un calendrier local inscriptible n'est pas qualifié | Ne jamais fabriquer un PASS avec une simulation. Etar ou une fixture locale ne seront utilisés que si vérifiables. |
| 2026-10-26 | La projection sportive R2 est en lecture seule ; aucune écriture wger, aucun refresh token rotatif consommé | Mission §10 et décisions V6.3 antérieures. |
| 2026-10-26 | L'écran sport utilise une fixture locale déterministe lorsque le backend n'est pas configuré | Mode démonstration sans serveur ; la séance affichée reste une prescription, jamais une performance. |
| 2026-10-26 | Le plugin OpenClaw est revalidé hors activation ; aucune modification de `~/.openclaw/openclaw.json`, aucun redémarrage | Mission §11 ; `BLOCKED_POLICY` persiste pour la délégation planificateur → recherche. |
| 2026-10-26 | La recherche Bridge reste au mieux `PROMPT_INSTRUCTED_UNVERIFIED` | Environnement réel vérifié en R1 ; aucune preuve de recherche web outillée. |
| 2026-10-26 | Les preuves R2 sont expurgées ; les identifiants matériels, comptes, chemins privés et jetons sont masqués dans les nouveaux documents | Mission §2. |
| 2026-10-26 | Les anciennes preuves R1 sont corrigées dans l'arbre courant sans réécriture d'historique Git ; l'exposition historique résiduelle est consignée séparément | Pas de `filter-branch`/rebase sans accord explicite. |
| 2026-10-26 | Les actions UI destructrices (suppression) restent confirmées ; l'archivage d'habitude n'est pas présenté comme une suppression définitive | Mission §7 et §8. |
| 2026-10-26 | Aucun nouveau protocole cryptographique, aucune dépendance Gradle supplémentaire pour l'UI ou les habitudes | Sobriété et réversibilité ; les primitives existantes suffisent. |
