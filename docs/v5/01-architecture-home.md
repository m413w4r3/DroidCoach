# ATLAS V5 · Architecture du HOME Android

## Deux activités, deux usages

- `MainActivity` est l'application de gestion ATLAS. Son filtre `MAIN/LAUNCHER` ouvre le tableau de bord coach, Focus, Habitudes, Paramètres, Observation et Sync.
- `LauncherActivity` est l'écran HOME Android. Son filtre `MAIN/HOME/DEFAULT` est le seul filtre HOME de l'application. Android l'ouvre après que l'utilisateur a choisi ATLAS comme application d'accueil.
- Le tableau de bord coach reste une destination interne à l'application. Il ne se substitue pas au HOME Android et ne présente plus une seconde page de launcher dans sa navigation basse ou « Plus ».
- Depuis HOME, le lien « Coach » ouvre directement `MainActivity`. « Launcher précédent » tente de lancer l'activité HOME antérieure mémorisée avant le changement de rôle; « Réglages HOME » permet de modifier le choix du launcher dans Android. Le rôle n'est jamais changé silencieusement.

## Contrôleur et stockage partagés

`AtlasApplication.controller` crée paresseusement une seule instance de `AtlasController` par processus. `MainActivity` et `LauncherActivity` reçoivent cette même instance et partagent donc la directive, les favoris, la pause d'urgence, Focus et les autres données en mémoire.

La source persistante reste le document JSON ATLAS, versionné dans `SharedPreferences` privé sous `atlas_local_state/state_v1`, via `LocalStateStore` et `AndroidStateBackend`. La persistance durable utilise `commit()`; les checkpoints utilisent `apply()`. Après arrêt du processus, le contrôleur unique est restauré depuis ce document. Les activités ne maintiennent pas deux copies concurrentes du graphe métier.

La capability historique `launcherHome` reste dans le document pour compatibilité et reflète le rôle HOME observé. Elle ne bloque plus l'écran lorsque le rôle Android est détenu : `CapabilityRules.mayRenderLauncherHome` donne priorité à ce rôle, y compris pour une ancienne installation où la capability avait été enregistrée désactivée. Les autres capabilities restent indépendantes.

## Contenu et interaction HOME

- Écran Compose dédié, edge-to-edge avec insets sûrs et thème ATLAS clair/sombre; aucune barre de navigation du coach.
- Heure et date locales, directive du jour, favoris sous forme de texte.
- Un glissement vers le haut depuis l'en-tête ou l'action « Ouvrir le tiroir d'applications » ouvre la liste. Le champ clavier filtre les noms et paquets sans tenir compte des accents et de la casse.
- Les activités sont découvertes avec la requête Android `MAIN/LAUNCHER` déclarée dans `<queries>`, puis lancées directement via leur composant Android explicite. La liste est actualisée à chaque reprise de l'activité après installation, désinstallation ou retour des réglages.
- Les favoris restent enregistrés par nom de paquet dans le document ATLAS. TalkBack annonce les actions d'ouverture et expose une action personnalisée pour épingler ou retirer un favori.
- La pause d'urgence hors ligne reste dans le pied d'écran. Les liens permanents vers le launcher précédent, les réglages HOME et le coach restent disponibles depuis l'accueil et le tiroir.
- Le bouton Retour ferme d'abord le tiroir; lorsque le tiroir est fermé sur le HOME actif, l'événement est consommé et l'écran reste ouvert.
- Les contrôles ont une hauteur minimale de 48 dp; le contenu défile et les textes ne sont pas tronqués volontairement. Le tiroir utilise un clavier de recherche avec action IME « Search ».

## Références et limites de réemploi

L'audit Olauncher existant est conservé dans [`docs/AUDIT_CIBLE.md`](../AUDIT_CIBLE.md) et [`THIRD_PARTY_NOTICES.md`](../../THIRD_PARTY_NOTICES.md). Il trace l'adaptation historique du tri de `app/src/main/java/app/olauncher/data/AppModel.kt` et de la présentation textuelle de `app/src/main/java/app/olauncher/ui/HomeFragment.kt`, commit `f8e8d6497c457734c7a3171d36374157678e1f33`, sous GPLv3. Le lot V5 réécrit la liaison et l'écran Compose; il n'ajoute aucun fichier Olauncher ou asset tiers. L'algorithme de recherche sans accents et les actions de ce tiroir sont ATLAS.

L'audit Lawnchair Apache-2.0 n'a pas identifié de composant nécessaire pour ce HOME textuel; aucun code Lawnchair/Launcher3 n'est repris. Aucun service d'accessibilité, root, Device Owner, kiosque, permission globale de visibilité des paquets ou installation silencieuse n'est ajouté.

## Validation et travaux restants

Inventaire pendant le lot : branche `feat/atlas-v5-launcher`; JDK 21.0.12.1; Gradle wrapper 9.6.0; Android SDK indiqué par `ANDROID_HOME=$HOME/Android/Sdk`; Python 3.12.4. Aucune configuration Gradle n'a été modifiée.

Depuis `android/`, les commandes suivantes ont réussi :

| Commande | Sortie |
|---|---|
| `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` | `BUILD SUCCESSFUL`; 112 tests JVM, 0 échec / erreur / test ignoré; lint réussi avec 36 avertissements et 0 erreur; APK debug assemblée. |
| `./gradlew :app:compileDebugAndroidTestKotlin` | `BUILD SUCCESSFUL`; sources instrumentées compilées. |
| `./gradlew :app:assembleDebugAndroidTest` | `BUILD SUCCESSFUL`; APK de tests instrumentés assemblée, tests non exécutés. |

Une première exécution JVM a signalé une attente de tri incorrecte dans le nouveau test des favoris; l'attente a été alignée sur le tri localisé par `Collator`, puis l'exécution complète a réussi.

Aucun émulateur n'a été lancé et aucun appareil n'a été utilisé. Le HOME Android, le retour système, le predictive back, TalkBack et la disposition à `fontScale=2.0` restent à vérifier visuellement et sur l'AVD par le lot 3. Aucun téléphone réel ni tablette `sw >= 600dp` n'est couvert par ces résultats.
