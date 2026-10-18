# PROMPT SECONDAIRE — VALIDATION SUR PIXEL RÉEL

Ce prompt **n'est pas à lancer avant d'avoir accès à un Pixel de test et aux consentements utilisateur**. Lis README, AGENTS, docs et `docs/VALIDATION.md` du premier incrément.

1. Identifier modèle/version Android, API level, APK/package réel du lecteur vidéo, batterie/Doze et permissions accordées volontairement.
2. Installer uniquement la version débogage ATLAS ; ne pas activer AccessibilityService, Device Owner, root ou kiosque.
3. Mesurer latence UsageStats sur ouvertures répétées, reboot, bascule d'apps, battery saver ; distinguer observé vs simulé.
4. Tester HOME, gestes retour/récents, sortie vers Pixel Launcher, notifications refusées, passage hors ligne et pause d'urgence.
5. Tester ACTION_VIEW vers vidéo précise avec app de l'utilisateur ou fallback navigateur ; ne pas modifier ReVanced.
6. Vérifier anti-boucle : jamais plus d'une intervention par cooldown ; jamais de notification à haute fréquence ou overlay qui empêche appels/réglages.
7. Rédiger `docs/RAPPORT_PIXEL.md` avec commandes, mesures, versions, résultats et limitations. Proposer ensuite de manière argumentée si l'audit/accessibilité expérimentale est justifié **sur appareil secondaire**.

Ne jamais déclarer une capacité Android garantie lorsque le test montre seulement un succès ponctuel.
