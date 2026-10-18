# 03 — Audit open source : état initial et protocole

## Tableau de présélection (état au 18-10-2026)
| Dépôt | Usage ATLAS | Indices vérifiés | Décision provisoire |
|---|---|---|---|
| https://github.com/aload0/Reef | Focus, routine, quota, friction | Kotlin/Compose ; README Pomodoro, blocker, Mindful Launch ; manifest utilise AccessibilityService, UsageStats, exact alarm, overlay ; README licence MIT assortie d'exigences additionnelles | **Auditer** modules ciblés, ne pas fusionner l'ensemble |
| https://github.com/iSoron/uhabits | Habitudes | Kotlin, GPLv3 ; README documente export CSV/SQLite, widgets, validation via Tasker | Application séparée / interop ; habitudes simples natives |
| https://github.com/LawnchairLauncher/lawnchair | Launcher | Base Launcher3, licence Apache pour LICENSE.txt ; branche 16-dev en cours de développement ; base de code volumineuse | Référence, ne pas forker par défaut |
| https://github.com/tanujnotes/Olauncher | Minimalisme | GPLv3, widgets non garantis pour notre interface | Inspiration graphique |
| https://github.com/ReVanced/revanced-manager | Gestion des patches | GPLv3, pas d'API de gestion du visionnage ATLAS confirmée | ReVanced utilisé comme lecteur externe |
| https://gitlab.com/revanced/revanced-patches | Patches ReVanced | Miroir officiel indiqué ; dépôt GitHub inaccessible lors de la vérification | Hors base de code ; voir uniquement pour configuration manuelle |
| https://github.com/ankidroid/Anki-Android | Révisions | GPLv3 | Option ultérieure, lien externe |

### Alertes spécifiques
- Le README de Reef présente un texte MIT complet **et** une obligation d'attribution visible, interdiction de reprendre marque/icône. Aucun `LICENSE` à la racine lors de la consultation. Vérifier portée effective de ces mentions avant réemploi : ne pas donner un feu vert juridique sur la seule base du badge MIT.
- Le rapport d'incident https://github.com/aload0/Reef/issues/91 décrit des sollicitations répétées pouvant gêner l'usage du téléphone. Vérifier antidoublon, watchdog, extinction du blocage en défaut et récupération.
- Le README de Loop indique support Tasker mais ne garantit pas une API de synchronisation bidirectionnelle complète. Documenter exactement le mécanisme et le tester.
- Lawnchair `16-dev` est une branche de développement et son README annonce des différences d'implémentation selon le canal de distribution. Ne pas décider d'un fork sans justification.

## Procédure de l'agent avant tout copier/coller
1. `git ls-remote`, choisir un SHA immuable de chaque candidat ; sauvegarder URL, commit, version, licence, date.
2. Lire README, build, manifest, catalogue d'APIs, structure des modules, tests existants et issues critiques.
3. Évaluer : fonction exacte, taille/complexité, dépendances, accès sensible, stabilité, performance, stratégie d'update, possibilité de bibliothèque/IPC/intent, attribution, compatibilité de licence.
4. Comparer pour chaque fonctionnalité : `réimplémenter simple`, `utiliser comme app externe`, `extraire du code`, `forker`. Fournir la justification et le coût de maintenance.
5. Ne pas copier de licence GPL dans un module non prévu pour suivre toutes ses obligations de redistribution ; si doute, rester sur app séparée.
6. Tester Reef sur **appareil secondaire** avant d'envisager sa technique d'accessibilité ; ne pas le faire dépendre du LLM.
7. Rédiger `docs/AUDIT_EFFECTIF.md` : liens, commits, résultats, décision et éventuellement liste précise des fichiers repris.

## Sources directes consultées
- https://github.com/aload0/Reef/blob/main/README.md
- https://github.com/aload0/Reef/blob/main/Reef/src/main/AndroidManifest.xml
- https://github.com/aload0/Reef/issues/91
- https://github.com/iSoron/uhabits/blob/dev/README.md
- https://github.com/LawnchairLauncher/lawnchair/blob/16-dev/README.md
- https://github.com/LawnchairLauncher/lawnchair/blob/16-dev/LICENSE.txt
- https://github.com/ReVanced/revanced-manager/blob/main/README.md
- https://github.com/ReVanced/where-is-revanced-patches
- https://support.google.com/googleplay/android-developer/answer/10964491?hl=fr
