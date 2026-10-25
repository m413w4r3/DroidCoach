# Matrice de qualification Android 17 — clôture V6.2

Date de synthèse : 2026-10-25. Pixel 9 Pro XL, Android 17 / API 37, build `CP3A.260905.009`; aucun numéro de série n'est conservé. Émulateur de complément : `emulator-5554`, Android 17 / API 37.

## Révisions et preuves

- Source de la campagne Pixel : `8890046012965f3a3112ff0c419014c74e189d11`.
- APK Pixel : SHA-256 `48c7766e9090010692f0eaafc1d0cbaf4b22e51259847f94b642a2d060b33583`; certificat debug `da3014662eb65e50a87ef364a2e583c331398795b222e3b37b1a16553a00eeee`.
- Source finale du code : `b13b3fb` (`4aff91a`, `1251009`, `b13b3fb`/`d718540`); APK final AVD : SHA-256 `2743688c830a3e3df42b9db3c4d251cbf796837160d252e5976098346b2448ac`.
- Sauvegarde app-only : archive hors dépôt, SHA-256 `26912d37b7f98ca88d9e1c42076698120c25e0bfb4de867a096d4609dc3160e0`; 12 entrées, 8 fichiers réguliers; manifeste canonique `a0c67cc6ee124241336a6b11869137fd6ffeb641b6b79aeda1a13a7498dc924b`.
- Preuves Pixel : résultats et empreintes transmis par le parent; captures et traces QA non versionnées. Les impressions d'écran versionnées sous `captures/` proviennent de l'émulateur, pas du Pixel.

`NOT_RUN` signifie qu'aucune preuve concluante n'est disponible. Un résultat historique ne vaut pas qualification du SHA final. Le propriétaire a demandé l'arrêt des tests sur le Pixel après la campagne; les tests finaux ont été exécutés sur `emulator-5554`.

## Scénarios

| Scénario | Statut | Type | SHA source / APK | Preuve ou référence | État après test |
|---|---|---|---|---|---|
| Remplacement non destructif de l'APK et conservation des données | PASS | Manuel, Pixel | `8890046…` / `48c7766e…` | `adb install -r`, certificat identique, 8 empreintes identiques à la sauvegarde | ATLAS installé; aucun uninstall ni `pm clear` |
| Démarrage à froid | PASS | Manuel, Pixel | `8890046…` / `48c7766e…` | `Status: ok`, `COLD`, 548 ms, aucun crash | Session Focus existante retrouvée en pause |
| Sauvegarde app-only lisible et vérifiée | PASS | Vérification de fichier | `8890046…` | SHA et manifeste indiqués ci-dessus; extraction locale vérifiée | Archive conservée hors dépôt |
| Restauration finale des données sauvegardées | NON EXÉCUTÉE — décision propriétaire | Manuel, Pixel | `8890046…` / `48c7766e…` | Le propriétaire a choisi « Garder l'état QA » le 2026-10-25 | Données ATLAS synthétiques conservées; sauvegarde d'origine intacte |
| HOME : tiroir, recherche, favori, ouverture d'une app, retour et sortie | PASS | Manuel, Pixel | `8890046…` / `48c7766e…` | ATLAS HOME, recherche, favori, Brave, « Launcher précédent » | Pixel Launcher restauré comme HOME |
| Pause d'urgence | PASS | Manuel, Pixel | `8890046…` / `48c7766e…` | Message 30 minutes, bouton « Fin », fin vérifiée | Pause terminée |
| HOME et Pause d'urgence hors ligne (mode avion) | PASS | Manuel, Pixel | `8890046…` / `48c7766e…` | Mode avion, directive locale, Pause et « Fin » | Réseau rétabli, HOME Pixel Launcher |
| Paramètres de confidentialité et objectifs hors ligne | NOT_RUN | Manuel, Pixel | `8890046…` / `48c7766e…` | Non testés spécifiquement hors ligne | À qualifier |
| Focus, objectif et progression 0 → 25 → 25 après redémarrage | PASS | Manuel, Pixel + JVM | `8890046…` / `48c7766e…` | Objectif 120 min, Focus lié 25 min; 0/120 avant fin; 25/120 après fin et après relance à froid (583 ms); annulation d'une nouvelle session liée : toujours 25/120 | État QA synthétique conservé sur le Pixel |
| Annulation sans crédit | PASS | Manuel, Pixel | `8890046…` / `48c7766e…` | Session liée de 25 min démarrée puis annulée après ~2 s; interface toujours 25/120 | Aucune minute créditée |
| Focus et progression simulés par FakeClock | PASS | Automatisé JVM | `b13b3fb` | Suite finale 332 tests, 0 échec; tests Focus/Goal listés dans [E2E_PERSONNALISE.md](E2E_PERSONNALISE.md) | Simulation uniquement |
| Panne d'écriture simulée et cohérence après recréation | PASS | Automatisé JVM + AVD | `b13b3fb` / `2743688c…` | Tests JVM et instrumentation de défaillance d'écriture | Reprise matérielle réelle non testée |
| Récupération Android après panne de stockage | NOT_RUN | Manuel, Pixel | `8890046…` / `48c7766e…` | Aucun test de panne matérielle réelle | À qualifier |
| Allowlist instrumentée non destructive Pixel | PASS | Automatisé, Pixel | `8890046…` / `48c7766e…` | 6/6 tests autorisés réussis | Aucun nettoyage des données ATLAS |
| Suite instrumentée locale AVD API 37 | PASS | Automatisé, AVD | `b13b3fb` / `2743688c…` | 78 méthodes : 77 PASS + 1 échec de harnais `expected_system_font_scale`; retest ciblé PASS; 78/78 effectifs; [preuves finales](preuves-finales-b13b3fb/PREUVES_FINALES.md) | `emulator-5554` uniquement |
| Notifications : refus/accord, publication, routage au tap, révocation | PASS | Manuel, Pixel | `8890046…` / `48c7766e…` | Permission refusée puis accordée; publisher 2/2; routage 6/6; fixture supprimée | Permission restaurée refusée |
| Quotas, anti-doublon, mode silencieux et retrait après révocation | PARTIEL / NOT_RUN | Manuel / automatisé | `b13b3fb` | Tests AVD de déduplication et de silence PASS; quotas complets et retrait après révocation non qualifiés sur Pixel | À qualifier |
| Localisation : refus, approximative, timeout, fournisseur arrêté, ancienneté, coarsening | NOT_RUN — capacité désactivée par défaut | Manuel, Pixel | `b13b3fb` | `CAP_LOCATION=false` par défaut; aucun test Android 17 spécifique; issue #1 ouverte | Non revendiqué |
| Mobilité visible, notification Arrêter, absence de trace brute | NOT_RUN — capacité désactivée par défaut | Manuel, Pixel | `b13b3fb` | `CAP_MOBILITY=false` par défaut; aucun essai | Non revendiqué |
| Rotation paysage | PASS | Manuel, Pixel | `8890046…` / `48c7766e…` | Parcours paysage réussi | Rotation restaurée à 0° |
| Police système 2× et accès aux actions | PASS | Manuel, Pixel + AVD | `8890046…` / `48c7766e…`; `b13b3fb` / `2743688c…` | Contenu défilable, actions atteignables; test AVD ciblé PASS | Police restaurée à 1,0×; P2 de contenu rogné documenté |
| TalkBack, libellés des actions critiques | PASS | Manuel, Pixel | `8890046…` / `48c7766e…` | Libellés `content-desc` vérifiés sur Urgence, Commencer, Focus, Habitudes, Plus | Accessibilité restaurée à Reef uniquement |
| Variantes 320/360 dp | NOT_RUN | Manuel, Pixel | `b13b3fb` | Pas de mesure de largeur disponible | À qualifier |
| Performance et consommation au repos | NOT_RUN | Mesure instrumentée | `b13b3fb` | Aucune mesure avec durée et conditions | À qualifier |
| E2E OpenClaw host-side (gateway réel) | PASS historique | Hôte, gateway réel | `8890046…` | [preuves-openclaw/host-run.txt](preuves-openclaw/host-run.txt) : réponse 25/120, propositions dans le mandat, ACK unique, révocation avant requête suivante, fallback | `MandateVerifier` Android non couvert par ce run |
| OpenClaw Android sur AVD API 37 | PASS historique + PASS final | Automatisé, AVD | `8890046…` et `b13b3fb` / `2743688c…` | [preuves-openclaw/android-avd-run.txt](preuves-openclaw/android-avd-run.txt) : OK (1 test), 11.143 s sur `8890046…`; [preuve finale](preuves-finales-b13b3fb/openclaw-android.txt) : OK (1 test), 17,743 s sur `b13b3fb`. Le premier essai sur `1251009` avait reçu HTTP 429 (quota), puis a réussi après réinitialisation | Aucun échec de code; test final PASS |
| Révocation pendant une requête OpenClaw réelle | NOT_RUN | Automatisé, AVD | `b13b3fb` | Tests locaux de génération, barrière et réponse tardive PASS; course réelle non rejouée | Limite documentée |
| AVD jetable initial | BLOCKED / AVD_ENVIRONMENT | Automatisé, AVD historique | Révision historique | `surfaceflinger`/`system_server` instables avant installation | Ne qualifie pas le HEAD |
| Android 14/API 34 | NOT_RUN | Hors cible | — | Aucun essai demandé | Aucun résultat revendiqué |

## Réglages finaux observés

- HOME : `com.google.android.apps.nexuslauncher`.
- Police : 1,0×; rotation : 0°.
- `stay_on_while_plugged_in` : **7**, valeur initiale réelle relevée au pré-vol (`r4-phone-preflight.tsv`) et jamais modifiée. La mention antérieure « initial 0, restauration à 0 » était erronée.
- Accessibilité : Reef uniquement.
- Permission notifications : refusée; fixture supprimée.
- Réseau : Wi-Fi et données cellulaires actifs; mode avion désactivé.
- Données ATLAS : état QA synthétique conservé à la demande du propriétaire; la restauration de la sauvegarde d'origine n'a pas été exécutée.

## Résultats historiques

Le rapport [API37_MATRICE.md](../API37_MATRICE.md) décrit la révision `7c12ad17f8340d775924a0b986dd44ac35b79722` et ses exécutions instrumentées. Ses 14/14 cas après correction de harnais ne sont pas attribués à `b13b3fb`. Les tentatives Android 14 historiques sont hors cible et ne sont pas relancées.
