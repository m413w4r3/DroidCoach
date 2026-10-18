> Contient du code GPLv3/MIT/Apache — ne pas distribuer l'APK sans publier les sources sous licence compatible

# ATLAS — Kit de cadrage avant agent de code

Version de préparation : 2026-10-18. Ce dépôt contient le cadrage produit et une première application Android Compose locale (jalon VM-1). Point de départ : `architecture_coach_pixel.md` fourni par le porteur de projet. Les décisions ci-dessous affinent le document sans effacer son cadre de mandat révocable, de règles locales et de consentements.

## Objet final
Un **système personnel de coaching Android** avec :
- **ATLAS Android**, application principale installable sur un Google Pixel, capable de proposer un écran HOME minimal, directives/négociation, sessions de concentration, habitudes, usage d'applications et accès à des contenus choisis ;
- **ATLAS Core**, service serveur modulaire pour mémoire des projets, directives, politique, revues, curation et adaptation d'agents IA ;
- **Intégrations externes remplaçables** (Loop, ReVanced, RSS, notes) si elles ont une API ou une liaison vérifiée. L'expérience est unifiée, même si les logiciels restent séparés.

## Décision d'intégration
Décision du porteur de projet pour cette préparation : usage exclusivement privé, APK non distribuée, reprise maximale des composants utiles des références auditables. Les licences GPLv3, MIT et Apache-2.0 sont acceptées pour cet usage. Toute redistribution exige la publication des sources et notices sous une licence compatible. Les composants de blocage, d'accessibilité, de surveillance et de privilège restent exclus du périmètre ATLAS.

## Par où commencer ?
1. Lire `docs/01-produit.md`, `docs/02-architecture.md` et `docs/03-audit-open-source.md`.
2. Si tu veux déléguer l'ensemble de l'étape technique au même agent, lui transmettre **le contenu de** `prompts/AGENT_PRINCIPAL.md` dans le dossier racine (en joignant les fichiers ou en lui donnant accès au dossier).
3. L'agent documente ses vérifications et construit une V0 démontrable. La validation sur le Pixel réel reste nécessaire avant tout blocage fort.
4. Utiliser `docs/04-recette-et-tests.md` comme définition de « terminé » ; suivre `docs/05-decisions-et-evolutions.md` pour les choix à différer.

## Dossier de travail : dépôts externes
Ne pas cloner tous les dépôts à la racine. Conserver leurs URLs dans `references/repos-candidats.yaml`. Pour inspecter le code, cloner **uniquement les présélectionnés** dans `_research/` (ignoré par Git), en checkout détaché sur SHA connu et lecture seule de préférence. La décision S6 autorise la reprise maximale de composants utiles pour cet usage exclusivement privé : consigner fichiers sources, SHA, licences, attribution et validation dans `docs/AUDIT_CIBLE.md` et `THIRD_PARTY_NOTICES.md`. Toute redistribution exige la publication des sources complètes sous licence compatible.

## Périmètre du premier incrément
Une application Android minimale avec HOME *optionnel*, carte de directive, minuterie Pomodoro et historique local, suivi basique d'habitudes, consentement pour observation d'apps, règle locale de friction simulable, lien vidéo externe, pause et sortie de secours, plus backend contractuel pouvant fonctionner en mode mock hors ligne. Aucune dépendance obligatoire à OpenClaw, Reef, ReVanced ou Loop pour la première démonstration.

## Non-objectifs initiaux
Root, ROM, Device Owner, lockscreen remplacé, surveillance des écrans privés, publication Play Store, modification de l'APK ReVanced, accès universel aux réglages d'apps tierces, interdiction absolue de sortir du coach.

## Build & VM-0

Depuis la racine du dépôt, lancer `./scripts/build-android.sh`. L'APK debug est généré dans `android/app/build/outputs/apk/debug/app-debug.apk`. La validation VM-0 sur émulateur et le chemin de capture sont consignés dans [docs/VALIDATION_VM.md](docs/VALIDATION_VM.md).

## VM-1 — démonstration Compose hors ligne

L'application Android fonctionne sans serveur, compte, clé ni permission système runtime. Elle conserve focus, habitudes, réponses du coach, règles simulées et capacités dans un stockage local privé. Le HOME ATLAS est un candidat facultatif; l'application démarre d'abord comme Activity classique.

```sh
./scripts/build-android.sh
/home/nill/Android/Sdk/platform-tools/adb -s emulator-5554 install -r android/app/build/outputs/apk/debug/app-debug.apk
/home/nill/Android/Sdk/platform-tools/adb -s emulator-5554 shell am start -n dev.atlas.coach/dev.atlas.coach.app.MainActivity
```

Les résultats exécutés, les verdicts VM-1 et les captures réelles de l'AVD sont dans [docs/VALIDATION_VM.md](docs/VALIDATION_VM.md). La recette de l'émulateur utilisé et ses limites sont dans [docs/07-emulateur-linux.md](docs/07-emulateur-linux.md).
