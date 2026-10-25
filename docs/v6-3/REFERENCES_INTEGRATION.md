# V6.3 — Références d'intégration vérifiées

Document de travail. Les faits ci-dessous proviennent de sources primaires consultées
le 2026-10-26. Aucun code tiers n'est copié dans ATLAS.

## 1. Etar Calendar

| Élément | Valeur vérifiée | Source |
|---|---|---|
| Dépôt | `https://github.com/Etar-Group/Etar-Calendar` | GitHub |
| Branche par défaut | `master` | GitHub API |
| Licence | **GPL-3.0** | GitHub API |
| `applicationId` | **`ws.xsoh.etar`** | `app/build.gradle.kts` |
| Variante debug | suffixe `.debug` → `ws.xsoh.etar.debug` | `app/build.gradle.kts` |
| `namespace` | `ws.xsoh.etar` | `app/build.gradle.kts` |
| `compileSdk` / `targetSdk` / `minSdk` | 37 / 37 / 24 | `app/build.gradle.kts` |
| Permissions calendrier d'Etar | `READ_CALENDAR`, `WRITE_CALENDAR` | `AndroidManifest.xml` |
| Autres permissions notables | `GET_ACCOUNTS`, `READ_CONTACTS`, `READ_SYNC_SETTINGS`, `SCHEDULE_EXACT_ALARM` | `AndroidManifest.xml` |

Conséquences ATLAS :

- La détection d'Etar doit utiliser `ws.xsoh.etar` et `ws.xsoh.etar.debug`, pas
  `org.dmfs.calendar` (erreur corrigée avant intégration).
- ATLAS n'a pas besoin des permissions d'Etar et ne doit pas les demander par
  imitation. Etar est une **interface de consultation** du provider, pas une API.
- Aucun code Etar n'est copié : `CalendarContract` suffit pour le premier lot.

## 2. CalendarContract (Android 17 / SDK 37.2)

### Permissions

- `android.permission.READ_CALENDAR` pour lire.
- `android.permission.WRITE_CALENDAR` pour insérer, modifier ou supprimer.
- Les intents `ACTION_INSERT` / `ACTION_EDIT` / `ACTION_VIEW` ne exigent aucune
  permission mais délèguent l'édition à l'interface calendrier ; ils ne permettent
  pas à ATLAS de gérer les événements par programme. Ce n'est donc pas la voie
  retenue pour L4.
- La documentation officielle consultée **ne documente pas** le comportement
  runtime (accord/refus/révocation) ni les changements Android 14+. Ces points
  restent à qualifier sur l'appareil ; aucune affirmation n'est faite ici.

### Colonnes et tables (vérifiées dans `android.jar` 37.2)

- `CalendarContract.Calendars` hérite de `CalendarColumns` et `SyncColumns` :
  `_ID`, `CALENDAR_DISPLAY_NAME`, `ACCOUNT_NAME`, `ACCOUNT_TYPE`,
  `OWNER_ACCOUNT`, `CALENDAR_ACCESS_LEVEL`, `VISIBLE`, `SYNC_EVENTS`,
  `IS_PRIMARY`, `CALENDAR_TIME_ZONE`, `CALENDAR_COLOR`.
  Attention : `CALENDAR_ACCESS_LEVEL` est déclaré dans `CalendarColumns`, pas
  directement dans `Calendars`. Utiliser la constante de l'interface si Kotlin
  ne résout pas l'héritage statique.
- `CalendarContract.Events` hérite de `EventsColumns` et `SyncColumns` :
  `_ID`, `CALENDAR_ID`, `TITLE`, `DTSTART`, `DTEND`, `EVENT_TIMEZONE`,
  `EVENT_END_TIMEZONE`, `ALL_DAY`, `RRULE`, `RDATE`, `DURATION`, `DELETED`,
  `DIRTY`, `CUSTOM_APP_PACKAGE`, `CUSTOM_APP_URI`.
  **`CUSTOM_APP_PACKAGE` et `CUSTOM_APP_URI` existent bien dans le SDK 37.2** :
  ce sont les marqueurs d'appartenance prévus par le contrat V6.3. Leur
  acceptation à l'insertion et leur conservation par le provider restent à
  qualifier sur l'appareil.
- `CalendarContract.Instances` est en lecture seule ; une requête doit encadrer
  une plage temporelle dans l'URI. `TITLE` provient de la vue `EventsColumns`.
- Insertion d'un événement non récurrent : `CALENDAR_ID`, `DTSTART`, `DTEND`,
  `EVENT_TIMEZONE` sont requis ; `DTSTART`/`DTEND` sont des millis UTC.
- Événement récurrent : `DURATION` + `RRULE`/`RDATE` ; premier lot ATLAS :
  **lecture seule** des récurrences, écriture de non-récurrents uniquement.

### Création d'un calendrier local — risque majeur

La documentation officielle indique que la création d'un calendrier local relève
d'un **sync adapter** : insertion avec `CALLER_IS_SYNCADAPTER=true`,
`ACCOUNT_NAME`, `ACCOUNT_TYPE` et `ACCOUNT_TYPE_LOCAL`. Une application ordinaire
ne peut généralement modifier que des propriétés superficielles d'un calendrier
existant.

Conséquence pour L4 : ATLAS ne doit pas supposer qu'il peut créer son propre
calendrier local. La sonde doit déterminer, sur l'appareil réel :

1. quels calendriers sont visibles ;
2. lesquels sont techniquement modifiables par ATLAS ;
3. si un calendrier local existe déjà (créé par Etar ou l'utilisateur) ;
4. si l'insertion d'événements dans ce calendrier réussit ;
5. si Etar affiche immédiatement l'événement créé.

Si aucun calendrier modifiable n'existe et si la création n'est pas fiable, L4
est `BLOCKED_ENVIRONMENT` ; ATLAS ne recourt ni à un fork d'Etar, ni à une
manipulation de base privée, ni à un faux sync adapter non qualifié.

### Stratégie de propriété des événements

- Marqueur : `CUSTOM_APP_PACKAGE = "dev.atlas.coach"` et
  `CUSTOM_APP_URI = "atlas://plan/{plan_id}/activity/{activity_id}"`.
- Un événement sans les deux marqueurs cohérents est `FOREIGN`/`AMBIGUOUS` et
  **jamais** modifié ou supprimé.
- Avant toute création, rechercher le marqueur pour éviter un doublon après crash.
- Après écriture, relire l'événement et comparer les champs ; un résultat inconnu
  devient `UNKNOWN` puis est réconcilié, jamais rejoué aveuglément.

### Tests à exécuter sur appareil (L2/L4)

| Test | Attendu | Statut actuel |
|---|---|---|
| Provider disponible | `content://com.android.calendar` résout | `NOT_RUN` |
| `READ_CALENDAR` refusée | état `DENIED`, aucune requête, aucun crash | `NOT_RUN` |
| `READ_CALENDAR` accordée | liste des calendriers visibles | `NOT_RUN` |
| Calendrier modifiable | `CALENDAR_ACCESS_LEVEL >= 500` et insertion autorisée | `NOT_RUN` |
| Insertion ATLAS | `_ID` retourné, relecture identique | `NOT_RUN` |
| Visibilité Etar | événement visible dans Etar `ws.xsoh.etar` | `NOT_RUN` |
| Déplacement | `DTSTART/DTEND` mis à jour, relecture | `NOT_RUN` |
| Suppression | événement marqué supprimé, lien `MISSING` | `NOT_RUN` |
| Événement étranger | jamais modifié | `NOT_RUN` |
| Récurrence | lue, non écrite | `NOT_RUN` |
| DST / fuseau | heure locale correcte autour du changement | `NOT_RUN` |

Aucun de ces tests n'est un `PASS` à ce stade : aucun appareil n'est connecté.

## 3. wger et Bridge

Non qualifiés à ce stade. Aucun identifiant, aucune URL, aucun service déployé.
Les contrats V6.3 (sections 3.5, 3.6, 3.10 de `CONTRATS_V63.md`) restent des
spécifications ; les capacités correspondantes doivent rester `BLOCKED` jusqu'à
preuve réelle. Ne pas déduire la disponibilité de wger.de ou du Bridge de la
présence d'un dépôt ou d'une documentation.
