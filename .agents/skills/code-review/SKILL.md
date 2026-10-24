---
name: code-review
description: "Auditer indépendamment une modification DroidCoach, une branche, un correctif ou une décision de validation avant fusion ou V6.3."
---

# Revue indépendante ATLAS

1. Fixer `BASE_SHA`, `RESULT_SHA`, diff, commits et mission originale. Ne pas relire un SHA historique à la place du HEAD actuel.
2. Lire `AGENTS.md`, les critères d'acceptation et les docs du sous-système. Identifier les limites spécifiques Android 17, stockage, consentement, mandats et fonctionnement hors ligne.
3. Déléguer deux examens **indépendants** en lecture seule si Paseo le permet :
   - **Conformité fonctionnelle :** parcours effectivement demandé, écrans, tests de preuve et migration des données.
   - **Qualité et sécurité :** callbacks, horloges, concurrence, droits Android, révocation, échecs de disque, notifications, compatibilité et privacy.
4. Pour chaque constat, fournir : sévérité P0/P1/P2, `chemin:ligne`, conditions de reproduction, comportement attendu, test/oracle et correction minimale.
5. Séparer clairement `DEFECT_CONFIRMED`, `RISK`, `EVIDENCE_GAP`, `STYLE_SUGGESTION`. Les préférences esthétiques sont soumises à revue utilisateur ; ne pas les transformer en P0.
6. Exiger la preuve **après** dernier correctif. Un test vert sur un ancien SHA ne couvre pas une modification ultérieure.
7. Produire un verdict `GO`, `GO_WITH_LIMITATIONS` ou `NO_GO`, avec éléments bloquants et prochaines actions.
8. **Aucune** modification Git distante, fusion, fermeture d'issue ou changement sur téléphone au titre de ce skill.

Ne pas considérer une CI JVM verte comme une qualification du launcher HOME sur Pixel physique. Ne pas rendre bloquant un test Android 14, hors cible utilisateur actuelle. Les scénarios API 37 non exécutés restent `NOT_RUN`.
