# ATLAS V6.2 — Provenance et réemploi OSS

S0 vérifie les révisions, les fichiers ciblés et les licences. **Aucun code des deux nouveaux projets n’a été copié dans ATLAS à la baseline `ee1e7b58c06531f6fdc631a42cdd7522d8ee90e1`.** Les clones sont des références de travail uniquement. Les destinations ATLAS ci-dessous sont prévues par le prompt; elles ne sont pas déclarées comme adaptations déjà présentes.

## OpenClaw officiel — localisation

- Dépôt : <https://github.com/openclaw/openclaw>
- SHA détaché : `e3fe108378f6eca2d4e2a4333fd08bf81bb87105`
- Destination : `references/repos/openclaw/`
- Clone : `--filter=blob:none`, sparse checkout actif; arbre de travail limité aux 13 fichiers sélectionnés (licence, README, quatre sources d’adaptation, trois fichiers de consultation, trois tests et la doc de commande). Le dépôt indique `remote.origin.partialclonefilter=blob:none` et `core.sparseCheckout=true`.
- L’historique et les arbres Git OpenClaw restent volumineux malgré le filtre (pack observé : 435,40 MiB); seuls les 13 fichiers retenus sont matérialisés dans l’arbre sparse.
- Licence vérifiée : MIT dans le `LICENSE` racine et le README, copyright © 2026 OpenClaw Foundation. Aucun en-tête de licence supplémentaire n’a été trouvé au début des trois fichiers de localisation ciblés.

Commandes : `git clone --filter=blob:none --no-checkout https://github.com/openclaw/openclaw.git references/repos/openclaw`; `git -C references/repos/openclaw sparse-checkout init --no-cone`; `git -C references/repos/openclaw sparse-checkout set --no-cone <les 13 chemins retenus ci-dessous>`; `git -C references/repos/openclaw checkout --detach e3fe108378f6eca2d4e2a4333fd08bf81bb87105`.

Provenance secondaire à traiter séparément : `LocationCoarsener.kt` cite explicitement `frameworks/base/services/core/java/com/android/server/location/fudger/LocationFudger.java` au commit court `81f52b053da6`. Le fichier AOSP à cette révision porte l’en-tête Apache License 2.0 et le copyright Android Open Source Project 2020. La référence ne prouve pas à elle seule quelles lignes sont dérivées; avant de reprendre la grille/jitter, comparer l’algorithme source et conserver l’attribution Apache si des éléments expressifs sont adaptés. Cette provenance n’est pas couverte par le seul avis MIT OpenClaw.

| Source exacte à réutiliser | Cible ATLAS prévue | Provenance technique et adaptation obligatoire |
|---|---|---|
| [`LocationCaptureManager.kt`](../../references/repos/openclaw/apps/android/app/src/main/java/ai/openclaw/app/node/LocationCaptureManager.kt) | `android/app/src/main/java/dev/atlas/coach/location/LocationCaptureManager.kt` | Acquisition `LocationManager`, contrôle de permissions, fraîcheur des fixes, timeout et retrait des listeners à l’annulation. La source appelle `getCurrentLocation` sans fallback API 26–29; l’adaptation doit prendre en charge `minSdk=26`. |
| [`LocationCoarsener.kt`](../../references/repos/openclaw/apps/android/app/src/main/java/ai/openclaw/app/node/LocationCoarsener.kt) | `android/app/src/main/java/dev/atlas/coach/location/LocationCoarsener.kt` | Frontière d’anonymisation, grille de 2 km et offset persistant au processus; retire altitude, vitesse, cap et extras. Aucun point brut ne doit franchir cette frontière vers le contexte Coach. |
| [`LocationDisclosure.kt`](../../references/repos/openclaw/apps/android/app/src/main/java/ai/openclaw/app/node/LocationDisclosure.kt) | `android/app/src/main/java/dev/atlas/coach/location/LocationDisclosure.kt` | Revalide précision et permission après la suspension d’acquisition et n’upgrade pas une demande commencée en approximatif. ATLAS doit ajouter son consentement d’envoi distinct et exiger la confirmation explicite pour tout envoi ponctuel précis. |
| [`LocationMode.kt`](../../references/repos/openclaw/apps/android/app/src/main/java/ai/openclaw/app/LocationMode.kt) | `android/app/src/main/java/dev/atlas/coach/location/LocationMode.kt` | La source contient `Off`, `WhileUsing` et `Always`; ne pas reprendre `Always`, son accès arrière-plan ou sa sémantique. Le mode V6.2 reste à la demande et la session temporaire passe par le service foreground explicite. |

Consultation sans copie intégrale : [`LocationHandler.kt`](../../references/repos/openclaw/apps/android/app/src/main/java/ai/openclaw/app/node/LocationHandler.kt), [`PermissionRequester.kt`](../../references/repos/openclaw/apps/android/app/src/main/java/ai/openclaw/app/PermissionRequester.kt), [`NodeForegroundService.kt`](../../references/repos/openclaw/apps/android/app/src/main/java/ai/openclaw/app/NodeForegroundService.kt), [`location-command.md`](../../references/repos/openclaw/docs/nodes/location-command.md) et les tests `LocationCaptureManagerTest.kt`, `LocationPrivacyTest.kt`, `LocationHandlerTest.kt` sous `apps/android/app/src/test/.../node/`.

Les erreurs ATLAS exigées sont `LOCATION_DISABLED`, `LOCATION_PERMISSION_REQUIRED`, `LOCATION_BACKGROUND_UNAVAILABLE`, `LOCATION_TIMEOUT` et `LOCATION_UNAVAILABLE`. `LocationHandler.kt` confirme les quatre derniers comportements fonctionnels, mais ne fournit pas l’erreur ATLAS `LOCATION_DISABLED`; l’adaptateur devra la distinguer d’un fournisseur sans fix. La commande upstream expose aussi un protocole Gateway et des données de position précises : ne pas importer `GatewaySession`, NodeRuntime, pairing, le framework d’outils, les permissions universelles ou le payload de position brut.

Les permissions V6.2 demeurent `ACCESS_COARSE_LOCATION` et `ACCESS_FINE_LOCATION`; pas d’`ACCESS_BACKGROUND_LOCATION`. La collecte ponctuelle commence approximativement, avec `timeoutMs=10000` et `maxAgeMs=15000`. La catégorie `location_summary` ne transporte que des changements de zone approximatifs et horodatés.

## AndyClaw — politique de déclenchement

- Dépôt : <https://github.com/EthereumPhone/AndyClaw>
- SHA détaché : `fd4a9af917f51d7b1c10e09e6496f54a290b253b`
- Destination : `references/repos/AndyClaw/`
- Clone : `--filter=blob:none`, sparse checkout actif; sept fichiers sélectionnés (`LICENSE`, README, la politique, son test et les trois fichiers demandés en consultation).
- Licence vérifiée : README `GPL-3.0` et fichier racine GNU GPL version 3. Le fichier ciblé ne porte pas d’en-tête SPDX ou copyright qui remplace la licence racine.

Commandes : `git clone --filter=blob:none --no-checkout https://github.com/EthereumPhone/AndyClaw.git references/repos/AndyClaw`; `git -C references/repos/AndyClaw sparse-checkout init --no-cone`; `git -C references/repos/AndyClaw sparse-checkout set --no-cone <les sept chemins retenus ci-dessous>`; `git -C references/repos/AndyClaw checkout --detach fd4a9af917f51d7b1c10e09e6496f54a290b253b`.

| Source exacte à adapter | Cible ATLAS prévue | Provenance technique et adaptation obligatoire |
|---|---|---|
| [`NotificationTriggerPolicy.kt`](../../references/repos/AndyClaw/app/src/main/java/org/ethereumphone/andyclaw/heartbeat/NotificationTriggerPolicy.kt) | `android/app/src/main/java/dev/atlas/coach/coach/proactive/ProactiveTriggerGate.kt` | Politique pure de déduplication par clé et cooldown, actuellement appliquée aux notifications Android. Adapter la logique aux événements typés de coaching, au cooldown de 60 min par motif et aux quotas V6.2; ne pas conserver les types Android `Notification`. |

Son test source est conservé en référence à `app/src/test/java/org/ethereumphone/andyclaw/heartbeat/NotificationTriggerPolicyTest.kt`. Les fichiers de consultation imposés sont `app/src/main/java/org/ethereumphone/andyclaw/skills/builtin/LocationSkill.kt`, `app/src/main/java/org/ethereumphone/andyclaw/skills/builtin/ScreenTimeSkill.kt` et `app/src/main/java/org/ethereumphone/andyclaw/safety/ToolAttenuation.kt`. **Ne pas reprendre `ScreenTimeSkill`** : son exécution dépend de privilèges système. N’importer ni `NotificationListenerService`, ni ethOS, runtime AndyClaw, shell, portefeuille, agent autonome ou lecture générale des notifications.

## Références historiques conservées

Les révisions déjà présentes sont confirmées sans modification :

| Référence | Révision vérifiée | Usage ATLAS déjà notifié |
|---|---|---|
| Olauncher | `f8e8d6497c457734c7a3171d36374157678e1f33` | Adaptation historique de `LauncherAppModel.kt` et HOME, GPLv3; aucune nouvelle source importée en S0. |
| Reef | `044358fb26bf11aa85dddadf919106ca4053d926` | Adaptations Focus/routines conservées avec attribution MIT particulière. |
| Lawnchair | `fc100ae9095a83b8b51c447a3b0a4ce2b399ad59` | Référence technique, aucun fork. |
| uhabits | `c06b682f6b7b9c09eb278a06ffb57a42f0cddd38` | Calculs et exports déjà intégrés, GPL-3.0-or-later. |

## Notices lors de l’implémentation

Le tableau S0 ci-dessus décrit uniquement l’état à la baseline. Les adaptations V6.2 effectivement livrées le 2026-10-19 sont consignées ci-dessous et dans `THIRD_PARTY_NOTICES.md`; les clones de référence et les tests amont non listés comme sources d’adaptation restent hors du produit.

## Adaptations effectivement livrées — 2026-10-19

### OpenClaw officiel — MIT

- Dépôt : <https://github.com/openclaw/openclaw>
- Révision : `e3fe108378f6eca2d4e2a4333fd08bf81bb87105`
- Licence : MIT, Copyright (c) 2026 OpenClaw Foundation; le texte requis de `references/repos/openclaw/LICENSE` est reproduit dans `THIRD_PARTY_NOTICES.md`.

| Fichier source exact | Cible ATLAS exacte | Modifications ATLAS |
|---|---|---|
| `apps/android/app/src/main/java/ai/openclaw/app/node/LocationCaptureManager.kt` | `android/app/src/main/java/dev/atlas/coach/location/LocationCaptureManager.kt` | Port Android vers l’interface `AtlasLocationProvider`; compatibilité API 26–29, délai, fraîcheur, permissions revalidées et annulation; aucun payload Gateway. |
| `apps/android/app/src/main/java/ai/openclaw/app/node/LocationCoarsener.kt` | `android/app/src/main/java/dev/atlas/coach/location/LocationCoarsener.kt` | Grille de 2 km et offset aléatoire partagé au processus; drift lent; résultat borné à latitude, longitude, précision minimale et horodatage. L’approche offset-plus-grille est aussi attribuée à AOSP ci-dessous. |
| `apps/android/app/src/main/java/ai/openclaw/app/node/LocationDisclosure.kt` | `android/app/src/main/java/dev/atlas/coach/location/LocationDisclosure.kt` | Sépare permission Android et partage ATLAS, revalide après suspension, garde l’automatique approximatif et fait passer les mesures brutes par le coarsener. |
| `apps/android/app/src/main/java/ai/openclaw/app/LocationMode.kt` | `android/app/src/main/java/dev/atlas/coach/location/LocationMode.kt` | Types et modes réduits aux capacités V6.2 à la demande; aucun mode `Always` ou permission d’arrière-plan. |
| `apps/android/app/src/test/java/ai/openclaw/app/node/LocationHandlerTest.kt` | `android/app/src/test/java/dev/atlas/coach/location/LocationRepositoryTest.kt` | Tests réécrits autour du dépôt ATLAS, du stockage local, du consentement et de la stabilité d’un résumé déjà minimisé. |
| `apps/android/app/src/test/java/ai/openclaw/app/node/LocationPrivacyTest.kt` | `android/app/src/test/java/dev/atlas/coach/location/LocationPrivacyTest.kt` | Tests adaptés aux permissions et au consentement séparés, à la révocation et à la divulgation coarse. |

`android/app/src/main/java/dev/atlas/coach/location/LocationRepository.kt` est le raccordement ATLAS original : il transmet les `LocationSummary` déjà coarsened sans leur appliquer une seconde grille ou un nouvel offset.

### AOSP LocationFudger — Apache-2.0

- Projet : Android Open Source Project, `platform/frameworks/base`.
- Source : `services/core/java/com/android/server/location/fudger/LocationFudger.java`, révision courte `81f52b053da6`.
- Cible : `android/app/src/main/java/dev/atlas/coach/location/LocationCoarsener.kt`.
- Licence et attribution : Apache License 2.0; Copyright (c) 2020 Android Open Source Project. `THIRD_PARTY_NOTICES.md` conserve cette attribution et le texte Apache-2.0. La classe Java et ses dépendances framework ne sont pas reprises; seule l’approche offset et grille, référencée dans la source OpenClaw, est adaptée au contrat ATLAS de résumé de 2 km.

### AndyClaw — GNU GPL version 3

- Dépôt : <https://github.com/EthereumPhone/AndyClaw>
- Révision : `fd4a9af917f51d7b1c10e09e6496f54a290b253b`
- Source exacte : `app/src/main/java/org/ethereumphone/andyclaw/heartbeat/NotificationTriggerPolicy.kt`.
- Cible exacte : `android/app/src/main/java/dev/atlas/coach/coach/proactive/ProactiveTriggerGate.kt`.
- Licence : GNU GPL version 3 selon README et `LICENSE` racine; le texte GPLv3 inclus dans `THIRD_PARTY_NOTICES.md` s’applique.
- Modifications : garde pure sur des événements de coaching typés; conserve la déduplication bornée par identifiant et la durée minimale; cooldown indexé par motif à 60 minutes; retire les callbacks de notifications Android, filtres de paquets, catégories de notification et hooks du runtime AndyClaw. Les quotas et l’état versionné restent ATLAS.
