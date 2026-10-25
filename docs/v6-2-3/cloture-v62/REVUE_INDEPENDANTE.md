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

## Correction des P1

Les deux P1 sont en cours de correction dans `AgentContracts.kt` et `ActionDispatcherTest.kt` :

- calcul de la durée réellement exécutée avec pauses longues;
- comparaison de fin de fenêtre en date/heure locales, y compris DST;
- test de durée totale exactement à la borne;
- oracle DST.

Après intégration, les reviewers doivent examiner le **SHA final** (pas seulement `1251009`) et confirmer la disparition des P1. Le verdict final de cette pièce sera mis à jour à ce moment.

## Verdict provisoire

**NO_GO provisoire.** Aucun P0; deux P1 confirmés en correction; des P2 de preuve et de transport restent ouverts. La clôture ne peut être déclarée qu'après re-revue du SHA final et acceptation explicite des limites restantes par le propriétaire.
