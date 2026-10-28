# ATLAS V6.3 — Audit UX/UI avant simplification

- **Date** : 2026-10-28
- **Base Git vérifiée** : `566668ac9cd0778f43d7f4d90a93c6a45645935c` (`origin/main`)
- **Branche de travail** : `feat/atlas-v6-3-ux-daily-use`
- **Appareil de recette** : Pixel 9 Pro XL physique, Android 17 / API 37, `dev.atlas.coach` 0.1.0
- **Rôle HOME au pré-vol** : `com.google.android.apps.nexuslauncher` — **aucun changement de rôle HOME** dans cette passe.
- **Captures** : `docs/captures/v6-3-r4/avant/` et `docs/captures/v6-3-r4/apres/` (expurgées).

## 1. Méthode

1. Lecture du code réellement composé par `AtlasApp`, `AtlasScreenFrame`, `HomeScreen`, `LauncherHomeScreen`, `AgentCoachScreen`, `PlanScreen`, `RelaySettingsScreen` et `SyncSettingsScreen`.
2. Parcours de l'APK installé sur le Pixel physique, dump UIAutomator et captures d'écran.
3. Comparaison avec les priorités utilisateur : ouvrir ATLAS, voir la journée, parler au coach, lancer une action.
4. Aucun test instrumenté destructif n'a été lancé sur le Pixel personnel : les tests existants effacent `atlas_local_state` via `targetContext` et ne sont pas admissibles sans sauvegarde et accord ciblé.

## 2. Constats par écran

### 2.1 Accueil `HomeScreen` (Tableau de bord)

| Constat | Effet | Décision |
|---|---|---|
| Deux blocs concurrents : « La prochaine étape » (directive) puis « Prochaine action » (plan). | L'utilisateur ne sait pas quelle information est la priorité du jour. | Fusionner en un seul hero : **prochaine activité planifiée** si elle existe, sinon proposition du coach, sinon invitation à planifier. |
| Aucune heure ni contexte temporel. | Impossible de savoir « où on en est » en ouvrant l'app. | Ajouter date + heure locale discrètes en tête. |
| Aucun accès direct au Coach ni au Planning. | Les deux parcours prioritaires sont à 2–3 taps (Plus → Coach / Plus → Plan du jour). | Onglets bas `Accueil · Plan · Coach · Focus · Plus` + actions rapides sur l'accueil. |
| « Proposition locale · disponible hors ligne » et « Mandat enregistré · 30. » occupent une place visuelle forte. | Bruit technique permanent. | Retirer le statut technique du hero ; conserver un indicateur hors ligne discret. Rendre les notices transitoires fermables. |
| Section Habitudes vide affichée avec un paragraphe. | Densité inutile. | Masquer la section si aucune habitude active ; afficher une liste compacte cochable sinon. |
| Focus est un bloc séparé avec un bouton « Voir l'état du focus ». | Action principale peu évidente. | Carte compacte « Focus » avec démarrage/reprise direct. |

### 2.2 Coach `AgentCoachScreen`

| Constat | Effet | Décision |
|---|---|---|
| La conversation est noyée entre le statut technique, les objectifs, la localisation, l'aperçu JSON du contexte, les tâches de fond et quatre boutons de réglages. | Écran très long, la conversation perd son statut de parcours principal. | Écran Coach = conversation + propositions + saisie. Déplacer objectifs, localisation, contexte, tâches de fond et liens vers un écran **Contexte du coach**. |
| Statut « OpenClaw via PC · relais connecté » en 12 sp gris. | L'état de connexion est peu lisible et mélange humain/technique. | Bandeau d'état humain : Connecté / Connexion en cours / Hors ligne / Appairage nécessaire / Accès révoqué / Erreur. Détail technique réservé à la configuration avancée. |
| « Provenance : OpenClaw via PC » et « Catégories : … » sous chaque réponse. | Répétition, bruit. | Une puce discrète « OpenClaw » ou « Local » par réponse ; catégories et provenance détaillée dans le contexte. |
| Le champ de saisie est correct mais sans action clavier « Envoyer ». | Un tap supplémentaire. | Ajouter `ImeAction.Send` avec envoi direct, brouillon conservé. |
| Les propositions sont sous la conversation, puis suivies de tous les panneaux d'intégration. | La proposition est correctement séparée mais éloignée visuellement de l'action. | Placer la proposition immédiatement après la conversation, dans une carte distincte avec confirmation explicite. |
| Les actions de gestion (Mandat, Données partagées, Journal des effets) sont quatre boutons pleine largeur. | Encombrement. | Regrouper dans **Contexte du coach**, avec une ligne compacte vers l'écran. |

### 2.3 Planning `PlanScreen`

| Constat | Effet | Décision |
|---|---|---|
| Titre de barre « Plan du jour » + titre interne « Aujourd'hui » + date. | Triple répétition. | Barre « Planning » ; en-tête `Aujourd'hui`/`Demain` + date + progression. |
| Un seul bouton primaire dont le libellé change (« Demander au coach » ou « Ajouter une activité »). | Le comportement est imprévisible. | Deux actions distinctes : **Ajouter** (secondaire) et **Demander au coach** (primaire si configuré). |
| Sections Coach et Calendrier avec paragraphes techniques en bas de page. | Sature l'écran quotidien. | Regrouper sous **Options** repliables ; les confirmations et erreurs restent visibles. |
| Phrase finale sur les modifications locales et le calendrier. | Répète ce que l'interface montre. | Déplacer dans **Options** / détails. |
| Aucune vue Demain. | Impossible de préparer le lendemain, alors que c'est une priorité explicite. | Ajouter un sélecteur `Aujourd'hui / Demain` adossé à un `PlanController` de date, sans toucher au plan du jour utilisé par l'accueil. |
| La prochaine activité est mise en évidence dans la liste mais pas en hero. | Peu visible après ouverture. | Carte hero « Prochaine activité » en tête, liste ordonnée ensuite. |

### 2.4 Connexion relayée `RelaySettingsScreen`

| Constat | Effet | Décision |
|---|---|---|
| URL `wss://…`, `serverId`, clé X25519 et UUID apparaissent dans l'écran principal. | Détails de diagnostic exposés, contraire à la demande. | Section **Avancé** repliée pour ces champs. Écran principal = état humain, PC appairé, actions. |
| Paragraphe d'introduction long (relais, FastAPI, VPN). | Explication avant l'état. | Une ligne de contexte maximum ; le détail passe en aide/avancé. |
| L'état est présent mais entouré de champs techniques. | La lecture « suis-je connecté ? » est lente. | Carte d'état en tête avec libellé humain, dernière connexion, action principale. |
| Les erreurs brutes sont affichées telles quelles. | Peut être technique, mais ne doit jamais être masquée. | Message humain + « Voir le détail » pour l'erreur brute, TLS/révocation/consentement toujours visibles. |

### 2.5 Partage backend `SyncSettingsScreen`

| Constat | Effet | Décision |
|---|---|---|
| Long paragraphe d'introduction, puis statut, puis cases, puis URL/jeton, puis six boutons. | Écran de réglage dense. | Statut humain en tête, catégories, puis **Configuration avancée** repliée. |
| Les champs URL/jeton et les actions de purge sont mélangés aux catégories. | Risque de mauvaise manipulation. | Séparer consentement (visible) et configuration technique (avancé). |
| Les erreurs TLS/401 sont déjà affichées dans le statut. | À préserver. | Conserver l'erreur visible et ajouter un détail repliable. |

### 2.6 Launcher HOME `LauncherHomeScreen` (rôle HOME actif sur le Pixel)

Le propriétaire a explicitement activé ATLAS comme application d'accueil pendant la passe. L'écran réel a été capturé (`docs/captures/v6-3-r4/avant/16-launcher.png`) et le tiroir d'applications n'est pas publié (noms d'applications personnelles).

| Constat | Effet | Décision |
|---|---|---|
| La carte « Prochaine action » affiche « Aucune action planifiée » mais n'est pas actionnable. | L'utilisateur voit le manque sans pouvoir corriger. | Rendre la carte cliquable vers le planning ; bouton « Planifier ma journée » si vide, « Voir le planning » sinon. |
| Aucun accès direct au planning dans le pied de page (`Apps · Coach · Pause · Plus`). | Le planning n'est pas un parcours launcher évident. | Ajouter `Plan` au pied de page, sans retirer `Pause` ni `Coach`. |
| « APPLICATIONS » suivi de « Aucun favori. » occupe une zone morte. | Écran vide en bas. | Remplacer par une action « Choisir mes applications » qui ouvre le tiroir. |
| La directive « AUJOURD'HUI » et la carte « Prochaine action » se répètent. | Deux blocs pour la même journée. | Garder la prochaine action en hero, la directive en bloc secondaire compact. |
| Le tiroir affiche « Glissez vers le bas pour revenir à l'accueil. » | Explication utile mais verbeuse. | Réduire à « Glisser vers le bas pour fermer » ou icône/geste documenté. |
| Le launcher est déjà sobre, textuel, avec horloge et date. | Base saine. | Ne pas refondre ; préserver les comportements HOME, favoris, recherche, retour au launcher précédent et pause d'urgence. |
| **P0 physique constaté** : le raccourci `Coach` du pied de page lance `MainActivity` avec `FLAG_ACTIVITY_NEW_TASK` ; `LauncherActivity` reste au-dessus, donc le tableau de bord est inaccessible depuis HOME. | Le parcours prioritaire « ouvrir le coach » est cassé quand ATLAS est le launcher. | Remplacer par `FLAG_ACTIVITY_REORDER_TO_FRONT` + `FLAG_ACTIVITY_SINGLE_TOP` (ou `CLEAR_TOP`), sans `NEW_TASK` ; vérifier `Coach` et les autres raccourcis sur le Pixel. |

### 2.7 Paramètres `SettingsScreen`, Sport, Bibliothèque, Mémoire

| Écran | Constat | Décision |
|---|---|---|
| Paramètres | Paragraphe « Choisissez les fonctions utiles… » et long texte sur le rôle HOME. | Réduire à une phrase ; le texte HOME reste sous la section, mais plus court. |
| Sport | Lignes « Source : wger en lecture · synchronisation indisponible », « dernière synchronisation », double bandeau d'erreur. | État humain d'abord ; source/sync sous « Détails ». |
| Bibliothèque | Paragraphe `ACTION_VIEW` en tête. | Descendre en note secondaire ; garder l'action d'ajout. |
| Mémoire | Paragraphe d'introduction long. | Réduire à une phrase. |
| Habitudes | Bouton « Ajouter » dupliqué quand la liste est vide. | Un seul bouton. |
| Focus | Déjà simple et lisible. | Ne pas refondre ; vérifier 48 dp et police 2×. |
| Diagnostic calendrier | Explications de consentement nécessaires. | Conserver ; ne pas masquer les permissions/refus. |

## 3. Navigation

### Actuelle

```
Tableau · Focus · Habitudes · Plus
                              ├─ Plan du jour
                              ├─ Sport
                              ├─ Diagnostic calendrier
                              ├─ Bibliothèque
                              ├─ Coach
                              ├─ Mandat du Coach
                              ├─ Données partagées
                              ├─ Journal des effets
                              ├─ Mémoire locale
                              ├─ Usage quotidien (Paramètres)
                              ├─ Usage global
                              └─ Diagnostic avancé
                                   └─ Partage backend
                                        └─ Connexion relayée
```

### Cible

```
Accueil · Plan · Coach · Focus · Plus
```

- **Accueil** : prochaine activité, actions rapides (Coach, Plan, Focus), habitudes du jour, directive compacte.
- **Plan** : aujourd'hui/demain, prochaine activité, liste, ajout, révision coach, options calendrier.
- **Coach** : conversation, proposition, saisie ; **Contexte du coach** pour objectifs/localisation/confidentialité.
- **Focus** : conservé tel quel.
- **Plus** : Habitudes, Bibliothèque, Sport, Mémoire, Connexion, Paramètres, Diagnostics.
- **Launcher HOME** : pied de page `Apps · Plan · Coach · Pause · Plus`, carte prochaine action actionnable, favoris conservés. Le rôle HOME a été activé par le propriétaire ; aucun retour arrière ne sera effectué sans demande explicite.

## 4. Éléments à supprimer, réduire, déplacer ou regrouper

### Supprimer de la surface principale

- « Proposition locale · disponible hors ligne » comme statut permanent du hero.
- « Mandat enregistré · 30. » et notices transitoires non fermables.
- Paragraphes répétitifs : « Choisissez les fonctions utiles… », « Le mode relayé joint… », « Les modifications du programme restent locales… ».
- « Provenance : … » et « Catégories : … » sous chaque message du coach.
- URL de relais, `serverId`, clé X25519, UUID et jeton dans la vue principale.

### Réduire

- Statut de connexion : une ligne + détail repliable.
- Explications de sécurité : une phrase sur l'écran, détail dans la confirmation ou l'aide.
- Sections vides : masquer plutôt qu'expliquer.
- Boutons de gestion du coach : une ligne compacte vers **Contexte du coach**.

### Déplacer

- Objectifs, localisation, contexte du prochain envoi, tâches de fond → **Contexte du coach**.
- Coach/Calendrier du planning → **Options** repliables.
- Configuration backend et identité relayée → **Avancé**.
- Diagnostics techniques → **Plus → Diagnostic avancé**.

### Regrouper

- Date/heure/prochaine activité/actions rapides dans un seul bloc d'accueil.
- Statut + dernière connexion + action Connecter/Déconnecter dans une seule carte.
- Consentements de synchronisation dans une liste unique.

## 5. Parcours prioritaires

1. **Ouvrir → parler au coach**
   - Accueil : bouton « Parler au coach » visible sans défiler.
   - Coach : champ de saisie visible, brouillon conservé, envoi clavier, état de réponse clair.
   - Proposition : carte séparée, action explicite, aucun effet automatique.
2. **Ouvrir → voir le planning**
   - Accueil : bouton « Voir le planning » + carte prochaine activité.
   - Plan : hero prochaine activité, liste ordonnée, ajout/déplacement/statut.
   - Demain : sélecteur, création et édition sans écraser aujourd'hui.
3. **Ouvrir → agir maintenant**
   - Focus : démarrage/reprise en un tap depuis l'accueil.
   - Habitudes : cochage direct depuis l'accueil.
4. **Hors ligne**
   - Accueil/Plan/Coach restent lisibles ; le mode local est indiqué sans jargon.
   - Pause d'urgence toujours accessible dans la barre supérieure.
5. **Connexion**
   - État humain immédiat ; erreur TLS/révocation/consentement jamais masquée ; détails dans **Avancé**.

## 6. Risques de régression

| Risque | Mitigation |
|---|---|
| Tests instrumentés dépendants des libellés d'onglets (`Ouvrir Tableau`, `Ouvrir Focus`, etc.). | Mettre à jour les tests et conserver les `testTag` existants utiles. |
| Tests du Coach cherchant « Objectifs » ou « Localisation » sur l'écran principal. | Les déplacer vers le nouvel écran **Contexte du coach** et adapter les tests. |
| Perte d'accès à une fonction existante. | Chaque fonction déplacée reste accessible par un chemin explicite ; revue de non-régression. |
| Demain écrase aujourd'hui. | Controller de date séparé, même répertoire, noms de fichiers par date ; tests JVM d'isolation. |
| Données personnelles dans les captures. | Captures expurgées, bulle Signal masquée, aucune conversation réelle publiée. |
| Changement de rôle HOME. | Interdit dans cette passe ; Pixel Launcher conservé. |
| Police 2× / rotation. | Trois onglets max par ligne si nécessaire ; vérification sur Pixel et tests de mise en page. |
| TalkBack. | Conserver les `contentDescription`, rôles d'onglet et tailles 48 dp. |

## 7. Limites assumées

- La recette automatisée instrumentée destructive n'est pas lancée sur le Pixel personnel ; elle sera tentée sur l'AVD API 37 si elle démarre, sinon `NOT_RUN`.
- Les captures « avant » contiennent une bulle de conversation Signal ; elles seront expurgées avant commit.
- Le rôle HOME n'est ni demandé ni modifié.
- Aucun secret, jeton, identifiant de serveur ou donnée personnelle ne sera publié.
- Une éventuelle capture d'écran de la vraie conversation sera remplacée par un état synthétique ou masquée.

## 8. Critères d'acceptation de la passe

- [ ] Accueil : date/heure, prochaine activité, Coach et Plan accessibles sans défiler.
- [ ] Coach : conversation + saisie + proposition ; aucun panneau technique dans le parcours principal.
- [ ] Plan : prochaine activité en hero, actions distinctes, aujourd'hui/demain, options repliées.
- [ ] Connexion : état humain en tête, détails techniques repliés, erreurs critiques visibles.
- [ ] Aucune fonctionnalité supprimée.
- [ ] JVM, lint et builds PASS sur le SHA final.
- [ ] Parcours manuel Pixel PASS ou limité documenté.
- [ ] Revue indépendante en lecture seule sans P0/P1.
