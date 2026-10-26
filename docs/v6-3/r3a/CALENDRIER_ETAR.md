# ATLAS V6.3 R3-A — Calendrier réel Etar

Statut : **qualification en cours**. Aucun `PASS` n'est déclaré sans relecture du
provider ni visibilité Etar.

## 1. Pré-vol constaté

| Élément | Valeur |
|---|---|
| Émulateur | `emulator-5554`, Android 17 / API 37, `sdk_gphone16k_x86_64` |
| Provider | `com.android.providers.calendar/.CalendarProvider2`, autorité `com.android.calendar` |
| Etar | `ws.xsoh.etar` 1.0.56 (versionCode 56, targetSdk 36) |
| Permissions Etar | `READ_CALENDAR` et `WRITE_CALENDAR` accordées à Etar |
| Calendriers avant test | **aucun** (`content query` → `No result found`) |
| Calendrier Google | compte Google non configuré ; ne jamais l'utiliser |
| Sauvegarde AVD | snapshot `atlas-r3a-pre` |
| Sauvegarde app ATLAS | `~/.local/share/atlas-v63-r3a/backups/atlas-app-data-pre-r3a.tar`, SHA-256 `41c1ad85…` |
| Consentement ATLAS | à accorder explicitement dans l'écran Diagnostic calendrier |

## 2. Compte et calendrier local ATLAS

### 2.1 Mécanisme

Un sync adapter minimal est ajouté à ATLAS :

- service `android.content.SyncAdapter` protégé par
  `android.permission.BIND_SYNC_ADAPTER` ;
- métadonnées `res/xml/atlas_calendar_sync_adapter.xml` :
  `accountType="dev.atlas.coach.calendar"`, `contentAuthority="com.android.calendar"`,
  `supportsUploading="false"`, `isAlwaysSyncable="false"` ;
- compte créé explicitement avec `AccountManager.addAccountExplicitly` ;
- calendrier créé avec l'URI `CalendarContract.Calendars.CONTENT_URI` enrichie de
  `CALLER_IS_SYNCADAPTER=true`, `ACCOUNT_NAME`, `ACCOUNT_TYPE` ;
- aucune synchronisation réseau : `onPerformSync` ne fait rien.

### 2.2 Identité synthétique

| Champ | Valeur |
|---|---|
| Account type | `dev.atlas.coach.calendar` |
| Account name | `atlas-r3a-test` |
| Display name | `ATLAS R3A TEST` |
| Owner | `atlas-r3a-test` |
| Rôle | calendrier local réservé aux tests ATLAS |

Le sélecteur du moteur accepte `LOCAL` et `dev.atlas.coach.calendar` uniquement.
Un calendrier Google, Exchange, ou tout autre type est refusé
`CALENDAR_NOT_LOCAL`.

### 2.3 Si le mécanisme échoue

Si `CalendarProvider` refuse l'insertion malgré un sync adapter enregistré, le
blocage exact (code, exception, version API) est consigné et le jalon calendrier
reste `BLOCKED_ENVIRONMENT`. Aucune insertion SQL directe, aucun `adb shell
content insert`, aucune donnée système fabriquée ne sert de preuve.

## 3. Corrections attendues du moteur R2

| # | Limite R2 | Correction R3 |
|---|---|---|
| 1 | Journal mémoire seulement | journal fichier atomique durable |
| 2 | Liens d'événements non persistés | `CalendarWriteLinkStore` durable |
| 3 | `WRITE_CALENDAR` absent du manifeste | permission déclarée + demande explicite |
| 4 | Rejeu `APPLIED` insuffisamment revérifié | réconciliation obligatoire avant réponse |
| 5 | `DayPlan` non raccordé | ticket `R3-CALENDAR-ETAR-01` |
| 6 | Sélection/consentement non utilisables au quotidien | écran de sélection + confirmation groupée |
| 7 | `UNKNOWN` non repris au redémarrage | `recoverPending()` au démarrage |
| 8 | Modification externe non visible | état `DIVERGED` + écran de divergence |
| 9 | Suppression sans contrôle du nombre de lignes | relecture obligatoire et `MISSING` |
| 10 | Aucune révocation immédiate | contrôle de permission avant chaque effet |

## 4. Scénarios obligatoires

| # | Scénario | Preuve attendue | Statut |
|---|---|---|---|
| C1 | Calendrier local sélectionné | catalogue + sélection explicite | NOT_RUN |
| C2 | Création d'activité | `DayPlan` versionné | NOT_RUN |
| C3 | Validation de la révision | confirmation groupée | NOT_RUN |
| C4 | Création événement CalendarContract | `event_id` relu | NOT_RUN |
| C5 | Relecture provider | hash et marqueurs conformes | NOT_RUN |
| C6 | Affichage réel dans Etar | capture + requête provider | NOT_RUN |
| C7 | Déplacement | `dtstart`/`dtend` relus | NOT_RUN |
| C8 | Modification titre/durée | relecture | NOT_RUN |
| C9 | Suppression autorisée | événement absent | NOT_RUN |
| C10 | Disparition vérifiée | provider + Etar | NOT_RUN |
| C11 | Répétition sans doublon | une seule ligne | NOT_RUN |
| C12 | Arrêt du processus pendant l'opération | état `UNKNOWN` durable | NOT_RUN |
| C13 | Relance et réconciliation | `RECONCILED` ou `MISSING` | NOT_RUN |
| C14 | Modification manuelle dans Etar | état `DIVERGED` | NOT_RUN |
| C15 | Détection de divergence | UI/état | NOT_RUN |
| C16 | Révocation `WRITE_CALENDAR` | refus fail-closed | NOT_RUN |
| C17 | Refus d'effet après révocation | aucun nouvel événement | NOT_RUN |
| C18 | Événement étranger non modifié | `FOREIGN_EVENT` | NOT_RUN |
| C19 | Calendrier supprimé/indisponible | `PROVIDER_UNAVAILABLE`/`CALENDAR_NOT_LOCAL` | NOT_RUN |
| C20 | READ_CALENDAR refusé | aucune lecture | NOT_RUN |
| C21 | WRITE_CALENDAR refusé | aucune écriture | NOT_RUN |
| C22 | Restauration des fixtures | état initial restauré | NOT_RUN |

## 5. Règles de sûreté

- Événements strictement synthétiques, titres préfixés `ATLAS TEST`.
- Ne jamais modifier un événement sans `CUSTOM_APP_PACKAGE=dev.atlas.coach` et
  `CUSTOM_APP_URI=atlas://plan/<plan>/activity/<activity>`.
- Ne jamais supprimer un calendrier ni un événement non créé par ATLAS.
- Sauvegarder le calendrier local avant les essais et supprimer uniquement les
  événements ATLAS après recette.
- Une révocation de permission est testée avant et après un effet.
- Aucune donnée de compte personnelle dans les preuves.
