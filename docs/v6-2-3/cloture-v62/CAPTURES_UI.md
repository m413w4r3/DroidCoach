# Captures UI ATLAS — clôture V6.2

Captures réalisées le **2026-10-25** sur l’émulateur Android **API 37** `emulator-5554` (`ro.kernel.qemu=1`, démarrage terminé), avec `dev.atlas.coach`. Les PNG sont des captures brutes en portrait, **1344 × 2992 px**. Elles proviennent de l’émulateur, **pas du Pixel physique**.

Les états observés utilisent les données locales de démonstration déjà présentes, notamment « Lis/avance 10 min » et « Recette locale ». Aucune donnée personnelle réelle n’a été saisie ou capturée. Le seul texte saisi pour illustrer Coach est « DEMO conversation synthetique ATLAS » ; il est resté dans le champ de rédaction et n’a pas été envoyé. Aucun volet de notifications, réglage Android ou application tierce n’a été ouvert.

| Écran | Fichier | Description | État observé | Date |
|---|---|---|---|---|
| Focus — actif | [01-focus-actif.png](captures/01-focus-actif.png) | Session de concentration, durée et cycles | « Lis/avance 10 min », 4 cycles, session en cours | 2026-10-25 |
| Focus — pause | [02-focus-pause.png](captures/02-focus-pause.png) | Même session après appui sur Pause | Session en pause, environ 45 min restantes | 2026-10-25 |
| Tableau de bord | [03-tableau.png](captures/03-tableau.png) | Directive locale, Focus et habitudes du jour | Directive « Lis/avance 10 min », proposition hors ligne, Focus en pause | 2026-10-25 |
| Habitudes | [04-habitudes.png](captures/04-habitudes.png) | Planning et habitude de démonstration | « Recette locale », validée aujourd’hui ; planning quotidien | 2026-10-25 |
| Plus / autres espaces et réglages | [05-plus.png](captures/05-plus.png) | Accès Coach, mandat, données, journal et réglages | Menu Plus avec Usage quotidien, Diagnostic avancé et À propos / Licences | 2026-10-25 |
| Coach local, objectifs et consentement de localisation | [06-coach-conversation.png](captures/06-coach-conversation.png) | Conversation vide, propositions, objectifs et localisation | Mode Local ; aucune proposition ni aucun objectif ; consentement de localisation non coché et aucun résumé disponible | 2026-10-25 |
| Coach — brouillon synthétique | [07-coach-conversation-synthetique.png](captures/07-coach-conversation-synthetique.png) | Champ de conversation avec un texte de démonstration | Brouillon « DEMO conversation synthetique ATLAS » non envoyé ; bouton Envoyer désactivé sans serveur configuré | 2026-10-25 |
| Mandat du Coach | [08-mandat-coach.png](captures/08-mandat-coach.png) | Version du mandat, autonomie et actions autorisées | Mandat v2 `agent-local-v2`, version 1 ; mode « Propositions seulement » | 2026-10-25 |
| Données partagées | [09-donnees-partagees.png](captures/09-donnees-partagees.png) | Catégories partageables avec le Coach | Aucune catégorie autorisée ; toutes les cases sont décochées | 2026-10-25 |
| Journal des effets | [10-journal-effets.png](captures/10-journal-effets.png) | Historique local des décisions et effets | Aucun effet d’agent enregistré | 2026-10-25 |
| Paramètres — Usage quotidien | [11-usage-quotidien.png](captures/11-usage-quotidien.png) | Capacités locales et apparence | Focus, Habitudes, Bibliothèque et Coach local actifs ; routines locales désactivées ; thème sombre | 2026-10-25 |
| Diagnostic avancé | [12-diagnostic-avance.png](captures/12-diagnostic-avance.png) | Observation, démonstration, simulateur et synchronisation | Observation non accordée dans Android ; événements synthétiques uniquement ; synchronisation désactivée en mode local | 2026-10-25 |
| À propos / Licences | [13-a-propos-licences.png](captures/13-a-propos-licences.png) | Licences et attributions visibles dans l’application | GPL-3.0 et notices Reef, Olauncher et Loop affichées | 2026-10-25 |
| Launcher HOME ATLAS | [14-launcher-home.png](captures/14-launcher-home.png) | Activité `LauncherActivity` démarrée directement, sans changer le rôle HOME Android | Directive locale et raccourcis visibles ; Focus indiqué en pause lors de cette capture | 2026-10-25 |
| Pause d’urgence | [15-pause-urgence.png](captures/15-pause-urgence.png) | Launcher pendant la pause d’urgence de 30 minutes | Le contrôle affiche « Fin » ; la pause a été terminée après la capture | 2026-10-25 |

## Vérifications et limites observées

- Les 15 fichiers sont des PNG distincts, de **1344 × 2992 px** et de plus de **100 Ko** chacun.
- Les captures `03` à `13` montrent parfois le bandeau « Focus démarré · 4 cycles au total » alors que l’état Focus observé est en pause. Ce bandeau persistant est visuellement contradictoire avec l’état affiché.
- Le champ de conversation accepte le texte synthétique, mais le contrôle Envoyer reste désactivé en mode local sans serveur configuré. Le message n’a donc pas été soumis.
- Aucun objectif de démonstration n’était enregistré et le journal des effets était vide ; ces états vides ont été conservés plutôt que d’ajouter des données.
- Le Focus déjà actif au lancement a été mis en pause puis repris après les captures. La Pause d’urgence a également été activée pour la capture `15`, puis terminée. À la fin, le Focus est actif et la Pause d’urgence est terminée.
