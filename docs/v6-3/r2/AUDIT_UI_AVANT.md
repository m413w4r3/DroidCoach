# ATLAS V6.3 — Audit UI avant refonte (émulateur Android 17)

Environnement : `emulator-5554` (`Pixel_9_Pro_XL_2`, API 37.2, 1344×2992,
480 dpi, police 1.0, thème Sombre, fuseau Europe/Paris, date 2026-10-26).
APK debug construit depuis la base de campagne ; données synthétiques.
Aucun appareil physique.

Captures de référence (hors dépôt, `~/.local/share/atlas-v63-r2/screenshots/pre/`) :

| # | Fichier | État |
|---|---|---|
| 01 | `01-dashboard-before.png` | Tableau de bord, directive locale, 1 habitude |
| 02 | `02-more-before.png` | Plus |
| 03 | `03-plan-empty-before.png` | Plan sans document |
| 04 | `04-plan-zero-activities-before.png` | Plan créé, 0 activité |
| 05 | `05-plan-3-activities-before.png` | Plan 3 activités, haut |
| 06 | `06-plan-3-activities-scrolled-before.png` | Plan 3 activités, bas |
| 07 | `07-habits-1-before.png` | Habitudes, formulaire + 1 entrée |
| 08 | `08-home-launcher-before.png` | HOME ATLAS |
| 09 | `09-theme-settings-before.png` | Réglages, apparence |
| 10 | `10-habits-empty-before.png` | Habitudes vides |
| 11–13 | `11..13-habits-5-before-*.png` | Habitudes, 5 entrées, trois positions |

## 1. Grille de diagnostic — Plan du jour

| Critère | Constat avant | Gravité |
|---|---|---|
| Informations essentielles | Date, nombre d'activités, statut du programme, activités | présentes mais noyées |
| Informations secondaires | Coach, aide, messages | présentes |
| Informations purement techniques | `version N`, `fuseau Europe/Paris`, `stockage privé atomique` dans l'en-tête permanent | **élevée** |
| Actions principales | « Ajouter une activité » ; « Demander au coach » conditionnel | peu hiérarchisées : deux boutons pleine largeur de même poids visuel |
| Actions de gestion | 4 boutons visibles sur **chaque** ligne : Déplacer, Modifier, Statut, Supprimer | **élevée** — 12 boutons pour 3 activités |
| Répétitions | Le couple date/version/fuseau/stockage est répété dans l'en-tête et dans la carte programme | moyenne |
| Défilement excessif | 3 activités dépassent déjà un écran à cause des 4 boutons par ligne | **élevée** |
| Contrôles rapprochés | Deux rangées de deux `TextButton` par activité ; cibles textuelles proches | **élevée** |
| Incohérence de couleur | Statut « Prévue » en gris, type/priorité en gris ; l'accent est surtout utilisé pour l'heure et les boutons | moyenne |
| Texte peu lisible | Aide de bas de page en `bodySmall` sur fond sombre ; paragraphe Coach long | moyenne |
| Erreurs mal distinguées | Aucun état d'erreur visible dans les captures ; le message de succès est une carte neutre | à vérifier après |
| Prochaine activité | Non mise en évidence ; « Lecture » passée et « Marche » à venir ont le même poids | **élevée** |
| État vide | « Journée libre » + paragraphe + deux boutons ; correct mais verbeux | faible |
| Détails à la demande | Notes, identifiants et informations techniques ne sont pas regroupés dans un écran de détail | moyenne |

## 2. Grille de diagnostic — Habitudes

| Critère | Constat avant | Gravité |
|---|---|---|
| Informations essentielles | Nom, état du jour, progression, historique récent | présents dans chaque carte |
| Informations secondaires | Planning, résumé de série, force | présents, parfois redondants |
| Informations purement techniques | « force 4% », « 4 jours sans échéance », libellé du planning | **moyenne** |
| Actions principales | « Valider aujourd'hui » / « Annuler aujourd'hui » | correct, mais mélangé au formulaire |
| Actions de gestion | « Modifier », « Supprimer » par carte | correctes mais visibles partout |
| Formulaire avant la liste | Nom, planning, jours, quota, ancrage, rappel, bouton d'ajout occupent tout le premier écran | **élevée** |
| Répétitions | « Chaque jour » / « Jours sélectionnés » répétés dans chaque carte | faible |
| Défilement | 5 habitudes + formulaire = plusieurs écrans ; l'historique est une ligne texte | **élevée** |
| Contrôles rapprochés | Jours de semaine sur plusieurs rangées de `TextButton` | moyenne |
| Couleurs | Statut validé/non validé principalement porté par le texte `✓/·/–` | correct pour daltonisme, mais peu lisible |
| Texte peu lisible | Résumé de progression très dense sur une ligne (`1/2 échéances · taux 50% · …`) | **moyenne** |
| Jours non planifiés vs manqués | `–` et `·` seulement, sans légende visible | **élevée** |
| Historique | 7 jours inline, 30 jours change toute la liste ; pas de détail par habitude | moyenne |
| État vide | « Aucune habitude pour le moment. » sous le formulaire | moyenne |
| Suppression | « Supprimer » archive en réalité ; le libellé ne le dit pas | **moyenne** |

## 3. Constats transverses

- Les quatre modes de thème existent et sont persistés ; aucune palette
  d'accent n'est proposée.
- Le contraste des palettes existantes est testé en JVM (AA normal).
- Le statut n'est pas uniquement coloré (texte + barre), mais la forme reste
  identique entre états ; un glyphe distinct serait plus robuste.
- Les cibles principales font au moins 48 dp ; les `TextButton` de gestion
  atteignent 48 dp mais sont visuellement denses.
- La police 2× et la rotation restent à qualifier dans la recette R2.
- Aucun statut ne doit dépendre de la couleur seule après refonte.

## 4. Décisions de refonte qui en découlent

1. Plan : en-tête court (date, titre, progression, état de synchronisation si
   nécessaire) ; version/fuseau/stockage dans « Détails ».
2. Plan : liste chronologique compacte, prochaine activité mise en avant,
   actions secondaires dans un menu « Plus » par ligne, action destructive
   distincte.
3. Plan : le différentiel du coach reste exact (création, déplacement,
   modification, suppression, conflit) avant confirmation.
4. Habitudes : trois espaces — liste du jour, détail avec grille d'historique,
   création/édition dédiée. Le formulaire ne précède plus la liste.
5. Habitudes : jours non planifiés distincts des jours manqués (forme + texte,
   pas seulement couleur) ; progression calculée depuis les check-ins réels.
6. Accent : quatre palettes sobres, indépendantes du mode clair/sombre, défaut
   inchangé ; contraste vérifié en JVM ; couleur jamais seule porteuse d'état.

## 5. Évaluation après refonte

À compléter dans `AUDIT_UI_APRES.md` avec les mêmes fixtures et les mêmes
positions de défilement, puis comparaison critère par critère.
