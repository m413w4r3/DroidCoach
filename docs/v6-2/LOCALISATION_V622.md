# ATLAS V6.2.2 — localisation A1, rapport initial

**Date :** 20 octobre 2026
**Branche / HEAD :** `feat/atlas-v6-2-2` / `bf647cc18a142dd0f5cd5e68b21c36d511014388`
**État :** diagnostic initial complété par une correction des tests et du harnais; aucune correction de production n'est appliquée. Résultats finaux et limites ci-dessous.

## Résultat initial

La preuve de baseline V6.2.1 confirme l'échec historique de mobilité :
`distance_m=8925794.0` dans
`V62MobilityServiceInstrumentedTest.visibleStartShowsStopNotificationAndServiceStopsWithoutRawTrail`.
Cette distance dépasse 8 900 km et ne peut pas être expliquée par l'arrondi
protecteur ATLAS de 2 km.

Je n'ai pas reproduit cette distance sur l'AVD neuf de cette session. Trois
exécutions ciblées ont donné un résultat intermittent : une mesure récente et
cohérente, puis deux démarrages sans mesure en 15 s. Dans ces deux derniers
cas, AndroidTest a quand même renvoyé `OK (1 test)` parce que le test transforme
l'absence de mesure en avertissement et poursuit les assertions de notification,
d'arrêt et de révocation. Ces deux `OK` ne prouvent donc pas l'acquisition d'une
position.

Le classement initial est **injection/fournisseur AVD non déterministe, avec
course de synchronisation et de cache non exclue**. La trace historique ne
contient ni le fournisseur, ni le fix Android brut, ni l'horodatage d'injection
comparé au fix reçu; elle ne permet pas d'attribuer le point éloigné à `network`,
`gps`, au cache ou à une autre étape. L'arrondi ATLAS n'explique pas l'écart.

## Environnement et précautions

- AVD jetable neuf `atlas_v622_s1_api34`, isolé sous
  `/tmp/atlas-v622-s1/avd-home/atlas_v622_s1_api34.avd`.
- API 34, `sdk_gphone64_x86_64`, Google APIs x86_64 image, révision 14,
  build `UE1A.230829.050`; émulateur `37.2.12.0`.
- Série `emulator-5580`; ports console/ADB réservés 5580/5581. L'AVD a été
  arrêté après les essais, sans effacer ses données.
- Localisation activée. Avant capture, aucun fix `last location` n'était
  présent; GPS et réseau étaient activés.
- `ACCESS_COARSE_LOCATION` accordée, `ACCESS_FINE_LOCATION` refusée,
  `POST_NOTIFICATIONS` accordée. L'APK ne déclare pas
  `ACCESS_BACKGROUND_LOCATION`.
- Aucune connexion ADB physique. Aucun AVD personnel n'a été lancé, modifié ou
  effacé.
- APK debug et AndroidTest produits par le build de baseline exécuté par S0;
  S1 n'a lancé aucune commande Gradle/build. SHA-256 : app
  `2f92aebc957d6bb8e391beabd7ac006a5c0cf9e395376740a0125c65039217db`, test
  `0b89f5f75b11e782d717bf2530cdcc0559312351a793e5bc91872004f5d7429a`.
- Les journaux complets restent privés sous `/tmp/atlas-v622-s1/logs/`.

## Essais instrumentés

La commande d'injection utilisée était `adb -s "$SERIAL" emu geo fix
2.123456 48.123456` (longitude, latitude; point de test synthétique). Les temps
hôte UTC et appareil UTC/uptime sont consignés dans les journaux privés.

1. **Mesure reçue — `OK (1 test)`.** L'injection a été lancée alors qu'une
   requête de localisation de l'application était visible dans `dumpsys
   location`. L'AVD a ensuite enregistré un fix GPS; le service a rapporté une
   mesure de 14 ms d'âge et un résumé d'incertitude 2 000 m. Le démarrage depuis
   Activity visible, la notification persistante, l'action Arrêter, l'arrêt à
   révocation et l'absence de coordonnées brutes dans les résumés ont passé.
   C'est une exécution réussie, pas une preuve de répétabilité.
2. **Pas de fix — `OK (1 test)`, non qualifiable PASS système.** Une seconde
   injection pendant une requête du fournisseur réseau n'a produit aucune
   mesure avant la limite de 15 s. Le journal contient
   `MOBILITY_MEASUREMENT_UNAVAILABLE`.
3. **Pas de fix — `OK (1 test)`, non qualifiable PASS système.** Une troisième
   tentative a injecté après l'apparition d'une requête de l'application dans
   l'état courant du fournisseur GPS. Aucun callback GPS n'a suivi l'injection
   dans la fenêtre de mesure; le journal contient encore
   `MOBILITY_MEASUREMENT_UNAVAILABLE`.

Une injection ADB effectuée lorsque GPS n'avait aucune requête active a renvoyé
`OK`, mais n'a pas changé le dernier fix connu. Le code de retour de la console
émulateur ne suffit donc pas à prouver que la position a été livrée à la
requête ATLAS.

## Classification par étape

- **Injection :** `emu geo fix` renvoie `OK`; une des deux injections faites
  pendant une requête a été suivie d'un fix, les deux autres essais instrumentés
  n'ont pas fourni de mesure. L'injection ne peut pas être considérée comme
  acquise sans callback Android et horodatage.
- **Fournisseur :** `LocationCaptureManager` choisit `network` puis `gps` en
  coarse-only. La trace de l'échec historique n'identifie pas lequel a fourni
  les 8 925 794 m.
- **Cache/fraîcheur :** le gestionnaire accepte le dernier fix de tout
  fournisseur activé si son âge est inférieur ou égal à 15 s. La fraîcheur est
  calculée par rapport à l'heure courante; elle n'est pas comparée à l'heure de
  l'injection hôte. Un fix récent mais antérieur à l'injection pourrait donc
  satisfaire la garde actuelle.
- **Arrondi :** `LocationDisclosure` passe les captures coarse dans le
  coarsener ATLAS de 2 km. Le test mobilité compare la position injectée au
  résumé déjà arrondi; ce résumé ne permet pas d'identifier seul le fix brut.
  L'échec historique de plus de 8 900 km n'est pas imputable à cet arrondi.
- **Synchronisation du harnais :** le script attend
  `COARSE_LOCATION_LISTENER_ACTIVE` et `MOBILITY_LOCATION_LISTENER_ACTIVE`,
  chaînes absentes des sources actuelles. Les tests présents émettent
  `COARSE_CAPTURE_MANAGER_CALL` et `MOBILITY_SERVICE_START_REQUEST`;
  aucun marqueur actuel n'atteste le début exact de la requête système.
- **Résultat manquant :** quand la mesure est absente, le test ne fait pas
  échouer le cas. Cette sortie doit être classée bloquée/non exécutée côté
  fournisseur, même si les assertions de service passent.

## Correction proposée, à prouver avant clôture

Priorité au test et au harnais, sans remplacer `LocationManager` et sans
modifier `LocationCaptureManager` à ce stade :

1. Faire coïncider les marqueurs avec les sources actuelles et synchroniser
   l'injection avec une requête active du fournisseur courant, en conservant
   les temps hôte et appareil.
2. Transmettre l'heure d'injection au test et exiger
   `capturedAtUtcMillis >= injectionStartedUtcMillis`, en plus de la fraîcheur
   de production. Une injection acceptée par le protocole sans callback frais
   doit donner `TEST_SYSTEM_BLOCKED`, pas un `PASS`.
3. Contrôler séparément le fix Android coarse reçu et le résumé ATLAS : comparer
   le fix à la fixture synthétique selon sa précision coarse déclarée; vérifier
   pour le résumé l'incertitude minimale, la minimisation, la conservation de
   l'horodatage et l'absence de trajectoire brute. Ne pas relâcher la tolérance
   du résumé à plusieurs milliers de kilomètres.
4. Séparer les assertions du cycle de service des assertions d'acquisition;
   l'absence de fix ne doit plus rendre vert le scénario système.
5. Si le fournisseur reste intermittent, ajouter une preuve logique séparée
   avec fournisseur contrôlé, et conserver `TEST_SYSTEM_BLOCKED` pour l'AVD.

Cette proposition n'a pas encore été codée ni éprouvée après correction. Aucun
changement de production, de test ou de script n'a été fait dans cette étape;
aucune suite complète ni le script `v62-complete-instrumentation.sh` n'a été
lancé. L'issue #1 demeure ouverte et aucune action GitHub n'a été effectuée.

## Traces

Le résumé expurgé suivi dans Git est
[localisation-diagnostic-initial.txt](preuves-v622/localisation-diagnostic-initial.txt).
Les journaux détaillés, horodatages et états `dumpsys` restent sous
`/tmp/atlas-v622-s1/logs/`.

## Mise à jour S1 — correction et validation

La correction est limitée aux deux tests Android de localisation et à la
recette visible. Aucun fichier de production n'a été modifié. La preuve
détaillée et les résultats sont consignés dans
[localisation-validation-s1.txt](preuves-v622/localisation-validation-s1.txt).

- Les tests capture et mobilité reçoivent maintenant l'horodatage UTC de
  l'injection par diffusion explicite et refusent un fix antérieur à cette
  injection. Dans le test de capture ponctuelle, le fix Android brut est
  comparé à la précision coarse que l'OS lui déclare; ce contrôle ne vérifie
  pas le fix brut du service mobilité. Le test mobilité contrôle le résumé
  coarse persistant, notamment sa cohérence spatiale avec le point injecté à
  l'intérieur de la borne historique de 25 km, son horodatage et sa
  minimisation. Aucun test instrumenté ne revendique la vérification du fix
  brut reçu par le service mobilité.
- Le test mobilité termine d'abord ses contrôles de notification, d'action
  Arrêter et de révocation; un échec de ces assertions reste un échec ordinaire.
  `TEST_SYSTEM_BLOCKED` n'est reconnu que si le journal brut de l'instrumentation
  contient exactement l'échec JUnit unique attendu, porté par l'assertion
  explicite d'absence de fix coarse frais. Une absence du marqueur de
  synchronisation ou de requête GPS active est classée `FAIL`. L'attente de
  chaque instrumentation est bornée à 90 s; un dépassement est `FAIL`.
- La recette exige le serial `emulator-5580`, l'AVD
  `atlas_v622_s1_api34` et l'API 34. Elle attend une requête GPS coarse
  courante de `dev.atlas.coach` dans la section des fournisseurs actifs de
  `dumpsys location` avant d'injecter. Les événements historiques ne satisfont
  pas cette garde. Les journaux sont conservés sous
  `/tmp/atlas-v622-s1/logs/`.
- Le build ciblé a réussi; les tests contrôlés d'acquisition, de divulgation,
  de TTL et de cycle mobilité comptent 32 réussites, 0 échec et 0 ignoré.
  Gate2 baseline compte aussi 6/6 tests Agent et 2/2 tests Worker.
- Lors de la recette stricte finale, les scénarios ponctuel et mobilité ont
  tous deux renvoyé `TEST_SYSTEM_BLOCKED`. Pour la mobilité, l'AVD a enregistré
  la requête GPS coarse à 21:35:11.230 (heure locale), puis l'injection à
  21:35:11.600; aucune réception/livraison GPS n'a suivi, et le compteur
  d'emplacements pour la requête est resté à zéro. Le cycle visible de service,
  Arrêter et révocation a passé avant l'échec de classification explicite.

L'écart historique de 8 925 794 m n'a toujours pas été reproduit. La preuve
historique ne contient pas le fix brut, son fournisseur ni son horodatage;
l'origine exacte reste donc indéterminée. Les résultats stricts présents
démontrent un défaut intermittent de livraison du fournisseur AVD et un faux
succès de l'ancien chemin sans mesure; ils ne démontrent pas de défaut de
production. Aucun changement à `LocationCaptureManager` n'est justifié par ces
traces.

La revue S1 a rétabli explicitement la borne historique de 25 km sur le résumé
coarse mobilité. Elle a aussi durci la classification pour exiger l'unique
échec JUnit attendu dans le journal brut et ajouté une borne de 90 s à
l'attente instrumentation. Le rejeu instrumenté et le build de cette révision
corrigée sont différés à S5/S6; les APK et résultats instrumentés ci-dessus
proviennent de la révision antérieure à cette dernière correction. Les résultats
acceptés 32 JVM et Gate2 6+2 restent des faits des exécutions précédentes, pas
une validation instrumentée de cette correction finale.

Le service instancie lui-même `LocationCaptureManager`; le test observe son
état de session et les résumés coarse persistés. Aucun seam actuel ne donne au
test le fix brut du service, donc la précision raw de mobilité reste non
vérifiée. Injecter un fournisseur au service demanderait un nouveau seam de
production que les traces actuelles ne justifient pas.
