# ATLAS — V6.2.3 intégrée sur main, qualification Android 17 en attente

Décision du propriétaire : 2026-10-24.

- PR #2 (https://github.com/m413w4r3/DroidCoach/pull/2) fusionnée ; merge initial `b1b10c87c8e48a57ea59225744a3c41188edd21b`.
- Ce merge constitue une **base d'intégration**, pas une validation complète de V6.2.
- CI GitHub de la branche V6.2.3 au SHA `66ffc532502f5d0fbe740025ab1e7ad5ce2fa1d2` : JVM, lint, build et backend réussis.
- Pixel 9 Pro XL API 37 : 14 tests ciblés PASS en campagne historique ; les scénarios HOME, permissions, notifications, localisation, mobilité, révocation, rotation restent à qualifier selon les rapports.
- AVD API 37 : environnement bloqué (surfaceflinger / ressources) ; ne pas imputer sans preuve un défaut à ATLAS.
- Android 14/API 34 : **n'est plus la cible**, retiré des exigences de qualification et tests instrumentés à partir de cette décision ; ne pas le relancer.
- Sauvegarde Pixel historique : stockée sous `/tmp/v623-qa/` lors de la campagne, conservation actuelle non vérifiée. Ne jamais compter sur sa disponibilité ; refaire une sauvegarde sûre avant opérations à risque.
- Issue #1 localisation : ouverte ; ne pas fermer sans preuve sur cible Android 17 et accord sur toute évolution de ses critères AVD.

Suivre `prompts/Prompt_Cloture_V6.2_Avant_V6.3.md` pour la campagne de clôture via Paseo. Les procédures `.agents/skills/` sont adaptées aux besoins d'ATLAS. Aucune action supplémentaire sur le téléphone n'a été réalisée dans la création de cette documentation.

**Le passage V6.3 n'est pas autorisé automatiquement par la seule fusion.**
