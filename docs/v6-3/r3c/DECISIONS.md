# ATLAS V6.3 — R3-C — Décisions automatiques

## D-R3C-01 — Pixel utilisateur 0 sans données préexistantes

**Constat.** Au pré-vol, `dev.atlas.coach` n'était pas installé pour
l'utilisateur 0 (`installed=false`, `ceDataInode=-1`) alors qu'il restait
installé pour les utilisateurs 12 et 13. Aucune sauvegarde app-only de
l'utilisateur 0 n'était donc possible.

**Décision.** Installer le couple R3-C pour l'utilisateur 0 uniquement, sans
toucher aux profils 12/13, et qualifier la préservation des données sur le
périmètre R3-C (données synthétiques créées par les tests). Ne pas restaurer
la sauvegarde R3-B sans autorisation ciblée et analyse d'impact.

**Réversibilité.** Mise à jour APK réversible ; les profils 12/13 et la
sauvegarde R3-B sont intacts.

## D-R3C-02 — A4-4 : 1012 constaté, aucune modification du relais

**Constat.** Trois probes (Funnel public, proxy local, relais direct `4402`)
observent `DEVICE_REVOKED` puis une fermeture `1012 "Server disconnected"`,
jamais `4001`. Le connecteur émet pourtant `4001`.

**Décision.** Ne pas modifier le relais épinglé ni falsifier le code observé.
Déclarer **A4-4 FAIL**, conserver A4-5..A4-10 PASS, et soumettre la décision
à l'utilisateur : corriger la propagation amont ou accepter explicitement
1012 comme fermeture de transport.

## D-R3C-03 — Chat OpenClaw réel avec message synthétique

**Décision.** Utiliser l'adaptateur OpenClaw existant du PC avec un message
strictement synthétique, sans modifier la configuration personnelle ni le
Bridge. Une conversation synthétique est créée côté gateway ; elle ne contient
aucune donnée personnelle. Le test Android et le test public ont utilisé ce
chemin.

## D-R3C-04 — Effacement A4 validé par le chemin de production

**Constat.** L'échec A4-4 interrompt le test UI avant le clic « Supprimer les
identifiants ».

**Décision.** Ajouter un test androidTest R3-C dédié qui appelle le même
chemin de production `clearCredentials()` et vérifie credential, Keystore,
alias et `PAIRING_REQUIRED`. Le clic UI et la confirmation restent
`PARTIAL` et doivent être rejoués après la décision A4-4.

## D-R3C-05 — État opérationnel laissé en fin de campagne

**Décision.** Laisser les services de test isolés actifs (relais 4402, proxy
4400, FastAPI 8100, connecteur 4401) et l'adaptateur FastAPI en `openclaw`
pour que le Pixel reste utilisable. Le Pixel utilisateur 0 reste appairé avec
l'appareil synthétique `r3c-pixel-chat`. Aucun service persistant systemd
n'est installé ; `linger` n'a pas été modifié.
