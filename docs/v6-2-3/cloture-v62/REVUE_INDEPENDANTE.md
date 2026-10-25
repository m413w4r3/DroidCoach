# Revue indépendante finale — V6.2

## Cible et méthode

- Base : `8890046012965f3a3112ff0c419014c74e189d11`.
- Code examiné par les reviewers : `1251009ef47a75da897c8d1efce4862ff4e6d82a` (`4aff91a` + `1251009`).
- HEAD documentaire examiné : `46a189559effc9b1122603f840ed572748a782f6`.
- Reviewers : deux sessions GPT-6 Luna XHigh, mode auto-review, worktrees isolés en lecture seule (`chmod -R a-w`). Aucun accès ADB, Pixel, réseau, build ou test par les reviewers.
- Le présent document synthétise leurs rapports; les rapports bruts sont conservés hors dépôt.

## Revue fonctionnelle

**Verdict : NO_GO sur `1251009`.** Aucun défaut P0/P1 fonctionnel confirmé. Les P2 suivants empêchent toutefois de déclarer la clôture immédiatement :

- absence d'oracle explicite pour une durée totale exactement égale à `maxMinutesPerSession` (le code accepte par comparaison stricte, le test manque);
- preuves CI/AVD finales déclarées mais artefacts versionnés encore rattachés au SHA de base `8890046…` (321 tests, APK `af2699e…`) plutôt qu'au SHA final `1251009` (328 tests, APK `80b1d004…`); les résultats finaux existent mais ne sont pas encore versés dans le paquet;
- rapport et registre indiquant encore la revue finale `PENDING` à la date de la revue.

Le reviewer confirme que les PASS Pixel historiques (`8890046…`) ne sont pas rejoués sur le SHA final, conformément à la décision du propriétaire; cette limite est acceptable si elle est explicitement indiquée. Le reviewer confirme aussi que localisation/mobilité sont désactivées par défaut (`CAP_LOCATION=false`, `CAP_MOBILITY=false`) et que l'issue #1 doit rester ouverte.

## Revue sécurité

**Verdict : NO_GO sur `1251009`.** Le P1 historique (bornes Focus insuffisantes) est partiellement corrigé, mais deux P1 restent :

1. **P1 — durée réelle supérieure à la durée vérifiée.** `AgentContracts.kt:497` ne compte que `focus_minutes * cycles + break_minutes * (cycles - 1)`. Or `AtlasController.startAgentFocusTransaction` construit un `FocusPlan` avec une pause longue de 15 min toutes les 4 cycles (`longBreakMinutes=15`, `longBreakEveryCycles=min(4, cycles)`), insérée par `FocusEngine`. Reproduction : max 90 min, plan 14 min + pause 5 min + 5 cycles, fenêtre finissant à 09:30 : le vérificateur calcule 90 min et accepte; l'exécution réelle dure 100 min et finit à 09:40.
2. **P1 — fin de fenêtre décalée au lendemain lors du retour DST.** `AgentContracts.kt:536` résout la borne avec `atZone` puis compare des instants. À l'heure répétée, l'heure d'été/hiver peut faire décaler la borne au lendemain et accepter une fin hors fenêtre. Reproduction : `Europe/Paris`, fenêtre 00:00–02:30, plan `2026-10-25T01:15:00Z` (02:15 au second passage), 30 min : fin locale 02:45, acceptée à tort.

P2 sécurité :

- **P2 — pull tardif après révocation :** le callback d'échec de `pullAgentInbox` ne refait pas le contrôle de génération/autorisation et peut persister une observation obsolète (`AtlasController.kt`).
- **P2 — URL OpenClaw configurable :** `OPENCLAW_BASE_URL` accepte un schéma/hôte arbitraire; le défaut loopback limite le risque, mais une surcharge peut envoyer le Bearer et le contexte vers un hôte distant HTTP.
- **P2 — chemins locaux :** certains documents de preuve versionnés contiennent des chemins d'environnement locaux; à expurger avant diffusion externe.

Le reviewer confirme : pas de P0, pas de secret ni de numéro de série Pixel dans les pièces examinées; `allowBackup=false`, `usesCleartextTraffic=false`, aucun `AccessibilityService`; localisation/mobilité désactivées par défaut.

## Corrections des P1

Trois vagues de correction ont été livrées :

- `4aff91a` et `1251009` : durée totale, fin de fenêtre date/heure, traversée de minuit;
- `b13b3fb`/`d718540` : pauses longues de 15 min toutes les 4 cycles, fin de fenêtre en heure locale et DST, test de borne exacte;
- `18484e2`/`f21fa8e` : pauses courtes portées à au moins 1 minute, mode `continuous`, helpers partagés de durée/fenêtre, et revalidation complète de la carte persistée au démarrage (`focus.start`) avant toute mutation.
- `1a6f761`/`edbb3f5` : refus explicite des cartes agent `continuous`, durée continue marquée non bornée, tests `focus.propose`/`focus.plan` refusant le paramètre.

Historique des re-revues :

- `b13b3fb` : **NO_GO** — P1 `break_minutes=0` sous-compté (deux reviewers) et P1 `focus.start` ne revalidant pas la carte persistée (reviewer sécurité); corrigés par `18484e2`.
- `18484e2` : **NO_GO** — P1 `continuous` non bornable (deux reviewers); corrigé par `1a6f761`.
- `1a6f761` : **GO_WITH_LIMITATIONS** pour les deux reviewers, aucun P0/P1 restant.

## Verdict final

**GO_WITH_LIMITATIONS — aucun P0/P1 de code.** Le P1 de durée non bornée est corrigé : refus explicite des cartes agent `continuous` avant le moteur, helper `Long.MAX_VALUE` fail-closed, tests des cas finis et refus du paramètre. Les reviewers ont lu les preuves `f3ee8a7` : JVM 339/339, lint 0 erreur/47 avertissements, APK `dc1c3e00…`, classes agent/focus ciblées 15/15 PASS, OpenClaw Android 13,817 s. Après redémarrage à froid, la suite AVD est 77 PASS + retest font scale = **78/78 effectifs**.

Limites acceptées explicitement, non présentées comme PASS :

- Pixel non retesté sur le SHA final (décision propriétaire); résultats Pixel historiques sur `8890046…`.
- Localisation/mobilité non validées et désactivées par défaut; issue #1 ouverte.
- Restauration Pixel non exécutée (état QA conservé).
- Révocation pendant une requête OpenClaw réelle non rejouée.
- P2 URL OpenClaw configurable et callback de pull tardif.
- Absence de test d'intégration direct de la transaction privée `startAgentFocusTransaction`; helper testé et ordre des gardes contrôlé statiquement.

Le premier run AVD sur l'émulateur déjà utilisé avait produit 5 flakes UI Compose; le run cold boot final ne conserve que l'échec de harnais font scale, puis le retest ciblé passe.
