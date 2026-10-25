# Parcours UI observés — clôture V6.2

Campagne Pixel du 2026-10-25, source `8890046012965f3a3112ff0c419014c74e189d11`, APK SHA-256 `48c7766e9090010692f0eaafc1d0cbaf4b22e51259847f94b642a2d060b33583`. Les captures de campagne Pixel ne sont pas versionnées et ne contiennent aucune donnée personnelle. Les captures versionnées sous `captures/` ont été produites sur `emulator-5554` avec des données synthétiques; leur index est [CAPTURES_UI.md](CAPTURES_UI.md) (commit `a523317`).

## Parcours Pixel réalisés

| Parcours | Résultat observé | Restauration |
|---|---|---|
| Sélection temporaire d'ATLAS comme HOME, ouverture du tiroir et recherche | Tiroir et recherche utilisables; favori épinglé puis retiré | Pixel Launcher remis comme HOME |
| Ouvrir Brave depuis HOME, revenir, puis choisir « Launcher précédent » | Ouverture et retour réussis; le bouton revient au launcher système | Pixel Launcher actif |
| Pause d'urgence | Message « Pause d'urgence active pendant 30 minutes », action « Fin » disponible; pause terminée | Aucun état de pause restant |
| Police système 2× | Contenu défilable; actions atteignables | Échelle remise à 1,0× |
| Rotation paysage | Parcours paysage réussi | Rotation remise à 0° |
| TalkBack | Libellés accessibles repérés sur Urgence, Commencer, Focus, Habitudes et Plus | Service d'accessibilité revenu à Reef uniquement |
| Notifications | Permission refusée puis accordée; publisher 2/2 et routage au tap 6/6 | Permission refusée; fixture d'inbox supprimée |
| Focus lié à un objectif, 25 min | 0/120 avant fin, 25/120 après fin et après relance à froid, annulation sans crédit | État QA synthétique conservé (décision propriétaire) |

## Captures UI versionnées — émulateur uniquement

Le 2026-10-25, l'agent `V62-UI-CAPTURES` a produit **15 PNG réels** en portrait 1344×2992 sur `emulator-5554` (API 37). Aucune capture ne provient du Pixel. Les fichiers sont dans `captures/` et indexés dans [CAPTURES_UI.md](CAPTURES_UI.md) :

- HOME/Focus : `01-focus-actif.png`, `02-focus-pause.png`, `03-tableau.png`, `14-launcher-home.png`, `15-pause-urgence.png`.
- Habitudes/réglages : `04-habitudes.png`, `05-plus.png`, `11-usage-quotidien.png`, `12-diagnostic-avance.png`, `13-a-propos-licences.png`.
- Coach : `06-coach-conversation.png`, `07-coach-conversation-synthetique.png`, `08-mandat-coach.png`, `09-donnees-partagees.png`, `10-journal-effets.png`.

Limites des captures : aucun objectif de démonstration ni effet d'agent n'était enregistré; le bouton Envoyer du Coach reste désactivé sans serveur; le texte synthétique est resté en brouillon. Aucun code, manifeste ou fichier Gradle n'a été modifié.

## Retours et observations

Le propriétaire a répondu : **« aucun irritant bloquant »**. Les observations restent au niveau **P2**, sans correctif dans cette clôture :

1. Le retour vers HOME depuis le tiroir est une étape de navigation moins évidente que l'ouverture d'une app ou le bouton « Launcher précédent ».
2. À police 2×, le contenu est rogné sous la navigation lorsque l'en-tête occupe deux rangées. Le contenu reste défilable et les actions restent atteignables.
3. Sur l'émulateur, un bandeau « Focus démarré » peut persister alors que le Focus est en pause; le libellé de phase de l'écran Focus reste correct. Observation d'affichage, non bloquante.

## État système à la rédaction

HOME est `com.google.android.apps.nexuslauncher`, police 1,0×, rotation 0°, accessibilité Reef uniquement, permission notifications refusée. `stay_on_while_plugged_in` vaut **7**, valeur initiale réelle du pré-vol, jamais modifiée. Le propriétaire a choisi de conserver l'état QA des données ATLAS : l'objectif et la session synthétiques restent présents; la sauvegarde d'origine n'a pas été restaurée. Voir [MATRICE_ANDROID17_PIXEL.md](MATRICE_ANDROID17_PIXEL.md).
