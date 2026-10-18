# 06 — Direction visuelle ATLAS (référence : Olauncher)

## Objectif
Créer une interface Android **principalement textuelle**, calme et très minimaliste, inspirée par la sobriété d’[Olauncher](https://github.com/tanujnotes/Olauncher), **sans copier** son code GPLv3, ses icônes, sa marque ou ses ressources. La filiation visuelle est une inspiration produit, pas une dépendance logicielle.

## Rôles séparés des références
- **Olauncher : référence UX prioritaire** — accueil simple, listes courtes d’apps, hiérarchie très légère, peu de couleurs et de sollicitations.
- **Lawnchair : référence technique** — gestion du rôle HOME, compatibilités Android, expérience launcher, sans exiger un fork.
- **Reef : référence fonctionnelle** — focus/Pomodoro, statistiques, friction, restrictions soumises à audit.
- **Loop : référence fonctionnelle** — habitudes et historique, interop plutôt que reprise GPLv3 au MVP.

## Principes non négociables de la première interface
1. **La prochaine action domine l’écran** : une directive lisible et sa justification en une ligne, pas un tableau de bord chargé.
2. **Navigation courte** : `Concentration`, `Habitudes`, `Bibliothèque`, `Coach` et accès explicite aux `Paramètres` ; raccourcis téléphone/essentiels si launcher HOME.
3. **Hiérarchie textuelle** : typographie Android accessible, contraste suffisant, une seule accentuation visuelle, surface calme ; thème sombre disponible, pas forcément imposé.
4. **Pas de feed** : maximum trois recommandations, jamais un défilement infini. Pas de gamification insistante, badges de culpabilisation ou indicateurs envahissants.
5. **Négociation claire** : `Commencer`, `Discuter`, `Alternative`, `Reporter / pause`. Une pause d'urgence reste trouvable sans IA ni connexion.
6. **Dégradé hors ligne** : conserver la dernière directive et l’indiquer si elle est périmée.
7. **Design original** : ne pas reproduire pixel pour pixel Olauncher et ne pas adopter son nom, son icône ou ses assets.

## Livrables UI exigés sur émulateur
- Un premier écran Compose installable, démarrable comme Activity classique même si l'utilisateur n'a pas encore choisi le rôle HOME.
- Une variante HOME proposée explicitement, jamais imposée.
- Une capture d’écran de l’accueil et une de la session focus prises **sur le vrai AVD** quand il existe, citées dans `docs/VALIDATION_VM.md`.
- Un test manuel/automatisé des actions de directive et de la navigation ; signaler les parcours non testés.

## Décision à reporter
La nécessité éventuelle de reprendre l’implémentation de Lawnchair dépendra des besoins concrets (widgets, récents, gestes, dossiers), **après** la preuve du MVP Compose.
