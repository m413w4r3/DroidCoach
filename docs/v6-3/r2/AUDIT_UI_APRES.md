# ATLAS V6.3 — Audit UI après refonte (émulateur Android 17)

Environnement : `emulator-5554` (`Pixel_9_Pro_XL_2`, API 37.2, 1344×2992,
480 dpi), date 2026-10-26, fuseau Europe/Paris. APK debug de la vague B,
SHA-256 `c6debbc5c17927e997ef776952ca7a0f723d28f107043cb007b78a9592a602ab`
(le SHA final est rappelé dans `preuves/gates-finales.txt`). Données
synthétiques : plan v4 à 3 activités, 5 habitudes actives, 1 archivée.

Captures après (hors dépôt, `$HOME/.local/share/atlas-v63-r2/screenshots/post/`) :

| # | Fichier | État |
|---|---|---|
| 01 | `01-theme-accents.png` | Réglages apparence, 4 modes + 4 accents |
| 02–04 | `02-accent-forest.png`, `03-accent-ocean.png`, `04-accent-slate.png` | Accents |
| 05 | `05-amoled-slate.png` | AMOLED + accent, fond noir |
| 06 | `06-light-forest.png` | Clair + Forêt |
| 07 | `07-theme-persisted-restart.png` | Persistance après redémarrage |
| 08 | `08-theme-landscape.png` | Rotation |
| 09 | `09-theme-font-2x.png` | Police 2× |
| 10–11 | `10-follow-system-dark.png`, `11-follow-system-light.png` | Suivi du système |
| 12 | `12-plan-after-top.png` | Plan du jour, 3 activités |
| 13 | `13-habits-after-list.png` | Mes habitudes, 5 entrées |
| 14 | `14-habit-detail-after.png` | Détail, historique 7 jours |
| 15 | `15-habit-detail-30d-after.png` | Détail, historique 30 jours |
| 16 | `16-habit-editor-after.png` | Création / édition |
| 17 | `17-sport-after.png` | Sport, fixture lecture seule |
| 18–19 | `18-calendar-write-disabled-after.png`, `19-calendar-write-no-local-after.png` | Écriture calendrier désactivée |

## 1. Plan du jour — avant / après

| Critère | Avant | Après |
|---|---|---|
| En-tête | date + version + fuseau + stockage | « Aujourd'hui », date lisible, progression, bouton Détails |
| Action principale | 2 boutons concurrents | une action contextuelle : Demander au coach / Ajouter |
| Activités | grandes cartes identiques | liste chronologique compacte avec repère temporel |
| Prochaine activité | non distinguée | carte mise en avant, libellé « Prochaine », glyphe ○ |
| Passé/en cours/à venir | même poids | glyphes ✓ / ● / ○ + texte explicite |
| Gestion | 4 boutons par ligne | menu « Plus » par ligne, action destructive libellée |
| Détails techniques | permanents | dialogue « Détails » |
| Diff coach | résumé + liste | résumé, version avant→après, nombre exact d'effets, conflits |
| État vide | paragraphe | carte courte + action unique |
| Défilement | 3 activités dépassaient l'écran | 3 activités + coach tiennent dans un écran |

## 2. Habitudes — avant / après

| Critère | Avant | Après |
|---|---|---|
| Structure | formulaire complet puis liste | trois espaces : liste, détail, éditeur |
| Premier écran | formulaire de création | progression du jour + liste + action Ajouter |
| Ligne | grande carte, 2 boutons | ligne compacte, validation, mini-grille, détail |
| Historique | une ligne texte `20✓ 21· …` | grille de cases avec glyphes et libellés accessibles |
| Jours non planifiés | `–` sans légende | `–` + « non planifié » + légende |
| Jours manqués | `·` ambigu | `!` + « dû et manqué » + légende |
| Progression | résumé dense sur une ligne | `x/y jours prévus validés · %`, séries, régularité |
| Détail | inexistant | grille 7/30 jours, séries, rappel, édition, archivage |
| Création | formulaire permanent | dialogue dédié ; quotidien = nom + Enregistrer |
| Archivage | bouton « Supprimer » ambigu | « Archiver », confirmation, historique conservé |
| État vide | sous le formulaire | message + action d'ajout |

## 3. Critères UX mesurables (mission §14)

| # | Critère | Avant | Après | Statut |
|---|---|---|---|---|
| 1 | Action principale visible sans paragraphe | 2 CTA concurrents | 1 CTA contextuel | PASS |
| 2 | Trois informations utiles immédiates | date/version/stockage | date, progression, prochaine activité | PASS |
| 3 | Détails techniques non dominants | permanents | dans « Détails » | PASS |
| 4 | Actions secondaires non toutes visibles | 4 par activité | menu « Plus » | PASS |
| 5 | Confirmation des actions dangereuses | suppression confirmée, archivage ambigu | suppression et archivage confirmés, bouton d'archivage en erreur | PASS |
| 6 | État vide indique quoi faire | oui, verbeux | oui, concis | PASS |
| 7 | Chargement/erreur explicites | succès neutre | erreurs de corruption, sync, permission visibles | PASS |
| 8 | Couleurs cohérentes et accessibles | 3 palettes | 4 accents × 3 bases, contrastes AA | PASS |
| 9 | Statut compréhensible en niveaux de gris | texte + barre | glyphes + texte + formes | PASS |
| 10 | Police 2× | non testé | testé sur apparence ; Plan/Habitudes à rejouer | À REJOUER |
| 11 | Cibles ≥ 48 dp | la plupart | boutons, menus, cases : 48 dp | PASS |
| 12 | Lecteur d'écran | partiel | contentDescriptions sur validations, grille, menus | PASS partiel |
| 13 | Pas de crash Compose / scroll imbriqué | corrigé en R1 | scroll unique rétabli, tests instrumentés | PASS si instrumentés |
| 14 | Thèmes/palettes persistants | thèmes oui | thèmes + accents persistants après redémarrage | PASS |
| 15 | Données existantes lisibles | plan v4, habitudes | plan v4 et 5 habitudes conservés | PASS |

## 4. Points restants

- Vérifier le clavier ouvert dans l'éditeur d'habitude et le dialogue d'activité.
- Rejouer police 2× sur Plan et Habitudes après la capture finale.
- Vérifier la rotation sur Plan/Habitudes et la persistance des dialogues.
- Le libellé « Agent désactivé » observé était un artefact de test, nettoyé
  avant les captures finales ; il ne provient pas de la refonte.
- Les messages de synchronisation sport/calendrier sont explicites ; aucun
  `PASS` réel wger/CalendarContract n'est revendiqué.
- Aucun test sur Pixel physique.
