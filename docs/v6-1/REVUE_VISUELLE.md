# Revue visuelle — ATLAS V6.1, lots D–F

**Date :** 2026-10-19  
**Branche :** `feat/atlas-v6-1-stabilisation`  
**Périmètre :** captures après le Lot E, comparaison avec les captures avant, critique visuelle et vérification de provenance. Le Lot F initial n’a pas modifié le code; les corrections ciblées du suivi E2 ci-dessous complètent cette revue.

## Conditions et vérifications

- AVD existant `Pixel_9_Pro_XL_2`, Android 17 / API 37, 1344 × 2992 px en portrait à 480 dpi; 2992 × 1344 px en paysage. Aucun téléphone physique n’a été utilisé. E2 a tourné sur `emulator-5554`; le viewport paysage temporaire a été réinitialisé à la fin.
- Les captures après sont des PNG natifs sans retouche ni redimensionnement. E2 a recapturé 16, 20 et 22 par `screencap -p`, puis les a extraites avec `adb pull`. Les 18 images avant sous `captures/avant/` sont conservées telles quelles.
- Les jeux de données visibles sont synthétiques ou vides. Les applications du tiroir sont celles de l’AVD. Les captures 07, 17 et 21 proviennent de la session OpenClaw décrite ci-dessous. La capture 22 remplacée par E2 utilise une fixture `ProposalV2` locale déterministe, sans contexte local partagé et sans appel à la Gateway.
- Pour les captures initiales, la Gateway OpenClaw locale répondait sur son endpoint de santé; un backend temporaire limité à `127.0.0.1` et une base temporaire ont servi à une génération réelle. La proposition réelle `home.set_directive` reste montrée en état **Proposée** dans [21](captures/21-openclaw-directive-proposed.png) : titre « Lecture calme — 25 minutes », détail « Lis tranquillement un texte pendant 25 minutes, à ton rythme. », action principale `open_coach`. La capture 22 E2 utilise une fixture locale équivalente pour montrer le libellé traduit; un processus Gateway était présent, mais la capture ne l’a pas appelé. Aucune action n’a été acceptée, enregistrée ou exécutée sur HOME. Le contexte partagé était vide. La réponse Coach réelle de [17](captures/17-coach-openclaw-real.png) provient de cette Gateway.
- Le premier essai réel, avant remise à zéro de l’état de démonstration uniquement, a été refusé côté Android pour « Action en période de refroidissement », y compris après correction de l’identifiant du mandat. Le journal antérieur affichait ces refus; le journal présent en [24](captures/24-coach-effect-journal.png) est vide après nettoyage. Le chemin de vérification semble compter des rejets pour le délai d’attente; c’est un défaut possible à confirmer et corriger avant de considérer l’application réelle de directives distante fiable. Le code n’a pas été modifié ici.
- Les dumps d’interface XML ADB utilisés pour les états indisponible/hors ligne et OpenClaw ont été recherchés avec des motifs usuels de jetons (Bearer, `sk-…`, GitHub, Slack, Google API, JWT) : aucun résultat. La réponse API de démonstration a aussi passé cette recherche. Les captures réelles affichent un identifiant de version de contexte `sha256-…`; c’est une empreinte, pas un secret. Les captures ont été relues visuellement; aucun jeton, donnée personnelle ou secret n’est visible.
- Pour E2, `testDebugUnitTest lintDebug assembleDebug` et trois classes instrumentées ciblées ont été exécutés; `AtlasVisualCaptureTest` n’a pas été lancé car l’AVD contenait des données d’application utiles au début du travail. L’AVD a été laissé avec Pixel Launcher comme rôle HOME, `fontScale=1.0`, rotation automatique, en portrait. Aucun `wipe-data` ni `pm clear` n’a été exécuté.

## Inventaire des captures après

| Capture | Avant comparé | Provenance et appréciation |
|---|---|---|
| [01 — HOME sombre](captures/01-home-dark.png) | [01 — HOME sombre](captures/avant/01-home-sombre.png) | Fixture locale. La directive reste prioritaire et l’état vide des favoris est discret. Le bloc directive garde beaucoup de poids visuel; l’état indique correctement que le backend est inconnu. |
| [02 — HOME AMOLED](captures/02-home-amoled.png) | [02 — AMOLED](captures/avant/02-home-amoled.png) | Fixture locale. Le fond noir et les surfaces gris foncé distinguent bien la carte; aucune mesure d’énergie n’a été faite. |
| [03 — HOME, directive](captures/03-home-agent-directive.png) | [03 — directive fixture](captures/avant/03-home-directive-agent-fixture.png) | **Fixture locale**, pas une directive OpenClaw appliquée. L’écran affiche « Lis/avance 10 min ». Le nom de fichier peut faire croire à tort que la directive distante est active; la proposition réelle non appliquée est en 21–22. |
| [04 — HOME, Focus actif](captures/04-home-focus-active.png) | [04 — Focus actif](captures/avant/04-home-focus-actif.png) | Fixture Focus. L’état Focus est maintenant juste sous la directive, ce qui corrige la priorité observée avant. Les deux blocs restent volumineux, mais se lisent dans le format portrait utilisé. |
| [05 — Tiroir](captures/05-launcher-drawer.png) | [05 — tiroir](captures/avant/05-tiroir-applications.png) | Applications système AVD. Liste et actions sont lisibles; le pied fixe réduit l’espace disponible. |
| [06 — Recherche](captures/06-launcher-search.png) | [06 — recherche](captures/avant/06-recherche.png) | Recherche synthétique « chrome ». Le résultat est facile à repérer et le clavier est fermé; les états sans résultat ne sont pas montrés. |
| [07 — Conversation Coach](captures/07-coach-conversation.png) | [07 — conversation](captures/avant/07-coach-conversation.png) | Conversation et proposition déterministes locales, malgré un backend alors joignable. La conversation vient avant la proposition; les réglages avancés ont quitté ce flux. La page reste verticale et nécessite le défilement pour tout parcourir. |
| [08 — Mandat Coach](captures/08-coach-mandate.png) | [12 — mandats](captures/avant/12-coach-mandats.png) | Réglages synthétiques. Destination dédiée, mais densité élevée de cases, durées et règles; certains contrôles continuent sous le pli et le pied de navigation. Difficile de vérifier rapidement l’ensemble des permissions. |
| [09 — Focus proposé](captures/09-focus-proposed.png) | [08 — Pomodoro proposé](captures/avant/08-pomodoro-propose.png) | Fixture locale. Tâche préremplie et modifiable, durée/cycles et bouton Démarrer sont regroupés. Le besoin d’une tâche visible est corrigé. |
| [10 — Focus en cours](captures/10-focus-running.png) | [09 — Pomodoro en cours](captures/avant/09-pomodoro-en-cours.png) | Minuteur synthétique. Phase, temps restant, pause, annulation et fin sont faciles à distinguer. Aucune notification système n’a été validée sur téléphone physique. |
| [11 — Réglages Focus avancés](captures/11-focus-advanced.png) | [10](captures/avant/10-pomodoro-avance.png) et [11](captures/avant/11-pomodoro-avance-suite.png) | Feuille modale dédiée avec pauses, cycles et mode continu; elle sort ces options du parcours principal. La quantité de réglages reste importante mais leurs valeurs sont visibles. |
| [12 — Habitudes](captures/12-habits.png) | [13 — habitudes](captures/avant/13-habitudes.png) | État vide synthétique. La création et la validation sont présentes; plusieurs options sont proches, mais aucune donnée personnelle n’apparaît. |
| [13 — Pause d’urgence active](captures/13-emergency-pause-active.png) | [01 — HOME de référence](captures/avant/01-home-sombre.png) | État synthétique actif. La commande de fin de pause reste visible dans le pied, donc la pause est réversible depuis HOME. |
| [14 — Texte 1,5×](captures/14-home-fontscale-1.5.png) | [14 — avant 1,5×](captures/avant/14-home-fontscale-1.5.png) | AVD portrait. Texte et actions restent lisibles; le contenu secondaire se rapproche davantage du pied fixe. |
| [15 — Texte 2×](captures/15-home-fontscale-2.0.png) | [15 — avant 2×](captures/avant/15-home-fontscale-2.0.png) | AVD portrait. Les libellés restent compréhensibles et le bouton urgence visible. Cela ne valide ni TalkBack, ni les petites largeurs, ni les cibles tactiles réelles. |
| [16 — HOME paysage](captures/16-home-landscape.png) | [16 — paysage](captures/avant/16-home-paysage.png) | Recapture E2 sur AVD en paysage. La carte et son CTA sont au-dessus du pied avec une séparation visible; la pause d’urgence reste distincte et Launcher, Réglages et Coach tiennent sur une rangée compacte. Les cibles de pied sont vérifiées à ≥48 dp en paysage et à fontScale 2.0 par instrumentation. |
| [17 — Réponse OpenClaw](captures/17-coach-openclaw-real.png) | [07 — conversation](captures/avant/07-coach-conversation.png) | Réponse réelle de la Gateway à une demande de démonstration de 25 minutes, sans données locales partagées. La réponse en anglais rompt avec l’interface française; le libellé de source expose une empreinte de contexte sans valeur opérationnelle pour l’utilisateur. |
| [18 — HOME hors ligne](captures/18-home-offline.png) | [01 — HOME sombre](captures/avant/01-home-sombre.png) | État local après retrait de la configuration temporaire : « statut backend inconnu · aucun contact réussi ». La provenance locale est explicite et le chemin principal reste disponible. |
| [19 — Coach indisponible](captures/19-coach-unavailable.png) | [07 — conversation](captures/avant/07-coach-conversation.png) | Adaptateur local seul. « Agent indisponible — fonctions locales actives » est clair; l’envoi est désactivé et le contenu local reste accessible. |
| [20 — Sync et consentement](captures/20-sync-status-after-consent.png) | Nouvel état | Fixture locale sans URL ni jeton : interrupteur Sync actif, catégorie « Réponses du coach » cochée, badge cohérent « Activée · configuration requise »; la file reste locale. Aucune valeur de jeton ni requête réseau n’est montrée. |
| [21 — Proposition OpenClaw](captures/21-openclaw-directive-proposed.png) | [07 — Coach](captures/avant/07-coach-conversation.png) | **Proposition réelle `home.set_directive`**, générée par la Gateway, état « Proposée ». L’interface montre les quatre choix explicites. Le contenu généré n’est visible qu’en ouvrant Modifier. Rien n’a été accepté. |
| [22 — Édition de directive](captures/22-openclaw-directive-edit.png) | [07 — Coach](captures/avant/07-coach-conversation.png) | Fixture `ProposalV2` locale : le champ « Action principale » montre « Ouvrir le Coach » et masque l’identifiant `open_coach`; la valeur contractuelle reste l’identifiant d’origine. Aucun appel Gateway ni enregistrement n’a été effectué pour cette capture. |
| [23 — Données partagées](captures/23-coach-shared-data.png) | [17 — données partagées](captures/avant/17-coach-donnees-partagees.png) | Toutes les catégories sont décochées; l’écran explique le consentement distinct et confirme qu’aucune donnée locale n’est partagée. Présentation claire et sans détail intime. |
| [24 — Journal des effets](captures/24-coach-effect-journal.png) | [18 — journal](captures/avant/18-coach-journal-effets.png) | Journal vide après nettoyage des seules données de démonstration. Son objectif est expliqué; l’état vide est très sobre. Il ne montre pas les refus de cooldown antérieurs, qui ont été effacés avec l’état synthétique. |

Les 18 captures avant originales sont incluses dans le ZIP. Les liens ci-dessus couvrent les paires pertinentes; pour les nouveaux états (pause active, offline, sync, propositions réelles), la comparaison est indicative et ne prétend pas à une parité de fixture.

## Bilan Lot E/E2 et défauts restants

### Changements visibles et confirmés

- HOME met maintenant la directive en premier, réduit l’état vide des favoris et place le Focus actif immédiatement après. Le contenu recommandé n’encombre plus le parcours principal.
- Le raccourci Coach ouvre bien le flux Coach. Conversation/proposition sont séparées des destinations Mandat, Données partagées et Journal.
- Focus met la tâche et les contrôles avant session au premier plan; la personnalisation est dans une feuille modale. Le thème AMOLED conserve un retour tactile statique.
- Les scénarios hors ligne et indisponible restent utilisables avec l’adaptateur local et indiquent la provenance.

### Défauts corrigés en E2

- **P1 paysage :** le scroll HOME est borné à sa zone et le pied compact conserve la pause d’urgence séparée des trois accès secondaires. La capture 16 montre le CTA hors du pied; la classe `HomeLandscapeFooterInstrumentationTest` vérifie aussi le cas paysage à fontScale 2.0 et les cibles ≥48 dp.
- **P1 état Sync ambigu :** le badge distingue désactivé, activé sans catégorie consentie, configuration manquante, contact en cours, backend joignable, observation expirée, hors ligne, authentification requise et erreurs. Il suit l’observation Lot C et sa fraîcheur de cinq minutes. En 20, Sync et la catégorie sont actifs, mais l’absence d’URL explique « Activée · configuration requise ».
- **P2 action technique exposée :** l’éditeur montre un libellé français (`open_coach` → « Ouvrir le Coach », `focus.start` → « Démarrer un focus ») et conserve l’identifiant contractuel à l’enregistrement.

### Défauts encore présents

- **P2 densité :** 08 regroupe beaucoup d’autorisations et de règles dans une seule destination défilable. Des sous-sections ou résumés seraient plus faciles à vérifier.
- **Risque d’application de directive :** des refus avec cooldown ont été observés avant la capture réelle en état « Proposée ». Il faut examiner le calcul de `lastActionAtUtcByType` et confirmer si des actions rejetées doivent réellement démarrer le cooldown. Aucun effet HOME réel n’a été exécuté pendant ce lot.
- Le nom [03-home-agent-directive.png](captures/03-home-agent-directive.png) ne doit pas servir de preuve qu’une proposition distante a été appliquée : il montre une fixture locale. Les captures réelles sont 21–22, dans Coach.

### Simplicité et accessibilité

Le style textuel et les surfaces sobres rendent les actions principales repérables sans illustration décorative. Le pied HOME a été vérifié en paysage à fontScale 1.0 et 2.0; ses quatre actions gardent des cibles d’au moins 48 dp. Les compromis restants sont la densité du mandat et la hauteur des pages Coach. TalkBack, Switch Access, les petits écrans et un appareil physique n’ont pas été vérifiés ici.

## OSS et provenance

Pour le **Lot E**, la vérification de [04-lot-e-ux.md](04-lot-e-ux.md) confirme : aucune nouvelle source OSS adaptée et aucune nouvelle dépendance Gradle; l’interface utilise les composants Compose Material 3 et AndroidX déjà présents. Ce constat ne décrit pas toutes les adaptations historiques du dépôt : Reef (MIT), Olauncher et Loop/uhabits (GPL, versions et fichiers tracés dans [THIRD_PARTY_NOTICES.md](../../THIRD_PARTY_NOTICES.md)) sont des références/reprises antérieures documentées dans [00-diagnostic-et-reemploi.md](00-diagnostic-et-reemploi.md). Lot E n’en ajoute pas.

## Décisions encore attendues

Les choix réversibles listés dans [03-audit-ux.md](03-audit-ux.md) restent à arbitrer :

1. **HOME :** une directive et une carte Focus/proposition au plus, ou garder aussi des suggestions de contenu visibles? Recommandation actuelle : conserver les suggestions dans la bibliothèque.
2. **Titre Focus :** reprendre le titre de directive comme valeur initiale modifiable, ou demander un titre distinct? L’implémentation prend le titre de directive comme défaut modifiable.
3. **Directive distante hors ligne :** garder la dernière directive avec fraîcheur/provenance, ou revenir à la directive locale? L’état présent montre la provenance; il faut valider la règle produit quand l’observation devient périmée.
4. **Pied HOME — résolu en E2 :** conserver la pause d’urgence sur une ligne distincte et les actions Launcher/Réglages/Coach sur une ligne compacte en paysage ou avec texte agrandi; cibles ≥48 dp. Le test de petites largeurs reste à faire.

Avant une validation de distribution, il reste à décider si TalkBack et petit format sont des critères bloquants et à poursuivre l’audit du cooldown des directives. Les trois défauts ciblés ci-dessus sont corrigés.


Archive : [atlas-v6-1-captures-revue.zip](atlas-v6-1-captures-revue.zip).
