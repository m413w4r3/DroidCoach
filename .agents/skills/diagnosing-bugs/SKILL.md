---
name: diagnosing-bugs
description: "Enquêter sur un crash, une régression, un test floconneux, un défaut API 37 ou un comportement ATLAS inexpliqué."
---

# Diagnostic reproductible

1. Décrire le symptôme exact et le SHA testé, l'API Android, l'appareil/AVD, les préconditions et l'impact utilisateur.
2. Obtenir un **oracle discriminant** pouvant échouer sur ce problème : test ciblé, trace expurgée rejouée, scénario instrumenté isolé, comparaison entre commits ou reproduction contrôlée.
3. Exécuter l'oracle et conserver la sortie horodatée avant de modifier le code. Pour Android, classifier `APP_BUG`, `TEST_HARNESS_BUG`, `DEVICE_OR_AVD_ENVIRONMENT` ou `UNKNOWN`.
4. Minimiser sans contourner : reproduire une seule classe et ses préconditions; ne pas supprimer d'assertion, élargir arbitrairement les tolérances ou masquer un test.
5. Proposer une seule hypothèse à la fois, citer le chemin source et l'observation contradictoire à vérifier.
6. Corriger le défaut au plus petit périmètre et exécuter la même commande **rouge puis verte** ainsi qu'une régression voisine.
7. Maximum trois tentatives ciblées par oracle ; après deux sans amélioration, demander une revue Luna XHigh indépendante.
8. Signaler `BLOCKED/NOT_RUN` lorsque l'AVD API 37 échoue avant installation ou que le Pixel n'est pas connecté. Ne jamais affirmer que l'application est cassée sur ce seul fait.
9. Réduire et expurger les logs, coordonnées, jetons et captures personnelles avant archivage.

Interdit : boucle de surveillance avec `sleep`, tests destructifs sur Pixel, suppression de données utilisateur ou upgrade de `targetSdk` sans mission dédiée.
